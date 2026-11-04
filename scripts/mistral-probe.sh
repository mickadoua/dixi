#!/usr/bin/env bash
# DMC-161: runs the Mistral clients inside a minified debug APK against canned answers
# (loopback server in the app, fake key). Usage, through emu-run:
#   emu-run phone -- scripts/mistral-probe.sh app/build/outputs/apk/debug/app-debug.apk OUT_DIR
set -u
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
PKG=tech.dmconsulting.voxbulle
APK=$1; OUT=$2
mkdir -p "$OUT"
$ADB install -r -g "$APK" || exit 1
$ADB logcat -c
$ADB shell am broadcast -n $PKG/.debug.MistralProbeReceiver
for i in $(seq 1 30); do
  $ADB logcat -d -s VoxbulleProbe:* > "$OUT/probe.txt"
  grep -q "PROBE \(PASS\|FAIL\)" "$OUT/probe.txt" && break
  sleep 1
done
$ADB logcat -d | grep -E "FATAL|AndroidRuntime" > "$OUT/crash.txt"
cat "$OUT/probe.txt"; echo "crash lines: $(wc -l < "$OUT/crash.txt")"
grep -q "PROBE PASS" "$OUT/probe.txt"
