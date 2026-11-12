#!/usr/bin/env bash
set -uo pipefail
mkdir -p device-captures
adb install -r test-apks/debug/app-debug.apk || exit 1
adb install -r test-apks/androidTest/debug/app-debug-androidTest.apk || exit 1
adb logcat -c
adb shell am instrument -w -r io.github.rxiet.baiye.test/androidx.test.runner.AndroidJUnitRunner | tee device-captures/instrumentation.txt
adb logcat -d -v threadtime > device-captures/logcat.txt
adb shell dumpsys webviewupdate > device-captures/webview.txt
adb shell screencap -p /sdcard/baiye-final.png
adb pull /sdcard/baiye-final.png device-captures/ >/dev/null 2>&1 || true
adb pull /sdcard/Android/data/io.github.rxiet.baiye/files/screenshots device-captures/ >/dev/null 2>&1 || true
if grep -Eq '^OK \([0-9]+ tests?\)' device-captures/instrumentation.txt; then exit 0; fi
grep -A 70 'FATAL EXCEPTION' device-captures/logcat.txt || true
exit 1
