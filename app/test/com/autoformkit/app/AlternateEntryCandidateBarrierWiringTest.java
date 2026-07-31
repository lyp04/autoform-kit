package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AlternateEntryCandidateBarrierWiringTest {
    private static String source() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    @Test
    public void pendingPathObservationIsNeverDraftOrContinuationProof() throws Exception {
        String main = source();
        String pending = section(main,
            "private boolean hasAlternateEntryPendingData()",
            "private void markAlternateEntryWorkEstablishedLocked()");
        assertTrue(pending.contains("alternateEntrySerial"));
        assertTrue(pending.contains("alternateEntryPhotos"));
        assertFalse(pending.contains("PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY"));
        assertFalse(pending.contains("pendingAlternateEntryPhotoPath"));

        String draft = section(main,
            "private AlternateEntryDraftState inMemoryAlternateEntryDraftState()",
            "private String alternateEntryDraftPreferenceKey()");
        assertFalse(draft.contains("PENDING_ALTERNATE_ENTRY_PHOTO_PATH_KEY"));
        assertFalse(draft.contains("pendingAlternateEntryPhotoPath"));
    }

    @Test
    public void typedRemoveToggleAndRebindMutationsRecheckInsideHandoffLock() throws Exception {
        String main = source();
        String editingBlock = section(main,
            "private boolean alternateEntryEditingBlocked()",
            "private void submitAlternateEntry()");
        assertTrue(editingBlock.contains(
            "hasPendingAlternateEntryAsyncReservationEvidence()"));

        String typed = section(main,
            "private void setAlternateEntrySerial(String serial, String source)",
            "private void startAlternateEntryScan()");
        assertTrue(typed.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(typed.indexOf("alternateEntryExpansionAllowedLocked()")
            < typed.indexOf("alternateEntrySerial ="));
        assertTrue(typed.indexOf("alternateEntrySerial =")
            < typed.indexOf("markAlternateEntryWorkEstablishedLocked()"));

        String ui = section(main,
            "private void refreshAlternateEntryUi()",
            "private void exitAlternateEntryPage()");
        assertTrue(ui.indexOf("alternateEntryExpansionAllowedLocked()")
            < ui.indexOf("alternateEntryPhotos.remove(path)"));
        assertTrue(ui.indexOf("alternateEntryExpansionAllowedLocked()")
            < ui.indexOf("alternateEntryToggleStates.put(policy.key, checked)"));

        String rebind = section(main,
            "private void bindAlternateEntryForNewWork(",
            "private boolean alternateEntryBindingStillCurrent(");
        assertTrue(rebind.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(rebind.indexOf("alternateEntryExpansionAllowedLocked()")
            < rebind.indexOf("bindAlternateEntry(source, entry, catalog)"));

        String exit = section(main,
            "private void exitAlternateEntryPage()",
            "private void applyTypedAlternateEntrySerial()");
        int exitLock = exit.indexOf("synchronized (UpdateInstallRules.HANDOFF_LOCK)");
        int exitClear = exit.indexOf("alternateEntryToggleStates.clear()");
        int exitLockEnd = exit.indexOf("\n        }", exitClear);
        assertTrue(exitLock >= 0 && exitLock < exitClear);
        assertTrue(exitClear < exitLockEnd);
    }

    @Test
    public void scanAndPhotoLaunchPersistExactReservationsBeforeExternalActivity() throws Exception {
        String main = source();
        String scan = section(main,
            "private void startAlternateEntryScan()",
            "private void handleAlternateEntryScanResult(");
        assertTrue(scan.contains("createAlternateEntryReservationLocked("));
        assertTrue(scan.contains("PENDING_ALTERNATE_ENTRY_SCAN_RESERVATION_KEY"));
        assertTrue(scan.indexOf(".commit()") < scan.indexOf("startActivityForResult"));

        String photo = section(main,
            "private void captureAlternateEntryPhoto()",
            "private File createAlternateEntryPhotoOutputFile()");
        assertTrue(photo.contains("createAlternateEntryReservationLocked("));
        assertTrue(photo.contains("PENDING_ALTERNATE_ENTRY_PHOTO_RESERVATION_KEY"));
        assertTrue(photo.indexOf(".commit()") < photo.indexOf("startActivityForResult"));
        assertFalse(photo.contains("markAlternateEntryWorkEstablishedLocked()"));
        assertFalse(photo.contains("persistAlternateEntryDraftBestEffort()"));
    }

    @Test
    public void callbacksAndOcrChoiceConsumeOnlyTheirExactReservation() throws Exception {
        String main = source();
        String scanResult = section(main,
            "private void handleAlternateEntryScanResult(",
            "private void clearPendingAlternateEntryScanGuard()");
        assertTrue(scanResult.contains("exactStoredAlternateEntryReservationLocked("));
        assertTrue(scanResult.contains("materializeAlternateEntrySerial(reservation"));
        assertFalse(scanResult.contains("alternateEntryEditingBlocked()"));
        assertFalse(scanResult.contains("setAlternateEntrySerial("));

        String ocr = section(main,
            "private void recognizeAlternateEntrySerialFromPhoto(",
            "private void showAlternateEntryOcrCandidates(");
        assertTrue(ocr.contains("beginReservedAlternateEntryBoundOperation("));
        assertTrue(ocr.contains("alternateEntryReservationMayMaterializeLocked("));

        String chooser = section(main,
            "private void showAlternateEntryOcrCandidates(",
            "private void captureAlternateEntryPhoto()");
        assertTrue(chooser.contains("materializeAlternateEntrySerial("));
        assertTrue(chooser.contains("clearAlternateEntryReservation("));
        assertFalse(chooser.contains("setAlternateEntrySerial("));

        String photoResult = section(main,
            "private void handleAlternateEntryPhotoResult(",
            "private void clearPendingAlternateEntryPhoto()");
        assertTrue(photoResult.contains("reservation.outputPath.equals(path)"));
        assertTrue(photoResult.contains("materializeAlternateEntryPhoto(reservation"));
        assertFalse(photoResult.contains("alternateEntryPhotos.add("));
        assertFalse(photoResult.contains("alternateEntryEditingBlocked()"));

        String commit = section(main,
            "private boolean commitMaterializedAlternateEntryReservationLocked(",
            "private boolean materializeAlternateEntrySerial(");
        assertTrue(commit.contains("putString(alternateEntryDraftPreferenceKey()"));
        assertTrue(commit.contains("putString(alternateEntryContinuationProofPreferenceKey()"));
        assertTrue(commit.contains("remove(alternateEntryReservationPreferenceKey("));
        assertTrue(commit.contains("consumeAlternateReservation("));
        assertTrue(commit.indexOf("consumeAlternateReservation(")
            < commit.indexOf("editor.commit()"));
    }

    @Test
    public void barrierCaptureRestoreAndSubmitAllCarryExactTokens() throws Exception {
        String main = source();
        String barrier = section(main,
            "private boolean unsafeCandidatesBlockActiveUse()",
            "private boolean unsafeContinuationAllowsCurrentWork()");
        assertTrue(barrier.contains("alternateEntryContinuationToken"));
        assertTrue(barrier.contains("liveAlternateEntryReservationPermitsLocked()"));

        String permits = section(main,
            "liveAlternateEntryReservationPermitsLocked()",
            "private AlternateEntryAsyncReservation createAlternateEntryReservationLocked(");
        assertTrue(permits.contains("scan.resultContinuationToken"));
        assertTrue(permits.contains("photo.resultContinuationToken"));

        String restore = section(main,
            "private boolean restoreAlternateEntryState(Bundle state)",
            "private void clearAlternateEntrySession(");
        assertTrue(restore.contains("exactStoredAlternateEntryContinuationTokenLocked"));
        assertTrue(restore.contains("liveAlternateEntryReservationTokensLocked().isEmpty()"));

        String authorize = section(main,
            "private boolean authorizeAlternateWorkerForUnsafeCandidate()",
            "private void publishActiveNotificationSnapshot()");
        assertTrue(authorize.contains("authorizeAlternateWorker("));
        assertTrue(authorize.contains("alternateEntryContinuationToken"));

        String submit = section(main,
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        assertTrue(submit.contains("hasPendingAlternateEntryAsyncReservationEvidence()"));
        assertTrue(submit.indexOf("hasPendingAlternateEntryAsyncReservationEvidence()")
            < submit.indexOf("authorizeAlternateWorkerForUnsafeCandidate()"));
    }
}
