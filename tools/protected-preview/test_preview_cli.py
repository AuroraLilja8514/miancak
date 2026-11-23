"""Test protected preview entry points with inert and synthetic inputs."""
from contextlib import redirect_stdout
from io import StringIO
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import handoff
import preview_ops as preview
import private_inputs as inputs
import release_ops as ops

ROOT = Path(__file__).resolve().parents[2]


class AdmissionCLI(unittest.TestCase):
    def invoke(self, *, mode="publish", expired=False):
        workflow = ROOT / ".github/workflows/protected-preview.yml"
        prefix = "run: python3 tools/protected-preview/preview_ops.py admit"
        commands = [line.strip().removeprefix("run: ") for line in workflow.read_text().splitlines()
                    if line.strip().startswith(prefix)]
        self.assertEqual(len(commands), 1)
        argv = shlex.split(commands[0])
        self.assertEqual(argv, ["python3", "tools/protected-preview/preview_ops.py", "admit",
                                "--timeout-seconds", "75"])
        # Start without inherited tokens or a production workflow identity.
        env = {"PYTHONDONTWRITEBYTECODE": "1", "PREVIEW_MODE": mode,
               "GITHUB_REPOSITORY": "fixture/inert", "GITHUB_REF": "refs/heads/inert"}
        if expired:
            env["MIANCAK_TIMEOUT_DEADLINE"] = "0"
        return subprocess.run([sys.executable, *argv[1:]], cwd=ROOT, env=env,
                              capture_output=True, text=True, timeout=10)

    def test_timeout_option_is_consumed_before_admission_checks(self):
        for mode in ("publish", "approve-inputs"):
            with self.subTest(mode=mode):
                result = self.invoke(mode=mode)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "FAIL: PREVIEW_WORKFLOW_AUTHORITY\n")

    def test_timeout_option_cannot_extend_an_expired_deadline(self):
        result = self.invoke(expired=True)
        self.assertEqual(result.returncode, 124)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "Timeout: inherited script deadline expired\n")

    def test_machine_gate_cli_refuses_inert_dispatch_before_writing_a_grant(self):
        workflow = ROOT / ".github/workflows/protected-preview.yml"
        prefix = "python3 tools/protected-preview/private_inputs.py authorize"
        commands = [line.strip() for line in workflow.read_text().splitlines()
                    if line.strip().startswith(prefix)]
        self.assertEqual(len(commands), 1)
        argv = shlex.split(commands[0])
        self.assertEqual(argv, ["python3", "tools/protected-preview/private_inputs.py", "authorize",
                                "--stage", "$stage", "--timeout-seconds", "2760"])
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            env = {"PYTHONDONTWRITEBYTECODE": "1", "PREVIEW_MODE": "publish", "GITHUB_RUN_ID": "1",
                   "GITHUB_REPOSITORY": "fixture/inert", "GITHUB_OUTPUT": str(stage / "outputs")}
            argv = [directory if word == "$stage" else word for word in argv]
            result = subprocess.run([sys.executable, *argv[1:]], cwd=ROOT, env=env, capture_output=True,
                                    text=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, "")
            self.assertEqual(result.stderr, "FAIL: PREVIEW_WORKFLOW_AUTHORITY\n")
            self.assertEqual(list(stage.iterdir()), [])


