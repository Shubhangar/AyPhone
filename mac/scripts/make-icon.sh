#!/bin/bash
# Builds mac/Resources/AppIcon.icns from assets/logo.svg.
set -euo pipefail
cd "$(dirname "$0")/.."
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
swiftc -O scripts/render-icon.swift -o "$WORK/render-icon" 2>&1 | grep -v "ld: warning" || true
SET="$WORK/AppIcon.iconset"
mkdir -p "$SET"
for spec in 16:16x16 32:16x16@2x 32:32x32 64:32x32@2x 128:128x128 256:128x128@2x 256:256x256 512:256x256@2x 512:512x512 1024:512x512@2x; do
    "$WORK/render-icon" ../assets/logo.svg "${spec%%:*}" "$SET/icon_${spec#*:}.png"
done
iconutil -c icns "$SET" -o Resources/AppIcon.icns
echo "Wrote Resources/AppIcon.icns"
