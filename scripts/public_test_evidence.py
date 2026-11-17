#!/usr/bin/env python3
"""Run device drivers behind a console boundary; stage typed public evidence only."""
if __name__ == '__main__':
    from run_with_timeout import guard_script
    guard_script(__file__)

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import stat
import subprocess
import tempfile
import time

SCENARIOS = {'interaction', 'first-install', 'word-table', 'word-pack', 'upgrade', 'reboot', 'release', 'n3'}
PHASES = ('word-tables-native', 'native', 'interactions', 'layout-files', 'restart',
          'launcher-lifecycle', 'clean-install', 'reopen', 'reboot', 'quality',
          'word-native', 'word-prepare', 'word-verify', 'word-reboot',
          'upgrade-prepare', 'upgrade-verify', 'upgrade-reboot', 'prepare', 'verify', 'rebooted')
METRICS = {'deploymentVerificationMicros', 'runtimeOpenMicros', 'warmComposeAndConfirmP50Micros',
           'warmComposeAndConfirmP95Micros', 'testProcessPssKiB', 'coreCount', 'coreTop1', 'coreTop5',
           'sentenceCount', 'sentenceTop1', 'coldDeployMicros', 'coreInputP50Micros',
           'coreInputP95Micros', 'processPssKiB', 'deployedBytes'}
SCOPES = {'DRIVER', 'FN', 'SYMBOL', 'PAGE_EDITOR', 'LIFECYCLE', 'FIRST_INSTALL'}
STAGES = set('START FN_ENTER FN_RETURN SYMBOL_ENTER SYMBOL_RETURN PAGE_EDITOR_ENTER PAGE_EDITOR_RETURN LIFECYCLE_ENTER LIFECYCLE_RETURN FINALLY_ENTER FINALLY_RETURN EVIDENCE_ENTER EVIDENCE_RETURN TEARDOWN_ENTER TEARDOWN_RETURN CATEGORY_ENTER CATEGORY_RETURN CATEGORY_LOOKUP_ENTER CATEGORY_LOOKUP_RETURN CATEGORY_BOUNDS_ENTER CATEGORY_BOUNDS_RETURN CATEGORY_SCROLL_LOOKUP_ENTER CATEGORY_SCROLL_LOOKUP_RETURN SCROLL_ENTER SCROLL_RETURN CLICK_ENTER CLICK_RETURN PAGE_WAIT_ENTER PAGE_WAIT_RETURN TAB_WAIT_ENTER TAB_WAIT_RETURN LAYOUT_WAIT_ENTER LAYOUT_WAIT_RETURN KEYBOARD_LOOKUP_ENTER KEYBOARD_LOOKUP_RETURN BOUNDS_ENTER BOUNDS_RETURN TOUCH_ENTER TOUCH_RETURN INSTRUMENTATION_IDLE_ENTER INSTRUMENTATION_IDLE_RETURN UI_IDLE_ENTER UI_IDLE_RETURN MAIN_SYNC_ENTER MAIN_SYNC_RETURN RECEIVED_TEXT_ENTER RECEIVED_TEXT_RETURN SCREENSHOT_ENTER SCREENSHOT_RETURN PNG_ENTER PNG_RETURN HIERARCHY_ENTER HIERARCHY_RETURN WAKE_ENTER WAKE_RETURN ORIENTATION_ENTER ORIENTATION_RETURN ROTATION_ENTER ROTATION_RETURN BACK_ENTER BACK_RETURN SETTINGS_ENTER SETTINGS_RETURN SETTINGS_LOOKUP_ENTER SETTINGS_LOOKUP_RETURN SETTINGS_CLICK_ENTER SETTINGS_CLICK_RETURN SETTINGS_WAIT_ENTER SETTINGS_WAIT_RETURN LAUNCH_ENTER LAUNCH_RETURN PICKER_ENTER PICKER_RETURN ENABLE_ENTER ENABLE_RETURN FOCUS_ENTER FOCUS_RETURN CANDIDATE_ENTER CANDIDATE_RETURN LOOKUP_ENTER LOOKUP_RETURN POLICY_ENTER POLICY_RETURN POLICY_SCROLL_ENTER POLICY_SCROLL_RETURN POLICY_LOOKUP_ENTER POLICY_LOOKUP_RETURN POLICY_CLICK_ENTER POLICY_CLICK_RETURN POLICY_WAIT_ENTER POLICY_WAIT_RETURN FRAME_WAIT_ENTER FRAME_WAIT_RETURN SCREENSHOT_FILES_ENTER SCREENSHOT_FILES_RETURN RECEIVER_WAIT_ENTER RECEIVER_WAIT_RETURN POLICY_READ_ENTER POLICY_READ_RETURN STATE_READ_ENTER STATE_READ_RETURN'.split())
RUNS = {'progress-interactions-main.jsonl': ('interactions', 'main'),
        'progress-interactions-restart.jsonl': ('interactions', 'restart'),
        'progress-first-install-clean-install.jsonl': ('first-install', 'clean-install'),
        'progress-first-install-reopen.jsonl': ('first-install', 'reopen'),
        'progress-first-install-reboot.jsonl': ('first-install', 'reboot')}
