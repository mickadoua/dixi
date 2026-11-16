#!/usr/bin/env bash
# Emulator proof for the floating bubble: 4 states over Contacts,
# tap vs drag, snap to the side, place after restart and rotation, hide zone,
# show again from the notification.
# Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/bubble-proof.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId
OUT=${1:-bubble-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s VoxbulleProof; }
# Center "x y" from the last log line matching $1 ("... at x=X y=Y").
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
count() { log | grep -c "$1"; }
state() { $ADB shell am broadcast -p $PKG -a $NS.debug.SET_BUBBLE_STATE --es state "$1" --el readyMs "${2:-1500}" >/dev/null; }
contacts() { $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4; }
show_bubble() { $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null; sleep 3; }
close_dialogs() { $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }
wait_log() { for _ in $(seq 1 "${2:-10}"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }

$ADB shell getprop ro.build.version.sdk
$ADB shell wm size; $ADB shell wm density
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put system screen_off_timeout 600000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 0
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
# Contacts asks for its permissions in a window that hides every overlay
# (Android rule): grant them all first.
for p in $($ADB shell dumpsys package com.google.android.contacts | sed -n 's/^ *\(android\.permission\.[A-Z_]*\): granted=false.*/\1/p' | sort -u); do
  $ADB shell pm grant com.google.android.contacts "$p" 2>/dev/null
done
# A cold boot on a loaded host can show "System UI isn't responding": let it settle.
sleep 25
close_dialogs
$ADB logcat -c

echo "--- 1. Bubble over Contacts, 4 states"
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
contacts
close_dialogs; sleep 1
XY=$(center "bubble shown")
echo "bubble center: $XY"
[ -n "$XY" ] || { $ADB logcat -d > "$OUT/logcat-full.txt"; echo "bubble not shown"; exit 2; }
$ADB shell dumpsys activity activities | grep -m1 -E 'topResumedActivity' | tee "$OUT/top-activity.txt"
check "Contacts editor in front, no permission window" "! grep -q RequestPermissions '$OUT/top-activity.txt'"
shot 1-idle
state Listening; sleep 1; shot 2-listening
state Processing; sleep 1; shot 3-processing
# « Texte prêt » goes back to « repos » after 1.5 s, faster than a screenshot
# here: hold it 6 s for the capture (the 1.5 s itself is a unit test).
state Ready 6000; sleep 1; shot 4-ready
sleep 6; shot 5-back-to-idle
for _ in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml | grep -q dumped && break; sleep 3; done
$ADB shell cat /sdcard/ui.xml > "$OUT/ui-idle.xml"
grep -o 'content-desc="Dixi[^"]*"' "$OUT/ui-idle.xml" | head -3

echo "--- 2. Focus and touches stay with the app below"
$ADB shell dumpsys window | grep -E 'mCurrentFocus' | tee "$OUT/focus.txt"
check "focus is not the bubble" "! grep -q $PKG '$OUT/focus.txt'"

echo "--- 3. Tap starts, drag never taps"
TAPS=$(count "bubble tapped")
$ADB shell input tap $XY
wait_log "recording started" 10
check "tap starts a dictation" "[ \$(count 'bubble tapped') -gt $TAPS ]"
wait_log "copied notification posted" 20; sleep 3
read -r X Y <<< "$XY"
TAPS=$(count "bubble tapped")
$ADB shell input swipe "$X" "$Y" 300 900 600
wait_log "bubble snapped" 10
sleep 1
check "drag does not tap" "[ \$(count 'bubble tapped') -eq $TAPS ]"
log | grep "bubble snapped" | tail -1 | tee "$OUT/snap.txt"
check "snapped to the left side" "grep -q 'side=Left' '$OUT/snap.txt'"
SNAP=$(center "bubble snapped")
echo "snapped center: $SNAP"
shot 6-after-drag-left

echo "--- 4. Same place after the service and the app restart"
$ADB shell am force-stop $PKG; sleep 2
$ADB logcat -c
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
contacts
AGAIN=$(center "bubble shown")
echo "after restart: $AGAIN"
check "same place after restart ($SNAP = $AGAIN)" "[ '$SNAP' = '$AGAIN' ]"
shot 7-after-restart

echo "--- 5. Rotation"
$ADB shell settings put system user_rotation 1; sleep 5
LAND=$(center "after configuration change")
echo "landscape: $LAND"
shot 8-landscape
$ADB shell settings put system user_rotation 0; sleep 5
PORT=$(center "after configuration change")
echo "portrait again: $PORT"
check "same place after rotating back ($SNAP = $PORT)" "[ '$SNAP' = '$PORT' ]"
shot 9-portrait-again

echo "--- 6. Hide zone"
read -r X Y <<< "$SNAP"
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
$ADB shell input motionevent DOWN "$X" "$Y"
for i in 1 2 3 4 5 6 7 8; do
  $ADB shell input motionevent MOVE $((X + (W / 2 - X) * i / 16)) $((Y + (H * 85 / 100 - Y) * i / 16))
done
shot 10-hide-zone-shown
for i in $(seq 9 16); do
  $ADB shell input motionevent MOVE $((X + (W / 2 - X) * i / 16)) $((Y + (H * 85 / 100 - Y) * i / 16))
done
# Hide zone center: 32 dp + 48 dp above the navigation bar.
for d in 0 20 40 60 80; do
  $ADB shell input motionevent MOVE $((W / 2)) $((H - 220 - d))
done
shot 11-over-hide-zone
$ADB shell input motionevent UP $((W / 2)) $((H - 300))
wait_log "bubble hidden" 5
check "drop on the cross hides the bubble" "log | grep -q 'bubble hidden'"
sleep 2; shot 12-hidden
$ADB shell dumpsys notification --noredact | grep -A2 "pkg=$PKG" > "$OUT/notifications.txt"

echo "--- 7. Show again from the notification"
$ADB logcat -c
$ADB shell cmd statusbar expand-notifications; sleep 4
shot 13-hidden-notification
for _ in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui2.xml | grep -q dumped && break; sleep 3; done
$ADB shell cat /sdcard/ui2.xml > "$OUT/ui-shade.xml"
NXY=$(python3 - "$OUT/ui-shade.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'Bulle masquée' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
echo "notification at: $NXY"
[ -n "$NXY" ] && $ADB shell input tap $NXY
wait_log "bubble shown" 10
BACK=$(center "bubble shown")
check "notification shows the bubble again at its place ($SNAP = $BACK)" "[ '$SNAP' = '$BACK' ]"
$ADB shell cmd statusbar collapse; sleep 3
shot 14-shown-again

$ADB shell settings put system accelerometer_rotation 1
$ADB logcat -d -s VoxbulleProof:V ActivityManager:W > "$OUT/logcat.txt"
echo "--- VoxbulleProof log"; grep VoxbulleProof "$OUT/logcat.txt" | tail -30
