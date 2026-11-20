#!/usr/bin/env bash

# Opt-in private fixture. Run under the same deadline and fresh-guest guard as
# the existing word-table tests. It must never stage Wanxiang files on the guest.
MIANCAK_ENTRY="$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
if [ "${MIANCAK_TIMEOUT_SCRIPT:-}" != "$MIANCAK_ENTRY" ]; then
  exec python3 "$(dirname -- "$MIANCAK_ENTRY")/run_with_timeout.py" --script "$MIANCAK_ENTRY" -- "$@"
fi
source "$(dirname -- "$MIANCAK_ENTRY")/adb_timeout.sh"
set -euo pipefail
umask 077
preflight=false
if [[ "${1:-}" == --preflight ]]; then preflight=true; shift; fi
if [[ "$#" != 3 ]]; then
  echo 'Usage: bundled_delivery_upgrade_tests.sh [--preflight] bundled-v6.apk split-v7.apk split-androidTest.apk' >&2
  exit 2
fi
bundled="$(realpath -- "$1")"
split="$(realpath -- "$2")"
tests="$(realpath -- "$3")"
python3 - "$MIANCAK_ADB_HELPER_DIR/.." "$bundled" "$split" "$tests" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from zipfile import ZipFile

repo, *apks = map(Path, sys.argv[1:])
sys.path.insert(0, str(repo))
from tools.release import release_ops as tools

package = 'org.miancak.keyboard.debug'
roles = ('BUNDLED', 'SPLIT', 'TEST')
signers, evidence = [], {}
sdk, _ = tools.sdk_tools()
for apk, role, application, version in zip(apks, roles, (package, package, package + '.test'), (6, 7, 0)):
    digest = tools.file_sha(apk)
    tools.require(digest == os.environ.get('MIANCAK_' + role + '_APK_SHA256'), role + '_FIXTURE_SHA256')
    info = tools.inspect_apk(apk)
    tools.require(info['application_id'] == application and info['version_code'] == version
                  and info['min_sdk'] == 33 and info['debuggable']
                  and (role == 'TEST' or info['abis'] == tools.ABIS), role + '_FIXTURE_IDENTITY')
    verify = tools.command([sdk / 'apksigner', 'verify', '--print-certs', apk], env=tools.java_environment())
    matches = re.findall(rb'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$', verify.stdout, re.M)
    tools.require(len(matches) == 1, role + '_FIXTURE_SIGNER')
    signers.append(matches[0].decode('ascii'))
    evidence[role.lower()] = {**info, 'bytes': apk.stat().st_size, 'sha256': digest}
tools.require(len(set(signers)) == 1, 'FIXTURE_SIGNERS_DIFFER')
xml = tools.command([sdk / 'aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apks[2]]).stdout
tools.require(b'name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"' in xml
              and b'targetPackage(0x01010021)="org.miancak.keyboard.debug"' in xml, 'FIXTURE_TEST_TARGET')
lock = json.loads((repo / 'native/wanxiang.lock.json').read_text())
model = 'assets/rime/' + lock['model']['filename']
manifest = 'assets/rime/manifest.json'
delivery = 'assets/rime/delivery.json'
with ZipFile(apks[0]) as old, ZipFile(apks[1]) as new:
    tools.require(model in old.namelist() and delivery not in old.namelist()
                  and model not in new.namelist() and delivery in new.namelist(), 'FIXTURE_DELIVERY_ROLES')
    original = old.read(manifest)
    plan = json.loads(new.read(delivery))
    original_model = hashlib.sha256()
    with old.open(model) as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            original_model.update(block)
    tools.require(original == new.read(manifest)
                  and json.loads(original)[lock['model']['filename']] == lock['model']['sha256']
                  and original_model.hexdigest() == lock['model']['sha256']
                  and old.getinfo(model).file_size == plan['model']['bytes'] == lock['model']['bytes']
                  and plan['model']['sha256'] == lock['model']['sha256'], 'FIXTURE_MODEL_COMPATIBILITY')
evidence.update(signing_certificate_sha256=signers[0], deployment_manifest_sha256=tools.digest(original))
print(json.dumps(evidence, sort_keys=True))
PY
if [[ "$preflight" == true ]]; then exit 0; fi

verify_test_target word-table
python3 "$MIANCAK_ADB_HELPER_DIR/public_test_evidence.py" empty-install --serial "$ANDROID_SERIAL" --package org.miancak.keyboard.debug
test "$(adb shell getenforce | tr -d '\r')" = Enforcing
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
mkdir -p test-results
mkdir -m 700 test-results/bundled-delivery
offline() {
  adb shell svc wifi disable
  adb shell svc data disable
}
run_phase() {
  local phase="$1" log="test-results/bundled-delivery/$1.log"
  adb shell am instrument -w -e class juloo.keyboard2.input.BundledDeliveryUpgradeTest -e deliveryPhase "$phase" \
    org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$log"; then return 1; fi
  grep -q '^OK (1 test)' "$log"
}
offline
adb install --no-incremental "$bundled"
adb install --no-incremental "$tests"
run_phase prepare
adb install --no-incremental -r "$split"
run_phase overwrite
before_boot="$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')"
[[ "$before_boot" =~ ^[a-f0-9-]{36}$ ]]
adb reboot
boot_deadline=$((SECONDS + 180))
boot_complete=false
while (( SECONDS < boot_deadline )); do
  after_boot="$(timeout --foreground 5s adb shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')" || after_boot=''
  completed="$(timeout --foreground 5s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || completed=''
  if [[ "$after_boot" =~ ^[a-f0-9-]{36}$ && "$after_boot" != "$before_boot" && "$completed" == 1 ]] && (( SECONDS < boot_deadline )); then
    boot_complete=true
    break
  fi
  sleep 1
done
if [[ "$boot_complete" != true ]]; then
  echo 'Bundled-to-split test guest did not complete a real reboot within 180 seconds.' >&2
  exit 1
fi
adb shell input keyevent KEYCODE_WAKEUP
if adb shell wm help 2>&1 | grep -q dismiss-keyguard; then adb shell wm dismiss-keyguard; else adb shell input keyevent 82; fi
offline
run_phase reboot
