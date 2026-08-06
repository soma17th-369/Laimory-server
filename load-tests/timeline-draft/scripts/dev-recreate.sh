#!/usr/bin/env bash
# `.env`를 고친 뒤 dev 앱 컨테이너를 재생성한다. **dev host 위에서** 실행한다(SSM 세션 등).
# `.env` 편집 자체는 이 스크립트가 하지 않는다 — 사람이 직접 고치고 이걸로 반영만 한다.
#
# `docker restart`로는 안 된다: `--env-file`은 컨테이너 생성 시점에만 읽힌다.
# 지우고 다시 만들어야 하는데, 이미지와 mount를 손으로 적으면 틀리기 쉽다(특히
# APP_PUSH_MODE=firebase의 credential read-only mount — 빠지면 앱이 기동에 실패한다).
# 그래서 둘 다 **실행 중인 컨테이너에서 그대로 읽어** 재현하고, 나머지 고정 옵션만
# `.github/workflows/deploy.yml`의 실행 계약과 맞춘다.
#
# 실패하면 이전 컨테이너로 되돌린다 — 새 컨테이너가 안 뜨거나 health check가 실패하면
# 이름만 바꿔 보관해 둔 기존 컨테이너를 다시 살린다. 앱이 내려간 채로 끝나지 않는다.
#
# 사용:
#   sudo ./dev-recreate.sh          # 확인 → 재생성 → health check
#   sudo ./dev-recreate.sh --show   # 현재 .env 값과 컨테이너만 보고 끝낸다

set -euo pipefail

ENV_FILE="${LAIMORY_ENV_FILE:-/home/ubuntu/app/.env}"
CONTAINER="${LAIMORY_CONTAINER:-laimory}"
HEALTH_URL="${LAIMORY_HEALTH_URL:-http://localhost:8080/api/v1/intro}"
# 2초 간격 재시도 횟수. 배포 워크플로와 같은 45회(=90초)가 기본이다.
HEALTH_RETRIES="${LAIMORY_HEALTH_RETRIES:-45}"

die() { echo "❌ $*" >&2; exit 1; }

# 값이 노출되면 안 되는 key는 길이만 보여준다.
show_env() {
    grep -E '^(APP_ENV|APP_AI_MODE|APP_GEO_MODE|APP_GEO_KAKAO_BASE_URL|KAKAO_REST_API_KEY|APP_PUSH_MODE|REDIS_KEY_PREFIX|APP_COMMIT_SHA)=' "$ENV_FILE" \
        | awk -F= '{k=$1; sub(/^[^=]*=/,"",$0);
                    if (k ~ /^(KAKAO_REST_API_KEY|JWT_SECRET)$|_SECRET$|PASSWORD/) printf "  %s=***(%d자)\n", k, length($0);
                    else printf "  %s=%s\n", k, $0}'
}

# 컨테이너를 부수기 전에 하는 최소 검사. 값 자체는 판단하지 않는다(사람이 정한 값이다) —
# 손편집에서 실제로 나오는 사고만 본다.
precheck() {
    # 중복 key: --env-file은 조용히 마지막 값을 쓴다. 의도한 줄이 안 먹는 대표적 원인이다.
    local dups
    dups="$(grep -oE '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE" | sort | uniq -d | tr -d '=' | tr '\n' ' ')"
    [ -z "$dups" ] || die ".env에 중복된 key가 있다: ${dups}— 한 줄씩만 남기고 다시 실행한다."

    # AI mode는 이번 작업의 핵심이라 오타를 여기서 잡는다(앱은 노출되지 않은 값이면 noop로 조용히 뜬다).
    grep -qxE 'APP_AI_MODE=(noop|fake|http)' "$ENV_FILE" \
        || die ".env의 APP_AI_MODE가 noop|fake|http 중 하나가 아니다."
}

[ -f "$ENV_FILE" ] || die ".env를 찾을 수 없다: $ENV_FILE"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "컨테이너 '$CONTAINER'를 찾을 수 없다."

IMAGE="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
NETWORK="$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$CONTAINER")"
MOUNTS="$(docker inspect -f '{{range .Mounts}}-v {{.Source}}:{{.Destination}}{{if not .RW}}:ro{{end}} {{end}}' "$CONTAINER")"
# 이미지 기본 CMD를 그대로 쓰는 게 배포 계약이지만, 실행 중인 컨테이너의 Cmd를 그대로 넘겨
# 어떤 경우에도 지금 도는 것과 같은 프로세스로 뜨게 한다(생략하면 재시작 루프에 빠질 수 있다).
CMD="$(docker inspect -f '{{if .Config.Cmd}}{{range .Config.Cmd}}{{.}} {{end}}{{end}}' "$CONTAINER")"

echo "env file : $ENV_FILE"
show_env
echo
echo "image    : $IMAGE"
echo "network  : $NETWORK"
echo "mounts   : ${MOUNTS:-(없음)}"

[ "${1:-}" != "--show" ] || exit 0

# 배포 계약과 다르면 추측해서 만들지 않는다.
[ "$NETWORK" = "host" ] || die "network mode가 host가 아니다($NETWORK). 배포 계약과 달라 자동 재생성하지 않는다."
precheck
echo
echo "✅ 사전 검사 통과"

# 기존 컨테이너는 지우지 않고 이름만 바꿔 보관한다 — 새 컨테이너가 실패하면 이걸 되살린다.
PREV="${CONTAINER}-prev-$(date -u +%Y%m%d%H%M%S)"
echo "▶ 기존 컨테이너 중지 후 $PREV 로 보관"
docker stop "$CONTAINER" >/dev/null
docker rename "$CONTAINER" "$PREV"

rollback() {
    echo "↩︎ 되돌리는 중 — $PREV 를 $CONTAINER 로 복구한다" >&2
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker rename "$PREV" "$CONTAINER" >/dev/null 2>&1 || true
    if docker start "$CONTAINER" >/dev/null 2>&1; then
        echo "↩︎ 이전 컨테이너로 복구했다. .env를 고친 뒤 다시 실행한다." >&2
    else
        echo "⚠️  복구도 실패했다. 'docker ps -a'로 상태를 직접 확인한다." >&2
    fi
}

echo "▶ 재생성"
# 고정 옵션은 .github/workflows/deploy.yml의 실행 계약과 같다.
# shellcheck disable=SC2086
if ! docker run -d --name "$CONTAINER" --restart always --network host \
        --log-opt max-size=10m --log-opt max-file=3 \
        --env-file "$ENV_FILE" $MOUNTS "$IMAGE" $CMD >/dev/null; then
    rollback
    die "docker run 실패 — .env 형식이나 디스크 상태를 확인한다."
fi

echo "▶ health check ($HEALTH_URL)"
for i in $(seq 1 "$HEALTH_RETRIES"); do
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
        echo "✅ ${i}번째 시도에서 정상"
        docker rm "$PREV" >/dev/null 2>&1 || true
        echo
        echo "반영된 값:"
        show_env
        exit 0
    fi
    sleep 2
done

echo "❌ health check 실패 — 새 컨테이너 로그 80줄:" >&2
docker logs --tail 80 "$CONTAINER" >&2 2>&1 || true
docker stop "$CONTAINER" >/dev/null 2>&1 || true
rollback
exit 1
