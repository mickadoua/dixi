#!/usr/bin/env bash
# admin-install.sh <racine-du-volume> : installe ces scripts dans
# <racine>/admin (Sacha seul : le volume est en lecture seule pour les autres).
# Chaque fichier est remplacé d'un seul renommage : un script en cours
# d'exécution, comme le superviseur de l'émulateur, garde l'ancienne version
# jusqu'à sa relance (le superviseur se relance seul au repos).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=${1:?usage : admin-install.sh <racine-du-volume>}
dest=$root/admin
files=(admin-install.sh android-sdk.versions sdk-sync.sh gradle-seed.sh android-purge.sh
  README.md CLAUDE-android-consigne.md android-devices-compose.yaml
  android-purge-cron.sh emu/emu-lib.sh emu/emu-supervisor emu/emu-start emu/emu-stop emu/emu-run emu/emu-renew emu/tests/test-emu.sh)
# Modes posés ici, pas lus dans la copie de travail : les commits signés faits
# par l'API GitHub créent les fichiers sans le bit exécutable (DMC-299).
mode_of() { case "$1" in *.md | *.yaml | *.versions | emu/emu-lib.sh) echo 644 ;; *) echo 755 ;; esac; }
for f in "${files[@]}"; do
  mkdir -p "$(dirname "$dest/$f")"
  if cmp -s "$here/$f" "$dest/$f" 2>/dev/null; then
    chmod "$(mode_of "$f")" "$dest/$f"
    continue
  fi
  cp -p "$here/$f" "$dest/$f.new.$$"
  chmod "$(mode_of "$f")" "$dest/$f.new.$$"
  mv -fT "$dest/$f.new.$$" "$dest/$f"
  echo "installé : $f"
done
