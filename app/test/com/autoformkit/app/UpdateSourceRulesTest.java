package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class UpdateSourceRulesTest {
    @Test
    public void missingStructuredSourcePreservesLegacyStableAndSavedBetaRoutes() throws Exception {
        JSONObject panel = legacyPanel();
        UpdateSourceRules.Resolved stable =
            UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
        assertTrue(stable.enabled);
        assertEquals("stable", stable.channel);
        assertEquals("sample-owner", stable.owner);
        assertEquals("sample-repo", stable.repo);
        assertEquals("update.json", stable.manifestAsset);
        assertEquals("", stable.releaseTag);

        UpdateSourceRules.Resolved beta =
            UpdateSourceRules.resolve(neutralApkConfig(), panel, "beta");
        assertEquals("beta", beta.channel);
        assertEquals("update.json", beta.manifestAsset);
        assertEquals("beta", beta.releaseTag);
    }

    @Test
    public void missingOrUnknownPreferenceAlwaysUsesStable() {
        assertEquals("stable", UpdateSourceRules.deviceChannel(null));
        assertEquals("stable", UpdateSourceRules.deviceChannel(""));
        assertEquals("stable", UpdateSourceRules.deviceChannel("stable"));
        assertEquals("stable", UpdateSourceRules.deviceChannel("nightly"));
        assertEquals("beta", UpdateSourceRules.deviceChannel("beta"));
    }

    @Test
    public void structuredSourceChangesOnlyCoordinatesNotApkRoutes() throws Exception {
        JSONObject panel = structuredPanel();
        UpdateSourceRules.Resolved stable =
            UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
        assertEquals("sample-owner", stable.owner);
        assertEquals("sample-repo", stable.repo);
        assertEquals("update.json", stable.manifestAsset);
        assertEquals("", stable.releaseTag);

        UpdateSourceRules.Resolved beta =
            UpdateSourceRules.resolve(neutralApkConfig(), panel, "beta");
        assertEquals("update.json", beta.manifestAsset);
        assertEquals("beta", beta.releaseTag);
    }

    @Test
    public void structuredSourcePreservesEveryExplicitInstalledApkChannelValue() throws Exception {
        JSONObject apk = neutralApkConfig()
            .put("stableManifestAsset", "stable-manifest.json")
            .put("stableReleaseTag", "stable-fixed")
            .put("betaManifestAsset", "beta-manifest.json")
            .put("betaReleaseTag", "beta-fixed");
        UpdateSourceRules.Resolved stable =
            UpdateSourceRules.resolve(apk, structuredPanel(), "stable");
        assertEquals("stable-manifest.json", stable.manifestAsset);
        assertEquals("stable-fixed", stable.releaseTag);
        UpdateSourceRules.Resolved beta =
            UpdateSourceRules.resolve(apk, structuredPanel(), "beta");
        assertEquals("beta-manifest.json", beta.manifestAsset);
        assertEquals("beta-fixed", beta.releaseTag);
    }

    @Test(expected = IllegalArgumentException.class)
    public void structuredSourceMustExactlyMatchFlatOwner() throws Exception {
        JSONObject panel = structuredPanel().put("updateOwner", "different-owner");
        UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void structuredSourceRequiresBothFlatCoordinates() throws Exception {
        JSONObject panel = structuredPanel();
        panel.put("updateRepo", JSONObject.NULL);
        UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidSentinelDisablesNewAppUpdateCheck() throws Exception {
        JSONObject panel = legacyPanel().put("updateSource", new JSONObject().put("version", 0));
        UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void remoteChannelOrTagFieldsAreUnsupported() throws Exception {
        JSONObject panel = structuredPanel();
        panel.getJSONObject("updateSource").put("defaultChannel", "beta");
        UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unnormalizedStructuredCoordinatesFailClosed() throws Exception {
        JSONObject panel = legacyPanel().put("updateSource", new JSONObject()
            .put("version", 1)
            .put("owner", " sample-owner ")
            .put("repo", "sample-repo"));
        UpdateSourceRules.resolve(neutralApkConfig(), panel, "stable");
    }

    private static JSONObject neutralApkConfig() throws Exception {
        return new JSONObject()
            .put("enabled", true)
            .put("owner", "")
            .put("repo", "")
            .put("manifestAsset", "update.json");
    }

    private static JSONObject legacyPanel() throws Exception {
        return new JSONObject()
            .put("updateOwner", "sample-owner")
            .put("updateRepo", "sample-repo");
    }

    private static JSONObject structuredPanel() throws Exception {
        return legacyPanel().put("updateSource", new JSONObject()
            .put("version", 1)
            .put("owner", "sample-owner")
            .put("repo", "sample-repo"));
    }
}
