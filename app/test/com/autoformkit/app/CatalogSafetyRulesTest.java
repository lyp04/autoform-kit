package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class CatalogSafetyRulesTest {
    @Test
    public void bundledSampleMarkerFailsClosed() throws Exception {
        assertFalse(CatalogSafetyRules.allowsRemoteOperations(
            new JSONObject().put("sampleCatalog", true)));
        assertTrue(CatalogSafetyRules.allowsRemoteOperations(new JSONObject()));
        assertTrue(CatalogSafetyRules.allowsRemoteOperations(null));
    }
}
