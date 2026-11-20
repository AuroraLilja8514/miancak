#!/usr/bin/env python3
"""Fetch pinned build inputs and generate packaged assets. Never used at runtime."""

# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[1] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import argparse
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
    import os
    return subprocess.check_output(["git", "-C", str(path), *args], text=True,
                                   timeout=float(os.environ.get("MIANCAK_FETCH_TIMEOUT_SECONDS", "600"))).strip()


def checkout(name, spec):
    dest = CACHE / name
    if not (dest / ".git").exists():
        dest.mkdir(parents=True, exist_ok=True)
        git(dest, "init", "--quiet")
        git(dest, "remote", "add", "origin", spec["url"])
    try:
        actual = git(dest, "rev-parse", "--verify", "--quiet", "HEAD")
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


def main(*, with_runtime_fixture=False):
    CACHE.mkdir(exist_ok=True)
    rime = checkout("librime", LOCK["librime"])
    git(rime, "submodule", "update", "--init", "--recursive", "--depth=1",
        "deps/leveldb", "deps/yaml-cpp", "deps/marisa-trie", "deps/opencc")
    pinyin = checkout("pinyin", LOCK["pinyin"])
    plugins = {name: checkout(directory, LOCK[name]) for name, directory in
               [("octagram", "octagram"), ("librime_lua", "librime-lua"), ("lua", "lua-thirdparty")]}
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
    from prepare_word_pack import prepare, install_default, SPEC
    report = prepare()
    profile = SPEC.get("default_profile", "legacy")
    if profile not in ("legacy", "ice-core", "wanxiang-base"):
        raise RuntimeError("Unsupported default word-pack profile: " + str(profile))
    word_pack = install_default(report, ASSETS) if profile == "ice-core" else None
    if profile == "wanxiang-base":
        from prepare_wanxiang import prepare as prepare_wanxiang
        word_pack = prepare_wanxiang(ASSETS, plugins, include_model=with_runtime_fixture)
    from prepare_wanxiang import sha256
    manifest = {p.relative_to(data).as_posix(): sha256(p) for p in sorted(data.rglob("*")) if p.is_file()}
    if profile == "wanxiang-base" and not with_runtime_fixture:
        model = word_pack["model"]
        manifest[model["filename"]] = model["sha256"]
    (data / "manifest.json").write_text(json.dumps(manifest, sort_keys=True) + "\n")
    metadata = dict(LOCK)
    if word_pack is not None:
        metadata["word_pack"] = word_pack
    metadata["dependencies"] = {name: git(rime / "deps" / name, "rev-parse", "HEAD")
                                for name in ["leveldb", "yaml-cpp", "marisa-trie", "opencc"]}
    (ASSETS / "native-sources.json").write_text(json.dumps(metadata, indent=2) + "\n")
    copy_notices(pinyin, "pinyin-simp")
    copy_notices(boost, "boost")
    copy_notices(plugins["octagram"], "librime-octagram")
    copy_notices(plugins["librime_lua"], "librime-lua")
    for name in ["leveldb", "yaml-cpp", "marisa-trie", "opencc"]:
        copy_notices(rime / "deps" / name, name)
    for name in ["LICENSE", "NOTICE"]:
        if (rime / name).exists():
            out = ASSETS / "third-party/librime" / name
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(rime / name, out)
    delivery = ASSETS.with_name("generated-rime-delivery")
    if profile == "wanxiang-base":
        from prepare_rime_delivery import prepare as prepare_delivery
        prepare_delivery(ASSETS, delivery)
    else:
        if delivery.exists():
            shutil.rmtree(delivery)
        shutil.copytree(ASSETS, delivery)
    print("Prepared locked native sources and APK pinyin assets", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-runtime-fixture", action="store_true", help="Also verify and stage the locked grammar for host runtime tests")
    main(with_runtime_fixture=parser.parse_args().with_runtime_fixture)
