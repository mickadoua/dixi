#!/usr/bin/env python3
"""Fills the Play Console store listing through the Android Publisher API v3, from the repo.

What it writes (and nothing else): store listing texts per language, listing images, contact
details (edits.details) and, with --commit only, the data safety form (applications.dataSafety).
No track, no release, no app deletion: those stay in play-publish.sh and its refusals.

Usage: python3 -I scripts/play-listing.py --langs en-US,fr-FR [mode] [options]
  --langs en-US,fr-FR      languages to send (required: an unreviewed translation never leaves by default)
  --read                   read only: prints what Play holds (edit opened, then dropped)
  --verify                 read only: compares Play with the repo, exit 1 if anything differs
  (default) dry run        writes into an edit, edits.validate, then drops the edit: nothing is saved
  --commit                 writes, edits.validate, edits.commit, then --verify on a fresh edit
  --data-safety FILE.csv   data safety CSV (format of the Play Console export). Checked in a dry
                           run, sent only with --commit: this call is not part of an edit.
  --package PKG            default: applicationId read in app/build.gradle.kts (tech.dmconsulting.*)
  --play-dir DIR           default: app/src/main/play of this repo (Gradle Play Publisher layout)

Repo layout read (Gradle Play Publisher's, so the same files serve both tools):
  <play-dir>/contact-email.txt, contact-website.txt, contact-phone.txt, default-language.txt
  <play-dir>/listings/<lang>/title.txt, short-description.txt, full-description.txt
  <play-dir>/listings/<lang>/graphics/<type>/*.png  (icon, feature-graphic, phone-screenshots, ...)
Images: Play keeps no file name, so a type is replaced as a whole (images.deleteall then upload in
file name order) only when the ordered list of SHA-256 differs from the repo's.

Secret: PLAY_SERVICE_ACCOUNT_JSON (service account key), never printed. The private key is
written to a private temp dir, deleted on exit. PLAY_API_ROOT only exists for the local fake API
of scripts/tests/play-listing-test.sh.
"""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from base64 import urlsafe_b64encode

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.environ.get("PLAY_API_ROOT", "https://androidpublisher.googleapis.com")
GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"
# The key and the token only ever go to Google, or to the local fake API of the tests.
LOCAL = re.compile(r"http://127\.0\.0\.1:\d+(/.*)?")
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
TEXT_LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
TEXT_FILES = {"title": "title.txt", "shortDescription": "short-description.txt",
              "fullDescription": "full-description.txt"}
CONTACT_FILES = {"contactEmail": "contact-email.txt", "contactWebsite": "contact-website.txt",
                 "contactPhone": "contact-phone.txt", "defaultLanguage": "default-language.txt"}
# Gradle Play Publisher folder -> API image type, with the size rules checked before any call.
IMAGE_TYPES = {
    "icon": ("icon", 1, (512, 512)),
    "feature-graphic": ("featureGraphic", 1, (1024, 500)),
    "tv-banner": ("tvBanner", 1, (1280, 720)),
    "phone-screenshots": ("phoneScreenshots", 8, None),
    "tablet-screenshots": ("sevenInchScreenshots", 8, None),
    "large-tablet-screenshots": ("tenInchScreenshots", 8, None),
    "tv-screenshots": ("tvScreenshots", 8, None),
    "wear-screenshots": ("wearScreenshots", 8, None),
}


class Fail(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


def say(msg):
    print("play-listing: " + msg, flush=True)


def b64url(data):
    return urlsafe_b64encode(data).rstrip(b"=").decode()


# ---------- repo side ----------

def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise Fail(2, f"{path}: not a PNG file")
    return struct.unpack(">II", head[16:24])


def check_image(path, fixed):
    w, h = png_size(path)
    if fixed and (w, h) != fixed:
        raise Fail(2, f"{path}: {w}x{h}, Play wants {fixed[0]}x{fixed[1]}")
    if not fixed and not (320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)):
        raise Fail(2, f"{path}: {w}x{h}, Play wants sides 320-3840 px and a ratio of at most 2:1")


