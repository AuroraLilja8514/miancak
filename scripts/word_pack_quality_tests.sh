#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results/device
collect() {
  local status=$?
  echo MIANCAK_WORD_PACK_REPORT_BEGIN
  adb exec-out run-as org.miancak.keyboard.debug cat files/test-results/word-pack-quality.json || true
  echo
  echo MIANCAK_WORD_PACK_REPORT_END
  adb logcat -b all -d > test-results/logcat.txt || true
  if adb exec-out run-as org.miancak.keyboard.debug tar -C files/test-results -cf - . > test-results/evidence.tar; then
    python3 - <<'PY'
import tarfile
with tarfile.open('test-results/evidence.tar') as archive:
    archive.extractall('test-results/device', filter='data')
PY
  fi
  if [[ "$status" -eq 0 && ! -s test-results/device/word-pack-quality.json ]]; then
    echo 'Missing real word-pack comparison report' >&2
    exit 1
  fi
}
trap collect EXIT
adb install -r artifacts/app-debug.apk
adb install -r artifacts/app-debug-androidTest.apk
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$actual_api" == "$MIANCAK_TEST_API" ]]
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -e class juloo.keyboard2.input.WordPackQualityTest org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee test-results/quality.log
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' test-results/quality.log
grep -Eq 'OK \(' test-results/quality.log
