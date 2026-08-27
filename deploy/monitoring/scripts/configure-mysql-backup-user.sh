#!/bin/bash
# Run on prod MySQL through SSM. Creates a localhost-only, read-only backup account for mysqldump.
# root는 socket 인증이므로 root 셸에서 실행하면 비밀번호 없이 붙는다.
set -euo pipefail

BACKUP_USERNAME="${1:-laimory_backup}"

if ! [[ "$BACKUP_USERNAME" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
  echo "invalid MySQL backup username" >&2
  exit 1
fi

read -rsp "MySQL backup password: " BACKUP_PASSWORD
echo
if ! [[ "$BACKUP_PASSWORD" =~ ^[A-Za-z0-9!#%\^\*_\+=:\.,~@-]{16,128}$ ]]; then
  unset BACKUP_PASSWORD
  echo "password must be 16-128 safe characters without quotes, spaces, dollar signs, or backslashes" >&2
  exit 1
fi

mysql <<SQL
CREATE USER IF NOT EXISTS '${BACKUP_USERNAME}'@'localhost'
  WITH MAX_USER_CONNECTIONS 2;
ALTER USER '${BACKUP_USERNAME}'@'localhost'
  IDENTIFIED BY '${BACKUP_PASSWORD}'
  WITH MAX_USER_CONNECTIONS 2
  ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM '${BACKUP_USERNAME}'@'localhost';
GRANT SELECT, LOCK TABLES ON laimory.* TO '${BACKUP_USERNAME}'@'localhost';
-- mysqldump --source-data=2가 요구하는 최소 전역 권한.
-- RELOAD는 옵션 자체가 요구하고, REPLICATION CLIENT는 그 옵션이 보내는 SHOW MASTER STATUS가 요구한다.
-- 둘 다 전역 전용 권한이라 DB 범위로 좁힐 수 없다.
GRANT RELOAD, REPLICATION CLIENT ON *.* TO '${BACKUP_USERNAME}'@'localhost';
SQL
unset BACKUP_PASSWORD

GRANTS="$(mysql --batch --skip-column-names \
  -e "SHOW GRANTS FOR '${BACKUP_USERNAME}'@'localhost'")"
# 전역 권한이 붙으면 SHOW GRANTS의 첫 줄이 USAGE에서 실제 권한 목록으로 바뀐다.
# 권한 나열 순서는 서버가 정하므로 결합된 문자열이 아니라 이름별로 확인한다.
if [[ "$GRANTS" != *"RELOAD"* ]] ||
  [[ "$GRANTS" != *"REPLICATION CLIENT"* ]] ||
  [[ "$GRANTS" != *"GRANT SELECT, LOCK TABLES ON \`laimory\`.*"* ]] ||
  [[ "$GRANTS" == *"INSERT"* || "$GRANTS" == *"UPDATE"* || "$GRANTS" == *"SUPER"* ]] ||
  [[ "$GRANTS" == *"REPLICATION SLAVE"* ]]; then
  echo "unexpected MySQL backup grants" >&2
  exit 1
fi

echo "MySQL backup account is ready: SELECT, LOCK TABLES on laimory.* plus global RELOAD," \
  "REPLICATION CLIENT for --source-data."
