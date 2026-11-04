#!/usr/bin/env bash
# Emulator proof for the notification permission on screen 5 (DMC-264), given
# or refused in Android's own window (no `install -g`, no `pm grant` for it).
#   emu-run phone --wait-lock 120 -- bash scripts/emu-notifications.sh <out-dir>
# The microphone and overlay are set by adb (proved by scripts/emu-onboarding.sh):
# the journey goes 1 → 2 (« Plus tard ») → 4 → 5.
# allow:  window shown on screen 5, allowed; back to 4 and on to 5, then a
#         relaunch on 5: no second window.
# refuse: fresh install, window refused: « Notifications désactivées », and
#         « Terminer » still opens the history.
set -u
OUT=${1:-proof-notifications}
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
N=0
shot() { N=$((N + 1)); $ADB exec-out screencap -p > "$OUT/$(printf %02d $N)-$1.png"; echo "shot $(printf %02d $N)-$1"; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
dump() {
    rm -f "$OUT/ui.xml"
    for i in 1 2 3 4 5 6; do
        $ADB shell rm -f /sdcard/ui.xml
        local r
        r=$($ADB shell uiautomator dump /sdcard/ui.xml 2>&1 | tail -1)
        case "$r" in *dumped*) $ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml"; return 0 ;; esac
        echo "  dump failed: $r"
        sleep 2
    done
    : > "$OUT/ui.xml"
}
find_node() {
    python3 - "$OUT/ui.xml" "$@" <<'PY'
import re, sys
xml = open(sys.argv[1]).read()
nodes = re.findall(r'<node [^>]*>', xml)
for want in sys.argv[2:]:
    for node in nodes:
        if any(f'{a}="{want}"' in node for a in ('text', 'content-desc', 'resource-id')):
            a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((a + c) // 2, (b + d) // 2)
            sys.exit(0)
PY
}
tap_system() {
    local xy=""
    dump; xy=$(find_node "$@")
    echo "tap system '$1' at ${xy:-?}"
    [ -n "$xy" ] && { wake; $ADB shell input tap $xy; }
    sleep 3
}
focus() { $ADB shell dumpsys window | grep -o "mCurrentFocus=[^ ]* [^ ]* [^ }]*" | head -1; }
tap_at() { echo "tap $1 at $2 $3"; wake; $ADB shell input tap "$2" "$3"; sleep 2; echo "  $(focus)"; }
BOTTOM="540 2220"; ABOVE="540 2052"
MARK=start
mark() { MARK="$1-$RANDOM"; $ADB shell log -t VoxbulleProof "MARK $MARK"; }
applog() { $ADB logcat -d -s VoxbulleProof:I | sed -n "/MARK $MARK/,\$p"; }
waitlog() {
    for i in $(seq 1 20); do applog | grep -q "$1" && return 0; sleep 2; done
    echo "no log line: $1"; return 1
}
notif_granted() { $ADB shell dumpsys package $PKG | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; }
window_shown() {
    for i in $(seq 1 15); do $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" && return 0; sleep 2; done
    return 1
}
asked_count() { applog | grep -c "notification permission asked"; }

fresh_install() {
    $ADB uninstall $PKG >/dev/null 2>&1
    $ADB install -r "$APK" || exit 1
    # Not under test here: microphone and overlay set by adb.
    $ADB shell pm grant $PKG android.permission.RECORD_AUDIO
    $ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
    check "fresh install: notifications not granted" "! notif_granted"
}
# Screens 1 → 2 (« Plus tard ») → 4 → 5, up to Android's notification window.
to_screen5() {
    $ADB shell am start -W -n $PKG/.MainActivity >/dev/null
    waitlog "onboarding step Welcome"; sleep 2
    tap_at Commencer $BOTTOM
    waitlog "onboarding step MicPermission"; sleep 2
    tap_at "Plus tard" $BOTTOM
    waitlog "onboarding step BubbleIntro"; sleep 2
    tap_at "Ouvrir le réglage" $ABOVE
    check "screen 5" "waitlog 'onboarding step BubbleEnabled'"
}

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB shell getprop persist.sys.locale | sed 's/^/locale /'
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
wake; $ADB shell wm dismiss-keyguard
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
$ADB logcat -c

# Throwaway first launch: just after a boot the first screenshots come out black.
fresh_install
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 8
$ADB exec-out screencap -p > "$OUT/warmup.png"
$ADB shell am force-stop $PKG

echo "== allow"
fresh_install
mark allow
to_screen5
check "Android's notification window shown on screen 5" "window_shown"
sleep 2; shot allow-system-window
tap_system "com.android.permissioncontroller:id/permission_allow_button" "Allow" "Autoriser"
check "allowed in the window" "waitlog 'notification permission granted=true' && notif_granted"
sleep 2; shot allow-screen5
check "note: « Une notification vous le confirme »" "applog | grep 'notifications off note' | tail -1 | grep -q 'note=false'"
# Back to screen 4 and on to 5 again: no second window.
$ADB shell input keyevent KEYCODE_BACK
waitlog "onboarding step BubbleIntro"; sleep 2
mark allow-again
tap_at "Ouvrir le réglage" $ABOVE
waitlog "onboarding step BubbleEnabled"; sleep 4
check "back on screen 5: no second window" "[ \$(asked_count) -eq 0 ] && ! focus | grep -q permissioncontroller"
$ADB shell am force-stop $PKG; sleep 2
mark allow-relaunch
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null
waitlog "onboarding step BubbleEnabled"; sleep 4
check "relaunch on screen 5: no second window" "[ \$(asked_count) -eq 0 ] && ! focus | grep -q permissioncontroller"

echo "== refuse"
fresh_install
mark refuse
to_screen5
check "Android's notification window shown on screen 5" "window_shown"
sleep 2; shot refuse-system-window
tap_system "com.android.permissioncontroller:id/permission_deny_button" "Don’t allow" "Don't allow" "Ne pas autoriser"
check "refused in the window" "waitlog 'notification permission granted=false' && ! notif_granted"
check "note: « Notifications désactivées »" "waitlog 'notifications off note=true'"
sleep 2; shot refuse-screen5
$ADB shell am force-stop $PKG; sleep 2
mark refuse-relaunch
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null
waitlog "onboarding step BubbleEnabled"; sleep 4
check "relaunch after a refusal: no second window, note still there" \
    "[ \$(asked_count) -eq 0 ] && ! focus | grep -q permissioncontroller && applog | grep -q 'notifications off note=true'"
tap_at Terminer $ABOVE
check "refusal blocks nothing: « Terminer » ends the onboarding" "waitlog 'onboarding done'"
sleep 3; shot refuse-home

$ADB logcat -d -b all > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
$ADB logcat -d -s VoxbulleProof:I > "$OUT/app-log.txt"
