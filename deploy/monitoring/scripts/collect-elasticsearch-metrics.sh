#!/bin/bash
# 기존 Grafana용 read-only Elasticsearch API key로 cluster 상태와 dev log 수집 시각을 노출한다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_elasticsearch.prom"
readonly API_KEY_FILE=/opt/laimory-monitoring/secrets/elasticsearch_api_key

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_elasticsearch.XXXXXX")
curl_config=$(mktemp)
chmod 0600 "$curl_config"
trap 'rm -f "$tmp_file" "$curl_config"' EXIT

write_failure() {
  cat > "$tmp_file" <<EOF
# HELP laimory_elasticsearch_up Whether the latest Elasticsearch health collection completed.
# TYPE laimory_elasticsearch_up gauge
laimory_elasticsearch_up 0
# HELP laimory_elasticsearch_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_elasticsearch_last_attempt_unixtime_seconds gauge
laimory_elasticsearch_last_attempt_unixtime_seconds $(date +%s)
EOF
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_failure
  echo "Elasticsearch metric collection failed" >&2
  exit 1
}

[ -n "${ELASTICSEARCH_URL:-}" ] || fail
[ -s "$API_KEY_FILE" ] || fail
api_key=$(tr -d '\r\n' < "$API_KEY_FILE")
[ -n "$api_key" ] || fail
# Bash builtin printf로만 config를 쓰므로 secret은 curl argv나 /proc cmdline에 노출되지 않는다.
printf 'header = "Authorization: ApiKey %s"\n' "$api_key" > "$curl_config"
unset api_key

health=$(curl -fsS --connect-timeout 3 --max-time 10 \
  --config "$curl_config" \
  "$ELASTICSEARCH_URL/_cluster/health?filter_path=status,active_shards_percent_as_number,unassigned_shards,number_of_pending_tasks") \
  || fail
status=$(jq -er '.status | select(. == "green" or . == "yellow" or . == "red")' \
  <<<"$health") || fail
active_ratio=$(jq -er '.active_shards_percent_as_number / 100' <<<"$health") || fail
unassigned=$(jq -er '.unassigned_shards' <<<"$health") || fail
pending=$(jq -er '.number_of_pending_tasks' <<<"$health") || fail

latest=$(curl -fsS --connect-timeout 3 --max-time 10 \
  --config "$curl_config" -H 'Content-Type: application/json' \
  -X POST "$ELASTICSEARCH_URL/laimory-dev-*/_search" \
  -d '{"size":1,"sort":[{"@timestamp":{"order":"desc"}}],"_source":false,"fields":[{"field":"@timestamp","format":"epoch_millis"}]}') || fail
latest_timestamp=$(jq -r \
  '(.hits.hits[0].fields["@timestamp"][0] // null)
   | if . == null then "NaN" else (tonumber / 1000) end' <<<"$latest") || fail

green=0
yellow=0
red=0
case "$status" in
  green) green=1 ;;
  yellow) yellow=1 ;;
  red) red=1 ;;
esac

cat > "$tmp_file" <<EOF
# HELP laimory_elasticsearch_up Whether the latest Elasticsearch health collection completed.
# TYPE laimory_elasticsearch_up gauge
laimory_elasticsearch_up 1
# HELP laimory_elasticsearch_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_elasticsearch_last_attempt_unixtime_seconds gauge
laimory_elasticsearch_last_attempt_unixtime_seconds $(date +%s)
# HELP laimory_elasticsearch_cluster_status Elasticsearch cluster health as bounded status labels.
# TYPE laimory_elasticsearch_cluster_status gauge
laimory_elasticsearch_cluster_status{status="green"} $green
laimory_elasticsearch_cluster_status{status="yellow"} $yellow
laimory_elasticsearch_cluster_status{status="red"} $red
# HELP laimory_elasticsearch_active_shards_ratio Fraction of active Elasticsearch shards.
# TYPE laimory_elasticsearch_active_shards_ratio gauge
laimory_elasticsearch_active_shards_ratio $active_ratio
# HELP laimory_elasticsearch_unassigned_shards Current unassigned Elasticsearch shards.
# TYPE laimory_elasticsearch_unassigned_shards gauge
laimory_elasticsearch_unassigned_shards $unassigned
# HELP laimory_elasticsearch_pending_tasks Current pending Elasticsearch cluster tasks.
# TYPE laimory_elasticsearch_pending_tasks gauge
laimory_elasticsearch_pending_tasks $pending
# HELP laimory_elasticsearch_latest_log_timestamp_seconds Timestamp of the latest indexed dev application log.
# TYPE laimory_elasticsearch_latest_log_timestamp_seconds gauge
laimory_elasticsearch_latest_log_timestamp_seconds $latest_timestamp
EOF

chmod 0644 "$tmp_file"
mv -f "$tmp_file" "$OUTPUT_FILE"
rm -f "$curl_config"
trap - EXIT
