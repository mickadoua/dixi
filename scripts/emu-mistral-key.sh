#!/usr/bin/env bash
# Emulator proof for the « Mistral API key » section of screen 10, several named keys (DMC-766):
# no key; a key refused (401) is not saved; « Perso » added and active; a 2nd key « Pro » added
# and active; tap on « Perso » makes it active again; « Test the active key »; both kept across
# a restart; « Pro » deleted, then the last key (back to « No key »).
# Also checks: one encrypted file per key, never a key in the files, the log or the UI tree;
# key files excluded from backups.
# Needs adb ($ANDROID_HOME) and a debug APK, one emulator session:
#   APP_LANG=fr bash scripts/emu-mistral-key.sh <out-dir>     (APP_LANG: fr by default, or en)
# Mistral is the debug build's fake (files/fake-mistral): it accepts only « dixi-fake-… » keys.
# No real key here.
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
WRONG_KEY=wrong-key-0000000000
KEY1=dixi-fake-perso-00000000
KEY2=dixi-fake-pro-000000000
. "$(dirname "$0")/emu-labels.sh"
OUT=${1:-proof-mistral-key}/$APP_LANG
mkdir -p "$OUT"
N=0
FAILS=0
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; FAILS=$((FAILS + 1)); fi; }
# Not /sdcard: its FUSE mount drops out on the loaded emulator.
UI=/data/local/tmp/ui.xml
# A loaded emulator often shows « … isn't responding »: answer « Wait » before any tap.
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
shot() { dismiss_anr; N=$((N + 1)); $ADB exec-out screencap -p > "$OUT/$(printf %02d $N)-$1.png"; echo "shot $(printf %02d $N)-$1"; }
dump() {
    for i in 1 2 3 4; do
        $ADB shell rm -f $UI
        case "$($ADB shell uiautomator dump $UI 2>&1 | tail -1)" in *dumped*) $ADB shell cat $UI > "$OUT/ui.xml"; return 0 ;; esac
        sleep 2
    done
    : > "$OUT/ui.xml"
}
# Center of the first node whose text or content-desc is exactly $1; EDITABLE:<n> = the n-th text field.
find_node() {
    python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
want = sys.argv[2]
nodes = re.findall(r'<node [^>]*>', open(sys.argv[1], errors='replace').read())
if want.startswith('EDITABLE:'):
    # Only with both fields of the add form on screen: the n-th is then always the same field.
    fields = [n for n in nodes if 'class="android.widget.EditText"' in n]
    nodes = fields[int(want[9:]) - 1:] if len(fields) >= 2 else []
else:
    nodes = [n for n in nodes if f'text="{want}"' in n or f'content-desc="{want}"' in n]
if nodes:
    a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', nodes[0]).groups())
    print((a + c) // 2, (b + d) // 2)
PY
}
on_screen() { dump; [ -n "$(find_node "$1")" ]; }
# Screen 10 scrolls: up, then down, until $1 is on screen.
reveal() {
    local i
    on_screen "$1" && return 0
    for i in 1 2 3 4 5; do $ADB shell input swipe 540 1700 540 1000 400; sleep 1; on_screen "$1" && return 0; done
    for i in 1 2 3 4 5 6 7 8; do $ADB shell input swipe 540 900 540 1600 400; sleep 1; on_screen "$1" && return 0; done
    echo "  not found on screen 10: $1"; return 1
}
tap() {
    reveal "$1" || return 1
    local xy; xy=$(find_node "$1")
    echo "tap '$1' at $xy"
    wake; $ADB shell input tap $xy; sleep 2
}
open() { $ADB shell am start -S -W -n $PKG/$NS.MainActivity "$@" >/dev/null; sleep 5; }
keyboard_down() { dismiss_anr; $ADB shell dumpsys input_method | grep -q 'mInputShown=true' && $ADB shell input keyevent KEYCODE_BACK; sleep 2; }
# Text length of the n-th field (the key field is a password: one dot per character).
field_length() { dump; python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
fields = [n for n in re.findall(r'<node [^>]*>', open(sys.argv[1], errors='replace').read()) if 'class="android.widget.EditText"' in n]
print(len(re.search(r' text="([^"]*)"', fields[int(sys.argv[2]) - 1]).group(1)) if len(fields) >= int(sys.argv[2]) else '')
PY
}
# Types $2 into the n-th field ($1), emptied first. Short chunks: a loaded emulator drops characters.
type_in() {
    local tries len k
    for tries in 1 2 3; do
        # The keyboard left open by the other field covers this one: a tap would hit the keyboard.
        keyboard_down
        reveal "EDITABLE:$1" || return 1
        wake; $ADB shell input tap $(find_node "EDITABLE:$1"); sleep 1
        $ADB shell input keyevent KEYCODE_MOVE_END $(printf '67 %.0s' $(seq 1 40))
        k=$2
        while [ -n "$k" ]; do $ADB shell input text "${k:0:4}"; k=${k:4}; sleep 0.5; done
        sleep 1
        len=$(field_length "$1")
        [ "$len" = "${#2}" ] && return 0
        echo "  field $1: ${len:-?} of ${#2} characters, typing again"
    done
}
# Name in the 1st field, key in the 2nd, then « Done » on the keyboard runs « Test key ».
add_key() {
    mark "add-$1"
    type_in 1 "$1"
    type_in 2 "$2"
    wake; $ADB shell input keyevent KEYCODE_ENTER
    waitlog "key test"
    keyboard_down
}
MARK=start
mark() { MARK="$1-$RANDOM"; $ADB shell log -t DixiProof "MARK $MARK"; }
applog() { $ADB logcat -d -s DixiProof:I | sed -n "/MARK $MARK/,\$p"; }
waitlog() { for i in $(seq 1 20); do applog | grep -q "$1" && return 0; sleep 2; done; echo "  no log line: $1"; return 1; }
key_files() { $ADB shell run-as $PKG ls no_backup/mistral_keys 2>/dev/null | grep -c '\.bin$'; }
# The row of key $1 says $2 under its name (state label).
row_says() { dump; python3 - "$OUT/ui.xml" "$1" "$2" <<'PY'
import re, sys
texts = [t for t in re.findall(r' text="([^"]*)"', open(sys.argv[1], errors='replace').read()) if t]
sys.exit(0 if any(t == sys.argv[2] and i + 1 < len(texts) and texts[i + 1] == sys.argv[3] for i, t in enumerate(texts)) else 1)
PY
}
no_key_in_ui() { dump; ! grep -q -e "$KEY1" -e "$KEY2" -e "$WRONG_KEY" "$OUT/ui.xml"; }
ACTIVE=$(label key_state_active)
IDLE=$(label key_state_idle)

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB install -r -g "$APK" || exit 1
$ADB shell pm clear $PKG >/dev/null
set_app_lang
# The fake Mistral, before the first launch (read when the process starts).
$ADB shell "run-as $PKG sh -c 'mkdir -p files; echo ok > files/fake-mistral'"
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell locksettings set-disabled true >/dev/null 2>&1
wake; $ADB shell wm dismiss-keyguard
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
$ADB logcat -c
mark start

# Throwaway pass: the first screenshots after a cold boot often come out black.
open --es screen Settings
$ADB exec-out screencap -p > "$OUT/warmup.png"
check "fake Mistral running" "waitlog 'fake mistral on http://127.0.0.1'"

echo "--- 1. No key: the section says so, the add form is open"
check "« No key » shown" "reveal \"$(label key_status_none)\""
check "add form open" "reveal \"$(label key_test)\""
shot 1-no-key

echo "--- 2. Key refused by Mistral: not saved"
add_key Perso "$WRONG_KEY"
check "wrong key refused (401)" "applog | grep -q 'key test Invalid'"
check "« Key refused » shown" "reveal \"$(label key_check_refused)\""
check "refused key not saved" "[ \"\$(key_files)\" = 0 ]"
shot 2-key-refused

echo "--- 3. « Perso » accepted: saved and active"
add_key Perso "$KEY1"
check "key accepted (fake)" "applog | grep -q 'key test Valid'"
check "« Key added and active » shown" "reveal \"$(label key_added)\""
check "1 key file" "[ \"\$(key_files)\" = 1 ]"
check "« Perso » active" "reveal Perso && row_says Perso \"$ACTIVE\""
shot 3-perso-active

echo "--- 4. 2nd key « Pro »: saved and active, « Perso » kept"
tap "$(label key_add)  +"
add_key Pro "$KEY2"
check "2nd key accepted (fake)" "applog | grep -q 'key test Valid'"
check "2 key files" "[ \"\$(key_files)\" = 2 ]"
check "« Pro » active" "reveal Pro && row_says Pro \"$ACTIVE\""
check "« Perso » not in use" "row_says Perso \"$IDLE\""
shot 4-pro-active

echo "--- 5. Tap on « Perso »: active again"
tap Perso
check "« Dixi now uses Perso » shown" "reveal \"$(label key_activated Perso)\""
check "« Perso » active" "row_says Perso \"$ACTIVE\""
check "« Pro » not in use" "row_says Pro \"$IDLE\""
shot 5-perso-active-again

echo "--- 6. « Test the active key »"
tap "$(label key_test_active)"
# The title line is « Key valid ✓ »: match the sentence under it.
check "« Key valid » shown" "for i in 1 2 3 4 5; do on_screen \"$(label key_check_valid_body)\" && break; sleep 2; false; done"
shot 6-active-key-valid
check "no key in the UI tree" "no_key_in_ui"

echo "--- 7. Restart: both keys kept, « Perso » still active"
open --es screen Settings
check "« Perso » active after a restart" "reveal Perso && row_says Perso \"$ACTIVE\""
check "« Pro » kept after a restart" "row_says Pro \"$IDLE\""
shot 7-after-restart
echo "key files: $($ADB shell run-as $PKG ls -l no_backup/mistral_keys 2>&1 | tr '\n' ' ')"
check "no key in the key files nor keys.json" "! $ADB shell run-as $PKG sh -c 'cat no_backup/mistral_keys/*' | grep -q -a -e '$KEY1' -e '$KEY2'"

echo "--- 8. Delete « Pro », then the last key"
tap "$(label key_delete_one Pro)"
check "dialog « Delete Pro? »" "on_screen \"$(label key_delete_title Pro)\""
shot 8-delete-dialog
tap "$(label key_delete_confirm)"
check "« Key Pro deleted » shown" "reveal \"$(label key_deleted Pro)\""
check "1 key file left" "[ \"\$(key_files)\" = 1 ]"
tap "$(label key_delete_one Perso)"
check "last key: the dialog says Dixi cannot dictate" "on_screen \"$(label key_delete_last_body)\""
tap "$(label key_delete_confirm)"
check "back to « No key »" "reveal \"$(label key_status_none)\""
check "no key file left" "[ \"\$(key_files)\" = 0 ]"
shot 9-no-key-again

$ADB logcat -d > "$OUT/logcat.txt"
check "no key in the log" "! grep -q -e '$KEY1' -e '$KEY2' -e '$WRONG_KEY' '$OUT/logcat.txt'"
check "no crash" "! $ADB logcat -b crash -d | grep -q $PKG"
echo "== backup"
$ADB shell dumpsys package $PKG | grep -E "flags=|privateFlags=" | head -2
check "app data not backed up (no ALLOW_BACKUP flag)" "! $ADB shell dumpsys package $PKG | grep 'flags=' | head -1 | grep -q ALLOW_BACKUP"
echo "checks failed: $FAILS"
[ "$FAILS" = 0 ]
