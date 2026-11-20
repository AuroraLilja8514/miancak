#!/usr/bin/env python3
from run_with_timeout import guard_script

if __name__ == '__main__':
    guard_script(__file__)

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import wanxiang_test_assets as prep


class TestFixtureInputs(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.apk = self.root / 'app.apk'
        self.lock = json.loads(prep.LOCK.read_text())
        self.files = {'base': b'synthetic archive', 'model': b'synthetic grammar'}
        self.plan = {'format': 1}
        for role, content in self.files.items():
            self.lock[role].update(bytes=len(content), sha256=hashlib.sha256(content).hexdigest())
            self.plan[role] = prep.resource(self.lock[role])
        self.plan['model']['filename'] = self.lock['model']['filename']
        lock = self.root / 'lock.json'
        lock.write_text(json.dumps(self.lock))
        self.enterContext(patch.object(prep, 'LOCK', lock))

    def package(self, *, split=True, model_inside=False):
        with ZipFile(self.apk, 'w') as output:
            if split:
                output.writestr('assets/rime/delivery.json', json.dumps(self.plan))
            if model_inside:
                output.writestr('assets/rime/' + self.plan['model']['filename'], self.files['model'])

    def test_offline_fixture_needs_both_pins_but_https_needs_only_base(self):
        self.package()
        (self.root / 'base-v18.1.13.zip').write_bytes(self.files['base'])
        self.assertEqual(prep.assets(self.apk, self.root, base_only=True), 'split')
        with self.assertRaisesRegex(ValueError, 'model fixture'):
            prep.assets(self.apk, self.root)
        model_path = self.root / self.plan['model']['filename']
        model_path.write_bytes(self.files['model'])
        self.assertEqual(prep.assets(self.apk, self.root), 'split')
        model_path.write_bytes(self.files['model'][::-1])
        with self.assertRaisesRegex(ValueError, 'model fixture'):
            prep.assets(self.apk, self.root)

    def test_https_preflight_fetches_only_the_reviewed_base_asset(self):
        self.package()
        with patch.object(prep, 'fetch') as fetch:
            prep.assets(self.apk, self.root, download=True, base_only=True)
        pin, path = fetch.call_args.args
        fetch.assert_called_once()
        self.assertEqual(pin['url'], self.plan['base']['url'])
        self.assertEqual(path, self.root / 'base-v18.1.13.zip')

    def test_wrong_model_pin_is_rejected_before_any_fetch(self):
        self.plan['model']['url'] = self.plan['model']['url'].rsplit('/', 1)[0] + '/1'
        self.package()
        with patch.object(prep, 'fetch') as fetch, self.assertRaisesRegex(ValueError, 'pins differ'):
            prep.assets(self.apk, self.root, download=True, base_only=True)
        fetch.assert_not_called()

    def test_bundled_historical_input_is_never_fetched_or_used_as_split(self):
        self.package(split=False, model_inside=True)
        self.assertEqual(prep.assets(self.apk, self.root), 'bundled')
        with self.assertRaisesRegex(ValueError, 'split-resource APK'):
            prep.assets(self.apk, self.root, download=True)
        self.package(model_inside=True)
        with self.assertRaisesRegex(ValueError, 'contains a grammar'):
            prep.assets(self.apk, self.root, download=True)


if __name__ == '__main__':
    unittest.main()
