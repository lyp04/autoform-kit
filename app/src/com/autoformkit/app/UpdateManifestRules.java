package com.autoformkit.app;

import java.io.IOException;
import java.util.Locale;

/** Pure validation for signed-release metadata consumed before an APK download starts. */
final class UpdateManifestRules {
    private UpdateManifestRules() {
    }

    static String requireSha256(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.startsWith("sha256:")) value = value.substring("sha256:".length()).trim();
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException("update.json must contain a 64-character SHA-256 digest");
        }
        return value;
    }
}
