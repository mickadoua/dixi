#!/usr/bin/env bash
# Ménage mensuel de l'outillage Android partagé (DMC-114). Lancé par
# android-purge-cron.sh (cron de l'hôte NAS) dans un conteneur jetable, sans
# réseau, sous l'uid 1000, avec un seul dossier monté à la fois.
#  1. supprime les dossiers build/ des projets Gradle non touchés depuis N jours ;
#  2. plafonne chaque dossier Gradle (un par société). Gradle retire déjà seul
#     ses entrées inutilisées (dépendances et distributions : 30 jours, build-cache et
#     transforms : 7 jours). Au-delà de la limite, on vide d'abord ce qui se
#     reconstruit sans réseau (build-cache, transforms), puis les dépendances
#     téléchargées si c'est encore trop gros.
# Les anciennes versions du SDK sont retirées par sdk-sync.sh, pas ici.
# Si un processus Gradle tourne, le script ne touche à rien.
#
# Usage : android-purge.sh [--dry-run] [--days 30] [--max-gb 15] \
#           --workspaces <dossier> [--workspaces <dossier>...] \
#           { --gradle-home <dossier> | --gradle-root <dossier> }...
#   --gradle-root : chaque sous-dossier est un GRADLE_USER_HOME (un par société).
set -euo pipefail

dry=0 days=30 max_gb=15 gradle_homes=() workspaces=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --days) days=${2:-}; shift ;;
    --max-gb) max_gb=${2:-}; shift ;;
    --workspaces) workspaces+=("${2:-}"); shift ;;
    --gradle-home) gradle_homes+=("${2:-}"); shift ;;
    --gradle-root)
      [ -d "${2:-}" ] || { echo "dossier introuvable : ${2:-}" >&2; exit 2; }
      for h in "$2"/*/; do
        [ -d "$h" ] && [ ! -L "${h%/}" ] && gradle_homes+=("${h%/}")
      done
      shift ;;
    *) echo "option inconnue : $1" >&2; exit 2 ;;
  esac
  shift
done
usage="usage : android-purge.sh [--dry-run] [--days N] [--max-gb N] --workspaces <dir>... { --gradle-home <dir> | --gradle-root <dir> }..."
{ [ "${#workspaces[@]}" -gt 0 ] || [ "${#gradle_homes[@]}" -gt 0 ]; } || { echo "$usage" >&2; exit 2; }
[[ $days =~ ^[1-9][0-9]*$ ]] || { echo "--days : entier positif attendu" >&2; exit 2; }
[[ $max_gb =~ ^[1-9][0-9]*$ ]] || { echo "--max-gb : entier positif attendu" >&2; exit 2; }
for d in "${workspaces[@]}" "${gradle_homes[@]}"; do
  real=$(realpath -e "$d") || { echo "dossier introuvable : $d" >&2; exit 2; }
  [ "$real" != / ] || { echo "refus : / comme dossier" >&2; exit 2; }
done
cutoff=$(date -d "-$days days" +%FT%T) || exit 2

say() { printf '== %s\n' "$*"; }
remove() {
  # Jamais à travers un lien : le chemin doit être déjà canonique.
  if [ "$(realpath -e -- "$1" 2>/dev/null)" != "$1" ]; then
    echo "   gardé (chemin qui passe par un lien) : $1" >&2
    return
  fi
  if [ "$dry" = 1 ]; then echo "   (dry-run) rm -rf $1"; return; fi
  rm -rf "$1" || echo "   échec de suppression, ignoré : $1" >&2
}
size_kb() { [ $# -gt 0 ] || { echo 0; return; }; du -sck "$@" 2>/dev/null | tail -1 | cut -f1; }
human() { numfmt --to=iec --from-unit=1024 "$1"; }

if command -v pgrep >/dev/null && pgrep -f 'org.gradle|GradleWrapperMain|GradleWorkerMain|KotlinCompileDaemon' >/dev/null; then
  say "Un processus Gradle tourne : rien n'est supprimé, à relancer plus tard"
  exit 0
fi

before_ws=$(size_kb "${workspaces[@]}")
before_gr=$(size_kb "${gradle_homes[@]}")

# 1. build/ des projets Gradle : seulement à côté d'un build.gradle(.kts), et
#    sans rien de modifié depuis $cutoff. En cas d'erreur de find, le dossier
#    est gardé. Pas de git ici : dans un dépôt écrit par un agent, git peut
#    exécuter du code (core.fsmonitor). Un build/ suivi par git se restaure par
#    git checkout.
[ "${#workspaces[@]}" -gt 0 ] && say "Dossiers build/ sans modification depuis le $cutoff"
[ "${#workspaces[@]}" -gt 0 ] && while IFS= read -r -d '' dir; do
  parent=$(dirname "$dir")
  [ -f "$parent/build.gradle.kts" ] || [ -f "$parent/build.gradle" ] || continue
  recent=$(find "$dir" -newermt "$cutoff" -print -quit) \
    || { echo "   gardé (erreur de lecture) : $dir" >&2; continue; }
  [ -z "$recent" ] || continue
  echo "   $(du -sh "$dir" | cut -f1)  $dir"
  remove "$dir"
done < <(find -H "${workspaces[@]}" -xdev -type d \( -name node_modules -o -name .git -o -name .zfs \) -prune \
           -o -type d -name build -print0 -prune)

# 2. Plafond de chaque dossier Gradle
limit_kb=$((max_gb * 1024 * 1024))
for gradle_home in "${gradle_homes[@]}"; do
  over() { [ "$(size_kb "$gradle_home")" -gt "$limit_kb" ]; }
  say "Cache Gradle $gradle_home : $(human "$(size_kb "$gradle_home")") pour une limite de ${max_gb}G"
  over || continue
  for d in "$gradle_home"/caches/build-cache-* "$gradle_home"/caches/transforms-* "$gradle_home"/caches/*/transforms; do
    [ -e "$d" ] && remove "$d"
  done
  if [ "$dry" = 1 ]; then
    echo "   (dry-run) si toujours au-dessus : rm -rf $gradle_home/caches/modules-2"
  elif over; then
    say "Toujours au-dessus : on vide les dépendances téléchargées"
    remove "$gradle_home/caches/modules-2"
  fi
done

say "Workspaces : $(human "$before_ws") -> $(human "$(size_kb "${workspaces[@]}")")"
say "Caches Gradle : $(human "$before_gr") -> $(human "$(size_kb "${gradle_homes[@]}")")"
