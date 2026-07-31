package com.autoformkit.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Realm-bound v2 session storage. Historical v1 bytes are rollback-only and never read here. */
final class SecureTokenStore {
    private static final String KEY_ALIAS = "autoform_token_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    // Signed-v1 storage names. Keep bytes untouched during an ordinary upgrade/realm mismatch so
    // the signed old App can roll back; an explicit Panel switch or logout clears them by intent.
    private static final String V1_TOKEN_CIPHER = "tokenCipher";
    private static final String V1_TOKEN_IV = "tokenIv";
    private static final String V1_PASSWORD_CIPHER = "pwdCipher";
    private static final String V1_PASSWORD_IV = "pwdIv";
    private static final String V1_PLAINTEXT_TOKEN = "token";
    private static final String V1_WEB_FINGERPRINT = "web_client_fingerprint";

    // Current storage is independent from v1, so an old App/provider cannot overwrite a value and
    // accidentally inherit a still-present new binding.
    private static final String V2_TOKEN_CIPHER = "sessionV2TokenCipher";
    private static final String V2_TOKEN_IV = "sessionV2TokenIv";
    private static final String V2_PASSWORD_CIPHER = "sessionV2PasswordCipher";
    private static final String V2_PASSWORD_IV = "sessionV2PasswordIv";
    private static final String V2_ACCOUNT = "sessionV2Account";
    private static final String V2_USERNAME = "sessionV2UserName";
    static final String PREF_CREDENTIAL_REALM = "sessionV2CredentialRealmSha256";
    static final String PREF_CREDENTIAL_FINGERPRINT = "sessionV2CredentialWebFingerprint";
    static final String PREF_SESSION_ID = "sessionV2SessionId";
    static final String PREF_SESSION_STATE_ID = "sessionV2StateId";
    static final String PREF_SESSION_STATE_REALM = "sessionV2StateRealmSha256";
    static final String PREF_ACCOUNT_BINDING = "sessionV2AccountBindingSha256";
    static final String PREF_USERNAME_BINDING = "sessionV2UserNameBindingSha256";
    static final String PREF_WEB_FINGERPRINT = "sessionV2WebFingerprint";
    static final String PREF_WEB_FINGERPRINT_REALM =
        "sessionV2WebFingerprintRealmSha256";

    private SecureTokenStore() {}

    /** One SharedPreferences snapshot for Provider/Bridge token envelope reads. */
    static final class BoundSession {
        final String realm;
        final String fingerprint;
        final String sessionId;
        final String stateId;
        final String token;

        private BoundSession(String realm, String fingerprint,
                             String sessionId, String stateId, String token) {
            this.realm = realm;
            this.fingerprint = fingerprint;
            this.sessionId = sessionId;
            this.stateId = stateId;
            this.token = token;
        }

        boolean hasCapability() {
            return SessionRealmRules.validDigest(realm) && fingerprint.length() >= 16
                && SessionRealmRules.validSessionId(sessionId)
                && SessionRealmRules.validSessionId(stateId);
        }
    }

