package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source guard for the one-time, exact-pair adoption of signed-v1 global Panel state. */
public class LegacyPanelStateMigrationWiringTest {
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
    public void legacyOperationalStateIsShapeAndActiveProfileBound() throws Exception {
        String source = mainActivitySource();
        String validation = section(source,
            "private boolean validRollbackPreference(",
            "/** Adopt only an exact receipt-proven signed-v1 change");

        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validRoundLedger(raw)"));
        assertTrue(validation.contains("RollbackMirrorRules.validStringArray(raw)"));
        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validDailyStats(raw)"));
        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validRoundLedger(raw, allProfiles)"));
        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validPreviousRoundKey("));
        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validDailyStats(raw, allProfiles)"));
    }

    @Test
    public void firstAdoptionRequiresGlobalOnlyExactPairAndCompatibleOwner()
            throws Exception {
        String method = section(mainActivitySource(),
            "private String readAndMirrorRollbackPreference(",
            "private boolean rollbackPreferenceMirrored(");

        assertTrue(method.contains("ROUND_LEDGER_KEY.equals(legacyKey)"));
        assertTrue(method.contains("legacyKey.startsWith(\"prevRoundMissing_\")"));
        assertTrue(method.contains("legacyKey.startsWith(DAILY_STATS_PREFIX)"));
        assertFalse(method.contains("DRAFT_STORE_KEY.equals(legacyKey)"));
        assertFalse(method.contains("MANUAL_QUEUE_KEY.equals(legacyKey)"));
        assertTrue(method.contains(
            "MainDraftSnapshotRules.verifiedLegacyMigrationReceipt("));
        assertTrue(method.contains("legacyMainDraftMigrationReceipt()"));
        assertTrue(method.contains("activeCatalogVersion, BuildConfig.VERSION_CODE"));
        assertTrue(method.contains("currentPanelPairSha256()"));
        assertTrue(method.contains(
            "RollbackMirrorRules.initialLegacyAdoptionReceipt("));
        assertTrue(method.contains(
            "hasScoped, hasLegacy, legacyRaw, legacyOwnedByActiveCatalog"));
        assertTrue(method.contains("!prefs.contains(ROLLBACK_GLOBAL_OWNER_KEY)"));
        assertTrue(method.contains("|| rollbackGlobalOwnerMatches()"));
        assertTrue(method.contains(
            "persistedReceipt != null\n            ? persistedReceipt : adoptionReceipt"));
    }

    @Test
    public void adoptionUsesOneMirrorCommitAndNeverReturnsUncommittedBytes()
            throws Exception {
        String source = mainActivitySource();
        String writer = section(source,
            "private SharedPreferences.Editor putMirroredRollbackPreference(",
            "private boolean validRollbackPreference(");
        String reader = section(source,
            "private String readAndMirrorRollbackPreference(",
            "private boolean rollbackPreferenceMirrored(");

        assertTrue(writer.contains(".putString(panelStatePreferenceKey(logicalKey), value)"));
        assertTrue(writer.contains(".putString(logicalKey, value)"));
        assertTrue(writer.contains(
            ".putString(ROLLBACK_GLOBAL_OWNER_KEY, currentConnectionNamespace())"));
        assertTrue(writer.contains(
            ".putString(rollbackMirrorReceiptPreferenceKey(logicalKey), receipt.toString())"));
        assertTrue(reader.contains(
            "putMirroredRollbackPreference(\n                prefs.edit(), legacyKey, decision.value).commit()"));
        assertTrue(reader.contains(
            "!committed || !rollbackPreferenceMirrored(legacyKey, fallback)"));
        assertTrue(reader.contains("blockedRollbackMirrors.add(legacyKey)"));
        assertTrue(reader.contains("return fallback;"));
        assertFalse(reader.contains(
            "return decision.value.isEmpty() ? fallback : decision.value"));

        // Pre-existing scoped reconciliation keeps its established ownership path; only the
        // global-only first-adoption branch is new.
        assertTrue(reader.contains(
            "validRollbackPreference(legacyKey, scopedRaw), true, false, 0L"));
        assertTrue(reader.contains("hasScoped && scopedRaw.equals(legacyRaw)"));
    }

    @Test
    public void candidatePromotionReconcilesLegacyStateAndRetainsRecoveryFile()
            throws Exception {
        String source = mainActivitySource();
        String readiness = section(source,
            "private boolean legacyPanelStateReadyForCachePromotion()",
            "private boolean legacyAStepContinuationPresent()");
        String safeBoundary = section(source,
            "private boolean safeToInstallBoundPanelSnapshot()",
            "/**\n     * UI state is checked before taking HANDOFF_LOCK");
        String promotion = section(source,
            "private PanelPairCacheCoordinator.Promotion\n            promotePanelPairCandidatesAtSafeBoundary()",
            "private boolean maybeInstallBoundPanelSnapshotAtSafeBoundary()");
        String reconciliation = section(source,
            "private boolean reconcileLegacyPanelBoundState(boolean retireLegacyQueueFile)",
            "/** Bind and retire cross-Panel legacy mirrors immediately before changing Panel/key. */");

        assertTrue(readiness.contains("reconcileLegacyPanelBoundState(false)"));
        assertTrue(readiness.contains(
            "LegacyUpgradeSafetyRules.cachePromotionAllowed(resolved, settings)"));
        assertTrue(safeBoundary.contains(
            "if (!legacyPanelStateReadyForCachePromotion()) return false;"));
        assertTrue(promotion.contains("if (!safeToInstallBoundPanelSnapshot())"));
        assertTrue(promotion.contains("PanelPairCacheCoordinator.promoteCandidates("));

        int preserve = reconciliation.indexOf(
            "if (!retireLegacyQueueFile) return true;");
        int delete = reconciliation.indexOf("AtomicCacheFile.delete(legacyQueue);");
        assertTrue(preserve >= 0);
        assertTrue(delete > preserve);
    }

    @Test
    public void legacyCameraContinuationExplicitlyBlocksPanelSwitchBeforeCommit()
            throws Exception {
        String save = section(mainActivitySource(),
            "private void savePanelConnection(String panelBaseInput, String catalogKeyInput,\n                                     String expectedOldBase, String expectedOldKey)",
            "private void resetPanelBoundState(boolean hadTokenBeforeConnectionChange,");

        int legacyBlock = save.indexOf(
            "connectionChanged && legacyAStepContinuationPresent()");
        int preferencesWrite = save.indexOf("SharedPreferences.Editor editor = prefs.edit();");
        assertTrue(legacyBlock >= 0);
        assertTrue(preferencesWrite > legacyBlock);
        assertTrue(save.contains("legacy_a_step_upgrade_blocked_detail"));
    }

    @Test
    public void printReconcileResolvesAndChecksLocalLedgerBeforeAnyRemoteGet()
            throws Exception {
        String method = section(mainActivitySource(),
            "private void loadPrintReconcile(",
            "private static final class PrintRemoteContext");

        int localRead = method.indexOf(
            "List<JSONObject> rounds = loadRecentRounds(3, context.binding.profileId)");
        int blockedCheck = method.indexOf(
            "blockedRollbackMirrors.contains(ROUND_LEDGER_KEY)");
        int printerGet = method.indexOf("context.api.printerState()");
        int serialWalk = method.indexOf("verifyRoundAgainstCloud(");
        assertTrue(localRead >= 0);
        assertTrue(blockedCheck > localRead);
        assertTrue(printerGet > blockedCheck);
        assertTrue(serialWalk > printerGet);
    }

    @Test
    public void receiptBoundHistoryUsesShapeOnlyAndRemoteWalkFiltersCurrentProfile()
            throws Exception {
        String source = mainActivitySource();
        String validation = section(source,
            "private boolean validRollbackPreference(",
            "/** Adopt only an exact receipt-proven signed-v1 change");
        String recent = section(source,
            "private List<JSONObject> loadRecentRounds(",
            "// After a remote verify resolves");
        String reconcile = section(source,
            "private void loadPrintReconcile(",
            "private static final class PrintRemoteContext");

        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validRoundLedger(raw)"));
        assertTrue(validation.contains(
            "LegacyPanelStateMigrationRules.validDailyStats(raw)"));
        assertTrue(validation.contains(
            "legacyRollbackPreferenceOwnedByActiveCatalog("));
        assertTrue(recent.contains(
            "profileId.equals(r.optString(\"profileId\", \"\"))"));
        assertTrue(reconcile.contains(
            "loadRecentRounds(3, context.binding.profileId)"));
    }

    @Test
    public void unresolvedOrUndurablePreviousRoundStateSuppressesNotification()
            throws Exception {
        String source = mainActivitySource();
        String notify = section(source,
            "private void notifyRoundToNotify(",
            "private Set<String> loadPrevRoundMissing(");
        String save = section(source,
            "private boolean savePrevRoundMissing(",
            "// ---- Local round ledger");

        int load = notify.indexOf("loadPrevRoundMissing(profileId)");
        int blocked = notify.indexOf(
            "blockedRollbackMirrors.contains(previousRoundKey)");
        int durable = notify.indexOf("!savePrevRoundMissing(profileId, thisRound)");
        int outbound = notify.indexOf("postNotifyEvent(");
        assertTrue(load >= 0);
        assertTrue(blocked > load);
        assertTrue(durable > blocked);
        assertTrue(outbound > durable);
        assertTrue(save.contains(
            "putMirroredRollbackPreference(\n            prefs.edit(), key, arr.toString()).commit()"));
        assertTrue(save.contains("!rollbackPreferenceMirrored(key, \"\")"));
        assertTrue(save.contains("blockedRollbackMirrors.add(key)"));
        assertFalse(save.contains(".apply()"));
    }
}
