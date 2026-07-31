package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

public class NotificationEventDataTest {
    @Test
    public void completedAtFormatterProducesApi23CompatibleRfc3339Offsets() {
        assertEquals("1970-01-01T00:00:00Z",
            NotificationEventData.formatCompletedAt(0L, TimeZone.getTimeZone("UTC")));
        assertEquals("1969-12-31T17:00:00-07:00",
            NotificationEventData.formatCompletedAt(
                0L, TimeZone.getTimeZone("GMT-07:00")));
        assertEquals("1970-01-01T05:30:00+05:30",
            NotificationEventData.formatCompletedAt(
                0L, TimeZone.getTimeZone("GMT+05:30")));
    }

    @Test
    public void completedAtFormatterRejectsMissingTimeZone() {
        try {
            NotificationEventData.formatCompletedAt(0L, null);
            fail("missing time zone must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeZone"));
        }
    }

    @Test
    public void submissionSummaryContainsOnlyAggregateAllowlistedFields() {
        JSONObject data = NotificationEventData.submissionSummary(
            false, 3, 2, 1, 4, 2, 1, 1);

        assertEquals(8, data.length());
        assertEquals(3, data.optInt("submittedCount"));
        assertFalse(data.has("profile"));
        assertFalse(data.has("account"));
        assertFalse(data.has("identifier"));
        assertFalse(data.has("message"));
        assertFalse(data.has("errors"));
    }

    @Test
    public void submissionRoundContainsOnlyTheStrictV3Fields() throws Exception {
        JSONObject data = NotificationEventData.submissionRound(
            false,
            "Example profile",
            "Example operator",
            "2026-07-22T12:34:56-07:00",
            3,
            Collections.singletonList(NotificationEventData.missingItem("Example item", 2)),
            Collections.singletonList("Example new item"),
            Collections.singletonList("Example recovered item"),
            Collections.singletonList("Example failure detail"),
            Collections.singletonList("EXAMPLE-UNIT-001"),
            Collections.singletonList("EXAMPLE-UNIT-002"));

        assertEquals(11, data.length());
        assertEquals(2, data.getJSONArray("missingItems")
            .getJSONObject(0).getInt("affectedCount"));
        assertTrue(NotificationEventData.isValidSubmissionRound(data));
        assertFalse(data.has("url"));
        assertFalse(data.has("template"));
        assertFalse(data.has("successMarker"));
        assertFalse(data.has("message"));
    }

    @Test
    public void submissionRoundRejectsOversizeArraysAndStrings() {
        try {
            NotificationEventData.submissionRound(
                true,
                "Example profile",
                "",
                "2026-07-22T12:34:56Z",
                1,
                Collections.emptyList(),
                Collections.nCopies(NotificationEventData.MAX_ROUND_ITEMS + 1, "Example"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
            fail("oversize array must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("newMissingItems"));
        }

        char[] chars = new char[NotificationEventData.MAX_DETAIL_LENGTH + 1];
        Arrays.fill(chars, 'x');
        try {
            NotificationEventData.submissionRound(
                true,
                "Example profile",
                "",
                "2026-07-22T12:34:56Z",
                1,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonList(new String(chars)),
                Collections.emptyList(),
                Collections.emptyList());
            fail("oversize detail must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("errors"));
        }
    }

    @Test
    public void submissionRoundValidatorRejectsUnknownOrMalformedFields() throws Exception {
        JSONObject data = NotificationEventData.submissionRound(
            true, "Example profile", "", "2026-07-22T12:34:56Z", 1,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        data.put("providerUrl", "https://private.example.invalid");
        assertFalse(NotificationEventData.isValidSubmissionRound(data));
        data.remove("providerUrl");
        data.put("completedAt", "2026-07-22 12:34:56");
        assertFalse(NotificationEventData.isValidSubmissionRound(data));
    }
}
