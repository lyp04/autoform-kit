package com.autoformkit.app.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.LinkedHashMap;

public class FingerprintTest {
    @Test
    public void failureIdentityDoesNotIncludeDeviceOrBuildMetadata() {
        String expected = Fingerprint.compute("print", "label_failed_after_retry", "print_adapter",
                new LinkedHashMap<>());
        assertEquals(expected, Fingerprint.computeFailure(
                "print", "label_failed_after_retry", "print_adapter", false));
    }

    @Test
    public void dnsUsesOnlyAFixedCategory() {
        String dns = Fingerprint.computeFailure(
                "network", "unknown_host", "api_request", true);
        String nonDns = Fingerprint.computeFailure(
                "network", "unknown_host", "api_request", false);
        assertNotEquals(dns, nonDns);
        assertEquals(dns, Fingerprint.computeFailure(
                "network", "unknown_host", "api_request", true));
    }
}
