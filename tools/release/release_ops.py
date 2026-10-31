#!/usr/bin/env python3
"""Trusted release preparation, artifact transport and GitHub Release publication."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

REPO = "AuroraLilja8514/miancak"
REPO_ID = 1355190124
PACKAGE = "org.miancak.keyboard"
CERTIFICATE = "3883a84d506b029db9d7cb6ddb7953c96ad3aa81a38e3e19290b3d50c4f9a26b"
BUILD_TOOLS = "35.0.0"
ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]
LIMIT = 512 * 1024 * 1024
GATES = {
    "generated", "unit", "build-debug", "build-release", "build-upgrade-baseline",
    "emulator-reboot (23)", "interaction-tests (23)", "interaction-tests (35)",
    "first-install (23, settings-first)", "first-install (23, ime-first)",
    "first-install (35, settings-first)", "first-install (35, ime-first)",
    "upgrade-tests (23)", "upgrade-tests (35)", "word-table-tests (23)",
    "word-table-tests (35)", "word-pack-quality (23)", "word-pack-quality (35)",
}

class ReleaseError(Exception):
    pass

def require(value, code):
    if not value:
        raise ReleaseError(code)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")

def command(args, *, env=None, timeout=180, allow_failure=False):
    completed = subprocess.run([str(x) for x in args], env=env, capture_output=True, timeout=timeout)
    require(allow_failure or completed.returncode == 0, "TOOL_FAILED")
    return completed

def install_deadline():
    seconds = int(os.environ.get("MIANCAK_SCRIPT_TIMEOUT_SECONDS", "3600"))
    require(0 < seconds <= 86400, "SCRIPT_TIMEOUT_CONFIGURATION")
    def expired(signum, frame):
        raise ReleaseError("RELEASE_OPERATION_TIMEOUT")
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(seconds)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None

def request(method, url, *, body=None, accept="application/vnd.github+json", optional=False):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname == "api.github.com"
            and parsed.path.startswith("/repos/" + REPO + "/")
            and parsed.port in (None, 443) and parsed.username is None, "API_DESTINATION")
    headers = {"Accept": accept, "User-Agent": "miancak-release",
               "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GH_TOKEN", "")
    require(token, "API_TOKEN_MISSING")
    headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=120) as response:
            payload = response.read(LIMIT + 1)
            require(len(payload) <= LIMIT, "API_RESPONSE_SIZE")
            return json.loads(payload)
    except urllib.error.HTTPError as failure:
        if optional and failure.code == 404:
            return None
        raise ReleaseError("API_HTTP_" + str(failure.code)) from None

def api(path, **kwargs):
    return request(kwargs.pop("method", "GET"),
                   "https://api.github.com/repos/" + REPO + "/" + path, **kwargs)

def download_api(path, target, expected=None):
    """Follow GitHub's signed storage redirects without forwarding a token."""
    url = "https://api.github.com/repos/" + REPO + "/" + path
    opener = urllib.request.build_opener(NoRedirect)
    for hop in range(5):
        parsed = urllib.parse.urlsplit(url)
        host = parsed.hostname or ""
        require(parsed.scheme == "https" and parsed.port in (None, 443)
                and parsed.username is None, "DOWNLOAD_DESTINATION")
        headers = {"User-Agent": "miancak-release", "Accept": "application/octet-stream"}
        if hop == 0:
            require(host == "api.github.com" and parsed.path.startswith("/repos/" + REPO + "/"),
                    "DOWNLOAD_DESTINATION")
            headers["Authorization"] = "Bearer " + os.environ.get("GH_TOKEN", "")
        else:
            require(host.endswith(".githubusercontent.com")
                    or host.endswith(".blob.core.windows.net")
                    or host.endswith(".s3.amazonaws.com"), "DOWNLOAD_REDIRECT_DESTINATION")
        try:
            response = opener.open(urllib.request.Request(url, headers=headers), timeout=120)
        except urllib.error.HTTPError as failure:
            if failure.code in (301, 302, 303, 307, 308):
                url = failure.headers.get("Location", "")
                continue
            raise ReleaseError("DOWNLOAD_HTTP_" + str(failure.code)) from None
        h = hashlib.sha256()
        size = 0
        with response, Path(target).open("wb") as output:
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                size += len(block)
                require(size <= LIMIT, "DOWNLOAD_SIZE")
                h.update(block)
                output.write(block)
        require(expected is None or h.hexdigest() == expected.removeprefix("sha256:"),
                "DOWNLOAD_DIGEST")
        return
    raise ReleaseError("DOWNLOAD_REDIRECT_LIMIT")

