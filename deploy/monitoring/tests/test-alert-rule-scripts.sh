#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MONITORING_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

MONITORING_ROOT="$TEST_DIR/monitoring"
FAKE_S3_ROOT="$TEST_DIR/s3"
FAKE_BIN="$TEST_DIR/bin"
CURL_CALL_DIR="$TEST_DIR/curl-calls"
mkdir -p \
  "$MONITORING_ROOT/grafana/provisioning/alerting" \
  "$MONITORING_ROOT/grafana" \
  "$MONITORING_ROOT/scripts" \
  "$FAKE_S3_ROOT" \
  "$FAKE_BIN" \
  "$CURL_CALL_DIR"

install -m 0755 "$MONITORING_DIR/scripts/validate-alert-rules.sh" \
  "$MONITORING_ROOT/scripts/validate-alert-rules.sh"

cat > "$FAKE_BIN/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
[[ $1 == s3 && $2 == cp ]]
source_uri=$3
destination=$4
relative=${source_uri#s3://test-bucket/bootstrap/monitoring/releases/alert-rules/}
cp "$FAKE_S3_ROOT/$relative" "$destination"
SCRIPT

cat > "$FAKE_BIN/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
count_file="$CURL_CALL_DIR/count"
count=0
[[ -f $count_file ]] && count=$(cat "$count_file")
count=$((count + 1))
printf '%s\n' "$count" > "$count_file"
delete_file="$MONITORING_ROOT/grafana/provisioning/alerting/laimory-alert-rule-deletes.yml"
[[ ! -f $delete_file ]] || cp "$delete_file" "$CURL_CALL_DIR/delete-$count.yml"
if [[ ${FAKE_CURL_FAIL_FIRST:-false} == true && $count -eq 1 ]]; then
  exit 22
fi
SCRIPT

cat > "$FAKE_BIN/flock" <<'SCRIPT'
#!/usr/bin/env bash
exit 0
SCRIPT

chmod 0755 "$FAKE_BIN/aws" "$FAKE_BIN/curl" "$FAKE_BIN/flock"

write_rule() {
  local directory=$1
  local filename=$2
  local uid=$3
  local group_name=${uid//_/-}
  cat > "$directory/$filename" <<YAML
apiVersion: 1

groups:
  - orgId: 1
    name: test-$group_name
    folder: Laimory
    interval: 1m
    rules:
      - uid: $uid
        title: $uid
        condition: A
        data: []
YAML
}

write_release() {
  local release_id=$1
  local uid=$2
  local release_dir="$FAKE_S3_ROOT/$release_id"
  mkdir -p "$release_dir"
  write_rule "$release_dir" test-rules.yml "$uid"
  (
    cd "$release_dir"
    sha256sum test-rules.yml > SHA256SUMS
  )
}

run_deploy() {
  local release_id=$1
  PATH="$FAKE_BIN:$PATH" \
    MONITORING_ROOT="$MONITORING_ROOT" \
    FAKE_S3_ROOT="$FAKE_S3_ROOT" \
    CURL_CALL_DIR="$CURL_CALL_DIR" \
    GRAFANA_RELOAD_URL=http://localhost/reload \
    "$MONITORING_DIR/scripts/deploy-alert-rules.sh" \
    "s3://test-bucket/bootstrap/monitoring/releases/alert-rules/$release_id"
}

# Legacy single-file provisioning migrates to the manifest-owned files and explicitly deletes removed UIDs.
write_rule "$MONITORING_ROOT/grafana/provisioning/alerting" rules.yml old_rule
FIRST_RELEASE=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
write_release "$FIRST_RELEASE" new_rule
run_deploy "$FIRST_RELEASE"

test -f "$MONITORING_ROOT/grafana/provisioning/alerting/test-rules.yml"
test ! -e "$MONITORING_ROOT/grafana/provisioning/alerting/rules.yml"
test ! -e "$MONITORING_ROOT/grafana/provisioning/alerting/laimory-alert-rule-deletes.yml"
grep -Fq "uid: old_rule" "$CURL_CALL_DIR/delete-1.yml"
grep -Fq "$FIRST_RELEASE" "$MONITORING_ROOT/grafana/alert-rule-release"

# A rejected reload restores the previous files and deletes any UID introduced by the failed release.
rm -f "$CURL_CALL_DIR"/*
SECOND_RELEASE=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
write_release "$SECOND_RELEASE" rejected_rule
if FAKE_CURL_FAIL_FIRST=true run_deploy "$SECOND_RELEASE"; then
  echo "rejected Grafana reload unexpectedly succeeded" >&2
  exit 1
fi

grep -Fq "uid: new_rule" "$MONITORING_ROOT/grafana/provisioning/alerting/test-rules.yml"
grep -Fq "$FIRST_RELEASE" "$MONITORING_ROOT/grafana/alert-rule-release"
grep -Fq "uid: new_rule" "$CURL_CALL_DIR/delete-1.yml"
grep -Fq "uid: rejected_rule" "$CURL_CALL_DIR/delete-2.yml"
test ! -e "$MONITORING_ROOT/grafana/provisioning/alerting/laimory-alert-rule-deletes.yml"

# Publishing writes rule and root-tool checksums under one immutable commit prefix.
PUBLISH_BIN="$TEST_DIR/publish-bin"
PUBLISH_ROOT="$TEST_DIR/published"
PUBLISH_RELEASE=cccccccccccccccccccccccccccccccccccccccc
mkdir -p "$PUBLISH_BIN" "$PUBLISH_ROOT"
cat > "$PUBLISH_BIN/git" <<SCRIPT
#!/usr/bin/env bash
case " \$* " in
  *" status --porcelain "*) exit 0 ;;
  *" rev-parse --verify HEAD "*) printf '%s\\n' "$PUBLISH_RELEASE"; exit 0 ;;
esac
exit 1
SCRIPT
cat > "$PUBLISH_BIN/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
[[ $1 == s3 && $2 == cp ]]
source_path=$3
destination_uri=$4
relative=${destination_uri#s3://test-bucket/}
mkdir -p "$PUBLISH_ROOT/${relative%/*}"
cp "$source_path" "$PUBLISH_ROOT/$relative"
SCRIPT
chmod 0755 "$PUBLISH_BIN/git" "$PUBLISH_BIN/aws"

PATH="$PUBLISH_BIN:$PATH" PUBLISH_ROOT="$PUBLISH_ROOT" \
  "$MONITORING_DIR/scripts/publish-alert-rules.sh" test-bucket sandbox >/dev/null
PUBLISHED_PREFIX="$PUBLISH_ROOT/bootstrap/monitoring/releases/alert-rules/$PUBLISH_RELEASE"
test "$(wc -l < "$PUBLISHED_PREFIX/SHA256SUMS" | tr -d ' ')" = "8"
test "$(wc -l < "$PUBLISHED_PREFIX/tools/SHA256SUMS" | tr -d ' ')" = "2"
test -f "$PUBLISHED_PREFIX/infrastructure-rules.yml"
test -f "$PUBLISHED_PREFIX/tools/deploy-alert-rules.sh"

echo "alert rule script tests passed"
