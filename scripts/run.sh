#!/usr/bin/env bash
# Build the debug APK, install it on the connected device/emulator, and launch FoldAmp.
#   scripts/run.sh            build + install + launch
#   scripts/run.sh --emulator also boots the "tl_fold" foldable AVD first if nothing is connected
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
# The default macOS USB backend makes the adb daemon hang at startup on this machine; libusb works.
export ADB_LIBUSB="${ADB_LIBUSB:-1}"

if [[ "${1:-}" == "--emulator" ]] && ! "$ADB" get-state >/dev/null 2>&1; then
  "$SDK/emulator/emulator" -avd "${AVD:-tl_fold}" -no-boot-anim >/dev/null 2>&1 &
  "$ADB" wait-for-device
  until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
fi

./gradlew :app:assembleDebug
"$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.foldamp/.ui.MainActivity
