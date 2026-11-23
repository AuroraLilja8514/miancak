"""Synthetic owner, build and signing-boundary contracts."""
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile

import handoff
import preview_ops as preview
import private_inputs as inputs
import release_ops as ops
import smoke_inputs as smoke
import smoke_owner as owner
from test_preview import identity

SCOPE = handoff.Scope(123, 1, "a" * 40, "b" * 64)
OWNER = {"id": inputs.OWNER_ID, "login": ops.REPO.split("/")[0]}
APPROVAL_ID = 456


def iso(value):
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def approval(scope, created, **changes):
    return {"id": APPROVAL_ID, "event": "workflow_dispatch", "run_attempt": 1,
            "head_branch": "master", "head_sha": scope.revision, "path": preview.WORKFLOW,
            "head_repository": {"id": ops.REPO_ID}, "actor": OWNER, "triggering_actor": OWNER,
            "display_title": inputs.approval_title(scope), "created_at": iso(created),
            "status": "completed", "conclusion": "success", **changes}


def job(scope, name, *, status="completed", conclusion="success"):
    return {"id": 12, "run_id": scope.run_id, "run_attempt": scope.attempt, "name": name,
            "head_sha": scope.revision, "status": status, "conclusion": conclusion}


class Approval(unittest.TestCase):
    def setUp(self):
        self.created = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=10)
        self.run = approval(SCOPE, self.created + timedelta(seconds=1))

    def test_owner_intent_is_bound_to_active_input_digest_not_a_name_or_a_rerun(self):
        inputs.approval_origin(self.run, SCOPE, self.created)
        for change in [
            {"actor": OWNER | {"id": 4}},
            {"triggering_actor": OWNER | {"id": 4}},
            {"run_attempt": 2},
            {"head_branch": "review/protected-preview-ciphertext"},
            {"head_sha": "c" * 40},
            {"head_repository": {"id": 0}},
            {"event": "push"},
            {"path": ".github/workflows/ci.yml"},
            {"display_title": inputs.approval_title(replace(SCOPE, run_id=999))},
            {"display_title": inputs.approval_title(replace(SCOPE, approval="c" * 64))},
            {"created_at": iso(self.created - timedelta(seconds=1))},
            {"created_at": iso(self.created + timedelta(seconds=inputs.REVIEW_SECONDS + 1))},
        ]:
            with self.subTest(change=change), self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_OWNER_APPROVAL_ORIGIN"):
                inputs.approval_origin(self.run | change, SCOPE, self.created)

    def test_unique_successful_approval_and_attempt_job_are_required(self):
        selected = job(replace(SCOPE, run_id=APPROVAL_ID), inputs.APPROVE_JOB)
        with mock.patch.object(ops, "api", side_effect=[
            {"total_count": 1, "workflow_runs": [self.run]}, self.run,
            {"total_count": 1, "jobs": [selected]},
        ]) as api:
            self.assertEqual(inputs.find_approval(SCOPE, self.created), APPROVAL_ID)
            self.assertIn(f"/{APPROVAL_ID}/attempts/1/jobs", api.call_args.args[0])
        for incomplete, error in (([self.run, self.run], "AMBIGUOUS"), ([], "LIST")):
            with mock.patch.object(ops, "api", return_value={"total_count": 2, "workflow_runs": incomplete}):
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_OWNER_APPROVAL_" + error):
                    inputs.find_approval(SCOPE, self.created)
        with mock.patch.object(ops, "api", side_effect=[self.run, {"total_count": 1, "jobs": [
            selected | {"run_attempt": 2}]}]):
            with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_JOB_ORIGIN"):
                inputs.approved_run(SCOPE, self.created, APPROVAL_ID)
        with mock.patch.object(ops, "api", return_value=self.run | {"status": "in_progress", "conclusion": None}):
            self.assertIsNone(inputs.approved_run(SCOPE, self.created, APPROVAL_ID))
        with mock.patch.object(ops, "api", return_value=self.run | {"conclusion": "cancelled"}):
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_OWNER_APPROVAL_FAILED"):
                inputs.approved_run(SCOPE, self.created, APPROVAL_ID)

    def test_expiry_and_extra_receipt_fields_fail(self):
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_INPUT_EXPIRED"):
            inputs.fresh({"created_at": iso(self.created - timedelta(seconds=inputs.RECEIPT_SECONDS))})
        record = preview.input_identity(SCOPE.revision, SCOPE.run_id)
        record.update(apk_identity=identity(), apks={name: {"sha256": "a" * 64, "bytes": 10}
                                                   for name in preview.INPUTS})
        preview.validate_input_record(record, SCOPE.revision, SCOPE.run_id)
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_INPUT_RECEIPT"):
            preview.validate_input_record(record | {"extra": "synthetic unapproved metadata"},
                                          SCOPE.revision, SCOPE.run_id)

    def test_cross_run_rehearsal_verifies_dummy_receipt_and_cannot_authorize_production(self):
        target = replace(SCOPE, workflow=owner.POLICY)
        raw = smoke.receipt_bytes(target)
        target = replace(target, approval=ops.digest(raw))
        title = inputs.approval_title(target, label=owner.LABEL)
        confirmation = approval(target, self.created + timedelta(seconds=1),
                                head_branch=owner.POLICY.branch, path=owner.POLICY.caller,
                                display_title=title)
        self.assertFalse(inputs.owner_run(confirmation, SCOPE))
        confirmer = replace(target, run_id=APPROVAL_ID)
        item = {"created_at": iso(self.created)}
        request = title.removeprefix(owner.LABEL + " ")

        def transport(scope, kind, artifact, folder):
            self.assertEqual(scope.workflow, owner.POLICY)
            self.assertEqual((kind, artifact), ("input-receipt", item))
            folder.mkdir()
            (folder / "receipt.json").write_bytes(raw)

        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(owner, "current_receipt", return_value=item), \
             mock.patch.object(handoff, "fetch_wire", side_effect=transport), \
             mock.patch.object(ops, "api", return_value=confirmation | {"status": "in_progress"}):
            stage = Path(directory)
            owner.approve(confirmer, stage, request)
            with self.assertRaisesRegex(ops.ReleaseError, "INPUT_SMOKE_RECEIPT"):
                owner.approve(confirmer, stage, f"approve-inputs {target.run_id} " + "0" * 64)
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_OWNER_APPROVAL_ORIGIN"):
                owner.approve(target, stage, request)

        selected = job(confirmer, owner.APPROVE_JOB)
        with mock.patch.object(ops, "api", side_effect=[
            {"total_count": 1, "workflow_runs": [confirmation]}, confirmation,
            {"total_count": 1, "jobs": [selected]},
        ]) as api:
            self.assertEqual(inputs.find_approval(target, self.created, label=owner.LABEL,
                                                 job_name=owner.APPROVE_JOB), APPROVAL_ID)
            self.assertTrue(api.call_args_list[0].args[0].startswith("actions/workflows/ci.yml/runs?"))
            self.assertEqual(api.call_args.args[0], f"actions/runs/{APPROVAL_ID}/attempts/1/jobs?per_page=100")


