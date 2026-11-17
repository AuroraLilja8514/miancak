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
import public_test_evidence as public


BEFORE = "11111111-1111-1111-1111-111111111111"
AFTER = "22222222-2222-2222-2222-222222222222"


class RebootReportTest(unittest.TestCase):
    def test_fresh_guest_guards_precede_reboot_and_evidence_collection(self):
        for override, environment, rejected in [
                ({}, {'MIANCAK_TEST_DISPOSABLE_GUEST':'false'}, None),
                ({('emu','avd','name'):'unrelated'}, {}, None),
                ({('shell','getprop','ro.build.version.sdk'):'23'}, {}, None),
                ({('shell','getprop','ro.kernel.qemu'):'0'}, {}, None),
                ({}, {}, ValueError('nonempty or unavailable package query'))]:
            values={('emu','avd','name'):'miancak-reboot-smoke',
                    ('shell','getprop','ro.build.version.sdk'):'35',
                    ('shell','getprop','ro.kernel.qemu'):'1', **override}
            with self.subTest(override=override,environment=environment,rejected=rejected), \
                 patch.dict(os.environ,{'MIANCAK_TEST_DISPOSABLE_GUEST':'true',**environment}), \
                 patch.object(sys,'argv',['emulator_reboot_smoke.py']), \
                 patch.object(smoke.subprocess,'check_output',return_value='emulator-5554'), \
                 patch.object(smoke,'adb',side_effect=lambda serial,*args:values[args]) as adb, \
                 patch.object(smoke,'require_empty_install',side_effect=rejected) as empty, \
                 patch.object(smoke.subprocess,'run') as run, \
                 self.assertRaises((RuntimeError,ValueError)):
                smoke.main()
            run.assert_not_called()
            self.assertFalse(any(c.args[1:]==('reboot',) for c in adb.call_args_list))
            if rejected:empty.assert_called_once_with('emulator-5554','org.miancak.keyboard')
            else:empty.assert_not_called()

    def exercise(self, observed_id, completed, offline_first=False):
        clock = [0.0]
        boot_reads = [0]

        def adb(_serial, *args, timeout=10):
            if args == ("emu", "avd", "name"):
                return "miancak-reboot-smoke"
            if args == ("shell", "getprop", "ro.build.version.sdk"):
                return "35"
            if args == ("shell", "getprop", "ro.kernel.qemu"): return "1"
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
            return SimpleNamespace(returncode=0, stdout="Android emulator 37.2.12 build_id 16428233")

        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as tmp:
            try:
                os.chdir(tmp)
                with patch.dict(os.environ, {"ANDROID_HOME": "/fake", "MIANCAK_TEST_DISPOSABLE_GUEST": "true"}), \
                     patch.object(sys, "argv",
                                  ["emulator_reboot_smoke.py", "--cycles", "1", "--boot-timeout-seconds", "3"]), \
                     patch.object(smoke.subprocess, "check_output", return_value="emulator-5554"), \
                     patch.object(smoke.subprocess, "run", side_effect=run), \
                     patch.object(smoke, "require_empty_install"), \
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


