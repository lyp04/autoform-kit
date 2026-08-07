package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MaterialCodeRulesTest {
    private static final List<String> KNOWN_CODES = Arrays.asList(
        "SAMPLE-A-01", "SAMPLE-B-02", "SAMPLE-C-03");

    @Test
    public void legacyEntryKeepsItsUnconfiguredSubstringCompatibility() {
        assertEquals(Collections.singletonList("SAMPLE-A-01"),
            MaterialCodeRules.findKnownCodes(
                "legacy response mentions SAMPLE-A-01", KNOWN_CODES,
                Collections.emptyList(), ""));
    }

    @Test
    public void automaticRecoveryRequiresANonEmptyValidConfiguredPattern() {
        String message = "missing SAMPLE-A-01";
        assertTrue(MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            message, KNOWN_CODES, Collections.emptyList(), "").isEmpty());
        assertTrue(MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            message, KNOWN_CODES, Collections.emptyList(), null).isEmpty());
        assertTrue(MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            message, KNOWN_CODES, Collections.emptyList(), "[").isEmpty());
    }

    @Test
    public void automaticRecoveryIntersectsRegexMatchesWithKnownCodesAndExclusions() {
        List<String> found = MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            "missing SAMPLE-X-99, SAMPLE-B-02, then SAMPLE-A-01",
            KNOWN_CODES, Collections.singletonList("SAMPLE-B-02"),
            "SAMPLE-[A-Z]-[0-9]{2}");

        assertEquals(Collections.singletonList("SAMPLE-A-01"), found);
    }

    @Test
    public void automaticRecoveryDoesNotUseSubstringMatchesOutsideRecognizerOutput() {
        assertTrue(MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            "echo contains SAMPLE-A-01", KNOWN_CODES, Collections.emptyList(),
            "OTHER-[A-Z]-[0-9]{2}").isEmpty());
    }

    @Test
    public void legacyProfileCompatibilityMatchesOnlyCurrentKnownCodes() {
        assertEquals(Collections.singletonList("SAMPLE-A-01"),
            MaterialCodeRules.findKnownCodesForAutomaticRecoveryCompatible(
                "missing SAMPLE-X-99 and SAMPLE-A-01", KNOWN_CODES,
                Collections.emptyList(), ""));
        assertTrue(MaterialCodeRules.findKnownCodesForAutomaticRecoveryCompatible(
            "missing SAMPLE-A-01", KNOWN_CODES, Collections.emptyList(), "[").isEmpty());
    }
}
