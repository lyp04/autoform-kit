package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PhotoSlotCaptureRulesTest {
    @Test
    public void minimumsAreCompletedBeforeAdditionalChoice() {
        assertEquals(0, PhotoSlotCaptureRules.nextBelowMinimum(
            new int[]{0, 0}, new int[]{1, 0}));
        assertEquals(-1, PhotoSlotCaptureRules.nextBelowMinimum(
            new int[]{1, 0}, new int[]{1, 0}));
    }

    @Test
    public void optionalSlotIsReachableBeforeEarlierSlotReachesMaximum() {
        assertEquals(Arrays.asList(0, 1), PhotoSlotCaptureRules.slotsWithCapacity(
            new int[]{1, 0}, new int[]{3, 2}));
    }

    @Test
    public void fullSlotsAreExcludedFromAdditionalChoice() {
        assertEquals(Collections.singletonList(1), PhotoSlotCaptureRules.slotsWithCapacity(
            new int[]{3, 1}, new int[]{3, 2}));
    }
}
