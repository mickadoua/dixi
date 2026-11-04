#!/usr/bin/env bash
# Emulator proof for the DMC-162 review fixes (DMC-165): a cancelled drag over
# the cross snaps and never hides; showing the bubble without the overlay
# permission stops the service without a crash. Run through emu-run:
#   /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/bubble-review-proof.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
OUT=${1:-bubble-review-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s VoxbulleProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
show_bubble() { $ADB shell am start -W -n $PKG/.MainActivity --ez showBubble true >/dev/null; sleep 3; }
wait_log() { for _ in $(seq 1 "${2:-10}"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
# Drags the bubble from its center to over the cross, finger still down.
drag_over_cross() {
  read -r X Y <<< "$(center "bubble shown")"
  read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
  $ADB shell input motionevent DOWN "$X" "$Y"
  for i in $(seq 1 16); do
    $ADB shell input motionevent MOVE $((X + (W / 2 - X) * i / 16)) $((Y + (H * 85 / 100 - Y) * i / 16))
  done
  for d in 0 20 40 60 80; do $ADB shell input motionevent MOVE $((W / 2)) $((H - 220 - d)); done
}

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put global hide_error_dialogs 1
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
sleep 25
$ADB logcat -c

echo "--- 1. Cancelled drag over the cross snaps"
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
drag_over_cross
shot 1-over-cross
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
$ADB shell input motionevent CANCEL $((W / 2)) $((H - 300))
wait_log "bubble snapped" 5
sleep 2; shot 2-after-cancel
check "cancel over the cross snaps" "log | grep -q 'bubble snapped'"
check "cancel over the cross does not hide" "! log | grep -q 'bubble hidden'"

echo "--- 2. Notification tap without the overlay permission (review DMC-162)"
$ADB logcat -c
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
drag_over_cross
$ADB shell input motionevent UP $((W / 2)) $((H - 300))
wait_log "hidden notification posted" 5
check "drop on the cross hides the bubble" "log | grep -q 'bubble hidden'"
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW deny
$ADB logcat -c; $ADB logcat -c -b crash
$ADB shell cmd statusbar expand-notifications; sleep 4
for _ in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml | grep -q dumped && break; sleep 3; done
$ADB shell cat /sdcard/ui.xml > "$OUT/ui-shade.xml"
NXY=$(python3 - "$OUT/ui-shade.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'Bulle masquée' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
echo "notification at: $NXY"
shot 3-hidden-notification
[ -n "$NXY" ] && $ADB shell input tap $NXY
wait_log "overlay permission missing" 10
sleep 3
$ADB shell cmd statusbar collapse; sleep 2; shot 4-after-tap
check "service stops on missing permission" "log | grep -q 'overlay permission missing'"
check "bubble not shown" "! log | grep -q 'bubble shown'"
$ADB logcat -d -b crash > "$OUT/crash.txt"
check "no crash" "! grep -q $PKG '$OUT/crash.txt'"
$ADB shell dumpsys activity services $PKG > "$OUT/services.txt"
check "service stopped" "! grep -q 'BubbleService' '$OUT/services.txt'"
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB logcat -d -s VoxbulleProof:V AndroidRuntime:E > "$OUT/logcat.txt"
echo "--- VoxbulleProof log"; tail -20 "$OUT/logcat.txt"
