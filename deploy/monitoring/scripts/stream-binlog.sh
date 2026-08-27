#!/bin/bash
# prod MySQL의 binlog를 monitoring host로 상시 스트리밍한다(PITR용 오프호스트 사본).
# 수집을 DB host에서 하지 않는 이유: 로컬 timer 방식은 완결된 파일만 올릴 수 있어 "지금 쓰이고
# 있는 binlog"를 원리적으로 반출하지 못하고, 사고 직전 트랜잭션이 늘 원본 디스크에만 남는다.
# 설정(호스트·계정·시작 파일)은 monitoring host의 /etc/laimory/binlog-stream.env가 소유한다 —
# 내부 주소와 버킷 이름은 저장소에 두지 않는다.
set -euo pipefail

readonly CONFIG_FILE=/etc/laimory/binlog-stream.env
readonly SPOOL_DIR=/var/lib/laimory/binlog
readonly STATE_FILE=/var/lib/laimory/binlog-stream-position
readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_binlog_stream.prom"
# 사슬이 끊긴 종료는 자동 재시작 대상이 아니다 — unit의 RestartPreventExitStatus가 이 코드를 막는다.
# 재시작해도 상황이 그대로라 크래시 루프만 되고, 필요한 조치는 "새 전체 덤프 + 스트림 재초기화"다.
readonly EXIT_CHAIN_BROKEN=78

install -d -m 0700 -o root -g root "$SPOOL_DIR" "$(dirname "$STATE_FILE")"
install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"

# chain_broken만 이 스크립트가 소유한다. stream_up은 의도적으로 여기서 쓰지 않는다 —
# node_exporter textfile collector는 파일이 다시 쓰일 때까지 마지막 내용을 계속 노출하므로,
# 프로세스가 자기 생존을 자기가 기록하면 죽는 순간 1로 굳어 아무도 죽음을 모른다.
# stream_up의 writer는 독립적으로 매분 도는 upload-binlog.sh다.
write_chain_metric() {
  local broken=$1 tmp_file
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_binlog_stream.XXXXXX")
  {
    echo "# HELP laimory_binlog_stream_chain_broken Whether the binlog stream lost its PITR chain."
    echo "# TYPE laimory_binlog_stream_chain_broken gauge"
    echo "laimory_binlog_stream_chain_broken $broken"
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  echo "binlog stream failed: $*" >&2
  exit 1
}

chain_broken() {
  write_chain_metric 1
  echo "binlog stream chain broken: $*" >&2
  echo "recovery requires a fresh full dump and stream re-initialisation" >&2
  exit "$EXIT_CHAIN_BROKEN"
}

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_HOST:?}" "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}" "${CONNECTION_SERVER_ID:?}"
[[ $CONNECTION_SERVER_ID =~ ^[0-9]+$ ]] || fail "invalid CONNECTION_SERVER_ID"

# MYSQL_PWD는 env로만 전달되어 argv에 노출되지 않는다.
export MYSQL_PWD="$MYSQL_PASSWORD"
mysql_query() {
  mysql --host="$MYSQL_HOST" --user="$MYSQL_USER" --ssl-mode=REQUIRED \
    --batch --skip-column-names -e "$1"
}

