#!/usr/bin/env python3
"""Only the reviewed, inline copy runs in the credential-bearing step."""
from __future__ import annotations
import base64
import json
import os
from pathlib import Path
import re
import tempfile
import sys
import release_ops as ops

def sign(prepared, output, probe_classes, expected=ops.CERTIFICATE):
    prepared, output = Path(prepared), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((prepared / "prepared.json").read_text())
    ops.require(manifest["repository"] == ops.REPO and manifest["certificate_sha256"] == expected,
                "SIGNING_MANIFEST_IDENTITY")
    unsigned = prepared / "unsigned.apk"
    ops.require(ops.file_sha(unsigned) == manifest["unsigned_apk_sha256"], "SIGNING_INPUT_DIGEST")
    actual = ops.inspect_apk(unsigned)
    ops.validate_apk(actual)
    ops.require(all(actual[key] == manifest[key] for key in actual), "SIGNING_PACKAGE")
    root, hashes = ops.sdk_tools()
    ops.require(hashes == manifest["tool_sha256"] and manifest["build_tools_version"] == ops.BUILD_TOOLS,
                "SIGNING_TOOLS")
    env = ops.java_environment()
    env["MIANCAK_EXPECTED_CERT_SHA256"] = expected
    encoded = env.get("MIANCAK_RELEASE_KEYSTORE_B64", "")
    password = env.get("MIANCAK_RELEASE_STORE_PASSWORD", "")
    ops.require(encoded and password and len(encoded) <= 48 * 1024, "SIGNING_CREDENTIALS_MISSING")
    # The checker requires one private-key entry and verifies the exact certificate
    # before any APK is signed. Its output is captured, never relayed with secrets.
    probe = ops.command([Path(env["JAVA_HOME"]) / "bin/java", "-XX:+DisableAttachMechanism",
                         "-XX:-HeapDumpOnOutOfMemoryError", "-XX:ErrorFile=/dev/null",
                         "-cp", probe_classes, "MiancakCredentialProbe"], env=env, allow_failure=True)
    ops.require(probe.returncode == 0
                and probe.stdout.strip() == b"PASS: PKCS12_PRIVATE_KEY_CERTIFICATE_PROOF",
                "SIGNING_KEY_OR_CERTIFICATE")
    private_dir = None
    try:
        encrypted = bytearray(base64.b64decode(re.sub(r"[ \t\r\n]", "", encoded), validate=True))
        with tempfile.TemporaryDirectory(prefix="miancak-signing-secret-",
                                         dir=os.environ.get("RUNNER_TEMP")) as temporary:
            private_dir = Path(temporary)
            os.chmod(private_dir, 0o700)
            key_file = private_dir / "key.p12"
            descriptor = os.open(key_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(encrypted)
            encrypted[:] = b"\0" * len(encrypted)
            destination = output / ("miancak-" + manifest["version"] + ".apk")
            ops.require(not destination.exists(), "SIGNING_OUTPUT_EXISTS")
            ops.command([root / "apksigner", "sign", "--ks", key_file, "--ks-type", "PKCS12",
                         "--ks-pass", "env:MIANCAK_RELEASE_STORE_PASSWORD",
                         "--key-pass", "env:MIANCAK_RELEASE_STORE_PASSWORD",
                         "--min-sdk-version", "21", "--v1-signing-enabled", "true",
                         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                         "--v4-signing-enabled", "false", "--out", destination, unsigned], env=env)
        ops.require(private_dir is not None and not private_dir.exists(), "SIGNING_CLEANUP")
        schemes = ops.verify_signed(destination, expected)
        ops.command([root / "zipalign", "-c", "-P", "16", "4", destination])
        # Keep the public artifact manifest explicit. Never copy environment data.
        record = {key: manifest[key] for key in (
            "format_version", "repository", "application_id", "version", "version_code",
            "min_sdk", "abis", "source_sha", "ci_run_id", "ci_artifact_id", "ci_artifact_digest",
            "application_tree_sha256", "build_tools_version", "tool_sha256",
            "unsigned_apk_sha256", "previous_release")}
        record.update(schemes)
        record.update({"apk_sha256": ops.file_sha(destination),
                       "release_run_id": int(os.environ["GITHUB_RUN_ID"]),
                       "release_notes": (prepared / "release-notes.md").read_text()})
        ops.write_json(output / "release-provenance.json", record)
        (output / "SHA256SUMS.txt").write_text(
            "\n".join(ops.file_sha(output / name) + "  " + name
                      for name in (destination.name, "release-provenance.json")) + "\n")
        ops.require({p.name for p in output.iterdir()} ==
                    {destination.name, "release-provenance.json", "SHA256SUMS.txt"}, "SIGNING_OUTPUT_FILES")
        print("PASS: RELEASE_APK_SIGNED_VERIFIED_AND_PRIVATE_FILES_REMOVED", flush=True)
        return record
    finally:
        if private_dir is not None and private_dir.exists():
            import shutil
            shutil.rmtree(private_dir)

def main():
    try:
        ops.install_deadline()
        ops.require(len(sys.argv) == 4, "SIGNING_ARGUMENTS")
        sign(sys.argv[1], sys.argv[2], sys.argv[3])
    except ops.ReleaseError as failure:
        print("FAIL: " + str(failure), file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: RELEASE_SIGNING", file=sys.stderr)
        raise SystemExit(1)

if __name__ == "__main__":
    if not (Path(__file__).resolve().parent / "run_with_timeout.py").is_file():
        sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    main()
