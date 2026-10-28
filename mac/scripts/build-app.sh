#!/bin/bash
# Builds AyPhone.app (release) into mac/build/ and installs it to ~/Applications.
# macOS registers notifications and Finder services reliably only for apps in an
# Applications folder, so run the installed copy.
set -euo pipefail
cd "$(dirname "$0")/.."
swift build -c release
APP=build/AyPhone.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$(swift build -c release --show-bin-path)/AyPhone" "$APP/Contents/MacOS/AyPhone"
cp "$(swift build -c release --show-bin-path)/AyPhoneBluetooth" "$APP/Contents/MacOS/AyPhoneBluetooth"
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

INSTALLED="$HOME/Applications/AyPhone.app"
mkdir -p "$HOME/Applications"
rm -rf "$INSTALLED"
ditto "$APP" "$INSTALLED"
# Bump the bundle date so macOS reloads the icon instead of showing a cached (possibly blank) one.
touch "$INSTALLED" "$INSTALLED/Contents/Info.plist"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$INSTALLED"
/System/Library/CoreServices/pbs -update 2>/dev/null || true
# Remove the copy installed under the app's old name.
rm -rf "$HOME/Applications/DroidLink.app" "$HOME/Applications/HyPhone.app"
# Only the installed copy should be known to macOS: a leftover build copy can become the one
# Notification Center takes the icon from (it showed a blank icon). Remove it once installed.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -u "$PWD/$APP" 2>/dev/null || true
rm -rf "$APP"
echo "Installed $INSTALLED"
