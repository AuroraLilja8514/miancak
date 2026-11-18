#!/usr/bin/env python3
"""Verify the shipped APK, not just a successful compiler invocation."""

# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[1] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import hashlib
import argparse
import json
from pathlib import Path
import struct
import zipfile
from apk_asset_inventory import read_manifest, size_report, verify_archive_names, verify_wanxiang_inventory

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / "native/sources.lock.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--release", action="store_true")
parser.add_argument("--size-report", type=Path, help="Write a bounded static ZIP size report after all checks pass")
args = parser.parse_args()
apk = args.apk
with zipfile.ZipFile(apk) as archive:
    verify_archive_names(archive.namelist())
    for abi in lock["abis"]:
        for library in ["libmiancak_rime.so", "libcdict_java.so"]:
            assert archive.getinfo(f"lib/{abi}/{library}").file_size > 1000, (abi, library)
            data = archive.read(f"lib/{abi}/{library}")
            assert data[:4] == b'\x7fELF'
            order = '<' if data[5] == 1 else '>'
            is64 = data[4] == 2
            # Verify actual ELF targets, in addition to their APK directory names.
            targets = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86_64": (2, 62)}
            machine = struct.unpack_from(order + 'H', data, 18)[0]
            assert (data[4], machine) == targets[abi], (abi, library, data[4], machine)
            offset = struct.unpack_from(order + ('Q' if is64 else 'I'), data, 32 if is64 else 28)[0]
            size, count = struct.unpack_from(order + 'HH', data, 54 if is64 else 42)
            for index in range(count):
                start = offset + index * size
                if struct.unpack_from(order + 'I', data, start)[0] == 1:
                    alignment = struct.unpack_from(order + ('Q' if is64 else 'I'), data, start + (48 if is64 else 28))[0]
                    assert alignment >= 16384, (abi, library, alignment)
    dictionary_bytes = archive.getinfo("assets/rime/pinyin_simp.dict.yaml").file_size
    assert dictionary_bytes > 100000
    print(f"APK bytes: {apk.stat().st_size}; packaged dictionary bytes: {dictionary_bytes}")
    manifest = read_manifest(archive)
    for name, digest in manifest.items():
        hashed = hashlib.sha256()
        with archive.open("assets/rime/" + name) as source:
            for block in iter(lambda: source.read(1024 * 1024), b""): hashed.update(block)
        assert hashed.hexdigest() == digest, name
    if args.release:
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
        assert b"Ljuloo/keyboard2/ImeTestActivity;" not in dex, "Debug fixture shipped in release"
        for descriptor in [b"Ljuloo/keyboard2/input/RimeEngine;", b"Ljuloo/keyboard2/input/EngineResult;"]:
            assert descriptor in dex, "JNI entry point was removed or renamed"
        for method in (b"customConfig", b"requireClosedForRecovery", b"recoverLegacyCopy"):
            assert method in dex, (method, "Configuration/recovery JNI method was removed or renamed")
    assert json.loads(archive.read("assets/native-sources.json"))["librime"] == lock["librime"]
    assert any(name.startswith("assets/third-party/pinyin-simp/LICENSE") for name in archive.namelist())
    pack_lock = json.loads((root / "native/word-pack.lock.json").read_text())
    if pack_lock.get("default_profile") == "ice-core":
        pack = json.loads(archive.read("assets/word-pack/current.json"))
        assert pack["profile"] == "ice-core" and pack["source"] == pack_lock["candidate"]
        assert pack["dictionary"] == "pinyin_simp"
        assert pack["entries"] == sum(x["rows"] - x["duplicates"] for x in pack["files"])
        assert pack["dictionarySha256"] == manifest["pinyin_simp.dict.yaml"]
        raw = archive.read("assets/rime/pinyin_simp.dict.yaml").decode("utf-8")
        rows = [line for line in raw.split("\n...\n", 1)[1].splitlines() if line.strip() and not line.startswith("#")]
        assert len(rows) == pack["entries"] == 551875
        assert [{k: x[k] for k in ("path", "sha256", "rows")} for x in pack["files"]] == pack_lock["files"][:2]
        assert json.loads(archive.read("assets/native-sources.json"))["word_pack"] == pack
        assert archive.getinfo("assets/third-party/rime-ice/LICENSE").file_size > 10000
        for entry in pack["files"]:
            assert archive.getinfo("assets/third-party/rime-ice/" + entry["path"] + ".header.txt").file_size > 100
        legacy = archive.read("assets/word-pack/miancak_legacy_0_3.dict.yaml")
        assert hashlib.sha256(legacy).hexdigest() == pack["legacy"]["sha256"]
        restored = legacy.replace(b"name: miancak_legacy_0_3\n", b"name: pinyin_simp\n", 1)
        assert hashlib.sha256(restored).hexdigest() == "e341598343a0f0f2035bb1aafc34a7f3bb7887deeecb3f60796262aaa2983e6b"
        print("Verified ice-core: 551875 unchanged source rows, attribution and offline 0.3.0 fallback")
    elif pack_lock.get("default_profile") == "wanxiang-base":
        wanxiang = json.loads((root / "native/wanxiang.lock.json").read_text())
        verify_wanxiang_inventory(archive.namelist(), manifest, wanxiang)
        pack = json.loads(archive.read("assets/word-pack/current.json"))
        assert pack["profile"] == "wanxiang-base" and pack["base"] == wanxiang["base"] and pack["model"] == wanxiang["model"]
        assert json.loads(archive.read("assets/native-sources.json"))["word_pack"] == pack
        for source in wanxiang["files"]:
            name = "wanxiang_defaults.yaml" if source["path"] == "default.yaml" else source["path"]
            assert manifest[name] == source["sha256"], name
            assert archive.getinfo("assets/rime/" + name).file_size == source["bytes"], name
        model = wanxiang["model"]
        assert manifest[model["filename"]] == model["sha256"]
        assert archive.getinfo("assets/rime/" + model["filename"]).file_size == model["bytes"]
        for variant in ("pro", "lite", "pure"):
            assert "assets/rime/wanxiang_" + variant + ".schema.yaml" not in archive.namelist()
        for name in ("base-LICENSE", "model-LICENSE", "model-LICENSE-SCOPE.md", "model-NOTICE.md"):
            assert archive.getinfo("assets/third-party/wanxiang/" + name).file_size > 100
        for dependency in ("octagram", "librime_lua", "lua"):
            assert json.loads(archive.read("assets/native-sources.json"))[dependency] == lock[dependency]
        for abi in lock["abis"]:
            binary = archive.read("lib/" + abi + "/libmiancak_rime.so")
            assert b"octagram" in binary and b"lua" in binary, (abi, "missing static module registration")
            for method in (b"customConfig", b"requireClosedForRecovery", b"recoverLegacyCopy"):
                assert b"Java_juloo_keyboard2_input_RimeEngine_" + method in binary, (abi, method, "missing configuration/recovery JNI entry")
        print("Verified locked Wanxiang Base, full grammar, immutable source bytes and static module markers; runtime still requires device acceptance")
    if args.size_report:
        report = size_report(archive, apk.stat().st_size)
        with apk.open("rb") as source:
            apk_hash = hashlib.file_digest(source, "sha256").hexdigest()
        report.update(apk_sha256=apk_hash,
                      asset_content_hashes_verified=True, native_abis=lock["abis"],
                      default_profile=pack_lock.get("default_profile"), release_checks=args.release)
        args.size_report.write_text(json.dumps(report, indent=2) + "\n")
print("Verified declared native ABIs, 16 KiB ELF alignment, hashed offline data and provenance")
