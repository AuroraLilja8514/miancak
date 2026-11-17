#!/usr/bin/env python3
"""Mutation checks for N4 identity, attribution, fallback and generation guards."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import contextlib
import io
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import check_project_identity as identity


class ProjectIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for directory in ("res", "fastlane/metadata/android"):
            for source in (identity.ROOT / directory).rglob("*"):
                if source.is_file() and source.suffix in {".xml", ".txt"}:
                    target = self.root / source.relative_to(identity.ROOT)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
        for filename in ("README.md", "docs/UPSTREAM.md", "build.gradle.kts", "AndroidManifest.xml"):
            target = self.root / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(identity.ROOT / filename, target)
        patch = mock.patch.object(identity, "ROOT", self.root)
        patch.start()
        self.addCleanup(patch.stop)

    def replace(self, filename, before, after):
        path = self.root / filename
        text = path.read_text()
        self.assertIn(before, text)
        path.write_text(text.replace(before, after))

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            identity.check()

    def test_current_product_and_intentional_upstream_attribution_pass(self):
        self.check()

    def test_upstream_launcher_destination_fails_without_banning_credits(self):
        self.replace("res/values/untranslated_strings.xml", identity.HOME + "</string>",
                     "https://github.com/Julow/Unexpected-Keyboard</string>")
        with self.assertRaisesRegex(ValueError, "wrong destination"):
            self.check()

    def test_localized_product_name_drift_fails(self):
        self.replace("res/values-de/strings.xml", '>MIANCAK</string>', '>Unexpected Keyboard</string>')
        with self.assertRaisesRegex(ValueError, "wrong product name"):
            self.check()

    def test_stale_localized_instructions_cannot_return_silently(self):
        self.replace("res/values-de/strings.xml", "</resources>",
                     '<string name="launcher_description">Old onboarding</string></resources>')
        with self.assertRaisesRegex(ValueError, "unreviewed N4 translation"):
            self.check()

    def test_localized_app_name_cannot_override_the_generated_build_label(self):
        self.replace("res/values-de/strings.xml", "</resources>",
                     '<string name="app_name">Unexpected Keyboard</string></resources>')
        with self.assertRaisesRegex(ValueError, "app_name must come from the Gradle"):
            self.check()

    def test_removing_accessible_link_behavior_fails(self):
        self.replace("res/layout/launcher_activity.xml", 'android:linksClickable="true"', 'android:linksClickable="false"')
        with self.assertRaisesRegex(ValueError, "click behavior"):
            self.check()

    def test_generated_store_drift_fails(self):
        self.replace("fastlane/metadata/android/en-US/title.txt", "MIANCAK", "Unexpected Keyboard")
        with self.assertRaisesRegex(ValueError, "Stale metadata"):
            self.check()

    def test_missing_translation_generates_english_instead_of_retaining_old_store_text(self):
        base = identity.translations.parse_strings_file(self.root / "res/values/strings.xml")
        outputs = identity.translations.metadata_texts("values-de", {}, base)
        self.assertEqual(base[("short_description", None)].text + "\n",
                         outputs[Path("fastlane/metadata/android/de-DE/short_description.txt")])
        self.assertIn("Based on Unexpected Keyboard", outputs[Path("fastlane/metadata/android/de-DE/full_description.txt")])
        self.assertEqual({}, identity.translations.metadata_texts("values-fi", {}, base))

    def test_store_length_limits_and_markup_are_enforced_before_writing(self):
        base = identity.translations.parse_strings_file(self.root / "res/values/strings.xml")
        for key, limit in identity.translations.METADATA_FIELDS.values():
            for bad in ("", "x" * (limit + 1)):
                string = ET.Element("string", name=key)
                string.text = bad
                with self.assertRaisesRegex(ValueError, "length invalid"):
                    identity.translations.metadata_texts("en", {(key, None): string}, base)
        rich = ET.fromstring('<string name="store_description">Text <b>markup</b></string>')
        with self.assertRaisesRegex(ValueError, "plain text"):
            identity.translations.metadata_texts("en", {("store_description", None): rich}, base)


if __name__ == "__main__":
    unittest.main(verbosity=2)
