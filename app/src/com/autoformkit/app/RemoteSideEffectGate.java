package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Process-wide gate shared by remote-side-effect workers and update/cache handoffs.
 *
 * <p>The durable check deliberately considers only key presence. A malformed value is still an
 * unresolved operation and must therefore fail closed. Worker leases hold no business data.</p>
 */
final class RemoteSideEffectGate {
    static final String UPLOAD_REPLAY_BARRIER_KEY =
        "pending_upload_replay_barrier_v1_json";
    static final String REPRINT_ATTEMPTS_KEY =
        "pending_reprint_attempts_v1_json";
    static final String MAIN_SUBMISSION_ATTEMPT_PREFIX =
        "pending_main_submission_attempt_json_";
    static final String PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX =
        "pending_previous_step_submission_attempt_json_";
    static final String ALTERNATE_SUBMISSION_ATTEMPT_PREFIX =
        "pending_alternate_submission_attempt_json_";

    private static int activeWorkerCount;

    private RemoteSideEffectGate() {
    }

    /** Pure durable-slot check suitable for callers that already hold a preferences snapshot. */
    static boolean durableSlotPresent(Map<String, ?> settings) {
        if (settings == null || settings.isEmpty()) return false;
        // A signed-v1 A-step camera return cannot be translated into the current dynamic recipe
        // without guessing its owner. Treat the untouched legacy keys like an unresolved remote
        // journal so an APK installer handoff cannot strand their only compatible continuation.
        if (LegacyUpgradeSafetyRules.pendingAStepEvidence(settings)) return true;
        if (settings.containsKey(UPLOAD_REPLAY_BARRIER_KEY)
                || settings.containsKey(REPRINT_ATTEMPTS_KEY)) {
            return true;
        }
        for (String key : settings.keySet()) {
            if (hasNamespaceSuffix(key, MAIN_SUBMISSION_ATTEMPT_PREFIX)
                    || hasNamespaceSuffix(key, PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX)
                    || hasNamespaceSuffix(key, ALTERNATE_SUBMISSION_ATTEMPT_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** True when a durable remote operation or an in-process remote worker is active. */
    static boolean blockingStatePresent(Map<String, ?> settings) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            return durableSlotPresent(settings) || activeWorkerCount > 0;
        }
    }

    /**
     * Reads the app-wide settings file while serialized with installer handoff transitions.
     * Missing/unreadable context fails closed.
     */
    static boolean blockingStatePresent(Context context) {
        if (context == null) return true;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                SharedPreferences settings = context.getSharedPreferences(
                    AppConfig.PREFS, Context.MODE_PRIVATE);
                return activeWorkerCount > 0 || durableSlotPresent(settings.getAll());
            } catch (RuntimeException ignored) {
                return true;
            }
        }
    }

    /**
     * Acquires a process-local worker lease if no installer handoff already owns the transition.
     * Durable records do not reject acquisition here: an exact recovery worker may need read-only
     * access to converge its own record. Flow-specific callers still arbitrate incompatible slots;
     * update/cache consumers use {@link #blockingStatePresent(Map)} and therefore remain blocked by
     * either the durable record or this lease.
     */
    static WorkerLease tryAcquireWorker(Map<String, ?> settings, boolean installerActive) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (settings == null || installerActive
                    || activeWorkerCount == Integer.MAX_VALUE) {
                return null;
            }
            activeWorkerCount++;
            return new WorkerLease();
        }
    }

    /** Context-backed acquisition; missing/unreadable context fails closed. */
    static WorkerLease tryAcquireWorker(Context context, boolean installerActive) {
        if (context == null) return null;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                SharedPreferences settings = context.getSharedPreferences(
                    AppConfig.PREFS, Context.MODE_PRIVATE);
                return tryAcquireWorker(settings.getAll(), installerActive);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /** Atomically arbitrates against the real installer capability under the shared lock. */
    static WorkerLease tryAcquireWorker(Context context) {
        if (context == null) return null;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (UpdateManager.installerHandoffActive(context)) return null;
            try {
                SharedPreferences settings = context.getSharedPreferences(
                    AppConfig.PREFS, Context.MODE_PRIVATE);
                return tryAcquireWorker(settings.getAll(), false);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    static int activeWorkerCount() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            return activeWorkerCount;
        }
    }

    private static boolean hasNamespaceSuffix(String key, String prefix) {
        return key != null && key.startsWith(prefix) && key.length() > prefix.length();
    }

    static final class WorkerLease implements AutoCloseable {
        private boolean released;

        private WorkerLease() {
        }

        @Override
        public void close() {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                if (released) return;
                released = true;
                if (activeWorkerCount > 0) activeWorkerCount--;
            }
        }
    }
}
