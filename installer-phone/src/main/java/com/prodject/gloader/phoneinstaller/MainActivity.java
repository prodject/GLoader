package com.prodject.gloader.phoneinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_LOGS_TREE = 100;
    private static final int REQUEST_EXPORT_LOG = 101;
    private static final int COLOR_BACKGROUND = Color.rgb(245, 246, 248);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_PRIMARY = Color.rgb(20, 22, 26);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(92, 99, 112);
    private static final int COLOR_OUTLINE = Color.rgb(218, 222, 229);
    private static final int COLOR_ACCENT = Color.rgb(11, 87, 208);
    private static final int COLOR_ACCENT_DARK = Color.rgb(8, 66, 160);
    private static final int COLOR_CONSOLE = Color.rgb(28, 31, 36);

    private TextView status;
    private TextView codeView;
    private TextView logView;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        appendLog("GLoader Installer started");
        refreshUsbStatus();
        refreshAssetsStatus();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 48 : 20;
        root.setPadding(dp(side), dp(28), dp(side), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView badge = label("ADB companion");
        root.addView(badge, matchWrap(0, 10));

        TextView title = text("GLoader Installer", 34, true, COLOR_PRIMARY);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, 4));

        TextView subtitle = text("QR authorization and direct APK installation for the head unit.",
                15, false, COLOR_SECONDARY_TEXT);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, constrainedWidth(side, 0, 20));

        LinearLayout statusPanel = card();
        TextView statusTitle = text("Connection", 16, true, COLOR_PRIMARY);
        statusPanel.addView(statusTitle, matchWrap(0, 8));
        status = text("", 14, false, COLOR_SECONDARY_TEXT);
        status.setLineSpacing(dp(2), 1f);
        statusPanel.addView(status, matchWrap(0, 0));
        root.addView(statusPanel, constrainedWidth(side, 0, 14));

        LinearLayout actionPanel = card();
        TextView actionTitle = text("Authorization", 16, true, COLOR_PRIMARY);
        actionPanel.addView(actionTitle, matchWrap(0, 8));

        codeView = text("Код появится здесь", 28, true, COLOR_PRIMARY);
        codeView.setGravity(Gravity.CENTER);
        codeView.setLetterSpacing(0.08f);
        codeView.setBackground(pillBackground(Color.rgb(239, 243, 248), COLOR_OUTLINE, 12));
        codeView.setPadding(dp(12), dp(14), dp(12), dp(14));
        actionPanel.addView(codeView, matchWrap(0, 12));

        Button qrButton = button("Получить код авторизации", true);
        qrButton.setOnClickListener(v -> pickLogsTree());
        actionPanel.addView(qrButton, buttonParams(0, 6));

        Button installButton = button("Пропатчить установщик", true);
        installButton.setOnClickListener(v -> startInstallAttempt());
        actionPanel.addView(installButton, buttonParams(6, 6));

        LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        Button usbButton = button("Обновить", false);
        usbButton.setOnClickListener(v -> refreshUsbStatus());
        secondaryRow.addView(usbButton, rowButtonParams(0, 4));
        Button exportButton = button("Сохранить лог", false);
        exportButton.setOnClickListener(v -> exportLog());
        secondaryRow.addView(exportButton, rowButtonParams(4, 0));
        actionPanel.addView(secondaryRow, matchWrap(4, 0));
        root.addView(actionPanel, constrainedWidth(side, 0, 14));

        LinearLayout logPanel = card();
        TextView logTitle = text("Log", 16, true, COLOR_PRIMARY);
        logPanel.addView(logTitle, matchWrap(0, 8));
        logView = text("", 12, false, Color.rgb(232, 238, 246));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setMinLines(12);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackground(pillBackground(COLOR_CONSOLE, Color.rgb(55, 61, 70), 8));
        logPanel.addView(logView, matchWrap(0, 0));
        root.addView(logPanel, constrainedWidth(side, 0, 0));

        return scroll;
    }

    private TextView text(String value, int sp, boolean strong, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (strong) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 12, true, COLOR_SECONDARY_TEXT);
        view.setGravity(Gravity.CENTER);
        view.setAllCaps(false);
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
        view.setBackground(pillBackground(Color.WHITE, COLOR_OUTLINE, 999));
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        int fill = primary ? COLOR_PRIMARY : COLOR_SURFACE;
        int stroke = primary ? COLOR_PRIMARY : COLOR_OUTLINE;
        int textColor = primary ? Color.WHITE : COLOR_PRIMARY;
        button.setTextColor(textColor);
        button.setBackground(ripple(fill, stroke, primary ? COLOR_ACCENT_DARK : Color.rgb(238, 241, 245), 8));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(pillBackground(COLOR_SURFACE, COLOR_OUTLINE, 8));
        card.setElevation(dp(1));
        return card;
    }

    private GradientDrawable pillBackground(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable ripple(int color, int stroke, int ripple, int radius) {
        return new RippleDrawable(
                ColorStateList.valueOf(ripple),
                pillBackground(color, stroke, radius),
                null);
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams constrainedWidth(int sidePaddingDp, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        int screenDp = getResources().getConfiguration().screenWidthDp;
        if (screenDp > 0) {
            params.width = dp(Math.min(720, screenDp - sidePaddingDp * 2));
        }
        return params;
    }

    private LinearLayout.LayoutParams buttonParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams rowButtonParams(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private void pickLogsTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_LOGS_TREE);
    }

    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "gloader-installer-log.txt");
        startActivityForResult(intent, REQUEST_EXPORT_LOG);
    }

    private void startInstallAttempt() {
        appendLog("Install requested");
        refreshUsbStatus();
        try {
            InstallerAssets assets = InstallerAssets.from(this);
            appendLog("Bundled APK: " + assets.apkName + " (" + assets.apkBytes + " bytes)");
            appendLog("Bundled helper: " + assets.helperName + " (" + assets.helperBytes + " bytes)");
            new Thread(() -> {
                try {
                    new AdbInstallRunner().install(this, assets, this::appendLog);
                } catch (Exception e) {
                    appendLog("Install failed: " + e.getMessage());
                }
            }, "gloader-adb-install").start();
        } catch (Exception e) {
            appendLog("Install failed: " + e.getMessage());
        }
    }

    private void refreshUsbStatus() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = manager.getDeviceList();
        appendLog("USB devices visible: " + devices.size());
        StringBuilder text = new StringBuilder();
        text.append("USB устройств видно: ").append(devices.size()).append('\n');
        for (UsbDevice device : devices.values()) {
            text.append(device.getDeviceName())
                    .append(" vid=").append(device.getVendorId())
                    .append(" pid=").append(device.getProductId())
                    .append(AdbInstallRunner.hasAdbInterface(device) ? " ADB" : "")
                    .append('\n');
        }
        status.setText(text.toString().trim());
    }

    private void refreshAssetsStatus() {
        try {
            InstallerAssets assets = InstallerAssets.from(this);
            appendLog("Assets ready: " + assets.apkName + ", " + assets.helperName);
        } catch (Exception e) {
            appendLog("Assets are not bundled in this build: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            appendLog("Picker cancelled");
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_LOGS_TREE) {
            try {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    appendLog("Persistable permission was not granted by this picker: " + e.getMessage());
                }
                DocumentFile root = DocumentFile.fromTreeUri(this, uri);
                if (root == null) {
                    appendLog("Cannot open selected folder");
                    return;
                }
                QrCodeExtractor.Result result = QrCodeExtractor.extract(this, root);
                codeView.setText(result.authCode);
                appendLog("Authorization code: " + result.authCode);
                appendLog("SN: " + result.serialNumber);
                appendLog("Source: " + result.sourceName);
            } catch (Throwable e) {
                codeView.setText("Код не найден");
                appendLog("QR code extraction failed: " + e.getMessage());
            }
        } else if (requestCode == REQUEST_EXPORT_LOG) {
            try {
                java.io.OutputStream output = getContentResolver().openOutputStream(uri);
                if (output == null) {
                    throw new IllegalStateException("Cannot open output file");
                }
                output.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.close();
                appendLog("Log exported");
            } catch (Exception e) {
                appendLog("Log export failed: " + e.getMessage());
            }
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            log.append(stamp).append("  ").append(message).append('\n');
            if (logView != null) {
                logView.setText(log.toString());
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
