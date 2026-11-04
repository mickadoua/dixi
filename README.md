# Voxbulle — technical proof (bubble + microphone)

Throwaway proof for Voxbulle risk #2: a floating bubble (`SYSTEM_ALERT_WINDOW`)
that starts microphone recording from on top of another app, in a
`microphone` foreground service. No network call: records 5 s, logs the file
duration, copies a fixed text and posts a "Texte copié" notification.

## Build

    ./gradlew --no-daemon testDebugUnitTest assembleDebug

## Emulator check (NAS)

    /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-proof.sh <out-dir>

The script grants permissions, shows the bubble, opens Contacts, taps the
bubble, then pastes the clipboard into Contacts. Results: screenshots and
`logcat.txt` (tag `VoxbulleProof`) in `<out-dir>`.
