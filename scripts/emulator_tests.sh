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
  local test_exit=$?
  stop_progress
  if [[ "$test_exit" -ne 0 ]]; then
    # A missing accessibility root does not establish an app or guest cause.
    # Ask ART for stacks before collecting logs, while the timed-out process
    # is still alive. Restrict the signal to this debuggable test package.
    adb shell ps > test-results/processes-at-failure.txt || true
    local app_pid
    app_pid="$(awk '{sub(/\r$/, "")} $NF == "org.miancak.keyboard.debug" {print $2; exit}' test-results/processes-at-failure.txt)"
    if [[ "$app_pid" =~ ^[0-9]+$ ]]; then
      adb shell run-as org.miancak.keyboard.debug kill -3 "$app_pid" > test-results/thread-dump-request.txt 2>&1 || true
      timeout --foreground 10s adb shell cat /data/anr/traces.txt > test-results/art-thread-dump.txt 2>&1 || true
    fi
  fi
  adb logcat -b all -d > test-results/logcat.txt || true
  adb shell dumpsys input_method > test-results/input-method.txt || true
  adb shell dumpsys window windows > test-results/windows.txt || true
  timeout --foreground 15s adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE > test-results/tombstones.txt || true
  adb shell cat /proc/meminfo > test-results/guest-memory.txt || true
  adb shell uiautomator dump /sdcard/miancak-window.xml || true
  adb pull /sdcard/miancak-window.xml test-results/window.xml || true
  python3 - <<'PY'
from pathlib import Path
lines = Path("test-results/logcat.txt").read_text(errors="replace").splitlines()
indexes = set()
for i, line in enumerate(lines):
    if any(marker in line for marker in ("AndroidRuntime", "FATAL EXCEPTION", "MIANCAK", "Fatal signal", "ANR in")):
        indexes.update(range(max(0, i - 2), min(len(lines), i + 15)))
for i in sorted(indexes)[-240:]:
    print(lines[i])
print(Path("test-results/window.xml").read_text() if Path("test-results/window.xml").exists() else "No UI dump")
PY
  adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
  if adb exec-out run-as org.miancak.keyboard.debug tar -C files/test-results -cf - . > test-results/private-evidence.tar; then
    python3 - <<'PY'
from pathlib import Path
import tarfile
out = Path("test-results/device"); out.mkdir(exist_ok=True)
with tarfile.open("test-results/private-evidence.tar") as archive:
    archive.extractall(out, filter="data")
PY
  fi
  python3 - <<'PY'
from pathlib import Path
for name in ('editor-eight-directions.png', 'editor-landscape-draft.png', 'symbols-landscape.png', 'fn-final.png'):
    path = Path('test-results/device') / name
    if path.is_file():
        print('Screenshot artifact: ' + name + ' (' + str(path.stat().st_size) + ' bytes)')
PY
  adb shell run-as org.miancak.keyboard.debug du -sk files/rime > test-results/rime-footprint-kib.txt || true
  cat test-results/rime-footprint-kib.txt
  if [[ -f test-results/device/native-metrics.json ]]; then
    cat test-results/device/native-metrics.json
  fi
  if [[ "$test_exit" -eq 0 ]]; then
    for evidence in fn-received-events.csv symbol-receiver.json editor-receiver.json editor-restart-receiver.json pointer-traces.jsonl; do
      if [[ ! -s "test-results/device/$evidence" ]]; then
        echo "Missing receiver evidence: $evidence" >&2
        exit 1
      fi
    done
  fi
}
trap collect EXIT
start_progress
adb install -r artifacts/app-debug.apk
adb install -r artifacts/app-debug-androidTest.apk
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ -n "${MIANCAK_TEST_API:-}" && "$actual_api" != "$MIANCAK_TEST_API" ]]; then
  echo "Unexpected emulator API: $actual_api"
  exit 1
fi
if [[ "$actual_api" -ge 35 ]]; then adb shell settings put system font_scale 1.3; fi
adb shell svc wifi disable
adb shell svc data disable
run_tests() {
  local result_name="$1"
  shift
  echo "MIANCAK stage=$result_name; instrumentation limit=${MIANCAK_INSTRUMENT_TIMEOUT_SECONDS:-900}s"
  adb shell am instrument -w "$@" org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/${result_name}.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/${result_name}.log"; then
    exit 1
  fi
  grep -Eq 'OK \(' "test-results/${result_name}.log"
}
run_tests word-tables-native -e class juloo.keyboard2.input.WordTableNativeTest
run_tests native -e class juloo.keyboard2.input.RimeNativeTest,juloo.keyboard2.MiancakPlatformTest,juloo.keyboard2.input.CandidatePresentationTest,juloo.keyboard2.PageCustomizationTest,juloo.keyboard2.LayoutRuntimeAuditTest,juloo.keyboard2.PointerTraceTest,juloo.keyboard2.LayoutPresentationTest
run_tests interactions -e class juloo.keyboard2.ImeInteractionTest
run_tests layout-files -e class juloo.keyboard2.PageFilesUiTest
adb shell am force-stop org.miancak.keyboard.debug
run_tests restart -e class juloo.keyboard2.ImeInteractionTest -e restartOnly true
