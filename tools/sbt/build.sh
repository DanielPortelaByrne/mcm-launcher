#!/bin/bash
# Rebuilds MCM's +SBT for the Fire TV from APKMirror's "+SBT (Android TV)" 1.25.42 bundle (.apkm).
#   tools/sbt/build.sh <path to .apkm> [<adb device>]
# Adds mcm_sbt.js to the app's index.html (opens on SBT News instead of SBT's broken own channel) and re-signs
# with the local debug key. Installs too when a device is given. The first install over SBT's own signature
# needs `adb uninstall br.com.sbt.mais` (+SBT then needs no login: logged out it opens straight on live TV),
# then restart PrivadoVPN so its split tunnel picks up the new install.
set -euo pipefail
APKM=$1; DEV=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd); WORK=$(mktemp -d); OUT=$HERE/../../artifacts/sbt
SDK=${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}; BT=$(ls -d "$SDK"/build-tools/* | tail -1)
APKTOOL=${APKTOOL:-$(ls "$HERE"/apktool_*.jar 2>/dev/null | tail -1)}
[ -f "$APKTOOL" ] || { echo "Put apktool_<version>.jar (github.com/iBotPeaches/Apktool releases) in $HERE or set APKTOOL"; exit 1; }
unzip -q "$APKM" -d "$WORK/apkm"
java -jar "$APKTOOL" d -s -o "$WORK/dec" "$WORK/apkm/base.apk" >/dev/null
python - "$WORK/dec/assets/www/index.html" "$HERE/mcm_sbt.js" <<'PY'
import sys
page, js = sys.argv[1:3]
s = open(page, encoding='utf-8').read()
if 'MCM Home' not in s:
    s = s.replace('<head>', '<head><script>' + open(js, encoding='utf-8').read() + '</script>', 1)
open(page, 'w', encoding='utf-8', newline='').write(s)
PY
java -jar "$APKTOOL" b -o "$WORK/base-raw.apk" "$WORK/dec" >/dev/null
for sp in xhdpi pt en; do
  python - "$WORK/apkm/split_config.$sp.apk" "$WORK/split_$sp-raw.apk" <<'PY'
import sys, zipfile
src, dst = sys.argv[1:3]
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, 'w') as zo:
    for i in zi.infolist():
        if not i.filename.startswith('META-INF/'): zo.writestr(i, zi.read(i.filename))
PY
done
mkdir -p "$OUT"; rm -f "$OUT"/*.apk
for f in "$WORK"/*-raw.apk; do o="$OUT/$(basename "${f%-raw.apk}").apk"
  "$BT/zipalign" -f -p 4 "$f" "$o"
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$o"; done
rm -f "$OUT"/*.idsig; rm -rf "$WORK"; ls -la "$OUT"
[ -n "$DEV" ] && adb -s "$DEV" install-multiple -r "$OUT"/base.apk "$OUT"/split_*.apk
