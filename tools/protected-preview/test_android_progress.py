#!/usr/bin/env python3
"""Exercise host-owned progress reporting without a guest or release data."""
from contextlib import redirect_stdout
from io import StringIO
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import android_progress as progress
from android_progress import Phase, Status


class AndroidProgressTest(unittest.TestCase):
    def setUp(self):
        directory = self.enterContext(tempfile.TemporaryDirectory())
        self.path = Path(directory) / "status.json"
        self.enterContext(mock.patch.dict(os.environ, {progress.STATUS_ENV: str(self.path)}, clear=True))
        with mock.patch.object(progress.time, "monotonic_ns", return_value=10**9):
            progress.begin(self.path)

    def report(self, *, failed=False):
        with redirect_stdout(StringIO()) as output:
            progress.report(self.path, failed)
        return output.getvalue().splitlines()

    def clock(self, seconds):
        return mock.patch.object(progress.time, "monotonic_ns", return_value=(seconds + 1) * 10**9)

    def test_runner_failure_reports_last_phase_and_bounded_elapsed_time(self):
        with self.clock(48), redirect_stdout(StringIO()) as private_output:
            progress.mark(Phase.BOOT, Status.STARTED)
        self.assertEqual(private_output.getvalue(), "")
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.report(failed=True), [
            "PREVIEW_ANDROID emulator-action started +0s",
            "PREVIEW_ANDROID emulator-boot started +48s",
            "PREVIEW_ANDROID emulator-boot runner-failed",
        ])

    def test_host_success_reports_only_explicit_verified_milestones(self):
        with self.clock(180):
            for phase in (Phase.GUEST, Phase.BASELINE, Phase.UPGRADE, Phase.PACK, Phase.OFFLINE, Phase.YAML, Phase.REBOOT):
                progress.mark(phase, Status.PASSED)
        self.assertEqual(self.report(), ["PREVIEW_ANDROID emulator-action started +0s", *(
            f"PREVIEW_ANDROID {phase} passed +180s" for phase in (
                "guest-guard", "install-v0.3.2", "upgrade-code-5-to-7", "online-pack", "offline-input",
                "functional-yaml", "reboot-and-recheck"))])

    def test_recognized_fixture_failure_is_an_enum_and_never_the_assertion(self):
        with self.clock(180):
            progress.mark(Phase.VERIFY, Status.STARTED)
        with self.clock(540):
            progress.fixture_failure("verify", "private fixture text\njava.lang.AssertionError: "
                                     "The exact HTTPS pack did not become ready\nprivate raw stderr")
            progress.failure(RuntimeError("private exception text"))
        output = self.report(failed=True)
        self.assertEqual(len(output), 3)
        self.assertEqual(output[-1], "PREVIEW_ANDROID online-pack pack-not-ready +540s")

    def test_ambiguous_fixture_output_and_host_timeout_use_safe_host_classification(self):
        with self.clock(20):
            progress.mark(Phase.VERIFY, Status.STARTED)
            progress.fixture_failure("verify", "java.lang.AssertionError: unknown private data\n"
                                     "java.lang.AssertionError: The exact HTTPS pack did not become ready\n"
                                     "java.lang.AssertionError: Custom YAML activation did not finish")
            progress.failure(subprocess.TimeoutExpired(["private command arg"], 600))
        self.assertEqual(self.report(failed=True)[-1], "PREVIEW_ANDROID online-pack-offline-input-yaml timeout +20s")

    def test_invalid_record_fails_closed_without_partial_output(self):
        initial = progress.read(self.path)
        invalid_events = [
            ["secret\n::error::spoof", Status.PASSED, 1],
            [Phase.PACK, "raw-private-error", 1],
            [Phase.PACK, Status.STARTED, True],
            [Phase.PACK, Status.STARTED, progress.MAX_SECONDS + 1],
            [Phase.PACK, Status.STARTED, 1, "fixture contents"],
        ]
        for event in invalid_events:
            with self.subTest(event=event):
                self.path.write_text(json.dumps(initial | {"events": [*initial["events"], event]}))
                self.assertEqual(self.report(failed=True), ["PREVIEW_ANDROID status-unavailable"])
        self.path.write_bytes(b"x" * (progress.MAX_BYTES + 1))
        self.assertEqual(self.report(), ["PREVIEW_ANDROID status-unavailable"])

    def test_symlink_or_public_file_is_rejected(self):
        public = self.path.with_name("public.json")
        self.path.rename(public)
        self.path.symlink_to(public)
        self.assertEqual(self.report(), ["PREVIEW_ANDROID status-unavailable"])
        self.path.unlink()
        public.rename(self.path)
        self.path.chmod(0o644)
        self.assertEqual(self.report(), ["PREVIEW_ANDROID status-unavailable"])


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
