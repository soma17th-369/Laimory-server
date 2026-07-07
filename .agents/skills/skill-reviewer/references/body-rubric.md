# Body Rubric

The frontmatter decides whether the skill loads. The body decides whether it works.

## Length and progressive disclosure

Target: **SKILL.md under 500 lines.** This isn't a hard cap — some skills have good reasons to be longer — but it's the line where you should ask "could detail move into `references/`?"

The progressive-disclosure pattern is:

| Level | What's there | When loaded |
| --- | --- | --- |
| 1. Frontmatter | name + description | Always in context |
| 2. SKILL.md body | Core instructions | When the skill is invoked |
| 3. `references/`, `scripts/`, `assets/` | Detail, code, templates | Only when the model navigates to them |

A skill that crams every API signature, every example, and every gotcha into the body wastes context and obscures the core workflow. Move:

- Long API reference → `references/api.md`
- Example outputs → `references/examples.md` or `assets/example.md`
- Detailed schemas → `references/schemas.md`
- Helper functions → `scripts/helpers.py`

Then point to them from the body: "For full API signatures, see `references/api.md`."

If the body references files that don't exist, that's a finding.

## Imperative voice

Skills are instructions to a model. Use the imperative form:

- ✓ "Run the validator before committing."
- ✗ "You should probably run the validator."
- ✓ "Read `references/auth.md` before writing any new endpoint."
- ✗ "It might be helpful to look at the auth reference."

## Explain the *why*

This is the single biggest writing-style issue in real skills. Today's models are smart and have good theory of mind — given the *reason* something matters, they generalize well. Given a bare command, they often don't.

Compare:

> **Bad:** ALWAYS use the `format_currency` helper.
>
> **Better:** Use the `format_currency` helper rather than rolling your own. It handles the locale-specific decimal separators that bit us in the past, and it's used everywhere else in the codebase so consistency matters.

The second version both tells the model what to do *and* equips it to handle adjacent cases ("oh, this related thing also has locale issues, I should be careful").

If the skill is a wall of bare imperatives with no reasoning, that's a finding: **"explain the why"**.

## Avoid railroading

Closely related, but worth its own bullet. Watch for:

- All-caps **MUST**, **NEVER**, **ALWAYS** sprinkled liberally.
- Overly rigid step-by-step procedures that don't allow for "if X, do Y instead".
- Instructions that solve one specific failure case but constrain the model in many other valid cases.

Skills are reused across many situations. If the author was burned by one bug and clamped down with a MUST, the MUST is now active in every other invocation too. Recommend reframing as "why this matters" rather than "you must."

A reasonable amount of MUST/NEVER is fine — for genuinely critical things (security, destructive actions, spec violations). The yellow flag is when they're everywhere.

## Gotchas section

This is the **highest-signal section** in any skill — the part that captures hard-won knowledge about what trips the model up. Real skills get better over time by accumulating gotchas.

If the skill doesn't have a Gotchas section (or equivalent — "Common Issues", "Pitfalls", "Watch out for"), that's almost always a finding. Either:

- The skill is too new to have learned its failure modes (recommend adding the section as the author iterates).
- The skill has gotchas the author knows but didn't write down (recommend adding what they know).

Sketch a starter Gotchas section in the review when you can. Even three bullets is better than zero.

## Structure within the body

A common, useful structure (adapt to the skill's category):

```
# Skill name (optional — folder name is enough)

<one-line restatement of what the skill does>

## When to use this
<elaborates the description for the model that just loaded the skill>

## Workflow / Instructions
<the actual steps>

## Gotchas
<failure modes and how to avoid them>

## Examples
<input → output, if applicable>

## Reference files
<pointers to references/ and scripts/>
```

Don't enforce this template rigidly — some skills don't need all of it. But if the skill is missing both an "instructions" and an "examples" section, that's worth flagging.

## Examples pattern

Concrete examples are powerful, especially when the skill produces structured output. The format from the skill-creator that works well:

```
**Example 1:**
Input: <user prompt or input>
Output: <what the skill should produce>
```

If a skill is about producing outputs in a particular format, and there are no examples, that's a finding.

## Hard-coded paths and per-user context

Watch for cases where the body assumes context that varies by user — Slack channel IDs, dashboard UIDs, internal hostnames, team conventions. If those are baked into the body, the skill only works for the author. Recommend extracting them to a `config.json` (see `setup-and-state.md`) or to clearly-marked placeholders the model should ask the user about on first run.

## Repeated work the skill should bundle

If the body describes a multi-step procedure that the model is going to re-derive every time (parsing the same JSON, building the same query, formatting output the same way), suggest moving that into a `scripts/` file. The skill spends its turns on composition, not reconstruction.

A signal: the body contains code in fenced blocks that the model is expected to write/run inline. If that code is non-trivial and used every invocation, it should be in `scripts/`.

## What "good" looks like

A solid SKILL.md body:

- Opens with one or two sentences restating the skill.
- Has clear sections (workflow, gotchas, examples, references).
- Uses imperative voice with the *why* explained.
- Cites bundled scripts and references rather than inlining everything.
- Has a Gotchas section that wasn't there in the first draft and got added because the author hit real failures.
- Doesn't have a wall of all-caps MUSTs.
- Trusts the model: gives it enough information to handle situations the author didn't anticipate.
