#!/usr/bin/env python3
"""Actual signed APK installation, input, data preservation and real reboot."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import selectors
import shlex
import signal
import subprocess
import sys
import time
from zipfile import ZipFile
import release_ops as ops

PACKAGE = ops.PACKAGE
RESULTS = Path("test-results/release")
DIAGNOSTIC_SECONDS = 25
DIAGNOSTIC_PROBE_SECONDS = 3
DIAGNOSTIC_READ_LIMIT = 512 * 1024
DIAGNOSTIC_FILE_LIMIT = 64 * 1024
DIAGNOSTIC_REMOTE_STATUS = "MIANCAK_DIAGNOSTIC_EXIT="

def diagnostic_remote_command(script, arguments=()):
    """One explicitly quoted, non-PTY command, with a legacy-adb-safe status.

    shell v1 and exec-out do not convey the remote exit code. The fixed
    trailer distinguishes an unreadable probe from an empty successful read.
    Scripts are static read-only probes; only allowlisted trace paths vary.
    """
    script = ('{ ' + script + '; } 2>&1; diagnostic_status=$?; '
              'printf "\\n' + DIAGNOSTIC_REMOTE_STATUS + '%s\\n" "$diagnostic_status"')
    return "sh -c " + shlex.quote(script) + " diagnostic-read" + "".join(
        " " + shlex.quote(argument) for argument in arguments)

def diagnostic_remote_output(text, record):
    record = dict(record)
    marker = re.search(r"\r?\n" + re.escape(DIAGNOSTIC_REMOTE_STATUS) + r"([0-9]{1,3})\r?\n\Z", text)
    if not marker or int(marker[1]) > 255:
        if record["status"] == "captured":
            record.update(status="unavailable", reason="remote-status-missing-or-invalid")
        return text, record
    text = text[:marker.start()]
    record["remote_returncode"] = int(marker[1])
    if record["status"] == "captured":
        if record["remote_returncode"]:
            record["status"] = "remote-error"
        # dumpsys can return zero even when the service rejects its command.
        elif re.search(r"(?m)^(?:Bad activity command, or no activities match:|Can't find service:|"
                       r"Permission Denial:|DUMP TIMEOUT|Error dumping service info:|"
                       r"(?:/system/bin/)?sh:|ls:|cat:|/data/anr:)", text):
            record.update(status="unavailable", reason="remote-error-response")
    return text, record

def diagnostic_capture(command, timeout, limit=DIAGNOSTIC_READ_LIMIT):
    """Bound the host adb reader too; never signal or restart an app/guest."""
    end = time.monotonic() + timeout
    output = bytearray()
    result = {"status": "unavailable", "truncated": False}
    process = None
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        with selectors.DefaultSelector() as ready:
            ready.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = end - time.monotonic() - .1  # reserve bounded host-child cleanup
                if remaining <= 0 or not ready.select(remaining):
                    result["status"] = "timeout"
                    break
                chunk = os.read(process.stdout.fileno(), min(8192, limit + 1 - len(output)))
                if not chunk:
                    code = process.wait(timeout=max(0, end - time.monotonic()))
                    result.update(status="captured" if code == 0 else "command-error", returncode=code)
                    break
                output.extend(chunk)
                if len(output) > limit:
                    result.update(status="output-limit", truncated=True)
                    break
    except subprocess.TimeoutExpired:
        result["status"] = "timeout"
    except (OSError, ValueError) as error:
        result.update(status="unavailable", error_type=type(error).__name__)
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()  # the local adb reader only, not a device process
            try:
                process.wait(timeout=max(0, end - time.monotonic()))
            except subprocess.TimeoutExpired:
                pass
            if process.stdout is not None:
                process.stdout.close()
    result["read_bytes"] = min(len(output), limit)
    return bytes(output[:limit]).decode("utf-8", errors="replace"), result

def diagnostic_directory():
    """Do not follow result-directory symlinks or accept another destination."""
    root = Path.cwd().absolute()
    expected = root / "test-results/release"
    if RESULTS.absolute() != expected:
        raise ValueError("diagnostic destination must be test-results/release")
    current = root
    for component in ("test-results", "release", "failure-diagnostics"):
        current /= component
        if current.is_symlink():
            raise ValueError("diagnostic destination is a symlink")
        current.mkdir(exist_ok=True)
    return current

def diagnostic_write(directory, name, text):
    if not re.fullmatch(r"[a-z][a-z0-9-]*\.(?:txt|json)", name):
        raise ValueError("invalid diagnostic filename")
    data = text.encode("utf-8", errors="replace")
    truncated = len(data) > DIAGNOSTIC_FILE_LIMIT
    if truncated:
        marker = b"\n[diagnostic output truncated]\n"
        data = data[:DIAGNOSTIC_FILE_LIMIT - len(marker)].decode("utf-8", errors="ignore").encode() + marker
    fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
    return len(data), truncated

def focused_diagnostic(text, extra_pattern=None):
    """Bound local app-related context; this is not personal-data redaction."""
    lines = text.splitlines()
    pattern = re.escape(PACKAGE) + r"(?=[:/\s.})]|$)"
    if extra_pattern:
        pattern += "|" + extra_pattern
    indexes = set()
    for index, line in enumerate(lines):
        if re.search(pattern, line):
            indexes.update(range(max(0, index - 3), min(len(lines), index + 16)))
    return "\n".join(lines[index] for index in sorted(indexes)) + ("\n" if indexes else "")

def matching_anr_traces(text):
    """Keep this app's blocks locally; app stacks are not a public-data sanitizer."""
    result = []
    for block in re.split(r"(?m)(?=^----- pid [0-9]+ at )", text):
        if not block.startswith("----- pid "):
            continue
        command = re.search(r"(?m)^Cmd line: ([^\r\n]+)\r?$", block)
        if command and re.fullmatch(re.escape(PACKAGE) + r"(?::[A-Za-z0-9_.-]+)?", command[1].strip()):
            end = re.search(r"(?m)^----- end [0-9]+ -----\s*$", block)
            result.append(block[:end.end()] if end else block)
    return "\n".join(result)

