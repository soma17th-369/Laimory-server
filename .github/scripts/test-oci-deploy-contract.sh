#!/usr/bin/env bash
# OCI prod 배포 경로의 계약 harness: deploy-oci.yml 구조, SSH gate, host 배포 스크립트의 전환·복귀·출력.
# host 스크립트는 사본이 아니라 원본을 fake docker(상태를 기억하는 stub)로 실행한다.
# 실행: bash .github/scripts/test-oci-deploy-contract.sh
set -u
export LC_ALL=C
REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$REPO_ROOT/.github/workflows/deploy-oci.yml"
DEPLOY="$REPO_ROOT/deploy/oci/deploy/laimory-deploy"
GATE="$REPO_ROOT/deploy/oci/deploy/laimory-deploy-gate"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
SHA=1234567890abcdef1234567890abcdef12345678
DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
SENTINEL=SENTINEL_SECRET_XYZZY
PASS=0
fail() { echo "FAIL: $1" >&2; [ ! -f "${CASE:-/x}/out.log" ] || sed 's/^/  | /' "$CASE/out.log" >&2; exit 1; }
ok() { PASS=$((PASS + 1)); echo "ok - $1"; }

# --- W1: workflow 구조 ---
ruby -ryaml -e '
  src = File.read(ARGV[0], encoding: "UTF-8")
  wf = begin; YAML.unsafe_load(src); rescue NoMethodError; YAML.load(src); end
  on = wf["on"] || wf[true]
  abort "push must be main only" unless on.dig("push", "branches") == ["main"]
  abort "push paths changed" unless on.dig("push", "paths") == [".github/workflows/deploy-oci.yml", ".dockerignore", "Dockerfile", "build.gradle", "gradle.properties", "gradle/**", "gradlew", "settings.gradle", "src/main/**"]
  abort "workflow must not run on pull requests" if on.keys.any? { |k| k.to_s.start_with?("pull_request") }
  abort "running deployments must not be cancelled" unless wf.dig("concurrency", "cancel-in-progress") == false
  abort "build-only must not share the deploy queue" unless wf.dig("concurrency", "group").include?("inputs.action == \x27build-only\x27")
  abort "default permissions must be read only" unless wf["permissions"] == {"contents" => "read"}
  build, deploy = wf["jobs"].values_at("build", "deploy")
  abort "build must be native arm64" unless build["runs-on"] == "ubuntu-24.04-arm"
  abort "build must not use the prod environment" if build.key?("environment")
  abort "build permissions changed" unless build["permissions"] == {"contents" => "read", "packages" => "write"}
  abort "push builds must honor DEPLOY_PAUSED" unless build["if"].include?("vars.DEPLOY_PAUSED != \x27true\x27")
  abort "deploy must use the prod environment" unless deploy["environment"] == "prod"
  abort "deploy permissions changed" unless deploy["permissions"] == {"contents" => "read", "id-token" => "write"}
  abort "deploy must run only on main" unless deploy["if"].include?("github.ref == \x27refs/heads/main\x27")
  abort "deploy must require a successful build on push" unless deploy["if"].include?("needs.build.result == \x27success\x27")
  abort "cancelling a run must stop the deploy" unless deploy["if"].start_with?("!cancelled() &&") && !deploy["if"].include?("always()")
  uses = wf["jobs"].values.flat_map { |j| j["steps"].map { |s| s["uses"] } }.compact
  abort "actions must be pinned to a commit (build output reaches prod)" unless uses.all? { |u| u =~ /@[0-9a-f]{40}\z/ }
  ts = deploy["steps"].find { |s| s["uses"].to_s.start_with?("tailscale/github-action@") }["with"]
  abort "tailscale must use workload identity without long-lived secrets" if ts.key?("authkey") || ts.key?("oauth-secret") || !ts.key?("audience")
  abort "tailscale tag changed" unless ts["tags"] == "tag:ci"
  ssh = deploy["steps"].find { |s| s["run"].to_s.include?("ssh -i") }
  %w[SSH_KEY HOST KNOWN_HOSTS].each { |k| abort "#{k} must be an environment secret" unless ssh["env"][k].include?("secrets.") }
  abort "host key must be verified" unless ssh["run"].include?("StrictHostKeyChecking=yes")
  abort "deploy command must be sent once" unless ssh["run"].scan(/"deploy \$SHA \$DIGEST"/).size == 1
  resolve = deploy["steps"].find { |s| s["name"] == "Resolve image" }["run"]
  abort "a manual deploy must only accept commits on main" unless resolve.include?("git merge-base --is-ancestor \"$sha\" \"$GITHUB_SHA\"")
  co = deploy["steps"].find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
  abort "deploy needs full history without stored credentials" unless co && co["with"] == {"fetch-depth" => 0, "persist-credentials" => false}
  runs = wf["jobs"].values.flat_map { |j| j["steps"].map { |s| s["run"].to_s } }.join("\n")
  abort "no shell tracing in a public log" if runs =~ /set -[a-z]*x/
  abort "expressions must not be interpolated into shell" if runs.include?("${{")
