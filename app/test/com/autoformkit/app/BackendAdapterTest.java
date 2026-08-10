package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BackendAdapterTest {
    private static JSONObject sharedFixture() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("panel/backend-adapter.example.json"),
            cwd.resolve("../panel/backend-adapter.example.json")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("shared panel fixture not found from " + cwd);
    }

    private static BackendAdapter fixtureAdapter() throws Exception {
        JSONObject config = new JSONObject();
        config.put("backendAdapter", sharedFixture());
        return BackendAdapter.from(config);
    }

    private static JSONObject outcomeRule(JSONArray codeValues, JSONArray messagePatterns)
            throws Exception {
        return new JSONObject()
            .put("codeValues", codeValues)
            .put("messagePatterns", messagePatterns);
    }

    private static JSONObject submitOutcomePolicy() throws Exception {
        return new JSONObject()
            .put("version", 1)
            .put("evidenceSha256", "b".repeat(64))
            .put("retryableNotWrittenRules", new JSONArray()
                .put(outcomeRule(
                    new JSONArray().put("TEMP-REJECT"),
                    new JSONArray().put("retry explicitly")))
                .put(outcomeRule(
                    new JSONArray().put(429),
                    new JSONArray())))
            .put("missingMaterialNotWrittenRules", new JSONArray()
                .put(outcomeRule(
                    new JSONArray().put("MISSING-INPUT"),
                    new JSONArray().put("missing example material"))));
    }

    private static JSONObject recipeOutcomePolicy() throws Exception {
        return new JSONObject()
            .put("version", 1)
            .put("evidenceSha256", "c".repeat(64))
            .put("retryableNotWrittenRules", new JSONArray()
                .put(outcomeRule(
                    new JSONArray().put("STEP-NOT-WRITTEN"),
                    new JSONArray().put("retry example recipe"))))
            .put("alreadyExistsAcknowledgedRules", new JSONArray()
                .put(outcomeRule(
                    new JSONArray().put("STEP-ALREADY-APPLIED"),
                    new JSONArray().put("example recipe already applied"))));
    }

    private static JSONObject controlledRecoveryCapability() throws Exception {
        java.security.KeyPairGenerator generator =
            java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new JSONObject()
            .put("version", 1)
            .put("issuanceMode", "panel_signed_exact_reconciliation")
            .put("evidenceAlgorithm", "RS256")
            .put("keyId", "sample-recovery-key-1")
            .put("publicKeySpkiHex", ControlledRecoveryRules.hex(
                generator.generateKeyPair().getPublic().getEncoded()))
            .put("maxEvidenceAgeSeconds", 300)
            .put("reconciliationContractSha256", "a".repeat(64))
            .put("enabledOperations", new JSONArray()
                .put("FINAL_SUBMISSION")
                .put("PREVIOUS_STEP_RECIPE")
                .put("MULTIPART_UPLOAD"));
    }

    private static ProfileWorkflow dynamicWorkflow(String resolverId) throws Exception {
        JSONObject profile = new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("enabled", true)
                .put("triggerResultKeys", new JSONArray().put("sample-review"))
                .put("templates", new JSONArray().put(new JSONObject()
                    .put("templateId", 7001)
                    .put("mode", "template_detail")
                    .put("resolverId", resolverId)
                    .put("expectedStep", 7)
                    .put("sources", new JSONObject()
                        .put("sample-evidence", "example-photo"))
                    .put("delayAfterMs", 25)))));
        return ProfileWorkflow.from(profile);
    }

    private static JSONObject dynamicTemplateData() throws Exception {
        return new JSONObject()
            .put("id", 7001)
            .put("name", "Fictional template")
            .put("processStep", 7)
            .put("warehouseId", 17)
            .put("sku", "SAMPLE-SKU")
            .put("fields", new JSONArray()
                .put(dynamicField("sample-serial-field", "sample-serial", true, true,
                    new JSONArray()))
                .put(dynamicField("sample-photo-field", "sample-photo", true, true,
                    new JSONArray()))
                .put(dynamicField("sample-choice-field", "sample-choice", true, true,
                    new JSONArray().put(new JSONObject()
                        .put("value", "sample-accepted")
                        .put("label", "Sample accepted")
                        .put("englishLabel", "Sample accepted")
                        .put("quantity", 2))))
                .put(dynamicField("sample-hidden-field", "sample-hidden", false, false,
                    new JSONArray())));
    }

    private static JSONObject dynamicField(String id, String type, boolean required,
                                           boolean visible, JSONArray options) throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("type", type)
            .put("parentType", "")
            .put("typeName", "")
            .put("title", id)
            .put("englishTitle", id)
            .put("required", required)
            .put("visible", visible)
            .put("maxCount", 4)
            .put("options", options);
    }

    private static JSONObject alternateLiveTarget() throws Exception {
        return new JSONObject()
            .put("template", new JSONObject()
                .put("id", 7001)
                .put("warehouseId", 17)
                .put("sku", "SAMPLE-SKU"))
            .put("snFields", new JSONObject().put("primary", "sample-serial"))
            .put("gradeMap", new JSONObject().put("sample-ready", new JSONObject()
                .put("field", "sample-result").put("value", "SAMPLE_READY")))
            .put("photoSlots", new JSONArray()
                .put(new JSONObject().put("field", "sample-photo")))
            .put("conditionalFields", new JSONArray()
                .put(new JSONObject().put("field", "sample-live-choice")));
    }

    private static JSONObject alternateLiveEntry() throws Exception {
        return new JSONObject()
            .put("toggles", new JSONArray().put(new JSONObject()
                .put("key", "sample-live-toggle")
                .put("default", false)
                .put("dataOverrides", new JSONObject())))
            .put("dataOverrides", new JSONObject())
            .put("dynamicOverrideFields", new JSONArray().put("sample-live-choice"))
            .put("dynamicOverrideProviders", new JSONArray().put(new JSONObject()
                .put("id", "sample-live-provider")
                .put("triggerToggleKey", "sample-live-toggle")
                .put("templateId", 7001)
                .put("expectedStep", 7)
                .put("resolverId", "sample-alternate-live-option-v1")
                .put("outputField", "sample-live-choice")));
    }

    private static JSONObject alternateLiveTemplate() throws Exception {
        return new JSONObject()
            .put("id", 7001)
            .put("name", "Sample alternate live template")
            .put("processStep", 7)
            .put("warehouseId", 17)
            .put("sku", "SAMPLE-SKU")
            .put("fields", new JSONArray().put(dynamicField(
                "sample-live-choice", "sample-choice", false, true,
                new JSONArray().put(new JSONObject()
                    .put("value", "sample-selected-option")
                    .put("label", "Sample selected option")
                    .put("englishLabel", "Sample selected option")
                    .put("quantity", 2)))));
    }

    @Test
    public void parsesTheExactPanelFixture() throws Exception {
        BackendAdapter adapter = fixtureAdapter();

        assertTrue(adapter.parseErrors.toString(), adapter.isSupported());
        assertEquals("https://backend.example.invalid/api", adapter.baseUrl);
        assertEquals("/entries", adapter.endpoint(BackendAdapter.ENDPOINT_SUBMIT_ENTRY));
        assertEquals("image", adapter.operations.ocr.multipartField);
        assertEquals("url", adapter.operations.upload.resultPath);
        assertEquals("templateId", adapter.operations.submit.templateIdField);
        assertEquals("code", adapter.operations.submit.materialItemMapping.codeField);
        assertEquals("label", adapter.operations.submit.materialItemMapping.nameField);
        assertEquals("quantity", adapter.operations.submit.materialItemMapping.quantityField);
        assertTrue(adapter.operations.previousSteps.missingResponseCodes.isEmpty());
        assertTrue(adapter.operations.previousSteps.missingMessagePatterns.isEmpty());
        assertTrue(adapter.operations.previousSteps.retryableMessagePatterns.isEmpty());
        assertTrue(adapter.operations.previousSteps.alreadyExistsMessagePatterns.isEmpty());
        assertFalse(adapter.operations.previousSteps.isRetryableResponse(
            new JSONObject().put("message", "retry example step"), adapter.response));
        assertFalse(adapter.operations.previousSteps.isAlreadyExistsResponse(
            new JSONObject().put("message", "example record already exists"),
            adapter.response));
        assertEquals(java.util.Arrays.asList("seconds", "milliseconds"),
            adapter.operations.duplicateCheck.epochUnits);
        assertTrue(adapter.operations.duplicateCheck.dateTransforms.isEmpty());
        assertTrue(adapter.missingForSubmit(false, true, false, false).toString(),
            adapter.missingForSubmit(false, true, false, false).isEmpty());
        assertEquals("UTC", adapter.operations.duplicateCheck.timeZone);
        assertEquals("AutoFormKit-Example/1.0", adapter.request.webUserAgent);
        assertEquals("en", adapter.request.webAcceptLanguage);
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(
            401, adapter.sessionInvalidPolicy));
        assertFalse(adapter.printing.enabled);
    }

    @Test
    public void previousStepResponseRulesAreExplicitAndCaseInsensitive() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject previous = fixture.getJSONObject("operations").getJSONObject("previousSteps");
        previous.put("retryableMessagePatterns", new JSONArray()
            .put("try the example step again")
            .put("temporary example error"));
        previous.put("alreadyExistsMessagePatterns", new JSONArray()
            .put("example record already exists"));
        previous.put("missingResponseCodes", new JSONArray().put(404).put("absent"));
        previous.put("missingMessagePatterns", new JSONArray()
            .put("example record was not found"));

        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(true, false, false, false).toString(),
            adapter.missingForSubmit(true, false, false, false).isEmpty());
        assertTrue(adapter.missingForPreviousStepLookup().toString(),
            adapter.missingForPreviousStepLookup().isEmpty());
        assertEquals(java.util.Arrays.asList(
                "try the example step again", "temporary example error"),
            adapter.operations.previousSteps.retryableMessagePatterns);
        assertEquals(java.util.Collections.singletonList("example record already exists"),
            adapter.operations.previousSteps.alreadyExistsMessagePatterns);
        assertEquals(new java.util.LinkedHashSet<>(java.util.Arrays.asList("404", "absent")),
            adapter.operations.previousSteps.missingResponseCodes);
        assertTrue(adapter.operations.previousSteps.isMissingResponse(404, "unrelated"));
        assertTrue(adapter.operations.previousSteps.isMissingResponse(
            "other", "The EXAMPLE RECORD WAS NOT FOUND"));
        assertFalse(adapter.operations.previousSteps.isMissingResponse(
            "405", "example record is unavailable"));
        assertTrue(adapter.operations.previousSteps.isRetryableResponse(
            new JSONObject().put("message", "Please TRY THE EXAMPLE STEP AGAIN later"),
            adapter.response));
        assertFalse(adapter.operations.previousSteps.isRetryableResponse(
            new JSONObject().put("message", "example record already exists"),
            adapter.response));
        assertTrue(adapter.operations.previousSteps.isAlreadyExistsResponse(
            new JSONObject().put("message",
                "The EXAMPLE RECORD ALREADY EXISTS for this identifier"),
            adapter.response));
        assertFalse(adapter.operations.previousSteps.isAlreadyExistsResponse(
            null, adapter.response));

        JSONObject unrelatedPayload = new JSONObject()
            .put("status", "rejected")
            .put("message", "A different business error")
            .put("result", new JSONObject()
                .put("echo", "try the example step again; example record already exists"));
        assertFalse(adapter.operations.previousSteps.isRetryableResponse(
            unrelatedPayload, adapter.response));
        assertFalse(adapter.operations.previousSteps.isAlreadyExistsResponse(
            unrelatedPayload, adapter.response));
        assertFalse(adapter.operations.previousSteps.hasRecipeRetryableNotWrittenRules());
        assertFalse(adapter.operations.previousSteps
            .hasRecipeAlreadyExistsAcknowledgedRules());
        assertEquals(BackendAdapter.PreviousSteps.RecipeResponseDisposition.UNCLASSIFIED,
            adapter.operations.previousSteps.recipeResponseDisposition(
                new JSONObject().put("message", "Please try the example step again"),
                adapter.response));
    }

    @Test
    public void recipeOutcomePolicyIsOperationScopedStructuredAndConflictSafe()
            throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("recipeOutcomePolicy", recipeOutcomePolicy());
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(adapter.operations.previousStepErrors.toString(),
            adapter.operations.previousStepErrors.isEmpty());
        assertTrue(adapter.operations.previousSteps.hasRecipeRetryableNotWrittenRules());
        assertTrue(adapter.operations.previousSteps
            .hasRecipeAlreadyExistsAcknowledgedRules());

        JSONObject contaminated = new JSONObject()
            .put("status", "STEP-NOT-WRITTEN")
            .put("message", "A different business failure")
            .put("result", new JSONObject()
                .put("requestEcho", "retry example recipe; example recipe already applied"));
        assertEquals(BackendAdapter.PreviousSteps.RecipeResponseDisposition.UNCLASSIFIED,
            adapter.operations.previousSteps.recipeResponseDisposition(
                contaminated, adapter.response));
        assertEquals(
            BackendAdapter.PreviousSteps.RecipeResponseDisposition.RETRYABLE_NOT_WRITTEN,
            adapter.operations.previousSteps.recipeResponseDisposition(
                new JSONObject().put("status", "STEP-NOT-WRITTEN")
                    .put("message", "Please RETRY EXAMPLE RECIPE"),
                adapter.response));
        assertEquals(
            BackendAdapter.PreviousSteps.RecipeResponseDisposition
                .ALREADY_EXISTS_ACKNOWLEDGED,
            adapter.operations.previousSteps.recipeResponseDisposition(
                new JSONObject().put("status", "STEP-ALREADY-APPLIED")
                    .put("message", "The EXAMPLE RECIPE ALREADY APPLIED"),
                adapter.response));

        JSONObject ambiguousFixture = sharedFixture();
        JSONObject ambiguousRule = outcomeRule(new JSONArray(),
            new JSONArray().put("ambiguous recipe outcome"));
        ambiguousFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("recipeOutcomePolicy", new JSONObject()
                .put("version", 1)
                .put("evidenceSha256", "d".repeat(64))
                .put("retryableNotWrittenRules", new JSONArray()
                    .put(new JSONObject(ambiguousRule.toString())))
                .put("alreadyExistsAcknowledgedRules", new JSONArray()
                    .put(new JSONObject(ambiguousRule.toString()))));
        BackendAdapter ambiguous = BackendAdapter.from(
            new JSONObject().put("backendAdapter", ambiguousFixture));
        assertEquals(BackendAdapter.PreviousSteps.RecipeResponseDisposition.CONFLICT,
            ambiguous.operations.previousSteps.recipeResponseDisposition(
                new JSONObject().put("status", "BUSINESS-ERROR")
                    .put("message", "Ambiguous recipe outcome"),
                ambiguous.response));

        JSONObject malformedFixture = sharedFixture();
        JSONObject malformed = recipeOutcomePolicy();
        malformed.getJSONArray("retryableNotWrittenRules")
            .getJSONObject(0).remove("messagePatterns");
        malformedFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("recipeOutcomePolicy", malformed);
        BackendAdapter invalid = BackendAdapter.from(
            new JSONObject().put("backendAdapter", malformedFixture));
        assertFalse(invalid.operations.previousStepErrors.isEmpty());
        assertFalse(invalid.operations.previousSteps.hasRecipeRetryableNotWrittenRules());
        assertFalse(invalid.operations.previousSteps
            .hasRecipeAlreadyExistsAcknowledgedRules());
    }

    @Test
    public void submitOutcomePolicyIsOptionalAndLegacyPatternsCannotAuthorizeRetry()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject submit = fixture.getJSONObject("operations").getJSONObject("submit");
        submit.put("retryableMessagePatterns", new JSONArray().put("retry explicitly"));
        submit.put("missingMaterialMessagePatterns",
            new JSONArray().put("missing example material"));
        submit.remove("outcomePolicy");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(adapter.parseErrors.toString(), adapter.isSupported());
        assertTrue(adapter.operations.submit.outcomePolicy.retryableNotWrittenRules.isEmpty());
        assertTrue(adapter.operations.submit.outcomePolicy.missingMaterialNotWrittenRules.isEmpty());
        assertFalse(adapter.operations.submit.hasRetryableNotWrittenRules());
        assertFalse(adapter.operations.submit.hasMissingMaterialNotWrittenRules());

        JSONObject legacyRetry = new JSONObject()
            .put("status", "TEMP-REJECT")
            .put("message", "Please retry explicitly later");
        JSONObject legacyMissing = new JSONObject()
            .put("status", "MISSING-INPUT")
            .put("error", new JSONObject()
                .put("message", "Missing example material from the request"));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            legacyRetry, adapter.response));
        assertFalse(adapter.operations.submit.isMissingMaterialResponse(
            legacyMissing, adapter.response));
    }

    @Test
    public void submitOutcomePolicyUsesConfiguredEvidenceWithAndAcrossSelectorsAndOrAcrossRules()
            throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("operations").getJSONObject("submit")
            .put("outcomePolicy", submitOutcomePolicy());
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(adapter.operations.submitErrors.toString(),
            adapter.operations.submitErrors.isEmpty());
        assertTrue(adapter.operations.submit.hasRetryableNotWrittenRules());
        assertTrue(adapter.operations.submit.hasMissingMaterialNotWrittenRules());

        JSONObject contaminated = new JSONObject()
            .put("status", "TEMP-REJECT")
            .put("message", "Unclassified business failure")
            .put("result", new JSONObject()
                .put("echo", "retry explicitly; missing example material"))
            .put("requestEcho", "retry explicitly; missing example material");
        assertFalse(adapter.operations.submit.isRetryableResponse(
            contaminated, adapter.response));
        assertFalse(adapter.operations.submit.isMissingMaterialResponse(
            contaminated, adapter.response));
        JSONObject nestedConfiguredMessage = new JSONObject()
            .put("status", "TEMP-REJECT")
            .put("message", new JSONObject()
                .put("requestEcho", "retry explicitly; missing example material"));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            nestedConfiguredMessage, adapter.response));
        assertFalse(adapter.operations.submit.isMissingMaterialResponse(
            nestedConfiguredMessage, adapter.response));

        JSONObject retry = new JSONObject()
            .put("status", "TEMP-REJECT")
            .put("message", "Please RETRY EXPLICITLY later");
        assertTrue(adapter.operations.submit.isRetryableResponse(retry, adapter.response));
        assertFalse(adapter.operations.submit.isMissingMaterialResponse(
            retry, adapter.response));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            new JSONObject()
                .put("status", "OTHER")
                .put("message", "Please retry explicitly later"),
            adapter.response));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            new JSONObject()
                .put("status", "TEMP-REJECT")
                .put("message", "A different business failure"),
            adapter.response));

        // This second rule demonstrates OR across rules and a code-only selector.
        assertTrue(adapter.operations.submit.isRetryableResponse(
            new JSONObject().put("status", 429).put("message", "unrelated"),
            adapter.response));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            new JSONObject().put("status", 430).put("message", "unrelated"),
            adapter.response));

        JSONObject missing = new JSONObject()
            .put("status", "MISSING-INPUT")
            .put("error", new JSONObject()
                .put("message", "Missing Example Material from the request"));
        assertFalse(adapter.operations.submit.isRetryableResponse(
            missing, adapter.response));
        assertTrue(adapter.operations.submit.isMissingMaterialResponse(
            missing, adapter.response));
        assertFalse(adapter.operations.submit.isMissingMaterialResponse(
            new JSONObject()
                .put("status", "OTHER")
                .put("error", new JSONObject()
                    .put("message", "Missing example material from the request")),
            adapter.response));
    }

    @Test
    public void malformedSubmitOutcomePolicyIsReportedAndDisablesAllClassifiers()
            throws Exception {
        JSONObject badHashFixture = sharedFixture();
        JSONObject badHashPolicy = submitOutcomePolicy()
            .put("evidenceSha256", "B".repeat(64));
        badHashFixture.getJSONObject("operations").getJSONObject("submit")
            .put("outcomePolicy", badHashPolicy);
        BackendAdapter badHash = BackendAdapter.from(
            new JSONObject().put("backendAdapter", badHashFixture));
        assertTrue(badHash.operations.submitErrors.toString(),
            badHash.operations.submitErrors.contains(
                "backendAdapter.operations.submit.outcomePolicy.evidenceSha256"));
        assertFalse(badHash.operations.submit.isRetryableResponse(
            new JSONObject()
                .put("status", "TEMP-REJECT")
                .put("message", "retry explicitly"),
            badHash.response));
        assertFalse(badHash.operations.submit.isMissingMaterialResponse(
            new JSONObject()
                .put("status", "MISSING-INPUT")
                .put("message", "missing example material"),
            badHash.response));

        JSONObject emptyRuleFixture = sharedFixture();
        JSONObject emptyRulePolicy = submitOutcomePolicy();
        emptyRulePolicy.getJSONArray("retryableNotWrittenRules")
            .put(outcomeRule(new JSONArray(), new JSONArray()));
        emptyRuleFixture.getJSONObject("operations").getJSONObject("submit")
            .put("outcomePolicy", emptyRulePolicy);
        BackendAdapter emptyRule = BackendAdapter.from(
            new JSONObject().put("backendAdapter", emptyRuleFixture));
        assertTrue(emptyRule.operations.submitErrors.toString(),
            emptyRule.operations.submitErrors.contains(
                "backendAdapter.operations.submit.outcomePolicy"
                    + ".retryableNotWrittenRules[2].selectors"));
        assertFalse(emptyRule.operations.submit.isRetryableResponse(
            new JSONObject()
                .put("status", "TEMP-REJECT")
                .put("message", "retry explicitly"),
            emptyRule.response));

        JSONObject badShapeFixture = sharedFixture();
        JSONObject badShapePolicy = submitOutcomePolicy();
        badShapePolicy.getJSONArray("missingMaterialNotWrittenRules")
            .put(new JSONObject()
                .put("codeValues", new JSONArray().put(new JSONObject()))
                .put("messagePatterns", new JSONArray().put(" ")));
        badShapeFixture.getJSONObject("operations").getJSONObject("submit")
            .put("outcomePolicy", badShapePolicy);
        BackendAdapter badShape = BackendAdapter.from(
            new JSONObject().put("backendAdapter", badShapeFixture));
        assertFalse(badShape.operations.submitErrors.toString(),
            badShape.operations.submitErrors.isEmpty());
        assertFalse(badShape.operations.submit.isRetryableResponse(
            new JSONObject()
                .put("status", "TEMP-REJECT")
                .put("message", "retry explicitly"),
            badShape.response));
        assertFalse(badShape.operations.submit.isMissingMaterialResponse(
            new JSONObject()
                .put("status", "MISSING-INPUT")
                .put("message", "missing example material"),
            badShape.response));
    }

    @Test
    public void duplicateDateCompatibilityPolicyIsExplicitAndCanUseDeviceTimeZone()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject duplicate = fixture.getJSONObject("operations")
            .getJSONObject("duplicateCheck");
        duplicate.put("epochDigitLengths", new JSONArray().put(10).put(13));
        duplicate.put("numericFractionPolicy", "truncate");
        duplicate.put("textParseConsumption", "prefix");
        duplicate.put("plausibilityScope", "epoch_only");
        duplicate.put("timeZoneSource", "device");
        duplicate.put("rootValueEnabled", true);

        java.util.TimeZone original = java.util.TimeZone.getDefault();
        java.util.TimeZone device = java.util.TimeZone.getTimeZone("Europe/Paris");
        java.util.TimeZone.setDefault(device);
        try {
            BackendAdapter adapter = BackendAdapter.from(
                new JSONObject().put("backendAdapter", fixture));
            BackendAdapter.DuplicateCheck operation = adapter.operations.duplicateCheck;
            assertTrue(adapter.missingForSubmit(false, true, false, false).toString(),
                adapter.missingForSubmit(false, true, false, false).isEmpty());
            assertTrue(operation.dateParsePolicy.valid);
            assertEquals(java.util.Arrays.asList(10, 13),
                operation.dateParsePolicy.epochDigitLengths);
            assertEquals("truncate", operation.dateParsePolicy.numericFractionPolicy);
            assertEquals(DuplicateDateRules.NUMERIC_EPOCH_PRECISION_EXACT,
                operation.dateParsePolicy.numericEpochPrecision);
            assertEquals("prefix", operation.dateParsePolicy.textParseConsumption);
            assertEquals("epoch_only", operation.dateParsePolicy.plausibilityScope);
            assertEquals("device", operation.dateParsePolicy.timeZoneSource);
            assertTrue(operation.dateParsePolicy.rootValueEnabled);
            assertEquals(device.getID(), operation.duplicateAgeTimeZone().getID());
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    public void duplicateDateCompatibilityPolicyIsOptionalButPartialPolicyFailsClosed()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject duplicate = fixture.getJSONObject("operations")
            .getJSONObject("duplicateCheck");
        for (String key : new String[]{"epochDigitLengths", "numericFractionPolicy",
                "textParseConsumption", "plausibilityScope", "timeZoneSource",
                "rootValueEnabled", "numericEpochPrecision"}) {
            duplicate.remove(key);
        }
        BackendAdapter compatible = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(compatible.operations.duplicateCheck.dateParsePolicy == null);
        assertTrue(compatible.missingForSubmit(false, true, false, false).toString(),
            compatible.missingForSubmit(false, true, false, false).isEmpty());

        duplicate.put("epochDigitLengths", new JSONArray().put(10));
        BackendAdapter partial = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        List<String> missing = partial.missingForSubmit(false, true, false, false);
        assertTrue(missing.toString(), missing.contains(
            "operations.duplicateCheck.numericFractionPolicy"));
        assertTrue(missing.toString(), missing.contains(
            "operations.duplicateCheck.rootValueEnabled"));
    }

    @Test
    public void duplicateDateNumericEpochPrecisionIsOptionalAndPanelControlled()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject duplicate = fixture.getJSONObject("operations")
            .getJSONObject("duplicateCheck");
        duplicate.put("epochDigitLengths", new JSONArray().put(10).put(13));
        duplicate.put("numericFractionPolicy", "truncate");
        duplicate.put("textParseConsumption", "prefix");
        duplicate.put("plausibilityScope", "epoch_only");
        duplicate.put("timeZoneSource", "device");
        duplicate.put("rootValueEnabled", true);
        duplicate.put("numericEpochPrecision", "minute_floor");

        BackendAdapter minuteFloor = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(minuteFloor.missingForSubmit(false, true, false, false).toString(),
            minuteFloor.missingForSubmit(false, true, false, false).isEmpty());
        assertEquals(DuplicateDateRules.NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR,
            minuteFloor.operations.duplicateCheck.dateParsePolicy.numericEpochPrecision);

        duplicate.put("numericEpochPrecision", "second_round");
        BackendAdapter invalid = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(invalid.missingForSubmit(false, true, false, false).toString(),
            invalid.missingForSubmit(false, true, false, false).contains(
                "operations.duplicateCheck.numericEpochPrecision"));
    }

    @Test
    public void previousStepResponseRulesAreRequiredWhenTheOperationIsUsed() throws Exception {
        JSONObject codeMissingFixture = sharedFixture();
        codeMissingFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("missingResponseCodes");
        BackendAdapter codeMissing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", codeMissingFixture));
        assertTrue(codeMissing.isSupported());
        assertTrue(codeMissing.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(codeMissing.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));
        assertTrue(codeMissing.missingForPreviousStepLookup()
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));

        JSONObject messageMissingFixture = sharedFixture();
        messageMissingFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("missingMessagePatterns");
        BackendAdapter messageMissing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", messageMissingFixture));
        assertTrue(messageMissing.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.missingMessagePatterns"));

        JSONObject retryMissingFixture = sharedFixture();
        retryMissingFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("retryableMessagePatterns");
        BackendAdapter retryMissing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", retryMissingFixture));
        assertTrue(retryMissing.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(retryMissing.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.retryableMessagePatterns"));

        JSONObject existsMissingFixture = sharedFixture();
        existsMissingFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("alreadyExistsMessagePatterns");
        BackendAdapter existsMissing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", existsMissingFixture));
        assertTrue(existsMissing.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.alreadyExistsMessagePatterns"));

        JSONObject blankPatternFixture = sharedFixture();
        blankPatternFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("retryableMessagePatterns", new JSONArray().put(" "));
        BackendAdapter blankPattern = BackendAdapter.from(
            new JSONObject().put("backendAdapter", blankPatternFixture));
        assertTrue(blankPattern.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.retryableMessagePatterns"));

        JSONObject numericPatternFixture = sharedFixture();
        numericPatternFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("alreadyExistsMessagePatterns", new JSONArray().put(7));
        BackendAdapter numericPattern = BackendAdapter.from(
            new JSONObject().put("backendAdapter", numericPatternFixture));
        assertTrue(numericPattern.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.alreadyExistsMessagePatterns"));

        JSONObject invalidCodeFixture = sharedFixture();
        invalidCodeFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("missingResponseCodes", new JSONArray().put(new JSONObject()));
        BackendAdapter invalidCode = BackendAdapter.from(
            new JSONObject().put("backendAdapter", invalidCodeFixture));
        assertTrue(invalidCode.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));

        JSONObject duplicateCodeFixture = sharedFixture();
        duplicateCodeFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .put("missingResponseCodes", new JSONArray().put(404).put("404"));
        BackendAdapter duplicateCode = BackendAdapter.from(
            new JSONObject().put("backendAdapter", duplicateCodeFixture));
        assertTrue(duplicateCode.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps.missingResponseCodes"));
    }

    @Test
    public void appliesConfiguredResponseAndOperationPaths() throws Exception {
        BackendAdapter adapter = fixtureAdapter();
        JSONObject response = new JSONObject()
            .put("status", "ok")
            .put("result", new JSONObject()
                .put("url", "https://files.example.invalid/a.jpg")
                .put("items", new JSONArray().put(new JSONObject()
                    .put("formData", new JSONObject().put("serial", "EXAMPLE-001")))));

        assertTrue(adapter.response.isSuccess(response));
        Object data = adapter.response.data(response);
        assertEquals("https://files.example.invalid/a.jpg", adapter.operations.upload.result(data));
        JSONArray items = adapter.operations.previousSteps.items(data);
        assertEquals("EXAMPLE-001", adapter.operations.previousSteps.serial(items.getJSONObject(0)));

        JSONObject envelope = adapter.operations.submit.wrap(12, 34, "SKU-DEMO",
            new JSONObject().put("serial", "EXAMPLE-001"));
        assertEquals(12, envelope.getInt("templateId"));
        assertEquals(34, envelope.getInt("warehouseId"));
        assertTrue(envelope.has("formData"));
        assertTrue(envelope.has("videoId"));
        assertFalse(envelope.has("template_id"));

        JSONObject item = adapter.operations.submit.materialItemMapping.item(
            "EXAMPLE-CODE", "Example item", 3);
        assertEquals("EXAMPLE-CODE", item.getString("code"));
        assertEquals("Example item", item.getString("label"));
        assertEquals(3, item.getInt("quantity"));
    }

    @Test
    public void codeMissingResponseCompatibilityIsExplicitAndPanelOwned() throws Exception {
        JSONObject compatibleFixture = sharedFixture();
        compatibleFixture.getJSONObject("auth")
            .put("successFieldsWhenCodeMissing", new JSONArray()
                .put("result")
                .put("sessionToken"))
            .put("dataRootWhenCodeMissing", true);
        BackendAdapter compatible = BackendAdapter.from(
            new JSONObject().put("backendAdapter", compatibleFixture));

        assertTrue(compatible.parseErrors.toString(), compatible.isSupported());
        JSONObject rootToken = new JSONObject().put("sessionToken", "sample-session");
        assertTrue(compatible.auth.isSuccess(rootToken, compatible.response));
        assertEquals(rootToken, compatible.auth.data(rootToken, compatible.response));
        assertFalse(compatible.response.isSuccess(rootToken));

        JSONObject nullDataMarker = new JSONObject().put("result", JSONObject.NULL);
        assertTrue(compatible.auth.isSuccess(nullDataMarker, compatible.response));
        assertEquals(nullDataMarker, compatible.auth.data(
            nullDataMarker, compatible.response));

        JSONObject rejected = new JSONObject()
            .put("status", "rejected")
            .put("sessionToken", "must-not-override-an-explicit-code");
        assertFalse(compatible.auth.isSuccess(rejected, compatible.response));

        JSONObject messaged = new JSONObject()
            .put("sessionToken", "must-not-override-an-explicit-error")
            .put("message", "Sample authentication error");
        assertFalse(compatible.auth.isSuccess(messaged, compatible.response));

        JSONObject strictFixture = sharedFixture();
        strictFixture.getJSONObject("auth")
            .remove("successFieldsWhenCodeMissing");
        strictFixture.getJSONObject("auth")
            .remove("dataRootWhenCodeMissing");
        BackendAdapter strict = BackendAdapter.from(
            new JSONObject().put("backendAdapter", strictFixture));
        assertFalse(strict.auth.isSuccess(rootToken, strict.response));
        assertEquals(null, strict.auth.data(rootToken, strict.response));
    }

    @Test
    public void businessCodeMissingCompatibilityIsExplicitAndPanelOwned() throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("response")
            .put("successFieldsWhenCodeMissing", new JSONArray()
                .put("acceptedPayload")
                .put("receipt.id"))
            .put("dataRootWhenCodeMissing", true)
            .put("rejectMessageWhenCodeMissing", true);
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(adapter.parseErrors.toString(), adapter.isSupported());

        JSONObject accepted = new JSONObject()
            .put("acceptedPayload", JSONObject.NULL)
            .put("requestId", "sample-request");
        assertTrue(adapter.response.isSuccess(accepted));
        assertEquals(accepted, adapter.response.data(accepted));

        JSONObject explicitFailure = new JSONObject()
            .put("status", "rejected")
            .put("acceptedPayload", true);
        assertFalse(adapter.response.isSuccess(explicitFailure));

        JSONObject ambiguousMessage = new JSONObject()
            .put("receipt", new JSONObject().put("id", "sample-receipt"))
            .put("message", "A business message is present");
        assertFalse(adapter.response.isSuccess(ambiguousMessage));
        assertEquals(null, adapter.response.data(ambiguousMessage));

        JSONObject relaxedFixture = sharedFixture();
        relaxedFixture.getJSONObject("response")
            .put("successFieldsWhenCodeMissing", new JSONArray().put("receipt.id"))
            .put("dataRootWhenCodeMissing", false)
            .put("rejectMessageWhenCodeMissing", false);
        BackendAdapter relaxed = BackendAdapter.from(
            new JSONObject().put("backendAdapter", relaxedFixture));
        assertTrue(relaxed.parseErrors.toString(), relaxed.isSupported());
        assertTrue(relaxed.response.isSuccess(ambiguousMessage));
        assertEquals(null, relaxed.response.data(ambiguousMessage));

        BackendAdapter strict = fixtureAdapter();
        assertFalse(strict.response.isSuccess(accepted));
        assertEquals(null, strict.response.data(accepted));
    }

    @Test
    public void malformedCodeMissingResponseCompatibilityFailsClosed() throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("auth")
            .put("successFieldsWhenCodeMissing", "result")
            .put("dataRootWhenCodeMissing", "true");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertFalse(adapter.isSupported());
        assertTrue(adapter.parseErrors.contains(
            "backendAdapter.auth.successFieldsWhenCodeMissing"));
        assertTrue(adapter.parseErrors.contains(
            "backendAdapter.auth.dataRootWhenCodeMissing"));

        JSONObject responseFixture = sharedFixture();
        responseFixture.getJSONObject("response")
            .put("successFieldsWhenCodeMissing", new JSONArray().put("receipt.id"))
            .put("dataRootWhenCodeMissing", "true");
        BackendAdapter malformedResponse = BackendAdapter.from(
            new JSONObject().put("backendAdapter", responseFixture));
        assertFalse(malformedResponse.isSupported());
        assertTrue(malformedResponse.parseErrors.contains(
            "backendAdapter.response.dataRootWhenCodeMissing"));
        assertTrue(malformedResponse.parseErrors.contains(
            "backendAdapter.response.rejectMessageWhenCodeMissing"));

        JSONObject orphanFixture = sharedFixture();
        orphanFixture.getJSONObject("response")
            .put("dataRootWhenCodeMissing", true)
            .put("rejectMessageWhenCodeMissing", true);
        BackendAdapter orphanResponse = BackendAdapter.from(
            new JSONObject().put("backendAdapter", orphanFixture));
        assertFalse(orphanResponse.isSupported());
        assertTrue(orphanResponse.parseErrors.contains(
            "backendAdapter.response.dataRootWhenCodeMissing"));
        assertTrue(orphanResponse.parseErrors.contains(
            "backendAdapter.response.rejectMessageWhenCodeMissing"));
    }

    @Test
    public void duplicateSubmitFieldMappingsFailBeforePayloadConstruction() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject submit = fixture.getJSONObject("operations").getJSONObject("submit");
        submit.put("warehouseIdField", submit.getString("templateIdField"));
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(false, false, false, false)
            .contains("backendAdapter.operations.submit.fieldNames"));
        try {
            adapter.operations.submit.wrap(12, 34, "SKU-DEMO", new JSONObject());
            throw new AssertionError("duplicate submit fields must fail closed");
        } catch (IllegalStateException expected) {
            assertEquals("backendAdapter.operations.submit.fieldNames", expected.getMessage());
        }
    }

    @Test
    public void duplicateMaterialItemMappingsFailBeforePayloadConstruction() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject mapping = fixture.getJSONObject("operations").getJSONObject("submit")
            .getJSONObject("materialItemMapping");
        mapping.put("nameField", mapping.getString("codeField"));
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(false, false, true, false)
            .contains("backendAdapter.operations.submit.materialItemMapping.fieldNames"));
        try {
            adapter.operations.submit.materialItemMapping.item(
                "EXAMPLE-CODE", "Example item", 3);
            throw new AssertionError("duplicate material fields must fail closed");
        } catch (IllegalStateException expected) {
            assertEquals("backendAdapter.operations.submit.materialItemMapping.fieldNames",
                expected.getMessage());
        }
    }

    @Test
    public void duplicateDateUnitsMustBeExplicitSupportedAndUnique() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject duplicate = fixture.getJSONObject("operations").getJSONObject("duplicateCheck");
        duplicate.remove("epochUnits");
        BackendAdapter missing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(missing.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.epochUnits"));

        duplicate.put("epochUnits", new JSONArray().put("seconds").put("seconds"));
        BackendAdapter repeated = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(repeated.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.epochUnits"));

        duplicate.put("epochUnits", new JSONArray().put("automatic"));
        BackendAdapter unsupported = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(unsupported.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.epochUnits"));
    }

    @Test
    public void duplicateDateTransformsMustBeExplicitSupportedStringsAndUnique()
            throws Exception {
        JSONObject missingFixture = sharedFixture();
        missingFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .remove("dateTransforms");
        BackendAdapter missing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", missingFixture));
        assertTrue(missing.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(missing.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.dateTransforms"));

        JSONObject nonStringFixture = sharedFixture();
        nonStringFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .put("dateTransforms", new JSONArray().put(7));
        BackendAdapter nonString = BackendAdapter.from(
            new JSONObject().put("backendAdapter", nonStringFixture));
        assertTrue(nonString.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(nonString.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.dateTransforms"));

        JSONObject unknownFixture = sharedFixture();
        unknownFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .put("dateTransforms", new JSONArray().put("automatic"));
        BackendAdapter unknown = BackendAdapter.from(
            new JSONObject().put("backendAdapter", unknownFixture));
        assertTrue(unknown.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(unknown.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.dateTransforms"));

        JSONObject paddedFixture = sharedFixture();
        paddedFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .put("dateTransforms", new JSONArray().put(" iso_t_to_space "));
        BackendAdapter padded = BackendAdapter.from(
            new JSONObject().put("backendAdapter", paddedFixture));
        assertTrue(padded.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(padded.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.dateTransforms"));

        JSONObject repeatedFixture = sharedFixture();
        repeatedFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .put("dateTransforms", new JSONArray()
                .put("iso_t_to_space")
                .put("iso_t_to_space"));
        BackendAdapter repeated = BackendAdapter.from(
            new JSONObject().put("backendAdapter", repeatedFixture));
        assertTrue(repeated.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(repeated.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck.dateTransforms"));

        JSONObject orderedFixture = sharedFixture();
        orderedFixture.getJSONObject("operations").getJSONObject("duplicateCheck")
            .put("dateTransforms", new JSONArray()
                .put("iso_t_to_space")
                .put("truncate_after_seconds"));
        BackendAdapter ordered = BackendAdapter.from(
            new JSONObject().put("backendAdapter", orderedFixture));
        assertEquals(java.util.Arrays.asList(
                "iso_t_to_space", "truncate_after_seconds"),
            ordered.operations.duplicateCheck.dateTransforms);
        assertTrue(ordered.missingForSubmit(false, true, false, false).toString(),
            ordered.missingForSubmit(false, true, false, false).isEmpty());
    }

    @Test
    public void optionalOperationsAreValidatedOnlyWhenUsed() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject operations = fixture.getJSONObject("operations");
        operations.remove("ocr");
        operations.remove("previousSteps");
        operations.remove("duplicateCheck");
        operations.getJSONObject("submit").remove("materialItemMapping");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.parseErrors.toString(), adapter.isSupported());
        assertTrue(adapter.missingForLogin().isEmpty());
        assertTrue(adapter.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(adapter.missingForOcr().contains("backendAdapter.operations.ocr"));
        assertTrue(adapter.missingForSubmit(true, false, false, false)
            .contains("backendAdapter.operations.previousSteps"));
        assertTrue(adapter.missingForSubmit(false, true, false, false)
            .contains("backendAdapter.operations.duplicateCheck"));
        assertTrue(adapter.missingForSubmit(false, false, true, false)
            .contains("backendAdapter.operations.submit.materialItemMapping"));
        assertTrue(adapter.missingForSubmit(false, false, false, true)
            .contains("backendAdapter.printing.enabled"));
        assertTrue(adapter.missingForControlledRecovery(
            ControlledRecoveryRules.Operation.FINAL_SUBMISSION)
            .contains("backendAdapter.operations.recovery"));
    }

    @Test
    public void parsesPanelOwnedControlledRecoveryWithoutChangingSubmitRequirements()
            throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("operations").put(
            "recovery", controlledRecoveryCapability());
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.parseErrors.toString(), adapter.isSupported());
        assertTrue(adapter.missingForSubmit(false, false, false, false).toString(),
            adapter.missingForSubmit(false, false, false, false).isEmpty());
        for (ControlledRecoveryRules.Operation operation
                : ControlledRecoveryRules.Operation.values()) {
            assertTrue(adapter.missingForControlledRecovery(operation).toString(),
                adapter.missingForControlledRecovery(operation).isEmpty());
            assertTrue(adapter.operations.recovery.capability.supports(operation));
        }
        assertEquals(64, adapter.operations.recovery.capability.sha256.length());
    }

    @Test
    public void malformedOrPartialRecoveryCapabilityFailsItsSeparateGate()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject recovery = controlledRecoveryCapability();
        recovery.put("operatorOutcome", "NOT_WRITTEN");
        recovery.put("enabledOperations", new JSONArray().put("FINAL_SUBMISSION"));
        fixture.getJSONObject("operations").put("recovery", recovery);
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(false, false, false, false).isEmpty());
        assertTrue(adapter.missingForControlledRecovery(
            ControlledRecoveryRules.Operation.FINAL_SUBMISSION)
            .contains("backendAdapter.operations.recovery.fields"));
        assertTrue(adapter.missingForControlledRecovery(
            ControlledRecoveryRules.Operation.MULTIPART_UPLOAD)
            .contains("backendAdapter.operations.recovery.enabledOperations.MULTIPART_UPLOAD"));
    }

    @Test
    public void adapterIsMandatoryAndCatalogSettingsAreAValidSource() throws Exception {
        JSONObject legacyOnly = new JSONObject()
            .put("backendApiBase", "https://legacy.example.invalid")
            .put("endpoints", new JSONObject().put("login", "/login"));
        BackendAdapter missing = BackendAdapter.from(legacyOnly);
        assertFalse(missing.isSupported());
        assertTrue(missing.missingForLogin().contains("backendAdapter"));

        JSONObject settings = new JSONObject().put("backendAdapter", sharedFixture());
        BackendAdapter catalog = BackendAdapter.from(new JSONObject(), settings);
        assertTrue(catalog.parseErrors.toString(), catalog.isSupported());
    }

    @Test
    public void sessionSignalsComeFromAdapterAuth() throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("auth")
            .put("sessionInvalidHttpStatuses", new JSONArray().put(419))
            .put("sessionInvalidCodes", new JSONArray().put("SESSION-DEMO"))
            .put("sessionInvalidMessagePatterns", new JSONArray().put("sign in again"));
        JSONObject config = new JSONObject()
            .put("backendAdapter", fixture)
            .put("sessionInvalidCodes", new JSONArray().put("IGNORED-LEGACY"));
        BackendAdapter adapter = BackendAdapter.from(config);

        assertTrue(BackendSessionErrors.isInvalidApiCode(
            "SESSION-DEMO", adapter.sessionInvalidPolicy));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(
            419, adapter.sessionInvalidPolicy));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(
            401, adapter.sessionInvalidPolicy));
        assertFalse(BackendSessionErrors.isInvalidApiCode(
            "IGNORED-LEGACY", adapter.sessionInvalidPolicy));
        assertTrue(BackendSessionErrors.isInvalidMessage(
            "Please SIGN IN AGAIN", adapter.sessionInvalidPolicy));
    }

    @Test
    public void enabledPrintingRequiresTheConfiguredTypeField() throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject printing = fixture.getJSONObject("printing");
        printing.put("enabled", true);
        printing.getJSONObject("online").put("values", new JSONArray().put("online"));
        printing.getJSONObject("values")
            .put("acceptedTypes", new JSONArray().put("label"))
            .put("printed", new JSONArray().put("done"))
            .put("failed", new JSONArray().put("failed"))
            .put("ongoing", new JSONArray().put("running"));
        printing.getJSONObject("fields").remove("type");

        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(false, false, false, true)
            .contains("backendAdapter.printing.fields.type"));
    }

    @Test
    public void printingTypeAndRetryPayloadFailClosedWhenTheirAllowlistKeysAreEmpty()
            throws Exception {
        BackendAdapter disabled = fixtureAdapter();
        assertFalse(disabled.printing.accepts(
            new JSONObject().put("type", "unexpected")));
        assertThrows(IllegalStateException.class,
            () -> disabled.printing.retryPayload(42L));

        JSONObject fixture = sharedFixture();
        JSONObject printing = fixture.getJSONObject("printing");
        printing.put("enabled", true);
        printing.getJSONObject("online").put("values", new JSONArray().put("online"));
        printing.getJSONObject("values")
            .put("acceptedTypes", new JSONArray().put("label"))
            .put("printed", new JSONArray().put("done"))
            .put("failed", new JSONArray().put("failed"))
            .put("ongoing", new JSONArray().put("running"));
        BackendAdapter enabled = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(enabled.missingForSubmit(false, false, false, true).toString(),
            enabled.missingForSubmit(false, false, false, true).isEmpty());
        assertTrue(enabled.printing.accepts(new JSONObject().put("type", "label")));
        assertFalse(enabled.printing.accepts(new JSONObject().put("type", "other")));
        assertEquals(42L, enabled.printing.retryPayload(42L).getLong("id"));
    }

    @Test
    public void codeMissingPrintJobsRequireThePanelOwnedReadOnlyCompatibilitySwitch()
            throws Exception {
        JSONObject fixture = sharedFixture();
        JSONObject printing = fixture.getJSONObject("printing");
        printing.put("enabled", true)
            .put("allowJobsArrayWhenCodeMissing", true);
        printing.getJSONObject("online").put("values", new JSONArray().put("online"));
        printing.getJSONObject("values")
            .put("acceptedTypes", new JSONArray().put("label"))
            .put("printed", new JSONArray().put("done"))
            .put("failed", new JSONArray().put("failed"))
            .put("ongoing", new JSONArray().put("running"));
        BackendAdapter enabled = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        JSONObject codeMissingJobs = new JSONObject().put("items", new JSONArray());
        assertTrue(enabled.printing.isJobsResponseSuccess(
            codeMissingJobs, enabled.response));
        assertFalse(enabled.printing.isJobsResponseSuccess(
            new JSONObject(codeMissingJobs.toString()).put(
                "message", "example rejection"),
            enabled.response));
        assertFalse(enabled.printing.isJobsResponseSuccess(
            new JSONObject(codeMissingJobs.toString()).put("status", "rejected"),
            enabled.response));

        printing.put("allowJobsArrayWhenCodeMissing", false);
        BackendAdapter strict = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertFalse(strict.printing.isJobsResponseSuccess(
            codeMissingJobs, strict.response));

        printing.put("allowJobsArrayWhenCodeMissing", "yes");
        BackendAdapter invalid = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        assertTrue(invalid.missingForSubmit(false, false, false, true)
            .contains("backendAdapter.printing.allowJobsArrayWhenCodeMissing"));
    }

    @Test
    public void bindsPanelDynamicCapabilityAndCompilesWithoutNetworkOrUi() throws Exception {
        BackendAdapter adapter = fixtureAdapter();
        ProfileWorkflow workflow = dynamicWorkflow("sample-template-detail-v1");

        assertTrue(workflow.dynamicPreviousStepErrors.toString(),
            workflow.dynamicPreviousStepErrors.isEmpty());
        assertTrue(adapter.missingForDynamicPreviousSteps(workflow).toString(),
            adapter.missingForDynamicPreviousSteps(workflow).isEmpty());

        BackendAdapter.DynamicPreviousStepConfig config = adapter.dynamicPreviousStepConfig(
            workflow.dynamicPreviousStepRecipes.get(0));
        assertEquals("id", config.templateDetailIdParam);
        assertEquals(7001, ((Number) config.templateId).intValue());
        assertEquals(7, ((Number) config.expectedStep).intValue());
        assertEquals("example-photo", config.sourceAliases.get("sample-evidence"));
        assertTrue(config.sourceKeys.contains("example-photo"));
        assertEquals(25L, config.delayAfterMs);
        assertEquals(0, config.sourceIndex);

        DynamicPreviousStepRules.CompiledPayload payload = config
            .compile(dynamicTemplateData(), "UNIT-SAMPLE-01")
            .materialize(new JSONObject().put("example-photo",
                "https://files.example.invalid/sample.jpg"));
        assertEquals("UNIT-SAMPLE-01", payload.data().getString("sample-serial-field"));
        assertEquals("https://files.example.invalid/sample.jpg",
            payload.data().getString("sample-photo-field"));
        assertEquals("sample-accepted",
            payload.data().getJSONObject("sample-choice-field").getString("code"));
    }

    @Test
    public void bindsAlternateLiveProviderWithoutNetworkOrUploadSideEffects() throws Exception {
        BackendAdapter adapter = fixtureAdapter();
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("sample-live-toggle", true);

        BackendAdapter.AlternateEntryDynamicOverrideConfig config =
            adapter.alternateEntryDynamicOverrideConfig(
                alternateLiveEntry(), alternateLiveTarget(), states);
        assertEquals("id", config.templateDetailIdParam);
        assertEquals(1, config.requests().size());
        assertEquals("sample-live-provider", config.requests().get(0).providerId);

        JSONObject overrides = config.resolve(new JSONObject()
            .put("sample-live-provider", alternateLiveTemplate()));
        JSONObject value = overrides.getJSONObject("sample-live-choice");
        assertEquals("sample-selected-option", value.getString("code"));
        assertEquals("Sample selected option", value.getString("label"));
        assertEquals(2, value.getInt("quantity"));
    }

    @Test
    public void malformedAlternateLiveResolverIsRejectedBeforeNetwork() throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("operations").getJSONObject("templateDetail")
            .getJSONObject("alternateEntryResolvers")
            .getJSONObject("sample-alternate-live-option-v1")
            .put("script", "sample");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("sample-live-toggle", true);
        try {
            adapter.alternateEntryDynamicOverrideConfig(
                alternateLiveEntry(), alternateLiveTarget(), states);
            throw new AssertionError("malformed alternate live resolver must fail closed");
        } catch (BackendAdapter.ConfigurationException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(
                "operations.templateDetail.alternateEntryResolvers"
                    + ".sample-alternate-live-option-v1"));
        }
    }

    @Test
    public void staticRecipesDoNotRequireDynamicCapability() throws Exception {
        JSONObject fixture = sharedFixture();
        fixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("recipeResolvers");
        fixture.getJSONObject("operations").getJSONObject("previousSteps")
            .remove("optionValueBuilders");
        fixture.getJSONObject("operations").remove("templateDetail");
        fixture.getJSONObject("endpoints").remove("templateDetail");
        BackendAdapter adapter = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(adapter.missingForSubmit(true, false, false, false).toString(),
            adapter.missingForSubmit(true, false, false, false).isEmpty());
        assertFalse(adapter.missingForDynamicPreviousSteps(
            dynamicWorkflow("sample-template-detail-v1")).isEmpty());
    }

    @Test
    public void unknownResolverMissingCapabilityAndUnknownDslFailClosed() throws Exception {
        BackendAdapter adapter = fixtureAdapter();
        List<String> unknownResolver = adapter.missingForDynamicPreviousSteps(
            dynamicWorkflow("missing-resolver"));
        assertTrue(unknownResolver.toString(), unknownResolver.contains(
            "backendAdapter.operations.previousSteps.recipeResolvers.missing-resolver"));

        JSONObject malformedFixture = sharedFixture();
        malformedFixture.getJSONObject("operations").getJSONObject("previousSteps")
            .getJSONObject("recipeResolvers")
            .getJSONObject("sample-template-detail-v1")
            .put("eval", "input.serial");
        BackendAdapter malformed = BackendAdapter.from(
            new JSONObject().put("backendAdapter", malformedFixture));
        List<String> malformedErrors = malformed.missingForDynamicPreviousSteps(
            dynamicWorkflow("sample-template-detail-v1"));
        assertTrue(malformedErrors.toString(), malformedErrors.contains(
            "backendAdapter.operations.previousSteps.recipeResolvers."
                + "sample-template-detail-v1.eval"));
        assertTrue(malformed.missingForSubmit(true, false, false, false).toString(),
            malformed.missingForSubmit(true, false, false, false).isEmpty());

        JSONObject missingFixture = sharedFixture();
        missingFixture.getJSONObject("endpoints").remove("templateDetail");
        missingFixture.getJSONObject("operations").getJSONObject("templateDetail")
            .remove("idParam");
        missingFixture.getJSONObject("fields").getJSONObject("formField").remove("visible");
        BackendAdapter missing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", missingFixture));
        List<String> missingErrors = missing.missingForDynamicPreviousSteps(
            dynamicWorkflow("sample-template-detail-v1"));
        assertTrue(missingErrors.toString(), missingErrors.contains(
            "backendAdapter.endpoints.templateDetail"));
        assertTrue(missingErrors.toString(), missingErrors.contains(
            "backendAdapter.operations.templateDetail.idParam"));
        assertTrue(missingErrors.toString(), missingErrors.contains(
            "backendAdapter.fields.formField.visible"));
    }

    @Test
    public void resolvesRelativeAndAbsoluteEndpointsWithoutChangingTheContract() throws Exception {
        assertEquals("https://backend.example.invalid/api/entries",
            BackendAdapter.resolveEndpointUrl(
                "https://backend.example.invalid/api/", "/entries").toExternalForm());
        assertEquals("https://other.example.invalid/v2/entries",
            BackendAdapter.resolveEndpointUrl(
                "https://backend.example.invalid/api", "https://other.example.invalid/v2/entries")
                .toExternalForm());
        assertEquals("https://other.example.invalid/v2/entries?fixed=1&page=2#result",
            BackendAdapter.resolveEndpointUrl(
                "https://backend.example.invalid/api",
                "https://other.example.invalid/v2/entries?fixed=1#result",
                "page=2").toExternalForm());
    }
}
