#!/usr/bin/env bash
# Tests du superviseur (conteneur android-emu) et des clients emu-start /
# emu-stop / emu-run / emu-renew, avec un faux émulateur et un faux adb (sans KVM).
# Le superviseur tourne ici en arrière-plan, sur un dossier de contrôle temporaire.
# Les scripts sont copiés dans le dossier temporaire et testés là : rien n'est
# modifié à côté de ce fichier. Usage : tests/test-emu.sh <dossier-temporaire>
# check évalue ses conditions plus tard : guillemets simples voulus.
# shellcheck disable=SC2016,SC2034,SC2064,SC2012
set -uo pipefail
src=$(cd "$(dirname "$0")/.." && pwd)
W=${1:?dossier temporaire}
rm -rf "$W"; mkdir -p "$W/emu"
cp "$src"/emu-lib.sh "$src"/emu-supervisor "$src"/emu-start "$src"/emu-stop "$src"/emu-run "$src"/emu-renew "$W/emu/"
# Exécutables même si la copie de travail ne garde pas les modes (DMC-299).
chmod 755 "$W"/emu/emu-supervisor "$W"/emu/emu-start "$W"/emu/emu-stop "$W"/emu/emu-run "$W"/emu/emu-renew
here=$W/emu
F="$W/sdk"
mkdir -p "$F/emulator/qemu/linux-x86_64" "$F/platform-tools" "$W/avd/phone.avd" "$W/avd/tv.avd" "$W/ctl" "$W/data"
touch "$W/avd/phone.avd/config.ini" "$W/avd/tv.avd/config.ini"

# Faux qemu : tourne jusqu'à ce qu'on le tue.
cat >"$F/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless" <<'EOF'
#!/usr/bin/env bash
date +%s > "$FAKE_DIR/qemu.start"
echo $$ > "$FAKE_DIR/qemu.pid"
[ "${FAKE_IGNORE_TERM:-0}" = 1 ] && trap '' TERM
while :; do sleep 0.2; done
EOF
# Faux lanceur : échoue si FAKE_FAIL=1, sinon lance le faux qemu et l'attend.
# Snapshot (DMC-174) : -wipe-data crée userdata-qemu.img ; -force-snapshot-load
# échoue si le snapshot manque (ou <load-fail>) ; -read-only écrit sa copie
# dans TMPDIR, et dans l'AVD si <write-golden> (session qui fuit) ; avec
# <snap-hang>, un chargement ne finit jamais de démarrer (repère ro-boot).
cat >"$F/emulator/emulator" <<'EOF'
#!/usr/bin/env bash
echo "$@" > "$FAKE_DIR/emulator.args"
echo "$@" >> "$FAKE_DIR/emulator.all"
env > "$FAKE_DIR/emulator.env"
if [ -e "$FAKE_DIR/fail" ]; then echo "ERROR | x86_64 emulation currently requires hardware acceleration!"; exit 1; fi
avd="" snap="" force=0 ro=0 wipe=0
while [ $# -gt 0 ]; do
  case "$1" in -avd) avd=$2; shift ;; -snapshot) snap=$2; shift ;; -force-snapshot-load) force=1 ;; -read-only) ro=1 ;; -wipe-data) wipe=1 ;; esac
  shift
done
d=$ANDROID_AVD_HOME/$avd.avd
[ "$wipe" = 0 ] || { echo data > "$d/userdata-qemu.img"; echo q > "$d/userdata-qemu.img.qcow2"; }
if [ "$force" = 1 ] && { [ ! -d "$d/snapshots/$snap" ] || [ -e "$FAKE_DIR/load-fail" ]; }; then
  echo "ERROR | Snapshot '$snap' can not be loaded"; exit 1
fi
if [ "$ro" = 1 ]; then touch "$FAKE_DIR/ro-boot"; else rm -f "$FAKE_DIR/ro-boot"; fi
if [ "$ro" = 1 ]; then
  echo overlay > "$TMPDIR/userdata-qemu.img.qcow2"
  [ ! -e "$FAKE_DIR/write-golden" ] || echo dirty >> "$d/userdata-qemu.img"
  # Comme le vrai émulateur (05/11) : écritures dans le .qcow2 de l'AVD malgré -read-only.
  [ ! -e "$FAKE_DIR/write-qcow2" ] || echo session >> "$d/userdata-qemu.img.qcow2"
  # Description du snapshot réécrite au chargement.
  [ ! -e "$FAKE_DIR/touch-pb" ] || echo load >> "$d/snapshots/$snap/snapshot.pb"
fi
"$(dirname "$0")/qemu/linux-x86_64/qemu-system-x86_64-headless" &
wait $!
EOF
# Faux adb : serveur, getprop (démarré <boot-s> s après le qemu, 1 par défaut ;
# utilisateur 0 déverrouillé <ce-s> s plus tard, 0 par défaut), date (en retard
# de <clock-lag> s) et su 0 date (noté dans clock.set), emu kill. Les
# comportements se règlent par des fichiers (le superviseur tourne déjà).
cat >"$F/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DIR/adb.calls"
serial=""
while [ $# -gt 0 ]; do case "$1" in -s) serial=$2; shift 2;; -P) shift 2;; -a) shift;; *) break;; esac; done
case "$1" in
  nodaemon) env > "$FAKE_DIR/adb.env"; exec sleep 100000 ;;
  shell)
    # Seul le serveur du conteneur émulateur (ou l'adb local du superviseur,
    # sans ADB_SERVER_SOCKET) connaît emulator-5554 ; vu par le réseau, il
    # reste caché jusqu'à <client-hidden-until>.
    [ -z "$serial" ] || [ "$serial" = emulator-5554 ] || { echo "adb: device '$serial' not found" >&2; exit 1; }
    case "${ADB_SERVER_SOCKET:-}" in
      "") ;;
      tcp:127.0.0.1:5639)
        if [ -e "$FAKE_DIR/client-hidden-until" ] && [ "$(date +%s)" -lt "$(cat "$FAKE_DIR/client-hidden-until")" ]; then
          echo "adb: device 'emulator-5554' not found" >&2; exit 1
        fi ;;
      *) echo "adb: device 'emulator-5554' not found" >&2; exit 1 ;;
    esac
    p=$(cat "$FAKE_DIR/qemu.pid" 2>/dev/null); t=$(cat "$FAKE_DIR/qemu.start" 2>/dev/null)
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null && [ ! -e "$FAKE_DIR/never-boot" ] &&
      { [ ! -e "$FAKE_DIR/snap-hang" ] || [ ! -e "$FAKE_DIR/ro-boot" ]; } &&
      [ $(( $(date +%s) - t )) -ge "$(cat "$FAKE_DIR/boot-s" 2>/dev/null || echo 1)" ]; then
      if [ "$2 $3" = "getprop sys.user.0.ce_available" ]; then
        [ $(( $(date +%s) - t )) -ge $(( $(cat "$FAKE_DIR/boot-s" 2>/dev/null || echo 1) + $(cat "$FAKE_DIR/ce-s" 2>/dev/null || echo 0) )) ] &&
          echo true || echo false
      elif [ "$2 $3" = "date +%s" ]; then
        echo $(( $(date +%s) - $(cat "$FAKE_DIR/clock-lag" 2>/dev/null || echo 0) ))
      elif [ "$2 $3 $4 $5" = "su 0 date -u" ]; then
        # Horloge réglée (DMC-299) : refusé si <su-fail>.
        [ ! -e "$FAKE_DIR/su-fail" ] || { echo "su: not found" >&2; exit 127; }
        echo "$6 $(date +%s)" >> "$FAKE_DIR/clock.set"
      else echo 1; fi
    else echo ""; fi ;;
  emu)
    if [ "$2 $3 $4" = "avd snapshot save" ]; then
      # Prise du snapshot dans l'AVD du dernier lancement.
      [ ! -e "$FAKE_DIR/save-fail" ] || { echo "KO: snapshot save failed"; exit 0; }
      [ ! -e "$FAKE_DIR/save-s" ] || sleep "$(cat "$FAKE_DIR/save-s")"
      home=$(sed -n 's/^ANDROID_AVD_HOME=//p' "$FAKE_DIR/emulator.env")
      avd=$(sed -n 's/.*-avd \([^ ]*\).*/\1/p' "$FAKE_DIR/emulator.args")
      mkdir -p "$home/$avd.avd/snapshots/$5" && echo ram > "$home/$avd.avd/snapshots/$5/ram.bin" && echo pb > "$home/$avd.avd/snapshots/$5/snapshot.pb" && echo OK
    else
      [ -e "$FAKE_DIR/ignore-kill" ] || kill "$(cat "$FAKE_DIR/qemu.pid")" 2>/dev/null
    fi ;;
esac
EOF
chmod +x "$F/emulator/emulator" "$F/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless" "$F/platform-tools/adb"

export ANDROID_HOME="$F" EMU_AVD_DIR="$W/avd" EMU_CTL="$W/ctl" EMU_DATA="$W/data" EMU_LIBS="$W/libs" \
  EMU_ADB_HOST=127.0.0.1 EMU_ADB_PORT=5639 FAKE_DIR="$W" PAPERCLIP_AGENT_ID=agent-A PAPERCLIP_TASK_ID=task-1 \
  ADB_SERVER_SOCKET=tcp:adb:5037
