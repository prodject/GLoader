package com.prodject.gloader.phoneinstaller;

import android.content.Context;

import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class QrCodeExtractor {
    private static final Pattern SALT_BRACKET = Pattern.compile("salt\\s*=\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern PASSWORD_BRACKET = Pattern.compile("password\\s*=\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern SN = Pattern.compile("\\ssn\\s*=\\s*([A-Za-z0-9_.-]+)");

    private QrCodeExtractor() {
    }

    static Result extract(Context context, DocumentFile selectedRoot) throws Exception {
        DocumentFile logs = latestLogsFolder(selectedRoot);
        if (logs == null) {
            throw new IllegalStateException("logs_* folder not found");
        }
        DocumentFile bugreport = latestBugreport(logs);
        if (bugreport == null) {
            throw new IllegalStateException("bugreport-*.zip not found in " + logs.getName());
        }

        byte[] zipBytes;
        try (InputStream input = context.getContentResolver().openInputStream(bugreport.getUri())) {
            if (input == null) {
                throw new IllegalStateException("Cannot open " + bugreport.getName());
            }
            zipBytes = readAll(input);
        }

        ParsedFields fields = parseZip(zipBytes);
        String code = calculate(fields.salt, fields.password, fields.serialNumber);
        return new Result(code, fields.serialNumber, logs.getName() + "/" + bugreport.getName());
    }

    private static DocumentFile latestLogsFolder(DocumentFile root) {
        DocumentFile best = null;
        for (DocumentFile file : root.listFiles()) {
            String name = file.getName();
            if (file.isDirectory() && name != null && name.startsWith("logs_")) {
                if (best == null || name.compareTo(best.getName()) > 0) {
                    best = file;
                }
            }
        }
        return best;
    }

    private static DocumentFile latestBugreport(DocumentFile folder) {
        DocumentFile best = null;
        for (DocumentFile file : folder.listFiles()) {
            String name = file.getName();
            if (file.isFile() && name != null && name.startsWith("bugreport-") && name.endsWith(".zip")) {
                if (best == null || name.compareTo(best.getName()) > 0) {
                    best = file;
                }
            }
        }
        return best;
    }

    private static ParsedFields parseZip(byte[] zipBytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name == null || !name.endsWith(".txt")) {
                    continue;
                }
                String content = new String(readAll(zip), StandardCharsets.UTF_8);
                List<Integer> salt = parseIntList(lastMatch(SALT_BRACKET, content));
                List<Integer> password = parseIntList(lastMatch(PASSWORD_BRACKET, content));
                String serial = lastMatch(SN, content);
                if (!salt.isEmpty() && !password.isEmpty() && serial != null) {
                    return new ParsedFields(salt, password, serial);
                }
            }
        }
        throw new IllegalStateException("salt/password/sn fields not found");
    }

    private static String lastMatch(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1).trim();
        }
        return value;
    }

    private static List<Integer> parseIntList(String value) {
        List<Integer> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(Integer.parseInt(trimmed));
            }
        }
        return result;
    }

    private static String calculate(List<Integer> salt, List<Integer> password, String serial) throws Exception {
        byte[] prk = hmac(toBytes(salt), toBytes(password));
        byte[] okm = hkdfExpand(prk, serial.getBytes(StandardCharsets.UTF_8), 6);
        String charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder code = new StringBuilder();
        for (byte b : okm) {
            code.append(charset.charAt((b & 0xff) % charset.length()));
        }
        return code.toString();
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        int counter = 1;
        while (okm.size() < length) {
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            input.write(previous);
            input.write(info);
            input.write(counter++);
            previous = hmac(prk, input.toByteArray());
            okm.write(previous);
        }
        byte[] all = okm.toByteArray();
        byte[] result = new byte[length];
        System.arraycopy(all, 0, result, 0, length);
        return result;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] toBytes(List<Integer> values) {
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            bytes[i] = (byte) (values.get(i) & 0xff);
        }
        return bytes;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static final class Result {
        final String authCode;
        final String serialNumber;
        final String sourceName;

        Result(String authCode, String serialNumber, String sourceName) {
            this.authCode = authCode;
            this.serialNumber = serialNumber;
            this.sourceName = sourceName;
        }
    }

    private static final class ParsedFields {
        final List<Integer> salt;
        final List<Integer> password;
        final String serialNumber;

        ParsedFields(List<Integer> salt, List<Integer> password, String serialNumber) {
            this.salt = salt;
            this.password = password;
            this.serialNumber = serialNumber;
        }
    }
}
