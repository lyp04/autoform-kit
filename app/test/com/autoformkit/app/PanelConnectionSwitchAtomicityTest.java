package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Guards the crash boundary when a manual or browser-paired Panel connection is replaced. */
public class PanelConnectionSwitchAtomicityTest {
    private static final Set<String> OLD_CONNECTION_CREDENTIAL_KEYS = new HashSet<>(
        Arrays.asList("tokenCipher", "tokenIv", "token", "pwdCipher", "pwdIv",
            "account", "userName", "recognizeTextUrl",
            "web_client_fingerprint", "sessionV2TokenCipher", "sessionV2TokenIv",
            "sessionV2PasswordCipher", "sessionV2PasswordIv", "sessionV2Account",
            "sessionV2UserName", "sessionV2CredentialRealmSha256",
            "sessionV2CredentialWebFingerprint", "sessionV2SessionId",
            "sessionV2AccountBindingSha256",
            "sessionV2UserNameBindingSha256", "sessionV2WebFingerprint",
            "sessionV2WebFingerprintRealmSha256"));

    @Test
    public void newConnectionAndOldCredentialsAreOneEditorSnapshot() {
        Map<String, Object> stored = oldConnectionPreferences();
        RecordingPreferences preferences = new RecordingPreferences(stored);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("panelBase", "new-panel");
        editor.putString("catalogKey", "new-read-key");
        Set<String> removed =
            SecureTokenStore.stageClearForPanelConnectionChange(editor);
        Set<String> rotated =
            SecureTokenStore.stageSessionStateRotationForPanelConnectionChange(editor);

        // A crash before commit still sees the complete old snapshot.
        assertEquals("old-panel", preferences.memory.get("panelBase"));
        assertEquals("old-credential", preferences.memory.get("tokenCipher"));

        assertTrue(editor.commit());

        // A crash after commit sees the complete new connection with no old login material.
        assertEquals("new-panel", preferences.memory.get("panelBase"));
        assertEquals("new-read-key", preferences.memory.get("catalogKey"));
        assertEquals(OLD_CONNECTION_CREDENTIAL_KEYS, removed);
        assertEquals(new HashSet<>(Arrays.asList(
            "sessionV2StateId", "sessionV2StateRealmSha256")), rotated);
        assertTrue(SessionRealmRules.validSessionId(
            (String) preferences.memory.get("sessionV2StateId")));
        assertEquals("", preferences.memory.get("sessionV2StateRealmSha256"));
        for (String key : OLD_CONNECTION_CREDENTIAL_KEYS) {
            assertFalse(preferences.memory.containsKey(key));
            assertFalse(preferences.durable.containsKey(key));
        }
        assertUnrelatedStatePreserved(preferences.memory);
        assertFalse(preferences.lastEditor.clearCalled);
    }

    @Test
    public void failedCommitRestoresTheCompleteOldConnectionBeforeReturning() {
        RecordingPreferences preferences =
            new RecordingPreferences(oldConnectionPreferences());
        Map<String, ?> before =
            PanelConnectionPreferenceTransaction.snapshot(preferences);
        Set<String> touched = new HashSet<>();
        touched.add("panelBase");
        touched.add("catalogKey");

        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("panelBase", "new-panel");
        editor.putString("catalogKey", "new-read-key");
        touched.addAll(SecureTokenStore.stageClearForPanelConnectionChange(editor));
        touched.addAll(
            SecureTokenStore.stageSessionStateRotationForPanelConnectionChange(editor));
        preferences.nextCommitSucceeds = false;
        assertFalse(editor.commit());

        // Android commit() may already have changed this process's memory before reporting its
        // failed disk write. The durable snapshot is still the complete old connection.
        assertEquals("new-panel", preferences.memory.get("panelBase"));
        assertEquals("old-panel", preferences.durable.get("panelBase"));
        assertFalse(preferences.memory.containsKey("tokenCipher"));
        assertFalse("old-session-state-id".equals(
            preferences.memory.get("sessionV2StateId")));
        assertEquals("old-credential", preferences.durable.get("tokenCipher"));

        // Even a second disk failure restores the old values synchronously in memory. A restart
        // also reads the old durable snapshot from the first failed commit.
        preferences.nextCommitSucceeds = false;
        assertFalse(PanelConnectionPreferenceTransaction.restore(
            preferences, before, touched));
        assertEquals("old-panel", preferences.memory.get("panelBase"));
        assertEquals("old-read-key", preferences.memory.get("catalogKey"));
        assertEquals("old-credential", preferences.memory.get("tokenCipher"));
        assertEquals("old-credential", preferences.memory.get("pwdCipher"));
        assertEquals("old-credential", preferences.memory.get("account"));
        assertEquals("old-session-state-id", preferences.memory.get("sessionV2StateId"));
        assertEquals("old-realm", preferences.memory.get("sessionV2StateRealmSha256"));
        assertEquals("old-panel", preferences.durable.get("panelBase"));
        assertEquals("old-credential", preferences.durable.get("tokenCipher"));
        assertUnrelatedStatePreserved(preferences.memory);
    }

