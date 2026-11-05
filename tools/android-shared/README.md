# Outillage Android partagé du NAS (DMC-114)

Un seul JDK + SDK Android, un seul cache de dépendances Gradle, un seul serveur adb pour les appareils réels et un seul émulateur à la demande, pour tous les agents de toutes les sociétés. Rien dans les dépôts.

## Architecture

- **Les agents ne tournent pas directement dans le conteneur Paperclip.** Chaque run est enfermé par `bwrap` (wrapper `/usr/local/bin/bwrap-paperclip`). Le run ne voit que les chemins que le wrapper monte, a son propre `/dev` (sans `/dev/kvm`) et son propre espace de processus. Un volume ajouté au conteneur n'est donc visible des agents que si le wrapper le monte. C'est aussi ce qui rend possible le **« lecture-écriture pour Sacha seul »** : le wrapper monte `/opt/android` en lecture-écriture pour Sacha et en lecture seule pour tous les autres, comme il monte déjà `/paperclip/previews` par société.
- **Émulateur dans un conteneur à part** (`android-emu`), le seul à recevoir `/dev/kvm`, sans privilège.
  - Au repos, il n'y tourne qu'un script bash (le superviseur) qui surveille un dossier de contrôle.
  - Un agent y dépose une demande (`emu-run`, `emu-start`). Le superviseur démarre l'émulateur, puis l'arrête à la demande (`emu-stop`), à l'échéance du bail ou au bout de 30 minutes.
  - Les agents parlent à l'émulateur par le serveur adb de ce conteneur (`tcp:android-emu:5037`).
- **Serveur adb du Google TV Streamer dans un conteneur à part** (`adb`), qui sort vers lui en Wi-Fi. **Jamais le téléphone OnePlus** (décision du fondateur, 03/11) : il reste derrière guarded-phone-mcp (un utilisateur Android par agent, PIN, bail, journal).
- **Réseau Docker dédié `android-devices`**, créé une fois, qui relie Paperclip, `adb` et `android-emu`. **Aucun port publié sur le LAN.** Aucune des deux applications ne possède ce réseau : arrêter ou mettre à jour l'une ne casse pas l'autre.
- **Cache Gradle : commun en lecture seule, inscriptible par société.**
  - Les dépendances viennent d'un cache commun en lecture seule (`GRADLE_RO_DEP_CACHE`), que Sacha publie à partir de ses propres builds.
  - Chaque société a son dossier Gradle inscriptible (ce qui manque au cache commun, transforms, build-cache), et Sacha a le sien, privé.
  - Raison : un dossier Gradle commun en écriture permettrait à un agent de déposer un script `init.d` ou de remplacer un jar en cache, donc d'exécuter du code dans les builds de toutes les autres sociétés, et dans ceux de Sacha, qui a l'écriture sur le SDK.
  - Vérifié le 03/11 : le template se compile **hors ligne** avec ce montage, et le dossier de la société n'a rien téléchargé (19 Ko dans `modules-2`).

## Volumes

