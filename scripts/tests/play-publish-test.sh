#!/usr/bin/env bash
# Tests de scripts/play-publish.sh sans Google ni Gradle :
#   - refus purs : beta, production, --listing, argument inconnu, avant toute lecture de secret ;
#   - --status contre une fausse API Play locale : jeton JWT signé (vérifié par openssl avec la
#     clé publique), edits.insert -> tracks.list -> edits.delete, jamais edits.commit, et
#     edits.delete même quand tracks.list échoue.
# Usage : scripts/tests/play-publish-test.sh   (aucun réseau sortant, tout reste sur 127.0.0.1)
# check évalue ses conditions plus tard : guillemets simples voulus.
# shellcheck disable=SC2016
set -uo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
W=$(mktemp -d "${PAPERCLIP_RUN_SCRATCH_DIR:-${TMPDIR:-/tmp}}/play-test.XXXXXX")
fails=0 server=
trap '[ -z "$server" ] || kill "$server" 2>/dev/null; rm -rf "$W"' EXIT
check() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
run() { (cd "$root" && env -i PATH="$PATH" HOME="$W" PAPERCLIP_RUN_SCRATCH_DIR="$W" "$@" bash scripts/play-publish.sh "${ARGS[@]}") >"$W/out" 2>"$W/err"; echo $? >"$W/rc"; }

# 1. Refus, sans aucun secret dans l'environnement.
for a in beta production --listing "alpha --listing" "internal beta" "--status production"; do
    read -ra ARGS <<<"$a"; run
    check "refus '$a' (code 3)" '[ "$(cat "$W/rc")" = 3 ] && grep -q "refused" "$W/err"'
done
for a in --delete "internal --release-status halted"; do
    read -ra ARGS <<<"$a"; run
    check "refus '$a' (code 2)" '[ "$(cat "$W/rc")" = 2 ]'
done
ARGS=(--status); run
check "--status sans clé : code 4" '[ "$(cat "$W/rc")" = 4 ] && grep -q "missing PLAY_SERVICE_ACCOUNT_JSON" "$W/err"'

# 2. Fausse API Play.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$W/sa.pem" 2>/dev/null
openssl pkey -in "$W/sa.pem" -pubout -out "$W/sa.pub"
cat >"$W/fake_play.py" <<'EOF'
import base64, json, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
W = sys.argv[1]
PKG = "/androidpublisher/v3/applications/tech.dmconsulting.dixi"
def log(line):
    open(W + "/calls", "a").write(line + "\n")
def fail_list():
    try:
        return open(W + "/fail_list").read().strip() == "1"
    except OSError:
        return False
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def authed(self):
        if self.headers.get("Authorization") != "Bearer tok-test":
            self.reply(401, {"error": {"code": 401, "message": "bad token", "status": "UNAUTHENTICATED"}})
            return False
        return True
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        if self.path == "/token":
            form = urllib.parse.parse_qs(body)
            open(W + "/assertion", "w").write(form["assertion"][0])
            log("token " + form["grant_type"][0])
            return self.reply(200, {"access_token": "tok-test", "expires_in": 3600})
        if not self.authed(): return
        if self.path == PKG + "/edits":
            log("insert")
            return self.reply(200, {"id": "e42"})
        if self.path.endswith(":commit"):
            log("COMMIT")
        self.reply(404, {"error": {"message": "unknown " + self.path}})
    def do_GET(self):
        if not self.authed(): return
        if self.path == PKG + "/edits/e42/tracks":
            log("list")
            if fail_list():
                return self.reply(403, {"error": {"code": 403, "status": "PERMISSION_DENIED",
                                                  "message": "The caller does not have permission"}})
            return self.reply(200, {"kind": "androidpublisher#tracksListResponse", "tracks": [
                {"track": "internal", "releases": [{"name": "1.0.0", "versionCodes": ["3"], "status": "draft"}]},
                {"track": "alpha"}, {"track": "beta"}, {"track": "production"}]})
        self.reply(404, {"error": {"message": "unknown " + self.path}})
    def do_DELETE(self):
        if not self.authed(): return
        log("delete " + self.path.rsplit("/", 1)[1])
        self.reply(204)
s = HTTPServer(("127.0.0.1", 0), H)
open(W + "/port", "w").write(str(s.server_port))
s.serve_forever()
EOF
python3 -I "$W/fake_play.py" "$W" & server=$!
for _ in $(seq 50); do [ -s "$W/port" ] && break; sleep 0.1; done
port=$(cat "$W/port")
SA=$(python3 -I -c 'import json,sys; print(json.dumps({"type": "service_account", "client_email": "test@example.iam.gserviceaccount.com",
    "private_key": open(sys.argv[1]).read(), "token_uri": "http://127.0.0.1:" + sys.argv[2] + "/token"}))' "$W/sa.pem" "$port")

ARGS=(--status); run PLAY_SERVICE_ACCOUNT_JSON="$SA" PLAY_API_ROOT="http://127.0.0.1:$port"
check "--status : code 0" '[ "$(cat "$W/rc")" = 0 ]'
check "--status : ordre token, insert, list, delete" '[ "$(tr "\n" " " <"$W/calls")" = "token urn:ietf:params:oauth:grant-type:jwt-bearer insert list delete e42 " ]'
check "--status : piste interne lue" 'grep -qx "internal: draft versionCodes=3 name=1.0.0" "$W/out"'
check "--status : pistes vides listées" 'grep -qx "production: no release" "$W/out" && grep -qx "highest versionCode: 3" "$W/out"'
IFS=. read -r h c s <"$W/assertion"
pad() { local x=$1; while [ $(( ${#x} % 4 )) -ne 0 ]; do x+='='; done; printf '%s' "$x" | tr -- '-_' '+/' | base64 -d; }
printf '%s.%s' "$h" "$c" >"$W/signed"; pad "$s" >"$W/sig"
check "JWT : signature RS256 valide" 'openssl dgst -sha256 -verify "$W/sa.pub" -signature "$W/sig" "$W/signed" >/dev/null'
check "JWT : portée androidpublisher et audience token_uri" 'pad "$c" | python3 -I -c "import json,sys; c=json.load(sys.stdin); sys.exit(0 if c[\"scope\"]==\"https://www.googleapis.com/auth/androidpublisher\" and c[\"aud\"].endswith(\"/token\") and c[\"exp\"]-c[\"iat\"]==600 else 1)"'
check "aucun secret affiché" '! grep -qE "tok-test|PRIVATE KEY|$h" "$W/out" "$W/err"'
check "dossier privé effacé" '[ -z "$(find "$W" -maxdepth 1 -name "play-publish.*")" ]'

: >"$W/calls"; echo 1 >"$W/fail_list"
ARGS=(--status); run PLAY_SERVICE_ACCOUNT_JSON="$SA" PLAY_API_ROOT="http://127.0.0.1:$port"
check "tracks.list refusé : code 6 et erreur exacte de Play" '[ "$(cat "$W/rc")" = 6 ] && grep -q "tracks.list: HTTP 403 PERMISSION_DENIED The caller does not have permission" "$W/err"'
check "tracks.list refusé : edit quand même supprimé, jamais commité" '[ "$(tr "\n" " " <"$W/calls")" = "token urn:ietf:params:oauth:grant-type:jwt-bearer insert list delete e42 " ]'

ARGS=(--status); run PLAY_SERVICE_ACCOUNT_JSON='{"type":"authorized_user"}'
check "clé qui n'est pas un compte de service : code 4" '[ "$(cat "$W/rc")" = 4 ]'

echo "$fails échec(s)"
[ "$fails" = 0 ]
