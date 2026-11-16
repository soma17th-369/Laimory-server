#!/bin/bash
# monitoring EC2 자신의 burst credit와 root EBS 지표를 CloudWatch에서 읽어 node_exporter textfile로 노출한다.
# S3 → R2 사진 복제(#647, 임시)의 실패 지표도 함께 읽는다 — 복제를 정리할 때 이 두 지표와 경보 규칙도 지운다.
set -euo pipefail

readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_aws.prom"
readonly IMDS=http://169.254.169.254/latest
readonly PHOTO_REPLICATOR_FUNCTION=laimory-shared-photo-replicator
readonly PHOTO_REPLICATOR_DLQ=laimory-shared-photo-replicator-dlq

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"
tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_aws.XXXXXX")
trap 'rm -f "$tmp_file"' EXIT

write_failure() {
  cat > "$tmp_file" <<EOF
# HELP laimory_aws_cloudwatch_up Whether the latest CloudWatch collection completed.
# TYPE laimory_aws_cloudwatch_up gauge
laimory_aws_cloudwatch_up 0
# HELP laimory_aws_cloudwatch_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_aws_cloudwatch_last_attempt_unixtime_seconds gauge
laimory_aws_cloudwatch_last_attempt_unixtime_seconds $(date +%s)
EOF
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

fail() {
  write_failure
  echo "CloudWatch metric collection failed" >&2
  exit 1
}

token=$(curl -fsS --connect-timeout 2 --max-time 5 -X PUT \
  "$IMDS/api/token" -H 'X-aws-ec2-metadata-token-ttl-seconds: 300') || fail
instance_id=$(curl -fsS --connect-timeout 2 --max-time 5 \
  -H "X-aws-ec2-metadata-token: $token" "$IMDS/meta-data/instance-id") || fail
region=$(curl -fsS --connect-timeout 2 --max-time 5 \
  -H "X-aws-ec2-metadata-token: $token" "$IMDS/meta-data/placement/region") || fail

instance_json=$(aws ec2 describe-instances --region "$region" --instance-ids "$instance_id" \
  --output json) || fail
root_device=$(jq -er '.Reservations[0].Instances[0].RootDeviceName' \
  <<<"$instance_json") || fail
volume_id=$(jq -er --arg device "$root_device" \
  '.Reservations[0].Instances[0].BlockDeviceMappings[]
   | select(.DeviceName == $device) | .Ebs.VolumeId' <<<"$instance_json") || fail

queries=$(jq -cn --arg instance "$instance_id" --arg volume "$volume_id" \
  --arg replicator "$PHOTO_REPLICATOR_FUNCTION" --arg dlq "$PHOTO_REPLICATOR_DLQ" '
  def metric($id; $namespace; $name; $dimension; $value; $stat):
    {
      Id: $id,
      MetricStat: {
        Metric: {
          Namespace: $namespace,
          MetricName: $name,
          Dimensions: [{Name: $dimension, Value: $value}]
        },
        Period: 300,
        Stat: $stat
      },
      ReturnData: true
    };
  [
    metric("credit"; "AWS/EC2"; "CPUCreditBalance"; "InstanceId"; $instance; "Average"),
    metric("surplus"; "AWS/EC2"; "CPUSurplusCreditsCharged"; "InstanceId"; $instance; "Sum"),
    metric("queue"; "AWS/EBS"; "VolumeQueueLength"; "VolumeId"; $volume; "Average"),
    metric("readops"; "AWS/EBS"; "VolumeReadOps"; "VolumeId"; $volume; "Sum"),
    metric("writeops"; "AWS/EBS"; "VolumeWriteOps"; "VolumeId"; $volume; "Sum"),
    metric("readtime"; "AWS/EBS"; "VolumeTotalReadTime"; "VolumeId"; $volume; "Sum"),
    metric("writetime"; "AWS/EBS"; "VolumeTotalWriteTime"; "VolumeId"; $volume; "Sum"),
    metric("idletime"; "AWS/EBS"; "VolumeIdleTime"; "VolumeId"; $volume; "Sum"),
    metric("replicadlq"; "AWS/SQS"; "ApproximateNumberOfMessagesVisible"; "QueueName"; $dlq; "Maximum"),
    metric("replicadest"; "AWS/Lambda"; "DestinationDeliveryFailures"; "FunctionName"; $replicator; "Sum")
  ]') || fail

# EndTime은 exclusive다. 5분 경계에 맞춰 진행 중인 partial bucket을 제외해야 VolumeIdleTime/300
# busy ratio와 Sum/평균 지표가 같은 완료 구간을 가리킨다.
end_epoch=$(( $(date -u +%s) / 300 * 300 ))
start_epoch=$(( end_epoch - 1200 ))
end_time=$(date -u -d "@$end_epoch" +%Y-%m-%dT%H:%M:%SZ)
start_time=$(date -u -d "@$start_epoch" +%Y-%m-%dT%H:%M:%SZ)
metrics_json=$(aws cloudwatch get-metric-data --region "$region" \
  --start-time "$start_time" --end-time "$end_time" \
  --scan-by TimestampDescending --metric-data-queries "$queries" --output json) || fail

# GetMetricData는 CLI exit 0이어도 query별 PartialData/InternalError를 반환할 수 있다. 기대한
# 10개 query가 모두 Complete인 경우에만 collector 자체를 up으로 기록한다.
jq -e '
  ["credit", "surplus", "queue", "readops", "writeops", "readtime", "writetime", "idletime",
   "replicadlq", "replicadest"]
    as $expected |
  ([.MetricDataResults[]?
    | select(.Id as $id | $expected | index($id))
    | .Id] | unique | length) == ($expected | length)
  and
  ([.MetricDataResults[]?
    | select(.Id as $id | $expected | index($id))]
    | all(.StatusCode == "Complete"))
  and ((.NextToken // "") == "")
' <<<"$metrics_json" >/dev/null || fail

value() {
  jq -r --arg id "$1" \
    '[.MetricDataResults[] | select(.Id == $id) | .Values[0]][0] // "NaN"' \
    <<<"$metrics_json"
}

credit=$(value credit)
surplus=$(value surplus)
queue=$(value queue)
# 복제 지표는 메시지·실패가 없으면 데이터 자체가 없다(DestinationDeliveryFailures는 0도 보내지 않음) → 0으로 본다.
replication_dlq=$(jq -r '[.MetricDataResults[] | select(.Id == "replicadlq") | .Values[0]][0] // 0' <<<"$metrics_json") || fail
replication_destination_failures=$(jq -r '[.MetricDataResults[] | select(.Id == "replicadest") | .Values[0]][0] // 0' <<<"$metrics_json") || fail
read_latency=$(jq -r '
  def value($id): [.MetricDataResults[] | select(.Id == $id) | .Values[0]][0] // null;
  (value("readtime")) as $total | (value("readops")) as $ops |
  if ($total|type) == "number" and ($ops|type) == "number" then
    if $ops > 0 then $total / $ops else 0 end
  else "NaN" end' <<<"$metrics_json") || fail
write_latency=$(jq -r '
  def value($id): [.MetricDataResults[] | select(.Id == $id) | .Values[0]][0] // null;
  (value("writetime")) as $total | (value("writeops")) as $ops |
  if ($total|type) == "number" and ($ops|type) == "number" then
    if $ops > 0 then $total / $ops else 0 end
  else "NaN" end' <<<"$metrics_json") || fail
busy_ratio=$(jq -r '
  def value($id): [.MetricDataResults[] | select(.Id == $id) | .Values[0]][0] // null;
  (value("idletime")) as $idle |
  if ($idle|type) == "number" then ([0, ([1, (1 - ($idle / 300))] | min)] | max)
  else "NaN" end' <<<"$metrics_json") || fail

cat > "$tmp_file" <<EOF
# HELP laimory_aws_cloudwatch_up Whether the latest CloudWatch collection completed.
# TYPE laimory_aws_cloudwatch_up gauge
laimory_aws_cloudwatch_up 1
# HELP laimory_aws_cloudwatch_last_attempt_unixtime_seconds Unix time of the latest collection attempt.
# TYPE laimory_aws_cloudwatch_last_attempt_unixtime_seconds gauge
laimory_aws_cloudwatch_last_attempt_unixtime_seconds $(date +%s)
# HELP laimory_aws_ec2_cpu_credit_balance Latest EC2 CPU credit balance.
# TYPE laimory_aws_ec2_cpu_credit_balance gauge
laimory_aws_ec2_cpu_credit_balance $credit
# HELP laimory_aws_ec2_cpu_surplus_credits_charged_last_5m Surplus CPU credits charged in the latest five-minute period.
# TYPE laimory_aws_ec2_cpu_surplus_credits_charged_last_5m gauge
laimory_aws_ec2_cpu_surplus_credits_charged_last_5m $surplus
# HELP laimory_aws_ebs_queue_length Latest average root EBS volume queue length.
# TYPE laimory_aws_ebs_queue_length gauge
laimory_aws_ebs_queue_length $queue
# HELP laimory_aws_ebs_read_latency_seconds Average root EBS read latency in the latest five-minute period.
# TYPE laimory_aws_ebs_read_latency_seconds gauge
laimory_aws_ebs_read_latency_seconds $read_latency
# HELP laimory_aws_ebs_write_latency_seconds Average root EBS write latency in the latest five-minute period.
# TYPE laimory_aws_ebs_write_latency_seconds gauge
laimory_aws_ebs_write_latency_seconds $write_latency
# HELP laimory_aws_ebs_busy_ratio Root EBS non-idle fraction in the latest five-minute period.
# TYPE laimory_aws_ebs_busy_ratio gauge
laimory_aws_ebs_busy_ratio $busy_ratio
# HELP laimory_aws_photo_replication_dlq_messages Messages waiting in the S3 to R2 photo replication DLQ.
# TYPE laimory_aws_photo_replication_dlq_messages gauge
laimory_aws_photo_replication_dlq_messages $replication_dlq
# HELP laimory_aws_photo_replication_destination_failures_last_5m Failed events the replication Lambda could not deliver to its DLQ in the latest five-minute period with data (looked back 20 minutes; 0 when none).
# TYPE laimory_aws_photo_replication_destination_failures_last_5m gauge
laimory_aws_photo_replication_destination_failures_last_5m $replication_destination_failures
EOF

chmod 0644 "$tmp_file"
mv -f "$tmp_file" "$OUTPUT_FILE"
trap - EXIT
