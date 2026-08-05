package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Upgrade-safe transformations for device-local aggregate counters. */
final class DailyStatsRules {
    static final String LEGACY_RESULTS = "legacyResults";
    static final String ALL_PROFILES_SCOPE = "all_profiles";
    static final int DAILY_STATS_V2_VERSION = 2;
    static final int MAX_V2_GROUPS = 16;
    static final int MAX_V2_FLAT_SUMMARIES = 8;
    static final int MAX_V2_SELECTORS = 512;
    static final int MAX_V2_LEGACY_KEYS = 128;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int MAX_LABEL_LENGTH = 160;

    private DailyStatsRules() {}

    /**
     * Earlier catalogs stored result totals at the root and showed the same aggregate for every
     * profile. Keep every numeric result key separately so an in-place upgrade neither loses nor
     * misattributes it; new counters remain profile-scoped under {@code results}.
     */
    static boolean migrateLegacyRootCounts(JSONObject stats) {
        if (stats == null) return false;
        JSONObject legacy = stats.optJSONObject(LEGACY_RESULTS);
        boolean changed = false;
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        java.util.Iterator<String> names = stats.keys();
        while (names.hasNext()) keys.add(names.next());
        for (String key : keys) {
            if ("counted".equals(key) || "results".equals(key) || LEGACY_RESULTS.equals(key)) continue;
            Object value = stats.opt(key);
            if (!(value instanceof Number)) continue;
            if (legacy == null) legacy = new JSONObject();
            try {
                legacy.put(key, Math.max(0, ((Number) value).intValue()));
                stats.remove(key);
                changed = true;
            } catch (Exception ignored) {}
        }
        if (changed) {
            try { stats.put(LEGACY_RESULTS, legacy); }
            catch (Exception ignored) { return false; }
        }
        return changed;
    }

    static int displayedCount(JSONObject stats, JSONObject profileResults, String resultKey) {
        int current = profileResults == null ? 0 : profileResults.optInt(resultKey, 0);
        JSONObject legacy = stats == null ? null : stats.optJSONObject(LEGACY_RESULTS);
        return current + (legacy == null ? 0 : legacy.optInt(resultKey, 0));
    }

