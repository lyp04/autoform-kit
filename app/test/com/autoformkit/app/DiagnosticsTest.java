package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticsTest {
    @Test
    public void supportTextRedactsConnectionSecretsAndProductionIdentifiers() {
        String raw = "https://panel.example/api token=secret-1234567890 "
            + "accessKey: abcdef1234567890 SN=ABC12345678901234 "
            + "operator@example.com /data/user/0/com.example/files/photo.jpg "
            + "UNIT123456789012";
        String safe = Diagnostics.sanitizeForSupport(raw);

        assertFalse(safe.contains("panel.example"));
        assertFalse(safe.contains("secret-1234567890"));
        assertFalse(safe.contains("abcdef1234567890"));
        assertFalse(safe.contains("ABC12345678901234"));
        assertFalse(safe.contains("operator@example.com"));
        assertFalse(safe.contains("com.example/files"));
        assertFalse(safe.contains("UNIT123456789012"));
        assertTrue(safe.contains("[redacted]"));
        assertTrue(safe.contains("[identifier]"));
    }
}
