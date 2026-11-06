#!/usr/bin/env bash
# Store captures Dixi (DMC-426): home, history, settings, dark theme, seeded neutral text.
#   emu-run phone --wait-lock 120 -- bash scripts/emu-store-screens.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle
OUT=${1:-store-screens}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
open() { $ADB shell am start -S -W -n $PKG/$NS.MainActivity "$@" >/dev/null; sleep 6; }
for _ in $(seq 1 60); do
  [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && [ "$($ADB shell getprop sys.user.0.ce_available | tr -d '\r')" = true ] && break
  sleep 5
done
$ADB install -r -g "$APK" || exit 1
$ADB shell pm clear $PKG >/dev/null
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put system screen_off_timeout 600000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell locksettings set-disabled true
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
sleep 30
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
$ADB shell cmd uimode night yes >/dev/null; sleep 2
open --es screen History --ei seedHistory 8; sleep 8
open --es screen Trial; shot 04-home
open --es screen History; shot 05-history
open --es screen Settings; shot 06-settings
$ADB logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
echo done
