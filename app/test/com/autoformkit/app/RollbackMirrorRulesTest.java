package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RollbackMirrorRulesTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final String KEY = "sample_snapshot";

    private static String snapshot(long savedAt, String marker) throws Exception {
        return new JSONObject()
            .put("version", 2)
            .put("profileId", "sample-form")
            .put("savedAt", savedAt)
            .put("marker", marker)
            .put("units", new org.json.JSONArray())
            .toString();
    }

    private static RollbackMirrorRules.Candidate candidate(
            String source, String raw, boolean selfBound) throws Exception {
        return RollbackMirrorRules.Candidate.of(source, raw, true, true, selfBound,
            RollbackMirrorRules.exactSavedAt(new JSONObject(raw)));
    }

    @Test
    public void rollbackChangeNewerThanReceiptBaselineWins() throws Exception {
        String baseline = snapshot(100, "baseline");
        String rollback = snapshot(200, "rollback-change");
        JSONObject receipt = RollbackMirrorRules.newReceipt(CONNECTION, KEY, baseline);
        List<RollbackMirrorRules.Candidate> copies = new ArrayList<>();
        copies.add(candidate("scoped-pref", baseline, true));
        copies.add(candidate("legacy-pref", rollback, false));

        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseNewestSnapshot(
            copies, receipt, CONNECTION, KEY);

        assertEquals(RollbackMirrorRules.Source.LEGACY, decision.source);
        assertEquals(rollback, decision.value);
        assertTrue(decision.mirrorAllowed);
    }

    @Test
    public void receiptDifferenceWinsEvenWhenRollbackClockMovedBackwards() throws Exception {
        String baseline = snapshot(500, "baseline");
        String rollback = snapshot(100, "rollback-after-clock-change");
        JSONObject receipt = RollbackMirrorRules.newReceipt(CONNECTION, KEY, baseline);
        List<RollbackMirrorRules.Candidate> copies = new ArrayList<>();
        copies.add(candidate("scoped-pref", baseline, true));
        copies.add(candidate("legacy-pref", rollback, false));

        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseNewestSnapshot(
            copies, receipt, CONNECTION, KEY);
        assertEquals(RollbackMirrorRules.Source.LEGACY, decision.source);
        assertEquals(rollback, decision.value);
    }

    @Test
    public void equalTimestampConflictAndUnownedCopyFailClosed() throws Exception {
        String first = snapshot(200, "first");
        String second = snapshot(200, "second");
        JSONObject receipt = RollbackMirrorRules.newReceipt(CONNECTION, KEY, first);
        List<RollbackMirrorRules.Candidate> tied = new ArrayList<>();
        tied.add(candidate("scoped-file", first, true));
        tied.add(candidate("legacy-file", second, false));
        assertTrue(RollbackMirrorRules.chooseNewestSnapshot(
            tied, receipt, CONNECTION, KEY).blocked());

        List<RollbackMirrorRules.Candidate> wrongPanel = new ArrayList<>();
        wrongPanel.add(candidate("scoped-file", first, true));
        wrongPanel.add(RollbackMirrorRules.Candidate.of(
            "legacy-file", snapshot(300, "other-panel"), true,
            false, false, 300));
        RollbackMirrorRules.Decision blocked = RollbackMirrorRules.chooseNewestSnapshot(
            wrongPanel, receipt, CONNECTION, KEY);
        assertTrue(blocked.blocked());
        assertFalse(blocked.mirrorAllowed);
        assertEquals(first, blocked.value);
    }

    @Test
    public void malformedCopyDoesNotOverwriteValidCopy() throws Exception {
        String valid = snapshot(100, "valid");
        List<RollbackMirrorRules.Candidate> copies = new ArrayList<>();
        copies.add(candidate("scoped-file", valid, true));
        copies.add(RollbackMirrorRules.Candidate.of(
            "legacy-file", "{", false, false, false, 0));

        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseNewestSnapshot(
            copies, RollbackMirrorRules.newReceipt(CONNECTION, KEY, valid),
            CONNECTION, KEY);
        assertTrue(decision.blocked());
        assertEquals(valid, decision.value);
        assertFalse(decision.mirrorAllowed);
    }

    @Test
    public void receiptBoundUntimestampedLegacyChangeWins() throws Exception {
        String baseline = "[]";
        String rollback = "[\"sample\"]";
        JSONObject receipt = RollbackMirrorRules.newReceipt(CONNECTION, KEY, baseline);
        RollbackMirrorRules.Candidate scoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", baseline, true, true, false, 0);
        RollbackMirrorRules.Candidate legacy = RollbackMirrorRules.Candidate.of(
            "legacy-pref", rollback, true, true, false, 0);

        RollbackMirrorRules.Decision decision =
            RollbackMirrorRules.chooseReceiptBoundValue(
                scoped, legacy, receipt, CONNECTION, KEY);
        assertEquals(RollbackMirrorRules.Source.LEGACY, decision.source);
        assertEquals(rollback, decision.value);
    }

    @Test
    public void untimestampedDivergenceWithoutReceiptIsBlocked() {
        RollbackMirrorRules.Candidate scoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", "[]", true, true, false, 0);
        RollbackMirrorRules.Candidate legacy = RollbackMirrorRules.Candidate.of(
            "legacy-pref", "[\"sample\"]", true, true, false, 0);
        assertTrue(RollbackMirrorRules.chooseReceiptBoundValue(
            scoped, legacy, null, CONNECTION, KEY).blocked());
    }

    @Test
    public void exactPairProofAdoptsOneValidGlobalValueAndRestartIsIdempotent() {
        String legacyRaw = "[{\"sample\":true}]";
        JSONObject adoption = RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, false, true, true, CONNECTION, KEY);
        assertTrue(RollbackMirrorRules.receiptMatches(
            adoption, CONNECTION, KEY, legacyRaw));

        RollbackMirrorRules.Candidate absent =
            RollbackMirrorRules.Candidate.absent("scoped-pref");
        RollbackMirrorRules.Candidate legacy = RollbackMirrorRules.Candidate.of(
            "legacy-pref", legacyRaw, true, true, false, 0L);
        RollbackMirrorRules.Decision first =
            RollbackMirrorRules.chooseReceiptBoundValue(
                absent, legacy, adoption, CONNECTION, KEY);
        assertEquals(RollbackMirrorRules.Source.LEGACY, first.source);
        assertEquals(legacyRaw, first.value);
        assertTrue(first.mirrorAllowed);

        // A successful atomic preference commit creates both copies plus this exact receipt. A
        // cold restart therefore converges to the same bytes without another adoption decision.
        RollbackMirrorRules.Candidate scoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", first.value, true, true, false, 0L);
        RollbackMirrorRules.Decision restarted =
            RollbackMirrorRules.chooseReceiptBoundValue(
                scoped, legacy, adoption, CONNECTION, KEY);
        assertEquals(RollbackMirrorRules.Source.IDENTICAL, restarted.source);
        assertEquals(legacyRaw, restarted.value);
        assertTrue(RollbackMirrorRules.receiptMatches(
            adoption, CONNECTION, KEY, restarted.value));
    }

    @Test
    public void failedInitialMirrorCanRetryButPartialOrDivergentStateFailsClosed() {
        String legacyRaw = "[{\"sample\":true}]";
        JSONObject firstPlan = RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, false, true, true, CONNECTION, KEY);

        // commit(false) with the old atomic preferences file still on disk leaves the original
        // global-only state. The next process deterministically derives the same exact receipt.
        JSONObject afterNoWriteRestart = RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, false, true, true, CONNECTION, KEY);
        assertEquals(firstPlan.toString(), afterNoWriteRestart.toString());

        // A scoped copy without its matching receipt is ambiguous evidence. Adoption cannot fill
        // in the missing step, and ordinary reconciliation also refuses the divergent bytes.
        assertNull(RollbackMirrorRules.initialLegacyAdoptionReceipt(
            true, true, legacyRaw, true, false, true, true, CONNECTION, KEY));
        RollbackMirrorRules.Candidate partialScoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", "[{\"different\":true}]", true, true, false, 0L);
        RollbackMirrorRules.Candidate legacy = RollbackMirrorRules.Candidate.of(
            "legacy-pref", legacyRaw, true, true, false, 0L);
        assertTrue(RollbackMirrorRules.chooseReceiptBoundValue(
            partialScoped, legacy, null, CONNECTION, KEY).blocked());

        assertNull(RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, true, true, true, CONNECTION, KEY));
        assertNull(RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, false, false, true, CONNECTION, KEY));
        assertNull(RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, true, false, true, false, CONNECTION, KEY));
        assertNull(RollbackMirrorRules.initialLegacyAdoptionReceipt(
            false, true, legacyRaw, false, false, true, true, CONNECTION, KEY));
    }

    @Test
    public void draftStoreMissingLegacyIsReceiptProvenTombstoneOnly() throws Exception {
        String baseline = snapshot(100, "baseline");
        JSONObject receipt = RollbackMirrorRules.newReceipt(CONNECTION, KEY, baseline);
        RollbackMirrorRules.Candidate scoped = candidate(
            "scoped-pref", baseline, true);
        RollbackMirrorRules.Candidate missing =
            RollbackMirrorRules.Candidate.absent("legacy-pref");

        RollbackMirrorRules.Decision draft = RollbackMirrorRules.chooseDraftStore(
            scoped, missing, receipt, CONNECTION, KEY);
        assertTrue(draft.tombstone());
        assertEquals("", draft.value);

        // Manual queues have no signed-v1 delete entry point and must not inherit this rule.
        List<RollbackMirrorRules.Candidate> manualCopies = new ArrayList<>();
        manualCopies.add(scoped);
        manualCopies.add(missing);
        RollbackMirrorRules.Decision manual =
            RollbackMirrorRules.chooseNewestSnapshot(
                manualCopies, receipt, CONNECTION, KEY);
        assertFalse(manual.tombstone());
        assertEquals(baseline, manual.value);
    }
}
