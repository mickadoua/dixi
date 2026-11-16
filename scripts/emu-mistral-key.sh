#!/usr/bin/env bash
# Emulator proof for the « Clé API Mistral » section of screen 10, dark neon theme:
# no key, key saved, key refused (a fake key: « Tester la clé » gets 401 from Mistral).
# Also checks: key kept encrypted across a restart, never in logcat nor the UI tree,
# key file excluded from backups.
# Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/emu-mistral-key.sh <out-dir> [apk]
# One dark theme since the neon charter: MODES="light dark" is no longer needed.
# Never use a real key here: FAKE_KEY below is not one.
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId
OUT=${1:-proof-mistral-key}
APK=${2:-app/build/outputs/apk/debug/app-debug.apk}
FAKE_KEY=fausse-cle-de-preuve-0000000000
mkdir -p "$OUT"
shot() { dump; $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"; }
open() { $ADB shell am start -S -W -n $PKG/$NS.MainActivity "$@" >/dev/null; waitfor; }
# Screen 10 scrolls: swipe up until the key section's « Tester la clé » button is on screen.
to_key() {
    for i in 1 2 3 4 5; do
        has 'text="Tester la clé"' && return 0
        $ADB shell input swipe 540 1600 540 700 400; sleep 2
    done
}
night() { $ADB shell cmd uimode night "$1" >/dev/null; sleep 3; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
waitfor() {
    for i in $(seq 1 20); do
        $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG" && break
        sleep 1
    done
    sleep 4
}
# Fresh dump each time: a failed dump leaves the previous file on the device (stale).
# A loaded host often shows « System UI isn't responding » over the app: answer « Wait ».
dump() {
    for attempt in 1 2 3; do
        $ADB shell rm -f /sdcard/ui.xml
        for i in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break; sleep 3; done
        $ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
        grep -q "isn't responding" "$OUT/ui.xml" || return 0
        local wait_xy
        wait_xy=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'text="Wait"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
        echo "ANR dialog: tap Wait at ${wait_xy:-?}"
        $ADB shell input tap ${wait_xy:-324 1368}
        sleep 3
    done
}
has() { dump; grep -q "$1" "$OUT/ui.xml"; }
# Taps the first node whose text or content-desc starts with $1 (is exactly $1 with $2 = exact).
tap() {
    wake
    dump
    local xy
    xy=$(python3 - "$OUT/ui.xml" "$1" "${2:-}" <<'PY'
import re, sys
want = sys.argv[2] + ('"' if sys.argv[3] == 'exact' else '')
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if f'text="{want}' in node or f'content-desc="{want}' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
)
    echo "tap '$1' at ${xy:-?}"
    [ -n "$xy" ] && $ADB shell input tap $xy
    sleep 2
}
hide_keyboard() {
    $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent KEYCODE_BACK
    sleep 2
}
# Length of the text in the (password) field, read from the UI tree.
typed_length() {
    dump
    python3 - "$OUT/ui.xml" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1]).read()):
    if 'EditText' in node:
        print(len(re.search(r' text="([^"]*)"', node).group(1))); break
else:
    print(0)
PY
}
# A loaded emulator drops characters of a long `input text`: type small chunks, check, retry.
save_fake_key() {
    tap "Coller la clé"
    for attempt in 1 2 3; do
        for i in $(seq 0 4 $((${#FAKE_KEY} - 1))); do
            $ADB shell input text "${FAKE_KEY:$i:4}"
            sleep 0.5
        done
        sleep 1
        local n
        n=$(typed_length)
        echo "typed $n of ${#FAKE_KEY} characters (attempt $attempt)"
        # 0: the tree hides the password text, nothing to check against.
        [ "$n" = "${#FAKE_KEY}" ] || [ "$n" = 0 ] && break
        $ADB shell input keyevent KEYCODE_MOVE_END
        $ADB shell input keyevent $(for k in $(seq 1 40); do printf 'KEYCODE_DEL '; done)
        sleep 1
    done
    echo "fake key readable in the UI tree while typed: $(grep -c "$FAKE_KEY" "$OUT/ui.xml")"
    hide_keyboard
    tap "Enregistrer"
    sleep 2
}
test_key() {
    tap "Tester la clé"
    for i in 1 2 3 4 5 6; do
        sleep 3
        has 'text="\(Clé valide\|Test : Mistral refuse\|Pas de réseau\|Service Mistral\)' && break
    done
    grep -o 'text="\(Clé valide[^"]*\|Test : Mistral refuse[^"]*\|Pas de réseau[^"]*\|Service Mistral[^"]*\)"' "$OUT/ui.xml" | sort -u | sed 's/^/test result: /'
}
status() { dump; grep -o 'text="\(Aucune clé[^"]*\|Clé enregistrée\|Clé refusée par Mistral. Vérifiez-la ou collez[^"]*\)"' "$OUT/ui.xml" | head -1 | sed "s/^/status $1: /"; }

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell pm clear $PKG >/dev/null
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell settings put global hide_error_dialogs 1
$ADB shell locksettings set-disabled true >/dev/null 2>&1
wake; $ADB shell wm dismiss-keyguard
sleep 15
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
wake; $ADB logcat -c

# Throwaway pass: the first screenshots after a cold boot often come out black.
night no
open --es screen Settings
shot warmup

for mode in ${MODES:-dark}; do
    [ $mode = dark ] && night yes || night no
    $ADB shell run-as $PKG rm -rf no_backup
    open --es screen Settings; to_key
    status "$mode no key"; shot "$mode-1-aucune-cle"
    save_fake_key
    status "$mode saved"; shot "$mode-2-cle-enregistree"
    echo "key file: $($ADB shell run-as $PKG ls -l no_backup 2>&1 | tr '\n' ' ')"
    echo "fake key readable in the key file: $($ADB shell run-as $PKG cat no_backup/mistral_key.bin | grep -c "$FAKE_KEY")"
    test_key
    status "$mode refused"; shot "$mode-3-cle-refusee"
    # Restart: the status comes back from the files (Keystore decrypts on the next dictation).
    open --es screen Settings; to_key
    status "$mode after restart"
done

# Delete through the dialog: back to « Aucune clé ».
tap "Supprimer la clé"
tap "Supprimer" exact
sleep 2
status "after delete"; shot dark-4-supprimee
echo "key files after delete: $($ADB shell run-as $PKG ls no_backup 2>&1 | tr '\n' ' ')"
night no

echo "fake key in logcat: $($ADB logcat -d | grep -c "$FAKE_KEY")"
$ADB logcat -d > "$OUT/logcat.txt"
echo "== backup"
$ADB shell dumpsys package $PKG | grep -E "flags=|privateFlags=" | head -2
$ADB shell bmgr backupnow $PKG 2>&1 | head -5
echo "proof done"
