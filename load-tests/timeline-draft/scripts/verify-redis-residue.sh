#!/usr/bin/env bash
# Redis 잔여 확인 — run 종료 후 draft task 키와 PROCESSING index가 정리됐는지 본다.
#
# 기대 동작(AI noop 기준):
#   - task 키 `{prefix}timeline:draft-task:{taskId}` 는 PROCESSING TTL 3분으로 자연 소멸한다.
#   - 사용자별 index `{prefix}timeline:draft-task:user:{userId}:processing` 키도 같은 TTL로 사라진다.
#   - 전역 index `{prefix}timeline:draft-task:processing-index` 는 stuck 지표(gauge)가 scrape될 때
#     TTL 밖 member를 prune한다. 즉 3분 경과 + Prometheus scrape 1회 이후 0에 수렴한다.
#     바로 0이 아니면 조금 기다렸다가 다시 확인한다(수동 삭제 불필요).
#
# 사용:
#   REDIS_HOST=... REDIS_PORT=6379 REDIS_PREFIX=dev_ \
#     load-tests/timeline-draft/scripts/verify-redis-residue.sh
#
# 인증이 필요한 Redis면 REDISCLI_AUTH 환경변수를 쓴다(인자로 비밀번호를 넘기지 않는다).

set -euo pipefail

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
# `:-`가 아니라 `-`다 — local/integration은 prefix가 빈 문자열이고, 빈 값을 기본값으로 되돌리면
# 존재하지 않는 dev_ 키를 조회해 "잔여 0"이 거짓으로 나온다.
REDIS_PREFIX="${REDIS_PREFIX-dev_}"

if ! command -v redis-cli >/dev/null 2>&1; then
    echo "redis-cli가 필요합니다." >&2
    exit 2
fi

cli() {
    redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
}

TASK_PATTERN="${REDIS_PREFIX}timeline:draft-task:*"
INDEX_KEY="${REDIS_PREFIX}timeline:draft-task:processing-index"

echo "redis    : ${REDIS_HOST}:${REDIS_PORT}"
echo "prefix   : ${REDIS_PREFIX}"
echo

# SCAN은 논블로킹이라 공유 Redis에서도 안전하다(KEYS는 쓰지 않는다).
TASK_KEYS="$(cli --scan --pattern "$TASK_PATTERN" | grep -v "^${INDEX_KEY}$" || true)"
TASK_COUNT="$(printf '%s' "$TASK_KEYS" | grep -c . || true)"
echo "draft-task 관련 키          : ${TASK_COUNT}"
if [ "$TASK_COUNT" -gt 0 ]; then
    printf '%s\n' "$TASK_KEYS" | head -20 | sed 's/^/  /'
    if [ "$TASK_COUNT" -gt 20 ]; then
        echo "  ... (총 ${TASK_COUNT}개)"
    fi
fi

INDEX_SIZE="$(cli ZCARD "$INDEX_KEY")"
echo "processing-index member     : ${INDEX_SIZE}"
echo

if [ "$TASK_COUNT" -eq 0 ] && [ "$INDEX_SIZE" -eq 0 ]; then
    echo "✅ Redis 잔여 0."
    exit 0
fi

echo "⚠️  아직 잔여가 있다. PROCESSING TTL 3분과 지표 scrape 1회를 기다린 뒤 다시 실행한다."
echo "    그래도 줄지 않으면 앱이 여전히 요청을 받고 있는지 확인한다."
exit 1
