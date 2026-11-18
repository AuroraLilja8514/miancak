"""Static APK inventory. No payload extraction, APK rewrite or device access."""
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


def verify_wanxiang_inventory(names, manifest, lock):
    """The full pinned Base, one grammar and explicit MIANCAK compatibility only.

    This checks membership. check_native_apk separately hashes every asset and
    compares all upstream source/model hashes, sizes, modules and native ABIs.
    """
    verify_archive_names(names)
    upstream = {"wanxiang_defaults.yaml" if f["path"] == "default.yaml" else f["path"]
                for f in lock["files"]}
    require(len(upstream) == len(lock["files"]), "Duplicate or colliding Base source path")
    expected = upstream | MANAGED_RIME | {lock["model"]["filename"]}
    require(set(manifest) == expected, "Rime inventory differs from pinned Base and managed compatibility assets")
    files = {n for n in names if not n.endswith("/")}
    actual = {n[len("assets/rime/"):] for n in files if n.startswith("assets/rime/")}
    require(actual == expected | {"manifest.json"}, "Extra or missing packaged Rime assets")
    grammars = {n for n in files if PurePosixPath(n).suffix.lower() == ".gram"}
    require(grammars == {"assets/rime/" + lock["model"]["filename"]}, "APK must contain exactly the requested grammar model")
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
