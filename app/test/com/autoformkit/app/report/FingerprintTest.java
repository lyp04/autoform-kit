package com.autoformkit.app.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.LinkedHashMap;

public class FingerprintTest {
    @Test
    public void failureIdentityDoesNotIncludeDeviceOrBuildMetadata() {
        String expected = Fingerprint.compute("print", "label_failed_after_retry", "cloud_box",
                new LinkedHashMap<>());
        assertEquals(expected, Fingerprint.computeFailure(
                "print", "label_failed_after_retry", "cloud_box", ""));
    }

    @Test
    public void dnsTargetRemainsAStableRootCauseDiscriminator() {
        String api = Fingerprint.computeFailure("dns", "unknown_host", "api_request",
                "api.backend.example");
        String control = Fingerprint.computeFailure("dns", "unknown_host", "api_request",
                "dns.google");
        assertNotEquals(api, control);
        assertEquals(api, Fingerprint.computeFailure("dns", "unknown_host", "api_request",
                "api.backend.example"));
    }
}
