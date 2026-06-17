---
name: resolve-pr-comment
description: Triage and resolve unresolved PR review comments by checking each one against the actual codebase, deciding accept/reject/defer, applying the code change when accepted, and posting a reply on the thread. Use whenever the user mentions "PR 리뷰 코멘트", "PR 댓글 처리", "리뷰 피드백 반영", "리뷰 코멘트 답변", "코멘트 회신", "PR 리뷰 정리", "resolve PR comments", "address review feedback", or shares a PR URL/number and asks to "go through the comments", "handle the feedback", "respond to review", or "fix the review comments". Also trigger when the user pastes review comments and asks "what should I do with these" or says "정리해줘" / "처리해줘" with an open PR in context. Do NOT use for writing the initial PR description, requesting reviews, doing the *first* code review on someone else's PR, or merging — this skill is specifically about the PR author closing the loop on review feedback they've already received.
---

# Resolve PR Comment

Walk through every unresolved review comment on a PR, decide whether each one should be accepted by checking the actual code, apply the change if accepted, and post a reply comment so the reviewer knows what happened.

The point of this skill is to give the reviewer a clear answer for every comment they left — either "fixed in <commit>" or "considered but not changing because <reason>" — rather than silently ignoring some and addressing others.

## When to use this

- The user has an open PR with multiple review comments and wants to work through them systematically.
- The user pastes review comments and asks how to address them.
- The user says something like "리뷰 코멘트 정리해줘" or "address PR feedback" with a PR reference.

If the user just wants to do one specific change a reviewer asked for, that's regular coding — you don't need this skill.

## Workflow

### 0. Identify the PR

Don't proceed with a guess — fetching the wrong PR's comments wastes the user's time and produces nonsense triage.

- If the user gives a URL like `https://github.com/<owner>/<repo>/pull/<n>`, parse it.
- If they give just a number, default to the current repo (`gh repo view --json owner,name`).
- If they give nothing ("내 PR 처리해줘"), run `gh pr status` to list PRs they authored on the current branch and confirm which one before proceeding.

### 1. Collect the unresolved comments

Run the unresolved-thread GraphQL query in `references/gh-commands.md` and filter to threads where `isResolved` is `false`. Treat each thread (not each comment) as the unit to resolve — a thread may have back-and-forth, and the reviewer's original ask plus any later clarifications together are what you need to address.

If `gh` is not installed or the user prefers, ask whether they'd rather paste the comments directly. Either input mode works — the rest of the workflow is the same.

### 2. Plan before touching code

Before resolving anything, briefly list every thread you found and what each one is asking for, then confirm the plan with the user. Without this step, the model tends to start fixing the first comment immediately and only notices conflicts between comments after it's already half-done — for example, two reviewers asking for opposite renames, or one comment that becomes moot because of how you address another. The plan list also lets the user reorder, drop threads they want to handle themselves, or flag ones where the reviewer's intent isn't what you read it as.

A short table is usually enough:

```
1. src/api/auth.py:42 — reviewer wants extract_token() renamed to parse_token()
2. src/db/migrations/003.sql — reviewer questions whether the index is needed
3. README.md — reviewer asks for an example in the auth section
```

Ask: "Should I work through all of these, or skip any?"

### 3. For each thread, decide and act

For every thread you're handling, run this loop:

**a. Read the actual code at the comment location.** Don't rely on the diff hunk in the comment — pull the current file. The code may have moved since the review, or the reviewer may have been looking at a stale version.

**b. Decide the disposition.** Pick one:

- **Accept** — the reviewer is right, apply the change.
- **Accept with modification** — the spirit is right but the literal suggestion needs adjusting (e.g., they suggested a rename but the new name they proposed conflicts with an existing symbol).
- **Reject with reason** — the reviewer's premise is wrong, or the change would break something they couldn't see, or there's a deliberate design choice they missed.
- **Defer** — valid concern but out of scope for this PR; will be tracked separately. When picking this disposition, file a follow-up issue with `gh issue create -t "<title>" -b "<context + link to thread>"` so the reply can reference a real issue number. If the user prefers not to file issues (some teams use a different tracker), ask before defaulting to `gh issue create` and adjust the reply template to "noting it for later" instead.
- **Needs user input** — the right answer depends on intent or context only the user has (product decisions, team conventions, anything subjective).

The "Needs user input" disposition is the important one. When in doubt, ask the user — don't guess on something they care about. Examples of when to ask:

- The reviewer is questioning a product/UX decision, not an implementation detail.
- Two reviewers gave conflicting feedback on the same code.
- The change is large enough that "accept" means rewriting a chunk of the PR.
- The reviewer's tone suggests strong opinion ("I'd really push back on this approach").

**c. If accepting, make the change.** Edit the file. Run any project-relevant checks (tests, linter, type checker) for the touched files. If the change cascades — renaming a function means updating every caller — do the cascade in the same step, not "I'll get to that later."

**d. Post the reply comment.** Reply on the thread with what you did and why. Keep it short. Examples in the "Reply comment templates" section below.

```bash
# Reply to a specific review thread (uses the thread's first comment ID as in_reply_to).
gh api repos/<owner>/<repo>/pulls/<pr-number>/comments \
  -f body="<reply text>" \
  -F in_reply_to=<original-comment-databaseId>
```

