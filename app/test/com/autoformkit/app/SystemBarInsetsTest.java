package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SystemBarInsetsTest {
    @Test
    public void paddingAddsTheReportedSystemInsetToTheOriginalPadding() {
        assertEquals(40, SystemBarInsets.padded(16, 24));
        assertEquals(16, SystemBarInsets.padded(16, 0));
        assertEquals(16, SystemBarInsets.padded(16, -1));
    }

    @Test
    public void repeatedCalculationUsesTheSameBaselineInsteadOfAccumulating() {
        int baseline = 16;
        assertEquals(40, SystemBarInsets.padded(baseline, 24));
        assertEquals(16, SystemBarInsets.padded(baseline, 0));
        assertEquals(60, SystemBarInsets.padded(baseline, 44));
        assertEquals(40, SystemBarInsets.padded(baseline, 24));
    }

    @Test
    public void topPaddingSaturatesInsteadOfOverflowing() {
        assertEquals(Integer.MAX_VALUE,
            SystemBarInsets.padded(Integer.MAX_VALUE - 1, 24));
    }

    @Test
    public void legacyAndroidVersionsKeepTheirExistingDecorBehavior() {
        assertFalse(SystemBarInsets.shouldApply(23));
        assertFalse(SystemBarInsets.shouldApply(34));
        assertTrue(SystemBarInsets.shouldApply(35));
        assertTrue(SystemBarInsets.shouldApply(36));
    }

    @Test
    public void fullPagesAndCameraControlsUseTheSharedInsetHandler() throws Exception {
        String main = source("MainActivity.java");
        String capture = source("CaptureActivity.java");
        String scanner = source("ScannerActivity.java");
        String helper = source("SystemBarInsets.java");

        assertEquals(3, count(main, "setPageContentView(scroll);"));
        assertTrue(main.contains("SystemBarInsets.reserveSystemBars(scroll);"));
        assertTrue(main.contains("SystemBarInsets.requestWhenAttached(insetAwarePageView);"));
        assertTrue(main.contains("SystemBarInsets.requestWhenAttached(scroll);"));
        assertTrue(main.contains("getWindow().setDecorFitsSystemWindows(false);"));
        assertTrue(capture.contains(
            "root, header, footer, orientedChrome, reviewControlsStage);"));
        assertTrue(capture.contains(
            "SystemBarInsets.rotateCameraOverlayInsets(orientedChrome, delta);"));
        assertTrue(capture.contains("SystemBarInsets.requestWhenAttached(root);"));
        assertFalse(scanner.contains("SystemBarInsets."));
        assertTrue(helper.contains("WindowInsets.Type.systemBars()"));
        assertTrue(helper.contains("WindowInsets.Type.displayCutout()"));
        assertTrue(helper.contains("setHeight(topOverlay, padded(topHeight, systemBars.top))"));
        assertTrue(helper.contains(
            "setHeight(bottomOverlay, padded(bottomHeight, systemBars.bottom))"));
        assertTrue(helper.contains("static void rotateCameraOverlayInsets("));
        assertTrue(helper.contains("left = state.top;"));
        assertTrue(helper.contains("top = state.right;"));
        assertTrue(helper.contains("right = state.bottom;"));
        assertTrue(helper.contains("bottom = state.left;"));
        assertTrue(helper.contains("padded(baseline.left, systemBars.left)"));
        assertTrue(helper.contains("padded(baseline.top, systemBars.top)"));
        assertTrue(helper.contains("padded(baseline.right, systemBars.right)"));
        assertTrue(helper.contains("padded(baseline.bottom, systemBars.bottom)"));
        assertFalse(helper.contains("status_bar_height"));
        assertFalse(helper.contains("dp(24)"));

        String install = section(main,
            "private void setPageContentView(ScrollView scroll)",
            "private LinearLayout panel()");
        int reserve = install.indexOf("SystemBarInsets.reserveSystemBars(scroll);");
        int attach = install.indexOf("setContentView(scroll);");
        int request = install.indexOf("SystemBarInsets.requestWhenAttached(scroll);");
        assertTrue(reserve >= 0 && reserve < attach);
        assertTrue(attach < request);

        String configuration = section(main,
            "public void onConfigurationChanged(Configuration newConfig)",
            "protected void onResume()");
        assertTrue(configuration.contains(
            "SystemBarInsets.requestWhenAttached(insetAwarePageView);"));
        assertTrue(helper.contains("root.post(root::requestApplyInsets);"));
    }

    private static int count(String source, String needle) {
        int result = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            result++;
        }
        return result;
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
}
