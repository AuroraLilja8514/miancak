"""Exercise the real publisher with dummy bytes and substituted hosted services."""
import copy
from dataclasses import replace
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest import mock

import handoff
import manifest_request as request
import preview_ops as preview
import release_ops as ops
from test_handoff import attested_subject


class PublisherProof(unittest.TestCase):
    def substitute(self, module, name, **options):
        patch = mock.patch.object(module, name, **options)
        self.addCleanup(patch.stop)
        return patch.start()

    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.signed = root / "sealed"
        self.signed.mkdir()
        self.apk = self.signed / preview.APK
        self.apk.write_bytes(b"synthetic signed payload")
        self.proof = root / handoff.ACCEPTANCE_PROOF
        self.proof.write_text("synthetic bundle supplied to the verifier")
        self.tools = root / "tools"
        self.sha = ops.file_sha(self.apk)
        self.scope = handoff.Scope(1234, 1, "a" * 40, "b" * 64, handoff.MERGE_PRODUCTION, {
            "path": request.REQUEST_PATH, "sha256": "c" * 64, "reviewed_base_sha": "d" * 40, "tree": "e" * 40,
        })
        environment = mock.patch.dict(os.environ, {"GITHUB_RUN_ID": str(self.scope.run_id)}, clear=True)
        self.addCleanup(environment.stop)
        environment.start()
        self.substitute(preview, "request_fields", return_value={"release_request": self.scope.release_request})
        self.substitute(ops, "inspect_apk", return_value={
            "application_id": ops.PACKAGE, "version": "0.3.4", "version_code": 7, "min_sdk": 33,
            "debuggable": False, "abis": ops.ABIS,
        })
        self.substitute(ops, "verify_signed")
        self.substitute(preview, "require_split")
        self.provenance = self.signed / "release-provenance.json"
        ops.write_json(self.provenance, {
            "repository": ops.REPO, "source_sha": preview.SOURCE, "application_tree_sha256": preview.APP_INPUT,
            "application_id": ops.PACKAGE, "version": "0.3.4", "version_code": 7, "release_run_id": self.scope.run_id,
            "certificate_sha256": ops.CERTIFICATE, "apk_sha256": self.sha,
            "previous_release": {"tag": preview.STABLE_TAG, "version": "0.3.2", "version_code": 5,
                                 "apk_sha256": preview.STABLE_APK},
        })
        (self.signed / "SHA256SUMS.txt").touch()
        report = root / "local-guest-result.json"
        ops.write_json(report, {
            "api": 35, "source_sha": preview.SOURCE, "apk_sha256": self.sha,
            "certificate_sha256": ops.CERTIFICATE, "baseline_version_code": 5, "version_code": 7,
            "scenario": "previous-formal-release-upgrade", "result": "passed", "real_reboot": True,
            "legacy_yaml_preserved": True, "requires_online_pack_setup": True,
            "custom_yaml_activated_after_reboot": True, "guest_detail": "synthetic private fixture",
        })
        preview.seal(self.signed, report, self.scope.revision, self.scope.approval)
        record = preview.signed_record(self.signed, accepted=True)
        self.assertNotIn("guest_detail", record["preview_acceptance"])
        self.attested = attested_subject("ciphertext", self.scope)
        self.statement = self.attested["verificationResult"]["statement"]
        self.statement.update(
            predicateType=handoff.ACCEPTANCE_PREDICATE,
            subject=[{"name": preview.ATTACHMENT, "digest": {"sha256": self.sha}}],
            predicate=handoff.accepted_apk_predicate(self.scope, self.signed, record),
        )
        self.assertNotIn("synthetic private fixture", json.dumps(self.statement))
        self.verifier = self.substitute(ops, "command", side_effect=lambda *a, **kw:
                                       SimpleNamespace(stdout=json.dumps([self.attested]).encode()))
        self.request = self.substitute(preview, "verify_request")
        self.draft = {
            "id": preview.DRAFT_ID, "tag_name": preview.TAG, "draft": True, "prerelease": True, "assets": [],
            "upload_url": f"https://uploads.github.com/repos/{ops.REPO}/releases/{preview.DRAFT_ID}/assets{{?name,label}}",
        }
        self.download = f"https://github.com/{ops.REPO}/releases/download/{preview.TAG}/{preview.ATTACHMENT}"
        self.asset = {"id": 9, "state": "uploaded", "name": preview.ATTACHMENT,
                      "size": self.apk.stat().st_size, "digest": "sha256:" + self.sha,
                      "browser_download_url": self.download}
        self.writes = []
        self.api = self.substitute(ops, "api", side_effect=self.answer_api)
        self.upload = self.substitute(ops, "upload_asset", side_effect=self.upload_asset)
        self.public = self.substitute(preview, "verify_public")

    def answer_api(self, path, **options):
        if path == f"git/ref/tags/{preview.TAG}":
            return {"ref": f"refs/tags/{preview.TAG}", "object": {"type": "commit", "sha": preview.SOURCE,
                    "url": f"https://api.github.com/repos/{ops.REPO}/git/commits/{preview.SOURCE}"}}
        if path == "releases/latest":
            return {"id": preview.STABLE_ID, "tag_name": preview.STABLE_TAG, "draft": False, "prerelease": False}
        self.assertEqual(path, f"releases/{preview.DRAFT_ID}")
        if options:
            self.assertEqual(options["method"], "PATCH")
            self.assertEqual({key: options["body"][key] for key in ("draft", "prerelease", "make_latest")},
                             {"draft": False, "prerelease": True, "make_latest": "false"})
            self.draft["draft"] = False
            self.writes.append("patch")
        return copy.deepcopy(self.draft)

    def upload_asset(self, draft, path):
        self.assertEqual((draft["id"], path.name, ops.file_sha(path)), (preview.DRAFT_ID, preview.ATTACHMENT, self.sha))
        self.draft["assets"] = [self.asset]
        self.writes.append("upload")
        return self.asset

    def publish(self, scope=None):
        preview.publish(self.signed, scope or self.scope, self.proof, self.tools)

    def test_exact_acceptance_is_verified_before_upload_and_publish(self):
        with mock.patch("builtins.print"):
            self.publish()
        self.verifier.assert_called_once_with([
            self.tools / "gh", "attestation", "verify", self.apk, "--bundle", self.proof,
            "--repo", ops.REPO, "--signer-workflow", f"{ops.REPO}/{handoff.SIGNER}",
            "--signer-digest", self.scope.revision, "--source-digest", self.scope.revision,
            "--source-ref", "refs/heads/master", "--deny-self-hosted-runners",
            "--cert-oidc-issuer", handoff.OIDC_ISSUER, "--predicate-type", handoff.ACCEPTANCE_PREDICATE,
            "--format", "json",
        ], timeout=90)
        self.request.assert_has_calls([mock.call(self.scope.revision, actions_read=True)] * 2)
        self.assertEqual(self.writes, ["upload", "patch"])
        self.public.assert_called_once()
        self.assertEqual(self.public.call_args.args[0], self.download)

    def test_attestation_replay_or_false_acceptance_never_touches_the_draft(self):
        cert = self.attested["verificationResult"]["signature"]["certificate"]
        predicate = self.statement["predicate"]
        subject = self.statement["subject"][0]
        for record, key, bad in (
            (cert, "buildTrigger", "workflow_dispatch"),
            (cert, "runInvocationURI", self.scope.invocation.replace("/attempts/1", "/attempts/2")),
            (cert, "buildSignerURI", cert["buildSignerURI"].replace(handoff.SIGNER, handoff.PUBLISHER)),
            (cert, "buildConfigURI", cert["buildConfigURI"].replace(preview.WORKFLOW, handoff.SIGNER)),
            (cert, "sourceRepositoryDigest", "1" * 40),
            (subject, "digest", {"sha256": "2" * 64}),
            (subject, "name", "another.apk"),
            (predicate, "release_request", self.scope.release_request | {"sha256": "3" * 64}),
            (predicate, "baseline_apk_sha256", "4" * 64),
            (predicate, "android_acceptance", predicate["android_acceptance"] | {
                "custom_yaml_activated_after_reboot": False}),
        ):
            with self.subTest(claim=key), mock.patch.dict(record, {key: bad}), \
                 self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_"):
                self.publish()
        self.provenance.write_bytes(self.provenance.read_bytes() + b" ")
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACCEPTANCE_PROOF"):
            self.publish()
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_SIGNED_ACCEPTANCE"):
            self.publish(replace(self.scope, attempt=2))
        self.api.assert_not_called()
        self.upload.assert_not_called()

    def test_missing_or_cryptographically_rejected_bundle_never_touches_the_draft(self):
        self.verifier.side_effect = ops.ReleaseError("TOOL_FAILED")
        with self.assertRaisesRegex(ops.ReleaseError, "TOOL_FAILED"):
            self.publish()
        self.proof.unlink()
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACCEPTANCE_PROOF_FILE"):
            self.publish()
        self.verifier.assert_called_once()
        self.api.assert_not_called()
        self.upload.assert_not_called()

    def test_failed_second_freshness_check_strands_asset_without_automatic_recovery(self):
        self.request.side_effect = [None, ops.ReleaseError("PREVIEW_REQUEST_STALE_BASE"), None]
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_REQUEST_STALE_BASE"):
            self.publish()
        self.assertTrue(self.draft["draft"])
        self.assertEqual(self.draft["assets"], [self.asset])
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_DRAFT_NOT_EMPTY"):
            self.publish()
        self.assertEqual(self.writes, ["upload"])
        self.upload.assert_called_once()
        self.public.assert_not_called()

    def test_uncertain_upload_is_not_retried_or_published(self):
        def lost_response(draft, path):
            self.upload_asset(draft, path)
            raise ops.ReleaseError("ASSET_UPLOAD_RESPONSE_LOST")

        self.upload.side_effect = lost_response
        with self.assertRaisesRegex(ops.ReleaseError, "ASSET_UPLOAD_RESPONSE_LOST"):
            self.publish()
        self.assertTrue(self.draft["draft"])
        self.assertEqual(self.draft["assets"], [self.asset])
        self.assertEqual(self.writes, ["upload"])
        self.public.assert_not_called()


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
