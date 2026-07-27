#!/usr/bin/env python3

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import upsert_digest_comment as digest


SHA = "a" * 40
OTHER_SHA = "b" * 40
TARGET = digest.PullRequestTarget("soma17th-369", "Laimory-server", 208)
LOGIN = "suhyun444"


def valid_body(sha=SHA):
    return "\n".join(
        [
            digest.MARKER,
            "---",
            "schema_version: 1",
            "status: merge-candidate",
            f"implementation_head_sha: {sha}",
            "---",
            "",
            "# Digest",
            "",
        ]
    )


def comment(comment_id, *, body=None, login=LOGIN, url=None):
    return {
        "id": comment_id,
        "body": valid_body() if body is None else body,
        "user": {"login": login},
        "html_url": (
            f"https://github.test/comment/{comment_id}" if url is None else url
        ),
    }


def ready_client(*, before_comments=None, after_comments=None):
    client = mock.Mock(spec=digest.GitHubClient)
    client.current_login.side_effect = [LOGIN, LOGIN]
    client.pull_request_head.side_effect = [SHA, SHA]
    client.list_comments.side_effect = [
        [] if before_comments is None else before_comments,
        [comment(11)] if after_comments is None else after_comments,
    ]
    client.create_comment.return_value = {"id": 11}
    client.update_comment.return_value = {"id": 11}
    return client


class BodyValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.base = Path(self.temp_dir.name)
        self.root = self.base / "repository"
        self.root.mkdir()

    def write(self, path, body):
        path.write_bytes(body.encode("utf-8"))
        return path

    def test_valid_repository_outside_body(self):
        body_path = self.write(self.base / "digest.md", valid_body())

        resolved, body = digest.read_and_validate_body(
            str(body_path.resolve()), self.root.resolve(), SHA
        )

        self.assertEqual(body_path.resolve(), resolved)
        self.assertEqual(valid_body(), body)

    def test_repository_body_is_blocked(self):
        body_path = self.write(self.root / "digest.md", valid_body())

        with self.assertRaises(digest.SafetyBlock):
            digest.read_and_validate_body(
                str(body_path.resolve()), self.root.resolve(), SHA
            )

    def test_alternate_case_repository_body_is_physically_blocked(self):
        body_path = self.write(self.root / "digest.md", valid_body())
        alternate_root = self.root.with_name(self.root.name.swapcase())
        alternate_body = alternate_root / body_path.name
        if not alternate_body.exists():
            self.skipTest("filesystem is case-sensitive")

        self.assertNotEqual(str(self.root), str(alternate_root))
        self.assertTrue(alternate_body.samefile(body_path))
        with self.assertRaises(digest.SafetyBlock):
            digest.read_and_validate_body(
                str(alternate_body.absolute()), self.root.resolve(), SHA
            )

    def test_physical_containment_check_fails_closed_on_filesystem_error(self):
        body_path = self.write(self.base / "digest.md", valid_body())

        with mock.patch.object(
            Path, "samefile", side_effect=OSError("unavailable")
        ):
            with self.assertRaises(digest.SafetyBlock):
                digest.read_and_validate_body(
                    str(body_path.resolve()), self.root.resolve(), SHA
                )

    def test_relative_and_missing_paths_are_operational_errors(self):
        with self.assertRaises(digest.OperationalError):
            digest.read_and_validate_body("digest.md", self.root.resolve(), SHA)
        with self.assertRaises(digest.OperationalError):
            digest.read_and_validate_body(
                str((self.base / "missing.md").resolve()),
                self.root.resolve(),
                SHA,
            )

    def test_marker_must_be_first_and_unique(self):
        cases = [
            "# heading\n" + valid_body(),
            valid_body() + digest.MARKER,
            valid_body().replace(digest.MARKER, ""),
        ]
        for index, body in enumerate(cases):
            with self.subTest(index=index):
                body_path = self.write(self.base / f"digest-{index}.md", body)
                with self.assertRaises(digest.SafetyBlock):
                    digest.read_and_validate_body(
                        str(body_path.resolve()), self.root.resolve(), SHA
                    )

    def test_implementation_head_line_must_match_exactly_once(self):
        cases = [
            valid_body(OTHER_SHA),
            valid_body() + f"implementation_head_sha: {SHA}\n",
            valid_body().replace("implementation_head_sha:", "head_sha:"),
        ]
        for index, body in enumerate(cases):
            with self.subTest(index=index):
                body_path = self.write(self.base / f"sha-{index}.md", body)
                with self.assertRaises(digest.SafetyBlock):
                    digest.read_and_validate_body(
                        str(body_path.resolve()), self.root.resolve(), SHA
                    )


