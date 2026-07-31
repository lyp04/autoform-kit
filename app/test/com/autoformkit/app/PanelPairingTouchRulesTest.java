package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelPairingTouchRulesTest {
    @Test
    public void unobscuredTouchesRemainUsable() {
        assertFalse(PanelPairingTouchRules.reject(0, 23));
        assertFalse(PanelPairingTouchRules.reject(0, 36));
    }

    @Test
    public void fullyObscuredTouchesAreAlwaysRejected() {
        assertTrue(PanelPairingTouchRules.reject(
            PanelPairingTouchRules.WINDOW_IS_OBSCURED, 23));
    }

    @Test
    public void partialOverlayFlagIsRejectedWhereAndroidDefinesIt() {
        assertFalse(PanelPairingTouchRules.reject(
            PanelPairingTouchRules.WINDOW_IS_PARTIALLY_OBSCURED, 28));
        assertTrue(PanelPairingTouchRules.reject(
            PanelPairingTouchRules.WINDOW_IS_PARTIALLY_OBSCURED, 29));
        assertTrue(PanelPairingTouchRules.reject(
            PanelPairingTouchRules.WINDOW_IS_OBSCURED
                | PanelPairingTouchRules.WINDOW_IS_PARTIALLY_OBSCURED, 36));
    }
}
