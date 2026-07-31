package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PreviousStepExecutionOrderRulesTest {
    @Test
    public void mergesStaticAndDynamicRecipesByOriginalSourceIndex() {
        List<PreviousStepExecutionOrderRules.Step> plan =
            PreviousStepExecutionOrderRules.plan(
                Arrays.asList(staticRecipe(0), staticRecipe(3)),
                Arrays.asList(dynamicRecipe(1), dynamicRecipe(2)));

        assertEquals(4, plan.size());
        assertEquals(0, plan.get(0).sourceIndex);
        assertFalse(plan.get(0).isDynamic());
        assertEquals(1, plan.get(1).sourceIndex);
        assertTrue(plan.get(1).isDynamic());
        assertEquals(2, plan.get(2).sourceIndex);
        assertTrue(plan.get(2).isDynamic());
        assertEquals(3, plan.get(3).sourceIndex);
        assertFalse(plan.get(3).isDynamic());

        try {
            plan.add(plan.get(0));
            throw new AssertionError("execution plan must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void negativeSourceIndexFailsClosed() {
        assertRejected(Collections.singletonList(staticRecipe(-1)),
            Collections.emptyList(), "negative sourceIndex");
        assertRejected(Collections.emptyList(),
            Collections.singletonList(dynamicRecipe(-1)), "negative sourceIndex");
    }

    @Test
    public void eachInputListMustBeStrictlyIncreasing() {
        assertRejected(Arrays.asList(staticRecipe(2), staticRecipe(1)),
            Collections.emptyList(), "static recipes must have strictly increasing");
        assertRejected(Collections.emptyList(),
            Arrays.asList(dynamicRecipe(4), dynamicRecipe(4)),
            "dynamic recipes must have strictly increasing");
    }

    @Test
    public void duplicateIndexAcrossRecipeKindsFailsClosed() {
        assertRejected(Collections.singletonList(staticRecipe(2)),
            Collections.singletonList(dynamicRecipe(2)), "duplicate sourceIndex 2");
    }

    private static ProfileWorkflow.PreviousStepRecipe staticRecipe(int sourceIndex) {
        return new ProfileWorkflow.PreviousStepRecipe(
            7001, 17, "SAMPLE-STATIC", new JSONObject(), "sample-serial",
            Collections.emptyList(), 0L, sourceIndex);
    }

    private static ProfileWorkflow.DynamicPreviousStepRecipe dynamicRecipe(int sourceIndex) {
        return new ProfileWorkflow.DynamicPreviousStepRecipe(
            7002, "sample-template-detail-v1", 7,
            Collections.singletonMap("sample-evidence", "sample-photo"),
            0L, sourceIndex);
    }

    private static void assertRejected(
            List<ProfileWorkflow.PreviousStepRecipe> staticRecipes,
            List<ProfileWorkflow.DynamicPreviousStepRecipe> dynamicRecipes,
            String expected) {
        try {
            PreviousStepExecutionOrderRules.plan(staticRecipes, dynamicRecipes);
            throw new AssertionError("execution order must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
