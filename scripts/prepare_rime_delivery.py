#!/usr/bin/env python3
"""Generate the APK's Rime overlays and exact post-install resource plan."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import argparse
import json
from pathlib import Path
import shutil

from apk_asset_inventory import MANAGED_RIME, wanxiang_apk_files, wanxiang_delivered_files
from prepare_wanxiang import LOCK, ROOT, sha256


def resource(spec):
    repo = spec["repository"].removeprefix("https://github.com/")
    if repo not in ("amzxyz/rime-wanxiang", "amzxyz/rime-wanxiang-data"):
        raise ValueError("Unexpected Wanxiang distribution repository")
    return {"url": f"https://api.github.com/repos/{repo}/releases/assets/{spec['asset_id']}",
            "bytes": spec["bytes"], "sha256": spec["sha256"]}


def delivery_plan(lock, overlays):
    return {"format": 1, "version": lock["base"]["version"], "base": resource(lock["base"]),
            "model": {**resource(lock["model"]), "filename": lock["model"]["filename"]},
            "files": [item for item in lock["files"] if item["path"] not in overlays and item["path"] != "default.yaml"],
            "overlays": overlays}


def prepare(source, output):
    lock = json.loads(LOCK.read_text())
    data = source / "rime"
    manifest = json.loads((data / "manifest.json").read_bytes())
    delivered = wanxiang_delivered_files(lock)
    if set(manifest) != set(delivered) | MANAGED_RIME:
        raise ValueError("Incompatible Wanxiang manifest membership")
    if any(manifest[name] != digest for name, digest in delivered.items()):
        raise ValueError("Wanxiang manifest differs from locked resource bytes")
    overlays = {}
    for name in sorted(wanxiang_apk_files(lock)):
        path = data / name
        digest = sha256(path)
        if manifest[name] != digest:
            raise ValueError("Changed MIANCAK overlay: " + name)
        overlays[name] = {"bytes": path.stat().st_size, "sha256": digest}
    plan = delivery_plan(lock, overlays)
    if output.exists():
        shutil.rmtree(output)
    kept = set(overlays) | {"manifest.json"}
    def excluded(directory, entries):
        relative = Path(directory).relative_to(source)
        if not relative.parts or relative.parts[0] != "rime":
            return ()
        prefix = "/".join(relative.parts[1:])
        if prefix:
            prefix += "/"
        return [name for name in entries if not any(path == prefix + name or path.startswith(prefix + name + "/") for path in kept)]
    shutil.copytree(source, output, ignore=excluded)
    (output / "rime/delivery.json").write_text(json.dumps(plan, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "build/generated-rime-assets")
    parser.add_argument("--output", type=Path, default=ROOT / "build/generated-rime-delivery")
    options = parser.parse_args()
    prepare(options.source, options.output)
