package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source guards for the process-wide credential realm and cross-app session handoff. */
public class SessionRealmWiringTest {
    @Test
    public void coldStartPromotionAndHotInstallAdvanceRealmBeforeExposure() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String onCreate = between(main,
            "protected void onCreate(Bundle savedInstanceState)",
            "protected void onSaveInstanceState(Bundle outState)");
        assertBefore(onCreate, "activateSessionRealm(activePair)",
            "publishActiveNotificationSnapshot()");

        String promotion = between(main,
            "promotePanelPairCandidatesAtSafeBoundary() {",
            "private boolean maybeInstallBoundPanelSnapshotAtSafeBoundary()");
        assertBefore(promotion, "synchronized (UpdateInstallRules.HANDOFF_LOCK)",
            "PanelPairCacheCoordinator.promoteCandidates(this, expectedConnection)");
        assertBefore(promotion,
            "PanelPairCacheCoordinator.promoteCandidates(this, expectedConnection)",
            "activateSessionRealm(");
        assertTrue(promotion.contains(
            "PanelPairCacheCoordinator.loadActivePairOrNull(this)"));

        String install = between(main,
            "private void installBoundPanelSnapshot(",
            "private boolean safeToInstallBoundPanelSnapshot()");
        assertBefore(install, "activateSessionRealm(pair)", "appConfig = config");
        assertBefore(install, "activateSessionRealm(pair)", "catalogSettings = catalog.settings");
    }

    @Test
    public void loginCommitRechecksExactDiskRealmAndFingerprintUnderHandoffLock()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String login = between(main, "private void login()", "private void logoutToSettings()");
        assertBefore(login, "final String loginRealmSnapshot = currentSessionRealmFingerprint()",
            "apiSnapshot.login(account, password, captcha,");
        assertBefore(login, "final String loginFingerprintSnapshot = webFingerprint()",
            "apiSnapshot.login(account, password, captcha,");
        assertBefore(login, "SessionBridge.capturePeerStates(",
            "apiSnapshot.login(account, password, captcha,");
        assertTrue(login.contains(
            "getApplicationContext(), loginRealmSnapshot"));

        String completion = between(login,
            "runOnUiThread(() -> {\n                    final boolean stale;",
            "} catch (Exception exc)");
        assertBefore(completion, "synchronized (UpdateInstallRules.HANDOFF_LOCK)",
            "SessionRealmResolver.activeFingerprint(this)");
        assertBefore(completion, "SessionRealmResolver.activeFingerprint(this)",
            "SecureTokenStore.putLoginForBinding(");
        assertBefore(completion, "SecureTokenStore.getBoundWebFingerprint(",
            "SecureTokenStore.putLoginForBinding(");
        assertBefore(completion, "SecureTokenStore.putLoginForBinding(",
            "finishBoundOperation(operation)");
        assertBefore(completion, "SecureTokenStore.putLoginForBinding(",
            "SessionBridge.propagateLogin(");
    }

    @Test
    public void captchaAndEveryApiCaptureUseTheBoundRealmFingerprint() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String begin = between(main,
            "private OperationBindingRules.Binding beginBoundOperation(",
            "/** A pre-barrier scan reservation");
        assertTrue(begin.contains("webFingerprint(), tokenSnapshot"));

        String captcha = between(main, "private void refreshCaptcha()", "private void login()");
        assertBefore(captcha, "beginBoundOperation(OperationBindingRules.CAPTCHA",
            "api(tokenSnapshot)");
        assertBefore(captcha, "api(tokenSnapshot)", "apiSnapshot.getCaptcha(");

        String fingerprint = between(main, "private String webFingerprint()",
            "private String boundOcrUrlPreferenceKey(");
        assertTrue(fingerprint.contains("SecureTokenStore.webFingerprintForRealm(prefs, realm)"));
        assertTrue(fingerprint.contains("LOCAL_PREVIEW_FINGERPRINT_KEY"));

        String api = between(main,
            "private Api api(String token, JSONObject configSnapshot,",
            "/** Workflow behavior belongs to the selected profile");
        assertTrue(api.contains("final String requestRealm = SessionRealmRules.fingerprint("));
        assertTrue(api.contains("final String requestWebFingerprint"));
        assertTrue(api.contains("tokenReadableAtCreation"));
        assertTrue(api.contains("SecureTokenStore.getForBinding("));
        assertTrue(api.contains("SecureTokenStore.getBoundWebFingerprint(prefs, requestRealm)"));
        assertTrue(api.contains("requestRealm.equals(currentSessionRealmFingerprint())"));
        assertTrue(api.contains(
            "return new Api(adapterSnapshot.baseUrl, token, requestWebFingerprint"));
    }

    @Test
    public void mainProviderAndBridgeHaveNoLegacyCredentialReadBypass() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String provider = source("app/src/com/autoformkit/app/SessionAuthProvider.java",
            "src/com/autoformkit/app/SessionAuthProvider.java");
        String bridge = source("app/src/com/autoformkit/app/SessionBridge.java",
            "src/com/autoformkit/app/SessionBridge.java");
        for (String value : new String[]{main, provider, bridge}) {
            for (String legacyCall : new String[]{"SecureTokenStore.get(",
                    "SecureTokenStore.put(", "SecureTokenStore.getPassword(",
                    "SecureTokenStore.putPassword(", "SecureTokenStore.clear(",
                    "SecureTokenStore.clearPassword("}) {
                assertFalse(legacyCall, value.contains(legacyCall));
            }
            for (String rawKey : new String[]{"prefs.getString(\"account\"",
                    "prefs.getString(\"userName\"",
                    "prefs.getString(\"web_client_fingerprint\"",
                    "prefs.edit().putString(\"account\"",
                    "prefs.edit().putString(\"userName\"",
                    "prefs.edit().putString(\"web_client_fingerprint\""}) {
                assertFalse(rawKey, value.contains(rawKey));
            }
        }

        String helpers = between(main, "private String savedToken()",
            "private String currentSessionRealmFingerprint()");
        assertTrue(helpers.contains("SecureTokenStore.getForBinding("));
        assertTrue(helpers.contains("SecureTokenStore.getPasswordForBinding("));
        assertTrue(helpers.contains("SecureTokenStore.getAccountForBinding("));
        assertTrue(helpers.contains("SecureTokenStore.getUserNameForBinding("));
    }

    @Test
    public void providerAndBridgeTransferOneCasProtectedSessionEnvelope() throws Exception {
        String provider = source("app/src/com/autoformkit/app/SessionAuthProvider.java",
            "src/com/autoformkit/app/SessionAuthProvider.java");
        String bridge = source("app/src/com/autoformkit/app/SessionBridge.java",
            "src/com/autoformkit/app/SessionBridge.java");
        String store = source("app/src/com/autoformkit/app/SecureTokenStore.java",
            "src/com/autoformkit/app/SecureTokenStore.java");

        String query = between(provider, "public Cursor query(", "public Uri insert(");
        assertBefore(query, "synchronized (UpdateInstallRules.HANDOFF_LOCK)",
            "SecureTokenStore.readBoundSession(prefs, realm)");
        assertTrue(query.contains("tokenCursor(session.token, session.fingerprint,"));
        assertTrue(provider.contains("\"protocolVersion\", \"stateId\""));

        String insert = between(provider, "public Uri insert(", "public int update(");
        assertTrue(insert.contains("values.getAsString(\"realm\")"));
        assertTrue(insert.contains("values.getAsString(\"sessionId\")"));
        assertTrue(insert.contains("values.getAsInteger(\"protocolVersion\")"));
        assertTrue(insert.contains("values.getAsString(\"expectedStateId\")"));
        assertBefore(insert, "protocolVersion != SessionBridge.PROTOCOL_VERSION",
            "SecureTokenStore.putPeerTokenForBinding(");
        assertBefore(insert, "synchronized (UpdateInstallRules.HANDOFF_LOCK)",
            "SessionRealmResolver.activeFingerprint(getContext())");
        assertBefore(insert, "SessionRealmResolver.activeFingerprint(getContext())",
            "SecureTokenStore.putPeerTokenForBinding(");

        String delete = between(provider, "public int delete(",
            "private void notifyTokenChange(");
        assertTrue(delete.contains("getQueryParameter(\"realm\")"));
        assertTrue(delete.contains("getQueryParameter(\"fingerprint\")"));
        assertTrue(delete.contains("getQueryParameter(\"sessionId\")"));
        assertTrue(delete.contains("getQueryParameter(\"protocolVersion\")"));
        assertTrue(delete.contains("getQueryParameter(\"expectedStateId\")"));
        assertTrue(delete.contains("session.realm.equals(expectedRealm)"));
        assertTrue(delete.contains("session.fingerprint.equals(expectedFingerprint)"));
        assertTrue(delete.contains("session.sessionId.equals(expectedSessionId)"));
        assertBefore(delete, "SecureTokenStore.readBoundSession(prefs, realm)",
            "SecureTokenStore.clearTokenForBinding(");

        String peerWrite = between(store, "static boolean putPeerTokenForBinding(",
            "static boolean updateUserNameForBinding(");
        assertBefore(peerWrite, "Map<String, ?> before = snapshot(prefs)",
            "SharedPreferences.Editor editor = prefs.edit()");
        assertBefore(peerWrite, "expectedState.equals(sessionStateId(before, realm))",
            "SharedPreferences.Editor editor = prefs.edit()");
        assertTrue(peerWrite.contains("current.sessionId.equals(session)"));
        assertTrue(peerWrite.contains("current.token.equals(sessionToken)"));

        String capture = between(bridge, "static PeerStateSnapshot capturePeerStates(",
            "/** Capture before local clear/Panel switch");
        assertBefore(capture, "expectedRealm.equals(peer.realm)", "states.put(pkg, peer.stateId)");
        String loginBridge = between(bridge, "static void propagateLogin(",
            "static void propagateLogout(Context ctx, String exceptPkg)");
        assertBefore(loginBridge,
            "if (!SessionRealmRules.validSessionId(expectedStateId)) continue",
            "ContentValues values = new ContentValues()");
        assertTrue(loginBridge.contains("values.put(\"protocolVersion\", PROTOCOL_VERSION)"));
        assertTrue(loginBridge.contains("values.put(\"expectedStateId\", expectedStateId)"));
        assertTrue(loginBridge.contains("values.put(\"realm\", activeRealm)"));
        assertTrue(loginBridge.contains("values.put(\"sessionId\", sessionId)"));

        assertTrue(bridge.contains("appendQueryParameter(\"realm\", capability.realm)"));
        assertTrue(bridge.contains(
            "appendQueryParameter(\"fingerprint\", capability.fingerprint)"));
        assertTrue(bridge.contains(
            "appendQueryParameter(\"sessionId\", capability.sessionId)"));
        String logout = between(bridge,
            "static void propagateLogout(Context ctx, String exceptPkg,",
            "private static boolean suppressDuplicateLogout(");
        assertBefore(logout, "queryPeerV2State(app, pkg)",
            "getContentResolver().delete(");
        assertTrue(logout.contains("appendQueryParameter(\"protocolVersion\""));
        assertTrue(logout.contains("appendQueryParameter(\"expectedStateId\", peer.stateId)"));
        String queryPeer = between(bridge, "private static PeerV2State queryPeerV2State(",
            "private static void broadcast(");
        assertBefore(queryPeer, "protocolColumn < 0", "return new PeerV2State(");
        assertTrue(queryPeer.contains("cursor.getInt(protocolColumn) != PROTOCOL_VERSION"));
        assertTrue(bridge.contains("key.equals(lastLogoutCapabilityKey)"));
    }

    @Test
    public void v2SecretsUseAadWhileV1BytesRemainRollbackOnly() throws Exception {
        String store = source("app/src/com/autoformkit/app/SecureTokenStore.java",
            "src/com/autoformkit/app/SecureTokenStore.java");
        String rules = source("app/src/com/autoformkit/app/SessionRealmRules.java",
            "src/com/autoformkit/app/SessionRealmRules.java");

        String tokenRead = between(store, "static String getForBinding(",
            "static String getPasswordForBinding(");
        assertTrue(tokenRead.contains("V2_TOKEN_CIPHER"));
        assertFalse(tokenRead.contains("V1_"));
        String fingerprint = between(store, "static String webFingerprintForRealm(",
            "/** Encrypt first, then atomically publish");
        assertTrue(fingerprint.contains("PREF_WEB_FINGERPRINT"));
        assertFalse(fingerprint.contains("V1_WEB_FINGERPRINT"));
        String login = between(store, "static boolean putLoginForBinding(",
            "/** Accepts an opt-in peer");
        assertTrue(login.contains("String sessionId = randomId()"));
        assertTrue(login.contains("String stateId = randomId()"));
        assertTrue(login.contains("while (stateId.equals(sessionId))"));
        assertTrue(login.contains("encrypt(sessionToken, \"token\""));
        assertTrue(login.contains("encryptedPassword = encrypt("));
        assertFalse(login.contains("V1_"));
        assertFalse(login.contains("putString(\"token\""));

        String crypto = between(store, "private static EncryptedValue encrypt(",
            "private static void stageEncrypted(");
        assertTrue(count(crypto, "cipher.updateAAD(") == 2);
        assertTrue(count(crypto, "SessionRealmRules.credentialAad(") == 2);

        String reconcile = between(store, "static boolean reconcileForBinding(",
            "static String getBoundWebFingerprint(");
        assertFalse(reconcile.contains("prefs.edit()"));
        String clear = between(store, "static boolean clearTokenForBinding(",
            "/** Stage a complete, atomic explicit Panel-boundary wipe");
        assertBefore(clear, "Map<String, ?> stored = snapshot(prefs)",
            "editor.commit()");
        assertBefore(clear, "editor.commit()",
            "PanelConnectionPreferenceTransaction.restore(prefs, stored, touched)");
        String panelClear = between(store,
            "static Set<String> stageClearForPanelConnectionChange(",
            "/** Rotate the independent CAS state");
        assertTrue(panelClear.contains("V1_TOKEN_CIPHER"));
        assertTrue(panelClear.contains("V1_PASSWORD_CIPHER"));
        assertTrue(panelClear.contains("V1_WEB_FINGERPRINT"));
        assertTrue(panelClear.contains("V2_TOKEN_CIPHER"));
        assertTrue(panelClear.contains("PREF_SESSION_ID"));
        assertFalse(panelClear.contains("PREF_SESSION_STATE_ID"));
        assertTrue(store.contains(
            "stageSessionStateRotationForPanelConnectionChange("));

        assertTrue(rules.contains("Arrays.asList(\"account\", \"userName\")"));
        assertFalse(rules.contains(
            "credentialValueBindingSha256(\n                \"password\""));

        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String logout = between(main, "private void logoutToSettings(boolean propagate)",
            "private void handleRemoteLogout(boolean firstHand)");
        assertBefore(logout, "synchronized (UpdateInstallRules.HANDOFF_LOCK)",
            "SessionBridge.captureLogoutCapability(");
        assertBefore(logout, "SessionBridge.captureLogoutCapability(",
            "SecureTokenStore.clearTokenForBinding(");
        assertBefore(logout, "if (!capabilityValid || !had)",
            "abandonReadablePendingMainFormTarget()");
        assertBefore(logout, "if (!sessionClearDurable)",
            "abandonReadablePendingMainFormTarget()");
        String invalidCapability = between(logout,
            "if (!capabilityValid || !had)", "if (!sessionClearDurable)");
        assertFalse(invalidCapability.contains("units.clear()"));
        assertFalse(invalidCapability.contains("clearAlternateEntrySession("));
        assertFalse(invalidCapability.contains("abandonReadablePendingMainFormTarget()"));
        String failedClear = between(logout, "if (!sessionClearDurable)",
            "synchronized (activeOperationNonces)");
        assertTrue(failedClear.contains("alert("));
        assertTrue(failedClear.contains("return"));
        assertFalse(failedClear.contains("units.clear()"));
        assertFalse(failedClear.contains("clearAlternateEntrySession("));
        assertFalse(failedClear.contains("abandonReadablePendingMainFormTarget()"));
        assertTrue(logout.contains("propagate && had && sessionClearDurable"));
    }

    @Test
    public void realmUsesFullConnectionIdentityAndExactCoherentDiskPair() throws Exception {
        String appConfig = source("app/src/com/autoformkit/app/AppConfig.java",
            "src/com/autoformkit/app/AppConfig.java");
        String securityId = between(appConfig,
            "static String connectionSecurityId(", "private static void notify(");
        assertTrue(securityId.contains("return fingerprint(source)"));
        assertFalse(securityId.contains("substring("));

        String resolver = source("app/src/com/autoformkit/app/SessionRealmResolver.java",
            "src/com/autoformkit/app/SessionRealmResolver.java");
        assertBefore(resolver, "PanelPairCacheCoordinator.loadActivePairOrNull(app)",
            "return pair == null ? \"\" : forPair(app, pair)");
        assertTrue(resolver.contains(
            "AppConfig.connectionSecurityId(panelBase, catalogKey)"));

        String rules = source("app/src/com/autoformkit/app/SessionRealmRules.java",
            "src/com/autoformkit/app/SessionRealmRules.java");
        assertTrue(rules.contains("connection.matches(\"[0-9a-f]{64}\")"));
        assertTrue(rules.contains(".put(\"adapterVersion\", adapter.version)"));
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

    private static int count(String value, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }
}
