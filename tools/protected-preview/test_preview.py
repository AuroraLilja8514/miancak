"""Host contracts for the unpublished, exact-target prerelease workflow."""
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import urllib.error

import yaml

import preview_device as device
import preview_ops as preview
import release_ops as ops

REVISION = "a" * 40
BUILD = 123
ARTIFACT = 456
APPROVAL = "c" * 64
DOWNLOAD = f"https://github.com/{ops.REPO}/releases/download/{preview.TAG}/{preview.ATTACHMENT}"


def release_state():
    return {
        f"git/ref/tags/{preview.TAG}": {
            "ref": f"refs/tags/{preview.TAG}", "object": {"type": "commit", "sha": preview.SOURCE,
            "url": f"https://api.github.com/repos/{ops.REPO}/git/commits/{preview.SOURCE}"},
        },
        f"releases/{preview.DRAFT_ID}": {
            "id": preview.DRAFT_ID, "tag_name": preview.TAG, "draft": True, "prerelease": True,
            "upload_url": f"https://uploads.github.com/repos/{ops.REPO}/releases/{preview.DRAFT_ID}/assets{{?name,label}}",
            "assets": [],
        },
        "releases/latest": {"id": preview.STABLE_ID, "tag_name": preview.STABLE_TAG,
                            "draft": False, "prerelease": False},
    }


def identity(*, old=False):
    return {"application_id": ops.PACKAGE, "version": "0.3.2" if old else "0.3.4",
            "version_code": 5 if old else 7, "min_sdk": 21 if old else 33,
            "debuggable": False, "abis": ops.ABIS}


def acceptance(sha):
    return {"api": 35, "source_sha": preview.SOURCE, "apk_sha256": sha,
            "certificate_sha256": ops.CERTIFICATE, "baseline_version_code": 5, "version_code": 7,
            "scenario": "previous-formal-release-upgrade", "result": "passed", "real_reboot": True,
            "legacy_yaml_preserved": True, "requires_online_pack_setup": True}


