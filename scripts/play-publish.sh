#!/usr/bin/env bash
# DMC-330: builds the release AAB signed with the upload key and publishes it on a Play test
# track, from the NAS (not GitHub Actions: CI minutes are capped, DMC-131).
#
# Usage:
#   scripts/play-publish.sh [internal|alpha] [--release-status draft|completed]
#       internal (default) = internal testing, alpha = closed testing.
#       --release-status: draft by default (Play only takes drafts until the app is published).
#   scripts/play-publish.sh --status
#       Read only (DMC-500): lists the releases of every track. edits.insert, tracks.list, then
#       edits.delete, never edits.commit. Needs only PLAY_SERVICE_ACCOUNT_JSON.
#
# Refused, with no way around it (Micka's decision of 11/11/2026 on DMC-442, DMC-500):
# the public tracks beta and production and any deletion. The store listing is not sent by
# Gradle from here: it goes through scripts/play-listing.py (Micka, 15/11, DMC-567).
#
# Secrets come from the environment (Paperclip secrets) and are never printed:
#   <PREFIX>_UPLOAD_KEYSTORE_B64, <PREFIX>_UPLOAD_KEYSTORE_PASSWORD, <PREFIX>_UPLOAD_KEY_ALIAS,
#   <PREFIX>_UPLOAD_KEY_PASSWORD (PREFIX = $PLAY_SECRET_PREFIX, default VOXBULLE, kept after the
#   rename to Dixi: the upload key does not depend on the name, DMC-383), and
#   PLAY_SERVICE_ACCOUNT_JSON (content of the service account key). The keystore, the key and
#   the access token are written to a private temp dir, deleted on exit.
# PLAY_API_ROOT only exists for scripts/tests/play-publish-test.sh (local fake Play API).
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "play-publish: $2" >&2; exit "$1"; }

MODE=publish TRACK=internal STATUS=draft
while [ $# -gt 0 ]; do
    case $1 in
        internal|alpha) TRACK=$1 ;;
        beta|production) die 3 "track '$1' is public: refused, it needs Micka's written approval (DMC-500)" ;;
        --listing) die 3 "--listing refused here: the store listing goes through scripts/play-listing.py (DMC-567)" ;;
        --status) MODE=status ;;
        --release-status) STATUS=${2:-}; shift ;;
        *) die 2 "unknown argument '$1' (allowed: internal, alpha, --release-status, --status)" ;;
    esac
    shift
done
case $STATUS in draft|completed) ;; *) die 2 "--release-status must be draft or completed" ;; esac

