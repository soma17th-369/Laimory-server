#!/bin/bash
# Grafana·Kibana는 공개 엔드포인트가 없다(#437) — 접속은 SSM 포트포워딩 전용이다.
# 로컬 포트 규약(Grafana 3000 · Kibana 5601)은 대시보드 딥링크와 알림 runbook URL이 전제한다.
#
# 사용법: open-observability-tunnel.sh grafana|kibana
#   터널이 열린 동안 http://localhost:3000 (Grafana) / http://localhost:5601 (Kibana)로 접속한다.
#   Ctrl+C로 세션을 종료한다. 인스턴스는 Name 태그로 실행 시점에 조회한다(ID를 저장소에 두지 않는다).
set -euo pipefail

TARGET="${1:?usage: open-observability-tunnel.sh grafana|kibana}"
PROFILE="${AWS_PROFILE:-sandbox}"
REGION="${AWS_REGION:-ap-northeast-2}"

case "$TARGET" in
  grafana)
    NAME_TAG="laimory-dev-monitoring-01"
    PORT=3000
    ;;
  kibana)
    NAME_TAG="laimory-dev-elk-01"
    PORT=5601
    ;;
  *)
    echo "unknown target: $TARGET (grafana|kibana)" >&2
    exit 64
    ;;
esac

INSTANCE_ID="$(aws ec2 describe-instances --profile "$PROFILE" --region "$REGION" \
  --filters "Name=tag:Name,Values=$NAME_TAG" "Name=instance-state-name,Values=running" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)"
if [[ -z "$INSTANCE_ID" || "$INSTANCE_ID" == "None" ]]; then
  echo "running instance not found for tag Name=$NAME_TAG" >&2
  exit 69
fi

echo "$TARGET → http://localhost:$PORT (Ctrl+C로 종료)"
exec aws ssm start-session --profile "$PROFILE" --region "$REGION" \
  --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession \
  --parameters "portNumber=$PORT,localPortNumber=$PORT"
