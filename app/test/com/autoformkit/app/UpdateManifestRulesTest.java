package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class UpdateManifestRulesTest {
    @Test
    public void acceptsACompleteDigestAndOptionalPrefix() throws Exception {
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(digest, UpdateManifestRules.requireSha256(digest));
        assertEquals(digest, UpdateManifestRules.requireSha256("SHA256:" + digest.toUpperCase()));
    }

    @Test(expected = IOException.class)
    public void rejectsMissingDigest() throws Exception {
        UpdateManifestRules.requireSha256("");
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedDigest() throws Exception {
        UpdateManifestRules.requireSha256("not-a-digest");
    }
}
