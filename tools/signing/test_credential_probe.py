#!/usr/bin/env python3
"""Test the exact inline production checker using disposable credentials only."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/signing-credentials.yml"
PIN = "3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b"
KEYSTORE = "MIANCAK_RELEASE_KEYSTORE_B64"
PASSWORD = "MIANCAK_RELEASE_STORE_PASSWORD"
EXPECTED = "MIANCAK_EXPECTED_CERT_SHA256"
PASS = "PASS: PKCS12_PRIVATE_KEY_CERTIFICATE_PROOF"
CASES: list[str] = []


def checked(command: list[str], env: dict[str, str], cwd: Path) -> bytes:
    result = subprocess.run(command, env=env, cwd=cwd, capture_output=True, timeout=90)
    if result.returncode:
        # Fixture commands see synthetic credentials only; keep even their diagnostics private.
        raise RuntimeError("Disposable fixture preparation failed: " + Path(command[0]).name)
    return result.stdout


def check(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)
    CASES.append(label)
    print("PASS:", label, flush=True)


def main() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    matches = re.findall(
        r"^          // MIANCAK_CREDENTIAL_PROBE_BEGIN\n(.*?)"
        r"^          // MIANCAK_CREDENTIAL_PROBE_END$",
        workflow, re.MULTILINE | re.DOTALL,
    )
    check(len(matches) == 1, "one exact embedded production checker")
    java = textwrap.dedent(matches[0])
    cert = ROOT / "docs/signing/miancak-release-cert.pem"
    pem_body = cert.read_text().split("-----BEGIN CERTIFICATE-----", 1)[1].split("-----END CERTIFICATE-----", 1)[0]
    der = base64.b64decode(re.sub(r"\s+", "", pem_body), validate=True)
    registry = json.loads((ROOT / "docs/signing/certificate-info.json").read_text())
    check(hashlib.sha256(der).hexdigest() == registry["certificateSha256"] == PIN,
          "workflow pin equals registered actual public certificate")
    # Only one credential-bearing step; PR/push jobs have no signing environment.
    check(workflow.count("secrets.") == 2 and workflow.count("name: release-signing") == 1
          and "github.ref == 'refs/heads/master'" in workflow
          and "github.event_name == 'workflow_dispatch'" in workflow
          and "needs: self-test" in workflow,
          "formal credentials gated to manual master after self-tests")
    formal = workflow.split("  formal-credential-probe:\n", 1)[1]
    check("uses:" not in formal and "permissions: {}" in formal
          and "checkout" not in formal and "upload-artifact" not in formal
          and "pull_request_target" not in workflow,
          "formal job has no checkout, third-party action, artifact or repository token")

    env = dict(os.environ)
    for name in (KEYSTORE, PASSWORD, EXPECTED, "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(name, None)
    java_home = Path(env["JAVA_HOME"])
    suffix = ".exe" if os.name == "nt" else ""
    java_bin = str(java_home / "bin" / ("java" + suffix))
    javac_bin = str(java_home / "bin" / ("javac" + suffix))
    keytool = str(java_home / "bin" / ("keytool" + suffix))
    password = secrets.token_urlsafe(30)
    env["MIANCAK_FIXTURE_PASSWORD"] = password

    with tempfile.TemporaryDirectory(prefix="miancak-probe-test-") as temporary:
        root = Path(temporary)
        source = root / "MiancakCredentialProbe.java"
        source.write_text(java, encoding="utf-8")
        checked([javac_bin, "-encoding", "UTF-8", "-d", str(root), str(source)], env, root)
        empty_cwd = root / "empty-probe-workdir"
        empty_cwd.mkdir()

        def generate(path: Path, alias: str, bits: int = 3072, expired: bool = False) -> None:
            command = [
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", str(bits),
                "-sigalg", "SHA256withRSA", "-validity", "1" if expired else "10950",
                "-dname", "CN=MIANCAK disposable credential fixture",
                "-keystore", str(path), "-storetype", "PKCS12",
                "-storepass:env", "MIANCAK_FIXTURE_PASSWORD",
                "-keypass:env", "MIANCAK_FIXTURE_PASSWORD", "-noprompt",
            ]
            if expired:
                command.extend(["-startdate", "2020/01/01 00:00:00"])
            checked(command, env, root)

        def export_der(path: Path, alias: str) -> bytes:
            return checked([
                keytool, "-exportcert", "-alias", alias, "-keystore", str(path),
                "-storetype", "PKCS12", "-storepass:env", "MIANCAK_FIXTURE_PASSWORD",
            ], env, root)

        alias = "fixture-with-an-unrelated-alias"
        primary = root / "disposable.p12"
        generate(primary, alias)
        primary_der = export_der(primary, alias)
        primary_pin = hashlib.sha256(primary_der).hexdigest()

        def probe(label: str, value: bytes | str | None = None, pwd: str | None = password,
                  expected: str | None = primary_pin, outcome: str = PASS,
                  args: list[str] | None = None) -> None:
            run_env = dict(env)
            for name in (KEYSTORE, PASSWORD, EXPECTED, "MIANCAK_FIXTURE_PASSWORD"):
                run_env.pop(name, None)
            if value is None:
                value = primary.read_bytes()
            encoded = base64.b64encode(value).decode("ascii") if isinstance(value, bytes) else value
            if encoded != "__MISSING__":
                run_env[KEYSTORE] = encoded
            if pwd is not None:
                run_env[PASSWORD] = pwd
            if expected is not None:
                run_env[EXPECTED] = expected
            result = subprocess.run(
                [java_bin, "-XX:+DisableAttachMechanism", "-XX:-HeapDumpOnOutOfMemoryError",
                 "-XX:ErrorFile=" + os.devnull, "-cp", str(root), "MiancakCredentialProbe", *(args or [])],
                env=run_env, cwd=empty_cwd, capture_output=True, text=True, timeout=45,
            )
            output = (result.stdout + result.stderr).strip()
            check(output == outcome and (result.returncode == 0) == outcome.startswith("PASS:")
                  and not list(empty_cwd.iterdir()) and password not in output
                  and (len(encoded) < 100 or encoded not in output),
                  label)

        probe("valid PKCS12 signs and verifies without an assumed alias")
        wrapped = "\r\n".join(textwrap.wrap(base64.b64encode(primary.read_bytes()).decode(), 64))
        probe("normal Base64 whitespace is accepted", wrapped)
        probe("missing keystore rejected", "__MISSING__", outcome="FAIL: MISSING_KEYSTORE")
        probe("missing password rejected", pwd=None, outcome="FAIL: MISSING_PASSWORD")
        probe("wrong password rejected without exception details", pwd="incorrect-disposable-password",
              outcome="FAIL: PKCS12_LOAD_FAILED")
        probe("malformed Base64 rejected", "this_is_not_base64!", outcome="FAIL: BASE64_INVALID")
        probe("oversize input rejected", "A" * (48 * 1024 + 1), outcome="FAIL: BASE64_INVALID")
        probe("corrupt PKCS12 rejected", b"not a PKCS12 container", outcome="FAIL: PKCS12_LOAD_FAILED")
        probe("different certificate rejected", expected="0" * 64, outcome="FAIL: CERTIFICATE_MISMATCH")
        probe("missing expected fingerprint rejected", expected=None, outcome="FAIL: EXPECTED_FINGERPRINT_INVALID")
        probe("malformed expected fingerprint rejected", expected="not-a-certificate",
              outcome="FAIL: EXPECTED_FINGERPRINT_INVALID")
        probe("external challenge or command arguments rejected", args=["external-message"],
              outcome="FAIL: INPUT_INVALID")

        multi = root / "multiple.p12"
        shutil.copyfile(primary, multi)
        generate(multi, "second-disposable-private-key")
        probe("multiple private keys rejected", multi.read_bytes(), outcome="FAIL: ENTRY_SELECTION_FAILED")

        public_der = root / "public.der"
        public_der.write_bytes(primary_der)
        public_only = root / "certificate-only.p12"
        checked([
            keytool, "-importcert", "-alias", "public-only", "-file", str(public_der),
            "-keystore", str(public_only), "-storetype", "PKCS12",
            "-storepass:env", "MIANCAK_FIXTURE_PASSWORD", "-noprompt",
        ], env, root)
        probe("certificate-only store rejected", public_only.read_bytes(), outcome="FAIL: ENTRY_SELECTION_FAILED")

        weak = root / "rsa2048.p12"
        generate(weak, "weak-fixture", bits=2048)
        probe("unapproved RSA size rejected with matching fingerprint", weak.read_bytes(),
              expected=hashlib.sha256(export_der(weak, "weak-fixture")).hexdigest(),
              outcome="FAIL: CERTIFICATE_POLICY_FAILED")

        expired = root / "expired.p12"
        generate(expired, "expired-fixture", expired=True)
        probe("expired certificate rejected with matching fingerprint", expired.read_bytes(),
              expected=hashlib.sha256(export_der(expired, "expired-fixture")).hexdigest(),
              outcome="FAIL: CERTIFICATE_POLICY_FAILED")

        if os.name == "nt":
            generator_source = ROOT / "fixture-generator/tools/keygen"
            native_parent = root / "native-fixture"
            native_parent.mkdir()
            native_env = dict(env)
            native_env["MIANCAK_GENERATOR_SOURCE"] = str(generator_source)
            native_env["MIANCAK_FIXTURE_PARENT"] = str(native_parent)
            script = root / "native-fixture.ps1"
            script.write_text(
                "$ErrorActionPreference = 'Stop'\n"
                ". (Join-Path $env:MIANCAK_GENERATOR_SOURCE 'Miancak-Keygen.Core.ps1')\n"
                "$secret = ConvertTo-SecureString $env:MIANCAK_FIXTURE_PASSWORD -AsPlainText -Force\n"
                "try { $null = New-MiancakKeyFiles -ParentDirectory $env:MIANCAK_FIXTURE_PARENT -Password $secret }\n"
                "finally { $secret.Dispose() }\n",
                encoding="utf-8-sig",
            )
            checked(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                     "-File", str(script)], native_env, root)
            bundle = native_parent / "MIANCAK-keys"
            native_info = json.loads((bundle / "certificate-info.json").read_text(encoding="utf-8-sig"))
            native_p12 = (bundle / "miancak-release.p12").read_bytes()
            probe("actual native Windows PBES2 bundle accepted by exact checker", native_p12,
                  expected=native_info["certificateSha256"])
            probe("native Windows bundle with wrong password rejected", native_p12, pwd="wrong-native-password",
                  expected=native_info["certificateSha256"], outcome="FAIL: PKCS12_LOAD_FAILED")

    print(json.dumps({"formalCredentialsUsed": False, "platform": os.name,
                      "passed": len(CASES), "cases": CASES}, ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
