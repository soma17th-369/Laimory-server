# Structural Checks

These are the mechanical things that determine whether a skill loads at all. Get these wrong and the skill never runs — none of the body quality matters. Check each item explicitly; don't assume.

## SKILL.md filename

- Must be **exactly** `SKILL.md` — case-sensitive.
- `skill.md`, `Skill.md`, `SKILL.MD`, `Skill.MD` are all rejected.
- Verify with the actual filename, not a paraphrase.

## Skill folder name

The folder name becomes the skill's slash-command in Claude Code (e.g. `/my-skill`) and is the default value of the `name` frontmatter field if `name` is omitted. Rules:

- **kebab-case only.** Lowercase letters, numbers, hyphens.
- **Max 64 characters.**
- No spaces, underscores, capitals, dots.
- Must not start with `claude-` or `anthropic-` — those prefixes are reserved.

| Example | Verdict |
| --- | --- |
| `pr-summary` | OK |
| `my_skill` | invalid (underscore) |
| `MySkill` | invalid (capitals) |
| `claude-helper` | invalid (reserved prefix) |
| `data analysis` | invalid (space) |

## YAML frontmatter

Required at the very top of `SKILL.md`. Common ways this breaks:

- **Missing `---` delimiters.** Both opening and closing `---` are required.
- **Unclosed quotes** in the description.
- **Tabs** instead of spaces for YAML indentation.
- **Angle brackets `<` or `>`** anywhere in the frontmatter — explicitly forbidden as a security restriction (frontmatter appears in the system prompt and could otherwise inject XML-like instructions).

The required fields are minimal:

```yaml
---
name: my-skill           # optional in practice — defaults to folder name
description: <…>         # required for Claude to ever auto-load it
---
```

Other recognized fields (all optional):

| Field | Purpose |
| --- | --- |
| `when_to_use` | Extra trigger context appended to description; counts against the 1,536-char cap |
| `argument-hint` | Autocomplete hint for arguments |
| `arguments` | Named positional args usable as `$name` in body |
| `disable-model-invocation` | `true` = only the user can invoke (good for destructive skills) |
| `user-invocable` | `false` = only Claude can invoke (good for background-knowledge skills) |
| `allowed-tools` | Pre-approved tools while the skill is active |
| `model` / `effort` | Override session model or effort for this skill |
| `context: fork` + `agent` | Run the skill as a forked subagent |
| `hooks` | Lifecycle hooks scoped to this skill only |
| `paths` | Glob patterns that gate auto-activation by file context |
| `metadata` | Free-form key/value (author, version, tags) |
| `license` | For open-source distribution |
| `compatibility` | Notes about required environment / dependencies |

## Forbidden / reserved

- `name` cannot start with `claude-` or `anthropic-`.
- No `<` or `>` characters anywhere in the frontmatter.
- No `README.md` inside the skill folder. Repo-level READMEs (one level up, for human distribution) are fine and recommended; READMEs *inside* the skill folder are explicitly disallowed because they confuse the loader and aren't part of the spec.

## Frontmatter findings template

When a structural issue is real, write the finding like this in the review:

> **Critical: folder is named `My_Skill`.** Skill folder names must be kebab-case (lowercase letters, numbers, hyphens). Rename to `my-skill`. Until this is fixed the skill cannot be loaded.

Don't soften critical structural findings — these prevent the skill from working, and the author needs to know they're blockers.
