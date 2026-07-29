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
  abort "monitoring instance variable missing" unless workflow.dig("env", "INSTANCE_ID") == "${{ vars.MONITORING_INSTANCE_ID }}"
  abort "monitoring bucket variable missing" unless workflow.dig("env", "BACKUP_BUCKET") == "${{ vars.MONITORING_BACKUP_BUCKET }}"

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

  ssm = steps.find { |step| step["id"] == "ssm" }
  abort "SSM step missing" unless ssm
  %w[SHA256SUMS sha256sum deploy-alert-rules.sh validate-alert-rules.sh].each do |marker|
    abort "SSM deploy missing #{marker}" unless ssm["run"].include?(marker)
  end
  abort "SSM remote shell must be POSIX-compatible" unless ssm["run"].include?("set -eu")
  abort "SSM remote shell must not require pipefail" if ssm["run"].include?("pipefail")
  abort "SSM target variable missing" unless ssm["run"].include?("--instance-ids \"$INSTANCE_ID\"")
  install_index = ssm["run"].index("install -m 0750")
  deploy_index = ssm["run"].index("/opt/laimory-monitoring/scripts/deploy-alert-rules.sh")
  abort "release tools must be installed before deploy" unless install_index && deploy_index && install_index < deploy_index
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
