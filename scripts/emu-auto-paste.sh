#!/usr/bin/env bash
# Emulator proof for auto-paste, fake Voxtral. One short part per session.
# Needs adb ($ANDROID_HOME) and a debug APK:
#   APP_LANG=fr bash scripts/emu-auto-paste.sh <part> <out-dir>     (APP_LANG: fr by default, or en)
# The app's labels come from its own strings (scripts/emu-labels.sh), in APP_LANG.
# Part s: screen 10 and screen 8 (« Not now », then « Agree and turn on », « Continue » opens
#         Android's accessibility settings), switch on when back with the service on.
# Part r: switches read again on return (found in review): bubble without the overlay
#         permission, auto-paste with the service off.
# Part p: dictation over Contacts, cursor in « First name »: the text is pasted, no « Text copied ».
# Part f: fallbacks: service off, then service on but no editable field: clipboard + notification.
# (Parts stay short: the emulator is killed for lack of memory after ~4 min.)
# The service is turned on with `settings put secure` (what the switch in Android's page writes):
# the system page is shown by the app, but tapping its switch and dialog is too slow here.
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
SERVICE=$PKG/$NS.paste.AutoPasteService
PART=${1:-s}
OUT=${2:-auto-paste-proof-out}/$PART
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
. "$(dirname "$0")/emu-labels.sh"
AUTO_PASTE=$(label settings_auto_paste)
SHOW_BUBBLE=$(label settings_bubble)
ACCEPT=$(label disclosure_accept)
# Words of the demo dictation (res demo_raw_text, cleaned up or not). The demo text follows the
# phone's language, not APP_LANG (FakeTranscriber reads it with the application context).
DEMO_WORDS='dixi test text\|texte de test dixi'
mkdir -p "$OUT"
trap '$ADB logcat -d > "$OUT/logcat-full.txt" 2>/dev/null; $ADB logcat -b crash -d > "$OUT/logcat-crash.txt" 2>/dev/null' EXIT
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
log() { $ADB logcat -d -s DixiProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
count() { log | grep -c "$1"; }
limits() { $ADB shell am broadcast -n $PKG/$NS.recording.DebugRecordingReceiver "$@" >/dev/null; }
open() { $ADB shell am start -S -W -n $PKG/$NS.MainActivity "$@" >/dev/null; sleep 3; }
contacts() { $ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4; }
# Contacts' editor in front (its first start after a cold boot is slow).
contacts_editor() {
  for _ in 1 2 3; do contacts; close_dialogs; wait_focus "EditorActivity" 20 && return 0; done
  return 1
}
show_bubble() { $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null; sleep 3; }
close_dialogs() { $ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
focus() { $ADB shell dumpsys window | grep mCurrentFocus; }
wait_log() { for _ in $(seq 1 "${2:-10}"); do log | grep -q "$1" && return 0; sleep 1; done; return 1; }
wait_more() { for _ in $(seq 1 "${3:-10}"); do [ "$(count "$1")" -gt "$2" ] && return 0; sleep 1; done; return 1; }
wait_focus() { for _ in $(seq 1 "${2:-20}"); do focus | grep -q "$1" && { sleep 2; return 0; }; sleep 1; done; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
service_on() { $ADB shell settings put secure enabled_accessibility_services "$SERVICE"; $ADB shell settings put secure accessibility_enabled 1; }
service_off() { $ADB shell settings put secure enabled_accessibility_services '""'; $ADB shell settings put secure accessibility_enabled 0; }
notif_count() { log | grep -c "copied notification posted"; }
dump() {
  $ADB shell rm -f /sdcard/ui.xml
  for _ in 1 2 3 4 5; do $ADB shell uiautomator dump /sdcard/ui.xml 2>/dev/null | grep -q dumped && break; sleep 3; done
  $ADB shell cat /sdcard/ui.xml > "$OUT/$1" 2>/dev/null
}
# Center of the first node whose text or description is $2, from a fresh dump into $1.
node_center() {
  dump "$1"
  python3 - "$OUT/$1" "$2" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1], errors="replace").read()):
    if f'text="{sys.argv[2]}"' in node or f'content-desc="{sys.argv[2]}"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
}
# Center of $2 in the dump $1 already taken (no new dump: each one costs emulator memory).
center_in() {
  python3 - "$OUT/$1" "$2" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1], errors="replace").read()):
    if f'text="{sys.argv[2]}"' in node or f'content-desc="{sys.argv[2]}"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2); break
