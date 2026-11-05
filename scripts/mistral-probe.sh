#!/usr/bin/env bash
# DMC-161, DMC-175: runs the Mistral clients inside a minified APK against canned answers
# (loopback server in the app, fake key). Works with the debug APK and with the probe
# build (release R8, not debuggable). Usage, through emu-run:
#   emu-run phone -- scripts/mistral-probe.sh app/build/outputs/apk/probe/app-probe.apk OUT_DIR
set -u
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
PKG=tech.dmconsulting.voxbulle
APK=$1; OUT=$2
mkdir -p "$OUT"
$ADB install -r -g "$APK" || exit 1
# Only lines logged after this device time count: an old PASS left in the buffer
# (logcat -c can fail) must not make this run pass.
SINCE="$($ADB shell date +%s | tr -d '\r').000"
case $SINCE in [0-9]*.000) ;; *) echo "no device time: $SINCE"; exit 1 ;; esac
$ADB logcat -c || echo "logcat -c failed, reading lines since $SINCE only"
$ADB shell am broadcast -n $PKG/.debug.MistralProbeReceiver
for i in $(seq 1 30); do
  $ADB logcat -d -v epoch -T "$SINCE" -s VoxbulleProbe:* > "$OUT/probe.txt"
  grep -q "PROBE \(PASS\|FAIL\)" "$OUT/probe.txt" && break
  sleep 1
done
$ADB logcat -d -v epoch -T "$SINCE" | grep -E "FATAL|AndroidRuntime" > "$OUT/crash.txt"
$ADB shell dumpsys package $PKG | grep -m1 -o "flags=\[[^]]*\]" > "$OUT/package-flags.txt"
cat "$OUT/probe.txt"; echo "crash lines: $(wc -l < "$OUT/crash.txt")"; cat "$OUT/package-flags.txt"
grep -q "PROBE PASS" "$OUT/probe.txt"
