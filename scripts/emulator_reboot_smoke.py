#!/usr/bin/env python3
"""Diagnose three real guest reboots independently of the APK test hosts."""
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parent))
    from run_with_timeout import guard_script
    guard_script(__file__)

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


def adb(serial, *args, timeout=10):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=timeout,
                                   text=True, stderr=subprocess.STDOUT).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog="Common option: --timeout-seconds N (default 3600; nested calls inherit the remaining budget).")
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--boot-timeout-seconds", type=int, default=180)
    args = parser.parse_args()
    if not 1 <= args.cycles <= 5 or not 1 <= args.boot_timeout_seconds <= 3600:
        parser.error("cycles must be 1–5 and boot timeout 1–3600 seconds")
    serial = subprocess.check_output(["adb", "get-serialno"], text=True, timeout=15).strip()
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise RuntimeError("This check only runs on its test emulator")
    name = adb(serial, "emu", "avd", "name").splitlines()[0]
    if name != "miancak-reboot-smoke":
        raise RuntimeError("Refuse to reboot a guest other than miancak-reboot-smoke")
    api = adb(serial, "shell", "getprop", "ro.build.version.sdk")
    if api != "23":
        raise RuntimeError("Expected the independent API 23 environment check")
    directory = Path("test-results"); directory.mkdir(exist_ok=True)
    emulator = Path(os.environ["ANDROID_HOME"]) / "emulator/emulator"
    version_result = subprocess.run([str(emulator), "-no-window", "-version"], text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
    version = version_result.stdout
    (directory / "emulator-version.txt").write_text(version)
    print(version, flush=True)
    if version_result.returncode != 0:
        raise RuntimeError(f"Headless emulator version query exited {version_result.returncode}; see emulator-version.txt")
    if "36.1.9" not in version or "13823996" not in version:
        raise RuntimeError("Unexpected emulator binary; the reboot gate requires 36.1.9 / 13823996")
    report = dict(api=int(api), serial=serial, avd=name, emulatorVersion=version,
                  configuredRenderer=os.environ.get("MIANCAK_EMULATOR_GPU_API23", "unspecified"),
                  method="adb reboot; no snapshot or app-data restore", cycles=[], passed=False)

    def checkpoint():
        path = directory / "emulator-reboots.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(path)

    checkpoint()
    try:
        for index in range(args.cycles):
            before = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
            if not re.fullmatch(r"[a-f0-9-]{36}", before):
                raise RuntimeError("Missing actual guest boot ID")
            print(f"MIANCAK emulator reboot {index+1}/{args.cycles}; budget={args.boot_timeout_seconds}s", flush=True)
            start = time.monotonic()
            adb(serial, "reboot", timeout=30)
            end = start + args.boot_timeout_seconds
            after = ""; completed = ""; last_error = ""
            while time.monotonic() < end:
                try:
                    after = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id", timeout=5)
                    completed = adb(serial, "shell", "getprop", "sys.boot_completed", timeout=5)
                    if after != before and re.fullmatch(r"[a-f0-9-]{36}", after) and completed == "1":
                        break
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                    last_error = str(error)
                    output = getattr(error, "output", None)
                    if output:
                        if isinstance(output, bytes):
                            output = output.decode("utf-8", errors="replace")
                        last_error += ": " + output.strip()[-1500:]
                time.sleep(1)
            else:
                report["failedCycle"] = dict(beforeBootId=before, lastObservedBootId=after,
                    lastObservedBootCompleted=completed, lastAdbError=last_error,
                    seconds=round(time.monotonic()-start, 3))
                raise RuntimeError("Guest did not finish a real reboot within budget; see failedCycle: " + last_error)
            report["cycles"].append(dict(beforeBootId=before, afterBootId=after,
                                          seconds=round(time.monotonic()-start, 3)))
            checkpoint()
        report["passed"] = True
        print("MIANCAK emulator reboot gate passed: 3 distinct kernel restarts" if args.cycles == 3
              else f"MIANCAK emulator reboot gate passed: {args.cycles} distinct kernel restarts", flush=True)
    except Exception as error:
        report["error"] = str(error)
        try:
            devices = subprocess.run(["adb", "devices", "-l"], text=True,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
            report["adbDevicesAtFailure"] = devices.stdout[-4000:]
            (directory / "adb-devices-at-failure.txt").write_text(devices.stdout)
            # The last error may precede a transport reconnect. Capture current
            # guest state separately instead of presenting stale stderr as the
            # whole failure cause. Each diagnostic has its own finite timeout.
            for label, command in (("boot-id", ("cat", "/proc/sys/kernel/random/boot_id")),
                                   ("boot-completed", ("getprop", "sys.boot_completed")),
                                   ("uptime", ("cat", "/proc/uptime"))):
                try:
                    value = adb(serial, "shell", *command, timeout=5)
                    report.setdefault("stateAfterFailure", {})[label] = value
                except (OSError, subprocess.SubprocessError) as diagnostic:
                    report.setdefault("stateAfterFailure", {})[label] = str(diagnostic)
            # Preserve enough framework evidence to distinguish a boot stall
            # from a stale ADB transport error. These probes cannot extend the
            # inherited script deadline and are each bounded independently.
            for label, command in (("boot-logcat", ("logcat", "-b", "all", "-d", "-t", "400")),
                                   ("boot-processes", ("ps",)),
                                   ("boot-activities", ("dumpsys", "activity", "processes")),
                                   ("boot-windows", ("dumpsys", "window", "windows"))):
                try:
                    value = adb(serial, "shell", *command, timeout=8)
                    (directory / (label + ".txt")).write_text(value[-60000:] + "\n")
                    report.setdefault("diagnosticFiles", {})[label] = "captured"
                except (OSError, subprocess.SubprocessError) as diagnostic:
                    report.setdefault("diagnosticFiles", {})[label] = str(diagnostic)
        except (OSError, subprocess.TimeoutExpired) as diagnostic:
            report["adbDiagnosticError"] = str(diagnostic)
        raise
    finally:
        checkpoint()


if __name__ == "__main__":
    main()
