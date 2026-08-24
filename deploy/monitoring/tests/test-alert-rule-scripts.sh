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
  "$MONITORING_ROOT/secrets" \
  "$MONITORING_ROOT/scripts" \
  "$FAKE_S3_ROOT" \
  "$FAKE_BIN" \
  "$CURL_CALL_DIR"
chmod 0700 "$MONITORING_ROOT/grafana/provisioning/alerting"

install -m 0755 "$MONITORING_DIR/scripts/validate-alert-rules.sh" \
  "$MONITORING_ROOT/scripts/validate-alert-rules.sh"

# Application ERROR notification is a numeric Elasticsearch alert with no raw log data in the payload.
ruby -ryaml -e '
  document = begin
    YAML.unsafe_load_file(ARGV[0])
  rescue NoMethodError
    YAML.load_file(ARGV[0])
  end
  rules = document.fetch("groups").flat_map { |group| group.fetch("rules") }
  rule = rules.find { |candidate| candidate["uid"] == "laimory_application_error_log" }
  abort "application ERROR alert missing" unless rule
  abort "application ERROR alert must fire without a pending period" unless rule["for"] == "0s"
  abort "application ERROR alert must be warning severity" unless rule.dig("labels", "severity") == "warning"
  abort "application ERROR alert must not pin an environment label" if rule.dig("labels", "environment")
  abort "application ERROR alert must treat empty search results as OK" unless rule["noDataState"] == "OK"

  query = rule.fetch("data").find { |item| item["refId"] == "A" }
  abort "Elasticsearch query missing" unless query["datasourceUid"] == "elasticsearch-dev"
  abort "ERROR query contract changed" unless query.dig("model", "query") == "service:laimory AND level:ERROR"
  abort "ERROR alert must count documents" unless query.dig("model", "metrics") == [{ "id" => "1", "type" => "count" }]
  terms = query.dig("model", "bucketAggs", 0)
  abort "ERROR alert must group by environment first" unless terms == {
    "field" => "environment",
    "id" => "3",
    "settings" => { "min_doc_count" => "1", "order" => "desc", "orderBy" => "_term", "size" => "10" },
    "type" => "terms",
  }
  histogram = query.dig("model", "bucketAggs", 1)
  abort "ERROR alert must use a one-minute date histogram as the last bucket aggregation" unless histogram == {
    "field" => "@timestamp",
    "id" => "2",
    "settings" => { "interval" => "1m", "min_doc_count" => 0 },
    "type" => "date_histogram",
  }

  reduce = rule.fetch("data").find { |item| item["refId"] == "B" }
  abort "ERROR alert must sum the five-minute window" unless reduce.dig("model", "reducer") == "sum"
  threshold = rule.fetch("data").find { |item| item["refId"] == "C" }
  evaluator = threshold.dig("model", "conditions", 0, "evaluator")
  abort "ERROR alert threshold must be greater than zero" unless evaluator == { "params" => [0], "type" => "gt" }

  summary = rule.dig("annotations", "summary").to_s
  description = rule.dig("annotations", "description").to_s
  abort "notification must not interpolate raw query values" if (summary + description).include?("{{")
  url = rule.dig("annotations", "runbook_url").to_s
  abort "Kibana ERROR investigation link missing" unless url.start_with?("https://dev.laimory.app/kibana/app/discover#/")
  abort "Kibana data view contract missing" unless url.include?("8e3c574e-45cc-430f-ae74-b91c277b8249")
  abort "Kibana ERROR filter missing" unless url.include?("level%3A%22ERROR%22")
' "$MONITORING_DIR/grafana/provisioning/alerting/application-rules.yml"

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
config_file=
expect_config=false
for argument in "$@"; do
  [[ $argument != *"$GRAFANA_TEST_PASSWORD"* ]] || {
    echo "Grafana password leaked in curl arguments" >&2
    exit 1
  }
  if [[ $expect_config == true ]]; then
    config_file=$argument
    expect_config=false
  elif [[ $argument == --config ]]; then
    expect_config=true
  fi
