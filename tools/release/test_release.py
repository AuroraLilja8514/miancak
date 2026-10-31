#!/usr/bin/env python3
"""Offline guards plus an official-tools, disposable-key APK signing fixture."""
import argparse
import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import textwrap
import unittest
from unittest import mock
import zipfile
import release_ops as ops
import sign_apk

ROOT = Path(__file__).resolve().parents[2]

class Guards(unittest.TestCase):
    def run_record(self):
        return {"status": "completed", "conclusion": "success", "event": "push",
                "head_branch": "master", "path": ".github/workflows/ci.yml",
                "head_repository": {"id": ops.REPO_ID}, "head_sha": "a"*40}

    def jobs(self):
        return [{"name": name, "conclusion": "success"} for name in ops.GATES]

    def test_exact_master_source_and_all_18_gates(self):
        ops.validate_run(self.run_record(), "a"*40, self.jobs())
        self.assertEqual(len(ops.GATES), 18)

    def test_ref_source_and_result_rejections(self):
        for key, value in [("event", "pull_request"), ("head_branch", "development"),
                           ("path", ".github/workflows/other.yml"), ("conclusion", "failure"),
                           ("status", "in_progress"), ("head_sha", "b"*40),
                           ("head_repository", {"id": 123})]:
            with self.subTest(key=key), self.assertRaises(ops.ReleaseError):
                ops.validate_run({**self.run_record(), key:value}, "a"*40, self.jobs())

    def test_missing_skipped_and_failed_gates_rejected(self):
        for jobs in [self.jobs()[1:], [{"name":n,"conclusion":"skipped"} for n in ops.GATES],
                     [{"name":n,"conclusion":"failure"} for n in ops.GATES]]:
            with self.assertRaises(ops.ReleaseError):
                ops.validate_run(self.run_record(),"a"*40,jobs)

    def test_only_latest_result_per_job_can_satisfy_a_gate(self):
        history=self.jobs()+[{"id":1,"name":"generated","conclusion":"success"},
                             {"id":2,"name":"generated","conclusion":"failure"}]
        with self.assertRaises(ops.ReleaseError):
            ops.validate_run(self.run_record(),"a"*40,history)
        history.insert(0,{"id":3,"name":"generated","conclusion":"success"})
        ops.validate_run(self.run_record(),"a"*40,history)

    def test_unrelated_host_or_repository_cannot_receive_token(self):
        with mock.patch.dict(os.environ, {"GH_TOKEN":"disposable-token"}):
            for url in ["http://api.github.com/repos/"+ops.REPO+"/releases",
                        "https://example.com/repos/"+ops.REPO+"/releases",
                        "https://api.github.com/repos/example/other/releases"]:
                with self.subTest(url=url), self.assertRaises(ops.ReleaseError):
                    ops.request("GET",url)

    def test_download_redirect_drops_authorization(self):
        seen=[]
        class Opener:
            def open(self, request, timeout):
                seen.append(request)
                if len(seen)==1:
                    import urllib.error
                    raise urllib.error.HTTPError(request.full_url,302,"redirect",
                        {"Location":"https://example.blob.core.windows.net/signed?token=fixture"},None)
                return io.BytesIO(b"fixture")
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.dict(os.environ, {"GH_TOKEN":"disposable-token"}), \
                 mock.patch("urllib.request.build_opener",return_value=Opener()):
                ops.download_api("actions/artifacts/1/zip",Path(temporary)/"file",ops.digest(b"fixture"))
        self.assertIn("Authorization",seen[0].headers)
        self.assertNotIn("Authorization",seen[1].headers)

    def test_unrecognized_download_redirect_is_rejected(self):
        class Opener:
            def open(self, request, timeout):
                import urllib.error
                raise urllib.error.HTTPError(request.full_url,302,"redirect",
                    {"Location":"https://example.com/untrusted"},None)
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.dict(os.environ, {"GH_TOKEN":"disposable-token"}), \
                 mock.patch("urllib.request.build_opener",return_value=Opener()):
                with self.assertRaises(ops.ReleaseError):
                    ops.download_api("actions/artifacts/1/zip",Path(temporary)/"file")

    def test_archive_traversal_and_symlinks_rejected(self):
        for name in ["../key.p12","/absolute.apk","folder\\escape.apk","C:drive.apk"]:
            with tempfile.TemporaryDirectory() as temporary:
                path=Path(temporary)/"archive.zip"
                with zipfile.ZipFile(path,"w") as archive:archive.writestr(name,b"data")
                with self.assertRaises(ops.ReleaseError):ops.checked_archive(path)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"archive.zip"
            item=zipfile.ZipInfo("symlink.apk");item.external_attr=0o120777<<16
            with zipfile.ZipFile(path,"w") as archive:archive.writestr(item,b"outside")
            with self.assertRaises(ops.ReleaseError):ops.checked_archive(path)

    def test_release_identity_scope(self):
        good={"application_id":ops.PACKAGE,"debuggable":False,"min_sdk":21,
              "abis":ops.ABIS,"version":"0.3.2","version_code":5}
        ops.validate_apk(good)
        for key,value in [("application_id",ops.PACKAGE+".debug"),("debuggable",True),
                          ("min_sdk",23),("abis",["x86_64"]),("version","../../other"),("version_code",0)]:
            with self.subTest(key=key),self.assertRaises(ops.ReleaseError):
                ops.validate_apk({**good,key:value})

    def test_workflow_and_generated_inline_sources(self):
        import generate_workflow
        expected=generate_workflow.render()
        self.assertTrue(expected==(ROOT/".github/workflows/release.yml").read_text(),
                        "Generated release workflow differs from its reviewed sources")
        formal=expected.split("  sign:\n",1)[1].split("  acceptance:\n",1)[0]
        self.assertNotIn("actions/checkout",formal)
        template=(ROOT/"tools/release/release-workflow.yml.in").read_text()
        self.assertNotIn("gradlew",template.split("  sign:\n",1)[1].split("  acceptance:\n",1)[0])
        self.assertIn("environment: release-signing",formal)
        self.assertIn("contents: write",expected.split("  publish:\n",1)[1])
        self.assertNotIn("contents: write",formal)
        self.assertIn("needs: [prepare, sign, acceptance]",expected)
        self.assertNotIn("pull_request_target",expected)

    def test_registered_certificate_and_release_notes(self):
        pem=(ROOT/"docs/signing/miancak-release-cert.pem").read_text()
        der=base64.b64decode(re.sub(r"\s+","",pem.split("-----BEGIN CERTIFICATE-----")[1]
                                  .split("-----END CERTIFICATE-----")[0]),validate=True)
        self.assertEqual(hashlib.sha256(der).hexdigest(),ops.CERTIFICATE)
        version=re.search(r'versionName = "([^"]+)"',(ROOT/"build.gradle.kts").read_text())[1]
        notes=ROOT/"docs/releases"/(version+".md")
        self.assertTrue(notes.is_file() and notes.stat().st_size>0,"Current version needs Release notes")

    def test_publication_is_draft_until_all_three_hashes_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);apk=folder/"miancak-0.3.2.apk";apk.write_bytes(b"synthetic-public-APK")
            record={"repository":ops.REPO,"certificate_sha256":ops.CERTIFICATE,"version":"0.3.2",
                    "version_code":5,"source_sha":"a"*40,"ci_run_id":10,"release_run_id":11,
                    "apk_sha256":ops.file_sha(apk),"release_notes":"Fixture"}
            ops.write_json(folder/"release-provenance.json",record)
            (folder/"SHA256SUMS.txt").write_text("\n".join(
                ops.file_sha(folder/name)+"  "+name for name in [apk.name,"release-provenance.json"])+"\n")
            calls=[];uploaded=[]
            def api(path,**kwargs):
                calls.append((path,kwargs))
                if path=="git/ref/heads/master":return {"object":{"sha":"a"*40}}
                if path.startswith("git/ref/tags/") or path.startswith("releases/tags/"):return None
                if path=="releases":
                    self.assertTrue(kwargs["body"]["draft"])
                    return {"id":7,"assets":[],"target_commitish":"a"*40,"draft":True}
                if path=="releases/7":
                    self.assertEqual(set(uploaded),{apk.name,"SHA256SUMS.txt","release-provenance.json"})
                    self.assertFalse(kwargs["body"]["draft"])
                    return {"draft":False,"tag_name":"v0.3.2","html_url":"https://github.com/"+ops.REPO+"/releases/tag/v0.3.2"}
                self.fail("Unexpected publication API operation")
            def upload(release,path):
                uploaded.append(path.name)
                return {"digest":"sha256:"+ops.file_sha(path)}
            info={"application_id":ops.PACKAGE,"debuggable":False,"min_sdk":21,
                  "abis":ops.ABIS,"version":"0.3.2","version_code":5}
            with mock.patch.object(ops,"api",side_effect=api), \
                 mock.patch.object(ops,"trusted_run",return_value={"head_sha":"a"*40}), \
                 mock.patch.object(ops,"inspect_apk",return_value=info), \
                 mock.patch.object(ops,"verify_signed"), \
                 mock.patch.object(ops,"upload_asset",side_effect=upload), \
                 contextlib.redirect_stdout(io.StringIO()):
                ops.publish(folder)
            self.assertEqual(calls[-1][0],"releases/7")
            with mock.patch.object(ops,"api",side_effect=api), \
                 mock.patch.object(ops,"trusted_run",return_value={"head_sha":"a"*40}), \
                 mock.patch.object(ops,"inspect_apk",return_value=info), \
                 mock.patch.object(ops,"verify_signed"), \
                 mock.patch.object(ops,"upload_asset",return_value={"digest":"sha256:"+"0"*64}):
                calls.clear();uploaded.clear()
                with self.assertRaises(ops.ReleaseError):ops.publish(folder)
                self.assertFalse(any(path=="releases/7" for path,kwargs in calls))

