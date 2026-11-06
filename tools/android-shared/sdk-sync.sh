#!/usr/bin/env bash
# Installe ou met à jour l'outillage Android partagé du NAS (JDK + SDK)
# selon android-sdk.versions, puis retire les versions qui n'y sont plus.
# Réservé à Sacha : pour les autres agents, le volume est en lecture seule.
#
# Usage : sdk-sync.sh [--dry-run] <racine-du-volume>
#   <racine>/jdk  JAVA_HOME (lien vers jdk-<version>)
#   <racine>/sdk  ANDROID_HOME (doit déjà contenir cmdline-tools/latest)
#   <racine>/avd  profils de l'émulateur (config.ini, emu-options), lus par
#                 emu-start et le superviseur
#   <racine>/emulator-libs  bibliothèques X11 du qemu headless
# --dry-run : affiche l'écart avec android-sdk.versions sans toucher aux
#   paquets ni au JDK (code 3 s'il y a un écart). sdkmanager rafraîchit
#   seulement son index sdk/.knownPackages.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
dry=0 root=''
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    -*) echo "option inconnue : $arg" >&2; exit 2 ;;
    *) [ -z "$root" ] || { echo "un seul argument <racine> attendu" >&2; exit 2; }
       root=$arg ;;
  esac
done
[ -n "$root" ] || { echo "usage : sdk-sync.sh [--dry-run] <racine-du-volume>" >&2; exit 2; }
# shellcheck source=android-sdk.versions
. "$here/android-sdk.versions"

# Profil <nom>:<image>:<appareil>[:<options>] -> name image device options.
# Options (DMC-408), séparées par des virgules, liste blanche du superviseur :
# lowram, memory=<Mo> (1024 à 8192). Elles deviennent <nom>.avd/emu-options.
parse_profile() {
  local raw extra o list=()
  IFS=: read -r name image device raw extra <<<"$1"
  options=''
  if [ -z "$name" ] || [ -z "$image" ] || [ -z "$device" ] || [ -n "$extra" ]; then
    echo "EMU_PROFILES : profil mal formé : $1" >&2; exit 2
  fi
  [ -z "$raw" ] || IFS=, read -ra list <<<"$raw"
  for o in "${list[@]}"; do
    case "$o" in
      lowram) options+="lowram=yes"$'\n' ;;
      memory=[1-8][0-9][0-9][0-9])
        if [ "${o#memory=}" -lt 1024 ] || [ "${o#memory=}" -gt 8192 ]; then
          echo "EMU_PROFILES : $name : $o hors de 1024 à 8192" >&2; exit 2
        fi
        options+="$o"$'\n' ;;
      *) echo "EMU_PROFILES : $name : option non permise : $o (lowram, memory=<Mo>)" >&2; exit 2 ;;
    esac
  done
}

# Chaque image d'un profil doit être listée, sinon l'étape 4 la retirerait.
for spec in $EMU_PROFILES; do
  parse_profile "$spec"
  case " $SYSTEM_IMAGES " in
    *" $image "*) ;;
    *) echo "EMU_PROFILES : $image n'est pas dans SYSTEM_IMAGES" >&2; exit 2 ;;
  esac
done

jdk=$root/jdk
sdk=$root/sdk
sdkmanager=$sdk/cmdline-tools/latest/bin/sdkmanager
drift=0 tmp='' maint=''
trap '[ -z "$tmp" ] || rm -rf "$tmp"; [ -z "$maint" ] || rm -f "$maint"' EXIT

# Pas de mise à jour sous un émulateur en marche : la maintenance bloque tout
# nouveau démarrage (superviseur et emu-start), puis on attend la fin de la
# session en cours (2 min au plus : la synchro doit tenir dans une commande
# d'agent de moins de 10 min). Le trap ci-dessus retire toujours le drapeau.
emu_ctl=${EMU_CTL:-/opt/android-emu}
if [ "$dry" = 0 ] && [ -d "$emu_ctl" ] && [ -w "$emu_ctl" ]; then
  rm -f -- "$emu_ctl/maintenance"
  # noclobber : création exclusive, jamais à travers un lien glissé là.
  (set -C; : >"$emu_ctl/maintenance") 2>/dev/null ||
    { echo "impossible de poser $emu_ctl/maintenance" >&2; exit 75; }
  maint=$emu_ctl/maintenance
  waited=0
  while [ -e "$emu_ctl/session" ]; do
    if [ "$waited" -ge 120 ]; then
      echo "une session d'émulateur est en cours depuis plus de 2 min : réessayer plus tard" >&2
      exit 75
    fi
    sleep 5
    waited=$((waited + 5))
  done
