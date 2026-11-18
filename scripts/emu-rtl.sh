#!/usr/bin/env bash
# Emulator proof for a right-to-left language (Arabic by default): onboarding
# screens 1 and 2, then the bubble docked at the right edge, then at the left edge,
# then the guided trial's hint card beside the bubble, at the right then at the left.
# The app language is set per app (`cmd locale`), Android's own screens stay in English.
# The bubble is placed from the left edge (Gravity.LEFT): docking must follow the
# finger, not the layout direction. So must the hint card of the guided trial.
# Part 3 needs the debug fake Mistral (files/fake-mistral, PR #66) to get past the
# key step; without it, it is skipped. Needs adb ($ANDROID_HOME) and a debug APK:
#   bash scripts/emu-rtl.sh <out-dir> [locale]
set -u
ADB=$ANDROID_HOME/platform-tools/adb
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.dixi  # class package (namespace), not the applicationId
OUT=${1:-rtl-proof-out}
LOCALE=${2:-ar}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
mkdir -p "$OUT"
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
log() { $ADB logcat -d -s DixiProof; }
wake() { $ADB shell input keyevent KEYCODE_WAKEUP; $ADB shell wm dismiss-keyguard; sleep 1; }
wait_log() { for _ in $(seq 1 "${2:-20}"); do log | grep -q "$1" && return 0; sleep 2; done; echo "no log line: $1"; return 1; }
check() { if eval "$2"; then echo "OK   $1"; else echo "FAIL $1"; fi; }
# Just after a snapshot boot Android's own load is very high (48 measured on 17/11):
# the app hits an ANR, hide_error_dialogs kills it, and the drag lands on the launcher.
# Waits until the emulator's 1-minute load is under 4 (at most 3 minutes).
settle() {
  local l i
  for i in $(seq 1 36); do
    l=$($ADB shell cat /proc/loadavg | cut -d' ' -f1)
    [ "${l%%.*}" -lt 4 ] 2>/dev/null && { echo "emulator load $l"; return 0; }
    sleep 5
  done
  echo "emulator load still $l"
}
alive() { [ -n "$($ADB shell pidof $PKG)" ]; }
read -r W H <<< "$($ADB shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)"
center() { log | grep "$1" | sed -n 's/.* x=\([0-9]*\) y=\([0-9]*\).*/\1 \2/p' | tail -1; }
# Drags the bubble from its last logged center to x=$1 at the same height, then lifts.
# One adb shell for the whole gesture: no adb round trip between events.
drag_to_x() {
  local X Y cmd i
  read -r X Y <<< "$(center "bubble shown\|bubble snapped\|bubble undocked")"
  [ -n "${Y:-}" ] || { echo "no bubble position in the log"; return 1; }
  cmd="input motionevent DOWN $X $Y"
  for i in 1 2 3 4 5 6 7 8; do cmd="$cmd; input motionevent MOVE $((X + ($1 - X) * i / 8)) $Y"; done
  wake; $ADB shell "$cmd; input motionevent UP $1 $Y"
}

$ADB shell getprop ro.build.version.sdk | sed 's/^/sdk /'
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell cmd locale set-app-locales $PKG --locales "$LOCALE"
$ADB shell cmd locale get-app-locales $PKG | tee "$OUT/app-locale.txt"
check "app locale is $LOCALE" "grep -q '$LOCALE' '$OUT/app-locale.txt'"
$ADB shell svc power stayon true
$ADB shell settings put system screen_off_timeout 1800000
$ADB shell locksettings set-disabled true
$ADB shell settings put global hide_error_dialogs 1
$ADB shell settings put system accelerometer_rotation 0
wake
$ADB logcat -c

settle
# Throwaway first launch: just after a boot the first screenshots come out black.
$ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 8
$ADB exec-out screencap -p > "$OUT/warmup.png"

echo "--- 1. Onboarding in $LOCALE"
check "screen 1 (welcome)" "wait_log 'onboarding step Welcome'"
sleep 2; shot 1-welcome
wake; $ADB shell input tap 540 2220  # lowest 56 dp button, centered: same point in RTL
check "screen 2 (microphone)" "wait_log 'onboarding step MicPermission'"
sleep 2; shot 2-mic
$ADB shell am force-stop $PKG; sleep 2

