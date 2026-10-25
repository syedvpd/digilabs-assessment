#!/usr/bin/env bash
# Usage: package-apk.sh <debug|release> <base.apk> <classes.dex> <outDir> <buildToolsDir>
# Adds classes.dex to the aapt2-linked APK, zip-aligns it and signs it with apksigner (v1+v2+v3).
set -euo pipefail
variant="$1"; base="$2"; dexfile="$3"; out="$4"; tools="$5"
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$out" "$root/keystore"
ks="$root/keystore/$variant.jks"
if [ ! -f "$ks" ]; then
  # Throw-away, self-signed key generated on first build. Replace with your own key for real distribution.
  keytool -genkeypair -keystore "$ks" -storepass tapout-$variant -keypass tapout-$variant \
    -alias tapout -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TapOut Guardian $variant, O=Local, C=US" >/dev/null 2>&1
fi
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$base" "$work/app.apk"
( cd "$(dirname "$dexfile")" && zip -q -j "$work/app.apk" "$(basename "$dexfile")" )
"$tools/zipalign" -f -p 4 "$work/app.apk" "$work/aligned.apk"
apk="$out/tapout-guardian-$variant.apk"
rm -f "$apk" "$apk.idsig"
"$tools/apksigner" sign --ks "$ks" --ks-pass "pass:tapout-$variant" --key-pass "pass:tapout-$variant" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$apk" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$apk" | head -8
echo "APK: $apk ($(stat -c %s "$apk") bytes)"
