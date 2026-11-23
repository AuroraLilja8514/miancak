"""Offline workflow trust checks and real age tests with disposable fixture bytes."""
import json
from dataclasses import replace
from pathlib import Path
import os
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile

import handoff
import preview_ops as preview
import release_ops as ops
import smoke_handoff as smoke

SCOPE = handoff.Scope(1234, 1, "a" * 40, "b" * 64)
SHA = "c" * 64


def attested_subject(kind, scope=SCOPE):
    policy = scope.workflow
    role_path = policy.signer_for(kind)
    uri = f"https://github.com/{ops.REPO}/"
    certificate = {
        "issuer": handoff.OIDC_ISSUER, "buildSignerDigest": scope.revision,
        "sourceRepositoryIdentifier": str(ops.REPO_ID), "sourceRepositoryDigest": scope.revision,
        "sourceRepositoryRef": policy.ref, "buildConfigDigest": scope.revision,
        "buildTrigger": policy.event, "runInvocationURI": scope.invocation,
        "buildSignerURI": uri + role_path + "@" + policy.ref,
        "buildConfigURI": uri + policy.caller + "@" + policy.ref,
        "runnerEnvironment": "github-hosted",
    }
    return {"verificationResult": {"signature": {"certificate": certificate}, "statement": {
        "predicateType": handoff.PREDICATE, "predicate": {"role": kind},
        "subject": [{"name": handoff.TRANSPORT[kind][0], "digest": {"sha256": SHA}}],
    }}}


def item(kind="recipient"):
    return {"id": 4, "name": SCOPE.name(kind), "expired": False, "size_in_bytes": 2048,
            "digest": "sha256:" + SHA, "workflow_run": {"id": SCOPE.run_id,
            "repository_id": ops.REPO_ID, "head_repository_id": ops.REPO_ID,
            "head_branch": "master", "head_sha": SCOPE.revision}}


def job():
    return {"id": 44, "name": handoff.SIGN_JOB, "run_id": SCOPE.run_id, "run_attempt": SCOPE.attempt,
            "head_sha": SCOPE.revision, "status": "completed", "conclusion": "success"}