def load_repo(play_dir, langs):
    details = {}
    for field, name in CONTACT_FILES.items():
        p = os.path.join(play_dir, name)
        if os.path.isfile(p) and read_text(p):
            details[field] = read_text(p)
    listings = {}
    for lang in langs:
        d = os.path.join(play_dir, "listings", lang)
        if not os.path.isdir(d):
            raise Fail(2, f"no listing folder {d}")
        texts = {}
        for field, name in TEXT_FILES.items():
            p = os.path.join(d, name)
            if not os.path.isfile(p):
                raise Fail(2, f"missing {p}")
            texts[field] = read_text(p)
            if not texts[field] or len(texts[field]) > TEXT_LIMITS[field]:
                raise Fail(2, f"{p}: {len(texts[field])} characters, Play allows 1 to {TEXT_LIMITS[field]}")
        images = {}
        g = os.path.join(d, "graphics")
        for folder in sorted(os.listdir(g)) if os.path.isdir(g) else []:
            if folder not in IMAGE_TYPES:
                raise Fail(2, f"unknown graphics folder {os.path.join(g, folder)}")
            kind, most, fixed = IMAGE_TYPES[folder]
            files = sorted(os.path.join(g, folder, f) for f in os.listdir(os.path.join(g, folder))
                           if f.lower().endswith(".png"))
            if not files or len(files) > most:
                raise Fail(2, f"{os.path.join(g, folder)}: {len(files)} PNG, Play allows 1 to {most}")
            for f in files:
                check_image(f, fixed)
            images[kind] = files
        listings[lang] = {"texts": texts, "images": images}
    return details, listings


def check_data_safety(path):
    text = open(path, encoding="utf-8", newline="").read()  # CRLF kept, as in the Play export
    rows = [r for r in csv.reader(io.StringIO(text)) if any(r)]
    if len(rows) < 2 or not rows[0] or not rows[0][0].startswith("Question ID"):
        raise Fail(2, f"{path}: not a Play data safety CSV (first column must be 'Question ID ...')")
    return text, len(rows) - 1


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


# ---------- Play side ----------

class Play:
    def __init__(self, pkg, key_json, tmp):
        self.api = f"{ROOT}/androidpublisher/v3/applications/{pkg}"
        self.upload = f"{ROOT}/upload/androidpublisher/v3/applications/{pkg}"
        self.edit = None
        self.token = self._token(key_json, tmp)

    def _token(self, key, tmp):
        pem = os.path.join(tmp, "key.pem")
        with open(pem, "w") as f:
            f.write(key["private_key"])
        token_uri = key.get("token_uri") or GOOGLE_TOKEN_URI
        if token_uri != GOOGLE_TOKEN_URI and not (LOCAL.fullmatch(token_uri) and LOCAL.fullmatch(ROOT)):
            raise Fail(4, "token_uri of PLAY_SERVICE_ACCOUNT_JSON is not Google's: refused")
        now = int(time.time())
        head = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        claims = b64url(json.dumps({"iss": key["client_email"], "scope": SCOPE, "aud": token_uri,
                                    "iat": now, "exp": now + 600}).encode())
        signing = f"{head}.{claims}".encode()
        sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", pem, "-binary"], input=signing,
                             capture_output=True)
        os.remove(pem)
        if sig.returncode:
            raise Fail(4, "the private key of PLAY_SERVICE_ACCOUNT_JSON cannot sign")
        body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                       "assertion": f"{head}.{claims}.{b64url(sig.stdout)}"}).encode()
        code, data = self._send("POST", token_uri, body, "application/x-www-form-urlencoded", auth=False)
        if code // 100 != 2 or "access_token" not in data:
            raise Fail(6, f"token refused: HTTP {code} {self.error(data)}")
        return data["access_token"]

    @staticmethod
    def error(data):
        e = data.get("error", data) if isinstance(data, dict) else data
        if isinstance(e, dict):
            return f"{e.get('status', '')} {e.get('message') or json.dumps(e)}".strip()
        return f"{e} {data.get('error_description', '') if isinstance(data, dict) else ''}".strip()

    def _send(self, method, url, body=None, ctype=None, auth=True):
        req = urllib.request.Request(url, data=body, method=method)
        if ctype:
            req.add_header("Content-Type", ctype)
        if auth:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                code, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            code, raw = e.code, e.read()
        except (urllib.error.URLError, OSError) as e:
            raise Fail(6, f"Play API unreachable ({method} {url.split('/applications/')[-1]}): {e}")
        try:
            return code, json.loads(raw) if raw else {}
        except ValueError:
            return code, {"error": raw[:300].decode(errors="replace")}

    def call(self, name, method, path, body=None, ok404=False, upload=None):
        if upload:
            url, data, ctype = f"{self.upload}{path}?uploadType=media", open(upload, "rb").read(), "image/png"
        else:
            url, ctype = self.api + path, "application/json" if body is not None else None
            data = json.dumps(body).encode() if body is not None else None
        code, out = self._send(method, url, data, ctype)
        if ok404 and code == 404:
            return None
        if code // 100 != 2:
            raise Fail(6, f"{name}: HTTP {code} {self.error(out)}")
        return out

    def open_edit(self):
        self.edit = self.call("edits.insert", "POST", "/edits")["id"]
        return f"/edits/{self.edit}"

    def drop_edit(self):
        if self.edit:
            edit, self.edit = self.edit, None
            try:
                self.call("edits.delete", "DELETE", f"/edits/{edit}")
            except Fail as e:
                say(f"warning: {e} (an unused edit expires on its own)")


