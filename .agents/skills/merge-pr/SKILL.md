---
name: merge-pr
description: Finish and squash-merge the current GitHub pull request after publishing a durable PR digest comment and verifying the repository's merge gates, then close the issues the PR links through closing keywords. Use when the user explicitly commands an actual merge with phrases such as "머지해줘", "이 PR 머지하자", "리뷰 반영 끝났으니 머지해", "merge this PR", or invokes `/merge-pr`, optionally with a PR number or URL. Do not use when the user only asks whether a PR is mergeable, asks for PR status, requests a code review, asks to resolve review comments, or discusses merging hypothetically; those requests do not authorize a merge.
---

# Merge PR

Treat the user's explicit merge command as authorization to complete this workflow. Do not ask for a second
confirmation after all gates pass. If the current request is not an explicit command to merge, report status only
and do not modify Git or GitHub state.

This skill handles work-branch PRs targeting `dev`. Refuse `dev` to `main` and any other base branch because release
merges require a separate workflow.

## Safety rules

- Run from the repository root with the PR head branch checked out.
- Require a clean worktree before creating the digest. Never discard, stash, stage, or commit unrelated changes.
- Run only one merge-pr workflow for a given PR at a time. The issue-comment API has no atomic uniqueness
  primitive for the initial marker comment.
- Treat an explicit merge command as authorization to convert an OPEN draft PR to Ready for review before
  inspection. Re-read the PR afterward and stop if the conversion fails or it remains a draft.
- Stop on ambiguity, closed PRs, merge conflicts, `CHANGES_REQUESTED`, unresolved review threads,
  missing/failing/pending checks, or local/remote head mismatch.
- Require the `build` check to finish with `SUCCESS`; absence is not success.
- Record only evidence you can verify. Never invent tool-call counts, failure counts, causes, tests, or review
  decisions.
- Never copy transcripts, hidden reasoning, raw command output, secrets, credentials, tokens, request bodies, or
  environment values into a digest.
- Keep the digest in a restrictive repository-outside temporary directory and remove it on every success or stop
  path. Never fall back to a repository path.
- Use squash merge only. Never use `--admin`, `--auto`, merge commit, rebase merge, or force-push.
- After the merge is verified, close only the issues GitHub reports in `closingIssuesReferences`. An issue
  mentioned in prose, in the branch name, or in a review comment is a candidate to report, never a target to close.

## Workflow

### 1. Identify, promote, and inspect the PR

Before any PR read or mutation, run `gh auth status`. On macOS, a failure seen only inside a sandbox is not proof
that the login is invalid: re-run the same read-only command from a context allowed to access the existing macOS
Keychain, with user-approved sandbox escalation when available. If that execution path is unavailable, ask the user
to run `gh auth status` in the host terminal and report only the status output. Recommend `gh auth login` only when
the Keychain-capable check also reports no valid login. Never run or request output from `gh auth token`, and never
ask the user to reveal an unmasked credential.

Pass `--pr` only when `$ARGUMENTS` contains a recognizable PR number, PR URL, or branch name. Ignore prose arguments
such as “이거 머지해줘” and use the PR associated with the current branch.

Read the PR state before running the bundled inspector:

```bash
gh pr view [<number-or-url>] --json state,isDraft,url
```

Require `state=OPEN`. If `isDraft=true`, convert it because the current explicit merge command authorizes that
transition, then re-read the state and require `isDraft=false`:

```bash
gh pr ready [<number-or-url>]
gh pr view [<number-or-url>] --json state,isDraft,url
```

Do not convert a draft when the user only asks for status, review, or mergeability; those requests do not authorize
this skill or the Ready transition. Keep the inspector's draft blocker as defense in depth.

Run the bundled inspector from the repository root:

```bash
SKILL_DIR="${CLAUDE_SKILL_DIR:-.agents/skills/merge-pr}"
python3 "$SKILL_DIR/scripts/inspect_pr.py" --wait --timeout 1200 [--pr <number-or-url>]
```

Interpret exit codes exactly:

- `0`: ready to prepare the digest.
- `1`: inspection could not run; report the operational error and stop.
- `2`: a gate is still waiting or timed out; report the waiting reasons and stop.
- `3`: a gate is blocked; report every blocker and stop.

Read the issues this PR closes on merge, and keep the numbers as `linked_issues`:

```bash
gh pr view [<number-or-url>] --json closingIssuesReferences
```

An empty list is a valid state, not a blocker: it only means the PR body carries no closing keyword. Never
substitute a number guessed from the branch name or from prose in the PR body for this list.

