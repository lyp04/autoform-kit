package com.autoformkit.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional, fail-closed protocol-v2 cross-app session bus. */
final class SessionBridge {
    static final String ACTION = "com.autoformkit.app.SESSION_CHANGED";
    static final int PROTOCOL_VERSION = 2;

    /** Apps allowed to read/write this app's session, and propagation targets. Empty = standalone. */
    static final List<String> SESSION_PEERS = Arrays.asList();

    private static final String PREFS = "settings";
    private static final long LOGOUT_DEBOUNCE_MS = 2000L;
    private static long lastLogoutPropagateMs = -LOGOUT_DEBOUNCE_MS;
    private static String lastLogoutCapabilityKey = "";

    private SessionBridge() {}

    private static Uri peerTokenUri(String pkg) {
        return Uri.parse("content://" + pkg + ".session/token");
    }

    static final class PeerStateSnapshot {
        private final Map<String, String> expectedStateIds;

        private PeerStateSnapshot(Map<String, String> expectedStateIds) {
            this.expectedStateIds = Collections.unmodifiableMap(
                new LinkedHashMap<>(expectedStateIds));
        }

        String expectedStateId(String pkg) {
            String value = expectedStateIds.get(pkg);
            return value == null ? "" : value;
        }
    }

    private static final class PeerV2State {
        final String token;
        final String fingerprint;
        final String realm;
        final String sessionId;
        final String stateId;

        PeerV2State(String token, String fingerprint, String realm,
                    String sessionId, String stateId) {
            this.token = token;
            this.fingerprint = fingerprint;
            this.realm = realm;
            this.sessionId = sessionId;
            this.stateId = stateId;
        }
    }

    static final class LogoutCapability {
        final String realm;
        final String fingerprint;
        final String sessionId;
        final String stateId;
        final boolean tokenPresent;

        private LogoutCapability(String realm, String fingerprint, String sessionId,
                                 String stateId, boolean tokenPresent) {
            this.realm = clean(realm);
            this.fingerprint = clean(fingerprint);
            this.sessionId = clean(sessionId);
            this.stateId = clean(stateId);
            this.tokenPresent = tokenPresent;
        }

        boolean valid() {
            return SessionRealmRules.validDigest(realm) && fingerprint.length() >= 16
                && SessionRealmRules.validSessionId(sessionId)
                && SessionRealmRules.validSessionId(stateId);
        }

        String debounceKey() {
            return realm + "\n" + fingerprint + "\n" + sessionId + "\n" + stateId;
        }
    }

    /** Probe before the login HTTP request; missing protocol-v2 columns mean zero later writes. */
    static PeerStateSnapshot capturePeerStates(Context ctx, String expectedRealm) {
        Map<String, String> states = new LinkedHashMap<>();
        if (!BuildConfig.CROSS_APP_SESSION_ENABLED || ctx == null
                || !SessionRealmRules.validDigest(expectedRealm)) {
            return new PeerStateSnapshot(states);
        }
        Context app = ctx.getApplicationContext();
        for (String pkg : SESSION_PEERS) {
            PeerV2State peer = queryPeerV2State(app, pkg);
            if (peer != null && expectedRealm.equals(peer.realm)) {
                states.put(pkg, peer.stateId);
            }
        }
        return new PeerStateSnapshot(states);
    }

