@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title GLoader - ADB installer

set "PACKAGE=com.prodject.gloader"
set "REMOTE_APK=/data/local/tmp/GLoader.apk"
set "REMOTE_HELPER=/data/local/tmp/installer.dex"
set "HELPER_CLASS=Installer"
set "REINSTALL_ON_SIGNATURE_MISMATCH=0"
set "APK=%~1"
set "HELPER=%GLOADER_INSTALL_HELPER%"

if /I "%~1"=="--replace-incompatible" (
    set "REINSTALL_ON_SIGNATURE_MISMATCH=1"
    set "APK=%~2"
)

if not defined APK (
    for /f "delims=" %%F in ('dir /b /o-d "GLoader-*.apk" 2^>nul') do (
        set "APK=%%F"
        goto apk_found
    )
)
:apk_found

if not defined APK (
    echo APK not found. Put GLoader-*.apk next to install.bat or pass a path.
    echo install.bat ^<path-to-apk^>
    echo install.bat --replace-incompatible ^<path-to-apk^>
    exit /b 1
)

if not exist "%APK%" (
    echo APK file not found: %APK%
    exit /b 1
)

if not defined HELPER (
    for /f "delims=" %%F in ('dir /b /o-d "gloader-installer*.dex" "build\installer-helper\gloader-installer.dex" 2^>nul') do (
        set "HELPER=%%F"
        goto helper_found
    )
)
:helper_found

echo Waiting for the short ADB window...
adb wait-for-device || exit /b 1

echo Uploading %APK%...
adb push "%APK%" "%REMOTE_APK%" || exit /b 1

set "INSTALL_STATUS=0"
set "INSTALL_OUTPUT="
if defined HELPER if exist "%HELPER%" (
    echo Uploading install helper %HELPER%...
    adb push "%HELPER%" "%REMOTE_HELPER%" || exit /b 1
    echo Installing GLoader through PackageInstaller helper...
    call :run_and_capture adb shell "CLASSPATH=%REMOTE_HELPER% app_process /system/bin %HELPER_CLASS% %REMOTE_APK%"
) else (
    echo Install helper not found. Falling back to pm install.
    call :run_and_capture adb shell pm install --user 0 -r -d -g "%REMOTE_APK%"
)

if not "%INSTALL_STATUS%"=="0" (
    echo %INSTALL_OUTPUT%
    echo %INSTALL_OUTPUT% | findstr /C:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" >nul
    if errorlevel 1 (
        call :cleanup_tmp
        exit /b %INSTALL_STATUS%
    )
    echo The installed GLoader package is signed with a different key.
    if "%REINSTALL_ON_SIGNATURE_MISMATCH%"=="1" (
        echo Replacing the incompatible installed package...
        adb shell pm clear --user 0 "%PACKAGE%" >nul 2>nul
        adb shell pm uninstall --user 0 "%PACKAGE%" >nul 2>nul
        if errorlevel 1 adb shell pm uninstall "%PACKAGE%" >nul 2>nul
        if defined HELPER if exist "%HELPER%" (
            adb shell "CLASSPATH=%REMOTE_HELPER% app_process /system/bin %HELPER_CLASS% %REMOTE_APK%"
        ) else (
            adb shell pm install --user 0 -r -d -g "%REMOTE_APK%"
        )
    ) else (
        echo Run uninstall.sh, then run install.bat again.
        echo Or run: install.bat --replace-incompatible "%APK%"
        call :cleanup_tmp
        exit /b %INSTALL_STATUS%
    )
)

echo Waiting for package registration...
set "PACKAGE_READY=0"
for /l %%I in (1,1,10) do (
    adb shell pm path "%PACKAGE%" >nul 2>nul
    if not errorlevel 1 (
        set "PACKAGE_READY=1"
        goto package_ready
    )
    timeout /t 1 >nul
)
:package_ready

if not "%PACKAGE_READY%"=="1" (
    echo Package was not registered after install request. Check device PackageInstaller logs.
    call :cleanup_tmp
    exit /b 1
)

call :grant_all_capabilities
call :cleanup_tmp

echo GLoader installed. Launching...
adb shell monkey -p "%PACKAGE%" -c android.intent.category.LAUNCHER 1 >nul 2>nul
echo.
pause
exit /b 0

:run_and_capture
set "INSTALL_OUTPUT="
for /f "delims=" %%L in ('%* 2^>^&1') do (
    set "INSTALL_OUTPUT=!INSTALL_OUTPUT! %%L"
)
set "INSTALL_STATUS=%ERRORLEVEL%"
exit /b 0

:grant_permission
adb shell pm grant "%PACKAGE%" %1 >nul 2>nul
adb shell pm grant --user 0 "%PACKAGE%" %1 >nul 2>nul
exit /b 0

:set_appop
adb shell appops set "%PACKAGE%" %1 %2 >nul 2>nul
adb shell appops set --user 0 "%PACKAGE%" %1 %2 >nul 2>nul
exit /b 0

