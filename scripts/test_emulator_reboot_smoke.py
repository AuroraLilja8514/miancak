#!/usr/bin/env python3
"""Exercise reboot acceptance and failure reports without a real device."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import emulator_reboot_smoke as smoke


BEFORE = "11111111-1111-1111-1111-111111111111"
AFTER = "22222222-2222-2222-2222-222222222222"


class RebootReportTest(unittest.TestCase):
    def exercise(self, observed_id, completed, offline_first=False):
        clock = [0.0]
        boot_reads = [0]

        def adb(_serial, *args, timeout=10):
            if args == ("emu", "avd", "name"):
                return "miancak-reboot-smoke"
            if args == ("shell", "getprop", "ro.build.version.sdk"):
                return "23"
            if args == ("reboot",):
                return ""
            if args == ("shell", "cat", "/proc/sys/kernel/random/boot_id"):
                boot_reads[0] += 1
                if boot_reads[0] == 1:
                    return BEFORE
                if offline_first and boot_reads[0] == 2:
                    raise subprocess.CalledProcessError(1, "adb", output="adb: device offline")
                return observed_id
            if args == ("shell", "getprop", "sys.boot_completed"):
                return completed
            return "diagnostic"

        def run(command, **kwargs):
            if command[0] == "adb":
                return SimpleNamespace(returncode=0, stdout="emulator-5554 device\n")
            return SimpleNamespace(returncode=0, stdout="Android emulator 36.1.9 build_id 13823996")

        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as tmp:
            try:
                os.chdir(tmp)
                with patch.dict(os.environ, {"ANDROID_HOME": "/fake"}), \
                     patch.object(sys, "argv",
                                  ["emulator_reboot_smoke.py", "--cycles", "1", "--boot-timeout-seconds", "3"]), \
                     patch.object(smoke.subprocess, "check_output", return_value="emulator-5554"), \
                     patch.object(smoke.subprocess, "run", side_effect=run), \
                     patch.object(smoke, "adb", side_effect=adb), \
                     patch.object(smoke.time, "monotonic", side_effect=lambda: clock[0]), \
                     patch.object(smoke.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)), \
                     contextlib.redirect_stdout(io.StringIO()):
                    try:
                        smoke.main()
                        error = None
                    except RuntimeError as exc:
                        error = str(exc)
                report = json.loads(Path("test-results/emulator-reboots.json").read_text())
                return error, report
            finally:
                os.chdir(previous)

    def test_reconnected_but_unfinished_boot_is_not_reported_as_offline(self):
        error, report = self.exercise(AFTER, "", offline_first=True)
        self.assertFalse(report["passed"])
        self.assertEqual(AFTER, report["failedCycle"]["lastObservedBootId"])
        self.assertEqual("", report["failedCycle"]["lastObservedBootCompleted"])
        self.assertIn("device offline", report["failedCycle"]["lastAdbError"])
        self.assertEqual("", report["failedCycle"]["lastPollAdbError"])
        self.assertIn("last poll reached guest", error)
        self.assertNotIn("device offline", error)

    def test_boot_completed_without_new_kernel_boot_id_fails(self):
        error, report = self.exercise(BEFORE, "1")
        self.assertIsNotNone(error)
        self.assertFalse(report["passed"])
        self.assertEqual([], report["cycles"])

    def test_new_boot_id_and_completed_boot_pass(self):
        error, report = self.exercise(AFTER, "1")
        self.assertIsNone(error)
        self.assertTrue(report["passed"])
        self.assertEqual(AFTER, report["cycles"][0]["afterBootId"])


if __name__ == "__main__":
    unittest.main()
