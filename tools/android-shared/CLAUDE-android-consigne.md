## Outillage Android (NAS)

Pour les runs d'agents sur le NAS (Paperclip). Sur un autre poste, installer le JDK 21 et le SDK Android soi-même, sans rien en mettre dans le dépôt.

- **Déjà réglé par l'environnement, rien à installer** : `JAVA_HOME=/opt/android/jdk`, `ANDROID_HOME=/opt/android/sdk` (lecture seule), `GRADLE_USER_HOME=/opt/gradle-home` (propre à la société) et `GRADLE_RO_DEP_CACHE=/opt/android/gradle-ro-cache` (dépendances communes, lecture seule). Pas de daemon Gradle.
- **Rien dans le dépôt ni le workspace** : jamais de SDK, de JDK ou de distribution Gradle, jamais de `sdkmanager`. Le dépôt ne déclare que ses versions : wrapper Gradle, `compileSdk`/`targetSdk`/`minSdk` dans `gradle/libs.versions.toml`. `local.properties` reste hors git.
- **Version absente du SDK** (`compileSdk`, build-tools) : tâche à Sacha, qui l'ajoute au fichier de versions du NAS. On ne contourne pas.
- **`adb` toujours par son chemin complet**, `$ANDROID_HOME/platform-tools/adb`, même dans un `bash -c` (le `PATH` y est réécrit).
- **Google TV Streamer** (adb over Wi-Fi, serveur `tcp:adb:5037` déjà réglé) :
  - `$ANDROID_HOME/platform-tools/adb devices -l` pour le voir ;
  - `$ANDROID_HOME/platform-tools/adb -s <serial> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk` pour installer ;
  - jamais `kill-server`, `connect`, `disconnect` ni `pair` : le serveur adb et l'appareil sont gérés par Sacha.
- **Téléphone OnePlus** : jamais par adb, seulement par guarded-phone-mcp. Jamais `adb connect` ni `adb pair` vers 192.168.1.80.
- **Émulateur**, quand l'appareil n'est pas disponible :
  - toujours `/opt/android/admin/emu/emu-run <phone|tv> [--wait-lock 120] -- <commande>`, options avant `--`. Il demande l'émulateur, lance la commande avec `adb` réglé sur lui, et le rend dans tous les cas ;
  - un seul émulateur à la fois sur le NAS : code 75 s'il est pris. Réessayer plus tard, ou `--wait-lock 120` (attente + démarrage + test sous 10 minutes, règle 7) ;
  - `--wait-lock` attend dans une file, par ordre d'arrivée. Délai écoulé (75) : relancer dans le même run dans les 5 minutes garde la place. Sans `--wait-lock`, refusé tant que quelqu'un attend ;
  - démarrage en 80 à 260 s selon la charge du NAS ; jamais d'émulateur laissé allumé ;
  - code 76 : NAS trop chargé (charge sur 1 minute au-dessus de 12), rien n'a démarré. Ne pas réessayer dans le run : poser une attente datée d'une heure sur la tâche (règle de charge de l'hôte), sinon la laisser en `todo` avec la mesure, et terminer ;
  - plusieurs commandes de test dans le même run : garder la session au lieu de redémarrer. `id=$(/opt/android/admin/emu/emu-start phone --wait-lock 120 --lease 300)`, garder `$id` dans `$PAPERCLIP_RUN_SCRATCH_DIR`, puis en tête de chaque commande suivante `/opt/android/admin/emu/emu-renew --id "$id" --lease 600` (600 s au plus) et `--lease 300` à la fin ; `emu-stop --id "$id"` après le dernier test. `emu-renew` en code 3 : session terminée, repartir d'`emu-start`. Détails : README de `/opt/android/admin` ;
  - `emu-run` rend le code de la commande de test. Ses propres codes : 75 émulateur pris ou SDK en maintenance ; 76 NAS trop chargé (attente d'une heure) ; 1 démarrage en échec (tout est déjà arrêté) ; 3 arrêt en échec, 4 pas de KVM, 69 conteneur de l'émulateur absent : tâche à Sacha.
