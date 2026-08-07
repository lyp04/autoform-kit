package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SubmissionPolicyRulesTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Test
    public void interUnitDelayAppliesOnlyWhenAnotherUnitRemains() {
        assertEquals(1250L, SubmissionPolicyRules.delayBeforeNext(1250L, true));
        assertEquals(0L, SubmissionPolicyRules.delayBeforeNext(1250L, false));
        assertEquals(0L, SubmissionPolicyRules.delayBeforeNext(-1L, true));
        assertEquals(60_000L, SubmissionPolicyRules.delayBeforeNext(90_000L, true));
    }

    @Test
    public void roundRetentionUsesTheProfileOwnedDayBoundary() {
        long now = 20L * DAY_MS;
        assertTrue(SubmissionPolicyRules.retainsRound(now - 7L * DAY_MS, now, 7));
        assertFalse(SubmissionPolicyRules.retainsRound(now - 7L * DAY_MS - 1L, now, 7));
        assertTrue(SubmissionPolicyRules.retainsRound(now + 5L * 60L * 1000L, now, 7));
        assertFalse(SubmissionPolicyRules.retainsRound(now + 5L * 60L * 1000L + 1L, now, 7));
        assertFalse(SubmissionPolicyRules.retainsRound(0L, now, 7));
    }

    @Test
    public void savedRoundRetentionWinsOverLaterProfileChanges() {
        assertEquals(7, SubmissionPolicyRules.retentionDays(7, 1));
        assertEquals(9, SubmissionPolicyRules.retentionDays(0, 9));
        assertEquals(1, SubmissionPolicyRules.retentionDays(31, 0));
    }

    @Test
    public void wholeUnitRetryRemainsAllowedAfterAnImageUploadStarts() {
        SubmissionPolicyRules.NetworkRetryGate gate =
            new SubmissionPolicyRules.NetworkRetryGate();

        assertTrue(gate.canRetryWholeUnit());
        gate.markUploadStarted();
        assertTrue(gate.canRetryWholeUnit());

        // Multiple images may be re-uploaded within the bounded profile retry budget.
        gate.markUploadStarted();
        assertTrue(gate.canRetryWholeUnit());
    }

    @Test
    public void structuredNotWrittenResponseCanUseLegacyMaterialRemovalRecovery() {
        assertTrue(SubmissionPolicyRules.shouldRecoverMissingMaterials(
            true, false, true, Arrays.asList("SAMPLE-A", "SAMPLE-B")));
        assertTrue(SubmissionPolicyRules.shouldRecoverMissingMaterials(
            true, true, false, Collections.singletonList("SAMPLE-A")));

        assertFalse(SubmissionPolicyRules.shouldRecoverMissingMaterials(
            false, true, true, Collections.singletonList("SAMPLE-A")));
        assertFalse(SubmissionPolicyRules.shouldRecoverMissingMaterials(
            true, false, false, Collections.singletonList("SAMPLE-A")));
        assertFalse(SubmissionPolicyRules.shouldRecoverMissingMaterials(
            true, true, true, Collections.emptyList()));
    }
}
