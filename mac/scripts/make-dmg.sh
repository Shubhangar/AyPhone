#!/bin/bash
# Builds a universal (Apple silicon + Intel) AyPhone.app and packages it as
# dist/AyPhone-<version>-mac.dmg with a drag-to-Applications layout.
#
# The app is ad-hoc signed, not notarized: on other Macs the first launch needs
# right-click → Open (see dist/INSTALL.md). Notarizing needs an Apple Developer ID.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" Resources/Info.plist)
WORK=build/dist
APP="$WORK/AyPhone.app"
OUT=../dist/AyPhone-$VERSION-mac.dmg

# Each architecture gets its own build folder; the new build system otherwise reuses one.
swift build -c release --scratch-path .build/arm64 --triple arm64-apple-macosx14.0
swift build -c release --scratch-path .build/x86_64 --triple x86_64-apple-macosx14.0
ARM=$(swift build -c release --scratch-path .build/arm64 --triple arm64-apple-macosx14.0 --show-bin-path)/AyPhone
X86=$(swift build -c release --scratch-path .build/x86_64 --triple x86_64-apple-macosx14.0 --show-bin-path)/AyPhone

rm -rf "$WORK"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create "$ARM" "$X86" -output "$APP/Contents/MacOS/AyPhone"
lipo -create "${ARM}Bluetooth" "${X86}Bluetooth" -output "$APP/Contents/MacOS/AyPhoneBluetooth"
cp Resources/Info.plist "$APP/Contents/Info.plist"
# A fresh build number each build, so macOS refreshes cached icons and metadata.
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $(date +%Y%m%d%H%M)" "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
# The same identity every build keeps macOS permissions (Accessibility) across updates; see
# make-signing-identity.sh. Without it, sign ad hoc.
SIGN=(--sign -)
KEYCHAIN="$PWD/signing/ayphone-signing.keychain-db"
if [ -f "$KEYCHAIN" ]; then
    security unlock-keychain -p "$(sed -n 's/^p12Password=//p' signing/signing.properties)" "$KEYCHAIN"
    SIGN=(--sign "AyPhone Local Signing" --keychain "$KEYCHAIN")
fi
codesign --force "${SIGN[@]}" "$APP/Contents/MacOS/AyPhoneBluetooth"
codesign --force "${SIGN[@]}" "$APP"

# Disk image: the app plus an Applications shortcut to drag it onto.
STAGE="$WORK/dmg"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
mkdir -p ../dist
rm -f "$OUT"
hdiutil create -volname "AyPhone" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$OUT" >/dev/null
# Keep build copies out of macOS's app registry (see build-app.sh), then remove them.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -u "$PWD/$APP" "$PWD/$STAGE/AyPhone.app" 2>/dev/null || true
rm -rf "$WORK"
echo "Built $(cd ../dist && pwd)/$(basename "$OUT")"
lipo -info "$ARM" "$X86" | sed "s/^/(slices) /"
