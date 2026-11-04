#!/usr/bin/env bash
# Emulator proof for the onboarding, screens 1 to 5 (DMC-140), with every
# permission given through Android's own screens (no `install -g`).
# Two parts, one emulator session each:
#   emu-run phone --wait-lock 60 -- bash scripts/emu-onboarding.sh light <out-dir>
#   emu-run phone --wait-lock 60 -- bash scripts/emu-onboarding.sh dark <out-dir>
# light: microphone allowed in the system window, app killed on screen 3 and
#        reopened there, overlay page left without allowing (« Réglage non
#        activé »), then allowed; « Terminer »; relaunch opens the history.
# dark:  microphone refused (« Ouvrir les réglages »), allowed from the app info
#        page; same trial and overlay steps.
# The emulator has no real microphone (level 0): the debug receiver lowers the
# « nothing heard » threshold to 0 so the trial reaches the fake transcription.
set -u
PART=${1:-light}; shift
OUT=${1:-proof-onboarding}/$PART
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
    for i in 1 2 3 4 5; do
        $ADB shell rm -f /sdcard/ui.xml
        $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break
        sleep 2
    done
    $ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
}
# Center of the first node matching one of the patterns (text, content-desc or resource-id, exact).
find_node() {
    python3 - "$OUT/ui.xml" "$@" <<'PY'
import re, sys
xml = open(sys.argv[1]).read() if sys.argv[1] else ""
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
has() { dump; [ -n "$(find_node "$@")" ]; }
# Waits (up to ~40 s) until one of the patterns is on screen.
waitfor() {
    for i in $(seq 1 10); do has "$@" && return 0; sleep 2; done
    echo "not found: $*"; return 1
}
tap() {
    local xy=""
    for i in 1 2 3; do dump; xy=$(find_node "$@"); [ -n "$xy" ] && break; sleep 2; done
    echo "tap '$1' at ${xy:-?}"
    [ -n "$xy" ] && { wake; $ADB shell input tap $xy; }
    sleep 2
}
debug_limits() {
    $ADB shell am broadcast -n $PKG/.recording.DebugRecordingReceiver --ei min_amplitude 0 >/dev/null
}
step_saved() { $ADB shell run-as $PKG cat files/datastore/settings.preferences_pb 2>/dev/null | tr -c '[:print:]' ' ' | grep -o "Welcome\|MicPermission\|Trial\|BubbleIntro\|BubbleEnabled" | tail -1; }
overlay_allowed() { $ADB shell appops get $PKG SYSTEM_ALERT_WINDOW | grep -q allow; }
mic_granted() { $ADB shell dumpsys package $PKG | grep -q "android.permission.RECORD_AUDIO: granted=true"; }
bubble_window() { $ADB shell dumpsys window windows | grep -c "ty=APPLICATION_OVERLAY.*$PKG\|$PKG.*APPLICATION_OVERLAY" ; }

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

# Throwaway first launch: just after a boot the first screenshots come out black.
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 8
$ADB exec-out screencap -p > "$OUT/warmup.png"
debug_limits

# Screen 1.
waitfor "Commencer"
shot 1-welcome
tap "Commencer"

# Screen 2, then the system microphone window.
waitfor "Autoriser le micro"
shot 2-mic
tap "Autoriser le micro"
waitfor "com.android.permissioncontroller:id/permission_deny_button"
shot 2-system-mic-window
if [ "$PART" = light ]; then
    tap "com.android.permissioncontroller:id/permission_allow_foreground_only_button"
    check "microphone granted from the system window" "mic_granted"
else
    tap "com.android.permissioncontroller:id/permission_deny_button"
    waitfor "Ouvrir les réglages"
    shot 2-mic-refused
    check "refused: « Ouvrir les réglages » shown" "has 'Ouvrir les réglages'"
    tap "Ouvrir les réglages"
    sleep 3
    shot 2-app-info
    # App info → Permissions → Microphone → Allow only while using the app.
    tap "Permissions" "Autorisations"
    tap "Microphone" "Micro"
    tap "com.android.permissioncontroller:id/allow_foreground_only_radio_button"
    shot 2-system-mic-allowed
    check "microphone granted from the app info page" "mic_granted"
    $ADB shell input keyevent KEYCODE_BACK; sleep 2
    $ADB shell input keyevent KEYCODE_BACK; sleep 2
    $ADB shell input keyevent KEYCODE_BACK; sleep 3
fi

# Screen 3: the trial opens on its own once the microphone is allowed.
waitfor "Dites une phrase"
check "screen 3 after the microphone" "has 'Dites une phrase'"
check "step saved: Trial" "[ \"\$(step_saved)\" = Trial ]"
if [ "$PART" = light ]; then
    # Leave the app in the middle: it must come back on screen 3.
    $ADB shell am force-stop $PKG; sleep 2
    $ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 5
    debug_limits
    check "relaunch mid-way reopens screen 3" "waitfor 'Dites une phrase'"
fi
shot 3-trial-idle
tap "Commencer la dictée"
sleep 4
shot 3-trial-recording
check "counter runs while recording" "has 'Arrêter la dictée'"
tap "Arrêter la dictée"
waitfor "Continuer"
shot 3-trial-result
check "result cards: raw and Voxbulle text" "has 'Vous avez dit (brut)' && has 'Texte Voxbulle'"
check "no overlay permission asked before the result" "! overlay_allowed"
tap "Copier"
sleep 1
shot 3-trial-copied
tap "Continuer"

# Screen 4, then Android's overlay page.
waitfor "Ouvrir le réglage"
shot 4-bubble-intro
open_overlay_page() {
    tap "Ouvrir le réglage"
    sleep 3
    # Android 11+ opens the list of apps: pick Voxbulle when it is there.
    dump
    if [ -z "$(find_node CHECKABLE)" ]; then tap "Voxbulle"; sleep 2; fi
}
if [ "$PART" = light ]; then
    open_overlay_page
    shot 4-system-overlay-page
    # Back without allowing.
    $ADB shell input keyevent KEYCODE_BACK; sleep 2
    has "Ouvrir le réglage" || { $ADB shell input keyevent KEYCODE_BACK; sleep 2; }
    waitfor "Réglage non activé"
    shot 4-refused
    check "overlay refused: stays on screen 4 with « Réglage non activé »" "has 'Réglage non activé'"
fi
open_overlay_page
tap CHECKABLE
sleep 1
shot 4-system-overlay-allowed
check "overlay allowed from Android's page" "overlay_allowed"
$ADB shell input keyevent KEYCODE_BACK; sleep 2
has "La bulle est prête" || { $ADB shell input keyevent KEYCODE_BACK; sleep 2; }

# Screen 5: the bubble is there.
waitfor "La bulle est prête"
sleep 2
shot 5-bubble-enabled
check "screen 5 after the overlay page" "has 'La bulle est prête'"
check "bubble shown" "$ADB logcat -d | grep -q 'bubble shown' || [ \"\$(bubble_window)\" -gt 0 ]"
dump; cp "$OUT/ui.xml" "$OUT/5-ui.xml"
check "« Activer le collage automatique » disabled until DMC-141" "grep -o '<node [^>]*' \"$OUT/5-ui.xml\" | grep 'Activer le collage automatique' | grep -q 'enabled=\"false\"' || grep -B2 'Activer le collage automatique' \"$OUT/5-ui.xml\" | grep -q 'enabled=\"false\"'"
tap "Terminer"
waitfor "Historique"
shot 9-home-after-onboarding

# Done: a relaunch never replays the onboarding.
$ADB shell am force-stop $PKG; sleep 2
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 5
check "relaunch after « Terminer » opens the history" "waitfor 'Historique' && ! has 'Commencer'"
shot 9-relaunch

$ADB logcat -d > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
$ADB shell cmd uimode night no