def remote_state(play, e, langs, kinds):
    details = play.call("edits.details.get", "GET", f"{e}/details")
    listings = {}
    for lang in langs:
        texts = play.call("edits.listings.get", "GET", f"{e}/listings/{lang}", ok404=True) or {}
        images = {}
        for kind in kinds:
            got = play.call("edits.images.list", "GET", f"{e}/listings/{lang}/{kind}")
            images[kind] = [i.get("sha256", "") for i in got.get("images", [])]
        listings[lang] = {"texts": texts, "images": images}
    return details, listings


def diff(local_details, local, remote_details, remote):
    """Lists what differs between the repo and Play, as human lines."""
    out = []
    for f, v in local_details.items():
        if remote_details.get(f, "") != v:
            out.append(f"details.{f}: Play has {remote_details.get(f, '')!r}, repo has {v!r}")
    for lang, item in local.items():
        for f, v in item["texts"].items():
            if remote[lang]["texts"].get(f, "") != v:
                out.append(f"{lang}.{f}: differs (Play {len(remote[lang]['texts'].get(f, ''))} chars, "
                           f"repo {len(v)})")
        for kind, files in item["images"].items():
            if remote[lang]["images"].get(kind, []) != [sha256(p) for p in files]:
                out.append(f"{lang}.{kind}: Play has {len(remote[lang]['images'].get(kind, []))} image(s), "
                           f"repo {len(files)}, or a different content/order")
    return out


def print_state(details, listings):
    say("details: " + json.dumps({k: details.get(k, "") for k in CONTACT_FILES}, ensure_ascii=False))
    for lang, item in listings.items():
        t = item["texts"]
        if not t:
            say(f"{lang}: no listing")
        else:
            say(f"{lang}: title={t.get('title', '')!r} short={len(t.get('shortDescription', ''))} chars "
                f"full={len(t.get('fullDescription', ''))} chars")
        for kind, shas in item["images"].items():
            say(f"{lang}.{kind}: {len(shas)} image(s) {' '.join(s[:8] for s in shas)}".rstrip())


def write(play, e, local_details, local, remote_details, remote):
    changed = 0
    want = {**remote_details, **local_details}
    want = {k: v for k, v in want.items() if k in CONTACT_FILES}
    if any(remote_details.get(k, "") != v for k, v in local_details.items()):
        play.call("edits.details.update", "PUT", f"{e}/details", want)
        say(f"details: written in the edit {json.dumps(local_details, ensure_ascii=False)}")
        changed += 1
    else:
        say("details: unchanged")
    for lang, item in local.items():
        old = remote[lang]["texts"]
        if any(old.get(f, "") != v for f, v in item["texts"].items()):
            body = {k: v for k, v in old.items() if k in ("video",)}
            body.update(language=lang, **item["texts"])
            play.call("edits.listings.update", "PUT", f"{e}/listings/{lang}", body)
            say(f"{lang}: texts written in the edit (title {item['texts']['title']!r})")
            changed += 1
        else:
            say(f"{lang}: texts unchanged")
        for kind, files in item["images"].items():
            if remote[lang]["images"].get(kind, []) == [sha256(p) for p in files]:
                say(f"{lang}.{kind}: {len(files)} image(s) unchanged")
                continue
            if remote[lang]["images"].get(kind):
                play.call("edits.images.deleteall", "DELETE", f"{e}/listings/{lang}/{kind}")
            for p in files:
                play.call("edits.images.upload", "POST", f"{e}/listings/{lang}/{kind}", upload=p)
            say(f"{lang}.{kind}: {len(files)} image(s) uploaded in the edit")
            changed += 1
    return changed


