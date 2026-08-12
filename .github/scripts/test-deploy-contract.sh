#!/usr/bin/env bash
# deploy.yml SSM 원격 배포 script의 계약 테스트 harness.
#
# production heredoc 본문을 deploy.yml에서 그대로 추출해(복사본 금지) 러너와 동일한 unquoted-heredoc
# 확장으로 SCRIPT를 재현한 뒤, fake docker/aws/curl/nginx PATH와 temp .env fixture로 실행한다.
# LAIMORY_ENV_FILE / LAIMORY_FCM_CRED_FILE seam으로 운영 경로 대신 fixture 경로를 준다.
# 검증 계약: 계획 #197의 T1~T10 — exact-one preflight fail-closed, APP_COMMIT_SHA 원자 upsert,
# pre-stop 실패의 .env/SHA 보존, secret 비출력, 모든 종료 경로의 prune -af 1회와 원래 status 보존.
# T5d(#282): subject mapping preflight — APP_SUBJECT_MODE=secretsmanager 고정, secret ARN 형식,
# region/runtime-role 일치, secret read, DB_* presence와 user_subject_links schema 검사의 fail-closed·값 비출력.
# T5e(#282 리뷰): secret 내용 계약 — 앱 parse()와 동일 규칙(JSON object·version·32-byte key·previous 쌍·
# SecretString 부재)의 fail-closed·항목 이름만 진단·payload 비출력, SPRING_PROFILES_ACTIVE docker 가드.
# T0(#285): workflow 레벨 계약 — manual deploy-existing(workflow_dispatch + required SHA/digest),
# push pause gate, dispatch build skip, SSM 전 ECR tag↔digest 일치 검증, digest pull,
# build-only.yml의 exact SHA checkout·digest 기록.
#
# 실행: bash .github/scripts/test-deploy-contract.sh  (macOS bash 3.2 / Linux bash 호환)
set -u

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$REPO_ROOT/.github/workflows/deploy.yml"
BUILD_ONLY="$REPO_ROOT/.github/workflows/build-only.yml"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

FAKE_SHA="1234567890abcdef1234567890abcdef12345678"
FAKE_DIGEST="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
SENTINEL="SENTINEL_SECRET_XYZZY"
# 합성 base64 key fixture(실제 secret 아님): 32바이트 2종(유효)과 31바이트(오염) — 앱 parse() 계약용.
B64_KEY_32A="QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
B64_KEY_32B="QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
B64_KEY_31="QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ=="
PASS=0

fail() { echo "FAIL: $1" >&2; exit 1; }
ok() { PASS=$((PASS + 1)); echo "ok - $1"; }

file_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then stat -f '%Lp' "$1"; else stat -c '%a' "$1"; fi
}

