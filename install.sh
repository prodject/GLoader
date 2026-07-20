#!/bin/sh
set -eu

PACKAGE="com.prodject.gloader"
REMOTE_APK="/data/local/tmp/GLoader.apk"
REINSTALL_ON_SIGNATURE_MISMATCH=0
APK="${1:-}"

if [ "${1:-}" = "--replace-incompatible" ]; then
    REINSTALL_ON_SIGNATURE_MISMATCH=1
    APK="${2:-}"
fi

if [ -z "$APK" ]; then
    APK=$(ls -1t ./GLoader-*.apk 2>/dev/null | head -n 1 || true)
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "APK not found. Put GLoader-*.apk in the current directory or pass a path:" >&2
    echo "./install.sh /path/to/GLoader-1.X.apk" >&2
    echo "./install.sh --replace-incompatible /path/to/GLoader-1.X.apk" >&2
    exit 1
fi

echo "Waiting for the short ADB window..."
adb wait-for-device

echo "Uploading $(basename "$APK")..."
adb push -q "$APK" "$REMOTE_APK"

echo "Installing GLoader..."
set +e
INSTALL_OUTPUT=$(adb shell pm install --user 0 -r -d -g "$REMOTE_APK" 2>&1)
INSTALL_STATUS=$?
set -e

if [ "$INSTALL_STATUS" -ne 0 ]; then
    printf '%s\n' "$INSTALL_OUTPUT" >&2
    if printf '%s\n' "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
        echo "The installed GLoader package is signed with a different key." >&2
        echo "Android cannot update it in place; uninstall it first, then install this APK." >&2
        if [ "$REINSTALL_ON_SIGNATURE_MISMATCH" -eq 1 ]; then
            echo "Replacing the incompatible installed package..."
            adb shell pm clear --user 0 "$PACKAGE" >/dev/null 2>&1 || true
            adb shell pm uninstall --user 0 "$PACKAGE" >/dev/null 2>&1 || adb shell pm uninstall "$PACKAGE" >/dev/null 2>&1 || true
            adb shell pm install --user 0 -r -d -g "$REMOTE_APK"
        else
            echo "Run ./uninstall.sh, then run ./install.sh again." >&2
            echo "Or run: ./install.sh --replace-incompatible \"$APK\"" >&2
            adb shell rm "$REMOTE_APK" 2>/dev/null || true
            exit "$INSTALL_STATUS"
        fi
    else
        adb shell rm "$REMOTE_APK" 2>/dev/null || true
        exit "$INSTALL_STATUS"
    fi
fi

# On stock Android these permissions usually require Settings UI confirmation.
# On head units where ADB shell has enough privileges, appops may apply them directly.
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb shell appops set "$PACKAGE" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell rm "$REMOTE_APK" 2>/dev/null || true

echo "GLoader installed. Launching..."
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
