#!/usr/bin/env python3
"""Create, update, or verify the current user's PR digest comment.

The helper deliberately owns only the mutable issue-comment boundary. It never
promotes, merges, deletes comments, resolves reviews, or changes repository files.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import urlparse


API_VERSION = "2026-03-10"
API_VERSION_HEADER = f"X-GitHub-Api-Version: {API_VERSION}"
MARKER = "<!-- laimory-pr-digest:v1 -->"
HEAD_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")
REPOSITORY_PART_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+$")

EXIT_READY = 0
EXIT_ERROR = 1
EXIT_BLOCKED = 3


class OperationalError(RuntimeError):
    """The helper could not complete a command, API, JSON, or file operation."""


class SafetyBlock(RuntimeError):
    """Observed state does not satisfy the fail-closed digest contract."""


class CommandFailure(RuntimeError):
    """A subprocess failed without exposing its potentially sensitive output."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise OperationalError(f"invalid arguments: {message}")


def run_command(
    command: list[str], cwd: Path, input_text: str | None = None
) -> str:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            input=input_text,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    except FileNotFoundError as exc:
        raise CommandFailure(f"required command not found: {command[0]}") from exc

    if result.returncode != 0:
        raise CommandFailure(
            f"{command[0]} exited with status {result.returncode}"
        )
    return result.stdout


def git(args: list[str], cwd: Path) -> str:
    try:
        return run_command(["git", *args], cwd).strip()
    except CommandFailure as exc:
        raise OperationalError(f"git command failed: {args[0]}") from exc


def repository_root(cwd: Path) -> Path:
    return Path(git(["rev-parse", "--show-toplevel"], cwd)).resolve()


def _parse_repository_path(path: str) -> tuple[str, str] | None:
    parts = [part for part in path.removesuffix(".git").split("/") if part]
    if len(parts) != 2:
        return None
    owner, repository = parts
    if not all(
        part not in {".", ".."}
        and REPOSITORY_PART_PATTERN.fullmatch(part)
        for part in parts
    ):
        return None
    return owner, repository


def repository_from_origin(root: Path) -> tuple[str, str]:
    remote = git(["remote", "get-url", "origin"], root)

    if remote.startswith("git@github.com:"):
        parsed = _parse_repository_path(remote.removeprefix("git@github.com:"))
    else:
        url = urlparse(remote)
        parsed = (
            _parse_repository_path(url.path)
            if url.hostname == "github.com"
            else None
        )

    if parsed is None:
        raise OperationalError("origin is not a supported github.com repository URL")
    return parsed


@dataclass(frozen=True)
class PullRequestTarget:
    owner: str
    repository: str
    number: int


def parse_pr_target(value: str, root: Path) -> PullRequestTarget:
    if value.isdigit() and int(value) > 0:
        owner, repository = repository_from_origin(root)
        return PullRequestTarget(owner, repository, int(value))

    url = urlparse(value)
    parts = [part for part in url.path.split("/") if part]
    if (
        url.scheme in {"http", "https"}
        and url.hostname == "github.com"
        and len(parts) == 4
        and parts[2] == "pull"
        and parts[3].isdigit()
        and int(parts[3]) > 0
        and all(
            part not in {".", ".."}
            and REPOSITORY_PART_PATTERN.fullmatch(part)
            for part in parts[:2]
        )
    ):
        return PullRequestTarget(parts[0], parts[1], int(parts[3]))

    raise OperationalError("--pr must be a positive PR number or github.com PR URL")


def normalized_expected_head(value: str) -> str:
    if not HEAD_PATTERN.fullmatch(value):
        raise OperationalError("--expected-head must be a 40-character hexadecimal SHA")
    return value.lower()


def is_physically_within_repository(path: Path, root: Path) -> bool:
    for parent in path.parents:
        try:
            if parent.samefile(root):
                return True
        except OSError as exc:
            raise SafetyBlock(
                "could not verify that --body-file is outside the repository"
            ) from exc
    return False


def read_and_validate_body(
    body_file: str, root: Path, expected_head: str
) -> tuple[Path, str]:
    raw_path = Path(body_file)
    if not raw_path.is_absolute():
        raise OperationalError("--body-file must be an absolute path")

    try:
        path = raw_path.resolve(strict=True)
    except OSError as exc:
        raise OperationalError("--body-file does not exist") from exc

    if not path.is_file():
        raise OperationalError("--body-file must identify a regular file")
    if (
        path == root
        or root in path.parents
        or is_physically_within_repository(path, root)
    ):
        raise SafetyBlock("--body-file must be outside the repository")

    try:
        body = path.read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        raise OperationalError("--body-file must be readable UTF-8") from exc

    lines = body.splitlines()
    if not lines or lines[0] != MARKER:
        raise SafetyBlock("digest marker must be the exact first line")
    if body.count(MARKER) != 1:
        raise SafetyBlock("digest marker must occur exactly once")

    expected_line = f"implementation_head_sha: {expected_head}"
    if lines.count(expected_line) != 1:
        raise SafetyBlock(
            "digest must contain exactly one expected implementation_head_sha line"
        )
    return path, body


