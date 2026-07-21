package com.prodject.gloader.installer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

public final class InstallHelper {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String SPLIT_NAME = "PackageInstaller";

    private InstallHelper() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: InstallHelper /data/local/tmp/app.apk [package.name]");
        }

        File apk = new File(args[0]);
        if (!apk.isFile()) {
            throw new IllegalArgumentException("APK not found: " + apk.getAbsolutePath());
        }

        Context context = systemContext();
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (args.length >= 2 && !args[1].isEmpty()) {
            params.setAppPackageName(args[1]);
        }
        params.setSize(apk.length());
        params.setInstallReason(PackageManagerInstallReason.USER);

        int sessionId = installer.createSession(params);
        try {
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                session.setStagingProgress(0);
                try (InputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite(SPLIT_NAME, 0, apk.length())) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    long written = 0;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        written += count;
                        if (apk.length() > 0) {
                            session.setStagingProgress((float) written / (float) apk.length());
                        }
                    }
                    session.fsync(output);
                }

                Intent callback = new Intent("com.prodject.gloader.installer.INSTALL_STATUS");
                PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pendingIntent.getIntentSender());
            }
        } catch (Exception e) {
            try {
                installer.abandonSession(sessionId);
            } catch (Exception ignored) {
            }
            throw e;
        }

        System.out.println("PackageInstaller session committed: " + sessionId);
    }

    private static final class PackageManagerInstallReason {
        private static final int USER = 4;

        private PackageManagerInstallReason() {
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
        Object thread = currentActivityThread.invoke(null);
        if (thread == null) {
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            thread = systemMain.invoke(null);
        }
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }
}
