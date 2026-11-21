#!/bin/bash
# OCI mysql 컨테이너의 복제 상태(SHOW REPLICA STATUS)를 textfile 지표로 기록한다.
# 복제 기간 전용이다 — 전환(승격) 때 이 timer와 지표·경보를 함께 제거한다.
#
# textfile 수집기는 파일이 다시 쓰일 때까지 마지막 값을 계속 노출한다. 이 스크립트(또는 timer)가
# 멈추면 running=1이 굳으므로, 매 실행 시각을 함께 남겨 경보가 "오래된 수집"도 정지로 본다.
set -euo pipefail

readonly CONFIG_FILE=${LAIMORY_OCI_MYSQL_ENV:-/etc/laimory/oci-mysql.env}
readonly OUTPUT_DIR=/var/lib/laimory/textfile
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_oci_mysql_replica.prom"

install -d -m 0755 -o root -g root "$OUTPUT_DIR"

io=0
sql=0
behind=-1   # -1 = 지연을 알 수 없음(스레드 정지·조회 실패). 0과 구분해야 지연 0을 오탐하지 않는다.

write_metrics() {
  local tmp_file
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_oci_mysql_replica.XXXXXX")
  {
    echo "# HELP laimory_oci_mysql_replica_running Whether the OCI MySQL replication thread is running (1) or not (0)."
    echo "# TYPE laimory_oci_mysql_replica_running gauge"
    echo "laimory_oci_mysql_replica_running{thread=\"io\"} $io"
    echo "laimory_oci_mysql_replica_running{thread=\"sql\"} $sql"
    echo "# HELP laimory_oci_mysql_replica_seconds_behind Seconds_Behind_Source of the OCI MySQL replica (-1 when unknown)."
    echo "# TYPE laimory_oci_mysql_replica_seconds_behind gauge"
    echo "laimory_oci_mysql_replica_seconds_behind $behind"
    echo "# HELP laimory_oci_mysql_replica_last_collect_unixtime_seconds Unix time this collector last ran."
    echo "# TYPE laimory_oci_mysql_replica_last_collect_unixtime_seconds gauge"
    echo "laimory_oci_mysql_replica_last_collect_unixtime_seconds $(date +%s)"
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

# 설정·조회가 실패하면 스레드가 멈춘 것과 같게 기록한다(복제 정지 경보가 발화해야 하는 상태다).
fail() {
  write_metrics
  echo "oci replica status collection failed: $*" >&2
  exit 1
}

[[ -f $CONFIG_FILE ]] || fail "missing config: $CONFIG_FILE"
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_CONTAINER:?}" "${MYSQL_MONITOR_USER:?}" "${MYSQL_MONITOR_PASSWORD:?}"
[[ $MYSQL_CONTAINER =~ ^[A-Za-z0-9_.-]+$ ]] || fail "invalid MYSQL_CONTAINER"

export MYSQL_PWD="$MYSQL_MONITOR_PASSWORD"
status=$(docker exec -e MYSQL_PWD "$MYSQL_CONTAINER" \
  mysql --user="$MYSQL_MONITOR_USER" --batch -e "SHOW REPLICA STATUS\G") || fail "SHOW REPLICA STATUS failed"
unset MYSQL_PWD
[[ -n $status ]] || fail "replication is not configured"

field() { awk -F': ' -v k="$1" '$1 ~ "^ *" k "$" { print $2; exit }' <<< "$status"; }

# IO 스레드는 재연결 중이면 "Connecting"이다 — 끊긴 상태이므로 0으로 본다.
[[ $(field Replica_IO_Running) == Yes ]] && io=1
[[ $(field Replica_SQL_Running) == Yes ]] && sql=1
seconds=$(field Seconds_Behind_Source)
[[ $seconds =~ ^[0-9]+$ ]] && behind=$seconds

write_metrics
echo "oci replica status: io=$io sql=$sql behind=$behind"
