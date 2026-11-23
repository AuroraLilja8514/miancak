#!/usr/bin/env python3
"""Install the pinned public Linux x86-64 tools into this job's temp directory."""
import argparse
import hashlib
import io
from pathlib import Path
import shutil
import signal
import tarfile
import urllib.parse
import urllib.request

PINS = {
    "age": (
        "https://github.com/FiloSottile/age/releases/download/v1.3.2/age-v1.3.2-linux-amd64.tar.gz",
        19405817, "cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10",
        {"age/age": "age", "age/age-keygen": "age-keygen"},
    ),
    "gh": (
        "https://github.com/cli/cli/releases/download/v2.105.0/gh_2.105.0_linux_amd64.tar.gz",
        15050573, "79e7ab5db738c48777b696e374f8b428f0647debfb424f44d2f426370407fe2f",
        {"gh_2.105.0_linux_amd64/bin/gh": "gh"},
    ),
}


class ReleaseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, url):
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != "https" or parsed.hostname not in
                ("release-assets.githubusercontent.com", "objects.githubusercontent.com")
                or parsed.port not in (None, 443) or parsed.username is not None):
            raise ValueError("Unexpected public tool download redirect")
        return super().redirect_request(req, fp, code, msg, headers, url)


def install(root, name):
    url, expected_size, expected_sha, files = PINS[name]
    opener = urllib.request.build_opener(ReleaseRedirect)
    with opener.open(urllib.request.Request(url, headers={
        "User-Agent": "miancak-preview-tooling", "Accept": "application/octet-stream",
    }), timeout=25) as response:
        data = response.read(expected_size + 1)
    if len(data) != expected_size or hashlib.sha256(data).hexdigest() != expected_sha:
        raise ValueError("Public tool archive identity mismatch")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for source, destination in files.items():
            entries = [entry for entry in archive.getmembers() if entry.name == source]
            if len(entries) != 1 or not entries[0].isfile() or not 0 < entries[0].size <= 64 * 1024 * 1024:
                raise ValueError("Unexpected public tool archive member")
            output = root / destination
            with archive.extractfile(entries[0]) as input_file, output.open("xb") as output_file:
                shutil.copyfileobj(input_file, output_file)
            output.chmod(0o700)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    parser.add_argument("--age-only", action="store_true")
    args = parser.parse_args()
    signal.alarm(180)
    try:
        args.destination.mkdir(mode=0o700)
        install(args.destination, "age")
        if not args.age_only:
            install(args.destination, "gh")
    except Exception:
        raise SystemExit("FAIL: PINNED_HANDOFF_TOOL_INSTALL") from None
