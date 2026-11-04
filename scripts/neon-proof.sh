#!/usr/bin/env bash
# Emulator captures of the neon charter (DMC-226) for the side-by-side check with
# Zoé's mockups (DMC-213): onboarding 1, main screen, settings, offer, then the
# bubble states and the « Masquer » zone over Contacts. Run through emu-run:
#   /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/neon-proof.sh <out-dir> [setup screens bubble]
# Parts can run one by one in a session kept by emu-renew (each under 10 min).
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.voxbulle
OUT=${1:-neon-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { dismiss_anr; $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
log() { $ADB logcat -d -s VoxbulleProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
state() { $ADB shell am broadcast -p $PKG -a $PKG.debug.SET_BUBBLE_STATE --es state "$1" --el readyMs 60000 >/dev/null; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
focused() { $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG"; }
# A loaded emulator often shows « System UI isn't responding »: tap « Wait ».
dismiss_anr() {
  for _ in 1 2 3; do
    $ADB shell dumpsys window | grep -q "Not Responding" || return 0
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    B=$($ADB shell cat /sdcard/ui.xml | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/p' | head -1)
    if [ -n "$B" ]; then read -r a b c d <<< "$B"; $ADB shell input tap $(((a + c) / 2)) $(((b + d) / 2)); else $ADB shell input keyevent KEYCODE_BACK; fi
    echo "dismissed an ANR dialog"; sleep 2
  done
}
# The card label is drawn upper case; the accessibility tree keeps the string.
card_on() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml | grep -q 'Bulle active'; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
screen() {
  $ADB shell am force-stop $PKG; sleep 1
  $ADB shell am start -W -n $PKG/.MainActivity --es screen "$1" ${2:-} >/dev/null
  for _ in 1 2 3 4 5 6; do focused && break; sleep 1; done
  sleep 3
}

setup() {
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 0
# Light system mode: the app must stay dark anyway (single theme).
$ADB shell cmd uimode night no
wake; $ADB shell wm dismiss-keyguard
for p in $($ADB shell dumpsys package com.google.android.contacts | sed -n 's/^ *\(android\.permission\.[A-Z_]*\): granted=false.*/\1/p' | sort -u); do
  $ADB shell pm grant com.google.android.contacts "$p" 2>/dev/null
done
sleep 20
}
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
echo "screen ${W}x${H}"

screens() {
# Throwaway pass: the first screenshots after a cold boot come out black.
screen Welcome; shot 00-warmup
wake
screen Welcome; shot 08-onboarding-1
check "onboarding 1 in the foreground" focused
screen Settings; shot 06-reglages
screen Offer; shot 07-ecran-offre
$ADB logcat -c
screen Home "--ei seedHistory 1"; shot 05-ecran-principal-off
# The bubble card is the switch: turn the bubble on (overlay allowed by appops).
$ADB shell input tap $((W / 2)) $((H * 18 / 100))
# A loaded emulator redraws late: wait for the bubble, then for the card.
for _ in 1 2 3 4 5 6 7 8 9 10; do log | grep -q 'bubble shown' && break; sleep 1; done
for _ in 1 2 3 4 5 6 7 8 9 10; do card_on && break; sleep 2; done
sleep 2
shot 05-ecran-principal
check "bubble shown from the main screen card" "log | grep -q 'bubble shown'"
check "main screen card says « Bulle active »" card_on
}

bubble() {
# Bubble over Contacts.
$ADB shell input keyevent KEYCODE_HOME; sleep 2
$ADB shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact >/dev/null; sleep 4
$ADB shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1; sleep 1
XY=$(center "bubble shown")
echo "bubble center: $XY"
[ -n "$XY" ] || { echo "FAIL bubble not shown"; return; }
read -r X Y <<< "$XY"
for s in Idle Listening Processing Ready; do
  wake; state $s; sleep 1; shot "01-bulle-$s"
done
check "« Texte copié » toast beside the ready bubble" "log | grep -q 'bubble toast shown'"
state Idle; sleep 1
wake
$ADB shell "input motionevent DOWN $X $Y; for i in 1 2 3 4 5 6 7 8; do input motionevent MOVE \$(( $X + ($W / 2 - $X) * i / 16 )) \$(( $Y + ($H * 60 / 100 - $Y) * i / 16 )); done"
sleep 1; shot 03-bulle-glisser-masquer
$ADB shell "for d in 0 40 80 120; do input motionevent MOVE $((W / 2)) \$(( $H - 120 - d )); done; input motionevent MOVE $((W / 2)) $((H - 240)); input motionevent MOVE $((W / 2)) $((H - 260))"
sleep 1; shot 03-bulle-sur-zone
$ADB shell input motionevent UP $((W / 2)) $((H - 260))
sleep 2
check "drop on the cross hides the bubble" "log | grep -q 'bubble hidden'"
sleep 1; shot 04-bulle-masquee-lisere
EDGE=$(center "bubble edge shown")
check "hidden bubble leaves the 20 dp edge tab" "[ -n '$EDGE' ]"
if [ -n "$EDGE" ]; then
  read -r EX EY <<< "$EDGE"
  # The tab window is 48 dp wide from the screen side: tap its middle.
  $ADB shell input tap $((EX < W / 2 ? 63 : W - 63)) "$EY"; sleep 2
  check "tap on the edge tab shows the bubble again" "log | grep -q 'bubble shown from the edge'"
  shot 04-bulle-reaffichee
fi
$ADB logcat -d > "$OUT/logcat.txt"
}

for part in ${2:-setup screens bubble}; do $part; done
