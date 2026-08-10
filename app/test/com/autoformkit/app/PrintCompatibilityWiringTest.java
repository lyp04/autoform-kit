package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the Panel-owned print compatibility branches and safety boundaries. */
public class PrintCompatibilityWiringTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
            cwd.resolve("src/com/autoformkit/app/MainActivity.java")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    @Test
    public void inlineOnlySkipsBatchSweepButStopKeepsItsFinalCheck() throws Exception {
        String submit = section(mainActivitySource(),
            "private void submitBatch()", "private void showPrintReconcileDialog()");

        assertTrue(submit.contains("usesDeferredMissingTwoPassRecheck()"));
        assertTrue(submit.contains("!stopOnUnconfirmed && deferredMissingTwoPass"));
        assertTrue(submit.contains("finalPrintCheckBeforeStopping("));
        assertTrue(submit.contains(
            "printResult == PrintConfirmationRules.Result.MISSING"));
        assertTrue(submit.contains("inlineFailedSns.add(currentUnit.sn);"));
        assertTrue(submit.contains("recheckDeferredPrintsAtBatchEnd("));
    }

    @Test
    public void unknownPresentationDoesNotChangeItsPolicyKey() throws Exception {
        String row = section(mainActivitySource(),
            "private View buildUnitRow(JSONObject u, int seq, boolean cloud, TextView header, LinearLayout list)",
            "private void confirmAndRetryPrint(");

        assertTrue(row.contains("presentsUnknownPrintStatusAsOngoing()"));
        assertTrue(row.contains("print_status_ongoing"));
        assertTrue(row.contains("print_status_unknown"));
        assertTrue(row.contains("allowsManualReprint("));
        assertTrue(row.contains("ProfileWorkflow.PRINT_STATUS_UNKNOWN"));
    }

    @Test
    public void remotePolicyBindsBatchModeButNotUiOnlyPresentation() throws Exception {
        String policy = section(mainActivitySource(),
            "private String printPolicySha256(ProfileWorkflow workflow)",
            "private PrintRemoteContext capturePrintRemoteContext(");

        assertTrue(policy.contains("workflow.printingBatchEndRecheckMode"));
        assertFalse(policy.contains("workflow.printingUnknownStatusPresentation"));
    }

    @Test
    public void compatibilityNeverBypassesFreshBindingOrJournalChecks() throws Exception {
        String retry = section(mainActivitySource(),
            "private void retryPrint(PrintRemoteContext context, TextView header, LinearLayout list)",
            "private PrintConfirmationRules.Result confirmPrintInline(");

        assertTrue(retry.contains("latestPrintJobForSnBound("));
        assertTrue(retry.contains("reprintTargetBlocked(target)"));
        assertTrue(retry.contains("requirePrintRemoteBinding("));
        assertTrue(retry.contains("executeBoundReprint("));
        assertFalse(retry.contains("PRINT_BATCH_END_INLINE_ONLY"));
        assertFalse(retry.contains("PRINT_UNKNOWN_PRESENTATION_AS_ONGOING"));
    }

    @Test
    public void lookupFailuresAreNeverReportedAsMissingPrintJobs() throws Exception {
        String source = mainActivitySource();
        String inline = section(source,
            "private PrintConfirmationRules.Result confirmPrintInline(",
            "private void recheckDeferredPrintsAtBatchEnd(");
        String deferred = section(source,
            "private PrintConfirmationRules.Result recheckDeferredPrintUnit(",
            "private void markRoundLedgerPrinted(");
        String reconcile = section(source,
            "private void verifyRoundAgainstCloud(",
            "private void confirmAndRetryPrint(");

        assertTrue(inline.contains("outcomeUncertain = true"));
        assertTrue(inline.contains(
            "confirmedPrinted, jobEverSeen, outcomeUncertain"));
        assertTrue(deferred.contains(
            "return PrintConfirmationRules.Result.UNCERTAIN;"));
        assertFalse(deferred.contains(
            "? PrintConfirmationRules.Result.MISSING"));
        assertTrue(reconcile.contains("Print-job query failed:"));
        assertFalse(reconcile.contains("catch (Exception ignored)"));

        String lookup = section(source,
            "private PrintJobLookup latestPrintJobForSn(",
            "private boolean resolveConfirmedPrintedReprint(");
        assertTrue(lookup.contains("api.getPrintJobs("));
        assertTrue(lookup.contains("isJobsResponseSuccess("));
        assertTrue(lookup.contains("Print-job response has no configured jobs array"));
    }
}
