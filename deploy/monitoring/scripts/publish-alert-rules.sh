#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <backup-bucket> [aws-profile]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage

BACKUP_BUCKET=$1
AWS_PROFILE=${2:-sandbox}
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
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

while IFS= read -r name || [[ -n $name ]]; do
  [[ -n $name ]] || continue
  digest=$(checksum "$ALERT_DIR/$name")
  printf '%s  %s\n' "$digest" "$name" >> "$TEMP_DIR/SHA256SUMS"
  aws s3 cp "$ALERT_DIR/$name" "$RELEASE_URI/$name" \
    --profile "$AWS_PROFILE" --region "$AWS_REGION" --only-show-errors
done < "$MANIFEST"

for tool in deploy-alert-rules.sh validate-alert-rules.sh; do
  digest=$(checksum "$SCRIPT_DIR/$tool")
  printf '%s  %s\n' "$digest" "$tool" >> "$TEMP_DIR/TOOL_SHA256SUMS"
  aws s3 cp "$SCRIPT_DIR/$tool" "$RELEASE_URI/tools/$tool" \
    --profile "$AWS_PROFILE" --region "$AWS_REGION" --only-show-errors
done

# The checksum manifest is uploaded last. Its presence means every referenced object upload completed.
aws s3 cp "$TEMP_DIR/SHA256SUMS" "$RELEASE_URI/SHA256SUMS" \
  --profile "$AWS_PROFILE" --region "$AWS_REGION" --only-show-errors
aws s3 cp "$TEMP_DIR/TOOL_SHA256SUMS" "$RELEASE_URI/tools/SHA256SUMS" \
  --profile "$AWS_PROFILE" --region "$AWS_REGION" --only-show-errors

echo "published immutable alert rule release:"
echo "$RELEASE_URI"
