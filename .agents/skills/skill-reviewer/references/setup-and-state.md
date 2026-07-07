# Setup, State, and Composition

Skills frequently need things they can't bake into the body: per-user config, persistent state across invocations, dependencies on other skills. How the author handles these tells you a lot about whether the skill will actually work for someone other than them.

## Per-user setup (config.json pattern)

If the skill needs values that vary by user — Slack channel ID, dashboard URL, ticket-tracker project key, internal API hostname — those values can't live in the body. Two acceptable patterns:

**1. `config.json` in the skill directory.** The skill body says:
> On first run, check whether `config.json` exists. If not, ask the user the questions in the "Setup" section below and write their answers to `config.json`. On subsequent runs, read `config.json` and skip the setup.

This pattern keeps per-user values out of the source code. Look for:
- A "Setup" section in the body.
- Clear instructions for what the model should ask on first run.
- Use of the `AskUserQuestion` tool (or equivalent) when structured multiple-choice prompts make sense.

**2. Environment variables / external config.** The skill body assumes certain env vars exist and tells the user where to set them. Acceptable, but worse than config.json for portable skills, because env vars are a global side effect.

**Findings to look for:**
- Slack channel IDs, dashboard UIDs, hostnames, or team-specific names hard-coded in the body.
- Body assumes the user is the author ("our team's Linear workspace") without explaining how someone else would point it elsewhere.

## Persistent state (memory)

Some skills get more useful when they remember previous runs:

- A standup skill that tracks what was said yesterday so today's post is delta-only.
- A code-review skill that remembers which files have been reviewed.
- An incident runbook that logs each invocation for postmortem reference.

Storage formats can be as simple as an append-only log file or as complex as a SQLite database — there's no one right answer. What matters is **where** the storage lives.

**Critical:** state should NOT live in the skill directory itself. The skill folder may be wiped on upgrade or reinstall. The conventional stable location is `${CLAUDE_PLUGIN_DATA}` (a stable per-plugin data folder).

**Findings to look for:**
- Skill writes a `.log` or `.db` file inside the skill directory — risk of data loss on upgrade.
- Skill clearly *would benefit* from memory (standup-style, incremental skills) but doesn't have any.
- Skill stores state but doesn't clean up old entries — file grows forever.

## Composition: depending on other skills

A skill can reference another skill by name and expect the model to invoke it. Examples:

- A deploy skill that says "first, run the `verify-staging` skill."
- A CSV-generation skill that says "use the `file-upload` skill to put the result somewhere durable."

There's no formal dependency mechanism — it's just convention. So:

**What's good:**
- The reference is explicit ("invoke the `<skill-name>` skill") rather than "do something to upload it."
- There's a fallback if the dependency isn't installed ("if `<skill-name>` isn't available, do X manually").

**Findings to look for:**
- Skill assumes another skill is present without saying so. New users hit silent failures.
- Skill duplicates logic from another skill instead of delegating. Recommend extracting and depending.

## Hooks

Skills can include hooks scoped to that skill's lifetime — they activate when the skill is loaded and deactivate when the session ends. Useful for:

- A `/careful` skill that adds a PreToolUse hook blocking destructive commands (`rm -rf`, `DROP TABLE`, force-push) — only when the user explicitly invokes it.
- A `/freeze` skill that blocks Edit/Write outside a specific directory while debugging.

These are powerful but easy to misuse. Watch for:

- Hooks that would be annoying if always-on but are useful sometimes — good fit for skill-scoped hooks.
- Hooks that should be repo-wide instead — those belong in repo-level configuration, not a skill.

## Tool restrictions: `allowed-tools`

The `allowed-tools` field pre-approves specific tools while the skill is active. Use cases:

- Deploy skill: pre-approve `Bash(git push:*)`, `Bash(kubectl apply:*)` so the user isn't prompted on every invocation.
- Read-only investigation skill: deliberately *limit* the skill's access by listing only the safe tools.

**Findings to look for:**
- Destructive skill (deploy, migration, delete) with no `allowed-tools` — every step prompts the user, which trains them to click through approvals.
- Read-only skill (analysis, review) that doesn't restrict tools — the skill's body says "don't write to anything," but the model has full Edit/Write access.
- Pre-approved wildcards like `Bash(*)` — too broad; should be specific subcommands.

## When the user invokes vs. when Claude invokes

Two frontmatter fields control this:

- `disable-model-invocation: true` — only the user can invoke (`/skill-name`). Claude won't auto-load. Description doesn't appear in Claude's context.
- `user-invocable: false` — only Claude can invoke. Hidden from the slash menu.

Default is "both can invoke."

**Findings to look for:**
- A destructive skill (deploy, delete, send-email) that *doesn't* set `disable-model-invocation: true`. Claude might invoke it during a tangentially related task.
- Background-knowledge skills (e.g. "context about our legacy CRM") that *don't* set `user-invocable: false`. They show up in the slash menu where they aren't useful.

## Path-gated skills

The `paths` frontmatter field auto-activates a skill only when the user is working with files matching the glob:

```yaml
paths:
  - "src/billing/**"
  - "**/*.proto"
```

Useful for skills that are very narrowly scoped — e.g., a skill about `.proto` files that shouldn't trigger when the user is editing CSS.

**Findings to look for:**
- Skill is clearly scoped to a subsystem ("our billing library") but doesn't use `paths` — would auto-load on irrelevant queries.
- `paths` is set but the description doesn't acknowledge it — might confuse direct invocation cases.

## Putting it together

When reviewing this area, ask:

1. Could someone other than the author actually use this skill? If not, why? (Hard-coded values, missing setup, hidden assumptions.)
2. Will the skill survive an upgrade or reinstall? (State outside the skill directory.)
3. Are destructive actions appropriately gated? (`disable-model-invocation`, `allowed-tools`, confirmation prompts.)
4. Are dependencies on other skills explicit?

Findings here are often subtle but high-impact: a skill that "works for me" but breaks for everyone else is a common failure mode that this rubric is designed to catch.
