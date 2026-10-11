#!/usr/bin/env bash
# Installs JDK 17 and the Android SDK inside .tools/ (nothing outside the project folder).
# Usage: bash tools/setup-toolchain.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

CMDLINE_TOOLS_ZIP="commandlinetools-win-13114758_latest.zip"
ANDROID_PACKAGES=("platform-tools" "platforms;android-35" "build-tools;35.0.0")

dl="$CP_TOOLS/downloads"
mkdir -p "$dl"

if [ ! -x "$JAVA_HOME/bin/java.exe" ]; then
  echo "==> JDK 17 (Temurin)"
  [ -f "$dl/jdk17.zip" ] || curl -fsSL --retry 3 -o "$dl/jdk17.zip" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
  rm -rf "$CP_TOOLS/jdk-extract" && mkdir -p "$CP_TOOLS/jdk-extract"
  unzip -q "$dl/jdk17.zip" -d "$CP_TOOLS/jdk-extract"
  rm -rf "$JAVA_HOME"
  # Windows Defender may briefly lock freshly extracted files, so copy instead of rename.
  cp -r "$CP_TOOLS/jdk-extract/"jdk-17* "$JAVA_HOME"
  rm -rf "$CP_TOOLS/jdk-extract"
fi
"$JAVA_HOME/bin/java" -version

if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  echo "==> Android command-line tools"
  [ -f "$dl/$CMDLINE_TOOLS_ZIP" ] || curl -fsSL --retry 3 -o "$dl/$CMDLINE_TOOLS_ZIP" \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  rm -rf "$ANDROID_HOME/cmdline-tools" && mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$dl/$CMDLINE_TOOLS_ZIP" -d "$ANDROID_HOME/cmdline-tools"
  cp -r "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$ANDROID_HOME/cmdline-tools/cmdline-tools"
fi

echo "==> Android SDK packages (license accepted by the project owner on 2026-10-11)"
yes | cmd //c "$(cygpath -w "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")" --licenses > /dev/null || true
cmd //c "$(cygpath -w "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat")" "${ANDROID_PACKAGES[@]}"

echo "==> Done. Tools in $CP_TOOLS"
