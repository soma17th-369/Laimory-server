# Review Checklist — what to look for

Ordered by importance. Spend your attention near the top: design and correctness matter far more than style. The standard you're holding the change to is "does this improve the code health of the system," not "is this perfect."

This list is based on Google's *Engineering Practices* code review guide, extended with the backend dimensions that don't reveal themselves by reading the diff alone.

## Table of contents

1. Design
2. Functionality / correctness
3. Complexity
4. Tests
5. Naming & comments
6. Style & consistency
7. Documentation
8. Backend dimensions: security, transactions, performance, error handling, concurrency

---

## 1. Design (most important)

- Do the pieces of the change fit together and fit the system? Does this belong here, or in a library / different layer?
- Is now the right time to add this, or is it speculative?
- Does it integrate cleanly with existing patterns, or does it cut against them?

This is the category most worth a [Blocker] or [Question]. A well-implemented change in the wrong place is still the wrong change.

## 2. Functionality / correctness

- Does the code do what the PR description says it intends? Is that intent actually good for the users (end users *and* the developers who'll call this later)?
- Edge cases: empty inputs, nulls, boundaries, large inputs, failure paths.
- Bugs visible just from reading: off-by-one, inverted conditions, wrong variable, missing await/return.
- For UI-affecting changes, behavior is hard to judge from code — ask for a screenshot or demo if it matters.

## 3. Complexity

- Is any line / function / class more complex than it needs to be? "Too complex" = can't be understood quickly, or invites bugs when someone modifies it later.
- **Over-engineering** — generic abstractions or hooks for a future that hasn't arrived. Flag it. Solve the problem that exists now.

## 4. Tests

- Are there tests appropriate to the change (unit / integration / e2e), in the same PR as the code?
- Will the tests actually fail if the code breaks? A test that passes no matter what is worse than none.
- Are assertions meaningful and specific? Is the test itself simple — tests are maintained code too.

## 5. Naming & comments

- Names: long enough to convey intent, short enough to read.
- Comments should explain **why**, not **what**. If a comment is needed to explain *what* the code does, the code usually wants to be simpler instead. Exceptions: regexes, non-obvious algorithms.
- Stale comments / TODOs that this change makes obsolete.

## 6. Style & consistency

- Follows the project's style guide / linter config.
- Where the style guide is silent, stay consistent with surrounding code.
- **Don't block on personal taste.** Anything that's preference, not a rule, is a [Nit]. Don't let nits gate the merge.
- Big reformatting mixed into a functional change is itself worth a comment — it should be a separate PR so the real diff is reviewable.

## 7. Documentation

- If the change alters how people build, run, configure, or call the code, did the README / docs / API reference get updated? If docs are missing, ask.

---

## 8. Backend dimensions

These are the "doesn't show in the diff, blows up in production" category. For a server-side PR (especially Spring Boot + JPA), check each deliberately.

### Security

- **Authorization, especially object ownership (IDOR/BOLA):** does an endpoint verify the resource belongs to the *authenticated* caller, rather than trusting an id from the request? This is the most common real hole. → usually [Blocker].
- **Input validation:** `@Valid` / whitelisting; injection risk in native queries or string-built JPQL (parameter binding used?).
- **Sensitive data exposure:** entities returned directly instead of DTOs; passwords / tokens / PII in logs or responses.
- **Secrets:** hardcoded keys/credentials; secrets accidentally committed.
- **Auth tokens:** JWT signature + expiry verification location; refresh-token handling.

### Transaction boundaries (JPA gotchas — frequently broken invisibly)

- **Self-invocation:** a `@Transactional` method called via `this.method()` from the same class bypasses the proxy and the annotation **does nothing**. Check newly added transactional methods aren't internal calls.
- **Scope too wide:** external HTTP calls / message publishing inside a transaction hold the DB connection → pool exhaustion. I/O belongs outside the transaction.
- **Missing `readOnly`:** read-only methods without `readOnly = true` pay for dirty-checking/flush and can't route to a read replica.
- **Side effects before commit:** sending mail/notifications inside the transaction means a rollback still leaves the side effect done. Look for `@TransactionalEventListener(AFTER_COMMIT)`.
- **OSIV:** if Open-Session-In-View is on, lazy loads drag the connection out to the controller; if off, check the service initialized everything it needs.

### Performance

- **N+1:** lazy association loaded in a loop. Solved with fetch join / `@EntityGraph` / batch size? First thing to suspect on any new query method.
- **Indexes:** new query's `WHERE` / `ORDER BY` columns indexed, or is it a full scan?
- **Memory paging:** fetching everything then slicing in app code; fetch-join + paging together (Hibernate's `firstResult/maxResults specified with collection fetch` warning).
- **Over-fetching:** select only needed columns (projections); avoid loading huge collections at once.

### Error handling

- **Swallowed exceptions:** `catch` that only logs and continues, or empty catch — failure that looks like success is the dangerous one.
- **Exception translation:** low-level exceptions (`SQLException`) bubbling up raw instead of being mapped to domain exceptions; stack traces / internals leaking into responses.
- **Consistent error responses:** centralized via `@ControllerAdvice` / `@ExceptionHandler`.
- **Timeouts:** external calls (HTTP/DB) without timeouts → one slow dependency exhausts the thread pool.
- **Partial-failure consistency:** multi-step work that fails midway shouldn't leave data half-written (transaction or compensation).

### Concurrency

- **Mutable state on singleton beans:** Spring beans are singletons by default; a mutable instance field on a `@Service`/`@Component` is not thread-safe. State should be method-local.
- **Read-modify-write races:** stock decrement, balance update, counters. Needs a lock — optimistic (`@Version`), pessimistic, or an atomic DB op (`UPDATE ... SET stock = stock - 1 WHERE stock >= 1`).
- **Idempotency:** payment/order endpoints where a duplicate request is harmful — unique constraint or idempotency key present?
- **Async:** exceptions silently lost in `@Async` / `CompletableFuture`; thread-pool sizing.