    @Test
    public void manualAndPairingSavePathStagesCredentialClearBeforeTheSingleCommit()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String save = between(main,
            "private void savePanelConnection(String panelBaseInput, String catalogKeyInput,",
            "/** A panel URL or key defines a security boundary");
        String reset = between(main,
            "private void resetPanelBoundState(boolean hadTokenBeforeConnectionChange,",
            "/** Brand shown in the UI");

        assertBefore(save, "PanelConnectionPreferenceTransaction.snapshot(prefs)",
            "SharedPreferences.Editor editor = prefs.edit()");
        assertBefore(save, "editor.putString(AppConfig.KEY_PANEL_BASE, base)",
            "SecureTokenStore.stageClearForPanelConnectionChange(editor)");
        assertBefore(save, "editor.putString(AppConfig.KEY_CATALOG_KEY, key)",
            "SecureTokenStore.stageClearForPanelConnectionChange(editor)");
        assertBefore(save, "SecureTokenStore.stageClearForPanelConnectionChange(editor)",
            "saved = editor.commit()");
        assertBefore(save,
            "SecureTokenStore.stageSessionStateRotationForPanelConnectionChange(editor)",
            "saved = editor.commit()");
        assertBefore(save,
            "oldLogoutCapability =\n                    SessionBridge.captureLogoutCapability",
            "saved = editor.commit()");
        assertBefore(save, "saved = editor.commit()",
            "resetPanelBoundState(\n            oldLogoutCapability != null"
                + " && oldLogoutCapability.tokenPresent,");
        assertTrue(save.contains("restoredAfterFailedCommit = "
            + "PanelConnectionPreferenceTransaction.restore("));
        assertBefore(save, "saved = editor.commit()",
            "restoredAfterFailedCommit = PanelConnectionPreferenceTransaction.restore(");
        assertBefore(save, "PanelConnectionPreferenceTransaction.snapshot(prefs)",
            "restoredAfterFailedCommit = PanelConnectionPreferenceTransaction.restore(");
        assertBefore(save, "if (connectionChanged && !restoredAfterFailedCommit)",
            "toast(t(\"panel_connect_failed\"))");

        // The pre-existing safety gates remain in front of the durable boundary.
        assertBefore(save, "legacyAStepContinuationPresent()", "saved = editor.commit()");
        assertBefore(save, "RemoteSideEffectGate.blockingStatePresent(this)",
            "saved = editor.commit()");
        assertBefore(save, "alternateEntryPanelChangeCleanupEvidencePresent()",
            "saved = editor.commit()");
        assertBefore(save, "migrateLegacyPanelBoundState()", "saved = editor.commit()");

        // Post-commit cleanup is cache/UI-only; credentials may never move back into that window.
        assertFalse(reset.contains("SecureTokenStore.clear(prefs)"));
        assertFalse(reset.contains("SecureTokenStore.clearPassword(prefs)"));
        assertFalse(reset.contains("remove(\"account\")"));
        assertTrue(reset.contains(
            "getApplicationContext(), null, oldLogoutCapability"));