# Charge de l'hôte simulée (DMC-285) : les tests ne dépendent pas de la charge réelle.
export EMU_LOADAVG="$W/loadavg"
echo "0.50 0.50 0.50 1/100 1" >"$W/loadavg"
# shellcheck source=emu-lib.sh
. "$here/emu-lib.sh"
set +e

pass=0; failn=0
ok() { echo "ok   - $1"; pass=$((pass + 1)); }
ko() { echo "FAIL - $1"; failn=$((failn + 1)); }
check() { if eval "$2"; then ok "$1"; else ko "$1"; fi; }

sup=""
# SUP_TRACE=1 : trace du superviseur (bash -x) dans sup.trace, à part : sur
# la sortie d'erreur, elle entrerait dans l'empreinte du snapshot (2>&1).
start_sup() {
  local pre=()
  [ "${SUP_TRACE:-0}" = 0 ] || pre=(env BASH_XTRACEFD=7 bash -x)
  exec 7>>"$W/sup.trace"
  EMU_CGROUP="$W/cg" EMU_SKIP_KVM_CHECK=1 EMU_POLL=0.2 EMU_BOOT_TIMEOUT=${SUP_BOOT:-16} EMU_STOP_GRACE=2 EMU_ABANDON_S=2 \
    EMU_MAX_SECONDS=${SUP_MAX:-1800} EMU_SNAPSHOT_CONF="$W/snap.conf" EMU_GOLDEN_IDLE_S=${SUP_GIDLE:-3} EMU_GOLDEN_CHECK_S=${SUP_GCHECK:-1} \
    EMU_GOLDEN_SETTLE_S=1 EMU_GOLDEN_SAVE_S=4 EMU_GOLDEN_RETRY_S=${SUP_GRETRY:-3} EMU_GOLDEN_MIN_FREE_MB=1 EMU_GOLDEN_BOOT_TIMEOUT=${SUP_GBOOT:-20} EMU_SNAP_BOOT_TIMEOUT=6 \
    "${pre[@]}" "$here/emu-supervisor" >>"$W/supervisor.log" 2>&1 &
  sup=$!
  exec 7>&-
  local _
  for _ in $(seq 50); do emu_supervisor_alive && return 0; sleep 0.1; done
  return 1
}
stop_sup() { [ -z "$sup" ] || { kill -TERM "$sup" 2>/dev/null; wait "$sup" 2>/dev/null; }; sup=""; }
fakes() {
  local d p
  for d in /proc/[0-9]*; do
    p=$(tr '\0' ' ' 2>/dev/null <"$d/cmdline") || continue
    case "$p" in *"$W/sdk/"*) [ "${d#/proc/}" != "$$" ] && printf '%s\n' "${d#/proc/}" ;; esac
  done
}
# Plus aucun processus de l'émulateur ni serveur adb, session libérée, données supprimées.
clean() { [ -z "$(fakes)" ] && [ ! -e "$W/ctl/session" ] && [ ! -e "$W/data/run" ]; }
wait_clean() { local _; for _ in $(seq 50); do clean && return 0; sleep 0.2; done; return 1; }
wait_ready() { local _; for _ in $(seq 75); do [ "$(emu_kv "$W/ctl/session/status" state)" = ready ] && return 0; sleep 0.2; done; return 1; }
# PID dont la ligne de commande est exactement $1.
procs() { local d; for d in /proc/[0-9]*; do [ "$(tr "\0" " " 2>/dev/null <"$d/cmdline")" = "$1 " ] && printf "%s\n" "${d#/proc/}"; done; }
leftovers() { for p in $(fakes); do kill -9 "$p" 2>/dev/null; done; }
trap 'stop_sup; leftovers' EXIT
reset() {
  wait_clean >/dev/null
  rm -f "$W/fail" "$W/client-hidden-until" "$W/never-boot" "$W/ignore-kill" "$W/boot-s" "$W/qemu.pid" "$W/qemu.start" \
    "$W/load-fail" "$W/write-golden" "$W/touch-pb" "$W/write-qcow2" "$W/save-fail" "$W/save-s" "$W/snap-hang" "$W/ro-boot" "$W/ce-s" \
    "$W/clock-lag" "$W/clock.set" "$W/su-fail"
}
# Attend que la commande de test tourne (emu-start a rendu la main).
wait_cmd() { local _; for _ in $(seq 100); do [ -n "$(procs "$1")" ] && return 0; sleep 0.2; done; return 1; }
last_result() { ls -t "$W/ctl/results" | head -n 1; }

# Faux cgroup du conteneur android-emu : plafond 5 Go, aucune tuerie OOM.
mkdir -p "$W/cg"
echo "oom_kill 0" >"$W/cg/memory.events"
echo 5368709120 >"$W/cg/memory.max"
start_sup
check "superviseur : battement" 'emu_supervisor_alive'

# 1. Cas nominal.
reset
out=$("$here/emu-run" phone -- bash -c 'echo "$ADB_SERVER_SOCKET $ANDROID_SERIAL"' 2>"$W/err1"); rc=$?
check "nominal : code 0" '[ $rc = 0 ]'
check "nominal : la commande vise le serveur adb du conteneur émulateur" '[ "$out" = "tcp:127.0.0.1:5639 emulator-5554" ]'
check "nominal : options headless, à froid, sans snapshot" 'grep -q -- "-no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save -wipe-data" "$W/emulator.args"'
check "nominal : serveur adb joignable du réseau (-a)" 'grep -q -- "^-a -P 5639 nodaemon server" "$W/adb.calls"'
check "nominal : l'émulateur ne voit pas le serveur adb partagé" '! grep -q "^ADB_SERVER_SOCKET=" "$W/emulator.env"'
check "nominal : temps et RAM affichés" 'grep -q "phone prêt en" "$W/err1"'
check "nominal : métrique enregistrée" 'grep -q ",phone," "$W/ctl/metrics.csv"'
check "nominal : compte rendu « stopped », code 0" '[ "$(emu_kv "$W/ctl/results/$(last_result)" result)" = stopped ] && [ "$(emu_kv "$W/ctl/results/$(last_result)" code)" = 0 ]'
check "nominal : adb emu kill utilisé" 'grep -q "emu kill" "$W/adb.calls"'
check "nominal : tout arrêté et nettoyé" wait_clean

# 2. La commande échoue : son code est rendu, l'émulateur est arrêté.
reset
"$here/emu-run" tv -- bash -c 'exit 7' 2>/dev/null; rc=$?
check "échec du test : code 7 rendu" '[ $rc = 7 ]'
check "échec du test : tout arrêté" wait_clean

# 3. Un seul à la fois : le second est refusé (75), un autre agent ne peut pas l'arrêter.
reset
"$here/emu-run" phone -- sleep 4 2>/dev/null &
first=$!
wait_ready
"$here/emu-start" tv 2>"$W/err3"; rc=$?
check "un seul : second démarrage refusé (75)" '[ $rc = 75 ]'
check "un seul : message avec le propriétaire" 'grep -q "agent agent-A, tâche task-1" "$W/err3"'
PAPERCLIP_AGENT_ID=agent-B "$here/emu-stop" 2>/dev/null; rc=$?
check "un seul : un autre agent ne peut pas l'arrêter (75)" '[ $rc = 75 ]'
check "un seul : le premier tourne encore" '[ "$(emu_kv "$W/ctl/session/status" state)" = ready ]'
wait "$first"; rc=$?
check "un seul : premier terminé proprement" '[ $rc = 0 ] && wait_clean'

# 4. --wait-lock : le second attend la fin du premier puis démarre.
reset
"$here/emu-run" phone -- sleep 2 2>/dev/null &
first=$!
wait_ready
"$here/emu-run" tv --wait-lock 30 -- true 2>/dev/null; rc=$?
wait "$first"
check "wait-lock : le second passe après le premier" '[ $rc = 0 ] && wait_clean'

# 5. L'émulateur échoue au démarrage : code 1, journal montré, tout nettoyé, puis ça repart.
reset
touch "$W/fail"
"$here/emu-run" phone -- true 2>"$W/err5"; rc=$?
check "démarrage en échec : code 1" '[ $rc = 1 ]'
check "démarrage en échec : journal montré" 'grep -q "hardware acceleration" "$W/err5"'
check "démarrage en échec : tout nettoyé" wait_clean
rm -f "$W/fail"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "démarrage en échec : session libérée" '[ $rc = 0 ]'

# 6. Android ne finit pas de démarrer : délai du superviseur dépassé, code 1.
reset
touch "$W/never-boot"
"$here/emu-run" phone -- true 2>"$W/err6"; rc=$?
check "boot trop long : code 1, message" '[ $rc = 1 ] && grep -q "pas fini de démarrer" "$W/err6"'
check "boot trop long : tout arrêté" wait_clean

# 7. « adb emu kill » ignoré, qemu qui ignore TERM : KILL sur le groupe.
reset
touch "$W/ignore-kill"
FAKE_IGNORE_TERM=1 "$here/emu-run" tv -- true 2>/dev/null; rc=$?
check "arrêt forcé : code 0 et tout arrêté" '[ $rc = 0 ] && wait_clean'

