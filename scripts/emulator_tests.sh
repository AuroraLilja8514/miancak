#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results
collect() {
  adb logcat -d > test-results/logcat.txt || true
  adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
}
trap collect EXIT
adb install -r artifacts/app-debug.apk
adb install -r artifacts/app-debug-androidTest.apk
adb shell svc wifi disable
adb shell svc data disable
run_tests() {
  local result_name="$1"
  shift
  adb shell am instrument -w "$@" org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/${result_name}.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/${result_name}.log"; then
    exit 1
  fi
  grep -Eq 'OK \(' "test-results/${result_name}.log"
}
run_tests native -e class juloo.keyboard2.input.RimeNativeTest
run_tests interactions -e class juloo.keyboard2.ImeInteractionTest
adb shell am force-stop org.miancak.keyboard.debug
run_tests restart -e class juloo.keyboard2.ImeInteractionTest -e restartOnly true
