#!/usr/bin/env python3
"""OCI prod 경보를 Grafana Cloud stack에 적용하거나 상태를 본다(관리자 Mac에서 실행, 표준 라이브러리만).

Grafana Cloud는 파일 provisioning을 지원하지 않아 HTTP provisioning API로 넣는다. 이 디렉터리의 JSON이
원본이고, apply는 템플릿·contact point·폴더·규칙 그룹·알림 정책을 그 내용으로 덮어쓴다(그룹에서 빠진
규칙은 지워진다). API로 만든 자원은 UI에서 편집이 잠긴다 — 바꿀 때는 JSON을 고쳐 다시 apply한다.

  apply [--threshold UID=VALUE ...]   적용. --threshold는 발화 시험용 임시 임계값(다시 apply하면 원복)
  status                              폴더 안 규칙의 상태(Normal/Pending/Firing, nodata·error)
  render                              보낼 JSON을 출력만 한다(webhook은 가림, 네트워크 호출 없음)

비밀은 화면·argv에 나오지 않는다. service account token과 Discord webhook URL은 사용자가 만든 0600
파일에서 읽어 요청 헤더·본문에만 싣는다.

환경 변수
  GRAFANA_CLOUD_URL              stack 주소(https://<stack>.grafana.net) — 저장소에 적지 않는다
  GRAFANA_CLOUD_SA_TOKEN_FILE    기본 ~/.config/laimory/grafana-cloud-sa-token (service account, Admin —
                                 규칙 provisioning의 provenance 권한은 Admin에만 있다)
  DISCORD_WEBHOOK_URL_FILE       기본 ~/.config/laimory/discord-webhook-url
  GRAFANA_CLOUD_PROM_DS_UID      기본 grafanacloud-prom
"""

import argparse
import json
import os
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
RULES = json.loads((HERE / "alert-rules.json").read_text())
NOTIFY = json.loads((HERE / "notification.json").read_text())
ORG_ID = 1


