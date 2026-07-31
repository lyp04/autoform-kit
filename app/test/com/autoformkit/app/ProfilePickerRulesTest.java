package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ProfilePickerRulesTest {
    private static JSONObject profile(String id) throws Exception {
        return new JSONObject().put("id", id);
    }

    @Test
    public void catalogWithNoVisibilityFieldsKeepsLegacyAllVisibleBehavior() throws Exception {
        JSONArray catalog = new JSONArray()
            .put(profile("one"))
            .put(profile("two"));

        JSONArray visible = ProfilePickerRules.visibleProfiles(catalog);

        assertEquals(2, visible.length());
        assertEquals("one", visible.getJSONObject(0).getString("id"));
        assertEquals("two", visible.getJSONObject(1).getString("id"));
    }

    @Test
    public void explicitCatalogShowsOnlyStrictBooleanTrue() throws Exception {
        JSONArray catalog = new JSONArray()
            .put(profile("visible").put("pickerVisible", true))
            .put(profile("hidden").put("pickerVisible", false))
            .put(profile("missing"))
            .put(profile("malformed").put("pickerVisible", "true"));

        JSONArray visible = ProfilePickerRules.visibleProfiles(catalog);

        assertEquals(1, visible.length());
        assertEquals("visible", visible.getJSONObject(0).getString("id"));
    }

    @Test
    public void nullAndNonObjectEntriesAreIgnored() throws Exception {
        JSONArray catalog = new JSONArray().put(JSONObject.NULL).put("not-a-profile");
        assertEquals(0, ProfilePickerRules.visibleProfiles(catalog).length());
        assertEquals(0, ProfilePickerRules.visibleProfiles(null).length());
    }
}
