#!/bin/bash
# Run on prod MySQL through SSM. Creates the replication account the monitoring host streams binlogs with.
# root는 socket 인증이므로 root 셸에서 실행하면 비밀번호 없이 붙는다.
#
# 이 계정은 전역 권한만 받을 수 있다 — REPLICATION SLAVE/CLIENT는 DB 범위로 좁힐 수 없는 권한이다.
# 대신 접속 호스트를 monitoring host 한 곳으로 고정하고 TLS를 계정 단위로 강제한다
# (서버 전역 require_secure_transport는 OFF다).
set -euo pipefail

usage() {
  echo "usage: $0 <monitoring-host-address> [username]" >&2
  echo "  monitoring-host-address: exact private address of the monitoring host ('%' is rejected)" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage

CLIENT_HOST=$1
BINLOG_USERNAME="${2:-laimory_binlog}"

# 와일드카드 호스트는 받지 않는다 — 이 계정은 서버 전체의 모든 row 변경을 읽을 수 있다.
if ! [[ "$CLIENT_HOST" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  echo "monitoring host must be an exact IPv4 address" >&2
  exit 1
fi
if ! [[ "$BINLOG_USERNAME" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
  echo "invalid MySQL binlog username" >&2
  exit 1
fi

read -rsp "MySQL binlog stream password: " BINLOG_PASSWORD
echo
if ! [[ "$BINLOG_PASSWORD" =~ ^[A-Za-z0-9!#%\^\*_\+=:\.,~@-]{16,128}$ ]]; then
  unset BINLOG_PASSWORD
  echo "password must be 16-128 safe characters without quotes, spaces, dollar signs, or backslashes" >&2
  exit 1
fi

mysql <<SQL
CREATE USER IF NOT EXISTS '${BINLOG_USERNAME}'@'${CLIENT_HOST}'
  REQUIRE SSL
  WITH MAX_USER_CONNECTIONS 2;
ALTER USER '${BINLOG_USERNAME}'@'${CLIENT_HOST}'
  IDENTIFIED BY '${BINLOG_PASSWORD}'
  REQUIRE SSL
  WITH MAX_USER_CONNECTIONS 2
  ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM '${BINLOG_USERNAME}'@'${CLIENT_HOST}';
-- REPLICATION SLAVE: mysqlbinlog --read-from-remote-server가 요구하는 권한.
-- REPLICATION CLIENT: SHOW BINARY LOGS / SHOW MASTER STATUS(재개 파일 확인·갭 지표).
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${BINLOG_USERNAME}'@'${CLIENT_HOST}';
SQL
unset BINLOG_PASSWORD

GRANTS="$(mysql --batch --skip-column-names \
  -e "SHOW GRANTS FOR '${BINLOG_USERNAME}'@'${CLIENT_HOST}'")"
# RELOAD가 붙으면 안 된다 — 주기적 FLUSH BINARY LOGS 방식을 채택하지 않았으므로 필요 없는 권한이다.
if [[ "$GRANTS" != *"REPLICATION SLAVE"* ]] ||
  [[ "$GRANTS" != *"REPLICATION CLIENT"* ]] ||
  [[ "$GRANTS" == *"SELECT"* || "$GRANTS" == *"INSERT"* || "$GRANTS" == *"UPDATE"* ]] ||
  [[ "$GRANTS" == *"RELOAD"* || "$GRANTS" == *"SUPER"* ]]; then
  echo "unexpected MySQL binlog grants" >&2
  exit 1
fi

# REQUIRE SSL은 SHOW GRANTS에 나오지 않으므로 계정 메타데이터에서 직접 확인한다.
SSL_TYPE="$(mysql --batch --skip-column-names \
  -e "SELECT ssl_type FROM mysql.user WHERE user='${BINLOG_USERNAME}' AND host='${CLIENT_HOST}'")"
if [[ "$SSL_TYPE" != "ANY" ]]; then
  echo "binlog account is not TLS-restricted (ssl_type='${SSL_TYPE}')" >&2
  exit 1
fi

echo "MySQL binlog account is ready: REPLICATION SLAVE, REPLICATION CLIENT from ${CLIENT_HOST} over TLS."
