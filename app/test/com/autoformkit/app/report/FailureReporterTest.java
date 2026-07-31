package com.autoformkit.app.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;

public class FailureReporterTest {
    @Test
    public void emptyDnsContextDoesNotTurnOrdinarySubmitFailureIntoDnsIncident() {
        assertFalse(FailureReporter.isDnsStage("submit", "IOException",
                new java.io.IOException("template missing")));
    }

    @Test
    public void realDnsTargetOrUnknownHostStillTriggersDiagnostics() {
        assertTrue(FailureReporter.isDnsStage("dns", "IOException", null));
        assertTrue(FailureReporter.isDnsStage("submit", "IOException",
                new UnknownHostException("api.backend.example")));
    }

    @Test
    public void queuedDiagnosticWorkCannotCrossPanelPairGeneration() {
        assertTrue(FailureReporter.samePairGeneration(4L, 4L));
        assertFalse(FailureReporter.samePairGeneration(4L, 5L));
    }

    @Test
    public void queueRequiresExactOpaquePairMarker() {
        String currentPair =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String otherPair =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        assertFalse(FailureReporter.queueBoundaryIsProven(
            false, "", currentPair));
        assertFalse(FailureReporter.queueBoundaryIsProven(
            true, otherPair, currentPair));
        assertFalse(FailureReporter.queueBoundaryIsProven(
            true, "not-a-digest", "not-a-digest"));
        assertTrue(FailureReporter.queueBoundaryIsProven(
            true, currentPair, currentPair));
    }

    @Test
    public void runtimeEventDataUsesExactAllowlistAndDropsSensitiveLegacyFields() throws Exception {
        String recordIdentifier = "RECORD-MARKER-7F91";
        String account = "ACCOUNT-MARKER-2B63";
        String profile = "PROFILE-MARKER-4D85";
        String absolutePath = "/data/user/0/example.invalid/files/PATH-MARKER-6A17.jpg";
        String url = "https://backend.example.invalid/private/URL-MARKER-8C39";
        String backendJson = "{\"record\":\"BACKEND-MARKER-1E52\",\"ok\":false}";
        String token = "TOKEN-MARKER-3F74";

        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        context.put("app_version", "1.2.3-test");
        context.put("git_head", "abc123def");
        context.put("android_sdk", "35");
        context.put("net_active", "wifi");
        context.put("net_validated", "true");
        context.put("net_captive", "false");
        context.put("net_internet", "true");
        context.put("net_not_metered", "false");
        context.put("net_vpn", "false");
        context.put("record_identifier", recordIdentifier);
        context.put("account", account);
        context.put("profile", profile);
        context.put("absolute_path", absolutePath);
        context.put("url", url);
        context.put("backend_response", backendJson);
        context.put("token", token);
        context.put("diagnostic_log_tail", String.join(" | ", Arrays.asList(
            recordIdentifier, account, profile, absolutePath, url, backendJson, token)));

        JSONObject legacy = new JSONObject()
            .put("stage", "submit")
            .put("errCode", "IOException")
            .put("subphase", "submit_unit")
            .put("message", "message " + recordIdentifier + " " + account + " " + profile + " " + url)
            .put("throwable", "stack " + absolutePath + " " + backendJson + " " + token)
            .put("ctx", new JSONObject(context))
            .put("ts", 123456789L)
            .put("fp", "abcd1234");
        FailureEvent event = FailureEvent.fromJson(legacy);

        JSONObject data = FailureReporter.runtimeEventData(event);
        Set<String> expectedKeys = new HashSet<>(Arrays.asList(
            "stage", "errorCode", "subphase", "fingerprint", "appVersion", "gitHead",
            "androidSdk", "networkTransport", "networkValidated", "networkCaptive",
            "networkInternet", "networkMetered", "networkVpn"));
        Set<String> actualKeys = new HashSet<>();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) actualKeys.add(keys.next());

        assertEquals(expectedKeys, actualKeys);
        assertEquals("submit", data.getString("stage"));
        assertEquals("io_exception", data.getString("errorCode"));
        assertEquals("submit_unit", data.getString("subphase"));
        assertEquals("abcd1234", data.getString("fingerprint"));
        assertEquals("1.2.3-test", data.getString("appVersion"));
        assertEquals("abc123def", data.getString("gitHead"));
        assertEquals(35, data.getInt("androidSdk"));
        assertEquals("wifi", data.getString("networkTransport"));
        assertTrue(data.getBoolean("networkValidated"));
        assertFalse(data.getBoolean("networkCaptive"));
        assertTrue(data.getBoolean("networkInternet"));
        assertTrue(data.getBoolean("networkMetered"));
        assertFalse(data.getBoolean("networkVpn"));

        String serialized = data.toString();
        for (String marker : Arrays.asList(recordIdentifier, account, profile, absolutePath,
                url, backendJson, token)) {
            assertFalse("runtime event leaked marker: " + marker, serialized.contains(marker));
        }
        assertFalse(data.has("message"));
        assertFalse(data.has("throwable"));
        assertFalse(data.has("context"));
        assertFalse(data.has("timestamp"));
    }
}
