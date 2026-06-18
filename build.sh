#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build.sh  –  One-shot build script for the VoiceToText APK
# Tested on Ubuntu 22.04 / 24.04 LTS (x86_64)
#
# Usage:
#   chmod +x build.sh
#   bash build.sh          # builds debug APK
#   bash build.sh release  # builds release APK (unsigned)
#
# Output: app/build/outputs/apk/debug/app-debug.apk
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BUILD_TYPE="${1:-debug}"
SDK_DIR="$HOME/.android-sdk"
CMDLINE_VERSION="11076708"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
ANDROID_PLATFORM="34"
BUILD_TOOLS="34.0.0"

# ── Colour helpers ────────────────────────────────────────────────────────────
green() { echo -e "\033[32m$*\033[0m"; }
blue()  { echo -e "\033[34m$*\033[0m"; }
red()   { echo -e "\033[31m$*\033[0m"; }

# ── 1. Java ───────────────────────────────────────────────────────────────────
blue "==> Checking Java"
if ! command -v java &>/dev/null; then
    echo "Installing OpenJDK 17…"
    sudo apt-get update -q && sudo apt-get install -y openjdk-17-jdk
fi
java -version 2>&1 | head -1

# ── 2. Android SDK command-line tools ─────────────────────────────────────────
blue "==> Android SDK"
if [ ! -f "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Downloading Android command-line tools…"
    sudo apt-get install -y wget unzip -q
    mkdir -p "$SDK_DIR/cmdline-tools"
    wget -q "$CMDLINE_URL" -O /tmp/cmdline-tools.zip
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
    mv /tmp/cmdline-tools-extract/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract
    green "  Installed cmdline-tools"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

# ── 3. SDK platform + build tools ─────────────────────────────────────────────
blue "==> SDK platform $ANDROID_PLATFORM + build-tools $BUILD_TOOLS"
if [ ! -d "$SDK_DIR/platforms/android-$ANDROID_PLATFORM" ]; then
    # Accept all licenses non-interactively
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager \
        "platforms;android-$ANDROID_PLATFORM" \
        "build-tools;$BUILD_TOOLS" \
        "platform-tools"
    green "  SDK components installed"
fi

# ── 4. Build APK ──────────────────────────────────────────────────────────────
blue "==> Building $BUILD_TYPE APK"
cd "$(dirname "$0")"

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease --no-daemon
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
else
    ./gradlew assembleDebug --no-daemon
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

green ""
green "✓ Build complete!"
green "  APK: $(pwd)/$APK_PATH"
green ""
echo "Install on a connected device with:"
echo "  adb install $APK_PATH"
