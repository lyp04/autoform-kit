package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level regression guards for interrupted local capture and durable draft deletion. */
public class MainDraftDiscardWiringTest {
    @Test
    public void newForegroundCaptureRetiresAnInterruptedLocalTargetBeforeSaving() throws Exception {
        String prepare = section(mainActivitySource(),
            "private PendingFormOperationRules.Target preparePendingMainFormTarget(",
            "private void restorePendingMainFormTarget()");

        int pending = prepare.indexOf("if (hasPendingMainFormOperation())");
        int restoredOnly = prepare.indexOf(
            "if (!pendingMainFormTargetRestoredFromPreviousProcess)", pending);
        int clear = prepare.indexOf("clearPendingMainFormTarget()", restoredOnly);
        int save = prepare.indexOf("saveDraft(true)", clear);
        assertTrue(pending >= 0 && restoredOnly > pending && clear > restoredOnly);
        assertTrue(save > clear);
        assertFalse(prepare.contains("|| hasPendingMainFormOperation()) return null"));
        assertTrue(prepare.contains(
            "Interrupted main-form operation retired before new \" + kind"));

        String persist = section(mainActivitySource(),
            "private boolean persistPendingMainFormTarget(",
            "private PendingFormOperationRules.Target preparePendingMainFormTarget(");
        assertTrue(persist.contains(
            "pendingMainFormTargetRestoredFromPreviousProcess = false;"));

        String restore = section(mainActivitySource(),
            "private void restorePendingMainFormTarget()",
            "private void applyPendingMainFormTargetToLegacyMemory(");
        assertTrue(restore.contains(
            "pendingMainFormTargetRestoredFromPreviousProcess = true;"));
    }

    @Test
    public void explicitDiscardCommitsDraftAndLocalTargetBeforeClearingUiOrFiles()
            throws Exception {
        String source = mainActivitySource();
        String discard = section(source,
            "private void discardAllDraftsAndResetForm()",
            "private MainDraftSnapshotRules.Binding mainDraftBindingForProfile(");
        int commit = discard.indexOf("if (!clearAllDrafts(true))");
        int memory = discard.indexOf("units.clear()", commit);
        assertTrue(commit >= 0 && memory > commit);
        assertTrue(discard.substring(commit, memory).contains("return;"));
        assertFalse(discard.contains("deleteUnitFiles(unit)"));

        String clear = section(source,
            "private boolean clearAllDrafts(boolean clearPendingLocalOperation)",
            "private JSONObject loadDraftStore()");
        int remoteGuard = clear.indexOf("hasStoredUploadReplayBarrier()");
        int draftRemove = clear.indexOf(".remove(DRAFT_KEY)");
        int targetRemove = clear.indexOf("removePendingMainFormTargetKeys(editor)");
        int durableCommit = clear.indexOf("editor.commit()");
        int memoryReset = clear.indexOf(
            "resetPendingMainFormTargetAfterDurableClear()", durableCommit);
        assertTrue(remoteGuard >= 0 && draftRemove > remoteGuard);
        assertTrue(targetRemove > draftRemove && durableCommit > targetRemove);
        assertTrue(memoryReset > durableCommit);
        assertFalse(clear.contains("remove(UPLOAD_REPLAY"));
        assertFalse(clear.contains("remove(PREVIOUS_STEP"));
    }

    @Test
    public void deletingAUnitRollsBackMemoryAndKeepsFilesUntilDurableSave() throws Exception {
        String delete = section(mainActivitySource(),
            "private void deleteUnit(UnitRecord unit)",
            "private int pruneSubmittedUnits()");
        int remove = delete.indexOf("units.remove(index)");
        int durableSave = delete.indexOf(
            "saveDraft(true, clearPendingOperation)", remove);
        int rollback = delete.indexOf("units.add(Math.min(index, units.size()), unit)",
            durableSave);
        int fileDelete = delete.indexOf("deleteUnitFiles(unit)", rollback);
        assertTrue(remove >= 0 && durableSave > remove && rollback > durableSave);
        assertTrue(fileDelete > rollback);
        assertFalse(delete.contains("saveDraft();"));
        assertTrue(delete.contains("pendingMainFormOperationTargetsUnit(unit)"));
    }

    @Test
    public void discardFailureCopyExistsInEverySupportedLanguage() throws Exception {
        String source = mainActivitySource();
        assertTrue(count(source, "case \"draft_discard_failed\":") == 3);
        assertTrue(count(source, "case \"draft_discard_failed_detail\":") == 3);
    }

    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path path = cwd.resolve("app/src/com/autoformkit/app/MainActivity.java");
        if (!Files.isRegularFile(path)) {
            path = cwd.resolve("src/com/autoformkit/app/MainActivity.java");
        }
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("MainActivity source not found from " + cwd);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        if (startAt < 0 || endAt <= startAt) {
            throw new AssertionError("source markers missing: " + start + " -> " + end);
        }
        return source.substring(startAt, endAt);
    }

    private static int count(String source, String needle) {
        int result = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            result++;
        }
        return result;
    }
}
