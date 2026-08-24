#!/bin/bash
# prod MySQL EBS 볼륨 스냅샷을 생성·보존기간 초과분 정리하고, 최신 완료 스냅샷 시각을
# node_exporter textfile로 기록한다. AWS Backup·DLM이 조직 SCP로 거부되어 직접 호출로 대체한다.
# 설정(볼륨 ID)은 monitoring 호스트의 /etc/laimory/ebs-snapshot-backup.env가 소유한다.
set -euo pipefail

readonly CONFIG_FILE=/etc/laimory/ebs-snapshot-backup.env
readonly OUTPUT_DIR=/var/lib/node_exporter/textfile_collector
readonly OUTPUT_FILE="$OUTPUT_DIR/laimory_ebs_snapshot.prom"
readonly TAG_KEY=laimory-backup
readonly TAG_VALUE=prod-mysql
readonly IMDS=http://169.254.169.254/latest

install -d -m 0750 -o root -g node_exporter "$OUTPUT_DIR"

# last_success는 상태 파일이 아니라 describe-snapshots 결과가 원천이다 — 스냅샷 존재 자체를 관측한다.
write_metrics() {
  local up=$1 last_success=$2 retained=$3
  local tmp_file
  tmp_file=$(mktemp "$OUTPUT_DIR/.laimory_ebs_snapshot.XXXXXX")
  {
    echo "# HELP laimory_ebs_snapshot_up Whether the latest snapshot backup run completed."
    echo "# TYPE laimory_ebs_snapshot_up gauge"
    echo "laimory_ebs_snapshot_up $up"
    echo "# HELP laimory_ebs_snapshot_last_attempt_unixtime_seconds Unix time of the latest snapshot run."
    echo "# TYPE laimory_ebs_snapshot_last_attempt_unixtime_seconds gauge"
    echo "laimory_ebs_snapshot_last_attempt_unixtime_seconds $(date +%s)"
    if [[ $last_success != none ]]; then
      echo "# HELP laimory_ebs_snapshot_last_success_unixtime_seconds Start time of the newest completed snapshot."
      echo "# TYPE laimory_ebs_snapshot_last_success_unixtime_seconds gauge"
      echo "laimory_ebs_snapshot_last_success_unixtime_seconds $last_success"
    fi
    echo "# HELP laimory_ebs_snapshot_retained Number of retained backup snapshots."
    echo "# TYPE laimory_ebs_snapshot_retained gauge"
    echo "laimory_ebs_snapshot_retained $retained"
  } > "$tmp_file"
  chmod 0644 "$tmp_file"
  mv -f "$tmp_file" "$OUTPUT_FILE"
}

describe_tagged() {
  aws ec2 describe-snapshots --region "$region" --owner-ids self \
    --filters "Name=tag:$TAG_KEY,Values=$TAG_VALUE" --output json
}

record_state_and_fail() {
  local snapshots last_success=none retained=0
  if snapshots=$(describe_tagged 2>/dev/null); then
    last_success=$(jq -r '[.Snapshots[] | select(.State == "completed") | .StartTime] | max // "none"' <<<"$snapshots")
    [[ $last_success == none ]] || last_success=$(date -d "$last_success" +%s)
    retained=$(jq -r '.Snapshots | length' <<<"$snapshots")
  fi
  write_metrics 0 "$last_success" "$retained"
  echo "ebs snapshot backup failed: $*" >&2
  exit 1
}

[[ -f $CONFIG_FILE ]] || { write_metrics 0 none 0; echo "missing config: $CONFIG_FILE" >&2; exit 1; }
# shellcheck disable=SC1090
source "$CONFIG_FILE"
: "${VOLUME_ID:?}"
RETENTION_DAYS=${RETENTION_DAYS:-14}
[[ $VOLUME_ID =~ ^vol-[0-9a-f]+$ ]] || { write_metrics 0 none 0; echo "invalid VOLUME_ID" >&2; exit 1; }

token=$(curl -fsS --connect-timeout 2 --max-time 5 -X PUT \
  "$IMDS/api/token" -H 'X-aws-ec2-metadata-token-ttl-seconds: 300') || record_state_and_fail "IMDS token"
region=$(curl -fsS --connect-timeout 2 --max-time 5 \
  -H "X-aws-ec2-metadata-token: $token" "$IMDS/meta-data/placement/region") || record_state_and_fail "IMDS region"

snapshot_id=$(aws ec2 create-snapshot --region "$region" --volume-id "$VOLUME_ID" \
  --description "laimory prod-mysql daily backup" \
  --tag-specifications "ResourceType=snapshot,Tags=[{Key=$TAG_KEY,Value=$TAG_VALUE}]" \
  --query SnapshotId --output text) || record_state_and_fail "create-snapshot"

# 작은 볼륨은 수 분 안에 끝난다. wait 시간 초과 시 이번 run은 실패로 기록하지만 스냅샷 자체는
# 백그라운드에서 계속 진행되므로, 완료되면 다음 run의 last_success가 자연히 회복된다.
aws ec2 wait snapshot-completed --region "$region" --snapshot-ids "$snapshot_id" \
  || record_state_and_fail "snapshot $snapshot_id not completed in time"

snapshots=$(describe_tagged) || record_state_and_fail "describe-snapshots"
cutoff=$(date -u -d "-${RETENTION_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)
expired_ids=$(jq -r --arg cutoff "$cutoff" \
  '.Snapshots[] | select(.State != "pending" and .StartTime < $cutoff) | .SnapshotId' <<<"$snapshots")
for expired_id in $expired_ids; do
  aws ec2 delete-snapshot --region "$region" --snapshot-id "$expired_id" \
    || record_state_and_fail "delete-snapshot $expired_id"
  echo "pruned expired snapshot: $expired_id"
done

snapshots=$(describe_tagged) || record_state_and_fail "describe-snapshots after prune"
last_success=$(jq -er '[.Snapshots[] | select(.State == "completed") | .StartTime] | max' <<<"$snapshots") \
  || record_state_and_fail "no completed snapshot found"
retained=$(jq -r '.Snapshots | length' <<<"$snapshots")
write_metrics 1 "$(date -d "$last_success" +%s)" "$retained"
echo "snapshot backup completed: $snapshot_id (retained: $retained)"