' "$WORKFLOW" || fail "W1: workflow contract"
ok "W1: main-only trigger, split permissions, pinned tailscale without secrets, verified host key"

# --- S1: 공개 로그로 나갈 수 있는 명령을 두지 않는다 ---
code() { grep -vE '^[[:space:]]*#' "$1"; }
for f in "$DEPLOY" "$GATE"; do
  ! code "$f" | grep -qE 'docker( compose)? logs|set -[a-z]*x|printenv|\beval\b' || fail "S1: forbidden command in $f"
  ! code "$f" | grep -E 'docker inspect' | grep -vqE -- '--format' || fail "S1: docker inspect without --format"
  ! code "$f" | grep -E 'compose config' | grep -vqE 'config -q' || fail "S1: compose config without -q"
  ! code "$f" | grep -E 'docker compose up' | grep -vqE -- '--no-deps .*--force-recreate "\$SERVICE-\$CANDIDATE"' || fail "S1: compose up must target only the candidate"
  ! code "$f" | grep -qE 'prune|compose down' || fail "S1: prune/down must not be used"
done
ok "S1: deploy scripts never print logs/env and only touch the candidate service"

# --- G1: gate ---
mkdir -p "$WORK/gate"
printf '#!/usr/bin/env bash\necho "sudo $*" >> "%s/gate/calls"\n' "$WORK" > "$WORK/gate/sudo"; chmod +x "$WORK/gate/sudo"
gate() { : > "$WORK/gate/calls"; env PATH="$WORK/gate:$PATH" SSH_ORIGINAL_COMMAND="$2" bash "$GATE" "$1" >/dev/null 2>&1; }
gate api "deploy $SHA $DIGEST" || fail "G1: valid request must pass"
[ "$(cat "$WORK/gate/calls")" = "sudo -n /usr/local/sbin/laimory-deploy api $SHA $DIGEST" ] || fail "G1: exact sudo command expected"
for bad in "" "bash" "deploy $SHA" "deploy $SHA $DIGEST extra" "deploy $SHA $DIGEST;id" "deploy ${SHA}0 $DIGEST" "deploy $SHA sha256:zz" \
  "rollback api" "ai $SHA $DIGEST" " deploy $SHA $DIGEST" "deploy $SHA $DIGEST
id" 'deploy $(id) x'; do
  ! gate api "$bad" || fail "G1: must reject: $bad"
  [ ! -s "$WORK/gate/calls" ] || fail "G1: rejected request must not reach sudo: $bad"
done
! gate mysql "deploy $SHA $DIGEST" || fail "G1: unknown service must be rejected"
ok "G1: the SSH gate passes only 'deploy <sha> <digest>' for its fixed service"

# --- fake docker ---
STUB="$WORK/stub"; mkdir -p "$STUB"
cat > "$STUB/docker" <<'STUBEOF'
#!/usr/bin/env bash
echo "docker $*" >> "$CASE/calls"
C="$CASE/containers"
ip() { case "$1" in *blue) echo 10.9.0.2 ;; *) echo 10.9.0.3 ;; esac; }
case "$1" in
  ps) a="$*"; s=${a##*service=}; [ ! -f "$C/$s" ] || echo "$s" ;;
  inspect) id=${*: -1}; case "$*" in
      *State.Running*) [ "$(cat "$C/$id")" = running ] && echo true || echo false ;;
      *IPAddress*) ip "$id" ;;
      *'{{.Image}}'*) echo "${FAKE_RUN_IMAGE:-sha256:verified}" ;; esac ;;
  stop) id=${*: -1}; [ "${FAKE_STOP_STUCK:-}" = "$id" ] || echo exited > "$C/$id" ;;
  start) id=${*: -1}; echo running > "$C/$id" ;;
  pull) exit "${FAKE_PULL:-0}" ;;
  image) case "$2" in
      inspect) case "$*" in *'{{.Id}}'*) echo sha256:verified ;; *) echo "${FAKE_LABEL:-$FIXTURE_SHA}" ;; esac ;;
      ls) echo sha256:old; echo sha256:new ;;
      rm) exit 1 ;; esac ;;
  compose) shift; case "$*" in
      "config -q") echo "$SENTINEL" >&2; exit "${FAKE_CONFIG:-0}" ;;
      up*) echo "$SENTINEL" >&2; [ -z "${FAKE_UP:-}" ] || exit 1; echo running > "$C/${*: -1}" ;;
      *"nginx -t") echo "$SENTINEL" >&2; exit "${FAKE_NGINX_T:-0}" ;;
      *"nginx -s reload") [ -n "${FAKE_RELOAD_STUCK:-}" ] || cp "$LAIMORY_ROOT"/nginx/upstream/*.conf "$CASE/loaded/" ;;
      *curl*) url=${*: -1}; case "$url" in
          *127.0.0.1*) [ -z "${FAKE_CONFIRM_FAIL:-}" ] || { printf '200 10.9.0.9:8080'; exit 0; }
            a=$(grep '^    server ' "$CASE/loaded/$FIXTURE_SERVICE.conf" | grep -v ' down;' | sed -E 's/.*server ([a-z]+-[a-z]+):.*/\1/')
            printf '200 %s:8080' "$(ip "$a")" ;;
          *) echo "$SENTINEL"; [ -z "${FAKE_UNHEALTHY:-}" ] || exit 22 ;; esac ;;
    esac ;;
