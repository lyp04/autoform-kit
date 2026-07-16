package com.autoformkit.app.report;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

public class FailureReporterTest {
    @Test
    public void emptyDnsContextDoesNotTurnOrdinarySubmitFailureIntoDnsIncident() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("dns_target", "");
        assertFalse(FailureReporter.isDnsStage("submit", "IOException", ctx,
                new java.io.IOException("template missing")));
    }

    @Test
    public void realDnsTargetOrUnknownHostStillTriggersDiagnostics() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("dns_target", "api2.besender.com");
        assertTrue(FailureReporter.isDnsStage("submit", "IOException", ctx, null));
        assertTrue(FailureReporter.isDnsStage("submit", "IOException", null,
                new UnknownHostException("api2.besender.com")));
    }
}
