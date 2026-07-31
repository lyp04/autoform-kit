package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Set;

/** Exact profile field mappings; no submission field is guessed by the app. */
final class ProfileFieldRules {
    private ProfileFieldRules() {}

    static String primaryIdentifierField(JSONObject profile) {
        JSONObject fields = profile == null ? null : profile.optJSONObject("snFields");
        return fields == null ? "" : fields.optString("primary", "").trim();
    }

    static String secondaryIdentifierField(JSONObject profile) {
        JSONObject fields = profile == null ? null : profile.optJSONObject("snFields");
        return fields == null ? "" : fields.optString("secondary", "").trim();
    }

    /**
     * Ordered, Panel-declared extra identifier fields which can be edited and submitted.
     *
     * <p>Primary/secondary roles use their dedicated bindings. Hidden plugins are not operator
     * input and therefore cannot become payload fields merely because stale draft JSON contains
     * a matching key.
     */
    static List<String> visibleExtraIdentifierFields(JSONObject profile) {
        JSONArray plugins = profile == null ? null : profile.optJSONArray("snPlugins");
        if (plugins == null) return Collections.emptyList();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.optJSONObject(i);
            if (!isVisible(plugin)) continue;
            String key = plugin.optString("key", "").trim();
            if ("primary".equals(key) || "secondary".equals(key)) continue;
            String field = plugin.optString("field", "").trim();
            if (!field.isEmpty()) fields.add(field);
        }
        return Collections.unmodifiableList(new ArrayList<>(fields));
    }

    /**
     * Returns only values whose field is declared by the active profile. Iterating this result,
     * rather than draft-owned map entries, is the final payload allow-list.
     */
    static Map<String, String> boundVisibleExtraIdentifierValues(
            JSONObject profile, Map<String, String> values) {
        LinkedHashMap<String, String> bound = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        for (String field : visibleExtraIdentifierFields(profile)) {
            if (values.containsKey(field)) bound.put(field, values.get(field));
        }
        return Collections.unmodifiableMap(bound);
    }

    /** Unknown, hidden or role-owned draft keys are stale evidence and block before upload. */
    static List<String> unexpectedExtraIdentifierFields(
            JSONObject profile, Map<String, String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        Set<String> allowed = new LinkedHashSet<>(visibleExtraIdentifierFields(profile));
        List<String> unexpected = new ArrayList<>();
        for (String field : values.keySet()) {
            if (field == null || !allowed.contains(field)) {
                unexpected.add(field == null ? "" : field);
            }
        }
        Collections.sort(unexpected);
        return Collections.unmodifiableList(unexpected);
    }

    /** Exact result-key membership check used before any upload or side-effecting request. */
    static boolean resultSelectionValid(JSONObject profile, String resultKey) {
        JSONObject results = profile == null ? null : profile.optJSONObject("gradeMap");
        if (results == null || results.length() == 0) {
            return resultKey == null || resultKey.trim().isEmpty();
        }
        if (resultKey == null || resultKey.isEmpty()
                || !resultKey.equals(resultKey.trim())) return false;
        JSONObject selected = results.optJSONObject(resultKey);
        return selected != null
            && !selected.optString("field", "").trim().isEmpty()
            && selected.has("value");
    }

    static JSONObject resultMapping(JSONObject profile, String resultKey)
            throws JSONException {
        if (!resultSelectionValid(profile, resultKey)) {
            throw new JSONException("Result mapping is missing for key: "
                + (resultKey == null ? "" : resultKey.trim()));
        }
        JSONObject results = profile == null ? null : profile.optJSONObject("gradeMap");
        if (results == null || results.length() == 0) return null;
        return new JSONObject(results.getJSONObject(resultKey).toString());
    }

    /** Required and optional photo boxes share one ordered runtime/upload contract. */
    static JSONArray photoSlots(JSONObject profile, boolean includeOptionalSlots) {
        JSONArray required = profile == null ? null : profile.optJSONArray("photoSlots");
        JSONArray optional = profile == null ? null : profile.optJSONArray("optionalSlots");
        JSONArray combined = new JSONArray();
        appendObjects(combined, required);
        if (includeOptionalSlots) appendObjects(combined, optional);
        return combined.length() == 0 ? null : combined;
    }

    static List<String> activePhotoSlotFields(
            JSONObject profile, boolean includeOptionalSlots) {
        JSONArray slots = photoSlots(profile, includeOptionalSlots);
        if (slots == null) return Collections.emptyList();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            String field = slot == null ? "" : slot.optString("field", "").trim();
            if (!field.isEmpty()) fields.add(field);
        }
        return Collections.unmodifiableList(new ArrayList<>(fields));
    }

    /** Photo keys from another profile or an inactive optional slot block before upload. */
    static List<String> unexpectedPhotoSlotFields(
            JSONObject profile, boolean includeOptionalSlots,
            Map<String, ? extends List<String>> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        Set<String> allowed = new LinkedHashSet<>(
            activePhotoSlotFields(profile, includeOptionalSlots));
        List<String> unexpected = new ArrayList<>();
        for (String field : values.keySet()) {
            if (field == null || !allowed.contains(field)) {
                unexpected.add(field == null ? "" : field);
            }
        }
        Collections.sort(unexpected);
        return Collections.unmodifiableList(unexpected);
    }

    private static void appendObjects(JSONArray target, JSONArray source) {
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) target.put(item);
        }
    }

    static boolean isVisible(JSONObject plugin) {
        return plugin != null && plugin.optBoolean("visible", true);
    }

    /** Required extra identifiers are explicit profile fields; primary/secondary use dedicated rows. */
    static List<String> missingRequiredVisibleExtraFields(
            JSONObject profile, Map<String, String> values) {
        JSONArray plugins = profile == null ? null : profile.optJSONArray("snPlugins");
        if (plugins == null) return Collections.emptyList();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < plugins.length(); i++) {
            JSONObject plugin = plugins.optJSONObject(i);
            if (!isVisible(plugin) || !plugin.optBoolean("required", false)) continue;
            String key = plugin.optString("key", "").trim();
            if ("primary".equals(key) || "secondary".equals(key)) continue;
            String field = plugin.optString("field", "").trim();
            if (field.isEmpty()) continue;
            String value = values == null ? "" : values.get(field);
            if (value == null || value.trim().isEmpty()) missing.add(field);
        }
        return missing;
    }

    /**
     * During a staged old/new App rollout, legacy catalogs may contain only perGrade while new
     * catalogs contain perResult (and may temporarily contain both). Prefer the new name, preserve
     * the legacy-only payload exactly, and reject a divergent dual definition before submission.
     */
    static Object conditionalFieldValue(JSONObject field, String resultKey) throws JSONException {
        if (field == null) return null;
        JSONObject perResult = field.optJSONObject("perResult");
        JSONObject perGrade = field.optJSONObject("perGrade");
        if (perResult != null && perGrade != null && !jsonValuesEqual(perResult, perGrade)) {
            throw new JSONException("conditionalFields perResult/perGrade mismatch");
        }
        JSONObject values = perResult != null ? perResult : perGrade;
        if (values != null && resultKey != null && values.has(resultKey)) {
            return values.opt(resultKey);
        }
        return field.opt("value");
    }

    private static boolean jsonValuesEqual(Object left, Object right) throws JSONException {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left == JSONObject.NULL || right == JSONObject.NULL) return left == right;
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject leftObject = (JSONObject) left;
            JSONObject rightObject = (JSONObject) right;
            if (leftObject.length() != rightObject.length()) return false;
            Iterator<String> keys = leftObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!rightObject.has(key)
                        || !jsonValuesEqual(leftObject.get(key), rightObject.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray leftArray = (JSONArray) left;
            JSONArray rightArray = (JSONArray) right;
            if (leftArray.length() != rightArray.length()) return false;
            for (int i = 0; i < leftArray.length(); i++) {
                if (!jsonValuesEqual(leftArray.get(i), rightArray.get(i))) return false;
            }
            return true;
        }
        return left.equals(right);
    }
}
