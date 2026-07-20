#!/bin/sh
set -eu

PACKAGE="com.prodject.gloader"
REMOTE_APK="/data/local/tmp/GLoader.apk"

echo "Waiting for ADB..."
adb wait-for-device

echo "Stopping GLoader if it is running..."
adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true

echo "Clearing GLoader private app data..."
adb shell pm clear --user 0 "$PACKAGE" >/dev/null 2>&1 || true

echo "Uninstalling GLoader..."
adb shell pm uninstall --user 0 "$PACKAGE" >/dev/null 2>&1 || adb shell pm uninstall "$PACKAGE" >/dev/null 2>&1 || true

echo "Removing temporary installer files..."
adb shell rm -f "$REMOTE_APK" /data/local/tmp/GLoader-*.apk 2>/dev/null || true

echo "Clearing the device logcat buffer if ADB has permission..."
adb logcat -c >/dev/null 2>&1 || true

echo "Done."
echo "Note: ADB cannot guarantee removal of Android Package Manager history, audit records, or firmware/vendor logs."
