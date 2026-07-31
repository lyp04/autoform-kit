package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SessionCredentialBindingTest {
    private static final String REALM_A = "a".repeat(64);
    private static final String REALM_B = "b".repeat(64);
    private static final String FINGERPRINT_A = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN_A = "sample-token-a";
    private static final String SESSION_A = "1234567890abcdef1234567890abcdef";
    private static final String STATE_A = "abcdefabcdefabcdefabcdefabcdefab";

    @Test
    public void accountAndUserNameRequireRealmFingerprintAndActualValueDigest() {
        Map<String, Object> initial = boundIdentity(REALM_A, FINGERPRINT_A);
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);

        assertEquals("sample-account",
            SecureTokenStore.getAccountForBinding(prefs, REALM_A, FINGERPRINT_A));
        assertEquals("Sample User",
            SecureTokenStore.getUserNameForBinding(prefs, REALM_A, FINGERPRINT_A));
        assertEquals("", SecureTokenStore.getAccountForBinding(
            prefs, REALM_B, FINGERPRINT_A));
        assertEquals("", SecureTokenStore.getUserNameForBinding(
            prefs, REALM_A, "fedcba9876543210fedcba9876543210"));

        prefs.memory.put("sessionV2Account", "tampered-account");
        prefs.memory.put("sessionV2UserName", "Tampered User");
        assertEquals("", SecureTokenStore.getAccountForBinding(
            prefs, REALM_A, FINGERPRINT_A));
        assertEquals("", SecureTokenStore.getUserNameForBinding(
            prefs, REALM_A, FINGERPRINT_A));
    }

    @Test
    public void legacyMissingBindingIsUnreadableWithoutDeletingRollbackBytes() {
        Map<String, Object> initial = new HashMap<>();
        initial.put("token", TOKEN_A);
        initial.put("account", "sample-account");
        initial.put("userName", "Sample User");
        initial.put("pwdCipher", "old-password-bytes");
        initial.put("web_client_fingerprint", FINGERPRINT_A);
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);

        assertEquals("", SecureTokenStore.getForBinding(prefs, REALM_A, FINGERPRINT_A));
        assertEquals("", SecureTokenStore.getAccountForBinding(
            prefs, REALM_A, FINGERPRINT_A));
        assertEquals("", SecureTokenStore.getBoundWebFingerprint(prefs, REALM_A));
        assertFalse(SecureTokenStore.reconcileForBinding(
            prefs, REALM_A, FINGERPRINT_A));
        assertEquals(initial, prefs.memory);
        assertEquals(initial, prefs.durable);
    }

    @Test
    public void preLoginFingerprintIsReusedOnlyInsideTheSameRealm() {
        Map<String, Object> initial = new HashMap<>();
        initial.put("web_client_fingerprint", FINGERPRINT_A); // legacy, deliberately unbound
        initial.put("token", TOKEN_A);
        initial.put("account", "sample-account");
        initial.put("draft_v2", "must-stay");
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);

        String first = SecureTokenStore.webFingerprintForRealm(prefs, REALM_A);
        assertTrue(first.length() >= 16);
        assertNotEquals(FINGERPRINT_A, first);
        assertEquals(first, SecureTokenStore.webFingerprintForRealm(prefs, REALM_A));
        String secondRealm = SecureTokenStore.webFingerprintForRealm(prefs, REALM_B);
        assertTrue(secondRealm.length() >= 16);
        assertNotEquals(first, secondRealm);

        // Realm rotation is logical isolation, not destructive cleanup.
        assertEquals(TOKEN_A, prefs.memory.get("token"));
        assertEquals("sample-account", prefs.memory.get("account"));
        assertEquals("must-stay", prefs.memory.get("draft_v2"));
    }

    @Test
    public void newLoginNeverFallsBackToPlaintextWhenKeystoreIsUnavailable() {
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(new HashMap<>());
        String fingerprint = SecureTokenStore.webFingerprintForRealm(prefs, REALM_A);
        Map<String, Object> before = new HashMap<>(prefs.memory);
        // Local JVM tests have no AndroidKeyStore. This must fail before creating an editor and
        // must never exercise the historical plaintext token slot.
        assertFalse(SecureTokenStore.putLoginForBinding(prefs, TOKEN_A, "sample-password",
            "sample-account", "Sample User", REALM_A, fingerprint));
        assertEquals(before, prefs.memory);
        assertEquals(before, prefs.durable);
        assertFalse(prefs.memory.containsKey("token"));
    }

    @Test
    public void failedFingerprintCommitRestoresPriorMemoryAndDurableSnapshot() {
        Map<String, Object> initial = new HashMap<>();
        initial.put("sessionV2WebFingerprint", FINGERPRINT_A);
        initial.put("sessionV2WebFingerprintRealmSha256", REALM_A);
        initial.put("draft_v2", "must-stay");
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);
        prefs.nextCommitSucceeds = false;

        assertEquals("", SecureTokenStore.webFingerprintForRealm(prefs, REALM_B));
        assertEquals(initial, prefs.memory);
        assertEquals(initial, prefs.durable);
    }

    @Test
    public void independentCasStateIsStablePerRealmAndRotatesAcrossRealmChanges() {
        Map<String, Object> initial = new HashMap<>();
        initial.put("token", TOKEN_A);
        initial.put("draft_v2", "must-stay");
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);

        String first = SecureTokenStore.ensureSessionStateForRealm(prefs, REALM_A);
        assertTrue(SessionRealmRules.validSessionId(first));
        assertEquals(first, SecureTokenStore.ensureSessionStateForRealm(prefs, REALM_A));
        String second = SecureTokenStore.ensureSessionStateForRealm(prefs, REALM_B);
        assertTrue(SessionRealmRules.validSessionId(second));
        assertNotEquals(first, second);
        String returned = SecureTokenStore.ensureSessionStateForRealm(prefs, REALM_A);
        assertTrue(SessionRealmRules.validSessionId(returned));
        assertNotEquals(first, returned);
        assertEquals(TOKEN_A, prefs.memory.get("token"));
        assertEquals("must-stay", prefs.memory.get("draft_v2"));
    }

    @Test
    public void peerLoginAlsoRejectsKeystoreFailureWithoutChangingOldState() {
        Map<String, Object> initial = boundIdentity(REALM_A, FINGERPRINT_A);
        initial.put("token", TOKEN_A);
        initial.put("account", "old-v1-account");
        initial.put("pwdCipher", "old-password-bytes");
        initial.put("pwdIv", "old-password-iv");
        initial.put("recognizeTextUrl", "https://old.example.invalid/ocr");
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);
        String peerFingerprint = "abcdef0123456789abcdef0123456789";

        Map<String, Object> before = new HashMap<>(prefs.memory);
        assertFalse(SecureTokenStore.putPeerTokenForBinding(
            prefs, "peer-token", REALM_A, peerFingerprint, SESSION_A, STATE_A));
        assertEquals(before, prefs.memory);
        assertEquals(before, prefs.durable);
    }

    @Test
    public void failedLogoutCommitRestoresV1AndV2TokenStateBeforeReturning() {
        Map<String, Object> initial = boundIdentity(REALM_A, FINGERPRINT_A);
        initial.put("sessionV2TokenCipher", "encrypted-token-bytes");
        initial.put("sessionV2TokenIv", "token-iv");
        initial.put("tokenCipher", "old-token-bytes");
        initial.put("tokenIv", "old-token-iv");
        initial.put("token", TOKEN_A);
        initial.put("recognizeTextUrl", "https://old.example.invalid/ocr");
        PanelConnectionSwitchAtomicityTest.RecordingPreferences prefs =
            new PanelConnectionSwitchAtomicityTest.RecordingPreferences(initial);
        prefs.nextCommitSucceeds = false;

        assertFalse(SecureTokenStore.clearTokenForBinding(
            prefs, REALM_A, FINGERPRINT_A, SESSION_A, STATE_A));
        // Both the first failed clear and the failed restore synchronously mutate the process map;
        // the helper must nevertheless leave memory and the original durable snapshot coherent.
        assertEquals(initial, prefs.memory);
        assertEquals(initial, prefs.durable);
    }

    private static Map<String, Object> boundIdentity(String realm, String fingerprint) {
        Map<String, Object> stored = new HashMap<>();
        stored.put("sessionV2Account", "sample-account");
        stored.put("sessionV2UserName", "Sample User");
        stored.put("sessionV2CredentialRealmSha256", realm);
        stored.put("sessionV2CredentialWebFingerprint", fingerprint);
        stored.put("sessionV2SessionId", SESSION_A);
        stored.put("sessionV2StateId", STATE_A);
        stored.put("sessionV2StateRealmSha256", realm);
        stored.put("sessionV2AccountBindingSha256",
            SessionRealmRules.credentialValueBindingSha256(
                "account", realm, fingerprint, SESSION_A, "sample-account"));
        stored.put("sessionV2UserNameBindingSha256",
            SessionRealmRules.credentialValueBindingSha256(
                "userName", realm, fingerprint, SESSION_A, "Sample User"));
        stored.put("sessionV2WebFingerprint", fingerprint);
        stored.put("sessionV2WebFingerprintRealmSha256", realm);
        return stored;
    }
}
