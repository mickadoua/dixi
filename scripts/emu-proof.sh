#!/usr/bin/env bash
# Emulator proof: bubble over another app, mic FGS started from the bubble,
# clipboard + notification.
# Needs adb ($ANDROID_HOME) and a debug APK: bash scripts/emu-proof.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId
OUT=${1:-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; }

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put system screen_off_timeout 600000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
# A cold boot on a loaded host can show "System UI isn't responding": let it settle.
sleep 25
close_dialogs() { $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }
close_dialogs
$ADB logcat -c
$ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true
sleep 3
# Another app in front. Not Settings: it hides third-party overlays on purpose.
$ADB shell pm list packages | grep -E 'chrome|contacts|dialer|messaging|launcher' | tee "$OUT/packages.txt"
$ADB shell input keyevent KEYCODE_HOME; sleep 2
shot 0-bubble-over-launcher
$ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact
sleep 4
$ADB shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|ResumedActivity' | tee "$OUT/top-activity.txt"
shot 1-bubble-over-other-app
XY=$($ADB logcat -d -s VoxbulleProof | sed -n 's/.*bubble shown at x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1)
echo "bubble center: $XY"
[ -n "$XY" ] || { $ADB logcat -d > "$OUT/logcat-full.txt"; echo "bubble not shown"; exit 2; }
close_dialogs; sleep 1
shot 1b-before-tap
$ADB shell input tap $XY
sleep 2
shot 2-listening
$ADB shell dumpsys activity services $PKG | grep -E 'isForeground|foregroundServiceType|ServiceRecord' | tee "$OUT/fgs.txt"
for i in $(seq 1 20); do $ADB logcat -d -s VoxbulleProof | grep -q 'copied notification posted' && break; sleep 1; done
$ADB shell cmd statusbar expand-notifications; sleep 6
shot 3-notification
$ADB shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" > "$OUT/notifications.txt"
$ADB shell cmd statusbar collapse; sleep 6
# Clipboard: paste into the other app's first text field.
for i in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml | grep -q dumped && break; sleep 3; done
$ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml"
XY2=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'class="android.widget.EditText"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
[ -n "$XY2" ] || XY2="540 1026"  # "First name" on pixel_7, seen on earlier runs
echo "text field: $XY2"
[ -n "$XY2" ] && $ADB shell input tap $XY2; sleep 2
$ADB shell input keyevent KEYCODE_PASTE; sleep 2
shot 4-pasted
for i in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui2.xml && break; sleep 3; done
$ADB shell cat /sdcard/ui2.xml > "$OUT/ui-pasted.xml"
grep -io 'texte de test Dixi' "$OUT/ui-pasted.xml" | head -1 | sed 's/^/PASTED: /'
$ADB shell dumpsys appops --package $PKG > "$OUT/appops.txt"
$ADB logcat -d -s VoxbulleProof:V ActivityManager:W AppOps:W > "$OUT/logcat.txt"
$ADB logcat -d | grep -iE 'voxbulle|ForegroundService|while-in-use|silenc' > "$OUT/logcat-grep.txt"
echo "--- VoxbulleProof log"; grep VoxbulleProof "$OUT/logcat.txt"
