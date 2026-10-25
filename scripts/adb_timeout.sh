# Sourced by APK drivers after their whole-script watchdog has started.
# Per-command bounds also cover commands used in collectors and pipelines.
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
