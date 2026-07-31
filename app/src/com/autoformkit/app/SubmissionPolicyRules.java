package com.autoformkit.app;

/** Pure helpers for profile-owned batch pacing and local round-ledger retention. */
final class SubmissionPolicyRules {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L;

    private SubmissionPolicyRules() {
    }

    /**
     * One gate is retained for every invocation of the outer whole-unit network retry loop.
     * Read-only preparation may be replayed, but once an upload has started the backend may
     * already own a file even when the client never received its response.  From that point on,
     * replaying the whole unit would be capable of creating duplicate/orphan uploads.
     */
    static final class NetworkRetryGate {
        private boolean uploadStarted;

        boolean canRetryWholeUnit() {
            return !uploadStarted;
        }

        void markUploadStarted() {
            uploadStarted = true;
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