# --- 1. production script 본문 추출(러너의 YAML 디코딩과 동일하게 YAML 파서 사용) ---
# T0(#285): workflow 레벨 계약(pause gate·deploy-existing dispatch·build-only)도 같은 파서로 검증한다.
[ -f "$BUILD_ONLY" ] || fail "build-only workflow file missing: $BUILD_ONLY"
ruby -ryaml -e '
  q = 39.chr
  load_yaml = lambda do |path|
    src = File.read(path)
    begin
      YAML.unsafe_load(src)
    rescue NoMethodError
      YAML.load(src)
    end
  end
  wf = load_yaml.call(ARGV[0])
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
  abort "workflow_dispatch trigger missing" unless triggers.key?("workflow_dispatch")
  image_sha = triggers.dig("workflow_dispatch", "inputs", "image_sha")
  abort "workflow_dispatch image_sha input missing" unless image_sha
  abort "image_sha input must be required" unless image_sha["required"] == true
  image_digest = triggers.dig("workflow_dispatch", "inputs", "image_digest")
  abort "workflow_dispatch image_digest input missing" unless image_digest
  abort "image_digest input must be required" unless image_digest["required"] == true
  push_only = "github.event_name == #{q}push#{q}"
  dispatch_only = "github.event_name == #{q}workflow_dispatch#{q}"
  gate_true = "steps.gate.outputs.deploy == #{q}true#{q}"
  steps = wf["jobs"]["deploy"]["steps"]
  gate = steps.find { |s| s["id"] == "gate" }
  abort "pause gate step missing" unless gate
  abort "pause gate must read vars.DEPLOY_PAUSED" unless gate.dig("env", "DEPLOY_PAUSED").to_s.include?("vars.DEPLOY_PAUSED")
  abort "pause gate must pause only push events" unless gate["run"].to_s.include?("\"$GITHUB_EVENT_NAME\" = \"push\"")
  abort "pause gate must check DEPLOY_PAUSED value" unless gate["run"].to_s.include?("\"$DEPLOY_PAUSED\" = \"true\"")
  abort "pause gate must log deploy paused" unless gate["run"].to_s.include?("deploy paused")
  build = steps.find { |s| s["name"] == "Build & push image" }
  abort "build step not found" unless build
  abort "build step must run only on push events" unless build["if"].to_s.include?(push_only)
  abort "build step must respect the pause gate" unless build["if"].to_s.include?(gate_true)
  ecr_check = steps.find { |s| s["run"].to_s.include?("aws ecr batch-get-image") }
  abort "deploy-existing ECR pre-check step missing" unless ecr_check
  abort "ECR pre-check must run only on manual dispatch" unless ecr_check["if"].to_s.include?(dispatch_only)
  abort "ECR pre-check must compare the recorded digest" unless ecr_check["run"].to_s.include?("ACTUAL_IMAGE_DIGEST") && ecr_check["run"].to_s.include?("EXPECTED_IMAGE_DIGEST")
  abort "deploy-existing must not require DescribeImages" if ecr_check["run"].to_s.include?("describe-images")
  step = steps.find { |s| s["id"] == "ssm" }
  abort "ssm step not found" unless step
  abort "ssm step must respect the pause gate" unless step["if"].to_s.include?(gate_true)
  abort "ssm IMAGE_TAG must come from inputs.image_sha on dispatch" unless step.dig("env", "IMAGE_TAG").to_s.include?("inputs.image_sha")
  abort "ssm IMAGE_DIGEST must come from inputs.image_digest on dispatch" unless step.dig("env", "IMAGE_DIGEST").to_s.include?("inputs.image_digest")
  abort "deploy-existing must pull by digest" unless step["run"].to_s.include?("@$IMAGE_DIGEST")
  bo = load_yaml.call(ARGV[1])
  bo_triggers = bo["on"] || bo[true]
  abort "build-only must be workflow_dispatch-only (no push trigger)" unless bo_triggers.is_a?(Hash) && bo_triggers.keys == ["workflow_dispatch"]
  bo_image_sha = bo_triggers.dig("workflow_dispatch", "inputs", "image_sha")
  abort "build-only image_sha input must be required" unless bo_image_sha && bo_image_sha["required"] == true
  bo_steps = bo["jobs"]["build-only"]["steps"]
  checkout = bo_steps.find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
  abort "build-only checkout must use exact input SHA" unless checkout.dig("with", "ref").to_s.include?("inputs.image_sha")
  rev = bo_steps.find { |s| s["id"] == "rev" }
  abort "build-only must compare checked-out SHA with input" unless rev["run"].to_s.include?("EXPECTED_SHA")
  image = bo_steps.find { |s| s["id"] == "image" }
  abort "build-only must record the ECR digest" unless image && image["run"].to_s.include?("aws ecr batch-get-image") && image["run"].to_s.include?("images[0].imageId.imageDigest") && image["run"].to_s.include?("GITHUB_OUTPUT")
  abort "build-only must not require DescribeImages" if image["run"].to_s.include?("describe-images")
  print step["run"]
