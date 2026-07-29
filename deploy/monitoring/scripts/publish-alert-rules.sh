#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <backup-bucket> [aws-profile]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage

BACKUP_BUCKET=$1
AWS_PROFILE=${2:-}
AWS_REGION=${AWS_REGION:-ap-northeast-2}
[[ $BACKUP_BUCKET =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] ||
  { echo "invalid S3 bucket name: $BACKUP_BUCKET" >&2; exit 1; }

for command in aws git; do
  command -v "$command" >/dev/null 2>&1 || { echo "missing required command: $command" >&2; exit 1; }
done

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MONITORING_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(cd "$MONITORING_DIR/../.." && pwd)
ALERT_DIR="$MONITORING_DIR/grafana/provisioning/alerting"
MANIFEST="$MONITORING_DIR/grafana/alert-rule-files.txt"

"$SCRIPT_DIR/validate-alert-rules.sh" "$ALERT_DIR" "$MANIFEST"

if [[ -n $(git -C "$REPO_ROOT" status --porcelain -- \
  deploy/monitoring/grafana/provisioning/alerting \
  deploy/monitoring/grafana/alert-rule-files.txt \
  deploy/monitoring/scripts/deploy-alert-rules.sh \
  deploy/monitoring/scripts/validate-alert-rules.sh) ]]; then
  echo "commit the alert rule files before publishing an immutable release" >&2
  exit 1
fi

RELEASE_ID=$(git -C "$REPO_ROOT" rev-parse --verify HEAD)
RELEASE_URI="s3://$BACKUP_BUCKET/bootstrap/monitoring/releases/alert-rules/$RELEASE_ID"
RELEASE_PREFIX="bootstrap/monitoring/releases/alert-rules/$RELEASE_ID"
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT
AWS_ARGS=(--region "$AWS_REGION")
if [[ -n $AWS_PROFILE ]]; then
  AWS_ARGS+=(--profile "$AWS_PROFILE")
fi

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

put_immutable() {
  local source_path=$1
  local object_key=$2
  local expected_digest=$3
  local existing_path
  local actual_digest

  if aws "${AWS_ARGS[@]}" s3api put-object \
    --bucket "$BACKUP_BUCKET" \
    --key "$object_key" \
    --body "$source_path" \
    --if-none-match '*' >/dev/null; then
    return 0
  fi

  existing_path=$(mktemp "$TEMP_DIR/existing.XXXXXX")
  if ! aws "${AWS_ARGS[@]}" s3api get-object \
    --bucket "$BACKUP_BUCKET" \
    --key "$object_key" \
    "$existing_path" >/dev/null; then
    echo "failed to create or verify immutable release object: $object_key" >&2
    return 1
  fi

  actual_digest=$(checksum "$existing_path")
  if [[ $actual_digest != "$expected_digest" ]]; then
    echo "immutable release collision: $object_key already contains different bytes" >&2
    return 1
  fi
  echo "immutable release object already exists with identical bytes: $object_key" >&2
}

while IFS= read -r name || [[ -n $name ]]; do
  [[ -n $name ]] || continue
  digest=$(checksum "$ALERT_DIR/$name")
  printf '%s  %s\n' "$digest" "$name" >> "$TEMP_DIR/SHA256SUMS"
  put_immutable "$ALERT_DIR/$name" "$RELEASE_PREFIX/$name" "$digest"
done < "$MANIFEST"

for tool in deploy-alert-rules.sh validate-alert-rules.sh; do
  digest=$(checksum "$SCRIPT_DIR/$tool")
  printf '%s  %s\n' "$digest" "$tool" >> "$TEMP_DIR/TOOL_SHA256SUMS"
  put_immutable "$SCRIPT_DIR/$tool" "$RELEASE_PREFIX/tools/$tool" "$digest"
done

# The checksum manifest is uploaded last. Its presence means every referenced object upload completed.
digest=$(checksum "$TEMP_DIR/SHA256SUMS")
put_immutable "$TEMP_DIR/SHA256SUMS" "$RELEASE_PREFIX/SHA256SUMS" "$digest"
digest=$(checksum "$TEMP_DIR/TOOL_SHA256SUMS")
put_immutable "$TEMP_DIR/TOOL_SHA256SUMS" "$RELEASE_PREFIX/tools/SHA256SUMS" "$digest"

echo "published immutable alert rule release:"
echo "$RELEASE_URI"
