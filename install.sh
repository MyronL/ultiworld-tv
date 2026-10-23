#!/usr/bin/env bash
# Build Ultiworld TV and install it on the TV over Wi-Fi with the Android SDK's adb.
#
# Turn on Settings > System > Developer options > USB debugging on the TV first. The TV already trusts this
# Mac's key (~/.android/adbkey), so there is no "Allow USB debugging?" prompt after the first time.
#
#   ./install.sh                 build a release APK, install it, open it
#   TV_HOST=192.168.1.50 ./install.sh
#   ./install.sh --no-build      install the APK from the last build
set -euo pipefail
cd "$(dirname "$0")"

TV="${TV_HOST:-192.168.1.50}:${TV_PORT:-5555}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || { echo "ERROR: no adb at $ADB; set ANDROID_HOME to your Android SDK." >&2; exit 1; }

if [ "${1:-}" != "--no-build" ]; then
  # Gradle 8.9 runs on JDK 17 or 21, not newer; Homebrew's openjdk@17 is keg-only, so name it.
  if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
  ./gradlew -q --console=plain assembleRelease
fi
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: $APK is missing; run without --no-build." >&2; exit 1; }

"$ADB" connect "$TV" >/dev/null || true
if [ "$("$ADB" -s "$TV" get-state 2>/dev/null)" != "device" ]; then
  echo "ERROR: can't reach the TV at $TV. Turn on USB debugging (Settings > System > Developer options)," >&2
  echo "accept the prompt on the TV if one appears, and check the IP address (Settings > Network)." >&2
  exit 1
fi

"$ADB" -s "$TV" install -r "$APK"
# Compile ahead of time: the first launch on a TV CPU is noticeably faster.
"$ADB" -s "$TV" shell cmd package compile -m speed -f local.ultiworldtv >/dev/null
"$ADB" -s "$TV" shell am start -n local.ultiworldtv/.MainActivity >/dev/null
echo "Installed. Ultiworld TV is open on the TV and listed with your other apps."
