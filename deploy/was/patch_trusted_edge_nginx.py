#!/usr/bin/env python3
"""Fail-closed patcher for the Laimory application nginx trusted edge."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from pathlib import Path
import re
import sys


LOCATION_ROOT = re.compile(r"^[ \t]*location[ \t]+/[ \t]*\{[ \t]*$")
SERVER_BLOCK = re.compile(r"^[ \t]*server[ \t]*\{[ \t]*$")
ANY_PROXY_PASS = re.compile(r"^[ \t]*proxy_pass\b")
APPLICATION_PROXY_PASS = re.compile(
    r"^[ \t]*proxy_pass[ \t]+http://127[.]0[.]0[.]1:8080;[ \t]*$"
)
ANY_HOST_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+Host(?:[ \t;]|$)", re.IGNORECASE
)
HOST_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+Host[ \t]+[$]host;[ \t]*$"
)
ANY_CLIENT_IP_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+Laimory-Client-IP(?:[ \t;]|$)",
    re.IGNORECASE,
)
CLIENT_IP_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+Laimory-Client-IP"
    r"[ \t]+[$]remote_addr;[ \t]*$"
)
ANY_FORWARDED_PROTO_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+X-Forwarded-Proto(?:[ \t;]|$)",
    re.IGNORECASE,
)
FORWARDED_PROTO_HEADER = re.compile(
    r"^[ \t]*proxy_set_header[ \t]+X-Forwarded-Proto"
    r"[ \t]+[$]scheme;[ \t]*$"
)


class LayoutError(ValueError):
    """The active nginx file is outside the runbook's expected layout."""


@dataclass
class LocationBlock:
    inner_depth: int
    proxy_passes: list[int] = field(default_factory=list)
    application_proxy_passes: list[int] = field(default_factory=list)
    host_headers: list[int] = field(default_factory=list)
    exact_host_headers: list[int] = field(default_factory=list)
    client_ip_headers: list[int] = field(default_factory=list)
    exact_client_ip_headers: list[int] = field(default_factory=list)
    forwarded_proto_headers: list[int] = field(default_factory=list)
    exact_forwarded_proto_headers: list[int] = field(default_factory=list)


def _scan_line(line: str) -> tuple[str, int, int]:
    """Strip an nginx comment and count unquoted braces."""
    visible: list[str] = []
    quote: str | None = None
    escaped = False
    opens = 0
    closes = 0

    for char in line:
        if escaped:
            visible.append(char)
            escaped = False
            continue
        if quote is not None:
            visible.append(char)
            if char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in ("'", '"'):
            quote = char
            visible.append(char)
            continue
        if char == "#":
            break
        visible.append(char)
        if char == "{":
            opens += 1
        elif char == "}":
            closes += 1

    if quote is not None:
        raise LayoutError("multiline quoted directives are not supported")
    return "".join(visible).rstrip(), opens, closes


def _record_directive(block: LocationBlock, line_number: int, directive: str) -> None:
    if ANY_PROXY_PASS.match(directive):
        block.proxy_passes.append(line_number)
    if APPLICATION_PROXY_PASS.match(directive):
        block.application_proxy_passes.append(line_number)
    if ANY_HOST_HEADER.match(directive):
        block.host_headers.append(line_number)
    if HOST_HEADER.match(directive):
        block.exact_host_headers.append(line_number)
    if ANY_CLIENT_IP_HEADER.match(directive):
        block.client_ip_headers.append(line_number)
    if CLIENT_IP_HEADER.match(directive):
        block.exact_client_ip_headers.append(line_number)
    if ANY_FORWARDED_PROTO_HEADER.match(directive):
        block.forwarded_proto_headers.append(line_number)
    if FORWARDED_PROTO_HEADER.match(directive):
        block.exact_forwarded_proto_headers.append(line_number)


