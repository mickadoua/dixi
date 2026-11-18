# Sourced by the emulator proofs: the app's labels in the language under test, read from the
# app's own strings (res/values-<lang>/strings.xml, else res/values/), never written in the script.
# The texts change between versions, and the emulator runs in English.
#   APP_LANG=fr (default) or en: the app's language, forced per app (Android 13+, `cmd locale`).
#   Android's own screens stay in the emulator's language.
# Needs ADB and PKG set before sourcing. Run from the repository root.
APP_LANG=${APP_LANG:-fr}
RES_DIR=${RES_DIR:-app/src/main/res}

# label <name> [arg…]: the string, %1$s / %1$d replaced by the args, escaped like the
# text="…" attributes of a uiautomator dump (so it can be matched against one).
label() {
    python3 - "$RES_DIR" "$APP_LANG" "$@" <<'PY'
import os, re, sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
res, lang, name, args = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
folders = ([] if lang == 'en' else [f'values-{lang}']) + ['values']
for folder in folders:
    path = os.path.join(res, folder, 'strings.xml')
    if not os.path.exists(path):
        continue
    node = ET.parse(path).getroot().find(f"string[@name='{name}']")
    if node is None:
        continue
    text = ''.join(node.itertext())
    if len(text) > 1 and text[0] == text[-1] == '"':
        text = text[1:-1]
    text = re.sub(r"\\(['\"@?])", r'\1', text).replace('\\n', '\n')
    for i, arg in enumerate(args, 1):
        text = re.sub(rf'%{i}\$[sd]', arg, text)
    print(escape(text, {'"': '&quot;'}))
    sys.exit(0)
sys.exit(f'no string {name} for {lang}')
PY
}

# The app in APP_LANG. After `pm clear` too: it can reset the app's language.
set_app_lang() {
    $ADB shell cmd locale set-app-locales $PKG --locales "$APP_LANG"
    echo "app locale: $($ADB shell cmd locale get-app-locales $PKG | tr -d '\r')"
}
