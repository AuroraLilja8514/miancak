#!/usr/bin/env python3
"""Actual signed APK installation, input, data preservation and real reboot."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import release_ops as ops

PACKAGE = ops.PACKAGE
RESULTS = Path("test-results/release")

def adb(*args, timeout=45, check=True):
    result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise ops.ReleaseError("RELEASE_ADB")
    return result.stdout

def shell(*args, **kwargs):
    return adb("shell", *args, **kwargs).decode("utf-8", errors="replace").strip()

def inactive():
    shell("ime", "set", original_ime)
    shell("am", "force-stop", PACKAGE)

def root():
    adb("root")
    adb("wait-for-device", timeout=120)
    ops.require(shell("id", "-u") == "0", "RELEASE_TEST_ROOT_UNAVAILABLE")

def snapshot(name):
    """Only public-test data hashes leave the emulator, never raw user files."""
    paths = []
    roots = ["/data/user/0/" + PACKAGE + "/files/rime/user",
             "/data/user/0/" + PACKAGE + "/shared_prefs",
             "/data/user_de/0/" + PACKAGE + "/shared_prefs"]
    for directory in roots:
        listing = shell("find", directory, "-type", "f", check=False)
        for path in listing.splitlines():
            if not path.startswith(directory + "/"):
                continue
            ops.require(re.fullmatch(r"[A-Za-z0-9_./-]+", path)
                        and ".." not in path.split("/"), "RELEASE_TEST_DATA_PATH")
            paths.append(path)
    values = {}
    for path in sorted(set(paths)):
        data = adb("exec-out", "cat", path)
        values[path] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    ops.require(any(".userdb" in path and value["bytes"] > 0 for path, value in values.items()),
                "RELEASE_LEARNING_NOT_PERSISTED")
    ops.require(any("/shared_prefs/" in path for path in values), "RELEASE_PREFS_NOT_PERSISTED")
    ops.write_json(RESULTS / (name + ".json"), values)
    return values

def phase(name):
    shell("ime", "enable", PACKAGE + "/juloo.keyboard2.Keyboard2")
    shell("ime", "set", PACKAGE + "/juloo.keyboard2.Keyboard2")
    text = shell("am", "instrument", "-w", "-e", "class", "juloo.keyboard2.ReleaseApkTest",
                 "-e", "releasePhase", name,
                 PACKAGE + ".debug.test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
    (RESULTS / (name + ".log")).write_text(text)
    passed = "OK (1 test)" in text and not re.search(
        r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", text)
    if not passed:
        # This job has no signing credentials; only our synthetic guest test
        # output is printed, so failures remain diagnosable without ZIP tools.
        print(text[-12000:], flush=True)
    ops.require(passed, "RELEASE_INPUT_" + name.upper())
    print("PASS: ACTUAL_SIGNED_APK_" + name.upper(), flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared", default="prepared")
    parser.add_argument("--signed", default="signed")
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=3600)
    args = parser.parse_args()
    def expired(signum, frame):
        raise ops.ReleaseError("RELEASE_DEVICE_TIMEOUT")
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(args.timeout_seconds)
    RESULTS.mkdir(parents=True, exist_ok=True)
    global original_ime
    original_ime = shell("settings", "get", "secure", "default_input_method")
    ops.require(original_ime and original_ime != "null" and not original_ime.startswith(PACKAGE + "/"),
                "RELEASE_TEST_INITIAL_IME")
    try:
        ops.require(int(shell("getprop", "ro.build.version.sdk")) == args.api, "RELEASE_TEST_API")
        root()
        prepared, signed = Path(args.prepared), Path(args.signed)
        record = json.loads((signed / "release-provenance.json").read_text())
        apk = signed / ("miancak-" + record["version"] + ".apk")
        ops.verify_signed(apk)
        ops.require(ops.file_sha(apk) == record["apk_sha256"], "RELEASE_TEST_APK_DIGEST")
        previous = prepared / "previous.apk"
        baseline = previous if previous.is_file() else apk
        ops.verify_signed(baseline)
        old = ops.inspect_apk(baseline)
        new = ops.inspect_apk(apk)
        if previous.is_file():
            ops.require(old["version_code"] < new["version_code"], "RELEASE_UPGRADE_VERSION")
        adb("install", "--no-incremental", str(prepared / "app-debug.apk"), timeout=180)
        adb("install", "--no-incremental", str(prepared / "app-debug-androidTest.apk"), timeout=180)
        adb("install", "--no-incremental", str(baseline), timeout=180)
        shell("svc", "wifi", "disable"); shell("svc", "data", "disable")
        shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        phase("prepare")
        inactive()
        before = snapshot("before-install")
        adb("install", "--no-incremental", "-r", str(apk), timeout=180)
        after = snapshot("after-install")
        ops.require(before == after, "RELEASE_UPGRADE_DATA_CHANGED")
        phase("verify")
        inactive()
        before_boot = snapshot("before-reboot")
        boot_before = shell("cat", "/proc/sys/kernel/random/boot_id")
        adb("reboot")
        adb("wait-for-device", timeout=120)
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            if shell("getprop", "sys.boot_completed", check=False) == "1":
                break
            time.sleep(2)
        else:
            raise ops.ReleaseError("RELEASE_REBOOT_TIMEOUT")
        root()
        boot_after = shell("cat", "/proc/sys/kernel/random/boot_id")
        ops.require(boot_after != boot_before, "RELEASE_REBOOT_NOT_REAL")
        ops.require(snapshot("after-reboot") == before_boot, "RELEASE_REBOOT_DATA_CHANGED")
        shell("input", "keyevent", "82")
        phase("rebooted")
        ops.write_json(RESULTS / "acceptance.json", {
            "api": args.api, "source_sha": record["source_sha"], "apk_sha256": record["apk_sha256"],
            "certificate_sha256": ops.CERTIFICATE,
            "baseline_version_code": old["version_code"], "version_code": new["version_code"],
            "scenario": "previous-formal-release-upgrade" if previous.is_file() else "bootstrap-same-version-reinstall",
            "input": "actual offline Chinese candidate commit and English typing",
            "data": "nonempty real userdb and preferences unchanged across install and reboot",
            "explicit_preferences": "Light theme and disabled Sup preserved",
            "real_reboot": True, "result": "passed",
        })
        print("PASS: SIGNED_APK_INSTALL_INPUT_UPGRADE_DATA_AND_REBOOT", flush=True)
    finally:
        adb("pull", "/sdcard/Android/data/" + PACKAGE + ".debug/files/release-results",
            str(RESULTS / "device"), timeout=90, check=False)
        signal.alarm(0)

if __name__ == "__main__":
    sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    try:
        main()
    except ops.ReleaseError as failure:
        print("FAIL: " + str(failure), file=sys.stderr)
        raise SystemExit(1)
