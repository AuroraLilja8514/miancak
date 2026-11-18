#!/usr/bin/env python3
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import prepare_wanxiang as w


class WanxiangPreparationTest(unittest.TestCase):
    def archive(self, root, members):
        path = root / "input.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in members:
                archive.writestr(name, data)
        return path

    def spec(self, name, data):
        return dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    def test_original_tones_weights_and_nested_bytes_survive(self):
        data = "你好\tnǐ hǎo\t12345\n".encode()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = self.archive(root, [("dicts/jichu.dict.yaml", data), ("custom/example.png", b"not installed")])
            w.unpack(archive, [self.spec("dicts/jichu.dict.yaml", data)], root / "out")
            self.assertEqual((root / "out/dicts/jichu.dict.yaml").read_bytes(), data)
            self.assertFalse((root / "out/custom/example.png").exists())

    def test_traversal_absolute_empty_and_backslash_paths_are_refused(self):
        for name in ("../outside", "/absolute", "a/../b", "a//b", "./a", "a\\b", ""):
            with self.subTest(name=name), self.assertRaises(ValueError): w.safe_path(name)

    def test_missing_or_modified_members_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = self.archive(root, [("one", b"1")])
            for spec in (self.spec("two", b"2"), self.spec("one", b"x"), self.spec("one", b"11")):
                with self.subTest(spec=spec), self.assertRaises(ValueError): w.unpack(archive, [spec], root / "out")

    def test_duplicate_archive_or_lock_members_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = self.archive(root, [("one", b"1"), ("one", b"1")]); spec = self.spec("one", b"1")
            with self.assertRaises(ValueError): w.unpack(archive, [spec], root / "out")
            with self.assertRaises(ValueError): w.unpack(archive, [spec, spec], root / "out")

    def test_cached_wrong_or_changed_release_bytes_are_not_reused(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "model"; p.write_bytes(b"wrong")
            with patch.object(w.urllib.request, "urlopen") as request:
                with self.assertRaises(ValueError): w.fetch(dict(url="https://example.invalid/model", bytes=4, sha256="0" * 64), p)
                request.assert_not_called()

    def test_download_size_hash_and_partial_cleanup(self):
        for data, size, digest in [(b"12345", 4, "0" * 64), (b"12", 4, "0" * 64), (b"1234", 4, "0" * 64)]:
            with self.subTest(data=data), tempfile.TemporaryDirectory() as tmp:
                p = Path(tmp) / "model"
                with patch.object(w.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                    with self.assertRaises(ValueError): w.fetch(dict(url="https://example.invalid/model", bytes=size, sha256=digest), p)
                self.assertFalse(p.exists()); self.assertFalse(p.with_name("model.partial").exists())

    def test_valid_download_is_content_pinned(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "model"; data = b"actual checked bytes"
            with patch.object(w.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                w.fetch(dict(url="https://example.invalid/model", bytes=len(data), sha256=hashlib.sha256(data).hexdigest()), p)
            self.assertEqual(p.read_bytes(), data)

    def test_base_and_model_are_exact_distribution_not_other_variants(self):
        spec = json.loads(w.LOCK.read_text())
        self.assertEqual(spec["profile"], "wanxiang-base")
        self.assertEqual(spec["base"]["asset_id"], 746092731)
        self.assertEqual(spec["model"]["asset_id"], 747939897)
        self.assertEqual(spec["model"]["bytes"], 407160876)
        self.assertEqual(spec["base"]["commit"], "9704d41ee6e52bac3f66618a267826d360aa64ee")
        names = {entry["path"] for entry in spec["files"]}
        self.assertIn("wanxiang.schema.yaml", names)
        self.assertFalse(any("wanxiang_pro" in n or "wanxiang_lite" in n or "wanxiang_pure" in n for n in names))

    def test_private_schema_has_no_lua_writer_pipeline(self):
        source = (w.ROOT / "native/wanxiang/miancak_pinyin_private.schema.yaml").read_text()
        self.assertIn("translator/enable_user_dict: false", source)
        for key in ("engine/processors:", "engine/segmentors:", "engine/translators:", "engine/filters:"):
            line = next(line for line in source.splitlines() if line.strip().startswith(key))
            self.assertNotIn("lua_", line)
        self.assertIn("__include: miancak_pinyin.schema:/", source)

    def test_raw_schema_ids_exist_before_rime_include_compilation(self):
        for name in ("miancak_pinyin", "miancak_pinyin_private"):
            source = (w.ROOT / "native/wanxiang" / (name + ".schema.yaml")).read_text()
            self.assertIn("schema:\n  schema_id: " + name + "\n", source)
        normal = (w.ROOT / "native/wanxiang/miancak_pinyin.schema.yaml").read_text()
        self.assertIn("schema/dependencies: [wanxiang_mixedcode, wanxiang_reverse, wanxiang_english, wanxiang_abbrev, wanxiang_phrase]", normal)

    def test_base_default_presets_are_preserved(self):
        self.assertIn("__include: wanxiang_defaults:/", (w.ROOT / "native/wanxiang/default.yaml").read_text())
        self.assertIn("switcher/fix_schema_list_order: true", (w.ROOT / "native/wanxiang/default.yaml").read_text())

    def test_custom_dictionary_retains_all_base_imports(self):
        names = ("zi", "jichu", "cuoyin", "duoyin", "diming", "mingren", "yiren")
        source = "# original provenance\n---\nname: wanxiang\nversion: LTS\nimport_tables:\n" + "".join("  - dicts/" + n + " # source comment\n" for n in names) + "...\n"
        text = w.custom_dictionary_header(source)
        for name in names: self.assertIn("- dicts/" + name + " # source comment", text)
        self.assertIn("- miancak_custom\n...", text)
        self.assertIn("name: miancak_wanxiang\n", text)
        self.assertTrue(text.startswith("# original provenance\n"))
        with self.assertRaises(ValueError): w.custom_dictionary_header(source.replace("name: wanxiang", "name: unexpected"))

    def test_candidate_limit_zero_uses_native_size_not_a_64_bit_literal(self):
        source = "before\nlimit = limit == 0 ? 0xffffffffffffffff : limit;\nafter\n"
        result = w.portable_candidate_limit(source)
        self.assertEqual(result, "before\nlimit = limit == 0 ? static_cast<size_t>(-1) : limit;\nafter\n")
        with self.assertRaises(ValueError): w.portable_candidate_limit("unexpected upstream source")


if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)
    unittest.main()
