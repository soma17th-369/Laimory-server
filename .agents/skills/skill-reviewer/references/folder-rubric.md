# Folder Rubric

A skill is a folder, not a file. The most interesting skills use the folder structure deliberately — bundling scripts, splitting reference material, providing templates. This file covers what to look for in the folder layout.

## Standard layout

```
my-skill/
├── SKILL.md           # required
├── scripts/           # optional — executable code (Python, Bash, JS, …)
├── references/        # optional — docs Claude reads when needed
└── assets/            # optional — files used in output (templates, fonts, icons)
```

These three subdirectories have semantic meaning:

| Folder | Purpose | Loaded into context? |
| --- | --- | --- |
| `scripts/` | Code the model executes via Bash/equivalent | No — runs, doesn't load |
| `references/` | Markdown reference material | Yes, when the model navigates to it |
| `assets/` | Templates, images, fonts used in the output | No — copied/used, not read |

If a skill puts everything in one `files/` folder, or puts code in `references/` and prose in `scripts/`, recommend reorganizing.

## What does NOT belong inside the skill folder

- **`README.md` inside the skill folder.** The spec explicitly disallows this. A repo-level `README.md` (one directory up, for human users browsing GitHub) is fine and recommended for distribution. But not inside.
- **Hidden config files unrelated to the skill** (`.DS_Store`, `node_modules/`, build artifacts).
- **Test fixtures**, unless the skill is specifically about testing and they're used as `assets/`.

If the author has a `README.md` inside the skill folder, that's a finding to fix before publishing.

## Scripts: when to bundle, when not to

Bundling a script makes sense when:

- The model would otherwise re-derive the same logic on every invocation.
- The work is deterministic and benefits from being run, not described (validation, parsing, formatting).
- The script encapsulates non-trivial domain logic (e.g. "fetch from our event source with the right credentials and table joins").

Bundling a script *doesn't* make sense when:

- The logic is trivial enough that the model writing it inline is fine and possibly clearer.
- The script is so coupled to the author's environment that it can't run anywhere else.

Reviewers should look at scripts and ask: "is this saving the model turns, or just adding setup overhead?" Both answers are legitimate findings.

## References: splitting and table-of-contents

`references/` is for content the model reads on demand. It enables the third level of progressive disclosure: detail that's available but not always loaded.

Patterns to look for:

- **Multiple framework/variant references**, organized by file: `references/aws.md`, `references/gcp.md`, `references/azure.md`. The body says "for AWS, see `references/aws.md`" and the model loads only the relevant one.
- **Long reference files (>300 lines) with a table of contents at the top.** This lets the model jump to a section instead of pulling the whole thing into context.
- **Schemas, API specs, glossaries, large gotcha catalogs** that don't need to be in the body.

Common issues:
- Reference files that are never linked from the body — the model won't find them, so they're dead code.
- The body inlines content that should clearly be in `references/` (whole API tables, long examples).

## Assets: templates and copy-able files

`assets/` is for files used in the *output* — templates the skill copies and fills in, fonts, icons, images. The classic example: a skill that produces a markdown report, with `assets/report-template.md` that the body says "copy this and fill in the sections."

If the skill produces structured output without using a template — and the structure is something a template would help with — that's worth suggesting.

## Empty or near-empty subfolders

Sometimes a skill folder has `scripts/` with nothing inside, or `references/` with one tiny file. Not a big deal, but worth flagging if it suggests the author started a structure they didn't finish.

## Cross-references between files

The body should cite reference files clearly:

> For complete API details, see `references/api.md`.
> Run `scripts/validate.py` against the input before generating output.

Three things to check:

1. **All cited files exist.** Broken references are a finding.
2. **All bundled files are cited from somewhere.** Orphan files won't get used.
3. **The pointer tells the model when to use the file**, not just that it exists. "See `references/auth.md` for auth details" is fine; "For the full set of OAuth scopes and edge-case error responses, see `references/auth.md`" is better.

## Folder-level findings to call out

These come up frequently:

- "Detailed reference material is inlined in SKILL.md (lines X–Y) — move to `references/<topic>.md` and link from the body."
- "There's a `README.md` inside the skill folder; the spec disallows this. Move human-facing documentation to a repo-level README one level up."
- "`scripts/foo.py` is bundled but never referenced from SKILL.md — either link it from the body or remove."
- "The Gotchas list is 40 bullets long. Consider splitting into `references/gotchas-<topic>.md` files grouped by failure mode."
