package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Catalog-level picker visibility compatibility rules. */
final class ProfilePickerRules {
    private ProfilePickerRules() {}

    /**
     * Old catalogs predate {@code pickerVisible} and exposed every profile in the picker. Preserve
     * that behavior only when the field is absent from the entire catalog. As soon as one profile
     * declares visibility, the catalog is using the new contract and only explicit {@code true}
     * entries are visible; missing, malformed, and {@code false} values fail closed.
     */
    static JSONArray visibleProfiles(JSONArray catalog) {
        JSONArray out = new JSONArray();
        if (catalog == null) return out;

        boolean legacyCatalog = true;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject profile = catalog.optJSONObject(i);
            if (profile != null && profile.has("pickerVisible")) {
                legacyCatalog = false;
                break;
            }
        }

        for (int i = 0; i < catalog.length(); i++) {
            JSONObject profile = catalog.optJSONObject(i);
            if (profile == null) continue;
            if (legacyCatalog || (profile.opt("pickerVisible") instanceof Boolean
                    && profile.optBoolean("pickerVisible", false))) {
                out.put(profile);
            }
        }
        return out;
    }
}
