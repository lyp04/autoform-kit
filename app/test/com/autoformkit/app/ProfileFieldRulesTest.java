package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProfileFieldRulesTest {
    @Test
    public void primaryIdentifierFieldNeverFallsBack() throws Exception {
        assertEquals("", ProfileFieldRules.primaryIdentifierField(null));
        assertEquals("", ProfileFieldRules.primaryIdentifierField(new JSONObject()));
        assertEquals("", ProfileFieldRules.primaryIdentifierField(new JSONObject()
            .put("snFields", new JSONObject().put("primary", "  "))));
        assertEquals("example_serial", ProfileFieldRules.primaryIdentifierField(
            new JSONObject().put("snFields",
                new JSONObject().put("primary", " example_serial "))));
    }

    @Test
    public void requiredVisibleExtraFieldsCannotBeBlank() throws Exception {
        JSONObject profile = new JSONObject().put("snPlugins", new JSONArray()
            .put(new JSONObject().put("key", "primary").put("field", "serial")
                .put("required", true))
            .put(new JSONObject().put("key", "extra").put("field", "reference")
                .put("required", true))
            .put(new JSONObject().put("key", "hidden").put("field", "hidden_value")
                .put("required", true).put("visible", false)));

        assertEquals(Collections.singletonList("reference"),
            ProfileFieldRules.missingRequiredVisibleExtraFields(
                profile, Collections.emptyMap()));
        assertEquals(Collections.emptyList(),
            ProfileFieldRules.missingRequiredVisibleExtraFields(
                profile, Collections.singletonMap("reference", "EXAMPLE")));
    }

    @Test
    public void extraIdentifierPayloadIsAllowListedByVisiblePanelFields()
            throws Exception {
        JSONObject profile = new JSONObject().put("snPlugins", new JSONArray()
            .put(new JSONObject().put("key", "primary").put("field", "serial"))
            .put(new JSONObject().put("key", "secondary").put("field", "reference"))
            .put(new JSONObject().put("key", "visible-extra")
                .put("field", "operator_code"))
            .put(new JSONObject().put("key", "hidden-extra")
                .put("field", "hidden_code").put("visible", false)));
        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("operator_code", "VISIBLE");
        draft.put("hidden_code", "MUST-NOT-SEND");
        draft.put("serial", "MUST-NOT-OVERRIDE");
        draft.put("unknown_field", "MUST-NOT-SEND");

        assertEquals(Collections.singletonList("operator_code"),
            ProfileFieldRules.visibleExtraIdentifierFields(profile));
        assertEquals(Collections.singletonMap("operator_code", "VISIBLE"),
            ProfileFieldRules.boundVisibleExtraIdentifierValues(profile, draft));
        assertEquals(
            List.of("hidden_code", "serial", "unknown_field"),
            ProfileFieldRules.unexpectedExtraIdentifierFields(profile, draft));
    }

    @Test
    public void resultSelectionMustResolveInsideTheExactActiveProfile()
            throws Exception {
        JSONObject first = new JSONObject().put("gradeMap", new JSONObject()
            .put("shared-key", new JSONObject()
                .put("field", "first_result").put("value", "FIRST")));
        JSONObject second = new JSONObject().put("gradeMap", new JSONObject()
            .put("other-key", new JSONObject()
                .put("field", "second_result").put("value", "SECOND")));

        assertTrue(ProfileFieldRules.resultSelectionValid(first, "shared-key"));
        assertFalse(ProfileFieldRules.resultSelectionValid(second, "shared-key"));
        assertFalse(ProfileFieldRules.resultSelectionValid(first, " shared-key "));
        assertEquals("first_result",
            ProfileFieldRules.resultMapping(first, "shared-key").getString("field"));
        assertEquals("FIRST",
            ProfileFieldRules.resultMapping(first, "shared-key").getString("value"));
        assertTrue(ProfileFieldRules.resultSelectionValid(new JSONObject(), ""));
        assertFalse(ProfileFieldRules.resultSelectionValid(new JSONObject(), "stale"));
    }

    @Test
    public void requiredAndOptionalPhotoSlotsShareRuntimeOrder() throws Exception {
        JSONObject profile = new JSONObject()
            .put("photoSlots", new JSONArray()
                .put(new JSONObject().put("field", "required_photo")))
            .put("optionalSlots", new JSONArray()
                .put(new JSONObject().put("field", "optional_photo")));

        JSONArray slots = ProfileFieldRules.photoSlots(profile, true);
        assertEquals(2, slots.length());
        assertEquals("required_photo", slots.getJSONObject(0).getString("field"));
        assertEquals("optional_photo", slots.getJSONObject(1).getString("field"));
        assertEquals(1, ProfileFieldRules.photoSlots(profile, false).length());

        Map<String, List<String>> captured = new LinkedHashMap<>();
        captured.put("required_photo", Collections.singletonList("/required.jpg"));
        captured.put("optional_photo", Collections.singletonList("/optional.jpg"));
        captured.put("other_profile_photo", Collections.singletonList("/wrong.jpg"));
        assertEquals(List.of("optional_photo", "other_profile_photo"),
            ProfileFieldRules.unexpectedPhotoSlotFields(profile, false, captured));
        assertEquals(Collections.singletonList("other_profile_photo"),
            ProfileFieldRules.unexpectedPhotoSlotFields(profile, true, captured));
    }

    @Test
    public void conditionalFieldsPreserveLegacyAliasAndRejectDivergence() throws Exception {
        JSONObject legacyOnly = new JSONObject()
            .put("value", new JSONArray().put("fallback"))
            .put("perGrade", new JSONObject()
                .put("accepted", new JSONArray().put("legacy-value")));
        assertEquals("legacy-value", ((JSONArray) ProfileFieldRules.conditionalFieldValue(
            legacyOnly, "accepted")).getString(0));
        assertEquals("fallback", ((JSONArray) ProfileFieldRules.conditionalFieldValue(
            legacyOnly, "missing")).getString(0));

        JSONObject equalAliases = new JSONObject()
            .put("perResult", new JSONObject()
                .put("accepted", new JSONArray().put("one"))
                .put("review", new JSONArray().put("two")))
            .put("perGrade", new JSONObject()
                .put("review", new JSONArray().put("two"))
                .put("accepted", new JSONArray().put("one")));
        assertEquals("one", ((JSONArray) ProfileFieldRules.conditionalFieldValue(
            equalAliases, "accepted")).getString(0));

        equalAliases.getJSONObject("perGrade")
            .put("accepted", new JSONArray().put("different"));
        try {
            ProfileFieldRules.conditionalFieldValue(equalAliases, "accepted");
            fail("divergent staged aliases must stop before submission");
        } catch (org.json.JSONException expected) {
            assertEquals("conditionalFields perResult/perGrade mismatch", expected.getMessage());
        }
    }
}