def main(argv):
    ap = argparse.ArgumentParser(prog="play-listing.py", description=__doc__.split("\n")[0])
    ap.add_argument("--langs", required=True)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--read", action="store_true")
    mode.add_argument("--verify", action="store_true")
    mode.add_argument("--commit", action="store_true")
    ap.add_argument("--data-safety")
    ap.add_argument("--package")
    ap.add_argument("--play-dir", default=os.path.join(REPO, "app", "src", "main", "play"))
    a = ap.parse_args(argv)

    langs = [x.strip() for x in a.langs.split(",") if x.strip()]
    if not langs or any(not re.fullmatch(r"[a-z]{2,3}(-[A-Z]{2})?", x) for x in langs):
        raise Fail(2, f"--langs: expected Play codes such as en-US,fr-FR, got {a.langs!r}")
    pkg = a.package
    if not pkg:
        m = re.search(r'^\s*applicationId\s*=\s*"([^"]+)"', open(os.path.join(REPO, "app", "build.gradle.kts")).read(), re.M)
        pkg = m.group(1) if m else ""
    if not re.fullmatch(r"tech\.dmconsulting\.[a-z0-9_.]+", pkg):
        raise Fail(2, f"package '{pkg}' is not a DM Consulting package")
    if ROOT != "https://androidpublisher.googleapis.com" and not LOCAL.fullmatch(ROOT):
        raise Fail(2, "PLAY_API_ROOT is only for the local fake API (http://127.0.0.1:<port>)")
    if a.data_safety and (a.read or a.verify):
        raise Fail(2, "--data-safety goes with a dry run or --commit")

    local_details, local = load_repo(a.play_dir, langs)
    safety = check_data_safety(a.data_safety) if a.data_safety else None
    kinds = sorted({k for item in local.values() for k in item["images"]} or {"icon"})
    if a.read:
        kinds = sorted({v[0] for v in IMAGE_TYPES.values()})

    raw = os.environ.pop("PLAY_SERVICE_ACCOUNT_JSON", "")
    if not raw:
        raise Fail(4, "missing PLAY_SERVICE_ACCOUNT_JSON")
    try:
        key = json.loads(raw)
        assert key.get("type") == "service_account" and key.get("private_key") and key.get("client_email")
    except (ValueError, AssertionError, AttributeError):
        raise Fail(4, "PLAY_SERVICE_ACCOUNT_JSON is not a service account key (JSON)")
    tmp = tempfile.mkdtemp(prefix="play-listing.", dir=os.environ.get("PAPERCLIP_RUN_SCRATCH_DIR") or None)
    os.chmod(tmp, 0o700)
    try:
        play = Play(pkg, key, tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    try:
        e = play.open_edit()
        r_details, remote = remote_state(play, e, langs, kinds)
        if a.read:
            say(f"{pkg}, read only (edit dropped, nothing committed)")
            every = play.call("edits.listings.list", "GET", f"{e}/listings").get("listings", [])
            say("languages on Play: " + (", ".join(sorted(x["language"] for x in every)) or "none"))
            print_state(r_details, remote)
            return 0
        if a.verify:
            gaps = diff(local_details, local, r_details, remote)
            for g in gaps:
                say("differs: " + g)
            say(f"{pkg}: verify {'OK, Play matches the repo' if not gaps else 'FAILED, ' + str(len(gaps)) + ' difference(s)'}"
                f" ({', '.join(langs)})")
            return 0 if not gaps else 1
        changed = write(play, e, local_details, local, r_details, remote)
        play.call("edits.validate", "POST", f"{e}:validate")
        say(f"edits.validate OK ({changed} change(s))")
        if not a.commit:
            say(f"{pkg}: dry run, edit dropped, nothing saved")
            if safety:
                say(f"data safety: {safety[1]} answer row(s) checked locally, not sent (only with --commit)")
            return 0
        play.call("edits.commit", "POST", f"{e}:commit")
        play.edit = None
        say(f"{pkg}: edits.commit OK")
    finally:
        play.drop_edit()

    # Read back before the data safety call, so that a refused CSV never hides the listing's state.
    e = play.open_edit()
    try:
        gaps = diff(local_details, local, *remote_state(play, e, langs, kinds))
    finally:
        play.drop_edit()
    for g in gaps:
        say("differs after commit: " + g)
    say(f"read back: {'OK, Play matches the repo' if not gaps else 'FAILED'}")
    if safety:
        try:
            play.call("applications.dataSafety", "POST", "/dataSafety", {"safetyLabels": safety[0]})
        except Fail as err:
            raise Fail(err.code, f"listing committed and read back above; data safety NOT saved: {err}")
        say(f"data safety: {safety[1]} answer row(s) sent (Play has no API to read them back)")
    return 0 if not gaps else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Fail as err:
        print(f"play-listing: {err}", file=sys.stderr)
        sys.exit(err.code)