# 8. Bail : emu-start sans emu-stop, le superviseur arrête seul à l'échéance.
reset
"$here/emu-start" phone --lease 10 >/dev/null 2>&1; rc=$?
check "bail : démarrage" '[ $rc = 0 ]'
printf 'id=%s\nuntil=%s\n' "$(emu_kv "$W/ctl/session/request" id)" $((EPOCHSECONDS + 1)) | emu_write "$W/ctl/session/lease"
check "bail échu : arrêté et nettoyé" wait_clean
check "bail échu : compte rendu « expired »" '[ "$(emu_kv "$W/ctl/results/$(last_result)" result)" = expired ]'

# 9. emu-run renouvelle le bail pendant une commande plus longue que lui.
reset
EMU_RUN_LEASE=10 EMU_RUN_RENEW=1 "$here/emu-run" phone -- bash -c 'sleep 1; printf "until=%s\n" $(( $(date +%s) + 1 )) > "$EMU_CTL/session/lease"; sleep 4' 2>/dev/null; rc=$?
check "bail renouvelé : la commande va au bout (code 0)" '[ $rc = 0 ] && [ "$(emu_kv "$W/ctl/results/$(last_result)" result)" = stopped ]'

# 10. emu-run interrompu (TERM) : la commande et l'émulateur sont arrêtés.
reset
"$here/emu-run" phone -- bash -c 'sleep 300 & wait' 2>/dev/null &
r=$!
wait_cmd "sleep 300"
kill -TERM "$r"; wait "$r"; rc=$?
check "TERM : code 143" '[ $rc = 143 ]'
check "TERM : plus aucun sleep 300 (petits-enfants)" '[ -z "$(procs "sleep 300")" ]'
check "TERM : émulateur arrêté et nettoyé" wait_clean

# 11. emu-run tué net (KILL) : bail échu, arrêt par le superviseur.
reset
EMU_RUN_LEASE=10 EMU_RUN_RENEW=1 "$here/emu-run" phone -- sleep 300 2>/dev/null &
r=$!
wait_cmd "sleep 300"
kill -KILL "$r"; wait "$r" 2>/dev/null
for p in $(procs "sleep 300"); do kill -KILL "$p" 2>/dev/null; done
printf 'id=%s\nuntil=%s\n' "$(emu_kv "$W/ctl/session/request" id)" $((EPOCHSECONDS + 1)) | emu_write "$W/ctl/session/lease"
check "KILL : le superviseur arrête l'émulateur seul" wait_clean

# 12. L'entrée standard arrive à la commande.
reset
out=$(echo bonjour | "$here/emu-run" phone -- cat 2>/dev/null)
check "stdin transmis" '[ "$out" = bonjour ]'

# 13. Demandes invalides ou hostiles écrites à la main.
reset
mkdir "$W/ctl/session"
printf 'id=0123456789abcdef\nprofile=../../etc\n' >"$W/ctl/session/request"
sleep 1
check "profil invalide : refusé, compte rendu code 2" '[ "$(emu_kv "$W/ctl/results/0123456789abcdef" code)" = 2 ] && wait_clean'
mkdir "$W/victim"; ln -s "$W/victim" "$W/ctl/session"
sleep 1
check "session en lien symbolique : retirée, cible intacte" '[ ! -L "$W/ctl/session" ] && [ -d "$W/victim" ] && [ -z "$(ls -A "$W/victim")" ]'
mkdir "$W/ctl/session"
sleep 3
check "session sans demande : retirée après le délai" '[ ! -e "$W/ctl/session" ]'
"$here/emu-start" phone --lease 10 >/dev/null 2>&1
"$here/emu-start" "phone; rm -rf /" 2>/dev/null; rc=$?
check "client : profil invalide refusé (2)" '[ $rc = 2 ]'
"$here/emu-stop" 2>/dev/null; rc=$?
check "emu-stop du propriétaire : 0" '[ $rc = 0 ] && wait_clean'
"$here/emu-stop" 2>"$W/err13"; rc=$?
check "emu-stop sans émulateur : 0" '[ $rc = 0 ] && grep -q "aucun émulateur" "$W/err13"'

# 14. Maintenance du SDK : aucun démarrage.
reset
touch "$W/ctl/maintenance"
"$here/emu-start" phone 2>"$W/err14"; rc=$?
rm -f "$W/ctl/maintenance"
check "maintenance : refus (75)" '[ $rc = 75 ] && grep -q "mise à jour du SDK" "$W/err14" && wait_clean'

# 15. emu-stop --id d'une session terminée ne touche pas à celle d'un autre.
reset
PAPERCLIP_AGENT_ID=agent-B "$here/emu-start" tv --lease 30 >/dev/null 2>&1
"$here/emu-stop" --id 0123456789abcdef 2>/dev/null; rc=$?
check "--id d'une autre session : rien arrêté" '[ $rc = 0 ] && [ "$(emu_kv "$W/ctl/session/status" state)" = ready ]'
PAPERCLIP_AGENT_ID=agent-B "$here/emu-stop" 2>/dev/null
wait_clean

# 16. Superviseur arrêté : refus immédiat (69) ; arrêt du conteneur en pleine session.
reset
"$here/emu-start" phone --lease 30 >/dev/null 2>&1
stop_sup
check "arrêt du conteneur : émulateur arrêté, compte rendu « aborted »" 'wait_clean && [ "$(emu_kv "$W/ctl/results/$(last_result)" result)" = aborted ]'
"$here/emu-start" phone 2>"$W/err16"; rc=$?
check "superviseur absent : 69" '[ $rc = 69 ] && grep -q "ne répond pas" "$W/err16" && [ ! -e "$W/ctl/session" ]'

# 17. Redémarrage du superviseur avec une session restée sur le disque.
mkdir "$W/ctl/session"; printf 'id=fedcba9876543210\nprofile=phone\n' >"$W/ctl/session/request"; echo "state=ready" >"$W/ctl/session/status"
start_sup
check "redémarrage : session en cours abandonnée, compte rendu « aborted »" '[ ! -e "$W/ctl/session" ] && [ "$(emu_kv "$W/ctl/results/fedcba9876543210" result)" = aborted ]'

# 18. Nouvelle version du superviseur : relancé au repos, toujours fonctionnel.
cp "$here/emu-supervisor" "$W/sup.bak"
cp "$here/emu-supervisor" "$here/.sup.new" && touch -d '+1 min' "$here/.sup.new" && mv -f "$here/.sup.new" "$here/emu-supervisor"
sleep 1
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "nouvelle version : relance et session OK" '[ $rc = 0 ] && grep -q "nouvelle version" "$W/supervisor.log" && kill -0 "$sup"'

# 19. Démarrage plus long que le bail d'emu-run : le bail est prolongé pendant le démarrage.
reset
echo 12 >"$W/boot-s"
EMU_RUN_LEASE=10 "$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "démarrage lent (12 s, bail 10 s) : code 0" '[ $rc = 0 ]'

# 20. Rien ne survit à emu-run (sleep du renouvellement compris).
reset
EMU_RUN_RENEW=7 "$here/emu-run" phone -- sleep 1 2>/dev/null
check "aucun sleep de renouvellement restant" '[ -z "$(procs "sleep 7")" ]'

# 21. Contenu hostile dans le dossier de contrôle : le superviseur survit.
reset
mkdir "$W/ctl/session"; touch "$W/ctl/session/x"; chmod 500 "$W/ctl/session"
sleep 3
check "session sans droits : retirée, superviseur vivant" '[ ! -e "$W/ctl/session" ] && kill -0 "$sup"'
rm -f "$W/ctl/metrics.csv"; mkdir "$W/ctl/metrics.csv"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "metrics.csv remplacé par un dossier : session OK, superviseur vivant" '[ $rc = 0 ] && kill -0 "$sup"'
rmdir "$W/ctl/metrics.csv"
reset
id=$("$here/emu-start" phone --lease 20 2>/dev/null)
rm -f "$W/ctl/session/lease"; mkfifo "$W/ctl/session/lease"
sleep 4
check "tube nommé à la place du bail : le superviseur bat toujours" '[ $((EPOCHSECONDS - $(emu_kv "$W/ctl/supervisor.alive" t))) -le 2 ]'
echo "id=0000000000000000" | emu_write "$W/ctl/session/stop"
sleep 1
check "demande d'arrêt d'une autre session : ignorée" '[ "$(emu_kv "$W/ctl/session/status" state)" = ready ]'
"$here/emu-stop" --id "$id" 2>/dev/null; rc=$?
check "emu-stop --id : 0" '[ $rc = 0 ] && wait_clean'
mkdir -p "$W/victim2"; mv "$W/ctl/results" "$W/results.bak"; ln -s "$W/victim2" "$W/ctl/results"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "results en lien : rien écrit dans la cible, session OK" '[ $rc = 0 ] && [ -z "$(ls -A "$W/victim2")" ] && [ -d "$W/ctl/results" ] && [ ! -L "$W/ctl/results" ]'

# 22. Relance du superviseur avec une demande pas encore prise en charge : traitée.
reset
stop_sup
mkdir "$W/ctl/session"
printf 'id=%s\nuntil=%s\n' 1111222233334444 $((EPOCHSECONDS + 30)) >"$W/ctl/session/lease"
printf 'id=1111222233334444\nprofile=tv\nagent=agent-A\n' >"$W/ctl/session/request"
start_sup
wait_ready
check "demande en attente pendant une relance : démarrée" '[ "$(emu_kv "$W/ctl/session/status" id)" = 1111222233334444 ]'
"$here/emu-stop" --id 1111222233334444 2>/dev/null
check "puis arrêtée proprement" wait_clean


