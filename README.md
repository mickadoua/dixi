# Voxbulle

Android dictation app with a floating bubble. Package `tech.dmconsulting.voxbulle`,
one Gradle module, one package per area: `bubble`, `recording`, `transcription`,
`dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`.
Dependencies are wired by hand in `AppContainer`.

## Build

    ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

## Mistral key (debug builds only)

Read at build time from the `MISTRAL_API_KEY` environment variable, else from
`local.properties` (ignored by git). Without a key, the app uses the fake
Voxtral services and nothing leaves the phone. Never commit a key.

## Emulator check (NAS)

    /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-proof.sh <out-dir>

The script grants permissions, shows the bubble, opens Contacts, taps the
bubble, then pastes the clipboard into Contacts. Results: screenshots and
`logcat.txt` (tag `VoxbulleProof`) in `<out-dir>`.