def collect_failure_diagnostics(api, failure):
    """Read-only, failure-only evidence from named disposable release guests.

    This cannot turn an unavailable probe or a failed APK gate into a pass.
    All commands share a small ceiling and the existing outer watchdog.
    """
    started = time.monotonic()
    deadline = started + DIAGNOSTIC_SECONDS
    inherited = os.environ.get("MIANCAK_TIMEOUT_DEADLINE")
    if inherited:
        try:
            inherited = float(inherited)
            deadline = min(deadline, inherited) if math.isfinite(inherited) else started
        except ValueError:
            deadline = started
    directory = diagnostic_directory()
    report = {"scope": "unverified", "acceptance_result": "failed",
              "failure_type": type(failure).__name__, "probes": {}}
    if isinstance(failure, ops.ReleaseError) and re.fullmatch(r"[A-Z0-9_]{1,100}", str(failure)):
        report["failure_code"] = str(failure)

    def probe(name, command, transform=None, limit=DIAGNOSTIC_READ_LIMIT, remote=False, expected=None):
        remaining = deadline - time.monotonic()
        if remaining <= .1:
            text, record = "", {"status": "budget-exhausted", "truncated": False, "read_bytes": 0}
        else:
            try:
                text, record = diagnostic_capture(command, min(DIAGNOSTIC_PROBE_SECONDS, remaining), limit)
            except subprocess.TimeoutExpired:
                text, record = "", {"status": "timeout", "truncated": False, "read_bytes": 0}
            except Exception as error:
                text, record = "", {"status": "unavailable", "error_type": type(error).__name__,
                                     "truncated": False, "read_bytes": 0}
        if remote:
            text, record = diagnostic_remote_output(text, record)
        if expected is not None and record["status"] == "captured" and not re.search(expected, text):
            record.update(status="unavailable", reason="unexpected-response")
        report["probes"][name] = record
        if transform is not None:
            content = transform(text)
            record["content"] = ("matching-context" if content else "no-matching-context"
                                 if record["status"] == "captured" and not record["truncated"] else "unavailable")
            record["file"] = name + ".txt"
            record["bytes"], clipped = diagnostic_write(directory, record["file"], content)
            record["truncated"] = record["truncated"] or clipped
        return text, record

    try:
        serial, state = probe("serial", ["adb", "get-serialno"], limit=128)
        serial = serial.strip()
        if state["status"] != "captured" or not re.fullmatch(r"emulator-[0-9]+", serial):
            report["scope"] = "refused-or-unavailable-emulator"
            return report
        prefix = ["adb", "-s", serial]
        name, state = probe("avd", prefix + ["emu", "avd", "name"], limit=256)
        name = name.splitlines()[0].strip() if name.splitlines() else ""
        if state["status"] != "captured" or api not in (23, 35) or name not in (
                "miancak-release-" + str(api), "miancak-release-rehearsal-" + str(api)):
            report["scope"] = "refused-or-unavailable-named-guest"
            return report
        report.update(scope="named-synthetic-release-guest", api=api)

        def remote_probe(name, script, transform=None, limit=DIAGNOSTIC_READ_LIMIT, arguments=(), expected=None):
            return probe(name, prefix + ["exec-out", diagnostic_remote_command(script, arguments)],
                         transform, limit, remote=True, expected=expected)

        # AOSP 6 ActivityManagerService.dump has no lastanr command or retained
        # last-ANR summary. Do not replace it with a broad activity/process dump.
        if api == 23:
            report["probes"]["last-anr"] = {"status": "unsupported", "reason": "aosp-6-no-lastanr-command",
                "truncated": False, "read_bytes": 0, "content": "unavailable"}
        else:
            remote_probe("last-anr", "dumpsys activity lastanr", focused_diagnostic,
                         expected=r"(?m)^ACTIVITY MANAGER LAST ANR(?:\s|$)")
        # Prefer existing app stacks before potentially stalled system dumps.
        # AOSP 6 toolbox ls accepts neither -1 nor -t. Its plain listing is
        # newline-delimited; exec-out avoids PTY formatting on either API.
        listing, state = remote_probe("anr-files", "ls /data/anr" if api == 23 else "ls -1t /data/anr",
                                      lambda text: text, limit=4096)
        state["content"] = "listing-output" if state["status"] == "captured" and not state["truncated"] else "unavailable"
        if state["status"] == "captured" and not state["truncated"]:
            names = [line for line in listing.splitlines()
                     if re.fullmatch(r"(?:traces\.txt(?:\.[A-Za-z0-9_-]+)?|anr_[A-Za-z0-9_.-]+)", line)]
            names = list(dict.fromkeys(names))
            if "traces.txt" in names:
                names.remove("traces.txt"); names.insert(0, "traces.txt")
            state["eligible_files"] = len(names)
            state["listing_lines"] = len(listing.splitlines())
            for index, name in enumerate(names):
                if index >= 2:
                    break
                # The basename was allowlisted. Refuse a symlink, and never
                # use a guest-provided path as a local output filename.
                remote_probe("app-anr-trace-" + str(index + 1), 'test ! -L "$1" && cat "$1"',
                             matching_anr_traces, arguments=("/data/anr/" + name,))
        remote_probe("activities", "dumpsys activity activities " + PACKAGE, focused_diagnostic,
                     expected=r"(?m)^ACTIVITY MANAGER ACTIVITIES(?:\s|$)")
        remote_probe("windows", "dumpsys window windows",
              lambda text: focused_diagnostic(text, r"mCurrentFocus=|mFocusedApp="),
              expected=r"(?m)^WINDOW MANAGER WINDOWS(?:\s|$)")
        remote_probe("system-log", shlex.join(["logcat", "-b", "system", "-b", "main", "-d", "-v", "threadtime", "-t", "300",
              "ActivityManager:I", "ActivityTaskManager:I", "WindowManager:I", "InputDispatcher:I",
              "InputManager-JNI:I", "AndroidRuntime:E", "*:S"]),
              lambda text: focused_diagnostic(text, r"\bInputDispatcher\s*:|\bInputManager-JNI\s*:"))
        remote_probe("event-log", shlex.join(["logcat", "-b", "events", "-d", "-v", "threadtime", "-t", "300",
              "am_anr:I", "am_crash:I", "am_proc_died:I", "am_create_activity:I", "am_resume_activity:I",
              "am_pause_activity:I", "am_finish_activity:I", "*:S"]), focused_diagnostic)
    finally:
        report["seconds"] = round(time.monotonic() - started, 3)
        diagnostic_write(directory, "manifest.json", json.dumps(report, sort_keys=True, indent=2) + "\n")
    return report

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
    """Read app-private bytes into host memory; persist only local paths/counts/hashes.

    Preservation comparisons use the complete map. Public staging excludes its
    per-file paths/hashes and never receives the raw preference/learning bytes.
    """
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

