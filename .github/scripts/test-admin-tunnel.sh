#!/usr/bin/env bash
# 실제 AWS/SSM 세션 없이 target 선택·모호성 fail-closed 계약을 검증한다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
cat > "$WORK/aws" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$CALLS"
case "$1 $2" in
  'ec2 describe-instances') printf '%s\n' "${IDS-i-fixture}" ;;
  'ssm describe-instance-information') printf '%s\n' "${PING-Online}" ;;
  'ssm start-session') echo started >> "$STARTED" ;;
  *) exit 1 ;;
esac
STUB
chmod +x "$WORK/aws"
export PATH="$WORK:$PATH" CALLS="$WORK/calls" STARTED="$WORK/started"
SCRIPT="$ROOT/deploy/monitoring/scripts/open-observability-tunnel.sh"
for target in grafana kibana admin-dev; do
  bash "$SCRIPT" "$target" > "$WORK/out"
done
bash "$SCRIPT" admin-prod laimory-prod-was-02 > "$WORK/out"
grep -q 'Name=tag:Environment,Values=prod' "$CALLS"
grep -q 'Name=tag:Name,Values=laimory-prod-was-02' "$CALLS"
grep -q 'portNumber=8081,localPortNumber=8081' "$CALLS"
[[ $(wc -l < "$STARTED" | tr -d ' ') == 4 ]]
for ids in '' None 'i-one i-two'; do
  if IDS="$ids" bash "$SCRIPT" admin-prod > "$WORK/out" 2>&1; then echo 'FAIL: ambiguous target accepted'; exit 1; fi
done
if PING=ConnectionLost bash "$SCRIPT" admin-dev > "$WORK/out" 2>&1; then echo 'FAIL: offline SSM accepted'; exit 1; fi
for target in admin ADMIN admin-test; do
  if bash "$SCRIPT" "$target" > "$WORK/out" 2>&1; then echo 'FAIL: invalid target accepted'; exit 1; fi
done
if bash "$SCRIPT" admin-prod laimory-dev-was-01 > "$WORK/out" 2>&1; then echo 'FAIL: crossed environment'; exit 1; fi
if bash "$SCRIPT" admin-prod 'laimory-prod-was-*' > "$WORK/out" 2>&1; then echo 'FAIL: explicit wildcard accepted'; exit 1; fi
[[ $(wc -l < "$STARTED" | tr -d ' ') == 4 ]]
echo 'ok - tunnel targets, exact host selection, ambiguity, environment and SSM Online checks'
