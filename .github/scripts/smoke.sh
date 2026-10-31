#!/usr/bin/env bash
# Installs the APK, starts ClassBoard, exercises the ribbon and popovers and fails on a crash.
set -u
APK="$1"
PKG=com.classboard.app
mkdir -p shots
adb install -r "$APK"
adb logcat -c
# Start the app the way the launcher icon / installer "Open" button does.
echo "Launcher entry: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG | tail -1)"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG | grep -q "$PKG/" \
  || { echo "::error::No launcher entry for $PKG"; exit 1; }
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 6
size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${size%x*}; H=${size#*x}
echo "screen ${W}x${H}"
shot() { adb exec-out screencap -p > "shots/$1.png" || true; }
check() {
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "::error::ClassBoard crashed after: $1"
    adb logcat -d | grep -A60 "FATAL EXCEPTION" | head -150
    shot "crash"
    exit 1
  fi
  if ! adb shell pidof $PKG >/dev/null; then
    echo "(app closed after: $1 - restarting)"
    adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
  fi
}
check "launch"
shot 01-start
# Draw a stroke in the middle of the board.
adb shell input swipe $((W/4)) $((H/3)) $((W*3/4)) $((H/2)) 400
sleep 1; check "drawing"; shot 02-stroke
# Ribbon buttons along the bottom edge: tap across the left half (More, Pen, Eraser, Select ...).
RY=$((H - H/40 - 20))
for x in 40 120 200 280 360 440 520 600 680; do
  px=$((x * W / 1280))
  adb shell input tap $px $RY
  sleep 1
  check "ribbon tap at $px"
  shot "03-ribbon-$x"
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  check "back after $px"
done
# Open MORE and tap every tile region of the popover.
adb shell input tap $((40 * W / 1280)) $RY
sleep 1
shot 04-more
for row in 1 2 3 4 5; do
  for col in 1 2 3 4; do
    adb shell input tap $((col * W / 1280 * 100)) $((H - H/10 - row * H / 11))
    sleep 1
    check "more tile r$row c$col"
    adb shell input keyevent KEYCODE_BACK; sleep 0.5
    adb shell input keyevent KEYCODE_BACK; sleep 0.5
    adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 1
    adb shell input tap $((40 * W / 1280)) $RY; sleep 1
  done
done
shot 05-end
check "end"
echo "Crash check:"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d | grep -A40 "FATAL EXCEPTION" | head -120
  exit 1
fi
echo "Smoke test passed"
