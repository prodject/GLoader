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

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite(apk.getName(), 0, apk.length())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            }

            Intent callback = new Intent("com.prodject.gloader.installer.INSTALL_STATUS");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pendingIntent.getIntentSender());
        }

        System.out.println("PackageInstaller session committed: " + sessionId);
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