# 23. emu-run interrompu pendant le démarrage : emu-start rend l'émulateur.
reset
echo 4 >"$W/boot-s"
"$here/emu-run" phone -- sleep 300 2>/dev/null &
r=$!
for _ in $(seq 50); do [ "$(emu_kv "$W/ctl/session/status" state)" = booting ] && break; sleep 0.2; done
kill -TERM "$r"; wait "$r"; rc=$?
check "TERM pendant le démarrage : code 143" '[ $rc = 143 ]'
check "TERM pendant le démarrage : émulateur arrêté, rien lancé" '[ -z "$(procs "sleep 300")" ] && wait_clean'


# 24. INT pendant le démarrage : code 130, aucun fichier temporaire laissé.
reset
echo 4 >"$W/boot-s"
# Une commande lancée avec & par un script ignore INT : env le rétablit.
tmp_count() { local f n=0; for f in "${TMPDIR:-/tmp}"/emu-run.*; do [ -e "$f" ] && n=$((n + 1)); done; echo "$n"; }
before=$(tmp_count)
env --default-signal=INT "$here/emu-run" phone -- sleep 300 2>/dev/null &
r=$!
for _ in $(seq 50); do [ "$(emu_kv "$W/ctl/session/status" state)" = booting ] && break; sleep 0.2; done
kill -INT "$r"; wait "$r"; rc=$?
check "INT pendant le démarrage : code 130" '[ $rc = 130 ]'
check "INT pendant le démarrage : rien laissé" 'wait_clean && [ "$(tmp_count)" = "$before" ]'

# 25. Tout le groupe d'emu-run tué net (KILL) : le renouvellement, dans son
#     propre groupe, arrête la commande de test ; l'émulateur part au bail.
reset
EMU_RUN_LEASE=10 EMU_RUN_RENEW=1 setsid "$here/emu-run" phone -- sleep 300 2>/dev/null &
r=$!
wait_cmd "sleep 300"
kill -KILL -- "-$r"; wait "$r" 2>/dev/null
for _ in $(seq 30); do [ -z "$(procs "sleep 300")" ] && break; sleep 0.2; done
check "KILL du groupe : la commande de test ne survit pas" '[ -z "$(procs "sleep 300")" ]'
printf 'id=%s\nuntil=%s\n' "$(emu_kv "$W/ctl/session/request" id)" $((EPOCHSECONDS + 1)) | emu_write "$W/ctl/session/lease"
check "KILL du groupe : émulateur arrêté au bail" wait_clean

# 26. Démarré pour le superviseur, mais pas encore visible par le réseau :
#     emu-start attend ; jamais visible : échec, émulateur rendu.
reset
echo $((EPOCHSECONDS + 4)) >"$W/client-hidden-until"
t0=$EPOCHSECONDS
out=$("$here/emu-run" phone -- true 2>/dev/null); rc=$?
check "visible en retard : emu-start attend puis réussit" '[ $rc = 0 ] && [ $((EPOCHSECONDS - t0)) -ge 4 ]'
check "visible en retard : tout est arrêté" wait_clean
reset
echo $((EPOCHSECONDS + 600)) >"$W/client-hidden-until"
EMU_CLIENT_WAIT=3 "$here/emu-start" phone >/dev/null 2>"$W/err"; rc=$?
check "jamais visible : emu-start en échec (code 1)" '[ $rc = 1 ] && grep -q "ne répond pas par tcp" "$W/err"'
check "jamais visible : émulateur rendu, session libérée" wait_clean
rm -f "$W/client-hidden-until"

# 27. File d'attente (DMC-168) : ordre d'arrivée. A tient l'émulateur, B puis
#     C attendent ; A redemande aussitôt après l'avoir rendu : il passe après
#     B et C. Un client sans --wait-lock est refusé tant que quelqu'un attend.
reset
rm -rf "$W/ctl/queue"; : >"$W/order"
( "$here/emu-run" phone -- sleep 3 2>/dev/null
  "$here/emu-run" phone --wait-lock 60 -- bash -c 'echo A >>"$FAKE_DIR/order"' 2>/dev/null ) &
qa=$!
wait_ready
PAPERCLIP_AGENT_ID=agent-B "$here/emu-run" phone --wait-lock 60 -- bash -c 'echo B >>"$FAKE_DIR/order"' 2>"$W/err27b" &
qb=$!
sleep 0.5
PAPERCLIP_AGENT_ID=agent-C "$here/emu-run" phone --wait-lock 60 -- bash -c 'echo C >>"$FAKE_DIR/order"' 2>/dev/null &
qc=$!
sleep 1.5
PAPERCLIP_AGENT_ID=agent-D "$here/emu-start" phone 2>"$W/err27d"; rc=$?
check "file : sans --wait-lock, refusé (75) avec la file" '[ $rc = 75 ] && grep -q "2 avant vous dans la file" "$W/err27d"'
wait "$qa"; ra=$?; wait "$qb"; rb=$?; wait "$qc"; rc=$?
check "file : les trois passent" '[ $ra = 0 ] && [ $rb = 0 ] && [ $rc = 0 ]'
check "file : ordre d'arrivée B, C, puis A qui redemandait" '[ "$(paste -sd, "$W/order")" = B,C,A ]'
check "file : B a vu sa place" 'grep -q "en file d.attente" "$W/err27b"'
check "file : aucun ticket laissé" '[ -z "$(ls "$W/ctl/queue")" ] && wait_clean'

# 28. Ticket d'un client disparu (muet depuis 60 s) : ignoré et retiré.
reset
mkdir -p "$W/ctl/queue"
old_t="$(( EPOCHSECONDS - 120 ))000000-0123456789abcdef"
touch -d '-60 seconds' "$W/ctl/queue/$old_t"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "file : ticket mort ignoré" '[ $rc = 0 ] && [ ! -e "$W/ctl/queue/$old_t" ] && wait_clean'

# 29. Délai écoulé : le même run garde son rang au tour suivant, devant un
#     client arrivé entre-temps.
reset
rm -rf "$W/ctl/queue"; : >"$W/order"
"$here/emu-run" phone -- sleep 5 2>/dev/null &
q1=$!
wait_ready
PAPERCLIP_RUN_ID=run-X "$here/emu-start" phone --wait-lock 1 2>/dev/null; rc=$?
check "rang gardé : délai écoulé (75), rang mis de côté" '[ $rc = 75 ] && [ -f "$W/ctl/queue/parked-run-X" ]'
PAPERCLIP_RUN_ID=run-Y "$here/emu-run" phone --wait-lock 60 -- bash -c 'echo Y >>"$FAKE_DIR/order"' 2>/dev/null &
qy=$!
sleep 1
PAPERCLIP_RUN_ID=run-X "$here/emu-run" phone --wait-lock 60 -- bash -c 'echo X >>"$FAKE_DIR/order"' 2>/dev/null &
qx=$!
wait "$q1"; wait "$qy"; ry=$?; wait "$qx"; rx=$?
check "rang gardé : X (arrivé avant Y) passe avant Y" '[ $rx = 0 ] && [ $ry = 0 ] && [ "$(paste -sd, "$W/order")" = X,Y ]'
check "rang gardé : rien ne reste dans la file" '[ -z "$(ls "$W/ctl/queue")" ] && wait_clean'

# 30. Émulateur tué faute de mémoire (OOM du conteneur) : cause écrite.
reset
"$here/emu-run" phone -- sleep 4 2>"$W/err30" &
r=$!
wait_ready
echo "oom_kill 1" >"$W/cg/memory.events"
kill -KILL "$(cat "$W/qemu.pid")"
wait "$r"; rc=$?
check "OOM : emu-run en échec" '[ $rc != 0 ]'
check "OOM : cause et plafond dans le compte rendu" 'grep -q "tué faute de mémoire (plafond du conteneur : 5120 Mo)" "$W/ctl/results/$(last_result)"'
check "OOM : code du lanceur noté" 'grep -q "arrêté seul (état : ready ; code [0-9]" "$W/ctl/results/$(last_result)"'
check "OOM : tout arrêté" wait_clean
echo "oom_kill 1" >"$W/cg/memory.events"