**e. Resolve the thread (only when appropriate).** If the change is committed and you're confident the reviewer would mark it resolved, you can resolve it via the GraphQL `resolveReviewThread` mutation. Default to *not* resolving on the reviewer's behalf for substantive disagreements — let them close the loop. For trivial fixes (typos, lint, obvious nits) auto-resolving is usually welcome; ask the user if you're unsure of the team's norm.

```bash
gh api graphql -f query='
  mutation($threadId: ID!) {
    resolveReviewThread(input: {threadId: $threadId}) {
      thread { isResolved }
    }
  }
' -F threadId=<thread-id>
```

### 4. Summarize at the end

After working through all threads, give the user a single summary: which were accepted, which were rejected (with the reason given to the reviewer), which need their input, and which are deferred. This is what they'll glance at before pushing the commit.

## Reply comment templates

Keep replies short, specific, and link to the commit when there is one. The reviewer will see this in their inbox; respect their time.

**Accepted, simple fix:**
> Done in <commit-sha> — renamed to `parse_token` and updated the three callers.

**Accepted with modification:**
> Agreed on the rename. Used `parse_auth_token` instead of `parse_token` since `parse_token` already exists in `lexer.py`. Done in <commit-sha>.

**Rejected, with reason:**
> Looked into this — keeping the current name. `extract_token` matches the convention used in the other auth modules (`extract_session`, `extract_refresh`), and renaming just here would be inconsistent. Open to revisiting if we rename them all.

**Deferred:**
> Good catch, but this index question affects the whole migrations strategy and I'd rather handle it in a follow-up so this PR stays focused. Filed as #<issue-number>.

**Needs more info:**
> Want to make sure I'm reading this right — are you suggesting we drop the retry entirely, or just lower the count? Happy to do either.

## Gotchas

- **Comments on outdated diffs.** If `isOutdated` is true, the line the reviewer pointed at may not exist anymore. Use `originalLine` from the GraphQL response to find what the reviewer was actually looking at — `git blame` or `git log -p` for that line in earlier commits will usually surface it. Then check whether the concern still applies in the current code before either accepting or replying with "no longer relevant" — sometimes the issue moved rather than disappeared.
- **The diff hunk lies.** The `diffHunk` field shows the diff at review time, not now. Always read the live file before deciding what to do.
- **Threading isn't always linear.** A reviewer may have left a comment, the author replied, the reviewer clarified — the *latest* message is usually what to address, but the original comment is what other threads are anchored to. When replying, use the original comment's `databaseId` as `in_reply_to`.
- **Bot comments.** CodeRabbit, sourcery, etc. leave review comments too. They're often noise, but not always. Don't auto-skip them — triage them like human comments, just be willing to reject more aggressively when the suggestion is clearly mechanical and wrong for the context.
- **Resolving on the reviewer's behalf.** Marking a thread resolved is a small social signal that says "I think this is done." Don't do it for anything where the reviewer might reasonably push back. Defaulting to leaving it unresolved is safer; the reviewer can always resolve it themselves once they read the reply.
- **Force-push surprises.** If the workflow involves rewriting history (e.g., squashing fixup commits), the comment commit-SHA references in your replies will become dangling. Either reference the PR-relative position ("in the latest version of `auth.py`") or post replies after the final push.
- **Multiple unresolved threads on the same line.** Sometimes two reviewers comment on the same line. Treat them as separate threads — each gets its own reply — but make the change once and reference the same commit in both replies.
- **Permissions.** Posting comments and resolving threads requires push access or being the PR author. If `gh` returns 403, the user may need to authenticate or you may be operating on a fork PR where comment permissions differ.

## Worked example: one thread end-to-end

A complete trace, so the loop in Step 3 isn't abstract.

**Fetched comment** (from the GraphQL query):
> `path: src/auth/token.py`, `line: 42`, `author: alice`
> "This `try/except: pass` is swallowing the error silently. At minimum we should log it — otherwise we'll never know when token parsing fails in production."

**Read the live file** (Step 3a):
```python
# src/auth/token.py:38-46
def parse_token(raw: str) -> Token | None:
    try:
        payload = jwt.decode(raw, KEY, algorithms=["HS256"])
        return Token(**payload)
    except Exception:
        pass
    return None
```

**Decide the disposition** (Step 3b): the reviewer is factually correct (no logging), no subjective call (logging failures is a policy the team already follows elsewhere — `auth/session.py` logs the same way), no breakage risk from adding a log line. → **Accept**.

**Make the change** (Step 3c):
```python
def parse_token(raw: str) -> Token | None:
    try:
        payload = jwt.decode(raw, KEY, algorithms=["HS256"])
        return Token(**payload)
    except Exception as e:
        log.warning("token parse failed", exc_info=e)
    return None
```
Run `pytest tests/test_token.py` to make sure nothing broke. Stage and commit.

**Post the reply** (Step 3d):
> Done in `a3f2c91` — added a `log.warning` with `exc_info` so the stack trace is captured. Matches the pattern in `auth/session.py`.

**Resolve?** (Step 3e): yes — this is a clean small fix the reviewer would have resolved themselves. Run the `resolveReviewThread` mutation.

That's one thread. Repeat for each one in the planned list, then write the summary in Step 4.

## Reference files

- `references/disposition-guide.md` — decision tree for the five dispositions, with worked examples.
- `references/gh-commands.md` — the full set of `gh` and GraphQL commands used by this skill, ready to copy.
