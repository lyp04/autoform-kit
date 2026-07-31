package com.autoformkit.app;

import java.util.Map;

/**
 * Pure fail-closed rules for storage left by the signed v1 App during an in-place upgrade.
 *
 * <p>The old A-step camera flow persisted only its continuation path/sequence. The current
 * profile-driven previous-step flow cannot safely infer which current recipe owns those bytes, so
 * their presence must pin the old Panel cache and block both a Panel switch and another APK
 * handoff. The values are deliberately never removed here: a signed rollback/recovery build must
 * still be able to consume the exact original path.
 */
final class LegacyUpgradeSafetyRules {
    static final String PENDING_A_STEP_PHOTO_PATH_KEY =
        "pending_a_step_photo_path";
    static final String PENDING_A_STEP_PHOTO_SEQUENCE_KEY =
        "pending_a_step_photo_seq";
    static final String PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY =
        "pending_a_step_entry_photo_path";

    private LegacyUpgradeSafetyRules() {}

    /** Key presence is evidence even when the stored type/value is malformed or empty. */
    static boolean pendingAStepEvidence(Map<String, ?> settings) {
        return settings == null
            || settings.containsKey(PENDING_A_STEP_PHOTO_PATH_KEY)
            || settings.containsKey(PENDING_A_STEP_PHOTO_SEQUENCE_KEY)
            || settings.containsKey(PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY);
    }

    /**
     * A cache may advance only after all legacy draft/queue/ledger mirrors have an exact ownership
     * receipt and no untranslatable A-step continuation remains.
     */
    static boolean cachePromotionAllowed(boolean legacyPanelStateResolved,
                                         Map<String, ?> settings) {
        return legacyPanelStateResolved && !pendingAStepEvidence(settings);
    }
}
