#!/bin/bash
# Builds a signed release APK into dist/AyPhone-<version>-android.apk.
# On first run it creates the release signing key (keystore/ayphone-release.jks) and
# keystore.properties with a random password. Back both up: every future update of the
# app must be signed with this same key, or phones will refuse to install it.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME=~/Library/Android/sdk
GRADLE=~/Library/Android/gradle/gradle-8.9/bin/gradle
echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ ! -f keystore.properties ]; then
    mkdir -p keystore
    PASS=$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore keystore/ayphone-release.jks -alias ayphone \
        -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
        -dname "CN=AyPhone, O=AyPhone" >/dev/null 2>&1
    printf 'storeFile=keystore/ayphone-release.jks\nstorePassword=%s\nkeyAlias=ayphone\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore.properties
    chmod 600 keystore.properties keystore/ayphone-release.jks
    echo "Created release signing key in keystore/ (password in keystore.properties)"
fi

# Java's network connections time out here over IPv6; prefer IPv4 for dependency downloads.
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
"$GRADLE" assembleRelease --no-daemon --console=plain -q

VERSION=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p ../dist
cp app/build/outputs/apk/release/app-release.apk "../dist/AyPhone-$VERSION-android.apk"
echo "Built $(cd ../dist && pwd)/AyPhone-$VERSION-android.apk"