def checked_archive(path):
    archive = zipfile.ZipFile(path)
    total = 0
    require(len(archive.infolist()) <= 30, "ARCHIVE_ENTRY_COUNT")
    for item in archive.infolist():
        parts = Path(item.filename).parts
        require(not item.filename.startswith(("/", "\\")) and "\\" not in item.filename
                and ".." not in parts and ":" not in item.filename
                and ((item.external_attr >> 16) & 0o170000) != 0o120000, "ARCHIVE_PATH")
        total += item.file_size
        require(total <= LIMIT, "ARCHIVE_EXPANSION")
    return archive

def fetch_artifact(artifact_id, run_id, target, expected=None, *, canonical=False):
    item = api("actions/artifacts/" + str(int(artifact_id)))
    require(not item["expired"] and item["workflow_run"]["id"] == int(run_id), "ARTIFACT_SOURCE")
    require(item.get("digest", "").startswith("sha256:"), "ARTIFACT_DIGEST_MISSING")
    if expected:
        require(item["digest"].removeprefix("sha256:") == expected.removeprefix("sha256:"),
                "ARTIFACT_DIGEST_MISMATCH")
    Path(target).mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="miancak-artifact-") as temporary:
        zipped = Path(temporary) / "artifact.zip"
        download_api("actions/artifacts/" + str(item["id"]) + "/zip", zipped, item["digest"])
        with checked_archive(zipped) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                name = Path(entry.filename).name
                if canonical:
                    require(entry.filename == name and
                            (name in {"unsigned.apk", "app-debug.apk", "app-debug-androidTest.apk",
                                      "previous.apk", "prepared.json", "release-notes.md",
                                      "release-provenance.json", "SHA256SUMS.txt"}
                             or re.fullmatch(r"miancak-\d+\.\d+\.\d+\.apk", name)), "ARTIFACT_CONTENT")
                else:
                    require(name.endswith(".apk"), "PRODUCER_ARTIFACT_CONTENT")
                destination = Path(target) / name
                require(not destination.exists(), "ARTIFACT_DUPLICATE")
                destination.write_bytes(archive.read(entry))
    return item

def sdk_tools():
    root = Path(os.environ["ANDROID_HOME"]) / "build-tools" / BUILD_TOOLS
    revision = re.search(r"^Pkg.Revision\s*=\s*(.+)$", (root / "source.properties").read_text(), re.M)
    require(revision and revision.group(1).strip() == BUILD_TOOLS, "SDK_VERSION")
    paths = {"aapt2": root / "aapt2", "zipalign": root / "zipalign",
             "apksigner.jar": root / "lib/apksigner.jar"}
    require(all(p.is_file() for p in paths.values()), "SDK_MISSING")
    return root, {name: file_sha(path) for name, path in paths.items()}

def java_environment():
    env = dict(os.environ)
    env["JAVA_HOME"] = env["JAVA_HOME_17_X64"]
    for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS"):
        env[name] = ""
    return env

