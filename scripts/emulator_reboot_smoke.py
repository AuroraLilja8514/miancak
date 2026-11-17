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
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time


class RebootEvidence:
    """Only fixed/derived observations. Never retain error text, IDs or a cause."""
    STAGES = {'INITIAL_CHECKPOINT', 'READ_BOOT_ID', 'REBOOT_REQUEST', 'POLL_BOOT_ID',
              'POLL_COMPLETION', 'WAIT_BOOT', 'CYCLE_CHECKPOINT', 'COMPLETE'}
    CODES = {'INVALID_BOOT_ID', 'BOOT_DEADLINE', 'ADB_TIMEOUT', 'ADB_ERROR', 'HOST_IO', 'OTHER'}
    POLLS = {'UNAVAILABLE', 'OK', 'TIMEOUT', 'ADB_ERROR'}
    COMPLETIONS = {'UNAVAILABLE', 'COMPLETE', 'NOT_COMPLETE', 'INVALID'}

    def __init__(self, clock):
        self.clock = clock
        self.valid = True
        self.stage_id = 'INITIAL_CHECKPOINT'
        self.cycle = self.poll_attempt = 0
        self.cycle_started = None
        self.poll_status = self.completion = 'UNAVAILABLE'
        self.changed = None
        self.failure = None
        self.attempted = False
        try: self.started = clock()
        except BaseException: self.valid = False

    def stage(self, stage):
        if stage not in self.STAGES: self.valid = False
        else: self.stage_id = stage

    def begin_cycle(self, cycle):
        self.cycle = cycle
        self.cycle_started = None
        self.poll_attempt = 0
        self.poll_status = self.completion = 'UNAVAILABLE'
        self.changed = None

    def begin_poll(self):
        self.poll_attempt += 1
        self.poll_status = self.completion = 'UNAVAILABLE'
        self.changed = None

    def boot_id(self, before, observed):
        self.changed = (observed != before) if re.fullmatch(r'[a-f0-9-]{36}', observed) else None

    def completed(self, observed):
        self.completion = ('COMPLETE' if observed == '1' else 'NOT_COMPLETE' if observed == '0'
                           else 'UNAVAILABLE' if observed == '' else 'INVALID')

    @staticmethod
    def error_code(error):
        if isinstance(error, subprocess.TimeoutExpired): return 'ADB_TIMEOUT'
        if isinstance(error, subprocess.CalledProcessError): return 'ADB_ERROR'
        if isinstance(error, OSError): return 'HOST_IO'
        return 'OTHER'

    def row(self):
        now = self.clock()
        elapsed = (now - self.started) * 1000
        cycle_elapsed = None if self.cycle_started is None else (now - self.cycle_started) * 1000
        if not self.valid or not 0 <= self.cycle <= 5 or not 0 <= self.poll_attempt <= 3601 \
                or not math.isfinite(elapsed) or not 0 <= elapsed <= 86400000 \
                or cycle_elapsed is not None and (not math.isfinite(cycle_elapsed) or not 0 <= cycle_elapsed <= 86400000):
            raise ValueError('unavailable observation')
        return dict(stage=self.stage_id, cycle=self.cycle, elapsed_ms=round(elapsed),
                    cycle_elapsed_ms=None if cycle_elapsed is None else round(cycle_elapsed),
                    poll_attempt=self.poll_attempt, poll_status=self.poll_status,
                    boot_id_changed=self.changed, completion=self.completion)

    def failed(self, code):
        if self.attempted: return
        self.attempted = True  # A recorder failure cannot let cleanup replace the primary.
        try:
            if code not in self.CODES: return
            self.failure = dict(status='observed', code=code, **self.row())
        except BaseException: pass

    def snapshot(self):
        try:
            return dict(schema=1, status='available', **self.row(),
                        failure=dict(self.failure) if self.failure is not None else {'status': 'unavailable'})
        except BaseException: return {'schema': 1, 'status': 'unavailable'}


