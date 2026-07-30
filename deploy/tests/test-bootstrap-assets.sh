#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PUBLISHER="$REPO_ROOT/deploy/scripts/publish-bootstrap-assets.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

"$PUBLISHER" --check

VALUES="$WORK/values.json"
cat > "$VALUES" <<'JSON'
{
  "dev_was_private_ip": "10.0.10.10",
  "monitoring_private_ip": "10.0.10.11",
  "dev_mysql_private_ip": "10.0.10.12",
  "redis_private_ip": "10.0.10.13",
  "elk_private_ip": "10.0.10.14",
  "dev_api_domain": "dev-api.example.com"
}
JSON

DRY_RUN=$("$PUBLISHER" --bucket example-bootstrap-bucket --values "$VALUES")
printf '%s\n' "$DRY_RUN" | grep -q 'DRY-RUN .*bootstrap/schema.sql'
printf '%s\n' "$DRY_RUN" | grep -q 'bootstrap/monitoring/prometheus/targets/application.yml'
printf '%s\n' "$DRY_RUN" | grep -q 'dry-run only'

cp "$REPO_ROOT/deploy/bootstrap-assets.txt" "$WORK/invalid-manifest"
printf '%s\n' 'static|deploy/elk/docker-compose.yml|bootstrap/duplicate.yml' >> "$WORK/invalid-manifest"
if BOOTSTRAP_MANIFEST_PATH="$WORK/invalid-manifest" "$PUBLISHER" --check >/dev/null 2>&1; then
  fail "duplicate source was accepted"
fi

sed '/^exclude|deploy\/monitoring\/README.md|/d' \
  "$REPO_ROOT/deploy/bootstrap-assets.txt" > "$WORK/missing-manifest"
if BOOTSTRAP_MANIFEST_PATH="$WORK/missing-manifest" "$PUBLISHER" --check >/dev/null 2>&1; then
  fail "missing classification was accepted"
fi

printf '%s\n' 'static|../outside|bootstrap/outside' > "$WORK/traversal-manifest"
if BOOTSTRAP_MANIFEST_PATH="$WORK/traversal-manifest" "$PUBLISHER" --check >/dev/null 2>&1; then
  fail "path traversal was accepted"
fi

cp "$REPO_ROOT/deploy/bootstrap-assets.txt" "$WORK/key-traversal-manifest"
sed 's|bootstrap/schema.sql|bootstrap/../schema.sql|' \
  "$WORK/key-traversal-manifest" > "$WORK/key-traversal-manifest.tmp"
mv "$WORK/key-traversal-manifest.tmp" "$WORK/key-traversal-manifest"
if BOOTSTRAP_MANIFEST_PATH="$WORK/key-traversal-manifest" "$PUBLISHER" --check >/dev/null 2>&1; then
  fail "upload key traversal was accepted"
fi

if "$PUBLISHER" --check --apply >/dev/null 2>&1; then
  fail "conflicting modes were accepted"
fi

echo "bootstrap asset tests passed"
