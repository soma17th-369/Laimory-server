#!/bin/bash
# Upstash Developer API의 read-only key로 Redis DB의 이번 달 누적 명령 수를 노출한다(#653).
# 무료 플랜은 월 50만 명령이 상한이고 자체 알림·Prometheus 연동이 없어, 이 값으로 Grafana 경보를 건다.
# Developer API 조회는 Redis 명령이 아니므로 그 한도를 소비하지 않는다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_upstash.prom"
readonly CREDENTIALS_FILE=/opt/laimory-monitoring/secrets/upstash_api_credentials

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_upstash.XXXXXX")
curl_config=$(mktemp)
chmod 0600 "$curl_config"
trap 'rm -f "$tmp_file" "$curl_config"' EXIT

write_failure() {
  cat > "$tmp_file" <<EOF
# HELP laimory_upstash_up Whether the latest Upstash usage collection completed.
# TYPE laimory_upstash_up gauge
laimory_upstash_up 0
# HELP laimory_upstash_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_upstash_last_attempt_unixtime_seconds gauge
laimory_upstash_last_attempt_unixtime_seconds $(date +%s)
EOF
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_failure
  echo "Upstash usage collection failed" >&2
  exit 1
}

[[ "${UPSTASH_REDIS_DATABASE_ID:-}" =~ ^[A-Za-z0-9-]+$ ]] || fail
[ -s "$CREDENTIALS_FILE" ] || fail
# 파일 한 줄 = "<계정 email>:<read-only API key>" (Developer API Basic auth).
credentials=$(tr -d '\r\n' < "$CREDENTIALS_FILE")
[[ "$credentials" == ?*:?* && "$credentials" != *'"'* ]] || fail
# Bash builtin printf로만 config를 쓰므로 secret은 curl argv나 /proc cmdline에 노출되지 않는다.
printf 'user = "%s"\n' "$credentials" > "$curl_config"
unset credentials

stats=$(curl -fsS --connect-timeout 5 --max-time 20 \
  --config "$curl_config" \
  "https://api.upstash.com/v2/redis/stats/$UPSTASH_REDIS_DATABASE_ID") || fail
monthly_requests=$(jq -er '.total_monthly_requests | numbers' <<<"$stats") || fail

cat > "$tmp_file" <<EOF
# HELP laimory_upstash_up Whether the latest Upstash usage collection completed.
# TYPE laimory_upstash_up gauge
laimory_upstash_up 1
# HELP laimory_upstash_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_upstash_last_attempt_unixtime_seconds gauge
laimory_upstash_last_attempt_unixtime_seconds $(date +%s)
# HELP laimory_upstash_monthly_requests Upstash Redis commands counted in the current billing month.
# TYPE laimory_upstash_monthly_requests gauge
laimory_upstash_monthly_requests $monthly_requests
EOF

chmod 0644 "$tmp_file"
mv -f "$tmp_file" "$OUTPUT_FILE"
rm -f "$curl_config"
trap - EXIT
