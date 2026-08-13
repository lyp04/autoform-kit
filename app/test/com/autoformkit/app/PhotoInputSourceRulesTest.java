package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

public class PhotoInputSourceRulesTest {
    @Test
    public void missingPolicyPreservesLegacyCameraDefault() {
        assertEquals(PhotoInputSourceRules.CAMERA,
            PhotoInputSourceRules.from(new JSONObject()));
        assertEquals(PhotoInputSourceRules.CAMERA,
            PhotoInputSourceRules.from(null));
    }

    @Test
    public void allPanelOwnedSourcesAreAccepted() throws Exception {
        for (String source : new String[]{"camera", "gallery", "file"}) {
            assertEquals(source, PhotoInputSourceRules.from(
                new JSONObject().put("inputSource", source)));
        }
    }

    @Test
    public void malformedOrUnknownPolicyFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
            PhotoInputSourceRules.from(new JSONObject().put("inputSource", " gallery")));
        assertThrows(IllegalArgumentException.class, () ->
            PhotoInputSourceRules.from(new JSONObject().put("inputSource", "camera_or_gallery")));
        assertThrows(IllegalArgumentException.class, () ->
            PhotoInputSourceRules.from(new JSONObject().put("inputSource", true)));
    }
}
