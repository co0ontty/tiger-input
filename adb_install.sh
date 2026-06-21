#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

BUILD_TASK="${BUILD_TASK:-:app:assembleDebug}"
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"
ADB_INSTALL_FLAGS="${ADB_INSTALL_FLAGS:--r -d -g}"

if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb was not found in PATH." >&2
  exit 1
fi

if [[ ! -x ./gradlew ]]; then
  chmod +x ./gradlew
fi

echo "Building APK with Gradle task: $BUILD_TASK"
./gradlew "$BUILD_TASK"

if [[ ! -f "$APK_PATH" ]]; then
  APK_PATH="$(find app/build/outputs/apk -type f -name '*.apk' 2>/dev/null | sort | tail -n 1 || true)"
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "Error: APK not found. Set APK_PATH=/path/to/app.apk if this project uses a custom output path." >&2
  exit 1
fi

adb start-server >/dev/null

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  DEVICES="$ANDROID_SERIAL"
else
  DEVICES="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
fi

DEVICE_COUNT="$(printf '%s\n' "$DEVICES" | sed '/^$/d' | wc -l | tr -d ' ')"

if [[ "$DEVICE_COUNT" == "0" ]]; then
  echo "Error: no authorized adb device is connected." >&2
  echo "Current adb devices:" >&2
  adb devices >&2
  exit 1
fi

echo "Installing APK to $DEVICE_COUNT device(s): $APK_PATH"
# Intentionally split ADB_INSTALL_FLAGS so callers can pass multiple flags:
#   ADB_INSTALL_FLAGS="-r -d -g" ./adb_install.sh
FAILED=0
while IFS= read -r SERIAL; do
  [[ -z "$SERIAL" ]] && continue

  echo "Installing on device: $SERIAL"
  if ! adb -s "$SERIAL" install $ADB_INSTALL_FLAGS "$APK_PATH"; then
    echo "Error: install failed on device $SERIAL." >&2
    FAILED=1
  fi
done <<< "$DEVICES"

if [[ "$FAILED" != "0" ]]; then
  exit 1
fi

echo "Install complete on all connected device(s)."
