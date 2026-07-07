# Skill Categories

Different kinds of skills need different things. Identify the category before applying a rubric — a Verification skill and a Library Reference skill are judged on different criteria.

These nine categories aren't exhaustive, and some skills span several. But most skills cluster cleanly into one. If the skill you're reviewing doesn't seem to fit any, that's worth flagging — it might mean the skill is doing too many things.

## 1. Library & API Reference

**Purpose:** teaches the model how to use a library, CLI, SDK, or API correctly.

**What it should have:**
- A `references/` folder with code snippets, signatures, and per-function notes.
- A Gotchas section listing common errors and how to avoid them.
- Examples showing correct vs. incorrect usage.
- Clear scope: this is *our internal billing library*, not all billing libraries everywhere.

**Findings to look for:**
- Skill repeats things the model already knows about a public library — should focus on what's *non-obvious* or *specific to your codebase*.
- No gotchas — the whole value of this skill type is hard-won lessons.

## 2. Product Verification

**Purpose:** describes how to test or verify code is working, often paired with an external tool (Playwright, tmux, headless browsers).

**What it should have:**
- Concrete scripts that drive the verification (browser drivers, test runners, assertion helpers).
- Programmatic state checks at each step rather than "look at the screenshot and trust the model."
- Optionally: video/screenshot recording so the human can audit what was tested.
- Hooks for asserting state, not just running through the flow.

**Findings to look for:**
- Pure prose without bundled scripts — verification is a place where code beats text.
- "Take a screenshot and check it looks right" — too vague to be reliable.

## 3. Data Fetching & Analysis

**Purpose:** connects the model to your data and monitoring stacks.

**What it should have:**
- Helper scripts/libraries that handle the boring parts (auth, table joins, dashboard IDs).
- A "common workflows" section ("for funnel analysis, do X; for cohort comparison, do Y").
- Schema/table reference (which table has the canonical user_id, what's joined to what).
- Example queries the model can compose.

**Findings to look for:**
- Hard-coded credentials in the skill (should be config or env vars).
- All logic in prose — should give the model composable Python/SQL helpers.

## 4. Business Process & Team Automation

**Purpose:** automates a repetitive workflow into one command.

**What it should have:**
- Concrete sequence of steps, often spanning multiple integrations.
- Optionally a log file (e.g. `standups.log`) so the model has memory across runs.
- Setup/config for per-user values (Slack channel, ticket-system project ID).
- Schema enforcement for outputs (valid enum values, required fields).

**Findings to look for:**
- Per-user config baked into the body (works only for the author).
- No memory pattern when one would clearly help (e.g. a daily-standup skill that doesn't track what was said yesterday).

## 5. Code Scaffolding & Templates

**Purpose:** generates framework boilerplate for a specific function in your codebase.

**What it should have:**
- Templates in `assets/` that the skill copies and fills in.
- Composable scripts the skill can invoke.
- Notes on natural-language requirements that pure code generation can't capture.

**Findings to look for:**
- Long inline code in SKILL.md that should be a template in `assets/`.
- No explanation of *why* the template is shaped this way — model can't adapt to edge cases.

## 6. Code Quality & Review

**Purpose:** enforces code quality, helps review code.

**What it should have:**
- Deterministic scripts/tools where possible (linters, type-checkers) rather than language instructions.
- Optionally hooks (PreToolUse / PostToolUse) so the skill runs automatically.
- Clear scope: which styles, which languages, which directories.

**Findings to look for:**
- Style rules described only in prose when a linter could enforce them — recommend bundling the linter config and a script that runs it.

## 7. CI/CD & Deployment

**Purpose:** fetches, pushes, deploys code; manages PRs.

**What it should have:**
- `disable-model-invocation: true` is often appropriate — you don't want Claude deciding to deploy.
- Pre-approved tools (`allowed-tools`) so the skill can run git/CI commands without approval prompts.
- Rollback or recovery instructions for failures.
- May reference other skills (e.g. a deploy skill that calls a verification skill).

**Findings to look for:**
- Destructive deploy skill without `disable-model-invocation: true` — Claude might deploy on a tangentially related prompt.
- No rollback path — if step 4 of 7 fails, the skill leaves the system in a bad state.

## 8. Runbooks

**Purpose:** takes a symptom (alert, error message, Slack thread) and produces a structured investigation report.

**What it should have:**
- A symptom-to-tool mapping ("if the symptom mentions X, check Y first").
- Multi-tool investigation workflow.
- A structured output format (so on-call humans can compare reports across incidents).

**Findings to look for:**
- Vague "investigate the issue" instructions — runbooks should be specific.
- No output format — the value of a runbook is in producing comparable findings.

## 9. Infrastructure Operations

**Purpose:** routine maintenance and operational procedures, sometimes with destructive actions.

**What it should have:**
- `disable-model-invocation: true` for anything destructive.
- Clear soak periods or confirmation steps before cascading actions.
- Scoped permissions via `allowed-tools` — only the commands actually needed.
- Audit logging where appropriate.

**Findings to look for:**
- Destructive actions without explicit user confirmation in the workflow.
- Skill could run unattended but doesn't log what it did.

## Cross-cutting: skills that span categories

Some skills genuinely span categories (e.g. a deploy skill that's both CI/CD and Verification). That's fine. The questions to ask:

- Is the skill doing too many things, and would split into two be cleaner?
- Are the success criteria of all relevant categories met?

Some skills are plainly trying to be one category but are missing the things that category needs (e.g. a Verification skill with no scripts, just prose). That's a finding.

## Using categories in the review

Identify the category in the **Summary** section of the review:

> This is a Library & API Reference skill for the team's internal billing library.

Then judge it against the criteria for that category. If you flag findings that conflict with the skill's category, you'll give bad advice — e.g., demanding bundled scripts in a skill whose entire purpose is reference prose, or demanding extensive prose in a skill whose entire purpose is scripted verification.
