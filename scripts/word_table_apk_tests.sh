#!/usr/bin/env bash
set -euo pipefail
mkdir -p test-results word-test-data
collect() {
  timeout 15s adb logcat -d > test-results/logcat.txt || true
  timeout 15s adb pull /sdcard/Android/data/org.miancak.keyboard.debug/files/test-results test-results/device || true
}
trap collect EXIT
sha256sum artifacts/app-debug.apk artifacts/app-debug-androidTest.apk artifacts/word-baseline.apk > test-results/actual-apk-sha256.txt
python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile('artifacts/app-debug.apk') as apk:
    Path('word-test-data/pinyin_simp.dict.yaml').write_bytes(apk.read('assets/rime/pinyin_simp.dict.yaml'))
with zipfile.ZipFile('artifacts/app-debug-androidTest.apk') as apk:
    for name in ['8105.dict.yaml','others.dict.yaml','miancak_addon.dict.yaml']:
        Path('word-test-data',name).write_bytes(apk.read('assets/word-tables/'+name))
PY
adb install --no-incremental artifacts/word-baseline.apk
adb install --no-incremental artifacts/app-debug-androidTest.apk
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put secure show_ime_with_hard_keyboard 1
if [[ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 35 ]]; then
  adb shell settings put system font_scale 1.3
  adb shell cmd locale set-app-locales org.miancak.keyboard.debug --locales zh-CN
fi
adb shell mkdir -p /sdcard/Download
for file in word-test-data/*.dict.yaml; do adb push "$file" /sdcard/Download/; done
run_test() {
  local name="$1"
  shift
  timeout 360s adb shell am instrument -w "$@" org.miancak.keyboard.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "test-results/$name.log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "test-results/$name.log"; then exit 1; fi
  grep -Eq 'OK \(' "test-results/$name.log"
}
record_learning() {
  python3 - "$1" <<'PY'
from pathlib import Path
import hashlib,io,json,subprocess,sys,tarfile
data=subprocess.check_output(['adb','exec-out','run-as','org.miancak.keyboard.debug','tar','-cf','-','files/rime/user'])
files={}
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    for item in archive:
        if item.isfile() and any('.userdb' in part for part in Path(item.name).parts):
            files[item.name]={'bytes':item.size,'sha256':hashlib.sha256(archive.extractfile(item).read()).hexdigest()}
assert files and any(value['bytes']>0 for value in files.values()),'Expected real persisted learning database'
Path('test-results','learning-'+sys.argv[1]+'.json').write_text(json.dumps(files,sort_keys=True,indent=2)+'\n')
PY
}
run_test word-native -e class juloo.keyboard2.input.WordTableNativeTest
adb shell ime enable org.miancak.keyboard.debug/juloo.keyboard2.Keyboard2
run_test word-prepare -e class juloo.keyboard2.WordTableUiTest -e wordPhase prepare
adb shell am force-stop org.miancak.keyboard.debug
record_learning before-install
adb install --no-incremental -r artifacts/app-debug.apk
record_learning after-install
cmp test-results/learning-before-install.json test-results/learning-after-install.json
run_test word-verify -e class juloo.keyboard2.WordTableUiTest -e wordPhase verify
adb shell am force-stop org.miancak.keyboard.debug
record_learning before-reboot
timeout 30s adb reboot
timeout 90s adb wait-for-device
for attempt in $(seq 1 60); do
  if [[ "$(timeout 5s adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then break; fi
  sleep 1
done
test "$(timeout 5s adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell input keyevent KEYCODE_WAKEUP
if adb shell wm help 2>&1 | grep -q dismiss-keyguard; then adb shell wm dismiss-keyguard; else adb shell input keyevent 82; fi
record_learning after-reboot
cmp test-results/learning-before-reboot.json test-results/learning-after-reboot.json
run_test word-reboot -e class juloo.keyboard2.WordTableUiTest -e wordPhase reboot