PY
}
# After a cold boot, « System UI isn't responding » can cover the screen: « Wait » closes it.
settle() {
  for _ in 1 2 3; do
    dump ui-anr.xml
    local xy; xy=$(center_in ui-anr.xml "Wait"); [ -n "$xy" ] || return 0
    echo "ANR dialog: Wait at $xy"; $ADB shell input tap $xy; sleep 5
  done
}
tap_text() {
  local xy; xy=$(node_center "$1" "$2")
  [ -n "$xy" ] || { sleep 5; xy=$(node_center "$1" "$2"); }
  echo "tap '$2' at ${xy:-?}"
  [ -n "$xy" ] && { wake; $ADB shell input tap $xy; sleep 3; }
  [ -n "$xy" ]
}
# checked="true|false" of the switch row whose title is $2 (the checkable node around the title).
switch_state() {
  dump "$1"
  python3 - "$OUT/$1" "$2" <<'PY'
import re, sys
nodes = re.findall(r'<node [^>]*>', open(sys.argv[1], errors="replace").read())
box = lambda n: tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
title = next((box(n) for n in nodes if f'text="{sys.argv[2]}"' in n), None)
if title:
    x, y = (title[0] + title[2]) // 2, (title[1] + title[3]) // 2
    for n in nodes:
        a, b, c, d = box(n)
        if 'checkable="true"' in n and a <= x <= c and b <= y <= d:
            print(re.search(r'checked="(\w+)"', n).group(1)); break
PY
}
tap_bubble() {
  local n; n=$(count "bubble tapped")
  for _ in 1 2 3; do
    wake; $ADB shell input tap $XY
    for _ in 1 2 3 4; do [ "$(count 'bubble tapped')" -gt "$n" ] && return 0; sleep 0.5; done
  done
  return 1
}
# First tap starts, second tap stops: the text leaves right away, no panel (DMC-768).
dictate() {
  tap_bubble; wait_log "recording started" 20; sleep 2
  tap_bubble
}
# Screen 10, auto-paste switch on through screen 8 (DataStore choice). The service is turned on
# first, so the app does not open Android's settings (proved in part s): that app alone gets the
# emulator killed for lack of memory. After `open`: Android turns off the services of a force-stopped app.
accept_auto_paste() {
  open --es screen Settings; wait_focus "$PKG"
  service_on; wait_log "auto-paste service connected" 15
  settle
  dump ui-10.xml
  BUBBLE_ROW=$(center_in ui-10.xml "$SHOW_BUBBLE")
  wake; $ADB shell input tap $(center_in ui-10.xml "$AUTO_PASTE"); sleep 3
  tap_text ui-8.xml "$ACCEPT"
  check "Android's settings not opened (service already on)" "focus | grep -q '$PKG'"
  check "screen 8 accepted (choice saved)" "[ \"\$(switch_state ui-10-after.xml \"$AUTO_PASTE\")\" = true ]"
}
# From screen 10: « Show the bubble » on, then Contacts in front, the bubble over it; sets XY.
# (The showBubble extra only works on a fresh activity, and a force-stop turns the service off.)
start_bubble() {
  local n; n=$(count "bubble shown")
  echo "tap '$SHOW_BUBBLE' at $BUBBLE_ROW"; wake; $ADB shell input tap $BUBBLE_ROW
  wait_more "bubble shown" "$n" 15
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  contacts_editor || echo "Contacts editor not in front"
  XY=$(center "bubble shown"); echo "bubble center: $XY"
}
# Cursor in « First name », keyboard closed (the field keeps the focus).
focus_first_name() {
  F=$(node_center ui-contacts.xml "First name"); [ -n "$F" ] || F="540 1026"
  wake; $ADB shell input tap $F; sleep 3
  # The keyboard pushes the emulator over its memory cap during the dictation: stop it
  # (the field keeps the focus). Not `am kill-all`: it kills Dixi and its bubble too.
  $ADB shell am force-stop com.google.android.inputmethod.latin; sleep 2
  focus | tee "$OUT/focus-before-dictation.txt"
}
occurrences() { dump "$1"; grep -io "$DEMO_WORDS" "$OUT/$1" | wc -l; }
# The Contacts editor does not show its name fields in the uiautomator dump: « Save », then the
# demo words counted in the name of the newest contact (Contacts' own provider).
# Contacts saves in a background job: wait for the row.
saved_name_occurrences() {
  tap_text "$1" "Save" >/dev/null
  for _ in $(seq 1 10); do
    $ADB shell content query --uri content://com.android.contacts/raw_contacts \
      --projection display_name --sort "_id\ DESC" | head -1 > "$OUT/saved-contact.txt"
    grep -q "^Row" "$OUT/saved-contact.txt" && break; sleep 2
  done
  grep -io "$DEMO_WORDS" "$OUT/saved-contact.txt" | wc -l
}