ApiRunner = Callable[[list[str], Path, Optional[str]], str]


class GitHubClient:
    def __init__(
        self,
        root: Path,
        runner: ApiRunner = run_command,
    ) -> None:
        self.root = root
        self.runner = runner

    def _api(
        self,
        endpoint: str,
        *,
        method: str = "GET",
        body: dict[str, Any] | None = None,
        paginate: bool = False,
    ) -> Any:
        command = [
            "gh",
            "api",
            "--method",
            method,
            "-H",
            API_VERSION_HEADER,
        ]
        if paginate:
            command.extend(["--paginate", "--slurp"])
        if body is not None:
            command.extend(["--input", "-"])
        command.append(endpoint)

        try:
            output = self.runner(
                command,
                self.root,
                json.dumps(body, ensure_ascii=False) if body is not None else None,
            )
        except CommandFailure as exc:
            raise OperationalError(
                f"GitHub API request failed: {method} {endpoint.split('?')[0]}"
            ) from exc

        try:
            return json.loads(output)
        except json.JSONDecodeError as exc:
            raise OperationalError(
                f"GitHub API returned invalid JSON: {method} {endpoint.split('?')[0]}"
            ) from exc

    def current_login(self) -> str:
        payload = self._api("/user")
        login = payload.get("login") if isinstance(payload, dict) else None
        if not isinstance(login, str) or not login:
            raise OperationalError("GitHub API returned no authenticated login")
        return login

    def pull_request_head(self, target: PullRequestTarget) -> str:
        payload = self._api(
            f"/repos/{target.owner}/{target.repository}/pulls/{target.number}"
        )
        head = payload.get("head") if isinstance(payload, dict) else None
        sha = head.get("sha") if isinstance(head, dict) else None
        if not isinstance(sha, str) or not HEAD_PATTERN.fullmatch(sha):
            raise OperationalError("GitHub API returned no valid PR head SHA")
        return sha.lower()

    def list_comments(self, target: PullRequestTarget) -> list[dict[str, Any]]:
        payload = self._api(
            (
                f"/repos/{target.owner}/{target.repository}/issues/"
                f"{target.number}/comments?per_page=100"
            ),
            paginate=True,
        )
        if not isinstance(payload, list):
            raise OperationalError("GitHub API returned an invalid comments page list")

        comments: list[dict[str, Any]] = []
        for page in payload:
            if not isinstance(page, list) or not all(
                isinstance(comment, dict) for comment in page
            ):
                raise OperationalError("GitHub API returned an invalid comments page")
            comments.extend(page)
        return comments

    def create_comment(
        self, target: PullRequestTarget, body: str
    ) -> dict[str, Any]:
        payload = self._api(
            (
                f"/repos/{target.owner}/{target.repository}/issues/"
                f"{target.number}/comments"
            ),
            method="POST",
            body={"body": body},
        )
        if not isinstance(payload, dict):
            raise OperationalError("GitHub API returned an invalid create response")
        return payload

    def update_comment(
        self, target: PullRequestTarget, comment_id: int, body: str
    ) -> dict[str, Any]:
        payload = self._api(
            (
                f"/repos/{target.owner}/{target.repository}/issues/comments/"
                f"{comment_id}"
            ),
            method="PATCH",
            body={"body": body},
        )
        if not isinstance(payload, dict):
            raise OperationalError("GitHub API returned an invalid update response")
        return payload


def _comment_id(comment: dict[str, Any]) -> int | None:
    value = comment.get("id")
    return (
        value
        if isinstance(value, int) and not isinstance(value, bool) and value > 0
        else None
    )


def _comment_author(comment: dict[str, Any]) -> str | None:
    user = comment.get("user")
    login = user.get("login") if isinstance(user, dict) else None
    return login if isinstance(login, str) and login else None


def own_marker_comments(
    comments: list[dict[str, Any]], login: str
) -> list[dict[str, Any]]:
    return [
        comment
        for comment in comments
        if isinstance(comment.get("body"), str)
        and MARKER in comment["body"]
        and (_comment_author(comment) or "").casefold() == login.casefold()
    ]


def verified_comment(
    *,
    client: GitHubClient,
    target: PullRequestTarget,
    body: str,
    expected_head: str,
    expected_login: str,
    expected_comment_id: int,
) -> dict[str, Any]:
    observed_head = client.pull_request_head(target)
    if observed_head != expected_head:
        raise SafetyBlock(
            "PR head does not match the expected implementation head"
        )

    observed_login = client.current_login()
    if observed_login.casefold() != expected_login.casefold():
        raise SafetyBlock("authenticated GitHub user changed during digest handling")

    matches = own_marker_comments(client.list_comments(target), observed_login)
    if len(matches) != 1:
        raise SafetyBlock(
            "expected exactly one current-author digest marker comment"
        )

    comment = matches[0]
    if _comment_id(comment) != expected_comment_id:
        raise SafetyBlock("digest comment ID does not match the expected comment")
    author = _comment_author(comment)
    if author is None or author.casefold() != observed_login.casefold():
        raise SafetyBlock("digest comment author does not match the authenticated user")
    if comment.get("body") != body:
        raise SafetyBlock("digest comment body does not match the body file")
    if body.count(MARKER) != 1:
        raise SafetyBlock("digest comment marker count is invalid")
    if body.splitlines().count(
        f"implementation_head_sha: {expected_head}"
    ) != 1:
        raise SafetyBlock("digest comment implementation head line is invalid")

    comment_url = comment.get("html_url")
    if not isinstance(comment_url, str) or not comment_url:
        raise SafetyBlock("digest comment has no URL")
    return comment


