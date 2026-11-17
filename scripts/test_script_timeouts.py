#!/usr/bin/env python3
"""Exercise real hangs, nested deadlines, signal forwarding and script entry points."""
import os
from pathlib import Path
import sys

if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import json
import signal
import subprocess
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "scripts/run_with_timeout.py"


class ScriptTimeoutTest(unittest.TestCase):
    def run_cli(self, *args, extra_env=None):
        env = dict(os.environ)
        # Each case tests its own outer boundary, not the suite's deadline.
        env.pop("MIANCAK_TIMEOUT_DEADLINE", None)
        env.update(extra_env or {})
        start = time.monotonic()
        result = subprocess.run([sys.executable, str(RUNNER), *map(str, args)],
                                env=env, capture_output=True, text=True, timeout=15)
        return result, time.monotonic() - start

    def test_preserves_success_output_and_exit_code(self):
        result, _ = self.run_cli("--timeout-seconds", "5", "--", sys.executable, "-c",
                                 "import sys; print('out'); print('err',file=sys.stderr); sys.exit(7)")
        self.assertEqual(7, result.returncode)
        self.assertEqual("out\n", result.stdout)
        self.assertEqual("err\n", result.stderr)

    def test_env_timeout_and_cli_override(self):
        command = ["--", sys.executable, "-c", "import time; time.sleep(.3)"]
        result, elapsed = self.run_cli(*command, extra_env={"MIANCAK_SCRIPT_TIMEOUT_SECONDS": ".1"})
        self.assertEqual(124, result.returncode)
        self.assertLess(elapsed, 3)
        result, _ = self.run_cli("--timeout-seconds=.9", *command,
                                 extra_env={"MIANCAK_SCRIPT_TIMEOUT_SECONDS": ".1"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_unbounded_or_invalid_values(self):
        for value in ("0", "-1", "inf", "nan", "bad"):
            result, _ = self.run_cli("--timeout-seconds", value, "--", sys.executable, "-c", "pass")
            self.assertEqual(2, result.returncode, value)

    @unittest.skipUnless(Path("/proc/self/stat").exists(), "Linux process-tree acceptance")
    def test_kills_descendants_including_new_session_and_term_resistant_child(self):
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "children.json"
            code = ("import json,os,signal,subprocess,sys,time; "
                    "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                    "c='import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(90)'; "
                    "a=subprocess.Popen([sys.executable,'-c',c]); "
                    "b=subprocess.Popen([sys.executable,'-c',c],start_new_session=True); "
                    "open(sys.argv[1],'w').write(json.dumps([os.getpid(),a.pid,b.pid])); time.sleep(90)")
            result, elapsed = self.run_cli("--timeout-seconds", ".7", "--", sys.executable,
                                           "-c", code, record,
                                           extra_env={"MIANCAK_TIMEOUT_GRACE_SECONDS": ".2"})
            self.assertEqual(124, result.returncode, result.stderr)
            self.assertLess(elapsed, 3)
            from run_with_timeout import identity
            for pid in json.loads(record.read_text()):
                value = identity(pid)
                self.assertTrue(value is None or value[1] == "Z", f"Surviving descendant {pid}: {value}")

    def test_nested_command_cannot_extend_parent_budget(self):
        child = "import subprocess,sys,time; time.sleep(.2); subprocess.run(sys.argv[1:],timeout=10)"
        result, elapsed = self.run_cli("--timeout-seconds", ".7", "--", sys.executable, "-c", child,
                                      sys.executable, RUNNER, "--timeout-seconds", "10", "--",
                                      sys.executable, "-c", "import time; time.sleep(90)",
                                      extra_env={"MIANCAK_TIMEOUT_GRACE_SECONDS": ".2"})
        self.assertEqual(124, result.returncode)
        self.assertLess(elapsed, 3)

    def test_parent_stat_fallback_tracks_only_descendants(self):
        import run_with_timeout as watchdog
        # No task/children files. A command name containing ')' must not shift
        # the PPid/start-time fields.
        records = {101: (1, '10'), 102: (101, '20'), 103: (102, '30'), 104: (1, '40')}

        def read(path):
            if str(path).endswith('/children'):
                raise FileNotFoundError(path)
            pid = int(path.parts[2])
            parent, started = records[pid]
            return f"{pid} (test ) name) S {parent} " + '0 ' * 17 + started

        owned = {}
        with mock.patch.object(watchdog.Path, 'read_text', autospec=True, side_effect=read), \
             mock.patch.object(watchdog.Path, 'iterdir', return_value=iter(
                 [Path('/proc') / str(pid) for pid in records] + [Path('/proc/self')])):
            watchdog.remember_tree(101, owned)
        self.assertEqual({101: '10', 102: '20', 103: '30'}, owned)

    def test_remember_tree_never_reowns_a_reused_pid(self):
        import run_with_timeout as watchdog
        owned = {101: 'original-start'}
        with mock.patch.object(watchdog, 'identity', return_value=('replacement-start', 'S')), \
             mock.patch.object(watchdog.Path, 'read_text') as read:
            watchdog.remember_tree(101, owned)
        self.assertEqual({101: 'original-start'}, owned)
        read.assert_not_called()

    def test_parent_snapshot_rechecks_child_start_before_owning_it(self):
        import run_with_timeout as watchdog
        owned = {}
        with mock.patch.object(watchdog, 'identity', side_effect=lambda pid:
                               ('root-start', 'S') if pid == 101 else ('replacement-start', 'S')), \
             mock.patch.object(watchdog.Path, 'read_text', side_effect=FileNotFoundError), \
             mock.patch.object(watchdog, 'parent_snapshot', return_value={101: [(102, 'old-start')]}):
            watchdog.remember_tree(101, owned)
        self.assertEqual({101: 'root-start'}, owned)

    @unittest.skipIf(os.name == 'nt', 'POSIX process identity')
    def test_reused_pid_is_never_signalled(self):
        import run_with_timeout as watchdog
        child = mock.Mock(pid=101)
        with mock.patch.object(watchdog, 'identity', return_value=('replacement-start', 'S')), \
             mock.patch.object(watchdog.os, 'getpgid') as getpgid, \
             mock.patch.object(watchdog.os, 'kill') as kill, \
             mock.patch.object(watchdog.os, 'killpg') as killpg:
            watchdog.signal_tree(child, {101: 'original-start'}, signal.SIGTERM)
        getpgid.assert_not_called()
        kill.assert_not_called()
        killpg.assert_not_called()

    @unittest.skipIf(os.name == "nt", "POSIX cancellation")
    def test_cancellation_is_forwarded_and_returns_signal_status(self):
        with tempfile.TemporaryDirectory() as directory:
            for sig in (signal.SIGINT, signal.SIGTERM):
                pidfile = Path(directory) / ("pid-" + str(sig))
                received = Path(directory) / ("signal-" + str(sig))
                child = ("import os,signal,sys,time; "
                         "handler=lambda n,f: (open(sys.argv[2],'w').write(str(n)),sys.exit(0)); "
                         "signal.signal(signal.SIGINT,handler); signal.signal(signal.SIGTERM,handler); "
                         "open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(90)")
                process = subprocess.Popen([sys.executable, str(RUNNER), "--timeout-seconds", "10", "--",
                                            sys.executable, "-c", child, str(pidfile), str(received)],
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                end = time.monotonic() + 5
                while not pidfile.exists() and time.monotonic() < end:
                    time.sleep(.02)
                self.assertTrue(pidfile.exists())
                process.send_signal(sig)
                _, error = process.communicate(timeout=5)
                self.assertEqual(128 + sig, process.returncode, error)
                self.assertEqual(str(sig), received.read_text())
                from run_with_timeout import identity
                value = identity(int(pidfile.read_text()))
                self.assertTrue(value is None or value[1] == "Z")

    def test_python_shell_and_gradle_entry_points_accept_common_option(self):
        # A sleeping stand-in is reached through actual APK/Gradle entry scripts.
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory)
            for name in ("adb", "java"):
                path = fake / name
                path.write_text("#!/bin/sh\nsleep 90\n")
                path.chmod(0o755)
            env = dict(os.environ, MIANCAK_TEST_DISPOSABLE_GUEST="true", PATH=str(fake) + os.pathsep + os.environ["PATH"],
                       JAVA_HOME=str(fake), MIANCAK_TIMEOUT_GRACE_SECONDS=".2")
            (fake / "bin").mkdir()
            (fake / "bin/java").symlink_to(fake / "java")
            env.pop("MIANCAK_TIMEOUT_DEADLINE", None)
            # Use a temporary cwd so a driver cannot overwrite real evidence.
            linked = fake / "linked-gradlew"
            linked.symlink_to(ROOT / "gradlew")
            for entry, args in ((ROOT / "scripts/emulator_tests.sh", []), (ROOT / "gradlew", ["--version"]), (linked, ["--version"])):
                result = subprocess.run(["/bin/bash", str(entry), "--timeout-seconds", ".5", *args], cwd=fake,
                                        env=env, capture_output=True, text=True, timeout=8)
                self.assertEqual(124, result.returncode, (str(entry), result.stderr))
            result = subprocess.run([sys.executable, str(ROOT / "scripts/audit_fn_baseline.py"),
                                     "--timeout-seconds", "10"], cwd=ROOT, env=env,
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_every_tracked_script_has_a_finite_entry_guard(self):
        paths = subprocess.check_output(["git", "ls-files", "*.py", "*.sh", "gradlew", "gradlew.bat"],
                                        cwd=ROOT, text=True, timeout=10).splitlines()
        for name in paths:
            if name == "scripts/adb_timeout.sh":
                continue  # sourced command wrapper, no standalone work
            source = (ROOT / name).read_text()
            self.assertTrue("guard_script" in source or "run_with_timeout.py" in source, name)

    def test_live_log_reader_stops_when_apk_driver_fails_early(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory)
            adb = fake / "adb"
            adb.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> adb-commands.txt\ncase \"$*\" in\n"
                           "  'get-serialno') echo emulator-5554 ;;\n"
                           "  '-s emulator-5554 emu avd name') echo miancak-interactions ;;\n"
                           "  '-s emulator-5554 shell getprop ro.kernel.qemu') echo 1 ;;\n"
                           "  '-s emulator-5554 shell getprop ro.build.version.sdk') echo 35 ;;\n"
                           "  '-s emulator-5554 exec-out '*) printf '\\nMIANCAK_EMPTY_INSTALL_EXIT=0\\n' ;;\n"
                           "  'install '*) exit 7 ;;\n"
                           "  'logcat -v brief '*) sleep 90 ;;\n"
                           "  'logcat -b all -d') echo LOCAL_RAW_LOG_SENTINEL ;;\n"
                           "  'exec-out '*) exit 1 ;;\nesac\n")
            adb.chmod(0o755)
            env = dict(os.environ, MIANCAK_TEST_DISPOSABLE_GUEST="true", PATH=str(fake)+os.pathsep+os.environ["PATH"],
                       MIANCAK_TEST_API="35")
            env.pop("MIANCAK_TIMEOUT_DEADLINE", None)
            result = subprocess.run(["/bin/bash", str(ROOT / "scripts/emulator_tests.sh"),
                                     "--timeout-seconds", "5"], cwd=fake, env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertNotIn("LOCAL_RAW_LOG_SENTINEL", result.stdout + result.stderr)
            self.assertEqual("LOCAL_RAW_LOG_SENTINEL\n", (fake / "test-results/logcat.txt").read_text())
            commands = (fake / "adb-commands.txt").read_text()
            # Reject active probes regardless of signal spelling, shell wrapper
            # or absolute executable path, including debuggerd's stack modes.
            self.assertNotRegex(commands,
                                r"(?i)(?<![\w.-])(?:kill(?:all)?|pkill|debuggerd(?:32|64)?)(?![\w.-])")
            self.assertNotIn("shell ps", commands)
            self.assertNotIn("/data/anr/traces.txt", commands)
            self.assertFalse((fake / "test-results/thread-dump-request.txt").exists())
            self.assertFalse((fake / "test-results/processes-at-failure.txt").exists())
            self.assertFalse((fake / "test-results/art-thread-dump.txt").exists())



if __name__ == "__main__":
    unittest.main()
