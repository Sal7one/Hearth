#!/usr/bin/env bash
# Run only on an explicitly selected emulator; never select a connected phone.
# Usage: test-android-abi.sh emulator-5580 arm64-v8a [split|universal]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:?Provide an emulator serial}"
ABI="${2:?Provide an Android ABI}"
PACKAGE_KIND="${3:-split}"
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a non-emulator serial' >&2; exit 2; }
case "$ABI" in armeabi-v7a|x86) BITS=32;; arm64-v8a|x86_64) BITS=64;; *) exit 2;; esac
case "$PACKAGE_KIND" in split) SUFFIX="$ABI";; universal) SUFFIX=universal;; *) exit 2;; esac
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
SUPPORTED="$($ADB -s "$SERIAL" shell getprop ro.product.cpu.abilist | tr -d '\r')"
[[ ",$SUPPORTED," == *",$ABI,"* ]] || { echo "Guest does not support $ABI: $SUPPORTED" >&2; exit 1; }
APP=com.sal7one.transiber.qa
for FLAVOR in play foss; do
  "$ADB" -s "$SERIAL" install --abi "$ABI" -r "$REPO/app/build/outputs/apk/$FLAVOR/qa/app-$FLAVOR-$SUFFIX-qa.apk"
  "$ADB" -s "$SERIAL" shell am force-stop "$APP"
  "$ADB" -s "$SERIAL" logcat -c
  LAUNCH="$($ADB -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "$APP/com.sal7one.transiber.MainActivity")"
  echo "$LAUNCH"
  [[ "$LAUNCH" == *"Status: ok"* ]] || exit 1
  sleep 3
  "$ADB" -s "$SERIAL" shell pidof "$APP"
  "$ADB" -s "$SERIAL" shell dumpsys package "$APP" | grep "primaryCpuAbi=$ABI"
  CRASHES="$($ADB -s "$SERIAL" logcat -d -b crash)"
  [[ "$CRASHES" != *"Process: $APP"* ]] || { echo "$CRASHES" >&2; exit 1; }
done
TEST_APK="$REPO/common-jni/build/outputs/apk/androidTest/debug/common-jni-debug-androidTest.apk"
"$ADB" -s "$SERIAL" install --abi "$ABI" -t -r "$TEST_APK"
RESULT="$($ADB -s "$SERIAL" shell am instrument -w -r -e class com.sal7one.common_jni.runtime.AbiRuntimeDeviceTest -e expectedAbi "$ABI" -e expectedBits "$BITS" com.sal7one.common_jni.test/androidx.test.runner.AndroidJUnitRunner)"
echo "$RESULT"
[[ "$RESULT" == *"OK (1 test)"* ]] || exit 1
"$ADB" -s "$SERIAL" logcat -d -s HearthAbiSmoke:I '*:S'
echo "$ABI $PACKAGE_KIND: Play/FOSS installation, startup and $BITS-bit native loading PASS"
