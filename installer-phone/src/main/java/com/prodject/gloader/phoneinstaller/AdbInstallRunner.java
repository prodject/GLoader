package com.prodject.gloader.phoneinstaller;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.io.File;
import java.util.Map;

final class AdbInstallRunner {
    private static final String ACTION_USB_PERMISSION =
            "com.prodject.gloader.phoneinstaller.USB_PERMISSION";
    private static final String REMOTE_APK = "/data/local/tmp/GLoader.apk";
    private static final String REMOTE_HELPER = "/data/local/tmp/installer.dex";
    private static final String PACKAGE = "com.prodject.gloader";

    interface Logger {
        void log(String message);
    }

    void install(Context context, InstallerAssets assets, Logger logger) throws Exception {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = findAdbDevice(usbManager);
        if (device == null) {
            throw new IllegalStateException("ADB USB device not found");
        }
        if (!usbManager.hasPermission(device)) {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pendingIntent);
            throw new IllegalStateException("USB permission requested. Confirm it and press install again.");
        }

        File apk = assets.copyApkToCache(context);
        File helper = assets.copyHelperToCache(context);

        logger.log("Opening ADB USB transport: " + device.getDeviceName());
        try (UsbAdbClient client = UsbAdbClient.open(context, usbManager, device, logger::log)) {
            logger.log("Pushing APK...");
            client.push(apk, REMOTE_APK, 0644);
            logger.log("Pushing helper...");
            client.push(helper, REMOTE_HELPER, 0644);
            logger.log("Running PackageInstaller helper...");
            String helperOutput = client.shellInteractive("CLASSPATH=" + REMOTE_HELPER
                    + " app_process /system/bin Installer " + REMOTE_APK);
            logger.log(helperOutput.trim());
            logger.log("Checking package registration...");
            String path = client.shell("pm path " + PACKAGE);
            logger.log(path.trim());
            if (!path.contains(PACKAGE) && !path.contains("package:")) {
                logger.log("Package was not registered. Pulling filtered logcat...");
                logger.log(client.shell("logcat -d | grep -iE 'PackageInstaller|PackageManager|PackageManagerService|Install|SecurityException|denied|restriction'"));
                throw new IllegalStateException("Package was not registered after install");
            }
            client.shell("appops set " + PACKAGE + " MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true");
            client.shell("appops set " + PACKAGE + " REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true");
            client.shell("pm grant " + PACKAGE + " android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true");
            client.shell("rm " + REMOTE_APK + " " + REMOTE_HELPER + " >/dev/null 2>&1 || true");
            client.shell("monkey -p " + PACKAGE + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true");
            logger.log("Install completed");
        }
    }

    private static UsbDevice findAdbDevice(UsbManager manager) {
        for (Map.Entry<String, UsbDevice> entry : manager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
                if (usbInterface.getInterfaceClass() == 0xff
                        && usbInterface.getInterfaceSubclass() == 0x42
                        && usbInterface.getInterfaceProtocol() == 0x01) {
                    return device;
                }
            }
        }
        return null;
    }

    static boolean hasAdbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == 0xff
                    && usbInterface.getInterfaceSubclass() == 0x42
                    && usbInterface.getInterfaceProtocol() == 0x01) {
                return true;
            }
        }
        return false;
    }
}