def split_resources(apk):
    with ZipFile(apk) as archive:
        return 'assets/rime/delivery.json' in archive.namelist()

def phase(name, *, setup_resources=False):
    shell("ime", "enable", PACKAGE + "/juloo.keyboard2.Keyboard2")
    shell("ime", "set", PACKAGE + "/juloo.keyboard2.Keyboard2")
    setup = ["-e", "releaseSetup", "true"] if setup_resources else []
    text = shell("am", "instrument", "-w", "-e", "class", "juloo.keyboard2.ReleaseApkTest",
                 "-e", "releasePhase", name,
                 *setup,
                 PACKAGE + ".debug.test/androidx.test.runner.AndroidJUnitRunner", timeout=600)
    (RESULTS / (name + ".log")).write_text(text)
    passed = "OK (1 test)" in text and not re.search(
        r"FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=", text)
    if not passed:
        # Raw instrumentation text remains local behind the console boundary.
        print("FAIL: RELEASE_INPUT_PHASE", flush=True)
    ops.require(passed, "RELEASE_INPUT_" + name.upper())
    print("PASS: ACTUAL_SIGNED_APK_" + name.upper(), flush=True)


def verify_release_target(api):
    """Read-only identity/empty-install guard before IME, root or app data access."""
    ops.require(os.environ.get('MIANCAK_TEST_DISPOSABLE_GUEST') == 'true', 'RELEASE_TARGET_DISPOSABLE_REQUIRED')
    def read(*args):
        text, state = diagnostic_capture(['adb', *args], 10, 4096)
        ops.require(state['status'] == 'captured' and not state.get('truncated'), 'RELEASE_TARGET_UNAVAILABLE')
        return text.strip()
    serial = read('get-serialno')
    ops.require(re.fullmatch(r'emulator-[0-9]+', serial), 'RELEASE_TARGET_SERIAL')
    def guest(*args): return read('-s', serial, *args)
    name = guest('emu', 'avd', 'name').splitlines()[0]
    ops.require(api == 35 and name in ('miancak-release-'+str(api), 'miancak-release-rehearsal-'+str(api)),
                'RELEASE_TARGET_AVD')
    ops.require(guest('shell', 'getprop', 'ro.kernel.qemu') == '1' and
                guest('shell', 'getprop', 'ro.build.version.sdk') == str(api), 'RELEASE_TARGET_API')
    text, state = diagnostic_capture(['adb', '-s', serial, 'exec-out',
        diagnostic_remote_command('pm list packages ' + PACKAGE)], 10, 4096)
    # Do not strip the result. Require complete canonical status and no package,
    # error or whitespace bytes; shell-v1 host success alone is ambiguous.
    ops.require(state['status'] == 'captured' and state.get('returncode') == 0
                and not state.get('truncated') and re.fullmatch(
                    r'\r?\n' + re.escape(DIAGNOSTIC_REMOTE_STATUS) + r'0\r?\n', text),
                'RELEASE_TARGET_PREEXISTING_OR_UNAVAILABLE_APP')
    os.environ['ANDROID_SERIAL'] = serial