# 31. emu-renew (DMC-174) : session gardée entre deux commandes d'un même run.
reset
export PAPERCLIP_RUN_ID=run-R
"$here/emu-renew" 2>/dev/null; r1=$?
"$here/emu-renew" --id 0123456789abcdef --lease 9 2>/dev/null; r2=$?
"$here/emu-renew" --id 0123456789abcdef --lease 601 2>/dev/null; r3=$?
"$here/emu-renew" --id XYZ 2>/dev/null; r4=$?
check "renew : usage (sans id, bail < 10 s ou > 600 s, id invalide) : code 2" '[ $r1 = 2 ] && [ $r2 = 2 ] && [ $r3 = 2 ] && [ $r4 = 2 ]'
"$here/emu-renew" --id 0123456789abcdef 2>"$W/err31"; rc=$?
check "renew : rien ne tourne : code 3" '[ $rc = 3 ] && grep -q "introuvable" "$W/err31"'
# Démarrage lent : pas encore prêt, renew refusé (emu-start s'en charge).
echo 3 >"$W/boot-s"
"$here/emu-start" phone --lease 10 >"$W/id31" 2>/dev/null &
s31=$!
for _ in $(seq 50); do [ "$(emu_kv "$W/ctl/session/status" state)" = booting ] && break; sleep 0.1; done
"$here/emu-renew" --id "$(emu_kv "$W/ctl/session/request" id)" 2>"$W/err31"; rc=$?
check "renew : session pas prête : code 75" '[ $rc = 75 ] && grep -q "pas prête" "$W/err31"'
wait "$s31"; rc=$?
id31=$(cat "$W/id31")
check "renew : commande 1 (emu-start --lease 10) rend la main, session prête" '[ $rc = 0 ] && emu_valid_id "$id31" && wait_ready'
# Commande 2, après la fin de la commande 1 : bail renouvelé, la session dure au-delà du premier bail.
sleep 6
"$here/emu-renew" --id "$id31" --lease 20 2>"$W/err31"; rc=$?
check "renew : bail renouvelé (code 0, échéance affichée)" '[ $rc = 0 ] && grep -q "bail renouvelé : arrêt automatique dans 20 s" "$W/err31"'
check "renew : bail écrit pour cette session" '[ "$(emu_kv "$W/ctl/session/lease" id)" = "$id31" ] && [ "$(emu_kv "$W/ctl/session/lease" until)" -ge $((EPOCHSECONDS + 18)) ]'
sleep 6
check "renew : toujours prête après l'échéance du premier bail" '[ "$(emu_kv "$W/ctl/session/status" state)" = ready ] && [ "$(emu_kv "$W/ctl/session/request" id)" = "$id31" ]'
qboot=$(grep -c ",phone," "$W/ctl/metrics.csv")
out=$(ADB_SERVER_SOCKET=tcp:127.0.0.1:5639 "$F/platform-tools/adb" -s emulator-5554 shell getprop sys.boot_completed)
check "renew : la session gardée répond, sans nouveau démarrage" '[ "$out" = 1 ] && [ "$(grep -c ",phone," "$W/ctl/metrics.csv")" = "$qboot" ]'
lease_before=$(cat "$W/ctl/session/lease")
PAPERCLIP_AGENT_ID=agent-B "$here/emu-renew" --id "$id31" 2>"$W/err31"; rc=$?
check "renew : autre agent refusé (75), bail inchangé" '[ $rc = 75 ] && grep -q "autre agent" "$W/err31" && [ "$(cat "$W/ctl/session/lease")" = "$lease_before" ]'
PAPERCLIP_RUN_ID=run-S "$here/emu-renew" --id "$id31" 2>"$W/err31"; rc=$?
check "renew : autre run refusé (75), bail inchangé" '[ $rc = 75 ] && grep -q "autre run" "$W/err31" && [ "$(cat "$W/ctl/session/lease")" = "$lease_before" ]'
"$here/emu-renew" --id 0123456789abcdef 2>/dev/null; rc=$?
check "renew : identifiant d'une autre session : code 3, bail inchangé" '[ $rc = 3 ] && [ "$(cat "$W/ctl/session/lease")" = "$lease_before" ]'
# Un agent attend : avertissement.
mkdir -p "$W/ctl/queue"
printf 'agent=agent-B\n' >"$W/ctl/queue/${EPOCHREALTIME/./}-fedcba9876543210"
"$here/emu-renew" --id "$id31" --lease 10 2>"$W/err31"; rc=$?
check "renew : file d'attente signalée" '[ $rc = 0 ] && grep -q "1 agent(s) en file d.attente : emu-stop --id $id31" "$W/err31"'
rm -f "$W/ctl/queue/"*
# Bail raccourci à 10 s sans emu-stop : le superviseur rend l'émulateur.
for _ in $(seq 75); do [ -f "$W/ctl/results/$id31" ] && break; sleep 0.2; done
check "renew : bail raccourci échu, émulateur arrêté" '[ "$(emu_kv "$W/ctl/results/$id31" result)" = expired ] && wait_clean'
"$here/emu-renew" --id "$id31" 2>"$W/err31"; rc=$?
check "renew : session terminée : code 3 et cause" '[ $rc = 3 ] && grep -q "terminée (expired" "$W/err31"'
# Arrêt déjà demandé : refusé.
reset
id31=$("$here/emu-start" phone --lease 30 2>/dev/null)
echo "id=$id31" >"$W/ctl/session/stop.tmp"
"$here/emu-renew" --id "$id31" 2>/dev/null; r0=$?
mv "$W/ctl/session/stop.tmp" "$W/ctl/session/stop"
"$here/emu-renew" --id "$id31" 2>"$W/err31"; rc=$?
check "renew : arrêt déjà demandé : code 3" '[ $r0 = 0 ] && [ $rc = 3 ]'
"$here/emu-stop" --id "$id31" 2>/dev/null
check "renew : tout arrêté" wait_clean
unset PAPERCLIP_RUN_ID

