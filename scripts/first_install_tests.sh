#!/usr/bin/env bash

# Re-enter under a finite watchdog; nested drivers share the remaining budget.
MIANCAK_ENTRY="$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
if [ "${MIANCAK_TIMEOUT_SCRIPT:-}" != "$MIANCAK_ENTRY" ]; then
  exec python3 "$(dirname -- "$MIANCAK_ENTRY")/../scripts/run_with_timeout.py" --script "$MIANCAK_ENTRY" -- "$@"
fi
source "$(dirname -- "$MIANCAK_ENTRY")/adb_timeout.sh"
set -euo pipefail
mkdir -p test-results
collect() {
  timeout --foreground 15s adb logcat -d > test-results/logcat.txt || true
  timeout --foreground 15s adb shell dumpsys meminfo org.miancak.keyboard.debug > test-results/app-memory.txt || true
  timeout --foreground 15s adb shell dumpsys window windows > test-results/windows.txt || true
  timeout --foreground 15s adb shell dumpsys input_method > test-results/input-method.txt || true
  timeout --foreground 15s adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
}
trap collect EXIT
if adb shell pm list packages | tr -d '\r' | grep -qx 'package:org.miancak.keyboard.debug'; then
  echo 'Fresh-install guest already contains MIANCAK; refuse a prepared-state test.'
  exit 1
fi
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$actual_api" = "$MIANCAK_TEST_API"
sha256sum artifacts/app-debug.apk > test-results/apk-sha256.txt
adb shell getprop > test-results/device-properties.txt
adb install artifacts/app-debug.apk
adb install artifacts/app-debug-androidTest.apk
if [[ "$actual_api" -ge 33 && "$MIANCAK_ENTRY_ORDER" == settings-first ]]; then
  adb shell cmd locale set-app-locales org.miancak.keyboard.debug --locales zh-CN
  adb shell cmd locale get-app-locales org.miancak.keyboard.debug > test-results/app-locale.txt
  grep -qi 'zh' test-results/app-locale.txt
fi
adb shell svc wifi disable
adb shell svc data disable
run_phase() {
  local phase="$1"
  shift
  timeout --foreground "${MIANCAK_INSTRUMENT_TIMEOUT_SECONDS:-900}s" adb shell am instrument -w -e class juloo.keyboard2.FirstInstallTest \
    -e entryOrder "$MIANCAK_ENTRY_ORDER" "$@" \
    org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/$phase.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/$phase.log"; then exit 1; fi
  grep -Eq 'OK \(' "test-results/$phase.log"
}
run_phase clean-install
adb shell am force-stop org.miancak.keyboard.debug
run_phase reopen -e revisit true
