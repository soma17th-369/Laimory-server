# Description Rubric

The `description` field is the highest-leverage thing in the entire skill. Claude scans every available skill's description to decide which ones to load — so a skill with a brilliant body and a vague description is a skill that never runs.

Most weak skills are weak right here. Focus your review attention accordingly.

## What a good description contains

Three components, in roughly this order:

1. **What it does** — the capability, in concrete terms.
2. **When to use it** — trigger phrases and contexts that match how real users phrase requests.
3. **Edge / negative cases** (optional but valuable) — when *not* to use it, especially when an adjacent skill exists.

A useful structural template:

```
<What it does, one clause>. Use when <trigger phrase 1>, <trigger phrase 2>,
or <trigger phrase 3>. Also use when <implicit case>. Do not use for
<near-miss case> — use <other-skill> instead.
```

## The character budget

The combined `description` + `when_to_use` field is capped at **1,536 characters** in the skill listing Claude scans. If you have lots of skills, descriptions also get further trimmed to fit a context budget (roughly 1% of the context window, fallback ~8,000 chars total across all skill descriptions).

Implication: don't waste the budget on filler, but also don't be afraid to use it. Front-load the most important trigger info.

## Push toward triggering, not summarizing

Claude tends to **undertrigger** skills — skip them when they'd help — more often than it overtriggers. So descriptions should lean a little pushy. Good patterns:

- "Use this skill whenever the user mentions X, Y, or Z, even if they don't explicitly ask for it."
- "Trigger this even when the user just pastes a `<thing>` and asks for feedback."
- Listing several phrasings of the same intent (formal, casual, abbreviated) so the description matches more real queries.

## Good descriptions

> Reviews Claude Skills (SKILL.md files and skill folders) and produces an actionable review report. Use this skill whenever the user asks to "review", "audit", "check", "critique", "evaluate", or "improve" a skill, plugin, or SKILL.md file. Also use when the user pastes skill frontmatter or content and asks for feedback.

✓ Names the artifact (SKILL.md, skill folders).
✓ Lists multiple trigger verbs.
✓ Covers the case where the user doesn't say "review" but clearly wants one.

> Analyzes Figma design files and generates developer handoff documentation. Use when user uploads .fig files, asks for "design specs", "component documentation", or "design-to-code handoff".

✓ Specific artifact (.fig files).
✓ Quoted trigger phrases users actually say.

> Manages Linear project workflows including sprint planning, task creation, and status tracking. Use when user mentions "sprint", "Linear tasks", "project planning", or asks to "create tickets".

✓ Both formal and casual phrasings.

## Bad descriptions and why

> Helps with projects.

✗ No triggers, no specifics, no artifact. Will rarely auto-load.

> Creates sophisticated multi-page documentation systems.

✗ Reads like marketing. No phrasing a user would actually type.

> Implements the Project entity model with hierarchical relationships.

✗ Describes implementation, not user intent. Users don't say "implement entity models."

> Use this skill.

✗ No content at all.

## Diagnosing a description issue

When you flag a description in your review:

1. Ask "what would a user have to type for Claude to think of this skill?" If you can't answer, the description is broken.
2. Check that the description actually matches the body. If the body is mostly about migrations and the description says "code review," the description was written first and never updated.
3. If the skill has a competing-skill story (e.g. "use `data-viz` for simple plots, `advanced-stats` for modeling"), the description should say so. This prevents both undertriggering and triggering the wrong one.

## Rewriting

If you flag a description, write the replacement. Show before → after with one sentence of rationale:

> **Suggested rewrite of the description**
>
> Before: `Helps you write better commits.`
>
> After: `Generates Conventional Commits-formatted commit messages from staged changes. Use when the user runs git commit, stages files and asks "what should the commit message be", or pastes a diff and asks for a commit message. Also use when the user mentions "conventional commits", "commit format", or asks to clean up a draft message.`
>
> Rationale: the original had no trigger phrases, no artifact (commits/diffs), and no mention of the Conventional Commits format which is the actual differentiator.

## Special cases

- **`disable-model-invocation: true` skills**: the description doesn't appear in Claude's context at all when this is set. So description quality matters less for triggering, but the description is still shown in the slash-menu, so it should still tell the human what the skill does.
- **`user-invocable: false` skills**: only Claude can call them. Description quality matters maximally — there's no slash-command fallback.
- **Skills with `paths:` glob patterns**: auto-load is gated by file context. The description still matters for direct invocation and for cases where the user is in a relevant file.
