#!/usr/bin/env python3
"""Verify complete, unmodified third-party test inputs against recorded hashes."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent / 'androidTest/assets/word-tables'
manifest = json.loads((root / 'PROVENANCE.json').read_text())
for name, spec in manifest['rime-ice']['files'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == spec['sha256'], name
print('Verified pinned Rime word-table fixtures and license')
