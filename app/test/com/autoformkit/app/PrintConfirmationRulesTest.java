package com.autoformkit.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrintConfirmationRulesTest {
    @Test
    public void confirmedPrintCompletesInline() {
        PrintConfirmationRules.Result result = PrintConfirmationRules.classify(true, true);

        assertEquals(PrintConfirmationRules.Result.PRINTED, result);
        assertFalse(PrintConfirmationRules.shouldAlertAfterInline(result));
        assertFalse(PrintConfirmationRules.shouldDeferUntilBatchEnd(result));
    }

    @Test
    public void seenButUnconfirmedJobAlertsNormally() {
        PrintConfirmationRules.Result result = PrintConfirmationRules.classify(false, true);

        assertEquals(PrintConfirmationRules.Result.FAILED, result);
        assertTrue(PrintConfirmationRules.shouldAlertAfterInline(result));
        assertFalse(PrintConfirmationRules.shouldDeferUntilBatchEnd(result));
    }

    @Test
    public void missingJobDefersUntilBatchEnd() {
        PrintConfirmationRules.Result result = PrintConfirmationRules.classify(false, false);

        assertEquals(PrintConfirmationRules.Result.MISSING, result);
        assertFalse(PrintConfirmationRules.shouldAlertAfterInline(result));
        assertTrue(PrintConfirmationRules.shouldDeferUntilBatchEnd(result));
    }

    @Test
    public void immediateBatchCheckWaitsOnlyForMissingJobs() {
        assertTrue(PrintConfirmationRules.shouldWaitForDelayedBatchCheck(
                PrintConfirmationRules.Result.MISSING));
        assertFalse(PrintConfirmationRules.shouldWaitForDelayedBatchCheck(
                PrintConfirmationRules.Result.PRINTED));
        assertFalse(PrintConfirmationRules.shouldWaitForDelayedBatchCheck(
                PrintConfirmationRules.Result.FAILED));
    }

    @Test
    public void finalBatchCheckAlertsOnlyForUnconfirmedJobs() {
        assertFalse(PrintConfirmationRules.shouldAlertAfterFinalBatchCheck(
                PrintConfirmationRules.Result.PRINTED));
        assertTrue(PrintConfirmationRules.shouldAlertAfterFinalBatchCheck(
                PrintConfirmationRules.Result.MISSING));
        assertTrue(PrintConfirmationRules.shouldAlertAfterFinalBatchCheck(
                PrintConfirmationRules.Result.FAILED));
    }

    @Test
    public void sessionExpiryRecoveryIncludesOnlySubmittedUnconfirmedUnits() {
        assertTrue(PrintConfirmationRules.isSubmittedButUnconfirmed("ok", "unconfirmed"));
        assertTrue(PrintConfirmationRules.isSubmittedButUnconfirmed("ok", ""));
        assertFalse(PrintConfirmationRules.isSubmittedButUnconfirmed("ok", "ok"));
        assertFalse(PrintConfirmationRules.isSubmittedButUnconfirmed("failed", "unconfirmed"));
        assertFalse(PrintConfirmationRules.isSubmittedButUnconfirmed("", "unconfirmed"));
    }

    @Test
    public void unconfirmedPrintsStillShowAfterAnotherLogoutPathClearsTheToken() {
        assertTrue(PrintConfirmationRules.shouldShowSessionExpiredNotice(true, false));
        assertTrue(PrintConfirmationRules.shouldShowSessionExpiredNotice(false, true));
        assertFalse(PrintConfirmationRules.shouldShowSessionExpiredNotice(false, false));
    }
}
