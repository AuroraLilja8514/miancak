#!/usr/bin/env python3
"""Preview acceptance adds a legacy YAML byte-retention check to the formal smoke."""
import json
import os
from pathlib import Path
import shlex
import sys

import device_smoke as smoke
import preview_ops as preview
import release_ops as ops

RIME = f"/data/user/0/{ops.PACKAGE}/files/rime"
YAML_PATH = RIME + "/user/miancak_pinyin.custom.yaml"
YAML = b"# Synthetic legacy Rime settings\npatch:\n  'menu/page_size': 5\n"
NEW_SHARED = RIME + "/shared-3b8f1a6b0999dbacf0b0db3bde1e6d6c4049955891019d094e32140740b4c6cc"
MODEL_SHA = "e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5"


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


def main(prepared, signed):
    previous = prepared / "previous.apk"
    ops.require(previous.is_file() and ops.file_sha(previous) == preview.STABLE_APK
                and not smoke.split_resources(previous), "PREVIEW_ANDROID_BASELINE")
    ops.require(smoke.split_resources(signed / preview.APK), "PREVIEW_ANDROID_SPLIT")
    preview.signed_record(signed)
    sys.argv = [sys.argv[0], "--prepared", str(prepared), "--signed", str(signed),
                "--api", "35", "--timeout-seconds", "1200"]
    smoke.main(before_overwrite=seed_yaml, after_reboot=verify_rebooted_data)
    result = smoke.RESULTS / "acceptance.json"
    report = json.loads(result.read_bytes())
    report["legacy_yaml_preserved"] = True
    report["requires_online_pack_setup"] = True
    ops.write_json(result, report)


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
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