def upsert(
    *,
    client: GitHubClient,
    target: PullRequestTarget,
    body: str,
    expected_head: str,
) -> dict[str, Any]:
    login = client.current_login()
    matches = own_marker_comments(client.list_comments(target), login)
    if len(matches) > 1:
        raise SafetyBlock(
            "multiple current-author digest marker comments require manual cleanup"
        )

    pre_mutation_head = client.pull_request_head(target)
    if pre_mutation_head != expected_head:
        raise SafetyBlock(
            "PR head changed before digest comment mutation"
        )

    if not matches:
        action = "created"
        expected_update_id = None
        mutation_response = client.create_comment(target, body)
    else:
        existing_id = _comment_id(matches[0])
        if existing_id is None:
            raise SafetyBlock("existing digest comment has no valid ID")
        action = "updated"
        expected_update_id = existing_id
        mutation_response = client.update_comment(target, existing_id, body)

    comment_id = _comment_id(mutation_response)
    if comment_id is None:
        raise OperationalError("GitHub API mutation returned no valid comment ID")
    if expected_update_id is not None and comment_id != expected_update_id:
        raise SafetyBlock("updated digest response changed the selected comment ID")

    comment = verified_comment(
        client=client,
        target=target,
        body=body,
        expected_head=expected_head,
        expected_login=login,
        expected_comment_id=comment_id,
    )
    return success_payload(
        action=action,
        comment=comment,
        login=login,
        expected_head=expected_head,
    )


def verify_only(
    *,
    client: GitHubClient,
    target: PullRequestTarget,
    body: str,
    expected_head: str,
    comment_id: int,
) -> dict[str, Any]:
    login = client.current_login()
    comment = verified_comment(
        client=client,
        target=target,
        body=body,
        expected_head=expected_head,
        expected_login=login,
        expected_comment_id=comment_id,
    )
    return success_payload(
        action="verified",
        comment=comment,
        login=login,
        expected_head=expected_head,
    )


def success_payload(
    *,
    action: str,
    comment: dict[str, Any],
    login: str,
    expected_head: str,
) -> dict[str, Any]:
    return {
        "action": action,
        "comment_id": _comment_id(comment),
        "comment_url": comment["html_url"],
        "author_login": login,
        "implementation_head_sha": expected_head,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = SafeArgumentParser(
        description="Upsert or verify the current user's PR digest marker comment"
    )
    parser.add_argument(
        "--pr", required=True, help="positive PR number or github.com PR URL"
    )
    parser.add_argument(
        "--body-file",
        required=True,
        help="absolute Markdown path outside the repository",
    )
    parser.add_argument(
        "--expected-head",
        required=True,
        help="expected 40-character PR head SHA",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="verify without creating or updating a comment",
    )
    parser.add_argument(
        "--comment-id",
        type=int,
        help="exact comment ID required by --verify-only",
    )
    return parser.parse_args(argv)


def execute(args: argparse.Namespace, cwd: Path) -> dict[str, Any]:
    root = repository_root(cwd)
    target = parse_pr_target(args.pr, root)
    expected_head = normalized_expected_head(args.expected_head)
    _, body = read_and_validate_body(args.body_file, root, expected_head)

    if args.verify_only:
        if args.comment_id is None or args.comment_id <= 0:
            raise OperationalError(
                "--verify-only requires a positive --comment-id"
            )
    elif args.comment_id is not None:
        raise OperationalError("--comment-id is only valid with --verify-only")

    client = GitHubClient(root)
    if args.verify_only:
        return verify_only(
            client=client,
            target=target,
            body=body,
            expected_head=expected_head,
            comment_id=args.comment_id,
        )
    return upsert(
        client=client,
        target=target,
        body=body,
        expected_head=expected_head,
    )


def _write_error(status: str, message: str) -> None:
    print(
        json.dumps(
            {"status": status, "error": message},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        file=sys.stderr,
    )


def main(argv: list[str] | None = None) -> int:
    try:
        args = parse_args(argv)
        payload = execute(args, Path.cwd())
        print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        return EXIT_READY
    except SafetyBlock as exc:
        _write_error("blocked", str(exc))
        return EXIT_BLOCKED
    except (OperationalError, CommandFailure, OSError, TypeError, ValueError) as exc:
        _write_error("error", str(exc))
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