esac
STUBEOF
printf '#!/usr/bin/env bash\nexit 0\n' > "$STUB/sleep"
# 바깥 실행용: 실행 중인 unit 흉내, systemd-run은 안쪽 실행을 그대로 돌리고 stdout을 지정한 로그에 덧붙인다.
printf '#!/usr/bin/env bash\necho "systemctl $*" >> "$CASE/calls"\n[ -n "${FAKE_UNIT_ACTIVE:-}" ]\n' > "$STUB/systemctl"
cat > "$STUB/systemd-run" <<'STUBEOF'
#!/usr/bin/env bash
echo "systemd-run $*" >> "$CASE/calls"
log=""
while [ "${1#--}" != "$1" ]; do case "$1" in --property=StandardOutput=append:*) log=${1#--property=StandardOutput=append:} ;; esac; shift; done
LAIMORY_DEPLOY_INNER=1 bash "$@" >> "$log" 2>/dev/null
STUBEOF
chmod +x "$STUB"/*

upstream() { # $1 service $2 active
  printf 'upstream laimory_%s {\n    zone laimory_%s 64k;\n    resolver_timeout 2s;\n' "$1" "$1"
  for c in blue green; do m=" down"; [ $c = "$2" ] && m=""; printf '    server %s-%s:8080 resolve max_fails=0%s;\n' "$1" "$c" "$m"; done
  printf '}\n'
}
new_case() { # $1 service: blue가 서빙 중, green은 없음
  CASE=$(mktemp -d "$WORK/case.XXXXXX"); export CASE; S=$1
  mkdir -p "$CASE/containers" "$CASE/loaded" "$CASE/root/nginx/upstream" "$CASE/state"
  upstream "$S" blue > "$CASE/root/nginx/upstream/$S.conf"; cp "$CASE/root/nginx/upstream/$S.conf" "$CASE/loaded/"
  echo running > "$CASE/containers/$S-blue"; : > "$CASE/calls"
  printf 'API_BLUE_IMAGE=old\nAPI_BLUE_SHA=old\nAPI_GREEN_IMAGE=stale\nAPI_GREEN_SHA=stale\nAI_BLUE_IMAGE=keep\n' > "$CASE/root/.env"
}
run() { # VAR=value... -- args
  local e=(); while [ "$1" != "--" ]; do e+=("$1"); shift; done; shift
  env PATH="$STUB:$PATH" SENTINEL="$SENTINEL" FIXTURE_SHA="$SHA" FIXTURE_SERVICE="$S" LAIMORY_DEPLOY_INNER=1 LAIMORY_ROOT="$CASE/root" \
    LAIMORY_STATE_DIR="$CASE/state" LAIMORY_RUN_DIR="$CASE/run" LAIMORY_NOW=1200 LAIMORY_HEALTH_TIMEOUT=1 LAIMORY_CONFIRM_TIMEOUT=2 "${e[@]+"${e[@]}"}" \
    bash "$DEPLOY" "$@" > "$CASE/out.log" 2> "$CASE/err.log"; RC=$?
  ! grep -q "$SENTINEL" "$CASE/out.log" || fail "public output leaked command output"
}
active() { grep '^    server ' "$CASE/loaded/$S.conf" | grep -v ' down;' | sed -E 's/.*-(blue|green):.*/\1/'; }
state() { cat "$CASE/containers/$S-$1" 2>/dev/null || echo absent; }
unchanged() { # 전환도 새 container도 없음
  [ "$(active)" = blue ] && [ "$(state blue)" = running ] && [ "$(state green)" = absent ] || fail "$1: nothing must change"
  grep -q '^API_GREEN_IMAGE=stale$' "$CASE/root/.env" || fail "$1: compose env must be preserved"
}

for svc in api ai; do
  new_case $svc; run -- $svc $SHA $DIGEST
  [ "$RC" = 0 ] || fail "D1($svc): success expected"
  [ "$(active)" = green ] && [ "$(state green)" = running ] && [ "$(state blue)" = exited ] || fail "D1($svc): traffic must move to green and blue stay stopped"
  U=$(echo $svc | tr a-z A-Z)
  grep -qx "${U}_GREEN_IMAGE=ghcr.io/soma17th-369/laimory-$([ $svc = api ] && echo server || echo ai)@$DIGEST" "$CASE/root/.env" || fail "D1($svc): image must be pinned by digest"
  grep -qx "${U}_GREEN_SHA=$SHA" "$CASE/root/.env" || fail "D1($svc): commit must be recorded per color"
  [ "$(grep -c '^API_BLUE_IMAGE=old$' "$CASE/root/.env")" = 1 ] && grep -q '^AI_BLUE_IMAGE=keep$' "$CASE/root/.env" || fail "D1($svc): other colors must be preserved"
  [ "$(cat "$CASE/state/$svc.standby")" = blue ] || fail "D1($svc): blue must become the rollback standby"
  grep -q "^docker stop -t $([ $svc = api ] && echo 120 || echo 300) $svc-blue$" "$CASE/calls" || fail "D1($svc): graceful stop timeout"
  [ "$(grep -n 'nginx -s reload' "$CASE/calls" | head -1 | cut -d: -f1)" -lt "$(grep -n "docker stop .*$svc-blue" "$CASE/calls" | head -1 | cut -d: -f1)" ] || fail "D1($svc): old color must stop only after the switch"
done
ok "D1: a healthy candidate takes over, the old color stops gracefully and is kept for rollback (api, ai)"

for e in FAKE_PULL=1 FAKE_LABEL=wrong FAKE_CONFIG=1; do
  new_case api; run $e -- api $SHA $DIGEST
  [ "$RC" != 0 ] || fail "D2($e): must fail"; unchanged "D2($e)"
done
new_case api; echo running > "$CASE/containers/api-green"; run -- api $SHA $DIGEST
[ "$RC" != 0 ] && [ "$(active)" = blue ] || fail "D2: two running colors must block the deployment"
new_case api; echo exited > "$CASE/containers/api-blue"; run -- api $SHA $DIGEST
[ "$RC" != 0 ] && [ "$(state green)" = absent ] || fail "D2: a stopped active color must block the deployment"
new_case api; printf '    server api:8080 resolve max_fails=0 down;\n' >> "$CASE/root/nginx/upstream/api.conf"; run -- api $SHA $DIGEST
[ "$RC" != 0 ] && [ "$(state green)" = absent ] || fail "D2: an unfinished structure migration must block the deployment"
ok "D2: pull/label/compose failures and an unexpected host state change nothing"

for now in 0225 0300 0449 2055 2109; do
  new_case api; run LAIMORY_NOW=$now -- api $SHA $DIGEST; [ "$RC" != 0 ] || fail "D3($now): must refuse"; unchanged "D3($now)"
done
for now in 0000 0224 0450 2054 2110 0859; do
  new_case api; run LAIMORY_NOW=$now -- api $SHA $DIGEST; [ "$RC" = 0 ] || fail "D3($now): must deploy"
done
new_case ai; run LAIMORY_NOW=0300 -- ai $SHA $DIGEST; [ "$RC" = 0 ] || fail "D3: ai has no scheduled jobs and must deploy"
ok "D3: api refuses the scheduled-job windows (KST); ai is unaffected"

for e in FAKE_UP=1 FAKE_RUN_IMAGE=sha256:other FAKE_UNHEALTHY=1; do
  new_case api; echo blue > "$CASE/state/api.standby"; run $e -- api $SHA $DIGEST
  [ "$RC" != 0 ] && [ "$(active)" = blue ] && [ "$(state blue)" = running ] && [ "$(state green)" != running ] || fail "D4($e): blue must keep serving and green must not run"
  ! grep -q 'nginx -s reload' "$CASE/calls" || fail "D4($e): nginx must not be touched"
  [ ! -f "$CASE/state/api.standby" ] || fail "D4($e): the overwritten color must not be offered as a rollback"
done
ok "D4: a bad candidate never receives traffic and is stopped"

for e in FAKE_NGINX_T=1 FAKE_RELOAD_STUCK=1; do
  new_case api; run $e -- api $SHA $DIGEST
  [ "$RC" != 0 ] && [ "$(active)" = blue ] && [ "$(state blue)" = running ] || fail "D5($e): blue must keep serving"
  grep -q 'api-blue:8080 resolve max_fails=0;$' "$CASE/root/nginx/upstream/api.conf" || fail "D5($e): upstream file must be restored"
done
new_case api; run FAKE_RELOAD_STUCK=1 -- api $SHA $DIGEST
[ "$(state green)" = exited ] || fail "D5: an unconfirmed candidate must be stopped"
new_case api; run FAKE_CONFIRM_FAIL=1 -- api $SHA $DIGEST
[ "$RC" != 0 ] && [ "$(state green)" = exited ] && [ "$(state blue)" = running ] || fail "D5: a candidate nginx does not serve must be stopped"
[ "$(active)" = blue ] && [ "$(grep -c 'nginx -s reload' "$CASE/calls")" = 2 ] || fail "D5: nginx must actually reload the old color after a failed confirmation"
new_case api; run FAKE_NGINX_T=1 -- api $SHA $DIGEST
grep -q '^CRITICAL' "$CASE/out.log" && [ "$(state green)" = running ] || fail "D5: when nginx cannot be restored both colors must stay up for inspection"
ok "D5: the old color is stopped only after nginx proves the candidate serves; otherwise upstream is restored"

new_case api; run FAKE_STOP_STUCK=api-blue -- api $SHA $DIGEST
[ "$RC" != 0 ] && [ "$(active)" = green ] && [ ! -f "$CASE/state/api.standby" ] || fail "D6: a still-running old color must be reported"
ok "D6: a stuck old color fails the run without moving traffic back"

new_case api; run -- api $SHA $DIGEST; run -- rollback api
[ "$RC" = 0 ] && [ "$(active)" = blue ] && [ "$(state blue)" = running ] && [ "$(state green)" = exited ] || fail "R1: rollback must return to blue"
[ "$(cat "$CASE/state/api.standby")" = green ] || fail "R1: the replaced color becomes the standby"
new_case api; echo exited > "$CASE/containers/api-green"; run -- rollback api
[ "$RC" != 0 ] && [ "$(state green)" = exited ] && [ "$(active)" = blue ] || fail "R1: rollback without a known-good standby must refuse"
ok "R1: rollback restores only a known-good standby"

# 바깥 실행(systemd 분리): 같은 서비스의 배포가 돌고 있으면 거절하고 그 로그를 건드리지 않는다. 결과·종료 코드는 그대로 전한다.
new_case api; run LAIMORY_DEPLOY_INNER= -- api $SHA $DIGEST
[ "$RC" = 0 ] && [ "$(active)" = green ] && grep -q '^done: ' "$CASE/out.log" || fail "O1: the detached run must report its result"
grep -q -- '--unit=laimory-deploy-api ' "$CASE/calls" || fail "O1: the unit name must be fixed per service"
new_case api; mkdir -p "$CASE/run"; echo running-deploy > "$CASE/run/api.log"
run LAIMORY_DEPLOY_INNER= FAKE_UNIT_ACTIVE=1 -- api $SHA $DIGEST
[ "$RC" != 0 ] && ! grep -q '^systemd-run' "$CASE/calls" || fail "O1: a second deployment of the same service must be refused"
grep -qx running-deploy "$CASE/run/api.log" || fail "O1: a refused run must not truncate the running deployment's log"
unchanged "O1"
new_case api; run LAIMORY_DEPLOY_INNER= FAKE_UNHEALTHY=1 -- api $SHA $DIGEST
[ "$RC" != 0 ] && grep -q '^FAIL: ' "$CASE/out.log" || fail "O1: a failed detached run must fail the caller with its message"
ok "O1: the detached run refuses a concurrent deployment and passes the result through"

new_case api
for args in "api $SHA" "api $SHA sha256:zz" "mysql $SHA $DIGEST" "api ${SHA}x $DIGEST" "rollback" "rollback mysql"; do
  run -- $args; [ "$RC" = 2 ] || fail "A1: must reject: $args"
done
unchanged "A1"
ok "A1: invalid arguments are rejected before touching the host"
echo "PASS: OCI deploy contract harness ($PASS groups)"
