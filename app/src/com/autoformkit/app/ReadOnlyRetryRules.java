package com.autoformkit.app;

/** Bounded compatibility policy used only by HTTP operations that cannot create remote effects. */
final class ReadOnlyRetryRules {
    static final int MAX_ATTEMPTS = 2;
    static final long RETRY_DELAY_MS = 3_000L;

    private ReadOnlyRetryRules() {
    }

    /** {@code completedAttempts} includes the failed attempt that just returned. */
    static boolean shouldRetry(int completedAttempts, boolean transientFailure) {
        return transientFailure && completedAttempts >= 1
            && completedAttempts < MAX_ATTEMPTS;
    }
}
