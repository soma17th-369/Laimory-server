#!/usr/bin/env python3

from contextlib import redirect_stderr
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


WAS_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = WAS_DIR.parents[1]
PATCHER_PATH = WAS_DIR / "patch_trusted_edge_nginx.py"
SPEC = importlib.util.spec_from_file_location("patch_trusted_edge_nginx", PATCHER_PATH)
PATCHER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = PATCHER
SPEC.loader.exec_module(PATCHER)


def application_location(client_header: str = "") -> str:
    return textwrap.dedent(
        f"""\
        server {{
            listen 443 ssl;
            location / {{
                proxy_pass http://127.0.0.1:8080;
                proxy_set_header Host $host;
        {client_header}        proxy_set_header X-Forwarded-Proto $scheme;
            }}
        }}
        """
    )


class TrustedEdgeNginxPatcherTest(unittest.TestCase):

    def test_inserts_in_application_location_even_when_other_location_has_header(self) -> None:
        source = textwrap.dedent(
            """\
            server {
                location /metrics {
                    proxy_pass http://127.0.0.1:9090;
                    proxy_set_header Laimory-Client-IP $remote_addr;
                }
                location / {
                    proxy_pass http://127.0.0.1:8080;
                    proxy_set_header Host $host;
                    proxy_set_header X-Forwarded-Proto $scheme;
                }
            }
            """
        )

        patched = PATCHER.patch_text(source)

        self.assertEqual(
            patched.count("proxy_set_header Laimory-Client-IP $remote_addr;"), 2
        )
        self.assertIn(
            "proxy_set_header Host $host;\n"
            "        proxy_set_header Laimory-Client-IP $remote_addr;\n"
            "        proxy_set_header X-Forwarded-Proto $scheme;",
            patched,
        )
        PATCHER.validate_text(patched)

    def test_exact_application_header_is_idempotent(self) -> None:
        source = application_location(
            "        proxy_set_header Laimory-Client-IP $remote_addr;\n"
        )

        self.assertEqual(PATCHER.patch_text(source), source)
        PATCHER.validate_text(source)

    def test_rejects_noncanonical_header_without_output_mutation(self) -> None:
        source = application_location(
            "        proxy_set_header Laimory-Client-IP $http_x_forwarded_for;\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "source.conf"
            destination_path = Path(directory) / "destination.conf"
            source_path.write_text(source)
            destination_path.write_text("sentinel")

            with redirect_stderr(io.StringIO()):
                result = PATCHER.main([str(source_path), str(destination_path)])

            self.assertEqual(result, 1)
            self.assertEqual(destination_path.read_text(), "sentinel")

    def test_rejects_multiple_application_locations(self) -> None:
        source = application_location() + application_location()

        with self.assertRaises(PATCHER.LayoutError):
            PATCHER.patch_text(source)

    def test_ignores_nonapplication_root_location_but_rejects_nested_app_layout(self) -> None:
        second_root_location = application_location() + textwrap.dedent(
            """\
            server {
                location / {
                    proxy_pass http://127.0.0.1:9090;
                }
            }
            """
        )
        nested_layout = application_location().replace(
            "        proxy_set_header Host $host;",
            "        if ($request_method = POST) {\n"
            "            return 405;\n"
            "        }\n"
            "        proxy_set_header Host $host;",
        )

        patched = PATCHER.patch_text(second_root_location)
        self.assertEqual(
            patched.count("proxy_set_header Laimory-Client-IP $remote_addr;"), 1
        )
        with self.assertRaises(PATCHER.LayoutError):
            PATCHER.patch_text(nested_layout)

    def test_rejects_proxy_and_header_split_across_locations(self) -> None:
        source = textwrap.dedent(
            """\
            server {
                location / {
                    proxy_pass http://127.0.0.1:8080;
                    proxy_set_header Host $host;
                    proxy_set_header X-Forwarded-Proto $scheme;
                }
                location /other {
                    proxy_set_header Laimory-Client-IP $remote_addr;
                }
            }
            """
        )

        with self.assertRaises(PATCHER.LayoutError):
            PATCHER.validate_text(source)

    def test_rejects_missing_forwarded_proto_and_unbalanced_layout(self) -> None:
        missing_forwarded_proto = application_location().replace(
            "        proxy_set_header X-Forwarded-Proto $scheme;\n", ""
        )
        unbalanced = application_location()[:-2]

        for source in (missing_forwarded_proto, unbalanced):
            with self.subTest(source=source):
                with self.assertRaises(PATCHER.LayoutError):
                    PATCHER.patch_text(source)

    def test_rejects_location_whose_parent_is_not_server(self) -> None:
        source = application_location().replace("server {", "http {")

        with self.assertRaises(PATCHER.LayoutError):
            PATCHER.patch_text(source)

    def test_runbook_restores_every_post_mutation_failure(self) -> None:
        readme = (WAS_DIR / "README.md").read_text()
        runbook = readme[
            readme.index("restore_and_fail() {") : readme.index('echo "backup=$BACKUP"')
        ]

        for guarded_failure in (
            '|| restore_and_fail "trusted-edge semantic post-check failed"',
            'nginx -t || restore_and_fail "nginx config test failed"',
            'systemctl reload nginx || restore_and_fail "nginx reload failed"',
            '|| restore_and_fail "effective nginx config dump failed"',
            '|| restore_and_fail "effective nginx semantic post-check failed"',
        ):
            with self.subTest(guarded_failure=guarded_failure):
                self.assertIn(guarded_failure, runbook)
        self.assertLess(runbook.index('mv "$TMP" "$SITE"'), runbook.index("--check \"$SITE\""))

        site_runbook = readme[
            readme.index('SITE=/etc/nginx/sites-available/laimory') :
            readme.index('echo "backup=$BACKUP"')
        ]
        self.assertIn('[[ -f "$SITE" && ! -L "$SITE" ]]', site_runbook)
        self.assertLess(
            site_runbook.index('[[ -f "$SITE" && ! -L "$SITE" ]]'),
            site_runbook.index('mv "$TMP" "$SITE"'),
        )

    def test_embedded_ssm_shell_is_valid_bash(self) -> None:
        readme = (WAS_DIR / "README.md").read_text()
        start = readme.index("sudo bash <<'ROOT'\n")
        end = readme.index("\nROOT\n", start) + len("\nROOT\n")

        result = subprocess.run(
            ["bash", "-n"],
            input=readme[start:end],
            text=True,
            capture_output=True,
            check=False,
        )

        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
