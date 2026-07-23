#!/bin/bash
# Run on dev MySQL through SSM. Creates an IP-scoped, USAGE-only exporter account.
set -euo pipefail

MONITORING_PRIVATE_IP="${1:?monitoring private IPv4 is required}"
EXPORTER_USERNAME="${2:-laimory_exporter}"

if ! [[ "$MONITORING_PRIVATE_IP" =~ ^10\.([0-9]{1,3}\.){2}[0-9]{1,3}$ ]]; then
  echo "monitoring private IP must be in the VPC 10/8 range" >&2
  exit 1
fi
if ! [[ "$EXPORTER_USERNAME" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
  echo "invalid MySQL exporter username" >&2
  exit 1
fi

read -rsp "MySQL exporter password: " EXPORTER_PASSWORD
echo
if ! [[ "$EXPORTER_PASSWORD" =~ ^[A-Za-z0-9!#%\^\*_\+=:\.,~@-]{16,128}$ ]]; then
  unset EXPORTER_PASSWORD
  echo "password must be 16-128 safe characters without quotes, spaces, dollar signs, or backslashes" >&2
  exit 1
fi

mysql <<SQL
CREATE USER IF NOT EXISTS '${EXPORTER_USERNAME}'@'${MONITORING_PRIVATE_IP}'
  WITH MAX_USER_CONNECTIONS 3;
ALTER USER '${EXPORTER_USERNAME}'@'${MONITORING_PRIVATE_IP}'
  IDENTIFIED BY '${EXPORTER_PASSWORD}'
  WITH MAX_USER_CONNECTIONS 3
  ACCOUNT UNLOCK;
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM '${EXPORTER_USERNAME}'@'${MONITORING_PRIVATE_IP}';
SQL
unset EXPORTER_PASSWORD

GRANTS="$(mysql --batch --skip-column-names \
  -e "SHOW GRANTS FOR '${EXPORTER_USERNAME}'@'${MONITORING_PRIVATE_IP}'")"
if [[ "$GRANTS" != *"GRANT USAGE ON *.*"* ]] ||
  [[ "$GRANTS" == *" PROCESS "* || "$GRANTS" == *" SELECT "* || "$GRANTS" == *" INSERT "* ]]; then
  echo "unexpected MySQL exporter grants" >&2
  exit 1
fi

echo "MySQL exporter account is unlocked with USAGE only."
