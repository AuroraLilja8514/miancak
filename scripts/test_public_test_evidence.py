#!/usr/bin/env python3
"""Host-only public boundary checks. No devices, credentials or raw uploads."""
if __name__ == '__main__':
    from run_with_timeout import guard_script
    guard_script(__file__)

import contextlib
import io
import json
import os
import re
import shlex
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import tarfile
import zipfile
import unittest
from unittest import mock
import public_test_evidence as evidence

ROOT = Path(__file__).resolve().parents[1]
SECRET = 'PRIVATE_LEARNING_PROCESS_LOG_SENTINEL_abcXYZ'


class Privacy(unittest.TestCase):
    def test_wanxiang_quality_exports_only_existing_fixed_numeric_fields(self):
        self.put('test-results/device/word-pack-quality.json', json.dumps({
            'wanxiang-base': {'coreCount': 70, 'coreTop1': 69, 'coreTop5': 70,
                             'samples': SECRET, 'model_path': SECRET, 'source': SECRET,
                             'processPssKiB': float('inf')}, SECRET: {'coreCount': 1}}))
        report = self.stage()
        self.assertEqual(70, report['metrics']['wanxiang-base.coreCount'])
        self.assertEqual(69, report['metrics']['wanxiang-base.coreTop1'])
        self.assertNotIn('wanxiang-base.samples', report['metrics'])
        self.assertNotIn('wanxiang-base.processPssKiB', report['metrics'])
        self.assertNotIn(SECRET, json.dumps(report))

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        patch=mock.patch.dict(os.environ,{'MIANCAK_TEST_DISPOSABLE_GUEST':'true'});patch.start();self.addCleanup(patch.stop)

    def put(self, path, value):
        p = self.root/path; p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(value if isinstance(value, bytes) else value.encode()); return p

    def stage(self, outcome='failure', scenario='interaction'):
        output = io.StringIO()
        with contextlib.redirect_stdout(output): evidence.stage(self.root, scenario, outcome, 'a'*40)
        self.assertNotIn(SECRET, output.getvalue())
        directory = self.root/'public-test-results'
        self.assertEqual({'summary.json','heartbeat.jsonl'}, {p.name for p in directory.iterdir()})
        for p in directory.iterdir(): self.assertNotIn(SECRET.encode(), p.read_bytes())
        return json.loads((directory/'summary.json').read_text())

    def test_hostile_tree_archives_logs_preferences_and_apks_never_copied(self):
        for name in ['processes-at-failure.txt','logcat.txt','windows.txt','art-thread-dump.txt',
                     'private-evidence.tar','nested/secret.zip','device/user.userdb','device/shared_prefs.xml',
                     'device/no_backup/settings-alignment-preferences.json','device/secret.apk']:
            self.put('test-results/'+name, SECRET)
        self.put('test-results/device/native-metrics.json', json.dumps({'runtimeOpenMicros':12, 'scope':SECRET}))
        self.put('test-results/interactions.log', SECRET+'\nFAILURES!!!\nOK (3 tests)\nTime: 1.2\n')
        self.put('artifacts/app-debug.apk', SECRET)
        report=self.stage()
        self.assertEqual('failure',report['workflow_outcome'])
        self.assertEqual('failed',report['phase_results']['interactions']['outcome'])
        self.assertEqual(12,report['metrics']['native.runtimeOpenMicros'])
        self.assertEqual(3,report['phase_results']['interactions']['tests'])
        self.assertRegex(report['provenance']['app_sha256'],r'^[a-f0-9]{64}$')

    def test_learning_outputs_are_reduced_to_counts_not_paths_or_digests(self):
        self.put('test-results/learning-before-install.json',json.dumps({SECRET:{'bytes':24,'sha256':SECRET}}))
        report=self.stage(); self.assertEqual(1,report['metrics']['learning.before-install.file_count'])
        self.assertEqual(24,report['metrics']['learning.before-install.bytes'])

    def test_footprint_and_quality_numeric_metrics_survive_without_samples(self):
        self.put('test-results/rime-footprint-kib.txt','1234\tfiles/rime\r\n')
        self.put('test-results/device/word-pack-quality.json',json.dumps({'baseline':{'coreCount':70,
            'samples':[SECRET]},'candidate':{'coldDeployMicros':100},'core':{'deployedBytes':234}}))
        values=self.stage()['metrics']
        self.assertEqual(1234,values['rime_directory_kib'])
        self.assertEqual(70,values['baseline.coreCount'])
        self.assertEqual(100,values['candidate.coldDeployMicros'])
        self.assertEqual(234,values['core.deployedBytes'])

    def test_release_driver_input_hashes_are_computed_not_copied(self):
        for folder,name in [('prepared','app-debug.apk'),('prepared','app-debug-androidTest.apk'),
                            ('signed','miancak-0.3.1.apk')]:
            self.put(folder+'/'+name,SECRET)
        report=self.stage(scenario='release')
        self.assertEqual({'harness_source_sha','release_driver_app_sha256','release_driver_test_sha256',
                          'release_app_sha256'},set(report['provenance']))

    def test_links_to_sensitive_files_and_directories_not_followed(self):
        secret=self.put('outside/sensitive',SECRET)
        (self.root/'test-results').mkdir()
        (self.root/'test-results/device').symlink_to(secret.parent, target_is_directory=True)
        report=self.stage(); self.assertFalse(report['metrics'])

    def test_known_file_symlink_and_hardlink_are_refused(self):
        secret=self.put('outside/sensitive',SECRET)
        dest=self.root/'test-results/interactions.log';dest.parent.mkdir()
        dest.symlink_to(secret)
        report=self.stage();self.assertFalse(report['phase_results'])
        with self.assertRaises(ValueError): evidence.read_file(self.root,'../outside',100)
        dest.unlink();os.link(secret,dest)
        with self.assertRaises(ValueError): evidence.read_file(self.root,'test-results/interactions.log',100)

    def test_existing_or_linked_public_destination_fails_closed(self):
        outside=self.root/'outside';outside.mkdir();(outside/'summary.json').write_text(SECRET)
        (self.root/'public-test-results').symlink_to(outside,target_is_directory=True)
        with self.assertRaises(ValueError): evidence.stage(self.root,'interaction','success','a'*40)
        self.assertEqual(SECRET,(outside/'summary.json').read_text())

    def test_staging_io_failure_blocks_upload_and_prints_only_fixed_token(self):
        self.put('private-test-results/run.json',json.dumps({'scenario':'interaction','exit_code':42,'seconds':2}))
        with mock.patch.object(Path,'write_bytes',side_effect=OSError(SECRET)), \
                mock.patch.object(Path,'cwd',return_value=self.root), \
                mock.patch.object(sys,'argv',['public_test_evidence.py','stage','--scenario','interaction',
                                              '--outcome','failure']), contextlib.redirect_stdout(io.StringIO()) as output:
            code=evidence.main()
        self.assertEqual(1,code)
        self.assertEqual('PUBLIC_EVIDENCE_FAILED\n',output.getvalue())
        self.assertFalse((self.root/'public-test-results').exists())
        self.assertEqual(42,json.loads((self.root/'private-test-results/run.json').read_text())['exit_code'])

    def test_malformed_and_oversized_metrics_do_not_leak(self):
        self.put('test-results/device/native-metrics.json','{'+SECRET)
        self.put('test-results/native.log',SECRET*(1024*1024))
        self.assertFalse(self.stage()['metrics'])

    def test_n3_details_unavailable_even_if_prepared_claim_is_forged(self):
        self.put('test-results/settings-alignment/report.json',json.dumps({'prepared':True,'text':SECRET}))
        self.put('test-results/settings-alignment/screenshot.png',SECRET)
        report=self.stage(scenario='n3')
        self.assertEqual('unavailable-no-public-screenshot-policy',report['n3_device_details'])

    def test_heartbeat_valid_bounded_protocol_survives(self):
        header={'schema':1,'scenario':'interactions','phase':'main','clock':'elapsedRealtime_ms','started_ms':1,
                'interval_ms':10000,'window_ms':840000,'meaning':'ack_only_proves_ordinary_main_callback_ran;missing_is_ambiguous'}
        row={'event':'pending','at_ms':12,'elapsed_ms':11,'scope':'SYMBOL','stage':'CATEGORY_ENTER','seq':4,
             'stage_ms':13,'call':1,'category':1,'page':0,'attempt':0,'request':1,'ack':0,'ack_ms':0,
             'previous_post':'accepted','post_token':1,'post_call_ms':2,'post_return_ms':3}
        text='\n'.join(map(json.dumps,[header,row]))+'\n'
        self.put('test-results/device/progress-interactions-main.jsonl',text)
        report=self.stage();self.assertEqual('available',report['heartbeat']['progress-interactions-main.jsonl'])
        self.assertIn(b'CATEGORY_ENTER',(self.root/'public-test-results/heartbeat.jsonl').read_bytes())

    def test_heartbeat_unknown_text_and_extra_fields_are_rejected(self):
        self.put('test-results/device/progress-interactions-main.jsonl',json.dumps({'schema':1,'text':SECRET})+'\n{}\n')
        report=self.stage();self.assertEqual('unavailable',report['heartbeat']['progress-interactions-main.jsonl'])

    def test_runner_captures_raw_console_and_preserves_failure(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code=evidence.run(self.root,'interaction',[sys.executable,'-c',
                'import sys;print('+repr(SECRET)+');print('+repr(SECRET)+',file=sys.stderr);sys.exit(42)'])
        self.assertEqual(42,code);self.assertNotIn(SECRET,output.getvalue())
        self.assertIn(SECRET,(self.root/'private-test-results/console.log').read_text())
        self.assertEqual(42,self.stage()['driver_exit_code'])

    def test_runner_report_io_failure_does_not_replace_original_exit(self):
        real=Path.write_text
        def fail(path,*args,**kwargs):
            if path.name=='run.json':raise OSError(SECRET)
            return real(path,*args,**kwargs)
        with mock.patch.object(Path,'write_text',fail), contextlib.redirect_stdout(io.StringIO()) as output:
            code=evidence.run(self.root,'release',[sys.executable,'-c','raise SystemExit(37)'])
        self.assertEqual(37,code);self.assertNotIn(SECRET,output.getvalue())

    def test_cli_errors_do_not_echo_sensitive_arguments(self):
        r=subprocess.run([sys.executable,str(ROOT/'scripts/public_test_evidence.py'),'stage','--scenario',SECRET],
                         capture_output=True,text=True,timeout=10,cwd=self.root)
        self.assertNotEqual(0,r.returncode);self.assertNotIn(SECRET,r.stdout+r.stderr)

    def test_target_refusal_is_read_only_and_never_echoes_foreign_response(self):
        calls=[]
        def fake(cmd,**kwargs):
            calls.append(cmd)
            return subprocess.CompletedProcess(cmd,0,SECRET.encode(),b'')
        with mock.patch.object(evidence.subprocess,'run',fake), self.assertRaises(ValueError): evidence.target('interaction')
        self.assertEqual([['adb','get-serialno']],calls)

    def test_target_guard_accepts_only_named_empty_application_install(self):
        def fake(cmd,**kwargs):
            suffix=cmd[3:] if cmd[1:3]==['-s','emulator-5554'] else cmd[1:]
            values={('get-serialno',):'emulator-5554',('emu','avd','name'):'miancak-interactions\nOK',
                    ('shell','getprop','ro.kernel.qemu'):'1',('shell','getprop','ro.build.version.sdk'):'35',
                    ('exec-out',evidence.empty_install_command('org.miancak.keyboard')):'\n'+evidence.EMPTY_INSTALL_STATUS+'0\n'}
            return subprocess.CompletedProcess(cmd,0,values[tuple(suffix)].encode(),b'')
        with mock.patch.object(evidence.subprocess,'run',fake), contextlib.redirect_stdout(io.StringIO()) as output:
            evidence.target('interaction')
        self.assertEqual('emulator-5554\n',output.getvalue())

    def test_retired_or_mismatched_api_is_refused_before_empty_install_query(self):
        for api, expected in [('23','23'), ('33','33'), ('35','33')]:
            calls=[]
            def fake(cmd,**kwargs):
                calls.append(cmd)
                suffix=cmd[3:] if cmd[1:3]==['-s','emulator-5554'] else cmd[1:]
                values={('get-serialno',):'emulator-5554',
                        ('shell','getprop','ro.build.version.sdk'):api}
                return subprocess.CompletedProcess(cmd,0,values[tuple(suffix)].encode(),b'')
            with self.subTest(api=api,expected=expected), \
                 mock.patch.dict(os.environ,{'MIANCAK_TEST_API':expected}), \
                 mock.patch.object(evidence.subprocess,'run',fake), self.assertRaises(ValueError):
                evidence.target('interaction')
            self.assertEqual(2,len(calls))

    def test_empty_install_requires_host_success_and_complete_canonical_remote_zero(self):
        marker=b'\n'+evidence.EMPTY_INSTALL_STATUS.encode()+b'0\n'
        for payload,host,stderr in [(b'',0,b''),(marker[:-1],0,b''),(marker[:-2],0,b''),
                (marker.replace(b'=0',b'=x'),0,b''),(marker.replace(b'=0',b'=00'),0,b''),
                (marker.replace(b'=0',b'=1'),0,b''),(marker+marker,0,b''),
                (b' '+marker,0,b''),(SECRET.encode()+marker,0,b''),(marker,7,b''),(marker,0,SECRET.encode())]:
            with self.subTest(payload=payload,host=host,stderr=stderr):
                with mock.patch.object(evidence.subprocess,'run',return_value=
                        subprocess.CompletedProcess([],host,payload,stderr)) as run:
                    with self.assertRaises(ValueError) as failure:
                        evidence.require_empty_install('emulator-5554','org.miancak.keyboard')
                self.assertNotIn(SECRET,str(failure.exception))
                self.assertEqual(['adb','-s','emulator-5554','exec-out',
                    evidence.empty_install_command('org.miancak.keyboard')],run.call_args.args[0])
                self.assertEqual(10,run.call_args.kwargs['timeout'])
        for payload in (marker,marker.replace(b'\n',b'\r\n')):
            with mock.patch.object(evidence.subprocess,'run',return_value=subprocess.CompletedProcess([],0,payload,b'')):
                evidence.require_empty_install('emulator-5554','org.miancak.keyboard')

    def test_empty_install_failure_console_is_fixed_and_contains_no_remote_bytes(self):
        for payload in (b'',SECRET.encode()+b'\n'+evidence.EMPTY_INSTALL_STATUS.encode()+b'0\n'):
            with mock.patch.object(sys,'argv',['public_test_evidence.py','empty-install','--serial',
                    'emulator-5554','--package','org.miancak.keyboard.debug']), \
                    mock.patch.object(evidence.subprocess,'run',return_value=subprocess.CompletedProcess([],0,payload,b'')), \
                    contextlib.redirect_stdout(io.StringIO()) as output,contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(1,evidence.main())
            self.assertEqual('PUBLIC_EVIDENCE_FAILED\n',output.getvalue())
            self.assertEqual('',errors.getvalue())

    def test_empty_install_shell_wrapper_records_remote_exit_and_arbitrary_output(self):
        pm=self.put('bin/pm',b'#!/bin/sh\nprintf "%s" "$PM_TEXT"\nexit "$PM_EXIT"\n');pm.chmod(0o755)
        for remote,text in ((0,''),(7,''),(0,SECRET)):
            with self.subTest(remote=remote,text=text):
                result=subprocess.run(['sh','-c',evidence.empty_install_command('org.miancak.keyboard.debug')],
                    capture_output=True,timeout=2,env=dict(os.environ,PATH=str(pm.parent)+os.pathsep+os.environ['PATH'],
                    PM_EXIT=str(remote),PM_TEXT=text))
                self.assertEqual(0,result.returncode)  # Legacy transport host-zero is insufficient.
                self.assertEqual((text+'\n'+evidence.EMPTY_INSTALL_STATUS+str(remote)+'\n').encode(),result.stdout)

    def test_all_five_shell_drivers_refuse_unmarked_empty_query_before_collection(self):
        fake=self.put('bin/adb',r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
with Path('commands.jsonl').open('a') as out:out.write(json.dumps(args)+'\n')
if args[:2]==['-s','emulator-5554']:args=args[2:]
values={('get-serialno',):'emulator-5554',('emu','avd','name'):os.environ['FAKE_AVD'],
('shell','getprop','ro.build.version.sdk'):'35',('shell','getprop','ro.kernel.qemu'):'1'}
if tuple(args) in values:print(values[tuple(args)])
elif args[:1]==['exec-out']:pass  # host zero, remote EOF, no completion marker
else:raise SystemExit(98)
''');fake.chmod(0o755)
        for script,name in [('emulator_tests.sh','miancak-interactions'),('first_install_tests.sh','miancak-clean'),
                ('word_pack_quality_tests.sh','miancak-quality'),('word_table_apk_tests.sh','miancak-words'),
                ('upgrade_apk_tests.sh','miancak-upgrade')]:
            with self.subTest(script=script):
                directory=self.root/script;directory.mkdir()
                env=dict(os.environ,PATH=str(fake.parent)+os.pathsep+os.environ['PATH'],FAKE_AVD=name,
                         MIANCAK_TEST_API='35',MIANCAK_TEST_DISPOSABLE_GUEST='true')
                result=subprocess.run(['bash',str(ROOT/'scripts'/script),'--timeout-seconds','5'],
                                      cwd=directory,env=env,capture_output=True,text=True,timeout=10)
                self.assertNotEqual(0,result.returncode)
                self.assertFalse((directory/'test-results').exists())
                calls=[json.loads(line) for line in (directory/'commands.jsonl').read_text().splitlines()]
                self.assertEqual(1,sum(call[2:3]==['exec-out'] for call in calls))
                self.assertTrue(all(call==['get-serialno'] or call[:2]==['-s','emulator-5554'] and
                    (call[2:4] in (['shell','getprop'],['emu','avd']) or call[2:3]==['exec-out']) for call in calls))

    def test_workflows_gate_exact_file_uploads_and_capture_every_device_driver(self):
        paths=['.github/workflows/ci.yml','.github/workflows/release-checks.yml','tools/release/release-workflow.yml.in','.github/workflows/release.yml']
        count=0
        for file in paths:
            for job in re.split(r'(?=^  [A-Za-z][A-Za-z0-9_-]*:\s*$)',(ROOT/file).read_text(),flags=re.M):
                if 'android-emulator-runner@' not in job:continue
                count+=1
                self.assertIn('MIANCAK_TEST_DISPOSABLE_GUEST: "true"',job)
                self.assertEqual(1,job.count('id: device-tests'))
                self.assertIn('public_test_evidence.py run --scenario',job)
                self.assertEqual(1,job.count('id: public-evidence'))
                for step in re.split(r'(?=^      - )',job,flags=re.M):
                    if 'upload-artifact@' not in step:continue
                    self.assertIn("steps.public-evidence.outcome == 'success'",step)
                    self.assertIn('path: |\n            public-test-results/summary.json\n            public-test-results/heartbeat.jsonl',step)
                    self.assertNotRegex(step,r'path: test-results')
        self.assertGreaterEqual(count,9)

    def test_phase_success_cannot_hide_driver_or_workflow_failure(self):
        self.put('test-results/native.log','OK (7 tests)\n')
        self.put('private-test-results/run.json',json.dumps({'scenario':'interaction','exit_code':42,'seconds':2}))
        self.assertEqual('failed',self.stage(outcome='success')['result'])

    def test_local_anr_marker_and_probe_status_survive_without_text(self):
        self.put('test-results/logcat.txt',"ANR in "+SECRET)
        self.put('test-results/release/failure-diagnostics/manifest.json',json.dumps({'probes':{
            'windows':{'status':'captured','read_bytes':123,'file':SECRET},SECRET:{'status':'captured'}}}))
        report=self.stage()
        self.assertTrue(report['raw_markers_seen']['anr'])
        self.assertEqual(1,report['raw_marker_sources_read'])
        self.assertEqual({'status':'captured','read_bytes':123},report['diagnostics']['windows'])

    def test_marker_ids_are_exactly_the_test_protocol(self):
        core=(ROOT/'androidTest/juloo/keyboard2/ProgressEvidence.java').read_text()
        stages=re.search(r'enum Stage\s*\{(.*?)\n  \}',core,re.S)[1]
        self.assertEqual(set(re.findall(r'\b[A-Z][A-Z_]+\b',stages)),evidence.STAGES)

    def test_n3_typed_profile_outcomes_keep_no_screenshot_geometry_or_reasons(self):
        prefix='20261116T120000Z-35-en-US'
        cases=[{'captureId':prefix+'-font'+font+'-rotation'+rotation,'status':'failed',
                'alignmentVerdict':'not-evaluated','reason':SECRET,'viewports':4}
               for font in ('1_0','1_3') for rotation in ('0','1')]
        self.put('test-results/settings-alignment-'+prefix+'/case-status.json',
                 json.dumps({'schemaVersion':1,'hostRestorationVerified':False,'cases':cases,'private':SECRET}))
        report=self.stage(scenario='n3')
        self.assertEqual(4,len(report['n3']['reported_profiles']))
        self.assertFalse(report['n3']['reported_host_restoration_verified'])
        self.assertEqual('unavailable-no-public-screenshot-policy',report['n3_device_details'])

    def test_success_refusal_of_existing_target_has_no_mutating_cleanup(self):
        calls=[]
        def fake(cmd,**kwargs):
            calls.append(cmd)
            suffix=cmd[3:] if cmd[1:3]==['-s','emulator-5554'] else cmd[1:]
            values={('get-serialno',):'emulator-5554',('emu','avd','name'):'miancak-interactions\nOK',
                    ('shell','getprop','ro.kernel.qemu'):'1',('shell','getprop','ro.build.version.sdk'):'35',
                    ('exec-out',evidence.empty_install_command('org.miancak.keyboard')):'package:org.miancak.keyboard.debug\n'+evidence.EMPTY_INSTALL_STATUS+'0\n'}
            return subprocess.CompletedProcess(cmd,0,values[tuple(suffix)].encode(),b'')
        with mock.patch.object(evidence.subprocess,'run',fake), self.assertRaises(ValueError): evidence.target('interaction')
        self.assertFalse(any('install' in c or 'root' in c or 'force-stop' in c for c in calls))

    def test_real_nested_archives_and_traversal_members_are_never_opened(self):
        nested=io.BytesIO()
        with zipfile.ZipFile(nested,'w') as archive:
            archive.writestr('../public-test-results/summary.json',SECRET)
            archive.writestr('private.userdb',SECRET)
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode='w') as archive:
            entry=tarfile.TarInfo('../escape');entry.size=len(SECRET)
            archive.addfile(entry,io.BytesIO(SECRET.encode()))
            entry=tarfile.TarInfo('nested.zip');entry.size=len(nested.getvalue())
            archive.addfile(entry,io.BytesIO(nested.getvalue()))
        self.put('test-results/private-evidence.tar',stream.getvalue())
        self.stage();self.assertFalse((self.root/'escape').exists())

    def test_raw_acceptance_digest_fields_are_not_trusted_as_provenance(self):
        self.put('test-results/release/acceptance.json',json.dumps({'source_sha':'c'*40,'apk_sha256':'d'*64,
                 'certificate_sha256':'e'*64,'data':SECRET}))
        report=self.stage(scenario='release')
        self.assertEqual({'harness_source_sha':'a'*40},report['provenance'])

    def test_runner_log_write_failure_preserves_original_failure(self):
        class Broken(io.BytesIO):
            def write(self,value):raise OSError(SECRET)
        real=Path.open
        def opened(path,*args,**kwargs):
            return Broken() if path.name=='console.log' else real(path,*args,**kwargs)
        with mock.patch.object(Path,'open',opened), contextlib.redirect_stdout(io.StringIO()) as output:
            code=evidence.run(self.root,'interaction',[sys.executable,'-c','print("raw");raise SystemExit(41)'])
        self.assertEqual(41,code);self.assertNotIn(SECRET,output.getvalue())

    def test_missing_disposable_attestation_refuses_before_adb(self):
        with mock.patch.dict(os.environ,{'MIANCAK_TEST_DISPOSABLE_GUEST':''}), mock.patch.object(evidence.subprocess,'run') as adb:
            with self.assertRaises(ValueError):evidence.target('interaction')
            adb.assert_not_called()

    def test_console_capture_has_a_byte_cap_and_still_drains(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            code=evidence.run(self.root,'interaction',[sys.executable,'-c',
                'import sys;sys.stdout.write(('+repr(SECRET)+')*250000)'])
        self.assertEqual(0,code);self.assertNotIn(SECRET,output.getvalue())
        self.assertLessEqual((self.root/'private-test-results/console.log').stat().st_size,8*1024*1024)
        self.assertEqual('passed',self.stage(outcome='success')['result'])

    def test_oversized_heartbeat_is_unavailable_not_a_partial_raw_copy(self):
        self.put('test-results/device/progress-interactions-main.jsonl',SECRET*2000)
        report=self.stage()
        self.assertEqual('unavailable',report['heartbeat']['progress-interactions-main.jsonl'])
        self.assertEqual(0,report['raw_marker_sources_read'])

    @staticmethod
    def typed_failure():
        return dict(status='observed', phase='FLOW', kind='ASSERTION', observed_ms=14,
                    scope='FIRST_INSTALL', stage='BACK_RETURN', seq=4, stage_ms=13,
                    call=13, category=0, page=-1, attempt=-1)

    @staticmethod
    def typed_rows():
        header = dict(schema=2, scenario='first-install', phase='reboot', clock='elapsedRealtime_ms',
                      started_ms=1, interval_ms=10000, window_ms=840000,
                      meaning='ack_only_proves_ordinary_main_callback_ran;missing_is_ambiguous')
        row = dict(event='exit_stop', at_ms=12, elapsed_ms=11, scope='FIRST_INSTALL', stage='FINALLY_RETURN',
                   seq=8, stage_ms=15, call=13, category=0, page=-1, attempt=-1, request=1, ack=1, ack_ms=13,
                   previous_post='accepted', post_token=1, post_call_ms=2, post_return_ms=3,
                   failure={'status': 'unavailable'})
        return header, row

    def project_rows(self, header, rows):
        name = 'progress-first-install-reboot.jsonl'
        self.put('test-results/device/' + name, '\n'.join(map(json.dumps, [header] + rows)) + '\n')
        report = self.stage(scenario='first-install')
        body = (self.root/'public-test-results/heartbeat.jsonl').read_text()
        shutil.rmtree(self.root/'public-test-results')
        return report, body, name

    def test_first_failure_survives_later_finally_breadcrumb_and_sampling_order(self):
        header, row = self.typed_rows()
        row['failure'] = self.typed_failure()
        report, body, name = self.project_rows(header, [row, dict(row, seq=9, stage='TEARDOWN_RETURN')])
        self.assertEqual(2, report['schema'])
        self.assertEqual('available', report['heartbeat'][name])
        self.assertEqual(self.typed_failure(), report['failure_evidence'][name])
        self.assertEqual('BACK_RETURN', report['failure_evidence'][name]['stage'])
        # Concurrent publication may be later than the row's supplied timestamp.
        self.assertGreater(row['failure']['observed_ms'], row['at_ms'])
        self.assertEqual(row, json.loads(body.splitlines()[1])['record'])

    def test_lost_or_changed_failure_latch_refuses_the_entire_heartbeat(self):
        header, row = self.typed_rows(); row['failure'] = self.typed_failure()
        for later in ({'status': 'unavailable'}, dict(row['failure'], phase='CLEANUP'),
                      dict(row['failure'], kind='IO'), dict(row['failure'], observed_ms=15)):
            with self.subTest(later=later):
                report, body, name = self.project_rows(header, [row, dict(row, failure=later)])
                self.assertEqual('unavailable', report['heartbeat'][name])
                self.assertEqual({'status': 'unavailable'}, report['failure_evidence'][name])
                self.assertEqual('', body)
                self.assertEqual('failed', report['result'])

    def test_missing_legacy_and_unobserved_failure_are_explicitly_unavailable(self):
        header, row = self.typed_rows()
        for schema, omit, available in ((1, True, True), (2, False, True), (2, True, False)):
            h = dict(header, schema=schema); r = dict(row)
            if omit: del r['failure']
            report, _, name = self.project_rows(h, [r])
            self.assertEqual('available' if available else 'unavailable', report['heartbeat'][name])
            self.assertEqual({'status': 'unavailable'}, report['failure_evidence'][name])
            self.assertEqual('failed', report['result'])

    def test_failure_unknown_keys_enums_and_hostile_text_are_never_forwarded(self):
        header, row = self.typed_rows(); good = self.typed_failure()
        variants = [None, [], dict(good, message=SECRET), dict(good, filename=SECRET),
                    dict(good, thread=SECRET), dict(good, intent=SECRET),
                    {'status': 'unavailable', 'message': SECRET}]
        for key in ('status', 'phase', 'kind', 'scope', 'stage'):
            variants += [dict(good, **{key: SECRET}), dict(good, **{key: []})]
        for value in variants:
            with self.subTest(value=value):
                report, body, name = self.project_rows(header, [dict(row, failure=value)])
                self.assertEqual('unavailable', report['heartbeat'][name])
                self.assertEqual({'status': 'unavailable'}, report['failure_evidence'][name])
                self.assertEqual('', body)

    def test_failure_numeric_bounds_and_all_fixed_kind_phase_values(self):
        header, row = self.typed_rows(); good = self.typed_failure()
        for key, low, high in [('observed_ms', 0, 2**63-1), ('seq', 0, 2**63-1),
                ('stage_ms', 0, 2**63-1), ('call', 0, 256), ('category', 0, 7),
                ('page', -1, 31), ('attempt', -1, 31)]:
            for value in (True, 1.0, None, SECRET, low-1, high+1, float('nan'), float('inf')):
                with self.subTest(key=key, value=value):
                    report, body, name = self.project_rows(header, [dict(row, failure=dict(good, **{key: value}))])
                    self.assertEqual('unavailable', report['heartbeat'][name]); self.assertEqual('', body)
            for value in (low, high):
                report, _, name = self.project_rows(header, [dict(row, failure=dict(good, **{key: value}))])
                self.assertEqual('available', report['heartbeat'][name])
        for phase in evidence.FAILURE_PHASES:
            for kind in evidence.FAILURE_KINDS:
                failure = dict(good, phase=phase, kind=kind)
                report, _, name = self.project_rows(header, [dict(row, failure=failure)])
                self.assertEqual(failure, report['failure_evidence'][name])

    @staticmethod
    def typed_reboot():
        row = dict(stage='WAIT_BOOT', cycle=2, elapsed_ms=3100, cycle_elapsed_ms=3000,
                   poll_attempt=3, poll_status='TIMEOUT', boot_id_changed=None, completion='UNAVAILABLE')
        return dict(schema=1, status='available', **row,
                    failure=dict(status='observed', code='BOOT_DEADLINE', **row))

    def project_reboot(self, diagnostic):
        self.put('test-results/emulator-reboots.json', json.dumps(dict(diagnostic=diagnostic,
            passed=False, error=SECRET, failedCycle={SECRET: SECRET}, cycles=[dict(seconds=1, beforeBootId=SECRET)])))
        report = self.stage(scenario='reboot')
        shutil.rmtree(self.root/'public-test-results')
        return report

    def test_reboot_missing_legacy_or_unavailable_retains_original_failed_outcome(self):
        for diagnostic in (None, {'schema': 1, 'status': 'unavailable'}):
            report = self.project_reboot(diagnostic)
            self.assertEqual({'schema': 1, 'status': 'unavailable'}, report['reboot_evidence'])
            self.assertEqual(1, report['metrics']['reboot.cycles_completed'])
            self.assertEqual('failed', report['result'])

    def test_reboot_projection_retains_only_exact_bounded_fields(self):
        good = self.typed_reboot()
        self.assertEqual(good, self.project_reboot(good)['reboot_evidence'])
        for key, cap in [('cycle', 5), ('poll_attempt', 3601), ('elapsed_ms', 86400000),
                         ('cycle_elapsed_ms', 86400000)]:
            for value in (True, 1.0, SECRET, -1, cap+1, float('nan'), float('inf')):
                for nested in (False, True):
                    bad = json.loads(json.dumps(good)); target = bad['failure'] if nested else bad
                    target[key] = value
                    with self.subTest(key=key, value=value, nested=nested):
                        self.assertEqual({'schema': 1, 'status': 'unavailable'},
                                         self.project_reboot(bad)['reboot_evidence'])
            for value in (0, cap):
                self.assertEqual(dict(good, **{key: value}), self.project_reboot(dict(good, **{key: value}))['reboot_evidence'])
        for key, value in [('schema', True), ('schema', 1.0), ('schema', 2), ('stage', SECRET),
                ('stage', []), ('poll_status', SECRET), ('completion', SECRET), ('boot_id_changed', 1),
                ('boot_id_changed', SECRET), ('path', SECRET), ('error', SECRET), ('status', SECRET),
                ('failure', dict(good['failure'], code=SECRET)), ('failure', dict(good['failure'], message=SECRET)),
                ('failure', {'status': 'unavailable', 'error': SECRET}), ('failure', [])]:
            with self.subTest(key=key, value=value):
                self.assertEqual({'schema': 1, 'status': 'unavailable'},
                                 self.project_reboot(dict(good, **{key: value}))['reboot_evidence'])
        for bad in ({'schema': True, 'status': 'unavailable'}, {'schema': 1.0, 'status': 'unavailable'},
                    {'schema': 1, 'status': 'unavailable', 'error': SECRET}):
            with self.assertRaises(ValueError): evidence.reboot_record(bad)

    def test_reboot_enum_values_and_unavailable_partial_poll_fields(self):
        good = self.typed_reboot()
        for key, values in [('stage', evidence.REBOOT_STAGES), ('poll_status', evidence.REBOOT_POLLS),
                ('completion', evidence.REBOOT_COMPLETIONS), ('boot_id_changed', (None, False, True)),
                ('cycle_elapsed_ms', (None,))]:
            for value in values:
                expected = dict(good, **{key: value})
                self.assertEqual(expected, self.project_reboot(expected)['reboot_evidence'])
        for code in evidence.REBOOT_CODES:
            expected = dict(good, failure=dict(good['failure'], code=code))
            self.assertEqual(expected, self.project_reboot(expected)['reboot_evidence'])

    def test_picker_failure_boundaries_remain_distinct_after_finally(self):
        header, row = self.typed_rows()
        for stage in ('PICKER_DISMISS_ENTER', 'PICKER_DISMISS_RETURN',
                      'PICKER_CURRENT_IME_ENTER', 'PICKER_CURRENT_IME_RETURN'):
            with self.subTest(stage=stage):
                failure = dict(self.typed_failure(), stage=stage)
                report, body, name = self.project_rows(header, [dict(row, failure=failure)])
                self.assertEqual('available', report['heartbeat'][name])
                self.assertEqual(failure, report['failure_evidence'][name])
                self.assertEqual('FINALLY_RETURN', json.loads(body.splitlines()[1])['record']['stage'])


if __name__=='__main__':unittest.main()
