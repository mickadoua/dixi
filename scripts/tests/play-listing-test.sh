#!/usr/bin/env bash
# Tests de scripts/play-listing.py (DMC-567) sans Google :
#   - refus avant tout appel : --langs absent ou invalide, paquet hors tech.dmconsulting, clé absente,
#     texte trop long, image à la mauvaise taille, CSV de sécurité des données invalide ;
#   - contre une fausse API Play locale qui garde un état par edit : essai à blanc (écrit dans l'edit,
#     edits.validate, puis edits.delete, jamais edits.commit), --commit (puis relecture sur un nouvel
#     edit), second --commit sans aucun envoi, --verify, sécurité des données envoyée seulement avec
#     --commit, erreur exacte de Play et edit supprimé quand Google refuse.
# Usage : scripts/tests/play-listing-test.sh   (aucun réseau sortant, tout reste sur 127.0.0.1)
# check évalue ses conditions plus tard : guillemets simples voulus.
# shellcheck disable=SC2016
set -uo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
W=$(mktemp -d "${PAPERCLIP_RUN_SCRATCH_DIR:-${TMPDIR:-/tmp}}/play-listing-test.XXXXXX")
fails=0 server=
trap '[ -z "$server" ] || kill "$server" 2>/dev/null; rm -rf "$W"' EXIT
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
run() { (cd "$W" && env -i PATH="$PATH" HOME="$W" PAPERCLIP_RUN_SCRATCH_DIR="$W" "$@" python3 -I "$root/scripts/play-listing.py" "${ARGS[@]}") >"$W/out" 2>"$W/err"; echo $? >"$W/rc"; }
rc() { cat "$W/rc"; }

# Dossier Play de test, au format de Gradle Play Publisher, avec de vrais PNG aux bonnes tailles.
P=$W/play
python3 -I - "$P" <<'EOF'
import os, struct, sys, zlib
P = sys.argv[1]
def png(path, w, h, shade):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    raw = b"".join(b"\0" + bytes([shade, 80, 160]) * w for _ in range(h))
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
def text(path, s):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(s + "\n")
text(P + "/contact-email.txt", "test@example.com")
text(P + "/contact-website.txt", "https://example.com")
for lang, n in (("en-US", 1), ("fr-FR", 2)):
    L = P + "/listings/" + lang
    text(L + "/title.txt", "Dixi – test " + lang)
    text(L + "/short-description.txt", "Courte " + lang)
    text(L + "/full-description.txt", "Longue « " + lang + " » é")
    png(L + "/graphics/icon/icon.png", 512, 512, 1)
    png(L + "/graphics/feature-graphic/f.png", 1024, 500, n)
    for i in (1, 2):
        png(L + "/graphics/phone-screenshots/0%d.png" % i, 540, 960, 10 * n + i)
text(P + "/listings/de-DE/title.txt", "Nur Text")
text(P + "/listings/de-DE/short-description.txt", "Kurz")
text(P + "/listings/de-DE/full-description.txt", "Lang")
EOF
printf 'Question ID (machine readable),Response ID (machine readable),Response value,Answer requirement,Human-friendly question label\nPSL_DATA_COLLECTION_COLLECTS_PERSONAL_DATA,,true,REQUIRED,Collects data\n' >"$W/safety.csv"
BASE=(--package tech.dmconsulting.dixi --play-dir "$P")