# 32. Snapshot propre (DMC-174). Sans réglage, jamais de construction ni de
# chargement : déjà vérifié par tous les cas précédents (aucun dossier golden).
G=$W/data/golden/phone
check "snapshot : rien sans réglage" '[ ! -e "$W/data/golden" ] && ! grep -q -- "-read-only" "$W/emulator.all" 2>/dev/null'
reset
"$here/emu-start" phone --boot warm 2>/dev/null; rc=$?
check "snapshot : --boot invalide refusé (2)" '[ $rc = 2 ]'
# --boot snapshot sans réglage : à froid, et dit pourquoi.
"$here/emu-run" phone --boot snapshot -- true 2>/dev/null; rc=$?
check "snapshot : demandé mais non activé : à froid" '[ $rc = 0 ] && grep -q -- "-wipe-data" "$W/emulator.args" && grep -q "cold (snapshot non activé pour phone)" "$W/supervisor.log"'
wait_clean
# Réglage opt-in : construction au repos.
wait_golden() { local _; for _ in $(seq 100); do [ -f "$G/stamp" ] && clean && return 0; sleep 0.2; done; return 1; }
echo "phone=opt-in" >"$W/snap.conf"
check "snapshot : construit au repos" wait_golden
check "snapshot : construit à froid, pris par adb emu avd snapshot save clean" 'grep -q -- "-wipe-data" "$W/emulator.args" && grep -q "emu avd snapshot save clean" "$W/adb.calls" && [ -f "$G/avd/phone.avd/snapshots/clean/ram.bin" ]'
check "snapshot : état publié (ready)" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = ready ] && [ -n "$(emu_kv "$W/ctl/golden.phone" size_mb)" ]'
check "snapshot : tv non construit" '[ ! -e "$W/data/golden/tv" ]'
check "snapshot : construction sans serveur adb sur le réseau (pas de -a)" 'grep -q -- "^-P 5639 nodaemon server" "$W/adb.calls"'
# Besoin de construction vérifié toutes les EMU_GOLDEN_CHECK_S s, pas à chaque
# tour : empreinte calculée 2 fois en 7 s (repos atteint à 3 s, vérif. toutes les 2 s).
stop_sup
SUP_TRACE=1 SUP_GCHECK=2 start_sup
sleep 7
stop_sup
nstamp=$(grep -c '^+* golden_stamp phone$' "$W/sup.trace")
check "snapshot : empreinte recalculée au rythme de EMU_GOLDEN_CHECK_S ($nstamp fois en 7 s)" '[ "$nstamp" -ge 1 ] && [ "$nstamp" -le 3 ]'
start_sup
# opt-in : sans --boot, toujours à froid.
reset
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "snapshot : opt-in sans --boot : à froid" '[ $rc = 0 ] && grep -q -- "-wipe-data" "$W/emulator.args"'
reset
out=$("$here/emu-run" phone --boot snapshot -- bash -c 'echo "$ADB_SERVER_SOCKET $ANDROID_SERIAL"' 2>"$W/err32"); rc=$?
check "snapshot : chargé en lecture seule, sans -wipe-data" '[ $rc = 0 ] && grep -q -- "-read-only -snapshot clean -force-snapshot-load -no-snapshot-save" "$W/emulator.args" && ! grep -q -- "-wipe-data" "$W/emulator.args"'
check "snapshot : AVD propre à la session, copies temporaires sous la session" 'grep -q "^ANDROID_AVD_HOME=$W/data/run/avd$" "$W/emulator.env" && grep -q "^TMPDIR=$W/data/run/tmp$" "$W/emulator.env"'
check "snapshot : la commande vise l'émulateur ; mode affiché" '[ "$out" = "tcp:127.0.0.1:5639 emulator-5554" ] && grep -q "prêt en .* s, snapshot" "$W/err32"'
check "snapshot : métrique avec le mode" '[ "$(tail -n 1 "$W/ctl/metrics.csv" | cut -d, -f5)" = snapshot ]'
check "snapshot : copies temporaires supprimées, snapshot toujours valide" 'wait_clean && [ -f "$G/stamp" ]'
# Horloge (DMC-299) : réglée sur l'heure du conteneur avant « prêt », retard noté.
reset
echo 600 >"$W/clock-lag"
"$here/emu-run" phone --boot snapshot -- true 2>/dev/null; rc=$?
# clock.set : « @<heure demandée> <heure du faux appareil à la demande> ».
set1=$(head -n 1 "$W/clock.set" 2>/dev/null) asked=""
asked=${set1%% *} asked=${asked#@}
check "horloge : snapshot : réglée une fois, sur l'heure du conteneur" '[ $rc = 0 ] && [ "$(wc -l <"$W/clock.set")" = 1 ] && [[ "${set1%% *}" =~ ^@[0-9]+$ ]] && [ $(( ${set1##* } - asked )) -ge -1 ] && [ $(( ${set1##* } - asked )) -le 1 ]'
check "horloge : retard noté dans le journal" 'grep -q "horloge de l.appareil réglée (retard de 60[01] s)" "$W/supervisor.log"'
reset
touch "$W/su-fail"
"$here/emu-run" phone --boot snapshot -- true 2>/dev/null; rc=$?
check "horloge : su en échec : session prête quand même (code 0), échec noté" '[ $rc = 0 ] && grep -q "horloge de l.appareil non réglée" "$W/supervisor.log" && [ "$(tail -n 1 "$W/ctl/metrics.csv" | cut -d, -f5)" = snapshot ]'
reset
"$here/emu-run" phone --boot cold -- true 2>/dev/null; rc=$?
check "horloge : à froid : pas de réglage" '[ $rc = 0 ] && [ ! -e "$W/clock.set" ]'
# Mode on : par défaut ; --boot cold force le froid.
echo "phone=on" >"$W/snap.conf"
reset
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "snapshot : on : chargé par défaut" '[ $rc = 0 ] && grep -q -- "-read-only" "$W/emulator.args"'
reset
"$here/emu-run" phone --boot cold -- true 2>/dev/null; rc=$?
check "snapshot : on : --boot cold à froid" '[ $rc = 0 ] && grep -q -- "-wipe-data" "$W/emulator.args"'
# Chargement en échec : retour à froid dans la même session, snapshot
# invalidé, et pas reconstruit avant EMU_GOLDEN_RETRY_S (ici 30 s).
stop_sup
SUP_GRETRY=30 start_sup
reset
touch "$W/load-fail"
"$here/emu-run" phone -- true 2>"$W/err32"; rc=$?
check "snapshot : chargement en échec : la session repart à froid (code 0)" '[ $rc = 0 ] && grep -q -- "-wipe-data" "$W/emulator.args" && grep -q "après l.échec du snapshot" "$W/supervisor.log"'
check "snapshot : invalidé après l'échec" 'grep -q "invalidé : émulateur arrêté au chargement" "$W/supervisor.log"'
check "snapshot : métrique « fallback » après l'échec" '[ "$(tail -n 1 "$W/ctl/metrics.csv" | cut -d, -f5)" = fallback ]'
rm -f "$W/load-fail"
nb=$(grep -c "snapshot propre de phone : démarrage à froid" "$W/supervisor.log")
sleep 6
check "snapshot : invalidé : pas de reconstruction avant le délai" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = invalid ] && [ ! -f "$G/stamp" ] && [ "$(grep -c "snapshot propre de phone : démarrage à froid" "$W/supervisor.log")" = "$nb" ]'
stop_sup
start_sup
check "snapshot : reconstruit au repos après le délai" wait_golden
# Chargement qui ne finit pas (6 s ici) : le retour à froid a de nouveau tout
# EMU_BOOT_TIMEOUT (16 s ici) ; il démarre en 12 s, soit 18 s depuis la demande.
reset
touch "$W/snap-hang"
echo 12 >"$W/boot-s"
"$here/emu-run" phone -- true 2>"$W/err32"; rc=$?
check "snapshot : chargement trop long : froid complet, sans échec (code 0)" '[ $rc = 0 ] && grep -q "invalidé : chargement plus long que 6 s" "$W/supervisor.log" && [ "$(tail -n 1 "$W/ctl/metrics.csv" | cut -d, -f3)" -ge 17 ]'
rm -f "$W/snap-hang" "$W/boot-s"
check "snapshot : reconstruit après le chargement trop long" wait_golden
# AVD de la session : .qcow2 et petits fichiers copiés, le reste en lien vers le snapshot.
reset
A34=$W/data/run/avd/phone.avd
"$here/emu-run" phone -- sh -c '[ -f "$1/userdata-qemu.img.qcow2" ] && [ ! -L "$1/userdata-qemu.img.qcow2" ] && [ -L "$1/snapshots" ] && [ ! -L "$1/config.ini" ] && [ "$(readlink "$1/snapshots")" = "$2/avd/phone.avd/snapshots" ]' _ "$A34" "$G" 2>/dev/null; rc=$?
check "snapshot : session sur ses copies des .qcow2, liens vers le reste" '[ $rc = 0 ] && wait_clean && grep -q "copies du snapshot prêtes en" "$W/supervisor.log"'
# Écritures de la session dans son .qcow2 : le snapshot n'est pas touché.
reset
touch "$W/write-qcow2"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
"$here/emu-run" phone -- true 2>/dev/null; rc2=$?
check "snapshot : écritures dans le .qcow2 de la session : snapshot intact, toujours valide" '[ $rc = 0 ] && [ $rc2 = 0 ] && wait_clean && [ -f "$G/stamp" ] && [ "$(emu_kv "$W/ctl/golden.phone" state)" = ready ] && ! grep -q session "$G/avd/phone.avd/userdata-qemu.img.qcow2" && grep -q -- "-read-only" "$W/emulator.args"'
rm -f "$W/write-qcow2"
# Session qui modifie les fichiers du snapshot : invalidé à l'arrêt.
reset
touch "$W/write-golden"
"$here/emu-run" phone -- true 2>/dev/null
check "snapshot : modifié par une session : invalidé à l'arrêt" 'grep -q "modifié par la session" "$W/supervisor.log" && grep -q "fichiers du snapshot modifiés par la session .*userdata-qemu.img" "$W/supervisor.log"'
check "snapshot : fichier modifié publié dans golden.phone" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = invalid ] && grep -q "^message=modifié par la session [0-9a-f]* : phone.avd/userdata-qemu.img (isolation" "$W/ctl/golden.phone"'
rm -f "$W/write-golden"
check "snapshot : reconstruit propre, sans les données de la session" 'wait_golden && ! grep -q dirty "$G/avd/phone.avd/userdata-qemu.img"'
# snapshot.pb réécrit au chargement : sans effet, publié, snapshot gardé.
reset
touch "$W/touch-pb"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "snapshot : snapshot.pb réécrit au chargement : gardé, signalé" '[ $rc = 0 ] && wait_clean && [ -f "$G/stamp" ] && [ "$(emu_kv "$W/ctl/golden.phone" state)" = ready ] && grep -q "^meta_changed=phone.avd/snapshots/clean/snapshot.pb (session" "$W/ctl/golden.phone" && grep -q "^build_s=" "$W/ctl/golden.phone"'
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "snapshot : session suivante encore sur le snapshot, toujours valide" '[ $rc = 0 ] && grep -q -- "-read-only" "$W/emulator.args" && wait_clean && [ -f "$G/stamp" ]'
# snapshot.pb et une image changés : invalidé, seule l'image est citée.
touch "$W/write-golden"
"$here/emu-run" phone -- true 2>/dev/null
check "snapshot : snapshot.pb + image : invalidé, image citée" 'wait_clean && [ ! -f "$G/stamp" ] && grep -q "^message=modifié par la session [0-9a-f]* : phone.avd/userdata-qemu.img (isolation" "$W/ctl/golden.phone"'
rm -f "$W/write-golden" "$W/touch-pb"
check "snapshot : reconstruit après l'invalidation" wait_golden
# Définition de l'AVD modifiée : snapshot caduc, session à froid.
reset
echo "hw.test=1" >>"$W/avd/phone.avd/config.ini"
"$here/emu-run" phone -- true 2>/dev/null
check "snapshot : AVD modifié : caduc, à froid" 'grep -q -- "-wipe-data" "$W/emulator.args" && grep -q "snapshot pas encore prêt" "$W/supervisor.log"'
check "snapshot : reconstruit pour la nouvelle définition" wait_golden
# Un agent arrive pendant la construction : abandon immédiat, l'agent est servi.
echo 5 >"$W/boot-s"
rm -f "$G/stamp"
for _ in $(seq 50); do [ "$(emu_kv "$W/ctl/golden.phone" state)" = building ] && break; sleep 0.2; done
check "snapshot : construction en cours" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = building ]'
t=$EPOCHSECONDS
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "snapshot : construction abandonnée, agent servi à froid" '[ $rc = 0 ] && grep -q "construction abandonnée" "$W/supervisor.log" && [ $((EPOCHSECONDS - t)) -le 15 ]'
rm -f "$W/boot-s"
check "snapshot : reconstruit après l'abandon" wait_golden
# Prise refusée : échec publié, pas de nouvel essai avant EMU_GOLDEN_RETRY_S, puis reprise.
reset
touch "$W/save-fail"
rm -f "$G/stamp"
for _ in $(seq 75); do [ "$(emu_kv "$W/ctl/golden.phone" state)" = failed ] && break; sleep 0.2; done
check "snapshot : prise refusée : échec publié, tout nettoyé" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = failed ] && grep -q "prise refusée : KO" "$W/supervisor.log" && wait_clean && [ ! -e "$G" ]'
rm -f "$W/save-fail"
check "snapshot : nouvel essai après le délai" wait_golden
# Prise sans réponse : abandonnée au délai.
reset
echo 30 >"$W/save-s"
rm -f "$G/stamp"
for _ in $(seq 100); do [ "$(emu_kv "$W/ctl/golden.phone" state)" = failed ] && break; sleep 0.2; done
check "snapshot : prise sans réponse : échec, rien ne reste" '[ "$(emu_kv "$W/ctl/golden.phone" state)" = failed ] && grep -q "prise sans réponse" "$W/supervisor.log" && wait_clean'
rm -f "$W/save-s"
# Arrêt du conteneur pendant la construction : tout est arrêté, rien de partiel ne reste.
reset
echo 5 >"$W/boot-s"
rm -f "$G/stamp"
for _ in $(seq 75); do [ "$(emu_kv "$W/ctl/golden.phone" state)" = building ] && break; sleep 0.2; done
stop_sup
check "snapshot : arrêt du conteneur pendant la construction : rien ne reste" '[ -z "$(fakes)" ] && [ ! -e "$G" ] && [ ! -e "$W/data/run" ] && grep -q "construction interrompue par l.arrêt du conteneur" "$W/supervisor.log"'
rm -f "$W/boot-s"
start_sup
# Construction demandée (emu-start --boot build) : par la file, sans attendre
# le repos, et pas interrompue par un agent qui demande l'émulateur. Ici, pas
# de construction au repos (SUP_GIDLE), délai de construction 40 s.
stop_sup
SUP_GIDLE=1000 SUP_GRETRY=1000 SUP_GBOOT=40 start_sup
wait_result() { local _; for _ in $(seq 150); do [ -f "$W/ctl/results/$1" ] && return 0; sleep 0.2; done; return 1; }
reset
"$here/emu-run" phone --boot build -- true 2>"$W/err34"; rc=$?
check "build : refusé par emu-run (2)" '[ $rc = 2 ] && grep -q "avec emu-start seulement" "$W/err34" && [ ! -e "$W/ctl/session" ]'
# Démarrage en 18 s : au-delà d'EMU_BOOT_TIMEOUT (16 s), sous EMU_GOLDEN_BOOT_TIMEOUT.
echo 18 >"$W/boot-s"
n0=$(wc -l <"$W/emulator.all")
gid=$("$here/emu-start" phone --boot build 2>"$W/err34"); rc=$?
check "build : emu-start rend la main dès le début (code 0, identifiant)" '[ $rc = 0 ] && emu_valid_id "$gid" && grep -q "construction du snapshot propre de phone commencée" "$W/err34"'
check "build : état building (session et golden.phone)" '[ "$(emu_kv "$W/ctl/session/status" state)" = building ] && [ "$(emu_kv "$W/ctl/golden.phone" state)" = building ]'
check "build : à froid, serveur adb limité au conteneur" 'grep -q -- "-wipe-data" "$W/emulator.args" && [ "$(grep "nodaemon server" "$W/adb.calls" | tail -n 1)" = "-P 5639 nodaemon server" ] && grep -q "démarrage à froid (session $gid, agent agent-A" "$W/supervisor.log"'
rm -f "$W/ran34"
PAPERCLIP_AGENT_ID=agent-B "$here/emu-run" phone --wait-lock 90 -- touch "$W/ran34" 2>"$W/err34b" &
qb=$!
sleep 3
check "build : un agent arrive : il attend, la construction continue" 'kill -0 $qb 2>/dev/null && [ "$(emu_kv "$W/ctl/golden.phone" state)" = building ] && ! grep -q "construction demandée abandonnée" "$W/supervisor.log"'
# Démarrage lent pour la construction seulement : l'agent B démarre ensuite en 1 s.
for _ in $(seq 150); do grep -q "snapshot propre de phone : démarré en 1[89] s" "$W/supervisor.log" && break; sleep 0.2; done
rm -f "$W/boot-s"
wait "$qb"; rb=$?
check "build : prête au-delà d'EMU_BOOT_TIMEOUT, compte rendu built (0)" 'wait_result "$gid" && [ "$(emu_kv "$W/ctl/results/$gid" result)" = built ] && [ "$(emu_kv "$W/ctl/results/$gid" code)" = 0 ] && [ -f "$G/stamp" ] && grep -q "snapshot propre de phone : démarré en 1[89] s" "$W/supervisor.log"'
check "build : l'agent passe ensuite, sur le snapshot" '[ $rb = 0 ] && [ -e "$W/ran34" ] && grep -q -- "-read-only" "$W/emulator.args" && wait_clean'
# Déjà prêt : rien construit.
reset
n0=$(wc -l <"$W/emulator.all")
"$here/emu-start" phone --boot build 2>"$W/err34" >/dev/null; rc=$?
check "build : déjà prêt : code 0, rien démarré" '[ $rc = 0 ] && grep -q "déjà prêt" "$W/err34" && [ "$(wc -l <"$W/emulator.all")" = "$n0" ] && wait_clean'
# Abandon par emu-stop : tout est arrêté, la session libérée.
reset
rm -f "$G/stamp"
echo 30 >"$W/boot-s"
gid=$("$here/emu-start" phone --boot build 2>/dev/null)
"$here/emu-stop" --id "$gid" 2>"$W/err34"; rc=$?
check "build : emu-stop l'abandonne (code 0), rien ne reste" '[ $rc = 0 ] && [ "$(emu_kv "$W/ctl/results/$gid" result)" = aborted ] && grep -q "construction demandée abandonnée" "$W/err34" && wait_clean && [ ! -e "$G" ] && [ "$(emu_kv "$W/ctl/golden.phone" state)" = missing ]'
rm -f "$W/boot-s"
# Échec, puis nouvelle demande aussitôt : le délai après échec ne s'applique pas.
reset
touch "$W/save-fail"
gid=$("$here/emu-start" phone --boot build 2>/dev/null)
check "build : échec : compte rendu failed (1), tout nettoyé" 'wait_result "$gid" && [ "$(emu_kv "$W/ctl/results/$gid" result)" = failed ] && grep -q "prise refusée" "$W/ctl/results/$gid" && wait_clean'
rm -f "$W/save-fail"
gid=$("$here/emu-start" phone --boot build 2>/dev/null); rc=$?
check "build : nouvelle demande juste après l'échec : construit" '[ $rc = 0 ] && wait_result "$gid" && [ "$(emu_kv "$W/ctl/results/$gid" result)" = built ] && [ -f "$G/stamp" ] && wait_clean'
# Réglage off : refus (2), rien démarré.
echo "phone=off" >"$W/snap.conf"
rm -f "$G/stamp"
n0=$(wc -l <"$W/emulator.all")
"$here/emu-start" phone --boot build 2>"$W/err34" >/dev/null; rc=$?
check "build : réglage off : code 2, rien démarré" '[ $rc = 2 ] && grep -q "snapshot non activé pour phone" "$W/err34" && [ "$(wc -l <"$W/emulator.all")" = "$n0" ] && wait_clean'
echo "phone=on" >"$W/snap.conf"
stop_sup
start_sup
# Réglage retiré : plus de construction ni de chargement.
rm -f "$W/snap.conf"
reset
"$here/emu-run" phone --boot snapshot -- true 2>/dev/null
check "snapshot : réglage retiré : à froid" 'grep -q -- "-wipe-data" "$W/emulator.args"'
check "snapshot : tout arrêté" wait_clean

