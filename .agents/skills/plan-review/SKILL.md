---
name: plan-review
description: Review implementation plans before coding by checking them against the actual repository, project conventions, domain language, contracts, tests, data/migration risks, and rollout steps. Use when the user asks to review, sanity-check, approve, improve, or critique a plan from Claude/Codex/another agent, pasted design notes, issue plans, implementation checklists, migration plans, or "can we just do this?" proposals. Also use for Korean requests like "계획 검토", "플랜 리뷰", "개선사항 한 번에 말해줘", "클로드 계획 봐줘", or "그냥 해도 될까?". Do not use for reviewing code already on a PR, resolving PR comments, or merging. Always return all actionable improvements in one consolidated response instead of drip-feeding partial findings.
---

# Plan Review

Review an implementation plan before code changes begin. Treat the plan as a proposal, not truth.

Core behavior: **give the complete review in one pass**. Do not hold back smaller issues for later rounds unless new information appears after the response.

## Modes

- If the user asks for review only, stop after the consolidated review. Do not edit code, plans, issues, or docs.
- If the user explicitly asks to improve, revise, or apply fixes to the plan in the same request, first form the full review, then update the plan artifact or provide a revised plan. Keep code implementation out of scope unless the user clearly asks to start coding.
- If the user asks "is this okay to do?", answer yes/no first, then list every gate or improvement needed before implementation.

## Workflow

1. Read the whole plan first.
2. Identify the intended behavior change, affected contracts, data shape, rollout scope, and verification path.
3. Inspect local context before judging when the plan references files, APIs, domain language, tests, schemas, build tools, branches, or repo conventions.
4. Compare the plan against:
   - Existing code paths and ownership boundaries.
   - Project instructions such as `CLAUDE.md`, `AGENTS.md`, and domain language docs when relevant.
   - Existing tests and integration/E2E coverage.
   - Persistence and compatibility risks, including JSON payload contracts, migrations, legacy data, backfills, cleanup jobs, and rollout order.
   - Public or cross-team API contracts, DTO names, response shape, client/AI assumptions, and documentation.
   - Operational concerns such as config changes, materialized data, secrets, CDN/storage semantics, retries, cleanup, observability, and rollback.
5. If the plan depends on current external facts or third-party behavior, verify with primary/current sources. Prefer official docs for technical claims.
6. Make a completeness pass before answering. Check that data compatibility, API contracts, tests, docs, rollout, rollback, ownership, and cross-team communication have all been considered.
7. Do not send partial findings while still investigating. Status updates may mention what context you are reading, but the review itself should arrive as one complete set of findings.

## Review Rules

- Favor repo evidence over general best practice.
- Mark uncertainty clearly. If a risk depends on an assumption, name the assumption and how to verify it.
- Do not reject a plan for hypothetical edge cases that are outside the stated scope unless they create real operational or contract risk.
- Distinguish "must fix before implementation" from "nice cleanup".
- Include missing tests, docs, rollout checks, and communication items. These are first-class plan quality issues.
- For data-shape changes without DDL, still check compatibility. JSON contracts and stored payloads can require backfill or release gates.
- For pass-through simplifications, verify every old transformation responsibility either moved somewhere else or is intentionally removed.
- For renames/deletions, call out imports, bean injection, mocks, comments, tests, and documentation that must move with them.
- If user asks "just okay to do?", answer directly, then list remaining improvements or gates.

## Output Shape

Give one consolidated response with all actionable improvements. Use concise severity grouping:

- **Must Fix**: issues that can break behavior, data compatibility, security, deployment, or team contracts.
- **Should Add**: tests, docs, rollout checks, or implementation details that reduce meaningful risk.
- **Optional**: polish or low-risk cleanup.

If the plan is ready, say so plainly and still mention any final gates. If there are no issues, say that clearly and identify residual risk or test gaps.

Avoid a drip-feed style such as "first issue..." followed by later additions. The user should not need to ask "anything else?" to get the complete review.

## Useful Checks

- Search referenced symbols with `rg` and read the actual files.
- Search constructor calls, mapper usage, DTO usages, tests, docs, and comments related to renamed or deleted classes.
- For persistence changes, search repository/integration tests and cleanup/scheduler code that deserializes stored payloads.
- For response contract changes, inspect controller tests, E2E tests, docs, and any client/AI-facing notes.
- For issue/PR plans, verify branch, commit, issue linkage, and merge/close wording only if the user asked for that level of process review.

## Gotchas

- Treat an external agent's plan as a claim to verify, not as source of truth. Read the referenced code or docs before accepting file names, class names, contracts, or test coverage claims.
- Do not let "no DDL" end the persistence review. Stored JSON, enum values, payload fields, cached materialized data, and old rows can still need compatibility handling.
- Do not drip-feed. If you notice a small issue while explaining a bigger one, include it in the same response under the right severity.
- Avoid generic best-practice objections without repo evidence. A plan can be acceptable when it matches local conventions even if another architecture would also work.
- When a plan deletes or simplifies a layer, identify the responsibility that layer used to own and verify where that responsibility now lives.
- If the user asks for both review and improvement, keep the review complete first, then make the requested plan edits. Do not silently switch into implementation.

## Examples

**Review only**

Input: "클로드 계획 봐줘. 개선사항 한 번에 전부 말해."

Output: Start with whether the plan is ready, then provide **Must Fix**, **Should Add**, and **Optional** findings in one response.

**Review and improve**

Input: "이 계획 리뷰하고 바로 개선해줘."

Output: Produce the full review, then revise the plan text or checklist so the accepted improvements are reflected. Mention any rejected or deferred suggestions separately.
