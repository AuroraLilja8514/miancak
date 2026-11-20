# Sourced by APK drivers after their whole-script watchdog has started.
# Per-command bounds also cover commands used in collectors and pipelines.
MIANCAK_ADB_HELPER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MIANCAK_PROGRESS_PID=""
start_progress() {
  if [[ "${MIANCAK_PROGRESS:-1}" == 0 ]]; then return; fi
  # Separate watchdog shares the outer deadline; cancellation reaches logcat.
  python3 "$MIANCAK_ADB_HELPER_DIR/run_with_timeout.py" -- adb logcat -v brief \
    'MIANCAK-Acceptance:I' 'MIANCAK-Focus:I' 'MIANCAK-WordTables:I' '*:S' >> test-results/live-progress-local.log 2>&1 &
  MIANCAK_PROGRESS_PID=$!
}
stop_progress() {
  if [[ -n "$MIANCAK_PROGRESS_PID" ]]; then
    if jobs -pr | grep -Fxq "$MIANCAK_PROGRESS_PID"; then
      kill -TERM "$MIANCAK_PROGRESS_PID" 2>/dev/null || true
    fi
    wait "$MIANCAK_PROGRESS_PID" 2>/dev/null || true
    MIANCAK_PROGRESS_PID=""
  fi
}
adb() {
  local seconds="${MIANCAK_ADB_TIMEOUT_SECONDS:-30}"
  case "${1:-}" in
    install|install-multiple) seconds="${MIANCAK_ADB_INSTALL_TIMEOUT_SECONDS:-180}" ;;
    push|pull) seconds="${MIANCAK_ADB_TRANSFER_TIMEOUT_SECONDS:-120}" ;;
    wait-for-device) seconds="${MIANCAK_ADB_BOOT_TIMEOUT_SECONDS:-120}" ;;
    shell)
      if [[ "${2:-}" == am && "${3:-}" == instrument ]]; then
        seconds="${MIANCAK_INSTRUMENT_TIMEOUT_SECONDS:-900}"
      fi ;;
  esac
  command timeout --foreground --signal=TERM --kill-after=5s "${seconds}s" adb "$@"
}

# Refuse unrelated/pre-existing app installs before collectors or mutation.
# CI independently creates the fresh wiped AVD; this never clears user data.
verify_test_target() {
  local serial
  serial="$(python3 "$MIANCAK_ADB_HELPER_DIR/public_test_evidence.py" target --scenario "$1")" || return
  export ANDROID_SERIAL="$serial"
  MIANCAK_VERIFIED_TEST_SERIAL="$serial"
}

# Public resource inputs only. Keep them out of APK/test evidence artifacts.
# Call after verify_test_target and before any app setup uses the files.
stage_wanxiang_test_assets() {
  test -n "${MIANCAK_VERIFIED_TEST_SERIAL:-}" && test "$MIANCAK_VERIFIED_TEST_SERIAL" = "$ANDROID_SERIAL" || return 1
  local fixture_dir="${MIANCAK_WANXIANG_FIXTURE_DIR:-.native/wanxiang-downloads}" mode
  mode="$(python3 - "$MIANCAK_ADB_HELPER_DIR" "$1" "$fixture_dir" <<'PY'
from pathlib import Path
import json, sys, zipfile
scripts, apk, fixtures = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
sys.path.insert(0, str(scripts))
from prepare_wanxiang import LOCK, sha256
with zipfile.ZipFile(apk) as package:
    if 'assets/rime/delivery.json' not in package.namelist():
        print('bundled')
        raise SystemExit(0)
lock = json.loads(LOCK.read_text())
for role, name in [('base', 'base-' + lock['base']['version'] + '.zip'), ('model', lock['model']['filename'])]:
    item = fixtures / name
    expected = lock[role]
    if not item.is_file() or item.stat().st_size != expected['bytes'] or sha256(item) != expected['sha256']:
        raise SystemExit('Missing or invalid pinned Wanxiang ' + role + ' fixture. Supply MIANCAK_WANXIANG_FIXTURE_DIR from an approved source.')
print('split')
PY
)" || return 1
  MIANCAK_TEST_SPLIT_RESOURCES="$mode"
  if [[ "$mode" == split ]]; then
    adb shell mkdir -p /sdcard/Download
    adb push "$fixture_dir/base-v18.1.13.zip" /sdcard/Download/miancak-fixture-base.zip
    adb push "$fixture_dir/wanxiang-lts-zh-hans.gram" /sdcard/Download/miancak-fixture-grammar.gram
    adb shell 'printf "invalid fixture\n" > /sdcard/Download/miancak-fixture-corrupt.gram'
  fi
}

install_wanxiang_test_assets() {
  if [[ "${MIANCAK_TEST_SPLIT_RESOURCES:-}" != split ]]; then return; fi
  adb shell am instrument -w -e class juloo.keyboard2.WanxiangSetupTest \
    org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee test-results/wanxiang-setup.log
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' test-results/wanxiang-setup.log; then return 1; fi
  grep -Eq 'OK \(' test-results/wanxiang-setup.log
}