class TargetParsingTest(unittest.TestCase):
    @mock.patch.object(
        digest,
        "repository_from_origin",
        return_value=("soma17th-369", "Laimory-server"),
    )
    def test_numeric_pr_uses_current_origin_repository(self, origin_mock):
        target = digest.parse_pr_target("208", Path("/tmp/repository"))

        self.assertEqual(TARGET, target)
        origin_mock.assert_called_once_with(Path("/tmp/repository"))

    def test_pr_url_supplies_repository_and_number(self):
        target = digest.parse_pr_target(
            "https://github.com/soma17th-369/Laimory-server/pull/208",
            Path("/tmp/repository"),
        )

        self.assertEqual(TARGET, target)

    def test_non_pr_targets_and_non_hex_heads_are_rejected(self):
        with self.assertRaises(digest.OperationalError):
            digest.parse_pr_target(
                "https://github.com/soma17th-369/Laimory-server/issues/208",
                Path("/tmp/repository"),
            )
        with self.assertRaises(digest.OperationalError):
            digest.normalized_expected_head("not-a-sha")


class GitHubClientTest(unittest.TestCase):
    def test_comment_listing_uses_full_pagination_and_api_version(self):
        calls = []

        def runner(command, cwd, input_text):
            calls.append((command, cwd, input_text))
            return json.dumps([[comment(1, login="other")], [comment(2)]])

        client = digest.GitHubClient(Path("/tmp/repository"), runner)

        result = client.list_comments(TARGET)

        self.assertEqual([1, 2], [item["id"] for item in result])
        command, _, input_text = calls[0]
        self.assertIn("--paginate", command)
        self.assertIn("--slurp", command)
        self.assertIn(digest.API_VERSION_HEADER, command)
        self.assertTrue(command[-1].endswith("comments?per_page=100"))
        self.assertIsNone(input_text)

    def test_create_sends_body_via_stdin_to_exact_issue_comment_endpoint(self):
        calls = []
        body = valid_body()

        def runner(command, cwd, input_text):
            calls.append((command, input_text))
            return json.dumps({"id": 5})

        client = digest.GitHubClient(Path("/tmp/repository"), runner)

        self.assertEqual({"id": 5}, client.create_comment(TARGET, body))

        command, input_text = calls[0]
        self.assertIn("POST", command)
        self.assertEqual(
            "/repos/soma17th-369/Laimory-server/issues/208/comments",
            command[-1],
        )
        self.assertNotIn(body, command)
        self.assertEqual({"body": body}, json.loads(input_text))

    def test_update_uses_exact_rest_comment_id(self):
        calls = []

        def runner(command, cwd, input_text):
            calls.append((command, input_text))
            return json.dumps({"id": 17})

        client = digest.GitHubClient(Path("/tmp/repository"), runner)

        client.update_comment(TARGET, 17, valid_body())

        command, _ = calls[0]
        self.assertIn("PATCH", command)
        self.assertEqual(
            "/repos/soma17th-369/Laimory-server/issues/comments/17",
            command[-1],
        )

    def test_api_failure_is_single_attempt_and_does_not_forward_raw_detail(self):
        calls = []

        def runner(command, cwd, input_text):
            calls.append(command)
            raise digest.CommandFailure("raw response containing digest body")

        client = digest.GitHubClient(Path("/tmp/repository"), runner)

        with self.assertRaises(digest.OperationalError) as caught:
            client.current_login()

        self.assertEqual(1, len(calls))
        self.assertNotIn("digest body", str(caught.exception))