:append_secure_setting
set "SETTING_KEY=%~1"
set "SETTING_VALUE=%~2"
set "SETTING_CURRENT="
for /f "delims=" %%L in ('adb shell settings get secure "%SETTING_KEY%" 2^>nul') do set "SETTING_CURRENT=%%L"
echo !SETTING_CURRENT! | findstr /C:"%SETTING_VALUE%" >nul
if not errorlevel 1 exit /b 0
if not defined SETTING_CURRENT (
    adb shell settings put secure "%SETTING_KEY%" "%SETTING_VALUE%" >nul 2>nul
    exit /b 0
)
if /I "!SETTING_CURRENT!"=="null" (
    adb shell settings put secure "%SETTING_KEY%" "%SETTING_VALUE%" >nul 2>nul
    exit /b 0
)
adb shell settings put secure "%SETTING_KEY%" "!SETTING_CURRENT!:%SETTING_VALUE%" >nul 2>nul
exit /b 0

:grant_all_capabilities
for %%P in (
    android.permission.CAMERA
    android.permission.RECORD_AUDIO
    android.permission.ACCESS_FINE_LOCATION
    android.permission.ACCESS_COARSE_LOCATION
    android.permission.ACCESS_BACKGROUND_LOCATION
    android.permission.POST_NOTIFICATIONS
    android.permission.READ_MEDIA_IMAGES
    android.permission.READ_MEDIA_VIDEO
    android.permission.READ_MEDIA_AUDIO
    android.permission.READ_MEDIA_VISUAL_USER_SELECTED
    android.permission.READ_EXTERNAL_STORAGE
    android.permission.WRITE_EXTERNAL_STORAGE
    android.permission.BLUETOOTH_CONNECT
    android.permission.BLUETOOTH_SCAN
    android.permission.BLUETOOTH_ADVERTISE
    android.permission.READ_CONTACTS
    android.permission.READ_CALL_LOG
    android.permission.WRITE_CALL_LOG
    android.permission.READ_PHONE_STATE
    android.permission.CALL_PHONE
    android.permission.ANSWER_PHONE_CALLS
    android.permission.READ_SMS
    android.permission.RECEIVE_SMS
    android.permission.SEND_SMS
    android.permission.READ_CALENDAR
    android.permission.WRITE_CALENDAR
    android.permission.ACTIVITY_RECOGNITION
    android.permission.NEARBY_WIFI_DEVICES
    android.permission.WRITE_SECURE_SETTINGS
    android.permission.WRITE_GLOBAL_SETTINGS
    android.permission.WRITE_SETTINGS
    android.permission.PACKAGE_USAGE_STATS
    android.permission.MANAGE_EXTERNAL_STORAGE
    android.permission.REQUEST_INSTALL_PACKAGES
    android.permission.INSTALL_PACKAGES
    android.permission.DELETE_PACKAGES
    android.permission.REQUEST_DELETE_PACKAGES
    android.permission.QUERY_ALL_PACKAGES
    android.permission.INTERNET
    android.permission.ACCESS_NETWORK_STATE
    android.permission.ACCESS_WIFI_STATE
    android.permission.CHANGE_WIFI_STATE
    android.permission.CHANGE_NETWORK_STATE
    android.permission.RECEIVE_BOOT_COMPLETED
    android.permission.QUICKBOOT_POWERON
    android.permission.FOREGROUND_SERVICE
    android.permission.FOREGROUND_SERVICE_DATA_SYNC
    android.permission.FOREGROUND_SERVICE_CAMERA
    android.permission.FOREGROUND_SERVICE_MICROPHONE
    android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
    android.permission.FOREGROUND_SERVICE_SPECIAL_USE
    android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
    android.permission.MODIFY_AUDIO_SETTINGS
    android.permission.MEDIA_CONTENT_CONTROL
    android.permission.SYSTEM_ALERT_WINDOW
    android.permission.KILL_BACKGROUND_PROCESSES
    android.permission.DUMP
    android.permission.CHANGE_CONFIGURATION
    android.permission.READ_LOGS
    android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
    android.permission.REORDER_TASKS
    android.permission.GET_TASKS
    android.permission.REAL_GET_TASKS
    android.permission.MANAGE_ACTIVITY_TASKS
    android.permission.ACCESS_NOTIFICATION_POLICY
    android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    android.permission.SCHEDULE_EXACT_ALARM
    android.permission.USE_EXACT_ALARM
    android.permission.USE_FULL_SCREEN_INTENT
    android.permission.WAKE_LOCK
    android.permission.TURN_SCREEN_ON
    android.permission.NFC
    android.permission.VIBRATE
    android.permission.FLASHLIGHT
    android.permission.DOWNLOAD_WITHOUT_NOTIFICATION
    android.permission.GET_PACKAGE_SIZE
    android.permission.USB_HOST
    android.permission.READ_FRAME_BUFFER
    android.permission.HIDE_OVERLAY_WINDOWS
    android.permission.INTERACT_ACROSS_PROFILES
    android.permission.USE_BIOMETRIC
    android.permission.USE_FINGERPRINT
    android.permission.USE_CREDENTIALS
    android.permission.USE_FACE
    android.permission.USE_FACE_AUTHENTICATION
    android.permission.USE_FACERECOGNITION
    android.permission.USE_IRIS
    android.permission.ACCESS_SURFACE_FLINGER
    android.permission.FORCE_STOP_PACKAGES
    android.permission.STATUS_BAR_SERVICE
    android.permission.EXPAND_STATUS_BAR
    android.permission.UPDATE_DEVICE_STATS
    android.permission.MODIFY_PHONE_STATE
    android.permission.PROCESS_OUTGOING_CALLS
    android.permission.GET_ACCOUNTS
    android.permission.READ_SYNC_SETTINGS
    android.permission.WRITE_SYNC_SETTINGS
    android.permission.READ_USER_DICTIONARY
    android.permission.WRITE_USER_DICTIONARY
    android.permission.SET_WALLPAPER
    android.permission.DISABLE_KEYGUARD
    android.permission.BROADCAST_STICKY
    android.permission.BROADCAST_PACKAGE_REPLACED
    android.permission.ACCESS_SUPERUSER
    android.permission.ACCESS_ADSERVICES_AD_ID
    android.permission.ACCESS_ADSERVICES_ATTRIBUTION
    android.permission.ACCESS_ADSERVICES_TOPICS
    geely.oneos.permission.SERVICE
    ecarx.car.permission.CAR_HVAC
    android.car.permission.CAR_INFO
    android.car.permission.CAR_VENDOR_EXTENSION
    android.car.permission.CAR_CONTROL_AUDIO_VOLUME
    android.car.permission.CAR_NAVIGATION_MANAGER
    android.car.permission.READ_CAR_DISPLAY_UNITS
    android.car.permission.GET_CAR_VENDOR_CATEGORY_DOOR
    android.car.permission.SET_CAR_VENDOR_CATEGORY_DOOR
    android.car.permission.GET_CAR_VENDOR_CATEGORY_SEAT
    android.car.permission.SET_CAR_VENDOR_CATEGORY_SEAT
    android.car.permission.GET_CAR_VENDOR_CATEGORY_MIRROR
    android.car.permission.SET_CAR_VENDOR_CATEGORY_MIRROR
    android.car.permission.GET_CAR_VENDOR_CATEGORY_INFO
    android.car.permission.SET_CAR_VENDOR_CATEGORY_INFO
    android.car.permission.GET_CAR_VENDOR_CATEGORY_HVAC
    android.car.permission.SET_CAR_VENDOR_CATEGORY_HVAC
    android.car.permission.GET_CAR_VENDOR_CATEGORY_LIGHT
    android.car.permission.SET_CAR_VENDOR_CATEGORY_LIGHT
) do call :grant_permission %%P

