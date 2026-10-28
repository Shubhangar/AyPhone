#!/bin/bash
# One-time toolchain setup (JDK 17, Android SDK, Gradle wrapper) plus a debug build.
# Expects the archives already downloaded into ~/Library/Android/toolchain-dl.
set -euo pipefail
cd "$(dirname "$0")/.."
# Always log next to the project, however the script was started.
exec > >(tee build.log) 2>&1
trap 'echo "FAILED at line $LINENO (exit $?)"' ERR

DL=~/Library/Android/toolchain-dl
SDK=~/Library/Android/sdk
GRADLE_HOME=~/Library/Android/gradle/gradle-8.9

echo "== JDK 17"
mkdir -p ~/Library/Java/JavaVirtualMachines
if ! /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    tar -xzf "$DL/jdk/jdk17.tar.gz" -C ~/Library/Java/JavaVirtualMachines
fi
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
echo "JAVA_HOME=$JAVA_HOME"

echo "== Gradle 8.9"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    mkdir -p ~/Library/Android/gradle
    unzip -q -o "$DL/gradle/gradle-8.9-bin.zip" -d ~/Library/Android/gradle
fi

echo "== Android command-line tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    rm -rf "$DL/cmdline/x"
    mkdir -p "$SDK/cmdline-tools"
    unzip -q -o "$DL/cmdline/cmdline-tools.zip" -d "$DL/cmdline/x"
    mv "$DL/cmdline/x/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"

echo "== SDK packages (license accepted by the user)"
# `yes` dies of SIGPIPE once sdkmanager stops reading; with pipefail that would abort the script.
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "sdk.dir=$SDK" > local.properties

echo "== Gradle wrapper"
# The wrapper task pings the distribution URL first, which can fail behind some networks;
# skip that check, and don't let a wrapper failure stop the build.
if [ ! -x ./gradlew ]; then
    "$GRADLE_HOME/bin/gradle" wrapper --gradle-version 8.9 --no-validate-url --no-daemon -q \
        || echo "(wrapper generation failed; building with the installed Gradle instead)"
fi

echo "== Build"
# Use the Gradle unpacked above rather than ./gradlew, so nothing needs re-downloading.
# Java's connections timed out here while curl's worked, so prefer IPv4 for every JVM
# (including Gradle's daemon) when fetching the Android Gradle plugin and libraries.
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"
"$GRADLE_HOME/bin/gradle" assembleDebug --no-daemon --console=plain
echo "BUILD_DONE: app/build/outputs/apk/debug/app-debug.apk"
