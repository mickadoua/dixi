#!/usr/bin/env bash
# Emulator proof for the bubble review fixes: a cancelled drag over
# the cross snaps and never hides; showing the bubble without the overlay
# permission stops the service without a crash.
# Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/bubble-review-proof.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId
OUT=${1:-bubble-review-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s VoxbulleProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
# Touches injected while the display is off or locked are dropped: wake and unlock first.
wake() {
  $ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard; sleep 1
  $ADB shell dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | head -1
}
show_bubble() { wake; $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null; wait_log "bubble shown" 15; sleep 2; }
wait_log() { for _ in $(seq 1 "${2:-10}"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
screen_size() { read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"; }
# Drags the bubble from its center to over the cross (W/2, H-300), finger still
# down. One adb shell for the whole gesture: no adb round trip between events.
drag_over_cross() {
  read -r X Y <<< "$(center "bubble shown\|bubble snapped")"
  [ -n "${Y:-}" ] || { echo "no bubble position in the log"; return 1; }
  screen_size
  local tx=$((W / 2)) ty=$((H - 300)) cmd i
  cmd="input motionevent DOWN $X $Y"
  for i in 1 2 3 4 5 6; do cmd="$cmd; input motionevent MOVE $((X + (tx - X) * i / 6)) $((Y + (ty - Y) * i / 6))"; done
  $ADB shell "$cmd; input motionevent MOVE $tx $((ty + 10)); input motionevent MOVE $tx $ty"
}
# visible / invisible / unknown: the hide zone (largest overlay window of the
# app) shows only once the bubble is really dragged.
hide_zone_state() {
  $ADB shell dumpsys window windows > "$OUT/windows.txt"
  python3 - "$OUT/windows.txt" "$PKG" <<'PY'
import re, sys
text, pkg = open(sys.argv[1], errors="replace").read(), sys.argv[2]
best = None
for block in re.split(r"\n\s*Window #\d+ ", text):
    if pkg not in block.split("\n", 1)[0] or "APPLICATION_OVERLAY" not in block:
        continue
    size = re.search(r"mAttrs=\{\(-?\d+,-?\d+\)\((\d+)x(\d+)\)", block)
    vis = re.search(r"mViewVisibility=0x([0-9a-f]+)", block)
    if size and vis and (best is None or int(size.group(1)) > best[0]):
        best = (int(size.group(1)), vis.group(1))
print("unknown" if best is None else ("visible" if best[1] == "0" else "invisible"))
PY
}
# Drags over the cross; retries (up to 3 times) only when the bubble was not
# grabbed at all, i.e. the hide zone never showed. $1: event that ends a failed try.
grab_over_cross() {
  local attempt state
  for attempt in 1 2 3; do
    wake
    drag_over_cross || return 1
    state=$(hide_zone_state)
    echo "grab attempt $attempt: hide zone $state"
    [ "$state" = invisible ] || return 0
    $ADB shell input motionevent "$1" "$((W / 2))" "$((H - 300))"; sleep 3
  done
  return 1
}

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put global hide_error_dialogs 1
# Keep the display on and the keyguard off for the whole proof.
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
wake
sleep 25
$ADB logcat -c

echo "--- 1. Cancelled drag over the cross snaps"
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
grab_over_cross CANCEL
shot 1-over-cross
$ADB shell input motionevent CANCEL $((W / 2)) $((H - 300))
wait_log "bubble snapped" 5
sleep 2; shot 2-after-cancel
check "cancel over the cross snaps" "log | grep -q 'bubble snapped'"
check "cancel over the cross does not hide" "! log | grep -q 'bubble hidden'"

log > "$OUT/logcat-part1.txt"; tail -8 "$OUT/logcat-part1.txt"
$ADB shell am force-stop $PKG; sleep 2
echo "--- 2. Notification tap without the overlay permission (review fix)"
$ADB logcat -c
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
grab_over_cross UP
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
