package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdvancedSettingsVisibilityRulesTest {
    @Test
    public void absentOrPartialPanelTupleIsVisibleForRecovery() {
        assertTrue(AdvancedSettingsVisibilityRules.shouldShow("", "", false));
        assertTrue(AdvancedSettingsVisibilityRules.shouldShow("https://panel.example", "", false));
        assertTrue(AdvancedSettingsVisibilityRules.shouldShow("", "read-key", false));
        assertTrue(AdvancedSettingsVisibilityRules.shouldShow("   ", "read-key", false));
    }

    @Test
    public void completePanelTupleIsHiddenUntilExplicitlyRevealed() {
        assertFalse(AdvancedSettingsVisibilityRules.shouldShow(
            "https://panel.example", "read-key", false));
        assertTrue(AdvancedSettingsVisibilityRules.shouldShow(
            "https://panel.example", "read-key", true));
    }

    @Test
    public void fifthEnglishTapInsideWindowReveals() {
        AdvancedSettingsVisibilityRules.TapProgress progress =
            AdvancedSettingsVisibilityRules.onEnglishTap(0, 0L, 10_000L);
        for (int i = 1; i < AdvancedSettingsVisibilityRules.REQUIRED_TAPS; i++) {
            assertFalse(progress.revealed);
            progress = AdvancedSettingsVisibilityRules.onEnglishTap(
                progress.count, progress.windowStartedAtMs, 10_000L + i * 400L);
        }
        assertTrue(progress.revealed);
        assertEquals(0, progress.count);
        assertEquals(0L, progress.windowStartedAtMs);
    }

    @Test
    public void expiredOrBackwardsClockStartsANewWindow() {
        AdvancedSettingsVisibilityRules.TapProgress expired =
            AdvancedSettingsVisibilityRules.onEnglishTap(
                4, 10_000L, 10_000L + AdvancedSettingsVisibilityRules.TAP_WINDOW_MS + 1L);
        assertFalse(expired.revealed);
        assertEquals(1, expired.count);

        AdvancedSettingsVisibilityRules.TapProgress backwards =
            AdvancedSettingsVisibilityRules.onEnglishTap(4, 10_000L, 9_999L);
        assertFalse(backwards.revealed);
        assertEquals(1, backwards.count);
        assertEquals(9_999L, backwards.windowStartedAtMs);
    }
}
