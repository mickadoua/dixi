#!/usr/bin/env bash
# Emulator captures of the neon charter for the side-by-side check with
# the design mockups: onboarding 1, home screen, settings, offer, then the
# bubble over Contacts (DMC-768): its states, the wave while listening, the
# half-bubble tucked at the edge, and the « Masquer » zone.
# Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/neon-proof.sh <out-dir> [setup screens bubble]
# Parts can run one by one in the same emulator session (each under 10 min).
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
OUT=${1:-neon-proof-out}
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"
shot() { dismiss_anr; $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
log() { $ADB logcat -d -s DixiProof; }
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
state() { $ADB shell am broadcast -p $PKG -a $NS.debug.SET_BUBBLE_STATE --es state "$1" --el readyMs 60000 >/dev/null; }
# Voice level for the wave while listening, 0 to 1.
level() { $ADB shell am broadcast -p $PKG -a $NS.debug.SET_BUBBLE_STATE --ef level "$1" >/dev/null; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; }
focused() { $ADB shell dumpsys window | grep -q "mCurrentFocus=.*$PKG"; }
# A loaded emulator often shows « System UI isn't responding »: tap « Wait ».
dismiss_anr() {
  for _ in 1 2 3; do
    $ADB shell dumpsys window | grep -q "Not Responding" || return 0
    $ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
    B=$($ADB shell cat /data/local/tmp/ui.xml | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/p' | head -1)
    if [ -n "$B" ]; then read -r a b c d <<< "$B"; $ADB shell input tap $(((a + c) / 2)) $(((b + d) / 2)); else $ADB shell input keyevent KEYCODE_BACK; fi
    echo "dismissed an ANR dialog"; sleep 2
  done
}
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
screen() {
  $ADB shell am force-stop $PKG; sleep 1
  $ADB shell am start -W -n $PKG/$NS.MainActivity --es screen "$1" ${2:-} >/dev/null
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
$ADB shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
CARD=$($ADB shell cat /data/local/tmp/ui.xml | grep -o 'text="\(Bubble off\|Bulle inactive\)"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/p' | head -1)
if [ -n "$CARD" ]; then read -r a b c d <<< "$CARD"; $ADB shell input tap $(((a + c) / 2)) $(((b + d) / 2)); else $ADB shell input tap $((W / 2)) $((H * 19 / 100)); fi
# A loaded emulator redraws late: wait for the bubble, then for the card.
for _ in 1 2 3 4 5 6 7 8 9 10; do log | grep -q 'bubble shown' && break; sleep 1; done
# Shot too early, the card still shows « Bulle désactivée » and the press ripple.
sleep 10
shot 05-ecran-principal
check "bubble shown from the main screen card" "log | grep -q 'bubble shown'"
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
# At rest: a 44 dp disc in a 48 dp touch window (mockups DMC-764 § 1).
DENSITY=$($ADB shell wm density | sed -n 's/.*: \([0-9]*\).*/\1/p' | tail -1)
# The app's overlay windows (bubble, its glow, the « Masquer » zone): the smallest is the bubble.
WIN=$($ADB shell dumpsys window windows | grep -A20 "Window{[^ ]* u0 $PKG}:" | sed -n 's/.*Requested w=\([0-9]*\) h=.*/\1/p' | sort -n | head -1); WIN=${WIN:-0}
echo "density ${DENSITY} dpi, smallest app window ${WIN} px ($((WIN * 160 / DENSITY)) dp)"
check "bubble window is 48 dp (44 dp disc)" "[ $((WIN * 160 / DENSITY)) -eq 48 ]"
for s in Idle Processing Ready; do
  wake; state $s; sleep 1; shot "01-bulle-$s"
done
# Listening: the wave around the bubble follows the voice level.
state Listening
for l in 0.15 0.9; do wake; level $l; sleep 1; shot "01-bulle-Listening-onde-$l"; done
state Idle; sleep 1
# Tucked at the edge: dragged against its side, it stays as a half-bubble.
read -r X Y <<< "$(center 'bubble \(shown\|snapped\)')"
if [ "$X" -gt $((W / 2)) ]; then EDGE=$((W - 4)); TX=$((W - 30)); else EDGE=4; TX=30; fi
wake
$ADB shell "input motionevent DOWN $X $Y; for i in 1 2 3 4 5 6; do input motionevent MOVE \$(( $X + ($EDGE - $X) * i / 6 )) $Y; done; input motionevent UP $EDGE $Y"
sleep 2
check "drag against the edge tucks the bubble" "log | grep -q 'bubble docked'"
shot 02-bulle-rangee-demi-bulle
DY=$(log | grep "bubble docked" | sed -n 's/.* at y=\([0-9]*\).*/\1/p' | tail -1)
wake; $ADB shell input tap "$TX" "${DY:-$Y}"; sleep 2
check "tap on the half-bubble brings it back" "log | grep -q 'bubble undocked'"
shot 02-bulle-ressortie
# « Masquer »: drop on the cross hides the bubble, with a notification to bring it back.
read -r X Y <<< "$(center 'bubble \(shown\|snapped\)')"
wake
$ADB shell "input motionevent DOWN $X $Y; for i in 1 2 3 4 5 6 7 8; do input motionevent MOVE \$(( $X + ($W / 2 - $X) * i / 16 )) \$(( $Y + ($H * 60 / 100 - $Y) * i / 16 )); done"
sleep 1; shot 03-bulle-glisser-masquer
$ADB shell "for d in 0 40 80 120; do input motionevent MOVE $((W / 2)) \$(( $H - 120 - d )); done; input motionevent MOVE $((W / 2)) $((H - 240)); input motionevent MOVE $((W / 2)) $((H - 260))"
sleep 1; shot 03-bulle-sur-zone
$ADB shell input motionevent UP $((W / 2)) $((H - 260))
sleep 2
check "drop on the cross hides the bubble" "log | grep -q 'bubble hidden'"
check "hidden bubble: notification to show it again" "log | grep -q 'hidden notification posted'"
sleep 1; shot 04-bulle-masquee
$ADB logcat -d > "$OUT/logcat.txt"
}

for part in ${2:-setup screens bubble}; do $part; done
