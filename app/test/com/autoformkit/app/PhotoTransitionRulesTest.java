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
    public void perUnitOrderDoesNotShowBatchNoticeButEveryAdjacentSlotDoes() {
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "front_back_per_unit", step(0, 0), step(0, 1), false));
        assertTrue(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 1), step(0, 2), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 0), null, false));
    }

    @Test
    public void missingOrShortBeforeStepDoesNotShowNotice() {
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", null, step(0, 1), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", new int[0], step(0, 1), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", new int[]{0}, step(0, 1), false));
    }

    @Test
    public void groupedCaptureShowsNoticeWhenOptionalSlotsAreSkipped() {
        assertTrue(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 0), step(0, 2), false));
        assertFalse(PhotoTransitionRules.shouldShowSlotTransitionNotice(
            "fronts_then_backs", step(0, 0), step(0, 2), true));
    }

    @Test
    public void noticeUsesConfiguredSlotTitles() {
        assertEquals(
            "上方示例框已拍完，开始拍下方示例框。",
            PhotoTransitionRules.formatSlotTransitionNotice(
                "%1$s已拍完，开始拍%2$s。", "上方示例框", "下方示例框"));
    }

    private static int[] step(int unitIndex, int slotIndex) {
        return new int[]{unitIndex, slotIndex};
    }
}
