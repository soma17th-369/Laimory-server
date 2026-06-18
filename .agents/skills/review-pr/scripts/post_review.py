#!/usr/bin/env python3
"""
post_review.py — Post a code review to a GitHub PR as a single batched review.

Why this exists: the GitHub reviews API is easy to get wrong by hand. The three
recurring mistakes are (1) using `position` instead of `line`+`side`, (2) forgetting
to pin `commit_id` so comments drift when the author pushes, and (3) hand-escaping a
multi-comment JSON payload in a shell heredoc. This script takes a clean review spec
and handles all three, so each review run spends its effort on the judgment, not the
API plumbing.

It does NOT decide to post. The skill workflow shows the drafted review to the user
and gets approval first; this script is only run after that yes.

Usage:
    python post_review.py --owner OWNER --repo REPO --pr N --spec review.json
    # or pipe UTF-8 bytes on stdin:
    cat review.json | python post_review.py --owner OWNER --repo REPO --pr N --spec -

review.json shape:
{
  "commit_id": "<head sha>",          # required — pin to the PR head (gh pr view N --json headRefOid)
  "event": "COMMENT",                 # COMMENT | REQUEST_CHANGES | APPROVE | PENDING (default COMMENT)
  "body": "overall summary ...",
  "comments": [
    {"path": "src/A.java", "line": 42, "side": "RIGHT", "body": "[Blocker] ..."},
    {"path": "src/B.java", "start_line": 18, "line": 23, "side": "RIGHT", "body": "[Suggestion] ..."}
  ]
}

Auth: uses `gh` if available (preferred). Falls back to GITHUB_TOKEN against the REST API.
"""
import argparse
import json
import shutil
import subprocess
import sys
import urllib.request

VALID_EVENTS = {"COMMENT", "REQUEST_CHANGES", "APPROVE", "PENDING"}


def read_spec_text(path):
    try:
        if path == "-":
            return sys.stdin.buffer.read().decode("utf-8-sig")
        with open(path, encoding="utf-8-sig") as f:
            return f.read()
    except UnicodeDecodeError as e:
        sys.exit(
            "ERROR: review spec is not valid UTF-8. Write the spec as UTF-8 and pass it "
            "with --spec review.json. On Windows/PowerShell, avoid piping non-ASCII JSON "
            "through stdin unless you have explicitly configured UTF-8 output encoding.\n"
            f"Decode error: {e}"
        )


def iter_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from iter_strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from iter_strings(item)


def reject_garbled_text(spec):
    for text in iter_strings(spec):
        if "\ufffd" in text:
            sys.exit(
                "ERROR: review spec appears to contain Unicode replacement characters (�), "
                "which usually means the text was already garbled by the shell before this "
                "script received it. Regenerate the spec as UTF-8 and post again."
            )


def load_spec(path):
    raw = read_spec_text(path)
    spec = json.loads(raw)
    reject_garbled_text(spec)

    if not spec.get("commit_id"):
        sys.exit(
            "ERROR: commit_id is required. Pin the review to the PR head so comments "
            "don't drift if the author pushes:\n"
            "    gh pr view <pr> --json headRefOid -q .headRefOid"
        )
    event = spec.get("event", "COMMENT")
    if event not in VALID_EVENTS:
        sys.exit(f"ERROR: event must be one of {sorted(VALID_EVENTS)}, got {event!r}")
    spec["event"] = event

    # Light sanity check on comments — catches the common field mistakes early.
    for i, c in enumerate(spec.get("comments", [])):
        for required in ("path", "body"):
            if required not in c:
                sys.exit(f"ERROR: comment[{i}] missing required field {required!r}")
        if "position" in c:
            sys.exit(
                f"ERROR: comment[{i}] uses 'position' (legacy). Use 'line' (the file "
                "line number) plus 'side' ('RIGHT' for added/context, 'LEFT' for removed)."
            )
        if "line" not in c:
            sys.exit(f"ERROR: comment[{i}] needs a 'line'. Inline comments must anchor to a line in the diff.")
        c.setdefault("side", "RIGHT")
    return spec


def post_via_gh(owner, repo, pr, spec):
    payload = json.dumps(spec, ensure_ascii=True).encode("utf-8")
    proc = subprocess.run(
        ["gh", "api", "--method", "POST",
         f"repos/{owner}/{repo}/pulls/{pr}/reviews", "--input", "-"],
        input=payload, capture_output=True,
    )
    return (
        proc.returncode,
        proc.stdout.decode("utf-8", errors="replace"),
        proc.stderr.decode("utf-8", errors="replace"),
    )


def post_via_token(owner, repo, pr, spec, token):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{owner}/{repo}/pulls/{pr}/reviews",
        data=json.dumps(spec, ensure_ascii=True).encode("utf-8"), method="POST",
        headers={"Authorization": f"Bearer {token}",
                 "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(req) as r:
            return 0, r.read().decode(), ""
    except urllib.error.HTTPError as e:
        return e.code, "", e.read().decode()


def explain_error(code, err):
    hint = ""
    if "422" in str(code) or "422" in err:
        hint = ("\nHINT: 422 usually means a comment is anchored to a line that isn't part "
                "of the diff. Move that comment to the nearest changed line (and reference "
                "the real location in the text), or fold it into the summary `body`.")
    elif "403" in str(code) or "403" in err:
        hint = ("\nHINT: 403 means no permission to review this PR (common on fork PRs). "
                "Surface this to the user rather than retrying.")
    elif "404" in str(code) or "404" in err:
        hint = "\nHINT: 404 — check owner/repo/pr, and `gh auth status`."
    return hint


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--pr", required=True, type=int)
    ap.add_argument("--spec", required=True, help="path to review JSON, or '-' for stdin")
    args = ap.parse_args()

    spec = load_spec(args.spec)

    if shutil.which("gh"):
        code, out, err = post_via_gh(args.owner, args.repo, args.pr, spec)
    else:
        import os
        token = os.environ.get("GITHUB_TOKEN")
        if not token:
            sys.exit("ERROR: `gh` not found and GITHUB_TOKEN not set. Cannot post.")
        code, out, err = post_via_token(args.owner, args.repo, args.pr, spec, token)

    if code != 0:
        print(f"Failed to post review (exit {code}).\n{err}{explain_error(code, err)}", file=sys.stderr)
        sys.exit(1)

    data = json.loads(out) if out.strip() else {}
    n = len(spec.get("comments", []))
    print(f"Posted review ({spec['event']}) with {n} inline comment(s).")
    if data.get("html_url"):
        print(f"  {data['html_url']}")


if __name__ == "__main__":
    main()
