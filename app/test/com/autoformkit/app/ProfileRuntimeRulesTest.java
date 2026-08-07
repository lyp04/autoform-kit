package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TimeZone;

public class ProfileRuntimeRulesTest {
    @Test
    public void missingWorkflowEnablesNothing() {
        ProfileWorkflow workflow = ProfileWorkflow.from(new JSONObject());
        assertFalse(workflow.declared);
        assertFalse(workflow.operationalPoliciesExplicit);
        assertFalse(workflow.previousStepsEnabled);
        assertTrue(workflow.directCreateResultKeys.isEmpty());
        assertFalse(workflow.shouldDirectCreatePreviousSteps("sample-review"));
        assertFalse(workflow.identifierCorrectionEnabled);
        assertTrue(workflow.identifierSubstitutions.isEmpty());
        assertTrue(workflow.identifierCorrectionResultKeys.isEmpty());
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.identifierCorrectionApplyAction);
        assertEquals(ProfileWorkflow.IDENTIFIER_CASE_PRESERVE, workflow.identifierCasePolicy);
        assertEquals(1, workflow.scanPrecheckMaxMissingAttempts);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.scanPrecheckBeforeLimitAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.scanPrecheckAtLimitAction);
        assertEquals(1, workflow.previousStepVerifyAttempts);
        assertEquals(0L, workflow.previousStepVerifyDelayMs);
        assertEquals(1, workflow.previousStepRecipeMaxAttempts);
        assertEquals(0L, workflow.previousStepRecipeRetryDelayMs);
        assertFalse(workflow.includeOptionalPhotoSlots);
        assertFalse(workflow.duplicateCheckEnabled);
        assertEquals(ProfileWorkflow.DUPLICATE_AGE_DAYS, workflow.duplicateAgeUnit);
        assertEquals(0, workflow.duplicateAgeValue);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateUnknownDateAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateRecentAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateEligibleAction);
        assertFalse(workflow.refreshMaterialsBeforeSubmit);
        assertFalse(workflow.missingRecoveryEnabled);
        assertFalse(workflow.missingRecoveryLocalNotice);
        assertFalse(workflow.submissionSummaryNotificationEnabled);
        assertEquals("", workflow.notificationProfileLabel);
        assertFalse(workflow.printingEnabled);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.printingPreflightAction);
        assertEquals(1, workflow.printingConfirmationPolls);
        assertEquals(250L, workflow.printingConfirmationPollIntervalMs);
        assertEquals(0, workflow.printingMaxAutoReprints);
        assertEquals(0L, workflow.printingFinalRecheckDelayMs);
        assertEquals("stop", workflow.printingOnUnconfirmed);
        assertEquals(ProfileWorkflow.PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS,
            workflow.printingBatchEndRecheckMode);
        assertEquals(ProfileWorkflow.PRINT_UNKNOWN_PRESENTATION_DISTINCT,
            workflow.printingUnknownStatusPresentation);
        assertTrue(workflow.usesDeferredMissingTwoPassRecheck());
        assertFalse(workflow.presentsUnknownPrintStatusAsOngoing());
        assertFalse(workflow.printingManualReprintEnabled);
        assertTrue(workflow.printingManualReprintStatuses.isEmpty());
        assertTrue(workflow.printingManualReprintRequiresConfirmation);
        assertFalse(workflow.allowsManualReprint(ProfileWorkflow.PRINT_STATUS_FAILED));
        assertEquals(1, workflow.submissionMaxAttempts);
        assertEquals(0L, workflow.submissionRetryDelayMs);
        assertEquals(0L, workflow.submissionInterUnitDelayMs);
        assertEquals(1, workflow.roundLedgerRetentionDays);
        assertEquals(1, workflow.submissionMaxConsecutiveFailures);
        assertEquals(ProfileWorkflow.STRUCTURED_NON_SUCCESS_LOCK,
            workflow.submissionStructuredNonSuccessAction);
        assertEquals(0, workflow.submissionNetworkRetryMaxAttempts);
        assertEquals(3000L, workflow.submissionNetworkRetryBaseDelayMs);
        assertEquals(30000L, workflow.submissionNetworkRetryMaxDelayMs);
    }

    @Test
    public void identifierCorrectionAndPrecheckDecisionsAreProfileOwned() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("enabled", true)
                .put("scanPrecheck", true)
                .put("identifierCorrection", new JSONObject()
                    .put("enabled", true)
                    .put("applyAction", "confirm")
                    .put("resultKeys", new JSONArray().put("sample-ready"))
                    .put("substitutions", new JSONArray()
                        .put(new JSONObject().put("from", "O").put("to", "0"))
                        .put(new JSONObject().put("from", "😀").put("to", "X"))))
                .put("identifierCasePolicy", "match_existing")
                .put("scanPrecheckPolicy", new JSONObject()
                    .put("maxMissingAttempts", 4)
                    .put("beforeLimitAction", "remove")
                    .put("atLimitAction", "require_artifact"))
                .put("verifyAttempts", 3)
                .put("verifyDelayMs", 750)
                .put("recipeMaxAttempts", 3)
                .put("recipeRetryDelayMs", 4000)));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertTrue(workflow.identifierCorrectionEnabled);
        assertEquals(ProfileWorkflow.ACTION_CONFIRM, workflow.identifierCorrectionApplyAction);
        assertEquals(2, workflow.identifierSubstitutions.size());
        assertEquals("O", workflow.identifierSubstitutions.get(0).from);
        assertEquals("0", workflow.identifierSubstitutions.get(0).to);
        assertEquals("😀", workflow.identifierSubstitutions.get(1).from);
        assertEquals(ProfileWorkflow.IDENTIFIER_CASE_MATCH_EXISTING, workflow.identifierCasePolicy);
        assertEquals(4, workflow.scanPrecheckMaxMissingAttempts);
        assertEquals(ProfileWorkflow.ACTION_REMOVE, workflow.scanPrecheckBeforeLimitAction);
        assertEquals(ProfileWorkflow.ACTION_REQUIRE_ARTIFACT, workflow.scanPrecheckAtLimitAction);
        assertEquals(3, workflow.previousStepVerifyAttempts);
        assertEquals(750L, workflow.previousStepVerifyDelayMs);
        assertEquals(3, workflow.previousStepRecipeMaxAttempts);
        assertEquals(4000L, workflow.previousStepRecipeRetryDelayMs);
        assertTrue(workflow.shouldAttemptIdentifierCorrection("sample-ready"));
        assertFalse(workflow.shouldAttemptIdentifierCorrection("sample-hold"));
        assertTrue(workflow.shouldMatchExistingIdentifierCase());
        assertEquals("A0X", workflow.canonicalizeIdentifier("AO😀"));
        assertEquals(Arrays.asList("A0😀", "AOX", "A0X"),
            workflow.identifierCorrectionCandidates("AO😀", 8));
        assertTrue(workflow.identifierCorrectionCandidates("AI1", 8).isEmpty());
        assertEquals(Collections.singletonList("A0😀"),
            workflow.identifierCorrectionCandidates("AO😀", 1));

        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("scanPrecheck", false);
        assertTrue(ProfileWorkflow.from(profile)
            .shouldAttemptIdentifierCorrection("sample-ready"));
    }

    @Test
    public void malformedIdentifierPoliciesFallBackSafelyAndDiscardInvalidMappings() throws Exception {
        JSONArray substitutions = new JSONArray()
            .put(new JSONObject().put("from", "A").put("to", "1"))
            .put(new JSONObject().put("from", "A").put("to", "2"))
            .put(new JSONObject().put("from", "AB").put("to", "3"))
            .put(new JSONObject().put("from", " ").put("to", "4"));
        for (int i = 0; i < 10; i++) {
            substitutions.put(new JSONObject()
                .put("from", String.valueOf((char) ('B' + i)))
                .put("to", String.valueOf(i % 10)));
        }
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("identifierCorrection", new JSONObject()
                    .put("enabled", "true")
                    .put("applyAction", "guess")
                    .put("substitutions", substitutions))
                .put("identifierCasePolicy", "upper")
                .put("scanPrecheckPolicy", new JSONObject()
                    .put("maxMissingAttempts", 99)
                    .put("beforeLimitAction", "retry")
                    .put("atLimitAction", "continue"))
                .put("verifyAttempts", 0)
                .put("verifyDelayMs", 99999)
                .put("recipeMaxAttempts", 0)
                .put("recipeRetryDelayMs", 99999)));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertFalse(workflow.identifierCorrectionEnabled);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.identifierCorrectionApplyAction);
        assertEquals(8, workflow.identifierSubstitutions.size());
        assertEquals(ProfileWorkflow.IDENTIFIER_CASE_PRESERVE, workflow.identifierCasePolicy);
        assertEquals(10, workflow.scanPrecheckMaxMissingAttempts);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.scanPrecheckBeforeLimitAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.scanPrecheckAtLimitAction);
        assertEquals(1, workflow.previousStepVerifyAttempts);
        assertEquals(30000L, workflow.previousStepVerifyDelayMs);
        assertEquals(1, workflow.previousStepRecipeMaxAttempts);
        assertEquals(60000L, workflow.previousStepRecipeRetryDelayMs);
    }

    @Test
    public void workflowAndScannerHintsAreProfileOwned() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("enabled", true)
                .put("scanPrecheck", true)
                .put("scanPrecheckExcludedResultKeys", new JSONArray().put("sample-hold"))
                .put("triggerResultKeys", new JSONArray().put("sample-review"))
                .put("directCreateResultKeys", new JSONArray().put("sample-review"))
                .put("artifacts", new JSONArray().put(new JSONObject()
                    .put("key", "example-evidence")
                    .put("title", "Example evidence")
                    .put("required", true)
                    .put("uploadNameTemplate", "{identifier}-sample-evidence.jpg")))
                .put("templates", new JSONArray().put(new JSONObject()
                    .put("templateId", 42)
                    .put("warehouseId", 7)
                    .put("sku", "EXAMPLE-STEP")
                    .put("serialField", "example_serial")
                    .put("fixedData", new JSONObject().put("example_state", "ready"))
                    .put("photoBindings", new JSONArray().put(new JSONObject()
                        .put("targetField", "example_photo")
                        .put("source", "example-evidence")))
                    .put("delayAfterMs", 25))))
            .put("duplicateCheck", new JSONObject()
                .put("enabled", true)
                .put("minAgeDaysToResubmit", 45))
            .put("materials", new JSONObject().put("refreshBeforeSubmit", true))
            .put("notifications", new JSONObject()
                .put("submissionSummary", true)
                .put("profileLabel", "  Example notification line  ")));
        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertTrue(workflow.shouldScanPrecheck("sample-ready"));
        assertFalse(workflow.shouldScanPrecheck("sample-hold"));
        assertTrue(workflow.shouldAutoCreatePreviousSteps("sample-review"));
        assertTrue(workflow.shouldDirectCreatePreviousSteps("sample-review"));
        assertFalse(workflow.shouldDirectCreatePreviousSteps("sample-ready"));
        assertTrue(workflow.submissionSummaryNotificationEnabled);
        assertEquals("Example notification line", workflow.notificationProfileLabel);
        assertFalse(workflow.shouldAutoCreatePreviousSteps("SAMPLE-REVIEW"));
        assertFalse(workflow.shouldDirectCreatePreviousSteps("SAMPLE-REVIEW"));
        assertEquals("example-evidence", workflow.workflowArtifacts.get(0).key);
        assertEquals("UNIT_01-sample-evidence.jpg",
            workflow.workflowArtifactUploadName("example-evidence", "UNIT/01", 1));
        assertEquals(42, workflow.previousStepRecipes.get(0).templateId);
        assertEquals(7, workflow.previousStepRecipes.get(0).warehouseId);
        assertEquals("EXAMPLE-STEP", workflow.previousStepRecipes.get(0).sku);
        assertEquals("example_photo",
            workflow.previousStepRecipes.get(0).photoBindings.get(0).targetField);
        assertEquals(ProfileWorkflow.DUPLICATE_AGE_DAYS, workflow.duplicateAgeUnit);
        assertEquals(45, workflow.duplicateAgeValue);
        assertTrue(workflow.refreshMaterialsBeforeSubmit);

        List<String> prefixes = SnScanRules.normalizePrefixes(
            new String[]{" demo-", "DEMO-", "bad prefix"});
        assertEquals(Collections.singletonList("DEMO-"), prefixes);
        assertTrue(SnScanRules.hasPreferredPrefix("DEMO-001", prefixes));
    }

    @Test
    public void invalidNotificationProfileLabelFailsClosedWithoutGuessing() throws Exception {
        JSONObject notifications = new JSONObject()
            .put("submissionSummary", true)
            .put("profileLabel", "x".repeat(161));
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("notifications", notifications));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertTrue(workflow.submissionSummaryNotificationEnabled);
        assertEquals("", workflow.notificationProfileLabel);

        notifications.remove("profileLabel");
        assertEquals("", ProfileWorkflow.from(profile).notificationProfileLabel);

        notifications.put("profileLabel", 42);
        assertEquals("", ProfileWorkflow.from(profile).notificationProfileLabel);
    }

    @Test
    public void recipeWithoutExplicitWarehouseOrSkuIsRejected() throws Exception {
        JSONObject previous = new JSONObject()
            .put("enabled", true)
            .put("triggerResultKeys", new JSONArray().put("sample-review"))
            .put("templates", new JSONArray()
                .put(new JSONObject()
                    .put("templateId", 42)
                    .put("sku", "EXAMPLE-STEP")
                    .put("serialField", "example_serial")
                    .put("fixedData", new JSONObject()))
                .put(new JSONObject()
                    .put("templateId", 43)
                    .put("warehouseId", 7)
                    .put("serialField", "example_serial")
                    .put("fixedData", new JSONObject())));
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", previous));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertTrue(workflow.previousStepRecipes.isEmpty());
        assertFalse(workflow.shouldAutoCreatePreviousSteps("sample-review"));
    }

    @Test
    public void dynamicRecipesAreParsedSeparatelyWithoutChangingStaticExecution() throws Exception {
        JSONObject previous = new JSONObject()
            .put("enabled", true)
            .put("triggerResultKeys", new JSONArray().put("sample-review"))
            .put("templates", new JSONArray()
                .put(new JSONObject()
                    .put("templateId", 42)
                    .put("warehouseId", 7)
                    .put("sku", "SAMPLE-STATIC")
                    .put("serialField", "sample_serial")
                    .put("fixedData", new JSONObject())
                    .put("photoBindings", new JSONArray())
                    .put("delayAfterMs", 0))
                .put(new JSONObject()
                    .put("templateId", 7001)
                    .put("mode", "template_detail")
                    .put("resolverId", "sample-template-detail-v1")
                    .put("expectedStep", 7)
                    .put("sources", new JSONObject()
                        .put("sample-evidence", "example-photo"))
                    .put("delayAfterMs", 25)));
        ProfileWorkflow workflow = ProfileWorkflow.from(new JSONObject()
            .put("workflow", new JSONObject().put("previousSteps", previous)));

        assertEquals(1, workflow.previousStepRecipes.size());
        assertEquals(42, workflow.previousStepRecipes.get(0).templateId);
        assertEquals(0, workflow.previousStepRecipes.get(0).sourceIndex);
        assertEquals(1, workflow.dynamicPreviousStepRecipes.size());
        assertTrue(workflow.dynamicPreviousStepErrors.toString(),
            workflow.dynamicPreviousStepErrors.isEmpty());
        ProfileWorkflow.DynamicPreviousStepRecipe dynamic =
            workflow.dynamicPreviousStepRecipes.get(0);
        assertEquals(7001, ((Number) dynamic.templateId).intValue());
        assertEquals(7, ((Number) dynamic.expectedStep).intValue());
        assertEquals("sample-template-detail-v1", dynamic.resolverId);
        assertEquals("example-photo", dynamic.sources.get("sample-evidence"));
        assertEquals(25L, dynamic.delayAfterMs);
        assertEquals(1, dynamic.sourceIndex);
        assertTrue(workflow.shouldAutoCreatePreviousSteps("sample-review"));
        assertTrue(workflow.shouldAutoCreateDynamicPreviousSteps("sample-review"));
    }

    @Test
    public void malformedDynamicRecipeFailsClosedAndNeverFallsBackToStatic() throws Exception {
        JSONObject malformed = new JSONObject()
            .put("templateId", 7001)
            .put("mode", "template_detail")
            .put("resolverId", "missing resolver")
            .put("expectedStep", 1.5)
            .put("sources", new JSONObject().put("bad alias", "example-photo"))
            .put("delayAfterMs", 120001)
            .put("fixedData", new JSONObject());
        JSONObject previous = new JSONObject()
            .put("enabled", true)
            .put("triggerResultKeys", new JSONArray().put("sample-review"))
            .put("templates", new JSONArray().put(malformed));

        ProfileWorkflow workflow = ProfileWorkflow.from(new JSONObject()
            .put("workflow", new JSONObject().put("previousSteps", previous)));

        assertTrue(workflow.previousStepRecipes.isEmpty());
        assertTrue(workflow.dynamicPreviousStepRecipes.isEmpty());
        assertFalse(workflow.dynamicPreviousStepErrors.isEmpty());
        assertFalse(workflow.shouldAutoCreatePreviousSteps("sample-review"));
        assertFalse(workflow.shouldAutoCreateDynamicPreviousSteps("sample-review"));
    }

    @Test
    public void legacyWorkflowPhotoRequiresExactPanelMapping() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject().put("artifacts", new JSONArray()
                .put(new JSONObject().put("key", "optional-example")
                    .put("title", "Optional example").put("required", false)
                    .put("uploadNameTemplate", "{identifier}-sample-optional.jpg"))
                .put(new JSONObject().put("key", "required-example")
                    .put("title", "Required example").put("required", true)
                    .put("uploadNameTemplate", "{identifier}-sample-required.jpg")))));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertNull(workflow.legacyArtifactTarget());

        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("legacyDraftArtifactKey", "required-example");
        assertEquals("required-example", ProfileWorkflow.from(profile)
            .legacyArtifactTarget().key);

        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("artifacts", new JSONArray().put(new JSONObject()
                .put("key", "only-example")
                .put("title", "Only example")
                .put("required", true)
                .put("uploadNameTemplate", "{identifier}-sample-only.jpg")));
        assertNull(ProfileWorkflow.from(profile).legacyArtifactTarget());
        profile.getJSONObject("workflow").getJSONObject("previousSteps")
            .put("legacyDraftArtifactKey", "only-example");
        assertEquals("only-example", ProfileWorkflow.from(profile).legacyArtifactTarget().key);
    }

    @Test
    public void legacyPreviousStepFieldsDoNotTriggerRecipes() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("enabled", true)
                .put("autoCreatePreviousSteps", true)
                .put("previousStepTemplates", new JSONArray().put(7))
                .put("triggerResultKeys", new JSONArray()))
            .put("duplicateCheck", new JSONObject().put("enabled", false))
            .put("materials", new JSONObject().put("refreshBeforeSubmit", false)));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);
        assertTrue(workflow.previousStepRecipes.isEmpty());
        assertFalse(workflow.shouldAutoCreatePreviousSteps("sample-review"));
    }

    @Test
    public void materialMatcherOnlyReturnsProfilePublishedCodes() {
        List<String> found = MaterialCodeRules.findKnownCodes(
            "missing PART-DEMO and UNCONFIGURED",
            Arrays.asList("PART-DEMO", "PART-OTHER"),
            new LinkedHashSet<>());
        assertEquals(Collections.singletonList("PART-DEMO"), found);
    }

    @Test
    public void configuredMaterialRecognizerFailsClosedWithoutARegexMatch() {
        List<String> known = Collections.singletonList("PART-DEMO");
        assertTrue(MaterialCodeRules.findKnownCodes(
            "literal PART-DEMO", known, Collections.emptySet(), "CODE-[0-9]+").isEmpty());
        assertTrue(MaterialCodeRules.findKnownCodes(
            "literal PART-DEMO", known, Collections.emptySet(), "[").isEmpty());
    }

    @Test
    public void panelSkipListExcludesCodesFromLegacyCompatibleMissingRecovery() {
        List<String> known = Arrays.asList("sample-one", "sample-two");
        List<String> missing = MaterialCodeRules.findKnownCodesForAutomaticRecovery(
            "missing sample-one and sample-two", known,
            Collections.singleton("sample-one"), "sample-[a-z]+");

        assertEquals(Collections.singletonList("sample-two"), missing);
    }

    @Test
    public void explicitOperationalPoliciesAreProfileOwned() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("compatibilityReviewed", true)
            .put("previousSteps", new JSONObject()
                .put("recipeMaxAttempts", 3)
                .put("recipeRetryDelayMs", 4000))
            .put("duplicateCheck", new JSONObject()
                .put("enabled", true)
                .put("agePolicy", new JSONObject()
                    .put("unit", "calendar_months")
                    .put("value", 1))
                .put("unknownDateAction", "skip_as_submitted")
                .put("recentAction", "skip_as_submitted")
                .put("eligibleAction", "confirm"))
            .put("printing", new JSONObject()
                .put("enabled", true)
                .put("preflightAction", "continue")
                .put("confirmationPolls", 6)
                .put("confirmationPollIntervalMs", 2500)
                .put("maxAutoReprints", 3)
                .put("finalRecheckDelayMs", 15000)
                .put("onUnconfirmed", "continue")
                .put("batchEndRecheckMode", "inline_only")
                .put("unknownStatusPresentation", "as_ongoing")
                .put("manualReprintEnabled", true)
                .put("manualReprintStatuses", new JSONArray()
                    .put("failed")
                    .put("ongoing")
                    .put("unknown")
                    .put("printed"))
                .put("manualReprintRequiresConfirmation", false))
            .put("materials", new JSONObject()
                .put("missingRecovery", new JSONObject()
                    .put("enabled", true)
                    .put("localNotice", true)))
            .put("submission", new JSONObject()
                .put("maxAttempts", 4)
                .put("retryDelayMs", 4000)
                .put("interUnitDelayMs", 1250)
                .put("roundLedgerRetentionDays", 9)
                .put("maxConsecutiveFailures", 3)
                .put("structuredNonSuccessAction", "reject_as_not_written")
                .put("networkRetry", new JSONObject()
                    .put("maxAttempts", 8)
                    .put("baseDelayMs", 3000)
                    .put("maxDelayMs", 30000))));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertEquals(ProfileWorkflow.DUPLICATE_AGE_CALENDAR_MONTHS, workflow.duplicateAgeUnit);
        assertEquals(1, workflow.duplicateAgeValue);
        assertEquals(ProfileWorkflow.ACTION_SKIP_AS_SUBMITTED,
            workflow.duplicateUnknownDateAction);
        assertEquals(ProfileWorkflow.ACTION_SKIP_AS_SUBMITTED, workflow.duplicateRecentAction);
        assertEquals(ProfileWorkflow.ACTION_CONFIRM, workflow.duplicateEligibleAction);
        assertEquals(3, workflow.previousStepRecipeMaxAttempts);
        assertEquals(4000L, workflow.previousStepRecipeRetryDelayMs);
        assertTrue(workflow.printingEnabled);
        assertEquals(ProfileWorkflow.ACTION_CONTINUE, workflow.printingPreflightAction);
        assertEquals(6, workflow.printingConfirmationPolls);
        assertEquals(2500L, workflow.printingConfirmationPollIntervalMs);
        assertEquals(3, workflow.printingMaxAutoReprints);
        assertEquals(15000L, workflow.printingFinalRecheckDelayMs);
        assertEquals("continue", workflow.printingOnUnconfirmed);
        assertEquals(ProfileWorkflow.PRINT_BATCH_END_INLINE_ONLY,
            workflow.printingBatchEndRecheckMode);
        assertEquals(ProfileWorkflow.PRINT_UNKNOWN_PRESENTATION_AS_ONGOING,
            workflow.printingUnknownStatusPresentation);
        assertFalse(workflow.usesDeferredMissingTwoPassRecheck());
        assertTrue(workflow.presentsUnknownPrintStatusAsOngoing());
        assertTrue(workflow.printingManualReprintEnabled);
        assertEquals(new LinkedHashSet<>(Arrays.asList("failed", "ongoing", "unknown")),
            workflow.printingManualReprintStatuses);
        assertFalse(workflow.printingManualReprintRequiresConfirmation);
        assertTrue(workflow.allowsManualReprint(ProfileWorkflow.PRINT_STATUS_FAILED));
        assertTrue(workflow.allowsManualReprint(ProfileWorkflow.PRINT_STATUS_ONGOING));
        assertTrue(workflow.allowsManualReprint(ProfileWorkflow.PRINT_STATUS_UNKNOWN));
        assertFalse(workflow.allowsManualReprint("printed"));
        assertFalse(workflow.allowsManualReprint("FAILED"));
        assertFalse(workflow.allowsManualReprint(null));
        assertTrue(workflow.missingRecoveryEnabled);
        assertTrue(workflow.missingRecoveryLocalNotice);
        assertEquals(4, workflow.submissionMaxAttempts);
        assertEquals(4000L, workflow.submissionRetryDelayMs);
        assertEquals(1250L, workflow.submissionInterUnitDelayMs);
        assertEquals(9, workflow.roundLedgerRetentionDays);
        assertEquals(3, workflow.submissionMaxConsecutiveFailures);
        assertEquals(ProfileWorkflow.STRUCTURED_NON_SUCCESS_REJECT_AS_NOT_WRITTEN,
            workflow.submissionStructuredNonSuccessAction);
        assertEquals(8, workflow.submissionNetworkRetryMaxAttempts);
        assertEquals(3000L, workflow.submissionNetworkRetryBaseDelayMs);
        assertEquals(30000L, workflow.submissionNetworkRetryMaxDelayMs);
    }

    @Test
    public void calendarMonthDuplicateThresholdMatchesLegacyInclusiveBoundary() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("duplicateCheck", new JSONObject()
                .put("agePolicy", new JSONObject()
                    .put("unit", "calendar_months")
                    .put("value", 1))));
        ProfileWorkflow workflow = ProfileWorkflow.from(profile);
        Calendar now = Calendar.getInstance();
        now.clear();
        now.set(2026, Calendar.MARCH, 31, 12, 0, 0);
        Calendar cutoff = (Calendar) now.clone();
        cutoff.add(Calendar.MONTH, -1);

        assertTrue(workflow.isDuplicateEligible(
            cutoff.getTimeInMillis(), now.getTimeInMillis(), TimeZone.getDefault()));
        assertFalse(workflow.isDuplicateEligible(
            cutoff.getTimeInMillis() + 1L, now.getTimeInMillis(), TimeZone.getDefault()));
    }

    @Test
    public void invalidOperationalPoliciesClampOrFallBackWithoutThrowing() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("duplicateCheck", new JSONObject()
                .put("recentAction", "continue")
                .put("eligibleAction", "skip_as_submitted"))
            .put("printing", new JSONObject()
                .put("enabled", "true")
                .put("preflightAction", "automatic")
                .put("confirmationPolls", -8)
                .put("confirmationPollIntervalMs", 90000)
                .put("maxAutoReprints", -3)
                .put("finalRecheckDelayMs", 999999)
                .put("batchEndRecheckMode", "unbounded")
                .put("unknownStatusPresentation", "guess"))
            .put("materials", new JSONObject()
                .put("missingRecovery", new JSONObject()
                    .put("enabled", 1)
                    .put("localNotice", "true")))
            .put("submission", new JSONObject()
                .put("maxAttempts", 0)
                .put("retryDelayMs", -1)
                .put("interUnitDelayMs", -1)
                .put("roundLedgerRetentionDays", 99)
                .put("maxConsecutiveFailures", 1000)
                .put("networkRetry", new JSONObject()
                    .put("maxAttempts", 1000)
                    .put("baseDelayMs", 0)
                    .put("maxDelayMs", 10))));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertEquals(ProfileWorkflow.DUPLICATE_AGE_DAYS, workflow.duplicateAgeUnit);
        assertEquals(0, workflow.duplicateAgeValue);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateUnknownDateAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateRecentAction);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.duplicateEligibleAction);
        assertFalse(workflow.printingEnabled);
        assertEquals(ProfileWorkflow.ACTION_BLOCK, workflow.printingPreflightAction);
        assertEquals(1, workflow.printingConfirmationPolls);
        assertEquals(30000L, workflow.printingConfirmationPollIntervalMs);
        assertEquals(0, workflow.printingMaxAutoReprints);
        assertEquals(120000L, workflow.printingFinalRecheckDelayMs);
        assertEquals(ProfileWorkflow.PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS,
            workflow.printingBatchEndRecheckMode);
        assertEquals(ProfileWorkflow.PRINT_UNKNOWN_PRESENTATION_DISTINCT,
            workflow.printingUnknownStatusPresentation);
        assertFalse(workflow.missingRecoveryEnabled);
        assertFalse(workflow.missingRecoveryLocalNotice);
        assertEquals(1, workflow.submissionMaxAttempts);
        assertEquals(0L, workflow.submissionRetryDelayMs);
        assertEquals(0L, workflow.submissionInterUnitDelayMs);
        assertEquals(30, workflow.roundLedgerRetentionDays);
        assertEquals(100, workflow.submissionMaxConsecutiveFailures);
        assertEquals(100, workflow.submissionNetworkRetryMaxAttempts);
        assertEquals(250L, workflow.submissionNetworkRetryBaseDelayMs);
        assertEquals(250L, workflow.submissionNetworkRetryMaxDelayMs);
    }

    @Test
    public void malformedNumericPoliciesUseDefaultsAndRespectNetworkDelayOrdering() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("printing", new JSONObject()
                .put("confirmationPolls", 2.5)
                .put("maxAutoReprints", "4"))
            .put("submission", new JSONObject()
                .put("maxAttempts", "5")
                .put("interUnitDelayMs", "1250")
                .put("roundLedgerRetentionDays", "9")
                .put("networkRetry", new JSONObject()
                    .put("baseDelayMs", 60000)
                    .put("maxDelayMs", "invalid"))));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertEquals(1, workflow.printingConfirmationPolls);
        assertEquals(0, workflow.printingMaxAutoReprints);
        assertEquals(1, workflow.submissionMaxAttempts);
        assertEquals(0L, workflow.submissionInterUnitDelayMs);
        assertEquals(1, workflow.roundLedgerRetentionDays);
        assertEquals(60000L, workflow.submissionNetworkRetryBaseDelayMs);
        assertEquals(60000L, workflow.submissionNetworkRetryMaxDelayMs);
    }

    @Test
    public void operationalPolicyOppositeBoundsAreAlsoClamped() throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("printing", new JSONObject()
                .put("confirmationPolls", 999)
                .put("confirmationPollIntervalMs", 0)
                .put("maxAutoReprints", 999)
                .put("finalRecheckDelayMs", -1))
            .put("submission", new JSONObject()
                .put("maxAttempts", 999)
                .put("retryDelayMs", 999999)
                .put("interUnitDelayMs", 999999)
                .put("roundLedgerRetentionDays", 0)
                .put("maxConsecutiveFailures", 0)
                .put("networkRetry", new JSONObject()
                    .put("maxAttempts", -1)
                    .put("baseDelayMs", 999999)
                    .put("maxDelayMs", 999999))));

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);

        assertEquals(12, workflow.printingConfirmationPolls);
        assertEquals(250L, workflow.printingConfirmationPollIntervalMs);
        assertEquals(3, workflow.printingMaxAutoReprints);
        assertEquals(0L, workflow.printingFinalRecheckDelayMs);
        assertEquals(10, workflow.submissionMaxAttempts);
        assertEquals(60000L, workflow.submissionRetryDelayMs);
        assertEquals(60000L, workflow.submissionInterUnitDelayMs);
        assertEquals(1, workflow.roundLedgerRetentionDays);
        assertEquals(1, workflow.submissionMaxConsecutiveFailures);
        assertEquals(0, workflow.submissionNetworkRetryMaxAttempts);
        assertEquals(60000L, workflow.submissionNetworkRetryBaseDelayMs);
        assertEquals(300000L, workflow.submissionNetworkRetryMaxDelayMs);
    }
}