' "$WORKFLOW" "$BUILD_ONLY" > "$WORK/ssm_run.sh" || fail "extract ssm step run block / workflow-level contract"
ok "T0: pause gate, deploy-existing dispatch and build-only workflow contract"

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
    "GITHUB_EVENT_NAME=push" "IMAGE_TAG=$FAKE_SHA" "IMAGE_DIGEST=$FAKE_DIGEST" \
    "IMG=registry.test/laimory:$FAKE_SHA" \
    /bin/bash "$WORK/driver.sh" > "$WORK/remote_script.sh" || fail "runner heredoc expansion"
SCRIPT_FILE="$WORK/remote_script.sh"
grep -q "APP_COMMIT_SHA=\" sha" "$SCRIPT_FILE" || fail "expanded script missing upsert awk"
grep -Fq "COLUMN_NAME = 'subject_id' AND COLUMN_TYPE = 'varchar(36)'" "$SCRIPT_FILE" \
  || fail "T5d: subject schema preflight must require VARCHAR(36)"
grep -Fq "CHARACTER_SET_NAME = 'ascii' AND COLLATION_NAME = 'ascii_bin'" "$SCRIPT_FILE" \
  || fail "T5d: subject schema preflight must require ascii/ascii_bin"
if grep -Fq "COLUMN_NAME = 'subject_id' AND COLUMN_TYPE = 'binary(16)'" "$SCRIPT_FILE"; then
  fail "T5d: subject schema preflight must not retain the pre-cutover BINARY(16) contract"
fi

# manual deploy-existing는 tag가 아닌 기록한 digest reference를 remote pull/run에 쓴다.
env "AWS_REGION=ap-test-1" "REGISTRY=registry.test" "ECR_REPOSITORY=laimory" \
    "GITHUB_EVENT_NAME=workflow_dispatch" "IMAGE_TAG=$FAKE_SHA" "IMAGE_DIGEST=$FAKE_DIGEST" \
    "IMG=registry.test/laimory@$FAKE_DIGEST" \
    /bin/bash "$WORK/driver.sh" > "$WORK/remote_dispatch_script.sh" \
    || fail "runner deploy-existing heredoc expansion"
grep -q "^ *docker pull registry.test/laimory@$FAKE_DIGEST$" "$WORK/remote_dispatch_script.sh" \
  || fail "T0: deploy-existing remote pull must use the recorded digest"
grep -q "^ *docker run -d .* registry.test/laimory@$FAKE_DIGEST$" "$WORK/remote_dispatch_script.sh" \
  || fail "T0: deploy-existing remote run must use the recorded digest"

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
SUBJECT_LN=$(ln_of 'APP_SUBJECT_MODE')
SCHEMA_LN=$(ln_of 'mysql:8.0')
MKTEMP_LN=$(ln_of 'mktemp')
STOP_LN=$(ln_of '^docker stop laimory')
{ [ -n "$TRAP_LN" ] && [ -n "$FIRST_CHECK_LN" ] && [ -n "$PULL_LN" ] && [ -n "$SUBJECT_LN" ] \
  && [ -n "$SCHEMA_LN" ] && [ -n "$MKTEMP_LN" ] && [ -n "$STOP_LN" ]; } \
  || fail "order: expected markers not found in expanded script"
[ "$TRAP_LN" -lt "$FIRST_CHECK_LN" ] || fail "order: trap must be installed before first failable check"
[ "$PULL_LN" -lt "$SUBJECT_LN" ] || fail "order: subject preflight must run after docker pull"
[ "$SUBJECT_LN" -lt "$SCHEMA_LN" ] || fail "order: mode/ARN checks must precede the schema check"
[ "$SCHEMA_LN" -lt "$MKTEMP_LN" ] || fail "order: subject schema check must run before APP_COMMIT_SHA upsert"
[ "$PULL_LN" -lt "$MKTEMP_LN" ] || fail "order: APP_COMMIT_SHA upsert must run after docker pull"
[ "$MKTEMP_LN" -lt "$STOP_LN" ] || fail "order: APP_COMMIT_SHA upsert must run before docker stop"
ok "T1/order: single --env-file run without -e; trap->preflight->pull->subject-preflight->upsert->stop"