def inspect_apk(path):
    root, _ = sdk_tools()
    badging = command([root / "aapt2", "dump", "badging", path]).stdout.decode("utf-8")
    package = re.search(r"^package:\s+(.+)$", badging, re.M)
    fields = dict(re.findall(r"(\w+)='([^']*)'", package[1])) if package else {}
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", badging, re.M)
    native = re.search(r"^native-code: (.+)$", badging, re.M)
    require({"name","versionCode","versionName"} <= fields.keys() and minimum
            and fields["versionCode"].isdigit() and fields.get("versionCodeMajor","0") == "0",
            "APK_MANIFEST")
    return {"application_id": fields["name"], "version_code": int(fields["versionCode"]),
            "version": fields["versionName"], "min_sdk": int(minimum[1]),
            "debuggable": "application-debuggable" in badging,
            "abis": sorted(re.findall(r"'([^']+)'", native[1])) if native else []}

def validate_apk(info):
    require(info["application_id"] == PACKAGE and not info["debuggable"]
            and info["min_sdk"] == 21 and info["abis"] == ABIS
            and re.fullmatch(r"\d+\.\d+\.\d+", info["version"])
            and 0 < info["version_code"] <= 2100000000, "APK_IDENTITY")

def verify_signed(path, expected=CERTIFICATE):
    root, _ = sdk_tools()
    result = command([root / "apksigner", "verify", "--verbose", "--print-certs",
                      "--min-sdk-version", "21", path], env=java_environment())
    text = result.stdout.decode("utf-8")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", text)
    require(len(certificates) == 1 and certificates[0].lower() == expected, "SIGNED_CERTIFICATE")
    require(all(re.search(r"Verified using v" + scheme + r" scheme .*: true", text)
                for scheme in ("1", "2", "3")), "SIGNATURE_SCHEMES")
    return {"v1": True, "v2": True, "v3": True, "certificate_sha256": expected}

def validate_run(value, current_sha, jobs):
    require(value.get("status") == "completed" and value.get("conclusion") == "success"
            and value.get("event") == "push" and value.get("head_branch") == "master"
            and value.get("path") == ".github/workflows/ci.yml"
            and value.get("head_repository", {}).get("id") == REPO_ID
            and value.get("head_sha") == current_sha
            and re.fullmatch(r"[0-9a-f]{40}", current_sha), "CI_SOURCE")
    by_name = {job["name"]: job for job in sorted(jobs, key=lambda value:value.get("id",0))}
    require(GATES <= by_name.keys()
            and all(by_name[name].get("conclusion") == "success" for name in GATES), "CI_GATES")

def trusted_run(run_id):
    value = api("actions/runs/" + str(int(run_id)))
    current = api("git/ref/heads/master")["object"]["sha"]
    jobs = []
    for page in range(1,4):
        listing = api("actions/runs/" + str(int(run_id)) +
                      "/jobs?filter=all&per_page=100&page=" + str(page))
        jobs.extend(listing["jobs"])
        if len(jobs) >= listing["total_count"]:
            break
    else:
        raise ReleaseError("CI_JOB_HISTORY_SIZE")
    validate_run(value, current, jobs)
    return value

def application_fingerprint():
    paths = ["AndroidManifest.xml", ".gitmodules", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
             "proguard-rules.pro", "gradle", "gradlew", "srcs", "res", "assets", "native", "vendor",
             "gen_layouts.py", "gen_method_xml.py", "gen_emoji.py", "scripts/prepare_rime.py",
             "scripts/gen_phonetic_pages.py"]
    tree = command(["git", "ls-tree", "-r", "-z", "HEAD", "--", *paths]).stdout
    require(tree, "APPLICATION_INPUTS_EMPTY")
    return digest(tree)

def release_asset(release, name, target):
    matches = [asset for asset in release["assets"] if asset["name"] == name]
    require(len(matches) == 1, "RELEASE_ASSET_MISSING")
    asset = matches[0]
    download_api("releases/assets/" + str(asset["id"]), target, asset.get("digest"))
    return asset

