"""Run the dummy entry points against real Git trees and simulated hosted responses."""
from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock
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
        for role, authenticated in (("admit", 3), ("signer", 9), ("publisher", 9)):
            with self.subTest(role=role):
                stage = self.root.parent / role
                output = self.root.parent / (role + ".output")
                command = ["admit"] if role == "admit" else ["prepare", role, "--stage", str(stage)]
                status, stdout, stderr, calls = self.run_command(command, data, GITHUB_OUTPUT=str(output))
                self.assertEqual((status, stderr), (0, ""))
                self.assertRegex(stdout, rf"^PASS: READINESS_METADATA role={role} reads=12 elapsed_ms=\d+\n$")
                self.assertEqual(sum(call.kwargs["authenticated"] for call in calls), authenticated)
                self.assertEqual(len(calls), 12)
                self.assertTrue(all(call.kwargs["timeout"] == 5 and call.kwargs["limit"] == 1024 * 1024
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
            ("GITHUB_WORKFLOW_REF", "wrong.yml", "REHEARSAL_PROTECTED_WORKFLOW"),
            ("GITHUB_RUN_ID", "1/2", "READINESS_RUN_ID"),
        ):
            with self.subTest(field=field):
                self.assertEqual(self.run_command(["admit"], {}, **{field: value}),
                                 (1, "", f"FAIL: {failure}\n", []))
        with mock.patch.object(request, "MERGE_ENABLED", True):
            self.assertEqual(self.run_command(["admit"], {}), (1, "", "FAIL: READINESS_PRODUCTION_DISARMED\n", []))

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
