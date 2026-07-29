#!/usr/bin/env bash
# deploy.yml SSM 원격 배포 script의 계약 테스트 harness.
#
# production heredoc 본문을 deploy.yml에서 그대로 추출해(복사본 금지) 러너와 동일한 unquoted-heredoc
# 확장으로 SCRIPT를 재현한 뒤, fake docker/aws/curl/nginx PATH와 temp .env fixture로 실행한다.
# LAIMORY_ENV_FILE / LAIMORY_FCM_CRED_FILE seam으로 운영 경로 대신 fixture 경로를 준다.
# 검증 계약: 계획 #197의 T1~T10 — exact-one preflight fail-closed, APP_COMMIT_SHA 원자 upsert,
# pre-stop 실패의 .env/SHA 보존, secret 비출력, 모든 종료 경로의 prune -af 1회와 원래 status 보존.
#
# 실행: bash .github/scripts/test-deploy-contract.sh  (macOS bash 3.2 / Linux bash 호환)
set -u

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$REPO_ROOT/.github/workflows/deploy.yml"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAKE_SHA="1234567890abcdef1234567890abcdef12345678"
SENTINEL="SENTINEL_SECRET_XYZZY"
PASS=0

fail() { echo "FAIL: $1" >&2; exit 1; }
ok() { PASS=$((PASS + 1)); echo "ok - $1"; }

file_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then stat -f '%Lp' "$1"; else stat -c '%a' "$1"; fi
}

# --- 1. production script 본문 추출(러너의 YAML 디코딩과 동일하게 YAML 파서 사용) ---
ruby -ryaml -e '
  src = File.read(ARGV[0])
  wf = begin
    YAML.unsafe_load(src)
  rescue NoMethodError
    YAML.load(src)
  end
  triggers = wf["on"] || wf[true]
  abort "dev push trigger missing" unless triggers.dig("push", "branches") == ["dev"]
  expected_paths = [
    ".github/workflows/deploy.yml",
    ".dockerignore",
    "Dockerfile",
    "build.gradle",
    "gradle.properties",
    "gradle/**",
    "gradlew",
    "settings.gradle",
    "src/main/**",
  ]
  abort "application deploy paths changed" unless triggers.dig("push", "paths") == expected_paths
  step = wf["jobs"]["deploy"]["steps"].find { |s| s["id"] == "ssm" }
  abort "ssm step not found" unless step
  print step["run"]
' "$WORKFLOW" > "$WORK/ssm_run.sh" || fail "extract ssm step run block"

awk '/<<EOF \|\| true$/{inside=1; next} inside && /^EOF$/{exit} inside{print}' \
  "$WORK/ssm_run.sh" > "$WORK/remote_body.raw"
[ -s "$WORK/remote_body.raw" ] || fail "remote heredoc body extraction is empty"
grep -q 'trap cleanup EXIT' "$WORK/remote_body.raw" || fail "extracted body missing EXIT cleanup"

# --- 2. 러너 heredoc 확장 재현: 러너-side 변수만 주고 같은 <<EOF 읽기로 SCRIPT를 얻는다 ---
{
  echo 'read -r -d "" SCRIPT <<EOF || true'
  cat "$WORK/remote_body.raw"
  echo 'EOF'
  echo 'printf "%s\n" "$SCRIPT"'
} > "$WORK/driver.sh"
env "AWS_REGION=ap-test-1" "REGISTRY=registry.test" "ECR_REPOSITORY=laimory" \
    "IMAGE_TAG=$FAKE_SHA" "IMG=registry.test/laimory:$FAKE_SHA" \
    /bin/bash "$WORK/driver.sh" > "$WORK/remote_script.sh" || fail "runner heredoc expansion"
SCRIPT_FILE="$WORK/remote_script.sh"
grep -q "APP_COMMIT_SHA=\" sha" "$SCRIPT_FILE" || fail "expanded script missing upsert awk"

# --- 3. T1 + 순서: 장기 실행 docker run은 --env-file 하나, -e/--env 0개; upsert는 pull 뒤 stop 앞 ---
RUN_LINE=$(grep -E '^ *docker run -d --name laimory ' "$SCRIPT_FILE")
[ "$(printf '%s\n' "$RUN_LINE" | grep -c .)" = "1" ] || fail "T1: expected exactly one long-running docker run"
printf '%s\n' "$RUN_LINE" | grep -q -- '--env-file' || fail "T1: --env-file missing from docker run"
if printf '%s\n' "$RUN_LINE" | grep -qE ' (-e|--env)[ =]'; then
  fail "T1: long-running docker run must not use -e/--env"
