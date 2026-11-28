#!/usr/bin/env python3
"""Runner-local baseline and one-tap download diagnosis. No signing or publication."""
import argparse
import json
import os
from pathlib import Path
import re
import sys


STAGES = {
    "guard", "install", "offline", "baseline-input", "baseline-launch", "baseline-settings",
    "baseline-theme", "baseline-extra-keys", "baseline-superscript", "baseline-editor",
    "baseline-candidate", "baseline-receiver", "baseline-language", "baseline-cleanup",
    "baseline-yaml", "baseline-persistence", "one-click", "entry-launcher", "entry-settings",
    "entry-network", "entry-download", "entry-ready", "entry-offline", "entry-receiver",
}
STATES = {"started", "passed", "failed"}
BASELINE_SHA = "74e884ef6465c96b57daa174ae5f5ce585f2c55417b591c76b8b77f212684bd5"
BASELINE_CERT = "3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b"
METHODS = {
    "launch": "baseline-launch", "settings": "baseline-settings", "scrollTo": "baseline-settings",
    "openExtraKeys": "baseline-extra-keys", "findCheckbox": "baseline-superscript",
    "checkboxChecked": "baseline-superscript", "setCheckbox": "baseline-superscript",
    "focusEditor": "baseline-editor", "type": "baseline-editor", "awaitEditor": "baseline-receiver",
    "screenshot": "baseline-cleanup",
}
ENTRY_ASSERTIONS = {
    "Wanxiang must be visible on first launch without scrolling": "entry-launcher",
    "Wanxiang entry must be visible in Settings without scrolling": "entry-settings",
    "The Settings entry must open the actual download screen": "entry-settings",
    "Normal Chinese setup button was absent": "entry-launcher",
    "Setup should finish checking the local resources": "entry-settings",
    "The fresh guest must need the Wanxiang download": "entry-download",
    "Normal setup did not offer Download": "entry-download",
    "No default network for pinned HTTPS setup": "entry-network",
    "The exact HTTPS pack did not become ready": "entry-ready",
    "Device must return offline before using the model": "entry-offline",
    "Fresh Chinese input lost or changed typed nihao": "entry-receiver",
}


def baseline_failure(log, source):
    # Classify the known stack frame only. Guest messages, stack traces, and
    # generated preference/learning data must stay in runner-local storage.
    frames = re.findall(r"(?m)^\s*at juloo\.keyboard2\.ReleaseApkTest\.(\w+)"
                        r"\(ReleaseApkTest\.java:(\d{1,4})\)", log)
    lines = source.splitlines()
    for method, line in frames:
        if method in METHODS:
            return METHODS[method]
        number = int(line)
        if not 0 < number <= len(lines):
            continue
        text = lines[number - 1]
        if "getActiveNetwork()" in text:
            return "offline"
        if "candidate" in text or "produce 你好" in text:
            return "baseline-candidate"
        if "miancak_language" in text:
            return "baseline-language"
        if "theme" in text.lower() or "light" in text:
            return "baseline-theme"
        if "checkbox" in text.lower() or "setCheckbox" in text:
            return "baseline-superscript"
    return "baseline-input"


def append_status(status, stage, state):
    if stage not in STAGES or state not in STATES:
        raise ValueError("invalid diagnostic status")
    with status.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps([stage, state]) + "\n")


def report(status):
    if not status.is_file() or status.stat().st_size > 8192:
        print("MIANCAK_DIAG no-bounded-guest-status")
        return
    for raw in status.read_text().splitlines():
        event = json.loads(raw)
        if (isinstance(event, list) and len(event) == 2
                and all(isinstance(item, str) for item in event)
                and event[0] in STAGES and event[1] in STATES):
            print(f"MIANCAK_DIAG {event[0]} {event[1]}")


def run(args):
    tools = args.tools.resolve() / "tools/protected-preview"
    sys.path.insert(0, str(tools))
    import device_smoke as smoke
    import preview_device
    import release_ops as ops

    os.chdir(args.stage)
    app = args.stage / "app-debug.apk"
    test = args.stage / "app-debug-androidTest.apk"
    previous = args.stage / "previous.apk"
    smoke.RESULTS.mkdir(parents=True, exist_ok=True)
    step = "guard"
    original_ime = None
    baseline = args.mode == "baseline"

    def status(state):
        append_status(args.status, step, state)

    try:
        status("started")
        smoke.verify_release_target(35)
        for package in (smoke.PACKAGE + ".debug", smoke.PACKAGE + ".debug.test"):
            ops.require(smoke.shell("pm", "list", "packages", package) == "", "DEBUG_ALREADY_INSTALLED")
        ops.require(smoke.shell("getenforce") == "Enforcing", "DIAGNOSTIC_SELINUX")
        if baseline:
            original_ime = smoke.shell("settings", "get", "secure", "default_input_method")
            ops.require(original_ime and original_ime != "null"
                        and not original_ime.startswith(smoke.PACKAGE + "/"), "DIAGNOSTIC_INITIAL_IME")
            smoke.original_ime = original_ime
            smoke.root()
        status("passed")

        step = "install"
        status("started")
        for apk in (app, test):
            smoke.adb("install", "--no-incremental", str(apk), timeout=180)
        if baseline:
            ops.require(previous.stat().st_size == 12103321 and ops.file_sha(previous) == BASELINE_SHA,
                        "DIAGNOSTIC_BASELINE_DIGEST")
            ops.verify_signed(previous, expected=BASELINE_CERT, previous=True)
            old = ops.inspect_apk(previous)
            ops.require(old["version_code"] == 5 and not smoke.split_resources(previous), "DIAGNOSTIC_BASELINE")
            smoke.adb("install", "--no-incremental", str(previous), timeout=180)
        status("passed")

        step = "offline"
        status("started")
        smoke.shell("svc", "wifi", "disable")
        smoke.shell("svc", "data", "disable")
        status("passed")
        if baseline:
            smoke.shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
            step = "baseline-input"
            status("started")
            try:
                smoke.phase("prepare", setup_resources=False)
            except ops.ReleaseError:
                log = smoke.RESULTS / "prepare.log"
                if log.is_file():
                    step = baseline_failure(log.read_text(), args.source.read_text())
                raise
            status("passed")
            step = "baseline-cleanup"
            status("started")
            smoke.inactive()
            status("passed")
            step = "baseline-yaml"
            status("started")
            preview_device.seed_yaml()
            status("passed")
            step = "baseline-persistence"
            status("started")
            smoke.snapshot("baseline-only")
            status("passed")
        else:
            step = "one-click"
            status("started")
            log = smoke.shell("am", "instrument", "-w", "-e", "class", "juloo.keyboard2.FirstInstallTest",
                "-e", "entryOrder", "settings-first", "-e", "setupMode", "https-one-click",
                smoke.PACKAGE + ".debug.test/androidx.test.runner.AndroidJUnitRunner", timeout=900)
            (smoke.RESULTS / "one-click.log").write_text(log)
            if not ("OK (1 test)" in log and not re.search(r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", log)):
                step = next((phase for assertion, phase in ENTRY_ASSERTIONS.items() if assertion in log), step)
                raise RuntimeError("one-click fixture failed")
            status("passed")
    except BaseException:
        status("failed")
        raise
    finally:
        if original_ime is not None:
            smoke.shell("ime", "set", original_ime, check=False)


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("baseline", "one-click", "report"))
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--stage", type=Path)
    parser.add_argument("--tools", type=Path)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    if args.mode == "report":
        report(args.status)
    else:
        run(args)
