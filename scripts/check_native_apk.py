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
import json
from pathlib import Path
import sys
import struct
import zipfile

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / "native/sources.lock.json").read_text())
apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    for abi in lock["abis"]:
        for library in ["libmiancak_rime.so", "libcdict_java.so"]:
            assert archive.getinfo(f"lib/{abi}/{library}").file_size > 1000, (abi, library)
            data = archive.read(f"lib/{abi}/{library}")
            assert data[:4] == b'\x7fELF'
            order = '<' if data[5] == 1 else '>'
            is64 = data[4] == 2
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
    manifest = json.loads(archive.read("assets/rime/manifest.json"))
    for name, digest in manifest.items():
        assert hashlib.sha256(archive.read("assets/rime/" + name)).hexdigest() == digest, name
    if "--release" in sys.argv[2:]:
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
        assert b"Ljuloo/keyboard2/ImeTestActivity;" not in dex, "Debug fixture shipped in release"
        for descriptor in [b"Ljuloo/keyboard2/input/RimeEngine;", b"Ljuloo/keyboard2/input/EngineResult;"]:
            assert descriptor in dex, "JNI entry point was removed or renamed"
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
print("Verified declared native ABIs, 16 KiB ELF alignment, hashed offline data and provenance")