fi
ln_of() { grep -n "$1" "$SCRIPT_FILE" | head -1 | cut -d: -f1; }
TRAP_LN=$(ln_of 'trap cleanup EXIT')
FIRST_CHECK_LN=$(ln_of 'JWT_SECRET')
PULL_LN=$(ln_of '^docker pull ')
MKTEMP_LN=$(ln_of 'mktemp')
STOP_LN=$(ln_of '^docker stop laimory')
{ [ -n "$TRAP_LN" ] && [ -n "$FIRST_CHECK_LN" ] && [ -n "$PULL_LN" ] && [ -n "$MKTEMP_LN" ] && [ -n "$STOP_LN" ]; } \
  || fail "order: expected markers not found in expanded script"
[ "$TRAP_LN" -lt "$FIRST_CHECK_LN" ] || fail "order: trap must be installed before first failable check"
[ "$PULL_LN" -lt "$MKTEMP_LN" ] || fail "order: APP_COMMIT_SHA upsert must run after docker pull"
[ "$MKTEMP_LN" -lt "$STOP_LN" ] || fail "order: APP_COMMIT_SHA upsert must run before docker stop"
ok "T1/order: single --env-file run without -e; trap->preflight->pull->upsert->stop"

# --- 4. fake PATH stubs ---
STUB="$WORK/stub"
mkdir -p "$STUB"

cat > "$STUB/docker" <<'STUBEOF'
#!/usr/bin/env bash
echo "docker $*" >> "${DOCKER_LOG:?}"
case "$1" in
  login) cat >/dev/null 2>&1 || true; exit "${FAKE_LOGIN_EXIT:-0}" ;;
  pull) exit "${FAKE_PULL_EXIT:-0}" ;;
  run) if [ "$2" = "--rm" ]; then exit "${FAKE_UID_CHECK_EXIT:-0}"; else exit "${FAKE_RUN_EXIT:-0}"; fi ;;
  image) exit "${FAKE_PRUNE_EXIT:-0}" ;;
  *) exit 0 ;;
esac
STUBEOF

cat > "$STUB/curl" <<'STUBEOF'
#!/usr/bin/env bash
exit "${FAKE_CURL_EXIT:-0}"
STUBEOF

cat > "$STUB/aws" <<'STUBEOF'
#!/usr/bin/env bash
echo "fake-ecr-password"
exit 0
STUBEOF

cat > "$STUB/nginx" <<'STUBEOF'
#!/usr/bin/env bash
exit "${FAKE_NGINX_EXIT:-0}"
STUBEOF

cat > "$STUB/systemctl" <<'STUBEOF'
#!/usr/bin/env bash
exit 0
STUBEOF

cat > "$STUB/tee" <<'STUBEOF'
#!/usr/bin/env bash
cat > /dev/null
exit 0
STUBEOF

cat > "$STUB/sed" <<'STUBEOF'
#!/usr/bin/env bash
exit 0
STUBEOF

cat > "$STUB/sleep" <<'STUBEOF'
#!/usr/bin/env bash
exit 0
STUBEOF

cat > "$STUB/mktemp" <<'STUBEOF'
#!/usr/bin/env bash
[ "${FAKE_MKTEMP_FAIL:-0}" = "1" ] && exit 1
PATH=/usr/bin:/bin exec mktemp "$@"
STUBEOF

cat > "$STUB/awk" <<'STUBEOF'
#!/usr/bin/env bash
[ "${FAKE_AWK_FAIL:-0}" = "1" ] && exit 1
PATH=/usr/bin:/bin exec awk "$@"
STUBEOF

cat > "$STUB/chmod" <<'STUBEOF'
#!/usr/bin/env bash
[ "${FAKE_CHMOD_FAIL:-0}" = "1" ] && exit 1
PATH=/usr/bin:/bin exec chmod "$@"
STUBEOF

cat > "$STUB/mv" <<'STUBEOF'
#!/usr/bin/env bash
[ "${FAKE_MV_FAIL:-0}" = "1" ] && exit 1
PATH=/usr/bin:/bin exec mv "$@"
STUBEOF