echo "--- 2. Bubble docked at each edge"
$ADB logcat -c
wake; $ADB shell am start -W -n $PKG/$NS.MainActivity --ez showBubble true >/dev/null
check "bubble shown" "wait_log 'bubble shown' 15"
$ADB shell input keyevent KEYCODE_HOME; sleep 3
settle
check "app still running before the drag" "alive"
shot 3-bubble
drag_to_x $((W - 2))
check "docked at the right edge after a drag to the right" "wait_log 'bubble docked side=Right' 10"
sleep 2; shot 4-docked-right
# A tap on the half-bubble brings it back whole, at the right edge.
read -r _ DY <<< "$(log | sed -n 's/.*bubble docked side=Right at y=\([0-9]*\).*/x \1/p' | tail -1)"
wake; $ADB shell input tap $((W - 10)) "${DY:-1200}"
check "tap brings it back at the right edge" "wait_log 'bubble undocked side=Right' 10"
sleep 2; shot 5-undocked-right
$ADB logcat -d -s DixiProof > "$OUT/app-log-right.txt"
# Whole again at the right edge: drag it from there to the left edge.
$ADB shell "input motionevent DOWN $((W - 80)) ${DY:-1200}; input motionevent MOVE $((W / 2)) ${DY:-1200}; input motionevent MOVE 2 ${DY:-1200}; input motionevent UP 2 ${DY:-1200}"
check "docked at the left edge after a drag to the left" "wait_log 'bubble docked side=Left' 10"
sleep 2; shot 6-docked-left

echo "--- 3. Guided trial: the hint card beside the bubble"
BOTTOM="540 2220"  # lowest 56 dp button
ABOVE="540 2052"   # the 56 dp button above it
UI=/data/local/tmp/ui.xml
# Center of the first node whose text (or class, for EDITABLE) matches, from a uiautomator dump.
node_xy() {
  $ADB shell uiautomator dump $UI >/dev/null 2>&1
  $ADB shell cat $UI > "$OUT/ui.xml"
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
for node in re.findall(r'<node [^>]*>', open(sys.argv[1], encoding='utf-8').read()):
    want = sys.argv[2]
    if (want == 'EDITABLE' and 'class="android.widget.EditText"' in node) or f'text="{want}"' in node:
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((a + c) // 2, (b + d) // 2)
        break
PY
}
tap() { wake; $ADB shell input tap $1 $2; sleep 3; }
$ADB uninstall $PKG >/dev/null 2>&1
$ADB install -r -g "$APK" || exit 1
$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow
$ADB shell cmd locale set-app-locales $PKG --locales "$LOCALE"
$ADB shell "run-as $PKG sh -c 'mkdir -p files; echo ok > files/fake-mistral'"
$ADB logcat -c
wake; $ADB shell am start -W -n $PKG/$NS.MainActivity >/dev/null; sleep 6
if ! wait_log 'fake mistral on http://127.0.0.1' 5; then
  echo "SKIP part 3: this build has no fake Mistral"
else
  wait_log 'onboarding step Welcome' && tap $BOTTOM
  wait_log 'onboarding step MicPermission' && tap $ABOVE  # microphone already granted (-g)
  wait_log 'onboarding step MistralKey'
  tap $(node_xy EDITABLE)
  for k in dixi fake rtlp roof 0000 0000; do $ADB shell input text $k; sleep 1; done
  $ADB shell input keyevent KEYCODE_ENTER
  check "fake key accepted" "wait_log 'key test Valid'"
  $ADB shell dumpsys input_method | grep -q 'mInputShown=true' && $ADB shell input keyevent KEYCODE_BACK
  sleep 2; tap $BOTTOM
  wait_log 'onboarding step BubbleIntro' && tap $ABOVE  # overlay already allowed
  check "guided trial" "wait_log 'guided trial step Tap'"
  settle
  check "app still running" "alive"
  read -r BX _ <<< "$(center 'bubble shown')"
  check "bubble at the right" "[ \"${BX:-0}\" -gt $((W / 2)) ]"
  sleep 2; shot 7-trial-bubble-right
  read -r AX _ <<< "$(node_xy '▸')"
  check "card arrow ▸ on the right half, toward the bubble (x=${AX:-none})" "[ \"${AX:-0}\" -gt $((W / 2)) ]"
  drag_to_x $((W / 4))
  check "bubble snapped to the left" "wait_log 'bubble snapped side=Left' 10"
  sleep 3; shot 8-trial-bubble-left
  read -r AX _ <<< "$(node_xy '◂')"
  check "card arrow ◂ on the left half, toward the bubble (x=${AX:-none})" "[ -n \"${AX:-}\" ] && [ \"$AX\" -lt $((W / 2)) ]"
fi

$ADB logcat -d -b all > "$OUT/logcat.txt"
grep -A8 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -B2 -A6 "$PKG" | head -20 > "$OUT/crashes.txt"
echo "app crash lines: $(wc -l < "$OUT/crashes.txt")"
$ADB logcat -d -s DixiProof > "$OUT/app-log.txt"
$ADB shell cmd locale set-app-locales $PKG --locales ""
