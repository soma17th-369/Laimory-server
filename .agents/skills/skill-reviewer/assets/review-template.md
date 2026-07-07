# Review: <skill-name>

## Summary

<2–4 sentences. State what the skill does, identify its category (Library Reference / Verification / Data Fetching / Business Process / Code Scaffolding / Code Quality / CI/CD / Runbook / Infra Ops, or hybrid), and give the headline verdict — is this skill ready to ship, ship-after-fixes, or in-progress?>

## Critical issues

<Things that block the skill from loading, triggering, or running. Examples: invalid name (capitals, underscores), missing SKILL.md, forbidden characters in frontmatter, `claude-`/`anthropic-` reserved prefix, README.md inside the folder, broken references to bundled files. If none, write "None.">

## Important improvements

<Things that meaningfully reduce skill quality but don't block it. Examples: vague description, no Gotchas section, hard-coded per-user values, walls of MUST/NEVER, missing examples, body inlining content that should be in references/. Each finding: one or two sentences + a concrete fix.>

## Suggestions

<Nice-to-haves. Examples: split into sub-references for clarity, add a config.json setup, consider a path-gate, add a memory log for incremental runs. These are "consider this" not "fix this".>

## What's working well

<Genuinely call out the good parts — authors need signal on what to keep. If the description is sharp, say so. If the Gotchas section is excellent, say so. If the script bundling is well-thought-out, say so. Never skip this section; it calibrates the rest of the review.>

## Suggested rewrite of the description

<Only include if the description has issues.>

**Before:**
> <current description>

**After:**
> <rewritten description>

**Rationale:** <one sentence on what changed and why>

---

## Optional: detailed findings by area

<For longer reviews where the sections above aren't enough, you can add per-area subsections. Skip if the review is short.>

### Structural
- ...

### Description
- ...

### Body
- ...

### Folder & resources
- ...

### Setup & state
- ...

### Category fit
- ...