def prepare(run_id, output):
    source = trusted_run(run_id)
    require(command(["git", "rev-parse", "HEAD"]).stdout.decode().strip() == source["head_sha"],
            "CHECKOUT_SOURCE")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    artifacts = api("actions/runs/" + str(int(run_id)) + "/artifacts?per_page=100")["artifacts"]
    prefix = "miancak-unsigned-release-" + source["head_sha"]
    builds = [a for a in artifacts if a["name"] == prefix and not a["expired"]]
    debug = [a for a in artifacts if a["name"] == "miancak-debug-" + source["head_sha"] and not a["expired"]]
    require(len(builds) == len(debug) == 1, "CI_ARTIFACTS")
    with tempfile.TemporaryDirectory(prefix="miancak-prepare-") as temporary:
        unpacked = Path(temporary) / "unsigned"
        fetch_artifact(builds[0]["id"], run_id, unpacked)
        apks = list(unpacked.glob("*.apk"))
        require(len(apks) == 1, "UNSIGNED_APK_COUNT")
        info = inspect_apk(apks[0])
        validate_apk(info)
        app_hash = application_fingerprint()
        existing = api("releases/tags/v" + info["version"], optional=True)
        if existing and not existing["draft"]:
            previous_record = Path(temporary) / "existing.json"
            release_asset(existing, "release-provenance.json", previous_record)
            record = json.loads(previous_record.read_text())
            require(record["application_tree_sha256"] == app_hash
                    and record["version_code"] == info["version_code"]
                    and record["certificate_sha256"] == CERTIFICATE, "VERSION_ALREADY_USED")
            return {"ready": "false", "reason": "ALREADY_RELEASED"}
        root, hashes = sdk_tools()
        require(command([root / "apksigner", "verify", apks[0]], env=java_environment(),
                        allow_failure=True).returncode != 0, "EXPECTED_UNSIGNED_APK")
        command(["python3", "scripts/check_native_apk.py", apks[0], "--release"])
        command([root / "zipalign", "-P", "16", "-f", "4", apks[0], output / "unsigned.apk"])
        command([root / "zipalign", "-c", "-P", "16", "4", output / "unsigned.apk"])
        debug_dir = Path(temporary) / "debug"
        fetch_artifact(debug[0]["id"], run_id, debug_dir)
        debug_apks = {}
        for apk in debug_dir.glob("*.apk"):
            package = inspect_apk(apk)["application_id"]
            require(package not in debug_apks, "DEBUG_APK_DUPLICATE")
            debug_apks[package] = apk
        require(set(debug_apks) == {PACKAGE + ".debug", PACKAGE + ".debug.test"}, "DEBUG_FIXTURE_IDENTITY")
        require(inspect_apk(debug_apks[PACKAGE + ".debug"])["version_code"] == info["version_code"],
                "DEBUG_FIXTURE_VERSION")
        debug_certificates = []
        for apk in debug_apks.values():
            printed = command([root / "apksigner", "verify", "--print-certs", apk],
                              env=java_environment()).stdout.decode()
            certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", printed)
            require(len(certificates) == 1, "DEBUG_FIXTURE_CERTIFICATE_COUNT")
            debug_certificates.append(certificates[0].lower())
        require(len(set(debug_certificates)) == 1 and debug_certificates[0] != CERTIFICATE,
                "DEBUG_FIXTURE_SIGNING_IDENTITY")
        shutil.copyfile(debug_apks[PACKAGE + ".debug"], output / "app-debug.apk")
        shutil.copyfile(debug_apks[PACKAGE + ".debug.test"], output / "app-debug-androidTest.apk")
        notes = Path("docs/releases") / (info["version"] + ".md")
        require(notes.is_file() and 0 < notes.stat().st_size <= 100000, "RELEASE_NOTES_MISSING")
        shutil.copyfile(notes, output / "release-notes.md")
        manifest = {**info, "format_version": 1, "repository": REPO,
                    "source_sha": source["head_sha"], "ci_run_id": int(run_id),
                    "ci_artifact_id": builds[0]["id"], "ci_artifact_digest": builds[0]["digest"],
                    "application_tree_sha256": app_hash, "build_tools_version": BUILD_TOOLS,
                    "tool_sha256": hashes, "unsigned_apk_sha256": file_sha(output / "unsigned.apk"),
                    "release_tag": "v" + info["version"], "certificate_sha256": CERTIFICATE,
                    "previous_release": None}
        previous = api("releases/latest", optional=True)
        if previous:
            previous_record = Path(temporary) / "previous.json"
            release_asset(previous, "release-provenance.json", previous_record)
            record = json.loads(previous_record.read_text())
            require(record["application_id"] == PACKAGE
                    and record["certificate_sha256"] == CERTIFICATE
                    and record["version_code"] < info["version_code"], "PREVIOUS_RELEASE_IDENTITY")
            release_asset(previous, "miancak-" + record["version"] + ".apk", output / "previous.apk")
            require(file_sha(output / "previous.apk") == record["apk_sha256"], "PREVIOUS_APK_DIGEST")
            verify_signed(output / "previous.apk")
            old = inspect_apk(output / "previous.apk")
            require(old["version_code"] == record["version_code"] and old["application_id"] == PACKAGE,
                    "PREVIOUS_APK_IDENTITY")
            manifest["previous_release"] = {"tag": previous["tag_name"], "version": old["version"],
                                            "version_code": old["version_code"],
                                            "apk_sha256": record["apk_sha256"]}
        write_json(output / "prepared.json", manifest)
        return {"ready": "true", "source_sha": source["head_sha"], "version": info["version"]}

