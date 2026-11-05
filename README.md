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

## Clé Mistral (transitoire)

Tant que DMC-227 n'est pas fusionné, le build debug lit la clé au build, dans la variable d'environnement `MISTRAL_API_KEY`, sinon dans `local.properties` (ignoré par git). Sans clé, l'app utilise les faux services Voxtral et rien ne sort du téléphone. DMC-227 retire ce chemin : la clé sera saisie dans l'écran Réglages et stockée chiffrée sur le téléphone. Ne jamais committer de clé.

## Vérification sur l'émulateur (NAS)

    /opt/android/admin/emu/emu-run phone --wait-lock 120 -- bash scripts/emu-proof.sh <dossier-de-sortie>

Le script accorde les permissions, affiche la bulle, ouvre Contacts, appuie sur la bulle, puis colle le presse-papiers dans Contacts. Résultats : captures et `logcat.txt` (tag `VoxbulleProof`) dans `<dossier-de-sortie>`.

L'outillage émulateur partagé du NAS est dans `tools/android-shared/` (voir son README).