fi

say() { printf '== %s\n' "$*"; }
run() { if [ "$dry" = 1 ]; then echo "   (dry-run) $*"; else "$@"; fi; }
revision() {
  [ -f "$1/source.properties" ] || return 0
  sed -n 's/^Pkg.Revision=//p' "$1/source.properties" | head -1
}

[ -x "$sdkmanager" ] || { echo "sdkmanager introuvable : $sdkmanager" >&2; exit 1; }

# 1. JDK : chaque version dans jdk-<version>, et jdk est un lien basculé
#    d'un seul renommage (atomique). La version précédente reste en place
#    jusqu'à la synchro suivante, pour les builds qui l'utilisent encore.
current=$(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)-LTS"$/\1/p' "$jdk/release" 2>/dev/null || true)
if [ "$current" = "$JDK_VERSION" ]; then
  say "JDK $JDK_VERSION déjà en place"
else
  drift=1
  say "JDK : ${current:-absent} -> $JDK_VERSION"
  tag=jdk-${JDK_VERSION/+/%2B}
  file=OpenJDK21U-jdk_x64_linux_hotspot_${JDK_VERSION/+/_}.tar.gz
  base=https://github.com/adoptium/temurin21-binaries/releases/download/$tag
  if [ "$dry" = 0 ]; then
    # Premier passage : l'ancien jdk/ réel devient jdk-<sa version>.
    if [ -d "$jdk" ] && [ ! -L "$jdk" ]; then
      mv "$jdk" "$root/jdk-${current:-ancien}"
      ln -s "jdk-${current:-ancien}" "$jdk"
    fi
    tmp=$(mktemp -d "$root/.jdk-dl.XXXXXX")
    curl -fsSL -o "$tmp/$file" "$base/$file"
    curl -fsSL -o "$tmp/$file.sha256.txt" "$base/$file.sha256.txt"
    (cd "$tmp" && sha256sum -c "$file.sha256.txt")
    target=$root/jdk-$JDK_VERSION
    rm -rf "$target" && mkdir "$target"
    tar -xzf "$tmp/$file" -C "$target" --strip-components=1
    ln -s "jdk-$JDK_VERSION" "$tmp/link" && mv -T "$tmp/link" "$jdk"
  fi
fi
# Ménage : on garde le JDK courant et le précédent.
if [ "$dry" = 0 ] && [ -L "$jdk" ]; then
  keep=$(readlink "$jdk")
  mapfile -t old < <(find "$root" -maxdepth 1 -name 'jdk-*' -printf '%T@ %f\n' \
    | sort -rn | cut -d' ' -f2- | grep -vxF "$keep" | tail -n +2)
  for d in "${old[@]}"; do say "Ancien JDK retiré : $d"; rm -rf "${root:?}/$d"; done
fi
export JAVA_HOME=$jdk

# 2. Licences : acceptées une seule fois, gardées dans <sdk>/licenses.
if [ ! -f "$sdk/licenses/android-sdk-license" ]; then
  say "Acceptation des licences"
  if [ "$dry" = 0 ]; then
    "$sdkmanager" --sdk_root="$sdk" --licenses < <(yes) >/dev/null
    [ -f "$sdk/licenses/android-sdk-license" ] || { echo "licences non acceptées" >&2; exit 1; }
  fi
fi

# 3. Paquets figés
wanted=(platform-tools)
for v in $BUILD_TOOLS; do wanted+=("build-tools;$v"); done
for v in $PLATFORMS; do wanted+=("platforms;$v"); done
wanted+=(emulator)
for v in $SYSTEM_IMAGES; do wanted+=("$v"); done

listing=$("$sdkmanager" --sdk_root="$sdk" --list_installed 2>&1) \
  || { echo "$listing" >&2; echo "sdkmanager --list_installed a échoué" >&2; exit 1; }
installed=$(sed -n 's/^ *\([a-z-]*;[^ |]*\) *|.*/\1/p; s/^ *\(platform-tools\|emulator\) *|.*/\1/p' <<<"$listing")

missing=()
for p in "${wanted[@]}"; do
  grep -qxF "$p" <<<"$installed" || missing+=("$p")
