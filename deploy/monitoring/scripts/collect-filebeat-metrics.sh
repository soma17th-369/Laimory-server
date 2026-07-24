#!/bin/bash
# WAS의 loopback-only Filebeat HTTP stats를 node_exporter textfile로 변환한다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_filebeat.prom"

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_filebeat.XXXXXX")
trap 'rm -f "$tmp_file"' EXIT

write_failure() {
  cat > "$tmp_file" <<EOF
# HELP laimory_filebeat_up Whether the latest Filebeat stats collection completed.
# TYPE laimory_filebeat_up gauge
laimory_filebeat_up 0
# HELP laimory_filebeat_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_filebeat_last_attempt_unixtime_seconds gauge
laimory_filebeat_last_attempt_unixtime_seconds $(date +%s)
EOF
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

stats=$(curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:5066/stats) || {
  write_failure
  exit 1
}

number() {
  jq -er "$1 | numbers" <<<"$stats"
}

output_total=$(number '.libbeat.output.events.total') || { write_failure; exit 1; }
output_acked=$(number '.libbeat.output.events.acked') || { write_failure; exit 1; }
output_failed=$(number '.libbeat.output.events.failed') || { write_failure; exit 1; }
output_dropped=$(number '.libbeat.output.events.dropped') || { write_failure; exit 1; }
output_active=$(number '.libbeat.output.events.active') || { write_failure; exit 1; }
queue_ratio=$(number '.libbeat.pipeline.queue.filled.pct') || { write_failure; exit 1; }
events_active=$(number '.filebeat.events.active') || { write_failure; exit 1; }
harvesters=$(number '.filebeat.harvester.open_files') || { write_failure; exit 1; }

cat > "$tmp_file" <<EOF
# HELP laimory_filebeat_up Whether the latest Filebeat stats collection completed.
# TYPE laimory_filebeat_up gauge
laimory_filebeat_up 1
# HELP laimory_filebeat_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_filebeat_last_attempt_unixtime_seconds gauge
laimory_filebeat_last_attempt_unixtime_seconds $(date +%s)
# HELP laimory_filebeat_output_events_total Filebeat output events by bounded result.
# TYPE laimory_filebeat_output_events_total counter
laimory_filebeat_output_events_total{result="total"} $output_total
laimory_filebeat_output_events_total{result="acked"} $output_acked
laimory_filebeat_output_events_total{result="failed"} $output_failed
laimory_filebeat_output_events_total{result="dropped"} $output_dropped
# HELP laimory_filebeat_output_events_active Current active Filebeat output events.
# TYPE laimory_filebeat_output_events_active gauge
laimory_filebeat_output_events_active $output_active
# HELP laimory_filebeat_queue_filled_ratio Fraction of the Filebeat internal queue currently filled.
# TYPE laimory_filebeat_queue_filled_ratio gauge
laimory_filebeat_queue_filled_ratio $queue_ratio
# HELP laimory_filebeat_events_active Current active Filebeat events.
# TYPE laimory_filebeat_events_active gauge
laimory_filebeat_events_active $events_active
# HELP laimory_filebeat_harvesters_open Current open Filebeat harvesters.
# TYPE laimory_filebeat_harvesters_open gauge
laimory_filebeat_harvesters_open $harvesters
EOF

chmod 0644 "$tmp_file"
mv -f "$tmp_file" "$OUTPUT_FILE"
trap - EXIT
