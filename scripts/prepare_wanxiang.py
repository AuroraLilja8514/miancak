#!/usr/bin/env python3
"""Prepare the locked Wanxiang Base distribution and grammar; build-time only."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "native/wanxiang.lock.json"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    if (not name or "\\" in name or path.is_absolute() or
            any(part in ("", ".", "..") for part in name.split("/"))):
        raise ValueError("Invalid distribution path")
    return path


def fetch(spec, target):
    """A mutable release URL is accepted only at its locked size and digest."""
    target = Path(target)
    if target.is_file():
        if target.stat().st_size != spec["bytes"] or sha256(target) != spec["sha256"]:
            raise ValueError("Cached distribution checksum mismatch: " + target.name)
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".partial")
    try:
        with urllib.request.urlopen(spec["url"], timeout=60) as source, temporary.open("xb") as output:
            total = 0
            for data in iter(lambda: source.read(1024 * 1024), b""):
                total += len(data)
                if total > spec["bytes"]:
                    raise ValueError("Distribution exceeds its locked size")
                output.write(data)
        if total != spec["bytes"] or sha256(temporary) != spec["sha256"]:
            raise ValueError("Downloaded distribution checksum mismatch")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def unpack(archive, files, output):
    """Copy the exact locked runtime subset, never execute an upstream installer."""
    output = Path(output)
    expected = {entry["path"]: entry for entry in files}
    if len(expected) != len(files):
        raise ValueError("Duplicate locked distribution path")
    for name in expected:
        safe_path(name)
    with zipfile.ZipFile(archive) as source:
        names = set()
        for entry in source.infolist():
            name = entry.filename.rstrip("/")
            safe_path(name)
            if name in names or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Duplicate or symlink distribution member")
            names.add(name)
            if entry.is_dir() or name not in expected:
                continue
            spec = expected[name]
            if entry.file_size != spec["bytes"]:
                raise ValueError("Distribution member size mismatch: " + name)
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with source.open(entry) as input_file, target.open("wb") as output_file:
                for data in iter(lambda: input_file.read(1024 * 1024), b""):
                    digest.update(data)
                    output_file.write(data)
            if digest.hexdigest() != spec["sha256"]:
                raise ValueError("Distribution member checksum mismatch: " + name)
        if not set(expected).issubset(names):
            raise ValueError("Incomplete Base distribution")


def custom_dictionary_header(dictionary):
    if dictionary.count("name: wanxiang\n") != 1 or dictionary.count("\n...") != 1:
        raise ValueError("Unexpected Base dictionary header")
    dictionary = dictionary.replace("name: wanxiang\n", "name: miancak_wanxiang\n", 1)
    return dictionary.replace("\n...", "\n  - miancak_custom\n...", 1)


def portable_candidate_limit(source):
    old = "limit = limit == 0 ? 0xffffffffffffffff : limit;"
    if source.count(old) != 1:
        raise ValueError("Pinned Lua candidate-limit source changed")
    return source.replace(old, "limit = limit == 0 ? static_cast<size_t>(-1) : limit;", 1)


def prepare(assets, sources, *, include_model=True):
    spec = json.loads(LOCK.read_text())
    if spec["profile"] != "wanxiang-base" or spec["base"]["version"] != "v18.1.13":
        raise ValueError("Unsupported Wanxiang distribution")
    cache = ROOT / ".native/wanxiang-downloads"
    archive = fetch(spec["base"], cache / "base-v18.1.13.zip")
    data = Path(assets) / "rime"
    stock = data / "wanxiang-stock"
    # Stage once so the original Base default can be retained as attribution,
    # while MIANCAK continues to own its two learning-policy schema IDs.
    unpack(archive, spec["files"], stock)
    for entry in spec["files"]:
        name = entry["path"]
        if name == "default.yaml":
            continue
        target = data / name
        target.parent.mkdir(parents=True, exist_ok=True)
        (stock / name).replace(target)
    notices = Path(assets) / "third-party/wanxiang"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(stock / "default.yaml", notices / "upstream-default.yaml")
    shutil.copyfile(stock / "default.yaml", data / "wanxiang_defaults.yaml")
    shutil.rmtree(stock)
    if include_model:
        model = fetch(spec["model"], cache / spec["model"]["filename"])
        target = data / spec["model"]["filename"]
        # The opt-in host fixture can share these immutable cache bytes.
        try:
            os.link(model, target)
        except OSError:
            shutil.copyfile(model, target)
    for entry in spec["notices"]:
        source = fetch(entry, cache / entry["filename"])
        shutil.copyfile(source, notices / entry["filename"])
    # Keep the previous fixed data-only schema for existing/imported word tables.
    custom = Path(assets) / "word-pack/custom-schema"
    custom.mkdir(parents=True, exist_ok=True)
    for name in ("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"):
        shutil.copyfile(ROOT / "native/data" / name, custom / name)
        shutil.copyfile(ROOT / "native/wanxiang" / name, data / name)
    # Rime import_tables is not recursive. Retain the Base root's actual seven
    # imports, then append the separately validated custom table.
    dictionary = custom_dictionary_header((data / "wanxiang.dict.yaml").read_text())
    (Path(assets) / "word-pack/wanxiang-custom.dict.yaml").write_text(dictionary)
    # Assemble pinned plugin sources outside every clean upstream checkout.
    lua = ROOT / ".native/miancak-lua-build"
    if lua.exists():
        shutil.rmtree(lua)
    shutil.copytree(sources["librime_lua"], lua, ignore=shutil.ignore_patterns(".git"))
    shutil.copytree(sources["lua"] / "lua5.4", lua / "thirdparty/lua5.4")
    types = lua / "src/types.cc"
    before = sha256(types)
    types.write_text(portable_candidate_limit(types.read_text()))
    lua_patch = dict(path="src/types.cc", beforeSha256=before, afterSha256=sha256(types),
                     change="Use size_t max for the zero/unlimited candidate limit on both 32- and 64-bit ABIs; one expression, no warning suppression")
    lua_notice = Path(assets) / "third-party/lua"
    lua_notice.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sources["lua"] / "lua5.4/lua.h", lua_notice / "LICENSE-in-lua.h")
    report = dict(format=2, profile="wanxiang-base", base=spec["base"], model=spec["model"],
                  files=spec["files"], dictionary="wanxiang", userDictionary="wanxiang",
                  adaptations="MIANCAK schema IDs/menu; no-learning schema excludes all Lua writers; original dictionary/tone/weight/model bytes unchanged")
    report["librimeLuaPatch"] = lua_patch
    (data / "miancak-profile.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (Path(assets) / "word-pack/current.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (notices / "SOURCE.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2) + "\n")
    return report


if __name__ == "__main__":
    raise SystemExit("Run scripts/prepare_rime.py to prepare the complete locked native inputs")
