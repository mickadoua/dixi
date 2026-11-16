# Dixi

App Android de dictée vocale : une bulle flottante, on parle, le texte transcrit (Voxtral, Mistral) est prêt à coller.

## Règles du repo

- Pas de GitHub Actions : les tests tournent en local.
- `main` est protégée : pull request obligatoire, fusion par squash uniquement, pas de force-push ni de suppression.
- Aucune clé dans le repo ni dans un APK : chaque utilisateur saisit sa propre clé Mistral dans l'app.

## Code

Package Kotlin `tech.dmconsulting.voxbulle`, un seul module Gradle (`app`), un package par domaine : `bubble`, `recording`, `transcription`, `dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`. Les dépendances sont branchées à la main dans `AppContainer`.

Décisions de conception :

- **Nom.** L'app s'appelle Dixi. Seuls l'`applicationId` (`tech.dmconsulting.dixi`, figé dès le premier envoi sur Play) et les textes visibles portent ce nom.
- **Package.** Le `namespace` et les packages Kotlin restent `tech.dmconsulting.voxbulle` : l'utilisateur ne les voit pas, et les déplacer toucherait chaque fichier pour rien. Un composant se nomme donc `tech.dmconsulting.dixi/tech.dmconsulting.voxbulle.X` (forme courte `/.X` impossible), d'où `PKG` et `NS` dans `scripts/`.
- **Anciens noms conservés.** Le thème `Theme.Voxbulle`, les classes (`VoxbulleApp`, `VoxbulleTheme`…), `rootProject.name`, l'alias Keystore `voxbulle_mistral_key` (le renommer ferait perdre la clé Mistral déjà saisie), les tags de journal `VoxbulleProof` / `VoxbulleProbe` et le préfixe `VOXBULLE_UPLOAD_*` des secrets d'envoi gardent `voxbulle`.
- **Module unique.** Un seul module `app`, sans la structure `androidApp/` + `shared/` de Kotlin Multiplatform : Dixi est une app Android seule, aucune cible iOS n'est prévue. À revoir si cela change. Les versions (AGP, Kotlin, BOM Compose, Gradle, SDK) suivent celles de notre gabarit Android.

## Build

    sh ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

Les fichiers n'ont pas le bit exécutable : lancez `sh ./gradlew` et `bash scripts/…`.

Style Kotlin (ktlint 1.8.0 par Spotless, règles dans `.editorconfig`, comme le template) :

    sh ./gradlew --no-daemon spotlessCheck   # vérifie
    sh ./gradlew --no-daemon spotlessApply   # corrige

## Publier une version

Play prend un Android App Bundle (`.aab`) signé avec la clé d'envoi.

1. Tagger le commit : `git tag v1.2.0` (donne `versionName` 1.2.0). `versionCode` est le nombre de commits de `HEAD` : il augmente à chaque fusion sur la branche de publication.
2. Exporter la clé d'envoi, puis construire :

       export UPLOAD_KEYSTORE_PATH=/chemin/vers/upload.jks
       export UPLOAD_KEYSTORE_PASSWORD=...
       export UPLOAD_KEY_ALIAS=...
       export UPLOAD_KEY_PASSWORD=...
       sh ./gradlew --no-daemon bundleRelease

   Sortie : `app/build/outputs/bundle/release/app-release.aab`.

En option, `VERSION_CODE` et `VERSION_NAME` remplacent les valeurs calculées (par exemple pour renvoyer le même commit avec un code plus haut).

Sans les quatre variables `UPLOAD_*`, le build release se fait quand même, non signé. Avec une partie seulement, le build échoue. La clé d'envoi vit hors du repo ; Play garde la clé de signature de l'app (Play App Signing). Ne jamais committer de keystore (`*.jks` et `*.keystore` sont ignorés), ni coller un mot de passe dans un ticket ou un journal.

L'envoi sur les pistes interne et fermée se fait avec `bash scripts/play-publish.sh` (Gradle Play Publisher, compte de service ; usage en tête du script). `bash scripts/play-publish.sh --status` lit l'état des pistes sans rien modifier. Les pistes beta et production et toute suppression sont refusées par le script. Tests : `bash scripts/tests/play-publish-test.sh` (fausse API Play locale, sans réseau).

La fiche du store (textes, images, coordonnées) et la sécurité des données passent par `python3 -I scripts/play-listing.py --langs en-US,fr-FR` (API Android Publisher ; usage en tête du script), à partir de `app/src/main/play/` : sans option, essai à blanc (edit validé puis jeté) ; `--read` et `--verify` en lecture seule ; `--commit` enregistre puis relit. `--data-safety <csv>` n'est envoyé qu'avec `--commit`. Tests : `bash scripts/tests/play-listing-test.sh`.

## Clé Mistral

L'app ne contient aucune clé : ni dans le repo, ni dans l'APK, ni dans `BuildConfig`, ni dans les ressources. Chaque utilisateur colle sa propre clé dans Réglages (écran 10). Elle est chiffrée par une clé Android Keystore (`settings/KeystoreKeyCipher`), stockée dans `noBackupFilesDir/mistral_key.bin` et exclue des sauvegardes et du transfert d'appareil (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). Sans clé, l'app utilise les faux services Voxtral (« Mode démo ») et rien ne sort du téléphone. Ne jamais committer, journaliser ni coller une clé dans un ticket.

## Vérification sur un émulateur ou un téléphone

Prérequis : un appareil ou un émulateur joignable par `adb` (`ANDROID_HOME` défini) et l'APK debug construit (`assembleDebug`).

    bash scripts/emu-proof.sh <dossier-de-sortie>

Le script installe l'APK, accorde les permissions, affiche la bulle, ouvre Contacts, appuie sur la bulle, puis colle le presse-papiers dans Contacts. Résultats : captures et `logcat.txt` (tag `VoxbulleProof`) dans `<dossier-de-sortie>`.

## Licence

Apache-2.0, voir `LICENSE`. Les polices Inter sont sous SIL OFL 1.1 (`licenses/fonts-OFL.txt`, `NOTICE`).