# 1. Refus avant tout appel, sans aucun secret.
ARGS=("${BASE[@]}"); run
check "--langs absent : code 2" '[ "$(rc)" = 2 ] && grep -q "langs" "$W/err"'
ARGS=("${BASE[@]}" --langs "en-US;rm"); run
check "--langs invalide : code 2" '[ "$(rc)" = 2 ]'
ARGS=(--package com.example.app --play-dir "$P" --langs en-US); run
check "paquet hors DM Consulting : code 2" '[ "$(rc)" = 2 ] && grep -q "not a DM Consulting package" "$W/err"'
ARGS=("${BASE[@]}" --langs it-IT); run
check "langue sans dossier : code 2" '[ "$(rc)" = 2 ] && grep -q "no listing folder" "$W/err"'
ARGS=("${BASE[@]}" --langs en-US); run
check "sans clé : code 4" '[ "$(rc)" = 4 ] && grep -q "missing PLAY_SERVICE_ACCOUNT_JSON" "$W/err"'
ARGS=("${BASE[@]}" --langs en-US --read --data-safety "$W/safety.csv"); run
check "--data-safety avec --read : code 2" '[ "$(rc)" = 2 ]'
echo "pas un CSV Play" >"$W/bad.csv"
ARGS=("${BASE[@]}" --langs en-US --data-safety "$W/bad.csv"); run
check "CSV de sécurité des données invalide : code 2" '[ "$(rc)" = 2 ] && grep -q "not a Play data safety CSV" "$W/err"'
cp -r "$P" "$W/long"; printf '%031d\n' 0 >"$W/long/listings/de-DE/title.txt"
ARGS=(--package tech.dmconsulting.dixi --play-dir "$W/long" --langs de-DE); run
check "titre de 31 caractères : code 2" '[ "$(rc)" = 2 ] && grep -q "31 characters, Play allows 1 to 30" "$W/err"'
cp -r "$P" "$W/size"; cp "$P/listings/en-US/graphics/feature-graphic/f.png" "$W/size/listings/en-US/graphics/icon/icon.png"
ARGS=(--package tech.dmconsulting.dixi --play-dir "$W/size" --langs en-US); run
check "icône à 1024x500 : code 2" '[ "$(rc)" = 2 ] && grep -q "1024x500, Play wants 512x512" "$W/err"'

# 2. Fausse API Play : un état publié, une copie par edit, commit = copie dans l'état publié.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$W/sa.pem" 2>/dev/null
cat >"$W/fake_play.py" <<'EOF'
import copy, hashlib, json, re, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
W = sys.argv[1]
PKG = "/androidpublisher/v3/applications/tech.dmconsulting.dixi"
UP = "/upload" + PKG
live = {"details": {"defaultLanguage": "en-US", "contactEmail": "old@example.com"}, "listings": {}, "images": {}}
edits, n = {}, [0]
def log(line): open(W + "/calls", "a").write(line + "\n")
def flag(name):
    try: return open(W + "/" + name).read().strip()
    except OSError: return ""
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def body(self): return self.rfile.read(int(self.headers.get("Content-Length", 0)))
    def route(self, method):
        if self.path == "/token" and method == "POST":
            self.body(); log("token"); return self.reply(200, {"access_token": "tok-test"})
        if self.headers.get("Authorization") != "Bearer tok-test":
            return self.reply(401, {"error": {"status": "UNAUTHENTICATED", "message": "bad token"}})
        path = self.path.split("?")[0]
        if method == "POST" and path == PKG + "/edits":
            n[0] += 1; e = "e%d" % n[0]; edits[e] = copy.deepcopy(live); log("insert " + e)
            return self.reply(200, {"id": e})
        if method == "POST" and path == PKG + "/dataSafety":
            open(W + "/safety_sent", "wb").write(self.body()); log("dataSafety")
            return self.reply(200, {})
        m = re.fullmatch(r"(?:/upload)?" + re.escape(PKG) + r"/edits/(e\d+)(.*)", path)
        if not m or m.group(1) not in edits:
            return self.reply(404, {"error": {"message": "unknown " + path}})
        e, rest, st = m.group(1), m.group(2), edits[m.group(1)]
        if rest == "" and method == "DELETE":
            del edits[e]; log("delete " + e); return self.reply(204)
        if rest == ":validate":
            log("validate " + e); return self.reply(200, {"id": e})
        if rest == ":commit":
            live.clear(); live.update(st); del edits[e]; log("COMMIT " + e); return self.reply(200, {"id": e})
        if rest == "/details":
            if method == "GET": log("details.get"); return self.reply(200, st["details"])
            st["details"] = json.loads(self.body()); log("details.update"); return self.reply(200, st["details"])
        if rest == "/listings" and method == "GET":
            return self.reply(200, {"listings": list(st["listings"].values())})
        lm = re.fullmatch(r"/listings/([A-Za-z-]+)(?:/(\w+))?", rest)
        if lm and not lm.group(2):
            lang = lm.group(1)
            if method == "GET":
                if lang not in st["listings"]: return self.reply(404, {"error": {"message": "not found"}})
                return self.reply(200, st["listings"][lang])
            if flag("deny_update") == "1":
                self.body(); log("listings.update DENIED")
                return self.reply(403, {"error": {"code": 403, "status": "PERMISSION_DENIED",
                                                  "message": "The caller does not have permission"}})
            st["listings"][lang] = json.loads(self.body()); log("listings.update " + lang)
            return self.reply(200, st["listings"][lang])
        if lm:
            key = lm.group(1) + "/" + lm.group(2); imgs = st["images"].setdefault(key, [])
            if method == "GET":
                return self.reply(200, {"images": [{"id": str(i), "sha256": s} for i, s in enumerate(imgs)]})
            if method == "DELETE":
                st["images"][key] = []; log("deleteall " + key); return self.reply(200, {"deleted": []})
            if method == "POST" and self.path.startswith(UP) and self.headers.get("Content-Type") == "image/png":
                imgs.append(hashlib.sha256(self.body()).hexdigest()); log("upload " + key)
                return self.reply(200, {"image": {"sha256": imgs[-1]}})
        self.reply(404, {"error": {"message": "unknown " + method + " " + path}})
    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_DELETE(self): self.route("DELETE")
