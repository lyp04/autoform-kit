package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the explicit, side-effect-free recovery path for an orphaned scanner reservation. */
public class AlternateEntryScanRecoveryWiringTest {
    private static String source() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
            cwd.resolve("src/com/autoformkit/app/MainActivity.java")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
    public void cancellationUsesExactBaseOrThePureSideEffectFreeColdStartRule()
            throws Exception {
        String body = section(source(),
            "private AlternateEntryAsyncReservation cancelableStoredAlternateEntryScanLocked()",
            "private boolean cancelStoredAlternateEntryScan(");
        assertTrue(body.contains("reservation.matches("));
        assertTrue(body.contains("currentConnectionNamespace()"));
        assertTrue(body.contains("currentPanelPairSha256()"));
        assertTrue(body.contains("alternateEntryBindingFingerprint"));
        assertTrue(body.contains("alternateEntryBackendFingerprint"));
        assertTrue(body.contains("alternateEntryReservationBaseStateSha256()"));
        assertTrue(body.contains("String guard = (String) rawGuard"));
        assertFalse(body.contains("alternateEntryOperationMatches(guard)"));
        assertTrue(body.contains("PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY"));
        assertTrue(body.contains(
            "AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan("));
        assertTrue(body.contains("stored.containsKey(alternateEntryDraftPreferenceKey())"));
        assertTrue(body.contains("activeAlternateEntryScanRecoveryBindings(alternateEntryId)"));
    }

    @Test
    public void cancellationDeletesOnlyTheSameScanReservationPair() throws Exception {
        String body = section(source(),
            "private boolean cancelStoredAlternateEntryScan(",
            "private Set<String> liveAlternateEntryReservationTokensLocked()");
        assertTrue(body.contains("cancelableStoredAlternateEntryScanLocked()"));
        assertTrue(body.contains("AlternateEntryScanRecoveryRules.sameReservation(expected, exact)"));
        assertTrue(body.contains("PENDING_ALTERNATE_ENTRY_SCAN_GUARD_KEY"));
        assertTrue(body.contains("PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY"));
        assertFalse(body.contains("PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY"));
        assertFalse(body.contains("PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY"));
        assertFalse(body.contains("alternateEntryDraftPreferenceKey"));
        assertFalse(body.contains("alternateEntryContinuationProofPreferenceKey"));
        assertFalse(body.contains("alternateSubmissionAttemptPreferenceKey"));
        assertFalse(body.contains("UploadReplayBarrier"));
        assertFalse(body.contains(".clear()"));
    }

    @Test
    public void tappingScanOffersTargetedCancelBeforeTheGenericEditingBlock()
            throws Exception {
        String body = section(source(),
            "private void startAlternateEntryScan()",
            "private void handleAlternateEntryScanResult(");
        int recover = body.indexOf("cancelableStoredAlternateEntryScanLocked()");
        int genericBlock = body.indexOf("alternateEntryEditingBlocked()");
        assertTrue(recover >= 0 && genericBlock > recover);
        assertTrue(body.contains("cancelStoredAlternateEntryScan(cancelable)"));
        assertTrue(body.contains("startAlternateEntryScan();"));
        assertTrue(body.contains("alternate_entry_cancel_scan_detail"));
    }
}
