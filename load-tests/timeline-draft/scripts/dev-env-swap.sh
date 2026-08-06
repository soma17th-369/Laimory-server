#!/usr/bin/env bash
# dev WAS의 `.env`를 바꾸고 앱 컨테이너를 재생성한다. **dev host 위에서** 실행한다(SSM 세션 등).
#
# `docker restart`로는 안 된다 — `--env-file`은 컨테이너 생성 시점에만 읽히므로 `.env`를 고쳐도
# 실행 중인 컨테이너에는 반영되지 않는다. 지우고 다시 만들어야 하고, 그때 원래 실행 옵션을
# 그대로 살려야 한다. 특히 APP_PUSH_MODE=firebase면 credential read-only mount가 빠지면 앱이
# 기동에 실패한다.
#
# 실행 옵션의 권위 원천은 `.github/workflows/deploy.yml`이다. 이 스크립트는 이미지와 mount를
# **실행 중인 컨테이너에서 그대로 읽어와** 재현하고, 나머지 고정 옵션만 위 워크플로와 맞춘다.
# 계약과 다른 상태(network mode 등)를 발견하면 아무것도 하지 않고 멈춘다.
#
# 사용:
#   ./dev-env-swap.sh show
#   ./dev-env-swap.sh set APP_AI_MODE=noop
#   ./dev-env-swap.sh set APP_GEO_KAKAO_BASE_URL=http://10.0.1.23:8080 KAKAO_REST_API_KEY=k6-257-dummy
#   ./dev-env-swap.sh unset APP_GEO_KAKAO_BASE_URL
#   ./dev-env-swap.sh snapshot before-loadtest  # 변경 전에 한 번 찍어둔다
#   ./dev-env-swap.sh restore before-loadtest   # 테스트 끝나고 완전 원복
#   ./dev-env-swap.sh recreate                # .env는 그대로 두고 재생성만
#
# 되돌리기: 시작 전 `snapshot`을 찍고 끝나고 `restore <이름>`으로 한 번에 원복한다.
# set/unset도 매번 자동 백업을 남기지만 그건 "직전 한 단계"만 되돌린다.

set -euo pipefail

ENV_FILE="${LAIMORY_ENV_FILE:-/home/ubuntu/app/.env}"
CRED_FILE="${LAIMORY_FCM_CRED_FILE:-/home/ubuntu/app/secrets/firebase-service-account.json}"
CONTAINER="${LAIMORY_CONTAINER:-laimory}"
BACKUP_DIR="${LAIMORY_ENV_BACKUP_DIR:-$(dirname "$ENV_FILE")/env-backups}"
HEALTH_URL="${LAIMORY_HEALTH_URL:-http://localhost:8080/api/v1/intro}"

# 값을 그대로 노출하면 안 되는 key — show와 diff에서 마스킹한다.
SECRET_KEYS='JWT_SECRET|.*_CLIENT_SECRET|KAKAO_REST_API_KEY|.*PASSWORD|.*_SECRET'

die() { echo "❌ $*" >&2; exit 1; }
info() { echo "   $*"; }

need_root_or_docker() {
    docker ps >/dev/null 2>&1 || die "docker를 실행할 수 없다. sudo로 실행하거나 docker 권한을 확인한다."
}

require_env_file() {
    [ -f "$ENV_FILE" ] || die ".env를 찾을 수 없다: $ENV_FILE"
}

mask() {
    # KEY=VALUE 한 줄을 받아 민감 key면 값을 가린다.
    awk -F= -v pat="^($SECRET_KEYS)$" '{
        key=$1; sub(/^[^=]*=/, "", $0);
        if (key ~ pat) printf "%s=***(%d자)\n", key, length($0);
        else printf "%s=%s\n", key, $0;
    }'
}

# 부하 테스트에 영향을 주는 key만 골라 보여준다(민감값은 마스킹).
print_loadtest_keys() {
    grep -E '^(APP_ENV|APP_AI_MODE|APP_AI_HTTP_BASE_URL|APP_GEO_MODE|APP_GEO_KAKAO_BASE_URL|KAKAO_REST_API_KEY|APP_PUSH_MODE|REDIS_KEY_PREFIX|SWAGGER_ENABLED|APP_COMMIT_SHA)=' \
        "$ENV_FILE" | mask | sed 's/^/  /' || true
}

# ── .env 편집 ─────────────────────────────────────────────────────────────────

