#!/usr/bin/env python3
"""Prepare a pinned, evaluation-only pure word pack; never import a scheme."""
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
    seen = set()
    counts = []
    with (OUT / 'candidate.dict.yaml').open('w') as out:
        out.write('# MIANCAK evaluation: selected rime-ice word data, original weights.\n'
                  '---\nname: pinyin_simp\nversion: "ice-' + SPEC['candidate']['commit'][:12] + '"\nsort: by_weight\n...\n')
        for entry in SPEC['files']:
            path = source / entry['path']
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if digest != entry['sha256']:
                raise RuntimeError('Source hash mismatch: ' + str(path))
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
    (OUT / 'provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Prepared evaluation-only pack:', json.dumps({k: report[k] for k in ('uniqueRows', 'candidateSha256')}))


if __name__ == '__main__':
    prepare()
