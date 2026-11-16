#!/usr/bin/env python3
"""PR-only rehearsal: sign test copies with a disposable key, never publish."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sys
import tempfile
import textwrap
import time
import release_ops as ops
import sign_apk

ROOT=Path(__file__).resolve().parents[2]

def prepare(output):
    head=os.environ["PR_HEAD_SHA"]
    merge=os.environ["GITHUB_SHA"]
    ops.require(re.fullmatch(r"[0-9a-f]{40}",head) and re.fullmatch(r"[0-9a-f]{40}",merge),
                "REHEARSAL_SOURCE")
    deadline=time.monotonic()+1500
    chosen=None
    while time.monotonic()<deadline:
        runs=ops.api("actions/runs?event=pull_request&head_sha="+head+"&per_page=20")["workflow_runs"]
        runs=[run for run in runs if run["head_sha"]==head and run["path"]==".github/workflows/ci.yml"]
        for run in runs:
            artifacts=ops.api("actions/runs/"+str(run["id"])+"/artifacts?per_page=100")["artifacts"]
            by_name={a["name"]:a for a in artifacts if not a["expired"]}
            unsigned=by_name.get("miancak-unsigned-release-"+merge)
            debug=by_name.get("miancak-debug-"+merge)
            if unsigned and debug:
                chosen=(run,unsigned,debug)
                break
        if chosen:break
        ops.require(not runs or any(run["status"]!="completed" for run in runs),
                    "REHEARSAL_BUILD_ARTIFACTS_MISSING")
        time.sleep(10)
    ops.require(chosen,"REHEARSAL_BUILD_TIMEOUT")
    run,unsigned,debug=chosen
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    root,hashes=ops.sdk_tools()
    env=ops.java_environment()
    java=Path(env["JAVA_HOME"])/"bin"
    with tempfile.TemporaryDirectory(prefix="miancak-disposable-rehearsal-") as temporary:
        temp=Path(temporary)
        ops.fetch_artifact(unsigned["id"],run["id"],temp/"unsigned")
        apks=list((temp/"unsigned").glob("*.apk"));ops.require(len(apks)==1,"REHEARSAL_UNSIGNED_COUNT")
        prepared=temp/"prepared";prepared.mkdir()
        ops.command([root/"zipalign","-P","16","-f","4",apks[0],prepared/"unsigned.apk"])
        info=ops.inspect_apk(prepared/"unsigned.apk");ops.validate_apk(info)
        ops.fetch_artifact(debug["id"],run["id"],temp/"debug")
        debug_apks={ops.inspect_apk(p)["application_id"]:p for p in (temp/"debug").glob("*.apk")}
        ops.require(set(debug_apks)=={ops.PACKAGE+".debug",ops.PACKAGE+".debug.test"},"REHEARSAL_DRIVER")
        password=secrets.token_urlsafe(30)
        env["MIANCAK_FIXTURE_PASSWORD"]=password
        key=temp/"disposable.p12"
        ops.command([java/"keytool","-genkeypair","-alias","disposable-rehearsal","-keyalg","RSA",
                     "-keysize","3072","-sigalg","SHA256withRSA","-validity","30",
                     "-dname","CN=MIANCAK DISPOSABLE APK REHEARSAL",
                     "-keystore",key,"-storetype","PKCS12",
                     "-storepass:env","MIANCAK_FIXTURE_PASSWORD",
                     "-keypass:env","MIANCAK_FIXTURE_PASSWORD","-noprompt"],env=env)
        der=ops.command([java/"keytool","-exportcert","-alias","disposable-rehearsal",
                         "-keystore",key,"-storetype","PKCS12",
                         "-storepass:env","MIANCAK_FIXTURE_PASSWORD"],env=env).stdout
        fingerprint=hashlib.sha256(der).hexdigest()
        workflow=(ROOT/".github/workflows/signing-credentials.yml").read_text()
        source=textwrap.dedent(re.search(
            r"^          // MIANCAK_CREDENTIAL_PROBE_BEGIN\n(.*?)"
            r"^          // MIANCAK_CREDENTIAL_PROBE_END$",workflow,re.M|re.S)[1])
        (temp/"MiancakCredentialProbe.java").write_text(source)
        ops.command([java/"javac","-encoding","UTF-8","-d",temp,temp/"MiancakCredentialProbe.java"],env=env)
        manifest={**info,"format_version":1,"repository":ops.REPO,"source_sha":merge,
                  "ci_run_id":run["id"],"ci_artifact_id":unsigned["id"],
                  "ci_artifact_digest":unsigned["digest"],"application_tree_sha256":ops.application_fingerprint(),
                  "build_tools_version":ops.BUILD_TOOLS,"tool_sha256":hashes,
                  "unsigned_apk_sha256":ops.file_sha(prepared/"unsigned.apk"),
                  "certificate_sha256":fingerprint,"previous_release":None,"disposable_key":True}
        ops.write_json(prepared/"prepared.json",manifest)
        (prepared/"release-notes.md").write_text("DISPOSABLE REHEARSAL ONLY. Not a formal release.")
        os.environ["MIANCAK_RELEASE_KEYSTORE_B64"]=base64.b64encode(key.read_bytes()).decode()
        os.environ["MIANCAK_RELEASE_STORE_PASSWORD"]=password
        try:
            sign_apk.sign(prepared,temp/"signed",temp,expected=fingerprint)
        finally:
            os.environ.pop("MIANCAK_RELEASE_KEYSTORE_B64",None)
            os.environ.pop("MIANCAK_RELEASE_STORE_PASSWORD",None)
        for file in (temp/"signed").iterdir():shutil.copyfile(file,output/file.name)
        shutil.copyfile(prepared/"prepared.json",output/"prepared.json")
        shutil.copyfile(debug_apks[ops.PACKAGE+".debug"],output/"app-debug.apk")
        shutil.copyfile(debug_apks[ops.PACKAGE+".debug.test"],output/"app-debug-androidTest.apk")
        ops.require(not any(p.suffix in (".p12",".pfx",".jks") for p in output.iterdir()),
                    "REHEARSAL_PRIVATE_OUTPUT")
    print("PASS: DISPOSABLE_ACTUAL_APK_REHEARSAL_PREPARED_NO_FORMAL_KEY",flush=True)

def device(folder,api):
    folder=Path(folder)
    manifest=json.loads((folder/"prepared.json").read_text())
    fingerprint=manifest["certificate_sha256"]
    ops.require(manifest["disposable_key"] is True and fingerprint!=ops.CERTIFICATE
                and re.fullmatch(r"[0-9a-f]{64}",fingerprint),"REHEARSAL_CERTIFICATE")
    actual_verify=ops.verify_signed
    ops.verify_signed=lambda path:actual_verify(path,expected=fingerprint)
    ops.CERTIFICATE=fingerprint
    import device_smoke
    sys.argv=["device_smoke.py","--prepared",str(folder),"--signed",str(folder),"--api",str(api)]
    device_smoke.main()
    report=Path("test-results/release/acceptance.json")
    result=json.loads(report.read_text())
    result.update({"disposable_key":True,"scope":"test copy; not formal signing acceptance"})
    ops.write_json(report,result)

if __name__=="__main__":
    sys.path.insert(0,str(ROOT/"scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    parser=argparse.ArgumentParser()
    parser.add_argument("operation",choices=["prepare","device"])
    parser.add_argument("--folder",default="rehearsal")
    parser.add_argument("--api",type=int)
    args=parser.parse_args()
    try:
        if args.operation=="prepare":prepare(args.folder)
        else:device(args.folder,args.api)
    except ops.ReleaseError as failure:
        print("FAIL: "+str(failure),file=sys.stderr)
        raise SystemExit(1)