EVENTS = {'start', 'reserved_before_post', 'pending', 'exit_stop', 'exit_deadline'}
POSTS = {'none', 'accepted', 'rejected', 'threw_outcome_unknown', 'cancelled_before_post'}
FAILURE_PHASES = {'FLOW', 'CLEANUP'}
FAILURE_KINDS = {'ASSERTION', 'AUTOMATION', 'TIMEOUT', 'IO', 'INTERRUPTED', 'OTHER'}
REBOOT_STAGES = {'INITIAL_CHECKPOINT', 'READ_BOOT_ID', 'REBOOT_REQUEST', 'POLL_BOOT_ID',
                 'POLL_COMPLETION', 'WAIT_BOOT', 'CYCLE_CHECKPOINT', 'COMPLETE'}
REBOOT_CODES = {'INVALID_BOOT_ID', 'BOOT_DEADLINE', 'ADB_TIMEOUT', 'ADB_ERROR', 'HOST_IO', 'OTHER'}
REBOOT_POLLS = {'UNAVAILABLE', 'OK', 'TIMEOUT', 'ADB_ERROR'}
REBOOT_COMPLETIONS = {'UNAVAILABLE', 'COMPLETE', 'NOT_COMPLETE', 'INVALID'}
EMPTY_INSTALL_STATUS = 'MIANCAK_EMPTY_INSTALL_EXIT='


def empty_install_command(package):
    if package not in ('org.miancak.keyboard', 'org.miancak.keyboard.debug'):
        raise ValueError('invalid package')
    # Like the read-only release collector: exec-out/shell-v1 cannot establish
    # remote success from the host adb exit alone. Keep status in the byte stream.
    script = ('{ pm list packages ' + package + '; } 2>&1; install_status=$?; '
              'printf "\\n' + EMPTY_INSTALL_STATUS + '%s\\n" "$install_status"')
    return 'sh -c ' + shlex.quote(script)


def require_empty_install(serial, package):
    if not re.fullmatch(r'emulator-[0-9]+', serial): raise ValueError('invalid serial')
    result = subprocess.run(['adb', '-s', serial, 'exec-out', empty_install_command(package)],
                            capture_output=True, timeout=10)
    # No strip(): even whitespace/error output is not a verified empty result.
    if result.returncode or result.stderr or len(result.stdout) > 4096 \
            or not re.fullmatch(rb'\r?\n' + EMPTY_INSTALL_STATUS.encode() + rb'0\r?\n', result.stdout):
        raise ValueError('existing or unavailable application install')


def number(value, maximum=2**63-1):
    return type(value) in (int, float) and 0 <= value <= maximum and math.isfinite(value)


def read_file(root, relative, limit):
    """No symlink components, devices/FIFOs, archives, recursion or path escape."""
    parts = Path(relative).parts
    if not parts or Path(relative).is_absolute() or any(p in ('', '.', '..') for p in parts):
        raise ValueError('invalid path')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            following = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = following
        child = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        try:
            info = os.fstat(child)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
                raise ValueError('invalid file')
            data = bytearray()
            while len(data) <= limit:
                chunk = os.read(child, min(65536, limit + 1 - len(data)))
                if not chunk: return bytes(data)
                data.extend(chunk)
            raise ValueError('oversized file')
        finally: os.close(child)
    finally: os.close(fd)


def document(root, relative, limit=262144):
    return json.loads(read_file(root, relative, limit))


def optional(root, relative, limit=262144):
    try: return document(root, relative, limit)
    except (OSError, ValueError, TypeError, RecursionError): return None


