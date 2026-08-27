#!/bin/bash
# monitoring host 스풀의 binlog를 S3로 올리고, 스트림·업로드 상태를 node_exporter textfile로 기록한다.
# stream-binlog.sh와 같은 /etc/laimory/binlog-stream.env를 읽는다(내부 주소·버킷은 저장소에 두지 않는다).
#
# 이 스크립트가 stream_up의 writer인 것은 의도적이다. node_exporter textfile collector는 .prom 파일이
# 다시 쓰일 때까지 마지막 내용을 계속 노출하므로, 스트림이 자기 생존 지표를 자기가 쓰면 죽는 순간
# 1이 그대로 굳어 아무도 죽음을 모른다. 독립적으로 매분 도는 이 timer가 대신 기록한다.
set -euo pipefail

readonly CONFIG_FILE=/etc/laimory/binlog-stream.env
readonly SPOOL_DIR=/var/lib/laimory/binlog
readonly STATE_FILE=/var/lib/laimory/binlog-stream-position
readonly UPLOADED_DIR=/var/lib/laimory/binlog-uploaded
readonly SUCCESS_FILE=/var/lib/laimory/binlog-upload-last-success
readonly STREAM_UNIT=laimory-binlog-stream.service
readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_binlog_upload.prom"

install -d -m 0700 -o root -g root "$SPOOL_DIR" "$UPLOADED_DIR"
install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"

stream_up=0
lag_bytes=-1

