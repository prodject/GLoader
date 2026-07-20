#!/bin/sh
set -eu

PACKAGE="com.prodject.gloader"
REMOTE_APK="/data/local/tmp/GLoader.apk"
APK="${1:-}"

if [ -z "$APK" ]; then
    APK=$(find "$(dirname "$0")/app/build/outputs/apk" -type f -name '*.apk' 2>/dev/null | head -n 1 || true)
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "APK не найден. Использование: ./install.sh /путь/GLoader.apk" >&2
    exit 1
fi

echo "Ожидание короткого окна ADB…"
adb wait-for-device

echo "Передача $(basename "$APK")…"
adb push -q "$APK" "$REMOTE_APK"

echo "Установка GLoader…"
adb shell pm install --user 0 -r -d -g "$REMOTE_APK"

# На штатном Android эти разрешения обычно требуют экрана настроек. На ГУ,
# где ADB shell имеет достаточные права, appops применит их без лишних касаний.
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb shell appops set "$PACKAGE" REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true
adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell rm "$REMOTE_APK" 2>/dev/null || true

echo "GLoader установлен. Запуск…"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
