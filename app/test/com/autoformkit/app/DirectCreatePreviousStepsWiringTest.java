package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Freezes the no-initial-lookup boundary without weakening recipe journals or verification. */
public class DirectCreatePreviousStepsWiringTest {
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

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    @Test
    public void directCreateBranchPrecedesTheOrdinaryExistenceLookup() throws Exception {
        String ensure = section(mainSource(),
            "private void ensurePreviousSteps(",
            "private void requirePreviousStepSideEffectCapability(");

        int receipt = ensure.indexOf("validateVerifiedPreviousStepSubmissionAttempt(");
        int continuation = ensure.indexOf(
            "if (retained != null && retained.requiresRecipeContinuation())");
        int direct = ensure.indexOf(
            "if (directCreate)");
        int ordinaryLookup = ensure.indexOf(
            "JSONObject body = previousStepsResponse(api, unit, expectedDraftBinding);");

        assertTrue(receipt >= 0);
        assertTrue(continuation > receipt);
        assertTrue(direct > continuation);
        assertTrue(ordinaryLookup > direct);

        String directBranch = ensure.substring(direct, ordinaryLookup);
        assertTrue(directBranch.contains("if (retained == null)"));
        assertTrue(directBranch.contains("runPreviousStepRecipesAndVerify("));
        assertTrue(directBranch.contains("verifyPreviousSteps("));
        assertTrue(directBranch.contains("markPreviousStepsOk("));
        assertTrue(directBranch.contains("return;"));
        assertFalse(directBranch.contains("previousStepsResponse("));
        assertFalse(directBranch.contains("tryCorrectSnFromPreviousSteps("));

        int directDecision = ensure.indexOf(
            "final boolean directCreate = workflow.shouldDirectCreatePreviousSteps(unit.grade);");
        int checkingLog = ensure.indexOf(
            "if (!directCreate) appendUnitLog(unit, t(\"checking_steps\"));");
        assertTrue(directDecision >= 0 && checkingLog > directDecision);
    }

    @Test
    public void directCreationStillUsesThePostRecipeVerificationLookup() throws Exception {
        String source = mainSource();
        String runAndVerify = section(source,
            "private JSONObject runPreviousStepRecipesAndVerify(",
            "private JSONObject verifyPreviousSteps(");
        String verify = section(source,
            "private JSONObject verifyPreviousSteps(",
            "private void alignSnCaseToPreviousSteps(");
        int recipes = runAndVerify.indexOf("runConfiguredPreviousStepRecipes(");
        int verification = runAndVerify.indexOf("verifyPreviousSteps(", recipes);
        int lookup = verify.indexOf("previousStepsResponse(");

        assertTrue(recipes >= 0);
        assertTrue(verification > recipes);
        assertTrue(lookup >= 0);
        assertTrue(verify.indexOf("if (api.isSuccess(body)) return body;", lookup) > lookup);
    }

    @Test
    public void completeExactReceiptSkipsEveryRecipePostWhilePartialReceiptContinues()
            throws Exception {
        String source = mainSource();
        String ensure = section(source,
            "private void ensurePreviousSteps(",
            "private void requirePreviousStepSideEffectCapability(");
        String recipes = section(source,
            "private void runConfiguredPreviousStepRecipes(",
            "private List<String> uploadPreviousStepSource(");

        int continuation = ensure.indexOf(
            "if (retained != null && retained.requiresRecipeContinuation())");
        int continueRun = ensure.indexOf("runPreviousStepRecipesAndVerify(", continuation);
        assertTrue(continuation >= 0 && continueRun > continuation);

        int direct = ensure.indexOf(
            "if (directCreate)");
        assertTrue(direct > continuation);
        int fresh = ensure.indexOf("if (retained == null)", direct);
        int complete = ensure.indexOf("} else {", fresh);
        int directEnd = ensure.indexOf(
            "if (direct != null && api.isSuccess(direct))", complete);
        String freshBranch = ensure.substring(fresh, complete);
        String completeReceiptBranch = ensure.substring(complete, directEnd);
        assertTrue(freshBranch.contains("runPreviousStepRecipesAndVerify("));
        assertTrue(completeReceiptBranch.contains("verifyPreviousSteps("));
        assertFalse(completeReceiptBranch.contains("runPreviousStepRecipesAndVerify("));
        assertFalse(completeReceiptBranch.contains("runConfiguredPreviousStepRecipes("));

        int completedPrefix = recipes.indexOf(
            "if (executionOrder <= completedRecipeCount)");
        int dynamicPostPath = recipes.indexOf("if (step.isDynamic())", completedPrefix);
        int staticPostPath = recipes.indexOf(
            "ProfileWorkflow.PreviousStepRecipe recipe = step.staticRecipe", completedPrefix);
        assertTrue(completedPrefix >= 0);
        assertTrue(dynamicPostPath > completedPrefix);
        assertTrue(staticPostPath > completedPrefix);
        assertTrue(recipes.substring(completedPrefix, dynamicPostPath).contains("continue;"));
    }

