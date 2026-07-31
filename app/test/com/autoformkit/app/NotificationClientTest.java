package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public class NotificationClientTest {
    private static final String PANEL_BASE = "https://panel.example";
    private static final String ACCESS_KEY = "example-read-key";
    private static final String PAIR_V7 =
        "0707070707070707070707070707070707070707070707070707070707070707";
    private static final String PAIR_V8 =
        "0808080808080808080808080808080808080808080808080808080808080808";

    @Test
    public void payloadUsesOnlyTheVersionedPanelContract() throws Exception {
        JSONObject data = new JSONObject()
            .put("success", true)
            .put("submittedCount", 2);
        JSONObject payload = NotificationClient.payload(
            NotificationClient.EVENT_SUBMISSION_SUMMARY, data);

        assertEquals(3, payload.length());
        assertEquals(2, payload.getInt("version"));
        assertEquals(NotificationClient.EVENT_SUBMISSION_SUMMARY, payload.getString("type"));
        assertEquals(2, payload.getJSONObject("data").getInt("submittedCount"));
        assertFalse(payload.has("content"));
        assertFalse(payload.has("message"));
    }

    @Test
    public void v3PayloadCarriesOnlyTheStrictStructuredRound() throws Exception {
        JSONObject data = NotificationEventData.submissionRound(
            false, "Example profile", "Example operator", "2026-07-22T12:34:56Z", 2,
            Collections.singletonList(NotificationEventData.missingItem("Example item", 1)),
            Collections.emptyList(), Collections.emptyList(),
            Collections.singletonList("Example failure"),
            Collections.singletonList("EXAMPLE-UNIT-001"), Collections.emptyList());

        JSONObject payload = NotificationClient.payload(
            3, NotificationClient.EVENT_SUBMISSION_ROUND, data);

        assertEquals(3, payload.getInt("version"));
        assertEquals(NotificationClient.EVENT_SUBMISSION_ROUND, payload.getString("type"));
        assertEquals("Example profile", payload.getJSONObject("data").getString("profileLabel"));
        assertEquals(3, payload.length());
        assertFalse(payload.has("provider"));
        assertFalse(payload.has("message"));
    }

    @Test
    public void v3PayloadRejectsUnknownEventAndMutatedData() throws Exception {
        JSONObject data = NotificationEventData.submissionRound(
            true, "Example profile", "", "2026-07-22T12:34:56Z", 1,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        try {
            NotificationClient.payload(3, NotificationClient.EVENT_SUBMISSION_SUMMARY, data);
            fail("v2 event must not cross the v3 contract");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed behavior.
        }

        data.put("unknown", true);
        try {
            NotificationClient.payload(3, NotificationClient.EVENT_SUBMISSION_ROUND, data);
            fail("mutated data must not cross the v3 contract");
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed behavior.
        }
    }

    @Test
    public void halfUpdatedDiskPairCannotReplaceActiveNotificationSnapshot() throws Exception {
        String connection = AppConfig.connectionNamespaceId(PANEL_BASE, ACCESS_KEY);
        JSONObject settingsV7 = new JSONObject();
        JSONObject configV7 = notificationConfig(7, "/notify-v7");
        NotificationClient.Snapshot activeV7 = NotificationClient.captureSnapshot(
            PANEL_BASE, ACCESS_KEY, connection, configV7, settingsV7, 7, PAIR_V7);
        assertNotNull(activeV7);

        // Config v8 arriving before catalog v8 is not a capturable notification pair.
        NotificationClient.Snapshot mixedV8V7 = NotificationClient.captureSnapshot(
            PANEL_BASE, ACCESS_KEY, connection,
            notificationConfig(8, "/notify-v8"), settingsV7, 7, PAIR_V8);
        assertNull(mixedV8V7);

        // The already-captured v7 endpoint remains immutable even if the source JSON is mutated.
        configV7.getJSONObject("notification").put("endpoint", "/mutated");
        assertEquals(PANEL_BASE + "/notify-v7", NotificationClient.resolvedEndpoint(
            activeV7, connection, NotificationClient.EVENT_SUBMISSION_SUMMARY));
        assertEquals("", NotificationClient.resolvedEndpoint(
            activeV7, AppConfig.connectionNamespaceId("https://other.example", ACCESS_KEY),
            NotificationClient.EVENT_SUBMISSION_SUMMARY));
    }

    @Test
    public void sampleOrMismatchedCatalogCannotCreateNotificationSnapshot() throws Exception {
        String connection = AppConfig.connectionNamespaceId(PANEL_BASE, ACCESS_KEY);
        JSONObject config = notificationConfig(7, "/notify-v7");
        assertNull(NotificationClient.captureSnapshot(PANEL_BASE, ACCESS_KEY, connection,
            config, new JSONObject().put("sampleCatalog", true), 7, PAIR_V7));
        assertNull(NotificationClient.captureSnapshot(PANEL_BASE, ACCESS_KEY, connection,
            config, new JSONObject(), 8, PAIR_V8));
        assertNull(NotificationClient.captureSnapshot(PANEL_BASE, ACCESS_KEY, connection,
            config, new JSONObject(), 7, ""));
    }

    @Test
    public void sameConnectionAndVersionCannotAliasDifferentPairDigests() throws Exception {
        String connection = AppConfig.connectionNamespaceId(PANEL_BASE, ACCESS_KEY);
        JSONObject config = notificationConfig(7, "/notify-v7");
        NotificationClient.Snapshot first = NotificationClient.captureSnapshot(
            PANEL_BASE, ACCESS_KEY, connection, config, new JSONObject(), 7, PAIR_V7);
        NotificationClient.Snapshot changedPair = NotificationClient.captureSnapshot(
            PANEL_BASE, ACCESS_KEY, connection, config, new JSONObject(), 7, PAIR_V8);

        assertNotNull(first);
        assertNotNull(changedPair);
        assertFalse(NotificationClient.samePairIdentity(first, changedPair));
        assertFalse(NotificationClient.sameInstalledGeneration(first, changedPair));
        String firstQueue = NotificationClient.queuePairNamespace(first);
        String changedQueue = NotificationClient.queuePairNamespace(changedPair);
        assertEquals(64, firstQueue.length());
        assertEquals(64, changedQueue.length());
        assertFalse(firstQueue.equals(changedQueue));
        assertFalse(firstQueue.contains(connection));
        assertFalse(firstQueue.contains(PAIR_V7));
    }

    @Test
    public void installedGenerationRejectsDetachedAndSupersededSnapshots() {
        assertFalse(NotificationClient.sameInstalledGeneration(0L, 0L));
        assertFalse(NotificationClient.sameInstalledGeneration(17L, 18L));
        assertTrue(NotificationClient.sameInstalledGeneration(18L, 18L));
    }

    private static JSONObject notificationConfig(int catalogVersion, String endpoint)
            throws Exception {
        return new JSONObject()
            .put("catalogVersion", catalogVersion)
            .put("notification", new JSONObject()
                .put("version", 2)
                .put("enabled", true)
                .put("endpoint", endpoint)
                .put("eventTypes", new org.json.JSONArray()
                    .put(NotificationClient.EVENT_SUBMISSION_SUMMARY))
                .put("diagnosticsEnabled", false));
    }
}
