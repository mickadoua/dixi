#!/usr/bin/env bash
# gradle-seed.sh <racine-du-volume> : publie le cache de dépendances Gradle
# commun, en lecture seule pour tous les agents (GRADLE_RO_DEP_CACHE).
# Source : le GRADLE_USER_HOME privé de Sacha (que personne d'autre n'écrit),
# rempli par ses builds des templates. Réservé à Sacha.
#
#   <racine>/gradle-ro-cache   lien vers gradle-ro-cache-<date>
#     modules-2/               copie de caches/modules-2, sans verrous ni gc.properties
# La bascule se fait d'un seul renommage ; la version précédente reste jusqu'à
# la publication suivante, pour les builds en cours.
set -euo pipefail
root=${1:?usage : gradle-seed.sh <racine-du-volume>}
src=${GRADLE_USER_HOME:?GRADLE_USER_HOME non défini}/caches/modules-2
[ -d "$src/files-2.1" ] || { echo "rien à publier : $src/files-2.1 absent" >&2; exit 1; }
new=$root/gradle-ro-cache-$(date -u +%Y%m%d%H%M%S)
tmp=$root/.gradle-ro-cache.tmp.$$
lnk=$root/.gradle-ro-cache.lnk.$$
trap 'rm -rf "$tmp" "$lnk"' EXIT
mkdir -p "$tmp/modules-2"
cp -a "$src/." "$tmp/modules-2/"
# Gradle refuse un cache en lecture seule qui contient ses verrous.
find "$tmp/modules-2" \( -name '*.lock' -o -name gc.properties \) -delete
chmod -R a+rX,go-w "$tmp"
mv "$tmp" "$new"
old=$(readlink "$root/gradle-ro-cache" 2>/dev/null || true)
ln -s "$(basename "$new")" "$lnk"
mv -fT "$lnk" "$root/gradle-ro-cache"
for d in "$root"/gradle-ro-cache-*; do
  case "$(basename "$d")" in "$(basename "$new")" | "$old") ;; *) rm -rf "$d" ;; esac
done
echo "publié : $(basename "$new") ($(du -sh "$new" | cut -f1)) ; précédent gardé : ${old:-aucun}"
