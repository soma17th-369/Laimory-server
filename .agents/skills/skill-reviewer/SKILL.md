---
name: skill-reviewer
description: Reviews Claude Skills (SKILL.md files and skill folders) and produces an actionable review report. Use this skill whenever the user asks to "review", "audit", "check", "critique", "evaluate", or "improve" a skill, plugin, or SKILL.md file. Also use when the user pastes skill frontmatter or content and asks for feedback, when a folder contains a SKILL.md and the user wants assessment, or when the user is comparing/curating skills for a marketplace. Trigger even if the user does not say the word "review" — phrases like "is this skill any good", "what would you change about my SKILL.md", "help me improve this skill", or "before I publish, can you take a look" all count.
---

# Skill Reviewer

You are reviewing a Claude Skill. Your job is to produce a clear, prioritized review report that tells the author what to keep, what to fix, and what to consider — grounded in the actual conventions of the Skills ecosystem rather than generic writing advice.

## What to do first

1. **Locate the skill.** Ask the user for the skill's path, a pasted SKILL.md, or a link. If they pasted only the body, ask for the YAML frontmatter — almost half of common issues live there.
2. **Read the whole skill folder, not just SKILL.md.** Use `view` (or read tools) on every file: `SKILL.md`, anything in `references/`, `scripts/`, `assets/`. A skill is a folder, not a markdown file. Reviews that ignore the folder miss the most interesting design choices.
3. **Identify the skill's category** before judging it. A Library Reference skill, a Verification skill, and a Runbook skill all need different things — applying one rubric to all three produces bad reviews. See `references/skill-categories.md` for the nine common types and what each one needs.
4. **Form a one-sentence thesis** about what the skill is trying to do and who would invoke it. If you can't, that's already a finding — the description probably fails.

## The review report

Always output the review using this exact structure. Authors skim; structure helps them act.

```
# Review: <skill-name>

## Summary
<2–4 sentences: what the skill does, what category it falls into, and the headline verdict>

## Critical issues
<Things that will cause the skill to break, fail to load, or fail to trigger. Empty section is fine — say "None.">

## Important improvements
<Things that meaningfully reduce skill quality but aren't blockers>

## Suggestions
<Nice-to-haves, polish, optional patterns worth considering>

## What's working well
<Genuinely call out the good parts — authors need signal on what to keep>

## Suggested rewrite of the description
<Only if the description has issues. Show before → after with a one-line rationale.>
```

Be specific. "Description is vague" is useless feedback; "the description says 'helps with projects' which contains no trigger phrases — Claude won't auto-load this on queries like 'set up Q4 sprint'" is actionable.

## What to check for

Walk through these areas in order. Each has its own reference file with the full rubric — load the relevant one when you reach that section, rather than carrying everything in context at once.

### 1. Structural correctness — `references/structural-checks.md`

The mechanical things that make a skill load at all: filename casing, folder naming, YAML delimiters, reserved-word violations, forbidden characters. Get these wrong and nothing else matters because the skill won't run.

### 2. Description quality — `references/description-rubric.md`

The description is the single highest-leverage field — it determines whether Claude ever loads the skill. Most weak skills are weak here. This file covers the "what + when + triggers" structure, the 1024–1536 character window, undertriggering vs. overtriggering signals, and how to write descriptions that match real user phrasing.

### 3. Body quality — `references/body-rubric.md`

The instructions themselves: are they imperative, do they explain the *why*, do they include a Gotchas section, do they avoid railroading the model with rigid MUSTs, do they reference bundled resources clearly. Also the 500-line target and progressive-disclosure check.

### 4. Folder & resources — `references/folder-rubric.md`