def observe(action, *args):
    try: return action(*args)
    except BaseException: return None


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

    observation = RebootEvidence(time.monotonic)

    def checkpoint():
        report["diagnostic"] = observe(observation.snapshot) or {"schema": 1, "status": "unavailable"}
        path = directory / "emulator-reboots.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(path)

    collectors_allowed = False
    try:
        checkpoint()
        collectors_allowed = True
        for index in range(args.cycles):
            observe(observation.begin_cycle, index + 1)
            observe(observation.stage, "READ_BOOT_ID")
            before = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
            if not re.fullmatch(r"[a-f0-9-]{36}", before):
                observe(observation.failed, "INVALID_BOOT_ID")
                raise RuntimeError("Missing actual guest boot ID")
            print(f"MIANCAK emulator reboot {index+1}/{args.cycles}; budget={args.boot_timeout_seconds}s", flush=True)
            start = time.monotonic()
            observation.cycle_started = start
            observe(observation.stage, "REBOOT_REQUEST")
            adb(serial, "reboot", timeout=30)
            end = start + args.boot_timeout_seconds
            after = ""; completed = ""; last_error = ""; last_poll_error = None
            while time.monotonic() < end:
                observe(observation.begin_poll)
                try:
                    # Keep the ID and completion flag from the same successful
                    # poll; an earlier offline error is not the current state.
                    observe(observation.stage, "POLL_BOOT_ID")
                    observed_id = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id", timeout=5)
                    observe(observation.boot_id, before, observed_id)
                    observe(observation.stage, "POLL_COMPLETION")
                    observed_completed = adb(serial, "shell", "getprop", "sys.boot_completed", timeout=5)
                    observe(observation.completed, observed_completed)
                    observation.poll_status = "OK"
                    after, completed = observed_id, observed_completed
                    last_poll_error = ""
                    if after != before and re.fullmatch(r"[a-f0-9-]{36}", after) and completed == "1":
                        break
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                    observation.poll_status = "TIMEOUT" if isinstance(error, subprocess.TimeoutExpired) else "ADB_ERROR"
                    last_error = str(error)
                    output = getattr(error, "output", None)
                    if output:
                        if isinstance(output, bytes):
                            output = output.decode("utf-8", errors="replace")
                        last_error += ": " + output.strip()[-1500:]
                    last_poll_error = last_error
                observe(observation.stage, "WAIT_BOOT")
                time.sleep(1)
            else:
                observe(observation.failed, "BOOT_DEADLINE")
                report["failedCycle"] = dict(beforeBootId=before, lastObservedBootId=after,
                    lastObservedBootCompleted=completed, lastAdbError=last_error,
                    lastPollAdbError=last_poll_error,
                    seconds=round(time.monotonic()-start, 3))
                last_poll = ("no boot poll completed" if last_poll_error is None else
                             "last poll reached guest" if last_poll_error == "" else
                             "last poll ADB error: " + last_poll_error)
                raise RuntimeError("Guest did not finish a real reboot within budget; "
                    f"last successful boot ID={after!r}, boot_completed={completed!r}; {last_poll}")
            report["cycles"].append(dict(beforeBootId=before, afterBootId=after,
                                          seconds=round(time.monotonic()-start, 3)))
            observe(observation.stage, "CYCLE_CHECKPOINT")
            checkpoint()
        observe(observation.stage, "COMPLETE")
        report["passed"] = True
        print("MIANCAK emulator reboot gate passed: 3 distinct kernel restarts" if args.cycles == 3
              else f"MIANCAK emulator reboot gate passed: {args.cycles} distinct kernel restarts", flush=True)
    except Exception as error:
        observe(observation.failed, observe(RebootEvidence.error_code, error) or 'OTHER')
        try:
            if collectors_allowed:
                report["error"] = str(error)
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
                                       ("boot-windows", ("dumpsys", "window", "windows"))):
                    try:
                        value = adb(serial, "shell", *command, timeout=8)
                        (directory / (label + ".txt")).write_text(value[-60000:] + "\n")
                        report.setdefault("diagnosticFiles", {})[label] = "captured"
                    except (OSError, subprocess.SubprocessError) as diagnostic:
                        report.setdefault("diagnosticFiles", {})[label] = str(diagnostic)
        except BaseException:
            pass  # Collection/formatting must not replace the original failure.
        raise
    finally:
        unwinding = sys.exc_info()[0] is not None
        try:
            checkpoint()
        except BaseException:
            if not unwinding:
                raise

if __name__ == "__main__":
    main()