def _find_root_locations(text: str) -> list[LocationBlock]:
    depth = 0
    top_level_block: str | None = None
    current: LocationBlock | None = None
    locations: list[LocationBlock] = []

    for line_number, line in enumerate(text.splitlines(keepends=True), start=1):
        directive, opens, closes = _scan_line(line)
        depth_before = depth

        if current is None and LOCATION_ROOT.match(directive):
            if depth_before != 1 or not SERVER_BLOCK.match(top_level_block or ""):
                raise LayoutError(
                    f"root application location must be directly inside a server block "
                    f"at line {line_number}"
                )
            if opens != 1 or closes != 0:
                raise LayoutError(f"unsupported root location opener at line {line_number}")
            current = LocationBlock(inner_depth=depth_before + 1)
        elif current is not None and depth_before == current.inner_depth:
            if opens:
                raise LayoutError(
                    f"nested application location layout is not supported at line {line_number}"
                )
            _record_directive(current, line_number, directive)

        depth += opens - closes
        if depth < 0:
            raise LayoutError(f"unmatched closing brace at line {line_number}")
        if depth_before == 0 and depth > 0:
            top_level_block = directive if opens == 1 and closes == 0 else None
        elif depth == 0:
            top_level_block = None
        if current is not None and depth < current.inner_depth:
            locations.append(current)
            current = None

    if depth != 0 or current is not None:
        raise LayoutError("unbalanced nginx block braces")
    return locations


def _application_location(text: str, require_client_header: bool) -> LocationBlock:
    locations = _find_root_locations(text)
    candidates = [
        location for location in locations if location.application_proxy_passes
    ]
    if len(candidates) != 1:
        raise LayoutError(
            "expected exactly one root `location /` with "
            "`proxy_pass http://127.0.0.1:8080;`"
        )

    target = candidates[0]
    if len(target.proxy_passes) != 1 or len(target.application_proxy_passes) != 1:
        raise LayoutError("application location must contain exactly one expected proxy_pass")
    if len(target.host_headers) != 1 or len(target.exact_host_headers) != 1:
        raise LayoutError(
            "application location must contain exactly "
            "`proxy_set_header Host $host;`"
        )
    if (
        len(target.forwarded_proto_headers) != 1
        or len(target.exact_forwarded_proto_headers) != 1
    ):
        raise LayoutError(
            "application location must contain exactly "
            "`proxy_set_header X-Forwarded-Proto $scheme;`"
        )
    if len(target.client_ip_headers) != len(target.exact_client_ip_headers):
        raise LayoutError(
            "application location contains a non-canonical Laimory-Client-IP directive"
        )
    expected_client_headers = 1 if require_client_header else (0, 1)
    if require_client_header:
        valid_client_count = len(target.exact_client_ip_headers) == expected_client_headers
    else:
        valid_client_count = len(target.exact_client_ip_headers) in expected_client_headers
    if not valid_client_count:
        qualifier = "exactly one" if require_client_header else "zero or one"
        raise LayoutError(
            f"application location must contain {qualifier} canonical "
            "Laimory-Client-IP directive"
        )
    return target


def validate_text(text: str) -> None:
    """Require the effective application location to have the trusted-edge header."""
    _application_location(text, require_client_header=True)


def patch_text(text: str) -> str:
    """Insert the trusted-edge header in the sole expected application location."""
    target = _application_location(text, require_client_header=False)
    if target.exact_client_ip_headers:
        return text

    lines = text.splitlines(keepends=True)
    host_index = target.exact_host_headers[0] - 1
    host_line = lines[host_index]
    indent = re.match(r"^[ \t]*", host_line).group(0)
    newline = "\r\n" if host_line.endswith("\r\n") else "\n"
    lines.insert(
        host_index + 1,
        f"{indent}proxy_set_header Laimory-Client-IP $remote_addr;{newline}",
    )
    patched = "".join(lines)
    validate_text(patched)
    return patched


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("source")
    parser.add_argument("destination", nargs="?")
    args = parser.parse_args(argv)
    if args.check and args.destination is not None:
        parser.error("--check does not accept a destination")
    if not args.check and args.destination is None:
        parser.error("destination is required unless --check is used")

    try:
        source = Path(args.source).read_bytes().decode("utf-8")
        if args.check:
            validate_text(source)
        else:
            patched = patch_text(source)
            Path(args.destination).write_bytes(patched.encode("utf-8"))
    except (LayoutError, OSError, UnicodeError) as error:
        print(f"trusted-edge nginx layout rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