# --- 4. fake PATH stubs ---
STUB="$WORK/stub"
mkdir -p "$STUB"

cat > "$STUB/docker" <<'STUBEOF'
#!/usr/bin/env bash
echo "docker $*" >> "${DOCKER_LOG:?}"
case "$1" in
  login) cat >/dev/null 2>&1 || true; exit "${FAKE_LOGIN_EXIT:-0}" ;;
  pull) exit "${FAKE_PULL_EXIT:-0}" ;;
  run)
    # subject schema preflight의 mysql:8.0 one-shot run은 UID check와 별도 seam으로 제어한다.
    # 성공 시 schema 질의의 exact-shape 판정(기본 1)만 stdout으로 낸다 — row/값 출력 없음.
    case " $* " in
      *" mysql:8.0 "*)
        [ "${FAKE_MYSQL_EXIT:-0}" = "0" ] && echo "${FAKE_MYSQL_OUTPUT:-1}"
        exit "${FAKE_MYSQL_EXIT:-0}" ;;
    esac
    if [ "$2" = "--rm" ]; then exit "${FAKE_UID_CHECK_EXIT:-0}"; else exit "${FAKE_RUN_EXIT:-0}"; fi ;;
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
# secretsmanager 경로는 SecretString 검증의 seam이다 — FAKE_SECRETSMANAGER_EXIT로 read 실패,
# FAKE_SECRET_STRING_ABSENT=1로 SecretBinary 전용(SecretString null), FAKE_SECRET_JSON으로 오염
# fixture를 재현한다. 기본은 유효 fixture(합성 base64 32-byte key + sentinel 잉여 필드 — 앱 parse()가
# unknown 필드를 무시하므로 preflight도 통과해야 하고, sentinel은 payload 누출 검출용). 출력은 실제
# aws --query SecretString --output json처럼 JSON 문자열 리터럴 형태다. ecr login 경로는 기존 동작 유지.
if [ "$1" = "secretsmanager" ]; then
  [ "${FAKE_SECRETSMANAGER_EXIT:-0}" = "0" ] || exit "${FAKE_SECRETSMANAGER_EXIT}"
  if [ "${FAKE_SECRET_STRING_ABSENT:-0}" = "1" ]; then
    echo null
    exit 0
  fi
  DEFAULT_SECRET_JSON='{"currentVersion":2,"currentKey":"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=","note":"SENTINEL_SECRET_XYZZY"}'
  FAKE_SECRET_JSON="${FAKE_SECRET_JSON-$DEFAULT_SECRET_JSON}" \
    python3 -c 'import json, os; print(json.dumps(os.environ["FAKE_SECRET_JSON"]))'
  exit 0
fi
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
APP_TRACING_MODE=noop
APP_SUBJECT_MODE=secretsmanager
APP_SUBJECT_SECRET_ARN=arn:aws:secretsmanager:ap-northeast-2:000000000000:secret:fixture
DB_HOST=db-host.fixture.test
DB_USERNAME=db-user-fixture
DB_PASSWORD=${SENTINEL}_db-password

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
for key in REDIS_KEY_PREFIX APP_ENV APP_GEO_MODE SWAGGER_ENABLED APP_AI_MODE APP_TRACING_MODE \
  APP_SUBJECT_MODE APP_SUBJECT_SECRET_ARN ; do
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
ok "T5b: fixed dev keys, APP_AI_MODE and subject mode/ARN fail closed on missing/wrong/duplicate lines"

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

# --- 10a. T5c: tracing 계약 — otlp 필수 세트 fail-closed + noop 잔존 금지 ---
TRACING_REQUIRED_KEYS="JAVA_TOOL_OPTIONS OTEL_SERVICE_NAME OTEL_EXPORTER_OTLP_ENDPOINT \
OTEL_EXPORTER_OTLP_PROTOCOL OTEL_TRACES_SAMPLER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER \
OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED \
OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS"

