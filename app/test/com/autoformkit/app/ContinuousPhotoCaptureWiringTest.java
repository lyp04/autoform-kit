package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards confirmed-photo durability and seamless handoff to the next required camera target. */
public class ContinuousPhotoCaptureWiringTest {
    @Test
    public void captureRequiresReviewConfirmationBeforeReturningSuccess() throws Exception {
        String capture = source("CaptureActivity.java");
        String saved = section(capture,
            "public void onImageSaved(ImageCapture.OutputFileResults result)",
            "public void onError(ImageCaptureException error)");
        assertTrue(saved.contains("showReview();"));
        assertFalse(saved.contains("setResult(RESULT_OK"));
        assertFalse(saved.contains("finish();"));

        String confirm = section(capture,
            "private void confirmPhoto()",
            "private void clearReviewBitmap()");
        assertTrue(confirm.contains("setResult(RESULT_OK, data);"));
        assertTrue(confirm.contains("EXTRA_CONTINUOUS_CAPTURE"));
        assertTrue(confirm.contains("finishWithoutAnimation();"));

        String retake = section(capture,
            "private void retakePhoto()",
            "private void confirmPhoto()");
        assertTrue(retake.contains("deleteOutput();"));
        assertTrue(retake.contains("scheduleCameraViewportRebind();"));
        assertFalse(capture.contains("cameraProvider.unbindAll()"));
        assertTrue(capture.contains("unbindOwnCameraUseCases()"));
    }

    @Test
    public void confirmedPhotoAndPendingClearShareOneDurableCommit() throws Exception {
        String main = source("MainActivity.java");
        String result = section(main,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data)");
        int durableSave = result.indexOf("saveDraft(true, true)");
        int continuation = result.indexOf("continueRequiredPhotoCapture(transitionNotice)");
        assertTrue(durableSave >= 0);
        assertTrue(continuation > durableSave);
        assertFalse(result.substring(durableSave, continuation)
            .contains("clearPendingMainFormTarget();"));
        assertTrue(result.contains("nextRequiredPhotoUsesCamera()"));
        assertTrue(main.contains("pendingPhotoIsRequiredNextStep()"));
        assertTrue(result.contains("photoCommitted = true;"));
        assertTrue(result.contains("if (photoCommitted)"));
        assertTrue(result.contains("Confirmed photo kept after continuation failure"));
    }

    @Test
    public void slotTransitionNoticeIsShownOverTheNextCamera() throws Exception {
        String main = source("MainActivity.java");
        String capture = source("CaptureActivity.java");
        assertTrue(main.contains(
            "intent.putExtra(CaptureActivity.EXTRA_OPENING_NOTICE,"));
        assertTrue(main.contains("nextCameraOpeningNotice = openingNotice"));
        assertTrue(capture.contains("showOpeningNoticeIfNeeded();"));
        assertTrue(capture.contains("noticeButton(t(\"continue_photo\"), true)"));
        assertTrue(capture.contains("noticeButton(t(\"finish_photos\"), false)"));
        assertTrue(capture.contains("orientedChrome.addView(shade"));
    }

    @Test
    public void failedAtomicDraftCommitRestoresTheOldPreferenceSnapshot() throws Exception {
        String main = source("MainActivity.java");
        String write = section(main,
            "private void writeDraftStore(JSONObject store, boolean durable,\n"
                + "                                 boolean clearPendingLocalOperation)",
            "private String draftStorePreferenceKey()");
        assertTrue(write.contains("PanelConnectionPreferenceTransaction.snapshot(prefs)"));
        assertTrue(write.contains("PENDING_MAIN_FORM_OPERATION_KEY"));
        assertTrue(write.contains("PanelConnectionPreferenceTransaction.restore("));
        assertTrue(write.contains("blockedRollbackMirrors.add(DRAFT_STORE_KEY)"));
        assertTrue(write.contains("mainDraftStorageAmbiguous = true"));
        int failedCommit = write.indexOf("if (!committed)");
        assertTrue(failedCommit >= 0);
        assertTrue(write.indexOf("PanelConnectionPreferenceTransaction.restore(")
            > failedCommit);
        assertTrue(main.contains("if (mainDraftStorageAmbiguous) return false;"));
        assertTrue(main.contains("alert(t(\"draft_save_failed\"), t(\"draft_storage_uncertain\"))"));
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