PKG=$(sed -n 's/^ *applicationId = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
[[ $PKG == tech.dmconsulting.* ]] || die 2 "applicationId '$PKG' is not a DM Consulting package"

[ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ] || die 4 "missing PLAY_SERVICE_ACCOUNT_JSON"
umask 077
TMP=$(mktemp -d "${PAPERCLIP_RUN_SCRATCH_DIR:-${TMPDIR:-/tmp}}/play-publish.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$TMP/play.json"
unset PLAY_SERVICE_ACCOUNT_JSON
# Checks the key and, for --status, extracts the private key and the two public fields.
python3 -I - "$TMP/play.json" "$TMP" <<'EOF' || die 4 "PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON)"
import json, sys
try:
    key = json.load(open(sys.argv[1]))
except ValueError:
    sys.exit(1)
if not (isinstance(key, dict) and key.get("type") == "service_account"
        and key.get("private_key") and key.get("client_email")):
    sys.exit(1)
d = sys.argv[2]
open(d + "/key.pem", "w").write(key["private_key"])
open(d + "/email", "w").write(key["client_email"])
open(d + "/token_uri", "w").write(key.get("token_uri") or "https://oauth2.googleapis.com/token")
EOF

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Prints "HTTP <code> <Play message>" for a failed call, without the request (no token).
play_error() {
    python3 -I - "$1" <<'EOF'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    e = d.get("error", d)
    if isinstance(e, dict):
        print(e.get("status", ""), e.get("message") or json.dumps(e))
    else:
        print(e, d.get("error_description", ""))
except Exception:
    print(open(sys.argv[1], errors="replace").read()[:500])
EOF
}

play_status() {
    # Globals, not locals: the EXIT trap below reads them after the function has returned.
    API=${PLAY_API_ROOT:-https://androidpublisher.googleapis.com}/androidpublisher/v3/applications/$PKG
    local now code
    now=$(date +%s)
    printf '{"iss":"%s","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"%s","iat":%s,"exp":%s}' \
        "$(cat "$TMP/email")" "$(cat "$TMP/token_uri")" "$now" "$((now + 600))" | b64url > "$TMP/claims"
    printf '{"alg":"RS256","typ":"JWT"}' | b64url > "$TMP/head"
    printf '%s.%s' "$(cat "$TMP/head")" "$(cat "$TMP/claims")" > "$TMP/jwt"
    openssl dgst -sha256 -sign "$TMP/key.pem" -binary "$TMP/jwt" | b64url > "$TMP/sig" ||
        die 4 "the private key of PLAY_SERVICE_ACCOUNT_JSON cannot sign"
    printf '.%s' "$(cat "$TMP/sig")" >> "$TMP/jwt"
    # Secrets go through files (-d @, -H @), never through the command line.
    code=$(curl -sS --max-time 60 -o "$TMP/token.json" -w '%{http_code}' \
        -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer --data-urlencode "assertion@$TMP/jwt" \
        "$(cat "$TMP/token_uri")") || die 6 "token endpoint unreachable"
    [[ $code == 2?? ]] || die 6 "token refused: HTTP $code $(play_error "$TMP/token.json")"
    python3 -I -c 'import json,sys; print("Authorization: Bearer " + json.load(open(sys.argv[1]))["access_token"])' \
        "$TMP/token.json" > "$TMP/auth" || die 6 "no access_token in the token answer"

    code=$(curl -sS --max-time 60 -o "$TMP/edit.json" -w '%{http_code}' -X POST -H @"$TMP/auth" \
        "$API/edits") || die 6 "Play API unreachable (edits.insert)"
    [[ $code == 2?? ]] || die 6 "edits.insert: HTTP $code $(play_error "$TMP/edit.json")"
    EDIT=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$TMP/edit.json") ||
        die 6 "no edit id in the edits.insert answer"
    # The edit is always dropped, even when tracks.list fails: nothing is ever committed.
    trap 'curl -sS --max-time 60 -o /dev/null -X DELETE -H @"$TMP/auth" "$API/edits/$EDIT" || true; rm -rf "$TMP"' EXIT

    code=$(curl -sS --max-time 60 -o "$TMP/tracks.json" -w '%{http_code}' -H @"$TMP/auth" \
        "$API/edits/$EDIT/tracks") || die 6 "Play API unreachable (tracks.list)"
    [[ $code == 2?? ]] || die 6 "tracks.list: HTTP $code $(play_error "$TMP/tracks.json")"
    echo "play-publish: $PKG, read only (edit dropped, nothing committed)"
    python3 -I - "$TMP/tracks.json" <<'EOF'
import json, sys
order = ["internal", "alpha", "beta", "production"]
tracks = {t["track"]: t.get("releases", []) for t in json.load(open(sys.argv[1])).get("tracks", [])}
codes = []
for name in order + sorted(set(tracks) - set(order)):
    releases = tracks.get(name, [])
    if not releases:
        print(f"{name}: no release")
    for r in releases:
        vc = r.get("versionCodes", [])
        codes += [int(c) for c in vc]
        print(f"{name}: {r.get('status', '?')} versionCodes={','.join(vc) or '-'} name={r.get('name', '-')}")
print(f"highest versionCode: {max(codes) if codes else 'none'}")
EOF
}

if [ "$MODE" = status ]; then
    play_status
    exit 0
fi

P=${PLAY_SECRET_PREFIX:-VOXBULLE}
KS_B64=${P}_UPLOAD_KEYSTORE_B64 KS_PASS=${P}_UPLOAD_KEYSTORE_PASSWORD
KEY_ALIAS=${P}_UPLOAD_KEY_ALIAS KEY_PASS=${P}_UPLOAD_KEY_PASSWORD
missing=()
for v in "$KS_B64" "$KS_PASS" "$KEY_ALIAS" "$KEY_PASS"; do
    [ -n "${!v:-}" ] || missing+=("$v")
done
[ ${#missing[@]} -eq 0 ] || die 4 "missing ${missing[*]}"

# Publish a commit, not a working tree: the SHA below must be what went to Play.
[ -z "$(git status --porcelain -- app gradle build.gradle.kts settings.gradle.kts gradle.properties)" ] ||
    die 5 "uncommitted changes under app/ or the build files"

printf '%s' "${!KS_B64}" | base64 -d > "$TMP/upload.keystore" 2>/dev/null ||
    die 4 "$KS_B64 is not valid base64"
rm -f "$TMP/key.pem"

export UPLOAD_KEYSTORE_PATH="$TMP/upload.keystore"
export UPLOAD_KEYSTORE_PASSWORD="${!KS_PASS}" UPLOAD_KEY_ALIAS="${!KEY_ALIAS}" UPLOAD_KEY_PASSWORD="${!KEY_PASS}"
export PLAY_SERVICE_ACCOUNT_FILE="$TMP/play.json"
export JAVA_HOME=${JAVA_HOME:-/opt/android/jdk}
# Gradle needs the paths above, not the raw secrets.
unset "$KS_B64"

# Only the bundle task: no listing, no deletion task is ever run from here.
echo "play-publish: $(git rev-parse --short HEAD) -> $TRACK ($STATUS)"
sh ./gradlew --no-daemon --console=plain :app:publishReleaseBundle --track "$TRACK" --release-status "$STATUS"

AAB=app/build/outputs/bundle/release/app-release.aab
CODES=$(find app/build/intermediates -path '*release*' -name available-version-codes.txt 2>/dev/null | head -1)
echo "play-publish: done. commit $(git rev-parse HEAD), track $TRACK, status $STATUS"
[ -z "$CODES" ] || echo "play-publish: versionCode $(head -1 "$CODES")"
sha256sum "$AAB"