make_otlp_fixture() {
  base_env_fixture
  PATH=/usr/bin:/bin sed -i.bak 's/^APP_TRACING_MODE=noop$/APP_TRACING_MODE=otlp/' "$CASE_DIR/.env"
  rm -f "$CASE_DIR/.env.bak"
  cat >> "$CASE_DIR/.env" <<'TRACING'
JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=laimory-dev
OTEL_EXPORTER_OTLP_ENDPOINT=http://10.0.32.14:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_TRACES_SAMPLER=always_on
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED=true
OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS=AWSAccessKeyId,Signature,sig,X-Goog-Signature,code,state,app_challenge,x,y,query
TRACING
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
}

new_case; make_otlp_fixture
execute_script
[ "$RC" = "0" ] || fail "T5c(otlp/valid): success expected, rc=$RC ($(cat "$CASE_DIR/out.log"))"
OTLP_RUN=$(grep '^docker run -d' "$CASE_DIR/docker.log")
printf '%s\n' "$OTLP_RUN" | grep -qE ' (-e|--env)[ =]' && fail "T5c(otlp/valid): no -e expected"
assert_prune_once "T5c(otlp/valid)"
assert_no_sentinel "T5c(otlp/valid)"

for key in $TRACING_REQUIRED_KEYS ; do
  new_case; make_otlp_fixture
  grep -v "^$key=" "$CASE_DIR/.env" > "$CASE_DIR/.env.new" && mv "$CASE_DIR/.env.new" "$CASE_DIR/.env"
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" != "0" ] || fail "T5c(otlp/missing $key): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env $key" "$CASE_DIR/out.log" || fail "T5c(otlp/missing $key): diagnostic expected"
  assert_env_untouched "T5c(otlp/missing $key)"
  assert_no_stop_no_run "T5c(otlp/missing $key)"
  assert_prune_once "T5c(otlp/missing $key)"
done

# 값 오류: 잘못된 protocol/endpoint는 trace를 조용히 유실하고, redaction 부분 목록은 full-override라
# 기본 서명 4종까지 벗겨진다 — dev 고정값은 byte 단위로 실패해야 한다.
for wrong in "OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf" "OTEL_METRICS_EXPORTER=otlp" "OTEL_LOGS_EXPORTER=otlp" "OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED=false" "OTEL_SERVICE_NAME=laimory-prod" "OTEL_EXPORTER_OTLP_ENDPOINT=http://10.0.32.99:4317" "OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS=code" ; do
  key=${wrong%%=*}
  new_case; make_otlp_fixture
  grep -v "^$key=" "$CASE_DIR/.env" > "$CASE_DIR/.env.new" && mv "$CASE_DIR/.env.new" "$CASE_DIR/.env"
  echo "$wrong" >> "$CASE_DIR/.env"
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" != "0" ] || fail "T5c(otlp/wrong $key): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env $key" "$CASE_DIR/out.log" || fail "T5c(otlp/wrong $key): diagnostic expected"
  assert_no_stop_no_run "T5c(otlp/wrong $key)"
  assert_prune_once "T5c(otlp/wrong $key)"
done

# sampler만 값 미고정 계약: 부하 테스트의 ratio 일시 전환(D4)이 pre-flight에 막히면 안 된다.
new_case; make_otlp_fixture
PATH=/usr/bin:/bin sed -i.bak 's/^OTEL_TRACES_SAMPLER=always_on$/OTEL_TRACES_SAMPLER=parentbased_traceidratio/' "$CASE_DIR/.env"
rm -f "$CASE_DIR/.env.bak"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" = "0" ] || fail "T5c(otlp/sampler-flex): ratio sampler must pass, rc=$RC ($(cat "$CASE_DIR/out.log"))"

# 중복: exact-one 위반은 --env-file 해석 순서에 기대지 않고 실패해야 한다.
new_case; make_otlp_fixture
echo "JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar" >> "$CASE_DIR/.env"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" != "0" ] || fail "T5c(otlp/dup): preflight failure expected"
grep -q "PREFLIGHT FAILED: .env JAVA_TOOL_OPTIONS" "$CASE_DIR/out.log" || fail "T5c(otlp/dup): diagnostic expected"
assert_no_stop_no_run "T5c(otlp/dup)"
assert_prune_once "T5c(otlp/dup)"

