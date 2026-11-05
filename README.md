# Voxbulle

Android dictation app with a floating bubble. Package `tech.dmconsulting.voxbulle`,
one Gradle module, one package per area: `bubble`, `recording`, `transcription`,
`dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`.
Dependencies are wired by hand in `AppContainer`.

## Build

    ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

## Mistral key

The app ships without any key: not in the repo, the APK, `BuildConfig` or the
resources (DMC-227). Each user pastes their own key in Settings (screen 10). It is
encrypted with an Android Keystore key (`settings/KeystoreKeyCipher`), stored in
`noBackupFilesDir/mistral_key.bin` and excluded from backups and device transfer
(`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). Without a key, the app
uses the fake Voxtral services (« Mode démo ») and nothing leaves the phone.
Never commit, log or paste a key in a task.

## Emulator check (NAS)

    /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-proof.sh <out-dir>

The script grants permissions, shows the bubble, opens Contacts, taps the
bubble, then pastes the clipboard into Contacts. Results: screenshots and
`logcat.txt` (tag `VoxbulleProof`) in `<out-dir>`.
