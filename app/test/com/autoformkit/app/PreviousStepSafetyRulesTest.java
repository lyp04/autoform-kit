package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class PreviousStepSafetyRulesTest {
    private static JSONObject fixture(String path) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{cwd.resolve(path), cwd.resolve("../").resolve(path)};
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new JSONObject(new String(
                    Files.readAllBytes(candidate), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("fixture not found: " + path);
    }

    @Test
    public void concurrentResultsAreDecidedInPanelOrder() throws Exception {
        PreviousStepSafetyRules.CandidateOutcome first =
            PreviousStepSafetyRules.CandidateOutcome.found("panel-first");
        PreviousStepSafetyRules.CandidateOutcome second =
            PreviousStepSafetyRules.CandidateOutcome.found("panel-second");
        List<Future<PreviousStepSafetyRules.CandidateOutcome>> bothReady = Arrays.asList(
            CompletableFuture.completedFuture(first),
            CompletableFuture.completedFuture(second));
        assertEquals("panel-first",
            PreviousStepSafetyRules.awaitFirstFoundInPanelOrder(bothReady, 1000L).target);

        List<Future<PreviousStepSafetyRules.CandidateOutcome>> firstMissing = Arrays.asList(
            CompletableFuture.completedFuture(
                PreviousStepSafetyRules.CandidateOutcome.missing()),
            CompletableFuture.completedFuture(second));
        assertEquals("panel-second",
            PreviousStepSafetyRules.awaitFirstFoundInPanelOrder(firstMissing, 1000L).target);
    }

    @Test
    public void unknownHigherPriorityAndTimeoutCannotApplyLowerSuccess() throws Exception {
        CompletableFuture<PreviousStepSafetyRules.CandidateOutcome> unknown =
            new CompletableFuture<>();
        unknown.completeExceptionally(new IOException("unknown higher-priority response"));
        List<Future<PreviousStepSafetyRules.CandidateOutcome>> unknownThenSuccess =
            Arrays.asList(unknown, CompletableFuture.completedFuture(
                PreviousStepSafetyRules.CandidateOutcome.found("lower")));
        try {
            PreviousStepSafetyRules.awaitFirstFoundInPanelOrder(
                unknownThenSuccess, 1000L);
            fail("unknown higher-priority result must fail closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unknown higher-priority"));
        }

        CompletableFuture<PreviousStepSafetyRules.CandidateOutcome> pending =
            new CompletableFuture<>();
        List<Future<PreviousStepSafetyRules.CandidateOutcome>> timeoutThenSuccess =
            Arrays.asList(pending, CompletableFuture.completedFuture(
                PreviousStepSafetyRules.CandidateOutcome.found("lower")));
        AtomicInteger corrections = new AtomicInteger();
        AtomicInteger missingActions = new AtomicInteger();
        try {
            PreviousStepSafetyRules.CandidateOutcome outcome =
                PreviousStepSafetyRules.awaitFirstFoundInPanelOrder(
                    timeoutThenSuccess, 5L);
            if (outcome == null) missingActions.incrementAndGet();
            else corrections.incrementAndGet();
            fail("timeout must stay distinct from all candidates being missing");
        } catch (PreviousStepSafetyRules.CandidateLookupTimeoutException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        assertEquals(0, corrections.get());
        assertEquals(0, missingActions.get());
    }

    @Test
    public void unclassifiedLookupNeverBecomesTransientFromItsMessage() {
        assertFalse(MainActivity.Api.isTransientApiNetworkError(
            new MainActivity.PreviousStepLookupUnclassifiedException("timeout")));
        assertFalse(MainActivity.Api.isTransientApiNetworkError(
            new MainActivity.PreviousStepLookupUnclassifiedException(
                "failed to connect")));
        assertFalse(MainActivity.Api.isTransientApiNetworkError(new IOException(
            "timed out wrapper",
            new MainActivity.PreviousStepLookupUnclassifiedException(
                "failed to connect"))));
    }

    @Test
    public void sideEffectsRequireReviewedWorkflowAndCompleteAdapter() throws Exception {
        JSONObject seed = fixture("app/assets/form-profiles.seed.json");
        JSONObject profile = new JSONObject(
            seed.getJSONArray("profiles").getJSONObject(0).toString());
        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("enabled", true);
        ProfileWorkflow reviewed = ProfileWorkflow.from(profile);
        JSONObject adapterJson = fixture("panel/backend-adapter.example.json");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", adapterJson));

        assertTrue(PreviousStepSafetyRules.sideEffectCapabilityErrors(
            reviewed, adapter).toString(),
            PreviousStepSafetyRules.sideEffectCapabilityErrors(reviewed, adapter).isEmpty());
        assertTrue(PreviousStepSafetyRules.lookupCapabilityErrors(
            reviewed, adapter).isEmpty());

        JSONObject unreviewedJson = new JSONObject(profile.toString());
        unreviewedJson.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertTrue(PreviousStepSafetyRules.sideEffectCapabilityErrors(
            ProfileWorkflow.from(unreviewedJson), adapter)
            .contains("profile.workflow.compatibilityReviewed"));
        assertTrue(PreviousStepSafetyRules.lookupCapabilityErrors(
            ProfileWorkflow.from(unreviewedJson), adapter)
            .contains("profile.workflow.compatibilityReviewed"));

        JSONObject legacyAdapterJson = new JSONObject(adapterJson.toString());
        legacyAdapterJson.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("missingResponseCodes");
        BackendAdapter legacyAdapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", legacyAdapterJson));
        assertTrue(legacyAdapter.isSupported());
        assertTrue(PreviousStepSafetyRules.sideEffectCapabilityErrors(
            reviewed, legacyAdapter)
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));
        assertTrue(PreviousStepSafetyRules.lookupCapabilityErrors(reviewed, legacyAdapter)
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));
    }
}
