#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results
collect() {
  adb logcat -d > test-results/logcat.txt || true
  adb shell dumpsys window windows > test-results/windows.txt || true
  adb shell dumpsys input_method > test-results/input-method.txt || true
  adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
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
adb shell svc wifi disable
adb shell svc data disable
run_phase() {
  local phase="$1"
  shift
  adb shell am instrument -w -e class juloo.keyboard2.FirstInstallTest \
    -e entryOrder "$MIANCAK_ENTRY_ORDER" "$@" \
    org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/$phase.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/$phase.log"; then exit 1; fi
  grep -Eq 'OK \(' "test-results/$phase.log"
}
run_phase clean-install
adb shell am force-stop org.miancak.keyboard.debug
run_phase reopen -e revisit true
