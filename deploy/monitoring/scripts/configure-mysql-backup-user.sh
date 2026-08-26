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
SQL
unset BACKUP_PASSWORD

GRANTS="$(mysql --batch --skip-column-names \
  -e "SHOW GRANTS FOR '${BACKUP_USERNAME}'@'localhost'")"
if [[ "$GRANTS" != *"GRANT USAGE ON *.*"* ]] ||
  [[ "$GRANTS" != *"GRANT SELECT, LOCK TABLES ON \`laimory\`.*"* ]] ||
  [[ "$GRANTS" == *"INSERT"* || "$GRANTS" == *"UPDATE"* || "$GRANTS" == *"SUPER"* ]]; then
  echo "unexpected MySQL backup grants" >&2
  exit 1
fi

echo "MySQL backup account is ready with SELECT, LOCK TABLES on laimory.* only."