done
[[ -n $config_file && -f $config_file ]] || {
  echo "curl config file missing" >&2
  exit 1
}
grep -Fq "$GRAFANA_TEST_PASSWORD" "$config_file" || {
  echo "Grafana credential missing from protected curl config" >&2
  exit 1
}
grep -Fq "user = \"laimory:$GRAFANA_TEST_PASSWORD\"" "$config_file" || {
  echo "Grafana admin username does not match the repository default" >&2
  exit 1
}
url=${!#}
if [[ $url == "$GRAFANA_TEST_RULE_URL/"* ]]; then
  uid=${url##*/}
  [[ ${FAKE_CURL_MISSING_UID:-} != "$uid" ]] || exit 22
  grep -R -Fq "uid: $uid" "$MONITORING_ROOT/grafana/provisioning/alerting"
  exit
fi
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

GRAFANA_TEST_PASSWORD=alert-deploy-secret-sentinel
printf '%s' "$GRAFANA_TEST_PASSWORD" \
  > "$MONITORING_ROOT/secrets/grafana_admin_password"
chmod 0400 "$MONITORING_ROOT/secrets/grafana_admin_password"

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
    GRAFANA_TEST_PASSWORD="$GRAFANA_TEST_PASSWORD" \
    GRAFANA_RELOAD_URL=http://localhost/reload \
    GRAFANA_PROVISIONING_RULE_URL=http://localhost/rules \
    GRAFANA_TEST_RULE_URL=http://localhost/rules \
    FAKE_CURL_MISSING_UID="${FAKE_CURL_MISSING_UID:-}" \
    "$MONITORING_DIR/scripts/deploy-alert-rules.sh" \
    "s3://test-bucket/bootstrap/monitoring/releases/alert-rules/$release_id"
}

directory_mode() {
  ruby -e 'printf "%o\n", File.stat(ARGV[0]).mode & 0777' "$1"
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
test "$(directory_mode "$MONITORING_ROOT/grafana/provisioning/alerting")" = 755

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
test "$(directory_mode "$MONITORING_ROOT/grafana/provisioning/alerting")" = 755

# A reload HTTP 200 is rejected when Grafana does not expose an expected release UID.
rm -f "$CURL_CALL_DIR"/*
THIRD_RELEASE=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
write_release "$THIRD_RELEASE" missing_after_reload
if FAKE_CURL_MISSING_UID=missing_after_reload run_deploy "$THIRD_RELEASE"; then
  echo "deployment without a provisioned release UID unexpectedly succeeded" >&2
  exit 1
fi

grep -Fq "uid: new_rule" "$MONITORING_ROOT/grafana/provisioning/alerting/test-rules.yml"
grep -Fq "$FIRST_RELEASE" "$MONITORING_ROOT/grafana/alert-rule-release"
grep -Fq "uid: new_rule" "$CURL_CALL_DIR/delete-1.yml"
grep -Fq "uid: missing_after_reload" "$CURL_CALL_DIR/delete-2.yml"
test ! -e "$MONITORING_ROOT/grafana/provisioning/alerting/laimory-alert-rule-deletes.yml"
test "$(directory_mode "$MONITORING_ROOT/grafana/provisioning/alerting")" = 755

# Publishing writes rule and root-tool checksums under one immutable commit prefix.
PUBLISH_BIN="$TEST_DIR/publish-bin"
PUBLISH_ROOT="$TEST_DIR/published"
PUBLISH_RELEASE=cccccccccccccccccccccccccccccccccccccccc
mkdir -p "$PUBLISH_BIN" "$PUBLISH_ROOT"
cat > "$PUBLISH_BIN/git" <<'SCRIPT'
#!/usr/bin/env bash
case " $* " in
  *" status --porcelain "*) exit 0 ;;
  *" rev-parse --verify HEAD "*) printf '%s\n' "$PUBLISH_RELEASE_ID"; exit 0 ;;
esac
exit 1
SCRIPT
cat > "$PUBLISH_BIN/aws" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$PUBLISH_AWS_LOG"

while [[ $# -gt 0 && $1 != s3api ]]; do
  case $1 in
    --region|--profile) shift 2 ;;
    *) echo "unexpected global aws argument: $1" >&2; exit 2 ;;
  esac
done
[[ ${1:-} == s3api && -n ${2:-} ]] || exit 2
operation=$2
shift 2

bucket=
key=
body=
if_none_match=
output=
while [[ $# -gt 0 ]]; do
  case $1 in
    --bucket) bucket=$2; shift 2 ;;
    --key) key=$2; shift 2 ;;
    --body) body=$2; shift 2 ;;
    --if-none-match) if_none_match=$2; shift 2 ;;
    --*) echo "unexpected s3api argument: $1" >&2; exit 2 ;;
    *) output=$1; shift ;;
  esac
done

[[ $bucket == test-bucket && -n $key ]] || exit 2
destination="$PUBLISH_ROOT/$key"
case $operation in
  put-object)
    [[ -n $body && $if_none_match == '*' ]] || exit 2
    [[ ! -e $destination ]] || exit 1
    mkdir -p "${destination%/*}"
    cp "$body" "$destination"
    ;;
  get-object)
    [[ -n $output && -f $destination ]] || exit 1
    cp "$destination" "$output"
    ;;
  *)
    echo "unexpected s3api operation: $operation" >&2
    exit 2
    ;;
esac
SCRIPT
chmod 0755 "$PUBLISH_BIN/git" "$PUBLISH_BIN/aws"

PUBLISH_AWS_LOG="$TEST_DIR/publish-aws.log"
PATH="$PUBLISH_BIN:$PATH" PUBLISH_ROOT="$PUBLISH_ROOT" PUBLISH_RELEASE_ID="$PUBLISH_RELEASE" \
  PUBLISH_AWS_LOG="$PUBLISH_AWS_LOG" \
  "$MONITORING_DIR/scripts/publish-alert-rules.sh" test-bucket >/dev/null
PUBLISHED_PREFIX="$PUBLISH_ROOT/bootstrap/monitoring/releases/alert-rules/$PUBLISH_RELEASE"
test "$(wc -l < "$PUBLISHED_PREFIX/SHA256SUMS" | tr -d ' ')" = "9"
test "$(wc -l < "$PUBLISHED_PREFIX/tools/SHA256SUMS" | tr -d ' ')" = "2"
test -f "$PUBLISHED_PREFIX/infrastructure-rules.yml"
test -f "$PUBLISHED_PREFIX/backup-rules.yml"
test -f "$PUBLISHED_PREFIX/tools/deploy-alert-rules.sh"
! grep -q -- '--profile' "$PUBLISH_AWS_LOG"
grep -q -- 's3api put-object' "$PUBLISH_AWS_LOG"
grep -q -- '--if-none-match \\*' "$PUBLISH_AWS_LOG"

# 같은 SHA와 같은 bytes 재시도는 기존 object를 검증하고 성공한다.
: > "$PUBLISH_AWS_LOG"
PATH="$PUBLISH_BIN:$PATH" PUBLISH_ROOT="$PUBLISH_ROOT" PUBLISH_RELEASE_ID="$PUBLISH_RELEASE" \
  PUBLISH_AWS_LOG="$PUBLISH_AWS_LOG" \
  "$MONITORING_DIR/scripts/publish-alert-rules.sh" test-bucket >/dev/null
grep -q -- 's3api get-object' "$PUBLISH_AWS_LOG"

# 같은 SHA가 다른 bytes를 가리키면 기존 object를 덮어쓰지 않고 실패한다.
printf '%s\n' 'different bytes' > "$PUBLISHED_PREFIX/infrastructure-rules.yml"
if PATH="$PUBLISH_BIN:$PATH" PUBLISH_ROOT="$PUBLISH_ROOT" PUBLISH_RELEASE_ID="$PUBLISH_RELEASE" \
  PUBLISH_AWS_LOG="$PUBLISH_AWS_LOG" \
  "$MONITORING_DIR/scripts/publish-alert-rules.sh" test-bucket >/dev/null 2>&1; then
  echo "immutable release collision unexpectedly succeeded" >&2
  exit 1
fi
grep -Fxq 'different bytes' "$PUBLISHED_PREFIX/infrastructure-rules.yml"

# 운영자 로컬 fallback은 명시한 profile을 계속 전달한다.
: > "$PUBLISH_AWS_LOG"
PROFILE_RELEASE=dddddddddddddddddddddddddddddddddddddddd
PATH="$PUBLISH_BIN:$PATH" PUBLISH_ROOT="$PUBLISH_ROOT" PUBLISH_RELEASE_ID="$PROFILE_RELEASE" \
  PUBLISH_AWS_LOG="$PUBLISH_AWS_LOG" \
  "$MONITORING_DIR/scripts/publish-alert-rules.sh" test-bucket sandbox >/dev/null
grep -q -- '--profile sandbox' "$PUBLISH_AWS_LOG"

echo "alert rule script tests passed"
