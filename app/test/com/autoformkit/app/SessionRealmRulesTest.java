package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class SessionRealmRulesTest {
    private static final String CONNECTION = "a".repeat(64);

    private static JSONObject adapterFixture() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{cwd.resolve("panel/backend-adapter.example.json"),
                cwd.resolve("../panel/backend-adapter.example.json")}) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(Files.readAllBytes(path),
                    StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("backend adapter fixture not found");
    }

    private static JSONObject config() throws Exception {
        return new JSONObject()
            .put("backendAdapter", adapterFixture())
            .put("webOrigin", "https://app.example.invalid")
            .put("webReferer", "https://app.example.invalid/forms")
            .put("brand", "Fictional Brand")
            .put("catalogVersion", 7);
    }

    private static JSONObject settings() throws Exception {
        return new JSONObject()
            .put("daily", new JSONObject().put("enabled", true))
            .put("printPolicy", new JSONObject().put("mode", "example"));
    }

    private static String realm(JSONObject config, JSONObject settings) {
        String value = SessionRealmRules.fingerprint(CONNECTION, config, settings);
        assertTrue(value, SessionRealmRules.validDigest(value));
        return value;
    }

    @Test
    public void coversExactlyTheTwelveAuthorizationCapableEndpoints() {
        assertEquals(Arrays.asList(
            "captcha", "detectionData", "labelRetry", "login", "loginVerify",
            "messageList", "printerState", "snRepetition", "submitEntry",
            "templateDetail", "uploadFile", "userInfo"),
            SessionRealmRules.AUTHORIZATION_ENDPOINT_KEYS);
        assertFalse(SessionRealmRules.AUTHORIZATION_ENDPOINT_KEYS.contains("recognizeText"));
        assertFalse(SessionRealmRules.AUTHORIZATION_ENDPOINT_KEYS.contains("templateList"));
    }

    @Test
    public void projectionPinsTheAdapterVersionExplicitly() throws Exception {
        JSONObject projection = SessionRealmRules.projection(CONNECTION, config(), settings());
        assertEquals(BackendAdapter.SUPPORTED_VERSION,
            projection.getInt("adapterVersion"));
    }

    @Test
    public void everyTransportRealmFieldChangesTheFingerprint() throws Exception {
        JSONObject original = config();
        String baseline = realm(original, settings());

        JSONObject base = new JSONObject(original.toString());
        base.getJSONObject("backendAdapter").put("baseUrl", "https://other.example.invalid/api");
        assertNotEquals(baseline, realm(base, settings()));

        for (String endpoint : SessionRealmRules.AUTHORIZATION_ENDPOINT_KEYS) {
            JSONObject changed = new JSONObject(original.toString());
            changed.getJSONObject("backendAdapter").getJSONObject("endpoints")
                .put(endpoint, "https://edge.example.invalid/" + endpoint + "?v=2");
            assertNotEquals(endpoint, baseline, realm(changed, settings()));
        }

        for (String field : new String[]{"bodyEncoding", "authScheme", "fingerprintHeader",
                "webUserAgent", "webAcceptLanguage"}) {
            JSONObject changed = new JSONObject(original.toString());
            JSONObject request = changed.getJSONObject("backendAdapter")
                .getJSONObject("request");
            request.put(field, "bodyEncoding".equals(field) ? "json" : "changed-" + field);
            assertNotEquals(field, baseline, realm(changed, settings()));
        }

        for (String field : new String[]{"webOrigin", "webReferer"}) {
            JSONObject changed = new JSONObject(original.toString());
            changed.put(field, "https://other.example.invalid/" + field);
            assertNotEquals(field, baseline, realm(changed, settings()));
        }

        for (String field : new String[]{"account", "password", "captcha", "client"}) {
            JSONObject changed = new JSONObject(original.toString());
            changed.getJSONObject("backendAdapter").getJSONObject("auth")
                .getJSONObject("loginFields").put(field, "changed_" + field);
            assertNotEquals(field, baseline, realm(changed, settings()));
        }
        assertNotEquals(baseline,
            SessionRealmRules.fingerprint("b".repeat(64), original, settings()));
    }

    @Test
    public void profileAndOrdinaryPoliciesDoNotChangeTheRealm() throws Exception {
        JSONObject originalConfig = config();
        JSONObject originalSettings = settings();
        String baseline = realm(originalConfig, originalSettings);

        JSONObject changedConfig = new JSONObject(originalConfig.toString())
            .put("brand", "Another display brand")
            .put("catalogVersion", 999)
            .put("profile", new JSONObject().put("id", "other-form"));
        JSONObject changedSettings = new JSONObject(originalSettings.toString())
            .put("daily", new JSONObject().put("enabled", false))
            .put("retryPolicy", new JSONObject().put("attempts", 99))
            .put("outcomePolicy", new JSONObject().put("mode", "changed"))
            .put("printPolicy", new JSONObject().put("mode", "changed"));

        assertEquals(baseline, realm(changedConfig, changedSettings));
    }

    @Test
    public void normalizesEquivalentUrlsButKeepsCrossHostAndQueryDifferences() throws Exception {
        JSONObject first = config();
        JSONObject second = new JSONObject(first.toString());
        second.getJSONObject("backendAdapter")
            .put("baseUrl", "https://BACKEND.example.invalid:443/api/")
            .getJSONObject("endpoints")
            .put("userInfo", "./users/../users/me");
        assertEquals(realm(first, settings()), realm(second, settings()));

        JSONObject crossHost = new JSONObject(first.toString());
        crossHost.getJSONObject("backendAdapter").getJSONObject("endpoints")
            .put("userInfo", "https://identity.example.invalid/me?tenant=sample");
        assertNotEquals(realm(first, settings()), realm(crossHost, settings()));
    }

    @Test
    public void malformedOrUnsupportedProjectionFailsClosed() throws Exception {
        assertEquals("", SessionRealmRules.fingerprint("", config(), settings()));
        JSONObject unsupported = config();
        unsupported.getJSONObject("backendAdapter").put("version", 999);
        // Version is an explicit projection field; an unsupported future version must not be
        // silently interpreted as today's realm schema.
        assertEquals("", SessionRealmRules.fingerprint(CONNECTION, unsupported, settings()));
        JSONObject invalidBase = config();
        invalidBase.getJSONObject("backendAdapter").put("baseUrl", "file:///tmp/example");
        assertEquals("", SessionRealmRules.fingerprint(CONNECTION, invalidBase, settings()));
    }

    @Test
    public void encryptedCredentialAadChangesWithRealmFingerprintAndRecordType()
            throws Exception {
        String realm = realm(config(), settings());
        String session = "0123456789abcdef0123456789abcdef";
        byte[] tokenAad = SessionRealmRules.credentialAad(
            "token", realm, "0123456789abcdef", session);
        assertFalse(Arrays.equals(tokenAad, SessionRealmRules.credentialAad(
            "token", "b".repeat(64), "0123456789abcdef", session)));
        assertFalse(Arrays.equals(tokenAad, SessionRealmRules.credentialAad(
            "token", realm, "fedcba9876543210", session)));
        assertFalse(Arrays.equals(tokenAad, SessionRealmRules.credentialAad(
            "password", realm, "0123456789abcdef", session)));
        assertFalse(Arrays.equals(tokenAad, SessionRealmRules.credentialAad(
            "token", realm, "0123456789abcdef", "fedcba9876543210")));
        assertNotEquals(
            SessionRealmRules.credentialValueBindingSha256(
                "account", realm, "0123456789abcdef", session, "same-value"),
            SessionRealmRules.credentialValueBindingSha256(
                "userName", realm, "0123456789abcdef", session, "same-value"));
    }

}
