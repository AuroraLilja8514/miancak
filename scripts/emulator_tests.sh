#!/usr/bin/env bash
set -euo pipefail
adb install -r artifacts/app-debug.apk
adb install -r artifacts/app-debug-androidTest.apk
adb shell am instrument -w org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee instrumentation.log
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' instrumentation.log; then
  exit 1
fi
grep -Eq 'OK \(' instrumentation.log
