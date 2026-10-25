#!/usr/bin/env python3
"""Check real guest reboots before spending time on the full APK matrix."""
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
    version = subprocess.check_output([str(emulator), "-version"], text=True,
                                      stderr=subprocess.STDOUT, timeout=15)
    if "36.1.9" not in version or "13823996" not in version:
        raise RuntimeError("Unexpected emulator binary; the reboot gate requires 36.1.9 / 13823996")
    report = dict(api=int(api), serial=serial, avd=name, emulatorVersion=version,
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
            after = ""; last_error = ""
            while time.monotonic() < end:
                try:
                    after = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id", timeout=5)
                    completed = adb(serial, "shell", "getprop", "sys.boot_completed", timeout=5)
                    if after != before and re.fullmatch(r"[a-f0-9-]{36}", after) and completed == "1":
                        break
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                    last_error = str(error)
                time.sleep(1)
            else:
                raise RuntimeError("Guest did not finish a real reboot: " + last_error)
            report["cycles"].append(dict(beforeBootId=before, afterBootId=after,
                                          seconds=round(time.monotonic()-start, 3)))
            checkpoint()
        report["passed"] = True
        print("MIANCAK emulator reboot gate passed: 3 distinct kernel restarts" if args.cycles == 3
              else f"MIANCAK emulator reboot gate passed: {args.cycles} distinct kernel restarts", flush=True)
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        checkpoint()


if __name__ == "__main__":
    main()