def fetch_prepared(artifact_id, run_id, expected, ci_run_id, output):
    source = trusted_run(ci_run_id)
    fetch_artifact(artifact_id, run_id, output, expected, canonical=True)
    manifest = json.loads((Path(output) / "prepared.json").read_text())
    require(manifest["source_sha"] == source["head_sha"]
            and manifest["ci_run_id"] == int(ci_run_id) and manifest["repository"] == REPO
            and file_sha(Path(output) / "unsigned.apk") == manifest["unsigned_apk_sha256"], "PREPARED_SOURCE")

def upload_asset(release, path):
    url = release["upload_url"].split("{", 1)[0]
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname == "uploads.github.com"
            and parsed.path == "/repos/" + REPO + "/releases/" + str(release["id"]) + "/assets",
            "UPLOAD_DESTINATION")
    headers = {"Authorization": "Bearer " + os.environ["GH_TOKEN"],
               "User-Agent": "miancak-release", "Content-Type": "application/octet-stream",
               "X-GitHub-Api-Version": "2022-11-28"}
    data = Path(path).read_bytes()
    require(len(data) <= LIMIT, "UPLOAD_SIZE")
    req = urllib.request.Request(url + "?name=" + urllib.parse.quote(Path(path).name, safe=""),
                                 data=data, headers=headers, method="POST")
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=180) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        raise ReleaseError("UPLOAD_HTTP_" + str(failure.code)) from None

