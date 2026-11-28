#!/usr/bin/env python3
"""Runner-local baseline and one-tap download diagnosis. No signing or publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from zipfile import ZipFile


STAGES = {
    "guard", "install", "offline", "baseline-input", "baseline-launch", "baseline-settings",
    "baseline-theme", "baseline-extra-keys", "baseline-superscript", "baseline-editor",
    "baseline-candidate", "baseline-receiver", "baseline-language", "baseline-cleanup",
    "baseline-yaml", "baseline-persistence", "one-click", "entry-launcher", "entry-settings",
    "device-ready", "entry-plan", "entry-activity", "entry-package", "entry-unlock", "entry-viewport",
    "entry-instrumentation", "entry-screen-ready", "entry-launch-request", "entry-launch-intent",
    "entry-launcher-visible", "entry-launcher-action", "entry-launcher-label", "entry-selector-unmatched",
    "entry-launcher-tap", "entry-pack-files", "entry-ready-again", "entry-reopen", "entry-reopen-preserved",
    "entry-network", "entry-download", "entry-ready", "entry-offline", "entry-receiver",
}
STATES = {"started", "passed", "failed", "observed"}
BASELINE_SHA = "74e884ef6465c96b57daa174ae5f5ce585f2c55417b591c76b8b77f212684bd5"
BASELINE_CERT = "3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b"
PLAN_SHA = "e145053e7cc5498fc97200649edb14090b75ead3c425fc0ea00bc9639d7b18ae"
MANIFEST_SHA = "3b8f1a6b0999dbacf0b0db3bde1e6d6c4049955891019d094e32140740b4c6cc"
METHODS = {
    "launch": "baseline-launch", "settings": "baseline-settings", "scrollTo": "baseline-settings",
    "openExtraKeys": "baseline-extra-keys", "findCheckbox": "baseline-superscript",
    "checkboxChecked": "baseline-superscript", "setCheckbox": "baseline-superscript",
    "focusEditor": "baseline-editor", "type": "baseline-editor", "awaitEditor": "baseline-receiver",
    "screenshot": "baseline-cleanup",
}
ENTRY_ASSERTIONS = {
    "context requires the FLAG_ACTIVITY_NEW_TASK flag": "entry-launch-intent",
    "Expected MIANCAK activity did not gain window focus: LauncherActivity": "entry-activity",
    "Expected MIANCAK activity did not gain window focus: SettingsActivity": "entry-settings",
    "Expected MIANCAK activity did not gain window focus: WanxiangSetupActivity": "entry-launcher-tap",
    "Unexpected launcher package": "entry-package",
    "The launcher user is locked": "entry-unlock",
    "Expected a portrait phone viewport": "entry-viewport",
    "The launcher layout is missing its Wanxiang button": "entry-launcher-visible",
    "The launcher Wanxiang button is clipped or offscreen": "entry-launcher-visible",
    "The launcher Wanxiang button is disabled": "entry-launcher-action",
    "The launcher Wanxiang button has no click action": "entry-launcher-action",
    "Wanxiang launcher label does not match the active locale": "entry-launcher-label",
    "Could not deliver the Wanxiang launcher tap": "entry-launcher-tap",
    "Wanxiang entry must be visible in Settings without scrolling": "entry-settings",
    "The fresh guest must need the Wanxiang download": "entry-download",
    "Normal setup did not offer Download": "entry-download",
    "No default network for pinned HTTPS setup": "entry-network",
    "The exact HTTPS pack did not become ready": "entry-ready",
    "Device must return offline before using the model": "entry-offline",
    "The Chinese receiver must run offline": "entry-offline",
    "Fresh Chinese input lost or changed typed nihao": "entry-receiver",
    "Reopened setup did not show the installed pack ready offline": "entry-ready-again",
    "Reopened setup offered a duplicate Download": "entry-ready-again",
    "Reopened setup started another transfer": "entry-ready-again",
}
REOPEN_MARKERS = {"entry-instrumentation", "entry-screen-ready", "entry-launch-request", "entry-activity",
                  "entry-viewport", "entry-launcher-visible", "entry-launcher-tap", "entry-ready-again"}
INSTALL_MARKERS = REOPEN_MARKERS | {
    "entry-network", "entry-download", "entry-ready", "entry-offline", "entry-settings", "entry-receiver",
}


def entry_markers(log, path):
    observed = set()
    for label in re.findall(r"(?m)^INSTRUMENTATION_STATUS: miancak_stage=([a-z-]+)\r?$", log):
        if label in observed:
            continue
        if label in INSTALL_MARKERS or label == "entry-selector-unmatched":
            append_status(path, label, "observed" if label == "entry-selector-unmatched" else "passed")
            observed.add(label)
    return observed


def check_delivery(app):
    with ZipFile(app) as archive:
        raw_plan = archive.read("assets/rime/delivery.json")
        manifest = archive.read("assets/rime/manifest.json")
        if (hashlib.sha256(raw_plan).hexdigest() != PLAN_SHA
                or hashlib.sha256(manifest).hexdigest() != MANIFEST_SHA):
            raise ValueError("The app must use the reviewed Base and grammar pins")
        plan = json.loads(raw_plan)
        archive_paths = set(archive.namelist())
        if (any(name.endswith(".gram") for name in archive_paths)
                or any("assets/rime/" + item["path"] in archive_paths for item in plan["files"])):
            raise ValueError("The diagnostic must start without a bundled pack")
    base, = [item for item in plan["files"] if item["path"] == "dicts/jichu.dict.yaml"]
    return [(plan["model"]["filename"], plan["model"]["bytes"]), (base["path"], base["bytes"])]


def installed_files(smoke, package, expected):
    fingerprints = []
    for name, size in expected:
        path = f"files/rime/shared-{MANIFEST_SHA}/{name}"
        value = smoke.shell("run-as", package, "stat", "-c", "%i:%s:%Y", path)
        if not re.fullmatch(r"[0-9]+:[0-9]+:[0-9]+", value) or int(value.split(":")[1]) != size:
            raise ValueError("Installed resource file identity is unavailable or invalid")
        fingerprints.append(value)
    return fingerprints


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

        if not baseline:
            step = "device-ready"
            status("started")
            ops.require(smoke.shell("getprop", "sys.boot_completed") == "1", "DIAGNOSTIC_BOOT_INCOMPLETE")
            smoke.shell("wm", "size", "1080x1920")
            smoke.shell("wm", "density", "480")
            status("passed")
            step = "entry-plan"
            status("started")
            pack = check_delivery(app)
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
            package = smoke.PACKAGE + ".debug"
            for step, required in (("one-click", INSTALL_MARKERS), ("entry-reopen", REOPEN_MARKERS)):
                status("started")
                reopen = ["-e", "revisit", "true", "-e", "evidencePhase", "reopen"] if step == "entry-reopen" else []
                if reopen:
                    smoke.shell("am", "force-stop", package)
                log = smoke.shell("am", "instrument", "-w", "-r", "-e", "class", "juloo.keyboard2.FirstInstallTest",
                    "-e", "entryOrder", "settings-first", "-e", "setupMode", "https-one-click",
                    *reopen, package + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=900)
                (smoke.RESULTS / (step + ".log")).write_text(log)
                observed = entry_markers(log, args.status)
                if not ("OK (1 test)" in log and not re.search(r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", log)):
                    step = next((phase for assertion, phase in ENTRY_ASSERTIONS.items() if assertion in log), step)
                    raise RuntimeError("one-click fixture failed")
                if not required.issubset(observed):
                    raise RuntimeError("one-click fixture did not report all required milestones")
                status("passed")
                if reopen:
                    step = "entry-reopen-preserved"
                    status("started")
                    ops.require(fingerprint == installed_files(smoke, package, pack), "DIAGNOSTIC_PACK_CHANGED_ON_REOPEN")
                    status("passed")
                else:
                    step = "entry-pack-files"
                    status("started")
                    fingerprint = installed_files(smoke, package, pack)
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