        // Browser pairing supplies expectedOldBase/key to this exact same save implementation.
        String redemption = between(main, "private void beginPanelPairingRedemption(",
            "/** Persist the panel address + access key");
        assertTrue(redemption.contains(
            "savePanelConnection(request.panelBase, result.accessKey,"));
        assertTrue(redemption.contains("expectedOldBase, expectedOldKey"));
    }

    private static Map<String, Object> oldConnectionPreferences() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("panelBase", "old-panel");
        stored.put("catalogKey", "old-read-key");
        for (String key : OLD_CONNECTION_CREDENTIAL_KEYS) {
            stored.put(key, "old-credential");
        }
        stored.put("draft_v2", "must-stay");
        stored.put("manual_queue", "must-stay");
        stored.put("round_ledger", "must-stay");
        stored.put("pending_photo_path", "must-stay");
        stored.put("sessionV2StateId", "old-session-state-id");
        stored.put("sessionV2StateRealmSha256", "old-realm");
        return stored;
    }

    private static void assertUnrelatedStatePreserved(Map<String, Object> stored) {
        assertEquals("must-stay", stored.get("draft_v2"));
        assertEquals("must-stay", stored.get("manual_queue"));
        assertEquals("must-stay", stored.get("round_ledger"));
        assertEquals("must-stay", stored.get("pending_photo_path"));
    }

    private static String source(String repositoryPath, String modulePath) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{cwd.resolve(repositoryPath), cwd.resolve(modulePath)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + repositoryPath);
    }

    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) {
            throw new AssertionError("source markers not found: " + startMarker);
        }
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("missing marker: " + first, firstIndex >= 0);
        assertTrue("missing marker: " + second, secondIndex >= 0);
        assertTrue("expected ordering: " + first + " before " + second,
            firstIndex < secondIndex);
    }

    static final class RecordingPreferences implements SharedPreferences {
        final Map<String, Object> memory;
        final Map<String, Object> durable;
        boolean nextCommitSucceeds = true;
        RecordingEditor lastEditor;

        RecordingPreferences(Map<String, Object> initial) {
            memory = new HashMap<>(initial);
            durable = new HashMap<>(initial);
        }

        @Override public Map<String, ?> getAll() {
            return new HashMap<>(memory);
        }

        @Override public String getString(String key, String fallback) {
            Object value = memory.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = memory.get(key);
            if (!(value instanceof Set)) return fallback;
            Set<String> result = new HashSet<>();
            for (Object item : (Set<?>) value) result.add((String) item);
            return result;
        }

        @Override public int getInt(String key, int fallback) {
            Object value = memory.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override public long getLong(String key, long fallback) {
            Object value = memory.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override public float getFloat(String key, float fallback) {
            Object value = memory.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = memory.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public boolean contains(String key) {
            return memory.containsKey(key);
        }

        @Override public SharedPreferences.Editor edit() {
            lastEditor = new RecordingEditor(this);
            return lastEditor;
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}

        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {}
    }

    private static final class RecordingEditor implements SharedPreferences.Editor {
        final RecordingPreferences owner;
        final Map<String, Object> puts = new HashMap<>();
        final Set<String> removed = new HashSet<>();
        boolean clearCalled;

        RecordingEditor(RecordingPreferences owner) {
            this.owner = owner;
        }

        @Override public SharedPreferences.Editor putString(String key, String value) {
            puts.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor putStringSet(String key, Set<String> value) {
            puts.put(key, value == null ? null : new HashSet<>(value));
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor putInt(String key, int value) {
            puts.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor putLong(String key, long value) {
            puts.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor putFloat(String key, float value) {
            puts.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) {
            puts.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor remove(String key) {
            removed.add(key);
            puts.remove(key);
            return this;
        }

        @Override public SharedPreferences.Editor clear() {
            clearCalled = true;
            return this;
        }

        @Override public boolean commit() {
            if (clearCalled) owner.memory.clear();
            for (String key : removed) owner.memory.remove(key);
            owner.memory.putAll(puts);
            if (owner.nextCommitSucceeds) {
                owner.durable.clear();
                owner.durable.putAll(owner.memory);
                return true;
            }
            return false;
        }

        @Override public void apply() {
            commit();
        }
    }
}
