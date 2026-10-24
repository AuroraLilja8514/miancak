#!/usr/bin/env python3
"""Offline source inventory. This does NOT prove IME reachability or KeyEvents."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "test/fixtures/upstream-fn"
COMMIT = "c87d9b8fbd501197286eaafeae88278dfbdd56b7"
DIRECTIONS = ("c", "nw", "ne", "sw", "se", "w", "e", "n", "s")
OUTPUT = ROOT / "docs/UPSTREAM_KEY_SLOTS.csv"


def verified_sources(root=FIXTURE):
    manifest = json.loads((root / "manifest.json").read_text())
    if manifest["commit"] != COMMIT:
        raise ValueError("Unexpected upstream revision")
    result = {}
    for path, expected in manifest["files"].items():
        data = (root / path).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Upstream fixture changed: " + path)
        result[path] = data
    return result


def slots(data):
    tree = ET.fromstring(data)
    rows = [tree] if tree.tag == "row" else tree.findall("row")
    for row_i, row in enumerate(rows):
        for col_i, key in enumerate(row.findall("key")):
            for direction_i, alias in enumerate(DIRECTIONS):
                value = key.get("key" + str(direction_i), key.get(alias, ""))
                yield (row_i, col_i, direction_i, alias, value,
                       str(value.startswith("loc ")).lower(),
                       key.get("width", "1"), key.get("shift", "0"))


def inventory(sources):
    out = io.StringIO(newline="")
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(("source", "row", "column", "slot", "direction", "declaration",
                     "localized", "width", "offset"))
    for path, data in sorted(sources.items()):
        if path.endswith(".xml"):
            for slot in slots(data):
                writer.writerow((path, *slot))
    return out.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Regenerate inventory from verified fixtures")
    args = parser.parse_args()
    sources = verified_sources()
    result = inventory(sources)
    if args.write:
        OUTPUT.write_text(result)
    elif OUTPUT.read_text() != result:
        raise SystemExit("Inventory is stale; inspect the diff after --write")
    print(f"Verified {len(sources)} pinned sources; {len(result.splitlines()) - 1} declared slots (including empty/localized slots)")
    print("Source inventory only: runtime Shift/Fn evaluation, configuration-dependent visibility and APK reachability remain separate acceptance gates.")


if __name__ == "__main__":
    main()
