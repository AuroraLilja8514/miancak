#!/usr/bin/env python3
"""Prepare pinned pure word data and provenance; never import a scheme."""

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
import re
import shutil
from prepare_rime import ROOT, checkout

OUT = ROOT / 'build/generated-word-pack-tests/quality'
SPEC = json.loads((ROOT / 'native/word-pack.lock.json').read_text())


def prepare():
    source = checkout('rime-ice', SPEC['candidate'])
    old = checkout('pinyin', json.loads((ROOT / 'native/sources.lock.json').read_text())['pinyin'])
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENSE', OUT / 'LICENSE.rime-ice')
    shutil.copyfile(old / 'pinyin_simp.dict.yaml', OUT / 'baseline.dict.yaml')
    legacy = ROOT / 'build/generated-rime-assets/word-pack/miancak_legacy_0_3.dict.yaml'
    legacy.parent.mkdir(parents=True, exist_ok=True)
    original = (old / 'pinyin_simp.dict.yaml').read_text()
    if original.count('name: pinyin_simp\n') != 1:
        raise RuntimeError('Unexpected legacy dictionary header')
    legacy.write_text(original.replace('name: pinyin_simp\n', 'name: miancak_legacy_0_3\n', 1))
    seen = set()
    counts = []
    with (OUT / 'candidate.dict.yaml').open('w') as out:
        out.write('# MIANCAK: selected rime-ice word data, original weights.\n'
                  '---\nname: pinyin_simp\nversion: "ice-' + SPEC['candidate']['commit'][:12] + '"\nsort: by_weight\n...\n')
        for entry in SPEC['files']:
            path = source / entry['path']
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != entry['sha256']:
                raise RuntimeError('Source hash mismatch: ' + str(path))
            header, separator, _ = path.read_text(encoding='utf-8-sig').partition('\n...\n')
            if not separator:
                raise RuntimeError('Missing attribution header: ' + str(path))
            attribution = OUT / 'source-headers' / (entry['path'] + '.header.txt')
            attribution.parent.mkdir(parents=True, exist_ok=True)
            attribution.write_text(header + '\n...\n')
            ended = False
            rows = duplicates = 0
            for line in path.read_text(encoding='utf-8-sig').splitlines():
                if not ended:
                    if line.strip() == '...':
                        ended = True
                    continue
                if not line.strip() or line.lstrip().startswith('#'):
                    continue
                values = line.split('\t')
                if len(values) != 3 or not re.fullmatch(r'[a-z]{1,8}( [a-z]{1,8})*', values[1]) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?', values[2]):
                    raise RuntimeError('Unsupported source row: ' + str(path) + ': ' + line)
                rows += 1
                key = tuple(values[:2])
                if key in seen:
                    duplicates += 1
                    continue
                seen.add(key)
                out.write(line + '\n')
            if not ended or rows != entry['rows']:
                raise RuntimeError('Source row count/header mismatch: ' + str(path))
            counts.append(dict(entry, duplicates=duplicates))
            if len(counts) == 2:
                out.flush()
                shutil.copyfile(OUT / 'candidate.dict.yaml', OUT / 'core.dict.yaml')
    report = dict(SPEC, files=counts, uniqueRows=len(seen),
                  conversion='8105, base, ext in order; first exact (text,code) wins; weights and readings unchanged; no scheme, Lua, OpenCC, uncoded or auxiliary tables',
                  coreRows=sum(x['rows'] - x['duplicates'] for x in counts[:2]),
                  coreSha256=hashlib.sha256((OUT / 'core.dict.yaml').read_bytes()).hexdigest(),
                  candidateSha256=hashlib.sha256((OUT / 'candidate.dict.yaml').read_bytes()).hexdigest(),
                  baselineSha256=hashlib.sha256((OUT / 'baseline.dict.yaml').read_bytes()).hexdigest())
    report['legacyImportSha256'] = hashlib.sha256(legacy.read_bytes()).hexdigest()
    (OUT / 'provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Prepared locked comparison packs:', json.dumps({k: report[k] for k in ('uniqueRows', 'candidateSha256')}))
    return report


def install_default(report, assets):
    """Adopt the measured core unchanged, with an independently usable old base."""
    if SPEC.get('default_profile') != 'ice-core':
        raise RuntimeError('Unsupported default word-pack profile')
    core = OUT / 'core.dict.yaml'
    if hashlib.sha256(core.read_bytes()).hexdigest() != report['coreSha256']:
        raise RuntimeError('Prepared default pack changed')
    shutil.copyfile(core, assets / 'rime/pinyin_simp.dict.yaml')
    old = json.loads((ROOT / 'native/sources.lock.json').read_text())['pinyin']
    pack = dict(format=1, profile='ice-core', dictionary='pinyin_simp',
                entries=report['coreRows'], dictionarySha256=report['coreSha256'],
                source=SPEC['candidate'], files=report['files'][:2],
                conversion='8105, base in order; first exact (text,code) wins; all readings and original weights retained; no scheme',
                legacy=dict(source=old, dictionary='miancak_legacy_0_3', sha256=report['legacyImportSha256']))
    (assets / 'word-pack/current.json').write_text(json.dumps(pack, ensure_ascii=False, indent=2) + '\n')
    notices = assets / 'third-party/rime-ice'
    notices.mkdir(parents=True, exist_ok=True)
    source = ROOT / '.native/rime-ice'
    shutil.copyfile(source / 'LICENSE', notices / 'LICENSE')
    for entry in SPEC['files'][:2]:
        text = (source / entry['path']).read_text(encoding='utf-8-sig')
        header, separator, _ = text.partition('\n...\n')
        if not separator:
            raise RuntimeError('Missing attribution header: ' + entry['path'])
        target = notices / (entry['path'] + '.header.txt')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(header + '\n...\n')
    (notices / 'SOURCE.json').write_text(json.dumps(pack, ensure_ascii=False, indent=2) + '\n')
    return pack


if __name__ == '__main__':
    prepare()
