#!/bin/bash
set -euo pipefail

SECRETS_DIR="${MONITORING_SECRETS_DIR:-/opt/laimory-monitoring/secrets}"

check_secret() {
  local name="$1"
  local expected="$2"
  local path="$SECRETS_DIR/$name"

  [ -s "$path" ] || {
    echo "missing or empty monitoring secret: $name" >&2
    return 1
  }

  local actual
  actual="$(stat -c '%u:%g:%a' "$path")"
  [ "$actual" = "$expected" ] || {
    echo "invalid owner or mode for $name: expected $expected, got $actual" >&2
    return 1
  }
}

check_secret grafana_admin_password 472:0:400
check_secret grafana_secret_key 472:0:400
check_secret elasticsearch_api_key 472:0:400
check_secret discord_webhook_url 472:0:400
check_secret google_oauth_client_secret 472:0:400
check_secret mysql_exporter_my.cnf 65534:0:400
check_secret redis_exporter_password.json 59000:59000:400
