#!/usr/bin/env bash
# The Servers sheet's on-device test (V2rayNG/app/src/androidTest/.../ServersSheetTest.kt), run by the workflow's `ui-test` job
# inside the emulator action:
#
#   ci/sheet-test.sh <width>x<height> <density>
#
# It gives the emulator the screen it is told (the phone the fault was found on: 1080x2340 at 450 dpi, three buttons at the
# bottom), installs the debug app and its test, runs the test with `am instrument` (not Gradle's connected task, which removes
# the app — and with it what the test wrote — when it ends), and pulls the test's notes and screenshots out of the app's own
# storage into $RUNNER_TEMP/sheet-test, where the job's next steps read them. Exit 0 only when every test passed.
set -uo pipefail
SIZE="$1"; DENSITY="$2"; PKG=com.allion.skyray
OUT="${RUNNER_TEMP:-/tmp}/sheet-test"; rm -rf "$OUT"; mkdir -p "$OUT"
{
  adb shell wm size "$SIZE"
  adb shell wm density "$DENSITY"
  # three buttons at the bottom, as on the phone the fault was found on: the tallest navigation bar
  adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  sleep 10
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
  echo "the emulator: Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')); $(adb shell wm size | tr '\r\n' '  '); $(adb shell wm density | tr '\r\n' '  '); navigation_mode $(adb shell settings get secure navigation_mode | tr -d '\r') (0 = three buttons)"
} > "$OUT/0-device.txt" 2>&1
APK=$(ls V2rayNG/app/build/outputs/apk/direct/debug/*.apk | head -1)
TEST=$(ls V2rayNG/app/build/outputs/apk/androidTest/direct/debug/*.apk | head -1)
{
  echo "app: $(basename "$APK"); test: $(basename "$TEST")"
  adb install -r -t "$APK"
  adb install -r -t "$TEST"
} >> "$OUT/0-device.txt" 2>&1
adb shell am instrument -w -e class com.v2ray.ang.ServersSheetTest "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > "$OUT/1-instrument.txt" 2>&1
cat "$OUT/0-device.txt" "$OUT/1-instrument.txt"
# what the test wrote, out of the app's own storage (a debug build: run-as)
for f in $(adb shell run-as "$PKG" ls files/sheet-test 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/sheet-test/$f" > "$OUT/$f"
done
ls -la "$OUT"
grep -q '^OK (' "$OUT/1-instrument.txt" && ! grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS: Error' "$OUT/1-instrument.txt"
