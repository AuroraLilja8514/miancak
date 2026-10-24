#!/usr/bin/env python3
"""Integrity and coordinate tests for the independent source inventory."""
import tempfile
import shutil
import unittest
from pathlib import Path
from audit_fn_baseline import FIXTURE, OUTPUT, inventory, slots, verified_sources


class BaselineTest(unittest.TestCase):
    def test_inventory_matches_verified_sources(self):
        self.assertEqual(OUTPUT.read_text(), inventory(verified_sources()))

    def test_corruption_is_rejected_before_regeneration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "upstream"
            shutil.copytree(FIXTURE, root)
            path = root / "srcs/compose/fn.json"
            path.write_bytes(path.read_bytes() + b" ")
            with self.assertRaisesRegex(ValueError, "fixture changed"):
                verified_sources(root)

    def test_aliases_cardinals_hidden_and_empty_slots(self):
        data = b'<keyboard><row><key c="a" ne="loc esc" n="up" e="right"/><key key0="b" key8="down"/></row></keyboard>'
        values = list(slots(data))
        self.assertEqual(18, len(values))
        self.assertEqual((0, 0, 2, "ne", "loc esc", "true", "1", "0"), values[2])
        self.assertEqual("up", values[7][4])
        self.assertEqual("right", values[6][4])
        self.assertEqual("", values[5][4])
        self.assertEqual("down", values[17][4])

    def test_digit_slots_and_original_fn_entry_exist_in_upstream(self):
        sources = verified_sources()
        qwerty = list(slots(sources["srcs/layouts/latn_qwerty_us.xml"]))
        digits = [s[4] for s in qwerty if s[0] == 0 and s[2] == 2]
        self.assertEqual(list("1234567890"), digits)
        bottom = list(slots(sources["res/xml/bottom_row.xml"]))
        self.assertEqual("fn", next(s[4] for s in bottom if s[1:3] == (1, 0)))


if __name__ == "__main__":
    unittest.main()
