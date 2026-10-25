#!/usr/bin/env python3
"""Fetch pinned build inputs and generate packaged assets. Never used at runtime."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".native"
LOCK = json.loads((ROOT / "native/sources.lock.json").read_text())
ASSETS = ROOT / "build/generated-rime-assets"


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


def checkout(name, spec):
    dest = CACHE / name
    if not (dest / ".git").exists():
        dest.mkdir(parents=True, exist_ok=True)
        git(dest, "init", "--quiet")
        git(dest, "remote", "add", "origin", spec["url"])
    try:
        actual = git(dest, "rev-parse", "HEAD")
    except subprocess.CalledProcessError:
        actual = None
    if actual != spec["commit"]:
        git(dest, "fetch", "--depth=1", "origin", spec["commit"])
        git(dest, "checkout", "--detach", "--quiet", spec["commit"])
    if git(dest, "rev-parse", "HEAD") != spec["commit"]:
        raise RuntimeError(f"Wrong source revision: {name}")
    if git(dest, "status", "--porcelain", "--untracked-files=normal"):
        raise RuntimeError(f"Modified source cache: {name}; remove it and prepare again")
    return dest


def copy_notices(source, name):
    for p in sorted(source.rglob("*")):
        if not p.is_file() or ".git" in p.relative_to(source).parts:
            continue
        if not p.name.lower().startswith(("license", "copying", "notice", "copyright")):
            continue
        if p.stat().st_size > 1024 * 1024:
            continue
        out = ASSETS / "third-party" / name / p.relative_to(source)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(p, out)


def main():
    CACHE.mkdir(exist_ok=True)
    rime = checkout("librime", LOCK["librime"])
    git(rime, "submodule", "update", "--init", "--recursive", "--depth=1",
        "deps/leveldb", "deps/yaml-cpp", "deps/marisa-trie", "deps/opencc")
    pinyin = checkout("pinyin", LOCK["pinyin"])
    boost = CACHE / "boost"
    marker = boost / ".miancak-source-sha256"
    if not marker.exists() or marker.read_text().strip() != LOCK["boost"]["sha256"]:
        archive = CACHE / "boost.tar.xz"
        if not archive.exists():
            print("Downloading pinned Boost source archive", flush=True)
            with urllib.request.urlopen(LOCK["boost"]["url"], timeout=120) as src, archive.open("wb") as out:
                shutil.copyfileobj(src, out)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != LOCK["boost"]["sha256"]:
            archive.unlink()
            raise RuntimeError("Boost source archive checksum mismatch")
        if boost.exists():
            shutil.rmtree(boost)
        with tarfile.open(archive) as tar:
            tar.extractall(CACHE, filter="data")
        (CACHE / ("boost-" + LOCK["boost"]["version"])).rename(boost)
        marker.write_text(LOCK["boost"]["sha256"] + "\n")
    if ASSETS.exists():
        shutil.rmtree(ASSETS)
    data = ASSETS / "rime"
    shutil.copytree(ROOT / "native/data", data)
    shutil.copyfile(pinyin / "pinyin_simp.dict.yaml", data / "pinyin_simp.dict.yaml")
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(data.iterdir())}
    (data / "manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")
    metadata = dict(LOCK)
    metadata["dependencies"] = {name: git(rime / "deps" / name, "rev-parse", "HEAD")
                                for name in ["leveldb", "yaml-cpp", "marisa-trie", "opencc"]}
    (ASSETS / "native-sources.json").write_text(json.dumps(metadata, indent=2) + "\n")
    copy_notices(pinyin, "pinyin-simp")
    copy_notices(boost, "boost")
    for name in ["leveldb", "yaml-cpp", "marisa-trie", "opencc"]:
        copy_notices(rime / "deps" / name, name)
    for name in ["LICENSE", "NOTICE"]:
        if (rime / name).exists():
            out = ASSETS / "third-party/librime" / name
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(rime / name, out)
    from prepare_word_pack import prepare
    prepare()
    print("Prepared locked native sources and hashed offline pinyin assets", flush=True)


if __name__ == "__main__":
    main()
