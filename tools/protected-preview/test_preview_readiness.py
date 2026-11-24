"""Run the dummy entry points against real Git trees and simulated hosted responses."""
from contextlib import redirect_stderr, redirect_stdout
from email.message import Message
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock
from urllib.error import HTTPError
from urllib.parse import urlencode

import handoff
import manifest_request as request
import preview_readiness as readiness
import release_ops as ops
from test_handoff import attested_subject
from test_manifest_rehearsal import PR_NUMBER, RUN_ID, RequestFixture


class Readiness(RequestFixture, unittest.TestCase):
    path = readiness.DUMMY_PATH
    branch = readiness.HEAD_BRANCH
    workflow = readiness.WORKFLOW
    review_step = readiness.REVIEW_STEP
    canonical = staticmethod(readiness.canonical_dummy)
    pr_cli = (readiness.__file__, "pr-check")

    def routed_response(self):
        data = self.push_metadata()
        data["rules/branches/master"] = [item | {"ruleset_id": request.RULESET_ID} for item in (
            {"type": "non_fast_forward"},
            {"type": "pull_request", "parameters": {"required_review_thread_resolution": True}},
            {"type": "required_status_checks", "parameters": {
                "strict_required_status_checks_policy": True, "do_not_enforce_on_create": False,
                "required_status_checks": [{"context": name} for name in request.REQUIRED],
            }},
        )]
        self.production_runs = (f"actions/workflows/{request.WORKFLOW_ID}/runs?event=push"
                                f"&head_sha={self.squash}&per_page=2")
        data[self.production_runs] = {"total_count": 0, "workflow_runs": []}
        for ref in (f"refs/pull/{PR_NUMBER}/merge", "refs/heads/" + self.branch):
            query = urlencode({"ref": ref, "per_page": 1})
            data[f"actions/caches?{query}"] = {"total_count": 0, "actions_caches": []}
        return data

    def run_command(self, argv, response, **env):
        def api(path, **options):
            answer = response[path]
            if isinstance(answer, Exception):
                raise answer
            return answer

        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr), mock.patch.object(ops, "api", side_effect=api) as calls:
            status = readiness.main(argv, environ=self.push_env(**env), root=self.root)
        return status, stdout.getvalue(), stderr.getvalue(), calls.call_args_list

    def test_real_future_request_pr_cli_accepts_only_the_canonical_single_file(self):
        self.request()
        passed = self.run_pr(shallow=True)
        self.assertEqual((passed.returncode, passed.stdout, passed.stderr),
                         (0, "PASS: PREVIEW_READINESS_REVIEW\n", ""))
        self.request(extra=True)
        failed = self.run_pr()
        self.assertEqual((failed.returncode, failed.stderr), (1, "FAIL: REHEARSAL_CHANGED_PATHS\n"))

    def test_admit_and_both_reusable_roles_exercise_the_real_router(self):
        self.request()
        data = self.routed_response()
        expected_routes = [
            "branches/master", "rules/branches/master", self.production_runs,
            f"commits/{self.squash}/pulls?per_page=2", f"pulls/{PR_NUMBER}", f"git/commits/{self.head}",
            self.ci_run_path, self.jobs_path,
            self.jobs_path.split("/attempts/", 1)[0] + "/artifacts?per_page=1",
            f"actions/caches?{urlencode({'ref': f'refs/pull/{PR_NUMBER}/merge', 'per_page': 1})}",
            f"actions/caches?{urlencode({'ref': 'refs/heads/' + self.branch, 'per_page': 1})}",
            "branches/master",
        ]
        for role in ("admit", "signer", "publisher"):
            with self.subTest(role=role):
                stage = self.root.parent / role
                output = self.root.parent / (role + ".output")
                command = ["admit"] if role == "admit" else ["prepare", role, "--stage", str(stage)]
                status, stdout, stderr, calls = self.run_command(command, data, GITHUB_OUTPUT=str(output))
                self.assertEqual((status, stderr), (0, ""))
                self.assertRegex(stdout, rf"^PASS: READINESS_METADATA role={role} reads=12 elapsed_ms=\d+\n$")
                self.assertEqual([call.args[0] for call in calls], expected_routes)
                self.assertTrue(all(call.kwargs["authenticated"] is True and call.kwargs["timeout"] == 5
                                    and call.kwargs["limit"] == 1024 * 1024
                                    and "method" not in call.kwargs for call in calls))
                if role != "admit":
                    expected = readiness.fixture_bytes(role)
                    self.assertEqual((stage / readiness.fixture_name(role)).read_bytes(), expected)
                    self.assertEqual(output.read_text(), f"digest=sha256:{ops.digest(expected)}\n")
                else:
                    self.assertFalse(stage.exists())
                    self.assertFalse(output.exists())

    def test_wrong_push_scope_and_armed_production_stop_before_any_read(self):
        self.request()
        self.routed_response()
        for field, value, failure in (
            ("GITHUB_RUN_ATTEMPT", "2", "REHEARSAL_RUNNER_SCOPE"),
            ("GITHUB_ACTOR_ID", "1", "REHEARSAL_RUNNER_ACTOR"),
            ("GITHUB_REF_PROTECTED", "false", "REHEARSAL_PROTECTED_WORKFLOW"),
            ("GITHUB_REF", "refs/heads/other", "REHEARSAL_PROTECTED_WORKFLOW"),
            ("GITHUB_WORKFLOW_REF", "wrong.yml", "REHEARSAL_PROTECTED_WORKFLOW"),
            ("GITHUB_RUN_ID", "1/2", "READINESS_RUN_ID"),
        ):
            with self.subTest(field=field):
                self.assertEqual(self.run_command(["admit"], {}, **{field: value}),
                                 (1, "", f"FAIL: {failure}\n", []))
        with mock.patch.object(request, "MERGE_ENABLED", True):
            self.assertEqual(self.run_command(["admit"], {}), (1, "", "FAIL: READINESS_PRODUCTION_DISARMED\n", []))

    def test_invalid_role_fails_before_reads(self):
        with mock.patch.object(ops, "api") as api, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            readiness.main(["prepare", "other", "--stage", str(self.root / "stage")],
                           environ=self.env, root=self.root)
        api.assert_not_called()

    def test_consumed_requests_cannot_start_the_v3_rehearsal(self):
        for version, suffix in ((1, ""), (2, "-v2")):
            with self.subTest(version=version):
                self.path = f".github/release-requests/_protected-preview-readiness-probe{suffix}.json"
                self.branch = f"release-request/dummy-preview-readiness{suffix}"
                record = json.loads(readiness.canonical_dummy(self.base)) | {"format_version": version}
                self.request(content=(json.dumps(record, indent=2, sort_keys=True) + "\n").encode())
                failed = self.run_pr()
                self.assertEqual((failed.returncode, failed.stderr), (1, "FAIL: REHEARSAL_PR_REF\n"))
                self.push_metadata()
                self.assertEqual(self.run_command(["admit"], {}), (1, "", "FAIL: REHEARSAL_CHANGED_PATHS\n", []))
                self.git("reset", "--hard", self.base)

    def test_v3_request_replay_fails_even_if_the_file_was_deleted(self):
        self.request()
        self.push_metadata()
        self.git("rm", self.path)
        self.git("commit", "-qm", "Remove consumed synthetic request")
        self.base = self.git("rev-parse", "HEAD")
        self.request()
        self.push_metadata()
        self.assertEqual(self.run_command(["admit"], {}), (1, "", "FAIL: REHEARSAL_PUSH_HISTORY\n", []))

    def test_rate_limit_competing_production_or_failed_review_yields_no_subject(self):
        self.request()
        data = self.routed_response()
        stage = self.root.parent / "blocked"
        output = self.root.parent / "digest"
        command = ["prepare", "signer", "--stage", str(stage)]
        bad_jobs = {"jobs": [job | {"conclusion": "failure"} for job in data[self.jobs_path]["jobs"]],
                    "total_count": data[self.jobs_path]["total_count"]}
        for replacement, failure in (
            ({"branches/master": ops.ReleaseError("API_HTTP_429")}, "API_HTTP_429"),
            ({self.production_runs: {"total_count": 1, "workflow_runs": [{"id": 3}]}}, "READINESS_PRODUCTION_RUN"),
            ({self.jobs_path: bad_jobs}, "REHEARSAL_REQUIRED_JOBS"),
        ):
            with self.subTest(failure=failure):
                status, stdout, stderr, _ = self.run_command(command, data | replacement, GITHUB_OUTPUT=str(output))
                self.assertEqual((status, stdout, stderr), (1, "", f"FAIL: {failure}\n"))
                self.assertFalse(stage.exists())
                self.assertFalse(output.exists())

    def test_http_failures_observe_only_bounded_facts_and_stop_before_attestation(self):
        self.request()
        data = self.routed_response()
        stage = self.root.parent / "blocked"
        output = self.root.parent / "digest"
        command = ["prepare", "publisher", "--stage", str(stage)]
        prefix = f"https://api.github.com/repos/{ops.REPO}/"
        for endpoint, name, headers, suffix in (
            ("rules/branches/master", "branch_rules",
             [("X-RateLimit-Limit", "1000"), ("X-RateLimit-Remaining", "0"), ("X-RateLimit-Reset", "1800000000")],
             " limit=1000 remaining=0 reset_epoch=1800000000"),
            (self.production_runs, "workflow_runs",
             [("X-RateLimit-Remaining", "947"), ("Retry-After", "77")], " remaining=947 retry_after_seconds=77"),
            (f"commits/{self.squash}/pulls?per_page=2", "commit_pull_requests",
             [("X-Accepted-GitHub-Permissions", "opaque-scope-fixture")], ""),
            (self.jobs_path, "review_jobs",
             [("X-RateLimit-Limit", "9" * 16), ("X-RateLimit-Remaining", "0"), ("X-RateLimit-Remaining", "7"),
              ("X-RateLimit-Reset", "٠"), ("Retry-After", "86401"), ("Location", "https://untrusted.invalid")], ""),
        ):
            with self.subTest(endpoint=name):
                seen = []
                error_body = mock.Mock()
                response_headers = Message()
                for key, value in headers:
                    response_headers[key] = value

                def open_request(req, *, timeout):
                    self.assertEqual(timeout, 5)
                    self.assertEqual(req.get_method(), "GET")
                    self.assertEqual(req.get_header("Authorization"), "Bearer synthetic-fixture")
                    route = req.full_url.removeprefix(prefix)
                    seen.append(route)
                    if route == endpoint:
                        raise HTTPError("https://opaque.invalid/private", 403, "opaque failure reason",
                                        response_headers, error_body)
                    return io.BytesIO(json.dumps(data[route]).encode())

                stdout, stderr = io.StringIO(), io.StringIO()
                with mock.patch.dict(os.environ, {"GH_TOKEN": "synthetic-fixture"}, clear=True), \
                     mock.patch.object(ops.urllib.request, "build_opener") as opener, \
                     redirect_stdout(stdout), redirect_stderr(stderr):
                    opener.return_value.open.side_effect = open_request
                    code = readiness.main(command, environ=self.push_env(GITHUB_OUTPUT=str(output)), root=self.root)
                self.assertEqual((code, stdout.getvalue(), stderr.getvalue()), (
                    1, "", f"READINESS_HTTP_ERROR category={name} auth=job_token status=403{suffix}\nFAIL: API_HTTP_403\n",
                ))
                self.assertEqual(seen[-1], endpoint)
                self.assertEqual(len(seen), len(set(seen)))
                error_body.read.assert_not_called()
                self.assertFalse(stage.exists())
                self.assertFalse(output.exists())

    def verify_fixture(self, role, change=None):
        stage = self.root.parent / role
        stage.mkdir()
        payload = readiness.fixture_bytes(role)
        fixture = stage / readiness.fixture_name(role)
        fixture.write_bytes(payload)
        proof = stage / "proof.json"
        proof.write_text("substituted bundle")
        scope = handoff.Scope(RUN_ID, 1, self.squash, ops.digest(payload), readiness.POLICY)
        signed = attested_subject("ciphertext" if role == "signer" else "recipient", scope)
        verification = signed["verificationResult"]
        verification["statement"].update(
            predicateType=readiness.PREDICATE, predicate={"role": role},
            subject=[{"name": fixture.name, "digest": {"sha256": ops.file_sha(fixture)}}],
        )
        if change is not None:
            verification["signature"]["certificate"].update(change)
        actual_command = ops.command
        with mock.patch.object(readiness, "install") as install, \
             mock.patch.object(ops, "api") as api, \
             mock.patch.object(ops, "command") as command:
            def run(argv, **options):
                if Path(argv[0]).name == "gh":
                    self.assertEqual(argv[0], stage / "tools" / "gh")
                    self.assertEqual(argv[argv.index("--signer-workflow") + 1],
                                     f"{ops.REPO}/{readiness.ROLE_WORKFLOWS[role]}")
                    self.assertEqual(argv[argv.index("--predicate-type") + 1], readiness.PREDICATE)
                    self.assertEqual(options["timeout"], 90)
                    return SimpleNamespace(stdout=json.dumps([signed]).encode())
                return actual_command(argv, **options)

            command.side_effect = run
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                result = readiness.main(["verify", role, "--stage", str(stage), "--proof", str(proof)],
                                        environ=self.push_env(), root=self.root)
            api.assert_not_called()
            install.assert_called_once_with(stage / "tools", "gh")
        return result, out.getvalue(), err.getvalue()

    def test_real_verifier_compares_claims_for_each_distinct_reusable_workflow(self):
        self.request()
        self.push_metadata()
        for role in readiness.ROLE_WORKFLOWS:
            with self.subTest(role=role):
                self.assertEqual(self.verify_fixture(role), (
                    0, f"PASS: READINESS_OIDC role={role} rejected=workflow,attempt,subject\n", "",
                ))

    def test_verifier_rejects_replayed_attempt_and_incorrect_push_claim(self):
        self.request()
        self.push_metadata()
        for role, change in (("signer", {"buildTrigger": "workflow_dispatch"}),
                             ("publisher", {"runInvocationURI": f"https://github.com/{ops.REPO}/actions/runs/{RUN_ID}/attempts/2"})):
            with self.subTest(role=role):
                self.assertEqual(self.verify_fixture(role, change), (1, "", "FAIL: HANDOFF_ATTESTATION_ORIGIN\n"))


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
