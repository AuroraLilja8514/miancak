#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Package the offline generator, never key material or test fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

def package(output: Path, report: Path, source_sha: str) -> Path:
    root = Path(__file__).resolve().parent
    repo = root.parent.parent
    results = json.loads(report.read_text(encoding='utf-8-sig'))
    if results.get('status') != 'passed' or results.get('checks', 0) < 20:
        raise ValueError('A successful actual Windows test report is required')
    if len(source_sha) != 40 or any(c not in '0123456789abcdef' for c in source_sha):
        raise ValueError('An exact source commit is required')
    payload = {}
    for name in ['Start-Keygen.cmd', 'Miancak-Keygen.ps1', 'Miancak-Keygen.Core.ps1', 'README.txt']:
        content = (root / name).read_text(encoding='utf-8-sig')
        content = content.replace('\r\n', '\n').replace('\n', '\r\n')
        encoding = 'utf-8-sig' if name.endswith(('.ps1', '.txt')) else 'ascii'
        payload[name] = content.encode(encoding)
    payload['LICENSE.txt'] = (repo / 'LICENSE').read_bytes()
    payload['TEST-REPORT.json'] = json.dumps(results, indent=2).encode('utf-8') + b'\n'
    metadata = {'generatorVersion': '1.0.0', 'repository': 'https://github.com/AuroraLilja8514/miancak',
                'sourceCommit': source_sha, 'sourcePath': 'tools/keygen', 'platform': 'Windows x64',
                'externalRuntimeRequired': False, 'usesWindowsComponentsOnly': True,
                'preGeneratedKeysIncluded': False}
    payload['SOURCEINFO.json'] = json.dumps(metadata, indent=2).encode('utf-8') + b'\n'
    sums = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in sorted(payload.items()))
    payload['PACKAGE-SHA256SUMS.txt'] = sums.encode('ascii')
    output.mkdir(parents=True, exist_ok=True)
    archive = output / 'miancak-keygen-windows-x64.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(payload.items()):
            info = zipfile.ZipInfo('miancak-keygen-windows-x64/' + name, (2026, 10, 28, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert all(Path(n).suffix.lower() not in {'.p12', '.pfx', '.key', '.jks', '.keystore'} for n in z.namelist())
        assert len(z.namelist()) == 8
    (output / (archive.name + '.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n', encoding='ascii')
    return archive

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--report', required=True, type=Path)
    p.add_argument('--source-sha', required=True)
    args = p.parse_args()
    print(package(args.output, args.report, args.source_sha))
