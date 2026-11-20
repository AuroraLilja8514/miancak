#!/usr/bin/env python3
import io
import json
from pathlib import Path
import unittest
import zipfile
import apk_asset_inventory as inventory


class ApkAssetInventoryTest(unittest.TestCase):
    def fixture(self):
        lock = {"files": [{"path": name, "sha256": "0" * 64} for name in ("default.yaml", "wanxiang.schema.yaml")],
                "model": {"filename": "wanxiang-lts-zh-hans.gram", "sha256": "0" * 64}}
        manifest = {name: "0" * 64 for name in (
            "wanxiang_defaults.yaml", "wanxiang.schema.yaml", "default.yaml",
            "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml",
            "miancak-profile.json", "pinyin_simp.dict.yaml", "wanxiang-lts-zh-hans.gram")}
        names = ["assets/rime/manifest.json"] + ["assets/rime/" + n for n in manifest]
        names += ["assets/word-pack/" + n for n in inventory.WORD_PACK]
        return lock, manifest, names

    def test_complete_base_model_and_explicit_compatibility_are_kept(self):
        lock, manifest, names = self.fixture()
        inventory.verify_wanxiang_inventory(names + ["assets/rime/", "classes.dex"], manifest, lock)

    def test_duplicate_model_or_shadowed_manifest_is_refused(self):
        lock, manifest, names = self.fixture()
        for extra in ("assets/rime/wanxiang-lts-zh-hans.gram", "assets/rime/manifest.json"):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "Duplicate APK"):
                inventory.verify_wanxiang_inventory(names + [extra], manifest, lock)

    def test_unmanifested_model_schema_table_or_compiled_cache_is_refused(self):
        lock, manifest, names = self.fixture()
        for extra in ("other.gram", "wanxiang_pro.schema.yaml", "dicts/extra.dict.yaml", "build/wanxiang.table.bin"):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "Extra or missing"):
                inventory.verify_wanxiang_inventory(names + ["assets/rime/" + extra], manifest, lock)

    def test_manifest_cannot_legitimize_an_unrequested_asset(self):
        lock, manifest, names = self.fixture()
        manifest["other.gram"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "differs from pinned Base"):
            inventory.verify_wanxiang_inventory(names + ["assets/rime/other.gram"], manifest, lock)

    def test_second_model_outside_rime_and_case_variants_are_refused(self):
        lock, manifest, names = self.fixture()
        for extra in ("assets/another.gram", "assets/MODEL.GRAM"):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "exactly the requested"):
                inventory.verify_wanxiang_inventory(names + [extra], manifest, lock)

    def test_missing_required_base_file_and_compatibility_are_refused(self):
        lock, manifest, names = self.fixture()
        for missing in ("assets/rime/wanxiang.schema.yaml", "assets/word-pack/miancak_legacy_0_3.dict.yaml"):
            with self.subTest(missing=missing), self.assertRaises(ValueError):
                inventory.verify_wanxiang_inventory([n for n in names if n != missing], manifest, lock)

    def test_additional_full_word_pack_is_not_silently_bundled(self):
        lock, manifest, names = self.fixture()
        with self.assertRaisesRegex(ValueError, "compatibility word-pack"):
            inventory.verify_wanxiang_inventory(names + ["assets/word-pack/ice-full.dict.yaml"], manifest, lock)

    def test_delivery_keeps_signed_configuration_and_excludes_download_payload(self):
        lock, manifest, names = self.fixture()
        for source in ("lua/wanxiang/processor.lua", "dicts/base.dict.yaml"):
            lock["files"].append({"path": source, "sha256": "0" * 64})
            manifest[source] = "0" * 64
        names = [name for name in names if not name.endswith(".gram")] + [
            "assets/rime/delivery.json", "assets/rime/version.txt", "assets/rime/lua/wanxiang/processor.lua"]
        manifest["version.txt"] = "0" * 64; lock["files"].append({"path": "version.txt", "sha256": "0" * 64})
        inventory.verify_wanxiang_inventory(names, manifest, lock, delivery=True)
        with self.assertRaisesRegex(ValueError, "Extra or missing"):
            inventory.verify_wanxiang_inventory(names + ["assets/rime/dicts/base.dict.yaml"], manifest, lock, delivery=True)
        with self.assertRaisesRegex(ValueError, "Extra or missing"):
            inventory.verify_wanxiang_inventory(names[:-1], manifest, lock, delivery=True)

    def test_colliding_upstream_default_alias_is_refused(self):
        lock, manifest, names = self.fixture()
        lock["files"].append({"path": "wanxiang_defaults.yaml"})
        with self.assertRaisesRegex(ValueError, "colliding Base"):
            inventory.verify_wanxiang_inventory(names, manifest, lock)

    def test_noncanonical_archive_paths_are_refused(self):
        for name in ("", "/absolute", "assets/../outside", "assets//file", "assets\\file", "./file"):
            with self.subTest(name=name), self.assertRaises(ValueError): inventory.verify_archive_names([name])

    def manifest(self, raw):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as z: z.writestr("assets/rime/manifest.json", raw)
        with zipfile.ZipFile(output) as z: return inventory.read_manifest(z)

    def test_manifest_duplicates_shapes_hashes_and_size_are_refused(self):
        for raw in ('{"a":"' + "0" * 64 + '","a":"' + "1" * 64 + '"}',
                    '[]', '{}', '{"a":null}', '{"a":"not-sha256"}', ' ' * (1024 * 1024 + 1)):
            with self.subTest(raw=raw[:50]), self.assertRaises(ValueError): self.manifest(raw)
        self.assertEqual(self.manifest('{"a":"' + "a" * 64 + '"}'), {"a": "a" * 64})

    def test_report_distinguishes_compressed_raw_and_apk_overhead(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as z:
            z.writestr("assets/rime/model.gram", b"x" * 8192)
            z.writestr("assets/rime/dicts/base.dict.yaml", b"phrase" * 256)
            z.writestr("lib/x86_64/libexample.so", b"library")
        with zipfile.ZipFile(output) as z:
            result = inventory.size_report(z, len(output.getvalue()))
        self.assertEqual(result["groups"]["grammar"]["uncompressed_bytes"], 8192)
        self.assertLess(result["groups"]["grammar"]["zip_payload_bytes"], 8192)
        self.assertGreater(result["zip_overhead_and_alignment_bytes"], 0)
        self.assertEqual(result["zip_payload_bytes"] + result["zip_overhead_and_alignment_bytes"], result["apk_bytes"])

    def test_real_lock_still_declares_full_base_and_one_original_model(self):
        lock = json.loads((Path(__file__).resolve().parents[1] / "native/wanxiang.lock.json").read_text())
        self.assertEqual(len(lock["files"]), 80)
        self.assertEqual(lock["model"]["bytes"], 390786092)
        self.assertEqual(lock["model"]["sha256"], "e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
