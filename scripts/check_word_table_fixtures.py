#!/usr/bin/env python3
"""Verify complete, unmodified third-party test inputs against recorded hashes."""

# A finite watchdog also bounds imports, child commands and shutdown.
if __name__ == "__main__":
    import sys as _timeout_sys
    from pathlib import Path as _TimeoutPath
    _timeout_sys.path.insert(0, str(_TimeoutPath(__file__).resolve().parents[1] / "scripts"))
    from run_with_timeout import guard_script as _guard_script
    _guard_script(__file__)

import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent / 'androidTest/assets/word-tables'
manifest = json.loads((root / 'PROVENANCE.json').read_text())
for name, spec in manifest['rime-ice']['files'].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == spec['sha256'], name
print('Verified pinned Rime word-table fixtures and license')
