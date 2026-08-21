package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the two explicit gallery actions through the existing durable photo pipelines. */
public class GalleryPhotoActionWiringTest {
    private static String source() throws Exception {
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
    public void mainAndAlternatePagesPlaceGalleryToTheRightInEqualRows() throws Exception {
        String main = source();
        String mainPage = section(main,
            "workflowArtifactPanel = workflowArtifactBox();",
            "LinearLayout submitPanel = panel();");
        int mainCamera = mainPage.indexOf(
            "equalActionButton(t(\"take_next_photo\"");
        int mainGallery = mainPage.indexOf(
            "equalActionButton(t(\"choose_gallery_photo\"");
        assertTrue(mainCamera >= 0 && mainGallery > mainCamera);
        assertTrue(mainPage.contains("LinearLayout photoActions = row();"));
        assertTrue(mainPage.contains("v -> pickNextPhotoFromGallery()"));

        String alternatePage = section(main,
            "capturePanel.addView(compactLabel(t(\"alternate_entry_photo\")));",
            "root.addView(capturePanel);");
        int alternateCamera = alternatePage.indexOf(
            "equalActionButton(t(\"alternate_entry_add_photo\"");
        int alternateGallery = alternatePage.indexOf(
            "equalActionButton(t(\"choose_gallery_photo\"");
        assertTrue(alternateCamera >= 0 && alternateGallery > alternateCamera);
        assertTrue(alternatePage.contains("LinearLayout alternatePhotoActions = row();"));
        assertTrue(alternatePage.contains("v -> pickAlternateEntryPhotoFromGallery()"));

        String equalButton = section(main,
            "private Button equalActionButton(",
            "private Button iconButton(");
        assertTrue(equalButton.contains(
            "0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f"));
    }

    @Test
    public void explicitGalleryActionsReuseConfiguredDurableTargets() throws Exception {
        String main = source();
        String alternate = section(main,
            "private void captureAlternateEntryPhoto()",
            "private File createAlternateEntryPhotoOutputFile()");
        assertTrue(alternate.contains(
            "captureAlternateEntryPhoto(PhotoInputSourceRules.GALLERY)"));
        assertTrue(alternate.contains(
            "AlternateEntryRules.photoInputSource(alternateEntryConfig)"));
        assertTrue(alternate.contains("createAlternateEntryReservationLocked("));
        assertTrue(alternate.indexOf("maxPhotos > 0")
            < alternate.indexOf("createAlternateEntryReservationLocked("));
        assertTrue(alternate.indexOf(
            "AlternateEntryRules.photoInputSource(alternateEntryConfig)")
            < alternate.indexOf("createAlternateEntryReservationLocked("));
        assertTrue(alternate.indexOf(".commit()")
            < alternate.indexOf("startActivityForResult(picker"));

        String nextPhoto = section(main,
            "private void captureNextPhoto()",
            "private void captureSupplementalPhoto(");
        assertTrue(nextPhoto.contains(
            "captureNextPhoto(PhotoInputSourceRules.GALLERY)"));
        assertTrue(nextPhoto.contains(
            "captureNextSlotPhoto(inputSourceOverride)"));
        assertTrue(nextPhoto.contains(
            "beginSlotCapture(next[0], next[1], inputSourceOverride)"));
        assertTrue(nextPhoto.contains(
            "actionPhotoInputSource(slot, inputSourceOverride)"));

        String sourceResolver = section(main,
            "private String actionPhotoInputSource(",
            "private boolean ensurePhotoInputSourceReady(");
        assertTrue(sourceResolver.contains("configuredPhotoInputSource(owner)"));
        assertTrue(sourceResolver.contains("PhotoInputSourceRules.GALLERY"));
        assertFalse(sourceResolver.contains("ensureCameraPermission()"));
    }

    @Test
    public void galleryResultsStillImportPrivatelyAndCommitThroughExistingPaths()
            throws Exception {
        String main = source();
        String dispatch = section(main,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(");
        assertTrue(dispatch.contains("REQ_PICK_MAIN_PHOTO_FROM_GALLERY"));
        assertTrue(dispatch.contains("REQ_PICK_ALTERNATE_PHOTO_FROM_GALLERY"));
        assertTrue(dispatch.contains("PrivateJpegImporter.importImage("));
        assertTrue(dispatch.contains("saveDraft(true, true)"));

        String alternateResult = section(main,
            "private void handleAlternateEntryPhotoResult(",
            "private void clearPendingAlternateEntryPhoto()");
        assertTrue(alternateResult.contains("PrivateJpegImporter.importImage("));
        assertTrue(alternateResult.contains("materializeAlternateEntryPhoto("));
        assertTrue(alternateResult.contains(
            "if (resultCode != RESULT_OK) {\n"
                + "            clearAlternateEntryReservation(reservation, true);"));
        int importFailure = alternateResult.indexOf("catch (Exception importFailure)");
        int exactClear = alternateResult.indexOf(
            "clearAlternateEntryReservation(reservation, true)", importFailure);
        assertTrue(importFailure >= 0 && exactClear > importFailure);
    }
}
