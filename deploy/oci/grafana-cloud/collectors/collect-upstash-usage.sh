#!/bin/bash
# Upstash Developer API의 read-only key로 prod Redis DB의 이번 달 누적 명령 수를 textfile 지표로 남긴다.
# 무료 플랜은 월 50만 명령이 상한이고 자체 알림이 없어 이 값으로 Grafana Cloud 경보를 건다(AWS #653의 OCI판).
# Developer API 조회는 Redis 명령이 아니므로 그 한도를 소비하지 않는다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/laimory/textfile
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_upstash.prom"
readonly CREDENTIALS_FILE=${UPSTASH_CREDENTIALS_FILE:-/etc/laimory/upstash-api-credentials}

install -d -m 0755 -o root -g root "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_upstash.XXXXXX")
curl_config=$(mktemp)
chmod 0600 "$curl_config"
trap 'rm -f "$tmp_file" "$curl_config"' EXIT

write_metrics() {
  local up=$1 monthly=${2:-}
  {
    echo "# HELP laimory_upstash_up Whether the latest Upstash usage collection completed."
    echo "# TYPE laimory_upstash_up gauge"
    echo "laimory_upstash_up $up"
    echo "# HELP laimory_upstash_last_attempt_unixtime_seconds Unix time of the latest collection attempt."
    echo "# TYPE laimory_upstash_last_attempt_unixtime_seconds gauge"
    echo "laimory_upstash_last_attempt_unixtime_seconds $(date +%s)"
    if [[ -n $monthly ]]; then
      echo "# HELP laimory_upstash_monthly_requests Upstash Redis commands counted in the current billing month."
      echo "# TYPE laimory_upstash_monthly_requests gauge"
      echo "laimory_upstash_monthly_requests $monthly"
    fi
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_metrics 0
  echo "Upstash usage collection failed: $*" >&2
  exit 1
}

[[ "${UPSTASH_REDIS_DATABASE_ID:-}" =~ ^[A-Za-z0-9-]+$ ]] || fail "invalid UPSTASH_REDIS_DATABASE_ID"
[[ -s $CREDENTIALS_FILE ]] || fail "missing credentials file"
# 파일 한 줄 = "<계정 email>:<read-only API key>" (Developer API Basic auth).
credentials=$(tr -d '\r\n' < "$CREDENTIALS_FILE")
[[ "$credentials" == ?*:?* && "$credentials" != *'"'* ]] || fail "malformed credentials"
# builtin printf로만 쓰므로 secret은 curl argv나 /proc cmdline에 나오지 않는다.
printf 'user = "%s"\n' "$credentials" > "$curl_config"
unset credentials

stats=$(curl -fsS --connect-timeout 5 --max-time 20 --config "$curl_config" \
  "https://api.upstash.com/v2/redis/stats/$UPSTASH_REDIS_DATABASE_ID") || fail "stats request failed"
monthly=$(python3 -c 'import json,sys; v=json.load(sys.stdin)["total_monthly_requests"]; assert isinstance(v,(int,float)); print(int(v))' <<<"$stats") \
  || fail "unexpected stats response"
write_metrics 1 "$monthly"
