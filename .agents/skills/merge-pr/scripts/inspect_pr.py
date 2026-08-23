#!/usr/bin/env python3
"""Inspect a PR and evaluate Laimory's work-branch-to-dev merge gates.

This script is deliberately read-only. It never writes files, pushes commits, resolves
reviews, or merges a pull request. JSON is written to stdout for the merge-pr skill.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


EXPECTED_BASE = "dev"
REQUIRED_CHECK = "build"
SUCCESS_CONCLUSIONS = {"SUCCESS", "NEUTRAL", "SKIPPED"}
FAILURE_CONCLUSIONS = {
    "ACTION_REQUIRED",
    "CANCELLED",
    "FAILURE",
    "STALE",
    "STARTUP_FAILURE",
    "TIMED_OUT",
}

EXIT_READY = 0
EXIT_ERROR = 1
EXIT_WAITING = 2
EXIT_BLOCKED = 3


class CommandError(RuntimeError):
    """Raised when a read-only git or gh command cannot complete."""


def run(command: list[str], cwd: Path) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except FileNotFoundError as exc:
        raise CommandError(f"required command not found: {command[0]}") from exc

    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "no error output"
        raise CommandError(f"{' '.join(command)} failed: {detail}")
    return result.stdout


def git(args: list[str], cwd: Path) -> str:
    return run(["git", *args], cwd).strip()


def gh(args: list[str], cwd: Path) -> str:
    return run(["gh", *args], cwd)


def repository_root(cwd: Path) -> Path:
    return Path(git(["rev-parse", "--show-toplevel"], cwd)).resolve()


def local_state(root: Path) -> dict[str, Any]:
    branch = git(["branch", "--show-current"], root)
    porcelain = run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"], root
    )
    dirty_lines = [
        line for line in porcelain.splitlines() if line
    ]
    return {
        "branch": branch,
        "head_sha": git(["rev-parse", "HEAD"], root),
        "dirty_paths": [line[3:] if len(line) > 3 else line for line in dirty_lines],
    }


def pr_target_args(target: str | None) -> list[str]:
    return [target] if target else []


def read_pr(root: Path, target: str | None) -> dict[str, Any]:
    fields = ",".join(
        [
            "number",
            "url",
            "title",
            "body",
            "state",
            "isDraft",
            "baseRefName",
            "headRefName",
            "headRefOid",
            "mergeable",
            "mergeStateStatus",
            "reviewDecision",
            "statusCheckRollup",
            "commits",
            "files",
        ]
    )
    output = gh(["pr", "view", *pr_target_args(target), "--json", fields], root)
    return json.loads(output)


def repo_from_pr_url(url: str) -> tuple[str, str]:
    parts = [part for part in urlparse(url).path.split("/") if part]
    if len(parts) < 4 or parts[2] != "pull":
        raise CommandError(f"cannot parse repository from PR URL: {url}")
    return parts[0], parts[1]


def read_review_threads(root: Path, pr: dict[str, Any]) -> list[dict[str, Any]]:
    owner, name = repo_from_pr_url(pr["url"])
    cursor: str | None = None
    threads: list[dict[str, Any]] = []

    while True:
        after = "null" if cursor is None else json.dumps(cursor)
        query = f"""
        query {{
          repository(owner: {json.dumps(owner)}, name: {json.dumps(name)}) {{
            pullRequest(number: {int(pr['number'])}) {{
              reviewThreads(first: 100, after: {after}) {{
                nodes {{
                  id
                  isResolved
                  isOutdated
                  path
                  line
                  originalLine
                  comments(first: 20) {{
                    nodes {{ author {{ login }} body url createdAt }}
                  }}
                }}
                pageInfo {{ hasNextPage endCursor }}
              }}
            }}
          }}
        }}
        """
        output = gh(["api", "graphql", "-f", f"query={query}"], root)
        data = json.loads(output)
        pull_request = data.get("data", {}).get("repository", {}).get("pullRequest")
        if not pull_request:
            raise CommandError(f"GitHub returned no review threads for PR #{pr['number']}")
        page = pull_request["reviewThreads"]
        threads.extend(page["nodes"])
        if not page["pageInfo"]["hasNextPage"]:
            return threads
        cursor = page["pageInfo"]["endCursor"]


def normalize_check(raw: dict[str, Any]) -> dict[str, Any]:
    typename = raw.get("__typename", "")
    if typename == "StatusContext" or "context" in raw:
        state = (raw.get("state") or "").upper()
        return {
            "type": "status",
            "name": raw.get("context") or "unknown",
            "status": "COMPLETED" if state in {"SUCCESS", "FAILURE", "ERROR"} else state,
            "conclusion": "FAILURE" if state == "ERROR" else state,
            "url": raw.get("targetUrl"),
        }

    return {
        "type": "check-run",
        "name": raw.get("name") or raw.get("workflowName") or "unknown",
        "status": (raw.get("status") or "").upper(),
        "conclusion": (raw.get("conclusion") or "").upper() or None,
        "url": raw.get("detailsUrl"),
    }


def check_is_required_build(check: dict[str, Any]) -> bool:
    return check["name"].split(" / ")[-1].strip().lower() == REQUIRED_CHECK


def evaluate(snapshot: dict[str, Any], expected_head: str | None = None) -> dict[str, Any]:
    local = snapshot["local"]
    pr = snapshot["pr"]
    threads = snapshot.get("review_threads", [])
    checks = [normalize_check(raw) for raw in pr.get("statusCheckRollup") or []]
    blockers: list[str] = []
    waiting: list[str] = []

    if local["dirty_paths"]:
        blockers.append("working tree is not clean")
    if not local["branch"]:
        blockers.append("detached HEAD is not allowed")
    elif local["branch"] != pr.get("headRefName"):
        blockers.append(
            f"current branch {local['branch']} does not match PR head {pr.get('headRefName')}"
        )
    if local["head_sha"] != pr.get("headRefOid"):
        blockers.append("local HEAD does not match the GitHub PR head")
    if expected_head and pr.get("headRefOid") != expected_head:
        blockers.append(
            f"PR head changed: expected {expected_head}, found {pr.get('headRefOid')}"
        )

    if pr.get("state") != "OPEN":
        blockers.append(f"PR state is {pr.get('state')}, not OPEN")
    if pr.get("isDraft"):
        blockers.append("PR is a draft")
    if pr.get("baseRefName") != EXPECTED_BASE:
        blockers.append(
            f"base branch is {pr.get('baseRefName')}, but this skill only merges into {EXPECTED_BASE}"
        )
    if pr.get("reviewDecision") == "CHANGES_REQUESTED":
        blockers.append("review decision is CHANGES_REQUESTED")

    mergeable = (pr.get("mergeable") or "UNKNOWN").upper()
    merge_state = (pr.get("mergeStateStatus") or "UNKNOWN").upper()
    if mergeable == "CONFLICTING" or merge_state == "DIRTY":
        blockers.append("PR has merge conflicts")
    elif mergeable == "UNKNOWN" or merge_state == "UNKNOWN":
        waiting.append("GitHub has not finished computing mergeability")

    unresolved = [thread for thread in threads if not thread.get("isResolved")]
    if unresolved:
        blockers.append(f"{len(unresolved)} review thread(s) remain unresolved")

    if pr.get("reviewDecision") == "REVIEW_REQUIRED":
        blockers.append("review decision is REVIEW_REQUIRED")

    required_checks = [check for check in checks if check_is_required_build(check)]
    if not required_checks:
        waiting.append(f"required check '{REQUIRED_CHECK}' has not appeared")

    for check in checks:
        status = check["status"]
        conclusion = check["conclusion"]
        if status not in {"COMPLETED", "SUCCESS", "FAILURE", "ERROR"} or conclusion is None:
            waiting.append(f"check '{check['name']}' is {status or 'PENDING'}")
        elif conclusion in FAILURE_CONCLUSIONS or conclusion in {"FAILURE", "ERROR"}:
            blockers.append(f"check '{check['name']}' concluded {conclusion}")
        elif check_is_required_build(check) and conclusion != "SUCCESS":
            blockers.append(f"required check '{REQUIRED_CHECK}' concluded {conclusion}, not SUCCESS")
        elif conclusion not in SUCCESS_CONCLUSIONS:
            blockers.append(f"check '{check['name']}' has unsupported conclusion {conclusion}")

    if merge_state == "BLOCKED":
        if waiting:
            waiting.append("GitHub reports mergeStateStatus=BLOCKED while gates are pending")
        else:
            blockers.append("GitHub reports mergeStateStatus=BLOCKED")
    elif merge_state == "BEHIND":
        blockers.append("PR head is behind the base branch")
    elif merge_state == "UNSTABLE":
        waiting.append("GitHub reports mergeStateStatus=UNSTABLE")

    if blockers:
        status = "blocked"
    elif waiting:
        status = "waiting"
    else:
        status = "ready"

    return {
        "status": status,
        "blockers": sorted(set(blockers)),
        "waiting": sorted(set(waiting)),
        "checks": checks,
        "unresolved_review_threads": len(unresolved),
    }


def collect(root: Path, target: str | None) -> dict[str, Any]:
    pr = read_pr(root, target)
    return {
        "repository_root": str(root),
        "local": local_state(root),
        "pr": pr,
        "review_threads": read_review_threads(root, pr),
    }


def output_snapshot(snapshot: dict[str, Any], evaluation: dict[str, Any], timed_out: bool) -> None:
    pr = snapshot["pr"]
    payload = {
        "status": evaluation["status"],
        "timed_out": timed_out,
        "blockers": evaluation["blockers"],
        "waiting": evaluation["waiting"],
        "repository_root": snapshot["repository_root"],
        "local": snapshot["local"],
        "pr": {
            "number": pr["number"],
            "url": pr["url"],
            "title": pr["title"],
            "body": pr.get("body"),
            "state": pr["state"],
            "is_draft": pr["isDraft"],
            "base_branch": pr["baseRefName"],
            "head_branch": pr["headRefName"],
            "head_sha": pr["headRefOid"],
            "mergeable": pr["mergeable"],
            "merge_state_status": pr["mergeStateStatus"],
            "review_decision": pr.get("reviewDecision"),
            "commits": pr.get("commits") or [],
            "files": pr.get("files") or [],
        },
        "checks": evaluation["checks"],
        "review_threads": snapshot["review_threads"],
        "unresolved_review_threads": evaluation["unresolved_review_threads"],
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Read-only inspection of Laimory PR merge gates"
    )
    parser.add_argument("--pr", help="PR number, URL, or branch; defaults to current branch")
    parser.add_argument("--expected-head", help="block if the GitHub PR head differs")
    parser.add_argument("--wait", action="store_true", help="poll waiting gates until ready or timeout")
    parser.add_argument("--timeout", type=int, default=1200, help="wait timeout in seconds")
    parser.add_argument("--interval", type=int, default=10, help="poll interval in seconds")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        root = repository_root(Path.cwd())
        gh(["auth", "status"], root)
        deadline = time.monotonic() + max(args.timeout, 0)

        while True:
            snapshot = collect(root, args.pr)
            evaluation = evaluate(snapshot, args.expected_head)
            if evaluation["status"] != "waiting" or not args.wait:
                output_snapshot(snapshot, evaluation, timed_out=False)
                return {
                    "ready": EXIT_READY,
                    "waiting": EXIT_WAITING,
                    "blocked": EXIT_BLOCKED,
                }[evaluation["status"]]

            if time.monotonic() >= deadline:
                output_snapshot(snapshot, evaluation, timed_out=True)
                return EXIT_WAITING
            time.sleep(max(args.interval, 1))
    except (CommandError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        print(
            json.dumps({"status": "error", "errors": [str(exc)]}, ensure_ascii=False, indent=2)
        )
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