def fail(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def read_secret(env_name, default, pattern, label):
    path = Path(os.environ.get(env_name, default)).expanduser()
    if not path.is_file():
        fail(f"{label} file not found: {path}")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        fail(f"{label} file must not be readable by group/others (chmod 600 {path})")
    value = path.read_text().strip()
    if not re.fullmatch(pattern, value):
        fail(f"{label} file does not look like a {label} (value not shown)")
    return value


def stack_url():
    url = os.environ.get("GRAFANA_CLOUD_URL", "").rstrip("/")
    if not re.fullmatch(r"https://[a-z0-9-]+\.grafana\.net", url):
        fail("set GRAFANA_CLOUD_URL=https://<stack>.grafana.net")
    return url


def rule_model(rule, group_name, folder_uid, ds_uid, threshold):
    return {
        "uid": rule["uid"],
        "title": rule["title"],
        "orgID": ORG_ID,
        "folderUID": folder_uid,
        "ruleGroup": group_name,
        "condition": "C",
        "data": [
            {
                "refId": "A",
                "relativeTimeRange": {"from": 600, "to": 0},
                "datasourceUid": ds_uid,
                "model": {
                    "refId": "A",
                    "datasource": {"type": "prometheus", "uid": ds_uid},
                    "editorMode": "code",
                    "expr": rule["expr"],
                    "instant": True,
                    "range": False,
                    "intervalMs": 60000,
                    "maxDataPoints": 43200,
                },
            },
            {
                "refId": "B",
                "relativeTimeRange": {"from": 0, "to": 0},
                "datasourceUid": "__expr__",
                "model": {
                    "refId": "B",
                    "datasource": {"type": "__expr__", "uid": "__expr__"},
                    "type": "reduce",
                    "expression": "A",
                    "reducer": "last",
                },
            },
            {
                "refId": "C",
                "relativeTimeRange": {"from": 0, "to": 0},
                "datasourceUid": "__expr__",
                "model": {
                    "refId": "C",
                    "datasource": {"type": "__expr__", "uid": "__expr__"},
                    "type": "threshold",
                    "expression": "B",
                    "conditions": [{"evaluator": {"type": rule["op"], "params": [threshold]}}],
                },
            },
        ],
        "noDataState": rule["noDataState"],
        "execErrState": "Error",
        "for": rule["for"],
        "annotations": {
            "summary": rule["summary"],
            "description": rule["description"],
            "runbook_url": rule["runbook_url"],
        },
        "labels": rule["labels"],
        "isPaused": False,
    }


def build_groups(ds_uid, overrides):
    folder_uid = RULES["folder"]["uid"]
    known = {r["uid"] for g in RULES["groups"] for r in g["rules"]}
    unknown = set(overrides) - known
    if unknown:
        fail(f"unknown rule uid in --threshold: {', '.join(sorted(unknown))}")
    groups = []
    for group in RULES["groups"]:
        rules = [
            rule_model(r, group["name"], folder_uid, ds_uid, overrides.get(r["uid"], r["threshold"]))
            for r in group["rules"]
        ]
        groups.append({
            "title": group["name"],
            "folderUid": folder_uid,
            "interval": RULES["interval_seconds"],
            "rules": rules,
        })
    return groups


def contact_point_body(webhook_url):
    cp = NOTIFY["contact_point"]
    return {
        "uid": cp["uid"],
        "name": cp["name"],
        "type": "discord",
        "disableResolveMessage": False,
        "settings": {
            "url": webhook_url,
            "use_discord_username": False,
            "title": '{{ template "laimory.discord.title" . }}',
            "message": '{{ template "laimory.discord.message" . }}',
        },
    }


class Api:
    def __init__(self, base, token):
        self.base = base
        self.token = token

    def call(self, method, path, body=None, ok=(200, 201, 202, 204), allow=()):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        if status in allow:
            return status, None
        if status not in ok:
            # 응답 본문엔 비밀이 없다(webhook은 Grafana가 가린다). 그래도 길이는 자른다.
            fail(f"{method} {path} -> HTTP {status}: {raw[:500].decode(errors='replace')}")
        return status, (json.loads(raw) if raw.strip() else None)


def apply(args):
    overrides = {}
    for item in args.threshold or []:
        uid, _, value = item.partition("=")
        try:
            overrides[uid] = float(value)
        except ValueError:
            fail(f"--threshold expects UID=NUMBER, got {item!r}")

    base = stack_url()
    token = read_secret("GRAFANA_CLOUD_SA_TOKEN_FILE", "~/.config/laimory/grafana-cloud-sa-token",
                        r"glsa_[A-Za-z0-9_]{20,200}", "service account token")
    webhook = read_secret("DISCORD_WEBHOOK_URL_FILE", "~/.config/laimory/discord-webhook-url",
                          r"https://(discord\.com|discordapp\.com)/api/webhooks/\d+/[A-Za-z0-9_-]+",
                          "Discord webhook URL")
    ds_uid = os.environ.get("GRAFANA_CLOUD_PROM_DS_UID", "grafanacloud-prom")
    api = Api(base, token)

    api.call("GET", f"/api/datasources/uid/{urllib.parse.quote(ds_uid)}")

    name = NOTIFY["template_name"]
    status, current = api.call("GET", f"/api/v1/provisioning/templates/{name}", allow=(404,))
    template_body = {"template": NOTIFY["template"]}
    if current and current.get("version"):
        template_body["version"] = current["version"]
    api.call("PUT", f"/api/v1/provisioning/templates/{name}", template_body)
    print(f"template {name}: applied")

    cp = contact_point_body(webhook)
    _, points = api.call("GET", "/api/v1/provisioning/contact-points")
    if any(p.get("uid") == cp["uid"] for p in points or []):
        api.call("PUT", f"/api/v1/provisioning/contact-points/{cp['uid']}", cp)
    else:
        api.call("POST", "/api/v1/provisioning/contact-points", cp)
    print(f"contact point {cp['name']}: applied")

    folder = RULES["folder"]
    # 단건 조회는 없는 uid에 404가 아니라 403(folders:read)을 돌려준다 — 접근 가능한 목록에서 찾는다.
    _, folders = api.call("GET", "/api/folders?limit=1000")
    if not any(f.get("uid") == folder["uid"] for f in folders or []):
        api.call("POST", "/api/folders", {"uid": folder["uid"], "title": folder["title"]})
    print(f"folder {folder['title']}: ready")

    for group in build_groups(ds_uid, overrides):
        path = f"/api/v1/provisioning/folder/{folder['uid']}/rule-groups/{urllib.parse.quote(group['title'])}"
        api.call("PUT", path, group)
        print(f"rule group {group['title']}: {len(group['rules'])} rules applied")

    api.call("PUT", "/api/v1/provisioning/policies", NOTIFY["policy"])
    print("notification policy: applied")
    if overrides:
        print("temporary thresholds active: " + ", ".join(f"{k}={v:g}" for k, v in overrides.items())
              + " — run apply again without --threshold to restore")


def status(_args):
    base = stack_url()
    token = read_secret("GRAFANA_CLOUD_SA_TOKEN_FILE", "~/.config/laimory/grafana-cloud-sa-token",
                        r"glsa_[A-Za-z0-9_]{20,200}", "service account token")
    _, body = Api(base, token).call("GET", "/api/prometheus/grafana/api/v1/rules")
    title = RULES["folder"]["title"]
    rows = []
    for group in (body or {}).get("data", {}).get("groups", []):
        if group.get("file") != title:
            continue
        for rule in group.get("rules", []):
            rows.append((group["name"], rule.get("name"), rule.get("state"), rule.get("health"),
                         (rule.get("lastError") or "")[:120]))
    if not rows:
        fail(f"no rules found in folder {title!r}")
    for row in sorted(rows):
        print("\t".join(str(c) for c in row))


def render(args):
    ds_uid = os.environ.get("GRAFANA_CLOUD_PROM_DS_UID", "grafanacloud-prom")
    output = {
        "template": {"name": NOTIFY["template_name"], "template": NOTIFY["template"]},
        "contact_point": contact_point_body("<discord webhook url from file>"),
        "rule_groups": build_groups(ds_uid, {}),
        "policy": NOTIFY["policy"],
    }
    json.dump(output, sys.stdout, indent=2, ensure_ascii=False)
    print()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    apply_parser = sub.add_parser("apply")
    apply_parser.add_argument("--threshold", action="append", metavar="UID=VALUE")
    apply_parser.set_defaults(func=apply)
    sub.add_parser("status").set_defaults(func=status)
    sub.add_parser("render").set_defaults(func=render)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
