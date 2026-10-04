#!/bin/bash
# Builds a separate copy of PrivadoVPN for one person (one account, one country each):
#   tools/privado-copy/build.sh <PrivadoVPN.apk> <suffix> "<label>" [<adb device>]
#   e.g. build.sh privado.apk uk "PrivadoVPN UK" 192.168.1.11:5555   ->  io.privado.android.uk
# The copy gets its own package name and label, and its widget service (WidgetVpnService) is opened to MCM
# only (a signature permission: MCM and the copies are signed with the same local debug key), so MCM can
# connect and disconnect it in the background (VpnPilot). Nothing else in PrivadoVPN is changed.
# Re-running with the same suffix updates the copy in place and keeps its sign-in.
set -euo pipefail
APK=$1; SUFFIX=$2; LABEL=$3; DEV=${4:-}
HERE=$(cd "$(dirname "$0")" && pwd); WORK=$(mktemp -d); OUT=$HERE/../../artifacts/privado
SDK=${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}; BT=$(ls -d "$SDK"/build-tools/* | tail -1)
# Windows (Git Bash): the SDK tools want Windows paths, and apksigner is a .bat.
win() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
SIGNER="$BT/apksigner"; [ -f "$SIGNER.bat" ] && SIGNER="$SIGNER.bat"
APKTOOL=${APKTOOL:-$(ls "$HERE"/../sbt/apktool_*.jar 2>/dev/null | tail -1)}
PKG=io.privado.android.$SUFFIX
java -jar "$APKTOOL" d -s -o "$WORK/dec" "$APK" >/dev/null
python - "$WORK/dec" "$PKG" "$LABEL" <<'PY'
import sys, re, glob, os
dec, pkg, label = sys.argv[1:4]
m = open(os.path.join(dec, 'AndroidManifest.xml'), encoding='utf-8').read()
m = m.replace(' package="io.privado.android" ', f' package="{pkg}" ', 1)
m = re.sub(r'android:authorities="io\.privado\.android\.', f'android:authorities="{pkg}.', m)
m = m.replace('"io.privado.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"', f'"{pkg}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"')
perm = f'{pkg}.permission.CONTROL_VPN'
svc = re.search(r'<service[^>]*android:name="io\.privado\.android\.widget\.WidgetVpnService"[^>]*?/?>', m).group(0)
opened = re.sub(r'android:exported="false"', 'android:exported="true"', svc)
if 'android:permission=' not in opened: opened = opened.replace('<service ', f'<service android:permission="{perm}" ', 1)
m = m.replace(svc, opened)
m = m.replace('<application ', f'<permission android:name="{perm}" android:protectionLevel="signature" />\n    <application ', 1)
open(os.path.join(dec, 'AndroidManifest.xml'), 'w', encoding='utf-8').write(m)
for f in glob.glob(os.path.join(dec, 'res', 'values*', 'strings.xml')):
    s = open(f, encoding='utf-8').read()
    s2 = re.sub(r'(<string name="app_name">)[^<]*(</string>)', lambda x: x.group(1) + label + x.group(2), s)
    if s2 != s: open(f, 'w', encoding='utf-8').write(s2)
PY
java -jar "$APKTOOL" b -o "$WORK/raw.apk" "$WORK/dec" >/dev/null
mkdir -p "$OUT"; "$BT/zipalign" -f -p 4 "$(win "$WORK/raw.apk")" "$(win "$OUT/privado-$SUFFIX.apk")"
"$SIGNER" sign --ks "$(win "$HOME/.android/debug.keystore")" --ks-pass pass:android --key-pass pass:android "$(win "$OUT/privado-$SUFFIX.apk")"
rm -f "$OUT"/*.idsig; rm -rf "$WORK"; ls -la "$OUT/privado-$SUFFIX.apk"
[ -n "$DEV" ] && adb -s "$DEV" install -r "$OUT/privado-$SUFFIX.apk"