class Trust(unittest.TestCase):
    def test_synthetic_attestations_and_transport_have_a_separate_identity(self):
        scope = replace(SCOPE, workflow=smoke.POLICY)
        for kind in ("recipient", "ciphertext"):
            with self.subTest(kind=kind):
                proof = [attested_subject(kind, scope)]
                handoff.verified_subject(proof, scope, kind, SHA)
                with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_ORIGIN"):
                    handoff.verified_subject(proof, SCOPE, kind, SHA)
                with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_ORIGIN"):
                    handoff.verified_subject([attested_subject(kind)], scope, kind, SHA)
                self.assertNotEqual(scope.name(kind), SCOPE.name(kind))
        self.assertNotIn("draft_id", scope.record())
        self.assertEqual(scope.record()["synthetic_workflow"], smoke.POLICY.caller)

    def test_synthetic_run_matches_exact_active_branch_revision_and_attempt(self):
        scope = replace(SCOPE, workflow=smoke.POLICY)
        policy = scope.workflow
        run = {"id": scope.run_id, "run_attempt": scope.attempt, "event": "push",
               "head_branch": policy.branch, "head_sha": scope.revision,
               "path": f"{policy.caller}@{policy.branch}", "display_title": policy.title,
               "status": "in_progress", "head_repository": {"id": ops.REPO_ID}}
        with mock.patch.object(ops, "api", return_value=run) as api:
            handoff.active_run(scope)
            api.assert_called_once_with(f"actions/runs/{scope.run_id}", timeout=10)
            for changes in ({"run_attempt": 2}, {"head_branch": "master"}, {"event": "workflow_dispatch"},
                            {"path": preview.WORKFLOW}, {"status": "completed"}):
                with self.subTest(changes=changes), self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACTIVE_RUN"):
                    api.return_value = run | changes
                    handoff.active_run(scope)

    def test_attestation_binds_workflow_role_revision_and_run_attempt(self):
        for kind in ("recipient", "ciphertext", "input-recipient"):
            handoff.verified_subject([attested_subject(kind)], SCOPE, kind, SHA)
        for field, wrong in {
            "issuer": "https://example.invalid", "buildSignerDigest": "d" * 40,
            "buildConfigDigest": "e" * 40, "sourceRepositoryIdentifier": "1",
            "sourceRepositoryRef": "refs/pull/1/merge", "runnerEnvironment": "self-hosted",
            "runInvocationURI": SCOPE.invocation.removesuffix("1") + "2",
            "buildSignerURI": f"https://github.com/{ops.REPO}/{preview.WORKFLOW}@refs/heads/master",
            "buildConfigURI": f"https://github.com/{ops.REPO}/other.yml@refs/heads/master",
        }.items():
            with self.subTest(field=field), self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_ORIGIN"):
                proof = attested_subject("recipient")
                proof["verificationResult"]["signature"]["certificate"][field] = wrong
                handoff.verified_subject([proof], SCOPE, "recipient", SHA)
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_SUBJECT"):
            handoff.verified_subject([attested_subject("recipient")], SCOPE, "recipient", "e" * 64)
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ATTESTATION_COUNT"):
            handoff.verified_subject([attested_subject("recipient")] * 2, SCOPE, "recipient", SHA)

    def test_invalid_cryptographic_bundle_fails_before_claims_are_used(self):
        with mock.patch.object(ops, "command", side_effect=ops.ReleaseError("TOOL_FAILED")) as command:
            with self.assertRaisesRegex(ops.ReleaseError, "TOOL_FAILED"):
                handoff.verify_proof(SCOPE, "recipient", Path("bundle"), Path("tools"))
            args = command.call_args.args[0]
            self.assertEqual(args[:3], [Path("tools/gh"), "attestation", "verify"])
            self.assertIn("--deny-self-hosted-runners", args)
            self.assertEqual(args[args.index("--signer-workflow") + 1], f"{ops.REPO}/{handoff.PUBLISHER}")

    def test_transport_rejects_wrong_run_expiration_digest_and_ambiguity(self):
        expected = item()
        self.assertEqual(handoff.matching_artifact(SCOPE, "recipient", {"total_count": 1, "artifacts": [expected]}),
                         expected)
        for changes in ({"name": "preview-recipient-1234-2"}, {"expired": True}, {"digest": "sha256:a"},
                        {"workflow_run": expected["workflow_run"] | {"id": 88}},
                        {"workflow_run": expected["workflow_run"] | {"head_repository_id": 99}}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ARTIFACT_ORIGIN"):
                handoff.matching_artifact(SCOPE, "recipient", {"total_count": 1, "artifacts": [expected | changes]})
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ARTIFACT_AMBIGUOUS"):
            handoff.matching_artifact(SCOPE, "recipient", {"total_count": 2, "artifacts": [expected, expected]})

    def test_only_exact_successful_acceptance_of_this_attempt_is_trusted(self):
        self.assertTrue(handoff.acceptance_job(SCOPE, {"total_count": 1, "jobs": [job()]}))
        self.assertFalse(handoff.acceptance_job(SCOPE, {"total_count": 1, "jobs": [
            job() | {"status": "in_progress", "conclusion": None}]}))
        for field, value in {"run_id": 999, "run_attempt": 2, "head_sha": "f" * 40,
                             "conclusion": "failure", "status": "completed_with_warning"}.items():
            with self.subTest(field=field), self.assertRaises(ops.ReleaseError):
                handoff.acceptance_job(SCOPE, {"total_count": 1, "jobs": [job() | {field: value}]})
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_JOB_AMBIGUOUS"):
            handoff.acceptance_job(SCOPE, {"total_count": 2, "jobs": [job(), job()]})

    def test_publisher_must_overlap_the_signer_in_the_same_attempt(self):
        publisher = job() | {"name": handoff.PUBLISH_JOB, "status": "in_progress", "conclusion": None}
        with mock.patch.object(handoff, "jobs_for_attempt") as listing:
            listing.return_value = {"total_count": 1, "jobs": [publisher]}
            self.assertTrue(handoff.publisher_running(SCOPE))
            listing.return_value = {"total_count": 1, "jobs": [publisher | {"status": "queued"}]}
            self.assertFalse(handoff.publisher_running(SCOPE))
            listing.return_value = {"total_count": 1, "jobs": [publisher | {"status": "completed"}]}
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_PUBLISHER_EXITED"):
                handoff.publisher_running(SCOPE)
            listing.return_value = {"total_count": 1, "jobs": [publisher | {"run_attempt": 2}]}
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_JOB_ORIGIN"):
                handoff.publisher_running(SCOPE)

    def test_a_ciphertext_name_is_not_an_acceptance_result(self):
        with mock.patch.object(handoff, "active_run"), mock.patch.object(handoff, "accepted", return_value=False), \
             mock.patch.object(ops, "api") as api, mock.patch.object(handoff.time, "monotonic", side_effect=[0, 0, 5, 5]):
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_DEADLINE"):
                handoff.wait_for_transport(SCOPE, "ciphertext", 5)
            api.assert_not_called()
        with mock.patch.object(handoff, "active_run"), mock.patch.object(handoff, "accepted", return_value=True), \
             mock.patch.object(ops, "api", return_value={"total_count": 1, "artifacts": [item("ciphertext")]}):
            self.assertEqual(handoff.wait_for_transport(SCOPE, "ciphertext", 5), item("ciphertext"))

    def test_no_overlap_times_out_without_fetching_the_public_key(self):
        with mock.patch.object(handoff, "active_run"), mock.patch.object(handoff, "publisher_running", return_value=False), \
             mock.patch.object(ops, "api") as api, mock.patch.object(handoff.time, "monotonic", side_effect=[0, 0, 5, 5]):
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_DEADLINE"):
                handoff.wait_for_transport(SCOPE, "recipient", 5)
            api.assert_not_called()

    def test_public_transport_rejects_extra_archive_members(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            with ZipFile(path / "wire.zip", "x") as archive:
                archive.writestr("accepted.age", b"ciphertext")
                archive.writestr("proof.json", b"proof")
                archive.writestr("identity.txt", b"synthetic extra")
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ARCHIVE_FILES"):
                handoff.unpack_exact(path / "wire.zip", path / "out", {"accepted.age": 99, "proof.json": 99})


@unittest.skipUnless(os.environ.get("MIANCAK_HANDOFF_TEST_TOOLS"), "Pinned local age binaries required")
class RealAge(unittest.TestCase):
    def setUp(self):
        self.tools = Path(os.environ["MIANCAK_HANDOFF_TEST_TOOLS"])
        self.assertEqual(ops.command([self.tools / "age", "--version"]).stdout.strip(), b"v1.3.2")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.publisher = self.root / "publisher"
        self.publisher.mkdir(mode=0o700)
        handoff.create_recipient(SCOPE, self.publisher, self.tools)
        self.public = self.publisher / "recipient" / "recipient.json"
        self.identity = self.publisher / "identity.txt"
        self.fixture = self.root / "fixture"
        self.fixture.mkdir()
        self.data = b"MIANCAK synthetic transport bytes. " * 101
        self.files = {"synthetic.txt": 4096}
        (self.fixture / "synthetic.txt").write_bytes(self.data)
        self.ciphertext = self.root / "accepted.age"
        handoff.encrypt_files(SCOPE, self.public, self.fixture, self.files, self.ciphertext, self.tools)

    def test_encrypt_decrypt_exact_bytes_with_no_plaintext_or_key_on_wire(self):
        encrypted = self.ciphertext.read_bytes()
        self.assertTrue(encrypted.startswith(b"age-encryption.org/v1\n"))
        self.assertNotIn(self.data[:25], encrypted)
        self.assertNotIn(b"AGE-SECRET-KEY", encrypted)
        self.assertNotIn(b"AGE-SECRET-KEY", self.public.read_bytes())
        out = self.root / "decoded"
        handoff.decrypt_files(SCOPE, self.public, self.identity, self.ciphertext, out, self.files, self.tools)
        self.assertEqual((out / "synthetic.txt").read_bytes(), self.data)
        self.assertEqual({p.name for p in out.iterdir()}, {"synthetic.txt"})

    def test_wrong_recipient_and_tampered_ciphertext_fail_in_age(self):
        wrong = self.root / "wrong"
        wrong.mkdir(mode=0o700)
        handoff.create_recipient(SCOPE, wrong, self.tools)
        with self.assertRaisesRegex(ops.ReleaseError, "TOOL_FAILED"):
            handoff.decrypt_files(SCOPE, wrong / "recipient/recipient.json", wrong / "identity.txt",
                                  self.ciphertext, self.root / "wrong-out", self.files, self.tools)
        encrypted = bytearray(self.ciphertext.read_bytes())
        encrypted[-1] ^= 1
        self.ciphertext.write_bytes(encrypted)
        with self.assertRaisesRegex(ops.ReleaseError, "TOOL_FAILED"):
            handoff.decrypt_files(SCOPE, self.public, self.identity, self.ciphertext, self.root / "tampered",
                                  self.files, self.tools)

    def test_replay_is_rejected_and_raw_guest_data_cannot_be_wrapped(self):
        stale = handoff.Scope(SCOPE.run_id, 2, SCOPE.revision, SCOPE.approval)
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_RECIPIENT_SCOPE"):
            handoff.decrypt_files(stale, self.public, self.identity, self.ciphertext, self.root / "replay",
                                  self.files, self.tools)
        (self.fixture / "guest.log").write_bytes(b"synthetic forbidden log")
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_LOCAL_FILES"):
            handoff.encrypt_files(SCOPE, self.public, self.fixture, self.files, self.root / "extra.age", self.tools)

    def test_valid_ciphertext_with_another_runs_receipt_is_rejected(self):
        packed = self.root / "synthetic.zip"
        with ZipFile(packed, "x") as archive:
            archive.writestr("synthetic.txt", self.data)
            wrong = handoff.Scope(1235, 1, SCOPE.revision, SCOPE.approval)
            archive.writestr(handoff.RECEIPT, json.dumps(handoff.receipt(wrong, self.public)))
        cipher = self.root / "cross-run.age"
        ops.command([self.tools / "age", "-r", handoff.recipient_value(SCOPE, self.public),
                     "-o", cipher, packed])
        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_RECEIPT_SCOPE"):
            handoff.decrypt_files(SCOPE, self.public, self.identity, cipher, self.root / "cross-run",
                                  self.files, self.tools)


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
