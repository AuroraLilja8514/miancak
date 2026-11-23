#!/usr/bin/env python3
"""Runner-local Android acceptance markers with a fixed public vocabulary."""
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
from enum import Enum

import release_ops as ops

STATUS_ENV = "PREVIEW_ANDROID_STATUS_FILE"
MAX_BYTES = 8192
MAX_EVENTS = 32
MAX_SECONDS = 3600


class Phase(str, Enum):
    ACTION = "emulator-action"
    BOOT = "emulator-boot"
    DRIVER = "driver-preflight"
    GUEST = "guest-guard"
    BASELINE = "install-v0.3.2"
    PREPARE = "baseline-input-and-data"
    UPGRADE = "upgrade-code-5-to-7"
    VERIFY = "online-pack-offline-input-yaml"
    PACK = "online-pack"
    OFFLINE = "offline-input"
    YAML = "functional-yaml"
    REBOOT = "reboot-and-recheck"
    EVIDENCE = "local-evidence-transfer"
    REPORT = "acceptance-report"


class Status(str, Enum):
    STARTED = "started"
    PASSED = "passed"
    RUNNER_FAILED = "runner-failed"
    TIMEOUT = "timeout"
    ADB_FAILED = "adb-failed"
    CHECK_FAILED = "check-failed"
    SETUP_UI_FAILED = "setup-ui-failed"
    NETWORK_NOT_READY = "network-not-ready"
    PACK_NOT_READY = "pack-not-ready"
    OFFLINE_NOT_READY = "offline-not-ready"
    CANDIDATE_MISSING = "candidate-missing"
    YAML_NOT_READY = "yaml-not-ready"
    DRIVER_FAILED = "driver-failed"


# These exact fixture assertions can refine a failed combined verify call.
# The host emits its own enum and never forwards an assertion or guest line.
VERIFY_FAILURES = {
    "Normal Chinese setup button was absent": (Phase.PACK, Status.SETUP_UI_FAILED),
    "Setup should finish checking the local resources": (Phase.PACK, Status.SETUP_UI_FAILED),
    "Normal setup did not offer Download": (Phase.PACK, Status.SETUP_UI_FAILED),
    "No default network for pinned HTTPS setup": (Phase.PACK, Status.NETWORK_NOT_READY),
    "The exact HTTPS pack did not become ready": (Phase.PACK, Status.PACK_NOT_READY),
    "Device must return offline before using the model": (Phase.OFFLINE, Status.OFFLINE_NOT_READY),
    "Signed receiver check must run offline": (Phase.OFFLINE, Status.OFFLINE_NOT_READY),
    "Real release did not deploy offline Rime or produce 你好": (Phase.OFFLINE, Status.CANDIDATE_MISSING),
    "Custom YAML must change actual offline input": (Phase.YAML, Status.OFFLINE_NOT_READY),
    "Custom YAML activation did not finish": (Phase.YAML, Status.YAML_NOT_READY),
}


def read(path):
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as stream:
        mode = os.fstat(stream.fileno()).st_mode
        if not stat.S_ISREG(mode) or mode & 0o077:
            raise ValueError("invalid status file mode")
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("oversized status file")
    data = json.loads(raw)
    if (type(data) is not dict or set(data) != {"started_ns", "events"}
            or type(data["started_ns"]) is not int or not 0 < data["started_ns"] < 2**63
            or type(data["events"]) is not list or not 1 <= len(data["events"]) <= MAX_EVENTS
            or data["events"][0] != [Phase.ACTION, Status.STARTED, 0]):
        raise ValueError("invalid status file shape")
    previous = 0
    for event in data["events"]:
        if type(event) is not list or len(event) != 3:
            raise ValueError("invalid event shape")
        phase, status, seconds = event
        Phase(phase)
        Status(status)
        if type(seconds) is not int or not previous <= seconds <= MAX_SECONDS:
            raise ValueError("invalid event time")
        previous = seconds
    return data


def store(path, data):
    with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=".android-status-", delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(data, stream, separators=(",", ":"))
            stream.close()
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def begin(path):
    store(path, {"started_ns": time.monotonic_ns(), "events": [[Phase.ACTION, Status.STARTED, 0]]})


def add(path, data, phase, status):
    seconds = (time.monotonic_ns() - data["started_ns"]) // 10**9
    events = data["events"]
    if len(events) < MAX_EVENTS and events[-1][2] <= seconds <= MAX_SECONDS:
        events.append([Phase(phase), Status(status), seconds])
        store(path, data)


def mark(phase, status):
    value = os.environ.get(STATUS_ENV)
    if value:
        path = Path(value)
        try:
            add(path, read(path), phase, status)
        except (OSError, ValueError, RecursionError):
            # Status collection is auxiliary to the real acceptance result.
            pass


def fixture_failure(name, text):
    if name == "verify":
        prefix = "java.lang.AssertionError: "
        assertions = (line.strip() for line in text.splitlines())
        matches = {VERIFY_FAILURES[message] for line in assertions if line.startswith(prefix)
                   and (message := line.removeprefix(prefix)) in VERIFY_FAILURES}
        if len(matches) == 1:
            mark(*matches.pop())


def failure(error):
    value = os.environ.get(STATUS_ENV)
    if not value:
        return
    path = Path(value)
    try:
        data = read(path)
        phase, last, _ = data["events"][-1]
        if last not in (Status.STARTED, Status.PASSED):
            return
        if isinstance(error, subprocess.TimeoutExpired) or (isinstance(error, ops.ReleaseError)
                and error.args in (("RELEASE_DEVICE_TIMEOUT",), ("RELEASE_REBOOT_TIMEOUT",))):
            status = Status.TIMEOUT
        elif isinstance(error, ops.ReleaseError):
            status = Status.ADB_FAILED if error.args == ("RELEASE_ADB",) else Status.CHECK_FAILED
        else:
            status = Status.DRIVER_FAILED
        add(path, data, phase, status)
    except (OSError, ValueError, RecursionError):
        pass


def report(path, failed):
    try:
        data = read(path)
    except (OSError, ValueError, RecursionError):
        print("PREVIEW_ANDROID status-unavailable")
        return
    for phase, status, seconds in data["events"]:
        print(f"PREVIEW_ANDROID {Phase(phase).value} {Status(status).value} +{seconds}s")
    phase, status, _ = data["events"][-1]
    if failed and status in (Status.STARTED, Status.PASSED):
        failed_phase = phase if status == Status.STARTED else Phase.ACTION
        print(f"PREVIEW_ANDROID {Phase(failed_phase).value} {Status.RUNNER_FAILED.value}")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    if sys.argv[1:] == ["before-boot"]:
        mark(Phase.BOOT, Status.STARTED)
    elif len(sys.argv) == 3 and sys.argv[1] == "begin":
        begin(Path(sys.argv[2]))
    elif len(sys.argv) == 4 and sys.argv[1] == "report" and sys.argv[3] in ("passed", "failed"):
        report(Path(sys.argv[2]), sys.argv[3] == "failed")
    else:
        raise SystemExit(2)