Keep the inspector's `head_sha` as `implementation_head_sha`. Use its PR metadata, commits, changed files, checks,
and review threads as objective evidence. Read the PR diff and relevant changed files when needed to understand
behavior; do not summarize from filenames alone.

### 2. Create the repository-outside digest snapshot

Read `assets/digest-template.md`. Create a restrictive temporary directory outside the repository:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
DIGEST_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/laimory-pr-digest.XXXXXX")"
chmod 700 "$DIGEST_TMP_DIR"
DIGEST_PATH="$DIGEST_TMP_DIR/pr-<number>-digest.md"
```

Keep a `finally`-equivalent cleanup registered from this point onward. In a single shell session, the equivalent is:

```bash
cleanup_digest() {
  rm -f -- "$DIGEST_PATH"
  rmdir -- "$DIGEST_TMP_DIR"
}
trap cleanup_digest EXIT
```

Remove the file and directory on every success or failure path. If cleanup itself fails, report the exact temporary
path; do not weaken or change the merge result. The comment helper independently rejects any body path that resolves
inside `REPO_ROOT`.

Copy the template to `DIGEST_PATH`, keep its exact first-line marker
`<!-- laimory-pr-digest:v1 -->` exactly once, set `status: merge-candidate`, fill `linked_issues` with the
`closingIssuesReferences` numbers from Step 1 (leave `[]` when there are none), and record exactly one line:

```text
implementation_head_sha: <implementation_head_sha>
```

The body is a comment snapshot of the implementation at that SHA. Do not claim that the PR is already merged. Do
not create or update `.agents/digest/` files.

Synthesize the digest from:

- the current conversation still available in context;
- the implementation plan and deviations actually observed;
- local commits and diff;
- GitHub PR body, commits, review threads, and checks;
- tests and manual verification with concrete evidence.

Use `confirmed`, `suspected`, or `unknown` for cause certainty. Write “not observed within the evidence scope”
instead of claiming an event never occurred. Mark unavailable execution metrics as `unavailable`; never estimate
them.

Review the body for placeholders, unsupported claims, raw output, and sensitive values. A GitHub Web UI Markdown
attachment is optional only when the user explicitly requests it; it is not the canonical digest, a helper gate, or
an automated upload step. Never use an undocumented upload endpoint.

### 3. Create or update the marker comment

Run the dedicated helper. It reads every page of PR Conversation issue comments and selects only comments written
by the current authenticated user that contain the exact marker:

```bash
python3 "$SKILL_DIR/scripts/upsert_digest_comment.py" \
  --pr <number-or-url> \
  --body-file "$DIGEST_PATH" \
  --expected-head <implementation_head_sha>
```

Interpret exit codes exactly:

- `0`: the helper created or updated one comment and verified the head, author, marker, exact body, comment ID,
  and URL by reading GitHub again.
- `1`: command, authentication, API, JSON, or file validation failed. Report the concise error and stop.
- `3`: a safety contract failed, including duplicate current-author marker comments, a head race, or a post-read
  mismatch. Report the blocker and stop without retrying with weaker selection.

Retain the success JSON's `comment_id` and `comment_url`. The helper uses:

- zero current-author marker comments: one POST to the PR issue-comment endpoint;
- one: one PATCH to that exact REST comment ID;
- more than one: no mutation and exit `3`.

Never replace this identity rule with “last comment”, `--edit-last`, automatic duplicate deletion, or a retry loop.
Comment creation can notify subscribers or hit a secondary rate limit; one API failure stops the workflow.

### 4. Recheck the same implementation head, build, and comment

Re-run the inspector with the original implementation SHA:

```bash
python3 "$SKILL_DIR/scripts/inspect_pr.py" \
  --pr <number-or-url> \
  --expected-head <implementation_head_sha> \
  --wait \
  --timeout 1200
```

Stop if another push changes the PR head, any review gate changes, or any check fails or times out. Require the same
SHA's `build` conclusion to remain `SUCCESS`.

After the inspector returns ready and immediately before the merge command, re-read and verify the exact comment
without mutation:

```bash
python3 "$SKILL_DIR/scripts/upsert_digest_comment.py" \
  --verify-only \
  --pr <number-or-url> \
  --body-file "$DIGEST_PATH" \
  --expected-head <implementation_head_sha> \
  --comment-id <comment_id>
```

Require exit `0`, the same `comment_id`, exact body, marker uniqueness, current author, non-empty URL, and unchanged
implementation head. Any failure stops the merge. Do not PATCH the digest with final check or merge results; the
GitHub PR, check, and merge state remain authoritative.

### 5. Squash merge with head protection

Immediately after final comment verification, run:

```bash
gh pr merge <number-or-url> \
  --squash \
  --delete-branch \
  --match-head-commit <implementation_head_sha>
