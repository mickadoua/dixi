#!/usr/bin/env bash
# Emulator proof for the bubble panel (DMC-237), over Contacts, fake Voxtral.
# Real flow: listening panel, Terminer, suggestion, word tap, Valider + toast, Annuler, Refaire.
# Then captures with the mockup text, next to mockups 09 and 10.
#   /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-panel.sh <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
OUT=${1:-panel-proof-out}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
log() { $ADB logcat -d -s VoxbulleProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
count() { log | grep -c "$1"; }
limits() { $ADB shell am broadcast -n $PKG/.recording.DebugRecordingReceiver "$@" >/dev/null; }
contacts() { $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4; }
show_bubble() { $ADB shell am start -W -n $PKG/.MainActivity --ez showBubble true >/dev/null; sleep 3; }
close_dialogs() { $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
wait_log() { for _ in $(seq 1 "${2:-10}"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
wait_more() { for _ in $(seq 1 "${3:-10}"); do [ "$(count "$1")" -gt "$2" ] && return 0; sleep 1; done; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
audio_files() { $ADB shell run-as $PKG ls cache 2>/dev/null | grep -c 'dictation-.*\.m4a'; }
panel() { $ADB shell am broadcast -a tech.dmconsulting.voxbulle.debug.SET_BUBBLE_STATE "$@" >/dev/null; }
tap_bubble() {
  local n; n=$(count "bubble tapped")
  for _ in 1 2 3; do
    wake; $ADB shell input tap $XY
    for _ in 1 2 3 4; do [ "$(count 'bubble tapped')" -gt "$n" ] && return 0; sleep 0.5; done
  done
  return 1
}
# Bounds "x1 y1 x2 y2" of the first node whose text is $2, from a fresh dump into $1.
node_bounds() {
  $ADB shell rm -f /sdcard/ui.xml
  for _ in 1 2 3 4; do $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break; sleep 2; done
  $ADB shell cat /sdcard/ui.xml > "$OUT/$1" 2>/dev/null
  python3 - "$OUT/$1" "$2" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1], errors="replace").read()):
    if f'text="{sys.argv[2]}"' in node or f'content-desc="{sys.argv[2]}"' in node or sys.argv[2] == "EditText" and "EditText" in node:
        print(*re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups()); break
PY
}
tap_node() {
  local b; b=$(node_bounds "$1" "$2"); echo "$2 at: $b"
  [ -n "$b" ] || return 1
  set -- $b; wake; $ADB shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
to_suggestion() {
  local n; n=$(count "panel Suggestion")
  tap_bubble; wait_log "recording started" 10; sleep 2
  tap_node ui-t.xml "Terminer"
  wait_more "panel Suggestion" "$n" 20
}

$ADB shell getprop ro.build.version.sdk
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
wake; $ADB shell wm dismiss-keyguard
for p in $($ADB shell dumpsys package com.google.android.contacts | sed -n 's/^ *\(android\.permission\.[A-Z_]*\): granted=false.*/\1/p' | sort -u); do
  $ADB shell pm grant com.google.android.contacts "$p" 2>/dev/null
done
# First screenshots after a cold boot come out black: one throwaway pass while it settles.
sleep 20
contacts; shot 0-warmup; $ADB shell input keyevent KEYCODE_HOME
close_dialogs
$ADB logcat -c
show_bubble
$ADB shell input keyevent KEYCODE_HOME; sleep 2
contacts; close_dialogs; sleep 1
XY=$(center "bubble shown")
echo "bubble center: $XY"
[ -n "$XY" ] || { $ADB logcat -d > "$OUT/logcat-full.txt"; echo "bubble not shown"; exit 2; }
# The emulator mic is silent: no silence rule.
limits --ei min_amplitude 0

echo "--- 1. Listening panel, Terminer, suggestion"
tap_bubble; check "tap opens the listening panel" "wait_log 'panel Listening' 10"
sleep 3; shot 1-listening-real
node_bounds ui-listening.xml "Terminer" > "$OUT/terminer-bounds.txt"
check "Terminer is at least 48 dp high" "awk '{exit !((\$4 - \$2) >= 48 * 2.625)}' '$OUT/terminer-bounds.txt'"
tap_node ui-t1.xml "Terminer"
sleep 0.5; shot 2-processing-real
check "Terminer = bubble stop: processing" "wait_log 'bubble state Processing' 5"
check "suggestion shown" "wait_log 'panel Suggestion' 20"
sleep 1; shot 3-suggestion-real
check "nothing copied before Valider" "[ \$(count 'clipboard set') -eq 0 ]"
check "audio deleted after transcription" "[ \$(audio_files) -eq 0 ]"

echo "--- 2. Tap a word, Valider, toast"
E=$(node_bounds ui-s.xml "EditText"); echo "text at: $E"
set -- $E; wake; $ADB shell input tap $(( $1 + 60 )) $(( $2 + 40 )); sleep 2
shot 4-word-tapped
$ADB shell dumpsys input_method | grep -m1 -E "mInputShown|mIsInputViewShown" | tee "$OUT/ime.txt"
$ADB shell input keyevent KEYCODE_BACK; sleep 1
tap_node ui-v.xml "Valider"
check "Valider copies the text" "wait_log 'clipboard set' 5"
sleep 0.3; shot 5-copied-toast
check "bubble shows Texte prêt" "wait_log 'bubble state Ready' 5"
node_bounds ui-toast.xml "Texte copié" > "$OUT/toast-bounds.txt"
cat "$OUT/toast-bounds.txt"
wait_log "bubble state Idle" 5

echo "--- 3. Annuler deletes the audio"
COPIES=$(count "clipboard set")
tap_bubble; wait_log "recording started" 10; sleep 3
BEFORE=$(audio_files); echo "audio files while recording: $BEFORE"
tap_node ui-a.xml "Annuler"; sleep 2
check "an audio file existed while recording" "[ $BEFORE -ge 1 ]"
check "Annuler deletes the audio" "[ \$(audio_files) -eq 0 ]"
check "Annuler: back to rest, no transcription" "log | grep 'bubble state' | tail -1 | grep -q Idle && [ \$(count 'clipboard set') -eq $COPIES ]"
shot 6-after-cancel

echo "--- 4. Refaire listens again"
to_suggestion
STARTS=$(count "recording started")
tap_node ui-r.xml "Refaire"
check "Refaire starts a new recording" "wait_more 'recording started' $STARTS 10"
check "Refaire: listening panel again" "log | grep 'panel ' | tail -1 | grep -q Listening"
tap_node ui-a2.xml "Annuler"; sleep 2
check "no copy after Refaire then Annuler" "[ \$(count 'clipboard set') -eq $COPIES ]"

echo "--- 5. Captures with the mockup text (debug hook)"
TEXT="Bonjour, je vous confirme le rendez-vous de jeudi à dix heures."
panel --es panel Listening --el elapsedMs 12000 --efa levels 0.02,0.05,0.2,0.45,0.15,0.6,0.3,0.08,0.4,0.85,0.25,0.05,0.35,0.5,0.1,0.28,0.03,0.18,0.38,0.06,0.02
sleep 1; shot 09-listening
panel --es panel Processing; sleep 1; shot 09b-processing
panel --es panel Suggestion --es text "'$TEXT'" --ei mark 44
sleep 1; shot 10-suggestion
panel --es panel None
panel --es state Ready --el readyMs 6000; sleep 1; shot 01-toast

$ADB logcat -d -s VoxbulleProof:V AndroidRuntime:E > "$OUT/logcat.txt"
echo "--- VoxbulleProof log"; grep -E "panel|bubble state|clipboard|recording|FATAL" "$OUT/logcat.txt" | tail -60
echo "emu-panel done"
