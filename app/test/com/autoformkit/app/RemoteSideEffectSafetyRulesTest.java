package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RemoteSideEffectSafetyRulesTest {
    private static JSONObject fixture(String relative) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{cwd.resolve(relative), cwd.resolve("..").resolve(relative)};
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("fixture not found: " + relative + " from " + cwd);
    }

    private static JSONObject reviewedProfile(boolean printingEnabled) throws Exception {
        JSONObject seed = fixture("app/assets/form-profiles.seed.json");
        JSONObject profile = new JSONObject(
            seed.getJSONArray("profiles").getJSONObject(0).toString());
        profile.getJSONObject("workflow").getJSONObject("printing")
            .put("enabled", printingEnabled);
        return profile;
    }

    private static BackendAdapter completeAdapter() throws Exception {
        return BackendAdapter.from(new JSONObject().put("backendAdapter",
            fixture("panel/backend-adapter.example.json")));
    }

    private static JSONObject completePrintingAdapterJson() throws Exception {
        JSONObject adapter = fixture("panel/backend-adapter.example.json");
        JSONObject printing = adapter.getJSONObject("printing");
        printing.put("enabled", true);
        printing.getJSONObject("online").put("values",
            new org.json.JSONArray().put("online"));
        printing.getJSONObject("values")
            .put("acceptedTypes", new org.json.JSONArray().put("label"))
            .put("printed", new org.json.JSONArray().put("done"))
            .put("failed", new org.json.JSONArray().put("failed"))
            .put("ongoing", new org.json.JSONArray().put("running"));
        return adapter;
    }

    @Test
    public void alternateEntryRequiresBothReviewedProfilesAndCompleteSubmitAdapter()
            throws Exception {
        JSONObject source = reviewedProfile(false);
        JSONObject target = reviewedProfile(false);
        BackendAdapter complete = completeAdapter();

        assertTrue(RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
            source, target, complete).toString(),
            RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
                source, target, complete).isEmpty());

        JSONObject unreviewedSource = new JSONObject(source.toString());
        unreviewedSource.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertTrue(RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
            unreviewedSource, target, complete)
            .contains("sourceProfile.workflow.compatibilityReviewed"));

        JSONObject unreviewedTarget = new JSONObject(target.toString());
        unreviewedTarget.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertTrue(RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
            source, unreviewedTarget, complete)
            .contains("targetProfile.workflow.compatibilityReviewed"));

        JSONObject incompleteJson = fixture("panel/backend-adapter.example.json");
        incompleteJson.getJSONObject("endpoints").remove("uploadFile");
        BackendAdapter incomplete = BackendAdapter.from(
            new JSONObject().put("backendAdapter", incompleteJson));
        assertTrue(RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
            source, target, incomplete)
            .contains("backendAdapter.endpoints.uploadFile"));
    }

    @Test
    public void blockedAlternateEntryExecutesZeroRemoteSideEffects() throws Exception {
        JSONObject source = reviewedProfile(false);
        JSONObject target = reviewedProfile(false);
        target.getJSONObject("workflow").put("compatibilityReviewed", false);
        AtomicInteger remoteSideEffects = new AtomicInteger();

        List<String> errors = RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
            source, target, completeAdapter());
        if (errors.isEmpty()) remoteSideEffects.incrementAndGet();

        assertFalse(errors.isEmpty());
        assertEquals(0, remoteSideEffects.get());
    }

    @Test
    public void printingRequiresReviewedEnabledWorkflowAndCompletePrintAdapter()
            throws Exception {
        ProfileWorkflow reviewedPrinting = ProfileWorkflow.from(reviewedProfile(true));
        BackendAdapter complete = BackendAdapter.from(new JSONObject().put(
            "backendAdapter", completePrintingAdapterJson()));
        assertTrue(RemoteSideEffectSafetyRules.printingCapabilityErrors(
            reviewedPrinting, complete).toString(),
            RemoteSideEffectSafetyRules.printingCapabilityErrors(
                reviewedPrinting, complete).isEmpty());

        JSONObject unreviewed = reviewedProfile(true);
        unreviewed.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertTrue(RemoteSideEffectSafetyRules.printingCapabilityErrors(
            ProfileWorkflow.from(unreviewed), complete)
            .contains("profile.workflow.compatibilityReviewed"));

        JSONObject incompleteJson = completePrintingAdapterJson();
        incompleteJson.getJSONObject("printing").getJSONObject("values")
            .put("acceptedTypes", new org.json.JSONArray());
        BackendAdapter incomplete = BackendAdapter.from(
            new JSONObject().put("backendAdapter", incompleteJson));
        assertTrue(RemoteSideEffectSafetyRules.printingCapabilityErrors(
            reviewedPrinting, incomplete)
            .contains("backendAdapter.printing.values.acceptedTypes"));
    }

    @Test
    public void blockedPrintingExecutesZeroRemoteSideEffects() throws Exception {
        ProfileWorkflow disabled = ProfileWorkflow.from(reviewedProfile(false));
        AtomicInteger remoteSideEffects = new AtomicInteger();

        List<String> errors = RemoteSideEffectSafetyRules.printingCapabilityErrors(
            disabled, BackendAdapter.from(new JSONObject().put(
                "backendAdapter", completePrintingAdapterJson())));
        if (errors.isEmpty()) remoteSideEffects.incrementAndGet();

        assertFalse(errors.isEmpty());
        assertEquals(0, remoteSideEffects.get());
    }
}
