# Disposition Guide

A short decision tree for picking one of the five dispositions when triaging a PR review comment. The point is to make the decision *fast* and *defensible* — you should be able to tell the user (and the reviewer) why you picked the disposition you did in one sentence.

## The decision tree

Read the comment, look at the actual code, then ask in order:

1. **Is the reviewer factually correct about what the code does?**
   - No → likely **Reject with reason** (the premise is wrong; explain what the code actually does).
   - Yes → continue.

2. **Is the suggested change something only the user/author can decide?** (product behavior, team convention, name choice when multiple are reasonable)
   - Yes → **Needs user input**. Don't guess on subjective calls.
   - No → continue.

3. **Would the change break something the reviewer couldn't see?** (downstream caller, hidden test, deliberate edge case)
   - Yes → **Reject with reason**, naming the specific thing that would break.
   - No → continue.

4. **Is the change in scope for this PR?**
   - No, but the concern is valid → **Defer** (file as a follow-up issue, reply with the link).
   - Yes → continue.

5. **Is the literal suggestion fine, or does it need adjusting?**
   - Fine → **Accept**.
   - Needs adjusting (name conflict, slight scope tweak) → **Accept with modification**.

The order matters: factual correctness first, then user-decision check, then breakage check, then scope, then literal-fit. Skipping straight to "accept" without the breakage check is how you ship regressions because a reviewer asked for something locally sensible that has nonlocal consequences. Skipping the scope check is how a small bug-fix PR balloons into an unrelated refactor.

## Worked examples

### Example 1: Accept

> **Reviewer:** This logger call uses `f"{user.id}"` but the rest of the file uses structured logging with `extra={"user_id": user.id}`.

The reviewer is right (factual), no subjective call (style is set by the rest of the file), no breakage risk, suggestion is exactly applicable. **Accept** and update the call.

### Example 2: Accept with modification

> **Reviewer:** Rename `get_data()` to `fetch_data()` to match the convention.

Concept is right, but `fetch_data` is already used in the same module for a different operation. **Accept with modification**: rename to `fetch_user_data()` and explain why in the reply.

### Example 3: Reject with reason

> **Reviewer:** This `try/except` is overly broad — catch `ValueError` specifically.

You check, and the wrapped block actually calls a third-party library that raises a custom `ProviderError` for parse failures plus `ValueError` for arithmetic. Catching only `ValueError` would let `ProviderError` escape and crash a request handler. **Reject with reason**: explain what else is raised and why the broad catch is intentional.

### Example 4: Defer

> **Reviewer:** While we're here, this whole module should be moved to `services/` instead of `lib/`.

Valid concern, but the PR is a bug fix and moving the module would balloon the diff and stall review. **Defer**: file an issue, reply linking to it.

### Example 5: Needs user input

> **Reviewer:** Should we be showing the full error message to the user here, or a generic one? Leaking the SQL error feels off.

This is a product/security policy call, not an implementation detail. Don't pick on the user's behalf. **Needs user input**: ask the user how they want to handle error messages, then act.

## When two dispositions seem to fit

Pick the one that's *more conservative for the reviewer*, not the author:

- "Accept vs Defer" → Accept if it's small. Defer only when accepting would meaningfully grow the PR.
- "Reject vs Needs user input" → Needs user input. Rejecting commits to a position; asking lets the user decide whether to push back.
- "Accept vs Accept with modification" → If you're adjusting the suggestion at all, call it "Accept with modification" and explain — reviewers don't like being told "done" only to find their suggestion wasn't actually what shipped.

## Anti-patterns

- **Silent acceptance.** Making the change but not replying. The reviewer doesn't know you addressed it.
- **Argumentative rejection.** A long debate-club rebuttal in the reply. Keep rejections to one or two sentences with the concrete reason; if the reviewer disagrees, they'll say so.
- **Bulk-accepting "nits".** Tempting because they're small, but if a "nit" rename shows up everywhere and you don't actually want the new name, you've now committed to it. Apply the same triage you would to a substantive comment, just faster.
