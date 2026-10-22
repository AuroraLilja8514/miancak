#!/usr/bin/env python3
"""Verify the shipped APK, not just a successful compiler invocation."""
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
    assert archive.getinfo("assets/rime/pinyin_simp.dict.yaml").file_size > 100000
    assert archive.getinfo("assets/rime/manifest.json").file_size > 0
    assert json.loads(archive.read("assets/native-sources.json"))["librime"] == lock["librime"]
    assert any(name.startswith("assets/third-party/pinyin-simp/LICENSE") for name in archive.namelist())
print("Verified declared native ABIs, real offline dictionary, manifest and provenance")
