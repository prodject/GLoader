package com.prodject.gloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int PICK_APK = 10;
    private static final int UNINSTALL_PACKAGE = 11;
    private static final String INSTALL_ACTION = "com.prodject.gloader.INSTALL_STATUS";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/prodject/GLoader/releases/latest";
    private static final String PREFS = "gloader_packages";
    private static final String TRACKED_PACKAGES = "installed_packages";
    private static final int COLOR_BACKGROUND = Color.rgb(245, 246, 248);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_PRIMARY = Color.rgb(20, 22, 26);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(92, 99, 112);
    private static final int COLOR_OUTLINE = Color.rgb(218, 222, 229);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private LinearLayout screen;
    private TextView status;
    private ProgressBar progress;
    private Button installerTab;
    private Button appsTab;
    private Button cleanupTab;
    private String pendingUninstallPackage;
    private final ArrayDeque<String> cleanupQueue = new ArrayDeque<>();
    private boolean cleanupSequenceActive;
    private boolean uninstallSelfAfterCleanup;
    private boolean appsFilterTrackedOnly;
    private int appsRenderGeneration;
    private int currentScreen;

    private static final class ReleaseInfo {
        final String version;
        final String apkName;
        final String apkUrl;

        ReleaseInfo(String version, String apkName, String apkUrl) {
            this.version = version;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;
        final boolean system;
        final boolean self;
        final boolean tracked;

        AppEntry(String label, String packageName, Drawable icon,
                boolean system, boolean self, boolean tracked) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
            this.system = system;
            this.self = self;
            this.tracked = tracked;
        }
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirmation);
                }
            } else if (code == PackageInstaller.STATUS_SUCCESS) {
                String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                if (packageName != null) trackPackage(packageName);
                toast("Application installed");
            } else {
                toast("Installation failed: " + intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, new IntentFilter(INSTALL_ACTION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installReceiver, new IntentFilter(INSTALL_ACTION));
        }
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 48 : 22;
        root.setPadding(dp(side), dp(30), dp(side), dp(42));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.prodject.gloader.R.drawable.ic_g_3d);
        logo.setContentDescription("GLoader logo");
        root.addView(logo, sized(112, 112));

        TextView title = text("GLoader", 34, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(6, 2));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setPadding(dp(5), dp(5), dp(5), dp(5));
        navigation.setBackground(cardBackground());
        navigation.setElevation(dp(2));
        root.addView(navigation, constrainedWidth(side, 14, 22));

        installerTab = menuButton("Installer");
        installerTab.setOnClickListener(v -> showInstallerScreen());
        navigation.addView(installerTab, menuParams());
        appsTab = menuButton("Apps");
        appsTab.setOnClickListener(v -> showAppsScreen());
        navigation.addView(appsTab, menuParams());
        cleanupTab = menuButton("Cleanup");
        cleanupTab.setOnClickListener(v -> showCleanupScreen());
        navigation.addView(cleanupTab, menuParams());

        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(screen, constrainedWidth(side, 0, 0));
        addUpdatePanel(root, side);
        setContentView(scroll);
        showInstallerScreen();
    }

    private void showInstallerScreen() {
        currentScreen = 0;
        selectTab(installerTab);
        prepareScreen("Install APKs from device storage");

        TextView subtitle = text("Install APKs from device storage and USB drives", 16, false);
        subtitle.setTextColor(COLOR_SECONDARY_TEXT);
        subtitle.setGravity(Gravity.CENTER);
        screen.addView(subtitle, matchWrap(0, 18));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(getResources().getConfiguration().screenWidthDp >= 700
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        screen.addView(actions, matchWrap(0, 14));

        Button browse = button("Select APK", true);
        browse.setOnClickListener(v -> browse());
        actions.addView(browse, actionParams());

        Button refresh = button("Scan again", false);
        refresh.setOnClickListener(v -> scanUsb());
        actions.addView(refresh, actionParams());

        addResultArea();
        scanUsb();
    }

    private void addUpdatePanel(LinearLayout parent, int sidePaddingDp) {
        LinearLayout updatePanel = new LinearLayout(this);
        updatePanel.setOrientation(getResources().getConfiguration().screenWidthDp >= 600
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        updatePanel.setGravity(Gravity.CENTER_VERTICAL);
        updatePanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        updatePanel.setBackground(cardBackground());
        updatePanel.setElevation(dp(2));

        TextView version = text("Current version: " + currentVersionName(), 14, false);
        version.setTextColor(COLOR_SECONDARY_TEXT);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout.LayoutParams versionParams = wide
                ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        updatePanel.addView(version, versionParams);

        Button update = button("Update", false);
        update.setOnClickListener(v -> checkForUpdate());
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(wide ? -2 : -1, dp(48));
        updateParams.setMargins(wide ? dp(10) : 0, wide ? 0 : dp(10), 0, 0);
        updatePanel.addView(update, updateParams);

        parent.addView(updatePanel, constrainedWidth(sidePaddingDp, 18, 0));
    }

    private void prepareScreen(String description) {
        screen.removeAllViews();
    }

    private void addResultArea() {
        status = text("Preparing…", 15, false);
        status.setTextColor(COLOR_SECONDARY_TEXT);
        status.setGravity(Gravity.CENTER);
        screen.addView(status, matchWrap(4, 12));
        progress = new ProgressBar(this);
        screen.addView(progress, sized(42, 42));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        screen.addView(results, new LinearLayout.LayoutParams(-1, -2));
    }

    private void scanUsb() {
        results.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        status.setText("Searching device storage and USB drives for APK files…");
        worker.execute(() -> {
            List<File> apks = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Set<String> foundPaths = new HashSet<>();

            addSearchRoot(Environment.getExternalStorageDirectory(), apks, visited, foundPaths);
            for (File root : getExternalFilesDirs(null)) {
                addSearchRoot(sharedStorageRoot(root), apks, visited, foundPaths);
            }

            StorageManager manager = getSystemService(StorageManager.class);
            for (StorageVolume volume : manager.getStorageVolumes()) {
                File root = volume.getDirectory();
                addSearchRoot(root, apks, visited, foundPaths);
            }
            Collections.sort(apks, Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(() -> showResults(apks));
        });
    }

    private File sharedStorageRoot(File appExternalDir) {
        if (appExternalDir == null) return null;
        File current = appExternalDir;
        while (current != null) {
            File androidDir = new File(current, "Android");
            if (androidDir.isDirectory()) return current;
            current = current.getParentFile();
        }
        return appExternalDir;
    }

    private void addSearchRoot(File root, List<File> apks, Set<String> visited, Set<String> foundPaths) {
        if (root == null || !root.canRead()) return;
        String path;
        try {
            path = root.getCanonicalPath();
        } catch (Exception ignored) {
            path = root.getAbsolutePath();
        }
        if (visited.add(path)) findApks(root, apks, foundPaths, 0);
    }

    private void findApks(File directory, List<File> found, Set<String> foundPaths, int depth) {
        if (depth > 12 || found.size() >= 500) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                findApks(child, found, foundPaths, depth + 1);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                String path;
                try {
                    path = child.getCanonicalPath();
                } catch (Exception ignored) {
                    path = child.getAbsolutePath();
                }
                if (foundPaths.add(path)) found.add(child);
            }
        }
    }

    private void showResults(List<File> apks) {
        progress.setVisibility(View.GONE);
        if (apks.isEmpty()) {
            status.setText("No APK files found. Select a file manually or grant storage access.");
            if (!Environment.isExternalStorageManager()) {
                Button access = button("Allow file access", true);
                access.setOnClickListener(v -> requestFileAccess());
                results.addView(access, matchWrap(4, 4));
            }
            return;
        }
        status.setText("APK files found: " + apks.size());
        for (File apk : apks) addApk(apk);
    }

    private void addApk(File apk) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(getResources().getConfiguration().screenWidthDp >= 600
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(12), dp(14));
        row.setBackground(cardBackground());
        row.setElevation(dp(2));
        TextView info = text(apk.getName() + "\n" + readableSize(apk.length()), 16, true);
        info.setLineSpacing(0, 1.15f);
        info.setTextColor(COLOR_PRIMARY);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout.LayoutParams infoParams = wide
                ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        if (!wide) infoParams.setMargins(0, 0, 0, dp(10));
        row.addView(info, infoParams);
        Button install = button("Install", true);
        install.setOnClickListener(v -> installFile(apk));
        row.addView(install, new LinearLayout.LayoutParams(wide ? -2 : -1, dp(52)));
        results.addView(row, matchWrap(5, 5));
    }

    private void showAppsScreen() {
        currentScreen = 1;
        selectTab(appsTab);
        prepareScreen("Installed applications");
        TextView subtitle = text("Installed applications", 24, true);
        subtitle.setGravity(Gravity.CENTER);
        screen.addView(subtitle, matchWrap(0, 2));
        TextView hint = text("Review user and system packages on this device", 15, false);
        hint.setTextColor(COLOR_SECONDARY_TEXT);
        hint.setGravity(Gravity.CENTER);
        screen.addView(hint, matchWrap(0, 14));

        Switch trackedOnly = new Switch(this);
        trackedOnly.setText("Show GLoader installs only");
        trackedOnly.setTextSize(14);
        trackedOnly.setTextColor(COLOR_PRIMARY);
        trackedOnly.setChecked(appsFilterTrackedOnly);
        trackedOnly.setPadding(dp(12), 0, dp(12), 0);
        trackedOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appsFilterTrackedOnly = isChecked;
            showAppsScreen();
        });
        screen.addView(trackedOnly, matchWrap(0, 8));

        addResultArea();
        int renderGeneration = ++appsRenderGeneration;
        status.setText("Loading applications…");
        worker.execute(() -> {
            List<ApplicationInfo> applications;
            PackageManager pm = getPackageManager();
            Set<String> tracked = getTrackedPackages();
            if (Build.VERSION.SDK_INT >= 33) {
                applications = pm.getInstalledApplications(
                        PackageManager.ApplicationInfoFlags.of(0));
            } else {
                applications = pm.getInstalledApplications(0);
            }
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo app : applications) {
                boolean self = getPackageName().equals(app.packageName);
                boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                entries.add(new AppEntry(app.loadLabel(pm).toString(), app.packageName,
                        app.loadIcon(pm), system, self, tracked.contains(app.packageName)));
            }
            entries.sort(Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(() -> showApplications(entries, renderGeneration));
        });
    }

    private void showApplications(List<AppEntry> applications, int renderGeneration) {
        if (currentScreen != 1 || renderGeneration != appsRenderGeneration) return;
        progress.setVisibility(View.GONE);
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry app : applications) {
            if (!appsFilterTrackedOnly || app.tracked) visible.add(app);
        }
        String suffix = appsFilterTrackedOnly ? " installed through GLoader" : "";
        status.setText("Applications found: " + visible.size() + suffix);
        if (visible.isEmpty()) {
            TextView empty = text(appsFilterTrackedOnly
                    ? "No tracked GLoader-installed applications are currently installed."
                    : "No applications found.", 15, false);
            empty.setTextColor(COLOR_SECONDARY_TEXT);
            empty.setGravity(Gravity.CENTER);
            results.addView(empty, matchWrap(8, 8));
            return;
        }
        addApplicationBatch(visible, 0, renderGeneration);
    }

    private void addApplicationBatch(List<AppEntry> applications, int start, int renderGeneration) {
        if (currentScreen != 1 || renderGeneration != appsRenderGeneration) return;
        int end = Math.min(start + 18, applications.size());
        for (int i = start; i < end; i++) addApplication(applications.get(i));
        if (end < applications.size()) {
            results.post(() -> addApplicationBatch(applications, end, renderGeneration));
        }
    }

    private void addApplication(AppEntry app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        row.setBackground(cardBackground());
        row.setElevation(dp(2));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setContentDescription(app.label);
        LinearLayout.LayoutParams iconParams = sized(48, 48);
        iconParams.setMargins(0, 0, dp(14), 0);
        row.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(app.label, 16, true);
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView packageName = text(app.packageName, 12, false);
        packageName.setTextColor(COLOR_SECONDARY_TEXT);
        packageName.setSingleLine(true);
        labels.addView(packageName, new LinearLayout.LayoutParams(-1, -2));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Button action = button(app.self ? "Current" : app.system ? "System" : "Uninstall",
                !app.system && !app.self);
        action.setEnabled(!app.system && !app.self);
        if (app.system || app.self) action.setAlpha(0.58f);
        if (!app.system && !app.self) action.setOnClickListener(v -> uninstallPackage(app.packageName));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, dp(48));
        actionParams.setMargins(dp(10), 0, 0, 0);
        row.addView(action, actionParams);
        results.addView(row, matchWrap(5, 5));
    }

    private void showCleanupScreen() {
        currentScreen = 2;
        selectTab(cleanupTab);
        prepareScreen("Cleanup");
        TextView subtitle = text("Cleanup & uninstall", 24, true);
        subtitle.setGravity(Gravity.CENTER);
        screen.addView(subtitle, matchWrap(0, 4));
        Set<String> tracked = getTrackedPackages();
        TextView description = text(
                "GLoader has recorded " + tracked.size() + " successfully installed package(s). "
                        + "Android will request confirmation before removing each application.", 15, false);
        description.setTextColor(COLOR_SECONDARY_TEXT);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(0, 1.15f);
        screen.addView(description, matchWrap(0, 18));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(cardBackground());
        card.setElevation(dp(2));
        screen.addView(card, matchWrap(0, 12));

        TextView privacyTitle = text("What cleanup removes", 17, true);
        card.addView(privacyTitle, matchWrap(0, 6));
        TextView privacy = text(
                "• Apps installed through GLoader that you confirm\n"
                        + "• GLoader and all of its private app data\n\n"
                        + "System Package Manager records and system logs are owned by Android and cannot be erased by a regular app.",
                14, false);
        privacy.setTextColor(COLOR_SECONDARY_TEXT);
        privacy.setLineSpacing(0, 1.15f);
        card.addView(privacy, matchWrap(0, 0));

        Button trackedButton = button("Uninstall tracked apps", false);
        trackedButton.setEnabled(!tracked.isEmpty());
        trackedButton.setOnClickListener(v -> confirmTrackedCleanup(false));
        screen.addView(trackedButton, matchWrap(5, 5));

        Button fullCleanup = dangerButton("Clean up and uninstall GLoader");
        fullCleanup.setOnClickListener(v -> confirmTrackedCleanup(true));
        screen.addView(fullCleanup, matchWrap(5, 5));

        Button selfOnly = button("Uninstall GLoader only", false);
        selfOnly.setOnClickListener(v -> confirmSelfUninstall());
        screen.addView(selfOnly, matchWrap(5, 5));
    }

    private void confirmTrackedCleanup(boolean uninstallSelfAfterward) {
        List<String> packages = getInstalledTrackedPackages();
        StringBuilder message = new StringBuilder();
        if (packages.isEmpty()) {
            message.append("No tracked GLoader-installed applications are currently installed.");
            if (uninstallSelfAfterward) {
                message.append("\n\nAndroid will ask you to confirm removal of GLoader itself.");
            }
        } else {
            message.append(uninstallSelfAfterward
                    ? "Android will ask you to confirm removal of these tracked apps, then GLoader itself:\n\n"
                    : "Android will ask you to confirm removal of these tracked apps:\n\n");
            message.append(describePackages(packages));
        }
        new AlertDialog.Builder(this)
                .setTitle("Confirm cleanup")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> startTrackedCleanup(uninstallSelfAfterward))
                .show();
    }

    private void startTrackedCleanup(boolean uninstallSelfAfterward) {
        cleanupQueue.clear();
        cleanupQueue.addAll(getInstalledTrackedPackages());
        cleanupSequenceActive = true;
        uninstallSelfAfterCleanup = uninstallSelfAfterward;
        uninstallNextTrackedPackage();
    }

    private void uninstallNextTrackedPackage() {
        String packageName = cleanupQueue.poll();
        if (packageName != null) {
            uninstallPackage(packageName);
        } else if (uninstallSelfAfterCleanup) {
            cleanupSequenceActive = false;
            uninstallSelfAfterCleanup = false;
            selfUninstall();
        } else {
            cleanupSequenceActive = false;
            toast("Tracked-app cleanup finished");
            showCleanupScreen();
        }
    }

    private void uninstallPackage(String packageName) {
        pendingUninstallPackage = packageName;
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + packageName));
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        startActivityForResult(intent, UNINSTALL_PACKAGE);
    }

    private void confirmSelfUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("Uninstall GLoader")
                .setMessage("Android will remove GLoader and its private app data. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall", (dialog, which) -> selfUninstall())
                .show();
    }

    private void selfUninstall() {
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private List<String> getInstalledTrackedPackages() {
        List<String> packages = new ArrayList<>();
        for (String packageName : getTrackedPackages()) {
            if (getPackageName().equals(packageName)) {
                forgetPackage(packageName);
            } else if (isPackageInstalled(packageName)) {
                packages.add(packageName);
            } else {
                forgetPackage(packageName);
            }
        }
        packages.sort(String.CASE_INSENSITIVE_ORDER);
        return packages;
    }

    private String describePackages(List<String> packages) {
        PackageManager pm = getPackageManager();
        StringBuilder description = new StringBuilder();
        for (String packageName : packages) {
            description.append("• ");
            try {
                ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
                description.append(app.loadLabel(pm)).append(" (").append(packageName).append(")");
            } catch (PackageManager.NameNotFoundException ignored) {
                description.append(packageName);
            }
            description.append('\n');
        }
        return description.toString().trim();
    }

    private Set<String> getTrackedPackages() {
        return new HashSet<>(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(TRACKED_PACKAGES, Collections.emptySet()));
    }

    private void trackPackage(String packageName) {
        Set<String> packages = getTrackedPackages();
        packages.add(packageName);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(TRACKED_PACKAGES, packages).apply();
    }

    private void forgetPackage(String packageName) {
        Set<String> packages = getTrackedPackages();
        packages.remove(packageName);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(TRACKED_PACKAGES, packages).apply();
    }

    private void browse() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, PICK_APK);
    }

    private void checkForUpdate() {
        if (!ensureInstallPermission()) return;
        toast("Checking for updates...");
        worker.execute(() -> {
            try {
                ReleaseInfo latest = fetchLatestRelease();
                if (latest == null) {
                    toast("No release APK found on GitHub");
                } else if (!isNewerVersion(latest.version, currentVersionName())) {
                    toast("GLoader is up to date");
                } else {
                    runOnUiThread(() -> confirmUpdate(latest));
                }
            } catch (Exception e) {
                toast("Update check failed: " + e.getMessage());
            }
        });
    }

    private void confirmUpdate(ReleaseInfo latest) {
        new AlertDialog.Builder(this)
                .setTitle("Update GLoader")
                .setMessage("Version " + latest.version + " is available. Download and install "
                        + latest.apkName + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update", (dialog, which) -> downloadAndInstallUpdate(latest))
                .show();
    }

    private void downloadAndInstallUpdate(ReleaseInfo latest) {
        toast("Downloading GLoader " + latest.version + "...");
        worker.execute(() -> {
            try {
                File apk = downloadReleaseApk(latest);
                install(new FileInputStream(apk), apk.getName(), apk.length());
            } catch (Exception e) {
                toast("Update failed: " + e.getMessage());
            }
        });
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        JSONObject release = new JSONObject(readUrl(LATEST_RELEASE_API));
        String version = normalizeVersion(release.optString("tag_name",
                release.optString("name", "")));
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (name.toLowerCase(Locale.ROOT).endsWith(".apk") && !url.isEmpty()) {
                return new ReleaseInfo(version, name, url);
            }
        }
        return null;
    }

    private String readUrl(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "GLoader/" + currentVersionName());
        try (InputStream input = connection.getInputStream()) {
            return readString(input);
        } finally {
            connection.disconnect();
        }
    }

    private File downloadReleaseApk(ReleaseInfo latest) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(latest.apkUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "GLoader/" + currentVersionName());
        File apk = new File(getCacheDir(), "gloader-update.apk");
        try (InputStream input = connection.getInputStream();
             OutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } finally {
            connection.disconnect();
        }
        return apk;
    }

    private String readString(InputStream input) throws Exception {
        byte[] buffer = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int count;
        while ((count = input.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, count, "UTF-8"));
        }
        return builder.toString();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null) {
            installUri(data.getData(), "selected.apk");
        } else if (request == UNINSTALL_PACKAGE) {
            String packageName = pendingUninstallPackage;
            pendingUninstallPackage = null;
            if (packageName != null) {
                boolean removed = result == RESULT_OK || !isPackageInstalled(packageName);
                if (removed) {
                    forgetPackage(packageName);
                    toast("Application removed: " + packageName);
                } else {
                    toast("Removal cancelled: " + packageName);
                }
            }
            if (cleanupSequenceActive) {
                uninstallNextTrackedPackage();
            } else if (currentScreen == 1) {
                showAppsScreen();
            } else if (currentScreen == 2) {
                showCleanupScreen();
            }
        }
    }

    private void installFile(File apk) {
        if (!ensureInstallPermission()) return;
        worker.execute(() -> {
            try { install(new FileInputStream(apk), apk.getName(), apk.length()); }
            catch (Exception e) { toast("Could not open APK: " + e.getMessage()); }
        });
    }

    private void installUri(Uri uri, String name) {
        if (!ensureInstallPermission()) return;
        worker.execute(() -> {
            try { install(getContentResolver().openInputStream(uri), name, -1); }
            catch (Exception e) { toast("Could not open APK: " + e.getMessage()); }
        });
    }

    private void install(InputStream source, String name, long length) throws Exception {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream input = source;
                 OutputStream output = session.openWrite(name, 0, length)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            Intent callback = new Intent(INSTALL_ACTION).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, id, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            toast("APK is ready to install");
        }
    }

    private boolean ensureInstallPermission() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(settings);
        toast("Allow installation from this source, then tap Install again");
        return false;
    }

    private void requestFileAccess() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? Color.WHITE : COLOR_PRIMARY);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setBackgroundTintList(null);
        b.setBackground(materialButtonBackground(primary));
        b.setElevation(primary ? dp(2) : 0);
        return b;
    }

    private RippleDrawable materialButtonBackground(boolean primary) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? COLOR_PRIMARY : COLOR_SURFACE);
        shape.setCornerRadius(dp(15));
        if (!primary) shape.setStroke(dp(1), COLOR_OUTLINE);
        int ripple = primary ? Color.argb(70, 255, 255, 255) : Color.argb(28, 0, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(ripple), shape, null);
    }

    private Button menuButton(String label) {
        Button b = button(label, false);
        b.setTextSize(13);
        b.setMinHeight(dp(46));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setElevation(0);
        return b;
    }

    private LinearLayout.LayoutParams menuParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void selectTab(Button selected) {
        Button[] tabs = { installerTab, appsTab, cleanupTab };
        for (Button tab : tabs) {
            boolean active = tab == selected;
            tab.setTextColor(active ? Color.WHITE : COLOR_PRIMARY);
            tab.setBackground(materialButtonBackground(active));
        }
    }

    private Button dangerButton(String label) {
        Button b = button(label, true);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(176, 32, 42));
        shape.setCornerRadius(dp(15));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(
                Color.argb(70, 255, 255, 255)), shape, null));
        return b;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(COLOR_SURFACE);
        card.setCornerRadius(dp(18));
        card.setStroke(dp(1), COLOR_OUTLINE);
        return card;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams actionParams() {
        boolean horizontal = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout.LayoutParams p = horizontal
                ? new LinearLayout.LayoutParams(0, dp(56), 1)
                : new LinearLayout.LayoutParams(-1, dp(56));
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        return p;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private LinearLayout.LayoutParams constrainedWidth(int sidePaddingDp, int top, int bottom) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = dp(860);
        int horizontalPadding = dp(sidePaddingDp * 2);
        int width = Math.min(maxWidth, Math.max(dp(260), screenWidth - horizontalPadding));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private LinearLayout.LayoutParams sized(int width, int height) {
        return new LinearLayout.LayoutParams(dp(width), dp(height));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String readableSize(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576f);
    }

    private String currentVersionName() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    private String normalizeVersion(String value) {
        String version = value == null ? "" : value.trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        return version;
    }

    private boolean isNewerVersion(String candidate, String current) {
        int[] candidateParts = versionParts(candidate);
        int[] currentParts = versionParts(current);
        int count = Math.max(candidateParts.length, currentParts.length);
        for (int i = 0; i < count; i++) {
            int left = i < candidateParts.length ? candidateParts[i] : 0;
            int right = i < currentParts.length ? currentParts[i] : 0;
            if (left != right) return left > right;
        }
        return false;
    }

    private int[] versionParts(String version) {
        String normalized = normalizeVersion(version);
        String[] pieces = normalized.split("\\.");
        int[] values = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String digits = pieces[i].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) values[i] = 0;
            else {
                try {
                    values[i] = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    values[i] = 0;
                }
            }
        }
        return values;
    }

    private void toast(String value) {
        runOnUiThread(() -> Toast.makeText(this, value, Toast.LENGTH_LONG).show());
    }

    @Override protected void onDestroy() {
        unregisterReceiver(installReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }
}