def failure_record(value):
    if value == {'status': 'unavailable'}: return value
    keys = {'status', 'phase', 'kind', 'observed_ms', 'scope', 'stage', 'seq', 'stage_ms',
            'call', 'category', 'page', 'attempt'}
    if not isinstance(value, dict) or set(value) != keys or value['status'] != 'observed' \
            or value['phase'] not in FAILURE_PHASES or value['kind'] not in FAILURE_KINDS \
            or value['scope'] not in SCOPES or value['stage'] not in STAGES:
        raise ValueError('invalid fixed failure')
    for key in ('observed_ms', 'seq', 'stage_ms', 'call', 'category'):
        if type(value[key]) is not int or not number(value[key]): raise ValueError('invalid failure number')
    for key in ('page', 'attempt'):
        if type(value[key]) is not int or not -1 <= value[key] <= 31: raise ValueError('invalid failure detail')
    if value['call'] > 256 or value['category'] > 7: raise ValueError('invalid failure bound')
    return value


def reboot_record(value):
    """Strict projection of the existing reboot operations, never raw report fields."""
    if isinstance(value, dict) and set(value) == {'schema', 'status'} and value['status'] == 'unavailable':
        if type(value['schema']) is not int or value['schema'] != 1: raise ValueError('invalid reboot schema')
        return {'schema': 1, 'status': 'unavailable'}
    fields = {'stage', 'cycle', 'elapsed_ms', 'cycle_elapsed_ms', 'poll_attempt',
              'poll_status', 'boot_id_changed', 'completion'}
    def row(row):
        if row['stage'] not in REBOOT_STAGES or row['poll_status'] not in REBOOT_POLLS \
                or row['completion'] not in REBOOT_COMPLETIONS:
            raise ValueError('invalid reboot enum')
        for name, cap in (('cycle', 5), ('elapsed_ms', 86400000), ('poll_attempt', 3601)):
            if type(row[name]) is not int or not number(row[name], cap): raise ValueError('invalid reboot number')
        if row['cycle_elapsed_ms'] is not None and (type(row['cycle_elapsed_ms']) is not int
                or not number(row['cycle_elapsed_ms'], 86400000)): raise ValueError('invalid cycle timing')
        if row['boot_id_changed'] is not None and type(row['boot_id_changed']) is not bool:
            raise ValueError('invalid boot comparison')
    if not isinstance(value, dict) or set(value) != fields | {'schema', 'status', 'failure'} \
            or type(value['schema']) is not int or value['schema'] != 1 or value['status'] != 'available':
        raise ValueError('invalid reboot protocol')
    row(value)
    failure = value['failure']
    if failure != {'status': 'unavailable'}:
        if not isinstance(failure, dict) or set(failure) != fields | {'status', 'code'} or failure['status'] != 'observed' \
                or failure['code'] not in REBOOT_CODES:
            raise ValueError('invalid reboot failure')
        row(failure)
    return value


def heartbeat(root, name, identity):
    candidates = []
    for prefix in ('test-results/device/', 'test-results/device/test-results/'):
        try: candidates.append(read_file(root, prefix + name, 65536))
        except FileNotFoundError: pass
    if len(candidates) != 1: raise ValueError('missing or ambiguous heartbeat')
    lines = candidates[0].splitlines()
    if not 2 <= len(lines) <= 96: raise ValueError('invalid record count')
    rows = [json.loads(line) for line in lines]
    header = rows[0]
    if not isinstance(header, dict): raise ValueError('invalid header')
    if header.get('schema') not in (1, 2) or header != {'schema': header['schema'], 'scenario': identity[0], 'phase': identity[1],
                  'clock': 'elapsedRealtime_ms', 'started_ms': header.get('started_ms'),
                  'interval_ms': 10000, 'window_ms': 840000,
                  'meaning': 'ack_only_proves_ordinary_main_callback_ran;missing_is_ambiguous'}:
        raise ValueError('invalid header')
    if any(type(header[k]) is not int for k in ('schema','interval_ms','window_ms')): raise ValueError('invalid header type')
    if type(header['started_ms']) is not int or not number(header['started_ms']): raise ValueError('invalid time')
    keys = {'event', 'at_ms', 'elapsed_ms', 'scope', 'stage', 'seq', 'stage_ms', 'call', 'category',
            'page', 'attempt', 'request', 'ack', 'ack_ms', 'previous_post', 'post_token', 'post_call_ms', 'post_return_ms'}
    observed_failure = None
    for row in rows[1:]:
        if not isinstance(row,dict): raise ValueError('invalid row')
        if set(row) != (keys | {'failure'} if header['schema'] == 2 else keys) or row['event'] not in EVENTS or row['scope'] not in SCOPES \
                or row['stage'] not in STAGES or row['previous_post'] not in POSTS:
            raise ValueError('invalid heartbeat')
        for key in keys - {'event', 'scope', 'stage', 'previous_post', 'page', 'attempt'}:
            if type(row[key]) is not int or not number(row[key]): raise ValueError('invalid number')
        for key in ('page', 'attempt'):
            if type(row[key]) is not int or not -1 <= row[key] <= 31: raise ValueError('invalid detail')
        if row['call'] > 256 or row['category'] > 7 or row['request'] > 96 or row['ack'] > 96 or row['post_token'] > 96:
            raise ValueError('invalid bound')
        if header['schema'] == 2:
            failure = failure_record(row['failure'])
            if failure['status'] == 'observed':
                if observed_failure is not None and observed_failure != failure: raise ValueError('replaced primary failure')
                observed_failure = failure
            elif observed_failure is not None: raise ValueError('lost primary failure')
    return rows


