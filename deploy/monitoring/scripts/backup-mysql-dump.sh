#!/bin/bash
# prod MySQL(호스트 네이티브 설치) 논리 덤프를 S3에 올리고 결과를 node_exporter textfile로 기록한다.
# 설정(계정·버킷 경로)은 배포 호스트의 /etc/laimory/mysqldump-backup.env가 소유한다 —
# 버킷 이름에 계정 ID가 들어가므로 저장소에 두지 않는다.
set -euo pipefail

readonly CONFIG_FILE=/etc/laimory/mysqldump-backup.env
readonly STATE_FILE=/var/lib/laimory/mysqldump-last-success
readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_mysqldump.prom"

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"
install -d -m 0700 -o root -g root "$(dirname "$STATE_FILE")"

# 실패해도 마지막 성공 시각은 보존해서 기록한다 — staleness alert가 이 값의 나이로 발화한다.
write_metrics() {
  local up=$1
  local tmp_file
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_mysqldump.XXXXXX")
  {
    echo "# HELP laimory_mysqldump_up Whether the latest mysqldump backup run completed."
    echo "# TYPE laimory_mysqldump_up gauge"
    echo "laimory_mysqldump_up $up"
    echo "# HELP laimory_mysqldump_last_attempt_unixtime_seconds Unix time of the latest backup attempt."
    echo "# TYPE laimory_mysqldump_last_attempt_unixtime_seconds gauge"
    echo "laimory_mysqldump_last_attempt_unixtime_seconds $(date +%s)"
    if [[ -f $STATE_FILE ]]; then
      local success_epoch success_bytes
      read -r success_epoch success_bytes < "$STATE_FILE"
      echo "# HELP laimory_mysqldump_last_success_unixtime_seconds Unix time of the latest successful backup upload."
      echo "# TYPE laimory_mysqldump_last_success_unixtime_seconds gauge"
      echo "laimory_mysqldump_last_success_unixtime_seconds $success_epoch"
      echo "# HELP laimory_mysqldump_last_success_size_bytes Compressed size of the latest successful dump."
      echo "# TYPE laimory_mysqldump_last_success_size_bytes gauge"
      echo "laimory_mysqldump_last_success_size_bytes $success_bytes"
    fi
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_metrics 0
  echo "mysqldump backup failed: $*" >&2
  exit 1
}

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}" "${S3_PREFIX:?}"
[[ $S3_PREFIX =~ ^s3://[a-z0-9.-]+(/[A-Za-z0-9._/-]+)?$ ]] || fail "invalid S3_PREFIX"
S3_PREFIX=${S3_PREFIX%/}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dump_file=$(mktemp -t "laimory-mysqldump-XXXXXX.sql.gz")
trap 'rm -f "$dump_file"' EXIT

# MYSQL_PWD는 env로만 전달되어 argv에 노출되지 않는다.
export MYSQL_PWD="$MYSQL_PASSWORD"

# PITR은 "이 덤프가 어느 source의 어느 좌표인가"를 둘 다 알아야 성립한다.
# 좌표는 --source-data=2가 주석으로 남기고, source 식별자는 여기서 직접 남긴다 —
# binlog 파일명은 source가 재생성되면 1번부터 재사용되므로 좌표만으로는 세대를 특정할 수 없다.
source_uuid=$(mysql --user="$MYSQL_USER" --batch --skip-column-names \
  -e "SELECT @@server_uuid") || fail "failed to read server_uuid"
[[ $source_uuid =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] ||
  fail "unexpected server_uuid format"

{
  printf -- '-- laimory_source_uuid=%s\n' "$source_uuid"
  mysqldump --user="$MYSQL_USER" --single-transaction --source-data=2 --databases laimory
} | gzip -9 > "$dump_file" || fail "mysqldump or gzip failed"
unset MYSQL_PWD

gzip -t "$dump_file" || fail "gzip integrity check failed"
# mysqldump는 정상 종료 시 마지막 줄에 완료 마커를 남긴다 — 잘린 덤프를 업로드 전에 걸러낸다.
zcat "$dump_file" | tail -1 | grep -q "Dump completed" || fail "dump completion marker missing"
# 좌표 주석이 없으면 binlog를 아무리 반출해도 재생 시작점이 없다 — 업로드 전에 막는다.
# MySQL 8.0.46 실측 출력은 여전히 구문법(CHANGE MASTER TO ... MASTER_LOG_FILE/POS)이다.
zcat "$dump_file" | head -50 | grep -q "^-- CHANGE MASTER TO " ||
  fail "binlog coordinate comment missing (is --source-data supported by the account?)"

object_key="${S3_PREFIX}/${stamp:0:4}/${stamp:4:2}/laimory-${stamp}.sql.gz"
aws s3 cp "$dump_file" "$object_key" --only-show-errors || fail "s3 upload failed"

size_bytes=$(stat -c%s "$dump_file")
printf '%s %s\n' "$(date +%s)" "$size_bytes" > "$STATE_FILE"
write_metrics 1
echo "mysqldump backup uploaded: $object_key ($size_bytes bytes)"