# 백업 파일명은 점으로 시작하지 않는다 — 숨김 파일이면 `ls`가 기본 목록에서 빼서
# restore가 "백업 없음"으로 실패한다(되돌리기가 필요한 순간에 정확히 터지는 실패다).
backup_env() {
    mkdir -p "$BACKUP_DIR"
    local stamp backup n
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup="$BACKUP_DIR/env.$stamp"
    # 타임스탬프가 초 단위라 연속 실행이 같은 이름으로 겹친다. 겹치면 되돌릴 이력을 잃으므로
    # 이름이 빌 때까지 일련번호를 붙인다(AI 전환과 geo 전환을 연달아 하는 경우가 실제로 그렇다).
    n=1
    while [ -e "$backup" ]; do
        backup="$BACKUP_DIR/env.$stamp-$n"
        n=$((n + 1))
    done
    # -p로 소유권·권한을 보존하되 mtime은 복사 시각으로 되돌린다 —
    # latest_backup이 `ls -t`(mtime 순)로 "가장 최근에 만든 백업"을 고르기 때문이다.
    cp -p "$ENV_FILE" "$backup"
    touch "$backup"
    chmod 600 "$backup"
    echo "$backup"
}

# 가장 최근 백업 경로(없으면 빈 문자열). glob으로 우리 백업만 고른다.
latest_backup() {
    ls -1t "$BACKUP_DIR"/env.* 2>/dev/null | head -1
}

# key가 정확히 한 줄이면 교체, 없으면 추가, 두 줄 이상이면 중단한다.
# (--env-file은 중복 key를 조용히 마지막 값으로 해석하므로 모호한 상태를 그대로 두지 않는다.)
apply_set() {
    local pair="$1" key value count tmp
    case "$pair" in
        *=*) ;;
        *) die "KEY=VALUE 형식이 아니다: $pair" ;;
    esac
    key="${pair%%=*}"
    value="${pair#*=}"
    [ -n "$key" ] || die "key가 비어 있다: $pair"

    count="$(grep -c "^${key}=" "$ENV_FILE" || true)"
    [ "$count" -le 1 ] || die ".env에 ${key}= 줄이 ${count}개다. 손으로 정리한 뒤 다시 실행한다."

    tmp="$(mktemp "$ENV_FILE.XXXXXX")"
    if [ "$count" = "1" ]; then
        awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$ENV_FILE" > "$tmp"
    else
        cat "$ENV_FILE" > "$tmp"
        # 마지막 줄에 개행이 없을 수 있으므로 보장한 뒤 덧붙인다.
        [ -s "$tmp" ] && [ "$(tail -c1 "$tmp" | wc -l)" -eq 0 ] && echo >> "$tmp"
        echo "${key}=${value}" >> "$tmp"
    fi
    chmod 600 "$tmp"
    chown --reference="$ENV_FILE" "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$ENV_FILE"
}

apply_unset() {
    local key="$1" tmp
    [ -n "$key" ] || die "key가 비어 있다."
    tmp="$(mktemp "$ENV_FILE.XXXXXX")"
    awk -v k="$key" 'index($0, k "=") == 1 { next } { print }' "$ENV_FILE" > "$tmp"
    chmod 600 "$tmp"
    chown --reference="$ENV_FILE" "$tmp" 2>/dev/null || true
    mv -f "$tmp" "$ENV_FILE"
}

# ── 검증 (배포 pre-flight와 같은 계약) ────────────────────────────────────────
#
# 이 검사가 없으면 오타 하나로 앱이 application default(빈 Redis prefix·local·noop·Swagger off)로
# 조용히 기동한다. 재생성 전에 fail-closed한다.

require_exact_line() {
    local key="$1" expected="$2" count
    count="$(grep -c "^${key}=" "$ENV_FILE" || true)"
    [ "$count" = "1" ] || die ".env의 ${key}는 정확히 한 줄이어야 한다(현재 ${count}줄)."
    grep -qxF "$expected" "$ENV_FILE" || die ".env의 ${key} 값이 기대와 다르다(기대: ${expected})."
}

require_one_of() {
    local key="$1" pattern="$2" count
    count="$(grep -c "^${key}=" "$ENV_FILE" || true)"
    [ "$count" = "1" ] || die ".env의 ${key}는 정확히 한 줄이어야 한다(현재 ${count}줄)."
    grep -qxE "${key}=(${pattern})" "$ENV_FILE" || die ".env의 ${key}는 ${pattern} 중 하나여야 한다."
}

require_nonempty() {
    local key="$1"
    grep -qE "^${key}=.+" "$ENV_FILE" || die ".env의 ${key}가 비어 있거나 없다."
}

