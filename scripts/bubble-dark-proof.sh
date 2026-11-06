#!/usr/bin/env bash
# Emulator proof for the design fixes of DMC-164: the « Masquer » zone at rest
# and with the bubble over it (72 dp), and the listening halo, in light then
# dark mode, over Contacts. Run through emu-run:
#   /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/bubble-dark-proof.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId (DMC-383)
OUT=${1:-bubble-dark-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s VoxbulleProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
state() { $ADB shell am broadcast -p $PKG -a $NS.debug.SET_BUBBLE_STATE --es state "$1" --el readyMs 1500 >/dev/null; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }

$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 0
wake; $ADB shell wm dismiss-keyguard
for p in $($ADB shell dumpsys package com.google.android.contacts | sed -n 's/^ *\(android\.permission\.[A-Z_]*\): granted=false.*/\1/p' | sort -u); do
  $ADB shell pm grant com.google.android.contacts "$p" 2>/dev/null
done
sleep 20
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"

# $1 = yes|no, $2 = dark|light
run() {
  echo "--- $2 mode"
  # Switching the night mode restarts SystemUI here: until it is back, the
  # display shows nothing (black screenshots). Wait, then wake and unlock.
  $ADB shell cmd uimode night "$1"; sleep 35
  wake; $ADB shell wm dismiss-keyguard; sleep 2
  $ADB shell am force-stop $PKG; sleep 2
  $ADB logcat -c
  $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null; sleep 3
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4
  $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; sleep 1
  XY=$(center "bubble shown")
  echo "bubble center: $XY"
  [ -n "$XY" ] || { echo "FAIL bubble not shown ($2)"; return; }
  read -r X Y <<< "$XY"
  wake; sleep 1
  $ADB shell dumpsys window windows | grep -E "Window #|mViewVisibility|mHasSurface|isOnScreen|mCurrentFocus" > "$OUT/$2-windows.txt"
  $ADB shell dumpsys power | grep -E "mWakefulness=|mHoldingDisplaySuspendBlocker" >> "$OUT/$2-windows.txt"
  state Listening; sleep 1; shot "$2-1-listening-halo"
  state Idle; sleep 1
  # One gesture per adb shell: drag halfway (zone at rest), hold, shoot.
  wake
  $ADB shell "input motionevent DOWN $X $Y; for i in 1 2 3 4 5 6 7 8; do input motionevent MOVE \$(( $X + ($W / 2 - $X) * i / 16 )) \$(( $Y + ($H * 60 / 100 - $Y) * i / 16 )); done"
  sleep 1; shot "$2-2-hide-zone-rest"
  $ADB shell "for d in 0 40 80 120; do input motionevent MOVE $((W / 2)) \$(( $H - 120 - d )); done; input motionevent MOVE $((W / 2)) $((H - 240)); input motionevent MOVE $((W / 2)) $((H - 260))"
  sleep 1; shot "$2-3-over-hide-zone"
  # The zone window must not be capped at alpha 0.8 (non-touchable overlay).
  $ADB shell dumpsys window windows | grep -E "ty=APPLICATION_OVERLAY" | grep -oE "\([0-9]+x[0-9]+\)|alpha=[0-9.]+|fl=[A-Z_ ]+NOT_TOUCHABLE" | paste -sd' ' > "$OUT/$2-alpha.txt"
  cat "$OUT/$2-alpha.txt"
  $ADB shell input motionevent UP $((W / 2)) $((H - 260))
  sleep 2
  check "$2: drop on the cross hides the bubble" "log | grep -q 'bubble hidden'"
  $ADB logcat -d > "$OUT/$2-logcat.txt"
}
# Light first: just after a cold boot the emulator is still busy and the
# SystemUI restart of a night mode switch leaves the display black longer.
# $2 = modes to run, in order (default: light dark).
for m in ${2:-light dark}; do
  if [ "$m" = dark ]; then run yes dark; else run no light; fi
done
$ADB shell cmd uimode night no