source_uuid=$(mysql_query "SELECT @@server_uuid") || fail "cannot reach source"
[[ $source_uuid =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] || fail "unexpected server_uuid"

# 상태 파일이 담는 값은 "다음에 수신을 재개할 파일"이지 "마지막으로 완료한 파일"이 아니다.
# 후자로 두면 재시작 시 이미 완결·업로드된 파일을 truncate한 채 재수신하는데, 그동안 스풀에는
# 더 뒤의 미완결 파일이 남아 있어 그 파일이 uploader의 "최신 1개 제외" 규칙을 통과해 버린다 →
# 잘린 파일이 S3의 정상본을 덮어써 PITR 사슬 중간이 조용히 파괴된다(버킷에 versioning 없음).
write_state() {
  local tmp_file
  tmp_file=$(mktemp "$(dirname "$STATE_FILE")/.binlog-stream-position.XXXXXX")
  printf '%s %s\n' "$source_uuid" "$1" > "$tmp_file"
  chmod 0600 "$tmp_file"
  mv -f "$tmp_file" "$STATE_FILE"
}

if [[ -f $STATE_FILE ]]; then
  read -r state_uuid resume_file < "$STATE_FILE"
  [[ -n ${state_uuid:-} && -n ${resume_file:-} ]] || fail "malformed state file"
  # binlog 파일명은 source가 재생성되면 1번부터 재사용되므로 이름만으로는 계보를 알 수 없다.
  [[ $state_uuid == "$source_uuid" ]] ||
    chain_broken "source uuid changed (state=$state_uuid current=$source_uuid)"
else
  # 최초 설치에서만 오는 경로다. 시작 파일을 재도출하지 않는다 — 현재 활성 파일로 시작하면
  # 첫 덤프 좌표보다 뒤에서 시작해 재생 사슬에 구멍이 생기므로, 운영자가 명시한 값만 받는다.
  [[ -n ${BINLOG_START_FILE:-} ]] ||
    fail "no state file and BINLOG_START_FILE is unset; set it once for the initial install"
  resume_file=$BINLOG_START_FILE
fi
[[ $resume_file =~ ^[A-Za-z0-9_-]+\.[0-9]{6}$ ]] || fail "invalid binlog file name: $resume_file"

# 재개 파일이 원본에서 이미 purge됐으면 재시작해도 영영 못 받는다 — 크래시 루프 대신 멈춘다.
mysql_query "SHOW BINARY LOGS" | cut -f1 | grep -qx "$resume_file" ||
  chain_broken "resume file $resume_file no longer exists on the source"

# --raw는 재개 시 출력 파일을 truncate하고 처음부터 다시 받는다. 재개 지점 이상으로 정렬되는
# 스풀 파일을 전부 지워야 "스풀의 최신 1개 = 지금 수신 중인 파일"이라는 uploader의 전제가 유지된다.
prefix=${resume_file%.*}
resume_seq=${resume_file##*.}
# 파일명 비교는 locale collation에 좌우되므로 순번을 10진수로 비교한다(선행 0 때문에 10# 필요).
for path in "$SPOOL_DIR/$prefix".[0-9]*; do
  [[ -f $path ]] || continue
  spooled=${path##*/}
  if (( 10#${spooled##*.} >= 10#$resume_seq )); then
    rm -f "$path"
  fi
done

write_state "$resume_file"
write_chain_metric 0
echo "binlog stream resuming from $resume_file (source $source_uuid)"

mysqlbinlog --read-from-remote-server --host="$MYSQL_HOST" --user="$MYSQL_USER" \
  --ssl-mode=REQUIRED --raw --stop-never --connection-server-id="$CONNECTION_SERVER_ID" \
  --result-file="$SPOOL_DIR/" "$resume_file" &
stream_pid=$!
trap 'kill "$stream_pid" 2>/dev/null || true' EXIT

# 스풀에 다음 파일이 나타나면 이전 파일은 닫힌 것이다(--raw는 rotation 시 이전 파일을 닫고
# 다음 파일을 연다) → 재개 지점을 그 새 파일로 전진시킨다. 이전 파일로 두면 위의 덮어쓰기 경로가 열린다.
current=$resume_file
while kill -0 "$stream_pid" 2>/dev/null; do
  newest=""
  for path in "$SPOOL_DIR/$prefix".[0-9]*; do
    [[ -f $path ]] || continue
    name=${path##*/}
    if [[ -z $newest ]] || (( 10#${name##*.} > 10#${newest##*.} )); then
      newest=$name
    fi
  done
  if [[ -n $newest && $newest != "$current" ]]; then
    write_state "$newest"
    current=$newest
  fi
  sleep 5
done

set +e
wait "$stream_pid"
stream_rc=$?
set -e
exit "$stream_rc"
