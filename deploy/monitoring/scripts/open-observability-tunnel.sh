#!/bin/bash
# Grafana·Kibana는 공개 엔드포인트가 없다(#437) — 접속은 SSM 포트포워딩 전용이다.
# 로컬 포트 규약(Grafana 3000 · Kibana 5601)은 대시보드 딥링크와 알림 runbook URL이 전제한다.
#
# 사용법: open-observability-tunnel.sh grafana|kibana|admin-dev|admin-prod [WAS Name 태그]
#   터널이 열린 동안 http://localhost:3000 (Grafana) / http://localhost:5601 (Kibana)로 접속한다.
#   Ctrl+C로 세션을 종료한다. 인스턴스는 Name 태그로 실행 시점에 조회한다(ID를 저장소에 두지 않는다).
set -euo pipefail

TARGET="${1:?usage: open-observability-tunnel.sh grafana|kibana|admin-dev|admin-prod [WAS Name tag]}"
PROFILE="${AWS_PROFILE:-sandbox}"
REGION="${AWS_REGION:-ap-northeast-2}"
FILTERS=("Name=instance-state-name,Values=running")

case "$TARGET" in
  grafana)
    NAME_TAG="laimory-dev-monitoring-01"
    PORT=3000
    ;;
  kibana)
    NAME_TAG="laimory-dev-elk-01"
    PORT=5601
    ;;
  admin-dev|admin-prod)
    ENVIRONMENT="${TARGET#admin-}"
    NAME_TAG="${2:-laimory-$ENVIRONMENT-was-*}"
    if [[ $# -gt 2 ]] || { [[ $# -eq 2 ]] && [[ ! "$NAME_TAG" =~ ^laimory-$ENVIRONMENT-was-[0-9]+$ ]]; }; then
      echo "WAS Name must belong to $ENVIRONMENT (example: laimory-$ENVIRONMENT-was-01)" >&2
      exit 64
    fi
    # dev WAS에는 Environment 태그가 없다. prod는 Name과 Environment 모두 검사한다.
    if [[ "$ENVIRONMENT" == "prod" ]]; then FILTERS+=("Name=tag:Environment,Values=prod"); fi
    PORT=8081
    ;;
  *)
    echo "unknown target: $TARGET (grafana|kibana|admin-dev|admin-prod)" >&2
    exit 64
    ;;
esac
if [[ "$TARGET" != admin-* && $# -ne 1 ]]; then
  echo "unexpected host argument for $TARGET" >&2
  exit 64
fi
FILTERS+=("Name=tag:Name,Values=$NAME_TAG")

INSTANCE_IDS="$(aws ec2 describe-instances --profile "$PROFILE" --region "$REGION" \
  --filters "${FILTERS[@]}" --query 'Reservations[].Instances[].InstanceId' --output text)"
read -r -a CANDIDATES <<< "$(tr '\t\n' '  ' <<< "$INSTANCE_IDS")"
if [[ ${#CANDIDATES[@]} -ne 1 || "${CANDIDATES[0]:-None}" == "None" ]]; then
  echo "expected exactly one running instance for Name=$NAME_TAG; select an exact WAS Name for admin targets" >&2
  exit 69
fi
INSTANCE_ID="${CANDIDATES[0]}"
SSM_STATUS="$(aws ssm describe-instance-information --profile "$PROFILE" --region "$REGION" \
  --filters "Key=InstanceIds,Values=$INSTANCE_ID" --query 'InstanceInformationList[0].PingStatus' --output text)"
if [[ "$SSM_STATUS" != "Online" ]]; then
  echo "selected instance is not SSM Online" >&2
  exit 69
fi

if [[ "$TARGET" == admin-* ]]; then
  echo "$TARGET [$NAME_TAG] → http://localhost:$PORT/admin/ (저장 즉시 반영, Ctrl+C로 종료)"
else
  echo "$TARGET → http://localhost:$PORT (Ctrl+C로 종료)"
fi
exec aws ssm start-session --profile "$PROFILE" --region "$REGION" \
  --target "$INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSession \
  --parameters "portNumber=$PORT,localPortNumber=$PORT"
