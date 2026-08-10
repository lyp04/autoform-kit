package com.autoformkit.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Diagnostics {
    private static final String CRASH_FILE = "last-crash.txt";
    private static final String LOG_FILE = "diagnostic-log.txt";
    private static final int MAX_LOG_BYTES = 24000;
    private static final int MAX_SUPPORT_TEXT_CHARS = 8000;
    private static final Pattern URL = Pattern.compile(
        "(?i)\\b(?:https?://|wss?://)[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern HOST_NAME = Pattern.compile(
        "(?i)\\b(?:[A-Z0-9-]+\\.)+(?:com|net|org|cn|io|dev|app|cloud)\\b");
    private static final Pattern PRIVATE_PATH = Pattern.compile(
        "(?i)(?:/data/|/storage/|/sdcard/|/mnt/|/Users/)[^\\s:;,]+(?:/[^\\s:;,]+)*");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(access[ _-]?key|catalog[ _-]?key|authorization|bearer|cookie|password|token|ticket|account|username|profile|entry)"
            + "(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern SERIAL_ASSIGNMENT = Pattern.compile(
        "(?i)\\b(sn|serial|base[ _-]?sn|new[ _-]?sn)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern LONG_IDENTIFIER = Pattern.compile(
        "\\b(?=[A-Za-z0-9_-]{12,64}\\b)(?=[A-Za-z0-9_-]*[0-9])[A-Za-z0-9_-]+\\b");
    private static final Pattern NUMERIC_IDENTIFIER = Pattern.compile("\\b[0-9]{8,20}\\b");

    private Diagnostics() {
    }

    public static synchronized void append(Context context, String message) {
        if (context == null || message == null) return;
        try {
            File file = new File(context.getFilesDir(), LOG_FILE);
            trimIfNeeded(file);
            String line = timestamp() + " " + message + "\n";
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void writeCrash(Context context, Thread thread, Throwable throwable) {
        if (context == null || throwable == null) return;
        try {
            File file = new File(context.getFilesDir(), CRASH_FILE);
            StringWriter writer = new StringWriter();
            writer.append("Time: ")
                .append(timestamp())
                .append("\nThread: ")
                .append(thread == null ? "unknown" : thread.getName())
                .append("\nAndroid SDK: ")
                .append(String.valueOf(android.os.Build.VERSION.SDK_INT))
                .append("\n\n");
            throwable.printStackTrace(new PrintWriter(writer));
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(writer.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            append(context, "Java crash captured: " + throwable.getClass().getName() + ": " + throwable.getMessage());
        } catch (Exception ignored) {
        }
    }

    public static synchronized String readCrash(Context context) {
        return readText(context, CRASH_FILE, 4000);
    }

    public static synchronized String readLog(Context context) {
        return readText(context, LOG_FILE, 4000);
    }

    /**
     * Removes credentials, endpoints, identifiers and private filesystem locations before text is
     * shown in or copied from the formal build's hidden support panel.
     */
    public static String sanitizeForSupport(String text) {
        String value = text == null ? "" : text;
        value = URL.matcher(value).replaceAll("[url]");
        value = EMAIL.matcher(value).replaceAll("[email]");
        value = HOST_NAME.matcher(value).replaceAll("[host]");
        value = PRIVATE_PATH.matcher(value).replaceAll("[path]");
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1$2[redacted]");
        value = SERIAL_ASSIGNMENT.matcher(value).replaceAll("$1$2[redacted]");
        value = LONG_IDENTIFIER.matcher(value).replaceAll("[identifier]");
        value = NUMERIC_IDENTIFIER.matcher(value).replaceAll("[identifier]");
        if (value.length() > MAX_SUPPORT_TEXT_CHARS) {
            value = value.substring(value.length() - MAX_SUPPORT_TEXT_CHARS);
        }
        return value;
    }

    private static void trimIfNeeded(File file) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return;
        try {
            String text = readFile(file);
            int keepFrom = Math.max(0, text.length() - (MAX_LOG_BYTES / 2));
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.substring(keepFrom).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static String readText(Context context, String name, int limit) {
        if (context == null) return "";
        File file = new File(context.getFilesDir(), name);
        if (!file.exists()) return "";
        try {
            String text = readFile(file);
            return text.length() > limit ? text.substring(Math.max(0, text.length() - limit)) : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFile(File file) throws Exception {
        try (InputStream input = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
