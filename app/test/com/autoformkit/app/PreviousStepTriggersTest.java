package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PreviousStepTriggersTest {
    @Test
    public void automaticCreationRequiresExplicitEnableAndNonEmptyMatchingGrades() {
        assertFalse(PreviousStepTriggers.configuredAutoCreate(null, Arrays.asList("A"), "A"));
        assertFalse(PreviousStepTriggers.configuredAutoCreate(false, Arrays.asList("A"), "A"));
        assertFalse(PreviousStepTriggers.configuredAutoCreate(true, Collections.emptyList(), "A"));
        assertFalse(PreviousStepTriggers.configuredAutoCreate(true, Arrays.asList("B"), "A"));
        assertTrue(PreviousStepTriggers.configuredAutoCreate(true, Arrays.asList("A", "B"), "A"));
    }

    @Test
    public void manuallyConfirmedRepairRemainsAnIndependentSafetyNet() {
        assertFalse(PreviousStepTriggers.shouldCreate(false, false));
        assertTrue(PreviousStepTriggers.shouldCreate(true, false));
        assertTrue(PreviousStepTriggers.shouldCreate(false, true));
    }
}
