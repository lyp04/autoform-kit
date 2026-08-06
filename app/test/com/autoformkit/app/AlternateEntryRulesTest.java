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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AlternateEntryRulesTest {
    private static JSONObject publicSeed() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/assets/form-profiles.seed.json"),
            cwd.resolve("assets/form-profiles.seed.json")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("bundled public seed not found from " + cwd);
    }

    private static JSONObject source() throws Exception {
        return new JSONObject().put("id", "sample-source");
    }

    private static JSONObject target() throws Exception {
        return new JSONObject()
            .put("id", "sample-hidden-target")
            .put("pickerVisible", false)
            .put("template", new JSONObject()
                .put("id", 4101)
                .put("warehouseId", 7)
                .put("sku", "SAMPLE-HIDDEN"))
            .put("snFields", new JSONObject().put("primary", "sample-serial"))
            .put("gradeMap", new JSONObject().put("sample-ready", new JSONObject()
                .put("field", "sample-result")
                .put("value", "READY")))
            .put("photoSlots", new JSONArray()
                .put(new JSONObject().put("field", "sample-photo-main"))
                .put(new JSONObject().put("field", "sample-photo-copy")))
            .put("conditionalFields", new JSONArray()
                .put(new JSONObject().put("field", "sample-note"))
                .put(new JSONObject().put("field", "sample-dynamic")))
            .put("operationFields", new JSONArray()
                .put(new JSONObject().put("field", "sample-mode").put("value", "BASE")))
            .put("choiceFields", new JSONArray()
                .put(new JSONObject().put("field", "sample-choice")
                    .put("value", "VISIBLE").put("visible", true))
                .put(new JSONObject().put("field", "sample-hidden-choice")
                    .put("value", "HIDDEN").put("visible", false)));
    }

    private static JSONObject config() throws Exception {
        return new JSONObject()
            .put("id", "sample-entry")
            .put("title", "Sample alternate entry")
            .put("titleI18n", new JSONObject().put("es", "Entrada de ejemplo"))
            .put("targetProfileId", "sample-hidden-target")
            .put("identifierRole", "primary")
            .put("resultKey", "sample-ready")
            .put("photoTargetFields", new JSONArray().put("sample-photo-main"))
            .put("joinWith", "|")
            .put("minPhotos", 1)
            .put("maxPhotos", 3)
            .put("uploadNameTemplate", "{identifier}-alternate-entry-{index}.jpg")
            .put("scanner", new JSONObject().put("applyExpectedLengthTo",
                new JSONArray().put("ocr").put("barcode")))
            .put("submissionRetry", new JSONObject()
                .put("maxAttempts", 3)
                .put("retryDelayMs", 4000))
            .put("dataOverrides", new JSONObject().put("sample-note", "CONFIGURED"))
            .put("dynamicOverrideFields", new JSONArray())
            .put("dynamicOverrideProviders", new JSONArray())
            .put("toggles", new JSONArray().put(new JSONObject()
                .put("key", "sample-option")
                .put("label", "Sample option")
                .put("labelI18n", new JSONObject().put("es", "Opción de ejemplo"))
                .put("default", false)
                .put("retainUntilExit", true)
                .put("dataOverrides", new JSONObject().put("sample-mode", "ENABLED"))))
            .put("flags", new JSONObject()
                .put("duplicateCheck", false)
                .put("previousSteps", false)
                .put("printing", false));
    }

    private static JSONArray catalog(JSONObject target) throws Exception {
        return new JSONArray().put(source()).put(target);
    }

    @Test
    public void publicSeedUsesDisabledEnvelopeAndReturnsNoEntries() throws Exception {
        JSONArray profiles = publicSeed().getJSONArray("profiles");
        assertTrue(profiles.length() > 0);
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject workflow = profiles.getJSONObject(index).getJSONObject("workflow");
            assertEquals(0, AlternateEntryRules.configuredEntries(workflow).length());
        }
    }

    @Test
    public void configuredEnvelopeReturnsDefensiveEntryCopies() throws Exception {
        JSONObject originalEntry = config();
        JSONObject workflow = new JSONObject().put("alternateEntries", new JSONObject()
            .put("enabled", true)
            .put("entries", new JSONArray().put(originalEntry)));

        JSONArray parsed = AlternateEntryRules.configuredEntries(workflow);
        assertEquals(1, parsed.length());
        assertEquals("sample-entry", parsed.getJSONObject(0).getString("id"));

        originalEntry.put("id", "mutated-source");
        assertEquals("sample-entry", parsed.getJSONObject(0).getString("id"));
        parsed.getJSONObject(0).put("title", "mutated-result");
        assertEquals("Sample alternate entry",
            workflow.getJSONObject("alternateEntries").getJSONArray("entries")
                .getJSONObject(0).getString("title"));
    }

    @Test
    public void absentEnvelopeIsEmptyButLegacyArrayAndWrongTypesFailClosed()
            throws Exception {
        assertEquals(0, AlternateEntryRules.configuredEntries(new JSONObject()).length());
        assertEquals(0, AlternateEntryRules.configuredEntries(null).length());

        JSONObject legacyArray = new JSONObject().put("alternateEntries", new JSONArray());
        assertRejected("must be an object", () ->
            AlternateEntryRules.configuredEntries(legacyArray));

        JSONObject stringBoolean = alternateEnvelope("false", new JSONArray());
        assertRejected("enabled must be a boolean", () ->
            AlternateEntryRules.configuredEntries(stringBoolean));

        JSONObject stringEntries = new JSONObject().put("alternateEntries", new JSONObject()
            .put("enabled", true).put("entries", "not-an-array"));
        assertRejected("entries must be an array", () ->
            AlternateEntryRules.configuredEntries(stringEntries));

        JSONObject nonObjectEntry = alternateEnvelope(true, new JSONArray().put("entry"));
        assertRejected("entries[0] must be an object", () ->
            AlternateEntryRules.configuredEntries(nonObjectEntry));
    }

    @Test
    public void unknownKeysAndContradictoryEnabledStateFailClosed() throws Exception {
        JSONObject unknown = alternateEnvelope(false, new JSONArray());
        unknown.getJSONObject("alternateEntries").put("unexpected", true);
        assertRejected("contains unknown field unexpected", () ->
            AlternateEntryRules.configuredEntries(unknown));

        JSONObject disabledWithEntry = alternateEnvelope(false,
            new JSONArray().put(config()));
        assertRejected("must be empty when disabled", () ->
            AlternateEntryRules.configuredEntries(disabledWithEntry));

        JSONObject enabledWithoutEntry = alternateEnvelope(true, new JSONArray());
        assertRejected("must not be empty when enabled", () ->
            AlternateEntryRules.configuredEntries(enabledWithoutEntry));
    }

    private static JSONObject alternateEnvelope(Object enabled, JSONArray entries)
            throws Exception {
        return new JSONObject().put("alternateEntries", new JSONObject()
            .put("enabled", enabled).put("entries", entries));
    }

    @Test
    public void buildsOnlyExplicitTargetPayloadAndIdentity() throws Exception {
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("sample-option", true);
        AlternateEntryRules.Resolution resolved = AlternateEntryRules.resolve(
            source(), catalog(target()), config(), "SN-SAMPLE-001",
            Arrays.asList("https://example.invalid/a", "https://example.invalid/b"),
            states, new JSONObject());

        assertEquals("sample-hidden-target", resolved.targetProfile.getString("id"));
        assertEquals(4101, ((Number) resolved.identity.templateId).intValue());
        assertEquals(7, ((Number) resolved.identity.warehouseId).intValue());
        assertEquals("SAMPLE-HIDDEN", resolved.identity.sku);
        assertEquals("SN-SAMPLE-001", resolved.data.getString("sample-serial"));
        assertEquals("READY", resolved.data.getString("sample-result"));
        assertEquals("VISIBLE", resolved.data.getString("sample-choice"));
        assertFalse(resolved.data.has("sample-hidden-choice"));
        assertEquals("ENABLED", resolved.data.getString("sample-mode"));
        assertEquals("CONFIGURED", resolved.data.getString("sample-note"));
        assertFalse(resolved.data.has("sample-dynamic"));
        assertEquals("https://example.invalid/a|https://example.invalid/b",
            resolved.data.getString("sample-photo-main"));
        assertFalse(resolved.flags.duplicateCheck);
        assertFalse(resolved.flags.previousSteps);
        assertFalse(resolved.flags.printing);
        assertEquals(3, resolved.submissionRetry.maxAttempts);
        assertEquals(4000L, resolved.submissionRetry.retryDelayMs);
    }

    @Test
    public void canonicalResultFieldIsBoundOnceAndNormalResolutionSucceeds()
            throws Exception {
        AlternateEntryRules.Resolution resolved = AlternateEntryRules.resolve(
            source(), catalog(target()), config(), "SN-SAMPLE-RESULT",
            onePhoto(), Collections.emptyMap(), new JSONObject());

        // putBase deliberately rejects duplicate ownership. Rebinding the result field while
        // constructing the canonical payload therefore makes this ordinary resolution throw.
        assertEquals("READY", resolved.data.getString("sample-result"));
    }

    @Test
    public void explicitSubmissionRetryPolicyIsBoundedAndLegacyAbsenceDoesNotRetry()
            throws Exception {
        AlternateEntryRules.Resolution configured = AlternateEntryRules.resolve(
            source(), catalog(target()), config(), "SN-SAMPLE-RETRY",
            onePhoto(), Collections.emptyMap(), null);
        assertEquals(3, configured.submissionRetry.maxAttempts);
        assertEquals(4000L, configured.submissionRetry.retryDelayMs);

        JSONObject absent = config();
        absent.remove("submissionRetry");
        AlternateEntryRules.Resolution legacy = AlternateEntryRules.resolve(
            source(), catalog(target()), absent, "SN-SAMPLE-LEGACY",
            onePhoto(), Collections.emptyMap(), null);
        assertEquals(1, legacy.submissionRetry.maxAttempts);
        assertEquals(0L, legacy.submissionRetry.retryDelayMs);

        JSONObject tooMany = config();
        tooMany.getJSONObject("submissionRetry").put("maxAttempts", 11);
        assertRejected("maxAttempts must be from 1 to 10", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), tooMany,
                "SN-SAMPLE-LIMIT", onePhoto(), Collections.emptyMap(), null));

        JSONObject unknown = config();
        unknown.getJSONObject("submissionRetry").put("unknown", true);
        assertRejected("submissionRetry contains unknown field unknown", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), unknown,
                "SN-SAMPLE-UNKNOWN", onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void explicitlyDeclaredPhotoTargetsReceiveTheSameJoinedMultiPhotoValue()
            throws Exception {
        JSONObject entry = config().put("photoTargetFields", new JSONArray()
            .put("sample-photo-main").put("sample-photo-copy"));
        AlternateEntryRules.Resolution resolved = AlternateEntryRules.resolve(
            source(), catalog(target()), entry, "SN-SAMPLE-002",
            Arrays.asList("https://example.invalid/one", "https://example.invalid/two"),
            Collections.emptyMap(), null);

        String expected = "https://example.invalid/one|https://example.invalid/two";
        assertEquals(expected, resolved.data.getString("sample-photo-main"));
        assertEquals(expected, resolved.data.getString("sample-photo-copy"));
    }

    @Test
    public void toggleDefaultsAndRetainUntilExitPolicyRemainAvailableToFutureUi()
            throws Exception {
        JSONObject toggle = config().getJSONArray("toggles").getJSONObject(0)
            .put("default", true);
        JSONObject entry = config().put("toggles", new JSONArray().put(toggle));
        AlternateEntryRules.Resolution resolved = AlternateEntryRules.resolve(
            source(), catalog(target()), entry, "SN-SAMPLE-003",
            Collections.singletonList("https://example.invalid/photo"),
            Collections.emptyMap(), null);

        assertEquals(1, resolved.togglePolicies.size());
        AlternateEntryRules.TogglePolicy policy = resolved.togglePolicies.get(0);
        assertEquals("sample-option", policy.key);
        assertEquals("Sample option", policy.localizedLabel("en"));
        assertEquals("Opción de ejemplo", policy.localizedLabel("es"));
        assertTrue(policy.defaultValue);
        assertTrue(policy.retainUntilExit);
        assertTrue(resolved.effectiveToggleStates.get("sample-option"));
        assertEquals("ENABLED", resolved.data.getString("sample-mode"));
    }

    @Test
    public void missingDuplicateOrVisibleTargetFailsClosed() throws Exception {
        assertRejected("target profile is missing", () -> AlternateEntryRules.resolve(
            source(), new JSONArray().put(source()), config(), "SN-SAMPLE-004",
            onePhoto(), Collections.emptyMap(), null));

        assertRejected("target profile id is not unique", () -> AlternateEntryRules.resolve(
            source(), new JSONArray().put(target()).put(target()), config(), "SN-SAMPLE-004",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject visible = target().put("pickerVisible", true);
        assertRejected("pickerVisible=false", () -> AlternateEntryRules.resolve(
            source(), catalog(visible), config(), "SN-SAMPLE-004",
            onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void preflightTargetResolutionIsUniqueHiddenAndDefensive() throws Exception {
        JSONObject original = target();
        JSONObject resolved = AlternateEntryRules.targetProfile(
            catalog(original), config());
        assertEquals("sample-hidden-target", resolved.getString("id"));
        resolved.put("id", "changed-copy");
        assertEquals("sample-hidden-target", original.getString("id"));

        assertRejected("target profile is missing", () ->
            AlternateEntryRules.targetProfile(new JSONArray().put(source()), config()));
        assertRejected("target profile id is not unique", () ->
            AlternateEntryRules.targetProfile(
                new JSONArray().put(target()).put(target()), config()));
        assertRejected("pickerVisible=false", () ->
            AlternateEntryRules.targetProfile(
                catalog(target().put("pickerVisible", true)), config()));
    }

    @Test
    public void toggleLabelsAndPhotoBoundsAreStrict() throws Exception {
        JSONObject missingLabel = config();
        missingLabel.getJSONArray("toggles").getJSONObject(0).remove("label");
        assertRejected("label is required", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), missingLabel, "SN-SAMPLE-BOUND-1",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject tooMany = config().put("minPhotos", 3).put("maxPhotos", 2);
        assertRejected("photo bounds are invalid", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), tooMany, "SN-SAMPLE-BOUND-2",
            onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void zeroMaxPhotosExplicitlyPreservesLegacyUnlimitedCapture() throws Exception {
        List<String> photos = new ArrayList<>();
        for (int index = 1; index <= 25; index++) {
            photos.add("https://example.invalid/photo-" + index + ".jpg");
        }
        AlternateEntryRules.Resolution resolved = AlternateEntryRules.resolve(
            source(), catalog(target()), config().put("maxPhotos", 0),
            "SN-SAMPLE-UNLIMITED", photos, Collections.emptyMap(), null);
        assertEquals(25, resolved.data.getString("sample-photo-main").split("\\|", -1).length);
    }

    @Test
    public void entryScannerLengthScopeIsExplicitAndStrict() throws Exception {
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("ocr", "barcode")),
            AlternateEntryRules.expectedLengthSources(config()));
        assertTrue(AlternateEntryRules.allowedLengthSourcesOverride(config()).isEmpty());

        JSONObject allowed = config();
        allowed.getJSONObject("scanner").put("applyAllowedLengthsTo",
            new JSONArray().put("ocr").put("barcode").put("entered"));
        assertEquals(new java.util.LinkedHashSet<>(
                Arrays.asList("ocr", "barcode", "entered")),
            AlternateEntryRules.allowedLengthSourcesOverride(allowed));

        JSONObject missing = config();
        missing.remove("scanner");
        assertRejected("scanner is required", () ->
            AlternateEntryRules.expectedLengthSources(missing));

        JSONObject empty = config();
        empty.getJSONObject("scanner").put("applyExpectedLengthTo", new JSONArray());
        assertRejected("must contain 1 to 3", () ->
            AlternateEntryRules.expectedLengthSources(empty));

        JSONObject duplicate = config();
        duplicate.getJSONObject("scanner").put("applyExpectedLengthTo",
            new JSONArray().put("ocr").put("ocr"));
        assertRejected("duplicate ocr", () ->
            AlternateEntryRules.expectedLengthSources(duplicate));

        JSONObject unknown = config();
        unknown.getJSONObject("scanner").put("unexpected", true);
        assertRejected("unknown field unexpected", () ->
            AlternateEntryRules.expectedLengthSources(unknown));

        JSONObject emptyAllowed = config();
        emptyAllowed.getJSONObject("scanner").put(
            "applyAllowedLengthsTo", new JSONArray());
        assertRejected("applyAllowedLengthsTo must contain 1 to 3", () ->
            AlternateEntryRules.allowedLengthSourcesOverride(emptyAllowed));

        JSONObject duplicateAllowed = config();
        duplicateAllowed.getJSONObject("scanner").put("applyAllowedLengthsTo",
            new JSONArray().put("entered").put("entered"));
        assertRejected("applyAllowedLengthsTo contains duplicate entered", () ->
            AlternateEntryRules.allowedLengthSourcesOverride(duplicateAllowed));
    }

    @Test
    public void entryScannerInheritsOrOverridesAllowedScopeAndKeepsLegacyExpectedScope()
            throws Exception {
        JSONObject sourceScanner = new JSONObject()
            .put("expectedLength", 17)
            .put("allowedLengths", new JSONArray().put(16).put(17));

        SnScanRules.Policy mainPolicy = SnScanRules.Policy.from(sourceScanner);
        SnScanRules.Policy inheritedEntry = SnScanRules.Policy.from(
            AlternateEntryRules.applyScannerScopeOverrides(sourceScanner, config()));
        for (SnScanRules.Policy policy : Arrays.asList(mainPolicy, inheritedEntry)) {
            assertTrue(policy.valid);
            for (String sourceKind : Arrays.asList(
                    SnScanRules.SOURCE_OCR, SnScanRules.SOURCE_BARCODE,
                    SnScanRules.SOURCE_ENTERED)) {
                assertEquals(SnScanRules.Rejection.NONE,
                    policy.rejectionForSource(repeat('A', 16), sourceKind));
                assertEquals(SnScanRules.Rejection.NONE,
                    policy.rejectionForSource(repeat('A', 17), sourceKind));
                assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
                    policy.rejectionForSource(repeat('A', 15), sourceKind));
            }
        }

        JSONObject overrideEntry = config();
        overrideEntry.getJSONObject("scanner").put("applyAllowedLengthsTo",
            new JSONArray().put("ocr").put("barcode").put("entered"));
        JSONObject narrowSource = new JSONObject(sourceScanner.toString())
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr"));
        SnScanRules.Policy overridden = SnScanRules.Policy.from(
            AlternateEntryRules.applyScannerScopeOverrides(
                narrowSource, overrideEntry));
        assertTrue(overridden.valid);
        assertTrue(overridden.appliesAllowedLengthsTo(SnScanRules.SOURCE_ENTERED));
        assertEquals(SnScanRules.Rejection.NONE,
            overridden.enteredRejection(repeat('A', 16)));

        JSONObject legacySource = new JSONObject().put("expectedLength", 17);
        SnScanRules.Policy legacyEntry = SnScanRules.Policy.from(
            AlternateEntryRules.applyScannerScopeOverrides(legacySource, config()));
        assertTrue(legacyEntry.valid);
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            legacyEntry.captureRejection(repeat('A', 16)));
        assertEquals(SnScanRules.Rejection.NONE,
            legacyEntry.barcodeRejection(repeat('A', 17)));
        assertEquals(SnScanRules.Rejection.NONE,
            legacyEntry.enteredRejection(repeat('A', 16)));

        SnScanRules.Policy unconstrainedEntry = SnScanRules.Policy.from(
            AlternateEntryRules.applyScannerScopeOverrides(new JSONObject(), config()));
        assertTrue(unconstrainedEntry.valid);
        assertEquals(SnScanRules.Rejection.NONE,
            unconstrainedEntry.captureRejection(repeat('A', 15)));
    }

    @Test
    public void missingSubmitIdentityAndSameSourceTargetFailClosed() throws Exception {
        JSONObject missingIdentity = target();
        missingIdentity.getJSONObject("template").remove("sku");
        assertRejected("template.sku", () -> AlternateEntryRules.resolve(
            source(), catalog(missingIdentity), config(), "SN-SAMPLE-005",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject sameSource = new JSONObject(source().toString())
            .put("pickerVisible", false)
            .put("template", target().getJSONObject("template"))
            .put("snFields", target().getJSONObject("snFields"))
            .put("gradeMap", target().getJSONObject("gradeMap"))
            .put("photoSlots", target().getJSONArray("photoSlots"));
        JSONObject sameEntry = config().put("targetProfileId", "sample-source");
        assertRejected("source and target profiles must differ", () ->
            AlternateEntryRules.resolve(source(), catalog(sameSource), sameEntry,
                "SN-SAMPLE-005", onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void baseTargetFieldConflictFailsClosed() throws Exception {
        JSONObject conflicted = target();
        conflicted.getJSONArray("operationFields").put(new JSONObject()
            .put("field", "sample-result").put("value", "CONFLICT"));
        assertRejected("target field conflict", () -> AlternateEntryRules.resolve(
            source(), catalog(conflicted), config(), "SN-SAMPLE-006",
            onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void photoTargetsMustBeDeclaredUniqueAndWithinBounds() throws Exception {
        JSONObject undeclared = config().put("photoTargetFields",
            new JSONArray().put("sample-unknown-photo"));
        assertRejected("not declared by target profile", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), undeclared, "SN-SAMPLE-007",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject duplicate = config().put("photoTargetFields", new JSONArray()
            .put("sample-photo-main").put("sample-photo-main"));
        assertRejected("duplicate photo target field", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), duplicate, "SN-SAMPLE-007",
            onePhoto(), Collections.emptyMap(), null));

        assertRejected("outside configured bounds", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), config().put("minPhotos", 2), "SN-SAMPLE-007",
            onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void serialIdentityAndRuntimeOverridesAreStrictlyProviderBound() throws Exception {
        JSONObject serialOverride = config().put("dataOverrides",
            new JSONObject().put("sample-serial", "REPLACED"));
        assertRejected("cannot replace serial", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), serialOverride, "SN-SAMPLE-008",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject identityOverride = config().put("dataOverrides",
            new JSONObject().put("sku", "REPLACED"));
        assertRejected("cannot replace template identity", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), identityOverride, "SN-SAMPLE-008",
            onePhoto(), Collections.emptyMap(), null));

        assertRejected("runtime dynamic override fields do not match active providers", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), config(), "SN-SAMPLE-008",
                onePhoto(), Collections.emptyMap(),
                new JSONObject().put("sample-note", "NOT-WHITELISTED")));

        JSONObject configuredDynamic = config()
            .put("dynamicOverrideFields", new JSONArray().put("sample-dynamic"))
            .put("dynamicOverrideProviders", new JSONArray().put(new JSONObject()
                .put("id", "sample-live-provider")
                .put("triggerToggleKey", "sample-option")
                .put("templateId", 4101)
                .put("expectedStep", 2)
                .put("resolverId", "sample-live-resolver-v1")
                .put("outputField", "sample-dynamic")));
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        enabled.put("sample-option", true);
        AlternateEntryRules.Resolution uiPreflight =
            AlternateEntryRules.resolveForUiPreflight(
                source(), catalog(target()), configuredDynamic, "SN-SAMPLE-008",
                onePhoto(), enabled);
        assertTrue(uiPreflight.effectiveToggleStates.get("sample-option"));
        assertTrue(uiPreflight.data.has("sample-dynamic"));

        AlternateEntryRules.Resolution dynamic = AlternateEntryRules.resolve(
            source(), catalog(target()), configuredDynamic, "SN-SAMPLE-008",
            onePhoto(), enabled,
            new JSONObject().put("sample-dynamic", "SAMPLE_DYNAMIC_VALUE"));
        assertEquals("SAMPLE_DYNAMIC_VALUE", dynamic.data.getString("sample-dynamic"));

        AlternateEntryRules.Resolution inactive = AlternateEntryRules.resolve(
            source(), catalog(target()), configuredDynamic, "SN-SAMPLE-008",
            onePhoto(), Collections.emptyMap(), null);
        assertFalse(inactive.data.has("sample-dynamic"));

        assertRejected("runtime dynamic override fields do not match active providers", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), configuredDynamic,
                "SN-SAMPLE-008", onePhoto(), enabled, null));
        assertRejected("runtime dynamic override value must not be null", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), configuredDynamic,
                "SN-SAMPLE-008", onePhoto(), enabled,
                new JSONObject().put("sample-dynamic", JSONObject.NULL)));

        JSONObject missingDynamic = config();
        missingDynamic.remove("dynamicOverrideFields");
        assertRejected("dynamicOverrideFields must be an array", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), missingDynamic, "SN-SAMPLE-008",
            onePhoto(), Collections.emptyMap(), null));

        JSONObject missingProviders = config();
        missingProviders.remove("dynamicOverrideProviders");
        assertRejected("dynamicOverrideProviders must be an array", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), missingProviders,
                "SN-SAMPLE-008", onePhoto(), Collections.emptyMap(), null));
    }

    @Test
    public void uploadFilenameTemplateFormatsLegacyStyleSafely() throws Exception {
        assertEquals("SN-SAMPLE-010-alternate-entry-2.jpg",
            AlternateEntryRules.formatUploadName(config(), "SN-SAMPLE-010", 2));
        assertEquals("SN___1-alternate-entry-1.jpg",
            AlternateEntryRules.formatUploadName(config(), "SN/\" 1", 1));
    }

    @Test
    public void uploadFilenameTemplateRejectsMissingUnknownOrUnsafeParts() throws Exception {
        JSONObject missing = config();
        missing.remove("uploadNameTemplate");
        assertRejected("uploadNameTemplate is required", () ->
            AlternateEntryRules.formatUploadName(missing, "SN-SAMPLE", 1));

        assertRejected("must contain {identifier}", () ->
            AlternateEntryRules.formatUploadName(config().put("uploadNameTemplate",
                "alternate-entry-{index}.jpg"), "SN-SAMPLE", 1));
        assertRejected("must contain {index}", () ->
            AlternateEntryRules.formatUploadName(config().put("uploadNameTemplate",
                "{identifier}-alternate-entry.jpg"), "SN-SAMPLE", 1));
        assertRejected("may only use {identifier} and {index}", () ->
            AlternateEntryRules.formatUploadName(config().put("uploadNameTemplate",
                "{identifier}-{unknown}-{index}.jpg"), "SN-SAMPLE", 1));
        assertRejected("must not contain path separators", () ->
            AlternateEntryRules.formatUploadName(config().put("uploadNameTemplate",
                "folder/{identifier}-{index}.jpg"), "SN-SAMPLE", 1));
        assertRejected("index must be positive", () ->
            AlternateEntryRules.formatUploadName(config(), "SN-SAMPLE", 0));
    }

    @Test
    public void unknownEntryKeyFailsClosed() throws Exception {
        assertRejected("contains unknown field unexpected", () ->
            AlternateEntryRules.resolve(source(), catalog(target()),
                config().put("unexpected", true), "SN-SAMPLE-011", onePhoto(),
                Collections.emptyMap(), null));
    }

    @Test
    public void unknownToggleOrEnabledSafetyFlagFailsClosed() throws Exception {
        assertRejected("unknown toggle state", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), config(), "SN-SAMPLE-009", onePhoto(),
            Collections.singletonMap("sample-unknown-toggle", true), null));

        JSONObject unknownToggle = config();
        unknownToggle.getJSONArray("toggles").getJSONObject(0).put("unexpected", true);
        assertRejected("contains unknown field unexpected", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), unknownToggle,
                "SN-SAMPLE-009", onePhoto(), Collections.emptyMap(), null));

        JSONObject unknownFlag = config();
        unknownFlag.getJSONObject("flags").put("unexpected", false);
        assertRejected("contains unknown field unexpected", () ->
            AlternateEntryRules.resolve(source(), catalog(target()), unknownFlag,
                "SN-SAMPLE-009", onePhoto(), Collections.emptyMap(), null));

        JSONObject unsafe = config();
        unsafe.getJSONObject("flags").put("printing", true);
        assertRejected("flags must all be false", () -> AlternateEntryRules.resolve(
            source(), catalog(target()), unsafe, "SN-SAMPLE-009", onePhoto(),
            Collections.emptyMap(), null));
    }

    private static java.util.List<String> onePhoto() {
        return Collections.singletonList("https://example.invalid/photo");
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void assertRejected(String expected, ThrowingRunnable action)
            throws Exception {
        try {
            action.run();
            throw new AssertionError("alternate entry must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
