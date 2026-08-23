#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORKFLOW="$REPO_ROOT/.github/workflows/deploy-monitoring.yml"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ruby -ryaml -e '
  workflow = begin
    YAML.unsafe_load_file(ARGV[0])
  rescue NoMethodError
    YAML.load_file(ARGV[0])
  end

  triggers = workflow["on"] || workflow[true]
  abort "dev push trigger missing" unless triggers.dig("push", "branches") == ["dev"]
  abort "workflow_dispatch trigger missing" unless triggers.key?("workflow_dispatch")

  expected_paths = [
    ".github/workflows/deploy-monitoring.yml",
    "deploy/monitoring/grafana/alert-rule-files.txt",
    "deploy/monitoring/grafana/provisioning/alerting/*-rules.yml",
    "deploy/monitoring/scripts/deploy-alert-rules.sh",
    "deploy/monitoring/scripts/publish-alert-rules.sh",
    "deploy/monitoring/scripts/validate-alert-rules.sh",
  ]
  abort "monitoring deploy paths changed" unless triggers.dig("push", "paths") == expected_paths
  abort "OIDC permission missing" unless workflow.dig("permissions", "id-token") == "write"
  abort "contents permission must be read-only" unless workflow.dig("permissions", "contents") == "read"
  abort "monitoring concurrency group changed" unless workflow.dig("concurrency", "group") == "deploy-monitoring-alert-rules-dev"
  abort "monitoring deploy must queue, not cancel" unless workflow.dig("concurrency", "cancel-in-progress") == false
  # instance id와 bucket 이름은 Secrets에서만 읽는다: Actions가 workflow-level env: 블록을 모든 step
  # 헤더의 로그에 그대로 echo하는데 repository Variable은 마스킹되지 않아 PUBLIC 저장소의 공개 로그에
  # 값이 남는다. Secret은 ***로 마스킹되므로 vars.로 되돌아가는 회귀를 여기서 함께 막는다.
  {"INSTANCE_ID" => "MONITORING_INSTANCE_ID",
   "BACKUP_BUCKET" => "MONITORING_BACKUP_BUCKET"}.each do |env_key, name|
    expr = workflow.dig("env", env_key).to_s
    abort "monitoring #{env_key} must read repository secret #{name}" unless expr.include?("secrets.#{name}")
    abort "monitoring #{env_key} must not read an unmasked repository variable" if expr.include?("vars.")
  end

  steps = workflow.dig("jobs", "deploy-alert-rules", "steps")
  abort "deploy job missing" unless steps
  configure = steps.find { |step| step["uses"] == "aws-actions/configure-aws-credentials@v4" }
  abort "OIDC configure step missing" unless configure
  abort "deploy role variable changed" unless configure.dig("with", "role-to-assume") == "${{ vars.AWS_DEPLOY_ROLE_ARN }}"

  publish = steps.find { |step| step["id"] == "publish" }
  abort "publish step missing" unless publish
  abort "publisher not called" unless publish["run"].include?("publish-alert-rules.sh \"$BACKUP_BUCKET\"")
  abort "publisher must use GitHub OIDC credentials, not a local profile" if publish["run"].include?("--profile")
  abort "release SHA contract missing" unless publish["run"].include?("$GITHUB_SHA")

  freshness = steps.find { |step| step["id"] == "freshness" }
  abort "dev HEAD freshness guard missing" unless freshness
  abort "manual dispatch contract missing" unless freshness["run"].include?("\"$GITHUB_EVENT_NAME\" != \"push\"")
  abort "freshness guard must read refs/heads/dev" unless freshness["run"].include?("git ls-remote origin refs/heads/dev")
  abort "freshness guard must compare current SHA" unless freshness["run"].include?("\"$DEV_HEAD\" != \"$GITHUB_SHA\"")
  abort "freshness guard skip output missing" unless freshness["run"].include?("deploy=false")
  abort "freshness guard deploy output missing" unless freshness["run"].include?("deploy=true")

  ssm = steps.find { |step| step["id"] == "ssm" }
  abort "SSM step missing" unless ssm
  freshness_condition = "steps.freshness.outputs.deploy == " + 39.chr + "true" + 39.chr
  abort "stale release may reach SSM" unless ssm["if"] == freshness_condition
  %w[SHA256SUMS sha256sum deploy-alert-rules.sh validate-alert-rules.sh].each do |marker|
    abort "SSM deploy missing #{marker}" unless ssm["run"].include?(marker)
  end
  abort "SSM remote shell must be POSIX-compatible" unless ssm["run"].include?("set -eu")
  abort "SSM remote shell must not require pipefail" if ssm["run"].include?("pipefail")
  abort "SSM target variable missing" unless ssm["run"].include?("--instance-ids \"$INSTANCE_ID\"")
  install_index = ssm["run"].index("install -m 0750")
  staged_deploy_index = ssm["run"].index("ALERT_RULE_VALIDATOR=")
  abort "release tools must execute from staging before activation" unless install_index && staged_deploy_index && staged_deploy_index < install_index
  abort "staged validator override missing" unless ssm["run"].include?("\"\\$TOOL_STAGE/validate-alert-rules.sh\"")
  abort "active deployer must not execute before successful activation" if ssm["run"].include?("/opt/laimory-monitoring/scripts/deploy-alert-rules.sh")

  wait = steps.find { |step| step["name"] == "Wait for deployment and fetch logs" }
  abort "deployment wait step missing" unless wait
  abort "stale release may wait for absent SSM command" unless wait["if"] == freshness_condition
  abort "workflow must not receive Grafana secret" if File.read(ARGV[0]).match?(/secrets\..*grafana|GRAFANA_ADMIN_PASSWORD/)
' "$WORKFLOW"

ruby -ryaml -e '
  workflow = begin
    YAML.unsafe_load_file(ARGV[0])
  rescue NoMethodError
    YAML.load_file(ARGV[0])
  end
  steps = workflow.dig("jobs", "deploy-alert-rules", "steps")
  print steps.find { |step| step["id"] == "ssm" }["run"]
' "$WORKFLOW" > "$WORK/ssm-run.sh"

awk '/<<EOF \|\| true$/{inside=1; next} inside && /^EOF$/{exit} inside{print}' \
  "$WORK/ssm-run.sh" > "$WORK/remote-body.raw"
{
  echo 'set -u'
  echo 'read -r -d "" SCRIPT <<EOF || true'
  cat "$WORK/remote-body.raw"
  echo 'EOF'
  echo 'printf "%s\n" "$SCRIPT"'
} > "$WORK/expand-driver.sh"
env \
  AWS_REGION=ap-test-1 \
  RELEASE_URI=s3://test-bucket/bootstrap/monitoring/releases/alert-rules/1234567890abcdef1234567890abcdef12345678 \
  /bin/bash "$WORK/expand-driver.sh" > "$WORK/remote-script.sh"
/bin/sh -n "$WORK/remote-script.sh"
grep -qx 'set -eu' "$WORK/remote-script.sh"

echo "monitoring deploy workflow contract tests passed"
