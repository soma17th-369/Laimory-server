#!/usr/bin/env bash
# artifact 격리 검증 — token·manifest·k6 결과가 저장소로 새지 않는지 확인한다.
#
# 커밋 전과 run 종료 후에 실행한다. 실패하면 커밋하지 않는다.
#
#   load-tests/timeline-draft/scripts/verify-artifact-hygiene.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

BASE="load-tests/timeline-draft"
ARTIFACT_DIR="${BASE}/.artifacts"
SENTINEL="${ARTIFACT_DIR}/.gitkeep"
FAILURES=0

fail() {
    echo "❌ $1" >&2
    FAILURES=$((FAILURES + 1))
}

pass() {
    echo "✅ $1"
}

# 1) sentinel이 추적되고 있어야 한다 — 없으면 .artifacts 디렉터리 자체가 사라져 규칙이 무의미해진다.
if git ls-files --error-unmatch "$SENTINEL" >/dev/null 2>&1; then
    pass "sentinel이 추적되고 있다: $SENTINEL"
else
    fail "sentinel이 추적되지 않는다: $SENTINEL (git add 필요)"
fi

# 2) sentinel 외에 .artifacts 아래에 추적되는 파일이 없어야 한다.
TRACKED_EXTRA="$(git ls-files "$ARTIFACT_DIR" | grep -v '^load-tests/timeline-draft/\.artifacts/\.gitkeep$' || true)"
if [ -z "$TRACKED_EXTRA" ]; then
    pass ".artifacts 아래 추적 파일은 sentinel 하나뿐이다"
else
    fail ".artifacts 아래에 추적되는 파일이 있다:"$'\n'"$TRACKED_EXTRA"
fi

# 3) ignore 규칙이 실제로 동작하는지 probe 파일로 확인한다(규칙이 조용히 깨지는 것을 잡는다).
PROBE="${ARTIFACT_DIR}/.hygiene-probe-tokens.json"
mkdir -p "$ARTIFACT_DIR"
printf '{"probe":true}\n' > "$PROBE"
if git check-ignore --quiet "$PROBE"; then
    pass "ignore 규칙이 .artifacts 신규 파일을 무시한다"
else
    fail "ignore 규칙이 동작하지 않는다: $PROBE 가 추적 후보로 남는다"
fi
rm -f "$PROBE"

# 4) 추적 대상 파일에 token·secret 형태 문자열이 없는지 확인한다.
#    JWT 3-segment 형태와 KakaoAK 키 값이 대상이다(#257의 dummy key는 허용).
TRACKED_FILES="$(git ls-files "$BASE")"
if [ -n "$TRACKED_FILES" ]; then
    JWT_HITS="$(echo "$TRACKED_FILES" | xargs grep -lE 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.' 2>/dev/null || true)"
    if [ -z "$JWT_HITS" ]; then
        pass "추적 파일에 JWT 형태 문자열이 없다"
    else
        fail "추적 파일에 JWT 형태 문자열이 있다:"$'\n'"$JWT_HITS"
    fi

    KEY_HITS="$(echo "$TRACKED_FILES" \
        | xargs grep -nE 'KakaoAK[[:space:]]+[A-Za-z0-9]{16,}|KAKAO_REST_API_KEY=[A-Za-z0-9]{16,}|JWT_SECRET=[^$[:space:]]{8,}' 2>/dev/null || true)"
    if [ -z "$KEY_HITS" ]; then
        pass "추적 파일에 실제 key 형태 값이 없다"
    else
        fail "추적 파일에 key 형태 값이 있다:"$'\n'"$KEY_HITS"
    fi
fi

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "artifact 격리 검증 통과."
    exit 0
fi
echo "artifact 격리 검증 실패: ${FAILURES}건" >&2
exit 1
