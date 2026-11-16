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
                    ('shell','getprop','ro.kernel.qemu'):'1',('shell','getprop','ro.build.version.sdk'):'23',
                    ('shell','pm','list','packages','org.miancak.keyboard'):''}
            return subprocess.CompletedProcess(cmd,0,values[tuple(suffix)].encode(),b'')
        with mock.patch.object(evidence.subprocess,'run',fake), contextlib.redirect_stdout(io.StringIO()) as output:
            evidence.target('interaction')
        self.assertEqual('emulator-5554\n',output.getvalue())

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
        self.assertEqual({'status':'captured','read_bytes':123},report['diagnostics']['windows'])

    def test_marker_ids_are_exactly_the_test_protocol(self):
        core=(ROOT/'androidTest/juloo/keyboard2/ProgressEvidence.java').read_text()
        stages=re.search(r'enum Stage\s*\{(.*?)\n  \}',core,re.S)[1]
        self.assertEqual(set(re.findall(r'\b[A-Z][A-Z_]+\b',stages)),evidence.STAGES)

    def test_n3_typed_profile_outcomes_keep_no_screenshot_geometry_or_reasons(self):
        prefix='20261116T120000Z-23-en-US'
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
                    ('shell','getprop','ro.kernel.qemu'):'1',('shell','getprop','ro.build.version.sdk'):'23',
                    ('shell','pm','list','packages','org.miancak.keyboard'):'package:org.miancak.keyboard.debug'}
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


if __name__=='__main__':unittest.main()