# noop 잔존 금지: 스위치만 내리고 agent env가 남는 "조용한 부분 off"를 차단한다.
for residue in "JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar" "OTEL_TRACES_SAMPLER=always_on" ; do
  residue_key=${residue%%=*}
  new_case; base_env_fixture
  echo "$residue" >> "$CASE_DIR/.env"
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" != "0" ] || fail "T5c(noop/residue $residue_key): preflight failure expected"
  grep -q "must not be set when APP_TRACING_MODE=noop" "$CASE_DIR/out.log" || fail "T5c(noop/residue $residue_key): diagnostic expected"
  assert_env_untouched "T5c(noop/residue $residue_key)"
  assert_no_stop_no_run "T5c(noop/residue $residue_key)"
  assert_prune_once "T5c(noop/residue $residue_key)"
done
ok "T5c: tracing contract fails closed (otlp full set exact-one with values, noop forbids residue)"

# --- 10b. T5d: subject mapping 계약(#282) — mode 고정값·secret read·DB 접속·schema fail-closed ---
# fixture mode 차단: 배포 환경에서 secretsmanager 외 값(테스트 고정 key)은 값 자체로 실패해야 한다.
new_case; base_env_fixture
PATH=/usr/bin:/bin sed -i.bak 's/^APP_SUBJECT_MODE=secretsmanager$/APP_SUBJECT_MODE=fixture/' "$CASE_DIR/.env"
rm -f "$CASE_DIR/.env.bak"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" != "0" ] || fail "T5d(mode=fixture): preflight failure expected"
grep -q "PREFLIGHT FAILED: .env APP_SUBJECT_MODE must be secretsmanager" "$CASE_DIR/out.log" \
  || fail "T5d(mode=fixture): diagnostic expected"
assert_env_untouched "T5d(mode=fixture)"
assert_no_stop_no_run "T5d(mode=fixture)"
assert_prune_once "T5d(mode=fixture)"
assert_no_sentinel "T5d(mode=fixture)"

# 앱과 host preflight가 같은 region/runtime role을 쓰도록 region mismatch·credential override를 차단한다.
new_case; base_env_fixture
printf '%s\n' 'AWS_REGION=us-east-1' >> "$CASE_DIR/.env"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" != "0" ] || fail "T5d(region mismatch): preflight failure expected"
grep -q "PREFLIGHT FAILED: .env AWS_REGION must match deployment region" "$CASE_DIR/out.log" \
  || fail "T5d(region mismatch): diagnostic expected"
assert_env_untouched "T5d(region mismatch)"
assert_no_stop_no_run "T5d(region mismatch)"
assert_prune_once "T5d(region mismatch)"
assert_no_sentinel "T5d(region mismatch)"

new_case; base_env_fixture
printf '%s\n' "AWS_ACCESS_KEY_ID=${SENTINEL}_aws-access-key" >> "$CASE_DIR/.env"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" != "0" ] || fail "T5d(credential override): preflight failure expected"
grep -q "PREFLIGHT FAILED: .env AWS credential/profile/endpoint overrides are forbidden" "$CASE_DIR/out.log" \
  || fail "T5d(credential override): diagnostic expected"
assert_env_untouched "T5d(credential override)"
assert_no_stop_no_run "T5d(credential override)"
assert_prune_once "T5d(credential override)"
assert_no_sentinel "T5d(credential override)"

# DB 접속 계약: presence exact-one(누락·중복)과 non-empty — 진단은 key 이름·개수까지만(값 비출력).
for db_key in DB_HOST DB_USERNAME DB_PASSWORD ; do
  for mutation in missing dup ; do
    new_case; base_env_fixture
    mutate_env "$db_key" "$mutation"
    execute_script
    [ "$RC" != "0" ] || fail "T5d($db_key/$mutation): preflight failure expected"
    grep -q "PREFLIGHT FAILED: .env $db_key" "$CASE_DIR/out.log" || fail "T5d($db_key/$mutation): key-only diagnostic expected"
    assert_env_untouched "T5d($db_key/$mutation)"
    assert_no_stop_no_run "T5d($db_key/$mutation)"
    assert_prune_once "T5d($db_key/$mutation)"
    assert_no_sentinel "T5d($db_key/$mutation)"
  done