class UpsertTest(unittest.TestCase):
    def test_zero_self_marker_comments_creates_and_re_reads(self):
        client = ready_client()

        result = digest.upsert(
            client=client,
            target=TARGET,
            body=valid_body(),
            expected_head=SHA,
        )

        self.assertEqual("created", result["action"])
        self.assertEqual(11, result["comment_id"])
        self.assertEqual(SHA, result["implementation_head_sha"])
        client.create_comment.assert_called_once_with(TARGET, valid_body())
        client.update_comment.assert_not_called()

    def test_one_self_marker_comment_updates_exact_id(self):
        old = comment(11, body=digest.MARKER + "\nold body")
        client = ready_client(before_comments=[old])

        result = digest.upsert(
            client=client,
            target=TARGET,
            body=valid_body(),
            expected_head=SHA,
        )

        self.assertEqual("updated", result["action"])
        client.update_comment.assert_called_once_with(TARGET, 11, valid_body())
        client.create_comment.assert_not_called()

    def test_update_response_must_preserve_selected_comment_id(self):
        old = comment(11, body=digest.MARKER + "\nold body")
        client = ready_client(before_comments=[old])
        client.update_comment.return_value = {"id": 12}

        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

        client.update_comment.assert_called_once_with(TARGET, 11, valid_body())
        self.assertEqual(1, client.list_comments.call_count)

    def test_multiple_self_marker_comments_block_without_mutation(self):
        client = ready_client(before_comments=[comment(1), comment(2)])

        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

        client.create_comment.assert_not_called()
        client.update_comment.assert_not_called()
        client.pull_request_head.assert_not_called()

    def test_other_authors_marker_comments_do_not_select_an_update_target(self):
        other = comment(4, login="someone-else")
        client = ready_client(before_comments=[other])

        result = digest.upsert(
            client=client,
            target=TARGET,
            body=valid_body(),
            expected_head=SHA,
        )

        self.assertEqual("created", result["action"])
        client.create_comment.assert_called_once()
        client.update_comment.assert_not_called()

    def test_head_mismatch_before_mutation_blocks_all_writes(self):
        client = ready_client()
        client.pull_request_head.side_effect = [OTHER_SHA]

        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

        client.create_comment.assert_not_called()
        client.update_comment.assert_not_called()

    def test_head_change_after_mutation_is_detected(self):
        client = ready_client()
        client.pull_request_head.side_effect = [SHA, OTHER_SHA]

        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

        client.create_comment.assert_called_once()

    def test_post_read_duplicate_from_concurrent_writer_blocks(self):
        client = ready_client(after_comments=[comment(11), comment(12)])

        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

        client.create_comment.assert_called_once()
        client.update_comment.assert_not_called()

    def test_post_read_requires_exact_id_body_author_url_and_login(self):
        cases = {
            "id": [comment(12)],
            "body": [comment(11, body=valid_body() + "\nchanged")],
            "author": [comment(11, login="someone-else")],
            "url": [comment(11, url="")],
        }
        for label, after_comments in cases.items():
            with self.subTest(label=label):
                client = ready_client(after_comments=after_comments)
                with self.assertRaises(digest.SafetyBlock):
                    digest.upsert(
                        client=client,
                        target=TARGET,
                        body=valid_body(),
                        expected_head=SHA,
                    )

        client = ready_client()
        client.current_login.side_effect = [LOGIN, "different-user"]
        with self.assertRaises(digest.SafetyBlock):
            digest.upsert(
                client=client,
                target=TARGET,
                body=valid_body(),
                expected_head=SHA,
            )

    def test_verify_only_re_reads_same_comment_without_mutation(self):
        client = mock.Mock(spec=digest.GitHubClient)
        client.current_login.side_effect = [LOGIN, LOGIN]
        client.pull_request_head.return_value = SHA
        client.list_comments.return_value = [comment(11)]

        result = digest.verify_only(
            client=client,
            target=TARGET,
            body=valid_body(),
            expected_head=SHA,
            comment_id=11,
        )

        self.assertEqual("verified", result["action"])
        client.create_comment.assert_not_called()
        client.update_comment.assert_not_called()


class SkillContractTest(unittest.TestCase):
    def setUp(self):
        self.skill_root = Path(__file__).resolve().parents[1]

    def test_template_has_exact_first_line_marker_and_comment_snapshot_wording(self):
        template = (
            self.skill_root / "assets" / "digest-template.md"
        ).read_text(encoding="utf-8")

        self.assertEqual(digest.MARKER, template.splitlines()[0])
        self.assertEqual(1, template.count(digest.MARKER))
        self.assertIn("implementation_head_sha", template)
        self.assertNotIn("post-digest CI", template)
        self.assertNotIn("own digest commit", template)

    def test_skill_keeps_merge_gates_and_uses_helper_final_verification(self):
        skill = (self.skill_root / "SKILL.md").read_text(encoding="utf-8")

        self.assertIn("upsert_digest_comment.py", skill)
        self.assertIn("--verify-only", skill)
        self.assertIn("--comment-id", skill)
        self.assertIn("--expected-head <implementation_head_sha>", skill)
        self.assertIn("--match-head-commit <implementation_head_sha>", skill)
        self.assertIn("--squash", skill)
        self.assertIn("--delete-branch", skill)
        self.assertIn("single-writer", skill)
        self.assertNotIn("gh pr comment --edit-last", skill)
        self.assertNotIn("post-digest SHA", skill)
        self.assertNotIn("git add -- <digest-path>", skill)
        self.assertNotIn('git commit -m "docs: PR', skill)
        self.assertNotIn("git push origin HEAD", skill)

    def test_metadata_disables_implicit_invocation_and_mentions_comment(self):
        config = (
            self.skill_root / "agents" / "openai.yaml"
        ).read_text(encoding="utf-8")

        self.assertIn("allow_implicit_invocation: false", config)
        self.assertIn("comment", config.lower())


class MainExitContractTest(unittest.TestCase):
    @mock.patch.object(digest, "parse_args")
    @mock.patch.object(digest, "execute")
    def test_safety_block_is_exit_three_and_stderr_only(
        self, execute_mock, parse_args_mock
    ):
        parse_args_mock.return_value = mock.Mock()
        execute_mock.side_effect = digest.SafetyBlock("head mismatch")
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exit_code = digest.main([])

        self.assertEqual(digest.EXIT_BLOCKED, exit_code)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("blocked", json.loads(stderr.getvalue())["status"])

    @mock.patch.object(digest, "parse_args")
    @mock.patch.object(digest, "execute")
    def test_operational_error_is_exit_one_and_stderr_only(
        self, execute_mock, parse_args_mock
    ):
        parse_args_mock.return_value = mock.Mock()
        execute_mock.side_effect = digest.OperationalError("API failure")
        stdout = io.StringIO()
        stderr = io.StringIO()

        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exit_code = digest.main([])

        self.assertEqual(digest.EXIT_ERROR, exit_code)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("error", json.loads(stderr.getvalue())["status"])


if __name__ == "__main__":
    unittest.main()
