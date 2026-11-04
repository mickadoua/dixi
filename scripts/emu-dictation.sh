#!/usr/bin/env bash
# Emulator proof for the dictation chain (DMC-139), over Contacts, fake Voxtral.
# Part a: full dictation, timing, single dictation, notification, paste.
# Part b: errors (nothing heard, microphone denied), « Voir le texte », « Masquer la bulle ».
# Run each part in its own session:
#   /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-dictation.sh a <out-dir>
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
PART=${1:-a}
OUT=${2:-dictation-proof-out}/$PART
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
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
# Taps the bubble until the log counts one more tap (injected taps get lost on a loaded emulator).
tap_bubble() {
  local n; n=$(count "bubble tapped")
  for _ in 1 2 3; do
    wake; $ADB shell input tap $XY
    for _ in 1 2 3 4; do [ "$(count 'bubble tapped')" -gt "$n" ] && return 0; sleep 0.5; done
  done
  return 1
}
focus() { $ADB shell dumpsys window | grep mCurrentFocus; }
# The shade opens and closes seconds late on a loaded emulator: wait on the window focus.
open_shade() {
  for _ in 1 2 3; do
    $ADB shell cmd statusbar expand-notifications
    for _ in 1 2 3 4 5 6; do focus | grep -q NotificationShade && { sleep 2; return 0; }; sleep 1; done
  done
  return 1
}
close_shade() {
  for _ in 1 2 3; do
    $ADB shell cmd statusbar collapse
    for _ in 1 2 3 4 5 6; do focus | grep -q NotificationShade || { sleep 1; return 0; }; sleep 1; done
  done
  $ADB shell input keyevent KEYCODE_BACK; sleep 2
}
audio_files() { $ADB shell run-as $PKG ls cache 2>/dev/null | grep -c 'dictation-.*\.m4a'; }
notif() { $ADB shell dumpsys notification --noredact | grep -A40 "pkg=$PKG" | grep -E "NotificationRecord|id=2|timeoutAfter|android.title|android.text|actions|\[[0-9]\] \"|channel=" ; }
# Center of the first node whose text is $2, from a fresh dump into $1.
node_center() {
  $ADB shell rm -f /sdcard/ui.xml
  for _ in 1 2 3 4; do $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break; sleep 2; done
  $ADB shell cat /sdcard/ui.xml > "$OUT/$1" 2>/dev/null
  python3 - "$OUT/$1" "$2" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1], errors="replace").read()):
    if f'text="{sys.argv[2]}"' in node or f'content-desc="{sys.argv[2]}"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
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
$ADB shell dumpsys activity activities | grep -m1 topResumedActivity | tee "$OUT/top-activity.txt"
shot 1-idle-over-contacts

if [ "$PART" = a ]; then
  echo "--- a1. Full dictation (emulator mic is silent: silence rule off)"
  limits --ei min_amplitude 0
  tap_bubble; check "tap starts listening" "wait_log 'recording started' 10"
  sleep 1; shot 2-listening
  $ADB shell dumpsys activity services $PKG | grep -E 'ServiceRecord|isForeground|foregroundServiceType' | tee "$OUT/fgs.txt"
  sleep 3
  tap_bubble
  # Single dictation: a tap while the text is processed does nothing.
  wake; $ADB shell input tap $XY
  shot 3-processing
  check "text copied" "wait_log 'clipboard set' 20"
  shot 4-after-copy
  # Android 13+ shows its own clipboard preview: kept in 4-after-copy to judge the double.
  wait_log "copied notification posted" 15; POSTED=$SECONDS
  notif | tee "$OUT/notification.txt"
  open_shade
  shot 5-notification
  close_shade
  wait_log "bubble state Idle" 10
  log | grep -E "bubble state|tapped|recording finished|clipboard|copied" | tee "$OUT/chain.txt"
  check "tap during processing ignored" "grep -q 'tapped in state Processing' '$OUT/chain.txt'"
  check "one text only" "[ \$(grep -c 'clipboard set' '$OUT/chain.txt') -eq 1 ]"
  check "text copied <= 3000 ms after stop" "[ \$(sed -n 's/.*clipboard set \([0-9]*\) ms.*/\1/p' '$OUT/chain.txt') -le 3000 ]"
  check "states Listening > Processing > Ready > Idle" "grep 'bubble state' '$OUT/chain.txt' | sed 's/.*state //' | tr '\n' ' ' | grep -q 'Listening Processing Ready Idle'"
  check "audio deleted" "[ \$(audio_files) -eq 0 ]"
  check "notification posted on channel copied" "grep -q 'channel=copied' '$OUT/notification.txt'"
  check "notification has 2 actions" "grep -q 'Voir le texte' '$OUT/notification.txt' && grep -q 'Masquer la bulle' '$OUT/notification.txt'"
  while [ $((SECONDS - POSTED)) -lt 8 ]; do sleep 1; done
  notif > "$OUT/notification-after-6s.txt"
  check "notification gone after 6 s" "! grep -q 'id=2 ' '$OUT/notification-after-6s.txt'"
  echo "--- a2. Paste into Contacts"
  focus | tee "$OUT/focus-before-paste.txt"
  F=$(node_center ui-contacts.xml "First name"); [ -n "$F" ] || F="540 1026"
  for _ in 1 2 3; do
    wake; $ADB shell input tap $F; sleep 3
    $ADB shell input keyevent KEYCODE_PASTE; sleep 3
    node_center ui-pasted.xml "x" >/dev/null
    grep -q 'texte de test voxbulle' "$OUT/ui-pasted.xml" && break
  done
  shot 6-pasted
  check "cleaned text pasted" "grep -q 'Bonjour ceci est un texte de test voxbulle.' '$OUT/ui-pasted.xml'"
