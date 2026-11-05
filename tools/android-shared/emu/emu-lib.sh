# shellcheck shell=bash
# Fonctions communes à emu-supervisor (conteneur android-emu) et aux clients
# emu-start, emu-stop, emu-run (bac à sable des agents). DMC-114.
#
# L'émulateur tourne dans un conteneur à part, le seul à voir /dev/kvm. Les
# agents le pilotent par des fichiers dans un dossier de contrôle partagé
# (EMU_CTL), et lui parlent par le serveur adb de ce conteneur
# (tcp:android-emu:5037, réseau Docker interne, aucun port publié).
#
# Dossier de contrôle :
#   session/          existe = l'émulateur est pris (créé par mkdir : atomique)
#     request         demande du client : id, profile, agent, run, task, boot
#     lease           id + échéance (secondes depuis 1970) : sans renouvellement, arrêt
#     stop            id : le client demande l'arrêt
#     status          écrit par le superviseur : id, state, serial, boot_s, rss_mb, boot
#   results/<id>      compte rendu final d'une session (succès, échec, journal)
#   supervisor.alive  battement du superviseur (t=<date>), toutes les secondes
#   maintenance       sdk-sync en cours : aucun démarrage
#   metrics.csv       date,profil,secondes de démarrage,Mo de RAM du qemu,cold|snapshot
#   golden.<profil>   état du snapshot propre du profil (DMC-174), pour information

set -euo pipefail

: "${ANDROID_HOME:=/opt/android/sdk}"
# Définitions des AVD (config.ini), en lecture seule dans le volume SDK.
: "${EMU_AVD_DIR:=/opt/android/avd}"
: "${EMU_CTL:=/opt/android-emu}"
# Serveur adb du conteneur de l'émulateur, vu depuis les agents.
: "${EMU_ADB_HOST:=android-emu}"
: "${EMU_ADB_PORT:=5037}"
: "${EMU_CONSOLE_PORT:=5554}"
: "${EMU_BOOT_TIMEOUT:=300}"
# Chargement du snapshot propre (DMC-174) : au-delà, retour à froid, qui a
# de nouveau EMU_BOOT_TIMEOUT.
: "${EMU_SNAP_BOOT_TIMEOUT:=90}"
# Après le démarrage, délai pour que l'appareil réponde par le réseau (emu-start).
: "${EMU_CLIENT_WAIT:=60}"
# Durée de vie maximale d'une session, même si son bail est renouvelé.
: "${EMU_MAX_SECONDS:=1800}"

# shellcheck disable=SC2034 # utilisées par les scripts qui chargent ce fichier
EMU_SERIAL="emulator-$EMU_CONSOLE_PORT"
EMU_SESSION="$EMU_CTL/session"
EMU_RESULTS="$EMU_CTL/results"
# shellcheck disable=SC2034
EMU_MAINTENANCE="$EMU_CTL/maintenance"

emu_log() { printf '%s: %s\n' "${EMU_CMD:-emu}" "$*" >&2; }
emu_die() { emu_log "$2"; exit "$1"; }

emu_profiles() {
  local f
  for f in "$EMU_AVD_DIR"/*.avd/config.ini; do
    [ -f "$f" ] || continue
    f=${f%/config.ini}
    basename "$f" .avd
  done
}
emu_valid_profile() { [[ "$1" =~ ^[a-z0-9_-]{1,32}$ ]] && [ -f "$EMU_AVD_DIR/$1.avd/config.ini" ]; }
emu_valid_id() { [[ "$1" =~ ^[0-9a-f]{16}$ ]]; }

# Valeur de <clé>= dans un fichier ordinaire : jamais à travers un lien, au plus
# 4 Ko lus, et jamais bloqué plus de 2 s (un tube nommé glissé à sa place).
emu_kv() {
  [ -f "$1" ] && [ ! -L "$1" ] || return 0
  { timeout 2 head -c 4096 -- "$1" 2>/dev/null | sed -n "s/^$2=//p" | head -n 1; } || true
}

# Écrit l'entrée standard dans $1, d'un seul renommage : un lecteur ne voit
# jamais un fichier à moitié écrit, et un lien placé là est remplacé, pas suivi.
emu_write() {
  local tmp
  tmp=$(mktemp "$(dirname "$1")/.w.XXXXXX" 2>/dev/null) || return 1
  if cat >"$tmp" && mv -fT "$tmp" "$1" 2>/dev/null; then return 0; fi
  rm -f "$tmp"
  return 1
}

emu_session_ok() { [ -d "$EMU_SESSION" ] && [ ! -L "$EMU_SESSION" ]; }

# Le superviseur bat toutes les secondes, même pendant un arrêt (qui peut
# durer 40 s) ; muet depuis 60 s = arrêté.
emu_supervisor_alive() {
  local t
  t=$(emu_kv "$EMU_CTL/supervisor.alive" t)
  [[ "$t" =~ ^[0-9]+$ ]] && [ $((EPOCHSECONDS - t)) -le 60 ]
}

emu_maintenance_on() {
  local t
  [ -f "$EMU_MAINTENANCE" ] || return 1
  t=$(stat -c %Y "$EMU_MAINTENANCE" 2>/dev/null) || return 1
  # Une maintenance de plus d'une heure est un reste : ignorée.
  [ $((EPOCHSECONDS - t)) -le 3600 ]
}

# Propriétaire lisible de la session en cours.
emu_owner() {
  local r=$EMU_SESSION/request
  printf 'profil %s, agent %s, tâche %s' "$(emu_kv "$r" profile)" "$(emu_kv "$r" agent)" "$(emu_kv "$r" task)"
}

# Affiche le compte rendu d'une session et rend son code.
emu_report() {
  local f=$EMU_RESULTS/$1 code
  code=$(emu_kv "$f" code)
  emu_log "$(emu_kv "$f" message)"
  if [ -f "$f" ] && [ ! -L "$f" ] && timeout 2 head -c 16384 -- "$f" 2>/dev/null | grep -q '^--- journal ---$'; then
    emu_log "fin du journal de l'émulateur :"
    timeout 2 head -c 16384 -- "$f" 2>/dev/null | sed '1,/^--- journal ---$/d' >&2
  fi
  [[ "$code" =~ ^[0-9]+$ ]] || code=1
  return "$code"
}

# Demande l'arrêt de la session <id> et attend son compte rendu (<s> secondes).
# Codes : ceux du compte rendu ; 69 superviseur absent ; 3 pas de réponse.
emu_stop_session() {
  local id=$1 wait_s=$2 n
  if emu_session_ok && [ "$(emu_kv "$EMU_SESSION/request" id)" = "$id" ]; then
    echo "id=$id" | emu_write "$EMU_SESSION/stop" || true
  fi
  n=$((wait_s * 2))
  while [ "$n" -gt 0 ]; do
    [ -f "$EMU_RESULTS/$id" ] && { emu_report "$id"; return; }
    if ! emu_supervisor_alive; then
      emu_log "le superviseur de l'émulateur ne répond plus (conteneur android-emu arrêté ?)."
      return 69
    fi
    sleep 0.5
    n=$((n - 1))
  done
  emu_log "pas de compte rendu d'arrêt en ${wait_s} s."
  return 3
}
