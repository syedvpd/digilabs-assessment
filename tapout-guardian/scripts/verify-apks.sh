#!/usr/bin/env bash
# Usage: verify-apks.sh <apk-dir> [build-tools-dir]
# Verifies both APKs: non-empty, apksigner signature (v2/v3), package name, version == gradle.properties, debuggable flag.
# Exits non-zero on the first failure and never prints keys or passwords.
set -euo pipefail
dir="${1:?apk dir}"; tools="${2:-${ANDROID_BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/debian}}"
root="$(cd "$(dirname "$0")/.." && pwd)"
want_name="$(sed -n 's/^appVersionName=//p' "$root/gradle.properties")"
want_code="$(sed -n 's/^appVersionCode=//p' "$root/gradle.properties")"
aapt="$tools/aapt"; [ -x "$aapt" ] || aapt="$(command -v aapt)"
fail=0
for variant in debug release; do
  apk="$dir/tapout-guardian-$variant.apk"
  echo "== $variant: $apk"
  [ -s "$apk" ] || { echo "FAIL: missing or empty"; fail=1; continue; }
  echo "size: $(stat -c %s "$apk") bytes"
  out="$("$tools/apksigner" verify --verbose --print-certs "$apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS)" || { echo "$out"; echo "FAIL: apksigner verify"; fail=1; continue; }
  echo "$out" | grep -E "^Verifies|Verified using v[1-4]|Number of signers|certificate DN" || true
  echo "$out" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" || { echo "FAIL: v2 signature missing"; fail=1; }
  badging="$("$aapt" dump badging "$apk" 2>/dev/null)"
  pkg="$(echo "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  vcode="$(echo "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
  vname="$(echo "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
  echo "package=$pkg versionName=$vname versionCode=$vcode"
  [ "$pkg" = "com.tapoutguardian" ] || { echo "FAIL: package name"; fail=1; }
  [ "$vname" = "$want_name" ] || { echo "FAIL: versionName != $want_name"; fail=1; }
  [ "$vcode" = "$want_code" ] || { echo "FAIL: versionCode != $want_code"; fail=1; }
  dbg=0; echo "$badging" | grep -q "^application-debuggable" && dbg=1
  if [ "$variant" = debug ] && [ $dbg -ne 1 ]; then echo "FAIL: debug APK is not debuggable"; fail=1; fi
  if [ "$variant" = release ] && [ $dbg -ne 0 ]; then echo "FAIL: release APK is debuggable"; fail=1; fi
  unzip -l "$apk" | grep -qiE '\.(jks|keystore|p12)$' && { echo "FAIL: key material inside APK"; fail=1; }
done
[ $fail -eq 0 ] && echo "ALL APK CHECKS PASSED" || { echo "APK CHECKS FAILED"; exit 1; }