def main(*, before_overwrite=None, after_reboot=None):
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
    verify_release_target(args.api)
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
        ops.verify_signed(baseline, previous=previous.is_file())
        old = ops.inspect_apk(baseline)
        new = ops.inspect_apk(apk)
        if previous.is_file():
            ops.require(old["version_code"] < new["version_code"], "RELEASE_UPGRADE_VERSION")
        adb("install", "--no-incremental", str(prepared / "app-debug.apk"), timeout=180)
        adb("install", "--no-incremental", str(prepared / "app-debug-androidTest.apk"), timeout=180)
        adb("install", "--no-incremental", str(baseline), timeout=180)
        shell("svc", "wifi", "disable"); shell("svc", "data", "disable")
        shell("settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        phase("prepare", setup_resources=split_resources(baseline))
        inactive()
        if before_overwrite is not None:
            before_overwrite()
        before = snapshot("before-install")
        adb("install", "--no-incremental", "-r", str(apk), timeout=180)
        after = snapshot("after-install")
        ops.require(before == after, "RELEASE_UPGRADE_DATA_CHANGED")
        phase("verify", setup_resources=split_resources(apk))
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
        if after_reboot is not None:
            after_reboot()
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
    except Exception as failure:
        try:
            collect_failure_diagnostics(args.api, failure)
        except BaseException as diagnostic_error:
            # Even an alarm during collection must not replace the failed gate.
            print("Release failure diagnostics unavailable: " + type(diagnostic_error).__name__, file=sys.stderr)
        raise
    finally:
        failed = sys.exc_info()[0] is not None
        try:
            adb("pull", "/sdcard/Android/data/" + PACKAGE + ".debug/files/release-results",
                str(RESULTS / "device"), timeout=90, check=False)
        except BaseException as transfer_error:
            if not failed:
                raise
            print("Release UI evidence transfer unavailable: " + type(transfer_error).__name__, file=sys.stderr)
        finally:
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
