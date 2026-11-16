#!/usr/bin/env python3
"""Mocked release-failure evidence checks. Never contacts a device or account."""
import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import device_smoke as smoke


APP_TRACE = ("----- pid 123 at 2026-11-16 12:00:00 -----\n"
             "Cmd line: org.miancak.keyboard\n"
             '"main" prio=5 tid=1 Blocked\n  at synthetic.Main.wait(Main.java:1)\n'
             "----- end 123 -----\n")
OTHER_TRACE = ("----- pid 456 at 2026-11-16 12:00:00 -----\n"
               "Cmd line: synthetic.other\nOTHER_PROCESS_PRIVATE_STACK\n----- end 456 -----\n")


def remote_script(command):
    if command[3:4] != ["exec-out"]:
        return ""
    parsed = shlex.split(command[4])
    assert len(command) == 5 and parsed[:2] == ["sh", "-c"]
    return parsed[2]


def is_probe(command, script):
    return remote_script(command).startswith("{ " + script + "; }")


def remote_output(text, code=0, newline="\n"):
    return text + newline + smoke.DIAGNOSTIC_REMOTE_STATUS + str(code) + newline


class Diagnostics(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
        self.addCleanup(os.chdir, self.previous)
        self.environment = mock.patch.dict(os.environ, {"MIANCAK_TIMEOUT_DEADLINE": ""})
        self.environment.start(); self.addCleanup(self.environment.stop)
        self.commands = []
        self.api = 23

    def capture(self, command, timeout, limit):
        self.commands.append((command, timeout, limit))
        self.assertGreater(timeout, 0)
        self.assertLessEqual(timeout, smoke.DIAGNOSTIC_PROBE_SECONDS)
        if command == ["adb", "get-serialno"]:
            text = "emulator-5554\n"
        elif command[3:] == ["emu", "avd", "name"]:
            text = "miancak-release-rehearsal-" + str(self.api) + "\nOK\n"
        elif is_probe(command, "dumpsys activity lastanr"):
            text = "ACTIVITY MANAGER LAST ANR\nANR in " + smoke.PACKAGE + "\nReason: Input dispatching timed out\n"
        elif is_probe(command, "dumpsys activity activities " + smoke.PACKAGE):
            text = "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
            text += "mResumedActivity=ActivityRecord{" + smoke.PACKAGE + "/.SettingsActivity}\nstate=RESUMED idle=false\n"
        elif is_probe(command, "dumpsys window windows"):
            text = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
            text += "mCurrentFocus=Window{Application Not Responding: " + smoke.PACKAGE + "}\n"
        elif remote_script(command).startswith("{ logcat "):
            text = "11-16 12:00:00 1 2 E InputDispatcher: Input dispatching timed out\n"
            text += "11-16 12:00:00 1 2 I am_anr: [0,123," + smoke.PACKAGE + ",timeout]\n"
        elif is_probe(command, "ls /data/anr") or is_probe(command, "ls -1t /data/anr"):
            text = "traces.txt\nanr_2026-11-16-12-00-00\nanr_older\n"
        elif is_probe(command, 'test ! -L "$1" && cat "$1"'):
            text = OTHER_TRACE + APP_TRACE + OTHER_TRACE
        else:
            self.fail("Unexpected diagnostic command: " + repr(command))
        if command[3:4] == ["exec-out"]:
            text = remote_output(text)
        return text, {"status": "captured", "returncode": 0, "truncated": False, "read_bytes": len(text.encode())}

    def collect(self, capture=None, api=23):
        self.api = api
        with mock.patch.object(smoke, "diagnostic_capture", side_effect=capture or self.capture):
            return smoke.collect_failure_diagnostics(api, smoke.ops.ReleaseError("RELEASE_INPUT_VERIFY"))

    def test_named_guest_captures_only_app_trace_sections_and_read_only_probes(self):
        report = self.collect()
        directory = Path("test-results/release/failure-diagnostics")
        self.assertEqual(json.loads((directory / "manifest.json").read_text()), report)
        self.assertEqual(report["scope"], "named-synthetic-release-guest")
        self.assertEqual(report["acceptance_result"], "failed")
        traces = sorted(directory.glob("app-anr-trace-*.txt"))
        self.assertEqual(len(traces), 2)
        for path in traces:
            self.assertIn("synthetic.Main.wait", path.read_text())
            self.assertNotIn("OTHER_PROCESS_PRIVATE_STACK", path.read_text())
        self.assertEqual(report["probes"]["last-anr"]["status"], "unsupported")
        self.assertFalse((directory / "last-anr.txt").exists())
        self.assertTrue(is_probe(self.commands[2][0], "ls /data/anr"))
        # A hung activity/window service must not consume the entire shared
        # budget before the existing ANR stacks have been attempted.
        trace_indexes = [index for index, (command, timeout, limit) in enumerate(self.commands)
                         if is_probe(command, 'test ! -L "$1" && cat "$1"')]
        activities_index = next(index for index, (command, timeout, limit) in enumerate(self.commands)
                                if is_probe(command, "dumpsys activity activities " + smoke.PACKAGE))
        self.assertEqual(len(trace_indexes), 2)
        self.assertTrue(all(index < activities_index for index in trace_indexes))
        for command, timeout, limit in self.commands:
            joined = " ".join(command)
            for forbidden in ("root", "force-stop", "kill", "signal", "input keyevent", "tap", "instrument",
                              "settings put", "setprop", "reboot", "pull", "push", "run-as", " ps"):
                self.assertNotIn(forbidden, joined)
            self.assertNotIn("/files/rime", joined)
            if command != ["adb", "get-serialno"]:
                self.assertEqual(command[:3], ["adb", "-s", "emulator-5554"])

    def test_both_supported_apis_accept_only_their_release_or_rehearsal_names(self):
        for api in (23, 35):
            for prefix in ("miancak-release-", "miancak-release-rehearsal-"):
                with self.subTest(api=api, prefix=prefix):
                    self.commands.clear()
                    def capture(command, timeout, limit):
                        if command[3:] == ["emu", "avd", "name"]:
                            self.commands.append((command, timeout, limit))
                            return prefix + str(api) + "\nOK\n", {"status": "captured", "truncated": False}
                        return self.capture(command, timeout, limit)
                    report = self.collect(capture, api=api)
                    self.assertEqual(report["scope"], "named-synthetic-release-guest")
                    self.assertEqual(report["api"], api)
                    self.assertEqual(any(is_probe(command, "dumpsys activity lastanr")
                                         for command, timeout, limit in self.commands), api == 35)
                    if api == 23:
                        self.assertEqual(report["probes"]["last-anr"]["status"], "unsupported")
                        listing = self.commands[2][0]
                        self.assertTrue(is_probe(listing, "ls /data/anr"))
                    else:
                        self.assertIn("Input dispatching timed out", Path(
                            "test-results/release/failure-diagnostics/last-anr.txt").read_text())
                        self.assertEqual(report["probes"]["last-anr"]["remote_returncode"], 0)

    def test_physical_or_unrecognized_guests_are_not_inspected(self):
        for serial, name in [("physical-device", "miancak-release-23"),
                             ("emulator-5554", "personal-phone"),
                             ("emulator-5554", "miancak-release-35")]:
            commands = []
            def capture(command, timeout, limit):
                commands.append(command)
                return serial if len(commands) == 1 else name, {"status": "captured", "truncated": False}
            report = self.collect(capture)
            self.assertTrue(report["scope"].startswith("refused-or-unavailable"))
            self.assertLessEqual(len(commands), 2)
            self.assertFalse(any("shell" in command for command in commands))

    def test_errors_timeouts_and_absent_matching_trace_are_explicit_not_success(self):
        def capture(command, timeout, limit):
            if is_probe(command, 'test ! -L "$1" && cat "$1"'):
                raise subprocess.TimeoutExpired(command, timeout)
            if is_probe(command, "dumpsys activity activities " + smoke.PACKAGE):
                raise OSError("must not publish this error payload")
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        self.assertEqual(report["probes"]["app-anr-trace-1"]["status"], "timeout")
        self.assertEqual(report["probes"]["activities"]["status"], "unavailable")
        self.assertEqual(report["probes"]["app-anr-trace-1"]["content"], "unavailable")
        self.assertEqual(report["acceptance_result"], "failed")
        self.assertNotIn("must not publish", Path("test-results/release/failure-diagnostics/manifest.json").read_text())

    def test_readable_trace_without_app_context_does_not_claim_a_probe_failure(self):
        def capture(command, timeout, limit):
            if is_probe(command, 'test ! -L "$1" && cat "$1"'):
                return remote_output(OTHER_TRACE), {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        self.assertEqual(report["probes"]["app-anr-trace-1"]["status"], "captured")
        self.assertEqual(report["probes"]["app-anr-trace-1"]["content"], "no-matching-context")

    def test_total_budget_caps_each_probe_and_stops_new_commands(self):
        clock = [100.0]
        def capture(command, timeout, limit):
            text, record = self.capture(command, timeout, limit)
            clock[0] += timeout
            return text, record
        with mock.patch.object(smoke.time, "monotonic", side_effect=lambda: clock[0]):
            report = self.collect(capture, api=35)
        self.assertLessEqual(report["seconds"], smoke.DIAGNOSTIC_SECONDS)
        self.assertTrue(any(probe["status"] == "budget-exhausted" for probe in report["probes"].values()))
        self.assertLessEqual(sum(timeout for command, timeout, limit in self.commands), smoke.DIAGNOSTIC_SECONDS)

    def test_outer_deadline_is_not_extended_and_invalid_deadlines_fail_closed(self):
        for inherited in ("99", "nan", "inf", "invalid"):
            self.commands.clear()
            with mock.patch.dict(os.environ, {"MIANCAK_TIMEOUT_DEADLINE": inherited}), \
                 mock.patch.object(smoke.time, "monotonic", return_value=100):
                report = self.collect()
            self.assertEqual(self.commands, [])
            self.assertEqual(report["probes"]["serial"]["status"], "budget-exhausted")

    def test_output_files_are_bounded_even_with_large_matching_context(self):
        def capture(command, timeout, limit):
            if is_probe(command, 'test ! -L "$1" && cat "$1"'):
                text = APP_TRACE.replace("synthetic.Main.wait", "X" * (smoke.DIAGNOSTIC_FILE_LIMIT * 3))
                return remote_output(text), {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        directory = Path("test-results/release/failure-diagnostics")
        for path in directory.iterdir():
            self.assertLessEqual(path.stat().st_size, smoke.DIAGNOSTIC_FILE_LIMIT)
        self.assertTrue(report["probes"]["app-anr-trace-1"]["truncated"])
        self.assertTrue((directory / "app-anr-trace-1.txt").read_text().endswith("[diagnostic output truncated]\n"))

    def test_trace_list_paths_and_file_count_cannot_escape_the_scope(self):
        def capture(command, timeout, limit):
            if is_probe(command, "ls /data/anr"):
                return remote_output("../secret\n/data/user/0/private\ntraces.txt;touch bad\n traces.txt \n\tanr_fake\nanr_good\nanr_second\nanr_third\n"), \
                    {"status": "captured", "truncated": False}
            return self.capture(command, timeout, limit)
        self.collect(capture)
        reads = [command for command, timeout, limit in self.commands
                 if is_probe(command, 'test ! -L "$1" && cat "$1"')]
        # Parse the explicit remote command as the device shell would.
        # The sh -c script must remain exactly one quoted argument.
        parsed = [shlex.split(" ".join(command[4:])) for command in reads]
        self.assertEqual([args[:2] for args in parsed], [["sh", "-c"], ["sh", "-c"]])
        self.assertEqual([args[3:] for args in parsed], [
            ["diagnostic-read", "/data/anr/anr_good"], ["diagnostic-read", "/data/anr/anr_second"]])
        for args in parsed:
            self.assertTrue(args[2].startswith('{ test ! -L "$1" && cat "$1"; } 2>&1;'))
            self.assertIn('printf "\\nMIANCAK_DIAGNOSTIC_EXIT=%s\\n" "$diagnostic_status"', args[2])
        self.assertTrue(all(len(command) == 5 for command in reads))

    def test_api23_plain_non_pty_listing_prioritizes_legacy_trace_and_caps_selection(self):
        listing = "anr_new\r\ntraces.txt.old\r\ntraces.txt\r\nanr_old\r\ntraces.txt\r\n"
        def capture(command, timeout, limit):
            if is_probe(command, "ls /data/anr"):
                self.commands.append((command, timeout, limit))
                self.assertEqual(command[3], "exec-out")
                self.assertEqual(limit, 4096)
                return remote_output(listing, newline="\r\n"), {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        self.assertEqual(report["probes"]["last-anr"]["read_bytes"], 0)
        self.assertEqual(report["probes"]["last-anr"]["status"], "unsupported")
        self.assertFalse(any("lastanr" in remote_script(command) for command, timeout, limit in self.commands))
        reads = [shlex.split(command[4])[-1] for command, timeout, limit in self.commands
                 if is_probe(command, 'test ! -L "$1" && cat "$1"')]
        self.assertEqual(reads, ["/data/anr/traces.txt", "/data/anr/anr_new"])
        self.assertEqual(report["probes"]["anr-files"]["eligible_files"], 4)
        self.assertEqual(report["probes"]["anr-files"]["remote_returncode"], 0)
        saved = Path("test-results/release/failure-diagnostics/anr-files.txt").read_bytes()
        self.assertEqual(saved.decode(), listing)
        self.assertNotIn(smoke.DIAGNOSTIC_REMOTE_STATUS.encode(), saved)

    def test_legacy_host_zero_with_remote_listing_failure_is_not_empty_directory(self):
        # AOSP 6 toolbox's unsupported -1 response is 36 bytes through its
        # legacy PTY. Preserve that evidence, not an eligible_files=0 claim.
        error = "ls: Unknown option '-1'. Aborting.\r\n"
        self.assertEqual(len(error.encode()), 36)
        def capture(command, timeout, limit):
            if is_probe(command, "ls /data/anr"):
                self.commands.append((command, timeout, limit))
                return remote_output(error, 1, "\r\n"), {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        state = report["probes"]["anr-files"]
        self.assertEqual(state["returncode"], 0)
        self.assertEqual(state["remote_returncode"], 1)
        self.assertEqual(state["status"], "remote-error")
        self.assertEqual(state["content"], "unavailable")
        self.assertNotIn("eligible_files", state)
        self.assertFalse(any(is_probe(command, 'test ! -L "$1" && cat "$1"')
                             for command, timeout, limit in self.commands))
        self.assertEqual(Path("test-results/release/failure-diagnostics/anr-files.txt").read_bytes(), error.encode())
        self.assertEqual(report["acceptance_result"], "failed")

    def test_listing_without_complete_remote_status_cannot_claim_trace_absence(self):
        cases = [("", "captured", False), ("traces.txt\n", "captured", False),
                 (remote_output("", 999), "captured", False),
                 ("traces.txt\n", "output-limit", True)]
        for text, status, truncated in cases:
            with self.subTest(text=text, status=status):
                def capture(command, timeout, limit):
                    if is_probe(command, "ls /data/anr"):
                        return text, {"status": status, "returncode": 0, "truncated": truncated}
                    return self.capture(command, timeout, limit)
                report = self.collect(capture)
                state = report["probes"]["anr-files"]
                self.assertEqual(state["status"], "output-limit" if truncated else "unavailable")
                self.assertEqual(state["content"], "unavailable")
                self.assertNotIn("eligible_files", state)
                self.assertNotIn("app-anr-trace-1", report["probes"])

    def test_remote_trace_read_failure_is_not_no_matching_app_context(self):
        def capture(command, timeout, limit):
            if is_probe(command, 'test ! -L "$1" && cat "$1"'):
                return remote_output("cat: /data/anr/traces.txt: Permission denied\n", 1), \
                    {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        report = self.collect(capture)
        state = report["probes"]["app-anr-trace-1"]
        self.assertEqual(state["status"], "remote-error")
        self.assertEqual(state["remote_returncode"], 1)
        self.assertEqual(state["content"], "unavailable")

    def test_zero_status_unsupported_or_unexpected_lastanr_is_not_absence(self):
        legacy_error = "Bad activity command, or no activities match: lastanr\r\nUse -h for help.\r\n"
        self.assertEqual(len(legacy_error.encode()), 73)
        for response in (legacy_error, "", "unexpected diagnostic response\n"):
            with self.subTest(response=response):
                def capture(command, timeout, limit):
                    if is_probe(command, "dumpsys activity lastanr"):
                        return remote_output(response), {"status": "captured", "returncode": 0, "truncated": False}
                    return self.capture(command, timeout, limit)
                report = self.collect(capture, api=35)
                state = report["probes"]["last-anr"]
                self.assertEqual(state["status"], "unavailable")
                self.assertEqual(state["content"], "unavailable")
                self.assertEqual(state["remote_returncode"], 0)

    def test_successful_empty_listing_is_distinct_from_remote_failure(self):
        def capture(command, timeout, limit):
            if is_probe(command, "ls /data/anr"):
                return remote_output(""), {"status": "captured", "returncode": 0, "truncated": False}
            return self.capture(command, timeout, limit)
        state = self.collect(capture)["probes"]["anr-files"]
        self.assertEqual(state["status"], "captured")
        self.assertEqual(state["remote_returncode"], 0)
        self.assertEqual(state["eligible_files"], 0)
        self.assertEqual(state["listing_lines"], 0)

    def test_destination_directory_and_output_symlinks_are_rejected(self):
        outside = Path("outside"); outside.mkdir()
        Path("test-results").mkdir(); Path("test-results/release").symlink_to(outside.absolute(), target_is_directory=True)
        with mock.patch.object(smoke, "diagnostic_capture") as capture, self.assertRaises(ValueError):
            smoke.collect_failure_diagnostics(23, ValueError("fixture"))
        capture.assert_not_called(); self.assertEqual(list(outside.iterdir()), [])
        Path("test-results/release").unlink()
        directory = smoke.diagnostic_directory()
        marker = outside / "marker"; marker.write_text("unchanged")
        (directory / "anr-files.txt").symlink_to(marker.absolute())
        with self.assertRaises(OSError):
            self.collect()
        self.assertEqual(marker.read_text(), "unchanged")
        with self.assertRaises(ValueError):
            smoke.diagnostic_write(directory, "../outside.txt", "not allowed")

    def test_trace_matching_is_exact_not_another_app_or_the_debug_driver(self):
        text = APP_TRACE.replace(smoke.PACKAGE, smoke.PACKAGE + ".debug")
        text += APP_TRACE.replace(smoke.PACKAGE, smoke.PACKAGE + "unrelated")
        self.assertEqual(smoke.matching_anr_traces(text), "")
        child = APP_TRACE.replace(smoke.PACKAGE, smoke.PACKAGE + ":worker")
        self.assertEqual(smoke.matching_anr_traces(child).rstrip(), child.rstrip())
        self.assertIn("synthetic.Main.wait", smoke.matching_anr_traces(APP_TRACE.replace("\n", "\r\n")))
        spoofed = OTHER_TRACE.replace("OTHER_PROCESS_PRIVATE_STACK", "Cmd line: " + smoke.PACKAGE)
        self.assertEqual(smoke.matching_anr_traces(spoofed), "")

    def test_failed_instrumentation_keeps_the_existing_acceptance_failure(self):
        Path("test-results/release").mkdir(parents=True)
        with mock.patch.object(smoke, "shell", return_value="ANR in " + smoke.PACKAGE + "\nFAILURES!!!"), \
             contextlib.redirect_stdout(io.StringIO()), \
             self.assertRaisesRegex(smoke.ops.ReleaseError, "^RELEASE_INPUT_VERIFY$"):
            smoke.phase("verify")

    def exercise_main(self, failure=None, collector_error=None, transfer_error=None):
        signed = Path("signed"); signed.mkdir(exist_ok=True)
        record = {"version": "0.3.4", "apk_sha256": "f" * 64, "source_sha": "a" * 40}
        (signed / "release-provenance.json").write_text(json.dumps(record))
        order = []
        boot_ids = iter(("before", "after"))
        def shell(*args, **kwargs):
            if args == ("settings", "get", "secure", "default_input_method"):
                return "synthetic.other/.Ime"
            if args == ("getprop", "ro.build.version.sdk"):
                return "23"
            if args == ("getprop", "sys.boot_completed"):
                return "1"
            if args == ("cat", "/proc/sys/kernel/random/boot_id"):
                return next(boot_ids)
            return ""
        def phase(name):
            order.append(name)
            if failure is not None and name == "verify":
                raise failure
        def collect(*args):
            order.append("diagnostics")
            if collector_error is not None:
                raise collector_error
        def adb(*args, **kwargs):
            if args[0] == "pull":
                order.append("ui-pull")
                if transfer_error is not None:
                    raise transfer_error
            return b""
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(sys, "argv", ["device_smoke.py", "--api", "23"]))
            stack.enter_context(mock.patch.object(smoke, "shell", side_effect=shell))
            stack.enter_context(mock.patch.object(smoke, "adb", side_effect=adb))
            stack.enter_context(mock.patch.object(smoke, "root"))
            stack.enter_context(mock.patch.object(smoke, "inactive"))
            stack.enter_context(mock.patch.object(smoke, "phase", side_effect=phase))
            snapshots = stack.enter_context(mock.patch.object(smoke, "snapshot", return_value={"synthetic": "hash"}))
            collector = stack.enter_context(mock.patch.object(smoke, "collect_failure_diagnostics", side_effect=collect))
            stack.enter_context(mock.patch.object(smoke.ops, "verify_signed"))
            stack.enter_context(mock.patch.object(smoke.ops, "file_sha", return_value=record["apk_sha256"]))
            stack.enter_context(mock.patch.object(smoke.ops, "inspect_apk", return_value={"version_code": 7}))
            stack.enter_context(mock.patch.object(smoke.signal, "signal"))
            alarm = stack.enter_context(mock.patch.object(smoke.signal, "alarm"))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            if failure is None:
                smoke.main()
            else:
                with self.assertRaises(type(failure)) as observed:
                    smoke.main()
                self.assertIs(observed.exception, failure)
            self.assertEqual(alarm.call_args, mock.call(0))
            return order, collector.call_count, snapshots.call_count

    def test_original_failed_gate_survives_collector_and_ui_transfer_failures(self):
        failure = smoke.ops.ReleaseError("RELEASE_INPUT_VERIFY")
        order, collected, snapshots = self.exercise_main(failure, RuntimeError("collector fixture"),
            subprocess.TimeoutExpired(["adb", "pull"], 90))
        self.assertEqual(order, ["prepare", "verify", "diagnostics", "ui-pull"])
        self.assertEqual(collected, 1)
        self.assertFalse(Path("test-results/release/acceptance.json").exists())

    def test_success_does_not_collect_or_skip_existing_gate_phases(self):
        order, collected, snapshots = self.exercise_main()
        self.assertEqual(order, ["prepare", "verify", "rebooted", "ui-pull"])
        self.assertEqual(collected, 0); self.assertEqual(snapshots, 4)
        self.assertEqual(json.loads(Path("test-results/release/acceptance.json").read_text())["result"], "passed")

    def test_original_exit_status_is_not_replaced_by_final_transfer_exception(self):
        failure = SystemExit(42)
        order, collected, snapshots = self.exercise_main(failure, transfer_error=OSError("transfer fixture"))
        self.assertEqual(failure.code, 42)
        self.assertEqual(collected, 0)
        self.assertEqual(order, ["prepare", "verify", "ui-pull"])


class RemoteStatus(unittest.TestCase):
    def test_shell_parses_read_path_and_status_as_single_explicit_command(self):
        # Exercise the outer/inner shell quoting, not only mocked adb argv.
        # These are local synthetic fixtures; no adb or device is contacted.
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace fixture's.txt"
            trace.write_text(APP_TRACE)
            link = Path(temporary) / "trace-link"
            link.symlink_to(trace)
            for path, code in ((trace, 0), (link, 1), (Path(temporary) / "missing", 1)):
                with self.subTest(path=path):
                    command = smoke.diagnostic_remote_command('test ! -L "$1" && cat "$1"', (str(path),))
                    result = subprocess.run(["sh", "-c", command], capture_output=True, timeout=2)
                    # The wrapper itself succeeds even when the read fails,
                    # just as a legacy adb transport can return host zero.
                    self.assertEqual(result.returncode, 0)
                    text, record = smoke.diagnostic_remote_output(result.stdout.decode(),
                        {"status": "captured", "returncode": result.returncode, "truncated": False})
                    self.assertEqual(record["remote_returncode"], code)
                    self.assertEqual(record["status"], "captured" if code == 0 else "remote-error")
                    self.assertEqual(bool(smoke.matching_anr_traces(text)), code == 0)
                    self.assertEqual(result.stderr, b"")
            self.assertEqual(trace.read_text(), APP_TRACE)

    def test_remote_zero_cannot_override_transport_timeout_error_or_output_limit(self):
        for status in ("timeout", "command-error", "output-limit", "unavailable"):
            with self.subTest(status=status):
                text, record = smoke.diagnostic_remote_output(remote_output(APP_TRACE),
                    {"status": status, "returncode": 1, "truncated": status == "output-limit"})
                self.assertEqual(record["status"], status)
                self.assertEqual(record["returncode"], 1)
                self.assertEqual(record["remote_returncode"], 0)
                self.assertEqual(text, APP_TRACE)


class BoundedReader(unittest.TestCase):
    def process(self, code=0):
        process = mock.Mock()
        process.stdout.fileno.return_value = 123
        state = {"code": None}
        process.poll.side_effect = lambda: state["code"]
        process.wait.side_effect = lambda timeout: state.update(code=code) or code
        process.kill.side_effect = lambda: state.update(code=-9)
        return process

    def test_capture_stops_after_one_over_limit_byte_and_cleans_local_reader(self):
        process = self.process()
        selector = mock.MagicMock(); selector.__enter__.return_value = selector
        selector.select.return_value = [(None, None)]
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process) as popen, \
             mock.patch.object(smoke.selectors, "DefaultSelector", return_value=selector), \
             mock.patch.object(smoke.os, "read", side_effect=[b"12345678", b"9"]) as read:
            text, record = smoke.diagnostic_capture(["adb", "read-only-fixture"], 1, limit=8)
        self.assertEqual(text, "12345678"); self.assertEqual(record["status"], "output-limit")
        self.assertTrue(record["truncated"]); process.kill.assert_called_once()
        self.assertEqual(read.call_args_list, [mock.call(123, 9), mock.call(123, 1)])
        self.assertNotIn("shell", popen.call_args.kwargs)

    def test_hung_probe_is_timeout_and_local_adb_is_killed(self):
        process = self.process()
        selector = mock.MagicMock(); selector.__enter__.return_value = selector
        selector.select.return_value = []
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
             mock.patch.object(smoke.selectors, "DefaultSelector", return_value=selector):
            text, record = smoke.diagnostic_capture(["adb", "read-only-fixture"], .5)
        self.assertEqual(record["status"], "timeout"); process.kill.assert_called_once()
        self.assertLessEqual(selector.select.call_args.args[0], .5)

    def test_nonzero_and_missing_adb_are_unavailable_not_captured_success(self):
        process = self.process(code=1)
        selector = mock.MagicMock(); selector.__enter__.return_value = selector
        selector.select.return_value = [(None, None)]
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
             mock.patch.object(smoke.selectors, "DefaultSelector", return_value=selector), \
             mock.patch.object(smoke.os, "read", return_value=b""):
            text, record = smoke.diagnostic_capture(["adb", "read-only-fixture"], 1)
        self.assertEqual(record["status"], "command-error"); self.assertEqual(record["returncode"], 1)
        with mock.patch.object(smoke.subprocess, "Popen", side_effect=FileNotFoundError("adb")):
            text, record = smoke.diagnostic_capture(["adb", "read-only-fixture"], 1)
        self.assertEqual(record["status"], "unavailable")


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