> **Mis en place le 03/11 vers 23:40 (Claude, pour Micka).** Écarts avec ce qui suit :
> - pas de datasets ZFS (pas d'accès root) : de simples dossiers sans quota, `/mnt/fastPool/hermes/android/{android,gradle-cache,adb-home,android-emu/ctl,android-emu/data}`, tous à 1000:1000. Dans les conteneurs et les runs, les chemins sont ceux du tableau ;
> - pour Sacha, le wrapper monte le dossier en écriture directement sur `/opt/android` ;
> - `android-devices` est la pile Portainer 102 (`/mnt/fastPool/hermes/android-devices/compose.yaml`), GID kvm 106, réseau 172.16.31.0/24 ;
> - le cron mensuel (étape 5) reste à poser par Micka (root).

| Dataset (hôte) | Conteneur Paperclip | Vu par les agents (wrapper) | `adb` / `android-emu` | Contenu |
|---|---|---|---|---|
| `fastPool/android` | `/opt/android` (lecture seule) et `/opt/android-rw` | lecture seule ; lecture-écriture pour Sacha | lecture seule | `jdk` (lien vers `jdk-<version>`), `sdk/` (avec `emulator/` et `system-images/`), `avd/` (profils), `emulator-libs/`, `gradle-ro-cache` (dépendances Gradle communes), `VERSIONS`, `admin/` (ces scripts) |
| `fastPool/gradle-cache` | `/opt/gradle-cache` | `/opt/gradle-cache/<PRÉFIXE>` (ou `sacha`) monté sur `/opt/gradle-home`, lecture-écriture | — | un `GRADLE_USER_HOME` par société (`DMC/`, `BLA/`…) et `sacha/` ; dans chacun, `gradle.properties` et `android-user-home/` (clé debug) |
| `fastPool/adb-home` | — | — | `adb` : `/adb-home` | clé adb que les appareils autorisent |
| `fastPool/android-emu`, `ctl/` | `/opt/android-emu` | lecture-écriture | `android-emu` : `/opt/android-emu` | dossier de contrôle : session, comptes rendus, `metrics.csv` |
| `fastPool/android-emu`, `data/` | — | — | `android-emu` : `/emu-data` | données jetables de la session (jusqu'à 2 Go, supprimées à l'arrêt), clé adb de l'émulateur |

Variables d'environnement du conteneur Paperclip (elles arrivent telles quelles dans les runs) :

```
JAVA_HOME=/opt/android/jdk
ANDROID_HOME=/opt/android/sdk
GRADLE_USER_HOME=/opt/gradle-home
GRADLE_RO_DEP_CACHE=/opt/android/gradle-ro-cache
ANDROID_USER_HOME=/opt/gradle-home/android-user-home
ADB_SERVER_SOCKET=tcp:adb:5037
```

- **`ANDROID_USER_HOME` gardé d'un run à l'autre.** La clé de signature debug (`debug.keystore`) y est créée. Dans le `HOME` du run, elle disparaît à chaque fois : chaque APK debug serait alors signé par une clé différente, et `adb install -r` refuserait la mise à jour (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- **Pas de daemon Gradle ni Kotlin.** Le wrapper crée `gradle.properties` dans chaque dossier Gradle avec `org.gradle.daemon=false` et `kotlin.compiler.execution.strategy=in-process`. Un daemon qui survit au tour fait échouer le run (règle 7 du CLAUDE.md commun), et ces daemons ne servent à rien d'un run à l'autre.

## Mise en place sur le NAS (Micka) : une seule intervention

Tout se fait en root sur l'hôte TrueNAS, dans l'ordre. L'étape 3 (YAML de Paperclip et wrapper) se fait d'un coup, avec un seul redéploiement de Paperclip, **à un moment calme** : il redémarre tous les agents. Les autres étapes ne coupent rien.

### Étape 0 : vérifications (lecture seule)

```bash
# KVM : le N100 a VT-x et EPT, il faut que le module soit chargé.
ls -l /dev/kvm                        # attendu : crw-rw---- 1 root kvm 10, 232 ...
grep -c -E 'vmx|svm' /proc/cpuinfo    # attendu : 4 (un par cœur)
lsmod | grep -E '^kvm'                # attendu : kvm_intel et kvm
getent group kvm | cut -d: -f3        # GID du groupe kvm : à reporter à la place de GID_KVM (étape 4)

# ACL du pool : les datasets sont créés en POSIX (étape 1), quel que soit le pool.
zfs get -H -o value acltype,aclmode fastPool

# Réseaux Docker existants, et conteneur Paperclip (aujourd'hui 2da7edcc8020).
docker network ls
docker ps --format '{{.ID}}  {{.Names}}' | grep -i paperclip

# Chemins hôte des workspaces et projets (pour le cron de l'étape 5).
ls -d /mnt/fastPool/hermes/paperclip/instances/default/workspaces /mnt/fastPool/hermes/paperclip/instances/default/projects

# D'où vient le wrapper (image personnalisée ou fichier copié) : c'est là qu'il faut le modifier.
docker exec <conteneur-paperclip> ls -l /usr/local/bin/bwrap-paperclip
```

Si `/dev/kvm` n'existe pas : lancer `modprobe kvm_intel`, puis vérifier que la virtualisation (VT-x) est activée dans le BIOS. Si ça ne suffit pas, retirer le service `android-emu` du YAML de l'étape 4 (sinon toute l'application refuse de démarrer, `adb` compris) et me le dire sur DMC-114 : le reste (SDK, Gradle, adb) marche sans l'émulateur.

### Étape 1 : datasets

```bash
for ds in android gradle-cache adb-home android-emu; do zfs create -o acltype=posix fastPool/$ds; done
# Garde-fous : le ménage tient chaque dossier Gradle sous 5 Go, une session d'émulateur sous 2 Go.
zfs set quota=20G fastPool/android
zfs set quota=40G fastPool/gradle-cache
zfs set quota=10G fastPool/android-emu
mkdir -p /mnt/fastPool/android-emu/ctl /mnt/fastPool/android-emu/data
chown -R 1000:1000 /mnt/fastPool/android /mnt/fastPool/gradle-cache /mnt/fastPool/adb-home /mnt/fastPool/android-emu
chmod 700 /mnt/fastPool/adb-home /mnt/fastPool/android-emu/data
```

### Étape 2 : réseau Docker dédié

```bash
docker network create android-devices
docker network ls | grep android-devices      # le réseau existe
```

### Étape 3 : application Paperclip (YAML) et wrapper

À ajouter au service Paperclip, **sans rien retirer** de ce qui existe. Si `environment` y est écrit en liste `- CLE=valeur`, garder cette forme. Si le service a déjà une liste `networks`, y ajouter `android-devices`.

```yaml
services:
  <service-paperclip>:
    volumes:
      - /mnt/fastPool/android:/opt/android:ro
      - /mnt/fastPool/android:/opt/android-rw
      - /mnt/fastPool/gradle-cache:/opt/gradle-cache
      - /mnt/fastPool/android-emu/ctl:/opt/android-emu
    environment:
      JAVA_HOME: /opt/android/jdk
      ANDROID_HOME: /opt/android/sdk
      GRADLE_USER_HOME: /opt/gradle-home
      GRADLE_RO_DEP_CACHE: /opt/android/gradle-ro-cache
      ANDROID_USER_HOME: /opt/gradle-home/android-user-home
      ADB_SERVER_SOCKET: tcp:adb:5037
    networks:
      - default
      - android-devices

networks:
  android-devices:
    external: true
```

Paperclip ne reçoit ni `devices`, ni `group_add`, ni aucun autre privilège. `/opt/android` est en lecture seule dans le conteneur. `/opt/android-rw` est le même dataset en écriture : le wrapper ne le monte, à la place de `/opt/android`, que pour les runs de Sacha.

Dans `bwrap-paperclip` (à sa source, voir l'étape 0), ajouter le bloc suivant juste **avant** la ligne `  if [ "$claude_bound" = 1 ] && ...`. Le fichier `bwrap-paperclip-android.diff`, plus bas, donne le même changement sous forme de diff.

```bash
  # Outillage Android partagé (DMC-114).
  # - /opt/android (JDK, SDK, cache de dépendances Gradle commun) : lecture
  #   seule pour tous. /opt/android-rw est le même dataset en écriture, monté
  #   à sa place pour Sacha (DMC) seul, qui le met à jour.
  # - Dossier Gradle inscriptible : un par société (une société ne peut pas
  #   injecter de code dans les builds d'une autre), et un privé pour Sacha
  #   (aucun autre agent ne peut agir sur les builds qui écrivent le SDK).
  # - Dossier de contrôle de l'émulateur : lecture-écriture pour tous.
  # Rien ici ne doit faire échouer un run : erreurs d'écriture ignorées.
  sacha=0
  [ "$company" = 32c05498-62ad-4fca-bf03-ab7953aa2a55 ] &&
    [ "${PAPERCLIP_AGENT_ID:-}" = 7b8c33a9-2329-473b-a7d0-822dd974b83f ] && sacha=1
  if [ "$sacha" = 1 ] && [ -d /opt/android-rw ]; then
    extra+=(--bind /opt/android-rw /opt/android)
  elif [ -d /opt/android ]; then
    extra+=(--ro-bind /opt/android /opt/android)
  fi
  if [ -d /opt/gradle-cache ] && [ -n "$prefixe" ]; then
    gradle_dir=/opt/gradle-cache/$prefixe
    [ "$sacha" = 1 ] && gradle_dir=/opt/gradle-cache/sacha
    mkdir -p "$gradle_dir" 2>/dev/null || true
    if [ -d "$gradle_dir" ] && [ ! -L "$gradle_dir" ]; then
      # set -C : jamais d'écrasement, jamais à travers un lien.
      (set -C; printf 'org.gradle.daemon=false\nkotlin.compiler.execution.strategy=in-process\n' \
        >"$gradle_dir/gradle.properties") 2>/dev/null || true
      extra+=(--bind "$gradle_dir" /opt/gradle-home)
    fi
  fi
  [ -d /opt/android-emu ] && extra+=(--bind /opt/android-emu /opt/android-emu)
```

Testé sur une copie du wrapper actuel, avec un faux `bwrap` qui affiche ses arguments :
- Sacha reçoit `/opt/android` en lecture-écriture et `/opt/gradle-cache/sacha` ;
- un autre agent de DMC reçoit `/opt/android` en lecture seule et `/opt/gradle-cache/DMC` ;
- un agent d'une autre société qui aurait le même identifiant d'agent reçoit la lecture seule et le dossier de sa société (`BLA`) ;
- une société inconnue n'a pas de dossier Gradle partagé ;
- rien n'est monté si un dossier n'existe pas ;
- un lien glissé à la place de `gradle.properties` n'est pas suivi, et un `/opt/gradle-cache` non inscriptible ne fait pas échouer le run.

Comme les autres protections du wrapper, cela ne vaut que pour les runs confinés (`filesystemScope=workspace`).

Puis redéployer Paperclip, et vérifier :

```bash
docker exec <conteneur-paperclip> grep -c DMC-114 /usr/local/bin/bwrap-paperclip   # attendu : 1
docker exec <conteneur-paperclip> ls -ld /opt/android /opt/android-rw /opt/gradle-cache /opt/android-emu
```

### Étape 4 : application `android-devices` (adb + émulateur)

- Créer une Custom App TrueNAS (« Install via YAML ») nommée `android-devices`, avec le contenu de `android-devices-compose.yaml` (plus bas).
- Remplacer d'abord `GID_KVM` par la valeur relevée à l'étape 0.
- Les deux conteneurs attendent que le SDK soit copié (vérification toutes les 30 s), puis démarrent seuls. Rien à refaire après mon passage.

Vérifier :

```bash
docker network inspect android-devices --format '{{range .Containers}}{{.Name}}{{"\n"}}{{end}}'   # Paperclip, adb, android-emu
docker exec <conteneur-android-emu> ls -l /dev/kvm          # le device est bien passé
docker exec <conteneur-android-emu> id                      # le GID kvm figure dans les groupes
```

**Accès réseau nouveaux, à approuver** :
- `adb` et `android-emu` écoutent sur le port 5037, **uniquement sur le réseau Docker `android-devices`** (aucun `ports:` publié) ;
- `adb` sort vers le Google TV Streamer en Wi-Fi (NAT Docker), jamais vers le téléphone ;
- l'Android de l'émulateur a accès à Internet par le NAT du conteneur, comme un téléphone (utile pour tester une app qui appelle une API).

### Étape 5 : ménage mensuel (cron)

Le cron lance des copies des scripts **appartenant à root, hors de tout volume monté dans un conteneur** : jamais celles du volume, que Sacha peut modifier.

`android-purge-cron.sh` lance `android-purge.sh` dans un **conteneur jetable** :
- sans réseau, sous l'uid 1000, sans capacités, système de fichiers en lecture seule ;
- **un seul dossier monté à la fois** (le workspace d'un agent, les projets d'une société, le dossier Gradle d'une société).

Pourquoi : rien de ce que contient un workspace ne doit pouvoir s'exécuter sur l'hôte. Le premier jet passait par `git`, qui peut lancer un programme choisi par le dépôt (`core.fsmonitor`) ; git n'est plus utilisé. Et un lien glissé par un agent ne mène qu'à son propre dossier.

```bash
install -d -o root -g root -m 755 /mnt/fastPool/root-bin
src=/mnt/fastPool/hermes/paperclip/instances/default/workspaces/7b8c33a9-2329-473b-a7d0-822dd974b83f/tools/android-shared
install -o root -g root -m 755 "$src/android-purge.sh" "$src/android-purge-cron.sh" /mnt/fastPool/root-bin/
less /mnt/fastPool/root-bin/android-purge-cron.sh /mnt/fastPool/root-bin/android-purge.sh   # relire les copies installées
bash /mnt/fastPool/root-bin/android-purge-cron.sh --dry-run                                  # essai à blanc
```

Puis, dans System > Advanced Settings > Cron Jobs > Add :
- utilisateur : `root` ;
- planification : `0 4 1 * *` (le 1er du mois à 4 h) ;
- commande : `bash /mnt/fastPool/root-bin/android-purge-cron.sh`.

`/mnt/fastPool/root-bin` ne doit être monté dans aucun conteneur.

### Ensuite (Sacha, sans toucher à l'hôte) : fait le 03/11

1. SDK de DMC-104 copié dans `/opt/android`, sans second téléchargement (copie vérifiée identique). Scripts installés, `sdk-sync.sh --dry-run` : aucun écart.
2. Template compilé (commit `22fe965` de `main`) avec mon dossier Gradle privé, cache commun publié (`gradle-seed.sh`). Build **hors ligne** avec un dossier Gradle vide : réussi, rien téléchargé.
3. Conteneurs `adb` et `android-emu` démarrés seuls.
4. `phone` et `tv` démarrés : voir « Émulateur » pour les mesures. APK du template installé et lancé sur `tv`.
5. Consigne ajoutée au `CLAUDE.md` de template-android : PR #6, relue, fusionnée (`808c84f`).
6. Tailles mesurées, workspace vidé.
7. Reste : appairage du Google TV Streamer, avec Micka à côté (voir « ADB »).

### Plan B, si Micka préfère ne pas toucher au wrapper

- Un conteneur `android-admin` ponctuel, lancé par Micka à chaque mise à jour du SDK, monte `/mnt/fastPool/android` en lecture-écriture et lance les scripts.
- Les autres agents verraient alors le SDK par un montage en lecture seule du conteneur Paperclip. Pour Gradle, il faudrait un dossier par société dans le YAML.
- Ça marche, mais chaque mise à jour (quelques fois par an) demande alors une intervention sur l'hôte. De plus, l'image `debian:trixie-slim` n'a pas `curl`, nécessaire pour télécharger le JDK.
- C'est pour ça que je propose le wrapper.

### Options écartées

- **`/dev/kvm` dans le conteneur Paperclip** : le `/dev` de chaque run est recréé par `bwrap`, il faudrait aussi l'y remonter, et KVM serait ouvert à tous les agents de toutes les sociétés.
- **Socket Docker donnée aux agents** pour lancer un conteneur émulateur à la demande : cela revient à leur donner root sur l'hôte.
- **Émulateur allumé en permanence** : environ 3 Go de RAM pris en continu.
- **Un seul dossier Gradle commun en écriture**, comme écrit dans le ticket : il permettrait à n'importe quel agent d'exécuter du code dans les builds des autres sociétés (voir « Architecture »). Le cache commun en lecture seule évite quand même de re-télécharger les dépendances.
- **Rattacher les deux applications au réseau interne de Paperclip** : si Paperclip recrée son réseau (mise à jour, arrêt), `android-devices` serait déconnectée, ou l'opération échouerait.

### Risques résiduels (acceptés, à connaître)

- **Les builds de Sacha ont l'écriture sur le SDK.** Le code d'un build (scripts Gradle, plugins) pourrait modifier le SDK, les scripts d'admin ou le cache commun publié à tous. Pour limiter ce risque :
  - je ne compile, avec ce droit, que des commits relus des templates, aux versions figées ;
  - je ne publie le cache commun (`gradle-seed.sh`) qu'après un tel build.
  
  L'alternative est le plan B : plus sûr, mais avec une intervention de Micka à chaque mise à jour.
- **Le dossier de contrôle de l'émulateur est commun à toutes les sociétés.** Un agent malveillant peut gêner une session en cours : la voler, la faire paraître finie, ou bloquer l'émulateur jusqu'à 30 minutes. Il ne peut rien exécuter dans le conteneur `android-emu`.
- **Les runs non confinés** (hors `filesystemScope=workspace`) voient tout le conteneur Paperclip, `/opt/android-rw` compris, comme ils voient déjà tous les workspaces.

## Versions et mise à jour (Sacha seul)

- `android-sdk.versions` est le seul fichier où vivent les versions : JDK, build-tools, platforms, révisions attendues de platform-tools, cmdline-tools et emulator, images et profils de l'émulateur.
- `admin/sdk-sync.sh /opt/android` :
  - installe ce qui manque et retire ce qui n'est plus listé ;
  - vérifie les révisions et copie le fichier dans `/opt/android/VERSIONS` ;
  - `--dry-run` montre l'écart sans rien changer (code 3 s'il y en a un).
- **Licences** : acceptées une fois, gardées dans `sdk/licenses/`.
- **JDK** : chaque version est dans `jdk-<version>` ; le lien `jdk` bascule d'un seul renommage atomique. Le JDK précédent reste jusqu'à la synchro suivante, pour les builds en cours.
- **Révisions** : une révision de platform-tools, cmdline-tools ou emulator différente du fichier arrête le script sans écrire `VERSIONS`.
- **Maintenance** : pendant une synchro, `sdk-sync.sh` pose `/opt/android-emu/maintenance`. Aucun émulateur ne démarre, et le script attend la fin de la session en cours (2 minutes au plus). Le drapeau est retiré même en cas d'interruption.
- **Cache Gradle commun** : `admin/gradle-seed.sh /opt/android` publie `modules-2` de mon dossier Gradle privé dans `gradle-ro-cache`, d'un seul renommage, sans les verrous. La version précédente reste pour les builds en cours. À relancer quand les dépendances des templates changent.
- **Scripts** : `admin/admin-install.sh /opt/android` installe une nouvelle version, fichier par fichier, par renommage. Le superviseur de l'émulateur voit le changement et se relance seul au repos.
- **platform-tools** : après une mise à jour, Micka redémarre le conteneur `adb`, puis je refais `adb connect` pour chaque appareil.

## Ménage mensuel

`android-purge.sh`, lancé par `android-purge-cron.sh` (cron de l'hôte, étape 5) dans un conteneur jetable, dossier par dossier :

- ne touche à rien si un processus Gradle ou Kotlin tourne sur le NAS ;
- supprime les `build/` des projets Gradle sans modification depuis 30 jours :
  - seulement à côté d'un `build.gradle(.kts)` ; jamais à travers un lien ;
  - sans `git` (il pourrait exécuter du code venu du dépôt) : un `build/` suivi par git se restaure par `git checkout` ;
  - ignore `node_modules`, `.git` et `.zfs`, et ne change pas de système de fichiers ;
  - garde un dossier en cas d'erreur de lecture ;
- Gradle retire déjà seul ses entrées inutilisées (dépendances et distributions : 30 jours ; build-cache et transforms : 7 jours) ;
- au-delà de 5 Go pour un dossier Gradle de société, vide build-cache et transforms ; si c'est encore trop, vide les dépendances téléchargées (elles se re-téléchargent) ;
- les anciennes versions du SDK partent avec `sdk-sync.sh`, celles du cache commun avec `gradle-seed.sh`.

## ADB (Google TV Streamer)

Un seul serveur adb (conteneur `adb`), relié au **Google TV Streamer** en Wi-Fi. `ADB_SERVER_SOCKET=tcp:adb:5037` est déjà réglé pour tous les runs.

**Le téléphone OnePlus n'y est jamais raccordé** (décision du fondateur, 03/11). Il reste derrière guarded-phone-mcp : un utilisateur Android par agent, un PIN, un bail et un journal. Personne ne fait `adb connect` ni `adb pair` vers 192.168.1.80.

Agent :

```
$ANDROID_HOME/platform-tools/adb devices -l                         # lister les appareils
$ANDROID_HOME/platform-tools/adb -s <serial> install -r app.apk     # installer un APK
$ANDROID_HOME/platform-tools/adb -s <serial> shell am start -n <package>/<activity>
```

Raccorder le Google TV Streamer (Sacha, avec Micka à côté de l'appareil la première fois) :

1. Sur l'appareil : Paramètres > Système > Options pour les développeurs > Débogage sans fil > Associer avec un code.
2. Lancer `adb pair <ip>:<port-d-association> <code>`, puis `adb connect <ip>:<port>`.
3. Pour une adresse stable, faire une réservation DHCP de l'appareil sur la box.

Le port du débogage sans fil change à chaque redémarrage de l'appareil. Il n'y a pas de reconnexion automatique, car le mDNS ne traverse pas le réseau Docker : il faut lire le nouveau port sur l'écran de l'appareil et refaire `adb connect <ip>:<port>`.

## Émulateur

Il sert quand le téléphone (guarded-phone-mcp) ou le Google TV Streamer ne sont pas disponibles. Il est headless, lancé à la demande, **un seul à la fois** sur le NAS, et toujours arrêté après le test.

Profils (`EMU_PROFILES` dans `android-sdk.versions`) :

| Profil | Image système | Appareil | RAM invitée |
|---|---|---|---|
| `phone` | `system-images;android-36;google_apis;x86_64` | `pixel_7` (1080×2400) | 4 Go (minimum imposé par l'émulateur) |
| `tv` | `system-images;android-36;google-tv;x86_64` | `tv_1080p` (1920×1080) | 2 Go |

Mesures réelles du 03/11 (N100, KVM, démarrage à froid, `-wipe-data`) :

| Profil | Démarrage (`sys.boot_completed`) | RAM du qemu (VmRSS) | RAM vue par Android |
|---|---|---|---|
| `phone` | 82 à 85 s, 115 s avec le NAS chargé (4 démarrages) | 4,3 à 4,4 Go | 3,8 Go |
| `tv` | 70 à 73 s, 119 à 139 s avec le NAS chargé (5 démarrages) | 2,5 à 2,7 Go | 1,9 Go |

- **RAM de `phone`** : l'émulateur passe toute image téléphone API 36 à 4 Go (« Increasing RAM size to 4096MB »), quel que soit l'écran (8 appareils essayés, de 720×1280 à 1080×2400) et même avec `-memory 2048`. Ni la fonction AVF ni `advancedFeatures.ini` n'y changent rien. La RAM de chaque profil reste donc celle de son `config.ini` (2 Go pour `tv`).
- **Plafond du conteneur** : 4,4 Go de qemu dans un plafond de 5 Go laissent peu de marge à `phone` pendant un test. Demande à Micka : passer `mem_limit` de `android-emu` à 6 Go (10,4 Go libres sur l'hôte le 03/11).
- **« Prêt »** (DMC-294, 05/11) : le superviseur attend `sys.boot_completed=1` **et** `sys.user.0.ce_available=true`, dans le même délai de démarrage (300 s, `EMU_BOOT_TIMEOUT`). Juste après `sys.boot_completed`, l'utilisateur 0 n'est pas encore déverrouillé : écran noir et `com.android.phone` qui redémarre toutes les 30 s. Même règle avant la prise du snapshot propre. Le temps noté dans `metrics.csv` compte cette attente.
- `emu-start` attend que l'appareil réponde aussi par `tcp:android-emu:5037` (60 s au plus, `EMU_CLIENT_WAIT`) avant de rendre la main : juste après le démarrage, un premier essai sur `tv` avait reçu « device not found ».

**Agent : toujours `emu-run`.** Il demande l'émulateur, lance la commande, puis le rend, même si la commande échoue ou est interrompue. La commande voit `ADB_SERVER_SOCKET=tcp:android-emu:5037` et `ANDROID_SERIAL=emulator-5554` : `adb` sans `-s` vise l'émulateur.

```
/opt/android/admin/emu/emu-run phone -- $ANDROID_HOME/platform-tools/adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
/opt/android/admin/emu/emu-run tv --wait-lock 120 -- bash -c '$ANDROID_HOME/platform-tools/adb install -r app.apk && $ANDROID_HOME/platform-tools/adb shell am start -n <package>/<activity>'
```

`emu-start <profil>` et `emu-stop` servent à enchaîner plusieurs commandes, **dans la même commande shell** et avec un trap. `emu-start` affiche l'identifiant de la session, qu'on passe à `emu-stop --id` pour ne jamais arrêter la session d'un autre :

```
id=$(/opt/android/admin/emu/emu-start phone) && trap "/opt/android/admin/emu/emu-stop --id $id" EXIT
export ADB_SERVER_SOCKET=tcp:android-emu:5037 ANDROID_SERIAL=emulator-5554
...
```

**Session gardée entre plusieurs commandes d'un même run** (DMC-174) : plutôt que de redémarrer l'émulateur à froid (80 à 260 s) à chaque commande, on garde la session et on renouvelle son bail par `emu-renew` en tête de chaque commande suivante. L'identifiant se garde dans le dossier temporaire du run. Aucun processus de fond : l'émulateur tourne dans le conteneur `android-emu`, pas dans le run.

```
# commande 1
id=$(/opt/android/admin/emu/emu-start phone --wait-lock 120 --lease 300) && echo "$id" >"$PAPERCLIP_RUN_SCRATCH_DIR/emu.id"
export ADB_SERVER_SOCKET=tcp:android-emu:5037 ANDROID_SERIAL=emulator-5554
...
# commandes suivantes : bail de 600 s pendant le test, puis 300 s pour la suite
id=$(cat "$PAPERCLIP_RUN_SCRATCH_DIR/emu.id") && /opt/android/admin/emu/emu-renew --id "$id" --lease 600 || exit
export ADB_SERVER_SOCKET=tcp:android-emu:5037 ANDROID_SERIAL=emulator-5554
...
/opt/android/admin/emu/emu-renew --id "$id" --lease 300
# dernière commande
/opt/android/admin/emu/emu-stop --id "$(cat "$PAPERCLIP_RUN_SCRATCH_DIR/emu.id")"
```

- `emu-renew --id <id> [--lease <s>]` : bail de 10 à 600 s à partir de maintenant (300 par défaut). Il peut aussi le raccourcir. Les 30 minutes par session restent la limite absolue.
- Il ne renouvelle que la session `<id>`, prête, et seulement pour le même agent et le même run Paperclip (75 sinon). Session terminée ou arrêt déjà demandé : code 3, avec la cause, et il faut repartir d'`emu-start`. 69 : conteneur absent.
- Si d'autres agents attendent dans la file, il le signale : rendre l'émulateur (`emu-stop --id`) dès le dernier test.
- Oubli d'`emu-stop` : le superviseur rend l'émulateur à l'échéance du dernier bail.

**Démarrage depuis un snapshot propre** (DMC-174, en essai) : au lieu d'un démarrage à froid (80 à 260 s), la session charge un snapshot pris juste après un démarrage à froid, en lecture seule.

- **Réglage par profil**, dans `/opt/android/emu-snapshot.conf` (volume SDK : Sacha seul l'écrit), une ligne `<profil>=off|opt-in|on`. Fichier absent ou profil absent : `off`, tout démarre à froid comme avant.
  - `opt-in` : seulement pour `emu-start --boot snapshot` (ou `emu-run <profil> --boot snapshot -- …`). C'est le mode d'essai : les autres sessions ne changent pas.
  - `on` : par défaut, sauf `--boot cold`.
- **Construction** : le superviseur la fait seul, quand l'émulateur est au repos depuis 120 s (`EMU_GOLDEN_IDLE_S`) et qu'il reste 12 Go libres dans `/emu-data`. Il démarre le profil à froid, attend 30 s après la fin du démarrage, prend le snapshot `clean` par `adb emu avd snapshot save clean`, puis arrête l'émulateur. Pendant la construction, le serveur adb n'écoute que dans le conteneur (sans `-a`) : aucun agent ne peut s'y connecter et salir le snapshot. Le besoin de construction est vérifié toutes les 30 s (`EMU_GOLDEN_CHECK_S`). Le snapshot est dans `/emu-data/golden/<profil>` du conteneur. Son démarrage à froid a 480 s (`EMU_GOLDEN_BOOT_TIMEOUT`) au lieu de 300 : il ne fait attendre aucun agent.
- **Construction demandée** (Sacha) : `emu-start <profil> --boot build`. Utile quand l'émulateur n'a jamais 120 s de repos (04/11 au soir : une construction au repos abandonnée à chaque nouvelle demande). La demande prend son tour dans la file et passe le contrôle de charge comme une session. `emu-start` rend la main dès que la construction a commencé (code 0, identifiant sur la sortie) ; l'émulateur reste pris jusqu'à la fin, au plus 480 + 30 + 300 s, sans bail à renouveler. Un agent qui arrive attend dans la file : la construction n'est pas abandonnée. Suivi dans `golden.<profil>` et `results/<id>` (`built` code 0, `failed` ou `aborted` code 1) ; abandon par `emu-stop --id <id>`. Snapshot déjà prêt et à jour : code 0 sans rien construire. Réglage `off` : code 2. Le délai d'une heure après un échec ne s'applique pas à une demande. Pas avec `emu-run` (code 2).
  - Si un agent demande l'émulateur pendant la construction, elle est abandonnée tout de suite (TERM, sans attendre) et l'agent est servi à froid.
  - Échec de construction, ou snapshot invalidé (chargement en échec, modifié par une session, session bloquée) : nouvel essai au bout d'une heure (`EMU_GOLDEN_RETRY_S`), jamais en boucle. État publié dans `/opt/android-emu/golden.<profil>` (`building`, `ready` avec durée et taille, `failed` avec la cause, `invalid`).
- **Snapshot caduc** : son empreinte couvre `config.ini` du profil, la version de l'émulateur, celle de l'image système et la clé adb. Après un `sdk-sync` qui change l'un d'eux, les sessions repartent à froid et le snapshot est reconstruit au repos.
- **Session** : `-read-only -snapshot clean -force-snapshot-load -no-snapshot-save`, sans `-wipe-data`. Les écritures de la session vont dans des copies temporaires sous `/emu-data/run/tmp`, supprimées à l'arrêt : rien ne passe d'une session à l'autre.
- **Garde-fous** :
  - chargement en échec ou plus long que 90 s (`EMU_SNAP_BOOT_TIMEOUT`) : la même session repart à froid, sans erreur pour l'agent, avec de nouveau les 300 s de `EMU_BOOT_TIMEOUT` (`emu-start` attend jusqu'à 300 + 90 + 30 s). Le snapshot est invalidé, puis reconstruit au plus tôt une heure après (journal dans `/emu-data/last-snapshot-fail.log`) ;
  - à l'arrêt de chaque session chargée depuis le snapshot, le superviseur vérifie que les images et le snapshot n'ont pas changé (taille et date). S'ils ont changé, le snapshot est invalidé : la session suivante ne peut pas voir les données de la précédente. Les fichiers changés sont cités dans `golden.<profil>` (`message=`). Seule exception : `snapshots/<nom>/snapshot.pb`, la description du snapshot, que l'émulateur peut réécrire à chaque chargement, même en `-read-only` (05/11 : snapshot invalidé dès la première session réelle). Elle ne contient aucune donnée de l'appareil : le snapshot est gardé, et le changement est signalé (`meta_changed=`).
- La métrique indique le mode en 5e colonne : `cold`, `snapshot`, ou `fallback` (chargement en échec puis froid ; le temps compte alors les deux essais depuis la demande). `emu-start` affiche `cold` ou `snapshot`.
- Session bloquée (qemu restant) alors qu'elle était chargée depuis le snapshot : le snapshot est invalidé.
- **À mesurer en essai, hors heures chargées**, avant de passer `phone` en `on` : temps de chargement, taille du snapshot sur disque, RAM du qemu, compatibilité `-gpu swiftshader_indirect`, et isolation (un fichier écrit dans `/data/local/tmp` par une session absent de la suivante).

Règles tenues :

- **Headless et à froid** : options `-no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save -wipe-data -gpu swiftshader_indirect`. Aucun snapshot de session n'est gardé (seul le snapshot propre d'un profil réglé, ci-dessus, chargé en lecture seule). Les données de la session (`userdata`, jusqu'à 2 Go) sont dans `/emu-data/run` du conteneur `android-emu`, et supprimées à l'arrêt.
- **Un seul à la fois** :
  - la session est un dossier (`/opt/android-emu/session`), créé par `mkdir`, qui est atomique ;
  - s'il existe déjà : code 75, avec le profil, l'agent et la tâche propriétaires, ou attente avec `--wait-lock <s>` ;
  - **file d'attente par ordre d'arrivée** (DMC-168) : avec `--wait-lock`, le client dépose un ticket `/opt/android-emu/queue/<arrivée en µs>-<id>`, réécrit chaque seconde, et seul le plus ancien ticket vivant prend l'émulateur. Celui qui vient de le rendre repasse derrière ceux qui attendaient ; un client sans `--wait-lock` est refusé (75) tant que quelqu'un attend. Un ticket muet depuis 15 s (client tué) est ignoré puis retiré ;
  - délai écoulé : le rang est mis de côté (`queue/parked-<run>`) ; le même run Paperclip qui redemande dans les 5 minutes garde sa place ;
  - **charge de l'hôte** (DMC-285) : au moment de prendre l'émulateur, `emu-start` lit la charge sur 1 minute (`/proc/loadavg`, `EMU_LOADAVG` dans les tests). Au-dessus de `EMU_MAX_LOAD` (12 par défaut, 12,00 passe), refus en code 76 avec « Hôte chargé (charge X > 12) : réessaie plus tard », sans rien démarrer ; `emu-run` rend ce code tel quel. Raison : le 04/11, avec une charge de 27 à 45, le démarrage de `phone` est passé de 91 à 256 s et 5 démarrages sur 15 ont échoué au délai de 300 s, chacun relançant un démarrage complet. Un client en file n'est jugé qu'à son tour et émulateur libre : la charge due à l'émulateur en cours ne le fait pas refuser, et un refus le retire de la file sans garder son rang. Fichier illisible : pas de refus. Les plafonds par session (30 minutes, bail de 90 s d'`emu-run`) ne changent pas ;
  - le superviseur ne fait tourner qu'un émulateur.
- **Toujours arrêté** :
  - `adb emu kill`, puis TERM, puis KILL sur tout le groupe de processus de l'émulateur ;
  - arrêt de tout reste de l'émulateur ou d'adb, puis contrôle qu'aucun processus `qemu-system-*` ne reste dans le conteneur. S'il en reste un, la session reste bloquée (aucun second émulateur) et le superviseur réessaie ;
  - `emu-run` arrête aussi tous les descendants de la commande de test. Interrompu pendant le démarrage, il fait rendre l'émulateur par `emu-start` ;
  - `emu-stop` refuse d'arrêter l'émulateur d'un autre agent (sauf `--force`).
- **Bail** :
  - sans renouvellement, le superviseur arrête l'émulateur ;
  - `emu-start` renouvelle le bail pendant tout le démarrage, puis `emu-run` toutes les 20 s (bail de 90 s) : si le run meurt, l'émulateur s'arrête dans les 90 s ;
  - `emu-start` seul : bail de 600 s (`--lease`) ;
  - `emu-renew --id` : nouveau bail de 10 à 600 s, même agent et même run seulement (DMC-174) ;
  - dans tous les cas, 30 minutes au plus par session.
- **Sans KVM** : refus immédiat (code 4).
- **Hôte chargé** : refus immédiat (code 76), voir « Un seul à la fois ». L'agent pose une attente datée d'une heure au lieu de réessayer.
- **Plafonds** : 5 Go de RAM (6 Go demandés, voir plus haut) et 3 cœurs pour le conteneur `android-emu` ; au-delà, seul ce conteneur est touché.
- **Mesures** : à chaque démarrage, le temps de démarrage et la RAM du qemu sont affichés et ajoutés à `/opt/android-emu/metrics.csv` (`date,profil,secondes,Mo,mode` ; mode `cold`, `snapshot` ou `fallback` depuis DMC-174, absent avant).
- **Comptes rendus** : `/opt/android-emu/results/<id>` (résultat, code, fin du journal de l'émulateur en cas d'échec), gardés un jour. Émulateur arrêté seul : le message donne aussi le code du lanceur et, si le compteur `oom_kill` du cgroup du conteneur a bougé pendant la session, « tué faute de mémoire » avec le plafond (DMC-168).
- **Dossier de contrôle traité comme hostile** :
  - le superviseur n'exécute rien de ce qu'il contient ;
  - il ne lit que des champs validés (profil connu, identifiant hexadécimal), 4 Ko au plus, avec un délai de 2 s, et sans suivre de lien ;
  - une demande d'arrêt ou un bail ne comptent que s'ils portent l'identifiant de la session en cours ;
  - une erreur ne l'arrête jamais.

Durée : `--wait-lock` + démarrage (jusqu'à 330 s) + attente réseau (jusqu'à 70 s, `EMU_CLIENT_WAIT`) + test + arrêt (jusqu'à 40 s) doivent tenir sous 10 minutes dans une commande d'agent (règle 7 du CLAUDE.md commun). Avec `--wait-lock 120`, il reste au moins 40 s pour le test dans le pire cas ; en pratique, le démarrage prend de 70 à 140 s selon la charge du NAS.

`adb` toujours par son chemin complet, `$ANDROID_HOME/platform-tools/adb` : dans un run, `BASH_ENV` réécrit le `PATH` de chaque `bash -c`, et un `adb` ajouté au `PATH` y devient introuvable.

Bibliothèques : le qemu headless a besoin de `libX11` et de ses dépendances. Le bac à sable des agents les a, l'image du conteneur non : `sdk-sync.sh` compte comme manquante toute bibliothèque résolue hors du SDK et de `emulator-libs`, sauf la glibc et `libgcc_s`. Il les extrait des paquets Debian trixie (signatures vérifiées) dans `/opt/android/emulator-libs`, et le superviseur les ajoute à `LD_LIBRARY_PATH`. Le rendu charge aussi `libX11-xcb` à l'exécution (`dlopen`), que `ldd` ne voit pas : elle est listée dans `EMULATOR_DLOPEN_LIBS`, et `sdk-sync.sh` vérifie sa présence (premier démarrage du 03/11 : arrêt sur cette bibliothèque, corrigé sans intervention sur l'hôte).

Tests : `admin/emu/tests/test-emu.sh <dossier-temporaire>` copie les scripts et lance le superviseur avec un faux émulateur et un faux adb, sans KVM : 143 contrôles, environ 7 à 8 min. La charge y est simulée (`EMU_LOADAVG`) : la charge réelle du NAS ne change pas le résultat, mais ne lancer la suite que sous 12.
