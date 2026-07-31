package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class PanelConnectionInputRulesTest {
    private static final String OLD_BASE = "https://old-panel.example.invalid";
    private static final String NEW_BASE = "https://new-panel.example.invalid";
    private static final String OLD_KEY = "fictional-old-read-key";
    private static final String NEW_KEY = "fictional-new-read-key";

    @Test
    public void classifiesEmptyPartialAndCompleteTuplesAfterTrimming() {
        assertSame(PanelConnectionInputRules.TupleState.EMPTY,
            PanelConnectionInputRules.classify(null, " \t"));
        assertSame(PanelConnectionInputRules.TupleState.PARTIAL,
            PanelConnectionInputRules.classify(" " + NEW_BASE + " ", ""));
        assertSame(PanelConnectionInputRules.TupleState.PARTIAL,
            PanelConnectionInputRules.classify("", " " + NEW_KEY + " "));
        assertSame(PanelConnectionInputRules.TupleState.COMPLETE,
            PanelConnectionInputRules.classify(
                " " + NEW_BASE + " ", " " + NEW_KEY + " "));
    }

    @Test
    public void panelNetworkRequiresBothAddressAndAccessKey() {
        assertFalse(PanelConnectionInputRules.allowsPanelNetwork("", ""));
        assertFalse(PanelConnectionInputRules.allowsPanelNetwork(NEW_BASE, ""));
        assertFalse(PanelConnectionInputRules.allowsPanelNetwork("", NEW_KEY));
        assertTrue(PanelConnectionInputRules.allowsPanelNetwork(NEW_BASE, NEW_KEY));
    }

    @Test
    public void acceptsOnlyCompleteOrCompletelyEmptyCandidateTuple() {
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            "", "", "", ""));
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            "", "", NEW_BASE, NEW_KEY));

        assertSame(PanelConnectionInputRules.Decision.PARTIAL_TUPLE,
            PanelConnectionInputRules.validate(
                PanelConnectionInputRules.Source.MANUAL,
                "", "", NEW_BASE, ""));
        assertSame(PanelConnectionInputRules.Decision.PARTIAL_TUPLE,
            PanelConnectionInputRules.validate(
                PanelConnectionInputRules.Source.MANUAL,
                "", "", "", NEW_KEY));
    }

    @Test
    public void whitespaceIsNormalizedBeforeTupleValidation() {
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            null, null, "  ", "\t"));
        assertSame(PanelConnectionInputRules.Decision.PARTIAL_TUPLE,
            PanelConnectionInputRules.validate(
                PanelConnectionInputRules.Source.MANUAL,
                null, null, " " + NEW_BASE + " ", "  "));
    }

    @Test
    public void manualBaseChangeCannotCarryTheOldNonEmptyKey() {
        PanelConnectionInputRules.Decision decision =
            PanelConnectionInputRules.validate(
                PanelConnectionInputRules.Source.MANUAL,
                OLD_BASE, OLD_KEY, NEW_BASE, OLD_KEY);

        assertSame(PanelConnectionInputRules.Decision.REUSED_OLD_KEY, decision);
        assertFalse(decision.allowed());
    }

    @Test
    public void manualSameBaseMayKeepKeyAndBaseChangeMayUseFreshKey() {
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            OLD_BASE, OLD_KEY, OLD_BASE, OLD_KEY));
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            OLD_BASE, OLD_KEY, NEW_BASE, NEW_KEY));
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            OLD_BASE, OLD_KEY, "", ""));
    }

    @Test
    public void pairingMayInstallFreshlyRedeemedKeyWithTheSameBytes() {
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.PAIRING,
            OLD_BASE, OLD_KEY, NEW_BASE, OLD_KEY));
    }

    @Test
    public void trailingSlashNormalizationRemainsTheCallersResponsibility() {
        assertSame(PanelConnectionInputRules.Decision.REUSED_OLD_KEY,
            PanelConnectionInputRules.validate(
                PanelConnectionInputRules.Source.MANUAL,
                OLD_BASE + "/", OLD_KEY, OLD_BASE, OLD_KEY));
        assertAllowed(PanelConnectionInputRules.validate(
            PanelConnectionInputRules.Source.MANUAL,
            OLD_BASE, OLD_KEY, OLD_BASE, OLD_KEY));
    }

    @Test
    public void nullSourceIsRejected() {
        try {
            PanelConnectionInputRules.validate(null,
                OLD_BASE, OLD_KEY, NEW_BASE, NEW_KEY);
            fail("missing source must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source"));
        }
    }

    private static void assertAllowed(PanelConnectionInputRules.Decision decision) {
        assertSame(PanelConnectionInputRules.Decision.ACCEPT, decision);
        assertTrue(decision.allowed());
    }
}
