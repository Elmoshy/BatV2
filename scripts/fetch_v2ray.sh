#!/usr/bin/env bash
# Pulls the v2ray engine module (xray core + tun2socks) from dev7dev/V2ray-Android into ./v2ray
# Version = env V2RAY_REF, else the first word of the file engine.ref (branch, tag or commit), else main.
set -euo pipefail
REF="${V2RAY_REF:-}"
if [ -z "$REF" ] && [ -f engine.ref ]; then REF="$(tr -d ' \r\n' < engine.ref)"; fi
REF="${REF:-main}"
TMP="$(mktemp -d)"
git clone https://github.com/dev7dev/V2ray-Android "$TMP/src"
git -C "$TMP/src" checkout "$REF"
SHA="$(git -C "$TMP/src" rev-parse HEAD)"
rm -rf v2ray
cp -r "$TMP/src/v2ray" ./v2ray
# our app calls Libv2ray directly (testConfig / measureOutboundDelay) so the aar must be visible to it
if grep -q 'implementation(group: "", name: "libv2ray"' v2ray/build.gradle; then
  sed -i 's/implementation(group: "", name: "libv2ray"/api(group: "", name: "libv2ray"/' v2ray/build.gradle
else
  echo "WARNING: could not find the libv2ray line in v2ray/build.gradle, patch it to api(...) by hand" >&2
fi
echo "engine ref: $REF" | tee engine-version.txt
echo "engine commit: $SHA" | tee -a engine-version.txt
ls -la v2ray/libs || true