class TypedRebootEvidenceTest(unittest.TestCase):
    SECRET = 'PRIVATE_user.userdb_Intent_THREAD_SENTINEL'

    def exercise(self, stage=None, cycle=2, poll='complete', failure=None,
                 broken_checkpoint=None, broken_recorder=False):
        """Run the real driver and exporter with finite fake existing operations."""
        clock = [0.0]
        state = dict(cycle=0, initial=True, boot_id=BEFORE)
        calls, owners = [], []
        injected = failure if failure is not None else OSError(self.SECRET)
        writes = [0]
        original_init = smoke.RebootEvidence.__init__
        original_write = Path.write_text

        def init(owner, timer):
            original_init(owner, timer)
            owners.append(owner)

        def write(path, *args, **kwargs):
            if path.name == 'emulator-reboots.tmp':
                writes[0] += 1
                if broken_checkpoint == writes[0] or broken_checkpoint == 'all-final' and writes[0] > 1:
                    raise injected
            return original_write(path, *args, **kwargs)

        def adb(_serial, *args, timeout=10):
            calls.append((args, timeout))
            if args == ('emu', 'avd', 'name'): return 'miancak-reboot-smoke'
            if args == ('shell', 'getprop', 'ro.build.version.sdk'): return '35'
            if args == ('shell', 'getprop', 'ro.kernel.qemu'): return '1'
            if args == ('reboot',):
                if stage == 'REBOOT_REQUEST' and state['cycle'] == cycle: raise injected
                return ''
            if args == ('shell', 'cat', '/proc/sys/kernel/random/boot_id'):
                if state['initial']:
                    state['cycle'] += 1
                    state['initial'] = False
                    if stage == 'READ_BOOT_ID' and state['cycle'] == cycle: raise injected
                    if stage == 'INVALID_BOOT_ID' and state['cycle'] == cycle: return self.SECRET
                    return state['boot_id']
                if state['cycle'] == cycle:
                    if poll == 'id-timeout': raise subprocess.TimeoutExpired(self.SECRET, 5)
                    if poll == 'id-error': raise subprocess.CalledProcessError(7, self.SECRET, output=self.SECRET)
                    if poll == 'invalid-id': return self.SECRET
                    if poll == 'same-id': return state['boot_id']
                return AFTER if state['boot_id'] == BEFORE else BEFORE
            if args == ('shell', 'getprop', 'sys.boot_completed'):
                if state['cycle'] == cycle:
                    if poll == 'completion-timeout': raise subprocess.TimeoutExpired(self.SECRET, 5)
                    if poll == 'completion-error': raise subprocess.CalledProcessError(7, self.SECRET, output=self.SECRET)
                    if poll == 'empty': return ''
                    if poll == 'not-complete': return '0'
                    if poll == 'invalid-completion': return self.SECRET
                    if poll in ('same-id', 'invalid-id'): return '1'
                state['boot_id'] = AFTER if state['boot_id'] == BEFORE else BEFORE
                state['initial'] = True
                return '1'
            return self.SECRET  # Raw collector data must stay outside projection.

        def run(command, **kwargs):
            if command[0] == 'adb':
                calls.append((tuple(command), kwargs['timeout']))
                return SimpleNamespace(returncode=0, stdout=self.SECRET)
            return SimpleNamespace(returncode=0, stdout='Android emulator 37.2.12 build_id 16428233')

        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as tmp:
            try:
                os.chdir(tmp)
                with patch.dict(os.environ, {'ANDROID_HOME': '/fake', 'MIANCAK_TEST_DISPOSABLE_GUEST': 'true'}), \
                        patch.object(sys, 'argv', ['smoke', '--cycles', '3', '--boot-timeout-seconds', '3']), \
                        patch.object(smoke.subprocess, 'check_output', return_value='emulator-5554'), \
                        patch.object(smoke.subprocess, 'run', side_effect=run), \
                        patch.object(smoke, 'require_empty_install'), \
                     patch.object(smoke, 'adb', side_effect=adb), \
                        patch.object(smoke.RebootEvidence, '__init__', init), \
                        patch.object(smoke.time, 'monotonic', side_effect=lambda: clock[0]), \
                        patch.object(smoke.time, 'sleep', side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)), \
                        patch.object(Path, 'write_text', write), \
                        contextlib.redirect_stdout(io.StringIO()) as console:
                    with patch.object(smoke.RebootEvidence, 'row', side_effect=RuntimeError(self.SECRET)) if broken_recorder else contextlib.nullcontext():
                        try:
                            smoke.main()
                            error = None
                        except BaseException as exc:
                            error = exc
                self.assertNotIn(self.SECRET, console.getvalue())
                raw = Path('test-results/emulator-reboots.json')
                report = json.loads(raw.read_text()) if raw.exists() else {}
                Path('private-test-results').mkdir()
                Path('private-test-results/run.json').write_text(json.dumps(
                    dict(scenario='reboot', exit_code=1 if error else 0, seconds=clock[0])))
                with contextlib.redirect_stdout(io.StringIO()) as exported:
                    public.stage(Path.cwd(), 'reboot', 'failure' if error else 'success', 'a' * 40)
                self.assertEqual('PUBLIC_EVIDENCE_STAGED\n', exported.getvalue())
                folder = Path('public-test-results')
                self.assertEqual({'summary.json', 'heartbeat.jsonl'}, {p.name for p in folder.iterdir()})
                for path in folder.iterdir(): self.assertNotIn(self.SECRET, path.read_text())
                summary = json.loads((folder / 'summary.json').read_text())
                return error, report, summary, calls, owners, injected
            finally:
                os.chdir(previous)

    def test_real_driver_and_exporter_enums_match(self):
        for left, right in [('STAGES', 'REBOOT_STAGES'), ('CODES', 'REBOOT_CODES'),
                            ('POLLS', 'REBOOT_POLLS'), ('COMPLETIONS', 'REBOOT_COMPLETIONS')]:
            self.assertEqual(getattr(smoke.RebootEvidence, left), getattr(public, right))

    def test_success_is_complete_without_claiming_a_failure(self):
        error, report, summary, *_ = self.exercise()
        self.assertIsNone(error)
        self.assertTrue(report['passed'])
        row = summary['reboot_evidence']
        self.assertEqual(('COMPLETE', 3, 1, 'OK', True, 'COMPLETE'), tuple(row[k] for k in
            ('stage', 'cycle', 'poll_attempt', 'poll_status', 'boot_id_changed', 'completion')))
        self.assertEqual({'status': 'unavailable'}, row['failure'])
        self.assertEqual(3, summary['metrics']['reboot.cycles_completed'])

    def test_uncaught_operations_latch_the_exact_stage_cycle_and_kind(self):
        for stage, exception, code in [('READ_BOOT_ID', OSError(self.SECRET), 'HOST_IO'),
                ('REBOOT_REQUEST', subprocess.TimeoutExpired(self.SECRET, 30), 'ADB_TIMEOUT'),
                ('READ_BOOT_ID', subprocess.CalledProcessError(7, self.SECRET), 'ADB_ERROR')]:
            for cycle in (1, 2, 3):
                with self.subTest(stage=stage, cycle=cycle, code=code):
                    error, _, summary, *rest = self.exercise(stage=stage, cycle=cycle, failure=exception)
                    self.assertIs(error, exception)
                    self.assertEqual('failed', summary['result'])
                    row = summary['reboot_evidence']['failure']
                    self.assertEqual((stage, cycle, code, 0), tuple(row[k] for k in ('stage', 'cycle', 'code', 'poll_attempt')))
                    self.assertEqual('UNAVAILABLE', row['completion'])
                    self.assertIsNone(row['boot_id_changed'])

    def test_invalid_initial_boot_id_has_no_raw_value_or_comparison(self):
        error, _, summary, *_ = self.exercise(stage='INVALID_BOOT_ID')
        self.assertIsInstance(error, RuntimeError)
        row = summary['reboot_evidence']['failure']
        self.assertEqual(('INVALID_BOOT_ID', 'READ_BOOT_ID', 2), (row['code'], row['stage'], row['cycle']))
        self.assertIsNone(row['cycle_elapsed_ms'])
        self.assertIsNone(row['boot_id_changed'])

    def test_deadline_retains_only_last_poll_availability(self):
        for poll, status, changed, completion in [
                ('id-timeout', 'TIMEOUT', None, 'UNAVAILABLE'),
                ('id-error', 'ADB_ERROR', None, 'UNAVAILABLE'),
                ('completion-timeout', 'TIMEOUT', True, 'UNAVAILABLE'),
                ('completion-error', 'ADB_ERROR', True, 'UNAVAILABLE'),
                ('empty', 'OK', True, 'UNAVAILABLE'), ('not-complete', 'OK', True, 'NOT_COMPLETE'),
                ('invalid-completion', 'OK', True, 'INVALID'), ('invalid-id', 'OK', None, 'COMPLETE'),
                ('same-id', 'OK', False, 'COMPLETE')]:
            with self.subTest(poll=poll):
                error, _, summary, *_ = self.exercise(poll=poll)
                self.assertIsInstance(error, RuntimeError)
                row = summary['reboot_evidence']['failure']
                self.assertEqual(('BOOT_DEADLINE', 'WAIT_BOOT', 2, 3, status, changed, completion, 3000),
                    tuple(row[k] for k in ('code', 'stage', 'cycle', 'poll_attempt', 'poll_status',
                                          'boot_id_changed', 'completion', 'cycle_elapsed_ms')))
                self.assertEqual(1, summary['metrics']['reboot.cycles_completed'])
                self.assertEqual('failed', summary['result'])

    def test_checkpoint_failure_is_primary_and_later_checkpoint_does_not_replace_it(self):
        error, _, summary, calls, _, injected = self.exercise(broken_checkpoint=1)
        self.assertIs(error, injected)
        self.assertEqual(('INITIAL_CHECKPOINT', 0), tuple(summary['reboot_evidence']['failure'][k]
                                                       for k in ('stage', 'cycle')))
        # Preflight is unchanged, and initial report I/O failure adds no collector or mutation.
        self.assertEqual([('emu', 'avd', 'name'), ('shell', 'getprop', 'ro.build.version.sdk'), ('shell', 'getprop', 'ro.kernel.qemu')], [c[0] for c in calls])
        error, _, summary, _, owners, injected = self.exercise(broken_checkpoint='all-final')
        self.assertIs(error, injected)
        failure = owners[0].snapshot()['failure']
        self.assertEqual(('CYCLE_CHECKPOINT', 1, 'HOST_IO'), tuple(failure[k] for k in ('stage', 'cycle', 'code')))
        self.assertEqual('failed', summary['result'])

    def test_final_io_and_hostile_error_formatting_preserve_primary_object(self):
        class Hostile(OSError):
            def __str__(self): raise AssertionError('Formatting must not escape')
        error, _, summary, _, owners, injected = self.exercise(stage='REBOOT_REQUEST', cycle=1, failure=Hostile(),
                                                               broken_checkpoint='all-final')
        self.assertIs(error, injected)
        self.assertEqual('REBOOT_REQUEST', owners[0].snapshot()['failure']['stage'])
        self.assertEqual('failed', summary['result'])

    def test_recorder_failure_preserves_primary_and_exports_unavailable(self):
        error, _, summary, _, _, injected = self.exercise(stage='REBOOT_REQUEST', broken_recorder=True)
        self.assertIs(error, injected)
        self.assertEqual({'schema': 1, 'status': 'unavailable'}, summary['reboot_evidence'])
        self.assertEqual('failed', summary['result'])

    def test_latch_is_immutable_and_failed_attempt_cannot_be_replaced(self):
        clock = [0.0]
        owner = smoke.RebootEvidence(lambda: clock[0])
        owner.begin_cycle(2); owner.stage('READ_BOOT_ID'); owner.failed('INVALID_BOOT_ID')
        first = owner.snapshot()['failure']
        owner.stage('COMPLETE'); owner.begin_cycle(3); owner.failed('OTHER')
        owner.snapshot()['failure']['stage'] = self.SECRET  # Returned dictionaries cannot mutate owner.
        self.assertEqual(first, owner.snapshot()['failure'])
        second = smoke.RebootEvidence(lambda: clock[0])
        clock[0] = float('nan'); second.failed('OTHER'); clock[0] = 0
        second.failed('HOST_IO')
        self.assertEqual({'status': 'unavailable'}, second.snapshot()['failure'])

    def test_unavailable_clocks_and_numeric_bounds_are_not_health_claims(self):
        for elapsed in (-1, float('inf'), float('nan'), 86401):
            clock = [0.0]; owner = smoke.RebootEvidence(lambda: clock[0]); clock[0] = elapsed
            self.assertEqual({'schema': 1, 'status': 'unavailable'}, owner.snapshot())
        for cycle, polls in [(-1, 0), (6, 0), (1, -1), (1, 3602)]:
            owner = smoke.RebootEvidence(lambda: 0)
            owner.begin_cycle(cycle); owner.poll_attempt = polls
            self.assertEqual({'schema': 1, 'status': 'unavailable'}, owner.snapshot())
        owner = smoke.RebootEvidence(lambda: 0); owner.stage(self.SECRET)
        self.assertEqual({'schema': 1, 'status': 'unavailable'}, owner.snapshot())

    def test_poll_reset_never_reuses_a_previous_comparison_or_completion(self):
        owner = smoke.RebootEvidence(lambda: 0); owner.begin_cycle(1); owner.begin_poll()
        owner.boot_id(BEFORE, AFTER); owner.completed('1'); owner.poll_status = 'OK'
        owner.begin_poll()
        row = owner.snapshot()
        self.assertEqual((2, None, 'UNAVAILABLE', 'UNAVAILABLE'), tuple(row[k] for k in
                         ('poll_attempt', 'boot_id_changed', 'completion', 'poll_status')))


if __name__ == "__main__":
    unittest.main()
