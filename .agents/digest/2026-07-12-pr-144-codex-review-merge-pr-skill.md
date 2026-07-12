---
schema_version: 1
status: merge-candidate
pr_number: 144
pr_url: https://github.com/soma17th-369/Laimory-server/pull/144
title: "fix: merge-pr 머지 상태 판정 보강"
base_branch: dev
head_branch: codex/review-merge-pr-skill
implementation_head_sha: 52d680d6f268d43602d02043df2b17a2532a3341
generated_at: 2026-07-12T06:58:38Z
linked_issues: []
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #144 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: review the repository's `merge-pr` skill and apply only improvements that materially strengthen its merge safety or repeatability.
- Acceptance criteria: classify GitHub merge states conservatively, preserve explicit invocation controls across supported runtimes, document observed operational traps, and cover the changes with deterministic tests.
- Out of scope: application runtime behavior, automatic base-branch updates, installing validator dependencies, and pagination for unusually long review-thread conversations.

## Change Summary

- Treat `mergeStateStatus=BEHIND` as a blocker because the PR head is out of date with the base branch.
- Treat `UNSTABLE` as a waiting state so the inspector does not report ready while GitHub still reports a non-passing status; continue allowing `HAS_HOOKS` as mergeable with passing status.
- Add regression coverage for the new merge-state decisions and the Codex implicit-invocation policy.
- Add Gotchas for host-keyring visibility, isolated worktree use, and post-digest CI, and link every bundled resource from `SKILL.md`.

## Plan Deviations

- The initial Claude-oriented review proposed `disable-model-invocation: true` in shared frontmatter. That change was removed after the Codex `skill-creator` specification and validator showed that shared frontmatter must remain limited to Codex-supported fields. The existing `agents/openai.yaml` policy and explicit authorization checks in the workflow body remain authoritative.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| Skill validation | The official `quick_validate.py` could not start, so it produced no validation result. | confirmed | Read its validation logic and ran equivalent YAML, field, type, naming, and length checks without installing new dependencies. | Both local and bundled Python lacked PyYAML; the equivalent validation passed. |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| `skill-reviewer` | Add an explicit Gotchas section and connect orphaned bundled resources. | accepted | These changes preserve hard-won operational knowledge without altering application behavior. |
| GitHub GraphQL schema inspection | Handle `BEHIND`, `UNSTABLE`, and `HAS_HOOKS` explicitly. | accepted | The prior evaluator could report ready for states that GitHub defines as out-of-date or non-passing. |
| Claude invocation recommendation | Add `disable-model-invocation` to shared `SKILL.md` frontmatter. | modified | Codex rejects that shared frontmatter field; runtime-specific implicit invocation remains disabled in `agents/openai.yaml`, and the body still requires an explicit merge command. |
| Reviewer follow-up | Paginate more than 20 comments inside one review thread. | deferred | Thread resolution is still evaluated, and no evidence showed this uncommon case affecting current merge safety. |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| Merge-state evaluation and invocation metadata | Python unit tests | passed | 20 tests at `52d680d6f268d43602d02043df2b17a2532a3341` |
| Frontmatter compatibility | Equivalent `quick_validate.py` structural checks with Ruby YAML | passed | local verification at implementation head |
| Patch hygiene | `git diff --check` | passed | implementation head |
| Repository integration | GitHub Actions `build` | passed | https://github.com/soma17th-369/Laimory-server/actions/runs/29183417855/job/86625149984 |
| Merge gates | bundled read-only inspector | passed | PR #144 at implementation head; no unresolved review threads |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- The official Python validator was not executed successfully because PyYAML is absent; equivalent structural checks passed.
- Review-thread comment bodies are fetched only for the first 20 comments in a thread. Resolution state is still checked independently.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: official validator dependency was unavailable; equivalent validation passed without expanding dependencies.

## Learning Candidates

- Shared Claude/Codex skills should keep common behavior in `SKILL.md` while placing runtime-specific invocation metadata in each runtime's supported configuration surface.
- Merge-state enums should be covered by explicit tests so newly observed GitHub states cannot silently become ready by default.
