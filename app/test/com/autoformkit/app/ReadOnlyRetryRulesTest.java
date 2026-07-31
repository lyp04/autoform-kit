package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReadOnlyRetryRulesTest {
    @Test
    public void oneTransientReadFailureGetsExactlyOneCompatibilityRetry() {
        assertEquals(2, ReadOnlyRetryRules.MAX_ATTEMPTS);
        assertEquals(3_000L, ReadOnlyRetryRules.RETRY_DELAY_MS);
        assertTrue(ReadOnlyRetryRules.shouldRetry(1, true));
        assertFalse(ReadOnlyRetryRules.shouldRetry(2, true));
        assertFalse(ReadOnlyRetryRules.shouldRetry(3, true));
    }

    @Test
    public void nonTransientAndInvalidAttemptCountsNeverRetry() {
        assertFalse(ReadOnlyRetryRules.shouldRetry(1, false));
        assertFalse(ReadOnlyRetryRules.shouldRetry(0, true));
        assertFalse(ReadOnlyRetryRules.shouldRetry(-1, true));
    }
}