class Trust(unittest.TestCase):
    def trusted_env(self):
        owner = ops.REPO.split("/")[0]
        return {"PREVIEW_MODE": "publish", "GITHUB_REPOSITORY": ops.REPO,
                "GITHUB_REPOSITORY_ID": str(ops.REPO_ID), "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/master", "GITHUB_WORKFLOW_SHA": REVISION,
                "GITHUB_SHA": REVISION, "GITHUB_WORKFLOW_REF": f"{ops.REPO}/{preview.WORKFLOW}@refs/heads/master",
                "GITHUB_ACTOR": owner, "GITHUB_TRIGGERING_ACTOR": owner, "GITHUB_RUN_ATTEMPT": "1"}

    def test_current_master_and_exact_workflow_checkout(self):
        with mock.patch.dict(os.environ, self.trusted_env(), clear=True), \
             mock.patch.object(ops, "command", return_value=mock.Mock(stdout=REVISION.encode())), \
             mock.patch.object(ops, "api", return_value={"object": {"sha": REVISION}}) as api:
            self.assertEqual(preview.authority("publish"), REVISION)
            api.return_value = {"object": {"sha": "b" * 40}}
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_TRUSTED_REVISION"):
                preview.authority("publish")

    def test_reject_branch_tag_event_mode_actor_workflow_and_rerun(self):
        for key, value in {"GITHUB_REF": f"refs/tags/{preview.TAG}", "GITHUB_EVENT_NAME": "pull_request_target",
                           "PREVIEW_MODE": "build", "GITHUB_ACTOR": "other-writer",
                           "GITHUB_TRIGGERING_ACTOR": "other-writer", "GITHUB_REPOSITORY_ID": "0",
                           "GITHUB_WORKFLOW_REF": f"{ops.REPO}/.github/workflows/release.yml@refs/heads/master",
                           "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": preview.SOURCE}.items():
            with self.subTest(key=key), mock.patch.dict(os.environ, self.trusted_env() | {key: value}, clear=True), \
                 mock.patch.object(ops, "api") as api:
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_WORKFLOW_AUTHORITY"):
                    preview.authority("publish")
                api.assert_not_called()

    def test_dispatch_requires_completed_build_from_the_same_review(self):
        record = {"id": BUILD, "run_attempt": 1, "event": "workflow_dispatch", "head_branch": "master",
                  "head_sha": REVISION, "path": preview.WORKFLOW, "display_title": f"{preview.TAG} build",
                  "head_repository": {"id": ops.REPO_ID}, "actor": {"login": ops.REPO.split("/")[0]},
                  "status": "completed", "conclusion": "success"}
        with mock.patch.object(ops, "api", return_value=record):
            preview.build_run(BUILD, REVISION, completed=True)
        for key, value in {"path": ".github/workflows/ci.yml", "event": "push", "head_sha": preview.SOURCE,
                           "display_title": f"{preview.TAG} publish", "run_attempt": 2,
                           "conclusion": "failure", "status": "in_progress"}.items():
            with self.subTest(key=key), mock.patch.object(ops, "api", return_value=record | {key: value}):
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_BUILD_RUN"):
                    preview.build_run(BUILD, REVISION, completed=True)

    def test_exact_release_rejects_changed_target_and_stable_latest(self):
        state = release_state()
        with mock.patch.object(ops, "api", side_effect=state.__getitem__):
            self.assertEqual(preview.exact_release()[0]["id"], preview.DRAFT_ID)
            for endpoint, key, value in (
                (f"git/ref/tags/{preview.TAG}", "object", {"sha": "b" * 40, "type": "commit"}),
                (f"releases/{preview.DRAFT_ID}", "prerelease", False),
                (f"releases/{preview.DRAFT_ID}", "draft", False),
                (f"releases/{preview.DRAFT_ID}", "tag_name", "v0.3.4"),
                (f"releases/{preview.DRAFT_ID}", "upload_url", "https://example.invalid/assets"),
                ("releases/latest", "tag_name", preview.TAG),
            ):
                with self.subTest(key=key), mock.patch.dict(state[endpoint], {key: value}):
                    with self.assertRaises(ops.ReleaseError):
                        preview.exact_release()

    def test_manifest_policy_is_exact_and_roles_are_distinct(self):
        preview.require_identity(identity())
        preview.require_identity(identity(old=True), previous=True)
        for field, value in {"application_id": ops.PACKAGE + ".debug", "version_code": 6,
                             "version": "0.3.3", "min_sdk": 21, "debuggable": True,
                             "abis": ["x86_64"]}.items():
            with self.subTest(field=field), self.assertRaises(ops.ReleaseError):
                preview.require_identity(identity() | {field: value})
        with self.assertRaises(ops.ReleaseError):
            preview.require_identity(identity(), previous=True)

    def test_approval_binds_tooling_source_manifest_archive_and_all_apks(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for name in preview.INPUTS:
                (folder / name).write_bytes(name.encode())
            record = {"repository": ops.REPO, "tag": preview.TAG, "source_sha": preview.SOURCE,
                      "tree": preview.TREE, "application_tree_sha256": preview.APP_INPUT,
                      "trusted_revision": REVISION, "build_tools": preview.SDK_HASHES, "build_run_id": BUILD,
                      "artifact_id": ARTIFACT, "artifact_digest": "sha256:" + "d" * 64,
                      "apk_identity": identity(), "apks": {n: ops.file_sha(folder / n) for n in preview.INPUTS}}
            approval = preview.attestation_sha(record)
            ops.write_json(folder / "approved.json", record)
            ops.write_json(folder / "prepared.json", preview.signing_manifest(record))
            preview.bind_signing_input(folder, REVISION, approval)
            for field, value in {"trusted_revision": "f" * 40, "artifact_id": 9,
                                 "artifact_digest": "sha256:" + "a" * 64, "source_sha": "e" * 40}.items():
                with self.subTest(field=field), self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_APPROVED_ATTESTATION"):
                    preview.require_approval(record | {field: value}, approval)
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_APPROVED_ATTESTATION"):
                preview.require_approval(record | {"apks": record["apks"] | {"unsigned.apk": "9" * 64}}, approval)
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNING_INPUT"):
                preview.bind_signing_input(folder, "e" * 40, approval)
            (folder / "app-debug-androidTest.apk").write_bytes(b"different driver")
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNING_INPUT"):
                preview.bind_signing_input(folder, REVISION, approval)


class Publication(unittest.TestCase):
    def report(self, path):
        sha = ops.file_sha(path)
        return {"trusted_revision": REVISION, "approval_sha256": APPROVAL, "apk_sha256": sha,
                "preview_acceptance": acceptance(sha)}

    def test_missing_real_upgrade_or_offline_reboot_receipt_aborts(self):
        report = acceptance(APPROVAL)
        preview.require_acceptance(report, APPROVAL)
        for key, value in {"api": 33, "result": "failure", "baseline_version_code": 7, "version_code": 6,
                           "certificate_sha256": "e" * 64, "source_sha": "e" * 40, "apk_sha256": "e" * 64,
                           "legacy_yaml_preserved": 1, "requires_online_pack_setup": False,
                           "real_reboot": False}.items():
            with self.subTest(key=key), self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNED_ACCEPTANCE"):
                preview.require_acceptance(report | {key: value}, APPROVAL)

    def test_wrong_apk_certificate_or_forged_acceptance_cannot_reach_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            apk = folder / preview.APK
            apk.write_bytes(b"candidate")
            (folder / "SHA256SUMS.txt").touch()
            record = {
                **self.report(apk), "repository": ops.REPO, "source_sha": preview.SOURCE,
                "application_tree_sha256": preview.APP_INPUT, "application_id": ops.PACKAGE,
                "version": "0.3.4", "version_code": 7, "certificate_sha256": ops.CERTIFICATE,
                "release_run_id": 1, "previous_release": {"tag": preview.STABLE_TAG, "version": "0.3.2",
                    "version_code": 5, "apk_sha256": preview.STABLE_APK},
            }
            record_path = folder / "release-provenance.json"
            ops.write_json(record_path, record)
            with mock.patch.dict(os.environ, {"GITHUB_RUN_ID": "1"}), \
                 mock.patch.object(ops, "inspect_apk", return_value=identity()), \
                 mock.patch.object(preview, "require_split"), mock.patch.object(ops, "verify_signed") as verify, \
                 mock.patch.object(ops, "upload_asset") as upload:
                verify.side_effect = ops.ReleaseError("SIGNED_CERTIFICATE")
                with self.assertRaisesRegex(ops.ReleaseError, "SIGNED_CERTIFICATE"):
                    preview.publish(folder, REVISION, APPROVAL)
                verify.side_effect = None
                record["preview_acceptance"]["apk_sha256"] = "0" * 64
                ops.write_json(record_path, record)
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNED_ACCEPTANCE"):
                    preview.publish(folder, REVISION, APPROVAL)
                upload.assert_not_called()

    def test_upload_digest_then_exact_prerelease_then_anonymous_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            apk = folder / preview.APK
            apk.write_bytes(b"accepted signed bytes")
            record = self.report(apk)
            uploaded = {"id": 101, "state": "uploaded", "name": preview.ATTACHMENT,
                        "size": apk.stat().st_size, "digest": "sha256:" + record["apk_sha256"],
                        "browser_download_url": DOWNLOAD}
            states = release_state()
            endpoint = f"releases/{preview.DRAFT_ID}"
            writes = []

            def api(path, **kwargs):
                if kwargs:
                    self.assertEqual(path, endpoint)
                    self.assertEqual(kwargs["method"], "PATCH")
                    self.assertEqual(len(states[endpoint]["assets"]), 1)
                    self.assertEqual({k: kwargs["body"][k] for k in ("draft", "prerelease", "make_latest")},
                                     {"draft": False, "prerelease": True, "make_latest": "false"})
                    states[endpoint]["draft"] = False
                    writes.append("publish")
                return copy.deepcopy(states[path])

            def upload(draft, path):
                self.assertEqual((draft["id"], path.name), (preview.DRAFT_ID, preview.ATTACHMENT))
                self.assertEqual(ops.file_sha(path), record["apk_sha256"])
                states[endpoint]["assets"] = [uploaded]
                writes.append("upload")
                return uploaded

            def public(url, path):
                self.assertEqual((url, ops.file_sha(path)), (DOWNLOAD, record["apk_sha256"]))
                writes.append("download")

            with mock.patch.object(preview, "signed_record", return_value=record) as verify, \
                 mock.patch.object(ops, "api", side_effect=api), mock.patch.object(ops, "upload_asset", side_effect=upload), \
                 mock.patch.object(preview, "verify_public", side_effect=public), mock.patch("builtins.print"):
                preview.publish(folder, REVISION, APPROVAL)
                verify.assert_called_once_with(folder, accepted=True)
            self.assertEqual(writes, ["upload", "publish", "download"])
            self.assertEqual(states["releases/latest"]["tag_name"], "v0.3.2")

    def test_bad_digest_and_existing_assets_do_not_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            apk = folder / preview.APK
            apk.write_bytes(b"accepted signed bytes")
            record = self.report(apk)
            state = release_state()
            with mock.patch.object(preview, "signed_record", return_value=record), \
                 mock.patch.object(ops, "api", side_effect=state.__getitem__) as api, \
                 mock.patch.object(ops, "upload_asset", return_value={"state": "uploaded", "name": preview.ATTACHMENT,
                     "size": apk.stat().st_size, "digest": "sha256:" + "f" * 64}) as upload, \
                 mock.patch.object(preview, "verify_public") as public:
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_UPLOAD_CHECKSUM"):
                    preview.publish(folder, REVISION, APPROVAL)
                upload.assert_called_once()
                self.assertTrue(all(not call.kwargs for call in api.call_args_list))
                public.assert_not_called()
                state[f"releases/{preview.DRAFT_ID}"]["assets"] = [{"id": 1}]
                upload.reset_mock()
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_DRAFT_NOT_EMPTY"):
                    preview.publish(folder, REVISION, APPROVAL)
                upload.assert_not_called()

    def test_public_bytes_use_no_auth_and_reject_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "public.apk"
            apk.write_bytes(b"correct")
            redirect = urllib.error.HTTPError(DOWNLOAD, 302, "", {"Location":
                "https://release-assets.githubusercontent.com/object"}, None)
            with mock.patch.dict(os.environ, {"GH_TOKEN": "must-not-be-forwarded"}), \
                 mock.patch.object(preview.urllib.request, "build_opener") as builder:
                reader = builder.return_value.open
                reader.side_effect = [redirect, io.BytesIO(b"correct")]
                preview.verify_public(DOWNLOAD, apk)
                self.assertTrue(all("Authorization" not in call.args[0].headers for call in reader.call_args_list))
                reader.side_effect = [io.BytesIO(b"corrupt")]
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_PUBLIC_CHECKSUM"):
                    preview.verify_public(DOWNLOAD, apk)


class Workflow(unittest.TestCase):
    def test_workflow_is_manual_with_scoped_keys_and_no_early_signed_upload(self):
        path = preview.ROOT / preview.WORKFLOW
        if not path.is_file():
            path = preview.ROOT / "tools/release/protected-preview.yml.in"
        workflow = yaml.load(path.read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(set(workflow["on"]), {"workflow_dispatch"})
        self.assertEqual(workflow["on"]["workflow_dispatch"]["inputs"]["mode"]["options"], ["build", "publish"])
        jobs = workflow["jobs"]
        self.assertEqual([name for name, job in jobs.items() if job["permissions"].get("contents") == "write"],
                         ["publish"])
        self.assertEqual([name for name, job in jobs.items() if "environment" in job], ["sign-and-accept"])
        self.assertEqual(jobs["sign-and-accept"]["environment"], "release-signing")
        self.assertNotIn("needs", jobs["publish"])
        self.assertNotIn("needs", jobs["sign-and-accept"])
        self.assertEqual(jobs["publish"]["uses"], "./.github/workflows/protected-preview-publisher.yml")
        self.assertEqual(workflow["concurrency"]["cancel-in-progress"], "false")
        publisher_path = preview.ROOT / ".github/workflows/protected-preview-publisher.yml"
        publisher_workflow = yaml.load(publisher_path.read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(set(publisher_workflow["on"]), {"workflow_call"})
        publisher = publisher_workflow["jobs"]["publisher"]
        self.assertNotIn("environment", publisher)
        self.assertEqual(publisher["permissions"], jobs["publish"]["permissions"])
        for name, job in list(jobs.items()) + [("publisher", publisher)]:
            for step in job.get("steps", []):
                if "uses" in step:
                    self.assertRegex(step["uses"], r"@[0-9a-f]{40}$")
                if "run" in step:
                    subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)
                if any("secrets." in value for value in step.get("env", {}).values()):
                    self.assertEqual(name, "sign-and-accept")
                    self.assertIn("preview_ops.py sign", step["run"])
                    self.assertNotIn("GH_TOKEN", step["env"])
        steps = jobs["sign-and-accept"]["steps"]
        recipient = next(i for i, step in enumerate(steps) if "handoff.py await-recipient" in step.get("run", ""))
        sign = next(i for i, step in enumerate(steps) if "preview_ops.py sign" in step.get("run", ""))
        device_step = next(i for i, step in enumerate(steps) if "preview_device.py" in step.get("with", {}).get("script", ""))
        seal = next(i for i, step in enumerate(steps) if "preview_ops.py seal" in step.get("run", ""))
        self.assertTrue(recipient < sign < device_step < seal)
        self.assertIn("handoff.py encrypt", steps[seal]["run"])
        wire = [step for step in steps if "upload-artifact" in step.get("uses", "")]
        self.assertEqual(len(wire), 1)
        self.assertNotIn("if", wire[0])
        self.assertEqual([Path(value).name for value in wire[0]["with"]["path"].splitlines()],
                         ["accepted.age", "proof.json"])
        self.assertEqual(wire[0]["with"]["overwrite"], "false")
        publisher_wire = [step for step in publisher["steps"] if "upload-artifact" in step.get("uses", "")]
        self.assertEqual(len(publisher_wire), 1)
        self.assertEqual([Path(value).name for value in publisher_wire[0]["with"]["path"].splitlines()],
                         ["recipient.json", "proof.json"])

    def test_reboot_requires_unchanged_legacy_yaml_and_exact_downloaded_model(self):
        with mock.patch.object(device.smoke, "adb", return_value=device.YAML) as adb, \
             mock.patch.object(device.smoke, "shell") as shell:
            model = device.NEW_SHARED + "/wanxiang-lts-zh-hans.gram"
            shell.return_value = device.MODEL_SHA + "  " + model
            device.verify_rebooted_data()
            shell.return_value = "0" * 64 + "  " + model
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_ONLINE_PACK_AFTER_REBOOT"):
                device.verify_rebooted_data()
            adb.return_value = b"changed YAML"
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_LEGACY_YAML_CHANGED"):
                device.verify_rebooted_data()


if __name__ == "__main__":
    unittest.main()
