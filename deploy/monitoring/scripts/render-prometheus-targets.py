#!/usr/bin/env python3
"""Render Prometheus file_sd targets from explicit, validated inputs."""

from __future__ import annotations

import argparse
import ipaddress
import json
from pathlib import Path
import re
from string import Template
import sys


REQUIRED_KEYS = {
    "dev_was_private_ip",
    "monitoring_private_ip",
    "dev_mysql_private_ip",
    "redis_private_ip",
    "elk_private_ip",
    "dev_api_domain",
}
TEMPLATES = {
    "application-targets.yml.template": "application.yml",
    "node-targets.yml.template": "node.yml",
    "probe-targets.yml.template": "probe.yml",
}
DOMAIN = re.compile(
    r"(?=^.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)
PRIVATE_IPV4_NETWORKS = tuple(
    ipaddress.ip_network(cidr) for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


class RenderError(ValueError):
    """Input or template violates the fail-closed rendering contract."""


def load_values(path: Path) -> dict[str, str]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise RenderError(f"cannot read values JSON: {error}") from error
    if not isinstance(raw, dict):
        raise RenderError("values JSON must be an object")
    actual = set(raw)
    missing = sorted(REQUIRED_KEYS - actual)
    extra = sorted(actual - REQUIRED_KEYS)
    if missing or extra:
        details = []
        if missing:
            details.append(f"missing keys: {', '.join(missing)}")
        if extra:
            details.append(f"unexpected keys: {', '.join(extra)}")
        raise RenderError("; ".join(details))
    if not all(isinstance(value, str) and value for value in raw.values()):
        raise RenderError("every value must be a non-empty string")

    for key in REQUIRED_KEYS - {"dev_api_domain"}:
        try:
            address = ipaddress.ip_address(raw[key])
        except ValueError as error:
            raise RenderError(f"{key} must be an IP address") from error
        if address.version != 4 or not any(
            address in network for network in PRIVATE_IPV4_NETWORKS
        ):
            raise RenderError(f"{key} must be a private IPv4 address")

    domain = raw["dev_api_domain"].lower()
    if not DOMAIN.fullmatch(domain):
        raise RenderError("dev_api_domain must be a DNS hostname without scheme or path")
    raw["dev_api_domain"] = domain
    return raw


def render(values_path: Path, template_dir: Path, output_dir: Path) -> None:
    values = load_values(values_path)
    if output_dir.exists() and not output_dir.is_dir():
        raise RenderError("output path must be a directory")
    expected_outputs = set(TEMPLATES.values())
    if output_dir.exists():
        existing_entries = {path.name: path for path in output_dir.iterdir()}
        unexpected_outputs = set(existing_entries) - expected_outputs
        invalid_outputs = {
            name for name, path in existing_entries.items() if not path.is_file()
        }
        if unexpected_outputs or invalid_outputs:
            rejected = sorted(unexpected_outputs | invalid_outputs)
            raise RenderError(
                "output directory contains unexpected entries: "
                + ", ".join(rejected)
            )

    rendered_outputs: dict[str, str] = {}
    for template_name, output_name in TEMPLATES.items():
        template_path = template_dir / template_name
        try:
            source = template_path.read_text(encoding="utf-8")
            rendered = Template(source).substitute(values)
        except (OSError, UnicodeError, KeyError, ValueError) as error:
            raise RenderError(f"cannot render {template_name}: {error}") from error
        if Template.pattern.search(rendered):
            raise RenderError(f"unresolved placeholder remains in {template_name}")
        rendered_outputs[output_name] = rendered

    output_dir.mkdir(parents=True, exist_ok=True)
    for output_name, rendered in rendered_outputs.items():
        (output_dir / output_name).write_text(rendered, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--values", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--template-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "prometheus",
    )
    args = parser.parse_args(argv)
    try:
        render(args.values, args.template_dir, args.output_dir)
    except RenderError as error:
        print(f"target rendering rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
