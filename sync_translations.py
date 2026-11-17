
# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[0] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import xml.etree.ElementTree as ET
import glob, os, sys
from pathlib import Path

# Generate store title/descriptions from Android source keys, using the English
# baseline for missing keys. Translation XML is read-only; legacy 'sync' mode
# additionally reports missing translations without importing upstream changes.

# Map language codes to the codes that Google Play understands. Languages
# mapped to [None] are not supported by Google Play.
VALUE_DIR_TO_METADATA = {
        "ar": "ar",
        "ars": None,
        "arz": None,
        "bn-rBD": None,
        "ca": "ca",
        "cs": "cs-CZ",
        "de": "de-DE",
        "en": "en-US",
        "es": "es-ES",
        "et": "et",
        "fa": None,
        "fil": "fil",
        "fr": "fr-FR",
        "in": "id",
        "it": "it-IT",
        "ja": "ja-JP",
        "ko": "ko-KR",
        "lv": "lv",
        "my": None,
        "nl": "nl-NL",
        "pl": "pl-PL",
        "pt": "pt-BR",
        "ro": "ro",
        "ru": "ru-RU",
        "ta": "ta-IN",
        "th": "th",
        "tlh": None,
        "tok": None,
        "tr": "tr-TR",
        "uk": "uk",
        "vi": "vi",
        "zh-rCN": "zh-CN",
        "zh-rTW": "zh-TW",
        }

# Dict of strings. Key is the pair string name and product field (often None).
def parse_strings_file(file):
    def key(ent): return ent.get("name"), ent.get("product")
    resrcs = ET.parse(file).getroot()
    return { key(ent): ent for ent in resrcs if ent.tag == "string" }

# # Print the XML file back autoformatted. Takes the output of [sync].
# def write_updated_strings(out, strings):
#     out.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
#     for key, string, comment in strings:
#         if comment: continue
#         out.write("    ")
#         out.write(ET.tostring(string, "unicode").strip())
#         out.write("\n")
#     out.write('</resources>\n')

# Print whether string file is uptodate.
def print_status(fname, strings):
    # Number of commented-out strings
    c = sum(1 for _, _, comment in strings if comment)
    status = "uptodate" if c == 0 else "missing %d strings" % c
    print("%s: %s" % (fname, status))

# Returns a list of tuples (key, string, commented).
def sync(baseline, strings):
    return [
            (key, strings[key], False) if key in strings else
            (key, base_string, True)
            for key, base_string in baseline.items() ]

METADATA_FIELDS = {
        "title.txt": ("app_name_release", 30),
        "short_description.txt": ("short_description", 80),
        "full_description.txt": ("store_description", 4000),
        }

def metadata_texts(value_dir, strings, baseline):
    locale = os.path.basename(value_dir).removeprefix("values-")
    if not locale in VALUE_DIR_TO_METADATA:
        if not any((key, None) in strings for key, _ in METADATA_FIELDS.values()):
            return {} # A locale without store text has not opted into metadata.
        raise Exception("Locale '%s' not known, please add it into sync_translations.py" % locale)
    metadata_name = VALUE_DIR_TO_METADATA[locale]
    if metadata_name == None:
        return {} # Not supported by the play store
    # Match Android's per-key English fallback. Never reuse stale store files
    # just because an unverified translation was deliberately removed.
    strings = {**baseline, **strings}
    result = {}
    for filename, (key, limit) in METADATA_FIELDS.items():
        string = strings[(key, None)]
        if len(string):
            raise ValueError("Store text must be plain text: " + key)
        text = (string.text or "").replace("\\n", "\n").replace("\\'", "'").removeprefix('"').removesuffix('"')
        if not text.strip() or len(text) > limit:
            raise ValueError("Store field length invalid: %s/%s (%d/%d)" % (metadata_name, filename, len(text), limit))
        result[Path("fastlane/metadata/android") / metadata_name / filename] = text + "\n"
    return result

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Generate store text from Android source keys and English fallback; no translation-service access.")
    parser.add_argument("mode", choices=["sync", "metadata"], help="sync also reports missing translations; neither mode rewrites translations")
    parser.add_argument("--check", action="store_true", help="compare generated metadata without writing")
    args = parser.parse_args()
    baseline = parse_strings_file("res/values/strings.xml")
    outputs = metadata_texts("en", baseline, baseline)
    for value_dir in sorted(glob.glob("res/values-*")):
        strings_file = os.path.join(value_dir, "strings.xml")
        if os.path.isfile(strings_file):
            local_strings = parse_strings_file(strings_file)
            outputs.update(metadata_texts(value_dir, local_strings, baseline))
            if args.mode == "sync":
                print_status(strings_file, sync(baseline, local_strings))
    stale = []
    for path, text in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                stale.append(str(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    if stale:
        parser.exit(1, "Stale metadata:\n" + "\n".join(stale) + "\n")
    print("%s %d store text files; field limits 30/80/4000 characters." % ("Checked" if args.check else "Generated", len(outputs)))

if __name__ == "__main__":
    main()
