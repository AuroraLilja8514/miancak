"""Reviewed shared release primitives for the protected preview."""

# Source and review e878ac038e7fc458b0da85295fd5115a901a290f
# The stable release entry points remain in tools/release.


from __future__ import annotations

import hashlib

import json

import os

from pathlib import Path

import re

import signal

import subprocess

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

MIN_SDK = 33

SIGNATURE_AUDIT_MIN_SDK = 21

LIMIT = 512 * 1024 * 1024

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

def request(method, url, *, body=None, accept="application/vnd.github+json", optional=False,
            timeout=120, authenticated=True, limit=LIMIT):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname == "api.github.com"
            and parsed.path.startswith("/repos/" + REPO + "/")
            and parsed.port in (None, 443) and parsed.username is None, "API_DESTINATION")
    headers = {"Accept": accept, "User-Agent": "miancak-release",
               "X-GitHub-Api-Version": "2022-11-28"}
    if authenticated:
        token = os.environ.get("GH_TOKEN", "")
        require(token, "API_TOKEN_MISSING")
        headers["Authorization"] = "Bearer " + token
    else:
        require(method == "GET" and body is None, "PUBLIC_METADATA_READ_ONLY")
        headers["Cache-Control"] = "no-cache"
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=timeout) as response:
            payload = response.read(limit + 1)
            require(len(payload) <= limit, "API_RESPONSE_SIZE")
            return json.loads(payload)
    except urllib.error.HTTPError as failure:
        if optional and failure.code == 404:
            return None
        raise ReleaseError("API_HTTP_" + str(failure.code)) from None

def api(path, **kwargs):
    return request(kwargs.pop("method", "GET"),
                   "https://api.github.com/repos/" + REPO + "/" + path, **kwargs)

def download_api(path, target, expected=None, *, limit=LIMIT):
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
            headers["X-GitHub-Api-Version"] = "2022-11-28"
            # Artifact archives use the REST media type; Release assets use
            # octet-stream to request their bytes rather than JSON metadata.
            headers["Accept"] = ("application/octet-stream" if path.startswith("releases/assets/")
                                 else "application/vnd.github+json")
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
                require(size <= limit, "DOWNLOAD_SIZE")
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
    code = fields.get("versionCode", "")
    require("name" in fields and minimum and (not code or code.isdigit())
            and fields.get("versionCodeMajor","0") in ("", "0"),
            "APK_MANIFEST")
    return {"application_id": fields["name"], "version_code": int(code or "0"),
            "version": fields.get("versionName", ""), "min_sdk": int(minimum[1]),
            "debuggable": "application-debuggable" in badging,
            "abis": sorted(re.findall(r"'([^']+)'", native[1])) if native else []}

def validate_apk(info, *, previous=False):
    # Released inputs keep their real manifests. Accept the old 21 floor and
    # subsequent supported releases; never require rebuilding the old APK.
    floor = (SIGNATURE_AUDIT_MIN_SDK <= info["min_sdk"] <= MIN_SDK
             if previous else info["min_sdk"] == MIN_SDK)
    require(info["application_id"] == PACKAGE and not info["debuggable"]
            and floor and info["abis"] == ABIS
            and re.fullmatch(r"\d+\.\d+\.\d+", info["version"])
            and 0 < info["version_code"] <= 2100000000, "APK_IDENTITY")

def verify_signed(path, expected=CERTIFICATE, *, previous=False):
    info = inspect_apk(path)
    validate_apk(info, previous=previous)
    root, _ = sdk_tools()
    def verify(floor):
        result = command([root / "apksigner", "verify", "--verbose", "--print-certs",
                          "--min-sdk-version", str(floor), path], env=java_environment())
        text = result.stdout.decode("utf-8")
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", text)
        require(len(certificates) == 1 and certificates[0].lower() == expected, "SIGNED_CERTIFICATE")
        return text
    text = verify(SIGNATURE_AUDIT_MIN_SDK)
    require(all(re.search(r"Verified using v" + scheme + r" scheme .*: true", text)
                for scheme in ("1", "2", "3")), "SIGNATURE_SCHEMES")
    if info["min_sdk"] != SIGNATURE_AUDIT_MIN_SDK:
        verify(info["min_sdk"])
    return {"v1": True, "v2": True, "v3": True, "certificate_sha256": expected,
            "signature_audit_min_sdk": SIGNATURE_AUDIT_MIN_SDK,
            "signature_supported_min_sdk": info["min_sdk"]}

def release_asset(release, name, target):
    matches = [asset for asset in release["assets"] if asset["name"] == name]
    require(len(matches) == 1, "RELEASE_ASSET_MISSING")
    asset = matches[0]
    download_api("releases/assets/" + str(asset["id"]), target, asset.get("digest"))
    return asset

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


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
