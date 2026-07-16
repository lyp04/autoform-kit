package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PreviousStepTemplateHintsTest {
    @Test
    public void mapsT2277PackagingTemplateToBothPreviousSteps() {
        assertEquals(32015, PreviousStepTemplateHints.forProcessStep(32017, 1));
        assertEquals(32016, PreviousStepTemplateHints.forProcessStep(32017, 2));
    }

    @Test
    public void preservesEveryVerifiedLegacyMapping() {
        int[][] mappings = {
                {769, 767, 768},
                {1900, 1897, 1899},
                {1482, 1480, 1481},
                {1902, 1898, 1901},
                {1786, 1784, 1785},
                {31973, 31971, 31972},
                {32017, 32015, 32016}
        };
        for (int[] mapping : mappings) {
            assertEquals(mapping[1], PreviousStepTemplateHints.forProcessStep(mapping[0], 1));
            assertEquals(mapping[2], PreviousStepTemplateHints.forProcessStep(mapping[0], 2));
        }
    }

    @Test
    public void doesNotGuessUnknownTemplatesOrProcesses() {
        assertEquals(0, PreviousStepTemplateHints.forProcessStep(32018, 1));
        assertEquals(0, PreviousStepTemplateHints.forProcessStep(32017, 3));
    }
}
