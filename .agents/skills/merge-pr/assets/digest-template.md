---
schema_version: 1
status: merge-candidate
pr_number: <number>
pr_url: <url>
title: "<pr-title>"
base_branch: dev
head_branch: <head-branch>
implementation_head_sha: <pre-digest-head-sha>
generated_at: <ISO-8601 timestamp>
linked_issues: []
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #<number> Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose:
- Acceptance criteria:
- Out of scope:

## Change Summary

- Describe externally visible behavior and important internal contract changes.

## Plan Deviations

- Describe what changed from the accepted plan and why.
- If none were observed, write: `No material deviation was observed within the evidence scope.`

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| <stage> | <what happened> | confirmed / suspected / unknown | <what changed> | <test, diff, review, or command result summary> |

If no material problem was observed, replace the table with: `No material problem was observed within the evidence scope.`

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| <human / Claude / Codex / PR reviewer> | <finding or proposal> | accepted / modified / rejected / deferred | <evidence-based reason> |

Record only decisions that changed or intentionally preserved the final design. Do not copy every review comment.

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| <behavior or risk> | <test, CI, or manual inspection> | passed / failed / not-run | <implementation SHA, check URL, or concise source> |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- List unverified behavior, deferred work, rollout concerns, or monitoring needs.
- If none are known, write: `No remaining risk was identified within the evidence scope.`

## Observed Execution Signals

- Exact tool-call count: <verified count or unavailable>
- Exact failed tool-call count: <verified count or unavailable>
- Material failures that affected the implementation: <concise list or not observed within the evidence scope>

Do not estimate counts from memory and do not include raw command output.

## Learning Candidates

- List only transferable candidates that may deserve a test, repository rule, checklist, or skill update after validation.
- Do not present a candidate as an adopted rule unless that rule was actually changed.
