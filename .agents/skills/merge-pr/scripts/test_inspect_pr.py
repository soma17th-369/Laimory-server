#!/usr/bin/env python3

import copy
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import inspect_pr


def ready_snapshot():
    return {
        "local": {
            "branch": "feat/example",
            "head_sha": "abc123",
            "dirty_paths": [],
        },
        "pr": {
            "state": "OPEN",
            "isDraft": False,
            "baseRefName": "dev",
            "headRefName": "feat/example",
            "headRefOid": "abc123",
            "reviewDecision": None,
            "mergeable": "MERGEABLE",
            "mergeStateStatus": "CLEAN",
            "statusCheckRollup": [
                {
                    "__typename": "CheckRun",
                    "name": "build",
                    "status": "COMPLETED",
                    "conclusion": "SUCCESS",
                    "detailsUrl": "https://example.test/check/1",
                }
            ],
        },
        "review_threads": [],
    }


class EvaluateTest(unittest.TestCase):
    def test_ready(self):
        result = inspect_pr.evaluate(ready_snapshot())
        self.assertEqual("ready", result["status"])
        self.assertEqual([], result["blockers"])
        self.assertEqual([], result["waiting"])

    def test_missing_required_build_waits(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["statusCheckRollup"] = []
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("waiting", result["status"])
        self.assertIn("required check 'build' has not appeared", result["waiting"])

    def test_pending_check_waits(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["statusCheckRollup"][0].update(
            {"status": "IN_PROGRESS", "conclusion": None}
        )
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("waiting", result["status"])

    def test_failed_check_blocks(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["statusCheckRollup"][0]["conclusion"] = "FAILURE"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("check 'build' concluded FAILURE", result["blockers"])

    def test_required_build_must_be_success(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["statusCheckRollup"][0]["conclusion"] = "SKIPPED"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("required check 'build' concluded SKIPPED, not SUCCESS", result["blockers"])

    def test_review_and_merge_blockers_are_all_reported(self):
        snapshot = ready_snapshot()
        snapshot["local"]["dirty_paths"] = ["notes.txt"]
        snapshot["pr"]["isDraft"] = True
        snapshot["pr"]["baseRefName"] = "main"
        snapshot["pr"]["reviewDecision"] = "CHANGES_REQUESTED"
        snapshot["pr"]["mergeable"] = "CONFLICTING"
        snapshot["review_threads"] = [{"isResolved": False}]
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertGreaterEqual(len(result["blockers"]), 6)

    def test_draft_blocks_if_promotion_did_not_happen(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["isDraft"] = True
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("PR is a draft", result["blockers"])

    def test_local_and_remote_head_mismatch_blocks(self):
        snapshot = ready_snapshot()
        snapshot["local"]["head_sha"] = "local456"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("local HEAD does not match the GitHub PR head", result["blockers"])

    def test_expected_head_detects_race(self):
        result = inspect_pr.evaluate(ready_snapshot(), expected_head="other456")
        self.assertEqual("blocked", result["status"])
        self.assertTrue(any("PR head changed" in item for item in result["blockers"]))

    def test_unknown_mergeability_waits(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeable"] = "UNKNOWN"
        snapshot["pr"]["mergeStateStatus"] = "UNKNOWN"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("waiting", result["status"])

    def test_blocked_merge_state_waits_when_check_is_pending(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeStateStatus"] = "BLOCKED"
        snapshot["pr"]["statusCheckRollup"][0].update(
            {"status": "IN_PROGRESS", "conclusion": None}
        )
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("waiting", result["status"])

    def test_blocked_merge_state_blocks_after_checks_pass(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeStateStatus"] = "BLOCKED"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("GitHub reports mergeStateStatus=BLOCKED", result["blockers"])

    def test_behind_merge_state_blocks(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeStateStatus"] = "BEHIND"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("PR head is behind the base branch", result["blockers"])

    def test_unstable_merge_state_waits_after_reported_checks_pass(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeStateStatus"] = "UNSTABLE"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("waiting", result["status"])
        self.assertIn(
            "GitHub reports mergeStateStatus=UNSTABLE", result["waiting"]
        )

    def test_has_hooks_merge_state_is_ready(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["mergeStateStatus"] = "HAS_HOOKS"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("ready", result["status"])

    def test_review_required_blocks(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["reviewDecision"] = "REVIEW_REQUIRED"
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("blocked", result["status"])
        self.assertIn("review decision is REVIEW_REQUIRED", result["blockers"])

    def test_status_context_success_is_accepted(self):
        snapshot = ready_snapshot()
        snapshot["pr"]["statusCheckRollup"] = [
            {
                "__typename": "StatusContext",
                "context": "build",
                "state": "SUCCESS",
                "targetUrl": "https://example.test/status/1",
            }
        ]
        result = inspect_pr.evaluate(snapshot)
        self.assertEqual("ready", result["status"])

    def test_input_is_not_mutated(self):
        snapshot = ready_snapshot()
        before = copy.deepcopy(snapshot)
        inspect_pr.evaluate(snapshot)
        self.assertEqual(before, snapshot)


class LocalStateTest(unittest.TestCase):
    @mock.patch.object(inspect_pr, "run")
    @mock.patch.object(inspect_pr, "git")
    def test_preserves_leading_dot_in_first_porcelain_path(self, git_mock, run_mock):
        git_mock.side_effect = ["feat/example", "abc123"]
        run_mock.return_value = " D .agents/branch.md\n?? new-file.txt\n"

        state = inspect_pr.local_state(Path(tempfile.gettempdir()))

        self.assertEqual([".agents/branch.md", "new-file.txt"], state["dirty_paths"])


class SkillSafetyTest(unittest.TestCase):
    def test_codex_metadata_disables_implicit_invocation(self):
        skill_root = Path(__file__).resolve().parents[1]
        openai_config = (skill_root / "agents" / "openai.yaml").read_text(
            encoding="utf-8"
        )

        self.assertIn("allow_implicit_invocation: false", openai_config)


if __name__ == "__main__":
    unittest.main()
