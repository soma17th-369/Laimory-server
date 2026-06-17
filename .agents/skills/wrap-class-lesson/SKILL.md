---
name: wrap-claude-lesson
description: Harvest durable lessons from the current Claude conversation into a Markdown file — focused on technical findings, mistakes & corrections, and approaches that worked. Use ONLY when the user explicitly asks for it. Trigger phrases include "wrap up", "wrap claude lesson", "정리해줘", "교훈 수확", "회고", "lessons learned", "retro", "what did we learn", "summarize takeaways", "이번 세션 정리", "끝낼 때 정리". Do NOT trigger this skill on implicit cues like a long session ending, the user saying "thanks", or natural conversation closure — wait for an explicit ask. This skill captures *transferable* insights, not a blow-by-blow recap.
---

# Wrap Claude Lesson

The user has explicitly asked you to wrap up this session. Your job is to compress the conversation into a small Markdown artifact they (or a future Claude reading their notes) can actually use later. This is harvesting, not summarizing.

## When this skill triggers

**Only on explicit user request.** Do not invoke this skill because the conversation feels long, because the user said "thanks", or because a problem got solved. Wait for the user to actually ask — phrases like "정리해줘", "wrap up", "lessons learned", "교훈 수확", "회고", or running the skill by name.

If you're tempted to suggest wrapping up unprompted, don't run the skill — just ask: "Want me to harvest the lessons from this session?" and let the user decide.

Examples of what is *not* an explicit ask:
- "Thanks, that worked!" — relief, not a request.
- "OK we're done." — session-end signal, not a wrap-up request.
- "Got it." — acknowledgment, not a request.

Examples of what *is* an explicit ask:
- "정리해줘" / "wrap up" / "wrap claude lesson"
- "교훈 수확해줘" / "lessons learned please"
- "이번 세션 회고하자" / "let's do a retro on this"

## What "lesson" means here

A lesson is a piece of knowledge that would have saved time if you'd known it at the start. Three tests for whether something belongs in the harvest:

1. **Transferable** — would it apply to a future task, not just this exact one?
2. **Non-obvious** — is it something the user (or Claude) didn't already know going in?
3. **Compressed** — can it be stated in one or two sentences?

A blow-by-blow summary fails all three. "We tried X, then Y, then Z" is a log, not a lesson. The lesson is *why* Z worked when X and Y didn't.

## What to harvest

Scan the whole conversation and pull items into these three buckets. Skip any bucket that has nothing real in it — empty buckets are fine, padded buckets are worse than missing ones.

- **Technical findings & tips** — specific facts about the codebase, library, API, environment, or workflow that turned out to matter. ("The `foo()` call silently swallows errors in v3.2; check the return value.") Practical tips the user can apply directly next time.
- **Mistakes & corrections** — places where you or the user went down a wrong path, and what fixed it. These are gold because the wrong path is usually the *plausible* one. Capture the wrong turn, the root cause, and the fix.
- **What worked** — approaches, patterns, framings, or techniques that turned out to be effective. Include enough context that a reader knows when to reach for them again.

These three buckets are deliberately narrow. If something doesn't fit — random observations, off-topic chatter, the user's mood, future plans — leave it out. The discipline is part of the value.

## Output

Always produce a Markdown file. Copy the structure from `assets/lesson-template.md` and fill it in — but **omit any of the three sections that would be empty or padded**. A lessons doc with one well-populated section beats one with three, two of which are filler.

After saving the file, give a 3–5 bullet inline summary of the *headline* lessons — the ones the user would most want to remember in 30 seconds. This keeps the value visible even if they never open the file.

### Output location and naming

- **Use `/mnt/user-data/outputs/` if available** — that's where files become downloadable for the user. Otherwise save to a working directory that's obvious from the conversation (e.g., the repo the user has been editing in).
- **Filename pattern:** `lessons-<short-topic>-<YYYY-MM-DD>.md`. Examples: `lessons-auth-middleware-2026-05-01.md`, `lessons-payment-refactor-2026-05-01.md`. Keep the topic slug short (2–4 kebab-case words). **Use a romanized/English slug even when the file content is Korean** — it keeps filenames portable across systems and easy to autocomplete in a terminal.
- **If file creation isn't available in this environment**, output the harvest inline with a clear "copy this into a `.md` file" framing. Don't pretend to save.

### Multi-topic sessions

If the conversation spans clearly distinct topics, default to **one file with topic-grouped sections** rather than multiple files — the user usually wants one place to look. Use `## <Topic>` headers and nest the three buckets under each. Switch to multiple files only if the user explicitly asks.