# 실패해도 마지막 성공 시각은 보존해서 기록한다 — staleness alert가 이 값의 나이로 발화한다.
write_metrics() {
  local up=$1 tmp_file
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_binlog_upload.XXXXXX")
  {
    echo "# HELP laimory_binlog_stream_up Whether the binlog stream unit is active."
    echo "# TYPE laimory_binlog_stream_up gauge"
    echo "laimory_binlog_stream_up $stream_up"
    echo "# HELP laimory_binlog_upload_up Whether the latest binlog upload run completed."
    echo "# TYPE laimory_binlog_upload_up gauge"
    echo "laimory_binlog_upload_up $up"
    echo "# HELP laimory_binlog_upload_last_attempt_unixtime_seconds Unix time of the latest upload attempt."
    echo "# TYPE laimory_binlog_upload_last_attempt_unixtime_seconds gauge"
    echo "laimory_binlog_upload_last_attempt_unixtime_seconds $(date +%s)"
    # -1은 "이번 실행에서 재지 못했다"는 뜻이다(원본 미도달 등). 0과 구분해야 갭 0을 오탐하지 않는다.
    echo "# HELP laimory_binlog_stream_lag_bytes Byte gap between the source binlog position and the spool."
    echo "# TYPE laimory_binlog_stream_lag_bytes gauge"
    echo "laimory_binlog_stream_lag_bytes $lag_bytes"
    if [[ -f $SUCCESS_FILE ]]; then
      echo "# HELP laimory_binlog_upload_last_success_unixtime_seconds Unix time of the latest successful tail upload."
      echo "# TYPE laimory_binlog_upload_last_success_unixtime_seconds gauge"
      echo "laimory_binlog_upload_last_success_unixtime_seconds $(cat "$SUCCESS_FILE")"
    fi
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_metrics 0
  echo "binlog upload failed: $*" >&2
  exit 1
}

# 스트림 생존은 업로드 성패와 독립이다 — 업로드가 실패해도 이 값은 사실대로 기록한다.
if systemctl is-active --quiet "$STREAM_UNIT"; then
  stream_up=1
fi

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${S3_PREFIX:?}"
[[ $S3_PREFIX =~ ^s3://[a-z0-9.-]+(/[A-Za-z0-9._/-]+)?$ ]] || fail "invalid S3_PREFIX"
S3_PREFIX=${S3_PREFIX%/}
RETENTION_DAYS=${RETENTION_DAYS:-7}
[[ $RETENTION_DAYS =~ ^[0-9]+$ ]] || fail "invalid RETENTION_DAYS"

# S3 key는 source 세대(server_uuid)로 분할한다. 파일명은 source가 재생성되면 1번부터 재사용되므로
# uuid가 없으면 새 세대의 binlog.000001이 옛 세대의 같은 이름을 덮어쓴다(버킷에 versioning 없음).
# uuid는 원본이 아니라 상태 파일에서 읽는다 — 원본이 죽어도 이미 받은 것은 올려야 한다.
[[ -f $STATE_FILE ]] || fail "no stream state file; the stream has never started"
read -r source_uuid current_file < "$STATE_FILE"
[[ $source_uuid =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] || fail "malformed state file"
[[ $current_file =~ ^[A-Za-z0-9_-]+\.[0-9]{6}$ ]] || fail "malformed state file"
prefix=${current_file%.*}

# 업로드 표식도 S3 key와 같이 source 세대로 묶는다. 파일명만 쓰면 source 교체 후 새 세대가 같은
# 이름을 다시 쓸 때 이전 세대의 표식 때문에 정규 업로드가 조용히 건너뛰어진다.
# 게다가 재초기화에서 스트림이 스풀을 비우면 아래 보존 스윕(스풀 순회)이 표식에 닿지 못해
# 고아 표식이 영구히 남는다 — 세대별 디렉터리로 나누면 그 표식을 애초에 참조하지 않는다.
marker_dir="$UPLOADED_DIR/$source_uuid"
install -d -m 0700 -o root -g root "$marker_dir"

# 스풀의 최신 파일이 지금 수신 중인 파일이다. 나머지는 rotation으로 닫힌 완결본이다.
newest=""
for path in "$SPOOL_DIR/$prefix".[0-9]*; do
  [[ -f $path ]] || continue
  name=${path##*/}
  if [[ -z $newest ]] || (( 10#${name##*.} > 10#${newest##*.} )); then
    newest=$name
  fi
done
# 스트림이 한 번이라도 돌았으면 활성 파일이 반드시 있다 — 비어 있으면 정상이 아니라 실패다.
# "올릴 게 없어서 성공"으로 처리하면 아무것도 안 올리면서 계속 성공을 보고하는 구멍이 남는다.
[[ -n $newest ]] || fail "spool is empty although the stream has state"

# 완결본: 정규 key로 한 번만 올린다. 업로드 표식이 있으면 건너뛴다.
for path in "$SPOOL_DIR/$prefix".[0-9]*; do
  [[ -f $path ]] || continue
  name=${path##*/}
  [[ $name != "$newest" ]] || continue
  [[ ! -f "$marker_dir/$name" ]] || continue
  aws s3 cp "$path" "$S3_PREFIX/$source_uuid/$name" --only-show-errors ||
    fail "s3 upload failed for $name"
  : > "$marker_dir/$name"
  echo "uploaded completed binlog: $name"
done

# 활성 tail: 매 실행마다 같은 key를 덮어쓴다. 이것이 이중 장애 창을 rotation 주기(~24h)가 아니라
# 이 timer 주기로 줄이는 장치다. 완결본 key 공간과 분리해 미완결 데이터가 완결본인 척하지 않게 한다.
aws s3 cp "$SPOOL_DIR/$newest" "$S3_PREFIX/$source_uuid/tail/$newest" --only-show-errors ||
  fail "s3 tail upload failed for $newest"
date +%s > "$SUCCESS_FILE"

# 보존: 업로드 표식이 있는 완결본만 지운다. 미업로드 파일은 나이와 무관하게 남긴다.
for path in "$SPOOL_DIR/$prefix".[0-9]*; do
  [[ -f $path ]] || continue
  name=${path##*/}
  [[ $name != "$newest" ]] || continue
  [[ -f "$marker_dir/$name" ]] || continue
  if [[ -n $(find "$path" -maxdepth 0 -mtime +"$RETENTION_DAYS") ]]; then
    rm -f "$path" "$marker_dir/$name"
  fi
done

# 갭 지표: 프로세스는 살아 있는데 진행하지 않는 좀비를 잡는다. 원본에 못 붙으면 측정을 건너뛴다
# (업로드 자체는 성공이므로 실패로 만들지 않는다).
if [[ -n ${MYSQL_HOST:-} && -n ${MYSQL_USER:-} && -n ${MYSQL_PASSWORD:-} ]]; then
  export MYSQL_PWD="$MYSQL_PASSWORD"
  if master_status=$(mysql --host="$MYSQL_HOST" --user="$MYSQL_USER" --ssl-mode=REQUIRED \
    --batch --skip-column-names -e "SHOW MASTER STATUS" 2>/dev/null); then
    source_file=$(echo "$master_status" | cut -f1)
    source_pos=$(echo "$master_status" | cut -f2)
    if [[ $source_file == "$newest" ]]; then
      lag_bytes=$(( source_pos - $(stat -c%s "$SPOOL_DIR/$newest") ))
    fi
  fi
  unset MYSQL_PWD
fi

write_metrics 1
echo "binlog upload complete (active=$newest, stream_up=$stream_up, lag=$lag_bytes)"