def publish(folder):
    folder = Path(folder)
    record = json.loads((folder / "release-provenance.json").read_text())
    require(record["repository"] == REPO and record["certificate_sha256"] == CERTIFICATE
            and re.fullmatch(r"\d+\.\d+\.\d+", record["version"]), "PUBLICATION_IDENTITY")
    require(api("git/ref/heads/master")["object"]["sha"] == record["source_sha"], "STALE_PUBLICATION")
    source = trusted_run(record["ci_run_id"])
    require(source["head_sha"] == record["source_sha"], "PUBLICATION_SOURCE")
    name = "miancak-" + record["version"] + ".apk"
    apk = folder / name
    require(file_sha(apk) == record["apk_sha256"], "PUBLICATION_APK_DIGEST")
    require(inspect_apk(apk)["version_code"] == record["version_code"], "PUBLICATION_VERSION")
    validate_apk(inspect_apk(apk))
    verify_signed(apk)
    allowed = {name, "release-provenance.json", "SHA256SUMS.txt"}
    require({p.name for p in folder.iterdir() if p.is_file()} == allowed, "PUBLICATION_FILES")
    checksums = (folder / "SHA256SUMS.txt").read_text().splitlines()
    require(set(checksums) == {file_sha(folder / p) + "  " + p
                               for p in (name, "release-provenance.json")}, "PUBLICATION_CHECKSUMS")
    tag = "v" + record["version"]
    existing_tag = api("git/ref/tags/" + tag, optional=True)
    if existing_tag:
        require(existing_tag["object"]["type"] == "commit"
                and existing_tag["object"]["sha"] == record["source_sha"], "TAG_ALREADY_EXISTS")
    release = api("releases/tags/" + tag, optional=True)
    body = record["release_notes"] + "\n\n" + (
        "Package: " + PACKAGE + " · versionCode " + str(record["version_code"]) + "\n\n"
        "Certificate SHA-256: " + CERTIFICATE + "\n\n"
        "Source: " + record["source_sha"] + "\n\n"
        "Build/verification: https://github.com/" + REPO + "/actions/runs/" + str(record["ci_run_id"]) +
        "\n\nSigned APK acceptance: https://github.com/" + REPO + "/actions/runs/" +
        str(record["release_run_id"]) + "\n")
    if release:
        require(release["draft"] and release["target_commitish"] == record["source_sha"], "RELEASE_ALREADY_PUBLISHED")
    else:
        release = api("releases", method="POST", body={"tag_name": tag,
                       "target_commitish": record["source_sha"], "name": "MIANCAK " + record["version"],
                       "body": body, "draft": True, "prerelease": False})
    by_name = {asset["name"]: asset for asset in release["assets"]}
    require(set(by_name) <= allowed, "UNEXPECTED_RELEASE_ASSET")
    for filename in sorted(allowed):
        asset = by_name.get(filename) or upload_asset(release, folder / filename)
        require(asset.get("digest") == "sha256:" + file_sha(folder / filename), "UPLOADED_DIGEST")
    final = api("releases/" + str(release["id"]), method="PATCH",
                body={"body": body, "draft": False, "prerelease": False, "make_latest": "true"})
    require(not final["draft"] and final["tag_name"] == tag, "PUBLICATION_RESULT")
    print("PASS: GITHUB_RELEASE_PUBLISHED " + final["html_url"], flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=["guard", "prepare", "fetch", "fetch-prepared", "publish"])
    parser.add_argument("--output", default="prepared")
    parser.add_argument("--artifact-id", type=int)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--digest")
    parser.add_argument("--ci-run-id", type=int)
    args = parser.parse_args()
    try:
        install_deadline()
        if args.operation == "guard":
            source = trusted_run(args.ci_run_id)
            with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                stream.write("source_sha=" + source["head_sha"] + "\n")
            print("PASS: TRUSTED_MASTER_CI_SOURCE")
        elif args.operation == "prepare":
            result = prepare(args.ci_run_id, args.output)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                    for key, value in result.items():
                        require(re.fullmatch(r"[a-z_]+", key) and "\n" not in str(value), "WORKFLOW_OUTPUT")
                        stream.write(key + "=" + str(value) + "\n")
            print("PASS: RELEASE_PREPARE_" + ("READY" if result["ready"] == "true" else "SKIPPED"))
        elif args.operation == "fetch":
            fetch_artifact(args.artifact_id, args.run_id, args.output, args.digest, canonical=True)
        elif args.operation == "fetch-prepared":
            fetch_prepared(args.artifact_id, args.run_id, args.digest, args.ci_run_id, args.output)
        else:
            publish(args.output)
    except ReleaseError as failure:
        print("FAIL: " + str(failure), file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("FAIL: RELEASE_OPERATION", file=sys.stderr)
        raise SystemExit(1)

if __name__ == "__main__":
    if not (Path(__file__).resolve().parent / "run_with_timeout.py").is_file():
        sys.path.insert(0,str(Path(__file__).resolve().parents[2] / "scripts"))
    from run_with_timeout import guard_script
    guard_script(__file__)
    main()