# 33. Charge de l'hôte (DMC-285) : au-dessus d'EMU_MAX_LOAD (12), refus (76)
#     sans rien démarrer ; à 12 ou moins, démarrage comme avant. Un client en
#     file n'est jugé qu'à son tour, émulateur libre.
load() { echo "$1 9.00 8.00 3/400 999" >"$W/loadavg"; }
queue_tickets() { ls "$W/ctl/queue" 2>/dev/null | grep -v '^parked-'; }
reset
load 15.00
rm -f "$W/emulator.args" "$W/ran33"
n0=$(ls "$W/ctl/results" | wc -l)
"$here/emu-run" phone -- touch "$W/ran33" 2>"$W/err33"; rc=$?
check "charge : 15 > 12 : code 76, message en une ligne" '[ $rc = 76 ] && [ "$(cat "$W/err33")" = "emu-start: Hôte chargé (charge 15.00 > 12) : réessaie plus tard" ]'
check "charge : 15 : ni émulateur ni commande, rien laissé" '[ ! -e "$W/emulator.args" ] && [ ! -e "$W/ran33" ] && [ ! -e "$W/ctl/session" ] && [ "$(ls "$W/ctl/results" | wc -l)" = "$n0" ] && [ -z "$(queue_tickets)" ]'
load 12.01
"$here/emu-start" phone --wait-lock 5 2>"$W/err33"; rc=$?
check "charge : 12,01 : refusé aussi par emu-start, sans attendre le délai" '[ $rc = 76 ] && grep -q "charge 12.01 > 12" "$W/err33" && [ ! -e "$W/emulator.args" ] && [ -z "$(queue_tickets)" ]'
load 3.00
"$here/emu-run" phone -- touch "$W/ran33" 2>/dev/null; rc=$?
check "charge : 3 : démarrage comme avant" '[ $rc = 0 ] && [ -e "$W/emulator.args" ] && [ -e "$W/ran33" ] && wait_clean'
reset
load 12.00
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "charge : 12,00 (pas au-dessus) : démarrage" '[ $rc = 0 ] && wait_clean'
reset
load 15.00
EMU_MAX_LOAD=20 "$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "charge : EMU_MAX_LOAD=20 : 15 passe" '[ $rc = 0 ] && wait_clean'
reset
EMU_LOADAVG="$W/absent" "$here/emu-run" phone -- true 2>"$W/err33"; rc=$?
check "charge : fichier illisible : démarrage, sans message parasite" '[ $rc = 0 ] && ! grep -q "absent\|loadavg" "$W/err33" && wait_clean'
# En file : la charge monte pendant que A tient l'émulateur. B attend sans
# être refusé, puis est refusé à son tour, sans qu'aucun émulateur démarre pour lui.
reset
load 3.00
rm -f "$W/ran33b"
"$here/emu-run" phone -- sleep 4 2>/dev/null &
qa=$!
wait_ready
starts=$(wc -l <"$W/emulator.all")
load 25.00
PAPERCLIP_AGENT_ID=agent-B "$here/emu-run" phone --wait-lock 60 -- touch "$W/ran33b" 2>"$W/err33b" &
qb=$!
sleep 1.5
check "charge : en file, pas refusé tant que l'émulateur est pris" 'kill -0 $qb 2>/dev/null && [ -n "$(queue_tickets)" ]'
wait "$qa"; ra=$?; wait "$qb"; rb=$?
check "charge : A finit normalement" '[ $ra = 0 ]'
check "charge : B refusé à son tour (76), rien démarré pour lui, ticket retiré" '[ $rb = 76 ] && grep -q "charge 25.00 > 12" "$W/err33b" && [ ! -e "$W/ran33b" ] && [ "$(wc -l <"$W/emulator.all")" = "$starts" ] && [ -z "$(queue_tickets)" ] && wait_clean'
load 0.50