done
new_case; base_env_fixture
PATH=/usr/bin:/bin sed -i.bak 's/^DB_PASSWORD=.*$/DB_PASSWORD=/' "$CASE_DIR/.env"
rm -f "$CASE_DIR/.env.bak"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" != "0" ] || fail "T5d(DB_PASSWORD/empty): preflight failure expected"
grep -q "PREFLIGHT FAILED: .env DB_PASSWORD must be non-empty" "$CASE_DIR/out.log" \
  || fail "T5d(DB_PASSWORD/empty): diagnostic expected"
assert_no_stop_no_run "T5d(DB_PASSWORD/empty)"
assert_prune_once "T5d(DB_PASSWORD/empty)"
assert_no_sentinel "T5d(DB_PASSWORD/empty)"

# runtime secret read 실패: host role 권한/ARN 문제는 구 컨테이너 중지 전에 잡힌다(.env/SHA 보존).
run_prestop_failure "subject-secret-read" base "FAKE_SECRETSMANAGER_EXIT=1"
grep -q "PREFLIGHT FAILED: subject secret read failed" "$CASE_DIR/out.log" \
  || fail "T3a(subject-secret-read): diagnostic expected"

# schema 검사 실패: query 실패(접속/권한)와 매치 개수 불일치 모두 fail-closed — row/값 미출력.
run_prestop_failure "subject-schema-query" base "FAKE_MYSQL_EXIT=1"
grep -q "PREFLIGHT FAILED: subject mapping schema query failed" "$CASE_DIR/out.log" \
  || fail "T3a(subject-schema-query): diagnostic expected"
run_prestop_failure "subject-schema-mismatch" base "FAKE_MYSQL_OUTPUT=0"
grep -q "PREFLIGHT FAILED: user_subject_links schema mismatch" "$CASE_DIR/out.log" \
  || fail "T3a(subject-schema-mismatch): diagnostic expected"
ok "T5d: subject mode/ARN/runtime-role/secret-read/DB/schema preflights fail closed before stopping the old container"

# --- 10c. T5e: subject secret 내용 계약 — 앱 parse()와 동일 규칙 fail-closed·payload 비출력 ---
# 기본 fixture(유효 current-only + 잉여 필드)는 위 모든 성공 케이스가 이미 통과시켰다.
# rotation(previous 쌍) 유효 secret도 성공해야 한다.
new_case; base_env_fixture
execute_script "FAKE_SECRET_JSON={\"currentVersion\":2,\"currentKey\":\"$B64_KEY_32A\",\"previousVersion\":1,\"previousKey\":\"$B64_KEY_32B\"}"
[ "$RC" = "0" ] || fail "T5e(rotation-valid): success expected, rc=$RC ($(cat "$CASE_DIR/out.log"))"
assert_sha_line "T5e(rotation-valid)"
assert_prune_once "T5e(rotation-valid)"
assert_no_sentinel "T5e(rotation-valid)"

# 오염 fixture는 전부 구 컨테이너 중지 전에 항목 이름만으로 실패해야 한다(payload는 sentinel 포함 —
# 어떤 출력에도 새면 assert_no_sentinel이 잡는다).
run_prestop_failure "secret-not-json" base "FAKE_SECRET_JSON={$SENTINEL"
grep -q "PREFLIGHT FAILED: subject secret is not valid JSON" "$CASE_DIR/out.log" \
  || fail "T5e(secret-not-json): diagnostic expected"
run_prestop_failure "secret-key-31-bytes" base \
  "FAKE_SECRET_JSON={\"currentVersion\":1,\"currentKey\":\"$B64_KEY_31\",\"note\":\"$SENTINEL\"}"
