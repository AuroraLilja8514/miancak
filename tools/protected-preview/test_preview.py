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

import manifest_rehearsal as rehearsal
import preview_device as device
import preview_ops as preview
import release_ops as ops
import smoke_inputs

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
            "legacy_yaml_preserved": True, "requires_online_pack_setup": True,
            "custom_yaml_activated_after_reboot": True}


class Trust(unittest.TestCase):
    def test_driver_manifest_targets_only_the_independent_debug_app(self):
        declaration = (
            '      E: instrumentation (line=9)\n'
            '        A: http://schemas.android.com/apk/res/android:name(0x01010003)'
            '="androidx.test.runner.AndroidJUnitRunner"\n'
            '        A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)'
            '="org.miancak.keyboard.debug" (Raw: "org.miancak.keyboard.debug")\n'
        )
        apk = Path("driver.apk")
        sdk = Path("pinned-build-tools")
        with mock.patch.object(ops, "command", return_value=mock.Mock(stdout=declaration.encode())) as command:
            preview.require_driver_target(apk, sdk)
            command.assert_called_once_with(
                [sdk / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
            for invalid in ("", declaration + declaration,
                            declaration.replace(ops.PACKAGE + ".debug", ops.PACKAGE),
                            declaration.replace("AndroidJUnitRunner", "OtherRunner")):
                with self.subTest(invalid=invalid), self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_DRIVER_TARGET"):
                    command.return_value.stdout = invalid.encode()
                    preview.require_driver_target(apk, sdk)

    def trusted_env(self):
        owner = ops.REPO.split("/")[0]
        return {"PREVIEW_MODE": "publish", "GITHUB_REPOSITORY": ops.REPO,
                "GITHUB_REPOSITORY_ID": str(ops.REPO_ID), "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": "refs/heads/master", "GITHUB_WORKFLOW_SHA": REVISION,
                "GITHUB_SHA": REVISION, "GITHUB_WORKFLOW_REF": f"{ops.REPO}/{preview.WORKFLOW}@refs/heads/master",
                "GITHUB_ACTOR_ID": str(preview.OWNER_ID),
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
                           "GITHUB_ACTOR_ID": "1",
                           "GITHUB_TRIGGERING_ACTOR": "other-writer", "GITHUB_REPOSITORY_ID": "0",
                           "GITHUB_WORKFLOW_REF": f"{ops.REPO}/.github/workflows/release.yml@refs/heads/master",
                           "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": preview.SOURCE}.items():
            with self.subTest(key=key), mock.patch.dict(os.environ, self.trusted_env() | {key: value}, clear=True), \
                 mock.patch.object(ops, "api") as api:
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_WORKFLOW_AUTHORITY"):
                    preview.authority("publish")
                api.assert_not_called()

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

    def test_admission_has_one_exact_dispatch_and_fails_closed(self):
        state = release_state()
        source_url = f"git/commits/{preview.SOURCE}"
        draft_url = f"releases/{preview.DRAFT_ID}"
        baseline = {"name": preview.STABLE_APK_NAME, "state": "uploaded", "digest": f"sha256:{preview.STABLE_APK}"}
        state.update({source_url: {"sha": preview.SOURCE, "tree": {"sha": preview.TREE}},
                      "git/ref/heads/master": {"object": {"sha": REVISION}}})
        state["releases/latest"]["assets"] = [baseline]

        def read_only(path, **kwargs):
            if path == draft_url:
                raise ops.ReleaseError("API_HTTP_403")
            return state[path]

        with mock.patch.object(ops, "command", return_value=mock.Mock(stdout=REVISION.encode())), \
             mock.patch.object(ops, "api", side_effect=read_only) as api:
            with mock.patch.dict(os.environ, self.trusted_env() | {"PREVIEW_MODE": "approve-inputs"}, clear=True):
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_WORKFLOW_AUTHORITY"):
                    preview.admit("approve-inputs")
            with mock.patch.dict(os.environ, self.trusted_env(), clear=True):
                preview.admit("publish")
                self.assertNotIn(draft_url, [call.args[0] for call in api.call_args_list])
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_DRAFT_READ_API_HTTP_403"):
                    preview.exact_release()
                for endpoint, replacement, error in (
                    (source_url, {"tree": {"sha": "0" * 40}}, "PREVIEW_CANDIDATE_TREE"),
                    ("releases/latest", {"assets": [baseline, baseline]}, "PREVIEW_BASELINE_DIGEST"),
                    ("releases/latest", {"assets": [baseline | {"digest": "sha256:" + "0" * 64}]},
                     "PREVIEW_BASELINE_DIGEST"),
                    ("git/ref/heads/master", {"object": {"sha": "0" * 40}}, "PREVIEW_TRUSTED_REVISION"),
                ):
                    with self.subTest(error=error), mock.patch.dict(state[endpoint], replacement):
                        with self.assertRaisesRegex(ops.ReleaseError, error):
                            preview.admit("publish")
                with mock.patch.object(ops, "api", side_effect=ops.ReleaseError("API_HTTP_403")):
                    with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_MASTER_READ_API_HTTP_403"):
                        preview.admit("publish")

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
        old_apk = b"synthetic baseline"
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(preview, "STABLE_APK", ops.digest(old_apk)):
            folder = Path(directory)
            for name in preview.INPUTS:
                (folder / name).write_bytes(name.encode())
            with mock.patch.object(preview, "inspect_inputs", return_value=identity()):
                record = preview.input_record(folder, REVISION, BUILD)
            (folder / "previous.apk").write_bytes(old_apk)
            transport = {"id": ARTIFACT, "digest": "sha256:" + "d" * 64}
            approval = preview.attestation_sha(record)
            ops.write_json(folder / "approved.json", record)
            ops.write_json(folder / "transport.json", transport)
            ops.write_json(folder / "prepared.json", preview.signing_manifest(record, transport))
            with mock.patch.dict(os.environ, {"GITHUB_RUN_ID": str(BUILD)}):
                preview.bind_signing_input(folder, REVISION, approval)
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_INPUT_RECEIPT"):
                    preview.bind_signing_input(folder, "e" * 40, approval)
                ops.write_json(folder / "transport.json", transport | {"id": 9})
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNING_INPUT"):
                    preview.bind_signing_input(folder, REVISION, approval)
                ops.write_json(folder / "transport.json", transport)
                (folder / "previous.apk").write_bytes(b"wrong baseline")
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNING_INPUT"):
                    preview.bind_signing_input(folder, REVISION, approval)
                (folder / "previous.apk").write_bytes(old_apk)
                (folder / "app-debug-androidTest.apk").write_bytes(b"different driver")
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNING_INPUT"):
                    preview.bind_signing_input(folder, REVISION, approval)
            for field, value in {"trusted_revision": "f" * 40, "build_run_id": 9,
                                 "source_sha": "e" * 40, "driver_patch_sha256": "1" * 64}.items():
                with self.subTest(field=field), self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_APPROVED_ATTESTATION"):
                    preview.require_approval(record | {field: value}, approval)
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_APPROVED_ATTESTATION"):
                preview.require_approval(record | {"apks": record["apks"] | {"unsigned.apk": "9" * 64}}, approval)


class Publication(unittest.TestCase):
    def report(self, path):
        sha = ops.file_sha(path)
        return {"trusted_revision": REVISION, "approval_sha256": APPROVAL, "apk_sha256": sha,
                "preview_acceptance": acceptance(sha)}

    def test_missing_real_upgrade_or_offline_reboot_receipt_aborts(self):
        report = acceptance(APPROVAL)
        preview.require_acceptance(report, APPROVAL)
        retention_only = {key: value for key, value in report.items() if key != "custom_yaml_activated_after_reboot"}
        with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNED_ACCEPTANCE"):
            preview.require_acceptance(retention_only, APPROVAL)
        for key, value in {"api": 33, "result": "failure", "baseline_version_code": 7, "version_code": 6,
                           "certificate_sha256": "e" * 64, "source_sha": "e" * 40, "apk_sha256": "e" * 64,
                           "legacy_yaml_preserved": 1, "requires_online_pack_setup": False,
                           "real_reboot": False, "custom_yaml_activated_after_reboot": 1}.items():
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
                del record["preview_acceptance"]["custom_yaml_activated_after_reboot"]
                ops.write_json(record_path, record)
                with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_SIGNED_ACCEPTANCE"):
                    preview.publish(folder, REVISION, APPROVAL)
                record["preview_acceptance"]["custom_yaml_activated_after_reboot"] = True
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
    def test_required_ci_review_keeps_the_original_checks_and_blocks_uploads(self):
        path = ".github/workflows/ci.yml"
        base = "4ee785138c5a01029c2d6687523bcdef6207200d"
        original = yaml.load(subprocess.check_output(["git", "-C", preview.ROOT, "show", f"{base}:{path}"]),
                             Loader=yaml.BaseLoader)
        revised = yaml.load((preview.ROOT / path).read_bytes(), Loader=yaml.BaseLoader)
        required = {"generated", "unit", "build-debug"}
        self.assertEqual(revised["permissions"], {"contents": "read"})
        branch = "refs/heads/review/protected-preview-ciphertext"
        review = f"github.event_name == 'workflow_dispatch' && github.ref == '{branch}'"
        review_branches = json.dumps([
            "review/trace-protected-preview-android",
            "review/bootstrap-protected-release-request",
            rehearsal.HEAD_BRANCH,
        ], separators=(",", ":"))
        scope = (f"github.repository == '{ops.REPO}' && (({review}) || "
                 "(github.event_name == 'pull_request' && "
                 f"contains(fromJSON('{review_branches}'), github.head_ref)))")
        ordinary = "${{ !(" + scope + ") }}"
        restored = copy.deepcopy(revised)
        self.assertEqual(restored["env"].pop("MIANCAK_REVIEW_CI"), "${{ " + scope + " }}")
        self.assertEqual(restored["on"]["push"].pop("paths-ignore"), [rehearsal.DUMMY_PATH])
        self.assertEqual(restored["on"]["workflow_dispatch"]["inputs"]["owner_approval_probe"]["default"], "")
        restored["on"]["workflow_dispatch"] = original["on"]["workflow_dispatch"]
        self.assertIn(review, restored.pop("run-name"))
        self.assertIn(review, restored["concurrency"]["cancel-in-progress"])
        for group in ("owner-probe-hold", "owner-probe-approve"):
            self.assertIn(group, restored["concurrency"]["group"])
        restored["concurrency"] = original["concurrency"]
        for name in ("owner-smoke-hold", "owner-smoke-approve"):
            owner_job = restored["jobs"].pop(name)
            self.assertIn("github.event_name == 'workflow_dispatch'", owner_job["if"])
            self.assertIn("inputs.owner_approval_probe", owner_job["if"])
        for name in ("inbound-smoke-build", "inbound-smoke-approval", "inbound-smoke-gate", "inbound-smoke-receive"):
            job = restored["jobs"].pop(name)
            self.assertIn(f"github.ref == '{branch}'", job["if"])
            self.assertIn("github.event_name == 'workflow_dispatch' && inputs.owner_approval_probe == ''", job["if"])
            self.assertIn("github.actor == github.repository_owner", job["if"])
            self.assertIn("github.run_attempt == 1", job["if"])
        guard = None
        for name, job in restored["jobs"].items():
            if name not in required:
                self.assertEqual(job.pop("if"), ordinary)
                continue
            self.assertEqual(job["name"], name)
            self.assertEqual(job.pop("if"), "${{ !((" + review + f" && github.repository == '{ops.REPO}' "
                             "&& inputs.owner_approval_probe != '')) }}")
            check = job["steps"].pop(1)
            self.assertEqual(check["if"], "env.MIANCAK_REVIEW_CI == 'true'")
            guard = guard or check["run"]
            self.assertEqual(check["run"], guard)
            self.assertEqual(job["steps"].pop(1), {
                "name": rehearsal.REVIEW_STEP,
                "if": "env.MIANCAK_REVIEW_CI == 'true' && github.event_name == 'pull_request' && "
                      f"github.head_ref == '{rehearsal.HEAD_BRANCH}'",
                "run": "python3 tools/protected-preview/manifest_rehearsal.py pr-check --timeout-seconds 25",
            })
            checkout = job["steps"][0]
            self.assertEqual(checkout["with"].pop("fetch-depth"),
                             "${{ github.event_name == 'pull_request' && "
                             f"github.head_ref == '{rehearsal.HEAD_BRANCH}' && 2 || 1 }}}}")
            if not checkout["with"]:
                del checkout["with"]
            if name == "generated":
                cli = job["steps"].pop()
                self.assertEqual(cli["if"], "env.MIANCAK_REVIEW_CI == 'true' && github.event_name == 'pull_request'")
                self.assertIn("test_preview_cli.py --timeout-seconds 40", cli["run"])
                self.assertIn("test_android_progress.py --timeout-seconds 30", cli["run"])
                self.assertIn("test_manifest_rehearsal.py --timeout-seconds 40", cli["run"])
            for step in job["steps"]:
                uses = step.get("uses", "")
                if uses.startswith(("actions/upload-artifact@", "actions/cache@")):
                    condition = step.pop("if")
                    if condition == "always() && env.MIANCAK_REVIEW_CI != 'true'":
                        step["if"] = "always()"
                    else:
                        self.assertEqual(condition, ordinary)
                if uses.startswith("gradle/actions/setup-gradle@"):
                    self.assertEqual(step.pop("with"), {"cache-disabled": "${{ env.MIANCAK_REVIEW_CI == 'true' }}"})
        self.assertEqual(restored, original)
        head = subprocess.check_output(["git", "-C", preview.ROOT, "rev-parse", "HEAD"], text=True).strip()
        env = os.environ | {"GITHUB_SHA": head, "GITHUB_WORKFLOW_SHA": head, "GITHUB_REPOSITORY": ops.REPO,
                            "GITHUB_REPOSITORY_ID": str(ops.REPO_ID), "GITHUB_ACTOR_ID": str(315702774),
                            "GITHUB_EVENT_NAME": "workflow_dispatch",
                            "GITHUB_REF": branch, "GITHUB_RUN_ATTEMPT": "1",
                            "GITHUB_ACTOR": "owner", "GITHUB_REPOSITORY_OWNER": "owner"}
        self.assertIsNotNone(guard)
        subprocess.run(["bash", "-c", guard], cwd=preview.ROOT, env=env, check=True)
        for field, value in {"GITHUB_REF": "refs/heads/master", "GITHUB_WORKFLOW_SHA": "a" * 40,
                             "GITHUB_RUN_ATTEMPT": "2", "GITHUB_ACTOR": "someone-else",
                             "GITHUB_ACTOR_ID": "1"}.items():
            with self.subTest(field=field):
                result = subprocess.run(["bash", "-c", guard], cwd=preview.ROOT, env=env | {field: value})
                self.assertNotEqual(result.returncode, 0)
        for job in revised["jobs"].values():
            for step in job.get("steps", []):
                if "run" in step:
                    subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)

    def test_manifest_rehearsal_has_one_literal_trigger_and_a_read_only_job(self):
        workflow = yaml.load((preview.ROOT / rehearsal.WORKFLOW).read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(workflow["on"], {
            "push": {"branches": [rehearsal.BRANCH], "paths": [rehearsal.DUMMY_PATH]},
        })
        self.assertEqual(workflow["permissions"], {})
        self.assertEqual(set(workflow["jobs"]), {"verify-dummy-merge"})
        job = workflow["jobs"]["verify-dummy-merge"]
        self.assertEqual(job["permissions"], {"contents": "read", "actions": "read", "pull-requests": "read"})
        self.assertNotIn("environment", job)
        self.assertNotIn("secrets", json.dumps(job))
        self.assertNotIn("outputs", job)
        checkout, verify = job["steps"]
        self.assertRegex(checkout["uses"], r"^actions/checkout@[a-f0-9]{40}$")
        self.assertEqual(checkout["with"], {
            "ref": "${{ github.workflow_sha }}", "persist-credentials": "false", "fetch-depth": "0",
        })
        self.assertEqual(verify["env"], {"GH_TOKEN": "${{ github.token }}"})
        self.assertEqual(verify["run"],
                         "python3 tools/protected-preview/manifest_rehearsal.py push-check --timeout-seconds 75")

    def test_scoped_pull_request_checks_require_exact_draft_and_real_merge_parents(self):
        path = preview.ROOT / ".github/workflows/ci.yml"
        workflow = yaml.load(path.read_bytes(), Loader=yaml.BaseLoader)
        guard = workflow["jobs"]["generated"]["steps"][1]["run"]
        with tempfile.TemporaryDirectory() as directory:
            env = os.environ | {"GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@example.invalid",
                                "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@example.invalid"}

            def git(*args, **kwargs):
                return subprocess.check_output(["git", *args], cwd=directory, env=env, text=True, **kwargs).strip()

            git("init", "-q", "--initial-branch=fixture")
            tree = git("mktree", input="")
            base = git("commit-tree", tree, "-m", "base")
            head = git("commit-tree", tree, "-p", base, "-m", "head")
            merge = git("commit-tree", tree, "-p", base, "-p", head, "-m", "merge")
            git("update-ref", "HEAD", merge)
            event = {"number": 101, "repository": {"id": ops.REPO_ID},
                     "pull_request": {"draft": True,
                                      "head": {"ref": "review/trace-protected-preview-android",
                                               "sha": head, "repo": {"id": ops.REPO_ID}},
                                      "base": {"ref": "master", "sha": base, "repo": {"id": ops.REPO_ID}}}}
            event_path = Path(directory) / "event.json"
            env.update(GITHUB_SHA=merge, GITHUB_WORKFLOW_SHA=merge, GITHUB_REPOSITORY=ops.REPO,
                       GITHUB_REPOSITORY_ID=str(ops.REPO_ID), GITHUB_ACTOR_ID=str(315702774),
                       GITHUB_EVENT_PATH=str(event_path), GITHUB_EVENT_NAME="pull_request",
                       GITHUB_REF="refs/pull/101/merge", GITHUB_RUN_ATTEMPT="1",
                       GITHUB_ACTOR="owner", GITHUB_REPOSITORY_OWNER="owner")
            event_path.write_text(json.dumps(event))
            result = subprocess.run(["bash", "-c", guard], cwd=directory, env=env, text=True,
                                    check=True, capture_output=True)
            self.assertIn(f"PR #101 head {head}, test merge {merge}", result.stdout)
            for keys, wrong in ((("number",), 99), (("pull_request", "draft"), False),
                                (("pull_request", "head", "ref"), "other"),
                                (("pull_request", "head", "sha"), "a" * 40),
                                (("pull_request", "head", "repo", "id"), 1),
                                (("pull_request", "base", "ref"), "other"),
                                (("pull_request", "base", "sha"), head)):
                altered = copy.deepcopy(event)
                field = altered
                for key in keys[:-1]:
                    field = field[key]
                field[keys[-1]] = wrong
                with self.subTest(keys=keys):
                    event_path.write_text(json.dumps(altered))
                    result = subprocess.run(["bash", "-c", guard], cwd=directory, env=env, capture_output=True)
                    self.assertNotEqual(result.returncode, 0)

    def test_owner_probe_is_manual_read_only_and_uploads_only_the_dummy_receipt(self):
        jobs = yaml.load((preview.ROOT / ".github/workflows/ci.yml").read_bytes(), Loader=yaml.BaseLoader)["jobs"]
        for name in ("owner-smoke-hold", "owner-smoke-approve"):
            job = jobs[name]
            self.assertEqual(job["permissions"], {"contents": "read", "actions": "read"})
            self.assertIn("github.actor == github.repository_owner && github.run_attempt == 1", job["if"])
            self.assertNotIn("environment", job)
            rendered = json.dumps(job)
            for forbidden in ("secrets.", ".apk", "apksigner", "release-signing", "adb", "actions/cache"):
                self.assertNotIn(forbidden, rendered)
            for step in job["steps"]:
                if "uses" in step:
                    self.assertRegex(step["uses"], r"@[0-9a-f]{40}$")
            uploads = [step["with"]["path"] for step in job["steps"]
                       if step.get("uses", "").startswith("actions/upload-artifact@")]
            expected = ["${{ runner.temp }}/owner-smoke-receipt/receipt.json"] if name.endswith("hold") else []
            self.assertEqual(uploads, expected)

    def test_inbound_smoke_only_exposes_dummy_metadata_and_ciphertext(self):
        def read(path):
            return yaml.load((preview.ROOT / path).read_bytes(), Loader=yaml.BaseLoader)

        jobs = read(".github/workflows/ci.yml")["jobs"]
        receiver_workflow = read(smoke_inputs.RECEIVER_WORKFLOW)
        self.assertEqual(receiver_workflow["on"], "workflow_call")
        receiver = receiver_workflow["jobs"]["receiver"]
        caller = jobs["inbound-smoke-receive"]
        self.assertEqual(caller["uses"], "./" + smoke_inputs.RECEIVER_WORKFLOW)
        self.assertEqual(caller["needs"], "inbound-smoke-gate")
        self.assertIn("needs.inbound-smoke-gate.result == 'success'", caller["if"])
        self.assertIn("!cancelled()", caller["if"])
        self.assertEqual(caller["name"] + " / " + receiver["name"], smoke_inputs.RECEIVE)
        proof_permissions = {"contents": "read", "actions": "read", "attestations": "write", "id-token": "write"}
        self.assertEqual(caller["permissions"], proof_permissions)
        self.assertEqual(receiver["permissions"], proof_permissions)
        build = jobs["inbound-smoke-build"]
        self.assertEqual(build["name"], smoke_inputs.BUILD)
        for name, job in (("build", build), ("approval", jobs["inbound-smoke-approval"]),
                          ("gate", jobs["inbound-smoke-gate"]), ("receiver", receiver)):
            rendered = json.dumps(job)
            self.assertNotIn("environment", job)
            for forbidden in ("secrets.", ".apk", "apksigner", "release-signing", "adb", "contents\": \"write"):
                self.assertNotIn(forbidden, rendered)
            if name != "receiver":
                self.assertEqual(job["permissions"], {"contents": "read", "actions": "read"})
            for step in job["steps"]:
                if "run" in step:
                    subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)
        def uploads(job):
            return [[Path(line).name for line in step["with"]["path"].splitlines()]
                    for step in job["steps"] if step.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(uploads(build), [["receipt.json"], ["inputs.age"]])
        self.assertEqual(uploads(receiver), [["recipient.json", "proof.json"]])
        for name in ("inbound-smoke-approval", "inbound-smoke-gate"):
            self.assertEqual(uploads(jobs[name]), [])

    def test_workflow_is_manual_with_scoped_keys_and_no_early_signed_upload(self):
        path = preview.ROOT / preview.WORKFLOW
        if not path.is_file():
            path = preview.ROOT / "tools/release/protected-preview.yml.in"
        workflow = yaml.load(path.read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(workflow["on"], {"workflow_dispatch": ""})
        self.assertEqual(workflow["run-name"], preview.TAG + " publish")
        self.assertEqual(workflow["env"]["CANDIDATE"], preview.SOURCE)
        self.assertEqual(workflow["env"]["PREVIEW_MODE"], "publish")
        jobs = workflow["jobs"]
        self.assertEqual(set(jobs), {"admit", "build", "authorize", "sign-and-accept", "publish"})
        self.assertEqual([name for name, job in jobs.items() if job["permissions"].get("contents") == "write"],
                         ["publish"])
        self.assertFalse(any("environment" in job for job in jobs.values()))
        for name in ("build", "authorize", "sign-and-accept", "publish"):
            self.assertIn("admit", jobs[name]["needs"])
            self.assertNotIn("always()", jobs[name]["if"])
        admit = jobs["admit"]
        self.assertEqual(admit["permissions"], {"contents": "read"})
        self.assertIn("preview_ops.py admit", admit["steps"][-1]["run"])
        for requirement in ("github.event_name == 'workflow_dispatch'", "refs/heads/master",
                            "github.actor == github.repository_owner", "github.run_attempt == 1"):
            self.assertIn(requirement, admit["if"])
        self.assertEqual(jobs["sign-and-accept"]["needs"], ["admit", "authorize"])
        self.assertEqual(jobs["publish"]["needs"], ["admit", "authorize", "build"])
        self.assertEqual(workflow["concurrency"]["group"], f"miancak-protected-preview-{preview.TAG}-production")
        self.assertEqual(jobs["publish"]["uses"], "./.github/workflows/protected-preview-publisher.yml")
        self.assertEqual(jobs["sign-and-accept"]["uses"], "./.github/workflows/protected-preview-signer.yml")
        signer_path = preview.ROOT / ".github/workflows/protected-preview-signer.yml"
        signer_workflow = yaml.load(signer_path.read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(set(signer_workflow["on"]), {"workflow_call"})
        signer = signer_workflow["jobs"]["signer"]
        self.assertEqual(signer["environment"], "release-signing")
        self.assertEqual(signer["permissions"], jobs["sign-and-accept"]["permissions"])
        self.assertEqual(jobs["build"]["permissions"], {"contents": "read", "actions": "read"})
        gate = jobs["authorize"]
        self.assertEqual(gate["permissions"], {"contents": "read", "actions": "read",
                                               "id-token": "write", "attestations": "write"})
        self.assertFalse(any("candidate" in step.get("with", {}).get("path", "") for step in gate["steps"]))
        self.assertEqual(set(signer_workflow["on"]["workflow_call"]["inputs"]),
                         {"approval_sha256", "grant_artifact_id"})
        signing_secrets = {
            "MIANCAK_RELEASE_KEYSTORE_B64": "${{ secrets.MIANCAK_RELEASE_KEYSTORE_B64 }}",
            "MIANCAK_RELEASE_STORE_PASSWORD": "${{ secrets.MIANCAK_RELEASE_STORE_PASSWORD }}",
        }
        self.assertEqual([name for name, job in jobs.items() if "secrets" in job], ["sign-and-accept"])
        self.assertEqual(jobs["sign-and-accept"]["secrets"], signing_secrets)
        self.assertEqual(signer_workflow["on"]["workflow_call"]["secrets"],
                         {name: {"required": "true"} for name in signing_secrets})
        self.assertEqual(jobs["sign-and-accept"]["with"], {
            "approval_sha256": "${{ needs.authorize.outputs.approval_sha256 }}",
            "grant_artifact_id": "${{ needs.authorize.outputs.grant_artifact_id }}",
        })
        self.assertEqual(workflow["concurrency"]["cancel-in-progress"], "false")
        publisher_path = preview.ROOT / ".github/workflows/protected-preview-publisher.yml"
        publisher_workflow = yaml.load(publisher_path.read_bytes(), Loader=yaml.BaseLoader)
        self.assertEqual(set(publisher_workflow["on"]), {"workflow_call"})
        publisher = publisher_workflow["jobs"]["publisher"]
        self.assertNotIn("environment", publisher)
        self.assertEqual(publisher["permissions"], jobs["publish"]["permissions"])
        publisher_steps = publisher["steps"]
        draft_preflight = next(i for i, step in enumerate(publisher_steps)
                               if "handoff.py recipient" in step.get("run", ""))
        recipient_publication = next(i for i, step in enumerate(publisher_steps)
                                     if "upload-artifact" in step.get("uses", ""))
        accepted_receive = next(i for i, step in enumerate(publisher_steps)
                                if "handoff.py receive" in step.get("run", ""))
        self.assertTrue(draft_preflight < recipient_publication < accepted_receive)
        self.assertNotIn("continue-on-error", publisher_steps[draft_preflight])
        self.assertNotIn("if", publisher_steps[recipient_publication])
        for name, job in list(jobs.items()) + [("publisher", publisher), ("signer", signer)]:
            for step in job.get("steps", []):
                if "uses" in step:
                    self.assertRegex(step["uses"], r"@[0-9a-f]{40}$")
                if "run" in step:
                    subprocess.run(["bash", "-n"], input=step["run"], text=True, check=True)
                if any("secrets." in value for value in step.get("env", {}).values()):
                    self.assertEqual(name, "signer")
                    self.assertIn("preview_ops.py sign", step["run"])
                    self.assertNotIn("GH_TOKEN", step["env"])
        steps = signer["steps"]
        input_recipient = next(i for i, step in enumerate(steps) if "private_inputs.py recipient" in step.get("run", ""))
        input_recipient_publication = next(i for i, step in enumerate(steps) if "upload-artifact" in step.get("uses", ""))
        inbound = next(i for i, step in enumerate(steps) if "private_inputs.py receive" in step.get("run", ""))
        recipient = next(i for i, step in enumerate(steps) if "handoff.py await-recipient" in step.get("run", ""))
        pre_key = next(i for i, step in enumerate(steps) if "private_inputs.py pre-key" in step.get("run", ""))
        sign = next(i for i, step in enumerate(steps) if "preview_ops.py sign" in step.get("run", ""))
        device_step = next(i for i, step in enumerate(steps) if "preview_device.py" in step.get("run", ""))
        seal = next(i for i, step in enumerate(steps) if "preview_ops.py seal" in step.get("run", ""))
        self.assertTrue(input_recipient < input_recipient_publication < inbound <= recipient < pre_key
                        < sign < device_step < seal)
        self.assertEqual(steps[sign]["env"], signing_secrets)
        self.assertNotIn("continue-on-error", steps[recipient])
        self.assertNotIn("continue-on-error", steps[device_step])
        self.assertNotIn("if", steps[seal])
        self.assertIn("handoff.py encrypt", steps[seal]["run"])
        wire = [step for step in steps if "upload-artifact" in step.get("uses", "")]
        self.assertEqual(len(wire), 2)
        self.assertNotIn("if", wire[1])
        self.assertEqual([Path(value).name for value in wire[1]["with"]["path"].splitlines()],
                         ["accepted.age", "proof.json"])
        self.assertEqual(wire[1]["with"]["overwrite"], "false")
        self.assertEqual([Path(value).name for value in wire[0]["with"]["path"].splitlines()],
                         ["recipient.json", "proof.json"])
        build_wire = [step for step in jobs["build"]["steps"] if "upload-artifact" in step.get("uses", "")]
        self.assertEqual([Path(step["with"]["path"]).name for step in build_wire],
                         ["receipt.json", "inputs.age"])
        gate_proof = next(step for step in gate["steps"] if "actions/attest@" in step.get("uses", ""))
        self.assertEqual(gate_proof["with"]["predicate"], '{"role":"input-grant"}')
        gate_wire = [step for step in gate["steps"] if "upload-artifact" in step.get("uses", "")]
        self.assertEqual([Path(value).name for value in gate_wire[0]["with"]["path"].splitlines()],
                         ["grant.json", "proof.json"])
        self.assertEqual(len(gate_wire), 1)
        self.assertEqual(gate["outputs"]["grant_artifact_id"], "${{ steps.grant.outputs.artifact-id }}")
        publisher_wire = [step for step in publisher["steps"] if "upload-artifact" in step.get("uses", "")]
        self.assertEqual(len(publisher_wire), 1)
        self.assertEqual([Path(value).name for value in publisher_wire[0]["with"]["path"].splitlines()],
                         ["recipient.json", "proof.json"])

    def test_private_build_signing_and_guest_output_stays_private_on_success_and_failure(self):
        def job(file, name):
            path = preview.ROOT / ".github/workflows" / file
            return yaml.load(path.read_bytes(), Loader=yaml.BaseLoader)["jobs"][name]

        build = job("protected-preview.yml", "build")
        signer = job("protected-preview-signer.yml", "signer")
        cases = (
            (build, "PREVIEW_PRIVATE_BUILD", "compiler.log", ""),
            (signer, "PREVIEW_SIGNING", "signing-private.log", "PASS: PREVIEW_SIGNING\n"),
            (signer, "PREVIEW_ANDROID_ACCEPTANCE", "acceptance-private.log", "PASS: PREVIEW_ANDROID_ACCEPTANCE\n"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java = root / "java"
            executables = java / "bin"
            executables.mkdir(parents=True)
            for name, script in {
                "git": '#!/bin/sh\nprintf "%s\\n" "$PREVIEW_EMULATOR_RUNNER"\n',
                "python3": '''#!/bin/sh
case "$1" in
  */android_progress.py) shift; exec /usr/bin/python3 "$MOCK_PROGRESS_HELPER" "$@" ;;
esac
echo private-stdout
echo private-stderr >&2
exit "$MOCK_EXIT"
''',
                "node": """#!/usr/bin/python3
import os
import subprocess
import sys
assert sys.argv[1].endswith("/.preview-emulator/lib/main.js")
assert os.environ["INPUT_API-LEVEL"] == os.environ["INPUT_SYSTEM-IMAGE-API-LEVEL"] == "35"
assert os.environ["INPUT_EMULATOR-BUILD"] == "16428233"
assert os.environ["INPUT_AVD-NAME"] == "miancak-release-35"
assert os.environ["INPUT_TARGET"] == "google_apis" and os.environ["INPUT_ARCH"] == "x86_64"
assert "-accel on" in os.environ["INPUT_EMULATOR-OPTIONS"]
assert "-gpu swangle" in os.environ["INPUT_EMULATOR-OPTIONS"]
assert os.environ["INPUT_EMULATOR-BOOT-TIMEOUT"] == "600"
prelaunch = os.environ["INPUT_PRE-EMULATOR-LAUNCH-SCRIPT"]
assert "android_progress.py" in prelaunch
subprocess.run(["sh", "-n", "-c", prelaunch], check=True)
subprocess.run(["/usr/bin/python3", os.environ["MOCK_PROGRESS_HELPER"], "before-boot"], check=True)
assert "preview_device.py" in os.environ["INPUT_SCRIPT"]
print("private-stdout")
print("private-stderr", file=sys.stderr)
raise SystemExit(int(os.environ["MOCK_EXIT"]))
""",
            }.items():
                stub = executables / name
                stub.write_text(script)
                stub.chmod(0o700)
            env = {"PATH": f"{executables}:/usr/bin:/bin", "JAVA_HOME_17_X64": str(java),
                   "GITHUB_WORKSPACE": directory, "RUNNER_TEMP": directory,
                   "PYTHONDONTWRITEBYTECODE": "1",
                   "MOCK_PROGRESS_HELPER": str(preview.ROOT / "tools/protected-preview/android_progress.py"),
                   "PREVIEW_EMULATOR_RUNNER": signer["env"]["PREVIEW_EMULATOR_RUNNER"]}
            signing_env = env | {"MIANCAK_RELEASE_KEYSTORE_B64": "synthetic-keystore",
                                 "MIANCAK_RELEASE_STORE_PASSWORD": "synthetic-password"}
            signing_step = next(step for step in signer["steps"] if "preview_ops.py sign" in step.get("run", ""))
            for missing in signing_step["env"]:
                with self.subTest(missing=missing):
                    result = subprocess.run(["bash", "-c", signing_step["run"]], cwd=root, text=True,
                                            capture_output=True, env=signing_env | {missing: ""})
                    self.assertEqual(result.returncode, 1)
                    self.assertEqual(result.stdout, "FAIL: PREVIEW_SIGNING_ENVIRONMENT_CREDENTIALS_UNAVAILABLE\n")
                    self.assertEqual(result.stderr, "")
                    self.assertFalse((root / "miancak-preview" / "signing-private.log").exists())
            for spec, error, log, success in cases:
                run = next(step["run"] for step in spec["steps"] if f"FAIL: {error}" in step.get("run", ""))
                stage = root / ("miancak-preview-build" if spec is build else "miancak-preview")
                stage.mkdir(exist_ok=True)
                step_env = signing_env if error == "PREVIEW_SIGNING" else env
                for status in (0, 2):
                    with self.subTest(step=error, status=status):
                        result = subprocess.run(["bash", "-c", run], cwd=root, text=True, capture_output=True,
                                                env=step_env | {"MOCK_EXIT": str(status)})
                        self.assertEqual(result.returncode, 1 if status else 0)
                        expected = f"FAIL: {error}\n" if status else success
                        if error == "PREVIEW_ANDROID_ACCEPTANCE":
                            lines = result.stdout.splitlines(keepends=True)
                            self.assertEqual(lines.pop(0), "PREVIEW_ANDROID emulator-action started +0s\n")
                            self.assertRegex(lines.pop(0), r"^PREVIEW_ANDROID emulator-boot started \+\d+s\n$")
                            if status:
                                self.assertEqual(lines.pop(0), "PREVIEW_ANDROID emulator-boot runner-failed\n")
                            self.assertEqual(lines, [expected])
                        else:
                            self.assertEqual(result.stdout, expected)
                        self.assertEqual(result.stderr, "")
                        self.assertCountEqual((stage / log).read_text().splitlines(),
                                              ["private-stdout", "private-stderr"])

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

    def test_custom_original_and_exact_applied_selection_must_both_exist(self):
        slot = "01234567-89ab-cdef-0000-0123456789ab"
        catalog = {"patches": [{"id": device.CUSTOM_ID, "filename": device.CUSTOM_NAME,
                               "sha256": device.CUSTOM_SHA, "bytes": len(device.CUSTOM)}]}
        applied = {"current": {"base": "bundled", "slot": slot, "patches": [device.CUSTOM_ID]}}
        original = json.dumps(catalog).encode()
        with mock.patch.object(device.smoke, "adb", side_effect=[original, device.CUSTOM,
                                                                json.dumps(applied).encode()]):
            self.assertEqual(device.custom_slot(), slot)
        with mock.patch.object(device.smoke, "adb", side_effect=[original, b"changed"]):
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_CUSTOM_ORIGINAL"):
                device.custom_slot()
        applied["current"]["patches"] = []
        with mock.patch.object(device.smoke, "adb", side_effect=[original, device.CUSTOM,
                                                                json.dumps(applied).encode()]):
            with self.assertRaisesRegex(ops.ReleaseError, "PREVIEW_CUSTOM_SELECTION"):
                device.custom_slot()


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
