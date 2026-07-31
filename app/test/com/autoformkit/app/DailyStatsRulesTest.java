package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DailyStatsRulesTest {
    @Test
    public void preservesLegacyAggregateCountsWithoutAssigningThemToOneProfile() throws Exception {
        JSONObject stats = new JSONObject()
            .put("sample-one", 4)
            .put("sample-two", 2)
            .put("counted", new JSONArray().put("sample|id"));

        assertTrue(DailyStatsRules.migrateLegacyRootCounts(stats));
        assertFalse(stats.has("sample-one"));
        assertEquals(4, stats.getJSONObject(DailyStatsRules.LEGACY_RESULTS)
            .getInt("sample-one"));
        assertEquals(5, DailyStatsRules.displayedCount(
            stats, new JSONObject().put("sample-one", 1), "sample-one"));
        assertFalse(DailyStatsRules.migrateLegacyRootCounts(stats));
    }

    @Test
    public void aggregatesConfiguredKeysAcrossVisibleProfilesAndAddsLegacyOnce()
            throws Exception {
        JSONObject stats = new JSONObject()
            .put("results", new JSONObject()
                .put("sample-visible-one", new JSONObject()
                    .put("sample-alpha", 2)
                    .put("sample-beta", 1))
                .put("sample-visible-two", new JSONObject()
                    .put("sample-alpha", 3))
                .put("sample-hidden", new JSONObject()
                    .put("sample-alpha", 50)))
            .put(DailyStatsRules.LEGACY_RESULTS, new JSONObject()
                .put("sample-alpha", 4)
                .put("sample-beta", 2));
        JSONArray visibleProfiles = new JSONArray()
            .put(new JSONObject().put("id", "sample-visible-one"))
            .put(new JSONObject().put("id", "sample-visible-two"));

        assertEquals(9, DailyStatsRules.displayedAllProfilesCount(
            stats, visibleProfiles, new JSONArray().put("sample-alpha")));
        assertEquals(12, DailyStatsRules.displayedAllProfilesCount(
            stats, visibleProfiles,
            new JSONArray().put("sample-alpha").put("sample-beta")));
    }

    @Test
    public void configuredPresentationRequiresExplicitAllProfilesScope() throws Exception {
        JSONObject settings = new JSONObject().put("dailyStats", new JSONObject()
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(new JSONObject()
                .put("id", "sample-summary")
                .put("label", "Sample")
                .put("uiColor", "#123456")
                .put("resultKeys", new JSONArray().put("sample-alpha")))));

        assertEquals(1, DailyStatsRules.allProfilesGroups(settings).length());
        settings.getJSONObject("dailyStats").getJSONArray("groups").put(
            new JSONObject(settings.getJSONObject("dailyStats")
                .getJSONArray("groups").getJSONObject(0).toString())
                .put("id", "sample-summary-two"));
        assertEquals(null, DailyStatsRules.allProfilesGroups(settings));
        settings.getJSONObject("dailyStats").getJSONArray("groups").remove(1);
        settings.getJSONObject("dailyStats").put("scope", "sample-current-only");
        assertEquals(null, DailyStatsRules.allProfilesGroups(settings));
    }
}
