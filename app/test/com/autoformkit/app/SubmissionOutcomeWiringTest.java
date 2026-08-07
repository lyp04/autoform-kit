package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Freezes the fail-closed wiring between Panel-owned response evidence and submit retries. */
public class SubmissionOutcomeWiringTest {
    private static String mainSource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity.java not found from " + cwd);
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing method start " + start, from >= 0);
        assertTrue("missing method end " + end, to > from);
        return source.substring(from, to);
    }

    private static String sessionInvalidBranch(String source) {
        int from = source.indexOf("if (BackendSessionErrors.isSessionInvalid(");
        assertTrue("missing session-invalid branch", from >= 0);
        int thrown = source.indexOf("throw ", from);
        assertTrue("missing session-invalid throw", thrown > from);
        int to = source.indexOf(';', thrown);
        assertTrue("missing session-invalid throw terminator", to > thrown);
        return source.substring(from, to + 1);
    }

    @Test
    public void automaticMaterialRecoveryUsesOnlyTheStrictConfiguredMessageExtractor()
            throws Exception {
        String source = mainSource();
        String extractor = method(source,
            "private List<String> missingMaterials(",
            "private Set<String> notifySkipMaterialCodes(");

        assertTrue(extractor.contains(
            "MaterialCodeRules.findKnownCodesForAutomaticRecovery("));
        assertTrue(extractor.contains("excluded.addAll(notifySkipMaterialCodes())"));
        assertFalse(extractor.contains("MaterialCodeRules.findKnownCodes("));
    }

    @Test
    public void materialRefreshUsesTheFiniteProfileRetryBudgetBeforeAnyUpload()
            throws Exception {
        String source = mainSource();
        String batch = method(source,
            "private void submitBatch()",
            "private boolean hasRemainingSubmittableUnit(");
        String retry = method(source,
            "private void runWithPreUploadNetworkRetry(",
            "private String uploadImageWithReplayBarrier(");

        assertTrue(batch.contains(
            "runWithPreUploadNetworkRetry(\n"
                + "                    () -> refreshProfileMaterialsBeforeSubmit("));
        assertTrue(retry.contains("Api.isTransientApiNetworkError(exc)"));
        assertTrue(retry.contains(
            "retries >= workflow.submissionNetworkRetryMaxAttempts"));
        assertTrue(retry.contains("computeSubmissionNetworkRetryDelay(retries, workflow)"));
        assertFalse(retry.contains("beginActiveMainUploadBarrier("));
        assertFalse(retry.contains("action.run();\n                action.run();"));
    }

    @Test
    public void finalMissingResponseIsRecordedAndPromptedWithoutAnotherSubmit()
            throws Exception {
        String source = mainSource();
        String submit = method(source,
            "private void submitUnit(",
            "private void ensurePreviousSteps(");

        int decision = submit.indexOf(
            "boolean willRetry = attempt < workflow.submissionMaxAttempts");
        int record = submit.indexOf("recordRoundMissing(unit.sn, missing)", decision);
        int remember = submit.indexOf("rememberMissingMaterials(missing)", record);
        int notice = submit.indexOf("notifyMissing(unit.sn, firstTime, willRetry)", remember);
        int stop = submit.indexOf("if (!willRetry)", notice);
        int rebuild = submit.indexOf("payload = buildPayload(", stop);

        assertTrue(decision >= 0);
        assertTrue(record > decision);
        assertTrue(remember > record);
        assertTrue(notice > remember);
        assertTrue(stop > notice);
        assertTrue(rebuild > stop);
    }

    @Test
    public void sideEffectClassifiersNeverReceiveTheSerializedWholeResponse()
            throws Exception {
        String source = mainSource();

        assertFalse(source.contains("response.toString()"));
        assertFalse(source.contains("body.toString(), endpoints.sessionInvalidPolicy"));
        assertFalse(source.contains(
            "String text = body == null ? \"\" : body.toString()"));
        assertTrue(source.contains(
            "isRetryableResponse(\n                            response, api.endpoints.response)"));
        assertTrue(source.contains(
            "isMissingMaterialResponse(response, api.endpoints.response)"));
        assertTrue(source.contains(
            "policy.recipeResponseDisposition(response, api.endpoints.response)"));
        assertFalse(source.contains(
            "policy.isRetryableResponse(response, api.endpoints.response)"));
        assertFalse(source.contains(
            "policy.isAlreadyExistsResponse(response, api.endpoints.response)"));
        assertTrue(source.contains(
            "effectiveRecipeMaxAttempts = policy.hasRecipeRetryableNotWrittenRules()"));
    }

    @Test
    public void sessionInvalidNeverMakesASideEffectingPostReplayable()
            throws Exception {
        String source = mainSource();
        String alternate = sessionInvalidBranch(method(source,
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides("));
        String main = sessionInvalidBranch(method(source,
            "private JournaledSubmissionResponse postMainSubmissionOnce(",
            "private void confirmMainSubmissionRejected("));
        String previous = sessionInvalidBranch(method(source,
            "private void submitAutoStepPayload(",
            "private JSONArray checkDuplicate("));

        for (String branch : new String[]{alternate, main, previous}) {
            assertTrue(branch.contains("markUncertain("));
            assertFalse(branch.contains("markServerRejected("));
            assertFalse(branch.contains("markExplicitlyRejected("));
            assertFalse(branch.contains("clearMainSubmissionAttempt("));
            assertFalse(branch.contains("clearAlternateSubmissionAttempt("));
        }
    }

    @Test
    public void profileOptInTurnsOnlyParsedNonSuccessIntoAnOrdinaryRejection()
            throws Exception {
        String source = mainSource();
        String submit = method(source,
            "private void submitUnit(",
            "private void ensurePreviousSteps(");
        String retry = method(source,
            "private void runWithSubmissionNetworkRetry(",
            "private void runWithPreUploadNetworkRetry(");

        int parsedResponse = submit.indexOf("JSONObject response = journaled.response");
        int optIn = submit.indexOf(
            "workflow.submissionStructuredNonSuccessAction", parsedResponse);
        int rejected = submit.indexOf("confirmMainSubmissionRejected(journaled)", optIn);
        int ordinaryFailure = submit.indexOf(
            "new SubmissionExplicitlyRejectedException", rejected);
        assertTrue(parsedResponse >= 0 && parsedResponse < optIn);
        assertTrue(optIn < rejected && rejected < ordinaryFailure);
        assertTrue(retry.contains("exc instanceof SubmissionExplicitlyRejectedException"));
        assertTrue(retry.contains("finishActiveMainUploadBarrier(context)"));
        assertFalse(retry.substring(retry.indexOf(
            "exc instanceof SubmissionExplicitlyRejectedException"))
            .contains("markUncertain("));
    }
}
