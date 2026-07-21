import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

public final class Installer {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String CALLBACK_ACTION = "com.prodject.gloader.installer.RESULT";

    private Installer() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: Installer <apk> [apk2 ...]");
            return;
        }

        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }

        Context context;
        try {
            context = shellContext();
        } catch (Throwable t) {
            System.out.println("CONTEXT_FAIL: " + t);
            t.printStackTrace();
            System.exit(1);
            return;
        }

        PackageManager packageManager = context.getPackageManager();
        PackageInstaller packageInstaller = packageManager.getPackageInstaller();

        int ok = 0;
        int fail = 0;
        for (String apkPath : args) {
            File apk = new File(apkPath);
            String displayName = apk.getName();
            String packageName = archivePackageName(packageManager, apkPath);
            try {
                install(context, packageInstaller, apk);
                if (isPackageRegistered(packageManager, packageName)) {
                    System.out.println("OK   " + displayName + "  (" + packageName + ")");
                    ok++;
                } else {
                    System.out.println("FAIL " + displayName + "  (" + packageName + ") not registered");
                    fail++;
                }
            } catch (Throwable t) {
                System.out.println("FAIL " + displayName + "  (" + packageName + ") " + t);
                t.printStackTrace();
                fail++;
            }
        }

        System.out.println("TOTAL: OK=" + ok + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    private static Context shellContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getMethod("getSystemContext");
        Context systemContext = (Context) getSystemContext.invoke(thread);
        try {
            return systemContext.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable ignored) {
            return systemContext;
        }
    }

    private static String archivePackageName(PackageManager packageManager, String apkPath) {
        try {
            PackageInfo packageInfo = packageManager.getPackageArchiveInfo(apkPath, 0);
            if (packageInfo != null && packageInfo.packageName != null) {
                return packageInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static boolean isPackageRegistered(PackageManager packageManager, String packageName)
            throws InterruptedException {
        if ("?".equals(packageName)) {
            return true;
        }
        for (int i = 0; i < 20; i++) {
            try {
                packageManager.getPackageInfo(packageName, 0);
                return true;
            } catch (Throwable ignored) {
                Thread.sleep(250);
            }
        }
        return false;
    }

    private static void install(Context context, PackageInstaller installer, File apk) throws Exception {
        if (!apk.isFile()) {
            throw new IllegalArgumentException("APK not found: " + apk.getAbsolutePath());
        }

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        try {
            params.setSize(apk.length());
        } catch (Throwable ignored) {
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            OutputStream output = session.openWrite("base.apk", 0, apk.length());
            InputStream input = new FileInputStream(apk);
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) > 0) {
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            } finally {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }

            Intent callback = new Intent(CALLBACK_ACTION).setPackage(SHELL_PACKAGE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            IntentSender sender = pendingIntent.getIntentSender();
            session.commit(sender);
        } catch (Throwable t) {
            try {
                installer.abandonSession(sessionId);
            } catch (Throwable ignored) {
            }
            throw t;
        } finally {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
