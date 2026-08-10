package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelSyncRecoveryRulesTest {
    private boolean allowed(int drafts, boolean otherBlocker) {
        return PanelSyncRecoveryRules.canDiscardObsoleteLocalDraft(
            true, true, false, true, false,
            49, 49, true, 55, 55, drafts, otherBlocker);
    }

    @Test
    public void permitsOnlyNewerValidPairWithAnObsoleteLocalDraft() {
        assertTrue(allowed(1, false));
        assertFalse(allowed(0, false));
        assertFalse(allowed(1, true));
        assertFalse(PanelSyncRecoveryRules.canDiscardObsoleteLocalDraft(
            true, true, false, true, true,
            49, 49, true, 55, 55, 1, false));
        assertFalse(PanelSyncRecoveryRules.canDiscardObsoleteLocalDraft(
            true, true, false, true, false,
            49, 49, true, 49, 49, 1, false));
        assertFalse(PanelSyncRecoveryRules.canDiscardObsoleteLocalDraft(
            true, true, false, true, false,
            49, 49, true, 55, 54, 1, false));
    }
}
