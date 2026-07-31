package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for Android lifecycle/editor ordering that host JVM tests cannot invoke. */
public class PanelConnectionBoundaryWiringTest {
    @Test
    public void validationAndPartialTupleGuardPrecedeEveryMutationAndPanelNetworkPath()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String save = between(main,
            "private void savePanelConnection(String panelBaseInput, String catalogKeyInput,\n"
                + "                                     String expectedOldBase, String expectedOldKey,\n"
                + "                                     PanelConnectionInputRules.Source source,",
            "private void promptPanelConnectionAlternateDiscard(");
        assertBefore(save, "PanelConnectionInputRules.validate(",
            "legacyAStepContinuationPresent()");
        assertBefore(save, "PanelConnectionInputRules.validate(",
            "alternateEntryPanelChangeCleanupEvidencePresent()");
        assertBefore(save, "PanelConnectionInputRules.validate(",
            "migrateLegacyPanelBoundState()");
        assertTrue(main.contains("PanelConnectionInputRules.Source.MANUAL, false"));
        assertTrue(main.contains("PanelConnectionInputRules.Source.PAIRING, false"));

        String sync = between(main,
            "private void synchronizePanelConnection(boolean interactive, boolean foreground,",
            "private void handlePanelRefreshFinished(");
        assertBefore(sync, "base.isEmpty() != key.isEmpty()", "checkPairedRetry(");
        assertBefore(sync, "base.isEmpty() != key.isEmpty()", "checkOnForeground(");
        assertBefore(sync, "base.isEmpty() != key.isEmpty()", "checkOnStartup(");
        assertBefore(sync, "base.isEmpty() != key.isEmpty()", "AppConfig.refresh(");
    }

    @Test
    public void receiptIsAtomicAndStartupBlocksAllNetworkUntilRecovery() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String onCreate = between(main, "protected void onCreate(Bundle savedInstanceState)",
            "public void onWindowFocusChanged(boolean hasFocus)");
        assertBefore(onCreate, "recoverPanelConnectionAlternateCleanupReceipt()",
            "PanelPairCacheCoordinator.loadActivePair(this)");
        assertBefore(onCreate, "recoverPanelConnectionAlternateCleanupReceipt()",
            "updateManager.checkOnStartup()");
        assertBefore(onCreate, "recoverPanelConnectionAlternateCleanupReceipt()",
            "synchronizePanelConnection(false)");
        assertBefore(onCreate, "recoverPanelConnectionAlternateCleanupReceipt()",
            "refreshCaptcha()");

        String save = between(main,
            "private void savePanelConnection(String panelBaseInput, String catalogKeyInput,\n"
                + "                                     String expectedOldBase, String expectedOldKey,\n"
                + "                                     PanelConnectionInputRules.Source source,",
            "private void promptPanelConnectionAlternateDiscard(");
        assertTrue(main.contains("alternateEntryReservationStorageAmbiguous\n"
            + "                || hasAlternateEntryPendingData()"));
        assertTrue(save.contains("stagePanelConnectionAlternateCleanup("));
        assertTrue(save.contains("approvedAlternateEvidenceSha256"));
        assertBefore(save, "currentAlternateEvidence.sha256.equals(",
            "capturePanelConnectionAlternateCleanupReceiptLocked(");
        assertBefore(save, "stagePanelConnectionAlternateCleanup(", "saved = editor.commit()");
        assertBefore(save, "if (!saved)", "recoverPanelConnectionAlternateCleanupReceipt()");
        assertFalse(save.contains("clearStoredAlternateEntryDraft(true)"));
        assertFalse(save.contains("clearAlternateEntrySession(true)"));

        String recovery = between(main,
            "private boolean recoverPanelConnectionAlternateCleanupReceipt()",
            "/** Clears only process memory after the new connection commit");
        assertBefore(recovery,
            "PanelConnectionAlternateCleanupRecovery.deleteCapturedPhotos(",
            ".remove(PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY)");

        String app = source("app/src/com/autoformkit/app/App.java",
            "src/com/autoformkit/app/App.java");
        assertTrue(app.contains("if (!panelConnectionAlternateCleanupPendingOrUnreadable())"));
        assertTrue(app.contains("PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY"));
    }

    private static String source(String repositoryPath, String modulePath) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{cwd.resolve(repositoryPath), cwd.resolve(modulePath)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + repositoryPath);
    }

    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) throw new AssertionError("source markers not found");
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("missing marker: " + first, firstIndex >= 0);
        assertTrue("missing marker: " + second, secondIndex >= 0);
        assertTrue("expected ordering", firstIndex < secondIndex);
    }
}
