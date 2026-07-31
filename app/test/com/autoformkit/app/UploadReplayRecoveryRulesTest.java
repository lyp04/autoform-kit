package com.autoformkit.app;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadReplayRecoveryRulesTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final String DIGEST_A = repeat('a', 64);
    private static final String DIGEST_B = repeat('b', 64);
    private static final String DIGEST_C = repeat('c', 64);
    private static final String DIGEST_D = repeat('d', 64);
    private static final String DIGEST_E = repeat('e', 64);
    private static final String OPERATION = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    public void mainRequiresExactCompletedReceiptLink() {
        UploadReplayBarrier.Identity barrier = UploadReplayBarrier.Identity.main(
            CONNECTION, 7, "profile-a", DIGEST_A, DIGEST_B, DIGEST_C,
            DIGEST_D, DIGEST_E, OPERATION);
        AlternateSubmissionAttempt.Key key = AlternateSubmissionAttempt.Key.of(
            CONNECTION, DIGEST_B, target("profile-a"), "fictional-001",
            DIGEST_A, DIGEST_C, OPERATION);
        AlternateSubmissionAttempt posting = AlternateSubmissionAttempt.prepare(
            key, AlternateSubmissionAttempt.restoreStoredValue(false, null))
            .beginPosting(key);

        assertFalse(UploadReplayRecoveryRules.completedMain(barrier, posting));
        assertTrue(UploadReplayRecoveryRules.completedMain(
            barrier, posting.markPostAcknowledged(key)));

        AlternateSubmissionAttempt.Key wrongOperation = AlternateSubmissionAttempt.Key.of(
            CONNECTION, DIGEST_B, target("profile-a"), "fictional-001",
            DIGEST_A, DIGEST_C, "fedcba98-7654-3210-fedc-ba9876543210");
        AlternateSubmissionAttempt wrongCompleted = AlternateSubmissionAttempt.prepare(
            wrongOperation, AlternateSubmissionAttempt.restoreStoredValue(false, null))
            .beginPosting(wrongOperation).markPostAcknowledged(wrongOperation);
        assertFalse(UploadReplayRecoveryRules.completedMain(barrier, wrongCompleted));
    }

    @Test
    public void alternateRequiresReceiptAndDurableDraftToMatch() {
        UploadReplayBarrier.Identity barrier = UploadReplayBarrier.Identity.alternate(
            CONNECTION, 7, "source-a", "target-a", DIGEST_A, DIGEST_B,
            DIGEST_C, DIGEST_D, DIGEST_E, OPERATION);
        AlternateSubmissionAttempt.Key key = AlternateSubmissionAttempt.Key.of(
            CONNECTION, DIGEST_B, target("target-a"), "fictional-001",
            DIGEST_E, DIGEST_A, OPERATION);
        AlternateSubmissionAttempt completed = AlternateSubmissionAttempt.prepare(
            key, AlternateSubmissionAttempt.restoreStoredValue(false, null))
            .beginPosting(key).markPostAcknowledged(key);
        AlternateEntryDraftState draft = AlternateEntryDraftState.create(
            DIGEST_A, CONNECTION, DIGEST_B, DIGEST_C, "entry-a", "source-a",
            "return-a", "fictional-001", SnScanRules.SOURCE_ENTERED,
            Collections.singletonList("/tmp/fictional.jpg"), Collections.emptyMap());

        assertTrue(UploadReplayRecoveryRules.completedAlternate(
            barrier, completed, draft, DIGEST_E));
        assertFalse(UploadReplayRecoveryRules.completedAlternate(
            barrier, completed, draft, DIGEST_D));
        AlternateEntryDraftState wrongSource = AlternateEntryDraftState.create(
            DIGEST_A, CONNECTION, DIGEST_B, DIGEST_C, "entry-a", "source-b",
            "return-a", "fictional-001", SnScanRules.SOURCE_ENTERED,
            Collections.singletonList("/tmp/fictional.jpg"), Collections.emptyMap());
        assertFalse(UploadReplayRecoveryRules.completedAlternate(
            barrier, completed, wrongSource, DIGEST_E));
    }

    private static AlternateSubmissionAttempt.TargetIdentity target(String profileId) {
        return AlternateSubmissionAttempt.TargetIdentity.of(
            profileId, "template-a", "warehouse-a", "sku-a");
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
