package com.autoformkit.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure, fail-closed merge of static and template-detail previous-step recipes. */
final class PreviousStepExecutionOrderRules {
    private PreviousStepExecutionOrderRules() {
    }

    static List<Step> plan(ProfileWorkflow workflow) {
        if (workflow == null) throw invalid("workflow");
        return plan(workflow.previousStepRecipes, workflow.dynamicPreviousStepRecipes);
    }

    static List<Step> plan(List<ProfileWorkflow.PreviousStepRecipe> staticRecipes,
                           List<ProfileWorkflow.DynamicPreviousStepRecipe> dynamicRecipes) {
        if (staticRecipes == null) throw invalid("static recipes");
        if (dynamicRecipes == null) throw invalid("dynamic recipes");
        validateStatic(staticRecipes);
        validateDynamic(dynamicRecipes);

        List<Step> ordered = new ArrayList<>(staticRecipes.size() + dynamicRecipes.size());
        int staticIndex = 0;
        int dynamicIndex = 0;
        while (staticIndex < staticRecipes.size() || dynamicIndex < dynamicRecipes.size()) {
            ProfileWorkflow.PreviousStepRecipe staticRecipe = staticIndex < staticRecipes.size()
                ? staticRecipes.get(staticIndex) : null;
            ProfileWorkflow.DynamicPreviousStepRecipe dynamicRecipe =
                dynamicIndex < dynamicRecipes.size() ? dynamicRecipes.get(dynamicIndex) : null;
            if (staticRecipe != null && dynamicRecipe != null
                    && staticRecipe.sourceIndex == dynamicRecipe.sourceIndex) {
                throw invalid("duplicate sourceIndex " + staticRecipe.sourceIndex);
            }
            if (dynamicRecipe == null || (staticRecipe != null
                    && staticRecipe.sourceIndex < dynamicRecipe.sourceIndex)) {
                ordered.add(Step.staticRecipe(staticRecipe));
                staticIndex++;
            } else {
                ordered.add(Step.dynamicRecipe(dynamicRecipe));
                dynamicIndex++;
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    private static void validateStatic(List<ProfileWorkflow.PreviousStepRecipe> recipes) {
        int previous = -1;
        for (int index = 0; index < recipes.size(); index++) {
            ProfileWorkflow.PreviousStepRecipe recipe = recipes.get(index);
            if (recipe == null) throw invalid("static recipes[" + index + "]");
            if (recipe.sourceIndex < 0) throw invalid("negative sourceIndex");
            if (index > 0 && recipe.sourceIndex <= previous) {
                throw invalid("static recipes must have strictly increasing sourceIndex");
            }
            previous = recipe.sourceIndex;
        }
    }

    private static void validateDynamic(
            List<ProfileWorkflow.DynamicPreviousStepRecipe> recipes) {
        int previous = -1;
        for (int index = 0; index < recipes.size(); index++) {
            ProfileWorkflow.DynamicPreviousStepRecipe recipe = recipes.get(index);
            if (recipe == null) throw invalid("dynamic recipes[" + index + "]");
            if (recipe.sourceIndex < 0) throw invalid("negative sourceIndex");
            if (index > 0 && recipe.sourceIndex <= previous) {
                throw invalid("dynamic recipes must have strictly increasing sourceIndex");
            }
            previous = recipe.sourceIndex;
        }
    }

    static final class Step {
        final int sourceIndex;
        final ProfileWorkflow.PreviousStepRecipe staticRecipe;
        final ProfileWorkflow.DynamicPreviousStepRecipe dynamicRecipe;

        private Step(int sourceIndex, ProfileWorkflow.PreviousStepRecipe staticRecipe,
                     ProfileWorkflow.DynamicPreviousStepRecipe dynamicRecipe) {
            this.sourceIndex = sourceIndex;
            this.staticRecipe = staticRecipe;
            this.dynamicRecipe = dynamicRecipe;
        }

        static Step staticRecipe(ProfileWorkflow.PreviousStepRecipe recipe) {
            return new Step(recipe.sourceIndex, recipe, null);
        }

        static Step dynamicRecipe(ProfileWorkflow.DynamicPreviousStepRecipe recipe) {
            return new Step(recipe.sourceIndex, null, recipe);
        }

        boolean isDynamic() {
            return dynamicRecipe != null;
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("previous-step execution order rejected: " + detail);
    }
}