$ADB shell getprop ro.build.version.sdk
for _ in $(seq 1 60); do
  [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && [ "$($ADB shell getprop sys.user.0.ce_available | tr -d '\r')" = true ] && break
  sleep 5
done
$ADB install -r -g "$APK" || exit 1
$ADB shell pm clear $PKG >/dev/null
set_app_lang
# `pm clear` also takes back the permissions granted by `install -g`.
$ADB shell pm grant $PKG android.permission.RECORD_AUDIO
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
service_off
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
wake; $ADB shell wm dismiss-keyguard
for p in $($ADB shell dumpsys package com.google.android.contacts | sed -n 's/^ *\(android\.permission\.[A-Z_]*\): granted=false.*/\1/p' | sort -u); do
  $ADB shell pm grant com.google.android.contacts "$p" 2>/dev/null
done
# First screenshots after a cold boot come out black: one throwaway pass while it settles.
# Part s: first screenshots after a boot can come out black, one throwaway pass while it settles.
# Parts p, f, r skip it. Run them with `--boot cold`: from the snapshot, the emulator is killed
# for lack of memory after 3 to 4 min (5120 MB container cap).
if [ "$PART" = s ]; then sleep 20; contacts; shot 0-warmup; $ADB shell input keyevent KEYCODE_HOME; else sleep 30; settle; fi
close_dialogs
$ADB logcat -c

if [ "$PART" = s ]; then
  echo "--- s1. Screen 10: auto-paste off by default"
  open --es screen Settings; wait_focus "$PKG"
  shot 1-settings-default
  check "auto-paste off by default" "[ \"\$(switch_state ui-10a.xml \"$AUTO_PASTE\")\" = false ]"
  echo "--- s2. Screen 8, « $(label disclosure_decline) » changes nothing"
  tap_text ui-10b.xml "$AUTO_PASTE"
  shot 2-disclosure
  # The section titles are upper-cased on screen: match the text under « What it reads ».
  check "screen 8 shown before any accessibility page" "node_center ui-8a.xml \"$(label disclosure_reads)\" | grep -q ."
  check "screen 8 says what the service reads, before the buttons" "node_center ui-8b.xml \"$(label disclosure_intro)\" | grep -q ."
  tap_text ui-8c.xml "$(label disclosure_decline)"
  check "« $(label disclosure_decline) »: back to screen 10, Android settings not opened" "focus | grep -q '$PKG'"
  check "auto-paste still off" "[ \"\$(switch_state ui-10c.xml \"$AUTO_PASTE\")\" = false ]"
  shot 3-after-not-now
  echo "--- s3. « $ACCEPT », then « $(label key_continue) », opens Android's accessibility settings"
  tap_text ui-10d.xml "$AUTO_PASTE"
  tap_text ui-8d.xml "$ACCEPT"
  check "dialog: Android will open its settings" "node_center ui-8e.xml \"$(label disclosure_next_step)\" | grep -q ."
  shot 3-next-step-dialog
  tap_text ui-8f.xml "$(label key_continue)"
  check "accessibility settings opened" "wait_focus com.android.settings 15"
  focus | tee "$OUT/focus-accessibility.txt"
  shot 4-accessibility-settings
  # Android's page uses the system language, not the app's.
  check "service listed by Android" "dump ui-a11y.xml; grep -q -e \"$(label auto_paste_service_label)\" -e \"$(APP_LANG=en label auto_paste_service_label)\" '$OUT/ui-a11y.xml'"
  service_on
  $ADB shell input keyevent KEYCODE_BACK
  check "back to screen 10" "wait_focus '$PKG' 15"
  check "auto-paste on after the service is on (read on return)" "[ \"\$(switch_state ui-10e.xml \"$AUTO_PASTE\")\" = true ]"
  check "service connected" "wait_log 'auto-paste service connected' 10"
  shot 5-settings-auto-paste-on
fi

if [ "$PART" = r ]; then
  echo "--- r1. Read again on return: service off in Android, overlay permission taken back"
  accept_auto_paste
  check "auto-paste on" "[ \"\$(switch_state ui-10.xml \"$AUTO_PASTE\")\" = true ]"
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  service_off
  $ADB shell appops set $PKG SYSTEM_ALERT_WINDOW deny
  $ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; wait_focus "$PKG"
  check "auto-paste off after the service was turned off in Android" "[ \"\$(switch_state ui-10f.xml \"$AUTO_PASTE\")\" = false ]"
  check "bubble off after the overlay permission was taken back" "[ \"\$(switch_state ui-10f.xml \"$SHOW_BUBBLE\")\" = false ]"
  shot 6-settings-after-return
  echo "--- r2. Bubble on, permission taken back in Android, back: the switch follows"
  $ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  tap_text ui-10g.xml "$SHOW_BUBBLE"
  check "bubble on" "[ \"\$(switch_state ui-10h.xml \"$SHOW_BUBBLE\")\" = true ]"
  $ADB shell input keyevent KEYCODE_HOME; sleep 2
  $ADB shell appops set $PKG SYSTEM_ALERT_WINDOW deny
  $ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; wait_focus "$PKG"
  check "bubble switch off on return" "[ \"\$(switch_state ui-10i.xml \"$SHOW_BUBBLE\")\" = false ]"
  shot 7-bubble-after-revoke
  $ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
fi

if [ "$PART" = p ]; then
  echo "--- p1. Auto-paste on, dictation over Contacts with the cursor in « First name »"
  accept_auto_paste
  limits --ei min_amplitude 0
  start_bubble; [ -n "$XY" ] || { echo "bubble not shown"; exit 2; }
  focus_first_name
  shot 1-cursor-in-first-name
  NOTIFS=$(notif_count)
  dictate
  check "text copied (clipboard, before the paste)" "wait_log 'clipboard set' 10"
  check "pasted by the service" "wait_log 'auto-paste: pasted' 10"
  sleep 0.3; shot 2-pasted-toast
  check "bubble ready (« Text ready »)" "wait_log 'bubble state Ready' 5"
  sleep 3
  check "no « Text copied » notification" "[ \$(notif_count) -eq $NOTIFS ]"
  shot 3-pasted
  check "text in « First name », once (saved contact)" "[ \$(saved_name_occurrences ui-pasted.xml) -eq 1 ]"
  log | grep -E "bubble state|clipboard|auto-paste|copied" | tee "$OUT/chain.txt"
fi

if [ "$PART" = f ]; then
  echo "--- f1. Option on, service turned off in Android: clipboard + notification"
  accept_auto_paste
  service_off; sleep 2
  limits --ei min_amplitude 0
  start_bubble; [ -n "$XY" ] || { echo "bubble not shown"; exit 2; }
  focus_first_name
  NOTIFS=$(notif_count)
  dictate
  check "service off logged" "wait_log 'auto-paste: service off' 10"
  check "« Text copied » notification" "wait_more 'copied notification posted' $NOTIFS 10"
  sleep 0.3; shot 1-service-off-copied-toast
  check "nothing pasted in « First name »" "[ \$(occurrences ui-f1.xml) -eq 0 ]"
  wait_log "bubble state Idle" 10
  echo "--- f2. Service on, no editable field (home screen): clipboard + notification"
  service_on
  check "service connected" "wait_log 'auto-paste service connected' 15"
  $ADB shell input keyevent KEYCODE_HOME; sleep 3
  NOTIFS=$(notif_count)
  dictate
  check "no editable field logged" "wait_log 'auto-paste: no editable field' 15"
  check "« Text copied » notification" "wait_more 'copied notification posted' $NOTIFS 10"
  sleep 0.3; shot 2-no-field-copied-toast
  log | grep -E "bubble state|clipboard|auto-paste|copied" | tee "$OUT/chain.txt"
fi

check "no crash" "! $ADB logcat -b crash -d | grep -q $PKG"
echo "emu-auto-paste part $PART done"