    /** Capture before local clear/Panel switch; no token or capability is ever broadcast. */
    static LogoutCapability captureLogoutCapability(Context ctx) {
        if (ctx == null) return new LogoutCapability("", "", "", "", false);
        Context app = ctx.getApplicationContext();
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String realm = SessionRealmResolver.activeFingerprint(app);
            SecureTokenStore.ensureSessionStateForRealm(prefs, realm);
            SecureTokenStore.BoundSession session =
                SecureTokenStore.readBoundSession(prefs, realm);
            return new LogoutCapability(session.realm, session.fingerprint,
                session.sessionId, session.stateId, !session.token.isEmpty());
        }
    }

    /** Push a proven local login only to peers whose v2 state was captured before login began. */
    static void propagateLogin(Context ctx, String token, String fingerprint,
                               String realmSha256, PeerStateSnapshot peerStates) {
        if (!BuildConfig.CROSS_APP_SESSION_ENABLED
                || ctx == null || token == null || token.isEmpty()
                || fingerprint == null || fingerprint.length() < 16
                || !SessionRealmRules.validDigest(realmSha256)
                || peerStates == null) return;
        Context app = ctx.getApplicationContext();
        final String activeRealm;
        final String sessionId;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            activeRealm = SessionRealmResolver.activeFingerprint(app);
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SecureTokenStore.ensureSessionStateForRealm(prefs, activeRealm);
            SecureTokenStore.BoundSession session =
                SecureTokenStore.readBoundSession(prefs, activeRealm);
            if (!activeRealm.equals(realmSha256)
                    || !fingerprint.equals(session.fingerprint)
                    || !token.equals(session.token)
                    || !session.hasCapability()) return;
            sessionId = session.sessionId;
        }
        for (String pkg : SESSION_PEERS) {
            String expectedStateId = peerStates.expectedStateId(pkg);
            if (!SessionRealmRules.validSessionId(expectedStateId)) continue;
            try {
                ContentValues values = new ContentValues();
                values.put("protocolVersion", PROTOCOL_VERSION);
                values.put("expectedStateId", expectedStateId);
                values.put("token", token);
                values.put("fingerprint", fingerprint);
                values.put("realm", activeRealm);
                values.put("sessionId", sessionId);
                app.getContentResolver().insert(peerTokenUri(pkg), values);
            } catch (Throwable ignored) {
                // Missing/legacy/unavailable peer: local login remains authoritative.
            }
        }
        broadcast(app, "login", null);
    }

    static void propagateLogout(Context ctx, String exceptPkg) {
        propagateLogout(ctx, exceptPkg, captureLogoutCapability(ctx));
    }

    /** Query each peer first; legacy providers and mismatched envelopes receive no DELETE. */
    static void propagateLogout(Context ctx, String exceptPkg,
                                LogoutCapability capability) {
        if (!BuildConfig.CROSS_APP_SESSION_ENABLED || ctx == null
                || capability == null || !capability.valid()
                || suppressDuplicateLogout(capability)) return;
        Context app = ctx.getApplicationContext();
        for (String pkg : SESSION_PEERS) {
            if (pkg.equals(exceptPkg)) continue;
            PeerV2State peer = queryPeerV2State(app, pkg);
            if (peer == null || peer.token.isEmpty()
                    || !capability.realm.equals(peer.realm)
                    || !capability.fingerprint.equals(peer.fingerprint)
                    || !capability.sessionId.equals(peer.sessionId)) continue;
            try {
                Uri deleteUri = peerTokenUri(pkg).buildUpon()
                    .appendQueryParameter("protocolVersion",
                        String.valueOf(PROTOCOL_VERSION))
                    .appendQueryParameter("expectedStateId", peer.stateId)
                    .appendQueryParameter("realm", capability.realm)
                    .appendQueryParameter("fingerprint", capability.fingerprint)
                    .appendQueryParameter("sessionId", capability.sessionId)
                    .build();
                app.getContentResolver().delete(deleteUri, null, null);
            } catch (Throwable ignored) {
            }
        }
        broadcast(app, "logout", exceptPkg);
    }

    private static boolean suppressDuplicateLogout(LogoutCapability capability) {
        synchronized (SessionBridge.class) {
            long now = SystemClock.uptimeMillis();
            String key = capability.debounceKey();
            if (key.equals(lastLogoutCapabilityKey)
                    && now - lastLogoutPropagateMs < LOGOUT_DEBOUNCE_MS) return true;
            lastLogoutCapabilityKey = key;
            lastLogoutPropagateMs = now;
            return false;
        }
    }

    private static PeerV2State queryPeerV2State(Context app, String pkg) {
        Cursor cursor = null;
        try {
            cursor = app.getContentResolver().query(
                peerTokenUri(pkg), null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) return null;
            int protocolColumn = cursor.getColumnIndex("protocolVersion");
            int stateColumn = cursor.getColumnIndex("stateId");
            int tokenColumn = cursor.getColumnIndex("token");
            int fingerprintColumn = cursor.getColumnIndex("fingerprint");
            int realmColumn = cursor.getColumnIndex("realm");
            int sessionColumn = cursor.getColumnIndex("sessionId");
            if (protocolColumn < 0 || stateColumn < 0 || tokenColumn < 0
                    || fingerprintColumn < 0 || realmColumn < 0 || sessionColumn < 0
                    || cursor.getInt(protocolColumn) != PROTOCOL_VERSION) return null;
            String stateId = clean(cursor.getString(stateColumn));
            if (!SessionRealmRules.validSessionId(stateId)) return null;
            return new PeerV2State(clean(cursor.getString(tokenColumn)),
                clean(cursor.getString(fingerprintColumn)),
                clean(cursor.getString(realmColumn)),
                clean(cursor.getString(sessionColumn)), stateId);
        } catch (Throwable unavailableOrLegacy) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void broadcast(Context app, String state, String exceptPkg) {
        for (String pkg : SESSION_PEERS) {
            if (pkg.equals(exceptPkg)) continue;
            try {
                Intent intent = new Intent(ACTION);
                intent.setPackage(pkg);
                intent.putExtra("state", state);
                intent.putExtra("source", app.getPackageName());
                app.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isLoggedIn(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            SharedPreferences prefs = app
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String realm = SessionRealmResolver.activeFingerprint(app);
            SecureTokenStore.ensureSessionStateForRealm(prefs, realm);
            SecureTokenStore.BoundSession session =
                SecureTokenStore.readBoundSession(prefs, realm);
            return session.hasCapability() && !session.token.isEmpty();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
