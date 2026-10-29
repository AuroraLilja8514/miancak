# Sourced by APK drivers after their whole-script watchdog has started.
# Per-command bounds also cover commands used in collectors and pipelines.
MIANCAK_ADB_HELPER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MIANCAK_PROGRESS_PID=""
start_progress() {
  if [[ "${MIANCAK_PROGRESS:-1}" == 0 ]]; then return; fi
  # Separate watchdog shares the outer deadline; cancellation reaches logcat.
  python3 "$MIANCAK_ADB_HELPER_DIR/run_with_timeout.py" -- adb logcat -v brief \
    'MIANCAK-Acceptance:I' 'MIANCAK-Focus:I' 'MIANCAK-WordTables:I' '*:S' >&2 &
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
