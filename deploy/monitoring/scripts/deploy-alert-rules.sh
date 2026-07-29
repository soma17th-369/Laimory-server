#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: sudo $0 s3://<bucket>/bootstrap/monitoring/releases/alert-rules/<commit-sha>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage

RELEASE_URI=${1%/}
[[ $RELEASE_URI =~ ^s3://[a-z0-9][a-z0-9.-]+/bootstrap/monitoring/releases/alert-rules/[0-9a-f]{40}$ ]] ||
  { echo "invalid alert rule release URI: $RELEASE_URI" >&2; exit 1; }

MONITORING_ROOT_WAS_SET=${MONITORING_ROOT+x}
MONITORING_ROOT=${MONITORING_ROOT:-/opt/laimory-monitoring}
[[ $MONITORING_ROOT == /* && $MONITORING_ROOT != "/" ]] ||
  { echo "MONITORING_ROOT must be a non-root absolute path" >&2; exit 1; }
if [[ ${EUID:-$(id -u)} -ne 0 && -z $MONITORING_ROOT_WAS_SET ]]; then
  echo "run as root" >&2
  exit 1
fi
ALERT_DIR="$MONITORING_ROOT/grafana/provisioning/alerting"
MANIFEST="$MONITORING_ROOT/grafana/alert-rule-files.txt"
RELEASE_MARKER="$MONITORING_ROOT/grafana/alert-rule-release"
VALIDATOR=${ALERT_RULE_VALIDATOR:-$MONITORING_ROOT/scripts/validate-alert-rules.sh}
BACKUP_ROOT="$MONITORING_ROOT/rollback/alert-rules"
LOCK_FILE="$MONITORING_ROOT/.deploy-alert-rules.lock"
DELETE_FILE="$ALERT_DIR/laimory-alert-rule-deletes.yml"
GRAFANA_RELOAD_URL=${GRAFANA_RELOAD_URL:-http://localhost:3000/grafana/api/admin/provisioning/alerting/reload}
GRAFANA_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}
GRAFANA_ADMIN_PASSWORD_FILE=${GRAFANA_ADMIN_PASSWORD_FILE:-$MONITORING_ROOT/secrets/grafana_admin_password}
AWS_REGION=${AWS_REGION:-ap-northeast-2}

[[ $VALIDATOR == /* ]] || { echo "validator must be an absolute path: $VALIDATOR" >&2; exit 1; }
[[ -x $VALIDATOR ]] || { echo "missing executable validator: $VALIDATOR" >&2; exit 1; }
[[ -d $ALERT_DIR ]] || { echo "missing alert provisioning directory: $ALERT_DIR" >&2; exit 1; }
[[ $GRAFANA_ADMIN_USER =~ ^[A-Za-z0-9._-]+$ ]] ||
  { echo "invalid Grafana admin username" >&2; exit 1; }
[[ -f $GRAFANA_ADMIN_PASSWORD_FILE && -s $GRAFANA_ADMIN_PASSWORD_FILE ]] ||
  { echo "missing or empty Grafana admin password file" >&2; exit 1; }
[[ -r $GRAFANA_ADMIN_PASSWORD_FILE ]] ||
  { echo "Grafana admin password file is not readable" >&2; exit 1; }
[[ ! -e $DELETE_FILE ]] || {
  echo "stale deleteRules file requires manual inspection: $DELETE_FILE" >&2
  exit 1
}

for command in aws curl flock sha256sum; do
  command -v "$command" >/dev/null 2>&1 || { echo "missing required command: $command" >&2; exit 1; }
done

exec 9>"$LOCK_FILE"
flock -n 9 || { echo "another alert rule deployment is running" >&2; exit 1; }

STAGE_DIR=$(mktemp -d "$MONITORING_ROOT/.alert-rules-stage.XXXXXX")
trap 'rm -rf "$STAGE_DIR"' EXIT
BACKUP_DIR=
APPLIED=false
CURL_CONFIG="$STAGE_DIR/curl.conf"

write_curl_config_value() {
  local value=$1
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '%s' "$value"
}

GRAFANA_ADMIN_PASSWORD=$(<"$GRAFANA_ADMIN_PASSWORD_FILE")
[[ -n $GRAFANA_ADMIN_PASSWORD ]] ||
  { echo "empty Grafana admin password" >&2; exit 1; }
[[ $(wc -l < "$GRAFANA_ADMIN_PASSWORD_FILE") -eq 0 &&
  $GRAFANA_ADMIN_PASSWORD != *$'\n'* && $GRAFANA_ADMIN_PASSWORD != *$'\r'* ]] ||
  { echo "Grafana admin password must be a single line" >&2; exit 1; }
{
  printf 'user = "'
  write_curl_config_value "$GRAFANA_ADMIN_USER"
  printf ':'
  write_curl_config_value "$GRAFANA_ADMIN_PASSWORD"
  printf '"\n'
} > "$CURL_CONFIG"
chmod 0600 "$CURL_CONFIG"
unset GRAFANA_ADMIN_PASSWORD

reload_grafana_alerting() {
  curl --config "$CURL_CONFIG" -fsS -X POST "$GRAFANA_RELOAD_URL"
}

collect_uids() {
  local directory=$1
  local output=$2
  find "$directory" -maxdepth 1 -type f \
    \( -name '*-rules.yml' -o -name 'rules.yml' -o -name 'operational-rules.yml' \) \
    -exec sed -nE 's/^      - uid: ([A-Za-z0-9_-]+)$/\1/p' {} + |
    sort -u > "$output"
}

write_delete_file() {
  local uid_file=$1
  local destination=$2
  [[ -s $uid_file ]] || return 0
  {
    printf '%s\n\n%s\n' 'apiVersion: 1' 'deleteRules:'
    while IFS= read -r uid; do
      printf '  - orgId: 1\n    uid: %s\n' "$uid"
    done < "$uid_file"
  } > "$destination"
  chmod 0644 "$destination"
}

restore_files() {
  [[ -n $BACKUP_DIR && -d $BACKUP_DIR/alerting ]] || return 0
  rm -f "$DELETE_FILE"
  find "$ALERT_DIR" -maxdepth 1 -type f \
    \( -name '*-rules.yml' -o -name 'rules.yml' -o -name 'operational-rules.yml' \) -delete
  cp -a "$BACKUP_DIR/alerting/." "$ALERT_DIR/"
  if [[ -f $BACKUP_DIR/alert-rule-files.txt ]]; then
    install -m 0644 "$BACKUP_DIR/alert-rule-files.txt" "$MANIFEST"
  else
    rm -f "$MANIFEST"
  fi
  if [[ -f $BACKUP_DIR/alert-rule-release ]]; then
    install -m 0644 "$BACKUP_DIR/alert-rule-release" "$RELEASE_MARKER"
  else
    rm -f "$RELEASE_MARKER"
  fi
}

cleanup() {
  status=$?
  if [[ $status -ne 0 && $APPLIED == true ]]; then
    restore_files
    echo "alert rule deployment failed; provisioning files were restored from $BACKUP_DIR" >&2
  fi
  rm -rf "$STAGE_DIR"
}
trap cleanup EXIT

aws s3 cp "$RELEASE_URI/SHA256SUMS" "$STAGE_DIR/SHA256SUMS" \
  --region "$AWS_REGION" --only-show-errors
[[ -s $STAGE_DIR/SHA256SUMS ]] || { echo "empty release checksum manifest" >&2; exit 1; }

: > "$STAGE_DIR/alert-rule-files.txt"
: > "$STAGE_DIR/release-names"
while read -r digest name extra; do
  [[ -z ${extra:-} ]] || { echo "invalid checksum entry for $name" >&2; exit 1; }
  [[ ${digest:-} =~ ^[0-9a-f]{64}$ ]] || { echo "invalid checksum for ${name:-<missing>}" >&2; exit 1; }
  [[ ${name:-} =~ ^[a-z0-9][a-z0-9-]*-rules\.yml$ ]] ||
    { echo "invalid release filename: ${name:-<missing>}" >&2; exit 1; }
  ! grep -Fqx "$name" "$STAGE_DIR/release-names" ||
    { echo "duplicate release filename: $name" >&2; exit 1; }
  printf '%s\n' "$name" >> "$STAGE_DIR/release-names"
  printf '%s\n' "$name" >> "$STAGE_DIR/alert-rule-files.txt"
  aws s3 cp "$RELEASE_URI/$name" "$STAGE_DIR/$name" \
    --region "$AWS_REGION" --only-show-errors
done < "$STAGE_DIR/SHA256SUMS"

(cd "$STAGE_DIR" && sha256sum --check --strict SHA256SUMS)
"$VALIDATOR" "$STAGE_DIR" "$STAGE_DIR/alert-rule-files.txt"
collect_uids "$ALERT_DIR" "$STAGE_DIR/old-uids"
collect_uids "$STAGE_DIR" "$STAGE_DIR/new-uids"
comm -23 "$STAGE_DIR/old-uids" "$STAGE_DIR/new-uids" > "$STAGE_DIR/removed-uids"
comm -13 "$STAGE_DIR/old-uids" "$STAGE_DIR/new-uids" > "$STAGE_DIR/added-uids"

stamp=$(date -u +%Y%m%dT%H%M%S%N)
BACKUP_DIR="$BACKUP_ROOT/$stamp"
install -d -m 0700 "$BACKUP_DIR/alerting"
find "$ALERT_DIR" -maxdepth 1 -type f \
  \( -name '*-rules.yml' -o -name 'rules.yml' -o -name 'operational-rules.yml' \) \
  -exec cp -a {} "$BACKUP_DIR/alerting/" \;
if [[ -f $MANIFEST ]]; then
  install -m 0600 "$MANIFEST" "$BACKUP_DIR/alert-rule-files.txt"
fi
if [[ -f $RELEASE_MARKER ]]; then
  install -m 0600 "$RELEASE_MARKER" "$BACKUP_DIR/alert-rule-release"
fi

APPLIED=true
find "$ALERT_DIR" -maxdepth 1 -type f \
  \( -name '*-rules.yml' -o -name 'rules.yml' -o -name 'operational-rules.yml' \) -delete
while IFS= read -r name; do
  install -m 0644 "$STAGE_DIR/$name" "$ALERT_DIR/$name"
done < "$STAGE_DIR/alert-rule-files.txt"
install -m 0644 "$STAGE_DIR/alert-rule-files.txt" "$MANIFEST"
write_delete_file "$STAGE_DIR/removed-uids" "$DELETE_FILE"

"$VALIDATOR" "$ALERT_DIR" "$MANIFEST"

if ! reload_grafana_alerting; then
  restore_files
  write_delete_file "$STAGE_DIR/added-uids" "$DELETE_FILE"
  echo "new rules were rejected; reloading the restored files" >&2
  if ! reload_grafana_alerting; then
    echo "restored files could not be reloaded; Grafana requires manual inspection" >&2
  fi
  rm -f "$DELETE_FILE"
  APPLIED=false
  exit 1
fi

rm -f "$DELETE_FILE"
printf '%s\n' "$RELEASE_URI" > "$STAGE_DIR/alert-rule-release"
install -m 0644 "$STAGE_DIR/alert-rule-release" "$RELEASE_MARKER"
APPLIED=false
echo "deployed alert rule release ${RELEASE_URI##*/}; backup: $BACKUP_DIR"