fi

if [ "$PART" = b ]; then
  echo "--- b1. Nothing heard (silent emulator mic, default silence rule)"
  limits
  $ADB shell cmd clipboard 2>/dev/null | head -1
  CLIPS=$(count "clipboard set")
  tap_bubble; wait_log "recording started" 10; sleep 2
  tap_bubble; sleep 0.3
  shot 7-error-nothing-heard
  check "error shown" "wait_log 'dictation error: Je n' 5"
  check "nothing copied" "[ \$(count 'clipboard set') -eq $CLIPS ]"
  check "audio deleted" "[ \$(audio_files) -eq 0 ]"
  sleep 4
  echo "--- b2. Microphone denied"
  $ADB shell pm revoke $PKG android.permission.RECORD_AUDIO
  sleep 2; XY=$(center "bubble shown"); [ -n "$XY" ] || { show_bubble; contacts; XY=$(center "bubble shown"); }
  tap_bubble; sleep 0.3
  shot 8-error-mic-denied
  check "mic error shown" "wait_log 'dictation error: Voxbulle a besoin du micro' 5"
  log | grep -E "bubble state|dictation error" | tee "$OUT/errors.txt"
  $ADB shell pm grant $PKG android.permission.RECORD_AUDIO
  sleep 4
  echo "--- b3. « Voir le texte » opens the history"
  sleep 2; XY=$(center "bubble shown"); [ -n "$XY" ] || { show_bubble; contacts; XY=$(center "bubble shown"); }
  limits --ei min_amplitude 0
  tap_bubble; wait_log "recording started" 10; sleep 2; tap_bubble
  wait_log "copied notification posted" 15
  open_shade
  V=$(node_center ui-shade.xml "Voir le texte"); echo "Voir le texte at: $V"
  shot 9-shade-actions
  [ -n "$V" ] && $ADB shell input tap $V; sleep 4
  $ADB shell dumpsys window | grep mCurrentFocus | tee "$OUT/focus-history.txt"
  shot 10-history
  node_center ui-history.xml "x" >/dev/null
  check "history opened with the text" "grep -q 'MainActivity' '$OUT/focus-history.txt' && grep -q 'Bonjour ceci est un texte de test voxbulle.' '$OUT/ui-history.xml'"
  echo "--- b4. « Masquer la bulle » hides the bubble"
  contacts; close_dialogs
  tap_bubble; wait_log "recording started" 10; sleep 2; tap_bubble
  sleep 1; wait_log "copied notification posted" 20
  for _ in 1 2 3; do
    open_shade
    H=$(node_center ui-shade2.xml "Masquer la bulle"); echo "Masquer la bulle at: $H"
    [ -n "$H" ] && $ADB shell input tap $H
    wait_log "bubble hidden" 4 && break
  done
  close_shade
  check "bubble hidden from the notification" "wait_log 'bubble hidden' 2"
  shot 11-hidden
fi

$ADB logcat -d -s VoxbulleProof:V AndroidRuntime:E > "$OUT/logcat.txt"
echo "--- VoxbulleProof log"; grep -E "VoxbulleProof|FATAL" "$OUT/logcat.txt" | tail -60
echo "emu-dictation part $PART done"
