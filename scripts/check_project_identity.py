#!/usr/bin/env python3
"""Check current product keys and generated destinations, preserving attribution.

This is deliberately key-based: upstream names in credits, licenses, source
comments and historical changelogs are not current-product identity failures.
"""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import sync_translations as translations

NAMES = {"app_name_release": "MIANCAK", "app_name_debug": "MIANCAK (Debug)"}
HOME = "https://github.com/AuroraLilja8514/miancak"
DESTINATIONS = {"launcher_repo_url": HOME, "launcher_issues_url": HOME + "/issues"}
FALLBACK_KEYS = {"launcher_description", "short_description", "store_description"}
LABEL_KEYS = {"launcher_project", "launcher_feedback"}
ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check():
    # Parse every resource XML, including layouts/menus, so broken markup fails.
    for path in sorted((ROOT / "res").rglob("*.xml")):
        ET.parse(path)
    base = translations.parse_strings_file(ROOT / "res/values/strings.xml")
    for directory in sorted((ROOT / "res").glob("values*")):
        seen = set()
        for path in sorted(directory.glob("*.xml")):
            for item in ET.parse(path).getroot():
                if item.tag != "string":
                    continue
                key = item.get("name")
                require(key != "app_name", str(path) + ": app_name must come from the Gradle build variant, not a resource override")
                identity = (key, item.get("product"))
                require(identity not in seen, str(path) + ": duplicate string " + str(key))
                seen.add(identity)
                if key in NAMES:
                    require(item.text == NAMES[key], str(path) + ": wrong product name " + key)
                if key in DESTINATIONS:
                    require(directory.name == "values" and item.text == DESTINATIONS[key], str(path) + ": wrong destination " + key)
                if key in FALLBACK_KEYS | LABEL_KEYS:
                    require(directory.name in {"values", "values-zh-rCN"}, str(path) + ": unreviewed N4 translation " + key)
                if key in NAMES.keys() | DESTINATIONS.keys() | FALLBACK_KEYS | LABEL_KEYS:
                    require(not len(item) and item.text and not re.search(r"%(?:\d+\$)?[a-zA-Z]", item.text), str(path) + ": unexpected markup/placeholder " + key)
    for key, expected in NAMES.items():
        require(base[(key, None)].text == expected, "Missing base name: " + key)
    constants = translations.parse_strings_file(ROOT / "res/values/untranslated_strings.xml")
    layout = ET.parse(ROOT / "res/layout/launcher_activity.xml").getroot()
    for key, expected in DESTINATIONS.items():
        require(constants[(key, None)].text == expected, "Missing destination: " + key)
        views = [view for view in layout.iter("TextView") if view.get(ANDROID + "id") == "@+id/" + key]
        require(len(views) == 1, "Missing unique launcher link: " + key)
        view = views[0]
        require(view.get(ANDROID + "text") == "@string/" + key and view.get(ANDROID + "autoLink") == "web" and view.get(ANDROID + "linksClickable") == "true", "Link text/click behavior diverges: " + key)
    for directory in ("values", "values-zh-rCN"):
        strings = translations.parse_strings_file(ROOT / "res" / directory / "strings.xml")
        for key in FALLBACK_KEYS | LABEL_KEYS:
            require((key, None) in strings, "Missing reviewed key: " + directory + "/" + key)
        store = strings[("store_description", None)].text
        require("MIANCAK" in store and "Rime" in store and "Unexpected Keyboard" in store and HOME in store and HOME + "/issues" in store, "Store identity/destinations/attribution diverge: " + directory)
    gradle = (ROOT / "build.gradle.kts").read_text()
    for name in NAMES.values():
        require('resValue("string", "app_name", "' + name + '")' in gradle, "Generated application label changed: " + name)
    manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()
    for tag in ("application", "service"):
        require(any(node.get(ANDROID + "label") == "@string/app_name" for node in manifest.iter(tag)), "Runtime label no longer uses app_name: " + tag)
    outputs = translations.metadata_texts("en", base, base)
    for path in sorted((ROOT / "res").glob("values-*/strings.xml")):
        outputs.update(translations.metadata_texts(path.parent.name, translations.parse_strings_file(path), base))
    actual = {p.relative_to(ROOT) for field in translations.METADATA_FIELDS for p in (ROOT / "fastlane/metadata/android").glob("*/" + field)}
    require(set(outputs) == actual, "Generated store locale/file set differs")
    for path, content in outputs.items():
        require((ROOT / path).read_text(encoding="utf-8") == content, "Stale metadata: " + str(path))
    require("Unexpected Keyboard" in (ROOT / "README.md").read_text() and "GPL-3.0" in (ROOT / "README.md").read_text(), "Keep fork and license attribution in README")
    require("https://github.com/Julow/Unexpected-Keyboard" in (ROOT / "docs/UPSTREAM.md").read_text(), "Keep upstream provenance")
    print("PASS: product keys, two launcher destinations, reviewed English/Chinese fallback, runtime label sources, XML/placeholder checks and %d generated store files; upstream attribution retained." % len(outputs))


if __name__ == "__main__":
    try:
        check()
    except (ValueError, KeyError, ET.ParseError) as error:
        raise SystemExit("FAIL: " + str(error))
