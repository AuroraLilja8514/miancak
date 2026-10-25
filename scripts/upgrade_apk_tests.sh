#!/usr/bin/env bash

# Re-enter under a finite watchdog; nested drivers share the remaining budget.
MIANCAK_ENTRY="$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")"
if [ "${MIANCAK_TIMEOUT_SCRIPT:-}" != "$MIANCAK_ENTRY" ]; then
  exec python3 "$(dirname -- "$MIANCAK_ENTRY")/../scripts/run_with_timeout.py" --script "$MIANCAK_ENTRY" -- "$@"
fi
source "$(dirname -- "$MIANCAK_ENTRY")/adb_timeout.sh"
set -euo pipefail
mkdir -p test-results upgrade-signed
collect() {
  stop_progress
  adb devices -l > test-results/adb-devices.txt || true
  timeout --foreground 15s adb logcat -d > test-results/logcat.txt || true
  timeout --foreground 15s adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
}
trap collect EXIT
start_progress
sha256sum artifacts/app-debug.apk artifacts/app-debug-androidTest.apk artifacts/baseline.apk > test-results/original-apk-sha256.txt
# Copies share a fresh local test identity. No release key enters PR execution.
keytool -genkeypair -keystore upgrade-signed/test.p12 -storetype PKCS12 -storepass android \
  -keypass android -alias upgrade-test -keyalg RSA -dname 'CN=MIANCAK upgrade test' -validity 30
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
for name in baseline app-debug app-debug-androidTest; do
  "$apksigner" sign --ks upgrade-signed/test.p12 --ks-pass pass:android --ks-key-alias upgrade-test \
    --out "upgrade-signed/$name.apk" "artifacts/$name.apk"
  "$apksigner" verify "upgrade-signed/$name.apk"
done
sha256sum upgrade-signed/*.apk > test-results/upgrade-apk-sha256.txt
adb install --no-incremental upgrade-signed/baseline.apk
adb install --no-incremental upgrade-signed/app-debug-androidTest.apk
adb shell ime enable org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2
adb shell svc wifi disable
adb shell svc data disable
run_phase() {
  local phase="$1"
  echo "Running actual APK upgrade phase: $phase"
  timeout --foreground "${MIANCAK_INSTRUMENT_TIMEOUT_SECONDS:-900}s" adb shell am instrument -w -e class juloo.keyboard2.UpgradeApkTest -e upgradePhase "$phase" \
    org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/upgrade-$phase.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/upgrade-$phase.log"; then exit 1; fi
  grep -Eq 'OK \(' "test-results/upgrade-$phase.log"
}
record_ime_state() {
  local phase="$1"
  adb shell settings get secure default_input_method | tr -d '\r' > "test-results/default-ime-$phase.txt"
  grep -Fxq 'org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2' "test-results/default-ime-$phase.txt"
  adb shell settings get secure enabled_input_methods | tr -d '\r' > "test-results/enabled-ime-$phase.txt"
  python3 - "$phase" <<'PYCODE'
from pathlib import Path
import sys
methods = (Path('test-results') / ('enabled-ime-' + sys.argv[1] + '.txt')).read_text().strip().split(':')
assert 'org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2' in {value.split(';')[0] for value in methods}
PYCODE
}
run_phase prepare
# Restore the test precondition after instrumentation ends its target process.
adb shell am start -n org.miancak.keyboard.debug/juloo.keyboard2.LauncherActivity
adb shell ime set org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2
record_ime_state before-install
adb install --no-incremental -r upgrade-signed/app-debug.apk
# Observe retention before a new instrumentation can stop/rebind the IME.
record_ime_state after-install
cmp test-results/default-ime-before-install.txt test-results/default-ime-after-install.txt
run_phase verify
adb shell am start -n org.miancak.keyboard.debug/juloo.keyboard2.LauncherActivity
adb shell ime set org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2
adb shell input keyevent KEYCODE_HOME
record_ime_state before-reboot
echo 'Rebooting the guest after verified APK replacement'
timeout --foreground 30s adb reboot
timeout --foreground 90s adb wait-for-device
for attempt in $(seq 1 60); do
  if [[ "$(timeout --foreground 5s adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then break; fi
  sleep 1
done
test "$(timeout --foreground 5s adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell input keyevent KEYCODE_WAKEUP
if adb shell wm help 2>&1 | grep -q dismiss-keyguard; then
  adb shell wm dismiss-keyguard
else
  adb shell input keyevent 82
fi
record_ime_state after-reboot
cmp test-results/default-ime-before-reboot.txt test-results/default-ime-after-reboot.txt
run_phase reboot