def metrics(root):
    output = {}
    for file in ('native-metrics.json', 'word-pack-quality.json'):
        data = optional(root, 'test-results/device/' + file)
        if not isinstance(data, dict): continue
        for group in ('native', 'baseline', 'candidate', 'core'):
            values = data if group == 'native' else data.get(group)
            if not isinstance(values, dict): continue
            for key in sorted(METRICS):
                value = values.get(key)
                if number(value): output[group + '.' + key] = value
    # Hashes and private filenames stay local; these maps contain only derived
    # hashes/counts, not raw learning DBs. Never forward their keys or digests.
    for phase in ('before-install', 'after-install', 'before-reboot', 'after-reboot'):
        data = optional(root, 'test-results/learning-' + phase + '.json')
        if isinstance(data, dict) and len(data) <= 10000 and all(
                isinstance(v, dict) and number(v.get('bytes')) for v in data.values()):
            output['learning.' + phase + '.file_count'] = len(data)
            total = sum(v['bytes'] for v in data.values())
            if number(total): output['learning.' + phase + '.bytes'] = total
    try:
        footprint = read_file(root, 'test-results/rime-footprint-kib.txt', 256).decode('ascii').strip()
        match = re.fullmatch(r'([0-9]{1,12})\s+files/rime', footprint)
        if match: output['rime_directory_kib'] = int(match[1])
    except (OSError, ValueError): pass
    return output



