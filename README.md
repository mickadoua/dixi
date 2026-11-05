# Voxbulle

App Android de dictée vocale : une bulle flottante, on parle, le texte transcrit (Voxtral, Mistral) est prêt à coller.

## Règles du repo

- Repo privé. Pas de GitHub Actions : tests en local et sur le NAS.
- `main` protégée : PR obligatoire, fusion par squash uniquement (commit signé par GitHub), pas de force-push ni de suppression.
- Fusion par Quinn ou Victor après leurs deux approbations dans Paperclip. Micka ne fusionne pas.
- Aucune clé dans le repo ni dans un APK : chaque utilisateur saisit sa propre clé Mistral dans l'app (DMC-227).

## Code

Package `tech.dmconsulting.voxbulle`, un seul module Gradle (`app`), un package par domaine : `bubble`, `recording`, `transcription`, `dictation`, `history`, `settings`, `onboarding`, `ui.theme`, `ui.navigation`. Les dépendances sont branchées à la main dans `AppContainer`.

## Build

    sh ./gradlew --no-daemon clean testDebugUnitTest assembleDebug

Les commits du repo sont faits par l'API GitHub (seule façon d'avoir des commits signés sans clé) : les fichiers n'ont pas le bit exécutable. D'où `sh ./gradlew` et `bash scripts/…`.

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

Sans les quatre variables `UPLOAD_*`, le build release se fait quand même, non signé. Avec une partie seulement, le build échoue. La clé d'envoi vit hors du repo, chez le CTO ; Play garde la clé de signature de l'app (Play App Signing). Ne jamais committer de keystore (`*.jks` et `*.keystore` sont ignorés), ni coller un mot de passe dans une tâche ou un journal.

L'envoi sur les pistes interne et fermée se fait depuis le NAS avec `bash scripts/play-publish.sh` (Gradle Play Publisher, DMC-330 ; usage en tête du script).

## Clé Mistral

L'app ne contient aucune clé : ni dans le repo, ni dans l'APK, ni dans `BuildConfig`, ni dans les ressources (DMC-227). Chaque utilisateur colle sa propre clé dans Réglages (écran 10). Elle est chiffrée par une clé Android Keystore (`settings/KeystoreKeyCipher`), stockée dans `noBackupFilesDir/mistral_key.bin` et exclue des sauvegardes et du transfert d'appareil (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`). Sans clé, l'app utilise les faux services Voxtral (« Mode démo ») et rien ne sort du téléphone. Ne jamais committer, journaliser ni coller une clé dans une tâche.

## Vérification sur l'émulateur (NAS)

    /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-proof.sh <dossier-de-sortie>

Le script accorde les permissions, affiche la bulle, ouvre Contacts, appuie sur la bulle, puis colle le presse-papiers dans Contacts. Résultats : captures et `logcat.txt` (tag `VoxbulleProof`) dans `<dossier-de-sortie>`.

L'outillage émulateur partagé du NAS est dans `tools/android-shared/` (voir son README).
