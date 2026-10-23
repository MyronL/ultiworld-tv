#!/usr/bin/env bash
# Build Ultiworld TV and install it on the TV over Wi-Fi.
#
# Uses adb-tools's ADB tools (../adb-tools/tools), because the TV already trusts their key: no second
# "Allow USB debugging?" prompt. Turn on Settings > System > Developer options > USB debugging first.
#
#   ./install.sh                 build a release APK, install it, open it
#   TV_HOST=192.168.1.50 ./install.sh
#   ./install.sh --no-build      install the APK from the last build
set -euo pipefail
cd "$(dirname "$0")"

export TV_HOST="${TV_HOST:-192.168.1.50}"
TOOLS="$(cd "${ADB_TOOLS:-../adb-tools}/tools" && pwd)"
PY="$TOOLS/venv/bin/python"
[ -x "$PY" ] || { echo "ERROR: no Python venv at $TOOLS/venv; set ADB_TOOLS to the adb-tools checkout." >&2; exit 1; }

if [ "${1:-}" != "--no-build" ]; then
  # Gradle 8.9 runs on JDK 17 or 21, not newer; Homebrew's openjdk@17 is keg-only, so name it.
  if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
  ./gradlew -q --console=plain assembleRelease
fi
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: $APK is missing; run without --no-build." >&2; exit 1; }

if ! "$PY" -I "$TOOLS/tv.py" 'echo connected' >/dev/null 2>&1; then
  echo "ERROR: can't reach the TV at $TV_HOST. Turn on USB debugging (Settings > System > Developer options)," >&2
  echo "accept the prompt on the TV, and check the IP address (Settings > Network)." >&2
  exit 1
fi

"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/ultiworldtv.apk
OUT=$("$PY" -I "$TOOLS/tv.py" 'pm install -r /data/local/tmp/ultiworldtv.apk; rm -f /data/local/tmp/ultiworldtv.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "ERROR: pm install failed (see above)." >&2; exit 1 ;;
esac
# Compile ahead of time: the first launch on a TV CPU is noticeably faster.
"$PY" -I "$TOOLS/tv.py" 'cmd package compile -m speed -f local.ultiworldtv >/dev/null; am start -n local.ultiworldtv/.MainActivity'
echo "Installed. Ultiworld TV is open on the TV and listed with your other apps."