def n3_summary(root):
    """Only four typed reported profile outcomes; never images, rows or reasons."""
    fd = os.open(root/'test-results', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        names=[]
        with os.scandir(fd) as entries:
            for i, entry in enumerate(entries):
                if i >= 1000: return None
                if re.fullmatch(r'settings-alignment-[0-9]{8}T[0-9]{6}Z-(23|35)-(en-US|zh-CN)', entry.name):
                    names.append(entry.name)
    finally: os.close(fd)
    if len(names) != 1: return None
    name=names[0];prefix=name.removeprefix('settings-alignment-')
    data=optional(root,'test-results/'+name+'/case-status.json')
    if not isinstance(data,dict) or data.get('schemaVersion') != 1 or type(data.get('hostRestorationVerified')) is not bool \
            or not isinstance(data.get('cases'),list) or len(data['cases']) != 4: return None
    result={'reported_host_restoration_verified':data['hostRestorationVerified'],
            'alignment_verdict':'not-evaluated','reported_profiles':{}}
    for font in ('1_0','1_3'):
        for rotation in ('0','1'):
            short='font'+font+'-rotation'+rotation
            cases=[c for c in data['cases'] if isinstance(c,dict) and c.get('captureId')==prefix+'-'+short]
            if len(cases)!=1: return None
            case=cases[0]
            if case.get('status') not in ('complete','failed','unrun') or case.get('alignmentVerdict') != 'not-evaluated':return None
            result['reported_profiles'][short]={'outcome':case['status']}
            if type(case.get('viewports')) is int and number(case['viewports'],1000000):
                result['reported_profiles'][short]['viewports']=case['viewports']
    return result

def stage(root, scenario, outcome, source, api=None, locale=None, entry_order=None):
    if scenario not in SCENARIOS or outcome not in {'success', 'failure', 'cancelled', 'skipped'}:
        raise ValueError('invalid fixed identity')
    root = Path(root).absolute()
    destination = root / 'public-test-results'
    if destination.exists() or destination.is_symlink(): raise ValueError('public destination exists')
    report = {'schema': 2, 'scenario': scenario, 'workflow_outcome': outcome,
              'details': 'unavailable-public-policy', 'phase_results': {}, 'metrics': metrics(root),
              'heartbeat': {}, 'provenance': {}, 'failure_evidence': {},
              'reboot_evidence': {'schema': 1, 'status': 'unavailable'}}
    if re.fullmatch('[0-9a-f]{40}', source): report['provenance']['harness_source_sha'] = source
    if api in (23, 35): report['api'] = api
    if locale in ('en-US','zh-CN'): report['locale'] = locale
    if entry_order in ('settings-first','ime-first'): report['entry_order'] = entry_order
    run = optional(root, 'private-test-results/run.json', 4096)
    if isinstance(run, dict) and run.get('scenario') == scenario:
        if type(run.get('exit_code')) is int and 0 <= run['exit_code'] <= 255: report['driver_exit_code'] = run['exit_code']
        if number(run.get('seconds'), 86400): report['driver_seconds'] = run['seconds']
    for phase in PHASES:
        for prefix in ('test-results/', 'test-results/release/'):
            try: text = read_file(root, prefix + phase + '.log', 8*1024*1024).decode('utf-8', errors='replace')
            except (OSError, ValueError): continue
            failed = bool(re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=', text))
            match = re.search(r'(?m)^OK \(([0-9]{1,6}) tests?\)\r?$', text)
            result = {'outcome': 'failed' if failed else 'passed' if match else 'unavailable'}
            if match: result['tests'] = int(match[1])
            timing = re.search(r'(?m)^Time: ([0-9.]{1,15})\r?$', text)
            if timing:
                try:
                    value = float(timing[1])
                    if number(value, 86400): result['seconds'] = value
                except ValueError: pass
            report['phase_results'][phase] = result
    report['result'] = ('failed' if outcome == 'failure' or report.get('driver_exit_code', 0) != 0
                        else 'passed' if outcome == 'success' and report.get('driver_exit_code') == 0 else 'unavailable')
    reboot = optional(root, 'test-results/emulator-reboots.json')
    if isinstance(reboot, dict):
        try: report['reboot_evidence'] = reboot_record(reboot.get('diagnostic'))
        except (ValueError, TypeError, KeyError, RecursionError): pass
    if isinstance(reboot, dict) and isinstance(reboot.get('cycles'), list) and len(reboot['cycles']) <= 5:
        report['metrics']['reboot.cycles_completed'] = len(reboot['cycles'])
        for i, cycle in enumerate(reboot['cycles']):
            if isinstance(cycle, dict) and number(cycle.get('seconds'), 86400):
                report['metrics']['reboot.cycle' + str(i + 1) + '.seconds'] = cycle['seconds']
    probes = optional(root, 'test-results/release/failure-diagnostics/manifest.json')
    if isinstance(probes, dict) and isinstance(probes.get('probes'), dict):
        report['diagnostics'] = {}
        for name in ('serial', 'avd', 'last-anr', 'anr-files', 'app-anr-trace-1', 'app-anr-trace-2',
                     'activities', 'windows', 'system-log', 'event-log'):
            entry = probes['probes'].get(name)
            if isinstance(entry, dict) and entry.get('status') in {'captured','timeout','unsupported','remote-error',
                    'command-error','unavailable','output-limit','budget-exhausted'}:
                report['diagnostics'][name] = {'status': entry['status']}
                for key in ('read_bytes', 'bytes', 'returncode', 'remote_returncode'):
                    if type(entry.get(key)) is int and number(entry[key], 1048576):
                        report['diagnostics'][name][key] = entry[key]
    report['raw_markers_seen'] = {key: False for key in ('anr', 'fatal_exception', 'fatal_signal')}
    report['raw_marker_sources_read'] = 0
    for path in ('test-results/logcat.txt','test-results/windows.txt',
                 'test-results/release/failure-diagnostics/system-log.txt',
                 'test-results/release/failure-diagnostics/windows.txt'):
        try: text = read_file(root, path, 8*1024*1024).decode('utf-8', errors='replace')
        except (OSError, ValueError): continue
        report['raw_marker_sources_read'] += 1
        for key, pattern in [('anr', r"ANR in|Application Not Responding|isn't responding"),
                             ('fatal_exception', 'FATAL EXCEPTION'), ('fatal_signal', 'Fatal signal')]:
            report['raw_markers_seen'][key] |= bool(re.search(pattern, text))
    release = optional(root, 'test-results/release/acceptance.json')
    if isinstance(release, dict):
        for key in ('api', 'baseline_version_code', 'version_code'):
            if type(release.get(key)) is int and number(release[key], 2**31-1): report['metrics']['release.'+key] = release[key]
    # Only explicit application inputs are hashed, never copied or unpacked.
    for role, path in (('app', 'artifacts/app-debug.apk'), ('test', 'artifacts/app-debug-androidTest.apk')):
        try: report['provenance'][role+'_sha256'] = hashlib.sha256(read_file(root, path, 256*1024*1024)).hexdigest()
        except (OSError, ValueError): pass
    if scenario == 'release':
        for role, basename in (('driver_app', 'app-debug.apk'), ('driver_test', 'app-debug-androidTest.apk'),
                               ('previous_app', 'previous.apk')):
            hashes = []
            for folder in ('prepared', 'rehearsal'):
                try: hashes.append(hashlib.sha256(read_file(root, folder+'/'+basename, 256*1024*1024)).hexdigest())
                except (OSError, ValueError): pass
            if len(hashes) == 1: report['provenance']['release_'+role+'_sha256'] = hashes[0]
    for folder in ('signed', 'rehearsal'):
        try:
            fd = os.open(root/folder, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                names=[]
                with os.scandir(fd) as entries:
                    for i, entry in enumerate(entries):
                        if i>=64: names=[]; break
                        if re.fullmatch(r'miancak-[0-9][0-9A-Za-z_.-]{0,60}\.apk',entry.name): names.append(entry.name)
            finally: os.close(fd)
            if len(names)==1:
                report['provenance']['release_app_sha256']=hashlib.sha256(read_file(root,folder+'/'+names[0],256*1024*1024)).hexdigest()
        except (OSError,ValueError): pass
    rows = []
    for name, identity in RUNS.items():
        report['failure_evidence'][name] = {'status': 'unavailable'}
        try:
            records = heartbeat(root, name, identity)
            report['heartbeat'][name] = 'available'
            for record in records[1:]:
                if record.get('failure', {}).get('status') == 'observed':
                    report['failure_evidence'][name] = record['failure']
            rows.extend({'run': name, 'record': record} for record in records)
        except (OSError, ValueError, TypeError, RecursionError, KeyError): report['heartbeat'][name] = 'unavailable'
    if scenario == 'n3':
        report['n3_device_details'] = 'unavailable-no-public-screenshot-policy'
        try: reported = n3_summary(root)
        except OSError: reported = None
        if reported is not None: report['n3'] = reported
    temporary = Path(tempfile.mkdtemp(prefix='.public-evidence-', dir=root))
    try:
        summary = (json.dumps(report, sort_keys=True, allow_nan=False) + '\n').encode()
        beats = b''.join((json.dumps(row, sort_keys=True, allow_nan=False)+'\n').encode() for row in rows)
        if len(summary)>65536 or len(beats)>512*1024: raise ValueError('public cap')
        (temporary/'summary.json').write_bytes(summary)
        (temporary/'heartbeat.jsonl').write_bytes(beats)
        temporary.rename(destination)
    finally:
        if temporary.exists(): shutil.rmtree(temporary)
    print('PUBLIC_EVIDENCE_STAGED', flush=True)


def run(root, scenario, command):
    if scenario not in SCENARIOS or not command: raise ValueError('invalid driver')
    root = Path(root).absolute()
    private = root/'private-test-results'
    private.mkdir(mode=0o700)  # Fresh per job; a pre-existing/symlink path fails closed.
    started = time.monotonic()
    log = (private/'console.log').open('xb')
    failed_io = False
    try:
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        saved = 0; next_progress = started + 60
        with selectors.DefaultSelector() as ready:
            ready.register(child.stdout, selectors.EVENT_READ)
            while ready.get_map():
                for key, _ in ready.select(.25):
                    chunk = os.read(key.fd, 65536)
                    if not chunk: ready.unregister(key.fileobj); break
                    keep = chunk[:max(0, 8*1024*1024-saved)]
                    if not failed_io:
                        try: log.write(keep)
                        except OSError: failed_io = True
                    saved += len(keep)
                if time.monotonic() >= next_progress:
                    print('DEVICE_TEST_RUNNING', flush=True); next_progress = time.monotonic()+60
        code = child.wait()
        child.stdout.close()
    finally:
        try: log.close()
        except OSError: failed_io = True
    code = code if code >= 0 else 128-code
    try:
        (private/'run.json').write_text(json.dumps({'scenario': scenario, 'exit_code': code,
            'seconds': round(time.monotonic()-started, 3)})+'\n')
    except OSError: failed_io = True
    if failed_io: print('DEVICE_EVIDENCE_UNAVAILABLE', flush=True)
    print('DEVICE_TEST_PASSED' if code == 0 else 'DEVICE_TEST_FAILED', flush=True)
    return code


def target(scenario):
    if os.environ.get('MIANCAK_TEST_DISPOSABLE_GUEST') != 'true': raise ValueError('fresh guest attestation required')
    names = {
        'interaction': 'miancak-interactions', 'first-install': 'miancak-clean',
        'word-pack': 'miancak-quality', 'word-table': 'miancak-words',
        'upgrade': 'miancak-upgrade'}
    def adb(*args):
        r = subprocess.run(['adb', *args], capture_output=True, timeout=10)
        if r.returncode or len(r.stdout) > 4096: raise ValueError('target unavailable')
        return r.stdout.decode('utf-8').strip()
    serial = adb('get-serialno')
    if not re.fullmatch(r'emulator-[0-9]+', serial): raise ValueError('not emulator')
    def guest(*args): return adb('-s', serial, *args)
    api = guest('shell', 'getprop', 'ro.build.version.sdk')
    expected = os.environ.get('MIANCAK_TEST_API', api)
    if api not in ('23', '35') or api != expected or guest('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise ValueError('not test guest')
    name = guest('emu', 'avd', 'name').splitlines()[0]
    if scenario not in names or name != names[scenario]:
        raise ValueError('not named test guest')
    require_empty_install(serial, 'org.miancak.keyboard')
    # Identity/absence checks are not proof of a user's data being absent. CI
    # independently provisions a fresh wiped named AVD; no existing data is cleared.
    print(serial, flush=True)


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, 'PUBLIC_EVIDENCE_ARGUMENT_ERROR\n')

def main():
    parser = SafeParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True, parser_class=SafeParser)
    check = sub.add_parser('target'); check.add_argument('--scenario', choices=sorted(SCENARIOS), required=True)
    empty = sub.add_parser('empty-install')
    empty.add_argument('--serial', required=True)
    empty.add_argument('--package', choices=['org.miancak.keyboard', 'org.miancak.keyboard.debug'], required=True)
    execute = sub.add_parser('run'); execute.add_argument('--scenario', choices=sorted(SCENARIOS), required=True)
    execute.add_argument('command', nargs=argparse.REMAINDER)
    export = sub.add_parser('stage'); export.add_argument('--scenario', choices=sorted(SCENARIOS), required=True)
    export.add_argument('--outcome', choices=['success','failure','cancelled','skipped'], required=True)
    export.add_argument('--source', default='')
    export.add_argument('--api', type=int, choices=[23,35])
    export.add_argument('--locale', choices=['en-US','zh-CN'])
    export.add_argument('--entry-order', choices=['settings-first','ime-first'])
    args = parser.parse_args()
    try:
        if args.operation == 'empty-install':
            require_empty_install(args.serial, args.package); print('EMPTY_INSTALL_VERIFIED', flush=True); return 0
        if args.operation == 'target': target(args.scenario); return 0
        if args.operation == 'stage': stage(Path.cwd(), args.scenario, args.outcome, args.source, args.api, args.locale, args.entry_order); return 0
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        return run(Path.cwd(), args.scenario, command)
    except Exception:
        # No exception, filename, subprocess argv or raw bytes reach CI console.
        print('PUBLIC_EVIDENCE_FAILED', flush=True); return 1


if __name__ == '__main__': raise SystemExit(main())
