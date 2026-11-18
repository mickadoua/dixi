#!/usr/bin/env bash
# Emulator proof for the onboarding (DMC-768): Welcome, microphone, Mistral key,
# bubble, guided trial with the real bubble, « Dixi is ready », then the home screen.
# Every permission goes through Android's own screens (no `install -g`).
# Two parts, one emulator session each. Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/emu-onboarding.sh light <out-dir>
#   bash scripts/emu-onboarding.sh dark <out-dir>
# light / dark: the system theme (Android's own screens). The app itself is dark only.
# light: microphone allowed in the system window; key refused (401) then accepted;
#        app killed on the key step and reopened there; overlay page left without
#        allowing (« Setting not turned on »), then allowed; guided trial (tap, wave,
#        text in the field, tuck at the edge, bring back); « Done »; relaunch opens home.
# dark:  microphone refused (« Open settings »), allowed from the app info page;
#        same key, bubble and trial steps.
# Mistral is the debug build's fake (files/fake-mistral, FakeMistralProvider): it refuses
# any key but « dixi-fake-… » and answers dictations with a fixed text. No real key here.
# Taps follow uiautomator dumps when they work, else fixed points (1080 x 2400 `phone`),
# and the app's log lines. The overlay windows (bubble) are not in the dumps: the app
# logs the bubble center.
# The emulator has no real microphone (level 0): the debug receiver lowers the
# « nothing heard » threshold to 0 so the trial reaches the fake transcription.
set -u
PART=${1:-light}; shift
OUT=${1:-proof-onboarding}/$PART
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
WRONG_KEY=wrong-key-0000000000
FAKE_KEY=dixi-fake-proof-0000000000
mkdir -p "$OUT"
N=0
FAILS=0
shot() { dismiss_anr; N=$((N + 1)); $ADB exec-out screencap -p > "$OUT/$(printf %02d $N)-$1.png"; echo "shot $(printf %02d $N)-$1"; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
# Not /sdcard: its FUSE mount drops out on the loaded emulator.
UI=/data/local/tmp/ui.xml
# A loaded emulator often shows « Process system isn't responding »: answer « Wait » before
# any tap (a tap under it can hit « Close app », and Android restarts).
dismiss_anr() {
    local k b
    for k in 1 2 3; do
        $ADB shell dumpsys window | grep -q "Not Responding" || return 0
        $ADB shell uiautomator dump $UI >/dev/null 2>&1
        b=$($ADB shell cat $UI | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/p' | head -1)
        if [ -n "$b" ]; then read -r x1 y1 x2 y2 <<< "$b"; $ADB shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)); else $ADB shell input keyevent KEYCODE_BACK; fi
        echo "  answered « Wait » to an ANR dialog"; sleep 3
    done
}
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; dismiss_anr; }
dump() {
    rm -f "$OUT/ui.xml"
    for i in 1 2 3 4; do
        $ADB shell rm -f $UI
        local r
        r=$($ADB shell uiautomator dump $UI 2>&1 | tail -1)
        case "$r" in *dumped*) $ADB shell cat $UI > "$OUT/ui.xml"; return 0 ;; esac
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
        elif want == 'EDITABLE':
            hit = 'class="android.widget.EditText"' in node
        else:
            hit = any(f'{a}="{want}"' in node for a in ('text', 'content-desc', 'resource-id'))
        if hit:
            a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
            print((a + c) // 2, (b + d) // 2)
            sys.exit(0)
PY
}
# Taps the first node matching one of the patterns, else FALLBACK="x y".
FALLBACK=""
tap_node() {
    local xy=""
    dump; xy=$(find_node "$@")
    [ -n "$xy" ] || { xy=$FALLBACK; [ -n "$xy" ] && echo "  '$1' not in the dump: fixed point"; }
    echo "tap '$1' at ${xy:-?}"
    [ -n "$xy" ] && { wake; $ADB shell input tap $xy; }
    FALLBACK=""
    sleep 3
}
focus() { $ADB shell dumpsys window | grep -o "mCurrentFocus=[^ ]* [^ ]* [^ }]*" | head -1; }
BOTTOM="540 2220"      # lowest 56 dp button
ABOVE="540 2052"       # the 56 dp button above it
# App log lines since the last mark (logcat is never cleared: the whole log is kept for the proof).
MARK=start
mark() { MARK="$1-$RANDOM"; $ADB shell log -t DixiProof "MARK $MARK"; }
applog() { $ADB logcat -d -s DixiProof:I | sed -n "/MARK $MARK/,\$p"; }
# Waits (up to ~40 s) for an app log line.
waitlog() {
    for i in $(seq 1 20); do applog | grep -q "$1" && return 0; sleep 2; done
    echo "no log line: $1"; return 1
}
debug_limits() {
    $ADB shell am broadcast -n $PKG/$NS.recording.DebugRecordingReceiver --ei min_amplitude 0 >/dev/null
}
step_saved() { $ADB shell run-as $PKG cat files/datastore/settings.preferences_pb 2>/dev/null | tr -c '[:print:]' ' ' | grep -o "Welcome\|MicPermission\|MistralKey\|BubbleIntro\|Trial\|BubbleEnabled" | tail -1; }
overlay_allowed() { $ADB shell appops get $PKG SYSTEM_ALERT_WINDOW | grep -q allow; }
mic_granted() { $ADB shell dumpsys package $PKG | grep -q "android.permission.RECORD_AUDIO: granted=true"; }
# Several keys since DMC-766: one encrypted no_backup/mistral_keys/<id>.bin per key, names in keys.json.
key_saved() { $ADB shell run-as $PKG ls no_backup/mistral_keys 2>/dev/null | grep -q '\.bin'; }
start_app() { $ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 6; debug_limits; }
back_to_app() {
    for k in 1 2 3; do
        $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG/" && break
        $ADB shell input keyevent KEYCODE_BACK; sleep 3
    done
}
# Android 13+: the notifications are asked at the microphone step, right after it (DMC-785).
# Before DMC-785 they came at the first tap on the bubble: no window here, nothing done.
allow_notifications_window() {
    for i in $(seq 1 5); do applog | grep -q "notifications permission granted=" && return 0; $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" && break; sleep 2; done
    $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" || return 0
    sleep 1; shot 2-system-notifications-window
    FALLBACK="540 1318"; tap_node "com.android.permissioncontroller:id/permission_allow_button" "Allow" "Autoriser"
    check "notifications allowed at the microphone step" "waitlog 'notifications permission granted=true'"
}
# Google Play services sometimes opens its own screen (« Sign in with ease ») over the app:
# bring Dixi back to the front (same task, same step).
front() {
    $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG/" && return 0
    echo "  $(focus) in front: back to Dixi"
    $ADB shell input keyevent KEYCODE_BACK; sleep 2
    $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG/" || { $ADB shell am start -n $PKG/$NS.MainActivity >/dev/null; sleep 4; }
}
# Last bubble center logged by the app: « x y ».
bubble_xy() { applog | grep -E "bubble (shown|snapped|placed)" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB shell getprop persist.sys.locale | sed 's/^/locale /'
echo "screen ${W}x${H}"
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r "$APK" || exit 1
# The fake Mistral, before the first launch (read when the process starts).
fake_mistral() { $ADB shell "run-as $PKG sh -c 'mkdir -p files; echo $1 > files/fake-mistral'"; }
fake_mistral ok
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
start_app
$ADB exec-out screencap -p > "$OUT/warmup.png"
check "fake Mistral running" "applog | grep -q 'fake mistral on http://127.0.0.1'"

# 1. Welcome.
waitlog "onboarding step Welcome"
sleep 2; shot 1-welcome
FALLBACK=$BOTTOM; tap_node "Get started" "Commencer"

# 2. Microphone, then the system window.
waitlog "onboarding step MicPermission"
sleep 2; shot 2-mic
FALLBACK=$ABOVE; tap_node "Allow the microphone" "Autoriser le micro"
for i in $(seq 1 15); do $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" && break; sleep 2; done
sleep 2; shot 2-system-mic-window
if [ "$PART" = light ]; then
    FALLBACK="540 1370"
    tap_node "com.android.permissioncontroller:id/permission_allow_foreground_only_button"
    waitlog "microphone permission granted=true"
    check "microphone granted from the system window" "mic_granted"
    allow_notifications_window
else
    FALLBACK="540 1610"
    tap_node "com.android.permissioncontroller:id/permission_deny_button"
    waitlog "microphone permission granted=false"
    sleep 2; shot 2-mic-refused
    check "microphone refused in the system window" "! mic_granted"
    FALLBACK=$ABOVE; tap_node "Open settings" "Ouvrir les réglages"
    sleep 4; shot 2-app-info
    # App info → Permissions → Microphone → Allow only while using the app.
    tap_node "Permissions" "Autorisations" "App permissions"
    tap_node "Microphone" "Micro"
    tap_node "com.android.permissioncontroller:id/allow_foreground_only_radio_button" "Allow only while using the app" \
        "Autoriser seulement si l'appli est en cours d'utilisation"
    shot 2-system-mic-allowed
    if mic_granted; then
        echo "OK   microphone granted from the app info page"
    else
        # Not found on screen: grant it so the rest of the journey still runs, and say so.
        echo "FAIL microphone granted from the app info page (FALLBACK pm grant)"; FAILS=$((FAILS + 1))
        $ADB shell pm grant $PKG android.permission.RECORD_AUDIO
    fi
    # Back until Dixi resumes; not once more: the notifications window opens over it.
    for k in 1 2 3 4; do
        applog | grep -q "microphone allowed in the app info page" && break
        $ADB shell input keyevent KEYCODE_BACK; sleep 3
    done
    waitlog "microphone allowed in the app info page"
    allow_notifications_window
fi

# 3. Mistral key: required, refused (401) then accepted.
check "key step after the microphone" "waitlog 'onboarding step MistralKey'"
sleep 1
check "step saved: MistralKey" "[ \"\$(step_saved)\" = MistralKey ]"
if [ "$PART" = light ]; then
    # Leave the app in the middle: it must come back on the key step.
    $ADB shell am force-stop $PKG; sleep 2
    mark relaunch-mid-way
    start_app
    check "relaunch mid-way reopens the key step" "waitlog 'onboarding step MistralKey' && ! applog | grep -q 'onboarding step Welcome'"
fi
front; sleep 2; shot 3-key-guide
# Length of the key field's text (a password field: the dump shows one dot per character).
field_length() { dump; python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'class="android.widget.EditText"' in node:
        print(len(re.search(r' text="([^"]*)"', node).group(1))); break
PY
}
# The loaded emulator drops characters of a long `input text`: type in short chunks,
# check the length, and start again if some are missing.
type_key() {
    local tries len
    for tries in 1 2 3; do
        front
        FALLBACK="540 1418"; tap_node EDITABLE
        # Empty the field: cursor at the end, then delete.
        $ADB shell input keyevent KEYCODE_MOVE_END $(printf '67 %.0s' $(seq 1 40))
        local k=$1
        wake
        while [ -n "$k" ]; do $ADB shell input text "${k:0:4}"; k=${k:4}; sleep 1; done
        sleep 2
        len=$(field_length)
        [ "$len" = "${#1}" ] && break
        echo "  key field: ${len:-?} of ${#1} characters, typing again"
    done
    # « Done » on the keyboard runs « Test key », like the button.
    wake; $ADB shell input keyevent KEYCODE_ENTER
}
# Keyboard down (it hides the answer); a BACK without it would leave the app.
keyboard_down() { dismiss_anr; $ADB shell dumpsys input_method | grep -q 'mInputShown=true' && $ADB shell input keyevent KEYCODE_BACK; sleep 2; }
mark key-wrong
type_key "$WRONG_KEY"
check "wrong key refused (401)" "waitlog 'key test Invalid'"
keyboard_down
shot 3-key-refused
check "refused key not saved" "! key_saved"
mark key-right
type_key "$FAKE_KEY"
check "key accepted by Mistral (fake)" "waitlog 'key test Valid'"
keyboard_down
shot 3-key-valid
check "accepted key saved (encrypted file)" "key_saved"
check "key never in the log" "! $ADB logcat -d | grep -q -e '$FAKE_KEY' -e '$WRONG_KEY'"
FALLBACK=$BOTTOM; tap_node "Continue" "Continuer"

# 4. Bubble, then Android's overlay page.
waitlog "onboarding step BubbleIntro"
sleep 2; shot 4-bubble-intro
open_overlay_page() {
    FALLBACK=$ABOVE; tap_node "Open the setting" "Ouvrir le réglage"
    sleep 2
    shot 4-system-overlay-page
    # Android 11+ may open the list of apps: pick Dixi when it is there.
    dump
    if [ -z "$(find_node CHECKABLE)" ]; then tap_node "Dixi"; shot 4-system-overlay-app; fi
}
if [ "$PART" = light ]; then
    open_overlay_page
    # Back without allowing.
    back_to_app
    check "overlay refused: stays on the bubble step with « Setting not turned on »" \
        "waitlog 'overlay permission refused' && ! applog | grep -q 'onboarding step Trial'"
    sleep 2; shot 4-refused
fi
open_overlay_page
tap_node CHECKABLE
shot 4-system-overlay-allowed
check "overlay allowed from Android's page" "overlay_allowed"
back_to_app

# 5. Guided trial with the real bubble.
check "guided trial after the overlay page" "waitlog 'onboarding step Trial'"
check "bubble shown" "waitlog 'bubble shown'"
check "hint a: tap" "waitlog 'guided trial step Tap'"
sleep 3; shot 5a-trial-tap
read -r BX BY <<< "$(bubble_xy)"
echo "bubble center: $BX $BY"
wake; $ADB shell input tap "$BX" "$BY"
# Before DMC-785, Android 13+ asked for notifications at the first dictation: still handled.
for i in $(seq 1 8); do $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller" && break; applog | grep -q 'bubble state Listening' && break; sleep 2; done
if $ADB shell dumpsys window | grep -q "mCurrentFocus=.*permissioncontroller"; then
    sleep 1; shot 5a-system-notifications-window
    FALLBACK="540 1318"; tap_node "com.android.permissioncontroller:id/permission_allow_button" "Allow" "Autoriser"
    check "notifications allowed from the system window" "$ADB shell dumpsys package $PKG | grep -q 'android.permission.POST_NOTIFICATIONS: granted=true'"
    applog | grep -q 'bubble state Listening' || { wake; $ADB shell input tap "$BX" "$BY"; }
fi
check "hint b: speak (bubble listening)" "waitlog 'guided trial step Speak'"
sleep 3; shot 5b-trial-listening-wave
wake; $ADB shell input tap "$BX" "$BY"
check "text in the field (fake transcription)" "waitlog 'guided trial step Dock'"
sleep 3; shot 5c-trial-text-in-field
check "no copy notification needed: text sent to the field" "applog | grep -q 'clipboard set'"
check "audio deleted after the dictation" "[ -z \"\$($ADB shell run-as $PKG ls cache | grep dictation-)\" ]"
# Drag the bubble against its edge: tucked away as a half-bubble.
read -r BX BY <<< "$(bubble_xy)"
if [ "$BX" -gt $((W / 2)) ]; then EDGE=$((W - 4)); else EDGE=4; fi
wake
$ADB shell "input motionevent DOWN $BX $BY; for i in 1 2 3 4 5 6; do input motionevent MOVE \$(( $BX + ($EDGE - $BX) * i / 6 )) $BY; done; input motionevent UP $EDGE $BY"
check "bubble tucked at the edge" "waitlog 'bubble docked'"
check "hint e: bring it back" "waitlog 'guided trial step Undock'"
sleep 2; shot 5d-trial-docked-half-bubble
DY=$(applog | grep "bubble docked" | sed -n 's/.* at y=\([0-9]*\).*/\1/p' | tail -1)
if [ "$EDGE" -gt 4 ]; then TX=$((W - 30)); else TX=30; fi
wake; $ADB shell input tap "$TX" "${DY:-$BY}"
check "tap brings the bubble back" "waitlog 'bubble undocked'"
check "trial done" "waitlog 'guided trial step Done'"

# 6. « Dixi is ready ».
check "« Dixi is ready » after the trial" "waitlog 'onboarding step BubbleEnabled'"
sleep 3; shot 6-ready
FALLBACK=$ABOVE; tap_node "Done" "Terminer"
check "« Done » ends the onboarding" "waitlog 'onboarding done'"
sleep 3; shot 7-home-after-onboarding

# Done: a relaunch never replays the onboarding.
$ADB shell am force-stop $PKG; sleep 2
mark relaunch-after-done
start_app
check "relaunch after « Done » shows no onboarding step" "applog | grep -q 'MARK relaunch-after-done' && focus | grep -q $PKG/ && ! applog | grep -q 'onboarding step'"
shot 7-relaunch-home

if [ "$PART" = light ]; then
    # 8. The key is refused at a dictation (revoked since): the cause shows next to the bubble,
    # and a tap on it opens the key settings.
    fake_mistral 401
    # Keep the error long enough for a screenshot (3.5 s by default).
    $ADB shell am broadcast -p $PKG -a $NS.debug.SET_BUBBLE_STATE --es state Idle --el readyMs 60000 >/dev/null
    $ADB shell input keyevent KEYCODE_HOME; sleep 2
    $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4
    mark key-revoked
    debug_limits
    read -r BX BY <<< "$($ADB logcat -d -s DixiProof:I | grep -E "bubble (shown|snapped|placed)" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1)"
    echo "bubble center: $BX $BY"
    wake; $ADB shell input tap "$BX" "$BY"
    waitlog "bubble state Listening"; sleep 2
    wake; $ADB shell input tap "$BX" "$BY"
    check "dictation refused: « Key refused » next to the bubble" "waitlog 'dictation error: \(Key refused\|Clé refusée\)'"
    shot 8-bubble-key-refused
    wake; $ADB shell input tap "$BX" "$BY"
    check "tap on « Key refused » opens the key settings" "waitlog 'key refused: opening the key settings'"
    sleep 4; shot 8-key-settings
    fake_mistral ok
fi

$ADB logcat -d -b all > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
check "no key in the full log" "! grep -q -e '$FAKE_KEY' -e '$WRONG_KEY' '$OUT/logcat.txt'"
$ADB logcat -d -s DixiProof:I > "$OUT/app-log.txt"
$ADB shell cmd uimode night no
echo "checks failed: $FAILS"
[ "$FAILS" = 0 ]
