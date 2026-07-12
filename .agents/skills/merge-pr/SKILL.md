---
name: merge-pr
description: Finish and squash-merge the current GitHub pull request after creating a durable PR digest and verifying the repository's merge gates. Use when the user explicitly commands an actual merge with phrases such as "머지해줘", "이 PR 머지하자", "리뷰 반영 끝났으니 머지해", "merge this PR", or invokes `/merge-pr`, optionally with a PR number or URL. Do not use when the user only asks whether a PR is mergeable, asks for PR status, requests a code review, asks to resolve review comments, or discusses merging hypothetically; those requests do not authorize a merge.
---

# Merge PR

Treat the user's explicit merge command as authorization to complete this workflow. Do not ask for a second confirmation after all gates pass. If the current request is not an explicit command to merge, report status only and do not modify Git or GitHub state.

This skill handles work-branch PRs targeting `dev`. Refuse `dev` to `main` and any other base branch because release merges require a separate workflow.

## Safety rules

- Run from the repository root with the PR head branch checked out.
- Require a clean worktree before creating the digest. Never discard, stash, stage, or commit unrelated changes.
- Treat an explicit merge command as authorization to convert an OPEN draft PR to Ready for review before inspection.
  Re-read the PR afterward and stop if the conversion fails or it remains a draft.
- Stop on ambiguity, closed PRs, merge conflicts, `CHANGES_REQUESTED`, unresolved review threads, missing/failing/pending checks, or local/remote head mismatch.
- Require the `build` check to finish with `SUCCESS`; absence is not success.
- Record only evidence you can verify. Never invent tool-call counts, failure counts, causes, tests, or review decisions.
- Never copy transcripts, hidden reasoning, raw command output, secrets, credentials, tokens, request bodies, or environment values into a digest.
- Use squash merge only. Never use `--admin`, `--auto`, merge commit, rebase merge, or force-push.

## Workflow

### 1. Identify, promote, and inspect the PR

Pass `--pr` only when `$ARGUMENTS` contains a recognizable PR number, PR URL, or branch name. Ignore prose arguments such as “이거 머지해줘” and use the PR associated with the current branch.

Read the PR state before running the bundled inspector:

```bash
gh pr view [<number-or-url>] --json state,isDraft,url
```

Require `state=OPEN`. If `isDraft=true`, convert it because the current explicit merge command authorizes
that transition, then re-read the state and require `isDraft=false`:

```bash
gh pr ready [<number-or-url>]
gh pr view [<number-or-url>] --json state,isDraft,url
```

Do not convert a draft when the user only asks for status, review, or mergeability; those requests do not authorize
this skill or the Ready transition. Keep the inspector's draft blocker as a defense in depth.

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

Keep the inspector's `head_sha` as `implementation_head_sha`. Use its PR metadata, commits, changed files, checks, and review threads as objective evidence. Read the PR diff and relevant changed files when needed to understand behavior; do not summarize from filenames alone.

### 2. Create or update the digest

Read `assets/digest-template.md`. Create the digest at:

```text
.agents/digest/YYYY-MM-DD-pr-<number>-<head-branch-slug>.md
```

Convert the head branch to lowercase kebab-case by replacing `/` and non-alphanumeric runs with `-`. If one existing file matches `.agents/digest/*-pr-<number>-*.md`, update it. If more than one matches, stop and report the duplicates instead of choosing one.

Set `status: merge-candidate` and record the pre-digest `implementation_head_sha`. The file is a snapshot of the implementation before its own documentation commit. Do not claim that the PR is already merged or that the post-digest CI passed.

Synthesize the digest from:

- the current conversation still available in context;
- the implementation plan and deviations actually observed;
- local commits and diff;
- GitHub PR body, commits, review threads, and checks;
- tests and manual verification with concrete evidence.

Use `confirmed`, `suspected`, or `unknown` for cause certainty. Write “not observed within the evidence scope” instead of claiming an event never occurred. Mark unavailable execution metrics as `unavailable`; never estimate them.

### 3. Commit only the digest

Review the completed digest for placeholders, unsupported claims, raw output, and sensitive values. Then stage only that file:

```bash
git add -- <digest-path>
git diff --cached --name-only
```

Require the staged file list to contain exactly the one digest path. If anything else is staged, run `git restore --staged -- <digest-path>` and stop without altering the other staged content.

If the digest is unchanged, do not create an empty commit. Otherwise commit and push:

```bash
git commit -m "docs: PR #<number> 작업 digest 추가"
git push origin HEAD
```

### 4. Recheck the new head and CI

Capture the pushed digest commit SHA with `git rev-parse HEAD`. Re-run the inspector and pin the expected head:

```bash
python3 "$SKILL_DIR/scripts/inspect_pr.py" \
  --pr <number-or-url> \
  --expected-head <digest-commit-sha> \
  --wait \
  --timeout 1200
```

Stop if another push changes the PR head, any review gate changes, or any check fails or times out. Do not rewrite the digest merely to record this final check; GitHub is the evidence for the post-digest SHA and CI result.

### 5. Squash merge with head protection

Immediately before merging, use the ready inspector result's `head_sha` and run:

```bash
gh pr merge <number-or-url> \
  --squash \
  --delete-branch \
  --match-head-commit <verified-head-sha>
```

The `--match-head-commit` guard must be present. If the command fails, do not retry with weaker flags.

### 6. Verify the result

Read the PR again and require `state=MERGED`:

```bash
gh pr view <number-or-url> --json state,mergedAt,mergeCommit,url,headRefName
git ls-remote --exit-code --heads origin <head-branch>
```

Treat no output plus exit code `2` from `git ls-remote --exit-code` as confirmation that the remote branch is absent. Other failures are verification errors. Report:

- PR URL and number;
- squash merge commit SHA;
- digest path;
- verified post-digest head SHA and `build` result;
- whether the remote work branch was deleted.

If the PR merged but branch cleanup failed, report cleanup as incomplete without attempting a second merge.

## Resources

- `scripts/inspect_pr.py`: read-only Git/GitHub inspection and deterministic merge-gate evaluation.
- `assets/digest-template.md`: required PR digest schema and evidence wording.
