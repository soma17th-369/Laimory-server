#!/bin/bash
# OCI host에서 root로 실행한다. mysql 컨테이너 안에 백업·모니터 로컬 계정을 만들고, 비밀번호를
# host 설정 파일(/etc/laimory/oci-mysql.env, 0600)에만 기록한다.
#
# 비밀번호는 여기서 무작위로 만들고 화면·argv·SQL 로그 어디에도 내보내지 않는다 — 사람이 볼 일이
# 없는 값이라 직접 입력받지 않는다. 다시 실행하면 비밀번호를 새로 바꾼다.
#
# 이 서버는 super_read_only라 계정 문장도 막힌다. 잠깐 풀고 다시 켠다(복제 스레드는 영향 없음).
# sql_log_bin=0으로 계정 문장을 이 서버 binlog에 남기지 않는다 — 백업 사슬에 계정 변경이 섞이지 않게.
set -euo pipefail

readonly CONFIG_FILE=${LAIMORY_OCI_MYSQL_ENV:-/etc/laimory/oci-mysql.env}
readonly ROOT_PASSWORD_PATH=/run/secrets/mysql-root-password   # 컨테이너 안 경로
readonly BACKUP_USER=laimory_backup
readonly MONITOR_USER=laimory_monitor

[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 1; }
[[ -f $CONFIG_FILE ]] || { echo "missing config: $CONFIG_FILE" >&2; exit 1; }
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${MYSQL_CONTAINER:?MYSQL_CONTAINER is not set in $CONFIG_FILE}"
[[ $MYSQL_CONTAINER =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "invalid MYSQL_CONTAINER" >&2; exit 1; }

# SQL에 그대로 들어가므로 따옴표·백슬래시가 나올 수 없는 문자 집합으로 만든다.
new_password() {
  local value
  value=$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40)
  [[ ${#value} -eq 40 ]] || { echo "password generation failed" >&2; exit 1; }
  printf '%s' "$value"
}

# 설정 파일의 키를 바꾸거나 덧붙인다. 임시 파일을 같은 디렉터리에 0600으로 만든 뒤 교체한다.
set_env_key() {
  local key=$1 value=$2 tmp
  tmp=$(mktemp "$(dirname "$CONFIG_FILE")/.oci-mysql.env.XXXXXX")
  chmod 0600 "$tmp"
  awk -v k="$key" -v v="$value" '
    BEGIN { done = 0 }
    $0 ~ "^" k "=" { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$CONFIG_FILE" > "$tmp"
  mv -f "$tmp" "$CONFIG_FILE"
}

root_sql() {
  docker exec -i "$MYSQL_CONTAINER" sh -c \
    "MYSQL_PWD=\$(cat $ROOT_PASSWORD_PATH) exec mysql --user=root --batch --skip-column-names"
}

backup_password=$(new_password)
monitor_password=$(new_password)

# SQL이 중간에 실패해도 super_read_only가 꺼진 채 남지 않게 한다.
trap 'echo "SET GLOBAL super_read_only = ON;" | root_sql > /dev/null 2>&1 || true' EXIT

root_sql <<SQL
SET SESSION sql_log_bin = 0;
SET GLOBAL super_read_only = OFF;
CREATE USER IF NOT EXISTS '${BACKUP_USER}'@'localhost' WITH MAX_USER_CONNECTIONS 2;
ALTER USER '${BACKUP_USER}'@'localhost' IDENTIFIED BY '${backup_password}' WITH MAX_USER_CONNECTIONS 2 ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${BACKUP_USER}'@'localhost';
-- mysqldump --single-transaction --source-data=2 --flush-logs의 최소 권한.
-- RELOAD: FTWRL·FLUSH LOGS, REPLICATION CLIENT: SHOW MASTER STATUS. 둘 다 전역 전용 권한이다.
GRANT SELECT, LOCK TABLES ON laimory.* TO '${BACKUP_USER}'@'localhost';
GRANT RELOAD, REPLICATION CLIENT ON *.* TO '${BACKUP_USER}'@'localhost';
CREATE USER IF NOT EXISTS '${MONITOR_USER}'@'localhost' WITH MAX_USER_CONNECTIONS 2;
ALTER USER '${MONITOR_USER}'@'localhost' IDENTIFIED BY '${monitor_password}' WITH MAX_USER_CONNECTIONS 2 ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${MONITOR_USER}'@'localhost';
-- SHOW REPLICA STATUS에 필요한 권한만 준다.
GRANT REPLICATION CLIENT ON *.* TO '${MONITOR_USER}'@'localhost';
SET GLOBAL super_read_only = ON;
SQL

set_env_key MYSQL_BACKUP_USER "$BACKUP_USER"
set_env_key MYSQL_BACKUP_PASSWORD "$backup_password"
set_env_key MYSQL_MONITOR_USER "$MONITOR_USER"
set_env_key MYSQL_MONITOR_PASSWORD "$monitor_password"
unset backup_password monitor_password

backup_grants=$(echo "SHOW GRANTS FOR '${BACKUP_USER}'@'localhost'" | root_sql)
monitor_grants=$(echo "SHOW GRANTS FOR '${MONITOR_USER}'@'localhost'" | root_sql)
if [[ $backup_grants != *"RELOAD, REPLICATION CLIENT"* ]] ||
  [[ $backup_grants != *"GRANT SELECT, LOCK TABLES ON \`laimory\`.*"* ]] ||
  [[ $backup_grants == *"INSERT"* || $backup_grants == *"SUPER"* ]] ||
  [[ $monitor_grants != *"REPLICATION CLIENT"* ]] ||
  [[ $monitor_grants == *"SELECT"* || $monitor_grants == *"RELOAD"* ]]; then
  echo "unexpected local account grants" >&2
  exit 1
fi
[[ $(echo "SELECT @@GLOBAL.super_read_only" | root_sql) == 1 ]] ||
  { echo "super_read_only did not come back ON" >&2; exit 1; }

echo "local accounts ready: ${BACKUP_USER}, ${MONITOR_USER} (passwords written to ${CONFIG_FILE})"
