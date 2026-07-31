package com.autoformkit.app;

import org.json.JSONObject;

/** Fail-closed rules that keep the bundled demonstration catalog non-operational. */
final class CatalogSafetyRules {
    private CatalogSafetyRules() {}

    static boolean isSampleCatalog(JSONObject settings) {
        return settings != null && settings.optBoolean("sampleCatalog", false);
    }

    static boolean allowsRemoteOperations(JSONObject settings) {
        return !isSampleCatalog(settings);
    }
}
