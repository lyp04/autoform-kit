package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Upgrade-safe transformations for device-local aggregate counters. */
final class DailyStatsRules {
    static final String LEGACY_RESULTS = "legacyResults";
    static final String ALL_PROFILES_SCOPE = "all_profiles";

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

    private static Set<String> nonEmptyStrings(JSONArray values) {
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index++) {
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