class StartupMetadata(unittest.TestCase):
    def setUp(self):
        self.scope = handoff.Scope(123, 1, "a" * 40, "b" * 64)
        owner = {"id": preview.OWNER_ID, "login": ops.REPO.split("/")[0]}
        self.metadata = {
            "id": self.scope.run_id, "run_attempt": 1, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": self.scope.revision, "path": preview.WORKFLOW,
            "display_title": handoff.PRODUCTION.title, "head_repository": {"id": ops.REPO_ID},
            "actor": owner, "triggering_actor": owner, "status": "in_progress", "conclusion": None,
        }

    def test_only_scoped_startup_waits_on_known_status_without_a_conclusion(self):
        with mock.patch.object(ops, "api") as api:
            for phase in ("builder-await-signer", "signer-startup"):
                for status in ("waiting", "queued", "pending", "requested"):
                    with self.subTest(phase=phase, status=status), redirect_stdout(StringIO()) as out:
                        api.return_value = self.metadata | {"status": status}
                        self.assertIsNone(handoff.active_run(self.scope, startup_phase=phase))
                        self.assertEqual(out.getvalue(), f"WAIT: {phase} {status}\n")
                        with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACTIVE_RUN_STATUS"):
                            handoff.active_run(self.scope)
            for change in ({"status": "completed", "conclusion": "cancelled"},
                           {"status": "waiting", "conclusion": "failure"},
                           {"status": "unrecognized\nraw payload"}):
                with self.subTest(change=change), redirect_stdout(StringIO()) as out:
                    api.return_value = self.metadata | change
                    with self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACTIVE_RUN_STATUS"):
                        handoff.active_run(self.scope, startup_phase="signer-startup")
                    self.assertEqual(out.getvalue(), "")

    def test_pending_metadata_cannot_replay_identity(self):
        with mock.patch.object(ops, "api") as api, redirect_stdout(StringIO()) as out:
            for change in ({"id": 122}, {"run_attempt": 2}, {"head_sha": "c" * 40},
                           {"head_branch": "other"}, {"head_repository": {"id": 9}},
                           {"path": "other"}, {"event": "push"}, {"actor": {"id": 9}},
                           {"triggering_actor": {"id": 9}}):
                api.return_value = self.metadata | {"status": "waiting"} | change
                with self.subTest(change=change), self.assertRaisesRegex(ops.ReleaseError, "HANDOFF_ACTIVE_RUN$"):
                    handoff.active_run(self.scope, startup_phase="builder-await-signer")
            self.assertEqual(out.getvalue(), "")

    def test_signer_startup_timeout_writes_no_recipient_and_reads_no_grant(self):
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.dict(os.environ, {"GITHUB_RUN_ID": str(self.scope.run_id),
                                          "PREVIEW_APPROVAL_SHA256": self.scope.approval}, clear=True), \
             mock.patch.object(preview, "authority", return_value=self.scope.revision), \
             mock.patch.object(ops, "api", return_value=self.metadata | {"status": "waiting"}) as api, \
             mock.patch.object(inputs.time, "monotonic", side_effect=[0, 0, 41, 41]), \
             mock.patch.object(inputs, "confirm_grant") as grant, \
             mock.patch.object(handoff, "create_recipient") as recipient, redirect_stdout(StringIO()) as out:
            stage = Path(directory)
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_INPUT_DEADLINE"):
                inputs.run(SimpleNamespace(operation="recipient", stage=stage))
            api.assert_called_once_with(f"actions/runs/{self.scope.run_id}", timeout=10)
            grant.assert_not_called()
            recipient.assert_not_called()
            self.assertEqual(list(stage.iterdir()), [])
            self.assertEqual(out.getvalue(), "WAIT: signer-startup waiting\n")

    def test_builder_authenticates_and_rechecks_recipient_only_while_active(self):
        record = {"apks": {"unsigned.apk": {"bytes": 1}}}
        scope = handoff.Scope(self.scope.run_id, 1, self.scope.revision, preview.attestation_sha(record))
        signer = {"id": 12, "run_id": scope.run_id, "head_sha": scope.revision, "run_attempt": 1,
                  "name": handoff.SIGN_JOB, "status": "in_progress", "conclusion": None}
        item = {"id": 14}
        for fetch_status, seal_status, current_item, error in (
            ("in_progress", "in_progress", item, None),
            ("waiting", "in_progress", item, "HANDOFF_ACTIVE_RUN_STATUS"),
            ("in_progress", "waiting", item, "HANDOFF_ACTIVE_RUN_STATUS"),
            ("in_progress", "in_progress", {"id": 15}, "PREVIEW_INPUT_RECIPIENT_CHANGED"),
        ):
            statuses = ("waiting", "queued", "in_progress", fetch_status, "in_progress", seal_status)
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory, \
                 mock.patch.object(ops, "api", side_effect=[self.metadata | {"status": s} for s in statuses]), \
                 mock.patch.object(inputs.time, "sleep") as sleep, \
                 mock.patch.object(inputs, "finished", return_value=True), \
                 mock.patch.object(handoff, "jobs_for_attempt", return_value={"total_count": 1, "jobs": [signer]}), \
                 mock.patch.object(inputs, "artifact", side_effect=[None, item, None, current_item]) as artifact, \
                 mock.patch.object(handoff, "fetch_wire") as fetch, mock.patch.object(handoff, "verify_proof") as proof, \
                 mock.patch.object(preview, "input_record", return_value=record), \
                 mock.patch.object(handoff, "encrypt_files") as encrypt, redirect_stdout(StringIO()) as out:
                stage = Path(directory)
                (stage / "receipt").mkdir()
                (stage / "receipt/receipt.json").write_bytes(preview.canonical_record(record))
                if error:
                    with self.assertRaisesRegex(ops.ReleaseError, error):
                        inputs.seal_inputs(scope, stage)
                    encrypt.assert_not_called()
                else:
                    inputs.seal_inputs(scope, stage)
                    encrypt.assert_called_once()
                self.assertEqual(sleep.call_count, 2)
                self.assertEqual(out.getvalue(), "WAIT: builder-await-signer waiting\nWAIT: builder-await-signer queued\n")
                if fetch_status == "waiting":
                    self.assertEqual(artifact.call_count, 2)
                    fetch.assert_not_called()
                    proof.assert_not_called()
                else:
                    fetch.assert_called_once_with(scope, "input-recipient", item, stage / "recipient")
                    proof.assert_called_once_with(scope, "input-recipient", stage / "recipient", stage / "tools")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
