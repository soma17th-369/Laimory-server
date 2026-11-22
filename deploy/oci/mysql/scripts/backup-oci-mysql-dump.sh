#!/bin/bash
# OCI host의 mysql 컨테이너를 논리 덤프해 R2에 올리고, 마지막 성공 시각을 textfile 지표로 남긴다.
# 설정(컨테이너·계정·R2 위치)은 host의 /etc/laimory/oci-mysql.env가 소유한다 — 저장소에 두지 않는다.
#
# 복제 기간에는 replica에서 뜬다. --source-data는 이 서버 자신의 binlog 좌표를 기록하고,
# log_replica_updates라 복제분도 이 서버 binlog에 있으므로 승격 전후가 같은 PITR 사슬이 된다.
set -euo pipefail

readonly CONFIG_FILE=${LAIMORY_OCI_MYSQL_ENV:-/etc/laimory/oci-mysql.env}
readonly STATE_DIR=/var/lib/laimory/oci-mysql
readonly STATE_FILE="$STATE_DIR/mysqldump-last-success"
readonly OUTPUT_DIR=/var/lib/laimory/textfile
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_oci_mysqldump.prom"

install -d -m 0755 -o root -g root "$OUTPUT_DIR"
install -d -m 0700 -o root -g root "$STATE_DIR"

# 실패해도 마지막 성공 시각은 보존해서 기록한다 — 신선도 경보가 이 값의 나이로 발화한다.
write_metrics() {
  local tmp_file
  [[ -f $STATE_FILE ]] || return 0
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_oci_mysqldump.XXXXXX")
  {
    echo "# HELP laimory_oci_mysqldump_last_success_unixtime_seconds Unix time of the latest successful OCI MySQL dump upload."
    echo "# TYPE laimory_oci_mysqldump_last_success_unixtime_seconds gauge"
    echo "laimory_oci_mysqldump_last_success_unixtime_seconds $(cat "$STATE_FILE")"
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_metrics
  echo "oci mysqldump backup failed: $*" >&2
  exit 1
}

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_CONTAINER:?}" "${MYSQL_BACKUP_USER:?}" "${MYSQL_BACKUP_PASSWORD:?}" "${RCLONE_CONFIG:?}" "${R2_REMOTE:?}"
[[ $MYSQL_CONTAINER =~ ^[A-Za-z0-9_.-]+$ ]] || fail "invalid MYSQL_CONTAINER"
[[ $R2_REMOTE =~ ^[A-Za-z0-9_-]+:[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]] || fail "invalid R2_REMOTE (remote:bucket)"
R2_PREFIX=${R2_PREFIX:-}
[[ $R2_PREFIX =~ ^([A-Za-z0-9][A-Za-z0-9._-]*/)*$ ]] || fail "invalid R2_PREFIX (empty or 'segment/')"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump_file=$(mktemp -t "laimory-oci-mysqldump-XXXXXX.sql.gz")
trap 'rm -f "$dump_file"' EXIT

# 비밀번호는 env로만 넘긴다(docker exec -e NAME은 호출자 env의 값을 전달하고 argv에 값을 싣지 않는다).
export MYSQL_PWD="$MYSQL_BACKUP_PASSWORD"
in_mysql() { docker exec -i -e MYSQL_PWD "$MYSQL_CONTAINER" "$@"; }

# PITR은 "이 덤프가 어느 서버 세대의 어느 좌표인가"를 둘 다 알아야 성립한다.
# binlog 파일명은 서버가 재생성되면 1번부터 재사용되므로 좌표만으로는 세대를 특정할 수 없다.
source_uuid=$(in_mysql mysql --user="$MYSQL_BACKUP_USER" --batch --skip-column-names \
  -e "SELECT @@server_uuid") || fail "failed to read server_uuid"
[[ $source_uuid =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] || fail "unexpected server_uuid format"

# --flush-logs: 덤프 좌표를 잡는 잠금 안에서 binlog를 새 파일로 넘긴다. 이 서버의 binlog는 하루
# 1MB 안팎이라 크기 기준 회전이 사실상 없다 — 매일 여기서 넘겨야 활성 파일이 닫혀 tail 업로드가
# 작게 유지되고 로컬 expire가 동작하며, 덤프 좌표가 새 파일의 시작이 되어 복구가 단순해진다.
{
  printf -- '-- laimory_source_uuid=%s\n' "$source_uuid"
  in_mysql mysqldump --user="$MYSQL_BACKUP_USER" --single-transaction --source-data=2 --flush-logs \
    --no-tablespaces --databases laimory
} | gzip -9 > "$dump_file" || fail "mysqldump or gzip failed"
unset MYSQL_PWD

gzip -t "$dump_file" || fail "gzip integrity check failed"
# mysqldump는 정상 종료 시 마지막 줄에 완료 마커를 남긴다 — 잘린 덤프를 업로드 전에 걸러낸다.
zcat "$dump_file" | tail -1 | grep -q "Dump completed" || fail "dump completion marker missing"
# 좌표와 uuid가 없으면 PITR 시작점을 특정할 수 없다. 8.0.46 출력은 구문법(CHANGE MASTER TO)이다.
# head/grep -q로 앞부분만 읽으면 zcat이 SIGPIPE로 끝나 pipefail에 걸린다 — awk는 입력을 끝까지 읽는다.
zcat "$dump_file" | awk '
  NR <= 50 && /^-- CHANGE MASTER TO /    { coord = 1 }
  NR <= 50 && /^-- laimory_source_uuid=/ { uuid = 1 }
  END { exit !(coord && uuid) }
' || fail "binlog coordinate or source uuid comment missing"

object="${R2_REMOTE}/${R2_PREFIX}mysqldump/${stamp:0:4}/${stamp:4:2}/laimory-${stamp}.sql.gz"
# --s3-no-head: rclone 1.60은 업로드 뒤 R2가 준 versionId로 HEAD를 보내고 R2가 501로 거절한다.
# 무결성은 rclone이 단일 part PUT에 싣는 Content-MD5를 R2가 검증하는 것으로 지킨다.
rclone --config "$RCLONE_CONFIG" copyto "$dump_file" "$object" --s3-no-head -q || fail "r2 upload failed"

date +%s > "$STATE_FILE"
write_metrics
echo "oci mysqldump uploaded: ${object#*:} ($(stat -c%s "$dump_file") bytes)"
