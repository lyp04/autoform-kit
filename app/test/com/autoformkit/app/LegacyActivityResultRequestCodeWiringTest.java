package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source guard preventing signed-v1 activity results from entering a new workflow. */
public class LegacyActivityResultRequestCodeWiringTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    @Test
    public void signedV1CodesStayReservedAndAlternateEntryUsesFreshCodes()
            throws Exception {
        String source = mainActivitySource();
        assertTrue(source.contains(
            "REQ_LEGACY_PICK_A_STEP_PHOTO = 2011;"));
        assertTrue(source.contains(
            "REQ_LEGACY_CAPTURE_A_STEP_PHOTO = 2012;"));
        assertTrue(source.contains(
            "REQ_LEGACY_SCAN_A_STEP_ENTRY_SN = 2013;"));
        assertTrue(source.contains(
            "REQ_LEGACY_CAPTURE_A_STEP_ENTRY_PHOTO = 2014;"));
        assertTrue(source.contains(
            "REQ_SCAN_ALTERNATE_ENTRY_SN = 2015;"));
        assertTrue(source.contains(
            "REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO = 2016;"));
        assertTrue(source.contains(
            "REQ_PICK_MAIN_PHOTO_FROM_GALLERY = 2017;"));
        assertTrue(source.contains(
            "REQ_PICK_MAIN_PHOTO_FROM_FILE = 2018;"));
        assertTrue(source.contains(
            "REQ_PICK_ALTERNATE_PHOTO_FROM_GALLERY = 2019;"));
        assertTrue(source.contains(
            "REQ_PICK_ALTERNATE_PHOTO_FROM_FILE = 2020;"));

        String dispatch = section(source,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data)");
        assertTrue(dispatch.contains(
            "if (requestCode == REQ_SCAN_ALTERNATE_ENTRY_SN) {\n"
                + "            handleAlternateEntryScanResult(resultCode, data);"));
        assertTrue(dispatch.contains(
            "handleAlternateEntryPhotoResult(requestCode, resultCode, data);"));
        assertFalse(dispatch.contains("REQ_LEGACY_"));
        assertFalse(dispatch.contains("requestCode == 2011"));
        assertFalse(dispatch.contains("requestCode == 2012"));
        assertFalse(dispatch.contains("requestCode == 2013"));
        assertFalse(dispatch.contains("requestCode == 2014"));

        assertTrue(source.contains(
            "startActivityForResult(intent, REQ_SCAN_ALTERNATE_ENTRY_SN);"));
        assertTrue(source.contains(
            "startActivityForResult(capture, REQ_CAPTURE_ALTERNATE_ENTRY_PHOTO);"));
        String alternateCapture = section(source,
            "private void captureAlternateEntryPhoto()",
            "private File createAlternateEntryPhotoOutputFile()");
        assertFalse(alternateCapture.contains("MediaStore.ACTION_IMAGE_CAPTURE"));
        assertFalse(alternateCapture.contains("Intent fallback"));
    }
}
