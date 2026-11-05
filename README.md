# Voxbulle

Android dictation app with a floating bubble. Package `tech.dmconsulting.voxbulle`,
one Gradle module, one package per area: `bubble`, `recording`, `transcription`,
`dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`.
Dependencies are wired by hand in `AppContainer`.

## Build

    ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

## Publish a version

Play takes an Android App Bundle (`.aab`) signed with the upload key.

1. Tag the commit: `git tag v1.2.0` (gives `versionName` 1.2.0). `versionCode` is the
   number of commits on `HEAD`, so it grows with every merge on the release branch.
2. Export the upload key, then build:

       export UPLOAD_KEYSTORE_PATH=/path/to/upload.jks
       export UPLOAD_KEYSTORE_PASSWORD=...
       export UPLOAD_KEY_ALIAS=...
       export UPLOAD_KEY_PASSWORD=...
       ./gradlew --no-daemon bundleRelease

   Output: `app/build/outputs/bundle/release/app-release.aab`.

Optional: `VERSION_CODE` and `VERSION_NAME` override the computed values (for example
to upload the same commit again with a higher code).

Without the four `UPLOAD_*` variables the release build is still made, unsigned. With
only some of them, the build fails. The upload key lives outside the repo, with the
CTO; Play keeps the app signing key (Play App Signing). Never commit a keystore
(`*.jks`, `*.keystore` are ignored) and never paste a password in a task or a log.

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