# chown --reference는 GNU 전용이라 위임하지 않는다 — 호출 인자 기록이 owner-보존 계약의 관측값이다.
cat > "$STUB/chown" <<'STUBEOF'
#!/usr/bin/env bash
echo "chown $*" >> "${CHOWN_LOG:?}"
exit "${FAKE_CHOWN_EXIT:-0}"
STUBEOF

chmod +x "$STUB"/*

# --- 5. fixture/실행 helpers ---
new_case() {
  CASE_DIR=$(mktemp -d "$WORK/case.XXXXXX")
}

base_env_fixture() {
  cat > "$CASE_DIR/.env" <<FIX
# dev runtime env (fixture)
JWT_SECRET=${SENTINEL}_0123456789012345678901234567890123456789
GOOGLE_CLIENT_ID=google-id-fixture
GOOGLE_CLIENT_SECRET=${SENTINEL}_google-secret
KAKAO_CLIENT_ID=kakao-id-fixture
KAKAO_CLIENT_SECRET=${SENTINEL}_kakao-secret
REDIS_KEY_PREFIX=dev_
APP_ENV=dev
APP_GEO_MODE=kakao
SWAGGER_ENABLED=true
APP_AI_MODE=fake
APP_PUSH_MODE=noop

XAPP_COMMIT_SHA=lookalike-must-survive
APP_COMMIT_SHA_OLD=lookalike-must-survive-2
FIX
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
}

make_firebase_fixture() {
  base_env_fixture
  PATH=/usr/bin:/bin sed -i.bak 's/^APP_PUSH_MODE=noop$/APP_PUSH_MODE=firebase/' "$CASE_DIR/.env"
  rm -f "$CASE_DIR/.env.bak"
  echo "GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json" >> "$CASE_DIR/.env"
  echo '{"fake":"credential"}' > "$CASE_DIR/cred.json"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
}

execute_script() {
  DOCKER_LOG="$CASE_DIR/docker.log"; : > "$DOCKER_LOG"
  CHOWN_LOG="$CASE_DIR/chown.log"; : > "$CHOWN_LOG"
  env "DOCKER_LOG=$DOCKER_LOG" "CHOWN_LOG=$CHOWN_LOG" \
      "LAIMORY_ENV_FILE=$CASE_DIR/.env" "LAIMORY_FCM_CRED_FILE=$CASE_DIR/cred.json" \
      "PATH=$STUB:$PATH" "$@" /bin/bash "$SCRIPT_FILE" > "$CASE_DIR/out.log" 2>&1
  RC=$?
}

assert_env_untouched() {
  cmp -s "$CASE_DIR/.env" "$CASE_DIR/.env.orig" || fail "$1: .env must be byte-identical after pre-stop failure"
}

assert_no_stop_no_run() {
  [ "$(grep -c '^docker stop' "$CASE_DIR/docker.log" || true)" = "0" ] || fail "$1: docker stop must not run"
  [ "$(grep -c '^docker run -d' "$CASE_DIR/docker.log" || true)" = "0" ] || fail "$1: docker run -d must not run"
}

assert_prune_once() {
  [ "$(grep -c '^docker image prune -af' "$CASE_DIR/docker.log" || true)" = "1" ] \
    || fail "$1: docker image prune -af must run exactly once"
}

assert_no_sentinel() {
  if grep -qF "$SENTINEL" "$CASE_DIR/out.log"; then
    fail "$1: secret sentinel leaked to stdout/stderr"
  fi
}

assert_sha_line() {
  [ "$(grep -c '^APP_COMMIT_SHA=' "$CASE_DIR/.env" || true)" = "1" ] || fail "$1: APP_COMMIT_SHA must be exactly one line"
  grep -qxF "APP_COMMIT_SHA=$FAKE_SHA" "$CASE_DIR/.env" || fail "$1: APP_COMMIT_SHA must equal deployed SHA"
  grep -v '^APP_COMMIT_SHA=' "$CASE_DIR/.env" > "$CASE_DIR/.env.rest"
  grep -v '^APP_COMMIT_SHA=' "$CASE_DIR/.env.orig" > "$CASE_DIR/.env.orig.rest"
  cmp -s "$CASE_DIR/.env.rest" "$CASE_DIR/.env.orig.rest" || fail "$1: non-target .env lines must be preserved byte-exact"
  [ "$(file_mode "$CASE_DIR/.env")" = "600" ] || fail "$1: .env mode must remain 600"
  ls "$CASE_DIR"/.env.?????? >/dev/null 2>&1 && fail "$1: temp .env file must not remain"
  grep -q -- "--reference=" "$CHOWN_LOG" || fail "$1: owner must be preserved via chown --reference"
}

# --- 6. T2: APP_COMMIT_SHA upsert(0/1/복수 + 유사 key 보존) ---
for dup in 0 1 3 ; do
  new_case; base_env_fixture
  i=0
  while [ "$i" -lt "$dup" ]; do
    echo "APP_COMMIT_SHA=old-sha-$i" >> "$CASE_DIR/.env"
    i=$((i + 1))
  done
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" = "0" ] || fail "T2(dup=$dup): success deploy expected, rc=$RC ($(cat "$CASE_DIR/out.log"))"
  assert_sha_line "T2(dup=$dup)"
  grep -qxF "XAPP_COMMIT_SHA=lookalike-must-survive" "$CASE_DIR/.env" || fail "T2: lookalike key must survive"
  grep -qxF "APP_COMMIT_SHA_OLD=lookalike-must-survive-2" "$CASE_DIR/.env" || fail "T2: lookalike key must survive"
  assert_prune_once "T2(dup=$dup)"
  assert_no_sentinel "T2(dup=$dup)"
done
ok "T2: atomic upsert converges to exactly one APP_COMMIT_SHA line (0/1/3 dups)"

# --- 7. T3: upsert 각 단계 실패 시 .env/SHA 보존 + stop 미진입 ---
for failure in FAKE_MKTEMP_FAIL FAKE_AWK_FAIL FAKE_CHMOD_FAIL FAKE_CHOWN_EXIT FAKE_MV_FAIL ; do
  new_case; base_env_fixture
  echo "APP_COMMIT_SHA=previous-sha" >> "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script "$failure=1"
  [ "$RC" != "0" ] || fail "T3($failure): failure expected"
  assert_env_untouched "T3($failure)"
  grep -qxF "APP_COMMIT_SHA=previous-sha" "$CASE_DIR/.env" || fail "T3($failure): previous SHA must be preserved"
  assert_no_stop_no_run "T3($failure)"
  assert_prune_once "T3($failure)"
  ls "$CASE_DIR"/.env.?????? >/dev/null 2>&1 && fail "T3($failure): temp .env must be cleaned by trap"
  assert_no_sentinel "T3($failure)"
done
ok "T3: upsert failures preserve .env bytes/SHA and never reach docker stop"

# --- 8. T3a: pre-stop 각 지점 실패의 보존 계약(login/pull/UID/nginx) ---
run_prestop_failure() {
  # $1 label, $2 fixture(base|firebase), $3 env override
  new_case
  if [ "$2" = "firebase" ]; then make_firebase_fixture; else base_env_fixture; fi
  execute_script "$3"
  [ "$RC" != "0" ] || fail "T3a($1): failure expected"
  assert_env_untouched "T3a($1)"
  assert_no_stop_no_run "T3a($1)"
  assert_prune_once "T3a($1)"
  assert_no_sentinel "T3a($1)"
}
run_prestop_failure "nginx" base "FAKE_NGINX_EXIT=1"
run_prestop_failure "login" base "FAKE_LOGIN_EXIT=1"
run_prestop_failure "pull" base "FAKE_PULL_EXIT=1"
run_prestop_failure "uid-check" firebase "FAKE_UID_CHECK_EXIT=1"
ok "T3a: nginx/login/pull/UID failures preserve .env and never stop the old container"

# --- 9. T5b: dev 고정 key + APP_AI_MODE exact-one fail-closed(누락·오값·중복) ---
mutate_env() {
  # $1 key, $2 mutation(missing|wrong|dup)
  case "$2" in
    missing) grep -v "^$1=" "$CASE_DIR/.env" > "$CASE_DIR/.env.new" && mv "$CASE_DIR/.env.new" "$CASE_DIR/.env" ;;
    wrong) grep -v "^$1=" "$CASE_DIR/.env" > "$CASE_DIR/.env.new" && mv "$CASE_DIR/.env.new" "$CASE_DIR/.env"
           echo "$1=unexpected-value" >> "$CASE_DIR/.env" ;;
    dup) grep "^$1=" "$CASE_DIR/.env" | head -1 >> "$CASE_DIR/.env" ;;
  esac
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
}
for key in REDIS_KEY_PREFIX APP_ENV APP_GEO_MODE SWAGGER_ENABLED APP_AI_MODE ; do
  for mutation in missing wrong dup ; do
    new_case; base_env_fixture
    mutate_env "$key" "$mutation"
    execute_script
    [ "$RC" != "0" ] || fail "T5b($key/$mutation): preflight failure expected"
    grep -q "PREFLIGHT FAILED: .env $key" "$CASE_DIR/out.log" || fail "T5b($key/$mutation): key-only diagnostic expected"
    assert_env_untouched "T5b($key/$mutation)"
    assert_no_stop_no_run "T5b($key/$mutation)"
    assert_prune_once "T5b($key/$mutation)"
    assert_no_sentinel "T5b($key/$mutation)"
  done
done
ok "T5b: fixed dev keys and APP_AI_MODE fail closed on missing/wrong/duplicate lines"

# --- 10. T5b(http): base URL 필수 계약 ---
for mutation in missing empty dup ; do
  new_case; base_env_fixture
  PATH=/usr/bin:/bin sed -i.bak 's/^APP_AI_MODE=fake$/APP_AI_MODE=http/' "$CASE_DIR/.env"
  rm -f "$CASE_DIR/.env.bak"
  case "$mutation" in
    missing) : ;;
    empty) echo "APP_AI_HTTP_BASE_URL=" >> "$CASE_DIR/.env" ;;
    dup) echo "APP_AI_HTTP_BASE_URL=http://ai-1.test" >> "$CASE_DIR/.env"
         echo "APP_AI_HTTP_BASE_URL=http://ai-2.test" >> "$CASE_DIR/.env" ;;
  esac
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" != "0" ] || fail "T5b(http/$mutation): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env APP_AI_HTTP_BASE_URL" "$CASE_DIR/out.log" || fail "T5b(http/$mutation): diagnostic expected"
  assert_no_stop_no_run "T5b(http/$mutation)"
  assert_prune_once "T5b(http/$mutation)"
done
new_case; base_env_fixture
PATH=/usr/bin:/bin sed -i.bak 's/^APP_AI_MODE=fake$/APP_AI_MODE=http/' "$CASE_DIR/.env"
rm -f "$CASE_DIR/.env.bak"
echo "APP_AI_HTTP_BASE_URL=http://ai.internal.test" >> "$CASE_DIR/.env"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" = "0" ] || fail "T5b(http/valid): success expected, rc=$RC ($(cat "$CASE_DIR/out.log"))"
ok "T5b(http): APP_AI_HTTP_BASE_URL exact-one non-empty enforced only for http mode"

# --- 11. T5/T5a: push mode 계약 ---
new_case; base_env_fixture
execute_script
[ "$RC" = "0" ] || fail "T5(noop): success expected, rc=$RC"
grep -q '^docker run --rm' "$CASE_DIR/docker.log" && fail "T5(noop): UID check must be skipped"
NOOP_RUN=$(grep '^docker run -d' "$CASE_DIR/docker.log")
printf '%s\n' "$NOOP_RUN" | grep -q -- '-v ' && fail "T5(noop): no credential mount expected"
printf '%s\n' "$NOOP_RUN" | grep -qE ' (-e|--env)[ =]' && fail "T5(noop): no -e expected"
ok "T5: noop mode runs without credential check, mount, or -e"

for mutation in missing wrong dup ; do
  new_case; base_env_fixture
  mutate_env "APP_PUSH_MODE" "$mutation"
  execute_script
  [ "$RC" != "0" ] || fail "T5a(push/$mutation): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env APP_PUSH_MODE" "$CASE_DIR/out.log" || fail "T5a(push/$mutation): diagnostic expected"
  assert_no_stop_no_run "T5a(push/$mutation)"
  assert_prune_once "T5a(push/$mutation)"
done
for adc_mutation in missing wrong dup ; do
  new_case; make_firebase_fixture
  mutate_env "GOOGLE_APPLICATION_CREDENTIALS" "$adc_mutation"
  execute_script
  [ "$RC" != "0" ] || fail "T5a(adc/$adc_mutation): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env GOOGLE_APPLICATION_CREDENTIALS" "$CASE_DIR/out.log" \
    || fail "T5a(adc/$adc_mutation): diagnostic expected"
  assert_no_stop_no_run "T5a(adc/$adc_mutation)"
  assert_prune_once "T5a(adc/$adc_mutation)"
done
ok "T5a: push mode and firebase ADC path fail closed before stopping the old container"

# --- 12. T6: firebase host credential file 검사(없음/빈 파일) ---
new_case; make_firebase_fixture
rm -f "$CASE_DIR/cred.json"
execute_script
[ "$RC" != "0" ] || fail "T6(missing): failure expected"
assert_no_stop_no_run "T6(missing)"
assert_prune_once "T6(missing)"
new_case; make_firebase_fixture
: > "$CASE_DIR/cred.json"
execute_script
[ "$RC" != "0" ] || fail "T6(empty): failure expected"
assert_no_stop_no_run "T6(empty)"
ok "T6: firebase requires an existing non-empty host credential file before stop"

# --- 13. T7: firebase 정상 경로 — read-only mount만 추가, ADC는 .env 소유 ---
new_case; make_firebase_fixture
execute_script
[ "$RC" = "0" ] || fail "T7: success expected, rc=$RC ($(cat "$CASE_DIR/out.log"))"
grep -q '^docker run --rm' "$CASE_DIR/docker.log" || fail "T7: UID 1001 readability check expected"
FB_RUN=$(grep '^docker run -d' "$CASE_DIR/docker.log")
printf '%s\n' "$FB_RUN" | grep -q -- "-v $CASE_DIR/cred.json:/run/secrets/firebase-service-account.json:ro" \
  || fail "T7: read-only credential mount expected"
printf '%s\n' "$FB_RUN" | grep -qE ' (-e|--env)[ =]' && fail "T7: no -e expected in firebase run"
printf '%s\n' "$FB_RUN" | grep -q 'GOOGLE_APPLICATION_CREDENTIALS' && fail "T7: ADC path must come from .env only"
UID_LN=$(grep -n '^docker run --rm' "$CASE_DIR/docker.log" | head -1 | cut -d: -f1)
STOP_LOG_LN=$(grep -n '^docker stop' "$CASE_DIR/docker.log" | head -1 | cut -d: -f1)
[ "$UID_LN" -lt "$STOP_LOG_LN" ] || fail "T7: UID check must run before stopping the old container"
assert_sha_line "T7"
assert_prune_once "T7"
assert_no_sentinel "T7"
ok "T7: firebase adds read-only mount only; ADC path stays in .env"

# --- 14. T8: run/health 실패 경로에서도 prune 1회 + 원래 실패 status ---
new_case; base_env_fixture
execute_script "FAKE_RUN_EXIT=1"
[ "$RC" != "0" ] || fail "T8(run): failure expected"
assert_prune_once "T8(run)"
new_case; base_env_fixture
execute_script "FAKE_CURL_EXIT=22"
[ "$RC" = "1" ] || fail "T8(health): health failure must exit 1, rc=$RC"
grep -q "HEALTH CHECK FAILED" "$CASE_DIR/out.log" || fail "T8(health): health failure message expected"
grep -q '^docker logs --tail 80 laimory' "$CASE_DIR/docker.log" || fail "T8(health): log tail expected"
assert_prune_once "T8(health)"
LAST_DOCKER=$(tail -1 "$CASE_DIR/docker.log")
printf '%s\n' "$LAST_DOCKER" | grep -q '^docker image prune -af' || fail "T8: prune must be the final docker call"
ok "T8: every failure path prunes exactly once and keeps its own exit status"

# --- 15. T9/T10: prune 실패는 배포 status를 바꾸지 않는다 ---
new_case; base_env_fixture
execute_script "FAKE_PRUNE_EXIT=1"
[ "$RC" = "0" ] || fail "T9: successful deploy must stay rc=0 when prune fails, rc=$RC"
grep -q "WARNING: docker image prune failed (deploy status unchanged)" "$CASE_DIR/out.log" \
  || fail "T9: fixed prune warning expected"
assert_no_sentinel "T9"
new_case; base_env_fixture
execute_script "FAKE_PULL_EXIT=3"
[ "$RC" = "3" ] || fail "T10: original non-zero status must be preserved, rc=$RC"
new_case; base_env_fixture
execute_script "FAKE_PULL_EXIT=3" "FAKE_PRUNE_EXIT=1"
[ "$RC" = "3" ] || fail "T10: prune failure must not mask original status, rc=$RC"
grep -q "WARNING: docker image prune failed (deploy status unchanged)" "$CASE_DIR/out.log" \
  || fail "T10: fixed prune warning expected"
ok "T9/T10: prune result never masks the deploy status (0 stays 0, 3 stays 3)"

echo "PASS: deploy contract harness ($PASS groups)"
