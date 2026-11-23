#!/usr/bin/env python3
"""Preview acceptance checks legacy YAML retention and functional custom YAML."""
import json
import os
from pathlib import Path
import re
import shlex
import sys

import android_progress as progress
from android_progress import Phase, Status
import device_smoke as smoke
import preview_ops as preview
import release_ops as ops

RIME = f"/data/user/0/{ops.PACKAGE}/files/rime"
YAML_PATH = RIME + "/user/miancak_pinyin.custom.yaml"
YAML = b"# Synthetic legacy Rime settings\npatch:\n  'menu/page_size': 5\n"
NEW_SHARED = RIME + "/shared-3b8f1a6b0999dbacf0b0db3bde1e6d6c4049955891019d094e32140740b4c6cc"
MODEL_SHA = "e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5"
CUSTOM_NAME = "wanxiang.custom.yaml"
CUSTOM = b"# Synthetic preview acceptance\npatch:\n  'switches/@7/reset': 1\n"
CUSTOM_SHA = ops.digest(CUSTOM)
CUSTOM_ID = ops.digest(CUSTOM_NAME.encode() + b"\0" + CUSTOM)
CUSTOM_SOURCE = "/sdcard/Download/" + CUSTOM_NAME
WORD_TABLES = RIME + "/word-tables"


def verify_yaml():
    ops.require(smoke.adb("exec-out", "cat", YAML_PATH) == YAML, "PREVIEW_LEGACY_YAML_CHANGED")


def seed_yaml():
    # Called only after the original named/empty disposable-guest guards,
    # real v0.3.2 input/learning and the test's force-stop.
    ops.require(smoke.shell("getenforce") == "Enforcing", "PREVIEW_GUEST_SELINUX")
    command = f"""set -euC
test ! -e {NEW_SHARED}
test ! -e {YAML_PATH}
owner=$(stat -c %u:%g {RIME}/user)
printf '%s' {shlex.quote(YAML.decode())} > {YAML_PATH}
chown "$owner" {YAML_PATH}
chmod 600 {YAML_PATH}
restorecon {YAML_PATH}
"""
    smoke.shell("sh -c " + shlex.quote(command))
    verify_yaml()


def verify_rebooted_data():
    verify_yaml()
    model = NEW_SHARED + "/wanxiang-lts-zh-hans.gram"
    printed = smoke.shell("sha256sum", model).split()
    ops.require(printed == [MODEL_SHA, model], "PREVIEW_ONLINE_PACK_AFTER_REBOOT")


def seed_custom_yaml():
    # This is the public Files picker input on the guarded disposable guest.
    # It never populates the application's catalog or selected deployment.
    command = f"""set -euC
mkdir -p /sdcard/Download
test ! -e {CUSTOM_SOURCE}
printf '%s' {shlex.quote(CUSTOM.decode())} > {CUSTOM_SOURCE}
"""
    smoke.shell("sh -c " + shlex.quote(command))
    ops.require(smoke.adb("exec-out", "cat", CUSTOM_SOURCE) == CUSTOM, "PREVIEW_CUSTOM_SOURCE_BYTES")


def custom_slot():
    configs = WORD_TABLES + "/config-patches"
    catalog = json.loads(smoke.adb("exec-out", "cat", configs + "/catalog.json"))
    matches = [entry for entry in catalog["patches"] if entry.get("id") == CUSTOM_ID]
    ops.require(len(matches) == 1 and matches[0]["filename"] == CUSTOM_NAME
                and matches[0]["sha256"] == CUSTOM_SHA and matches[0]["bytes"] == len(CUSTOM),
                "PREVIEW_CUSTOM_CATALOG")
    ops.require(smoke.adb("exec-out", "cat", configs + "/" + CUSTOM_ID + "/source.custom.yaml") == CUSTOM,
                "PREVIEW_CUSTOM_ORIGINAL")
    current = json.loads(smoke.adb("exec-out", "cat", WORD_TABLES + "/selection.json"))["current"]
    slot = current.get("slot", "")
    ops.require(current.get("base") == "bundled" and current.get("patches") == [CUSTOM_ID]
                and re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", slot),
                "PREVIEW_CUSTOM_SELECTION")
    return slot


def main(prepared, signed):
    progress.mark(Phase.BOOT, Status.PASSED)
    progress.mark(Phase.DRIVER, Status.STARTED)
    # Recheck the approved app and two driver digests after signing and before
    # handing any package to the disposable guest. This step has no signing key.
    revision = preview.workflow_revision("publish")
    preview.bind_signing_input(prepared, revision, os.environ.get("PREVIEW_APPROVAL_SHA256", ""))
    previous = prepared / "previous.apk"
    ops.require(previous.is_file() and ops.file_sha(previous) == preview.STABLE_APK
                and not smoke.split_resources(previous), "PREVIEW_ANDROID_BASELINE")
    ops.require(smoke.split_resources(signed / preview.APK), "PREVIEW_ANDROID_SPLIT")
    preview.signed_record(signed)
    progress.mark(Phase.DRIVER, Status.PASSED)
    sys.argv = [sys.argv[0], "--prepared", str(prepared), "--signed", str(signed),
                "--api", "35", "--timeout-seconds", "1200"]
    selected = None

    def after_verify():
        nonlocal selected
        selected = custom_slot()

    def after_reboot():
        verify_rebooted_data()
        ops.require(selected is not None and custom_slot() == selected, "PREVIEW_CUSTOM_REBOOT_SELECTION")

    smoke.main(before_overwrite=seed_yaml, before_verify=seed_custom_yaml, after_verify=after_verify,
               after_reboot=after_reboot, custom_yaml_sha=CUSTOM_SHA)
    progress.mark(Phase.REPORT, Status.STARTED)
    result = smoke.RESULTS / "acceptance.json"
    report = json.loads(result.read_bytes())
    report["legacy_yaml_preserved"] = True
    report["requires_online_pack_setup"] = True
    report["custom_yaml_activated_after_reboot"] = True
    ops.write_json(result, report)
    progress.mark(Phase.REPORT, Status.PASSED)


if __name__ == "__main__":
    if not (Path(__file__).resolve().parent / "run_with_timeout.py").is_file():
        sys.path.insert(0, str(preview.ROOT / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    try:
        ops.require(len(sys.argv) == 3 and os.environ.get("PREVIEW_MODE") == "publish",
                    "PREVIEW_ANDROID_ARGUMENTS")
        main(Path(sys.argv[1]), Path(sys.argv[2]))
    except ops.ReleaseError as failure:
        progress.failure(failure)
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception as failure:
        progress.failure(failure)
        raise
