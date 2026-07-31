package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class LegacyPanelStateMigrationRulesTest {
    private static JSONArray profiles() throws Exception {
        return new JSONArray()
            .put(new JSONObject().put("id", "sample-one"))
            .put(new JSONObject().put("id", "sample-two"));
    }

    private static String ledger(String profileId) throws Exception {
        return new JSONArray().put(new JSONObject()
            .put("ts", 946684800000L)
            .put("tsText", "01-01 00:00")
            .put("profileId", profileId)
            .put("units", new JSONArray().put(new JSONObject()
                .put("sn", "SAMPLE-0001")
                .put("submit", "ok")
                .put("printed", "unconfirmed")
                .put("cloudStatus", 2)
                .put("cloudId", 41L))))
            .toString();
    }

    @Test
    public void signedV1LedgerNeedsAUniqueProfileInTheExactCatalog() throws Exception {
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            ledger("sample-one"), profiles()));
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            ledger("other-panel-profile"), profiles()));

        JSONArray duplicate = profiles()
            .put(new JSONObject().put("id", "sample-one"));
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            ledger("sample-one"), duplicate));
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            "[{\"ts\":1,\"tsText\":\"01-01 00:00\","
                + "\"profileId\":\"sample-one\",\"units\":[{}]}]",
            profiles()));
    }

    @Test
    public void ledgerControlFieldsKeepSignedWriterTypesAndRelationships()
            throws Exception {
        JSONObject round = new JSONArray(ledger("sample-one")).getJSONObject(0);
        JSONObject unit = round.getJSONArray("units").getJSONObject(0);

        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(round).toString(), profiles()));

        for (Object invalidStatus : new Object[]{"2", 3, -2, 4_294_967_297L}) {
            JSONObject changed = new JSONObject(round.toString());
            changed.getJSONArray("units").getJSONObject(0)
                .put("cloudStatus", invalidStatus);
            assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
                new JSONArray().put(changed).toString(), profiles()));
        }
        for (Object invalidId : new Object[]{"41", -1L, 1.5d}) {
            JSONObject changed = new JSONObject(round.toString());
            changed.getJSONArray("units").getJSONObject(0)
                .put("cloudId", invalidId);
            assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
                new JSONArray().put(changed).toString(), profiles()));
        }

        JSONObject missingPair = new JSONObject(round.toString());
        missingPair.getJSONArray("units").getJSONObject(0).remove("cloudId");
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(missingPair).toString(), profiles()));

        JSONObject missingWithJob = new JSONObject(round.toString());
        missingWithJob.getJSONArray("units").getJSONObject(0)
            .put("cloudStatus", -1).put("cloudId", 41L);
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(missingWithJob).toString(), profiles()));

        JSONObject current = new JSONObject(round.toString());
        current.getJSONArray("units").getJSONObject(0)
            .remove("cloudStatus");
        current.getJSONArray("units").getJSONObject(0)
            .remove("cloudId");
        current.getJSONArray("units").getJSONObject(0)
            .put("remotePrintStatus", 3).put("remotePrintId", 41L);
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(current).toString(), profiles()));

        JSONObject equivalentMixed = new JSONObject(current.toString());
        equivalentMixed.getJSONArray("units").getJSONObject(0)
            .put("cloudStatus", 0).put("cloudId", 41L);
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(equivalentMixed).toString(), profiles()));
        equivalentMixed.getJSONArray("units").getJSONObject(0)
            .put("cloudId", 42L);
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(equivalentMixed).toString(), profiles()));

        JSONObject failed = new JSONObject(round.toString());
        failed.getJSONArray("units").getJSONObject(0)
            .put("submit", "failed").put("printed", "na")
            .remove("cloudStatus");
        failed.getJSONArray("units").getJSONObject(0).remove("cloudId");
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(failed).toString(), profiles()));

        JSONObject inconsistent = new JSONObject(round.toString());
        inconsistent.getJSONArray("units").getJSONObject(0)
            .put("submit", "failed").put("printed", "unconfirmed");
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(inconsistent).toString(), profiles()));

        JSONObject numericSn = new JSONObject(round.toString());
        numericSn.getJSONArray("units").getJSONObject(0).put("sn", 41L);
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(numericSn).toString(), profiles()));

        JSONObject emptyUnits = new JSONObject(round.toString()).put("units", new JSONArray());
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(emptyUnits).toString(), profiles()));

        // Unknown fields are forward-compatible and never become control inputs.
        unit.put("futureDisplayHint", new JSONObject().put("sample", true));
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            new JSONArray().put(round).toString(), profiles()));
    }

    @Test
    public void signedV1PrintFieldsRemainFallbackAndNeutralFieldsWin()
            throws Exception {
        JSONObject legacy = new JSONObject()
            .put("cloudStatus", 2)
            .put("cloudId", 41L);
        assertEquals(2, LegacyPanelStateMigrationRules.remotePrintStatus(legacy, -99));
        assertEquals(41L, LegacyPanelStateMigrationRules.remotePrintId(legacy));

        legacy.put("remotePrintStatus", 1).put("remotePrintId", 42L);
        assertEquals(1, LegacyPanelStateMigrationRules.remotePrintStatus(legacy, -99));
        assertEquals(42L, LegacyPanelStateMigrationRules.remotePrintId(legacy));
    }

    @Test
    public void dynamicPreviousRoundKeyCannotCrossProfiles() throws Exception {
        assertTrue(LegacyPanelStateMigrationRules.validPreviousRoundKey(
            "prevRoundMissing_sample-one", profiles()));
        assertFalse(LegacyPanelStateMigrationRules.validPreviousRoundKey(
            "prevRoundMissing_other", profiles()));
        assertFalse(LegacyPanelStateMigrationRules.validPreviousRoundKey(
            "not-a-previous-round-key", profiles()));
    }

    @Test
    public void dailyLegacyAggregateIsAllowedButPerProfileMapsAreCatalogBound()
            throws Exception {
        JSONObject signedV1 = new JSONObject()
            .put("counted", new JSONArray().put("retired-profile|SAMPLE-0001"))
            .put("A", 4)
            .put("B", 2)
            .put("C", 1);
        assertTrue(LegacyPanelStateMigrationRules.validDailyStats(
            signedV1.toString(), profiles()));

        JSONObject scoped = new JSONObject(signedV1.toString())
            .put("results", new JSONObject().put("sample-one",
                new JSONObject().put("sample-pass", 3)));
        assertTrue(LegacyPanelStateMigrationRules.validDailyStats(
            scoped.toString(), profiles()));
        scoped.getJSONObject("results").put("other-panel-profile",
            new JSONObject().put("sample-pass", 9));
        assertFalse(LegacyPanelStateMigrationRules.validDailyStats(
            scoped.toString(), profiles()));
    }

    @Test
    public void receiptBoundHistorySurvivesCatalogRemovalButCannotBeInitiallyAdopted()
            throws Exception {
        JSONArray currentProfiles = new JSONArray()
            .put(new JSONObject().put("id", "sample-one"));
        String removedProfileLedger = ledger("sample-two");
        assertTrue(LegacyPanelStateMigrationRules.validRoundLedger(
            removedProfileLedger));
        assertFalse(LegacyPanelStateMigrationRules.validRoundLedger(
            removedProfileLedger, currentProfiles));

        JSONObject priorStats = new JSONObject().put("results", new JSONObject()
            .put("sample-two", new JSONObject().put("sample-pass", 3)));
        assertTrue(LegacyPanelStateMigrationRules.validDailyStats(
            priorStats.toString()));
        assertFalse(LegacyPanelStateMigrationRules.validDailyStats(
            priorStats.toString(), currentProfiles));

        assertTrue(RollbackMirrorRules.validStringArray("[\"sample-code\"]"));
        assertFalse(LegacyPanelStateMigrationRules.validPreviousRoundKey(
            "prevRoundMissing_sample-two", currentProfiles));
    }
}
