#!/usr/bin/env bash
# Emulator proof of the recording (DMC-136): start from the bubble over
# Contacts, notification, manual stop, « Arrêter » action, 5 min limit
# (shortened by the debug receiver), nothing heard, microphone denied.
#   /opt/android/admin/emu/emu-run phone -- bash scripts/emu-proof-recording.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
OUT=${1:-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; }
log() { $ADB logcat -d -s VoxbulleProof; }
mark() { echo "=== $*"; $ADB shell "log -t VoxbulleProof '=== $*'"; }
# Log lines since the last mark only, so each step waits for its own lines.
since_mark() { log | python3 -c 'import sys; s = sys.stdin.read(); print(s[s.rfind("=== "):])'; }
wait_log() { for i in $(seq 1 "$2"); do since_mark | grep -q "$1" && return 0; sleep 1; done; echo "TIMEOUT waiting for: $1"; return 1; }
close_dialogs() { $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }
cache() { echo "cacheDir: [$($ADB shell run-as $PKG ls cache | tr '\n' ' ')]"; }
limits() { $ADB shell am broadcast -n $PKG/.recording.DebugRecordingReceiver "$@" >/dev/null; }
bubble_xy() { log | sed -n 's/.*bubble shown at x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
tap_bubble() { close_dialogs; sleep 1; $ADB shell input tap $(bubble_xy); }
# The loaded emulator can drop a tap (shade still closing): tap until the log shows $1.
tap_until() { for i in 1 2 3; do tap_bubble; wait_log "$1" 8 && return 0; done; return 1; }
# Back to the idle bubble: the "text ready" state ignores taps for 1.5 s.
wait_idle() { wait_log "copied notification posted\|recording error" 20; sleep 4; }
notif_dump() { $ADB shell dumpsys notification --noredact | grep -A40 "pkg=$PKG" | grep -E "NotificationRecord|channel=|importance|actions=|\[0\]|android.title=|android.text=|flags=" > "$OUT/$1.txt"; cat "$OUT/$1.txt"; }
open_contacts() {
  $ADB shell am start -W -n $PKG/.MainActivity --ez showBubble true >/dev/null; sleep 3
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4
  $ADB shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity'
}

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put system screen_off_timeout 600000
$ADB shell settings put global hide_error_dialogs 1
# Contacts asks for its own permissions on first launch, over the bubble: grant them first.
for p in $($ADB shell pm list packages | sed -n 's/^package:\(.*contacts.*\)/\1/p' | tr -d '\r'); do
  for perm in READ_CONTACTS WRITE_CONTACTS GET_ACCOUNTS POST_NOTIFICATIONS READ_PHONE_STATE CALL_PHONE; do
    $ADB shell pm grant "$p" android.permission.$perm 2>/dev/null
  done
done
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
sleep 25  # cold boot on a loaded NAS: let System UI settle
close_dialogs
$ADB logcat -c
open_contacts | tee "$OUT/top-activity.txt"
[ -n "$(bubble_xy)" ] || { $ADB logcat -d > "$OUT/logcat-full.txt"; echo "bubble not shown"; exit 2; }
shot 0-bubble-over-contacts

mark "A nothing heard (default limits, silent emulator mic)"
tap_until "recording started"; sleep 3; tap_until "bubble tapped in state Listening"
wait_log "recording error NothingHeard" 10; sleep 1; shot A-nothing-heard-toast; cache

# The emulator mic only gives silence: accept it, and shorten the limit to 15 s.
limits --el warn_ms 10000 --el max_ms 15000 --ei min_amplitude 0
mark "B manual stop"
wait_idle
tap_until "startForeground(microphone) OK"; sleep 4
shot B1-listening
$ADB shell dumpsys activity services $PKG | grep -E 'ServiceRecord|isForeground|foregroundServiceType' | tee "$OUT/B-fgs.txt"
notif_dump B-notification
$ADB shell cmd statusbar expand-notifications; sleep 4; shot B2-notification; $ADB shell cmd statusbar collapse; sleep 4
tap_until "bubble tapped in state Listening"; wait_log "clipboard set" 15; sleep 1; cache

mark "C stop from the notification action"
wait_idle
tap_until "startForeground(microphone) OK"; sleep 3
$ADB shell cmd statusbar expand-notifications; sleep 4
for i in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml | grep -q dumped && break; sleep 3; done
$ADB shell cat /sdcard/ui.xml > "$OUT/C-shade.xml"
XY=$(python3 - "$OUT/C-shade.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if re.search(r'(text|content-desc)="(ARRÊTER|Arrêter)"', node):
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
echo "stop action at: $XY"
shot C1-shade-with-stop
[ -n "$XY" ] && $ADB shell input tap $XY
wait_log "stop action from notification" 10; wait_log "clipboard set" 15
$ADB shell cmd statusbar collapse; sleep 2; cache

mark "D limit (shortened to 15 s, warning at 10 s)"
wait_idle
tap_until "recording started"
wait_log "recording warning" 20; sleep 1
notif_dump D-notification-warning
$ADB shell cmd statusbar expand-notifications; sleep 3; shot D1-warning; $ADB shell cmd statusbar collapse
wait_log "recording limit reached" 15; wait_log "clipboard set" 15; sleep 1
shot D2-after-limit; cache
notif_dump D-notifications-after

mark "E microphone denied"
$ADB shell pm revoke $PKG android.permission.RECORD_AUDIO  # kills the process
sleep 2
open_contacts >/dev/null
tap_until "recording refused"; sleep 1; shot E-mic-denied-toast
$ADB shell pm grant $PKG android.permission.RECORD_AUDIO

$ADB logcat -d -s VoxbulleProof:V ActivityManager:W AppOps:W > "$OUT/logcat.txt"
$ADB logcat -d | grep -iE 'voxbulle|ForegroundService|while-in-use|silenc' > "$OUT/logcat-grep.txt"
echo "--- VoxbulleProof log"; grep -E "VoxbulleProof" "$OUT/logcat.txt" | sed 's/^.*VoxbulleProof: //'
