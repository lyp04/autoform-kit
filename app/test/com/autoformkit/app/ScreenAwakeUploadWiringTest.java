package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the screen-on exception scoped to image transfers instead of whole activities. */
public class ScreenAwakeUploadWiringTest {
    @Test
    public void normalPagesNoLongerForceTheScreenToStayOn() throws Exception {
        String main = source("MainActivity.java");
        String capture = source("CaptureActivity.java");
        String scanner = source("ScannerActivity.java");

        String onCreate = section(main,
            "protected void onCreate(Bundle savedInstanceState)",
            "protected void onSaveInstanceState(Bundle outState)");
        assertFalse(onCreate.contains("FLAG_KEEP_SCREEN_ON"));
        assertFalse(capture.contains("FLAG_KEEP_SCREEN_ON"));
        assertFalse(scanner.contains("FLAG_KEEP_SCREEN_ON"));
    }

    @Test
    public void everyImageTransferUsesTheReferenceCountedFinallyScope() throws Exception {
        String main = source("MainActivity.java");
        String manifest = manifest();
        String service = source("UploadProtectionService.java");
        String helper = section(main,
            "private interface ScreenAwakeUpload<T>",
            "private void showSubmitLoading(int total)");

        assertTrue(manifest.contains(
            "<uses-permission android:name=\"android.permission.WAKE_LOCK\" />"));
        assertTrue(manifest.contains(
            "<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE\" />"));
        assertTrue(manifest.contains(
            "android:name=\".UploadProtectionService\""));
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""));
        assertTrue(helper.contains("activeScreenAwakeUploads++"));
        assertTrue(helper.contains("} finally {"));
        assertTrue(helper.contains("activeScreenAwakeUploads--"));
        assertTrue(helper.contains("PowerManager.PARTIAL_WAKE_LOCK"));
        assertTrue(helper.contains(
            "uploadCpuWakeLock.acquire(UPLOAD_CPU_WAKE_LOCK_TIMEOUT_MS)"));
        assertTrue(helper.contains("uploadCpuWakeLock.release()"));
        assertTrue(helper.contains("UploadProtectionService.start(this)"));
        assertTrue(helper.contains("UploadProtectionService.stop(this)"));
        assertTrue(service.contains("startForeground(NOTIFICATION_ID, notification())"));
        assertTrue(service.contains("return START_NOT_STICKY;"));
        assertTrue(service.contains("this::stopSelf"));
        assertTrue(helper.contains(
            "getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"));
        assertTrue(helper.contains(
            "getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"));

        // Declaration + main upload + alternate-entry upload + both OCR upload paths.
        assertEquals(5, occurrences(main, "runScreenAwakeUpload("));
        assertEquals(1, occurrences(main,
            "getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"));
        assertEquals(1, occurrences(main,
            "getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"));

        String progress = section(main,
            "private void showSubmitLoading(int total)",
            "private void setSubmitProgressMessage(String message)");
        assertTrue(progress.contains("holdSubmitScreenAwakeLease();"));
        assertTrue(progress.contains("releaseSubmitScreenAwakeLease();"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        if (startAt < 0 || endAt <= startAt) {
            throw new AssertionError("source section not found: " + start);
        }
        return source.substring(startAt, endAt);
    }

    private static String source(String fileName) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/").resolve(fileName),
                cwd.resolve("src/com/autoformkit/app/").resolve(fileName)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + fileName);
    }

    private static String manifest() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/AndroidManifest.xml"),
                cwd.resolve("AndroidManifest.xml")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("AndroidManifest.xml not found");
    }
}
