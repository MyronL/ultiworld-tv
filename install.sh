#!/usr/bin/env bash
# Build Ultiworld TV and install it on an Android TV / Google TV over the network with the Android SDK's adb.
#
# First turn on the TV's developer options (Settings > System > About > tap "Android TV OS build" 7 times; on
# older Android TV, Settings > Device Preferences > About > Build), then Developer options > USB debugging (or
# "Network debugging"). The first install asks on the TV to allow this computer; tick "Always allow" and later
# installs don't ask again. A TV with "Wireless debugging" needs `adb pair` once first; see the README.
#
#   TV_HOST=192.168.1.50 ./install.sh              build a release APK, install it, open it
#   TV_HOST=192.168.1.50 ./install.sh --no-build   install the APK from the last build
#   TV_PORT=5555 (the default) is the TV's debugging port; a TV with "Wireless debugging" shows its own.
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE=io.github.myronl.ultiworldtv
usage() { sed -n '9,11s/^# \{0,3\}//p' "$0"; }
BUILD=1
case "${1:-}" in
  "") ;;
  --no-build) BUILD=0 ;;
  -h | --help) usage; exit 0 ;;
  *) echo "ERROR: unknown option $1" >&2; usage >&2; exit 1 ;;
esac

[ -n "${TV_HOST:-}" ] || { echo "ERROR: set TV_HOST to the TV's IP address (Settings > Network on the TV)." >&2; exit 1; }
TV="$TV_HOST:${TV_PORT:-5555}"

# adb from ANDROID_HOME, then the SDK named in local.properties, then whatever is on the PATH.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then SDK="$(sed -n 's/^sdk\.dir=//p' local.properties)"; fi
if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"; else ADB="$(command -v adb || true)"; fi
[ -n "$ADB" ] || { echo "ERROR: no adb found; set ANDROID_HOME to your Android SDK." >&2; exit 1; }

if [ "$BUILD" = 1 ]; then
  # The build needs JDK 17 or newer. Without JAVA_HOME, use the default java when it's new enough, or else look
  # for one: macOS's java_home, then Homebrew's keg-only openjdk installs.
  java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1; }
  if [ -z "${JAVA_HOME:-}" ] && ! [ "$(java_major java || true)" -ge 17 ] 2>/dev/null; then
    if [ -x /usr/libexec/java_home ] && JH="$(/usr/libexec/java_home -v 17+ 2>/dev/null)"; then
      export JAVA_HOME="$JH"
    else
      for JH in /opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 \
        /usr/local/opt/openjdk /usr/local/opt/openjdk@21 /usr/local/opt/openjdk@17; do
        JH="$JH/libexec/openjdk.jdk/Contents/Home"
        if [ -x "$JH/bin/java" ]; then export JAVA_HOME="$JH"; break; fi
      done
    fi
  fi
  JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  JAVA_MAJOR="$(java_major "$JAVA" || true)"
  if [ -z "$JAVA_MAJOR" ]; then
    echo "ERROR: no Java found. Install JDK 17 or newer and set JAVA_HOME to it." >&2; exit 1
  elif [ "$JAVA_MAJOR" -lt 17 ]; then
    echo "ERROR: building needs JDK 17 or newer, but $JAVA is Java $JAVA_MAJOR. Set JAVA_HOME to a newer JDK." >&2; exit 1
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

if ! OUT="$("$ADB" -s "$TV" install -r "$APK" 2>&1)"; then
  echo "$OUT" >&2
  if echo "$OUT" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
    echo "" >&2
    echo "The app on the TV was downloaded or installed from a different computer, so it's signed with another key. Uninstall it" >&2
    echo "first (on the TV: Settings > Apps > Ultiworld TV > Uninstall, or run: $ADB -s $TV uninstall $PACKAGE)," >&2
    echo "then run this again. You'll need to log in again afterwards." >&2
  fi
  exit 1
fi
# Compile ahead of time: the first launch on a TV CPU is noticeably faster. Optional, so a TV that refuses doesn't matter.
"$ADB" -s "$TV" shell cmd package compile -m speed -f "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" -s "$TV" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
echo "Installed. Ultiworld TV is open on the TV and listed with your other apps."
