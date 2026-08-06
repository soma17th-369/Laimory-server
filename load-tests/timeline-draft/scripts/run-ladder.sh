#!/usr/bin/env bash
# #251 단계 사다리 실행기 — 낮은 단계가 gate를 통과한 경우에만 다음 단계로 올라간다.
#
# k6는 threshold 위반 시 0이 아닌 코드로 끝난다. 그 순간 사다리를 멈추고 마지막으로 통과한 단계와
# 처음 실패한 단계를 출력한다. 각 단계는 서로 다른 recordDate를 쓴다(STEP_INDEX) — daily_records가
# (user_id, record_date) UNIQUE라 같은 날짜를 재사용하면 두 번째 단계가 INSERT 대신 UPDATE가 된다.
#
# 사용:
#   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app CONFIRM_AI_NOOP=yes \
#     load-tests/timeline-draft/scripts/run-ladder.sh calendar-core
#
#   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app CONFIRM_AI_NOOP=yes CONFIRM_SIMULATOR=yes \
#     load-tests/timeline-draft/scripts/run-ladder.sh geo-1-stay
#
# 사다리 재정의: LADDER="1 10 50" ... run-ladder.sh calendar-core

set -euo pipefail

SCENARIO="${1:-}"
if [ -z "$SCENARIO" ]; then
    echo "사용법: run-ladder.sh <calendar-core|geo-1-stay|geo-18-stay>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

SCRIPT_PATH="load-tests/timeline-draft/k6/${SCENARIO}.js"
if [ ! -f "$SCRIPT_PATH" ]; then
    echo "알 수 없는 시나리오입니다: $SCENARIO ($SCRIPT_PATH 없음)" >&2
    exit 2
fi

: "${RUN_ID:?RUN_ID가 필요합니다(예: 20260806-01)}"
: "${BASE_URL:?BASE_URL이 필요합니다(예: https://dev.laimory.app)}"

ARTIFACT_DIR="${ARTIFACT_DIR:-load-tests/timeline-draft/.artifacts}"
mkdir -p "$ARTIFACT_DIR"

# 기본 사다리. core와 geo-1은 #251이 고정한 순서다.
#
# geo-18은 용량 사다리가 아니라 pool 포화 민감도다. 요청 하나가 좌표 18개를 동시에 구독하므로 VU 수가
# 그대로 순간 동시 lookup 수(VUS × 18)가 되고, 전용 pool 용량(active 20 + pending 20 = 40)을 3 VU에서
# 넘는다. 실측(로컬, 기본 설정)에서 1 VU는 완전 성공, 2 VU는 경계 실패가 허용 한도 안에서 발생, 3 VU 이상은
# FAILURE_RATIO로 502다. 사다리를 길게 두는 의미가 없어 전이 구간만 훑는다.
if [ -n "${LADDER:-}" ]; then
    STEPS="$LADDER"
else
    case "$SCENARIO" in
        calendar-core) STEPS="1 10 50 100 300 500 1000" ;;
        geo-1-stay)    STEPS="1 10 20 40 50 100 300 500 1000" ;;
        geo-18-stay)   STEPS="1 2 3" ;;
    esac
fi

echo "run-id   : $RUN_ID"
echo "scenario : $SCENARIO"
echo "target   : $BASE_URL"
echo "ladder   : $STEPS"
echo

STEP_INDEX=0
LAST_PASSED=""

for VUS in $STEPS; do
    LOG_FILE="${ARTIFACT_DIR}/${RUN_ID}-${SCENARIO}-${VUS}vu.log"
    echo "── step ${STEP_INDEX}: ${VUS} VU ──────────────────────────────"

    # k6는 threshold 실패를 0이 아닌 종료 코드로 알린다. set -e가 여기서 바로 끝내지 않도록 감싼다.
    set +e
    RUN_ID="$RUN_ID" \
    BASE_URL="$BASE_URL" \
    VUS="$VUS" \
    STEP_INDEX="$STEP_INDEX" \
    ARTIFACT_DIR="$ARTIFACT_DIR" \
        k6 run "$SCRIPT_PATH" 2>&1 | tee "$LOG_FILE"
    K6_STATUS="${PIPESTATUS[0]}"
    set -e

    if [ "$K6_STATUS" -ne 0 ]; then
        echo
        echo "❌ ${VUS} VU 단계에서 gate 실패(k6 exit ${K6_STATUS}). 사다리를 여기서 멈춘다."
        echo "   마지막 통과 단계 : ${LAST_PASSED:-없음}"
        echo "   최초 실패 단계   : ${VUS} VU"
        echo "   로그             : ${LOG_FILE}"
        exit "$K6_STATUS"
    fi

    LAST_PASSED="$VUS"
    STEP_INDEX=$((STEP_INDEX + 1))
    echo

    # 다음 단계 전에 PROCESSING task TTL(3분)이 지나가도록 쉰다. 겹치면 앞 단계의 잔여 부하가
    # 다음 단계 지표에 섞이고, Redis PROCESSING index도 계속 누적된 상태로 관측된다.
    if [ "$VUS" != "$(echo "$STEPS" | awk '{print $NF}')" ]; then
        COOLDOWN="${COOLDOWN_SECONDS:-190}"
        echo "cooldown ${COOLDOWN}s (PROCESSING TTL 3분 경과 대기)"
        sleep "$COOLDOWN"
        echo
    fi
done

echo "✅ 사다리 전체 통과: $STEPS"
echo "   결과 파일: ${ARTIFACT_DIR}/${RUN_ID}-${SCENARIO}-*"
