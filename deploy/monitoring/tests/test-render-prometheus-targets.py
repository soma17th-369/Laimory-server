#!/usr/bin/env python3

import json
from pathlib import Path
import subprocess
import tempfile
import unittest


MONITORING_DIR = Path(__file__).resolve().parents[1]
RENDERER = MONITORING_DIR / "scripts" / "render-prometheus-targets.py"


def valid_values() -> dict[str, str]:
    return {
        "dev_was_private_ip": "10.0.10.10",
        "monitoring_private_ip": "10.0.10.11",
        "dev_mysql_private_ip": "10.0.10.12",
        "redis_private_ip": "10.0.10.13",
        "elk_private_ip": "10.0.10.14",
        "dev_api_domain": "dev-api.example.com",
    }


class RenderPrometheusTargetsTest(unittest.TestCase):

    def run_renderer(
        self, values: object, output_dir: Path
    ) -> subprocess.CompletedProcess[str]:
        values_path = output_dir.parent / "values.json"
        values_path.write_text(json.dumps(values))
        return subprocess.run(
            [
                str(RENDERER),
                "--values",
                str(values_path),
                "--output-dir",
                str(output_dir),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_renders_exact_output_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "targets"

            result = self.run_renderer(valid_values(), output)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                {path.name for path in output.iterdir()},
                {"application.yml", "node.yml", "probe.yml"},
            )
            self.assertIn("10.0.10.10:9090", (output / "application.yml").read_text())
            self.assertIn("10.0.10.14:9100", (output / "node.yml").read_text())
            self.assertIn(
                "https://dev-api.example.com/status",
                (output / "probe.yml").read_text(),
            )

    def test_rejects_missing_extra_and_invalid_values(self) -> None:
        cases = []
        missing = valid_values()
        missing.pop("elk_private_ip")
        cases.append(missing)
        extra = valid_values()
        extra["unknown"] = "value"
        cases.append(extra)
        public_ip = valid_values()
        public_ip["redis_private_ip"] = "8.8.8.8"
        cases.append(public_ip)
        invalid_domain = valid_values()
        invalid_domain["dev_api_domain"] = "https://dev-api.example.com/status"
        cases.append(invalid_domain)

        for values in cases:
            with self.subTest(values=values), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                result = self.run_renderer(values, root / "targets")
                self.assertNotEqual(result.returncode, 0)

    def test_rejects_unresolved_template_and_unexpected_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "targets"
            output.mkdir()
            (output / "foreign.yml").write_text("sentinel")

            result = self.run_renderer(valid_values(), output)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((output / "foreign.yml").read_text(), "sentinel")


if __name__ == "__main__":
    unittest.main()
