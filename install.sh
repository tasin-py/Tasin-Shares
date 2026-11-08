#!/usr/bin/env bash
# Build Tasin Shares and install it on a TV over network ADB, then grant the permissions it needs.
# Usage: TV_HOST=192.168.0.105 ./install.sh        (needs adb on PATH; set SKIP_BUILD=1 to install the existing APK)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PKG=com.tasin.shares
APK=app/build/outputs/apk/release/TasinShares.apk

[ -n "${SKIP_BUILD:-}" ] || ./gradlew --no-daemon -q assembleRelease

adb connect "$TV_HOST:5555"
tv() { adb -s "$TV_HOST:5555" "$@"; }
tv install -r "$APK"
# Files over FTP and the web page need all-files access; some TVs (TCL) block start at boot without APP_AUTO_START.
tv shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
tv shell appops set $PKG APP_AUTO_START allow 2>/dev/null || true
# The approval screen and the "controlled by" pill draw over other apps.
tv shell appops set $PKG SYSTEM_ALERT_WINDOW allow
tv shell cmd deviceidle whitelist +$PKG
tv shell am start -n $PKG/.MainActivity
echo "Installed. Open http://$TV_HOST:8080 (or the web port you set) and allow the code on the TV."
