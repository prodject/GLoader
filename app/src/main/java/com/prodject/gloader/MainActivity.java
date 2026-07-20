package com.prodject.gloader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_APK = 10;
    private static final String INSTALL_ACTION = "com.prodject.gloader.INSTALL_STATUS";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView status;
    private ProgressBar progress;

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
                toast("Приложение установлено");
            } else {
                toast("Ошибка установки: " + intent.getStringExtra(
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
        scanUsb();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 48 : 22;
        root.setPadding(dp(side), dp(24), dp(side), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.prodject.gloader.R.drawable.ic_g_foreground);
        root.addView(logo, sized(104, 104));

        TextView title = text("GLoader", 32, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, 2));

        TextView subtitle = text("Установка APK с USB-накопителя", 16, false);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap(0, 20));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(getResources().getConfiguration().screenWidthDp >= 700
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        root.addView(actions, matchWrap(0, 14));

        Button browse = button("Выбрать APK");
        browse.setOnClickListener(v -> browse());
        actions.addView(browse, actionParams());

        Button refresh = button("Сканировать снова");
        refresh.setOnClickListener(v -> scanUsb());
        actions.addView(refresh, actionParams());

        status = text("Подготовка…", 15, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap(0, 10));

        progress = new ProgressBar(this);
        root.addView(progress, sized(42, 42));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        int maxWidth = dp(840);
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(-1, -2);
        resultsParams.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(side * 2), maxWidth);
        root.addView(results, resultsParams);
        setContentView(scroll);
    }

    private void scanUsb() {
        results.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        status.setText("Поиск APK на USB-накопителях…");
        worker.execute(() -> {
            List<File> apks = new ArrayList<>();
            StorageManager manager = getSystemService(StorageManager.class);
            for (StorageVolume volume : manager.getStorageVolumes()) {
                File root = volume.getDirectory();
                if (root != null && volume.isRemovable() && root.canRead()) {
                    findApks(root, apks, 0);
                }
            }
            Collections.sort(apks, Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(() -> showResults(apks));
        });
    }

    private void findApks(File directory, List<File> found, int depth) {
        if (depth > 12 || found.size() >= 500) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) findApks(child, found, depth + 1);
            else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) found.add(child);
        }
    }

    private void showResults(List<File> apks) {
        progress.setVisibility(View.GONE);
        if (apks.isEmpty()) {
            status.setText("APK не найдены. Выберите файл вручную или предоставьте доступ к файлам.");
            if (!Environment.isExternalStorageManager()) {
                Button access = button("Разрешить доступ к файлам");
                access.setOnClickListener(v -> requestFileAccess());
                results.addView(access, matchWrap(4, 4));
            }
            return;
        }
        status.setText("Найдено APK: " + apks.size());
        for (File apk : apks) addApk(apk);
    }

    private void addApk(File apk) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(getResources().getConfiguration().screenWidthDp >= 600
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(8), dp(10));
        TextView info = text(apk.getName() + "\n" + readableSize(apk.length()), 16, true);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Button install = button("Установить");
        install.setOnClickListener(v -> installFile(apk));
        row.addView(install, new LinearLayout.LayoutParams(-2, dp(52)));
        results.addView(row, matchWrap(5, 5));
    }

    private void browse() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, PICK_APK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null) {
            installUri(data.getData(), "selected.apk");
        }
    }

    private void installFile(File apk) {
        if (!ensureInstallPermission()) return;
        worker.execute(() -> {
            try { install(new FileInputStream(apk), apk.getName(), apk.length()); }
            catch (Exception e) { toast("Не удалось открыть APK: " + e.getMessage()); }
        });
    }

    private void installUri(Uri uri, String name) {
        if (!ensureInstallPermission()) return;
        worker.execute(() -> {
            try { install(getContentResolver().openInputStream(uri), name, -1); }
            catch (Exception e) { toast("Не удалось открыть APK: " + e.getMessage()); }
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
            toast("APK подготовлен к установке");
        }
    }

    private boolean ensureInstallPermission() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        startActivity(settings);
        toast("Разрешите установку и нажмите «Установить» ещё раз");
        return false;
    }

    private void requestFileAccess() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        return b;
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(56));
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        return p;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
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
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " КБ";
        return String.format(Locale.getDefault(), "%.1f МБ", bytes / 1048576f);
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
