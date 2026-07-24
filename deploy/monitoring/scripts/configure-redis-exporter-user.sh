#!/bin/bash
# Run on Redis through SSM. Keeps the exporter unable to read keys, arguments, or mutate data.
set -euo pipefail

APP_USERNAME="${1:-laimory_app}"
EXPORTER_USERNAME="${2:-laimory_monitoring}"

for username in "$APP_USERNAME" "$EXPORTER_USERNAME"; do
  if ! [[ "$username" =~ ^[A-Za-z0-9_]{1,64}$ ]]; then
    echo "invalid Redis username" >&2
    exit 1
  fi
done

read -rsp "Redis app/admin password: " APP_PASSWORD
echo
read -rsp "Redis exporter password: " EXPORTER_PASSWORD
echo
if [ "${#EXPORTER_PASSWORD}" -lt 16 ]; then
  unset APP_PASSWORD EXPORTER_PASSWORD
  echo "Redis exporter password must be at least 16 characters" >&2
  exit 1
fi

printf '>%s' "$EXPORTER_PASSWORD" |
  REDISCLI_AUTH="$APP_PASSWORD" redis-cli --user "$APP_USERNAME" -x \
    ACL SETUSER "$EXPORTER_USERNAME" reset on resetkeys resetchannels \
    -@all +info +ping '+client|setname' >/dev/null
REDISCLI_AUTH="$APP_PASSWORD" redis-cli --user "$APP_USERNAME" ACL SAVE >/dev/null

REDISCLI_AUTH="$EXPORTER_PASSWORD" redis-cli --user "$EXPORTER_USERNAME" PING |
  grep -qx PONG
REDISCLI_AUTH="$EXPORTER_PASSWORD" redis-cli --user "$EXPORTER_USERNAME" INFO server >/dev/null

for denied_command in \
  "GET laimory-monitoring-permission-probe" \
  "SET laimory-monitoring-permission-probe denied" \
  "SCAN 0" \
  "SLOWLOG GET 1"; do
  DRY_RUN="$(
    REDISCLI_AUTH="$APP_PASSWORD" redis-cli --user "$APP_USERNAME" \
      ACL DRYRUN "$EXPORTER_USERNAME" $denied_command 2>&1 || true
  )"
  case "$DRY_RUN" in
    *NOPERM*|*"has no permissions"*) ;;
    *)
      unset APP_PASSWORD EXPORTER_PASSWORD DRY_RUN
      echo "Redis exporter unexpectedly permits: $denied_command" >&2
      exit 1
      ;;
  esac
done

unset APP_PASSWORD EXPORTER_PASSWORD DRY_RUN
echo "Redis exporter ACL permits INFO/PING/CLIENT SETNAME and denies key access and writes."
