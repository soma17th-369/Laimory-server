---
name: review-pr
description: Perform a code review on a GitHub pull request — read the commits and diff, evaluate the changes across design, correctness, complexity, tests, security, transaction boundaries, performance, error handling, and concurrency, then post the review as inline line comments plus a summary on the PR. Use whenever the user shares a GitHub PR URL or number and asks to "코드리뷰 해줘", "PR 리뷰해줘", "이 PR 봐줘", "리뷰해서 코멘트 남겨줘", "리뷰 코멘트 달아줘", "이 PR 괜찮은지 봐줘", "여기 문제 없는지 확인하고 코멘트 달아줘", "review this PR", "do a code review", "leave review comments on this PR", or "check this pull request". Trigger this even when the user doesn't say the word "review" explicitly, as long as they want code that's already on a PR looked over and commented on. Do NOT use this for resolving review feedback the user RECEIVED as the PR author (that's the resolve-pr-comment skill), for writing the PR description, or for merging — this skill is the reviewer reviewing someone's PR and leaving comments.
---

# Review PR

Read a GitHub pull request, review the code change systematically, and post the review — inline comments on specific lines plus an overall summary — back to the PR.

The goal is the standard from Google's code review guide: make sure the change **improves the overall code health of the system**, not that it's perfect. Approve-worthy code can still get comments. The valuable output is a review that separates what genuinely blocks the merge from what's optional, so the author knows exactly what to act on.

Two principles that shape everything below:

- **Separate blockers from preferences.** A reviewer who flags personal style as if it were a bug erodes trust and slows everyone down. Every comment carries a severity so the author can triage.
- **Never post without the user's go-ahead.** Posting a review is public, visible to the author and the whole team, and hard to walk back. Always show the drafted review in chat and get an explicit yes before anything lands on GitHub.

## When to use this

- The user shares a PR (URL or number) and wants it reviewed and commented on.
- The user is the reviewer on someone else's PR, or wants a self-review pass on their own before requesting review.

When **not** to use it:

- The user is the PR author working through feedback they already received → that's `resolve-pr-comment`.
- The user just wants a verbal opinion on a snippet they pasted, with no intent to post to GitHub → just review it in chat, no skill needed.

## Workflow

### 0. Identify the PR

Don't guess — reviewing the wrong PR wastes the user's time and risks posting comments on the wrong place.

- URL like `https://github.com/<owner>/<repo>/pull/<n>` → parse owner/repo/number.
- Bare number → default to the current repo (`gh repo view --json owner,name`).
- Nothing specific ("이 PR 리뷰해줘" with no link) → run `gh pr status` / `gh pr list` and confirm which one.

Confirm the language the comments should be in. Default to matching the language already used in the PR's description and existing comments (for the user's own Korean-language repos that's usually Korean; for English/OSS repos, English). Ask if it's ambiguous.

### 1. Gather the full change, not just the diff

A review based only on the diff hunks misses the most important category — design — because you can't see how the change fits the file and the system. Get both:

- **The diff and commits**: `gh pr diff <pr>` and `gh pr view <pr> --json title,body,commits,files`. Read the PR description to understand *intent* — you're checking whether the code does what the author meant and whether that's good for users.
- **The surrounding code**: if the repo is checked out locally, read the whole changed files and the call sites. If not, fetch them (see `references/gh-review-commands.md`). Per the Google guide: a four-line change can be the symptom of a fifty-line method that now needs splitting — you only see that with the whole file.

Read **every** changed line you're reviewing. Skim only generated code, lockfiles, and large data files. If a block is too hard to understand, that's itself review feedback — ask the author to clarify rather than rubber-stamping it.

Do **not** write findings from the diff alone. Before flagging a possible bug, trace the actual flow that can produce it:

- Follow the producer -> validation boundary -> persistence/state transition -> consumer/read path.
- Identify invariants the code already establishes upstream. If an upstream boundary guarantees a field, state, ownership, or shape, don't re-flag the downstream code for a case that cannot occur through the real product path.
- For claims like "this can be null", "this can be unauthenticated", "this can bypass validation", or "this creates an inconsistent state", name the concrete path that makes it happen. If the only path is manual DB corruption, test-only construction, or a future/legacy migration scenario, classify it accordingly or omit the inline comment.
- When the diff touches a mapper, DTO, helper, or low-level service, read its callers and the write path that feeds it before commenting. Most false positives come from reviewing a downstream line without checking the upstream contract.

### 2. Review systematically against the checklist

Work through `references/review-checklist.md` rather than reacting ad hoc — it's the structured set of things to look for, ordered by importance (design first, style last), and it includes the backend-specific dimensions (security, transaction boundaries, performance, error handling, concurrency) that don't show up by just reading the diff. As you go, collect findings as a list of `{path, line, severity, note}`.

Severity scheme (prefix every inline comment with one):

- **[Blocker]** — must change before merge: a bug, security hole, data-loss risk, broken test, or design problem that degrades code health.
- **[Suggestion]** — would meaningfully improve the code; the author should seriously consider it but it isn't strictly blocking.
- **[Nit]** — minor / stylistic / personal preference. Explicitly optional. Never let nits hold up a merge.
- **[Question]** — you don't understand something or need the author's intent before judging. Genuinely asking, not a disguised demand.
- **[Praise]** — something done well. Include these. Reviews that only point at mistakes are demoralizing, and naming good patterns is some of the most useful mentoring you can do.

### 3. Draft the comments and summary

For each finding, write an inline comment anchored to the file and line. Good review comments:

- **Are specific and located.** "This query is N+1" on the exact line beats "watch out for performance" in the summary.
- **Explain the why and, when non-obvious, suggest a fix.** "[Suggestion] This runs a query per element of `orders`. Consider a `@EntityGraph` or fetch join so it's one query." The author shouldn't have to guess what you'd accept.
- **Are framed as the reviewer's reading, not commands** for anything subjective. Use [Question] when you might be missing context.
- **Don't mix a big style rewrite into a functional review.** If the whole file wants reformatting, say so once in the summary; don't litter twenty [Nit]s.

Then write a **summary** (the review body): 2-4 sentences on the overall design and whether it's healthy, what the blockers are (if any), and at least one genuine positive. This is what the author reads first.

### 4. Show the user the full review and get explicit approval — do not skip

Render the entire review in chat before touching GitHub: the summary, then each inline comment with its file, line, severity, and text. Then ask plainly, e.g. "이대로 PR에 올릴까요? 빼거나 고칠 코멘트 있으면 말해줘."

This matters for two reasons. Posting is public and irreversible-ish — the author gets notified the moment it lands. And the user may want to drop a [Nit], soften a [Blocker], or correct a comment where you misread the intent. Let them edit before it's on the record. Wait for a clear yes. If they want changes, revise and show again.

Also confirm the **verdict** here. Default to posting as a plain `COMMENT` (neutral). Only use `REQUEST_CHANGES` or `APPROVE` if the user explicitly asks — those are strong social signals, and `APPROVE` in particular shouldn't be automated.

### 5. Post the review as one batched review

Post all inline comments + the summary as a **single review**, not as N separate comments — otherwise the author gets spammed with a dozen notifications and the comments aren't grouped.

Write the approved review to a JSON spec and post it with the bundled script, which handles the parts that are easy to get wrong by hand (using `line`+`side` instead of legacy `position`, pinning `commit_id` so comments don't drift if the author pushes, and reporting the cause when GitHub rejects a comment):

```bash
python scripts/post_review.py --owner <owner> --repo <repo> --pr <pr> --spec review.json
```

**Encoding rule:** write `review.json` as UTF-8 and pass it by path. Do not generate non-ASCII review JSON through a Windows PowerShell pipeline unless you have explicitly configured UTF-8 byte output. Mojibake in a public PR review is worse than a failed post; the script rejects invalid UTF-8 and obvious replacement-character garbage before posting.

The spec shape and the raw `gh api` call it wraps (for when you need to do it by hand) are in `references/gh-review-commands.md`. Get `commit_id` from `gh pr view <pr> --json headRefOid`.

After posting, give the user the review URL and a one-line recap: how many blockers / suggestions / nits, and the verdict.

## Comment templates

Keep them short and concrete. Examples (adapt language to the repo):

**Blocker — bug:**
> [Blocker] `parseToken` swallows the exception and returns null, so a malformed token looks identical to an expired one to the caller. Callers can't distinguish the cases — suggest letting it throw or returning a typed result.

**Blocker — security:**
> [Blocker] This trusts `userId` from the request body. Anyone can pass another user's id and read their orders. Use the id from the authenticated principal instead.

**Suggestion — performance:**
> [Suggestion] This lazy-loads `member` once per row in the loop (N+1). A fetch join or `@EntityGraph` on the query would make it one round trip.

**Nit:**
> [Nit] `tmp` → maybe `pendingOrders`? Non-blocking, just for the next reader.

**Question:**
> [Question] Is the `REQUIRES_NEW` here intentional? If the outer transaction rolls back, this write will still be committed — want to confirm that's the desired behavior before I flag it.

**Praise:**
> [Praise] Nice — the test covers the concurrent-decrement case explicitly, which is exactly where this kind of bug hides.

## Gotchas

- **Inline comments must land on lines in the diff.** The reviews API anchors a line comment to a line that's part of the change (use `line` + `side: "RIGHT"` for added/changed lines, `side: "LEFT"` for removed ones). Commenting on an unchanged line outside the diff returns 422 — put that feedback in the summary, or anchor it to the nearest changed line and reference the real location in the text.
- **Use `line`, not `position`.** The modern API takes the file line number (`line`, plus `start_line` for multi-line). `position` (offset within the diff) is legacy and easy to get wrong.
- **Pin `commit_id` to the PR head SHA.** If the author pushes more commits between your read and your post, an unpinned review can attach comments to the wrong place. Get the head SHA from `gh pr view <pr> --json headRefOid`.
- **One review, not many comments.** Resist posting comments one-by-one as you find them; batch into a single review (Step 5) so it arrives grouped and the author isn't flooded.
- **Don't auto-APPROVE.** Approval is a personal attestation. Even if the code looks clean, default to `COMMENT` and let the user choose to approve.
- **Bot-authored PRs (Dependabot, etc.)** still deserve a real look at what's changing, but the review bar and tone differ from a human's PR — be pragmatic.
- **Large PRs.** If the diff is huge, say so. A useful partial review ("reviewed the auth and DB layers; didn't get to the frontend") with that scope stated in the summary beats a shallow skim of everything. The single best thing you can tell the author of a 2000-line PR may be to split it.
- **403 on post.** Posting a review needs the right access. If the post returns 403, the account may lack permission on the repo (common on fork PRs) — surface this to the user rather than retrying.
- **Recovering from a bad post.** A *submitted* review can't be deleted, only **dismissed** (`PUT /repos/<owner>/<repo>/pulls/<pr>/reviews/<review-id>/dismissals` with a reason), and individual comments can be edited (`PATCH`) or deleted (`DELETE /repos/<owner>/<repo>/pulls/comments/<comment-id>`). The cleaner safety net is the Step 4 approval gate — and if the user wants to eyeball it in GitHub first, post with `event: "PENDING"`, which creates a draft review they submit themselves from the GitHub UI (a pending review *can* be deleted before submitting).

## Worked example: one finding end-to-end

So the workflow isn't abstract — one finding from read to posted comment.

**The diff hunk** (from `gh pr diff`): a new method in `OrderService.java`:
```java
public List<OrderDto> getOrders(Long userId) {
    List<Order> orders = orderRepository.findByUserId(userId);
    return orders.stream().map(o -> new OrderDto(o, o.getMember().getName())).toList();
}
```

**Read the surrounding code** (Step 1): the endpoint calling this passes `userId` straight from the request body, and `Order.member` is a `@ManyToOne(fetch = LAZY)`.

**Apply the checklist** (Step 2): two findings.
- Security → authorization: `userId` comes from the client, so any user can read another's orders. → **[Blocker]**.
- Performance → N+1: `o.getMember().getName()` lazy-loads the member once per order. → **[Suggestion]**.

**Draft the comments** (Step 3), anchored to the lines:
```json
{
  "commit_id": "<head sha>",
  "event": "COMMENT",
  "body": "Solid shape overall and the DTO mapping is clean. One blocker on authorization to sort out before merge, plus an N+1 worth fixing.",
  "comments": [
    {"path": "src/main/java/com/x/OrderService.java", "line": 2, "side": "RIGHT",
     "body": "[Blocker] userId is taken from the request, so a caller can pass someone else's id and read their orders. Use the authenticated principal's id instead."},
    {"path": "src/main/java/com/x/OrderService.java", "line": 3, "side": "RIGHT",
     "body": "[Suggestion] member is LAZY, so this maps with one query per order (N+1). A fetch join or @EntityGraph on findByUserId makes it one round trip."}
  ]
}
```

**Show the user and confirm** (Step 4), then **post** (Step 5):
```bash
python scripts/post_review.py --owner x --repo shop --pr 128 --spec review.json
```

That's one pass. The summary recap to the user: 1 blocker, 1 suggestion, posted as COMMENT.

## Reference files

- `references/review-checklist.md` — what to look for, ordered by importance, including the backend dimensions. Read this in Step 2.
- `references/gh-review-commands.md` — the `gh` / API commands to fetch the PR and the spec shape for posting. Read this in Steps 1 and 5.
- `scripts/post_review.py` — posts the approved review as one batched review. Used in Step 5.