The skill as a folder. Are scripts in `scripts/`, references in `references/`, assets in `assets/`? Are large reference files broken up with a table of contents? Is there a `README.md` inside the skill folder (there shouldn't be)? Are scripts duplicating work the model could compose itself, or genuinely saving turns?

### 5. Category-fit — `references/skill-categories.md`

Different skill categories have different success criteria. A Verification skill needs assertions and recording hooks; a Runbook skill needs a symptom-to-tool map; a Code Scaffolding skill needs templates. This file helps you apply the right rubric.

### 6. Setup, memory, and config — `references/setup-and-state.md`

If the skill needs per-user setup (channel IDs, dashboard URLs, credentials), is there a `config.json` pattern? If it stores state across invocations, is it using a stable folder like `${CLAUDE_PLUGIN_DATA}` rather than the skill directory (which gets blown away on upgrade)? If it depends on other skills, are those references explicit?

## Principles for good reviews

**Be the kind of reviewer you'd want.** Authors put real work into skills. Lead with the thesis, call out what's working, and order findings by impact — not by what you happened to notice first.

**Don't recommend what isn't in the conventions.** This skill is grounded in three documents about how Claude Skills actually work. Don't import generic technical-writing advice that contradicts them. Examples of common bad advice to avoid:
- "Add a README.md inside the skill folder" — explicitly forbidden by the spec.
- "Use UPPER_SNAKE_CASE for the skill name" — must be kebab-case.
- "Write 'MUST' and 'NEVER' liberally for emphasis" — railroads the model and is a yellow flag, not a feature.
- "Make the description short and punchy" — descriptions need triggers and context; they get a 1024–1536 character budget for a reason.

**Calibrate to the author's stage.** A draft someone shares to ask "is this idea any good?" needs different feedback than a skill about to ship to a marketplace. If the skill is clearly an early sketch, focus on direction and category-fit; save the YAML-delimiter pedantry for when the skill is otherwise done.

**Prefer rewriting to describing.** When the description is weak, write a replacement. When a Gotchas section is missing, sketch one based on what the skill does. Showing beats telling.

**Distinguish "wrong" from "different choice."** Some things are spec violations (reserved name prefixes, missing SKILL.md). Others are stylistic and the author may have reasons. Mark each finding accordingly — don't present preferences as defects.

## Common failure modes you'll see often

These come up so frequently that it's worth pattern-matching for them on every review:

- **Description is a summary, not a trigger.** "This skill helps you manage tasks." OK, but Claude is choosing between this skill and twenty others; it needs phrases the user is likely to actually say.
- **No Gotchas section.** The author wrote what the skill does but not what trips Claude up. This is the highest-signal section, and its absence is almost always a missed opportunity.
- **MUST/NEVER everywhere.** A skill peppered with all-caps imperatives usually means the author didn't trust the model, hit one bug, and clamped down. Recommend reframing as "why this matters" instead.
- **Single big SKILL.md, no folder.** The author wrote a long instruction file but didn't split detailed reference into `references/` or move repeated logic into `scripts/`. Progressive disclosure was the whole point.
- **Description and body disagree.** The frontmatter says it does X; the body is mostly about Y. Usually means the skill grew and the description didn't.
- **Reserved-name violations.** Anything with `claude-` or `anthropic-` in the name is invalid. Catch this immediately.
- **Hidden state assumptions.** Skill says "your team's standard workflow" without explaining where to put the team-specific config, so it only works for the author.

## When you're not sure

If a finding could go either way, say so. "I'd consider splitting this into two skills, but if you're using both together every time it may be fine as-is" is more useful than a confident wrong call. Reviews lose trust fast when they assert weak claims as strong ones.

## Output format

Return the review as a Markdown document, using the structure above. Don't wrap it in extra commentary — the report is the deliverable. If the review is short (skill is in good shape), the report should be short. If the skill is a 12-issue rewrite, the report can be long, but each finding should still be one or two sentences plus a concrete suggestion.

## Reference files

- `references/structural-checks.md` — naming, frontmatter, security restrictions
- `references/description-rubric.md` — what makes descriptions trigger correctly
- `references/body-rubric.md` — imperative voice, gotchas, railroading, 500-line guideline
- `references/folder-rubric.md` — scripts/, references/, assets/, no README inside
- `references/skill-categories.md` — the nine common categories and their fit criteria
- `references/setup-and-state.md` — config.json, ${CLAUDE_PLUGIN_DATA}, composing skills
- `assets/review-template.md` — fillable template for the review report