def integration():
    root,hashes=ops.sdk_tools()
    env=ops.java_environment()
    java=Path(env["JAVA_HOME"])/"bin"
    with tempfile.TemporaryDirectory(prefix="miancak-release-fixture-") as temporary:
        temp=Path(temporary)
        manifest=temp/"AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="org.miancak.keyboard" android:versionCode="5" android:versionName="0.3.2">'
            '<uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35"/>'
            '<application android:debuggable="false" android:label="MIANCAK fixture"/></manifest>')
        unaligned=temp/"unaligned.apk"
        ops.command([root/"aapt2","link","-o",unaligned,"--manifest",manifest,
                     "-I",Path(os.environ["ANDROID_HOME"])/"platforms/android-35/android.jar"])
        with zipfile.ZipFile(unaligned,"a") as archive:
            for abi in ops.ABIS:
                archive.writestr("lib/"+abi+"/libmiancak_rime.so",b"fixture-only-not-an-ELF")
        prepared=temp/"prepared";prepared.mkdir()
        ops.command([root/"zipalign","-P","16","-f","4",unaligned,prepared/"unsigned.apk"])
        password="MIANCAK_DISPOSABLE_RELEASE_FIXTURE_4Sq#9b1"
        env["MIANCAK_FIXTURE_PASSWORD"]=password
        key=temp/"fixture.p12"
        ops.command([java/"keytool","-genkeypair","-alias","unknown-internal-fixture-alias",
                     "-keyalg","RSA","-keysize","3072","-sigalg","SHA256withRSA",
                     "-validity","10950","-dname","CN=MIANCAK release fixture",
                     "-storetype","PKCS12","-keystore",key,
                     "-storepass:env","MIANCAK_FIXTURE_PASSWORD",
                     "-keypass:env","MIANCAK_FIXTURE_PASSWORD","-noprompt"],env=env)
        der=ops.command([java/"keytool","-exportcert","-alias","unknown-internal-fixture-alias",
                         "-keystore",key,"-storetype","PKCS12",
                         "-storepass:env","MIANCAK_FIXTURE_PASSWORD"],env=env).stdout
        fingerprint=hashlib.sha256(der).hexdigest()
        workflow=(ROOT/".github/workflows/signing-credentials.yml").read_text()
        code=textwrap.dedent(re.search(
            r"^          // MIANCAK_CREDENTIAL_PROBE_BEGIN\n(.*?)"
            r"^          // MIANCAK_CREDENTIAL_PROBE_END$",workflow,re.M|re.S)[1])
        source=temp/"MiancakCredentialProbe.java";source.write_text(code)
        ops.command([java/"javac","-encoding","UTF-8","-d",temp,source],env=env)
        data={**ops.inspect_apk(prepared/"unsigned.apk"),"repository":ops.REPO,
              "certificate_sha256":fingerprint,"unsigned_apk_sha256":ops.file_sha(prepared/"unsigned.apk"),
              "tool_sha256":hashes,"build_tools_version":ops.BUILD_TOOLS,
              "format_version":1,"source_sha":"a"*40,"ci_run_id":1,"ci_artifact_id":2,
              "ci_artifact_digest":"sha256:"+"b"*64,"application_tree_sha256":"c"*64,
              "previous_release":None}
        ops.write_json(prepared/"prepared.json",data)
        (prepared/"release-notes.md").write_text("Disposable release fixture; never published.")
        encoded=base64.b64encode(key.read_bytes()).decode()
        with mock.patch.dict(os.environ,{
            "MIANCAK_RELEASE_KEYSTORE_B64":encoded,"MIANCAK_RELEASE_STORE_PASSWORD":password,
            "GITHUB_RUN_ID":"3"}):
            capture=io.StringIO()
            with contextlib.redirect_stdout(capture):
                record=sign_apk.sign(prepared,temp/"signed",temp,expected=fingerprint)
            assert password not in capture.getvalue() and encoded not in capture.getvalue()
            assert record["certificate_sha256"]==fingerprint and all(record["v"+str(x)] for x in [1,2,3])
            assert len(list((temp/"signed").iterdir()))==3
            print("PASS: official apksigner v1/v2/v3, one-key auto-selection, SHA pin and cleaned private staging")
            with mock.patch.dict(os.environ,{"MIANCAK_RELEASE_STORE_PASSWORD":"wrong-fixture-password"}):
                try:sign_apk.sign(prepared,temp/"wrong-password",temp,expected=fingerprint)
                except ops.ReleaseError as failure:assert str(failure)=="SIGNING_KEY_OR_CERTIFICATE"
                else:raise AssertionError("Wrong password was accepted")
            print("PASS: wrong password refuses before APK signing")
            data["certificate_sha256"]=ops.CERTIFICATE
            ops.write_json(prepared/"prepared.json",data)
            try:sign_apk.sign(prepared,temp/"wrong-certificate",temp)
            except ops.ReleaseError as failure:assert str(failure)=="SIGNING_KEY_OR_CERTIFICATE"
            else:raise AssertionError("Unregistered certificate was accepted")
            print("PASS: unregistered certificate refuses before APK signing")
            data["certificate_sha256"]=fingerprint
            data["unsigned_apk_sha256"]="0"*64
            ops.write_json(prepared/"prepared.json",data)
            try:sign_apk.sign(prepared,temp/"wrong-apk",temp,expected=fingerprint)
            except ops.ReleaseError as failure:assert str(failure)=="SIGNING_INPUT_DIGEST"
            else:raise AssertionError("Changed APK was accepted")
            print("PASS: modified APK refuses before credential use")
        print(json.dumps({"build_tools":ops.BUILD_TOOLS,"tool_sha256":hashes,"formal_key_used":False}))

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--integration",action="store_true")
    args=parser.parse_args()
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Guards))
    if not result.wasSuccessful():raise SystemExit(1)
    if args.integration:integration()