for %%O in (
    POST_NOTIFICATION
    START_FOREGROUND
    RUN_IN_BACKGROUND
    RUN_ANY_IN_BACKGROUND
    REQUEST_INSTALL_PACKAGES
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    SYSTEM_ALERT_WINDOW
    WRITE_SETTINGS
    READ_LOGS
    SCHEDULE_EXACT_ALARM
    USE_FULL_SCREEN_INTENT
    TURN_SCREEN_ON
    WAKE_LOCK
    GET_USAGE_STATS
    ACCESS_BACKGROUND_LOCATION
    MANAGE_EXTERNAL_STORAGE
) do call :set_appop %%O allow

adb shell cmd deviceidle whitelist +\"%PACKAGE%\" >nul 2>nul
adb shell cmd power whitelist-add "%PACKAGE%" >nul 2>nul
adb shell settings put secure location_mode 3 >nul 2>nul
adb shell settings put secure location_providers_allowed +gps,+network >nul 2>nul
adb shell svc wifi enable >nul 2>nul
adb shell settings put global wifi_on 1 >nul 2>nul
adb shell cmd wifi set-wifi-enabled enabled >nul 2>nul
adb shell settings put global wifi_sleep_policy 2 >nul 2>nul
adb shell settings put global device_provisioned 1 >nul 2>nul
adb shell settings put global geely_device_provisioned 1 >nul 2>nul
adb shell settings put global package_verifier_enable 0 >nul 2>nul
call :append_secure_setting enabled_accessibility_services "%PACKAGE%/com.prodject.gloader.MainActivity"
call :append_secure_setting enabled_notification_listeners "%PACKAGE%/com.prodject.gloader.MainActivity"
exit /b 0

:cleanup_tmp
adb shell rm "%REMOTE_APK%" >nul 2>nul
adb shell rm "%REMOTE_HELPER%" >nul 2>nul
exit /b 0
