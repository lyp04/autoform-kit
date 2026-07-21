package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhotoTransitionRulesTest {
    @Test
    public void groupedCaptureShowsNoticeWhenFirstSlotCompletes() {
        assertTrue(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(1, 0), step(0, 1), false));
    }

    @Test
    public void incompleteFrontsAndRepeatedFrontPhotosDoNotShowNotice() {
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 0), step(1, 0), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 1), step(0, 1), false));
    }

    @Test
    public void existingBackPhotoDoesNotShowNoticeAgain() {
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(1, 0), step(0, 1), true));
    }

    @Test
    public void perUnitOrderAndOtherSlotTransitionsDoNotShowBatchNotice() {
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "front_back_per_unit", step(0, 0), step(0, 1), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 1), step(0, 2), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 0), null, false));
    }

    @Test
    public void noticeUsesConfiguredSlotTitles() {
        assertEquals(
            "外观照片已拍完，开始拍配件照片。",
            PhotoTransitionRules.formatSlotTransitionNotice(
                "%1$s已拍完，开始拍%2$s。", "外观照片", "配件照片"));
    }

    private static int[] step(int unitIndex, int slotIndex) {
        return new int[]{unitIndex, slotIndex};
    }
}
