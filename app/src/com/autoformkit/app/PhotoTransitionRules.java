package com.autoformkit.app;

import java.util.Locale;

/** Pure transition rules shared by the profile-driven photo flow. */
final class PhotoTransitionRules {
    private PhotoTransitionRules() {
    }

    /** Grouped capture has completed one slot and will begin the next still-empty slot. */
    static boolean shouldShowSlotTransitionNotice(
        String photoOrder,
        int[] stepBeforeSave,
        int[] stepAfterSave,
        boolean nextSlotAlreadyStarted
    ) {
        return "fronts_then_backs".equals(photoOrder)
            && slotIndex(stepBeforeSave) >= 0
            && slotIndex(stepAfterSave) > slotIndex(stepBeforeSave)
            && !nextSlotAlreadyStarted;
    }

    static String formatSlotTransitionNotice(
        String template,
        String completedSlotTitle,
        String nextSlotTitle
    ) {
        return String.format(Locale.ROOT, template, completedSlotTitle, nextSlotTitle);
    }

    private static int slotIndex(int[] step) {
        return step == null || step.length < 2 ? -1 : step[1];
    }
}