    /** Stable per-realm CAS state; a realm change rotates it even when no credential is readable. */
    static String ensureSessionStateForRealm(SharedPreferences prefs, String realmSha256) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        if (!realm.isEmpty() && !SessionRealmRules.validDigest(realm)) return "";
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Map<String, ?> before = snapshot(prefs);
            String existing = sessionStateId(before, realm);
            if (!existing.isEmpty()) return existing;
            String generated = randomId();
            String priorRawState = string(before, PREF_SESSION_STATE_ID).trim();
            while (generated.equals(priorRawState)) generated = randomId();
            Set<String> touched = new LinkedHashSet<>();
            Collections.addAll(touched, PREF_SESSION_STATE_ID, PREF_SESSION_STATE_REALM);
            if (prefs.edit()
                    .putString(PREF_SESSION_STATE_ID, generated)
                    .putString(PREF_SESSION_STATE_REALM, realm)
                    .commit()) return generated;
            PanelConnectionPreferenceTransaction.restore(prefs, before, touched);
            return "";
        }
    }

    static BoundSession readBoundSession(SharedPreferences prefs, String realmSha256) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        return boundSession(snapshot(prefs), realm);
    }

    private static BoundSession boundSession(Map<String, ?> stored, String realmSha256) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        String stateId = sessionStateId(stored, realm);
        String fingerprint = string(stored, PREF_WEB_FINGERPRINT).trim();
        if (!webFingerprintMatches(stored, realm, fingerprint)
                || !credentialContextMatches(stored, realm, fingerprint)) {
            return new BoundSession(realm, "", "", stateId, "");
        }
        String sessionId = string(stored, PREF_SESSION_ID);
        String token = decrypt(stored, V2_TOKEN_CIPHER, V2_TOKEN_IV,
            "token", realm, fingerprint, sessionId).trim();
        return new BoundSession(realm, fingerprint, sessionId, stateId, token);
    }

    static String getForBinding(SharedPreferences prefs, String realmSha256,
                                String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        if (!credentialContextMatches(stored, realmSha256, webFingerprint)) return "";
        String sessionId = string(stored, PREF_SESSION_ID);
        String token = decrypt(stored, V2_TOKEN_CIPHER, V2_TOKEN_IV,
            "token", realmSha256, webFingerprint, sessionId).trim();
        return token;
    }

    static String getPasswordForBinding(SharedPreferences prefs, String realmSha256,
                                        String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        String sessionId = string(stored, PREF_SESSION_ID);
        return credentialContextMatches(stored, realmSha256, webFingerprint)
            ? decrypt(stored, V2_PASSWORD_CIPHER, V2_PASSWORD_IV,
                "password", realmSha256, webFingerprint, sessionId)
            : "";
    }

    static String getAccountForBinding(SharedPreferences prefs, String realmSha256,
                                       String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        if (!credentialContextMatches(stored, realmSha256, webFingerprint)) return "";
        String sessionId = string(stored, PREF_SESSION_ID);
        String account = string(stored, V2_ACCOUNT);
        return valueBindingMatches(stored, PREF_ACCOUNT_BINDING, "account",
            realmSha256, webFingerprint, sessionId, account) ? account : "";
    }

    static String getUserNameForBinding(SharedPreferences prefs, String realmSha256,
                                        String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        if (!credentialContextMatches(stored, realmSha256, webFingerprint)) return "";
        String sessionId = string(stored, PREF_SESSION_ID);
        String userName = string(stored, V2_USERNAME);
        return valueBindingMatches(stored, PREF_USERNAME_BINDING, "userName",
            realmSha256, webFingerprint, sessionId, userName) ? userName : "";
    }

    static String getSessionIdForBinding(SharedPreferences prefs, String realmSha256,
                                         String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        if (!credentialContextMatches(stored, realmSha256, webFingerprint)) return "";
        String sessionId = string(stored, PREF_SESSION_ID);
        return SessionRealmRules.validSessionId(sessionId) ? sessionId : "";
    }

    /** Read-only reconciliation. Mismatched/legacy bytes stay on disk but cannot be read or sent. */
    static boolean reconcileForBinding(SharedPreferences prefs, String realmSha256,
                                       String webFingerprint) {
        Map<String, ?> stored = snapshot(prefs);
        if (!webFingerprintMatches(stored, realmSha256, webFingerprint)
                || !credentialContextMatches(stored, realmSha256, webFingerprint)) return false;
        String sessionId = string(stored, PREF_SESSION_ID);
        boolean hasToken = stored.containsKey(V2_TOKEN_CIPHER) || stored.containsKey(V2_TOKEN_IV);
        if (hasToken && decrypt(stored, V2_TOKEN_CIPHER, V2_TOKEN_IV,
                "token", realmSha256, webFingerprint, sessionId).trim().isEmpty()) return false;
        boolean hasPassword = stored.containsKey(V2_PASSWORD_CIPHER)
            || stored.containsKey(V2_PASSWORD_IV);
        if (hasPassword && decrypt(stored, V2_PASSWORD_CIPHER, V2_PASSWORD_IV,
                "password", realmSha256, webFingerprint, sessionId).isEmpty()) return false;
        if (stored.containsKey(V2_ACCOUNT) || stored.containsKey(PREF_ACCOUNT_BINDING)) {
            String account = string(stored, V2_ACCOUNT);
            if (!valueBindingMatches(stored, PREF_ACCOUNT_BINDING, "account",
                    realmSha256, webFingerprint, sessionId, account)) return false;
        }
        if (stored.containsKey(V2_USERNAME) || stored.containsKey(PREF_USERNAME_BINDING)) {
            String userName = string(stored, V2_USERNAME);
            if (!valueBindingMatches(stored, PREF_USERNAME_BINDING, "userName",
                    realmSha256, webFingerprint, sessionId, userName)) return false;
        }
        return true;
    }

    static String getBoundWebFingerprint(SharedPreferences prefs, String realmSha256) {
        Map<String, ?> stored = snapshot(prefs);
        String fingerprint = string(stored, PREF_WEB_FINGERPRINT).trim();
        return webFingerprintMatches(stored, realmSha256, fingerprint) ? fingerprint : "";
    }

    /** Generates before captcha/login and reuses only within the exact full session realm. */
    static String webFingerprintForRealm(SharedPreferences prefs, String realmSha256) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        if (!SessionRealmRules.validDigest(realm)) return "";
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Map<String, ?> before = snapshot(prefs);
            String existing = string(before, PREF_WEB_FINGERPRINT).trim();
            if (webFingerprintMatches(before, realm, existing)) return existing;
            String generated = UUID.randomUUID().toString().replace("-", "");
            Set<String> touched = new LinkedHashSet<>();
            touched.add(PREF_WEB_FINGERPRINT);
            touched.add(PREF_WEB_FINGERPRINT_REALM);
            if (prefs.edit()
                    .putString(PREF_WEB_FINGERPRINT, generated)
                    .putString(PREF_WEB_FINGERPRINT_REALM, realm)
                    .commit()) return generated;
            PanelConnectionPreferenceTransaction.restore(prefs, before, touched);
            return "";
        }
    }

    /** Encrypt first, then atomically publish the complete v2 credential/session bundle. */
    static boolean putLoginForBinding(SharedPreferences prefs, String token, String password,
                                      String account, String userName, String realmSha256,
                                      String webFingerprint) {
        String sessionToken = clean(token);
        String storedPassword = password == null ? "" : password;
        String storedAccount = clean(account);
        String storedUserName = clean(userName);
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        String fingerprint = clean(webFingerprint);
        String sessionId = randomId();
        String stateId = randomId();
        while (stateId.equals(sessionId)) stateId = randomId();
        if (sessionToken.isEmpty() || storedPassword.isEmpty() || storedAccount.isEmpty()
                || !SessionRealmRules.validDigest(realm) || fingerprint.length() < 16) return false;
        final EncryptedValue encryptedToken;
        final EncryptedValue encryptedPassword;
        try {
            encryptedToken = encrypt(sessionToken, "token", realm, fingerprint, sessionId);
            encryptedPassword = encrypt(
                storedPassword, "password", realm, fingerprint, sessionId);
        } catch (Exception unavailable) {
            // No plaintext fallback and no editor yet: failure is zero-modification.
            return false;
        }
        String accountBinding = valueBinding(
            "account", realm, fingerprint, sessionId, storedAccount);
        String userNameBinding = valueBinding(
            "userName", realm, fingerprint, sessionId, storedUserName);
        if (accountBinding.isEmpty() || userNameBinding.isEmpty()) return false;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Map<String, ?> before = snapshot(prefs);
            String previousStateId = sessionStateId(before, realm);
            if (!webFingerprintMatches(before, realm, fingerprint)
                    || previousStateId.isEmpty()) return false;
            while (stateId.equals(previousStateId) || stateId.equals(sessionId)) {
                stateId = randomId();
            }
            Set<String> touched = credentialWriteKeys();
            SharedPreferences.Editor editor = prefs.edit();
            stageEncrypted(editor, V2_TOKEN_CIPHER, V2_TOKEN_IV, encryptedToken);
            stageEncrypted(editor, V2_PASSWORD_CIPHER, V2_PASSWORD_IV, encryptedPassword);
            editor.putString(V2_ACCOUNT, storedAccount)
                .putString(V2_USERNAME, storedUserName)
                .putString(PREF_CREDENTIAL_REALM, realm)
                .putString(PREF_CREDENTIAL_FINGERPRINT, fingerprint)
                .putString(PREF_SESSION_ID, sessionId)
                .putString(PREF_SESSION_STATE_ID, stateId)
                .putString(PREF_SESSION_STATE_REALM, realm)
                .putString(PREF_ACCOUNT_BINDING, accountBinding)
                .putString(PREF_USERNAME_BINDING, userNameBinding);
            if (editor.commit()) return true;
            PanelConnectionPreferenceTransaction.restore(prefs, before, touched);
            return false;
        }
    }

    /** Accepts an opt-in peer only as one complete disk-realm/token/web-fingerprint bundle. */
    static boolean putPeerTokenForBinding(SharedPreferences prefs, String token,
                                          String realmSha256, String webFingerprint,
                                          String sessionId,
                                          String expectedStateId) {
        String sessionToken = clean(token);
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        String fingerprint = clean(webFingerprint);
        String session = clean(sessionId);
        String expectedState = clean(expectedStateId);
        if (sessionToken.isEmpty() || !SessionRealmRules.validDigest(realm)
                || fingerprint.length() < 16
                || !SessionRealmRules.validSessionId(session)
                || !SessionRealmRules.validSessionId(expectedState)) return false;
        final EncryptedValue encryptedToken;
        try {
            encryptedToken = encrypt(sessionToken, "token", realm, fingerprint, session);
        } catch (Exception unavailable) {
            return false;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Map<String, ?> before = snapshot(prefs);
            BoundSession current = boundSession(before, realm);
            if (current.hasCapability()) {
                // Exact re-delivery is idempotent and performs no write. Every state-changing
                // delivery below must still match the target state captured before peer login.
                if (current.realm.equals(realm)
                    && current.fingerprint.equals(fingerprint)
                    && current.sessionId.equals(session)
                    && current.token.equals(sessionToken)) return true;
            }
            if (!expectedState.equals(sessionStateId(before, realm))) return false;
            String newStateId = randomId();
            while (newStateId.equals(expectedState) || newStateId.equals(session)) {
                newStateId = randomId();
            }
            Set<String> touched = credentialWriteKeys();
            Collections.addAll(touched, PREF_WEB_FINGERPRINT,
                PREF_WEB_FINGERPRINT_REALM, "recognizeTextUrl");
            SharedPreferences.Editor editor = prefs.edit();
            stageEncrypted(editor, V2_TOKEN_CIPHER, V2_TOKEN_IV, encryptedToken);
            editor.remove(V2_PASSWORD_CIPHER).remove(V2_PASSWORD_IV)
                .remove(V2_ACCOUNT).remove(V2_USERNAME)
                .remove(PREF_ACCOUNT_BINDING).remove(PREF_USERNAME_BINDING)
                .remove("recognizeTextUrl")
                .putString(PREF_CREDENTIAL_REALM, realm)
                .putString(PREF_CREDENTIAL_FINGERPRINT, fingerprint)
                .putString(PREF_SESSION_ID, session)
                .putString(PREF_SESSION_STATE_ID, newStateId)
                .putString(PREF_SESSION_STATE_REALM, realm)
                .putString(PREF_WEB_FINGERPRINT, fingerprint)
                .putString(PREF_WEB_FINGERPRINT_REALM, realm);
            if (editor.commit()) return true;
            PanelConnectionPreferenceTransaction.restore(prefs, before, touched);
            return false;
        }
    }

    static boolean updateUserNameForBinding(SharedPreferences prefs, String userName,
                                            String realmSha256, String webFingerprint,
                                            String expectedToken) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!clean(expectedToken).equals(
                    getForBinding(prefs, realmSha256, webFingerprint))) return false;
            String value = clean(userName);
            String sessionId = getSessionIdForBinding(
                prefs, realmSha256, webFingerprint);
            String binding = valueBinding(
                "userName", realmSha256, webFingerprint, sessionId, value);
            return !binding.isEmpty() && prefs.edit()
                .putString(V2_USERNAME, value)
                .putString(PREF_USERNAME_BINDING, binding)
                .commit();
        }
    }

    /** Explicit logout clears the current v2 token and historical v1 tokens in one commit. */
    static boolean clearTokenForBinding(SharedPreferences prefs, String realmSha256,
                                        String webFingerprint, String expectedSessionId,
                                        String expectedStateId) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Map<String, ?> stored = snapshot(prefs);
            if (!credentialContextMatches(stored, realmSha256, webFingerprint)
                    || !clean(expectedSessionId).equals(
                        string(stored, PREF_SESSION_ID))
                    || !clean(expectedStateId).equals(
                        sessionStateId(stored, realmSha256))) return false;
            Set<String> touched = new LinkedHashSet<>();
            Collections.addAll(touched,
                V2_TOKEN_CIPHER, V2_TOKEN_IV, V2_USERNAME, PREF_USERNAME_BINDING,
                V1_TOKEN_CIPHER, V1_TOKEN_IV, V1_PLAINTEXT_TOKEN,
                "recognizeTextUrl", PREF_SESSION_STATE_ID, PREF_SESSION_STATE_REALM);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : touched) {
                if (!PREF_SESSION_STATE_ID.equals(key)
                        && !PREF_SESSION_STATE_REALM.equals(key)) editor.remove(key);
            }
            String tombstoneStateId = randomId();
            while (tombstoneStateId.equals(clean(expectedStateId))) {
                tombstoneStateId = randomId();
            }
            editor.putString(PREF_SESSION_STATE_ID, tombstoneStateId)
                .putString(PREF_SESSION_STATE_REALM,
                    clean(realmSha256).toLowerCase(java.util.Locale.US));
            if (editor.commit()) return true;
            // commit(false) may already have changed SharedPreferences' process-memory map. Put
            // the exact pre-logout bytes back before releasing HANDOFF_LOCK; the caller must not
            // tell peers that logout completed.
            PanelConnectionPreferenceTransaction.restore(prefs, stored, touched);
            return false;
        }
    }

    /** Stage a complete, atomic explicit Panel-boundary wipe (v1 rollback + v2 current state). */
    static Set<String> stageClearForPanelConnectionChange(SharedPreferences.Editor editor) {
        if (editor == null) throw new IllegalArgumentException("editor is required");
        Set<String> removed = new LinkedHashSet<>();
        Collections.addAll(removed,
            V1_TOKEN_CIPHER, V1_TOKEN_IV, V1_PLAINTEXT_TOKEN,
            V1_PASSWORD_CIPHER, V1_PASSWORD_IV, "account", "userName",
            "recognizeTextUrl", V1_WEB_FINGERPRINT,
            V2_TOKEN_CIPHER, V2_TOKEN_IV, V2_PASSWORD_CIPHER, V2_PASSWORD_IV,
            V2_ACCOUNT, V2_USERNAME, PREF_CREDENTIAL_REALM,
            PREF_CREDENTIAL_FINGERPRINT, PREF_SESSION_ID,
            PREF_ACCOUNT_BINDING, PREF_USERNAME_BINDING,
            PREF_WEB_FINGERPRINT, PREF_WEB_FINGERPRINT_REALM);
        for (String key : removed) editor.remove(key);
        return removed;
    }

    /** Rotate the independent CAS state in the same transaction as an explicit Panel switch. */
    static Set<String> stageSessionStateRotationForPanelConnectionChange(
            SharedPreferences.Editor editor) {
        if (editor == null) throw new IllegalArgumentException("editor is required");
        editor.putString(PREF_SESSION_STATE_ID, randomId())
            .putString(PREF_SESSION_STATE_REALM, "");
        Set<String> touched = new LinkedHashSet<>();
        Collections.addAll(touched, PREF_SESSION_STATE_ID, PREF_SESSION_STATE_REALM);
        return touched;
    }

    private static Set<String> credentialWriteKeys() {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys, V2_TOKEN_CIPHER, V2_TOKEN_IV,
            V2_PASSWORD_CIPHER, V2_PASSWORD_IV, V2_ACCOUNT, V2_USERNAME,
            PREF_CREDENTIAL_REALM, PREF_CREDENTIAL_FINGERPRINT, PREF_SESSION_ID,
            PREF_SESSION_STATE_ID, PREF_SESSION_STATE_REALM,
            PREF_ACCOUNT_BINDING, PREF_USERNAME_BINDING);
        return keys;
    }

    private static EncryptedValue encrypt(String value, String recordType,
                                          String realmSha256, String webFingerprint,
                                          String sessionId)
            throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(SessionRealmRules.credentialAad(
            recordType, realmSha256, webFingerprint, sessionId));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
    }

    private static String decrypt(Map<String, ?> stored, String cipherKey, String ivKey,
                                  String recordType, String realmSha256,
                                  String webFingerprint, String sessionId) {
        String cipherText = string(stored, cipherKey);
        String ivText = string(stored, ivKey);
        if (cipherText.isEmpty() || ivText.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            cipher.updateAAD(SessionRealmRules.credentialAad(
                recordType, realmSha256, webFingerprint, sessionId));
            return new String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
        } catch (Exception unavailableOrMismatched) {
            return "";
        }
    }

    private static void stageEncrypted(SharedPreferences.Editor editor,
                                       String cipherKey, String ivKey,
                                       EncryptedValue encrypted) {
        editor.putString(cipherKey, encrypted.cipherText)
            .putString(ivKey, encrypted.ivText);
    }

    private static boolean credentialContextMatches(Map<String, ?> stored, String realmSha256,
                                                    String webFingerprint) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        String fingerprint = clean(webFingerprint);
        return SessionRealmRules.validDigest(realm) && fingerprint.length() >= 16
            && realm.equals(string(stored, PREF_CREDENTIAL_REALM))
            && fingerprint.equals(string(stored, PREF_CREDENTIAL_FINGERPRINT))
            && SessionRealmRules.validSessionId(string(stored, PREF_SESSION_ID))
            && !sessionStateId(stored, realm).isEmpty();
    }

    private static boolean webFingerprintMatches(Map<String, ?> stored, String realmSha256,
                                                 String webFingerprint) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        String fingerprint = clean(webFingerprint);
        return SessionRealmRules.validDigest(realm) && fingerprint.length() >= 16
            && realm.equals(string(stored, PREF_WEB_FINGERPRINT_REALM))
            && fingerprint.equals(string(stored, PREF_WEB_FINGERPRINT));
    }

    private static boolean valueBindingMatches(Map<String, ?> stored, String bindingKey,
                                               String kind, String realmSha256,
                                               String webFingerprint, String sessionId,
                                               String actualValue) {
        String expected = valueBinding(
            kind, realmSha256, webFingerprint, sessionId, actualValue);
        return !expected.isEmpty() && expected.equals(string(stored, bindingKey));
    }

    private static String valueBinding(String kind, String realmSha256,
                                       String webFingerprint, String sessionId,
                                       String actualValue) {
        return SessionRealmRules.credentialValueBindingSha256(
            kind, realmSha256, webFingerprint, sessionId, actualValue);
    }

    private static Map<String, ?> snapshot(SharedPreferences prefs) {
        if (prefs == null) return Collections.emptyMap();
        try {
            Map<String, ?> values = prefs.getAll();
            return values == null ? Collections.emptyMap() : values;
        } catch (RuntimeException unreadable) {
            return Collections.emptyMap();
        }
    }

    private static String string(Map<String, ?> stored, String key) {
        Object value = stored == null ? null : stored.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static String sessionStateId(Map<String, ?> stored, String realmSha256) {
        String realm = clean(realmSha256).toLowerCase(java.util.Locale.US);
        if (!realm.equals(string(stored, PREF_SESSION_STATE_REALM))) return "";
        String stateId = string(stored, PREF_SESSION_STATE_ID).trim();
        return SessionRealmRules.validSessionId(stateId) ? stateId : "";
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class EncryptedValue {
        final String cipherText;
        final String ivText;

        EncryptedValue(String cipherText, String ivText) {
            this.cipherText = cipherText;
            this.ivText = ivText;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
