"""Exercise the disarmed and protected preview entry with real Git trees."""
import copy
from email.message import Message
import io
import json
import os
import sys
import time
import unittest
from unittest import mock
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.response import addinfourl

import handoff
import manifest_request as request
import preview_ops as preview
import release_ops as ops
from test_manifest_rehearsal import CI_RUN_ID, PR_NUMBER, RUN_ID, RequestFixture

APPROVAL = "a" * 64


class ProtectedRequest(RequestFixture, unittest.TestCase):
    path = request.REQUEST_PATH
    branch = request.HEAD_BRANCH
    workflow = preview.WORKFLOW
    workflow_id = request.WORKFLOW_ID
    review_step = request.REVIEW_STEP
    canonical = staticmethod(request.canonical_request)
    pr_cli = (request.__file__,)

    def admitted_metadata(self):
        data = json.loads(json.dumps(self.push_metadata()))
        data[self.push_run_path]["workflow_runs"][0]["display_title"] = f"{preview.TAG} publish"
        data["rules/branches/master"] = [item | {"ruleset_id": request.RULESET_ID} for item in (
            {"type": "non_fast_forward"},
            {"type": "pull_request", "parameters": {"required_review_thread_resolution": True}},
            {"type": "required_status_checks", "parameters": {
                "strict_required_status_checks_policy": True, "do_not_enforce_on_create": False,
                "required_status_checks": [{"context": name} for name in request.REQUIRED],
            }},
        )]
        for ref in (f"refs/pull/{PR_NUMBER}/merge", "refs/heads/" + self.branch):
            query = urlencode({"ref": ref, "per_page": 1})
            data[f"actions/caches?{query}"] = {"total_count": 0, "actions_caches": []}
        data.update({
            "git/ref/heads/master": {"object": {"sha": self.squash}},
            f"git/ref/tags/{preview.TAG}": {"ref": f"refs/tags/{preview.TAG}",
                "object": {"sha": preview.SOURCE, "type": "commit",
                           "url": f"https://api.github.com/repos/{ops.REPO}/git/commits/{preview.SOURCE}"}},
            f"git/commits/{preview.SOURCE}": {"sha": preview.SOURCE, "tree": {"sha": preview.TREE}},
            "releases/latest": {"id": preview.STABLE_ID, "tag_name": preview.STABLE_TAG,
                                "draft": False, "prerelease": False, "assets": [
                                    {"name": preview.STABLE_APK_NAME, "state": "uploaded",
                                     "digest": "sha256:" + preview.STABLE_APK}]},
        })
        return data

    def run_admit(self, data, *, enabled=True, **env):
        context = self.push_env(PREVIEW_MODE="publish") | env
        with mock.patch.dict(os.environ, context, clear=True), mock.patch.object(preview, "ROOT", self.root), \
             mock.patch.object(request, "MERGE_ENABLED", enabled), mock.patch.object(ops, "install_deadline"), \
             mock.patch.object(sys, "argv", ["preview_ops.py", "admit"]), \
             mock.patch.object(ops, "api", side_effect=lambda path, **kw: data[path]) as read:
            preview.main()
        return read

    def test_real_draft_cli_checks_the_canonical_tree_on_a_depth_two_checkout(self):
        self.request()
        result = self.run_pr(shallow=True)
        self.assertEqual((result.returncode, result.stdout), (0, "PASS: PREVIEW_REQUEST_REVIEW_TREE\n"),
                         result.stderr)

    def test_second_workflow_file_fails_the_actual_draft_cli(self):
        self.request(extra=True)
        result = self.run_pr()
        self.assertEqual((result.returncode, result.stderr), (1, "FAIL: REHEARSAL_CHANGED_PATHS\n"))

    def test_production_bootstrap_has_no_runtime_override(self):
        self.request()
        self.push_metadata()
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_MERGE_DISARMED"):
            self.run_admit({}, enabled=False, MERGE_ENABLED="true", PREVIEW_MERGE_ENABLED="true")

    def test_actual_admission_reaches_only_bounded_metadata_and_exact_release_reads(self):
        self.request()
        read = self.run_admit(self.admitted_metadata())
        guarded = [call for call in read.call_args_list if "authenticated" in call.kwargs]
        self.assertEqual(len(guarded), 12)
        for call in guarded:
            self.assertEqual(call.kwargs, {
                "authenticated": True, "timeout": 5, "limit": 1024 * 1024, "on_http_error": None,
            })
        self.assertTrue(all("method" not in call.kwargs for call in read.call_args_list))

    def test_protected_request_checks_rules_and_unique_pr_with_job_token(self):
        self.request()
        data = self.admitted_metadata()
        with mock.patch.object(request, "MERGE_ENABLED", True), \
             mock.patch.object(ops, "api", side_effect=lambda path, **kw: data[path]) as read:
            request.verify(self.squash, root=self.root, environ=self.push_env())
        routes = [call.args[0] for call in read.call_args_list]
        self.assertEqual(len(routes), 12)
        self.assertEqual(routes[:6], [
            "branches/master", "rules/branches/master", self.push_run_path,
            f"commits/{self.squash}/pulls?per_page=2", f"pulls/{PR_NUMBER}", f"git/commits/{self.head}",
        ])
        self.assertIn(self.jobs_path, routes)
        self.assertTrue(all(call.kwargs["authenticated"] is True for call in read.call_args_list))
        self.assertTrue(all(call.kwargs["timeout"] == 5 and call.kwargs["limit"] == 1024 * 1024
                            for call in read.call_args_list))

    def test_missing_or_wrong_request_body_prevents_build_admission(self):
        self.request(content=self.canonical(self.base).replace(ops.CERTIFICATE.encode(), b"0" * 64))
        data = self.admitted_metadata()
        with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_REQUEST_BYTES"):
            self.run_admit(data)
        self.squash = self.base
        self.push_metadata()
        with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_PUSH_HISTORY"):
            self.run_admit({})

    def test_wrong_origin_and_replay_fail_the_actual_entry(self):
        self.request()
        data = self.admitted_metadata()
        for changes in (
            {"GITHUB_REF": "refs/heads/review/untrusted"}, {"GITHUB_ACTOR_ID": "10"},
            {"GITHUB_RUN_ATTEMPT": "2"}, {"GITHUB_TRIGGERING_ACTOR": "not-the-owner"},
            {"GITHUB_REF_PROTECTED": "false"}, {"GITHUB_RUN_ID": "1234"},
        ):
            with self.subTest(changes=changes), self.assertRaises(ops.ReleaseError):
                self.run_admit(data, **changes)
        data[self.push_run_path]["total_count"] = 2
        with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_RUN_UNIQUE"):
            self.run_admit(data)

    def test_deleted_production_request_cannot_be_readded(self):
        self.request()
        self.git("update-ref", "HEAD", self.head)
        self.git("rm", self.path)
        self.git("commit", "-q", "-m", "remove the request")
        self.base = self.git("rev-parse", "HEAD")
        self.request()
        self.push_metadata()
        with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_PUSH_HISTORY"):
            self.run_admit({})

    def test_inactive_protection_blocks_even_a_canonical_request(self):
        self.request()
        data = self.admitted_metadata()
        data["rules/branches/master"] = []
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_REQUEST_PROTECTION"):
            self.run_admit(data)

    def test_forged_merger_head_tree_and_missing_or_failed_checks_are_rejected(self):
        self.request()
        metadata = self.admitted_metadata()
        for path, keys, value, error in (
            (f"pulls/{PR_NUMBER}", ("merged_by", "id"), 11, "MERGER"),
            (f"pulls/{PR_NUMBER}", ("head", "ref"), "release-request/elsewhere", "PR_REF"),
            (f"git/commits/{self.head}", ("tree", "sha"), "c" * 40, "REVIEW_TREE"),
            (self.ci_run_path, ("total_count",), 0, "RUN_UNIQUE"),
            (f"actions/runs/{CI_RUN_ID}/attempts/1/jobs?per_page=30",
             ("jobs", 0, "conclusion"), "failure", "CHECK_JOB"),
            (f"actions/runs/{CI_RUN_ID}/attempts/1/jobs?per_page=30",
             ("jobs", 0, "steps"), [], "REQUIRED_TREE_CHECK"),
        ):
            with self.subTest(path=path, keys=keys):
                data = copy.deepcopy(metadata)
                node = data[path]
                for key in keys[:-1]:
                    node = node[key]
                node[keys[-1]] = value
                with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_" + error):
                    self.run_admit(data)

    def test_receipt_scope_and_oidc_claims_distinguish_protected_push_from_dispatch(self):
        self.request()
        self.push_metadata()
        with mock.patch.dict(os.environ, self.push_env(), clear=True), \
             mock.patch.object(preview, "ROOT", self.root), mock.patch.object(request, "MERGE_ENABLED", True):
            scope = handoff.production_scope(RUN_ID, self.squash, APPROVAL)
            binding = scope.record()["release_request"]
            self.assertEqual(preview.input_identity(self.squash, RUN_ID)["release_request"], binding)
            self.assertEqual(binding["sha256"], ops.digest(self.canonical(self.base)))
            self.assertEqual(binding["tree"], self.tree)
            self.assertNotIn("release_request", handoff.Scope(RUN_ID, 1, self.squash, APPROVAL).record())
            cert = {"issuer": handoff.OIDC_ISSUER, "buildSignerDigest": self.squash,
                    "sourceRepositoryIdentifier": str(ops.REPO_ID), "sourceRepositoryDigest": self.squash,
                    "sourceRepositoryRef": "refs/heads/master", "buildConfigDigest": self.squash,
                    "buildTrigger": "push", "runInvocationURI": scope.invocation,
                    "runnerEnvironment": "github-hosted",
                    "buildSignerURI": f"https://github.com/{ops.REPO}/{handoff.SIGNER}@refs/heads/master",
                    "buildConfigURI": f"https://github.com/{ops.REPO}/{preview.WORKFLOW}@refs/heads/master"}
            handoff.certificate_claims(scope, handoff.SIGNER, cert)
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_ORIGIN"):
                handoff.certificate_claims(scope, handoff.SIGNER, cert | {"buildTrigger": "workflow_dispatch"})

    def test_missing_or_failed_proof_never_reaches_the_signing_cli(self):
        self.request()
        data = self.admitted_metadata()
        staging = self.root.parent / "prepared"
        staging.mkdir()
        with mock.patch.dict(os.environ, self.push_env(PREVIEW_MODE="publish", PREVIEW_APPROVAL_SHA256=APPROVAL),
                             clear=True), mock.patch.object(preview, "ROOT", self.root), \
             mock.patch.object(request, "MERGE_ENABLED", True), mock.patch.object(ops, "install_deadline"), \
             mock.patch.object(sys, "argv", ["preview_ops.py", "sign", "--prepared", str(staging)]), \
             mock.patch.object(ops, "api", side_effect=lambda path, **kw: data[path]) as api, \
             mock.patch.object(preview, "bind_signing_input"), mock.patch("sign_apk.sign") as sign:
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_REQUEST_PRE_KEY"):
                preview.main()
            sign.assert_not_called()
            preview.request_pre_key(staging, self.squash, APPROVAL, record=True)
            api.reset_mock()
            preview.main()
            sign.assert_called_once()
            api.assert_not_called()
            proof = staging / "request-pre-key.json"
            expired = time.time() - 100
            os.utime(proof, (expired, expired))
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_REQUEST_PRE_KEY"):
                preview.main()
            proof.write_text(json.dumps({"attempt": 2}))
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_REQUEST_PRE_KEY"):
                preview.main()
            sign.assert_called_once()

    def test_metadata_restricts_method_destination_and_authorization(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN": "job-token-fixture"}, clear=True), \
             mock.patch.object(ops.urllib.request, "build_opener") as build:
            fetch = build.return_value.open
            with self.assertRaisesRegex(ops.ReleaseError, "PUBLIC_METADATA_READ_ONLY"):
                ops.api("pulls/45", method="PATCH", body={}, authenticated=False)
            for endpoint in (
                "rules/branches/master", "branches/master", f"git/commits/{'a' * 40}",
                f"commits/{'a' * 40}/pulls?per_page=2", "pulls/45", "actions/caches?per_page=1",
            ):
                fetch.reset_mock()
                fetch.return_value = io.BytesIO(b"{}")
                request.metadata_read(endpoint)
                fetch.assert_called_once()
                sent = fetch.call_args.args[0]
                self.assertEqual(sent.full_url, f"https://api.github.com/repos/{ops.REPO}/{endpoint}")
                self.assertEqual(sent.get_method(), "GET")
                self.assertEqual(fetch.call_args.kwargs["timeout"], 5)
                self.assertEqual(sent.headers["Cache-control"], "no-cache")
                self.assertEqual(sent.headers.get("Authorization"), "Bearer job-token-fixture")
            fetch.reset_mock()
            for endpoint in ("releases/453133947", "../actions/caches", "https://example.invalid/actions/caches",
                             "actions/workflows/1/runs", "actions/runs/123/attempts/2/jobs", "rules/branches/other"):
                with self.subTest(endpoint=endpoint), self.assertRaisesRegex(
                    ops.ReleaseError, "PREVIEW_REQUEST_METADATA_ROUTE"
                ):
                    request.metadata_read(endpoint)
            with mock.patch.dict(os.environ, {"GITHUB_TOKEN": "other-token-fixture"}, clear=True), \
                 self.assertRaisesRegex(ops.ReleaseError, "API_TOKEN_MISSING"):
                request.metadata_read("rules/branches/master")
            fetch.assert_not_called()

    def test_metadata_limits_fail_without_retry_or_authentication_fallback(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN": "job-token-fixture"}, clear=True), \
             mock.patch.object(ops.urllib.request, "build_opener") as build:
            fetch = build.return_value.open
            for path, body, failure in (
                ("pulls/45", io.BytesIO(b" " * (1024 * 1024 + 1)), "API_RESPONSE_SIZE"),
                ("branches/master", HTTPError("synthetic", 429, "limit", {}, None), "API_HTTP_429"),
                (f"commits/{'a' * 40}/pulls?per_page=2",
                 HTTPError("synthetic", 403, "denied", {}, None), "API_HTTP_403"),
            ):
                fetch.reset_mock(return_value=True, side_effect=True)
                if isinstance(body, HTTPError):
                    fetch.side_effect = body
                else:
                    fetch.return_value = body
                with self.subTest(path=path), self.assertRaisesRegex(ops.ReleaseError, failure):
                    request.metadata_read(path)
                fetch.assert_called_once()
                self.assertEqual(fetch.call_args.args[0].get_header("Authorization"), "Bearer job-token-fixture")

    def test_metadata_redirect_never_makes_a_second_request(self):
        urllib_request = ops.urllib.request
        seen = []

        class RedirectResponse(urllib_request.HTTPSHandler):
            def https_open(self, req):
                seen.append((req.full_url, req.get_header("Authorization")))
                headers = Message()
                headers["Location"] = "https://other-origin.invalid/redirect"
                response = addinfourl(io.BytesIO(b""), headers, req.full_url, 302)
                response.msg = "Found"
                return response

        opener = urllib_request.build_opener(urllib_request.ProxyHandler({}), RedirectResponse, ops.NoRedirect)
        with mock.patch.dict(os.environ, {"GH_TOKEN": "job-token-fixture"}, clear=True), \
             mock.patch.object(urllib_request, "build_opener", return_value=opener), \
             self.assertRaisesRegex(ops.ReleaseError, "API_HTTP_302"):
            request.metadata_read("rules/branches/master")
        self.assertEqual(seen, [(f"https://api.github.com/repos/{ops.REPO}/rules/branches/master",
                                 "Bearer job-token-fixture")])

    def test_failed_required_check_prevents_draft_upload(self):
        self.request()
        data = self.admitted_metadata()
        data[self.jobs_path]["jobs"][0]["conclusion"] = "failure"
        with mock.patch.dict(os.environ, self.push_env(), clear=True), mock.patch.object(preview, "ROOT", self.root), \
             mock.patch.object(request, "MERGE_ENABLED", True), \
             mock.patch.object(ops, "api", side_effect=lambda path, **kw: data[path]), \
             mock.patch.object(preview, "signed_record", return_value={"trusted_revision": self.squash,
                                                                     "approval_sha256": APPROVAL}), \
             mock.patch.object(handoff, "verify_accepted_apk"), \
             mock.patch.object(ops, "upload_asset") as upload:
            scope = handoff.production_scope(RUN_ID, self.squash, APPROVAL)
            with self.assertRaisesRegex(ops.ReleaseError, "REHEARSAL_CHECK_JOB"):
                preview.publish(self.root, scope, self.root / handoff.ACCEPTANCE_PROOF, self.root)
            upload.assert_not_called()


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
