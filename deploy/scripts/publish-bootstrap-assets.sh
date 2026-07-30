#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MANIFEST=${BOOTSTRAP_MANIFEST_PATH:-"$REPO_ROOT/deploy/bootstrap-assets.txt"}
RENDERER="$REPO_ROOT/deploy/monitoring/scripts/render-prometheus-targets.py"

BUCKET=""
VALUES=""
PROFILE=""
MODE=dry-run
MODE_SELECTED=false

usage() {
  echo "usage: $0 [--check] [--bucket NAME --values FILE [--profile PROFILE] [--apply]]" >&2
}

fail() {
  echo "bootstrap publish rejected: $1" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --check)
      [ "$MODE_SELECTED" = false ] || fail "--check and --apply are mutually exclusive"
      MODE=check
      MODE_SELECTED=true
      shift
      ;;
    --apply)
      [ "$MODE_SELECTED" = false ] || fail "--check and --apply are mutually exclusive"
      MODE=apply
      MODE_SELECTED=true
      shift
      ;;
    --bucket) [ "$#" -ge 2 ] || { usage; exit 2; }; BUCKET=$2; shift 2 ;;
    --values) [ "$#" -ge 2 ] || { usage; exit 2; }; VALUES=$2; shift 2 ;;
    --profile) [ "$#" -ge 2 ] || { usage; exit 2; }; PROFILE=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

[ -f "$MANIFEST" ] || fail "manifest does not exist"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
ENTRIES="$WORK/entries"
SOURCES="$WORK/sources"
UPLOAD_KEYS="$WORK/upload-keys"
TRACKED="$WORK/tracked"
: > "$ENTRIES"
: > "$SOURCES"
: > "$UPLOAD_KEYS"

while IFS='|' read -r kind source destination; do
  case "$kind" in
    ""|\#*) continue ;;
    static|generated|exclude) ;;
    *) fail "unknown kind: $kind" ;;
  esac
  [ -n "$source" ] && [ -n "$destination" ] || fail "every entry needs three fields"
  case "$source" in
    /*|*..*|*//*|*'|'*) fail "unsafe source path: $source" ;;
  esac
  [ -f "$REPO_ROOT/$source" ] || fail "source does not exist: $source"
  printf '%s\n' "$source" >> "$SOURCES"
  printf '%s|%s|%s\n' "$kind" "$source" "$destination" >> "$ENTRIES"

  if [ "$kind" != exclude ]; then
    case "$destination" in
      bootstrap/*) ;;
      *) fail "upload key must stay under bootstrap/: $destination" ;;
    esac
    case "$destination" in
      *..*|*//*|*'|'*) fail "unsafe upload key: $destination" ;;
    esac
    case "$source" in
      *.env|*.pem|*.key|*.p12|*.pfx|*/secrets/*)
        fail "sensitive-looking source cannot be uploaded: $source"
        ;;
    esac
    if [ "$kind" = generated ]; then
      case "$source|$destination" in
        deploy/monitoring/prometheus/application-targets.yml.template\|bootstrap/monitoring/prometheus/targets/application.yml) ;;
        deploy/monitoring/prometheus/node-targets.yml.template\|bootstrap/monitoring/prometheus/targets/node.yml) ;;
        deploy/monitoring/prometheus/probe-targets.yml.template\|bootstrap/monitoring/prometheus/targets/probe.yml) ;;
        *) fail "unknown generated asset mapping: $source -> $destination" ;;
      esac
    fi
    printf '%s\n' "$destination" >> "$UPLOAD_KEYS"
  fi
done < "$MANIFEST"

[ -s "$ENTRIES" ] || fail "manifest is empty"
[ -z "$(sort "$SOURCES" | uniq -d)" ] || fail "a source is classified more than once"
[ -z "$(sort "$UPLOAD_KEYS" | uniq -d)" ] || fail "an upload key is used more than once"

{
  git -C "$REPO_ROOT" ls-files -- src/main/resources/db/schema.sql
  git -C "$REPO_ROOT" ls-files -- deploy/elk
  git -C "$REPO_ROOT" ls-files -- deploy/monitoring
  git -C "$REPO_ROOT" ls-files -- deploy/was/patch_trusted_edge_nginx.py
} | sort -u > "$TRACKED"
sort -u "$SOURCES" > "$WORK/manifest-sources"

if ! diff -u "$TRACKED" "$WORK/manifest-sources" > "$WORK/classification.diff"; then
  cat "$WORK/classification.diff" >&2
  fail "allowlisted tracked files and manifest classification differ"
fi

if [ "$MODE" = check ]; then
  echo "bootstrap asset manifest is complete"
  exit 0
fi

[ -n "$BUCKET" ] || fail "--bucket is required outside --check"
[ -n "$VALUES" ] || fail "--values is required outside --check"
[[ "$BUCKET" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || fail "invalid bucket name"
[ -f "$VALUES" ] || fail "values file does not exist"
if [ -n "$PROFILE" ]; then
  [[ "$PROFILE" =~ ^[A-Za-z0-9_.-]+$ ]] || fail "invalid profile name"
fi

TARGETS="$WORK/targets"
"$RENDERER" --values "$VALUES" --output-dir "$TARGETS"

upload() {
  local source=$1
  local key=$2
  if [ "$MODE" = dry-run ]; then
    printf 'DRY-RUN %s -> s3://%s/%s\n' "$source" "$BUCKET" "$key"
    return
  fi
  local args=(s3 cp "$source" "s3://$BUCKET/$key" --only-show-errors)
  if [ -n "$PROFILE" ]; then
    args+=(--profile "$PROFILE")
  fi
  aws "${args[@]}"
}

while IFS='|' read -r kind source destination; do
  case "$kind" in
    static) upload "$REPO_ROOT/$source" "$destination" ;;
    generated) upload "$TARGETS/$(basename "$destination")" "$destination" ;;
    exclude) ;;
  esac
done < "$ENTRIES"

if [ "$MODE" = dry-run ]; then
  echo "dry-run only; pass --apply after explicit AWS write approval"
else
  echo "bootstrap assets published"
fi
