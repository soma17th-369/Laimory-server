#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MONITORING_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
ALERT_DIR=${1:-"$MONITORING_DIR/grafana/provisioning/alerting"}
MANIFEST=${2:-"$MONITORING_DIR/grafana/alert-rule-files.txt"}

fail() {
  echo "alert rule validation failed: $*" >&2
  exit 1
}

[[ -d $ALERT_DIR ]] || fail "directory does not exist: $ALERT_DIR"
[[ -f $MANIFEST && ! -L $MANIFEST ]] || fail "manifest is missing or is a symlink: $MANIFEST"

temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT
expected_name_file="$temp_dir/expected-names"
uid_file="$temp_dir/uids"
group_file="$temp_dir/groups"
: > "$expected_name_file"
: > "$uid_file"
: > "$group_file"

declare -a expected_files=()
while IFS= read -r name || [[ -n $name ]]; do
  [[ -n $name ]] || continue
  [[ $name =~ ^[a-z0-9][a-z0-9-]*-rules\.yml$ ]] ||
    fail "invalid manifest entry: $name"
  ! grep -Fqx "$name" "$expected_name_file" || fail "duplicate manifest entry: $name"
  printf '%s\n' "$name" >> "$expected_name_file"
  expected_files+=("$name")
done < "$MANIFEST"

(( ${#expected_files[@]} > 0 )) || fail "manifest is empty"

shopt -s nullglob
actual_paths=("$ALERT_DIR"/*-rules.yml)
shopt -u nullglob
(( ${#actual_paths[@]} == ${#expected_files[@]} )) ||
  fail "expected ${#expected_files[@]} rule files, found ${#actual_paths[@]}"

for path in "${actual_paths[@]}"; do
  name=${path##*/}
  grep -Fqx "$name" "$expected_name_file" || fail "unmanaged rule file: $name"
done

for name in "${expected_files[@]}"; do
  path="$ALERT_DIR/$name"
  [[ -f $path && ! -L $path && -s $path ]] || fail "missing, empty, or symlinked rule file: $name"
  [[ $(sed -n '1p' "$path") == "apiVersion: 1" ]] || fail "$name has an invalid apiVersion"
  grep -Fqx "groups:" "$path" || fail "$name has no groups root"
  grep -Eq '^    name: [a-z0-9][a-z0-9-]*$' "$path" || fail "$name has no named rule group"
  grep -Eq '^      - uid: [A-Za-z0-9_-]+$' "$path" || fail "$name has no alert UID"
  sed -nE 's/^      - uid: ([A-Za-z0-9_-]+)$/\1/p' "$path" >> "$uid_file"
  sed -nE 's/^    name: ([a-z0-9][a-z0-9-]*)$/\1/p' "$path" >> "$group_file"
done

duplicate_uids=$(sort "$uid_file" | uniq -d)
[[ -z $duplicate_uids ]] || fail "duplicate alert UID(s): ${duplicate_uids//$'\n'/, }"

duplicate_groups=$(sort "$group_file" | uniq -d)
[[ -z $duplicate_groups ]] || fail "duplicate rule group(s): ${duplicate_groups//$'\n'/, }"

echo "validated ${#expected_files[@]} alert rule files, $(wc -l < "$group_file" | tr -d ' ') groups, and $(wc -l < "$uid_file" | tr -d ' ') unique UIDs"
