#!/usr/bin/env bash
# Capture the production caption UI with explicitly labeled fixture text.
# Build first: ./gradlew :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:?Provide a disposable Android TV emulator serial}"
OUTPUT="${2:?Provide an output directory for the screenshots}"
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a non-emulator serial' >&2; exit 2; }
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
APP=com.sal7one.transiber.debug
mkdir -p "$OUTPUT"
"$ADB" -s "$SERIAL" install -r "$REPO/app/build/outputs/apk/play/debug/app-play-universal-debug.apk"
"$ADB" -s "$SERIAL" install -t -r "$REPO/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"
"$ADB" -s "$SERIAL" shell appops set "$APP" SYSTEM_ALERT_WINDOW allow
RESULT="$("$ADB" -s "$SERIAL" shell am instrument -w -r -e class com.sal7one.transiber.caption.TvOverlayScreenshotTest "$APP.test/androidx.test.runner.AndroidJUnitRunner")"
echo "$RESULT"
[[ "$RESULT" == *"OK (1 test)"* ]] || exit 1
"$ADB" -s "$SERIAL" exec-out run-as "$APP" tar -C files -cf - tv-screenshots | tar -xf - -C "$OUTPUT"
echo "Production TV overlay screenshots (fixture text): $OUTPUT/tv-screenshots"
