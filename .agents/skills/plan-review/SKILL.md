---
name: plan-review
description: Review implementation plans before coding by validating material claims and omissions against the current repository, applicable project conventions, and version-matched primary sources. Use when the user asks to review, sanity-check, approve, improve, or critique a plan from Claude/Codex/another agent, pasted design notes, issue plans, implementation checklists, migration plans, or "can we just do this?" proposals. Also use for Korean requests like "계획 검토", "플랜 리뷰", "개선사항 한 번에 말해줘", "클로드 계획 봐줘", or "그냥 해도 될까?". Return one scope-bounded verdict and report only evidence-backed plan-level problems that must be resolved before implementation. Omit speculative, out-of-scope, implementation/code-review-level, and optional polish concerns; say the plan is ready when no material finding remains. Do not use for reviewing code already on a PR, resolving PR comments, or merging.
---

# Plan Review

Decide whether an implementation plan is safe enough to start, not whether it describes an ideal implementation.
Treat the plan as a proposal, not truth.

Inspect actual code when needed to validate architectural claims, ownership boundaries, contracts, and existing
behavior. A plan does not need to pre-decide routine coding details that can be evaluated during implementation or
code review.

## Modes

- For review only, stop after one consolidated verdict. Do not edit code, plans, issues, or docs.
- If the user asks to improve or revise the plan, review it first and then apply only the findings that pass the
  finding gate below. Keep code implementation out of scope unless the user asks to start coding.
- If the user asks "is this okay to do?", answer yes or no first.

## Workflow

1. Read the whole plan and identify its stated scope, intended behavior, and success criteria.
2. Inspect referenced files plus only the neighboring code and project instructions needed to verify the plan's
   claims. Favor repository evidence over general best practice.
3. Identify decisions that genuinely must be settled before coding. Check contracts, data compatibility, security,
   deployment, ownership, and verification only when the proposed change implicates them.
4. If viability depends on a current third-party fact, verify it with version-matched primary sources.
5. Apply the finding gate to every candidate concern. Discard candidates that do not pass.
6. Return the verdict and all qualifying findings together. Do not drip-feed additional review rounds.

## Finding Gate

Report a concern only when **all** of these are true:

1. **In scope**: it is caused by the proposed change or violates an existing contract or ownership boundary.
2. **Grounded**: repository evidence or a version-matched primary source supports it.
3. **Reachable**: there is a concrete path from the plan to a failure, not merely a possible edge case.
4. **Material**: the consequence affects behavior, data compatibility, security, deployment, team contracts, or
   the ability to verify the change.
5. **Plan-level**: the decision must be made before implementation; deferring it to implementation or code review
   would create rework or leave the plan unsafe to start.

For every reported finding, state the concrete failure, cite the governing evidence, and propose the smallest
sufficient correction. If any gate is not met, omit the concern rather than downgrading it into a suggestion.

An unknown fact may be a finding only when that exact fact determines whether the plan is viable. State what must
be verified; do not turn general uncertainty into a list of hypothetical defenses.

## Review Boundaries

- Review code-level structure when the plan commits to a boundary, responsibility, data flow, or contract that
  conflicts with the repository. Do not require method signatures, class layouts, local error branches, or other
  implementation details merely because the plan could be more specific.
- A different architecture being cleaner is not a finding. Require a concrete failure in this repository.
- Prefer the smallest existing mechanism that addresses the risk. Do not require speculative abstractions,
  configuration, fallback paths, or future-proofing.
- Respect existing automation and ownership boundaries. Verify an existing workflow instead of requiring the plan
  to restate its internal commands.
- Rare but high-impact risks still qualify when evidence shows a reachable path. Rarity alone neither creates nor
  dismisses a finding.
- Missing tests, documentation, rollout steps, rollback, or communication qualify only when their absence passes
  the finding gate. Do not add each category by default.
- For stored data or public payload changes, inspect compatibility even without DDL. For deletions or
  simplifications, verify only responsibilities and consumers implicated by the changed path.
- On a revised-plan review, verify the addressed findings and materially changed surface. Do not reopen resolved
  findings at a lower level of detail. A new finding requires new evidence or a materially changed plan; otherwise
  conclude the review.

## Output Shape

Start with exactly one verdict:

- **Ready**: no qualifying finding remains.
- **Not ready**: one or more qualifying findings remain.

For **Not ready**, use a single `Required changes` list. Each item must include:

- concrete failure;
- repository or primary-source evidence;
- smallest sufficient plan correction.

For **Ready**, stop after a concise rationale. Mention an assumption only if it is material and explicitly established
by the plan or repository. Do not append empty severity sections, optional improvements, generic residual risks, or
"final gates."

## Examples

**Review only**

Input: "클로드 계획 봐줘. 개선사항 한 번에 전부 말해."

Output: Start with **Ready** or **Not ready**. If not ready, return every finding that passes the gate in one
`Required changes` list.

**Resolved revision**

Input: "앞 리뷰 반영한 수정 계획이야. 다시 봐줘."

Output: Recheck the previous findings and materially changed surface. If they are resolved and no new evidence
establishes another plan-level failure, answer **Ready** and stop.
