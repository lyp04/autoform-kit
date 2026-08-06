package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
            "if (workflow.shouldDirectCreatePreviousSteps(unit.grade))");
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
}
