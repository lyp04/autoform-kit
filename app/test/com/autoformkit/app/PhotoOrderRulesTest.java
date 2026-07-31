package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class PhotoOrderRulesTest {
    @Test
    public void eachProfileOwnsItsPhotoOrderDefault() throws Exception {
        JSONObject grouped = new JSONObject().put("defaultPhotoOrder", "fronts_then_backs");
        JSONObject perRecord = new JSONObject().put("defaultPhotoOrder", "front_back_per_unit");

        assertEquals("fronts_then_backs", PhotoOrderRules.profileDefault(grouped));
        assertEquals("front_back_per_unit", PhotoOrderRules.profileDefault(perRecord));
    }

    @Test
    public void inProgressDraftKeepsItsCapturedOrderAcrossUpgrade() throws Exception {
        JSONObject profile = new JSONObject().put("defaultPhotoOrder", "fronts_then_backs");

        assertEquals("front_back_per_unit",
            PhotoOrderRules.restoreForDraft(profile, "front_back_per_unit", true));
        assertEquals("fronts_then_backs",
            PhotoOrderRules.restoreForDraft(profile, "front_back_per_unit", false));
        assertEquals("fronts_then_backs",
            PhotoOrderRules.restoreForDraft(profile, "unknown", true));
    }
}