# 34. Utilisateur 0 pas encore déverrouillé (DMC-294) : sys.boot_completed=1
#     ne suffit pas. La session n'est prête, et la commande ne part, qu'avec
#     sys.user.0.ce_available=true ; le temps noté compte cette attente.
reset
echo 1 >"$W/boot-s"
echo 4 >"$W/ce-s"
rm -f "$W/ce34"
"$here/emu-run" phone -- sh -c '"$0" shell getprop sys.user.0.ce_available >"$1"' "$F/platform-tools/adb" "$W/ce34" 2>/dev/null &
r34=$!
for _ in $(seq 50); do [ -f "$W/qemu.start" ] && break; sleep 0.1; done
sleep 3
st34=$(emu_kv "$W/ctl/session/status" state)
check "ce_available : démarré mais verrouillé (3 s) : toujours booting" '[ "$st34" = booting ] && grep -q "sys.user.0.ce_available" "$W/adb.calls"'
wait "$r34"; rc=$?
check "ce_available : la commande voit true au premier appel" '[ $rc = 0 ] && [ "$(cat "$W/ce34")" = true ]'
check "ce_available : temps de démarrage noté ≥ 5 s" '[ "$(tail -n 1 "$W/ctl/metrics.csv" | cut -d, -f3)" -ge 5 ] && wait_clean'
# Construction du snapshot propre : pas de prise avant le déverrouillage.
reset
echo 1 >"$W/boot-s"
echo 4 >"$W/ce-s"
echo "phone=opt-in" >"$W/snap.conf"
rm -f "$G/stamp"
check "ce_available : snapshot construit" wait_golden
check "ce_available : snapshot démarré en ≥ 5 s avant la prise" '[ "$(sed -n "s/.*snapshot propre de phone : démarré en \([0-9]*\) s.*/\1/p" "$W/supervisor.log" | tail -n 1)" -ge 5 ]'
rm -f "$W/snap.conf"
reset

# 35. Options par profil (DMC-408) : liste blanche lowram / memory, lue dans
# <profil>.avd/emu-options ; le reste n'arrive jamais à l'émulateur.
printf 'lowram=yes\nmemory=2560\n' >"$W/avd/phone.avd/emu-options"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "options : -lowram -memory 2560 passés à l'émulateur" '[ $rc = 0 ] && grep -q -- "-lowram -memory 2560 -gpu" "$W/emulator.args" && wait_clean'
check "options : tv sans options" '"$here/emu-run" tv -- true 2>/dev/null && ! grep -q -- "-lowram\|-memory" "$W/emulator.args" && wait_clean'
reset
printf 'lowram=no\nmemory=512\ngpu=host\n-gpu host\n' >"$W/avd/phone.avd/emu-options"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "options : hors liste blanche ignorées, memory hors bornes noté" '[ $rc = 0 ] && ! grep -q -- "-lowram\|-memory\|host" "$W/emulator.args" && grep -q "memory=512 ignoré" "$W/supervisor.log" && wait_clean'
reset
printf 'memory=4096x\n' >"$W/avd/phone.avd/emu-options"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "options : memory non numérique ignoré" '[ $rc = 0 ] && ! grep -q -- "-memory" "$W/emulator.args" && wait_clean'
reset
# Le snapshot dépend des options : les changer le rend caduc, et la
# construction comme la session les reçoivent.
rm -f "$W/avd/phone.avd/emu-options"
echo "phone=on" >"$W/snap.conf"
check "options : snapshot construit sans options" wait_golden
st0=$(cat "$G/stamp")
printf 'lowram=yes\nmemory=2560\n' >"$W/avd/phone.avd/emu-options"
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "options : options changées : snapshot caduc, à froid avec les options" '[ $rc = 0 ] && grep -q -- "-wipe-data -lowram -memory 2560" "$W/emulator.args" && grep -q "snapshot pas encore prêt" "$W/supervisor.log"'
# L'ancien stamp reste en place jusqu'à la reconstruction (après EMU_GOLDEN_IDLE_S).
wait_new_golden() { local _; for _ in $(seq 150); do [ -f "$G/stamp" ] && [ "$(cat "$G/stamp")" != "$1" ] && clean && return 0; sleep 0.2; done; return 1; }
check "options : snapshot reconstruit avec les options" 'wait_new_golden "$st0" && grep -q -- "-wipe-data -lowram -memory 2560" "$W/emulator.args"'
reset
"$here/emu-run" phone -- true 2>/dev/null; rc=$?
check "options : session chargée depuis le snapshot avec les mêmes options" '[ $rc = 0 ] && grep -q -- "-read-only .* -lowram -memory 2560" "$W/emulator.args" && wait_clean'
rm -f "$W/avd/phone.avd/emu-options" "$W/snap.conf"
reset

# 36. EMU_PROFILES (DMC-408) : sdk-sync.sh refuse toute option hors liste
# blanche ou mal écrite (code 2, avant tout geste), puis écrit ou retire
# <profil>.avd/emu-options (vu ici en --dry-run, sans SDK réel).
S=$W/sync
mkdir -p "$S/root/sdk/cmdline-tools/latest/bin" "$S/root/avd/phone.avd"
cp "$src/../sdk-sync.sh" "$S/"
printf '#!/bin/sh\nexit 0\n' >"$S/root/sdk/cmdline-tools/latest/bin/sdkmanager"
chmod 755 "$S/sdk-sync.sh" "$S/root/sdk/cmdline-tools/latest/bin/sdkmanager"
img='system-images;android-36;google_apis;x86_64'
sync_profiles() {
  { cat "$src/../android-sdk.versions"; printf 'EMU_PROFILES="%s"\n' "$1"; } >"$S/android-sdk.versions"
  EMU_CTL=$S/ctl "$S/sdk-sync.sh" --dry-run "$S/root" >"$S/out" 2>&1
}
bad=''
for o in 'lowram,' ',lowram' 'lowram,,memory=2048' '' 'lowram:' 'lowram:x' 'lowram,lowram' \
  'memory=1024,memory=2048' 'memory=02560' 'memory=1023' 'memory=9000' 'memory=' 'gpu=host' '-gpu host' 'lowram=yes'; do
  sync_profiles "phone:$img:pixel_7:$o"
  [ $? = 2 ] && grep -q "^EMU_PROFILES : " "$S/out" && ! grep -q "^==" "$S/out" || bad+=" [$o]"
done
check "profils : options mal écrites ou hors liste refusées (2) avant tout geste" '[ -z "$bad" ] || { echo "acceptées :$bad"; false; }'
sync_profiles "phone:$img:pixel_7:memory=8192,lowram"; rc=$?
check "profils : options permises acceptées, emu-options à écrire" '[ $rc = 3 ] && grep -q "Profil phone : options memory=8192 lowram=yes" "$S/out"'
printf 'memory=8192\nlowram=yes\n' >"$S/root/avd/phone.avd/emu-options"
sync_profiles "phone:$img:pixel_7:memory=8192,lowram"
check "profils : emu-options déjà à jour, rien à écrire" '! grep -q "Profil phone : options" "$S/out"'
sync_profiles "phone:$img:pixel_7"
check "profils : options retirées du profil, emu-options à retirer" 'grep -q "Profil phone : options retirées" "$S/out" && grep -q "(dry-run) rm -f .*/phone.avd/emu-options" "$S/out"'

stop_sup
echo "---"
echo "$pass ok, $failn en échec"
[ "$failn" = 0 ]
