#!/usr/bin/env bash
# DMC-330: builds the release AAB signed with the upload key and publishes it on a Play track,
# from the NAS (not GitHub Actions: CI minutes are capped, DMC-131).
#
# Usage: scripts/play-publish.sh [internal|alpha|beta|production] [--release-status draft|completed] [--listing]
#   internal (default) = internal testing, alpha = closed testing. Agents publish there alone.
#   beta (open testing) and production are public: they need MICKA_APPROVED_ISSUE=DMC-<n>,
#   the task holding Micka's written approval of this release.
#   --release-status: draft by default (Play only takes drafts until the app is published).
#   --listing: also publishes the store listing from app/src/main/play.
#
# Secrets come from the environment (Paperclip secrets) and are never printed:
#   <PREFIX>_UPLOAD_KEYSTORE_B64, <PREFIX>_UPLOAD_KEYSTORE_PASSWORD, <PREFIX>_UPLOAD_KEY_ALIAS,
#   <PREFIX>_UPLOAD_KEY_PASSWORD (PREFIX = $PLAY_SECRET_PREFIX, default VOXBULLE), and
#   PLAY_SERVICE_ACCOUNT_JSON (content of the service account key). The keystore and the key are
#   written to a private temp dir, deleted on exit.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "play-publish: $2" >&2; exit "$1"; }

TRACK=internal; STATUS=draft; LISTING=0
while [ $# -gt 0 ]; do
    case $1 in
        internal|alpha|beta|production) TRACK=$1 ;;
        --release-status) STATUS=${2:-}; shift ;;
        --listing) LISTING=1 ;;
        *) die 2 "unknown argument '$1'" ;;
    esac
    shift
done
case $STATUS in draft|completed) ;; *) die 2 "--release-status must be draft or completed" ;; esac

if [ "$TRACK" = beta ] || [ "$TRACK" = production ]; then
    [[ ${MICKA_APPROVED_ISSUE:-} =~ ^DMC-[0-9]+$ ]] ||
        die 3 "track '$TRACK' is public: set MICKA_APPROVED_ISSUE=DMC-<n>, the task with Micka's written approval"
    echo "play-publish: track $TRACK approved on $MICKA_APPROVED_ISSUE"
fi

P=${PLAY_SECRET_PREFIX:-VOXBULLE}
KS_B64=${P}_UPLOAD_KEYSTORE_B64 KS_PASS=${P}_UPLOAD_KEYSTORE_PASSWORD
KEY_ALIAS=${P}_UPLOAD_KEY_ALIAS KEY_PASS=${P}_UPLOAD_KEY_PASSWORD
missing=()
for v in "$KS_B64" "$KS_PASS" "$KEY_ALIAS" "$KEY_PASS" PLAY_SERVICE_ACCOUNT_JSON; do
    [ -n "${!v:-}" ] || missing+=("$v")
done
[ ${#missing[@]} -eq 0 ] || die 4 "missing ${missing[*]}"

# Publish a commit, not a working tree: the SHA below must be what went to Play.
[ -z "$(git status --porcelain -- app gradle build.gradle.kts settings.gradle.kts gradle.properties)" ] ||
    die 5 "uncommitted changes under app/ or the build files"

umask 077
TMP=$(mktemp -d "${PAPERCLIP_RUN_SCRATCH_DIR:-${TMPDIR:-/tmp}}/play-publish.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
printf '%s' "${!KS_B64}" | base64 -d > "$TMP/upload.keystore" 2>/dev/null ||
    die 4 "$KS_B64 is not valid base64"
printf '%s' "$PLAY_SERVICE_ACCOUNT_JSON" > "$TMP/play.json"
python3 -I - "$TMP/play.json" <<'EOF' || die 4 "PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON)"
import json, sys
try:
    key = json.load(open(sys.argv[1]))
except ValueError:
    sys.exit(1)
sys.exit(0 if isinstance(key, dict) and key.get("type") == "service_account" else 1)
EOF

export UPLOAD_KEYSTORE_PATH="$TMP/upload.keystore"
export UPLOAD_KEYSTORE_PASSWORD="${!KS_PASS}" UPLOAD_KEY_ALIAS="${!KEY_ALIAS}" UPLOAD_KEY_PASSWORD="${!KEY_PASS}"
export PLAY_SERVICE_ACCOUNT_FILE="$TMP/play.json"
export JAVA_HOME=${JAVA_HOME:-/opt/android/jdk}
# Gradle needs the paths above, not the raw secrets.
unset "$KS_B64" PLAY_SERVICE_ACCOUNT_JSON

tasks=(:app:publishReleaseBundle --track "$TRACK" --release-status "$STATUS")
[ "$LISTING" = 0 ] || tasks+=(:app:publishReleaseListing)
echo "play-publish: $(git rev-parse --short HEAD) -> $TRACK ($STATUS)"
sh ./gradlew --no-daemon --console=plain "${tasks[@]}"

AAB=app/build/outputs/bundle/release/app-release.aab
CODES=$(find app/build/intermediates -path '*release*' -name available-version-codes.txt 2>/dev/null | head -1)
echo "play-publish: done. commit $(git rev-parse HEAD), track $TRACK, status $STATUS"
[ -z "$CODES" ] || echo "play-publish: versionCode $(head -1 "$CODES")"
sha256sum "$AAB"
