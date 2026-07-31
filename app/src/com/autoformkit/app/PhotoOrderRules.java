package com.autoformkit.app;

import org.json.JSONObject;

/** Validated photo-order defaults owned by the active panel profile. */
final class PhotoOrderRules {
    static final String GROUPED = "fronts_then_backs";
    static final String PER_RECORD = "front_back_per_unit";

    private PhotoOrderRules() {}

    static String profileDefault(JSONObject profile) {
        return normalize(profile == null ? "" : profile.optString("defaultPhotoOrder", ""), GROUPED);
    }

    /**
     * A draft snapshots the order under which its existing photos were captured. Keep that order
     * until the in-progress records finish; a Panel change applies to the next empty batch. This is
     * runtime state, not a device-owned configuration override.
     */
    static String restoreForDraft(JSONObject profile, String draftOrder, boolean hasRecords) {
        if (hasRecords) {
            String restored = normalize(draftOrder, "");
            if (!restored.isEmpty()) return restored;
        }
        return profileDefault(profile);
    }

    private static String normalize(String value, String fallback) {
        String candidate = value == null ? "" : value.trim();
        return GROUPED.equals(candidate) || PER_RECORD.equals(candidate) ? candidate : fallback;
    }
}
