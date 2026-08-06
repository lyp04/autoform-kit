package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AlternateEntrySelectionStateTest {
    private static final String CONNECTION_A = "0123456789abcdefabcd";
    private static final String CONNECTION_B = "fedcba9876543210fedc";

    private static JSONArray sources(String... ids) throws Exception {
        JSONArray values = new JSONArray();
        for (String id : ids) values.put(new JSONObject().put("id", id));
        return values;
    }

    @Test
    public void strictRoundTripAndPreferenceKeyBindConnectionAndEntry() {
        AlternateEntrySelectionState state = AlternateEntrySelectionState.create(
            CONNECTION_A, "problem-entry", "profile-b");
        AlternateEntrySelectionState parsed = AlternateEntrySelectionState.parse(
            state.toJson().toString());

        assertEquals("profile-b", parsed.sourceProfileId);
        assertTrue(parsed.matches(CONNECTION_A, "problem-entry"));
        assertFalse(parsed.matches(CONNECTION_B, "problem-entry"));
        assertFalse(parsed.matches(CONNECTION_A, "other-entry"));

        String key = AlternateEntrySelectionState.preferenceKey(
            CONNECTION_A, "problem-entry");
        assertTrue(key.startsWith(AlternateEntrySelectionState.PREFERENCE_PREFIX));
        assertEquals(AlternateEntrySelectionState.PREFERENCE_PREFIX.length() + 64,
            key.length());
        assertFalse(key.contains("problem-entry"));
        assertFalse(key.equals(AlternateEntrySelectionState.preferenceKey(
            CONNECTION_B, "problem-entry")));
        assertFalse(key.equals(AlternateEntrySelectionState.preferenceKey(
            CONNECTION_A, "other-entry")));
    }

    @Test
    public void rememberedSourceWinsByStableIdAcrossCatalogReorder() throws Exception {
        AlternateEntrySelectionState remembered = AlternateEntrySelectionState.create(
            CONNECTION_A, "entry", "profile-b");

        assertEquals(1, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-a", "profile-b", "profile-c"), remembered,
            CONNECTION_A, "entry", "profile-a"));
        assertEquals(0, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-b", "profile-c", "profile-a"), remembered,
            CONNECTION_A, "entry", "profile-a"));
    }

    @Test
    public void exactDraftSourceWinsAcrossReorderAndNeverFallsBackToFirst() throws Exception {
        AlternateEntrySelectionState remembered = AlternateEntrySelectionState.create(
            CONNECTION_A, "entry", "profile-a");

        assertEquals(2, AlternateEntrySelectionState.pageSourceIndex(
            sources("profile-a", "profile-c", "profile-b"), "profile-b", remembered,
            CONNECTION_A, "entry", "profile-a"));
        assertEquals(0, AlternateEntrySelectionState.pageSourceIndex(
            sources("profile-b", "profile-a", "profile-c"), "profile-b", remembered,
            CONNECTION_A, "entry", "profile-a"));
        assertEquals(-1, AlternateEntrySelectionState.pageSourceIndex(
            sources("profile-a", "profile-c"), "profile-b", remembered,
            CONNECTION_A, "entry", "profile-a"));
        assertEquals(-1, AlternateEntrySelectionState.pageSourceIndex(
            sources("profile-b", "profile-b", "profile-a"), "profile-b", remembered,
            CONNECTION_A, "entry", "profile-a"));
    }

    @Test
    public void staleOrWronglyScopedSelectionFallsBackToCurrentThenFirst() throws Exception {
        AlternateEntrySelectionState stale = AlternateEntrySelectionState.create(
            CONNECTION_A, "entry", "removed-profile");
        assertEquals(1, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-a", "profile-c"), stale,
            CONNECTION_A, "entry", "profile-c"));
        assertEquals(0, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-a", "profile-c"), stale,
            CONNECTION_A, "entry", "not-present"));

        AlternateEntrySelectionState otherPanel = AlternateEntrySelectionState.create(
            CONNECTION_B, "entry", "profile-c");
        assertEquals(0, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-a", "profile-c"), otherPanel,
            CONNECTION_A, "entry", "profile-a"));
        assertEquals(0, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-a", "profile-c"), stale,
            CONNECTION_A, "other-entry", "profile-a"));
    }

    @Test
    public void duplicateRememberedIdentityFailsClosedAndEmptyListReturnsMinusOne()
            throws Exception {
        AlternateEntrySelectionState remembered = AlternateEntrySelectionState.create(
            CONNECTION_A, "entry", "profile-b");
        assertEquals(2, AlternateEntrySelectionState.preferredSourceIndex(
            sources("profile-b", "profile-b", "profile-c"), remembered,
            CONNECTION_A, "entry", "profile-c"));
        assertEquals(-1, AlternateEntrySelectionState.preferredSourceIndex(
            new JSONArray(), remembered, CONNECTION_A, "entry", "profile-c"));
    }

    @Test
    public void malformedOrAmbiguousSerializedValuesAreRejected() throws Exception {
        JSONObject valid = AlternateEntrySelectionState.create(
            CONNECTION_A, "entry", "profile-a").toJson();

        JSONObject unknown = new JSONObject(valid.toString()).put("extra", true);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntrySelectionState.parse(unknown.toString()));
        JSONObject wrongVersion = new JSONObject(valid.toString()).put("version", 2);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntrySelectionState.parse(wrongVersion.toString()));
        JSONObject wrongType = new JSONObject(valid.toString()).put("sourceProfileId", 7);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntrySelectionState.parse(wrongType.toString()));
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntrySelectionState.create("not-a-namespace", "entry", "profile"));
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntrySelectionState.create(CONNECTION_A, " entry", "profile"));
    }
}