```

The `--match-head-commit` guard must be present. If the command fails, do not retry with weaker flags.

### 6. Verify the result

Read the PR again and require `state=MERGED`:

```bash
gh pr view <number-or-url> --json state,mergedAt,mergeCommit,url,headRefName
git ls-remote --exit-code --heads origin <head-branch>
```

Treat no output plus exit code `2` from `git ls-remote --exit-code` as confirmation that the remote branch is
absent. Other failures are verification errors. Report:

- PR URL and number;
- squash merge commit SHA;
- digest comment URL;
- verified implementation head SHA and `build` result;
- whether the remote work branch was deleted.

If the PR merged but branch cleanup failed, report cleanup as incomplete without attempting a second merge.

### 7. Close the linked issues

`dev` is this repository's default branch, so GitHub auto-closes every issue linked through a closing keyword
(`Closes #N`) the moment the squash merge lands. Auto-close is the expected path, not a guarantee: verify each one
and close only what is still open. Do not skip this step because the PR body had `Closes #N`.

For every `linked_issues` entry from Step 1:

```bash
gh issue view <issue-number> --json number,state,url
```

- `state=CLOSED`: record it as auto-closed. Do not comment on it and do not re-close it.
- `state=OPEN`: close it explicitly, citing the merge as evidence:

```bash
gh issue close <issue-number> \
  --comment "PR #<pr-number> (squash merge <merge-commit-sha>) 머지 완료로 종료합니다."
```

When `linked_issues` is empty, close nothing. Report that the PR linked no issue, and list any candidate numbers
observed in the branch name or PR body so the user can close them; only an explicit user instruction authorizes
closing one of those, because an issue can cover work this PR only partly delivers.

A `gh issue close` failure is reported as incomplete follow-up with the issue number and the exact error. Never
re-run the merge, and never retry with a different issue number.

Add to the final report, per linked issue: number, URL, and whether it was auto-closed, closed by this step, or
left open with the reason. Finally remove `DIGEST_PATH` and `DIGEST_TMP_DIR`. A cleanup failure is reported
separately and does not change an already observed merge or issue result.

## Resources

- `scripts/inspect_pr.py`: read-only Git/GitHub inspection and deterministic merge-gate evaluation.
- `scripts/upsert_digest_comment.py`: exact current-author marker comment upsert and final read-back verification.
- `scripts/test_inspect_pr.py`, `scripts/test_upsert_digest_comment.py`: regression tests for merge and comment
  gates plus explicit-invocation metadata.
- `assets/digest-template.md`: required PR digest schema and evidence wording.
- `agents/openai.yaml`: Codex UI metadata and implicit-invocation policy.

## Gotchas

- On macOS, follow the Step 1 Keychain-aware authentication preflight before treating a sandboxed
  `gh auth status` failure as an invalid login.
- The comment workflow is single-writer per PR. Two simultaneous runs can both observe zero marker comments and
  POST because GitHub offers no comment uniqueness/CAS primitive. A run that observes duplicates stops without
  editing or deleting either comment; inspect and manually reduce current-author duplicates to one before retrying.
- Marker selection uses fully paginated PR Conversation issue comments. Review inline comments and another
  author's marker are not update targets.
- Creating or updating a comment can notify subscribers and can receive a secondary rate limit. Do not add an
  automatic retry loop.
- `gh pr merge --delete-branch` can switch or fast-forward the current worktree while deleting the PR branch. When
  the shared checkout is on another branch or contains unrelated work, use a clean isolated worktree for this
  workflow and remove only that worktree afterward.
- Issue auto-close on merge depends on the base branch being the repository default branch. `dev` is the default
  branch here, so a work-branch PR closes its linked issues on merge; the same PR retargeted at a non-default base
  would leave them open. Verify state instead of assuming either outcome.
- `closingIssuesReferences` is populated only by closing keywords in the PR body (or a manually linked issue). A
  branch named `fix/366-...` links nothing by itself, and GitHub will not infer the issue from it.
- The digest comment does not change the PR head or start CI. Step 4 deliberately rechecks the same
  `implementation_head_sha`, existing build, and exact comment immediately before merging.
- GitHub documents Markdown attachment through the Web UI. It is optional and manual, not an automated merge gate;
  the helper uses only the documented issue-comment body API.
- Keep shared `SKILL.md` frontmatter limited to `name` and `description` for Codex compatibility. Runtime-specific
  implicit-invocation policy belongs in `agents/openai.yaml`; destructive authorization remains enforced in the
  workflow body and inspector.
