package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PanelConnectionAlternateCleanupRecoveryTest {
    private static final String BASE = "https://new-panel.example.invalid";
    private static final String KEY = "fictional-new-read-key";
    private static final String OLD_NAMESPACE = "0123456789abcdef0123";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void crashReplayDeletesFilesIdempotentlyOnlyAfterSourcesAreGone() throws Exception {
        File photos = temporary.newFolder("photos");
        File nested = new File(photos, "batch");
        assertTrue(nested.mkdir());
        File photo = new File(nested, "one.jpg");
        Files.write(photo.toPath(), "fictional-photo".getBytes(StandardCharsets.UTF_8));
        PanelConnectionAlternateCleanupReceipt receipt = receipt("batch/one.jpg");
        Map<String, Object> committed = new HashMap<>();
        committed.put(PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY,
            receipt.toJson().toString());

        assertTrue(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            committed, receipt, BASE, KEY, photos));
        assertFalse(photo.exists());
        // A crash before receipt removal replays the same receipt; an absent file is complete.
        assertTrue(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            committed, receipt, BASE, KEY, photos));
    }

    @Test
    public void survivingCapturedSourceBlocksBeforeAnyPhotoDelete() throws Exception {
        File photos = temporary.newFolder("photos-source-block");
        File photo = new File(photos, "one.jpg");
        Files.write(photo.toPath(), new byte[]{1, 2, 3});
        PanelConnectionAlternateCleanupReceipt receipt = receipt("one.jpg");
        Map<String, Object> inconsistent = new HashMap<>();
        inconsistent.put("pending_alternate_entry_draft_json_" + OLD_NAMESPACE,
            "fictional-draft");
        final int[] deletes = {0};

        assertFalse(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            inconsistent, receipt, BASE, KEY, photos, file -> {
                deletes[0]++;
                return file.delete();
            }));
        assertTrue(photo.exists());
        assertTrue(deletes[0] == 0);
    }

    @Test
    public void partialOrDifferentTupleAndDeleteFailureKeepPhoto() throws Exception {
        File photos = temporary.newFolder("photos-tuple-block");
        File photo = new File(photos, "one.jpg");
        Files.write(photo.toPath(), new byte[]{4, 5, 6});
        PanelConnectionAlternateCleanupReceipt receipt = receipt("one.jpg");

        assertFalse(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            Collections.emptyMap(), receipt, BASE, "", photos));
        assertFalse(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            Collections.emptyMap(), receipt,
            "https://other-panel.example.invalid", KEY, photos));
        assertFalse(PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(
            Collections.emptyMap(), receipt, BASE, KEY, photos, file -> false));
        assertTrue(photo.exists());
    }

    private static PanelConnectionAlternateCleanupReceipt receipt(String photo) {
        return PanelConnectionAlternateCleanupReceipt.validate(
            "0123456789abcdef0123456789abcdef", OLD_NAMESPACE,
            AppConfig.connectionSecurityId(BASE, KEY), Collections.singletonList(photo));
    }
}