done
if [ "${#missing[@]}" -gt 0 ]; then
  drift=1
  say "À installer : ${missing[*]}"
  run "$sdkmanager" --sdk_root="$sdk" --install "${missing[@]}"
else
  say "Paquets figés déjà installés : ${wanted[*]}"
fi

# 4. Anciennes versions : tout build-tools / platforms / image système non listé est retiré,
#    après l'installation des versions voulues.
extra=()
while IFS= read -r p; do
  case "$p" in
    build-tools\;* | platforms\;* | system-images\;*)
      printf '%s\n' "${wanted[@]}" | grep -qxF "$p" || extra+=("$p") ;;
  esac
done <<<"$installed"
if [ "${#extra[@]}" -gt 0 ]; then
  drift=1
  say "À retirer : ${extra[*]}"
  run "$sdkmanager" --sdk_root="$sdk" --uninstall "${extra[@]}"
fi

# 5. Paquets « dernière version seulement » : on vérifie la révision.
bad=0
for pair in "platform-tools:$PLATFORM_TOOLS_REVISION" "cmdline-tools/latest:$CMDLINE_TOOLS_REVISION" \
  "emulator:$EMULATOR_REVISION"; do
  dir=${pair%%:*} want=${pair#*:}
  got=$(revision "$sdk/$dir")
  if [ "$got" != "$want" ]; then
    bad=1
    echo "ÉCART : $dir est en ${got:-absent}, android-sdk.versions dit $want." >&2
  fi
done

# 6. Profils de l'émulateur : <racine>/avd/<nom>.avd/config.ini, créés par
#    avdmanager. Le superviseur en copie un dans un dossier jetable à chaque
#    démarrage : ici, rien n'est jamais écrit par l'émulateur.
avd=$root/avd
declare -A profile_names=()
for spec in $EMU_PROFILES; do
  parse_profile "$spec"
  profile_names[$name]=1
  sysdir="image.sysdir.1=${image//;//}/"
  if grep -qxF "$sysdir" "$avd/$name.avd/config.ini" 2>/dev/null &&
    grep -qxF "hw.device.name=$device" "$avd/$name.avd/config.ini"; then
    say "Profil $name déjà en place ($image, $device)"
  else
    drift=1
    say "Profil $name : $image, $device"
    if [ "$dry" = 0 ]; then
      [ -n "$tmp" ] || tmp=$(mktemp -d "$root/.sync.XXXXXX")
      echo no | ANDROID_AVD_HOME="$tmp/avd" ANDROID_USER_HOME="$tmp/user" ANDROID_HOME="$sdk" \
        "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -f -n "$name" -k "$image" -d "$device" \
        -p "$tmp/avd/$name.avd" >/dev/null
      mkdir -p "$avd/$name.avd"
      cp "$tmp/avd/$name.avd/config.ini" "$avd/$name.avd/.config.ini.new"
      mv -f "$avd/$name.avd/.config.ini.new" "$avd/$name.avd/config.ini"
    fi
  fi
  # Options de démarrage lues par le superviseur (absentes : aucune).
  opt_file=$avd/$name.avd/emu-options
  if [ -z "$options" ]; then
    [ -e "$opt_file" ] || continue
    drift=1
    say "Profil $name : options retirées"
    run rm -f "$opt_file"
  elif [ "$(cat "$opt_file" 2>/dev/null)" != "${options%$'\n'}" ]; then
    drift=1
    say "Profil $name : options $(printf '%s' "$options" | paste -sd' ')"
    [ "$dry" = 1 ] && continue
    mkdir -p "$avd/$name.avd"
    printf '%s' "$options" >"$opt_file.new"
    mv -f "$opt_file.new" "$opt_file"
  fi
done
for d in "$avd"/*.avd; do
  [ -d "$d" ] || continue
  name=$(basename "$d" .avd)
  [ -n "${profile_names[$name]:-}" ] && continue
  drift=1
  say "Profil retiré : $name"
  run rm -rf "$d"
done

# 7. Bibliothèques du qemu headless absentes de l'image des agents : paquets
#    Debian extraits sans root (apt-get dans un dossier temporaire, signatures
#    vérifiées avec le trousseau Debian du conteneur).
qemu_bin=$sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless
libs=$root/emulator-libs
# Le bac à sable des agents a libX11 & co dans /usr/lib, pas l'image
# debian:trixie-slim du conteneur android-emu : toute bibliothèque résolue hors
# du SDK et de emulator-libs compte comme manquante, sauf la glibc et libgcc_s,
# présentes dans toute image Debian.
base_libs_re='^(linux-vdso\.so\.1|ld-linux-x86-64\.so\.2|lib(c|m|dl|pthread|rt|util|resolv)\.so\.[0-9]+|libgcc_s\.so\.1)$'
deps_missing() {
  local name arrow path rest
  # ldd en échec (fichier illisible ou pas ELF) : le fichier compte comme manquant.
  { LD_LIBRARY_PATH="$sdk/emulator/lib64:$sdk/emulator/lib64/qt/lib:$libs" ldd "$1" 2>&1 ||
    echo "$(basename "$1") => not found"; } |
    while read -r name arrow path rest; do
      [ "$arrow" = "=>" ] || continue
      if [ "$path" = not ]; then echo "$name" && continue; fi
      case "$path" in "$sdk"/* | "$libs"/*) continue ;; esac
      [[ "$name" =~ $base_libs_re ]] || echo "$name"
    done
}
missing_libs() {
  local l f
  {
    deps_missing "$qemu_bin"
    # Chargées par dlopen (ldd ne les voit pas) : présentes, ainsi que leurs dépendances.
    for l in ${EMULATOR_DLOPEN_LIBS:-}; do
      f=""
      for f in "$libs/$l" "$sdk/emulator/lib64/$l" ""; do [ -e "$f" ] && break; done
      if [ -n "$f" ]; then deps_missing "$f"; else echo "$l"; fi
    done
  } | sort -u
}
if [ ! -x "$qemu_bin" ]; then
  [ "$dry" = 1 ] || { echo "qemu headless introuvable : $qemu_bin" >&2; exit 1; }
elif [ -z "$(missing_libs)" ]; then
  say "Bibliothèques de l'émulateur complètes"
else
  drift=1
  say "Bibliothèques manquantes : $(missing_libs | paste -sd' ')"
  if [ "$dry" = 0 ]; then
    [ -n "$tmp" ] || tmp=$(mktemp -d "$root/.sync.XXXXXX")
    a=$tmp/apt
    mkdir -p "$a/etc/apt/apt.conf.d" "$a/etc/apt/preferences.d" "$a/etc/apt/sources.list.d" \
      "$a/state/lists/partial" "$a/cache/archives/partial" "$a/debs" "$a/root"
    k=/usr/share/keyrings/debian-archive-keyring.gpg
    printf 'deb [signed-by=%s] http://deb.debian.org/debian trixie main\ndeb [signed-by=%s] http://deb.debian.org/debian-security trixie-security main\n' \
      "$k" "$k" >"$a/etc/apt/sources.list"
    printf 'Dir::Etc "%s/etc/apt";\nDir::State "%s/state";\nDir::State::status "%s/state/status";\nDir::Cache "%s/cache";\nDebug::NoLocking "true";\nAPT::Sandbox::User "";\n' \
      "$a" "$a" "$a" "$a" >"$a/apt.conf"
    : >"$a/state/status"
    APT_CONFIG=$a/apt.conf apt-get -qq update
    # shellcheck disable=SC2086
    (cd "$a/debs" && APT_CONFIG=$a/apt.conf apt-get -qq download $EMULATOR_LIBS_DEBS)
    for deb in "$a"/debs/*.deb; do dpkg-deb -x "$deb" "$a/root"; done
    mkdir -p "$libs"
    cp -a "$a"/root/usr/lib/x86_64-linux-gnu/*.so* "$libs/"
    left=$(missing_libs)
    [ -z "$left" ] || { echo "bibliothèques toujours manquantes : $left" >&2; exit 1; }
  fi
fi

if [ "$dry" = 1 ]; then
  if [ "$drift" = 1 ] || [ "$bad" = 1 ]; then
    say "Écart avec android-sdk.versions (paquets et JDK inchangés)"
    exit 3
  fi
  say "Aucun écart"
  exit 0
fi
if [ "$bad" = 1 ]; then
  echo "Corriger android-sdk.versions (ou la révision installée), puis relancer." >&2
  echo "VERSIONS n'est pas mis à jour." >&2
  exit 1
fi
cp "$here/android-sdk.versions" "$root/VERSIONS"
say "OK : $(du -sh "$jdk/" "$sdk" "$avd" "$libs" | tr '\t\n' ' ')"
