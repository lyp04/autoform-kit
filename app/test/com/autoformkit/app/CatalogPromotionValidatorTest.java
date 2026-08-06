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

public class CatalogPromotionValidatorTest {
    @Test
    public void acceptsTrackedSampleAndBoundedLegacyPickerPhotoContracts()
            throws Exception {
        JSONObject current = catalog();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(current));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(current, config()));

        JSONObject legacy = catalog();
        legacy.getJSONObject("settings").remove("dailyStats");
        legacy.getJSONObject("settings").remove("dailyStatsV2");
        JSONArray profiles = legacy.getJSONArray("profiles");
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            profile.remove("pickerVisible");
            profile.remove("defaultPhotoOrder");
            JSONArray slots = profile.getJSONArray("photoSlots");
            JSONArray uploads = new JSONArray();
            for (int slotIndex = 0; slotIndex < slots.length(); slotIndex++) {
                JSONObject slot = slots.getJSONObject(slotIndex);
                uploads.put(new JSONObject()
                    .put("field", slot.getString("field"))
                    .put("sources", new JSONArray().put(
                        slotIndex == 0 ? "front" : "back")));
            }
            profile.remove("photoSlots");
            profile.remove("optionalSlots");
            profile.put("uploadFields", uploads);
            profile.getJSONObject("workflow").getJSONObject("photos")
                .put("includeOptionalSlots", false);
        }
        assertTrue(CatalogPromotionValidator.isStructurallyValid(legacy));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(legacy, config()));
    }

    @Test
    public void oldCatalogWithoutPrintCompatibilityFieldsUsesCurrentSafeFallback()
            throws Exception {
        JSONObject legacy = catalog();
        JSONArray profiles = legacy.getJSONArray("profiles");
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject printing = profiles.getJSONObject(index)
                .getJSONObject("workflow").getJSONObject("printing");
            printing.remove("batchEndRecheckMode");
            printing.remove("unknownStatusPresentation");

            ProfileWorkflow workflow = ProfileWorkflow.from(profiles.getJSONObject(index));
            assertTrue(workflow.operationalPoliciesExplicit);
            assertTrue(workflow.usesDeferredMissingTwoPassRecheck());
            assertFalse(workflow.presentsUnknownPrintStatusAsOngoing());
        }

        assertTrue(CatalogPromotionValidator.isStructurallyValid(legacy));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(legacy, config()));
    }

    @Test
    public void rejectsPresentButInvalidPrintCompatibilityFields() throws Exception {
        JSONObject invalidBatchMode = catalog();
        firstProfile(invalidBatchMode).getJSONObject("workflow")
            .getJSONObject("printing").put("batchEndRecheckMode", "unbounded");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(invalidBatchMode));

        JSONObject invalidPresentation = catalog();
        firstProfile(invalidPresentation).getJSONObject("workflow")
            .getJSONObject("printing").put("unknownStatusPresentation", "guess");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(invalidPresentation));
    }

    @Test
    public void rejectsMalformedDuplicateOrUnreachablePickerProfiles()
            throws Exception {
        JSONObject emptyObject = catalog();
        emptyObject.put("profiles", new JSONArray().put(new JSONObject()));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(emptyObject));

        JSONObject duplicate = catalog();
        duplicate.getJSONArray("profiles").getJSONObject(1).put("id",
            duplicate.getJSONArray("profiles").getJSONObject(0).getString("id"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicate));

        JSONObject noPicker = catalog();
        for (int index = 0; index < noPicker.getJSONArray("profiles").length(); index++) {
            noPicker.getJSONArray("profiles").getJSONObject(index)
                .put("pickerVisible", false);
        }
        assertFalse(CatalogPromotionValidator.isStructurallyValid(noPicker));

        JSONObject policyLess = catalog();
        policyLess.getJSONArray("profiles").getJSONObject(0).remove("workflow");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(policyLess));
    }

    @Test
    public void legacyEmptyOptionalSecondaryIdentifierRemainsCompatible()
            throws Exception {
        JSONObject compatible = catalog();
        firstProfile(compatible).getJSONObject("snFields").put("secondary", "");
        assertTrue(CatalogPromotionValidator.isStructurallyValid(compatible));

        JSONObject required = copy(compatible);
        firstProfile(required).put("requiresSecondSn", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(required));

        JSONObject whitespace = copy(compatible);
        firstProfile(whitespace).getJSONObject("snFields").put("secondary", " ");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(whitespace));

        JSONObject wrongType = copy(compatible);
        firstProfile(wrongType).getJSONObject("snFields").put("secondary", false);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongType));
    }

    @Test
    public void rejectsConfiguredPhotoMutationsButAllowsInactiveLegacyOverlap()
            throws Exception {
        JSONObject invalidOrder = catalog();
        invalidOrder.getJSONArray("profiles").getJSONObject(0)
            .put("defaultPhotoOrder", "guessed-order");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(invalidOrder));

        JSONObject badBounds = catalog();
        badBounds.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("photoSlots").getJSONObject(0)
            .put("minPhotos", 2).put("maxPhotos", 1);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badBounds));

        JSONObject duplicateSlot = catalog();
        JSONArray slots = duplicateSlot.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("photoSlots");
        slots.getJSONObject(1).put("field",
            slots.getJSONObject(0).getString("field"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicateSlot));

        // During a staged old/new Panel rollout, inactive uploadFields may mirror the new slot
        // field. Slot mode owns the payload, so this is not an active ownership collision.
        JSONObject transition = catalog();
        JSONObject profile = transition.getJSONArray("profiles").getJSONObject(0);
        profile.put("uploadFields", new JSONArray().put(new JSONObject()
            .put("field", profile.getJSONArray("photoSlots")
                .getJSONObject(0).getString("field"))
            .put("sources", new JSONArray().put("front"))));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(transition));
    }

    @Test
    public void legacyUploadSourcesMustUseTheBoundedFrontBackContract()
            throws Exception {
        JSONObject compatible = catalog();
        firstProfile(compatible).put("uploadFields", new JSONArray().put(
            new JSONObject().put("field", "example_legacy_photo")));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(compatible));

        JSONObject unknown = copy(compatible);
        firstProfile(unknown).getJSONArray("uploadFields").getJSONObject(0)
            .put("sources", new JSONArray().put("front").put("example-unknown"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(unknown));

        JSONObject duplicate = copy(compatible);
        firstProfile(duplicate).getJSONArray("uploadFields").getJSONObject(0)
            .put("sources", new JSONArray().put("front").put("front"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicate));

        JSONObject empty = copy(compatible);
        firstProfile(empty).getJSONArray("uploadFields").getJSONObject(0)
            .put("sources", new JSONArray());
        assertFalse(CatalogPromotionValidator.isStructurallyValid(empty));

        JSONObject explicitNull = copy(compatible);
        firstProfile(explicitNull).getJSONArray("uploadFields").getJSONObject(0)
            .put("sources", JSONObject.NULL);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(explicitNull));
    }

    @Test
    public void materialItemsMustBeCompleteAndGloballyUnambiguous()
            throws Exception {
        JSONObject compatible = catalogWithMaterials();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(compatible));

        JSONObject missingCode = copy(compatible);
        firstMaterial(missingCode).remove("code");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingCode));

        JSONObject blankName = copy(compatible);
        firstMaterial(blankName).put("name", "");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(blankName));

        JSONObject missingQuantity = copy(compatible);
        firstMaterial(missingQuantity).remove("defaultQty");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingQuantity));

        JSONObject zeroQuantity = copy(compatible);
        firstMaterial(zeroQuantity).put("defaultQty", 0);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(zeroQuantity));

        JSONObject stringQuantity = copy(compatible);
        firstMaterial(stringQuantity).put("defaultQty", "1");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(stringQuantity));

        JSONObject duplicateCode = copy(compatible);
        firstProfile(duplicateCode).getJSONArray("materialGroups").put(
            materialGroup("example_materials_two", "EXAMPLE-ITEM-01"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicateCode));

        JSONObject javaPattern = copy(compatible);
        firstProfile(javaPattern).put("materialCodePattern",
            "\\QEXAMPLE-ITEM-01\\E");
        assertTrue(CatalogPromotionValidator.isStructurallyValid(javaPattern));

        JSONObject invalidPattern = copy(compatible);
        firstProfile(invalidPattern).put("materialCodePattern", "[");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(invalidPattern));

        JSONObject wrongPatternType = copy(compatible);
        firstProfile(wrongPatternType).put("materialCodePattern", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongPatternType));
    }

    @Test
    public void executableRetriesAndMissingRecoveryRequireStructuredOutcomeEvidence()
            throws Exception {
        JSONObject mainRetry = catalog();
        firstProfile(mainRetry).getJSONObject("workflow")
            .getJSONObject("submission").put("maxAttempts", 2);
        JSONObject noPolicy = config();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(mainRetry));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(mainRetry, noPolicy));

        // Legacy arrays remain available to old Apps, but cannot authorize a new App retry.
        noPolicy.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("submit").put("retryableMessagePatterns",
                new JSONArray().put("sample legacy retry"));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(mainRetry, noPolicy));

        JSONObject retryConfig = config();
        retryConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("submit").put("outcomePolicy", outcomePolicy(true, false));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(
            mainRetry, retryConfig));

        JSONObject missingRecovery = catalogWithMaterials();
        JSONObject missingWorkflow = firstProfile(missingRecovery)
            .getJSONObject("workflow");
        missingWorkflow.getJSONObject("submission").put("maxAttempts", 2);
        missingWorkflow.getJSONObject("materials").getJSONObject("missingRecovery")
            .put("enabled", true);
        assertTrue(CatalogPromotionValidator.isStructurallyValid(missingRecovery));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(
            missingRecovery, retryConfig));

        JSONObject completeConfig = config();
        completeConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("submit").put("outcomePolicy", outcomePolicy(true, true));
        // Rules alone are insufficient: extraction must be bound to a Java-compiled pattern.
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(
            missingRecovery, completeConfig));
        firstProfile(missingRecovery).put("materialCodePattern",
            "\\QEXAMPLE-ITEM-01\\E");
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(
            missingRecovery, completeConfig));
    }

    @Test
    public void choiceFieldsMustBeReviewedAndSelectDeclaredOptions()
            throws Exception {
        JSONObject compatible = catalogWithChoice();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(compatible));

        JSONObject missingOptions = copy(compatible);
        firstChoice(missingOptions).remove("options");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingOptions));

        JSONObject emptyOptions = copy(compatible);
        firstChoice(emptyOptions).put("options", new JSONArray());
        assertFalse(CatalogPromotionValidator.isStructurallyValid(emptyOptions));

        JSONObject malformedOption = copy(compatible);
        firstChoice(malformedOption).put("options", new JSONArray().put(
            new JSONObject().put("value", "example-one")));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(malformedOption));

        JSONObject duplicateOption = copy(compatible);
        firstChoice(duplicateOption).getJSONArray("options").put(
            new JSONObject().put("value", "example-one").put("label", "Duplicate"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicateOption));

        JSONObject unreviewed = copy(compatible);
        firstChoice(unreviewed).put("reviewRequired", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(unreviewed));

        JSONObject malformedReview = copy(compatible);
        firstChoice(malformedReview).put("reviewRequired", "false");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(malformedReview));

        JSONObject malformedRequired = copy(compatible);
        firstChoice(malformedRequired).put("required", "false");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(malformedRequired));

        JSONObject undeclared = copy(compatible);
        firstChoice(undeclared).put("value", "example-unknown");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(undeclared));

        JSONObject wrongSingleShape = copy(compatible);
        firstChoice(wrongSingleShape).put("value", new JSONArray().put("example-one"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongSingleShape));

        JSONObject wrongMultiShape = copy(compatible);
        firstChoice(wrongMultiShape).put("kind", "multi").put("value", "example-one");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongMultiShape));

        JSONObject emptyRequiredMulti = copy(compatible);
        firstChoice(emptyRequiredMulti).put("kind", "multi").put("value", new JSONArray());
        assertFalse(CatalogPromotionValidator.isStructurallyValid(emptyRequiredMulti));
    }

    @Test
    public void previousStepPhotoAndResolverReferencesMustClose()
            throws Exception {
        JSONObject danglingStatic = catalog();
        JSONObject profile = danglingStatic.getJSONArray("profiles").getJSONObject(0);
        JSONObject previous = profile.getJSONObject("workflow")
            .getJSONObject("previousSteps");
        previous.put("templates", new JSONArray().put(staticRecipe("missing-photo-source")));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(danglingStatic));

        JSONObject dynamic = catalogWithDynamicPreviousStep();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(dynamic));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(dynamic, config()));

        JSONObject danglingSource = catalogWithDynamicPreviousStep();
        danglingSource.getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("workflow").getJSONObject("previousSteps")
            .getJSONArray("templates").getJSONObject(0)
            .getJSONObject("sources").put("sample-evidence", "missing-photo-source");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(danglingSource));

        JSONObject missingResolverConfig = config();
        missingResolverConfig.getJSONObject("backendAdapter")
            .getJSONObject("operations").getJSONObject("previousSteps")
            .getJSONObject("recipeResolvers").remove("sample-template-detail-v1");
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(
            dynamic, missingResolverConfig));
    }

    @Test
    public void executablePreviousStepRecipesRequireOperationScopedOutcomeEvidence()
            throws Exception {
        JSONObject retrying = catalogWithDynamicPreviousStep();
        JSONObject previous = firstProfile(retrying).getJSONObject("workflow")
            .getJSONObject("previousSteps");
        previous.put("enabled", true)
            .put("triggerResultKeys", new JSONArray().put("sample-ready"))
            .put("recipeMaxAttempts", 2);
        assertTrue(CatalogPromotionValidator.isStructurallyValid(retrying));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(retrying, config()));

        JSONObject retryConfig = config();
        retryConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("previousSteps")
            .put("recipeOutcomePolicy", recipeOutcomePolicy(true, false));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(retrying, retryConfig));

        // Preserving an old-App "already exists" acknowledgement requires independent evidence.
        retryConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("previousSteps").put("alreadyExistsMessagePatterns",
                new JSONArray().put("sample recipe already applied"));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(retrying, retryConfig));
        retryConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("previousSteps")
            .put("recipeOutcomePolicy", recipeOutcomePolicy(true, true));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(retrying, retryConfig));

        JSONObject dormant = catalogWithDynamicPreviousStep();
        firstProfile(dormant).getJSONObject("workflow").getJSONObject("previousSteps")
            .put("recipeMaxAttempts", 2);
        // A stored recipe with the workflow disabled and no triggers opens no POST path.
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(dormant, config()));
    }

    @Test
    public void incompleteLegacyStaticRecipeIsRejectedInsteadOfSilentlyDisappearing()
            throws Exception {
        JSONObject missingLocation = catalog();
        JSONObject profile = missingLocation.getJSONArray("profiles").getJSONObject(0);
        JSONObject recipe = staticRecipe(
            profile.getJSONArray("photoSlots").getJSONObject(0).getString("field"));
        recipe.remove("warehouseId");
        recipe.remove("sku");
        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("enabled", true)
            .put("templates", new JSONArray().put(recipe));

        // ProfileWorkflow is the production parser. Its historical behavior is to omit an
        // incomplete static recipe; accepting this catalog would therefore disable a configured
        // remote step without warning. Promotion must fail instead of treating the omission as a
        // compatibility default.
        assertTrue(ProfileWorkflow.from(profile).previousStepRecipes.isEmpty());
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingLocation));
    }

    @Test
    public void anonymousLegacyTwoStepMappingCanUseClosedLiveResolvers()
            throws Exception {
        JSONObject migrated = catalog();
        JSONObject profile = migrated.getJSONArray("profiles").getJSONObject(0);
        String source = profile.getJSONArray("photoSlots")
            .getJSONObject(0).getString("field");
        profile.put("previousStepTemplates", new JSONObject()
            .put("step1TemplateId", 7101)
            .put("step2TemplateId", 7102));
        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("enabled", true)
            .put("templates", new JSONArray()
                .put(dynamicRecipe(7101, 1, "sample-template-detail-v1",
                    new JSONObject().put("sample-evidence", source)))
                .put(dynamicRecipe(7102, 2, "sample-no-photo-step-v1",
                    new JSONObject())));

        JSONObject config = config();
        JSONObject resolvers = config.getJSONObject("backendAdapter")
            .getJSONObject("operations").getJSONObject("previousSteps")
            .getJSONObject("recipeResolvers");
        JSONObject noPhoto = new JSONObject(
            resolvers.getJSONObject("sample-template-detail-v1").toString());
        JSONArray kinds = noPhoto.getJSONArray("kindSelectors");
        JSONArray retainedKinds = new JSONArray();
        for (int index = 0; index < kinds.length(); index++) {
            JSONObject item = kinds.getJSONObject(index);
            if (!"sample-photo".equals(item.optString("kind", ""))) {
                retainedKinds.put(item);
            }
        }
        JSONArray rules = noPhoto.getJSONArray("rules");
        JSONArray retainedRules = new JSONArray();
        for (int index = 0; index < rules.length(); index++) {
            JSONObject item = rules.getJSONObject(index);
            JSONObject action = item.optJSONObject("action");
            if (action == null || !"photo".equals(action.optString("type", ""))) {
                retainedRules.put(item);
            }
        }
        noPhoto.put("kindSelectors", retainedKinds).put("rules", retainedRules);
        resolvers.put("sample-no-photo-step-v1", noPhoto);

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);
        assertEquals(0, workflow.previousStepRecipes.size());
        assertEquals(2, workflow.dynamicPreviousStepRecipes.size());
        assertEquals(7101, ((Number) workflow.dynamicPreviousStepRecipes
            .get(0).templateId).intValue());
        assertEquals(1, ((Number) workflow.dynamicPreviousStepRecipes
            .get(0).expectedStep).intValue());
        assertEquals(7102, ((Number) workflow.dynamicPreviousStepRecipes
            .get(1).templateId).intValue());
        assertEquals(2, ((Number) workflow.dynamicPreviousStepRecipes
            .get(1).expectedStep).intValue());
        assertTrue(CatalogPromotionValidator.isStructurallyValid(migrated));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(migrated, config));
    }

    @Test
    public void schemaShapedButImpossibleLiveResolverIsNotExecutable()
            throws Exception {
        JSONObject dynamic = catalogWithDynamicPreviousStep();
        JSONObject config = config();
        JSONObject resolver = config.getJSONObject("backendAdapter")
            .getJSONObject("operations").getJSONObject("previousSteps")
            .getJSONObject("recipeResolvers")
            .getJSONObject("sample-template-detail-v1");

        JSONObject impossiblePredicate = new JSONObject()
            .put("attribute", "id")
            .put("caseSensitive", true)
            .put("present", true);
        resolver.getJSONArray("kindSelectors").getJSONObject(0)
            .put("selector", new JSONObject()
                .put("allOf", new JSONArray().put(
                    new JSONObject(impossiblePredicate.toString())))
                .put("noneOf", new JSONArray().put(
                    new JSONObject(impossiblePredicate.toString()))));

        // The catalog recipe itself is complete and the resolver still has every schema field.
        // It is nevertheless a non-executable placeholder because no field can match its first
        // kind. Promotion must fail before that latent production break reaches a live unit.
        assertTrue(CatalogPromotionValidator.isStructurallyValid(dynamic));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(dynamic, config));
    }

    @Test
    public void alternateTargetsPhotosPoliciesAndAdapterResolversMustClose()
            throws Exception {
        JSONObject alternate = catalogWithAlternate(false);
        assertTrue(CatalogPromotionValidator.isStructurallyValid(alternate));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(alternate, config()));

        JSONObject missingTarget = catalogWithAlternate(false);
        alternateEntry(missingTarget).put("targetProfileId", "missing-target");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingTarget));

        JSONObject visibleTarget = catalogWithAlternate(false);
        visibleTarget.getJSONArray("profiles").getJSONObject(2)
            .put("pickerVisible", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(visibleTarget));

        JSONObject danglingPhoto = catalogWithAlternate(false);
        alternateEntry(danglingPhoto).put(
            "photoTargetFields", new JSONArray().put("missing-photo-field"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(danglingPhoto));

        JSONObject dynamic = catalogWithAlternate(true);
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(dynamic, config()));
        JSONObject missingResolverConfig = config();
        missingResolverConfig.getJSONObject("backendAdapter")
            .getJSONObject("operations").getJSONObject("templateDetail")
            .getJSONObject("alternateEntryResolvers")
            .remove("sample-alternate-live-option-v1");
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(
            dynamic, missingResolverConfig));

        JSONObject retryingAlternate = catalogWithAlternate(false);
        alternateEntry(retryingAlternate).put("submissionRetry", new JSONObject()
            .put("maxAttempts", 2).put("retryDelayMs", 0));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(retryingAlternate));
        assertFalse(CatalogPromotionValidator.isExecutableWithConfig(
            retryingAlternate, config()));
        JSONObject retryConfig = config();
        retryConfig.getJSONObject("backendAdapter").getJSONObject("operations")
            .getJSONObject("submit").put("outcomePolicy", outcomePolicy(true, false));
        assertTrue(CatalogPromotionValidator.isExecutableWithConfig(
            retryingAlternate, retryConfig));

        JSONObject maximumEntryId = catalogWithAlternate(false);
        alternateEntry(maximumEntryId).put("id", "e".repeat(256));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(maximumEntryId));

        JSONObject oversizedEntryId = catalogWithAlternate(false);
        alternateEntry(oversizedEntryId).put("id", "e".repeat(257));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(oversizedEntryId));
    }

    @Test
    public void dailyStatsPresentationMustBeExplicitBoundedAndVisible()
            throws Exception {
        JSONObject valid = catalog();
        valid.getJSONObject("settings").put("dailyStats",
            dailyStats("sample-ready"));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(valid));

        JSONObject implicitLegacyPicker = copy(valid);
        for (int index = 0;
                index < implicitLegacyPicker.getJSONArray("profiles").length(); index++) {
            implicitLegacyPicker.getJSONArray("profiles").getJSONObject(index)
                .remove("pickerVisible");
        }
        assertFalse(CatalogPromotionValidator.isStructurallyValid(implicitLegacyPicker));

        JSONObject unknownSetting = copy(valid);
        unknownSetting.getJSONObject("settings").getJSONObject("dailyStats")
            .put("sampleUnknown", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(unknownSetting));

        JSONObject wrongScope = copy(valid);
        wrongScope.getJSONObject("settings").getJSONObject("dailyStats")
            .put("scope", "sample-current-profile");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongScope));

        JSONObject badColor = copy(valid);
        badColor.getJSONObject("settings").getJSONObject("dailyStats")
            .getJSONArray("groups").getJSONObject(0).put("uiColor", "sample-red");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badColor));

        JSONObject duplicateWithinGroup = copy(valid);
        duplicateWithinGroup.getJSONObject("settings").getJSONObject("dailyStats")
            .getJSONArray("groups").getJSONObject(0).getJSONArray("resultKeys")
            .put("sample-ready");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(duplicateWithinGroup));

        JSONObject overlap = copy(valid);
        overlap.getJSONObject("settings").getJSONObject("dailyStats")
            .getJSONArray("groups").put(new JSONObject()
                .put("id", "sample-summary-two")
                .put("label", "Second sample")
                .put("uiColor", "#654321")
                .put("resultKeys", new JSONArray().put("sample-ready")));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(overlap));

        JSONObject unreachable = copy(valid);
        unreachable.getJSONObject("settings").put("dailyStats",
            dailyStats("sample-missing"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(unreachable));

        JSONObject hiddenOnly = catalogWithAlternate(false);
        hiddenOnly.getJSONArray("profiles").getJSONObject(2).getJSONObject("gradeMap")
            .put("sample-hidden-only", new JSONObject()
                .put("field", "sample_hidden_result")
                .put("value", "sample-hidden-value"));
        hiddenOnly.getJSONObject("settings").put("dailyStats",
            dailyStats("sample-hidden-only"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(hiddenOnly));
    }

    @Test
    public void dailyStatsV2UsesExactVisibleProfileResultPairsAndStrictLayerOverlap()
            throws Exception {
        JSONObject valid = catalog();
        valid.getJSONObject("settings").put("dailyStatsV2", dailyStatsV2());
        assertTrue(CatalogPromotionValidator.isStructurallyValid(valid));

        JSONObject nonIntegerVersion = copy(valid);
        nonIntegerVersion.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .put("version", 2.0d);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(nonIntegerVersion));

        JSONObject wrongPair = copy(valid);
        wrongPair.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("groups").getJSONObject(0).getJSONArray("selectors")
            .getJSONObject(0).put("resultKey", "sample-missing");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongPair));

        JSONObject groupOverlap = copy(valid);
        JSONObject duplicateGroup = new JSONObject(groupOverlap.getJSONObject("settings")
            .getJSONObject("dailyStatsV2").getJSONArray("groups").getJSONObject(0).toString())
            .put("id", "sample-card-two");
        groupOverlap.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("groups").put(duplicateGroup);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(groupOverlap));

        JSONObject flatAgainstGroup = copy(valid);
        JSONObject flat = v2Item("sample-flat", "Sample flat",
            "example-intake", "sample-ready");
        flatAgainstGroup.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("flatSummaries").put(flat);
        assertTrue(CatalogPromotionValidator.isStructurallyValid(flatAgainstGroup));
        flatAgainstGroup.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("flatSummaries")
            .put(new JSONObject(flat.toString()).put("id", "sample-flat-two"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(flatAgainstGroup));

        JSONObject badLegacyAssignment = copy(valid);
        badLegacyAssignment.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("groups").getJSONObject(0)
            .put("legacyResultKeys", new JSONArray().put("sample-review"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badLegacyAssignment));
    }

    @Test
    public void alternateEntryStatsUseExactEnabledVisibleSourceEntryPairs()
            throws Exception {
        JSONObject valid = catalogWithAlternate(false);
        valid.getJSONObject("settings").put("dailyStatsV2", dailyStatsV2());
        String sourceId = valid.getJSONArray("profiles").getJSONObject(0)
            .getString("id");
        String entryId = alternateEntry(valid).getString("id");
        valid.getJSONObject("settings").put("dailyStatsAlternateEntries",
            dailyStatsAlternateEntries("sample-card", sourceId, entryId));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(valid));

        JSONObject emptyCollections = copy(valid);
        emptyCollections.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .put("groups", new JSONArray())
            .put("flatSummaries", new JSONArray());
        assertTrue(CatalogPromotionValidator.isStructurallyValid(emptyCollections));

        JSONObject wrongVersion = copy(valid);
        wrongVersion.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries").put("version", 1.0d);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(wrongVersion));

        JSONObject unknownKey = copy(valid);
        unknownKey.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries").put("sampleUnknown", true);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(unknownKey));

        JSONObject missingEntry = copy(valid);
        missingEntry.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .getJSONArray("groups").getJSONObject(0)
            .getJSONArray("selectors").getJSONObject(0)
            .put("entryId", "sample-missing");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(missingEntry));

        JSONObject hiddenSource = copy(valid);
        String hiddenId = hiddenSource.getJSONArray("profiles").getJSONObject(2)
            .getString("id");
        hiddenSource.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .getJSONArray("groups").getJSONObject(0)
            .getJSONArray("selectors").getJSONObject(0)
            .put("profileId", hiddenId);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(hiddenSource));

        JSONObject groupDuplicate = copy(valid);
        JSONObject duplicateSelector = new JSONObject(groupDuplicate.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .getJSONArray("groups").getJSONObject(0)
            .getJSONArray("selectors").getJSONObject(0).toString());
        groupDuplicate.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .getJSONArray("groups").getJSONObject(0)
            .getJSONArray("selectors").put(duplicateSelector);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(groupDuplicate));

        JSONObject groupAndFlat = copy(valid);
        groupAndFlat.getJSONObject("settings").getJSONObject("dailyStatsV2")
            .getJSONArray("flatSummaries").put(v2Item(
                "sample-flat", "Sample flat", sourceId, "sample-ready"));
        groupAndFlat.getJSONObject("settings")
            .getJSONObject("dailyStatsAlternateEntries")
            .getJSONArray("flatSummaries").put(new JSONObject()
                .put("id", "sample-flat")
                .put("selectors", new JSONArray().put(new JSONObject()
                    .put("profileId", sourceId)
                    .put("entryId", entryId))));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(groupAndFlat));
    }

    @Test
    public void profileAndResultColorsMustUseSixDigitHex() throws Exception {
        JSONObject valid = catalog();
        firstProfile(valid).put("uiColor", "#123ABC");
        firstProfile(valid).getJSONObject("gradeMap").getJSONObject("sample-ready")
            .put("uiColor", "#abcdef")
            .put("operatorLabel", "Ready")
            .put("operatorLabelI18n", new JSONObject().put("es", "Listo"));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(valid));

        JSONObject badProfile = copy(valid);
        firstProfile(badProfile).put("uiColor", "#1234");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badProfile));

        JSONObject badResult = copy(valid);
        firstProfile(badResult).getJSONObject("gradeMap").getJSONObject("sample-ready")
            .put("uiColor", "red");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badResult));

        JSONObject badOperatorLabel = copy(valid);
        firstProfile(badOperatorLabel).getJSONObject("gradeMap")
            .getJSONObject("sample-ready").put("operatorLabel", " Ready ");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badOperatorLabel));

        JSONObject badOperatorLocale = copy(valid);
        firstProfile(badOperatorLocale).getJSONObject("gradeMap")
            .getJSONObject("sample-ready").put("operatorLabelI18n",
                new JSONObject().put("sample-locale", "Sample"));
        assertFalse(CatalogPromotionValidator.isStructurallyValid(badOperatorLocale));
    }

    @Test
    public void scannerAllowedLengthsPromoteWithCompatibleLegacyFallbackOnly()
            throws Exception {
        JSONObject valid = catalog();
        JSONObject profile = firstProfile(valid);
        profile.put("expectedSnLength", 17);
        JSONObject scanner = profile.getJSONArray("snPlugins")
            .getJSONObject(0).getJSONObject("scanner");
        scanner.put("allowedLengths", new JSONArray().put(16).put(17));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(valid));

        JSONObject contradictoryFallback = copy(valid);
        contradictoryFallback.getJSONArray("profiles").getJSONObject(0)
            .put("expectedSnLength", 15);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(
            contradictoryFallback));

        JSONObject explicitOverride = copy(contradictoryFallback);
        explicitOverride.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("snPlugins").getJSONObject(0).getJSONObject("scanner")
            .put("expectedLength", 17);
        assertTrue(CatalogPromotionValidator.isStructurallyValid(explicitOverride));

        JSONObject incompatibleBounds = copy(valid);
        incompatibleBounds.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("snPlugins").getJSONObject(0).getJSONObject("scanner")
            .put("minLength", 17);
        assertFalse(CatalogPromotionValidator.isStructurallyValid(incompatibleBounds));
    }

    @Test
    public void alternateEntryAllowedLengthScopeInheritsOrOverridesPrimaryScanner()
            throws Exception {
        JSONObject inherited = catalogWithAlternate(false);
        JSONObject source = inherited.getJSONArray("profiles").getJSONObject(0);
        source.put("expectedSnLength", 17);
        source.getJSONArray("snPlugins").getJSONObject(0).getJSONObject("scanner")
            .put("allowedLengths", new JSONArray().put(16).put(17));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(inherited));

        JSONObject overridden = copy(inherited);
        overridden.getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("workflow").getJSONObject("alternateEntries")
            .getJSONArray("entries").getJSONObject(0).getJSONObject("scanner")
            .put("applyAllowedLengthsTo", new JSONArray()
                .put("ocr").put("barcode").put("entered"));
        assertTrue(CatalogPromotionValidator.isStructurallyValid(overridden));

        JSONObject missingAllowedPolicy = copy(overridden);
        missingAllowedPolicy.getJSONArray("profiles").getJSONObject(0)
            .getJSONArray("snPlugins").getJSONObject(0).getJSONObject("scanner")
            .remove("allowedLengths");
        assertFalse(CatalogPromotionValidator.isStructurallyValid(
            missingAllowedPolicy));
    }

    private static JSONObject dailyStats(String resultKey) throws Exception {
        return new JSONObject()
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(new JSONObject()
                .put("id", "sample-summary")
                .put("label", "Sample summary")
                .put("labelI18n", new JSONObject().put("en", "Sample summary"))
                .put("uiColor", "#123456")
                .put("resultKeys", new JSONArray().put(resultKey))));
    }

    private static JSONObject dailyStatsV2() throws Exception {
        return new JSONObject()
            .put("version", DailyStatsRules.DAILY_STATS_V2_VERSION)
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(v2Item(
                "sample-card", "Sample card", "example-intake", "sample-ready")
                .put("legacyResultKeys", new JSONArray().put("sample-ready"))))
            .put("flatSummaries", new JSONArray());
    }

    private static JSONObject dailyStatsAlternateEntries(
            String itemId, String profileId, String entryId) throws Exception {
        return new JSONObject()
            .put("version", DailyStatsRules.DAILY_STATS_ALTERNATE_ENTRIES_VERSION)
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(new JSONObject()
                .put("id", itemId)
                .put("selectors", new JSONArray().put(new JSONObject()
                    .put("profileId", profileId)
                    .put("entryId", entryId)))))
            .put("flatSummaries", new JSONArray());
    }

    private static JSONObject v2Item(String id, String label, String profileId,
                                     String resultKey) throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("label", label)
            .put("uiColor", "#123456")
            .put("selectors", new JSONArray().put(new JSONObject()
                .put("profileId", profileId)
                .put("resultKey", resultKey)));
    }

    private static JSONObject catalogWithDynamicPreviousStep() throws Exception {
        JSONObject root = catalog();
        JSONObject profile = root.getJSONArray("profiles").getJSONObject(0);
        String photoField = profile.getJSONArray("photoSlots")
            .getJSONObject(0).getString("field");
        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("templates", new JSONArray().put(dynamicRecipe(
                7001, 1, "sample-template-detail-v1",
                new JSONObject().put("sample-evidence", photoField))));
        return root;
    }

    private static JSONObject dynamicRecipe(int templateId, int expectedStep,
                                            String resolverId, JSONObject sources)
            throws Exception {
        return new JSONObject()
            .put("templateId", templateId)
            .put("mode", "template_detail")
            .put("resolverId", resolverId)
            .put("expectedStep", expectedStep)
            .put("sources", sources)
            .put("delayAfterMs", 0);
    }

    private static JSONObject staticRecipe(String source) throws Exception {
        return new JSONObject()
            .put("templateId", 7001)
            .put("warehouseId", 1)
            .put("sku", "EXAMPLE_PREVIOUS")
            .put("fixedData", new JSONObject())
            .put("serialField", "example_previous_serial")
            .put("photoBindings", new JSONArray().put(new JSONObject()
                .put("targetField", "example_previous_photo")
                .put("source", source)))
            .put("delayAfterMs", 0);
    }

    private static JSONObject catalogWithAlternate(boolean dynamic) throws Exception {
        JSONObject root = catalog();
        JSONArray profiles = root.getJSONArray("profiles");
        JSONObject source = profiles.getJSONObject(0);
        JSONObject target = new JSONObject(profiles.getJSONObject(1).toString())
            .put("id", "example-hidden-target")
            .put("pickerVisible", false);
        profiles.put(target);

        String photoField = target.getJSONArray("photoSlots")
            .getJSONObject(0).getString("field");
        JSONObject entry = new JSONObject()
            .put("id", "example-alternate")
            .put("title", "Example alternate")
            .put("targetProfileId", "example-hidden-target")
            .put("identifierRole", "primary")
            .put("resultKey", "sample-ready")
            .put("photoTargetFields", new JSONArray().put(photoField))
            .put("joinWith", ",")
            .put("minPhotos", 1)
            .put("maxPhotos", 1)
            .put("uploadNameTemplate", "{identifier}-alternate-{index}.jpg")
            .put("scanner", new JSONObject().put("applyExpectedLengthTo",
                new JSONArray().put("ocr").put("barcode")))
            .put("dataOverrides", new JSONObject())
            .put("dynamicOverrideFields", new JSONArray())
            .put("dynamicOverrideProviders", new JSONArray())
            .put("toggles", new JSONArray())
            .put("flags", new JSONObject()
                .put("duplicateCheck", false)
                .put("previousSteps", false)
                .put("printing", false));
        if (dynamic) {
            String outputField = "example-live-choice";
            target.put("conditionalFields", new JSONArray().put(
                new JSONObject().put("field", outputField)));
            entry.put("toggles", new JSONArray().put(new JSONObject()
                .put("key", "example-live-toggle")
                .put("label", "Example live toggle")
                .put("default", false)
                .put("retainUntilExit", true)
                .put("dataOverrides", new JSONObject())));
            entry.put("dynamicOverrideFields", new JSONArray().put(outputField));
            entry.put("dynamicOverrideProviders", new JSONArray().put(new JSONObject()
                .put("id", "example-live-provider")
                .put("triggerToggleKey", "example-live-toggle")
                .put("templateId", target.getJSONObject("template").getInt("id"))
                .put("expectedStep", target.getJSONObject("template").getInt("step"))
                .put("resolverId", "sample-alternate-live-option-v1")
                .put("outputField", outputField)));
        }
        source.getJSONObject("workflow").put("alternateEntries",
            new JSONObject().put("enabled", true)
                .put("entries", new JSONArray().put(entry)));
        return root;
    }

    private static JSONObject alternateEntry(JSONObject catalog) throws Exception {
        return catalog.getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("workflow").getJSONObject("alternateEntries")
            .getJSONArray("entries").getJSONObject(0);
    }

    private static JSONObject catalog() throws Exception {
        return readJson("app/assets/form-profiles.seed.json",
            "assets/form-profiles.seed.json");
    }

    private static JSONObject catalogWithMaterials() throws Exception {
        JSONObject root = catalog();
        firstProfile(root).put("materialGroups", new JSONArray().put(
            materialGroup("example_materials_one", "EXAMPLE-ITEM-01")));
        return root;
    }

    private static JSONObject materialGroup(String field, String code) throws Exception {
        return new JSONObject()
            .put("field", field)
            .put("materials", new JSONArray().put(new JSONObject()
                .put("code", code)
                .put("name", "Example item")
                .put("defaultQty", 1)));
    }

    private static JSONObject outcomePolicy(boolean retryable, boolean missing)
            throws Exception {
        JSONArray retryRules = new JSONArray();
        JSONArray missingRules = new JSONArray();
        if (retryable) {
            retryRules.put(new JSONObject()
                .put("codeValues", new JSONArray().put("SAMPLE-NOT-WRITTEN"))
                .put("messagePatterns", new JSONArray()));
        }
        if (missing) {
            missingRules.put(new JSONObject()
                .put("codeValues", new JSONArray().put("SAMPLE-MISSING"))
                .put("messagePatterns", new JSONArray()));
        }
        return new JSONObject()
            .put("version", 1)
            .put("evidenceSha256", "a".repeat(64))
            .put("retryableNotWrittenRules", retryRules)
            .put("missingMaterialNotWrittenRules", missingRules);
    }

    private static JSONObject recipeOutcomePolicy(boolean retryable, boolean acknowledged)
            throws Exception {
        JSONArray retryRules = new JSONArray();
        JSONArray acknowledgedRules = new JSONArray();
        if (retryable) {
            retryRules.put(new JSONObject()
                .put("codeValues", new JSONArray().put("SAMPLE-RECIPE-NOT-WRITTEN"))
                .put("messagePatterns", new JSONArray()));
        }
        if (acknowledged) {
            acknowledgedRules.put(new JSONObject()
                .put("codeValues", new JSONArray().put("SAMPLE-RECIPE-ALREADY"))
                .put("messagePatterns", new JSONArray()));
        }
        return new JSONObject()
            .put("version", 1)
            .put("evidenceSha256", "b".repeat(64))
            .put("retryableNotWrittenRules", retryRules)
            .put("alreadyExistsAcknowledgedRules", acknowledgedRules);
    }

    private static JSONObject firstMaterial(JSONObject root) throws Exception {
        return firstProfile(root).getJSONArray("materialGroups").getJSONObject(0)
            .getJSONArray("materials").getJSONObject(0);
    }

    private static JSONObject catalogWithChoice() throws Exception {
        JSONObject root = catalog();
        firstProfile(root).put("choiceFields", new JSONArray().put(new JSONObject()
            .put("field", "example_choice")
            .put("kind", "single")
            .put("options", new JSONArray()
                .put(new JSONObject().put("value", "example-one")
                    .put("label", "Example one"))
                .put(new JSONObject().put("value", "example-two")
                    .put("label", "Example two")))
            .put("value", "example-one")
            .put("required", true)
            .put("visible", true)
            .put("reviewRequired", false)));
        return root;
    }

    private static JSONObject firstChoice(JSONObject root) throws Exception {
        return firstProfile(root).getJSONArray("choiceFields").getJSONObject(0);
    }

    private static JSONObject firstProfile(JSONObject root) throws Exception {
        return root.getJSONArray("profiles").getJSONObject(0);
    }

    private static JSONObject copy(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }

    private static JSONObject config() throws Exception {
        return new JSONObject().put("backendAdapter",
            readJson("panel/backend-adapter.example.json",
                "../panel/backend-adapter.example.json"));
    }

    private static JSONObject readJson(String... candidates) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (String candidate : candidates) {
            Path path = cwd.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("fixture not found from " + cwd);
    }
}
