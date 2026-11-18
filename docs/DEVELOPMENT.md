# Dixi

Android voice dictation app: a floating bubble, you speak, and the transcribed text (Voxtral, Mistral) is ready to paste.

## Repository rules

- Tests run locally. GitHub Actions only builds the signed APK of a version tag (`.github/workflows/release.yml`).
- `main` is protected: pull request required, squash merge only, no force-push, no deletion.
- No key in the repository or in an APK: each user enters their own Mistral key in the app.

## Code

Kotlin package `tech.dmconsulting.dixi`, a single Gradle module (`app`), one package per domain: `bubble`, `recording`, `transcription`, `dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`. Dependencies are wired by hand in `AppContainer`.

Design decisions:

- **Name.** The app is called Dixi: the `applicationId` (`tech.dmconsulting.dixi`, fixed from the first upload to Play), the `namespace`, the Kotlin packages, the classes (`DixiApp`, `DixiTheme`…), the `Theme.Dixi` theme, `rootProject.name` and the log tags `DixiProof` / `DixiProbe`. `PKG` and `NS` in `scripts/` are both `tech.dmconsulting.dixi`.
- **Old names kept.** Two internal names keep `voxbulle`, invisible to users: the Keystore alias `voxbulle_mistral_key` (renaming it would lose the Mistral key already entered) and the `VOXBULLE_UPLOAD_*` prefix of the upload secrets (default of `PLAY_SECRET_PREFIX` in `scripts/play-publish.sh`).
- **Single module.** One `app` module, without the `androidApp/` + `shared/` layout of Kotlin Multiplatform: Dixi is an Android-only app and no iOS target is planned. Revisit if that changes. Versions (AGP, Kotlin, Compose BOM, Gradle, SDK) follow current stable Android defaults.

## Build

    sh ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

The files do not have the executable bit: run `sh ./gradlew` and `bash scripts/…`.

Kotlin style (ktlint 1.8.0 through Spotless, rules in `.editorconfig`):

    sh ./gradlew --no-daemon spotlessCheck   # check
    sh ./gradlew --no-daemon spotlessApply   # fix

## Releasing

Play takes an Android App Bundle (`.aab`) signed with the upload key.

1. Tag the commit: `git tag v1.2.0` (gives `versionName` 1.2.0). `versionCode` is the number of commits in `HEAD`: it goes up with every merge to the release branch.
2. Export the upload key, then build:

       export UPLOAD_KEYSTORE_PATH=/path/to/upload.jks
       export UPLOAD_KEYSTORE_PASSWORD=...
       export UPLOAD_KEY_ALIAS=...
       export UPLOAD_KEY_PASSWORD=...
       sh ./gradlew --no-daemon bundleRelease

   Output: `app/build/outputs/bundle/release/app-release.aab`.

Optionally, `VERSION_CODE` and `VERSION_NAME` override the computed values (for example to resubmit the same commit with a higher code).

Without the four `UPLOAD_*` variables, the release build still works, unsigned. With only some of them, the build fails. The upload key lives outside the repository; Play keeps the app signing key (Play App Signing). Never commit a keystore (`*.jks` and `*.keystore` are ignored), and never paste a password into an issue or a log.

Upload to the internal and closed tracks is done with `bash scripts/play-publish.sh` (Gradle Play Publisher, service account; usage at the top of the script). `bash scripts/play-publish.sh --status` reads the state of the tracks without changing anything. The script refuses the beta and production tracks and any deletion. Tests: `bash scripts/tests/play-publish-test.sh` (local fake Play API, no network).

The store listing (texts, images, contact details) and the data safety form go through `python3 -I scripts/play-listing.py --langs en-US,fr-FR` (Android Publisher API; usage at the top of the script), from `app/src/main/play/`. With no option it is a dry run (edit validated, then discarded); `--read` and `--verify` are read-only; `--commit` saves, then reads back. `--data-safety <csv>` is only sent with `--commit`. Tests: `bash scripts/tests/play-listing-test.sh`.

## Mistral key

The app contains no key: not in the repository, not in the APK, not in `BuildConfig`, not in resources. Each user pastes their own key during onboarding, which cannot end until Mistral accepts the key ("Test key"), and can keep several named keys in Settings, one of them active. Each key is encrypted by an Android Keystore key (`settings/KeystoreKeyCipher`), stored in `noBackupFilesDir/mistral_keys/<id>.bin` (names and active key in `keys.json`, never the keys) and excluded from backups and device transfer (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). The single key of older versions (`noBackupFilesDir/mistral_key.bin`) is moved there at first start. Without any key, dictations fail with "No key". Only debug builds (`BuildConfig.DEMO_MODE`) fall back to fake Voxtral services ("Demo mode"), and nothing leaves the phone. Never commit, log or paste a key into an issue.

## Checking on an emulator or a phone

Prerequisites: a device or emulator reachable through `adb` (`ANDROID_HOME` set) and the debug APK built (`assembleDebug`).

    bash scripts/emu-proof.sh <output-directory>

The script installs the APK, grants the permissions, shows the bubble, opens Contacts, taps the bubble, then pastes the clipboard into Contacts. Results: screenshots and `logcat.txt` (tag `DixiProof`) in `<output-directory>`.

## License

Apache-2.0, see `LICENSE`. The Inter fonts are under SIL OFL 1.1 (`licenses/fonts-OFL.txt`, `NOTICE`).