s = HTTPServer(("127.0.0.1", 0), H)
open(W + "/port", "w").write(str(s.server_port))
s.serve_forever()
EOF
python3 -I "$W/fake_play.py" "$W" & server=$!
for _ in $(seq 50); do [ -s "$W/port" ] && break; sleep 0.1; done
port=$(cat "$W/port")
SA=$(python3 -I -c 'import json,sys; print(json.dumps({"type": "service_account", "client_email": "test@example.iam.gserviceaccount.com",
    "private_key": open(sys.argv[1]).read(), "token_uri": "http://127.0.0.1:" + sys.argv[2] + "/token"}))' "$W/sa.pem" "$port")
ENV=(PLAY_SERVICE_ACCOUNT_JSON="$SA" PLAY_API_ROOT="http://127.0.0.1:$port")
calls() { tr "\n" " " <"$W/calls"; }

ARGS=("${BASE[@]}" --langs en-US); run PLAY_SERVICE_ACCOUNT_JSON="$SA" PLAY_API_ROOT="https://evil.example.com"
check "PLAY_API_ROOT hors 127.0.0.1 : code 2, aucun jeton demandé" '[ "$(rc)" = 2 ] && grep -q "only for the local fake API" "$W/err"'
ARGS=("${BASE[@]}" --langs en-US); run PLAY_SERVICE_ACCOUNT_JSON="$SA"
check "token_uri de la clé hors Google : code 4, refusé" '[ "$(rc)" = 4 ] && grep -q "token_uri of PLAY_SERVICE_ACCOUNT_JSON is not Google" "$W/err"'

: >"$W/calls"; ARGS=("${BASE[@]}" --langs en-US,fr-FR,de-DE --data-safety "$W/safety.csv"); run "${ENV[@]}"
check "essai à blanc : code 0" '[ "$(rc)" = 0 ]'
check "essai à blanc : tout écrit dans l'edit, validé puis supprimé, jamais commité" \
    'calls | grep -q "details.update listings.update en-US upload en-US/featureGraphic" && calls | grep -q "validate e1 delete e1 $" && ! calls | grep -q COMMIT'
