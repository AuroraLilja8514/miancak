#!/usr/bin/env python3
"""Fetch or verify only the current APK's public, pinned Android test inputs."""
if __name__ == '__main__':
    from run_with_timeout import guard_script
    guard_script(__file__)

import argparse
import json
from pathlib import Path
import urllib.error
from zipfile import ZipFile

from prepare_rime_delivery import resource
from prepare_wanxiang import LOCK, fetch, sha256


def assets(apk, directory, *, download=False, base_only=False):
    lock = json.loads(LOCK.read_text())
    with ZipFile(apk) as package:
        if 'assets/rime/delivery.json' not in package.namelist():
            if download or base_only:
                raise ValueError('Expected a split-resource APK')
            return 'bundled'
        entry = package.getinfo('assets/rime/delivery.json')
        if entry.file_size > 1024 * 1024:
            raise ValueError('Oversized APK delivery plan')
        plan = json.loads(package.read(entry))
        if plan['format'] != 1 or plan['base'] != resource(lock['base']) or plan['model'] != {
            **resource(lock['model']), 'filename': lock['model']['filename']
        }:
            raise ValueError('APK delivery pins differ from the reviewed test assets')
        if 'assets/rime/' + lock['model']['filename'] in package.namelist():
            raise ValueError('Split APK contains a grammar model')

    model = lock['model']
    items = [('base', 'base-' + lock['base']['version'] + '.zip')]
    if not base_only:
        items.append(('model', model['filename']))
    for role, name in items:
        pin = {**lock[role], **resource(lock[role])}
        path = directory / name
        if download:
            try:
                fetch(pin, path)
            except urllib.error.HTTPError as rejected:
                raise ValueError(f'Pinned Wanxiang {role} returned HTTP {rejected.code}') from rejected
            except (OSError, ValueError) as unavailable:
                raise ValueError(f'Could not fetch and verify pinned Wanxiang {role}') from unavailable
        elif not path.is_file() or path.stat().st_size != pin['bytes'] or sha256(path) != pin['sha256']:
            raise ValueError(f'Missing or invalid pinned Wanxiang {role} fixture')
    return 'split'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--base-only', action='store_true')
    args = parser.parse_args()
    try:
        print(assets(args.apk, args.directory, download=args.fetch, base_only=args.base_only))
    except ValueError as failure:
        raise SystemExit(str(failure)) from None


if __name__ == '__main__':
    main()
