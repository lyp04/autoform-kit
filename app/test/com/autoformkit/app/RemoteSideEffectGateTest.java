package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class RemoteSideEffectGateTest {
    @Test
    public void everyDurableSlotBlocksByPresenceEvenWhenItsValueIsCorrupt() {
        String[] keys = new String[]{
            RemoteSideEffectGate.UPLOAD_REPLAY_BARRIER_KEY,
            RemoteSideEffectGate.REPRINT_ATTEMPTS_KEY,
            RemoteSideEffectGate.MAIN_SUBMISSION_ATTEMPT_PREFIX + "namespace-a",
            RemoteSideEffectGate.PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX + "0123456789abcdefabcd",
            RemoteSideEffectGate.ALTERNATE_SUBMISSION_ATTEMPT_PREFIX + "any-namespace"
        };

        for (String key : keys) {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put(key, Boolean.TRUE);
            assertTrue(key, RemoteSideEffectGate.durableSlotPresent(settings));
        }
    }

    @Test
    public void namespacedSlotsAcceptAnyNonEmptySuffixButNotBareOrLookalikeKeys() {
        assertTrue(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            RemoteSideEffectGate.MAIN_SUBMISSION_ATTEMPT_PREFIX + "x", "{broken")));
        assertTrue(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            RemoteSideEffectGate.PREVIOUS_STEP_SUBMISSION_ATTEMPT_PREFIX + "other", null)));
        assertTrue(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            RemoteSideEffectGate.ALTERNATE_SUBMISSION_ATTEMPT_PREFIX + "!", 7)));

        assertFalse(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            "pending_main_submission_attempt_json", "{}")));
        assertFalse(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            "x_" + RemoteSideEffectGate.MAIN_SUBMISSION_ATTEMPT_PREFIX + "namespace", "{}")));
        assertFalse(RemoteSideEffectGate.durableSlotPresent(Collections.singletonMap(
            RemoteSideEffectGate.UPLOAD_REPLAY_BARRIER_KEY + "_namespace", "{}")));
        assertFalse(RemoteSideEffectGate.durableSlotPresent(Collections.emptyMap()));
        assertFalse(RemoteSideEffectGate.durableSlotPresent(null));
    }

    @Test
    public void leaseRefusesInstallerButAllowsAnExactRecoveryWorker() {
        assertNull(RemoteSideEffectGate.tryAcquireWorker(Collections.emptyMap(), true));
        RemoteSideEffectGate.WorkerLease recovery =
            RemoteSideEffectGate.tryAcquireWorker(Collections.singletonMap(
                RemoteSideEffectGate.REPRINT_ATTEMPTS_KEY, "not-json"), false);
        assertNotNull(recovery);
        recovery.close();
        assertEquals(0, RemoteSideEffectGate.activeWorkerCount());
    }

    @Test
    public void workerLeasesBlockUntilAllAreReleasedAndReleaseIsIdempotent() {
        Map<String, Object> empty = Collections.emptyMap();
        RemoteSideEffectGate.WorkerLease first =
            RemoteSideEffectGate.tryAcquireWorker(empty, false);
        RemoteSideEffectGate.WorkerLease second =
            RemoteSideEffectGate.tryAcquireWorker(empty, false);
        assertNotNull(first);
        assertNotNull(second);
        try {
            assertEquals(2, RemoteSideEffectGate.activeWorkerCount());
            assertTrue(RemoteSideEffectGate.blockingStatePresent(empty));

            first.close();
            first.close();
            assertEquals(1, RemoteSideEffectGate.activeWorkerCount());
            assertTrue(RemoteSideEffectGate.blockingStatePresent(empty));
        } finally {
            first.close();
            second.close();
            second.close();
        }

        assertEquals(0, RemoteSideEffectGate.activeWorkerCount());
        assertFalse(RemoteSideEffectGate.blockingStatePresent(empty));
    }
}
