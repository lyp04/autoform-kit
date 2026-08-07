package com.autoformkit.app;

/** Pure helpers for profile-owned batch pacing and local round-ledger retention. */
final class SubmissionPolicyRules {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L;

    private SubmissionPolicyRules() {
    }

    /**
     * Image upload is retryable preparation: it cannot create the final form record. The final
     * and previous-step POST journals independently prevent replay once either form POST starts.
     */
    static final class NetworkRetryGate {
        boolean canRetryWholeUnit() {
            return true;
        }

        void markUploadStarted() {
            // Retained as a source-compatible hook for older call sites. Uploads are replayable.
        }
    }

    static long delayBeforeNext(long configuredDelayMs, boolean hasNextUnit) {
        if (!hasNextUnit) return 0L;
        return Math.max(0L, Math.min(60_000L, configuredDelayMs));
    }

    static boolean retainsRound(long timestampMs, long nowMs, int retentionDays) {
        int days = Math.max(1, Math.min(30, retentionDays));
        if (timestampMs <= 0L || timestampMs - nowMs > MAX_FUTURE_SKEW_MS) return false;
        return nowMs - timestampMs <= days * DAY_MS;
    }

    static int retentionDays(int snapshotDays, int currentProfileDays) {
        if (snapshotDays >= 1 && snapshotDays <= 30) return snapshotDays;
        return Math.max(1, Math.min(30, currentProfileDays));
    }
}