grep -q "PREFLIGHT FAILED: subject secret currentKey must decode to exactly 32 bytes" "$CASE_DIR/out.log" \
  || fail "T5e(secret-key-31-bytes): diagnostic expected"
run_prestop_failure "secret-half-previous-pair" base \
  "FAKE_SECRET_JSON={\"currentVersion\":2,\"currentKey\":\"$B64_KEY_32A\",\"previousVersion\":1,\"note\":\"$SENTINEL\"}"
grep -q "PREFLIGHT FAILED: subject secret previousVersion and previousKey must be present together" "$CASE_DIR/out.log" \
  || fail "T5e(secret-half-previous-pair): diagnostic expected"
run_prestop_failure "secret-string-absent" base "FAKE_SECRET_STRING_ABSENT=1"
grep -q "PREFLIGHT FAILED: subject secret SecretString missing" "$CASE_DIR/out.log" \
  || fail "T5e(secret-string-absent): diagnostic expected"

# SPRING_PROFILES_ACTIVE 가드: 키 없음(기본 fixture)이 정상 — docker가 값에 포함되면 실패하고,
# docker가 없는 값은 이 가드에 걸리지 않는다(진단은 key 이름과 고정 문구만).
for spa in "docker" "dev,docker" ; do
  new_case; base_env_fixture
  printf '%s\n' "SPRING_PROFILES_ACTIVE=$spa" >> "$CASE_DIR/.env"
  chmod 600 "$CASE_DIR/.env"
  cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
  execute_script
  [ "$RC" != "0" ] || fail "T5e(profiles=$spa): preflight failure expected"
  grep -q "PREFLIGHT FAILED: .env SPRING_PROFILES_ACTIVE docker profile must not be active" "$CASE_DIR/out.log" \
    || fail "T5e(profiles=$spa): diagnostic expected"
  assert_env_untouched "T5e(profiles=$spa)"
  assert_no_stop_no_run "T5e(profiles=$spa)"
  assert_prune_once "T5e(profiles=$spa)"
  assert_no_sentinel "T5e(profiles=$spa)"
done
new_case; base_env_fixture
printf '%s\n' "SPRING_PROFILES_ACTIVE=prod" >> "$CASE_DIR/.env"
chmod 600 "$CASE_DIR/.env"
cp "$CASE_DIR/.env" "$CASE_DIR/.env.orig"
execute_script
[ "$RC" = "0" ] || fail "T5e(profiles=prod): non-docker value must pass this guard, rc=$RC ($(cat "$CASE_DIR/out.log"))"
ok "T5e: secret content contract mirrors app parse() and SPRING_PROFILES_ACTIVE=docker fails closed"

# --- 11. T5/T5a: push mode 계약 ---
new_case; base_env_fixture
execute_script
[ "$RC" = "0" ] || fail "T5(noop): success expected, rc=$RC"
# UID check는 credential mount(-v)가 있는 one-shot run만 해당 — mysql:8.0 schema check run과 구분한다.
grep -q '^docker run --rm -v ' "$CASE_DIR/docker.log" && fail "T5(noop): UID check must be skipped"
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
grep -q '^docker run --rm -v ' "$CASE_DIR/docker.log" || fail "T7: UID 1001 readability check expected"
FB_RUN=$(grep '^docker run -d' "$CASE_DIR/docker.log")
printf '%s\n' "$FB_RUN" | grep -q -- "-v $CASE_DIR/cred.json:/run/secrets/firebase-service-account.json:ro" \
  || fail "T7: read-only credential mount expected"
printf '%s\n' "$FB_RUN" | grep -qE ' (-e|--env)[ =]' && fail "T7: no -e expected in firebase run"
printf '%s\n' "$FB_RUN" | grep -q 'GOOGLE_APPLICATION_CREDENTIALS' && fail "T7: ADC path must come from .env only"
UID_LN=$(grep -n '^docker run --rm -v ' "$CASE_DIR/docker.log" | head -1 | cut -d: -f1)
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
