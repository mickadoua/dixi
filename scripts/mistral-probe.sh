#!/usr/bin/env bash
# DMC-161, DMC-175: runs the Mistral clients inside a minified APK against canned answers
# (loopback server in the app, fake key). Works with the debug APK and with the probe
# build (release R8, not debuggable). Usage, through emu-run:
#   emu-run phone -- scripts/mistral-probe.sh app/build/outputs/apk/probe/app-probe.apk OUT_DIR
set -u
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
PKG=tech.dmconsulting.dixi
NS=tech.dmconsulting.voxbulle  # class package (namespace), not the applicationId (DMC-383)
APK=$1; OUT=$2
mkdir -p "$OUT"
$ADB install -r -g "$APK" || exit 1
# The receiver starts each line with this id: an old PASS left in the buffer (logcat -c
# can fail) cannot make this run pass. Not a time filter: after a snapshot load the
# emulator clock jumps back for some seconds.
RUN="run-$(date +%s)-$$"
$ADB logcat -c || echo "logcat -c failed, reading the lines of $RUN only"
$ADB shell am broadcast -n $PKG/$NS.debug.MistralProbeReceiver --es run "$RUN"
for i in $(seq 1 30); do
  $ADB logcat -d -s VoxbulleProbe:* | grep -F "$RUN " > "$OUT/probe.txt"
  grep -q "$RUN PROBE \(PASS\|FAIL\)" "$OUT/probe.txt" && break
  sleep 1
done
$ADB logcat -d | grep -E "FATAL EXCEPTION| E AndroidRuntime:" > "$OUT/crash.txt"
$ADB shell dumpsys package $PKG | grep -m1 -o "flags=\[[^]]*\]" > "$OUT/package-flags.txt"
cat "$OUT/probe.txt"; echo "crash lines: $(wc -l < "$OUT/crash.txt")"; cat "$OUT/package-flags.txt"
grep -q "$RUN PROBE PASS" "$OUT/probe.txt"