check "essai à blanc : 2 captures par langue, aucun deleteall sur une fiche vide" '[ "$(grep -c "upload fr-FR/phoneScreenshots" "$W/calls")" = 2 ] && ! grep -q deleteall "$W/calls"'
check "essai à blanc : sécurité des données vérifiée mais pas envoyée" '[ ! -e "$W/safety_sent" ] && grep -q "1 answer row(s) checked locally, not sent" "$W/out"'

ARGS=("${BASE[@]}" --langs en-US,fr-FR,de-DE --verify); run "${ENV[@]}"
check "--verify après l'essai à blanc : rien n'a changé chez Play (code 1)" '[ "$(rc)" = 1 ] && grep -q "details.contactEmail: Play has '\''old@example.com'\''" "$W/out"'

: >"$W/calls"; ARGS=("${BASE[@]}" --langs en-US,fr-FR,de-DE --commit --data-safety "$W/safety.csv"); run "${ENV[@]}"
check "--commit : code 0 et relecture OK" '[ "$(rc)" = 0 ] && grep -q "read back: OK, Play matches the repo" "$W/out"'
check "--commit : validate, commit, sécurité des données, puis relecture sur un nouvel edit" \
    'calls | grep -qE "validate e3 COMMIT e3 insert e4 .*delete e4 dataSafety $"'
check "--commit : CSV envoyé tel quel dans safetyLabels" 'python3 -I -c "import json,sys; sys.exit(json.load(open(sys.argv[1]))[\"safetyLabels\"]!=open(sys.argv[2]).read())" "$W/safety_sent" "$W/safety.csv"'

: >"$W/calls"; ARGS=("${BASE[@]}" --langs en-US,fr-FR,de-DE --commit); run "${ENV[@]}"
check "second --commit : aucun envoi, tout inchangé" '[ "$(rc)" = 0 ] && ! grep -qE "update|upload|deleteall" "$W/calls" && grep -q "en-US.phoneScreenshots: 2 image(s) unchanged" "$W/out"'

: >"$W/calls"; python3 -I - "$P" <<'EOF'
import sys; p = sys.argv[1] + "/listings/en-US/graphics/phone-screenshots/"
a, b = open(p + "01.png", "rb").read(), open(p + "02.png", "rb").read()
open(p + "01.png", "wb").write(b); open(p + "02.png", "wb").write(a)
EOF
ARGS=("${BASE[@]}" --langs en-US --commit); run "${ENV[@]}"
check "captures réordonnées : deleteall puis 2 envois, le reste inchangé" \
    '[ "$(rc)" = 0 ] && calls | grep -q "deleteall en-US/phoneScreenshots upload en-US/phoneScreenshots upload en-US/phoneScreenshots" && ! grep -q "listings.update" "$W/calls"'

: >"$W/calls"; ARGS=("${BASE[@]}" --langs en-US --read); run "${ENV[@]}"
check "--read : état lu, edit supprimé, jamais commité" '[ "$(rc)" = 0 ] && grep -q "en-US.phoneScreenshots: 2 image(s)" "$W/out" && grep -q "languages on Play: de-DE, en-US, fr-FR" "$W/out" && calls | grep -q "delete e" && ! calls | grep -q COMMIT'

echo "Longue nouvelle" >"$P/listings/de-DE/full-description.txt"; echo 1 >"$W/deny_update"; : >"$W/calls"
ARGS=("${BASE[@]}" --langs de-DE --commit); run "${ENV[@]}"
check "Google refuse : code 6 et erreur exacte" '[ "$(rc)" = 6 ] && grep -q "edits.listings.update: HTTP 403 PERMISSION_DENIED The caller does not have permission" "$W/err"'
check "Google refuse : edit supprimé, jamais commité" 'calls | grep -q "DENIED delete e" && ! calls | grep -q COMMIT'

check "aucun secret affiché" '! grep -qE "tok-test|PRIVATE KEY" "$W/out" "$W/err"'
check "dossier privé effacé" '[ -z "$(find "$W" -maxdepth 1 -name "play-listing.*")" ]'

echo "$fails échec(s)"
[ "$fails" = 0 ]
