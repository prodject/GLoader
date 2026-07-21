package com.prodject.gloader.phoneinstaller;

import android.app.Activity;
import android.content.Intent;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        scroll.addView(root);

        TextView title = text("GLoader Installer", 24, true);
        root.addView(title);

        status = text("", 14, false);
        status.setPadding(0, dp(10), 0, dp(12));
        root.addView(status);

        Button qrButton = button("Получить код авторизации");
        qrButton.setOnClickListener(v -> pickLogsTree());
        root.addView(qrButton);

        codeView = text("Код появится здесь после выбора флешки.", 20, true);
        codeView.setGravity(Gravity.CENTER_HORIZONTAL);
        codeView.setPadding(0, dp(14), 0, dp(18));
        root.addView(codeView);

        Button installButton = button("Пропатчить установщик");
        installButton.setOnClickListener(v -> startInstallAttempt());
        root.addView(installButton);

        Button usbButton = button("Обновить USB/ADB статус");
        usbButton.setOnClickListener(v -> refreshUsbStatus());
        root.addView(usbButton);

        Button exportButton = button("Сохранить лог");
        exportButton.setOnClickListener(v -> exportLog());
        root.addView(exportButton);

        logView = text("", 13, false);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(0, dp(16), 0, 0);
        root.addView(logView);

        return scroll;
    }

    private TextView text(String value, int sp, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(0xff202124);
        if (strong) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(params);
        return button;
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
            new AdbInstallRunner().install(assets);
        } catch (Exception e) {
            appendLog("Install is not available yet: " + e.getMessage());
            appendLog("Подключите ГУ к телефону через USB OTG. Для реальной установки нужен встроенный ADB client; системной команды adb на Android-телефоне нет.");
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
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            DocumentFile root = DocumentFile.fromTreeUri(this, uri);
            if (root == null) {
                appendLog("Cannot open selected folder");
                return;
            }
            try {
                QrCodeExtractor.Result result = QrCodeExtractor.extract(this, root);
                codeView.setText(result.authCode);
                appendLog("Authorization code: " + result.authCode);
                appendLog("SN: " + result.serialNumber);
                appendLog("Source: " + result.sourceName);
            } catch (Exception e) {
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
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        log.append(stamp).append("  ").append(message).append('\n');
        if (logView != null) {
            logView.setText(log.toString());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
