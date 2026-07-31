package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AlternateEntryDynamicOverrideRulesTest {
    private static JSONObject target() throws Exception {
        return new JSONObject()
            .put("id", "sample-hidden-target")
            .put("pickerVisible", false)
            .put("template", new JSONObject()
                .put("id", 7001)
                .put("step", 7)
                .put("warehouseId", 17)
                .put("sku", "SAMPLE-SKU"))
            .put("snFields", new JSONObject().put("primary", "sample-serial"))
            .put("gradeMap", new JSONObject().put("sample-ready", new JSONObject()
                .put("field", "sample-result").put("value", "SAMPLE_READY")))
            .put("photoSlots", new JSONArray()
                .put(new JSONObject().put("field", "sample-photo")))
            .put("conditionalFields", new JSONArray()
                .put(new JSONObject().put("field", "sample-live-choice"))
                .put(new JSONObject().put("field", "sample-static")));
    }

    private static JSONObject entry() throws Exception {
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

    private static JSONObject resolver() throws Exception {
        return new JSONObject()
            .put("version", 1)
            .put("searchTextAttributes", new JSONArray()
                .put("id").put("title").put("englishTitle"))
            .put("optionSearchTextAttributes", new JSONArray()
                .put("id").put("title").put("englishTitle"))
            .put("fieldSelector", selector("id", "sample-live-choice"))
            .put("optionSelector", selector("id", "sample-selected-option"))
            .put("valueBuilder", new JSONObject()
                .put("type", "object")
                .put("members", new JSONObject()
                    .put("code", new JSONObject().put("type", "present")
                        .put("path", "option.id").put("fallbackIfMissing", ""))
                    .put("label", new JSONObject().put("type", "firstNonEmpty")
                        .put("paths", new JSONArray()
                            .put("option.title").put("option.englishTitle")))
                    .put("quantity", new JSONObject().put("type", "integer")
                        .put("path", "option.quantity").put("default", 1))));
    }

    private static JSONObject resolverMap() throws Exception {
        return new JSONObject().put("sample-alternate-live-option-v1", resolver());
    }

    private static JSONObject mapping() throws Exception {
        return new JSONObject()
            .put("template", new JSONObject()
                .put("id", "id")
                .put("name", "name")
                .put("sku", "sku")
                .put("step", "processStep")
                .put("warehouseId", "warehouseId")
                .put("fieldList", "fields"))
            .put("formField", new JSONObject()
                .put("id", "id")
                .put("type", "type")
                .put("parentType", "parentType")
                .put("typeName", "typeName")
                .put("title", "title")
                .put("englishTitle", "englishTitle")
                .put("required", "required")
                .put("visible", "visible")
                .put("maxCount", "maxCount")
                .put("options", "options"))
            .put("option", new JSONObject()
                .put("value", "value")
                .put("label", "label")
                .put("englishLabel", "englishLabel")
                .put("quantity", "quantity"));
    }

    private static JSONObject liveTemplate() throws Exception {
        return new JSONObject()
            .put("id", 7001)
            .put("name", "Sample live template")
            .put("processStep", 7)
            .put("warehouseId", 17)
            .put("sku", "SAMPLE-SKU")
            .put("fields", new JSONArray().put(liveField(
                "sample-live-choice", "Sample live choice",
                new JSONArray().put(option(
                    "sample-selected-option", "Sample selected option", 2)))));
    }

    private static JSONObject liveField(String id, String title, JSONArray options)
            throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("type", "sample-choice")
            .put("parentType", "")
            .put("typeName", "Sample choice")
            .put("title", title)
            .put("englishTitle", title)
            .put("required", false)
            .put("visible", true)
            .put("maxCount", 1)
            .put("options", options);
    }

    private static JSONObject option(String id, String title, int quantity) throws Exception {
        return new JSONObject()
            .put("value", id)
            .put("label", title)
            .put("englishLabel", title)
            .put("quantity", quantity);
    }

    private static JSONObject selector(String attribute, Object expected) throws Exception {
        return new JSONObject().put("allOf", new JSONArray().put(new JSONObject()
            .put("attribute", attribute)
            .put("equalsAny", new JSONArray().put(expected))
            .put("caseSensitive", true)));
    }

    private static Map<String, Boolean> enabled() {
        Map<String, Boolean> states = new LinkedHashMap<>();
        states.put("sample-live-toggle", true);
        return states;
    }

    private static AlternateEntryDynamicOverrideRules.Plan compile(
            JSONObject entry, JSONObject target, Map<String, Boolean> states,
            JSONObject resolvers) throws Exception {
        return AlternateEntryDynamicOverrideRules.compile(
            entry, target, states, resolvers, mapping());
    }

    private static JSONObject oneLive(JSONObject live) throws Exception {
        return new JSONObject().put("sample-live-provider", live);
    }

    @Test
    public void enabledProviderResolvesOnePanelOwnedFieldBeforeSideEffects() throws Exception {
        AlternateEntryDynamicOverrideRules.Plan plan = compile(
            entry(), target(), enabled(), resolverMap());
        assertEquals(1, plan.requests().size());
        AlternateEntryDynamicOverrideRules.Request request = plan.requests().get(0);
        assertEquals("sample-live-provider", request.providerId);
        assertEquals(7001, ((Number) request.templateId).intValue());
        assertEquals(7, ((Number) request.expectedStep).intValue());
        assertEquals("sample-alternate-live-option-v1", request.resolverId);
        assertEquals("sample-live-choice", request.outputField);

        JSONObject overrides = plan.resolve(oneLive(liveTemplate()));
        JSONObject value = overrides.getJSONObject("sample-live-choice");
        assertEquals("sample-selected-option", value.getString("code"));
        assertEquals("Sample selected option", value.getString("label"));
        assertEquals(2, value.getInt("quantity"));
    }

    @Test
    public void disabledProviderProducesNoRequestOrRuntimeField() throws Exception {
        AlternateEntryDynamicOverrideRules.Plan plan = compile(
            entry(), target(), Collections.emptyMap(), resolverMap());
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.resolve(null).length());
        assertRejected("unexpected live template", () ->
            plan.resolve(oneLive(liveTemplate())));
    }

    @Test
    public void missingOrExtraLiveResponsesFailClosed() throws Exception {
        AlternateEntryDynamicOverrideRules.Plan plan = compile(
            entry(), target(), enabled(), resolverMap());
        assertRejected("missing live template", () -> plan.resolve(new JSONObject()));
        JSONObject extra = oneLive(liveTemplate()).put("sample-extra-provider", liveTemplate());
        assertRejected("unexpected live template", () -> plan.resolve(extra));
        assertRejected("must be an object", () ->
            plan.resolve(new JSONObject().put("sample-live-provider", "not-an-object")));
    }

    @Test
    public void everyLiveIdentityComponentMustMatchTheTarget() throws Exception {
        for (Object[] mutation : new Object[][]{
            {"id", 7002, "templateId"},
            {"processStep", 8, "expectedStep"},
            {"warehouseId", 18, "warehouseId"},
            {"sku", "OTHER-SAMPLE-SKU", "sku"}
        }) {
            AlternateEntryDynamicOverrideRules.Plan plan = compile(
                entry(), target(), enabled(), resolverMap());
            JSONObject live = liveTemplate().put((String) mutation[0], mutation[1]);
            assertRejected((String) mutation[2], () -> plan.resolve(oneLive(live)));
        }
    }

    @Test
    public void fieldAndOptionSelectorsRequireExactlyOneMatch() throws Exception {
        AlternateEntryDynamicOverrideRules.Plan normal = compile(
            entry(), target(), enabled(), resolverMap());
        JSONObject noField = liveTemplate().put("fields", new JSONArray().put(liveField(
            "sample-other-field", "Other field", new JSONArray().put(option(
                "sample-selected-option", "Sample selected option", 1)))));
        assertRejected("fieldSelector matched no values", () ->
            normal.resolve(oneLive(noField)));

        JSONObject fieldResolver = resolver();
        fieldResolver.put("fieldSelector", selector("title", "Repeated field"));
        JSONObject fields = liveTemplate().put("fields", new JSONArray()
            .put(liveField("sample-live-choice", "Repeated field",
                new JSONArray().put(option(
                    "sample-selected-option", "Sample selected option", 1))))
            .put(liveField("sample-other-field", "Repeated field",
                new JSONArray().put(option(
                    "sample-selected-option", "Sample selected option", 1)))));
        JSONObject fieldResolvers = new JSONObject()
            .put("sample-alternate-live-option-v1", fieldResolver);
        AlternateEntryDynamicOverrideRules.Plan ambiguousField = compile(
            entry(), target(), enabled(), fieldResolvers);
        assertRejected("fieldSelector is ambiguous", () ->
            ambiguousField.resolve(oneLive(fields)));

        JSONObject noOption = liveTemplate();
        noOption.getJSONArray("fields").getJSONObject(0)
            .put("options", new JSONArray().put(option(
                "sample-other-option", "Other option", 1)));
        assertRejected("optionSelector matched no values", () ->
            normal.resolve(oneLive(noOption)));

        JSONObject optionResolver = resolver();
        optionResolver.put("optionSelector", selector("title", "Repeated option"));
        JSONObject options = liveTemplate();
        options.getJSONArray("fields").getJSONObject(0).put("options", new JSONArray()
            .put(option("sample-selected-option", "Repeated option", 1))
            .put(option("sample-other-option", "Repeated option", 1)));
        JSONObject optionResolvers = new JSONObject()
            .put("sample-alternate-live-option-v1", optionResolver);
        AlternateEntryDynamicOverrideRules.Plan ambiguousOption = compile(
            entry(), target(), enabled(), optionResolvers);
        assertRejected("optionSelector is ambiguous", () ->
            ambiguousOption.resolve(oneLive(options)));
    }

    @Test
    public void unknownDslKeysMissingReferencesAndNullValuesFailClosed() throws Exception {
        JSONObject unknownProvider = entry();
        unknownProvider.getJSONArray("dynamicOverrideProviders")
            .getJSONObject(0).put("script", "sample");
        assertRejected("unknown key script", () -> compile(
            unknownProvider, target(), enabled(), resolverMap()));

        JSONObject unknownResolver = resolver().put("script", "sample");
        assertRejected("unknown key script", () -> compile(
            entry(), target(), enabled(), new JSONObject()
                .put("sample-alternate-live-option-v1", unknownResolver)));
        assertRejected("missing resolver", () -> compile(
            entry(), target(), enabled(), new JSONObject()));
        assertRejected("fieldMapping requires template, formField, and option", () ->
            AlternateEntryDynamicOverrideRules.compile(
                entry(), target(), enabled(), resolverMap(), new JSONObject()));

        JSONObject nullResolver = resolver();
        nullResolver.put("valueBuilder", new JSONObject()
            .put("type", "literal").put("value", JSONObject.NULL));
        AlternateEntryDynamicOverrideRules.Plan nullPlan = compile(
            entry(), target(), enabled(), new JSONObject()
                .put("sample-alternate-live-option-v1", nullResolver));
        assertRejected("returned null", () -> nullPlan.resolve(oneLive(liveTemplate())));
    }

    @Test
    public void outputAllowListAndProtectedOwnersFailClosed() throws Exception {
        JSONObject mismatch = entry();
        mismatch.put("dynamicOverrideFields", new JSONArray().put("sample-static"));
        assertRejected("not allow-listed", () -> compile(
            mismatch, target(), enabled(), resolverMap()));

        for (String protectedField : new String[]{
            "sample-serial", "sample-result", "sample-photo", "sku"
        }) {
            JSONObject protectedEntry = entry();
            protectedEntry.put("dynamicOverrideFields", new JSONArray().put(protectedField));
            protectedEntry.getJSONArray("dynamicOverrideProviders")
                .getJSONObject(0).put("outputField", protectedField);
            assertRejected("cannot replace", () -> compile(
                protectedEntry, target(), enabled(), resolverMap()));
        }

        JSONObject conflict = entry();
        conflict.put("dataOverrides", new JSONObject()
            .put("sample-live-choice", "SAMPLE_STATIC"));
        assertRejected("conflicts with a static or toggle override", () -> compile(
            conflict, target(), enabled(), resolverMap()));
    }

    @Test
    public void emptyOptionalIdentifierRolesAreIgnoredButPrimaryRemainsStrict()
            throws Exception {
        JSONObject legacyTarget = target();
        legacyTarget.getJSONObject("snFields")
            .put("secondary", "")
            .put("package", "");
        AlternateEntryDynamicOverrideRules.Plan plan = compile(
            entry(), legacyTarget, enabled(), resolverMap());
        assertEquals(1, plan.requests().size());
        assertEquals(1, plan.resolve(oneLive(liveTemplate())).length());

        JSONObject missingPrimary = target();
        missingPrimary.getJSONObject("snFields").put("primary", "");
        assertRejected("snFields.primary must be a non-empty trimmed string", () -> compile(
            entry(), missingPrimary, enabled(), resolverMap()));

        JSONObject whitespaceOptional = target();
        whitespaceOptional.getJSONObject("snFields").put("secondary", " ");
        assertRejected("snFields.secondary must be a non-empty trimmed string", () -> compile(
            entry(), whitespaceOptional, enabled(), resolverMap()));

        JSONObject wrongTypeOptional = target();
        wrongTypeOptional.getJSONObject("snFields").put("secondary", false);
        assertRejected("snFields.secondary must be a string", () -> compile(
            entry(), wrongTypeOptional, enabled(), resolverMap()));
    }

    @Test
    public void unknownToggleStateUnsafeIdsAndUnsafeIntegersFailClosed() throws Exception {
        Map<String, Boolean> unknownState = new LinkedHashMap<>();
        unknownState.put("sample-unknown-toggle", true);
        assertRejected("unknown toggle state", () -> compile(
            entry(), target(), unknownState, resolverMap()));

        JSONObject unsafeId = entry();
        unsafeId.getJSONArray("dynamicOverrideProviders")
            .getJSONObject(0).put("resolverId", "__proto__");
        assertRejected("safe bounded identifier", () -> compile(
            unsafeId, target(), enabled(), resolverMap()));

        JSONObject fractional = entry();
        fractional.getJSONArray("dynamicOverrideProviders")
            .getJSONObject(0).put("expectedStep", 7.5d);
        assertRejected("positive integer", () -> compile(
            fractional, target(), enabled(), resolverMap()));

        JSONObject unsafeInteger = entry();
        unsafeInteger.getJSONArray("dynamicOverrideProviders")
            .getJSONObject(0).put("templateId", 9_007_199_254_740_992L);
        assertRejected("positive integer", () -> compile(
            unsafeInteger, target(), enabled(), resolverMap()));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertRejected(String expected, ThrowingRunnable runnable)
            throws Exception {
        try {
            runnable.run();
            throw new AssertionError("alternate live override must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