@unittest.skipUnless(os.environ.get("MIANCAK_HANDOFF_TEST_TOOLS"), "Pinned age fixture required")
class Custody(unittest.TestCase):
    def test_actual_age_round_trip_then_independent_signed_input_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            builder = root / "build"
            signer = root / "sign"
            builder.mkdir()
            signer.mkdir()
            tools = Path(os.environ["MIANCAK_HANDOFF_TEST_TOOLS"])
            for stage in (builder, signer):
                (stage / "tools").symlink_to(tools)
            original = builder / "inputs"
            original.mkdir()
            for name in preview.INPUTS:
                (original / name).write_bytes(b"dummy data for " + name.encode())
            created = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(seconds=5)
            fallback = b"synthetic already published baseline"
            archives = {}
            metadata = {}

            def make_wire(scope, kind, directory, *names):
                wire = root / (kind + ".zip")
                with ZipFile(wire, "x") as archive:
                    for name in names:
                        archive.write(directory / name, name)
                art_id = 1 + len(archives)
                archives[art_id] = wire
                metadata[kind] = {"id": art_id, "name": scope.name(kind), "size_in_bytes": wire.stat().st_size,
                                  "digest": "sha256:" + ops.file_sha(wire), "created_at": iso(created),
                                  "expired": False, "workflow_run": {"id": scope.run_id, "head_sha": scope.revision,
                                  "head_branch": "master", "head_repository_id": ops.REPO_ID,
                                  "repository_id": ops.REPO_ID}}

            with mock.patch.object(preview, "inspect_inputs", return_value=identity()):
                record = preview.input_record(original, SCOPE.revision, SCOPE.run_id)
                scope = replace(SCOPE, approval=preview.attestation_sha(record))
                receipt = builder / "receipt"
                receipt.mkdir()
                (receipt / "receipt.json").write_bytes(preview.canonical_record(record))
                make_wire(scope, "input-receipt", receipt, "receipt.json")
                handoff.create_recipient(scope, signer, tools)
                (signer / "recipient/proof.json").write_text("{}")
                make_wire(scope, "input-recipient", signer / "recipient", "recipient.json", "proof.json")

                def api(path, **kwargs):
                    if path.startswith("actions/artifacts/"):
                        return next(item for item in metadata.values() if item["id"] == int(path.split("/")[-1]))
                    if path.startswith("actions/workflows/"):
                        return {"total_count": 1, "workflow_runs": [approval(scope, created)]}
                    if path == f"actions/runs/{APPROVAL_ID}":
                        return approval(scope, created)
                    self.fail("Unexpected fixture API")

                def download(path, destination, expected, *, limit):
                    archive = archives[int(path.split("/")[2])]
                    self.assertLessEqual(archive.stat().st_size, limit)
                    self.assertEqual(expected, "sha256:" + ops.file_sha(archive))
                    shutil.copyfile(archive, destination)

                def baseline(release, name, target):
                    target.write_bytes(fallback)
                    return {"digest": "sha256:" + ops.digest(fallback)}

                running = job(scope, handoff.SIGN_JOB, status="in_progress", conclusion=None)
                jobs = {"total_count": 3, "jobs": [running, job(scope, inputs.GATE_JOB), job(scope, inputs.BUILD_JOB)]}
                approved_jobs = {"total_count": 1,
                                 "jobs": [job(replace(scope, run_id=APPROVAL_ID), inputs.APPROVE_JOB)]}
                with (
                    mock.patch.object(ops, "api", side_effect=api),
                    mock.patch.object(ops, "download_api", side_effect=download),
                    mock.patch.object(handoff, "active_run"),
                    mock.patch.object(handoff, "jobs_for_attempt",
                                      side_effect=lambda case: jobs if case == scope else approved_jobs),
                    mock.patch.object(inputs, "artifact", side_effect=lambda case, kind: metadata[kind]),
                    mock.patch.object(handoff, "verify_proof") as verify,
                    mock.patch.object(preview, "exact_release", return_value=({"assets": []}, {})),
                    mock.patch.object(preview, "STABLE_APK", ops.digest(fallback)),
                    mock.patch.object(ops, "release_asset", side_effect=baseline) as previous,
                    mock.patch.object(ops, "inspect_apk", return_value=identity(old=True)),
                    mock.patch.object(ops, "verify_signed"),
                    mock.patch.dict(os.environ, {"PREVIEW_APPROVAL_RUN_ID": str(APPROVAL_ID),
                                                 "PREVIEW_RECEIPT_ARTIFACT_ID": "1",
                                                 "GITHUB_RUN_ID": str(scope.run_id)}),
                ):
                    inputs.seal_inputs(scope, builder)
                    verify.assert_called_once_with(scope, "input-recipient", builder / "recipient", builder / "tools")
                    self.assertTrue((builder / "inputs.age").read_bytes().startswith(b"age-encryption.org/v1\n"))
                    make_wire(scope, "input-ciphertext", builder, "inputs.age")
                    jobs["jobs"][-1]["conclusion"] = "failure"
                    with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACCEPTANCE_FAILED"):
                        inputs.receive_inputs(scope, signer)
                    previous.assert_not_called()
                    jobs["jobs"][-1]["conclusion"] = "success"
                    inputs.receive_inputs(scope, signer)
                    prepared = signer / "prepared"
                    preview.bind_signing_input(prepared, scope.revision, scope.approval)
                    self.assertEqual(json.loads((prepared / "approved.json").read_bytes()), record)
                    for name in preview.INPUTS:
                        self.assertEqual((prepared / name).read_bytes(), (original / name).read_bytes())
                    previous.reset_mock()
                    (prepared / "unsigned.apk").write_bytes(b"replacement unsigned fixture")
                    encrypted_artifact = metadata["input-ciphertext"]
                    with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_PRIVATE_INPUT_CHANGED"):
                        preview.prepare_private(prepared, scope.revision, scope.run_id, record,
                                                {"id": encrypted_artifact["id"],
                                                 "digest": encrypted_artifact["digest"]}, scope.approval)
                    previous.assert_not_called()


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
