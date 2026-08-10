package com.autoformkit.app;

/** Pure visibility and tap-window rules for the settings-page maintenance section. */
final class AdvancedSettingsVisibilityRules {
    static final int REQUIRED_TAPS = 5;
    static final long TAP_WINDOW_MS = 2500L;

    static final class TapProgress {
        final int count;
        final long windowStartedAtMs;
        final boolean revealed;

        TapProgress(int count, long windowStartedAtMs, boolean revealed) {
            this.count = count;
            this.windowStartedAtMs = windowStartedAtMs;
            this.revealed = revealed;
        }
    }

    private AdvancedSettingsVisibilityRules() {}

    static boolean shouldShow(String panelBase, String catalogKey, boolean revealed) {
        return revealed || PanelConnectionInputRules.classify(panelBase, catalogKey)
            != PanelConnectionInputRules.TupleState.COMPLETE;
    }

    static TapProgress onEnglishTap(int count, long windowStartedAtMs, long nowMs) {
        if (count <= 0 || windowStartedAtMs <= 0L || nowMs < windowStartedAtMs
                || nowMs - windowStartedAtMs > TAP_WINDOW_MS) {
            count = 0;
            windowStartedAtMs = nowMs;
        }
        count++;
        if (count >= REQUIRED_TAPS) return new TapProgress(0, 0L, true);
        return new TapProgress(count, windowStartedAtMs, false);
    }
}
