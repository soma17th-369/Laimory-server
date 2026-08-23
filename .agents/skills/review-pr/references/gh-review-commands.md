# gh / API Command Reference

Everything needed to read a PR and post a batched review. Copy and substitute placeholders.

Placeholders:
- `<owner>` / `<repo>` — repository owner and name
- `<pr>` — PR number (integer)
- `<head-sha>` — the PR head commit SHA (pin the review to this)

Assumes `gh` is authenticated (`gh auth status`). For the no-`gh` fallback, see the bottom.

## Identify the PR

From a URL `https://github.com/acme/widgets/pull/482`: `<owner>=acme`, `<repo>=widgets`, `<pr>=482`.

From a bare number, default to the current repo:

```bash
gh repo view --json owner,name -q '"\(.owner.login)/\(.name)"'
```

List the user's PRs if none was given:

```bash
gh pr status
gh pr list --limit 20
```

## Read the change

```bash
# Title, body (intent!), commit list, changed files, head SHA.
gh pr view <pr> --json title,body,author,commits,files,headRefName,headRefOid

# The full diff.
gh pr diff <pr>
```

Grab the head SHA for pinning the review:

```bash
HEAD_SHA=$(gh pr view <pr> --json headRefOid -q .headRefOid)
```

## Read the surrounding code (for context, not just the diff)

If the repo is checked out locally, read the whole files directly — that's the richest. Otherwise fetch the file at the PR head:

```bash
BRANCH=$(gh pr view <pr> --json headRefName -q .headRefName)
git fetch origin "$BRANCH"
git show "origin/$BRANCH:<path/to/file>"
```

Or via the contents API (no local clone needed):

```bash
gh api "repos/<owner>/<repo>/contents/<path>?ref=$HEAD_SHA" -q '.content' | base64 -d
```

## Post the review (single batched review)

Preferred path — write the approved review to a spec file and let the bundled script post it. It enforces `line`+`side` (not legacy `position`), requires a pinned `commit_id`, and explains GitHub's error if the post is rejected:

```bash
python scripts/post_review.py --owner <owner> --repo <repo> --pr <pr> --spec review.json
```

Write `review.json` as UTF-8 and pass it by path. On Windows/PowerShell, avoid building the JSON in a pipeline and posting with `--spec -` for Korean or other non-ASCII comments unless the pipeline is known to emit UTF-8 bytes. The script reads spec files and stdin as UTF-8, and aborts if the text already contains Unicode replacement characters (`�`), because that means the shell probably garbled the review before the API call.

Spec shape (`review.json`):

```json
{
  "commit_id": "<head-sha>",
  "body": "<overall summary — 2-4 sentences. design verdict, blockers, one positive>",
  "event": "COMMENT",
  "comments": [
    {
      "path": "src/main/java/com/x/OrderService.java",
      "line": 42,
      "side": "RIGHT",
      "body": "[Blocker] Trusts userId from the request body — use the authenticated principal instead."
    },
    {
      "path": "src/main/java/com/x/OrderRepository.java",
      "start_line": 18,
      "line": 23,
      "side": "RIGHT",
      "body": "[Suggestion] N+1 here — a fetch join or @EntityGraph makes this one query."
    }
  ]
}
```

Under the hood it's a single POST. To do it by hand (no script):

```bash
gh api repos/<owner>/<repo>/pulls/<pr>/reviews --input review.json
```

Field notes:

- **`event`**: `COMMENT` (neutral — the default), `REQUEST_CHANGES`, `APPROVE`, or `PENDING`. Use `PENDING` to create the review as a draft the user previews/submits in GitHub's own UI instead of publishing immediately. Default to `COMMENT`; never `APPROVE` without an explicit ask.
- **`line` / `start_line`**: file line numbers (1-based) in the file at `side`. `start_line` only for multi-line comments; omit it for single-line.
- **`side`**: `RIGHT` for the new version (added/context lines), `LEFT` for the old version (removed lines). Most comments are `RIGHT`.
- **`commit_id`**: pin to the PR head SHA (`gh pr view <pr> --json headRefOid -q .headRefOid`) so comments don't drift if the author pushes.

### A comment got rejected (422)

The line isn't part of the diff. Options: move the comment to the nearest changed line and reference the real location in the text, or fold it into the summary `body`. You can post the review without the offending comment and mention the rest in the summary.

## Verdict-only review (no inline comments)

```bash
gh api repos/<owner>/<repo>/pulls/<pr>/reviews \
  -f commit_id="$HEAD_SHA" \
  -f body="<summary>" \
  -f event="COMMENT"
```

## Common errors

- **422 on a comment** — line not in the diff (see above), or `position`/`line` confusion. Use `line` + `side`, not `position`.
- **404** — wrong owner/repo/number, or `gh` authed to an account that can't see the repo. `gh auth status`.
- **403 on the review POST** — no permission to review (common on fork PRs). Surface to the user; don't retry blindly.

## Without `gh`

The same endpoints work via `curl` with a `GITHUB_TOKEN`:

```bash
curl -sX POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/<owner>/<repo>/pulls/<pr>/reviews \
  -d @/tmp/review.json
```

If neither is available, do the whole review in chat and have the user paste the comments into GitHub's UI — the judgment is the valuable part; the API call is mechanical.
