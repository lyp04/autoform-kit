package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BundledSeedTest {
    private static JSONObject asset(String name) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/assets").resolve(name),
            cwd.resolve("assets").resolve(name)
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("bundled asset " + name + " not found from " + cwd);
    }

    private static JSONObject seed() throws Exception {
        return asset("form-profiles.seed.json");
    }

    @Test
    public void seedIsExplicitlyNonOperationalAndUsesArbitraryResultKeys() throws Exception {
        JSONObject root = seed();
        assertTrue(root.getJSONObject("settings").getBoolean("sampleCatalog"));
        JSONArray profiles = root.getJSONArray("profiles");
        assertTrue(profiles.length() > 0);
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            assertTrue(profile.getBoolean("pickerVisible"));
            assertNotNull(profile.getJSONArray("snPlugins").getJSONObject(0));
            JSONObject results = profile.getJSONObject("gradeMap");
            assertEquals(2, results.length());
            assertTrue(results.has("sample-ready"));
            assertTrue(results.has("sample-review"));
            JSONObject previous = profile.getJSONObject("workflow")
                .getJSONObject("previousSteps");
            ProfileWorkflow workflow = ProfileWorkflow.from(profile);
            assertTrue(workflow.operationalPoliciesExplicit);
            assertFalse(workflow.includeOptionalPhotoSlots);
            assertEquals(1, workflow.previousStepRecipeMaxAttempts);
            assertEquals(0L, workflow.previousStepRecipeRetryDelayMs);
            assertTrue(workflow.printingManualReprintStatuses.isEmpty());
            assertTrue(workflow.printingManualReprintRequiresConfirmation);
            assertEquals(ProfileWorkflow.PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS,
                workflow.printingBatchEndRecheckMode);
            assertEquals(ProfileWorkflow.PRINT_UNKNOWN_PRESENTATION_DISTINCT,
                workflow.printingUnknownStatusPresentation);
            assertEquals(0L, workflow.submissionInterUnitDelayMs);
            assertEquals(1, workflow.roundLedgerRetentionDays);
            assertNotNull(previous.getJSONArray("scanPrecheckExcludedResultKeys"));
            assertNotNull(previous.getJSONArray("triggerResultKeys"));
            assertNotNull(previous.getJSONArray("artifacts"));
            assertNotNull(previous.getJSONArray("templates"));
        }
    }

    @Test
    public void publicSeedAndUpdateDefaultsContainNoDeploymentCoordinates() throws Exception {
        JSONObject settings = seed().getJSONObject("settings");
        for (String key : new String[]{
                "backendAdapter", "backendApiBase", "endpoints", "webOrigin", "webReferer",
                "updateSource", "updateOwner", "updateRepo", "notifyWebhook"}) {
            assertFalse(key, settings.has(key));
        }

        JSONObject update = asset("update-config.json");
        assertEquals("", update.getString("owner"));
        assertEquals("", update.getString("repo"));
        assertEquals("update.json", update.getString("manifestAsset"));
    }

    @Test
    public void newRecipeAndManualReprintFieldsArePartOfTheExplicitCompatibilityGate()
            throws Exception {
        JSONObject profile = seed().getJSONArray("profiles").getJSONObject(0);
        String[][] requiredPaths = new String[][]{
            {"previousSteps", "recipeMaxAttempts"},
            {"previousSteps", "recipeRetryDelayMs"},
            {"printing", "manualReprintStatuses"},
            {"printing", "manualReprintRequiresConfirmation"}
        };

        for (String[] path : requiredPaths) {
            JSONObject incomplete = new JSONObject(profile.toString());
            incomplete.getJSONObject("workflow").getJSONObject(path[0]).remove(path[1]);
            assertFalse(path[0] + "." + path[1],
                ProfileWorkflow.from(incomplete).operationalPoliciesExplicit);
        }
    }
}