verify_env() {
    # dev 고정값
    require_exact_line APP_ENV "APP_ENV=dev"
    require_exact_line REDIS_KEY_PREFIX "REDIS_KEY_PREFIX=dev_"
    require_exact_line APP_GEO_MODE "APP_GEO_MODE=kakao"
    require_exact_line SWAGGER_ENABLED "SWAGGER_ENABLED=true"

    # mode enum
    require_one_of APP_AI_MODE 'noop|fake|http'
    require_one_of APP_PUSH_MODE 'noop|firebase'
    if grep -qxF 'APP_AI_MODE=http' "$ENV_FILE"; then
        require_nonempty APP_AI_HTTP_BASE_URL
    fi
    if grep -qxF 'APP_PUSH_MODE=firebase' "$ENV_FILE"; then
        require_exact_line GOOGLE_APPLICATION_CREDENTIALS \
            "GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json"
        { [ -f "$CRED_FILE" ] && [ -s "$CRED_FILE" ]; } \
            || die "APP_PUSH_MODE=firebase인데 credential 파일이 없거나 비었다: $CRED_FILE"
    fi

    # 필수 secret 존재(값은 검사하지 않는다)
    grep -qE '^JWT_SECRET=.{32,}$' "$ENV_FILE" || die ".env의 JWT_SECRET이 없거나 32자 미만이다."
    require_nonempty GOOGLE_CLIENT_ID
    require_nonempty GOOGLE_CLIENT_SECRET
    require_nonempty KAKAO_CLIENT_ID
    require_nonempty KAKAO_CLIENT_SECRET
    # kakao mode에서 앱이 fail-fast로 요구한다(simulator용 dummy 값도 여기서 통과한다).
    require_nonempty KAKAO_REST_API_KEY

    echo "✅ .env 검증 통과"
}

# ── 컨테이너 재생성 ───────────────────────────────────────────────────────────

recreate_container() {
    local image mounts network restart

    docker inspect "$CONTAINER" >/dev/null 2>&1 \
        || die "컨테이너 '$CONTAINER'를 찾을 수 없다. 배포가 된 host인지 확인한다."

    image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
    network="$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$CONTAINER")"
    restart="$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$CONTAINER")"
    # 현재 붙어 있는 bind mount를 그대로 재현한다(firebase credential 등).
    mounts="$(docker inspect -f '{{range .Mounts}}-v {{.Source}}:{{.Destination}}{{if not .RW}}:ro{{end}} {{end}}' "$CONTAINER")"

    # 배포 계약과 다르면 추측해서 만들지 않는다.
    [ "$network" = "host" ] || die "network mode가 host가 아니다($network). 배포 계약과 달라 자동 재생성하지 않는다."

    info "image   : $image"
    info "network : $network / restart: $restart"
    info "mounts  : ${mounts:-(없음)}"
    echo

    echo "▶ 기존 컨테이너 중지·삭제"
    docker stop "$CONTAINER" >/dev/null 2>&1 || true
    docker rm "$CONTAINER" >/dev/null 2>&1 || true

    echo "▶ 재생성"
    # 고정 옵션은 .github/workflows/deploy.yml의 실행 계약과 같다.
    # shellcheck disable=SC2086
    docker run -d --name "$CONTAINER" --restart always --network host \
        --log-opt max-size=10m --log-opt max-file=3 \
        --env-file "$ENV_FILE" $mounts "$image" >/dev/null

    echo "▶ health check ($HEALTH_URL)"
    local ok=0 i
    for i in $(seq 1 45); do
        if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
            ok=1; echo "✅ ${i}번째 시도에서 정상"; break
        fi
        sleep 2
    done
    if [ "$ok" != "1" ]; then
        echo "❌ health check 실패 — 최근 로그 80줄:" >&2
        docker logs --tail 80 "$CONTAINER" 2>&1 || true
        echo >&2
        echo "되돌리려면: $0 restore" >&2
        exit 1
    fi
}

# ── 서브커맨드 ────────────────────────────────────────────────────────────────

cmd_show() {
    require_env_file
    echo "env file : $ENV_FILE"
    echo
    echo "부하 테스트 관련 값:"
    print_loadtest_keys
    echo
    if docker inspect "$CONTAINER" >/dev/null 2>&1; then
        echo "컨테이너:"
        docker inspect -f '  image   : {{.Config.Image}}
  started : {{.State.StartedAt}}
  status  : {{.State.Status}}' "$CONTAINER"
    else
        echo "컨테이너 '$CONTAINER' 없음"
    fi
    echo
    if [ -d "$BACKUP_DIR" ]; then
        echo "스냅샷($BACKUP_DIR):"
        ls -1t "$BACKUP_DIR"/snapshot.* 2>/dev/null | sed 's|.*/snapshot\.|  |' || true
        echo "자동 백업(최근 5개):"
        ls -1t "$BACKUP_DIR"/env.* 2>/dev/null | head -5 | sed 's|.*/|  |'
    fi
}

