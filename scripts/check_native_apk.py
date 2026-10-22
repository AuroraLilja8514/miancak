#!/usr/bin/env python3
"""Verify the shipped APK, not just a successful compiler invocation."""
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
lock = json.loads((root / "native/sources.lock.json").read_text())
apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    for abi in lock["abis"]:
        for library in ["libmiancak_rime.so", "libcdict_java.so"]:
            assert archive.getinfo(f"lib/{abi}/{library}").file_size > 1000, (abi, library)
    assert archive.getinfo("assets/rime/pinyin_simp.dict.yaml").file_size > 100000
    assert archive.getinfo("assets/rime/manifest.json").file_size > 0
    assert json.loads(archive.read("assets/native-sources.json"))["librime"] == lock["librime"]
    assert any(name.startswith("assets/third-party/pinyin-simp/LICENSE") for name in archive.namelist())
print("Verified declared native ABIs, real offline dictionary, manifest and provenance")
