#!/usr/bin/env python3
"""Embed reviewed signing helpers; the credential job never checks out app code."""
from pathlib import Path
import argparse
import re
import textwrap

ROOT=Path(__file__).resolve().parents[2]

def file_block(name, content, delimiter):
    return ('umask 077\nmkdir -p "$RUNNER_TEMP/miancak-release-runtime"\n'
            'cat > "$RUNNER_TEMP/miancak-release-runtime/'+name+'" <<\''+delimiter+"'\n"
            +content.rstrip()+"\n"+delimiter)

def render():
    template=(ROOT/"tools/release/release-workflow.yml.in").read_text()
    ops=file_block("release_ops.py",(ROOT/"tools/release/release_ops.py").read_text(),"MIANCAK_OPS_EOF")
    signer=file_block("sign_apk.py",(ROOT/"tools/release/sign_apk.py").read_text(),"MIANCAK_SIGNER_EOF")
    source=(ROOT/".github/workflows/signing-credentials.yml").read_text()
    java=textwrap.dedent(re.search(
        r"^          // MIANCAK_CREDENTIAL_PROBE_BEGIN\n(.*?)"
        r"^          // MIANCAK_CREDENTIAL_PROBE_END$",source,re.M|re.S)[1])
    probe=file_block("MiancakCredentialProbe.java",java,"MIANCAK_JAVA_EOF")
    probe+='\n"$JAVA_HOME_17_X64/bin/javac" -encoding UTF-8 -d "$RUNNER_TEMP/miancak-release-runtime" "$RUNNER_TEMP/miancak-release-runtime/MiancakCredentialProbe.java"'
    for marker,content in [("<<INLINE_OPS>>",ops),("<<INLINE_SIGN_RUNTIME>>",ops+"\n"+signer+"\n"+probe)]:
        replacement=textwrap.indent(content,"          ")
        template=template.replace("          "+marker,replacement)
    return template

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    args=parser.parse_args()
    path=ROOT/".github/workflows/release.yml"
    expected=render()
    if args.check:
        assert path.read_text()==expected,"Regenerate release.yml with tools/release/generate_workflow.py"
    else:path.write_text(expected)
