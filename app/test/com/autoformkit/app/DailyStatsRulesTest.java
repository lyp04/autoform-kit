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

    @Test
    public void v2SeparatesTheSameResultKeyByExactProfileAndAssignsLegacyExplicitly()
            throws Exception {
        JSONArray visibleProfiles = new JSONArray()
            .put(visibleProfile("sample-primary", "sample-shared"))
            .put(visibleProfile("sample-secondary", "sample-shared"));
        JSONObject settings = new JSONObject().put("dailyStatsV2", new JSONObject()
            .put("version", 2)
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(v2Item(
                "sample-primary-summary", "Sample primary",
                "sample-primary", "sample-shared")
                .put("legacyResultKeys", new JSONArray().put("sample-shared"))))
            .put("flatSummaries", new JSONArray().put(v2Item(
                "sample-secondary-summary", "Sample secondary",
                "sample-secondary", "sample-shared"))));
        JSONObject stats = new JSONObject()
            .put("results", new JSONObject()
                .put("sample-primary", new JSONObject().put("sample-shared", 7))
                .put("sample-secondary", new JSONObject().put("sample-shared", 3)))
            .put(DailyStatsRules.LEGACY_RESULTS,
                new JSONObject().put("sample-shared", 2));

        JSONObject v2 = DailyStatsRules.allProfilesV2(settings, visibleProfiles);
        assertTrue(v2 != null);
        JSONObject group = v2.getJSONArray("groups").getJSONObject(0);
        JSONObject flat = v2.getJSONArray("flatSummaries").getJSONObject(0);
        assertEquals(9, DailyStatsRules.displayedSelectedCount(stats,
            group.getJSONArray("selectors"), group.getJSONArray("legacyResultKeys")));
        assertEquals(3, DailyStatsRules.displayedSelectedCount(stats,
            flat.getJSONArray("selectors"), null));
    }

    @Test
    public void v2RejectsPairOverlapWithinEachPresentationLayerButAllowsFlatAgainstCards()
            throws Exception {
        JSONArray visibleProfiles = new JSONArray()
            .put(visibleProfile("sample-one", "sample-ready"));
        JSONObject card = v2Item(
            "sample-card", "Sample card", "sample-one", "sample-ready");
        JSONObject flat = v2Item(
            "sample-flat", "Sample flat", "sample-one", "sample-ready");
        JSONObject settings = new JSONObject().put("dailyStatsV2", new JSONObject()
            .put("version", 2)
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(card))
            .put("flatSummaries", new JSONArray().put(flat)));
        assertTrue(DailyStatsRules.allProfilesV2(settings, visibleProfiles) != null);

        settings.getJSONObject("dailyStatsV2").getJSONArray("flatSummaries")
            .put(new JSONObject(flat.toString()).put("id", "sample-flat-two"));
        assertEquals(null, DailyStatsRules.allProfilesV2(settings, visibleProfiles));
        settings.getJSONObject("dailyStatsV2").getJSONArray("flatSummaries").remove(1);
        settings.getJSONObject("dailyStatsV2").put("version", 2.0d);
        assertEquals(null, DailyStatsRules.allProfilesV2(settings, visibleProfiles));
    }

    @Test
    public void visibleProfileWithoutResultsDoesNotDisableAnOtherwiseValidV2Presentation()
            throws Exception {
        JSONArray visibleProfiles = new JSONArray()
            .put(visibleProfile("sample-with-results", "sample-ready"))
            .put(new JSONObject()
                .put("id", "sample-without-results")
                .put("pickerVisible", true));
        JSONObject settings = new JSONObject().put("dailyStatsV2", new JSONObject()
            .put("version", 2)
            .put("scope", DailyStatsRules.ALL_PROFILES_SCOPE)
            .put("groups", new JSONArray().put(v2Item(
                "sample-card", "Sample card", "sample-with-results", "sample-ready")))
            .put("flatSummaries", new JSONArray()));

        assertTrue(DailyStatsRules.allProfilesV2(settings, visibleProfiles) != null);
    }

    private static JSONObject visibleProfile(String id, String resultKey) throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("pickerVisible", true)
            .put("gradeMap", new JSONObject().put(resultKey, new JSONObject()));
    }

    private static JSONObject v2Item(String id, String label, String profileId,
                                     String resultKey) throws Exception {
        return new JSONObject()
            .put("id", id)
            .put("label", label)
            .put("uiColor", "#123456")
            .put("selectors", new JSONArray().put(new JSONObject()
                .put("profileId", profileId)
                .put("resultKey", resultKey)));
    }
}
