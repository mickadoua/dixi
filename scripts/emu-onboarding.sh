#!/usr/bin/env bash
# Emulator proof for the onboarding, screens 1 to 5, with every
# permission given through Android's own screens (no `install -g`).
# Two parts, one emulator session each. Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/emu-onboarding.sh light <out-dir>
#   bash scripts/emu-onboarding.sh dark <out-dir>
# light / dark: the system theme (Android's own screens). The app itself is dark only.
# light: microphone allowed in the system window, app killed on screen 3 and
#        reopened there, overlay page left without allowing (“Setting not
#        turned on”), then allowed; “Done”; relaunch opens the home tab.
# dark:  microphone refused (“Open settings”), allowed from the app info
#        page; same trial and overlay steps.
# App screens are tapped at fixed points (1080 x 2400 `phone`) and followed
# through the app's log lines: uiautomator dumps fail too often on the loaded
# host. Dumps are kept for Android's own screens, with fallback points.
# The emulator has no real microphone (level 0): the debug receiver lowers the
# « nothing heard » threshold to 0 so the trial reaches the fake transcription.
set -u
PART=${1:-light}; shift
OUT=${1:-proof-onboarding}/$PART
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId
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
# Center of the first node matching one of the patterns (text, content-desc or resource-id, exact).
find_node() {
    python3 - "$OUT/ui.xml" "$@" <<'PY'
import re, sys
xml = open(sys.argv[1]).read()
nodes = re.findall(r'<node [^>]*>', xml)
for want in sys.argv[2:]:
    for node in nodes:
        if want == 'CHECKABLE':
            hit = 'checkable="true"' in node
        else:
            hit = any(f'{a}="{want}"' in node for a in ('text', 'content-desc', 'resource-id'))
        if hit:
            a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((a + c) // 2, (b + d) // 2)
            sys.exit(0)
PY
}
# Android's own screens: tap the first node matching one of the patterns, else at FALLBACK="x y".
FALLBACK=""
tap_system() {
    local xy=""
    dump; xy=$(find_node "$@")
    [ -n "$xy" ] || xy=$FALLBACK
    echo "tap system '$1' at ${xy:-?}"
    [ -n "$xy" ] && { wake; $ADB shell input tap $xy; }
    FALLBACK=""
    sleep 3
}
# App screens: fixed points measured on the screenshots.
focus() { $ADB shell dumpsys window | grep -o "mCurrentFocus=[^ ]* [^ ]* [^ }]*" | head -1; }
tap_at() { echo "tap $1 at $2 $3"; wake; $ADB shell input tap "$2" "$3"; sleep 2; echo "  $(focus)"; }
BOTTOM="540 2220"      # lowest 56 dp button
ABOVE="540 2052"       # the 56 dp button above it
MIC="540 725"          # screen 3, 72 dp microphone button (idle and recording)
COPY="270 2220"; CONTINUE="810 2220"
# App log lines since the last mark (logcat is never cleared: the whole log is kept for the proof).
MARK=start
mark() { MARK="$1-$RANDOM"; $ADB shell log -t VoxbulleProof "MARK $MARK"; }
applog() { $ADB logcat -d -s VoxbulleProof:I | sed -n "/MARK $MARK/,\$p"; }
# Waits (up to ~40 s) for an app log line.
waitlog() {
    for i in $(seq 1 20); do applog | grep -q "$1" && return 0; sleep 2; done
    echo "no log line: $1"; return 1
}
step_is() { [ "$(applog | grep -o 'onboarding step [A-Za-z]*' | tail -1)" = "onboarding step $1" ]; }
debug_limits() {
    $ADB shell am broadcast -n $PKG/$NS.recording.DebugRecordingReceiver --ei min_amplitude 0 >/dev/null
}
step_saved() { $ADB shell run-as $PKG cat files/datastore/settings.preferences_pb 2>/dev/null | tr -c '[:print:]' ' ' | grep -o "Welcome\|MicPermission\|Trial\|BubbleIntro\|BubbleEnabled" | tail -1; }
overlay_allowed() { $ADB shell appops get $PKG SYSTEM_ALERT_WINDOW | grep -q allow; }
mic_granted() { $ADB shell dumpsys package $PKG | grep -q "android.permission.RECORD_AUDIO: granted=true"; }

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB shell getprop persist.sys.locale | sed 's/^/locale /'
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r "$APK" || exit 1
check "fresh install: microphone not granted" "! mic_granted"
check "fresh install: overlay not allowed" "! overlay_allowed"
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
wake; $ADB shell wm dismiss-keyguard
if [ "$PART" = dark ]; then MODE=yes; else MODE=no; fi
# Switching the night mode restarts SystemUI: the display stays black a while.
$ADB shell cmd uimode night $MODE; sleep 30
wake; $ADB shell wm dismiss-keyguard
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
$ADB logcat -c
mark start

# Throwaway first launch: just after a boot the first screenshots come out black.
$ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 8
$ADB exec-out screencap -p > "$OUT/warmup.png"
debug_limits

# Screen 1.
waitlog "onboarding step Welcome"
sleep 2; shot 1-welcome
tap_at "Get started" $BOTTOM

# Screen 2, then the system microphone window.
waitlog "onboarding step MicPermission"
sleep 2; shot 2-mic
tap_at "Allow the microphone" $ABOVE
for i in $(seq 1 15); do $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" && break; sleep 2; done
sleep 2; shot 2-system-mic-window
if [ "$PART" = light ]; then
    FALLBACK="540 1370"
    tap_system "com.android.permissioncontroller:id/permission_allow_foreground_only_button"
    waitlog "microphone permission granted=true"
    check "microphone granted from the system window" "mic_granted"
else
    FALLBACK="540 1610"
    tap_system "com.android.permissioncontroller:id/permission_deny_button"
    waitlog "microphone permission granted=false"
    sleep 2; shot 2-mic-refused
    check "microphone refused in the system window" "! mic_granted"
    tap_at "Open settings" $ABOVE
    sleep 4; shot 2-app-info
    # App info → Permissions → Microphone → Allow only while using the app.
    tap_system "Permissions" "Autorisations" "App permissions"
    sleep 1; shot 2-app-permissions
    tap_system "Microphone" "Micro"
    sleep 1; shot 2-app-permission-mic
    tap_system "com.android.permissioncontroller:id/allow_foreground_only_radio_button" "Allow only while using the app" \
        "Autoriser seulement si l'appli est en cours d'utilisation"
    shot 2-system-mic-allowed
    if mic_granted; then
        echo "OK   microphone granted from the app info page"
    else
        # Not found on screen: grant it so the rest of the journey still runs, and say so.
        echo "FAIL microphone granted from the app info page (FALLBACK pm grant)"
        $ADB shell pm grant $PKG android.permission.RECORD_AUDIO
    fi
    for k in 1 2 3; do
        $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG/" && break
        $ADB shell input keyevent KEYCODE_BACK; sleep 3
    done
    waitlog "microphone allowed in the app info page"
fi

# Screen 3: the trial opens on its own once the microphone is allowed.
check "screen 3 after the microphone" "waitlog 'onboarding step Trial'"
sleep 1
check "step saved: Trial" "[ \"\$(step_saved)\" = Trial ]"
if [ "$PART" = light ]; then
    # Leave the app in the middle: it must come back on screen 3.
    $ADB shell am force-stop $PKG; sleep 2
    mark relaunch-mid-way
    $ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 5
    debug_limits
    check "relaunch mid-way reopens screen 3" "waitlog 'onboarding step Trial' && ! applog | grep -q 'onboarding step Welcome'"
fi
sleep 2; shot 3-trial-idle
tap_at "micro (start)" $MIC
waitlog "trial state Recording"
sleep 4; shot 3-trial-recording
check "recording started (foreground service, microphone)" "applog | grep -q 'recording started'"
tap_at "micro (stop)" $MIC
waitlog "trial state Result"
sleep 2; shot 3-trial-result
check "result shown (raw and Dixi text)" "applog | grep -q 'trial state Result'"
check "no overlay permission asked before the result" "! overlay_allowed"
check "audio deleted after the trial" "[ -z \"\$($ADB shell run-as $PKG ls cache | grep dictation-)\" ]"
tap_at Copy $COPY
sleep 3; shot 3-trial-copied
tap_at Continue $CONTINUE

# Screen 4, then Android's overlay page.
waitlog "onboarding step BubbleIntro"
sleep 2; shot 4-bubble-intro
open_overlay_page() {
    tap_at "Open the setting" $ABOVE
    sleep 4
    shot 4-system-overlay-page
    # Android 11+ may open the list of apps: pick Dixi when it is there.
    dump
    if [ -z "$(find_node CHECKABLE)" ]; then tap_system "Dixi"; sleep 2; shot 4-system-overlay-app; fi
}
back_to_app() {
    for k in 1 2 3; do
        $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG/" && break
        $ADB shell input keyevent KEYCODE_BACK; sleep 3
    done
}
if [ "$PART" = light ]; then
    open_overlay_page
    # Back without allowing.
    back_to_app
    check "overlay refused: stays on screen 4 with “Setting not turned on”" "waitlog 'overlay permission refused' && ! applog | grep -q 'onboarding step BubbleEnabled'"
    sleep 2; shot 4-refused
fi
open_overlay_page
tap_system CHECKABLE
sleep 1; shot 4-system-overlay-allowed
check "overlay allowed from Android's page" "overlay_allowed"
back_to_app

# Screen 5: the bubble is there.
check "screen 5 after the overlay page" "waitlog 'onboarding step BubbleEnabled'"
check "bubble shown" "waitlog 'bubble shown'"
sleep 3; shot 5-bubble-enabled
tap_at Done $ABOVE
check "“Done” ends the onboarding" "waitlog 'onboarding done'"
sleep 3; shot 9-home-after-onboarding

# Done: a relaunch never replays the onboarding.
$ADB shell am force-stop $PKG; sleep 2
mark relaunch-after-done
$ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 6
check "relaunch after “Done” shows no onboarding step" "applog | grep -q 'MARK relaunch-after-done' && focus | grep -q $PKG/ && ! applog | grep -q 'onboarding step'"
shot 9-relaunch

$ADB logcat -d -b all > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
$ADB logcat -d -s VoxbulleProof:I > "$OUT/app-log.txt"
$ADB shell cmd uimode night no
