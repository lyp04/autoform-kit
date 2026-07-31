package com.autoformkit.app;

/** Pure tapjacking guard for the one trust-granting pairing button. */
final class PanelPairingTouchRules {
    // MotionEvent flag values are stable Android API constants; keep this helper JVM-testable.
    static final int WINDOW_IS_OBSCURED = 0x1;
    static final int WINDOW_IS_PARTIALLY_OBSCURED = 0x2;

    private PanelPairingTouchRules() {}

    static boolean reject(int eventFlags, int sdkInt) {
        if ((eventFlags & WINDOW_IS_OBSCURED) != 0) return true;
        return sdkInt >= 29 && (eventFlags & WINDOW_IS_PARTIALLY_OBSCURED) != 0;
    }
}
