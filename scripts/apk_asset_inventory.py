"""Static APK inventory. No payload extraction, APK rewrite or device access."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

from collections import defaultdict
import json
from pathlib import PurePosixPath
import re


MANAGED_RIME = {"default.yaml", "miancak_pinyin.schema.yaml",
                "miancak_pinyin_private.schema.yaml", "miancak-profile.json",
                "pinyin_simp.dict.yaml"}
WORD_PACK = {"current.json", "miancak_legacy_0_3.dict.yaml", "wanxiang-custom.dict.yaml",
             "custom-schema/default.yaml", "custom-schema/miancak_pinyin.schema.yaml",
             "custom-schema/miancak_pinyin_private.schema.yaml"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_archive_names(names):
    require(len(names) == len(set(names)), "Duplicate APK entry can shadow an asset")
    for name in names:
        path = name[:-1] if name.endswith("/") else name
        require(path and not path.startswith("/") and "\\" not in path and
                all(p not in ("", ".", "..") for p in path.split("/")),
                "Noncanonical APK entry path")


def read_manifest(archive):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate Rime manifest key")
            result[key] = value
        return result
    with archive.open("assets/rime/manifest.json") as stream:
        raw = stream.read(1024 * 1024 + 1)
    require(len(raw) <= 1024 * 1024, "Rime manifest exceeds 1 MiB")
    manifest = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    require(isinstance(manifest, dict) and manifest, "Rime manifest must be a nonempty object")
    require(all(isinstance(v, str) and re.fullmatch("[a-f0-9]{64}", v) for v in manifest.values()),
            "Rime manifest requires SHA-256 values")
    return manifest


def wanxiang_delivered_files(lock):
    names = ["wanxiang_defaults.yaml" if f["path"] == "default.yaml" else f["path"] for f in lock["files"]]
    require(len(set(names)) == len(names) and lock["model"]["filename"] not in names,
            "Duplicate or colliding Base source path")
    files = dict(zip(names, (f["sha256"] for f in lock["files"])))
    files[lock["model"]["filename"]] = lock["model"]["sha256"]
    return files


def wanxiang_apk_files(lock):
    # Signed APK owns the pinned Lua, schema and conversion configuration.
    # The verified upstream archive supplies only the locked data subset.
    configuration = {"wanxiang_defaults.yaml" if entry["path"] == "default.yaml" else entry["path"]
                     for entry in lock["files"] if entry["path"].endswith((".lua", ".yaml", ".json"))
                     and not entry["path"].endswith(".dict.yaml")}
    return MANAGED_RIME | configuration | {"version.txt"}


def verify_wanxiang_inventory(names, manifest, lock, *, delivery=False):
    """The full pinned Base, one grammar and explicit MIANCAK compatibility only.

    This checks membership. check_native_apk separately hashes every asset and
    compares all upstream source/model hashes, sizes, modules and native ABIs.
    """
    verify_archive_names(names)
    expected = set(wanxiang_delivered_files(lock)) | MANAGED_RIME
    require(set(manifest) == expected, "Rime inventory differs from pinned Base and managed compatibility assets")
    files = {n for n in names if not n.endswith("/")}
    actual = {n[len("assets/rime/"):] for n in files if n.startswith("assets/rime/")}
    packaged = wanxiang_apk_files(lock) | {"manifest.json", "delivery.json"} if delivery else expected | {"manifest.json"}
    require(actual == packaged, "Extra or missing packaged Rime assets")
    grammars = {n for n in files if PurePosixPath(n).suffix.lower() == ".gram"}
    expected_grammars = set() if delivery else {"assets/rime/" + lock["model"]["filename"]}
    require(grammars == expected_grammars, "APK must contain exactly the requested grammar model inventory")
    tables = {n[len("assets/word-pack/"):] for n in files if n.startswith("assets/word-pack/")}
    require(tables == WORD_PACK, "Extra or missing compatibility word-pack assets")


def size_report(archive, apk_bytes):
    groups = defaultdict(lambda: {"zip_payload_bytes": 0, "uncompressed_bytes": 0, "entries": 0})
    largest = []
    for info in archive.infolist():
        if info.is_dir():
            continue
        name = info.filename
        group = ("grammar" if name.endswith(".gram") else
                 "native-libraries" if name.startswith("lib/") else
                 "rime-other" if name.startswith("assets/rime/") else
                 "word-pack-compatibility" if name.startswith("assets/word-pack/") else
                 "third-party-notices" if name.startswith("assets/third-party/") else "other")
        row = groups[group]
        row["zip_payload_bytes"] += info.compress_size
        row["uncompressed_bytes"] += info.file_size
        row["entries"] += 1
        largest.append({"path": name, "zip_payload_bytes": info.compress_size,
                        "uncompressed_bytes": info.file_size, "zip_method": info.compress_type})
    payload = sum(v["zip_payload_bytes"] for v in groups.values())
    require(apk_bytes >= payload, "APK bytes cannot be smaller than its ZIP payload")
    for row in groups.values():
        row["apk_percent"] = round(100 * row["zip_payload_bytes"] / apk_bytes, 4)
    return {"format": 1, "scope": "Static APK bytes; no installation/runtime cost measurement",
            "apk_bytes": apk_bytes, "zip_payload_bytes": payload,
            "zip_overhead_and_alignment_bytes": apk_bytes - payload,
            "groups": dict(sorted(groups.items())),
            "largest_entries": sorted(largest, key=lambda x: (-x["zip_payload_bytes"], x["path"]))[:20]}
