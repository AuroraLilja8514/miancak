#!/usr/bin/env python3
"""One reviewed preview target. The stable release entry points stay separate."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from zipfile import ZipFile

import release_ops as ops

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ".github/workflows/protected-preview.yml"
OWNER_ID = 315702774
TAG = "v0.3.4-preview.1"
SOURCE = "30b074bf08ba7e668761874b41819781a3f4d44f"
TREE = "4cf327606fbf51372771dc061bd8728eda8f8a70"
APP_INPUT = "8c5b3db965cf5726fa891e6390c9636dc74839cad6abd7b1aa8ad832ed1500a5"
DRAFT_ID = 453133947
STABLE_ID = 446744401
STABLE_TAG = "v0.3.2"
STABLE_APK_NAME = "miancak-0.3.2.apk"
STABLE_APK = "74e884ef6465c96b57daa174ae5f5ce585f2c55417b591c76b8b77f212684bd5"
APK = "miancak-0.3.4.apk"  # Exact basename emitted by the existing signer.
ATTACHMENT = "miancak-0.3.4-preview.1.apk"
INPUTS = {"unsigned.apk", "app-debug.apk", "app-debug-androidTest.apk"}
INPUT_BYTES_LIMIT = 95 * 1024 * 1024
DRIVER_PATCH = Path(__file__).with_name("release-yaml-driver.patch")
SIGNED_FILES = {APK, "release-provenance.json", "SHA256SUMS.txt"}
SDK_HASHES = {
    "aapt2": "d1096e11aba9c974644369ee3c50d239acac3f3428ffa928e5b9c14dfb7a57de",
    "zipalign": "a205d94a45c8522ca1b571dcbe190df145c818f0a29dfb366e4dd78eef4f56a7",
    "apksigner.jar": "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7",
}
DELIVERY = "e145053e7cc5498fc97200649edb14090b75ead3c425fc0ea00bc9639d7b18ae"


def hex_digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)


def approved_actor(name):
    return name.lower() == ops.REPO.split("/")[0].lower()


def owner_dispatch(run):
    return all(run.get(field, {}).get("id") == OWNER_ID
               and approved_actor(run.get(field, {}).get("login", ""))
               for field in ("actor", "triggering_actor"))


def workflow_revision(mode):
    """Pin the tools checkout to the executing workflow."""
    env = os.environ
    revision = env.get("GITHUB_WORKFLOW_SHA", "")
    ops.require(mode == "publish"
                and env.get("PREVIEW_MODE") == mode
                and env.get("GITHUB_REPOSITORY") == ops.REPO
                and env.get("GITHUB_REPOSITORY_ID") == str(ops.REPO_ID)
                and env.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
                and env.get("GITHUB_REF") == "refs/heads/master"
                and env.get("GITHUB_WORKFLOW_REF") == f"{ops.REPO}/{WORKFLOW}@refs/heads/master"
                and env.get("GITHUB_RUN_ATTEMPT") == "1"
                and env.get("GITHUB_ACTOR_ID") == str(OWNER_ID)
                and approved_actor(env.get("GITHUB_ACTOR", ""))
                and approved_actor(env.get("GITHUB_TRIGGERING_ACTOR", ""))
                and re.fullmatch(r"[0-9a-f]{40}", revision)
                and revision == env.get("GITHUB_SHA"), "PREVIEW_WORKFLOW_AUTHORITY")
    checkout = ops.command(["git", "-C", ROOT, "rev-parse", "HEAD"]).stdout.decode().strip()
    ops.require(checkout == revision, "PREVIEW_TRUSTED_REVISION")
    ops.require(getattr(ops, "MIN_SDK", None) == 33 and ops.BUILD_TOOLS == "35.0.0", "PREVIEW_TOOLING_POLICY")
    return revision


def authority(mode):
    revision = workflow_revision(mode)
    ops.require(revision == ops.api("git/ref/heads/master")["object"]["sha"],
                "PREVIEW_TRUSTED_REVISION")
    return revision


def exact_release(published=False):
    tag = ops.api(f"git/ref/tags/{TAG}")
    draft = ops.api(f"releases/{DRAFT_ID}")
    stable = ops.api("releases/latest")
    ops.require(tag.get("ref") == f"refs/tags/{TAG}"
                and tag.get("object") == {"sha": SOURCE, "type": "commit",
                                          "url": f"https://api.github.com/repos/{ops.REPO}/git/commits/{SOURCE}"},
                "PREVIEW_TAG_CHANGED")
    ops.require(draft["id"] == DRAFT_ID and draft["tag_name"] == TAG
                and draft["draft"] is (not published) and draft["prerelease"] is True
                and draft["upload_url"] == f"https://uploads.github.com/repos/{ops.REPO}/releases/{DRAFT_ID}/assets{{?name,label}}",
                "PREVIEW_DRAFT_IDENTITY")
    ops.require(stable["id"] == STABLE_ID and stable["tag_name"] == STABLE_TAG
                and stable["draft"] is False and stable["prerelease"] is False, "PREVIEW_STABLE_LATEST")
    return draft, stable


def admit(mode):
    authority(mode)
    draft, stable = exact_release()
    ops.require(draft["assets"] == [], "PREVIEW_DRAFT_NOT_EMPTY")
    source = ops.api(f"git/commits/{SOURCE}", timeout=15)
    ops.require(source["sha"] == SOURCE and source["tree"]["sha"] == TREE, "PREVIEW_CANDIDATE_TREE")
    assets = [asset for asset in stable["assets"] if asset["name"] == STABLE_APK_NAME]
    ops.require(len(assets) == 1 and assets[0]["state"] == "uploaded"
                and assets[0]["digest"] == f"sha256:{STABLE_APK}", "PREVIEW_BASELINE_DIGEST")


def candidate_checkout(path):
    def git(*args):
        return ops.command(["git", "-C", path, *args]).stdout.decode().strip()
    ops.require(git("rev-parse", "HEAD") == SOURCE and git("rev-parse", "HEAD^{tree}") == TREE
                and not git("status", "--porcelain"), "PREVIEW_CANDIDATE_CHECKOUT")


def require_identity(info, *, previous=False):
    ops.validate_apk(info, previous=previous)
    expected = ("0.3.2", 5, 21) if previous else ("0.3.4", 7, 33)
    ops.require((info["version"], info["version_code"], info["min_sdk"]) == expected,
                "PREVIEW_APK_VERSION")


def require_split(path):
    with ZipFile(path) as apk:
        delivery = apk.read("assets/rime/delivery.json")
        ops.require(ops.digest(delivery) == DELIVERY, "PREVIEW_DELIVERY_MANIFEST")
        files = set(apk.namelist())
        planned = json.loads(delivery)
        external = {entry["path"] for entry in planned["files"]} | {planned["model"]["filename"]}
        ops.require(not files.intersection("assets/rime/" + name for name in external)
                    and not any(name.endswith(".gram") for name in files), "PREVIEW_BUNDLED_DATA")


def require_driver_target(apk, sdk):
    """Check the actual compiled instrumentation target with pinned aapt2."""
    text = ops.command([sdk / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml",
                        apk]).stdout.decode()
    parts = re.split(r"(?m)^ +E: ([^ (\r\n]+)[^\r\n]*\r?\n", text)[1:]
    declarations = [body for name, body in zip(parts[::2], parts[1::2]) if name == "instrumentation"]
    ops.require(len(declarations) == 1, "PREVIEW_DRIVER_TARGET")
    attributes = re.findall(
        r'(?m)^ +A: http://schemas\.android\.com/apk/res/android:(name|targetPackage)'
        r'\(0x[0-9a-fA-F]+\)="([^"\r\n]+)"', declarations[0])
    ops.require(sorted(attributes) == [
        ("name", "androidx.test.runner.AndroidJUnitRunner"),
        ("targetPackage", ops.PACKAGE + ".debug"),
    ], "PREVIEW_DRIVER_TARGET")


def inspect_inputs(folder):
    ops.require({p.name for p in folder.iterdir()} == INPUTS, "PREVIEW_INPUT_FILES")
    sdk, hashes = ops.sdk_tools()
    ops.require(hashes == SDK_HASHES, "PREVIEW_BUILD_TOOLS")
    unsigned = folder / "unsigned.apk"
    info = ops.inspect_apk(unsigned)
    require_identity(info)
    require_split(unsigned)
    java = ops.java_environment()
    result = ops.command([sdk / "apksigner", "verify", unsigned], env=java, allow_failure=True)
    ops.require(result.returncode != 0, "PREVIEW_EXPECT_UNSIGNED")
    ops.command([sdk / "zipalign", "-c", "-P", "16", "4", unsigned])
    certificates = set()
    for name, suffix in (("app-debug.apk", ".debug"), ("app-debug-androidTest.apk", ".debug.test")):
        apk = folder / name
        fixture = ops.inspect_apk(apk)
        ops.require(fixture["application_id"] == ops.PACKAGE + suffix
                    and fixture["min_sdk"] == 33, "PREVIEW_DRIVER_IDENTITY")
        if suffix == ".debug":
            ops.require(fixture["version_code"] == 7 and fixture["debuggable"], "PREVIEW_DRIVER_VERSION")
        else:
            require_driver_target(apk, sdk)
        printed = ops.command([sdk / "apksigner", "verify", "--print-certs", apk], env=java).stdout.decode()
        signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", printed)
        ops.require(len(signers) == 1, "PREVIEW_DRIVER_CERTIFICATE")
        certificates.add(signers[0])
    ops.require(len(certificates) == 1 and ops.CERTIFICATE not in certificates, "PREVIEW_DRIVER_SIGNERS")
    return info


def input_identity(revision, run_id):
    return {
        "repository": ops.REPO, "tag": TAG, "source_sha": SOURCE, "tree": TREE,
        "draft_id": DRAFT_ID, "certificate_sha256": ops.CERTIFICATE,
        "baseline_release_id": STABLE_ID, "baseline_apk_sha256": STABLE_APK,
        "application_tree_sha256": APP_INPUT, "trusted_revision": revision,
        "build_run_id": run_id, "build_run_attempt": 1, "build_tools": SDK_HASHES,
        "driver_patch_sha256": ops.file_sha(DRIVER_PATCH),
    }


def validate_input_record(record, revision, run_id):
    expected = input_identity(revision, run_id)
    ops.require(isinstance(record, dict) and record.keys() == expected.keys() | {"apks", "apk_identity"}
                and all(type(record[key]) is type(value) and record[key] == value
                        for key, value in expected.items()), "PREVIEW_INPUT_RECEIPT")
    apks = record["apks"]
    ops.require(isinstance(apks, dict) and apks.keys() == INPUTS
                and all(isinstance(value, dict) and value.keys() == {"sha256", "bytes"}
                        and hex_digest(value["sha256"]) and type(value["bytes"]) is int
                        and value["bytes"] > 0 for value in apks.values())
                and sum(value["bytes"] for value in apks.values()) < INPUT_BYTES_LIMIT,
                "PREVIEW_INPUT_RECEIPT_APKS")
    info = record["apk_identity"]
    expected_info = {"application_id": ops.PACKAGE, "version": "0.3.4", "version_code": 7,
                     "min_sdk": 33, "debuggable": False, "abis": ops.ABIS}
    ops.require(info == expected_info and all(type(info[k]) is type(v) for k, v in expected_info.items()),
                "PREVIEW_INPUT_RECEIPT_IDENTITY")


def input_record(folder, revision, run_id):
    record = input_identity(revision, run_id)
    record["apk_identity"] = inspect_inputs(folder)
    record["apks"] = {name: {"sha256": ops.file_sha(folder / name), "bytes": (folder / name).stat().st_size}
                      for name in sorted(INPUTS)}
    validate_input_record(record, revision, run_id)
    return record


def canonical_record(record):
    return json.dumps(record, sort_keys=True, separators=(",", ":")).encode()


def attestation_sha(record):
    return ops.digest(canonical_record(record))


def require_approval(record, approved_sha):
    ops.require(hex_digest(approved_sha) and attestation_sha(record) == approved_sha,
                "PREVIEW_APPROVED_ATTESTATION")


def signing_manifest(record, transport):
    ops.require(transport.keys() == {"id", "digest"} and type(transport["id"]) is int
                and transport["id"] > 0 and re.fullmatch(r"sha256:[0-9a-f]{64}", transport["digest"]),
                "PREVIEW_INPUT_TRANSPORT")
    return {
        **record["apk_identity"], "format_version": 1, "repository": ops.REPO,
        "source_sha": SOURCE, "ci_run_id": record["build_run_id"], "ci_artifact_id": transport["id"],
        "ci_artifact_digest": transport["digest"], "application_tree_sha256": APP_INPUT,
        "build_tools_version": ops.BUILD_TOOLS, "tool_sha256": SDK_HASHES,
        "unsigned_apk_sha256": record["apks"]["unsigned.apk"]["sha256"], "release_tag": TAG,
        "certificate_sha256": ops.CERTIFICATE,
        "previous_release": {"tag": STABLE_TAG, "version": "0.3.2", "version_code": 5,
                             "apk_sha256": STABLE_APK},
    }


def prepare_private(folder, revision, run_id, record, transport, approved_sha):
    require_approval(record, approved_sha)
    ops.require(input_record(folder, revision, run_id) == record, "PREVIEW_PRIVATE_INPUT_CHANGED")
    draft, stable = exact_release()
    ops.require(draft["assets"] == [], "PREVIEW_DRAFT_NOT_EMPTY")
    previous = folder / "previous.apk"
    item = ops.release_asset(stable, STABLE_APK_NAME, previous)
    ops.require(item.get("digest") == "sha256:" + STABLE_APK
                and ops.file_sha(previous) == STABLE_APK, "PREVIEW_BASELINE_DIGEST")
    require_identity(ops.inspect_apk(previous), previous=True)
    ops.verify_signed(previous, previous=True)
    ops.write_json(folder / "approved.json", record)
    ops.write_json(folder / "transport.json", transport)
    ops.write_json(folder / "prepared.json", signing_manifest(record, transport))
    (folder / "release-notes.md").write_bytes((ROOT / "docs/PROTECTED_PRERELEASE_NOTES.md").read_bytes())


def bind_signing_input(folder, revision, approved_sha):
    """Last local check before the reviewed signer sees any credential."""
    record = json.loads((folder / "approved.json").read_bytes())
    require_approval(record, approved_sha)
    validate_input_record(record, revision, int(os.environ["GITHUB_RUN_ID"]))
    manifest = json.loads((folder / "prepared.json").read_bytes())
    transport = json.loads((folder / "transport.json").read_bytes())
    ops.require(manifest == signing_manifest(record, transport)
                and ops.file_sha(folder / "previous.apk") == STABLE_APK
                and all((folder / name).stat().st_size == record["apks"][name]["bytes"]
                        and ops.file_sha(folder / name) == record["apks"][name]["sha256"] for name in INPUTS),
                "PREVIEW_SIGNING_INPUT")


def signed_record(folder, *, accepted=False):
    record = json.loads((folder / "release-provenance.json").read_bytes())
    apk = folder / APK
    ops.require({p.name for p in folder.iterdir()} == SIGNED_FILES
                and record["repository"] == ops.REPO and record["source_sha"] == SOURCE
                and record["application_tree_sha256"] == APP_INPUT
                and record["application_id"] == ops.PACKAGE
                and record["version"] == "0.3.4" and record["version_code"] == 7
                and record["release_run_id"] == int(os.environ["GITHUB_RUN_ID"])
                and record["certificate_sha256"] == ops.CERTIFICATE
                and record["apk_sha256"] == ops.file_sha(apk)
                and record["previous_release"] == {"tag": STABLE_TAG, "version": "0.3.2", "version_code": 5,
                                                     "apk_sha256": STABLE_APK}, "PREVIEW_SIGNED_IDENTITY")
    require_identity(ops.inspect_apk(apk))
    require_split(apk)
    ops.verify_signed(apk)
    if accepted:
        require_acceptance(record["preview_acceptance"], record["apk_sha256"])
    return record


def require_acceptance(report, apk_sha):
    expected = {
        "api": 35, "source_sha": SOURCE, "apk_sha256": apk_sha,
        "certificate_sha256": ops.CERTIFICATE, "baseline_version_code": 5, "version_code": 7,
        "scenario": "previous-formal-release-upgrade", "result": "passed",
        "real_reboot": True, "legacy_yaml_preserved": True,
        "requires_online_pack_setup": True, "custom_yaml_activated_after_reboot": True,
    }
    ops.require(all(type(report.get(key)) is type(value) and report[key] == value
                    for key, value in expected.items()), "PREVIEW_SIGNED_ACCEPTANCE")


def seal(folder, report_path, revision, approved_sha):
    record = signed_record(folder)
    report = json.loads(report_path.read_bytes())
    require_acceptance(report, record["apk_sha256"])
    # Keep guest paths, text, output, preferences and diagnostics on the runner.
    record["preview_acceptance"] = {key: report[key] for key in (
        "api", "source_sha", "apk_sha256", "certificate_sha256", "baseline_version_code",
        "version_code", "scenario", "result", "real_reboot", "legacy_yaml_preserved",
        "requires_online_pack_setup", "custom_yaml_activated_after_reboot")}
    record["trusted_revision"] = revision
    record["approval_sha256"] = approved_sha
    ops.write_json(folder / "release-provenance.json", record)
    (folder / "SHA256SUMS.txt").write_text("".join(
        f"{ops.file_sha(folder / name)}  {name}\n" for name in (APK, "release-provenance.json")))


def inspect_asset(asset, apk, *, published=False):
    direct = f"https://github.com/{ops.REPO}/releases/download/{TAG}/{ATTACHMENT}"
    ops.require(asset["state"] == "uploaded" and asset["name"] == ATTACHMENT
                and (not published or asset["browser_download_url"] == direct)
                and asset["size"] == apk.stat().st_size
                and asset.get("digest") == "sha256:" + ops.file_sha(apk), "PREVIEW_UPLOAD_CHECKSUM")
    return direct


def verify_public(url, apk):
    """Anonymous, bounded bytes-only verification. Do not forward the API token."""
    opener = urllib.request.build_opener(ops.NoRedirect)
    for _ in range(4):
        request = urllib.request.Request(url, headers={"User-Agent": "miancak-preview"})
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise ops.ReleaseError(f"PREVIEW_PUBLIC_HTTP_{error.code}") from None
            url = error.headers.get("Location", "")
            parts = urllib.parse.urlsplit(url)
            ops.require(parts.scheme == "https" and parts.port in (None, 443)
                        and parts.username is None and (parts.hostname or "").endswith(".githubusercontent.com"),
                        "PREVIEW_PUBLIC_REDIRECT")
            continue
        size = 0
        limit = apk.stat().st_size
        sha = hashlib.sha256()
        with response:
            for block in iter(lambda: response.read(1024 * 1024), b""):
                size += len(block)
                ops.require(size <= limit, "PREVIEW_PUBLIC_SIZE")
                sha.update(block)
        ops.require(size == limit and sha.hexdigest() == ops.file_sha(apk), "PREVIEW_PUBLIC_CHECKSUM")
        return
    raise ops.ReleaseError("PREVIEW_PUBLIC_REDIRECT_LIMIT")


def publish(folder, revision, approved_sha):
    record = signed_record(folder, accepted=True)
    ops.require(record["trusted_revision"] == revision
                and hex_digest(approved_sha) and record["approval_sha256"] == approved_sha,
                "PREVIEW_PUBLICATION_APPROVAL")
    draft, _ = exact_release()
    ops.require(draft["assets"] == [], "PREVIEW_DRAFT_NOT_EMPTY")
    with tempfile.TemporaryDirectory(prefix="miancak-preview-asset-", dir=folder.parent) as directory:
        apk = Path(directory) / ATTACHMENT
        os.link(folder / APK, apk)
        asset = ops.upload_asset(draft, apk)
        direct = inspect_asset(asset, apk)
        again, _ = exact_release()
        ops.require(len(again["assets"]) == 1 and again["assets"][0]["id"] == asset["id"],
                    "PREVIEW_DRAFT_ASSETS_CHANGED")
        inspect_asset(again["assets"][0], apk)
        notes = (ROOT / "docs/PROTECTED_PRERELEASE_NOTES.md").read_text()
        notes += (f"\n\nSource `{SOURCE}`. Version code 7.\n\n"
                  f"APK SHA-256 `{record['apk_sha256']}`.\n\n"
                  f"Signing certificate SHA-256 `{ops.CERTIFICATE}`.\n")
        ops.api(f"releases/{DRAFT_ID}", method="PATCH",
                body={"name": "MIANCAK v0.3.4-preview.1", "body": notes,
                      "draft": False, "prerelease": True, "make_latest": "false"})
        final, _ = exact_release(published=True)
        ops.require(len(final["assets"]) == 1 and final["assets"][0]["id"] == asset["id"],
                    "PREVIEW_PUBLIC_ASSETS")
        inspect_asset(final["assets"][0], apk, published=True)
        verify_public(direct, apk)
        print(f"PASS: {direct} sha256:{record['apk_sha256']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("admit", "build-check", "sign", "seal"))
    parser.add_argument("--folder", type=Path)
    parser.add_argument("--prepared", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--probe-classes", type=Path)
    args = parser.parse_args()
    ops.install_deadline()
    if args.operation == "admit":
        admit(os.environ.get("PREVIEW_MODE", ""))
        print("PASS: PREVIEW_EXACT_DISPATCH_ADMITTED")
        return
    # The credential-bearing step has no GitHub token. Provenance is checked
    # before it and publication checks it independently on a clean runner.
    mode = "publish"
    revision = workflow_revision(mode) if args.operation == "sign" else authority(mode)
    approval = os.environ.get("PREVIEW_APPROVAL_SHA256", "")
    if args.operation == "build-check":
        draft, _ = exact_release()
        ops.require(draft["assets"] == [], "PREVIEW_DRAFT_NOT_EMPTY")
        candidate_checkout(args.folder)
    elif args.operation == "sign":
        ops.require(not os.environ.get("GH_TOKEN") and os.environ.get("PREVIEW_MODE") == "publish",
                    "PREVIEW_SIGNING_ENVIRONMENT")
        bind_signing_input(args.prepared, revision, approval)
        from sign_apk import sign
        sign(args.prepared, args.folder, args.probe_classes)
    else:
        seal(args.folder, args.report, revision, approval)


if __name__ == "__main__":
    if not (Path(__file__).resolve().parent / "run_with_timeout.py").is_file():
        sys.path.insert(0, str(ROOT / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    try:
        main()
    except ops.ReleaseError as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: PREVIEW_OPERATION", file=sys.stderr)
        raise SystemExit(1)
