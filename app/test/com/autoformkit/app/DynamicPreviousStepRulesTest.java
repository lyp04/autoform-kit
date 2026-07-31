package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DynamicPreviousStepRulesTest {
    @Test
    public void compilesBeforeUploadAndMaterializesFictionalPayload() throws Exception {
        DynamicPreviousStepRules.CompiledPlan plan = compile(template(), resolver(), builders());

        assertEquals(7001, ((Number) plan.templateId()).intValue());
        assertEquals(17, ((Number) plan.warehouseId()).intValue());
        assertEquals(23L, plan.delayAfterMs());

        JSONObject uploads = new JSONObject().put("sample-photo-source",
            new JSONArray().put("https://files.example.invalid/one")
                .put("https://files.example.invalid/two"));
        DynamicPreviousStepRules.CompiledPayload payload = plan.materialize(uploads);
        JSONObject data = payload.data();
        assertEquals("UNIT-FICTION-01", data.getString("field-serial"));
        assertEquals("https://files.example.invalid/one,https://files.example.invalid/two",
            data.getString("field-photo"));
        assertEquals("literal-priority", data.getString("field-choice"));

        data.put("field-serial", "mutated");
        assertEquals("UNIT-FICTION-01",
            plan.materialize(uploads).data().getString("field-serial"));
    }

    @Test
    public void optionSelectorsUseConfiguredPriorityAndBackendOrder() throws Exception {
        JSONObject firstOnly = resolver();
        JSONObject action = firstOnly.getJSONArray("rules").getJSONObject(2)
            .getJSONObject("action");
        action.getJSONArray("optionSelectors").remove(0);

        JSONObject data = compile(template(), firstOnly, builders())
            .materialize(upload()).data();
        JSONObject choice = data.getJSONObject("field-choice");
        assertEquals("ordinary-first", choice.getString("code"));
        assertEquals("Ordinary", choice.getString("label"));
        assertEquals(2, choice.getInt("quantity"));
    }

    @Test
    public void presentBuilderUsesHasKeySemanticsEvenForEmptyValue() throws Exception {
        JSONObject live = template();
        live.getJSONArray("fields").getJSONObject(2).getJSONArray("options")
            .getJSONObject(0).put("value", "");
        JSONObject resolver = resolver();
        resolver.getJSONArray("rules").getJSONObject(2).getJSONObject("action")
            .getJSONArray("optionSelectors").remove(0);
        resolver.getJSONArray("rules").getJSONObject(2).getJSONObject("action")
            .put("valueBuilder", "sample-present");
        JSONObject builders = builders().put("sample-present", new JSONObject()
            .put("type", "present")
            .put("path", "option.id")
            .put("fallbackIfMissing", "must-not-be-used"));

        assertEquals("", compile(live, resolver, builders)
            .materialize(upload()).data().getString("field-choice"));
    }

    @Test
    public void fieldSearchTextIncludesExplicitCanonicalId() throws Exception {
        JSONObject resolver = resolver();
        resolver.put("searchTextAttributes", new JSONArray().put("id"));
        resolver.getJSONArray("kindSelectors").getJSONObject(0)
            .put("selector", selector("searchText", "equalsAny",
                new JSONArray().put("field-serial"), true));

        JSONObject data = compile(template(), resolver, builders())
            .materialize(upload()).data();
        assertEquals("UNIT-FICTION-01", data.getString("field-serial"));
    }

    @Test
    public void secondStepCanExplicitlyCompileWithoutPhotoSource() throws Exception {
        JSONObject live = template();
        live.getJSONArray("fields").remove(1);
        JSONObject noPhotoResolver = resolver();
        noPhotoResolver.getJSONArray("kindSelectors").remove(1);
        noPhotoResolver.getJSONArray("rules").remove(1);
        JSONObject noPhotoRecipe = recipe().put("sources", new JSONObject());

        DynamicPreviousStepRules.CompiledPlan plan = DynamicPreviousStepRules.compile(
            live, noPhotoResolver, builders(), mapping(), noPhotoRecipe, "UNIT-FICTION-01");
        JSONObject data = plan.materialize(new JSONObject()).data();

        assertEquals("UNIT-FICTION-01", data.getString("field-serial"));
        assertTrue(!data.has("field-photo"));
        assertEquals("literal-priority", data.getString("field-choice"));
    }

    @Test
    public void ambiguousMissingUnknownRequiredAndConflictingFieldsFailClosed()
            throws Exception {
        JSONObject ambiguous = template();
        ambiguous.getJSONArray("fields").put(field("field-serial-two", "sample-serial",
            true, true, new JSONArray()));
        assertRejected(ambiguous, resolver(), builders(), "ambiguous");

        JSONObject missing = template();
        missing.getJSONArray("fields").remove(1);
        assertRejected(missing, resolver(), builders(), "matched no values");

        JSONObject unknown = template();
        unknown.getJSONArray("fields").put(field("field-unknown", "sample-unknown",
            true, true, new JSONArray()));
        assertRejected(unknown, resolver(), builders(), "unknown required visible field");

        JSONObject conflict = resolver();
        conflict.getJSONArray("rules").put(new JSONObject(
            conflict.getJSONArray("rules").getJSONObject(0).toString()));
        assertRejected(template(), conflict, builders(), "output field conflict");
    }

    @Test
    public void identityMismatchAndUnknownDslFailClosed() throws Exception {
        JSONObject mismatched = recipe();
        mismatched.put("templateId", 7002);
        assertRejected(template(), resolver(), builders(), mismatched,
            "templateId does not match");

        JSONObject unknown = resolver().put("eval", "input.serial");
        assertRejected(template(), unknown, builders(), "unknown key eval");

        JSONObject badBuilder = builders();
        badBuilder.getJSONObject("sample-option-object").put("script", "return option.id");
        assertRejected(template(), resolver(), badBuilder, "unknown key script");
    }

    @Test
    public void maliciouslyLargeBackendArraysFailBeforeSelection() throws Exception {
        JSONObject live = template();
        JSONArray fields = new JSONArray();
        for (int index = 0; index < 257; index++) {
            fields.put(field("field-" + index, "sample-unknown", false, true,
                new JSONArray()));
        }
        live.put("fields", fields);
        assertRejected(live, resolver(), builders(), "too large");
    }

    private static DynamicPreviousStepRules.CompiledPlan compile(
            JSONObject template, JSONObject resolver, JSONObject builders) throws Exception {
        return DynamicPreviousStepRules.compile(template, resolver, builders, mapping(), recipe(),
            "UNIT-FICTION-01");
    }

    private static JSONObject upload() throws Exception {
        return new JSONObject().put("sample-photo-source",
            "https://files.example.invalid/only");
    }

    private static JSONObject recipe() throws Exception {
        return new JSONObject()
            .put("templateId", 7001)
            .put("mode", "template_detail")
            .put("resolverId", "sample-template-detail-v1")
            .put("expectedStep", 7)
            .put("sources", new JSONObject()
                .put("sample-evidence", "sample-photo-source"))
            .put("delayAfterMs", 23);
    }

    private static JSONObject mapping() throws Exception {
        return new JSONObject()
            .put("template", new JSONObject()
                .put("id", "id")
                .put("name", "name")
                .put("sku", "sku")
                .put("step", "step")
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

    private static JSONObject template() throws Exception {
        return new JSONObject()
            .put("id", 7001)
            .put("name", "Fictional template")
            .put("step", 7)
            .put("warehouseId", 17)
            .put("sku", "SAMPLE-SKU")
            .put("fields", new JSONArray()
                .put(field("field-serial", "sample-serial", true, true, new JSONArray()))
                .put(field("field-photo", "sample-photo", true, true, new JSONArray()))
                .put(field("field-choice", "sample-choice", true, true,
                    new JSONArray()
                        .put(option("ordinary-first", "Ordinary", 2))
                        .put(option("priority-second", "Priority choice", 3))))
                .put(field("field-optional", "sample-unknown", false, true,
                    new JSONArray())));
    }

    private static JSONObject field(String id, String type, boolean required,
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

    private static JSONObject option(String value, String label, int quantity) throws Exception {
        return new JSONObject()
            .put("value", value)
            .put("label", label)
            .put("englishLabel", label)
            .put("quantity", quantity);
    }

    private static JSONObject resolver() throws Exception {
        return new JSONObject()
            .put("version", 1)
            .put("identity", new JSONObject()
                .put("templateId", present("template.id", 0))
                .put("expectedStep", present("template.step", 0))
                .put("warehouseId", present("template.warehouseId", 0))
                .put("sku", new JSONObject().put("type", "firstNonEmpty")
                    .put("paths", new JSONArray().put("template.sku"))))
            .put("searchTextAttributes", new JSONArray()
                .put("title").put("englishTitle").put("typeName"))
            .put("optionSearchTextAttributes", new JSONArray()
                .put("title").put("englishTitle").put("id"))
            .put("kindSelectors", new JSONArray()
                .put(kind("sample-serial"))
                .put(kind("sample-photo"))
                .put(kind("sample-choice")))
            .put("rules", new JSONArray()
                .put(rule("sample-serial", "exactly_one",
                    new JSONObject().put("type", "serial")))
                .put(rule("sample-photo", "exactly_one", new JSONObject()
                    .put("type", "photo")
                    .put("source", "sample-evidence")
                    .put("joinWith", ",")))
                .put(rule("sample-choice", "first_in_backend_order", new JSONObject()
                    .put("type", "fixedOption")
                    .put("optionSelectors", new JSONArray()
                        .put(new JSONObject()
                            .put("selector", selector("searchText", "containsAny",
                                new JSONArray().put("priority"), false))
                            .put("cardinality", "exactly_one")
                            .put("literalOverride", "literal-priority"))
                        .put(new JSONObject()
                            .put("selector", presentSelector("title"))
                            .put("cardinality", "first_in_backend_order")))
                    .put("valueBuilder", "sample-option-object")
                    .put("onNoMatch", "reject"))));
    }

    private static JSONObject builders() throws Exception {
        return new JSONObject().put("sample-option-object", new JSONObject()
            .put("type", "object")
            .put("members", new JSONObject()
                .put("code", present("option.id", "fallback-code"))
                .put("label", new JSONObject().put("type", "firstNonEmpty")
                    .put("paths", new JSONArray()
                        .put("option.title").put("option.englishTitle")))
                .put("quantity", new JSONObject().put("type", "integer")
                    .put("path", "option.quantity").put("default", 1))));
    }

    private static JSONObject present(String path, Object fallback) throws Exception {
        return new JSONObject().put("type", "present").put("path", path)
            .put("fallbackIfMissing", fallback);
    }

    private static JSONObject kind(String type) throws Exception {
        return new JSONObject().put("kind", type)
            .put("selector", selector("type", "equalsAny",
                new JSONArray().put(type), true));
    }

    private static JSONObject rule(String kind, String cardinality, JSONObject action)
            throws Exception {
        return new JSONObject()
            .put("selector", selector("kind", "equalsAny",
                new JSONArray().put(kind), true))
            .put("cardinality", cardinality)
            .put("action", action);
    }

    private static JSONObject presentSelector(String attribute) throws Exception {
        return new JSONObject().put("allOf", new JSONArray().put(new JSONObject()
            .put("attribute", attribute).put("present", true).put("caseSensitive", true)));
    }

    private static JSONObject selector(String attribute, String operator, JSONArray values,
                                       boolean caseSensitive) throws Exception {
        return new JSONObject().put("allOf", new JSONArray().put(new JSONObject()
            .put("attribute", attribute)
            .put(operator, values)
            .put("caseSensitive", caseSensitive)));
    }

    private static void assertRejected(JSONObject template, JSONObject resolver,
                                       JSONObject builders, String expected) throws Exception {
        assertRejected(template, resolver, builders, recipe(), expected);
    }

    private static void assertRejected(JSONObject template, JSONObject resolver,
                                       JSONObject builders, JSONObject recipe,
                                       String expected) throws Exception {
        try {
            DynamicPreviousStepRules.compile(template, resolver, builders, mapping(), recipe,
                "UNIT-FICTION-01");
            throw new AssertionError("dynamic recipe must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