## How to do this well

**Read before harvesting.** Don't start writing until you've reviewed the whole conversation. The most valuable lessons are usually buried in the middle, where the user corrected a wrong assumption or where a flailing approach finally clicked.

**Quote sparingly, paraphrase the insight.** "User said X" is rarely the lesson. The lesson is what X implies. Translate.

**Attribute correctly.** If a finding came from the user's domain knowledge, say so ("the user noted that..."). If it came from running code or reading docs, say that. Future-you needs to know which lessons are battle-tested vs. hypothetical.

**Don't invent.** If a section would only have weak material, leave it out. Empty is honest; padded looks thorough but trains the user to ignore future harvests.

**Calibrate length to the session.** A 20-minute debugging session probably yields 3–6 bullets total. A multi-hour design conversation might yield 15–20. If your output looks the same length regardless of session size, something is off.

**Match the user's working language.** If the conversation has been in Korean, write the harvest in Korean. If mixed, mirror the user's dominant language. The lessons doc is for the user's future reference — it should read the way they'd write it themselves.

## Examples

**Example 1 — debugging session**

Conversation arc (compressed): user reports a Next.js form silently failing in production. Claude suggests checking the network tab; nothing wrong there. User mentions it works locally. After several turns, Claude asks about middleware. Turns out a custom CSRF middleware was rejecting POSTs that lacked a header the new form didn't send. User says "ah, of course" and adds the header. Done.

Bad harvest (this is a *log*, not lessons):
> - We checked the network tab
> - We checked the console
> - We looked at the form code
> - We found it was the CSRF middleware

Good harvest:
```markdown
## Technical findings & tips
- The repo's custom CSRF middleware (`src/middleware/csrf.ts`) silently rejects POSTs missing the `X-CSRF-Token` header. Failures show as 200s with empty bodies, not 403s — easy to miss.
- Tip: when a form silently fails, grep for custom middleware in the request path before opening DevTools.

## Mistakes & corrections
- **Wrong turn:** spent 20 min on the form's client code and network tab.
  **Why it failed:** the symptom (silent fail) suggested a client issue, but the middleware was returning 200 + empty body, which masked itself as a client problem.
  **Fix:** start any "form not posting" investigation by inspecting middleware that touches the route, *before* assuming client-side.
```

Notice "Project context" and "Open threads" aren't included — this skill's three buckets don't cover those, and that's intentional.

**Example 2 — short design conversation**

Conversation arc: user asks how to structure a new analytics module. Claude suggests two patterns. User picks pattern B (composable handlers) and asks Claude to scaffold it. Done in ~15 minutes.

Good harvest (short — that's appropriate for a short session):
```markdown
## What worked
- Composable handler pattern (one function per metric, registered in a map) was preferred over a class hierarchy for this codebase. Reason: easier to add metrics in PRs without merge conflicts. Reach for this pattern when there's a registry of similar things and merge frequency matters.
```

That's the whole file. No "Technical findings" (nothing non-obvious came up), no "Mistakes & corrections" (no wrong turns). Skipping them is the right call. A one-section lessons doc is fine; a three-section padded doc is not.

## Gotchas

- **Don't re-explain the work.** This is for the user (or a future Claude reading their notes), both of whom already lived through the conversation. Skip the setup; jump to the insight.
- **Beware recency bias.** The last thing discussed often isn't the most important lesson. Scan the whole arc.
- **Don't moralize.** "We should have planned more carefully" is not a lesson. "Spec'ing the data shape before writing the parser would have saved two iterations" is.
- **Inline summary is for headlines, not the full harvest.** If you find yourself reproducing the file in chat, the file isn't doing its job — trust it and keep the inline portion punchy.
- **Don't drift outside the three buckets.** If you notice something that doesn't fit — user preferences, open threads, project background, future plans — mention it inline in your reply ("Also worth noting: ...") but don't add it to the file. The file's value is its scope. If the user wants those things captured too, they'll ask.
- **No file if the environment can't save one.** If file creation isn't available, output the harvest inline and tell the user to copy it. Don't pretend to save.

## When to push back

If the user asks for a wrap-up after a short or unproductive conversation, say so honestly: "There aren't really durable lessons here yet — mostly setup. Want me to note what's still open instead, or keep going?" A skill that produces a "lessons" doc out of nothing teaches the user the doc is noise.

## Reference files

- `assets/lesson-template.md` — copyable Markdown template with the three sections. Read it once, then fill in the sections you have material for.
