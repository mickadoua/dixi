#!/usr/bin/env bash
# Cron mensuel du NAS (root) pour le ménage Android (DMC-114).
# Lance android-purge.sh (copie root posée à côté de ce script) dans un
# conteneur jetable : sans réseau, uid 1000, sans capacités, un seul dossier
# monté à la fois. Aucun code venu d'un workspace ne tourne sur l'hôte, et un
# lien glissé par un agent ne mène qu'à son propre dossier.
# Usage : android-purge-cron.sh [--dry-run]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
base=/mnt/fastPool/hermes/paperclip/instances/default
gradle_root=/mnt/fastPool/hermes/android/gradle-cache   # chemin réel posé le 03/11 (pas de dataset dédié)
image=debian:trixie-slim
dry=()
case "${1:-}" in
  "") ;;
  --dry-run) dry=(--dry-run) ;;
  *) echo "usage : android-purge-cron.sh [--dry-run]" >&2; exit 2 ;;
esac

# Le conteneur ne voit pas les processus de l'hôte : le contrôle se fait ici.
if pgrep -f 'org.gradle|GradleWrapperMain|GradleWorkerMain|KotlinCompileDaemon' >/dev/null; then
  echo "Un processus Gradle tourne : rien n'est supprimé, à relancer plus tard"
  exit 0
fi

purge() {
  local dir=$1
  shift
  echo "## $dir"
  docker run --rm --network none --user 1000:1000 --cap-drop ALL \
    --security-opt no-new-privileges --read-only --tmpfs /tmp \
    -v "$here/android-purge.sh:/purge.sh:ro" -v "$dir:/d" \
    "$image" bash /purge.sh "${dry[@]}" "$@" || echo "   échec sur $dir, on continue" >&2
}
for d in "$base"/workspaces/*/ "$base"/projects/*/; do
  d=${d%/}
  [ -d "$d" ] && [ ! -L "$d" ] || continue
  purge "$d" --workspaces /d --days 30
done
for d in "$gradle_root"/*/; do
  d=${d%/}
  [ -d "$d" ] && [ ! -L "$d" ] || continue
  purge "$d" --gradle-home /d --max-gb 5
done
