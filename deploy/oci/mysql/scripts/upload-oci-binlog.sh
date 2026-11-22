#!/bin/bash
# OCI host의 mysql 컨테이너 binlog를 R2로 반출하고, 마지막 성공 시각을 textfile 지표로 남긴다.
# datadir은 bind mount라 host에서 파일을 직접 읽는다(DB 연결이 필요 없다 — mysqld가 죽어도 이미 쓴 것은 올린다).
#
# 단일 host라 R2가 유일한 오프호스트 사본이다. host 전손 때 손실 창은 이 timer 주기다.
# 완결 파일은 정규 key로 한 번, 지금 쓰는 활성 파일은 매 실행 tail/ key로 덮어쓴다 —
# 활성 파일은 회전(매일 덤프의 --flush-logs) 전까지 닫히지 않으므로 tail이 없으면 창이 하루가 된다.
set -euo pipefail

readonly CONFIG_FILE=${LAIMORY_OCI_MYSQL_ENV:-/etc/laimory/oci-mysql.env}
readonly STATE_DIR=/var/lib/laimory/oci-mysql
readonly UPLOADED_DIR="$STATE_DIR/binlog-uploaded"
readonly SUCCESS_FILE="$STATE_DIR/binlog-upload-last-success"
readonly OUTPUT_DIR=/var/lib/laimory/textfile
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_oci_binlog_upload.prom"

install -d -m 0755 -o root -g root "$OUTPUT_DIR"
install -d -m 0700 -o root -g root "$STATE_DIR" "$UPLOADED_DIR"

# 실패해도 마지막 성공 시각은 보존해서 기록한다 — 반출 정지 경보가 이 값의 나이로 발화한다.
write_metrics() {
  local tmp_file
  [[ -f $SUCCESS_FILE ]] || return 0
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_oci_binlog_upload.XXXXXX")
  {
    echo "# HELP laimory_oci_binlog_upload_last_success_unixtime_seconds Unix time of the latest successful OCI MySQL binlog tail upload."
    echo "# TYPE laimory_oci_binlog_upload_last_success_unixtime_seconds gauge"
    echo "laimory_oci_binlog_upload_last_success_unixtime_seconds $(cat "$SUCCESS_FILE")"
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_metrics
  echo "oci binlog upload failed: $*" >&2
  exit 1
}

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_DATADIR:?}" "${RCLONE_CONFIG:?}" "${R2_REMOTE:?}"
[[ $R2_REMOTE =~ ^[A-Za-z0-9_-]+:[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]] || fail "invalid R2_REMOTE (remote:bucket)"
R2_PREFIX=${R2_PREFIX:-}
[[ $R2_PREFIX =~ ^([A-Za-z0-9][A-Za-z0-9._-]*/)*$ ]] || fail "invalid R2_PREFIX (empty or 'segment/')"
[[ -d $MYSQL_DATADIR ]] || fail "missing datadir: $MYSQL_DATADIR"

# key는 서버 세대(server_uuid)로 나눈다. 파일명은 서버가 재생성되면 1번부터 재사용되므로
# uuid가 없으면 새 세대의 binlog.000001이 옛 세대의 같은 이름을 덮어쓴다.
source_uuid=$(sed -n 's/^server-uuid=//p' "$MYSQL_DATADIR/auto.cnf" 2>/dev/null) || true
[[ $source_uuid =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] || fail "cannot read server-uuid from auto.cnf"
marker_dir="$UPLOADED_DIR/$source_uuid"
install -d -m 0700 -o root -g root "$marker_dir"
key_base="${R2_REMOTE}/${R2_PREFIX}binlog/${source_uuid}"

# binlog.index의 마지막 줄이 지금 쓰는 활성 파일이다. 나머지는 회전으로 닫힌 완결본이다.
# 로컬 expire가 지운 파일은 index에서도 빠진다.
index_file="$MYSQL_DATADIR/binlog.index"
[[ -f $index_file ]] || fail "missing $index_file"
names=()
while IFS= read -r line; do
  name=${line##*/}
  [[ $name =~ ^binlog\.[0-9]{6}$ ]] || fail "unexpected binlog.index entry"
  names+=("$name")
done < "$index_file"
(( ${#names[@]} > 0 )) || fail "binlog.index is empty"
active=${names[-1]}

rclone_put() {
  # --s3-no-head: rclone 1.60은 업로드 뒤 versionId로 HEAD를 보내고 R2가 501로 거절한다.
  rclone --config "$RCLONE_CONFIG" copyto "$1" "$2" --s3-no-head -q
}

# 완결본: 정규 key로 한 번만 올린다. 업로드 표식이 있으면 건너뛴다.
for name in "${names[@]}"; do
  [[ $name != "$active" ]] || continue
  [[ ! -f "$marker_dir/$name" ]] || continue
  rclone_put "$MYSQL_DATADIR/$name" "$key_base/$name" || fail "r2 upload failed for $name"
  : > "$marker_dir/$name"
  echo "uploaded completed binlog: $name"
done

# 활성 tail: 쓰이는 중인 파일을 그대로 올리면 rclone이 크기 변화를 감지해 실패하므로 사본을 올린다.
# 완결본 key 공간과 분리해 미완결 데이터가 완결본인 척하지 않게 한다.
snapshot=$(mktemp -t "laimory-oci-binlog-tail-XXXXXX")
trap 'rm -f "$snapshot"' EXIT
cp "$MYSQL_DATADIR/$active" "$snapshot" || fail "failed to snapshot $active"
rclone_put "$snapshot" "$key_base/tail/$active" || fail "r2 tail upload failed for $active"
date +%s > "$SUCCESS_FILE"

# 로컬 expire로 사라진 파일의 표식은 정리한다(파일은 MySQL이 지운다 — 이 스크립트는 binlog를 지우지 않는다).
for marker in "$marker_dir"/binlog.*; do
  [[ -f $marker ]] || continue
  [[ -f "$MYSQL_DATADIR/${marker##*/}" ]] || rm -f "$marker"
done

write_metrics
echo "oci binlog upload complete (active=$active)"
