package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure fail-closed checks for signed-v1 state that the current runtime cannot reinterpret. */
public class LegacyUpgradeSafetyRulesTest {
    @Test
    public void cachePromotionRequiresSuccessfulLegacyPanelReconciliation() {
        Map<String, Object> ordinarySettings = Collections.singletonMap("lang", "zh");

        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            false, ordinarySettings));
        assertTrue(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            true, ordinarySettings));
        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(true, null));
    }

    @Test
    public void everyLegacyAStepSlotPinsCacheAndInstallerByPresenceWithoutMutation() {
        String[] keys = new String[]{
            LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_PATH_KEY,
            LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_SEQUENCE_KEY,
            LegacyUpgradeSafetyRules.PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY
        };
        Object[] values = new Object[]{
            "/data/user/0/com.autoformkit.app/files/photos/original.jpg",
            "wrong-type-must-still-block",
            ""
        };

        for (int index = 0; index < keys.length; index++) {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("unrelated", 7);
            settings.put(keys[index], values[index]);
            Map<String, Object> before = new LinkedHashMap<>(settings);

            assertTrue(keys[index],
                LegacyUpgradeSafetyRules.pendingAStepEvidence(settings));
            assertFalse(keys[index], LegacyUpgradeSafetyRules.cachePromotionAllowed(
                true, settings));
            assertTrue(keys[index], RemoteSideEffectGate.durableSlotPresent(settings));
            assertEquals(keys[index], before, settings);
        }
    }

    @Test
    public void lookalikeAndUnrelatedKeysDoNotCreateFalseEvidence() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("x_pending_a_step_photo_path", "/synthetic/path.jpg");
        settings.put("pending_a_step_photo_path_suffix", "/synthetic/path.jpg");

        assertFalse(LegacyUpgradeSafetyRules.pendingAStepEvidence(settings));
        assertTrue(LegacyUpgradeSafetyRules.cachePromotionAllowed(true, settings));
        assertFalse(RemoteSideEffectGate.durableSlotPresent(settings));
    }

    @Test
    public void legacyCameraEvidenceBlocksHandoffButNotAnExactRecoveryWorker() {
        Map<String, Object> settings = Collections.singletonMap(
            LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_PATH_KEY,
            "/data/user/0/com.autoformkit.app/files/photos/original.jpg");
        assertTrue(RemoteSideEffectGate.durableSlotPresent(settings));

        RemoteSideEffectGate.WorkerLease recovery =
            RemoteSideEffectGate.tryAcquireWorker(settings, false);
        assertNotNull(recovery);
        recovery.close();
        assertEquals(0, RemoteSideEffectGate.activeWorkerCount());
    }
}
