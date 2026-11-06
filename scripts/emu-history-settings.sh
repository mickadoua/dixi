#!/usr/bin/env bash
# Emulator proof for screens 9 (history) and 10 (settings), light and dark.
# Uses the debug-only extras of MainActivity (--es screen, --ei seedHistory).
# Three parts, one emulator session each (boot alone can take 4 min on the NAS):
#   emu-run phone --wait-lock 30 -- bash scripts/emu-history-settings.sh history <out-dir>
#   emu-run phone --wait-lock 30 -- bash scripts/emu-history-settings.sh settings <out-dir>
#   emu-run phone --wait-lock 30 -- bash scripts/emu-history-settings.sh dark <out-dir>
set -u
PART=${1:-history}; shift
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId (DMC-383)
OUT=${1:-proof-history}
APK=app/build/outputs/apk/debug/app-debug.apk
HISTORY_FILE=files/datastore/dictation_history.json
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
open() { $ADB shell am start -S -W -n $PKG/$NS.MainActivity "$@" >/dev/null; sleep 2; }
night() { $ADB shell cmd uimode night "$1" >/dev/null; sleep 2; }
entries() { $ADB shell run-as $PKG cat $HISTORY_FILE 2>/dev/null | python3 -c 'import json,sys
d=sys.stdin.read().strip(); print(len(json.loads(d)) if d else 0)'; }
dump() {
    for i in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break; sleep 3; done
    $ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml"
}
# Waits until our activity has focus, then lets Compose draw. uiautomator dumps
# take several seconds each on a loaded NAS: they are kept for taps only.
waitfor() {
    for i in $(seq 1 20); do
        $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG" && break
        sleep 1
    done
    sleep 4
}
# Taps the first node whose text or content-desc is exactly $1, else at $2
# (coordinates measured on the 1080 x 2400 `phone` screenshots).
tap() {
    dump
    local xy
    xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
want = sys.argv[2]
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if f'text="{want}"' in node or f'content-desc="{want}"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
    [ -n "$xy" ] || xy=${2:-}
    echo "tap '$1' at ${xy:-?}"
    [ -n "$xy" ] && $ADB shell input tap $xy
    sleep 2
}

# Prints the bounds of the « Mode démo » label and of the node $1 (content-desc),
# and how many pixels they share (QA DMC-167: the label hid the settings icon).
overlap() {
    dump
    python3 - "$OUT/ui.xml" "$1" <<'PY2'
import re, sys
xml, want = open(sys.argv[1]).read(), sys.argv[2]
def box(pred):
    for node in re.findall(r'<node [^>]*>', xml):
        if pred(node):
            return tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups()))
label = box(lambda n: 'content-desc="Mode démo :' in n)
other = box(lambda n: f'content-desc="{want}"' in n)
print(f"demo label bounds: {label}")
print(f"'{want}' bounds: {other}")
if label and other:
    w = max(0, min(label[2], other[2]) - max(label[0], other[0]))
    h = max(0, min(label[3], other[3]) - max(label[1], other[1]))
    print(f"overlap label/'{want}': {w * h} px")
PY2
}

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell pm clear $PKG >/dev/null
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell settings put system screen_off_timeout 600000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard
# A cold boot on a loaded NAS can show "System UI isn't responding": let it settle.
sleep 15
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
$ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard

# Seeds 21 dictations and waits until all writes are on disk.
seed() {
    open --es screen History --ei seedHistory 21
    for i in $(seq 1 30); do [ "$(entries)" = 20 ] && break; sleep 1; done
}

if [ "$PART" = history ]; then
night no
open --es screen History
waitfor
shot 9-empty-light
echo "entries at start: $(entries)"

# 21 dictations in: the oldest must go.
seed
open --es screen History
waitfor
shot 9-full-light
echo "entries after 21 adds: $(entries)"
overlap "Réglages"
overlap "Retour"
# The settings icon must still open screen 10.
tap "Réglages" "1020 170"
waitfor
dump; grep -q 'text="Langue"' "$OUT/ui.xml" && echo "settings icon opens screen 10: yes" || echo "settings icon opens screen 10: NO"
shot 10-from-icon-light
$ADB shell run-as $PKG cat $HISTORY_FILE > "$OUT/dictation_history.json"

# Persistence: kill the app, reopen without seeding.
$ADB shell am force-stop $PKG
open --es screen History
waitfor
echo "entries after restart: $(entries)"

# Copy by tapping the first line, then paste into Contacts.
tap "Bonjour Claire, je voulais confirmer le rendez-vous de jeudi à 14 h."
shot 9-copied-light
$ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4
for i in $(seq 1 10); do dump; grep -q 'class="android.widget.EditText"' "$OUT/ui.xml" && break; sleep 3; done
XY=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'class="android.widget.EditText"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
[ -n "$XY" ] && { $ADB shell input tap $XY; sleep 2; $ADB shell input keyevent KEYCODE_PASTE; sleep 2; }
dump; grep -o 'confirmer le rendez-vous de jeudi' "$OUT/ui.xml" | head -1 | sed 's/^/PASTED: /'
$ADB shell input keyevent KEYCODE_BACK; $ADB shell input keyevent KEYCODE_BACK
fi

if [ "$PART" = settings ]; then
# Settings, light.
night no
open --es screen Settings
waitfor
shot 10-light
overlap "Retour"
tap "Langue"
waitfor
shot 10-language-dialog-light
tap "English" "346 1351"
dump; grep -o 'text="English"' "$OUT/ui.xml" | head -1 | sed 's/^/language row: /'
tap "Afficher la bulle" "360 420"
sleep 2
$ADB shell dumpsys activity services $PKG | grep -c 'BubbleService' | sed 's/^/bubble service records: /'
shot 10-bubble-on-light
tap "Afficher la bulle" "360 420"
sleep 2
$ADB shell dumpsys activity services $PKG | grep -c 'BubbleService' | sed 's/^/bubble service records after off: /'
$ADB shell am force-stop $PKG
open --es screen Settings
waitfor; shot 10-after-restart-light; dump; grep -o 'text="English"' "$OUT/ui.xml" | head -1 | sed 's/^/language after restart: /'

fi

if [ "$PART" = dark ]; then
# Dark: full history, settings, then "Tout effacer" with its confirmation.
night yes
seed
open --es screen History
waitfor
shot 9-full-dark
open --es screen Settings
waitfor
shot 10-dark
# Language without uiautomator (its dumps are slow): tap, then look.
$ADB shell input tap 132 773; sleep 3
shot 10-language-dialog-dark
$ADB shell input tap 346 1351; sleep 1
shot 10-language-tapped-dark
sleep 4
shot 10-language-english-dark
$ADB shell run-as $PKG ls -la files/datastore/
open --es screen History
waitfor
tap "Tout effacer" "540 2242"
waitfor
shot 9-confirm-dark
tap "Effacer"
waitfor
shot 9-cleared-dark
echo "entries after clear: $(entries)"
open --es screen History
waitfor
shot 9-empty-dark
night no
fi
$ADB logcat -d | grep -A8 "FATAL EXCEPTION" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