cmd_set() {
    [ "$#" -ge 1 ] || die "사용법: $0 set KEY=VALUE [KEY=VALUE ...]"
    need_root_or_docker; require_env_file
    local backup
    backup="$(backup_env)"
    echo "▶ 백업: $backup"
    local pair
    for pair in "$@"; do
        apply_set "$pair"
        echo "$pair" | mask | sed 's/^/   설정: /'
    done
    echo
    verify_env
    echo
    recreate_container
}

cmd_unset() {
    [ "$#" -ge 1 ] || die "사용법: $0 unset KEY [KEY ...]"
    need_root_or_docker; require_env_file
    local backup
    backup="$(backup_env)"
    echo "▶ 백업: $backup"
    local key
    for key in "$@"; do
        apply_unset "$key"
        echo "   제거: $key"
    done
    echo
    verify_env
    echo
    recreate_container
}

# 시작 전 상태를 이름으로 박아둔다. set/unset의 자동 백업은 "직전 한 단계"만 되돌리므로,
# 여러 번 바꾼 뒤 완전히 원복하려면 이 스냅샷이 필요하다.
cmd_snapshot() {
    require_env_file
    local name="${1:-before-loadtest}"
    mkdir -p "$BACKUP_DIR"
    local target="$BACKUP_DIR/snapshot.$name"
    [ -e "$target" ] && die "같은 이름의 스냅샷이 이미 있다: $target (다른 이름을 쓰거나 지운 뒤 다시 실행)"
    cp -p "$ENV_FILE" "$target"
    touch "$target"
    chmod 600 "$target"
    echo "▶ 스냅샷 저장: $target"
    echo
    print_loadtest_keys
    echo
    echo "원복: $0 restore $name"
}

# 인자는 파일 경로 / 스냅샷 이름 / 백업 파일명 셋 다 받는다. 생략하면 직전 한 단계.
resolve_backup() {
    local arg="$1"
    if [ -f "$arg" ]; then echo "$arg"; return; fi
    if [ -f "$BACKUP_DIR/snapshot.$arg" ]; then echo "$BACKUP_DIR/snapshot.$arg"; return; fi
    if [ -f "$BACKUP_DIR/$arg" ]; then echo "$BACKUP_DIR/$arg"; return; fi
    echo ""
}

cmd_restore() {
    need_root_or_docker
    local arg="${1:-}" backup
    if [ -z "$arg" ]; then
        [ -d "$BACKUP_DIR" ] || die "백업 디렉터리가 없다: $BACKUP_DIR"
        backup="$(latest_backup)"
        [ -n "$backup" ] || die "백업이 하나도 없다: $BACKUP_DIR"
    else
        backup="$(resolve_backup "$arg")"
        [ -n "$backup" ] || die "백업을 찾을 수 없다: $arg  (목록은 '$0 show')"
    fi
    [ -f "$backup" ] || die "백업 파일이 없다: $backup"
    echo "▶ 복원: $backup"
    cp -p "$backup" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo
    # restore는 "한 단계 뒤"로만 간다 — 여러 번 바꿨다면 마지막 백업이 원래 상태가 아닐 수 있다.
    # 착각을 막기 위해 복원 결과를 바로 보여준다. 더 앞으로 가려면 `show`의 목록에서 골라 인자로 넘긴다.
    echo "복원된 값:"
    print_loadtest_keys
    echo
    verify_env
    echo
    recreate_container
}

cmd_recreate() {
    need_root_or_docker; require_env_file
    verify_env
    echo
    recreate_container
}

main() {
    local cmd="${1:-}"
    [ "$#" -gt 0 ] && shift || true
    case "$cmd" in
        show)     cmd_show ;;
        set)      cmd_set "$@" ;;
        unset)    cmd_unset "$@" ;;
        snapshot) cmd_snapshot "$@" ;;
        restore)  cmd_restore "$@" ;;
        recreate) cmd_recreate ;;
        verify)   require_env_file; verify_env ;;
        *)
            cat >&2 <<USAGE
사용법: $0 <명령>

  show                      현재 .env 값(민감값 마스킹)·컨테이너·백업 목록
  verify                    .env만 검증(변경·재생성 없음)
  set KEY=VALUE [...]       백업 → 수정 → 검증 → 컨테이너 재생성 → health check
  unset KEY [...]           같은 흐름으로 key 제거
  snapshot [이름]           현재 .env를 이름으로 저장(기본 before-loadtest). 변경 전에 한 번.
  restore [이름|파일]        스냅샷·백업으로 되돌린 뒤 재생성(생략하면 직전 한 단계)
  recreate                  .env는 그대로 두고 재생성만

환경변수: LAIMORY_ENV_FILE, LAIMORY_CONTAINER, LAIMORY_ENV_BACKUP_DIR,
          LAIMORY_FCM_CRED_FILE, LAIMORY_HEALTH_URL
USAGE
            exit 2 ;;
    esac
}

main "$@"
