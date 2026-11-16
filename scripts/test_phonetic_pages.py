
# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[1] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import unittest
from gen_phonetic_pages import tone, FINALS

class TonesTest(unittest.TestCase):
    def test_ordered_vowels_and_compound_finals(self):
        for plain, third in {"a": "ǎ", "ai": "ǎi", "ao": "ǎo", "ei": "ěi", "ou": "ǒu", "iu": "iǔ", "ui": "uǐ", "iong": "iǒng", "üe": "üě", "üan": "üǎn", "ün": "ǚn"}.items():
            self.assertEqual(third, tone(plain, 3))
    def test_all_finals_have_four_tones_and_neutral(self):
        self.assertEqual(36, len(FINALS))
        for final in FINALS:
            self.assertEqual(final, tone(final, 0))
            self.assertEqual(4, len({tone(final, i) for i in range(1, 5)}))
    def test_uppercase_text(self):
        self.assertEqual("NǙ", tone("NÜ", 3))
        self.assertEqual("SHUǏ", tone("SHUI", 3))

if __name__ == "__main__":
    unittest.main()