    /**
     * Returns the Panel-owned ordered cards only for the explicit cross-profile presentation.
     * Catalog promotion validates the complete schema; these checks keep an old/unvalidated cache
     * from silently changing the login screen after an App-only upgrade.
     */
    static JSONArray allProfilesGroups(JSONObject catalogSettings) {
        JSONObject presentation = catalogSettings == null
            ? null : catalogSettings.optJSONObject("dailyStats");
        Object rawScope = presentation == null ? null : presentation.opt("scope");
        if (!(rawScope instanceof String) || !ALL_PROFILES_SCOPE.equals(rawScope)) {
            return null;
        }
        JSONArray groups = presentation.optJSONArray("groups");
        if (groups == null || groups.length() == 0 || groups.length() > 16) return null;
        Set<String> groupIds = new LinkedHashSet<>();
        Set<String> groupedKeys = new LinkedHashSet<>();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            Object rawId = group == null ? null : group.opt("id");
            Object rawLabel = group == null ? null : group.opt("label");
            Object rawColor = group == null ? null : group.opt("uiColor");
            String groupId = rawId instanceof String ? (String) rawId : "";
            if (group == null
                    || groupId.isEmpty()
                    || !groupId.equals(groupId.trim())
                    || !groupIds.add(groupId)
                    || !(rawLabel instanceof String)
                    || ((String) rawLabel).trim().isEmpty()
                    || !rawLabel.equals(((String) rawLabel).trim())
                    || !(rawColor instanceof String)
                    || !((String) rawColor).matches("#[0-9A-Fa-f]{6}")) {
                return null;
            }
            JSONArray keys = group.optJSONArray("resultKeys");
            if (keys == null || keys.length() == 0 || keys.length() > 128) return null;
            Set<String> localKeys = new LinkedHashSet<>();
            for (int keyIndex = 0; keyIndex < keys.length(); keyIndex++) {
                Object rawKey = keys.opt(keyIndex);
                if (!(rawKey instanceof String)
                        || ((String) rawKey).trim().isEmpty()
                        || !rawKey.equals(((String) rawKey).trim())
                        || !localKeys.add((String) rawKey)
                        || !groupedKeys.add((String) rawKey)) {
                    return null;
                }
            }
        }
        return groups;
    }

    /**
     * Returns the Panel-owned v2 presentation after bounded cache-level validation. Selectors are
     * exact profile/result pairs, so the same opaque result key may carry different meanings in
     * different profiles without the App inspecting its label or backend value.
     */
    static JSONObject allProfilesV2(JSONObject catalogSettings, JSONArray visibleProfiles) {
        JSONObject presentation = catalogSettings == null
            ? null : catalogSettings.optJSONObject("dailyStatsV2");
        if (presentation == null
                || !hasOnlyKeys(presentation, "version", "scope", "groups", "flatSummaries")) {
            return null;
        }
        Object rawVersion = presentation.opt("version");
        Object rawScope = presentation.opt("scope");
        if (!(rawVersion instanceof Byte || rawVersion instanceof Short
                || rawVersion instanceof Integer || rawVersion instanceof Long)
                || ((Number) rawVersion).longValue() != DAILY_STATS_V2_VERSION
                || !(rawScope instanceof String)
                || !ALL_PROFILES_SCOPE.equals(rawScope)) {
            return null;
        }
        JSONArray groups = presentation.optJSONArray("groups");
        JSONArray flatSummaries = presentation.optJSONArray("flatSummaries");
        if (groups == null || groups.length() == 0 || groups.length() > MAX_V2_GROUPS
                || flatSummaries == null
                || flatSummaries.length() > MAX_V2_FLAT_SUMMARIES) {
            return null;
        }

        Map<String, Set<String>> visibleResults = visibleProfileResults(visibleProfiles);
        if (visibleResults.isEmpty()) return null;
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> groupPairs = new LinkedHashSet<>();
        Set<String> flatPairs = new LinkedHashSet<>();
        Set<String> assignedLegacyKeys = new LinkedHashSet<>();
        if (!validV2Items(groups, false, visibleResults, itemIds,
                groupPairs, assignedLegacyKeys)) {
            return null;
        }
        if (!validV2Items(flatSummaries, true, visibleResults, itemIds,
                flatPairs, null)) {
            return null;
        }
        return presentation;
    }

    /**
     * Sums one configured result group across exactly the picker-visible profiles. Historical
     * root counts were already global, so each legacy result key is included once rather than once
     * per profile.
     */
    static int displayedAllProfilesCount(JSONObject stats, JSONArray visibleProfiles,
                                         JSONArray resultKeys) {
        if (stats == null || visibleProfiles == null || resultKeys == null) return 0;
        Set<String> keys = nonEmptyStrings(resultKeys);
        if (keys.isEmpty()) return 0;

        long total = 0L;
        JSONObject allResults = stats.optJSONObject("results");
        Set<String> countedProfiles = new LinkedHashSet<>();
        for (int index = 0; index < visibleProfiles.length(); index++) {
            JSONObject visible = visibleProfiles.optJSONObject(index);
            String profileId = visible == null ? "" : visible.optString("id", "").trim();
            if (profileId.isEmpty() || !countedProfiles.add(profileId)) continue;
            JSONObject profileResults = allResults == null
                ? null : allResults.optJSONObject(profileId);
            for (String key : keys) {
                total = addCount(total, profileResults, key);
            }
        }

        JSONObject legacy = stats.optJSONObject(LEGACY_RESULTS);
        for (String key : keys) total = addCount(total, legacy, key);
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** Counts exact Panel-selected profile/result pairs and explicitly assigned legacy keys. */
    static int displayedSelectedCount(JSONObject stats, JSONArray selectors,
                                      JSONArray legacyResultKeys) {
        if (stats == null || selectors == null) return 0;
        long total = 0L;
        JSONObject allResults = stats.optJSONObject("results");
        Set<String> countedPairs = new LinkedHashSet<>();
        for (int index = 0; index < selectors.length(); index++) {
            JSONObject selector = selectors.optJSONObject(index);
            String profileId = selector == null ? "" : selector.optString("profileId", "");
            String resultKey = selector == null ? "" : selector.optString("resultKey", "");
            if (profileId.isEmpty() || resultKey.isEmpty()) continue;
            String pair = pairKey(profileId, resultKey);
            if (!countedPairs.add(pair)) continue;
            JSONObject profileResults = allResults == null
                ? null : allResults.optJSONObject(profileId);
            total = addCount(total, profileResults, resultKey);
        }

        JSONObject legacy = stats.optJSONObject(LEGACY_RESULTS);
        for (String resultKey : nonEmptyStrings(legacyResultKeys)) {
            total = addCount(total, legacy, resultKey);
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static boolean validV2Items(JSONArray items, boolean flat,
                                        Map<String, Set<String>> visibleResults,
                                        Set<String> itemIds, Set<String> assignedPairs,
                                        Set<String> assignedLegacyKeys) {
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null || !hasOnlyKeys(item, flat
                    ? new String[]{"id", "label", "labelI18n", "uiColor", "selectors"}
                    : new String[]{"id", "label", "labelI18n", "uiColor", "selectors",
                        "legacyResultKeys"})) {
                return false;
            }
            String id = strictText(item.opt("id"), MAX_ID_LENGTH);
            String label = strictText(item.opt("label"), MAX_LABEL_LENGTH);
            String color = strictText(item.opt("uiColor"), 7);
            if (id == null || label == null || color == null
                    || !color.matches("#[0-9A-Fa-f]{6}")
                    || !itemIds.add(id)
                    || (item.has("labelI18n")
                        && !validLocalizedLabels(item.opt("labelI18n")))) {
                return false;
            }
            JSONArray selectors = item.optJSONArray("selectors");
            if (selectors == null || selectors.length() == 0
                    || selectors.length() > MAX_V2_SELECTORS) {
                return false;
            }
            Set<String> localPairs = new LinkedHashSet<>();
            Set<String> selectedResultKeys = new LinkedHashSet<>();
            for (int selectorIndex = 0; selectorIndex < selectors.length(); selectorIndex++) {
                JSONObject selector = selectors.optJSONObject(selectorIndex);
                if (selector == null || !hasOnlyKeys(selector, "profileId", "resultKey")) {
                    return false;
                }
                String profileId = strictText(selector.opt("profileId"), MAX_TEXT_LENGTH);
                String resultKey = strictText(selector.opt("resultKey"), MAX_TEXT_LENGTH);
                Set<String> profileKeys = profileId == null ? null : visibleResults.get(profileId);
                String pair = profileId == null || resultKey == null
                    ? "" : pairKey(profileId, resultKey);
                if (profileKeys == null || !profileKeys.contains(resultKey)
                        || !localPairs.add(pair) || !assignedPairs.add(pair)) {
                    return false;
                }
                selectedResultKeys.add(resultKey);
            }

            if (flat && item.has("legacyResultKeys")) return false;
            if (!flat && item.has("legacyResultKeys")) {
                JSONArray legacyKeys = item.optJSONArray("legacyResultKeys");
                if (legacyKeys == null || legacyKeys.length() == 0
                        || legacyKeys.length() > MAX_V2_LEGACY_KEYS) {
                    return false;
                }
                Set<String> localLegacyKeys = new LinkedHashSet<>();
                for (int keyIndex = 0; keyIndex < legacyKeys.length(); keyIndex++) {
                    String key = strictText(legacyKeys.opt(keyIndex), MAX_TEXT_LENGTH);
                    if (key == null || !selectedResultKeys.contains(key)
                            || !localLegacyKeys.add(key)
                            || assignedLegacyKeys == null
                            || !assignedLegacyKeys.add(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Map<String, Set<String>> visibleProfileResults(JSONArray visibleProfiles) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (int index = 0; visibleProfiles != null && index < visibleProfiles.length(); index++) {
            JSONObject profile = visibleProfiles.optJSONObject(index);
            if (profile == null || !Boolean.TRUE.equals(profile.opt("pickerVisible"))) continue;
            String id = strictText(profile.opt("id"), MAX_TEXT_LENGTH);
            JSONObject resultMap = profile.optJSONObject("gradeMap");
            if (id == null || out.containsKey(id)) return new LinkedHashMap<>();
            Set<String> resultKeys = new LinkedHashSet<>();
            if (resultMap != null) {
                java.util.Iterator<String> keys = resultMap.keys();
                while (keys.hasNext()) resultKeys.add(keys.next());
            }
            out.put(id, resultKeys);
        }
        return out;
    }

    private static boolean validLocalizedLabels(Object raw) {
        if (!(raw instanceof JSONObject)) return false;
        JSONObject labels = (JSONObject) raw;
        if (!hasOnlyKeys(labels, "en", "es")) return false;
        java.util.Iterator<String> keys = labels.keys();
        while (keys.hasNext()) {
            if (strictText(labels.opt(keys.next()), MAX_LABEL_LENGTH) == null) return false;
        }
        return true;
    }

    private static boolean hasOnlyKeys(JSONObject value, String... allowed) {
        Set<String> allowedKeys = new LinkedHashSet<>();
        java.util.Collections.addAll(allowedKeys, allowed);
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            if (!allowedKeys.contains(keys.next())) return false;
        }
        return true;
    }

    private static String strictText(Object raw, int maximumLength) {
        if (!(raw instanceof String)) return null;
        String value = (String) raw;
        if (value.isEmpty() || !value.equals(value.trim()) || value.length() > maximumLength) {
            return null;
        }
        return value;
    }

    private static String pairKey(String profileId, String resultKey) {
        return profileId.length() + ":" + profileId + resultKey;
    }

    private static Set<String> nonEmptyStrings(JSONArray values) {
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; values != null && index < values.length(); index++) {
            Object raw = values.opt(index);
            if (!(raw instanceof String)) continue;
            String value = ((String) raw).trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static long addCount(long current, JSONObject owner, String key) {
        Object raw = owner == null ? null : owner.opt(key);
        if (!(raw instanceof Number)) return current;
        long count = Math.max(0L, ((Number) raw).longValue());
        if (current >= Integer.MAX_VALUE - count) return Integer.MAX_VALUE;
        return current + count;
    }
}
