#!/bin/bash
# Reads one secret value from stdin and atomically installs it with the container UID that consumes it.
set -euo pipefail

SECRET_NAME="${1:?secret name is required}"
SECRETS_DIR="${MONITORING_SECRETS_DIR:-/opt/laimory-monitoring/secrets}"

case "$SECRET_NAME" in
  grafana_admin_password|grafana_secret_key|elasticsearch_api_key|discord_webhook_url|google_oauth_client_secret)
    OWNER=472
    GROUP=0
    ;;
  mysql_exporter_my.cnf)
    OWNER=65534
    GROUP=0
    ;;
  redis_exporter_password.json)
    OWNER=59000
    GROUP=59000
    ;;
  *)
    echo "unsupported monitoring secret name: $SECRET_NAME" >&2
    exit 1
    ;;
esac

install -d -m 0700 -o root -g root "$SECRETS_DIR"
TEMP_FILE="$(mktemp "$SECRETS_DIR/.${SECRET_NAME}.XXXXXX")"
trap 'rm -f "$TEMP_FILE"' EXIT

cat > "$TEMP_FILE"
if [ ! -s "$TEMP_FILE" ]; then
  echo "$SECRET_NAME must not be empty" >&2
  exit 1
fi

chown "$OWNER:$GROUP" "$TEMP_FILE"
chmod 0400 "$TEMP_FILE"
mv -f "$TEMP_FILE" "$SECRETS_DIR/$SECRET_NAME"
trap - EXIT
