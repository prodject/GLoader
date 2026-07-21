package com.prodject.gloader.phoneinstaller;

import android.content.Context;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
    private static final Pattern SALT_LINE = Pattern.compile("salt\\s*=\\s*([^\\n]+)");
    private static final Pattern PASSWORD_LINE = Pattern.compile("password\\s*=\\s*([^\\n]+)");
    private static final Pattern SN = Pattern.compile(" sn\\s*=\\s*([A-Za-z0-9_.-]+)");

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

        try (InputStream input = context.getContentResolver().openInputStream(bugreport.getUri())) {
            if (input == null) {
                throw new IllegalStateException("Cannot open " + bugreport.getName());
            }
            ParsedFields fields = parseZip(input);
            String code = calculate(fields.salt, fields.password, fields.serialNumber);
            return new Result(code, fields.serialNumber, logs.getName() + "/" + bugreport.getName());
        }
    }

    private static DocumentFile latestLogsFolder(DocumentFile root) {
        DocumentFile best = null;
        DocumentFile[] files = root.listFiles();
        if (files == null) {
            return null;
        }
        for (DocumentFile file : files) {
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
        DocumentFile[] files = folder.listFiles();
        if (files == null) {
            return null;
        }
        for (DocumentFile file : files) {
            String name = file.getName();
            if (file.isFile() && name != null && name.startsWith("bugreport-") && name.endsWith(".zip")) {
                if (best == null || name.compareTo(best.getName()) > 0) {
                    best = file;
                }
            }
        }
        return best;
    }

    private static ParsedFields parseZip(InputStream zipInput) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(zipInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name == null || !name.endsWith(".txt")) {
                    continue;
                }
                ParsedFields fields = parseTxtEntry(zip);
                if (fields != null) {
                    return fields;
                }
            }
        }
        throw new IllegalStateException("salt/password/sn fields not found");
    }

    private static ParsedFields parseTxtEntry(InputStream input) throws Exception {
        String saltText = null;
        String passwordText = null;
        String serial = null;
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder), 64 * 1024);
        String line;
        while ((line = reader.readLine()) != null) {
            String lineSalt = lastMatch(SALT_BRACKET, line);
            String linePassword = lastMatch(PASSWORD_BRACKET, line);
            if (lineSalt == null) {
                lineSalt = stripOptionalBrackets(lastMatch(SALT_LINE, line));
            }
            if (linePassword == null) {
                linePassword = stripOptionalBrackets(lastMatch(PASSWORD_LINE, line));
            }
            String lineSerial = lastMatch(SN, line);
            if (lineSalt != null) {
                saltText = lineSalt;
            }
            if (linePassword != null) {
                passwordText = linePassword;
            }
            if (lineSerial != null) {
                serial = lineSerial;
            }
        }
        if (saltText == null || passwordText == null || serial == null) {
            return null;
        }
        List<Integer> salt = parseIntList(saltText);
        List<Integer> password = parseIntList(passwordText);
        if (salt.isEmpty() || password.isEmpty()) {
            return null;
        }
        return new ParsedFields(salt, password, serial);
    }

    private static String lastMatch(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1).trim();
        }
        return value;
    }

    private static String stripOptionalBrackets(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
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