    @Test
    public void directCreateProfileKeepsTwoRecipesAndTheDeclaredCurrentPhotoBinding()
            throws Exception {
        String directResult = "sample-direct";
        String currentPhoto = "sample-current-photo";
        JSONObject previous = new JSONObject()
            .put("enabled", true)
            .put("scanPrecheck", true)
            .put("scanPrecheckExcludedResultKeys", new JSONArray().put(directResult))
            .put("triggerResultKeys", new JSONArray().put(directResult))
            .put("directCreateResultKeys", new JSONArray().put(directResult))
            .put("artifacts", new JSONArray().put(new JSONObject()
                .put("key", currentPhoto)
                .put("title", "Sample current photo")
                .put("required", true)
                .put("uploadNameTemplate", "{identifier}-sample-current.jpg")))
            .put("templates", new JSONArray()
                .put(dynamicRecipe(7001, 1, new JSONObject()
                    .put("sample-photo-alias", currentPhoto)))
                .put(dynamicRecipe(7002, 2, new JSONObject())));
        ProfileWorkflow workflow = ProfileWorkflow.from(new JSONObject()
            .put("workflow", new JSONObject().put("previousSteps", previous)));

        assertTrue(workflow.shouldDirectCreatePreviousSteps(directResult));
        assertTrue(workflow.shouldAutoCreateDynamicPreviousSteps(directResult));
        assertFalse(workflow.shouldScanPrecheck(directResult));
        assertFalse(workflow.shouldDirectCreatePreviousSteps("sample-other"));
        assertFalse(workflow.shouldAutoCreateDynamicPreviousSteps("sample-other"));
        assertTrue(workflow.shouldScanPrecheck("sample-other"));
        assertEquals(1, workflow.workflowArtifacts.size());
        assertTrue(workflow.workflowArtifacts.get(0).required);
        assertEquals(currentPhoto, workflow.workflowArtifacts.get(0).key);
        assertEquals(2, workflow.dynamicPreviousStepRecipes.size());
        assertTrue(workflow.dynamicPreviousStepRecipes.get(0).sources
            .containsValue(currentPhoto));
        assertTrue(workflow.dynamicPreviousStepRecipes.get(1).sources.isEmpty());

        java.util.List<PreviousStepExecutionOrderRules.Step> plan =
            PreviousStepExecutionOrderRules.plan(workflow);
        assertEquals(2, plan.size());
        assertTrue(plan.get(0).isDynamic());
        assertTrue(plan.get(1).isDynamic());
        assertEquals(0, plan.get(0).sourceIndex);
        assertEquals(1, plan.get(1).sourceIndex);
    }

    private static JSONObject dynamicRecipe(
            int templateId, int expectedStep, JSONObject sources) throws Exception {
        return new JSONObject()
            .put("templateId", templateId)
            .put("mode", "template_detail")
            .put("resolverId", "sample-template-detail-v1")
            .put("expectedStep", expectedStep)
            .put("sources", sources)
            .put("delayAfterMs", 0);
    }
}
