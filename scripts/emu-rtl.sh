#!/usr/bin/env bash
# Emulator proof for a right-to-left language (Arabic by default): onboarding
# screens 1 and 2, then the bubble docked at the right edge, then at the left edge.
# The app language is set per app (`cmd locale`), Android's own screens stay in English.
# The bubble is placed from the left edge (Gravity.LEFT): docking must follow the
# finger, not the layout direction. Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/emu-rtl.sh <out-dir> [locale]
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
OUT=${1:-rtl-proof-out}
LOCALE=${2:-ar}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s DixiProof; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard; sleep 1; }
wait_log() { for _ in $(seq 1 "${2:-20}"); do log | grep -q "$1" && return 0; sleep 2; done; echo "no log line: $1"; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
# Just after a snapshot boot Android's own load is very high (48 measured on 17/11):
# the app hits an ANR, hide_error_dialogs kills it, and the drag lands on the launcher.
# Waits until the emulator's 1-minute load is under 4 (at most 3 minutes).
settle() {
  local l i
  for i in $(seq 1 36); do
    l=$($ADB shell cat /proc/loadavg | cut -d' ' -f1)
    [ "${l%%.*}" -lt 4 ] 2>/dev/null && { echo "emulator load $l"; return 0; }
    sleep 5
  done
  echo "emulator load still $l"
}
alive() { [ -n "$($ADB shell pidof $PKG)" ]; }
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
# Drags the bubble from its last logged center to x=$1 at the same height, then lifts.
# One adb shell for the whole gesture: no adb round trip between events.
drag_to_x() {
  local X Y cmd i
  read -r X Y <<< "$(center "bubble shown\|bubble snapped\|bubble undocked")"
  [ -n "${Y:-}" ] || { echo "no bubble position in the log"; return 1; }
  cmd="input motionevent DOWN $X $Y"
  for i in 1 2 3 4 5 6 7 8; do cmd="$cmd; input motionevent MOVE $((X + ($1 - X) * i / 8)) $Y"; done
  wake; $ADB shell "$cmd; input motionevent UP $1 $Y"
}

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell cmd locale set-app-locales $PKG --locales "$LOCALE"
$ADB shell cmd locale get-app-locales $PKG | tee "$OUT/app-locale.txt"
check "app locale is $LOCALE" "grep -q '$LOCALE' '$OUT/app-locale.txt'"
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
wake
$ADB logcat -c

settle
# Throwaway first launch: just after a boot the first screenshots come out black.
$ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 8
$ADB exec-out screencap -p > "$OUT/warmup.png"

echo "--- 1. Onboarding in $LOCALE"
check "screen 1 (welcome)" "wait_log 'onboarding step Welcome'"
sleep 2; shot 1-welcome
wake; $ADB shell input tap 540 2220  # lowest 56 dp button, centered: same point in RTL
check "screen 2 (microphone)" "wait_log 'onboarding step MicPermission'"
sleep 2; shot 2-mic
$ADB shell am force-stop $PKG; sleep 2

echo "--- 2. Bubble docked at each edge"
$ADB logcat -c
wake; $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null
check "bubble shown" "wait_log 'bubble shown' 15"
$ADB shell input keyevent KEYCODE_HOME; sleep 3
settle
check "app still running before the drag" "alive"
shot 3-bubble
drag_to_x $((W - 2))
check "docked at the right edge after a drag to the right" "wait_log 'bubble docked side=Right' 10"
sleep 2; shot 4-docked-right
# A tap on the half-bubble brings it back whole, at the right edge.
read -r _ DY <<< "$(log | sed -n 's/.*bubble docked side=Right at y=\([0-9]*\).*/x \1/p' | tail -1)"
wake; $ADB shell input tap $((W - 10)) "${DY:-1200}"
check "tap brings it back at the right edge" "wait_log 'bubble undocked side=Right' 10"
sleep 2; shot 5-undocked-right
$ADB logcat -d -s DixiProof > "$OUT/app-log-right.txt"
# Whole again at the right edge: drag it from there to the left edge.
$ADB shell "input motionevent DOWN $((W - 80)) ${DY:-1200}; input motionevent MOVE $((W / 2)) ${DY:-1200}; input motionevent MOVE 2 ${DY:-1200}; input motionevent UP 2 ${DY:-1200}"
check "docked at the left edge after a drag to the left" "wait_log 'bubble docked side=Left' 10"
sleep 2; shot 6-docked-left

$ADB logcat -d -b all > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
$ADB logcat -d -s DixiProof > "$OUT/app-log.txt"
$ADB shell cmd locale set-app-locales $PKG --locales ""
