package com.autoformkit.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.List;

/**
 * Optional cross-app session bus. This app is the hub / source of truth; opt-in peer apps (none by
 * default) can inherit the login and stay in lock-step so a login / logout / server-side kick in any
 * one of them propagates to all.
 *
 * <p>Two layers:
 * <ul>
 *   <li><b>Data (the truth)</b>: every participant exposes a {@code SessionAuthProvider}. We INSERT
 *       (login) / DELETE (logout) the peer's token through it. Access is gated by a package
 *       allow-list ({@link #SESSION_PEERS}). Logout MUST use delete: {@code SecureTokenStore.put("")}
 *       clears, so insert deliberately ignores an empty token to avoid an accidental wipe — only
 *       delete expresses an intentional logout.</li>
 *   <li><b>Signal</b>: a directed broadcast ({@link #ACTION}) carrying NO token, only telling the
 *       peer to re-read its own local token. A forged broadcast is harmless: the forger can't delete
 *       the allow-list-protected token, so the peer's re-read still finds it present and stays
 *       logged in.</li>
 * </ul>
 *
 * <p>{@link #SESSION_PEERS} is empty by default (standalone). To enable cross-app SSO, add the peer
 * apps' release package ids here; the peers must expose a matching {@code SessionAuthProvider}.
 */
final class SessionBridge {
    static final String ACTION = "com.autoformkit.app.SESSION_CHANGED";

    /** Apps allowed to read/write this app's session, and the targets we propagate to. Empty = standalone. */
    static final List<String> SESSION_PEERS = Arrays.asList();

    private static final String PREFS = "settings";

    private static final long LOGOUT_DEBOUNCE_MS = 2000L;
    private static long lastLogoutPropagateMs = -LOGOUT_DEBOUNCE_MS;

    private SessionBridge() {
    }

    private static Uri peerTokenUri(String pkg) {
        return Uri.parse("content://" + pkg + ".session/token");
    }

    /** A local first-hand login: push token (+fingerprint) to every peer and signal them to re-read. */
    static void propagateLogin(Context ctx, String token, String fingerprint) {
        if (ctx == null || token == null || token.isEmpty()) {
            return;
        }
        Context app = ctx.getApplicationContext();
        for (String pkg : SESSION_PEERS) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("token", token);
                if (fingerprint != null && !fingerprint.isEmpty()) {
                    cv.put("fingerprint", fingerprint);
                }
                app.getContentResolver().insert(peerTokenUri(pkg), cv);
            } catch (Throwable ignored) {
                // peer not installed / not allowing writes — local login still stands
            }
        }
        broadcast(app, "login", null);
    }

    /**
     * A local first-hand logout / expiry: delete the shared token from peers (except {@code exceptPkg},
     * the peer that just told us) and signal them. Debounced to avoid a broadcast storm on rapid
     * repeat logouts.
     */
    static void propagateLogout(Context ctx, String exceptPkg) {
        if (ctx == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastLogoutPropagateMs < LOGOUT_DEBOUNCE_MS) {
            return;
        }
        lastLogoutPropagateMs = now;
        Context app = ctx.getApplicationContext();
        for (String pkg : SESSION_PEERS) {
            if (pkg.equals(exceptPkg)) {
                continue;
            }
            try {
                app.getContentResolver().delete(peerTokenUri(pkg), null, null);
            } catch (Throwable ignored) {
            }
        }
        broadcast(app, "logout", exceptPkg);
    }

    private static void broadcast(Context app, String state, String exceptPkg) {
        for (String pkg : SESSION_PEERS) {
            if (pkg.equals(exceptPkg)) {
                continue;
            }
            try {
                Intent intent = new Intent(ACTION);
                intent.setPackage(pkg); // directed — required for Android 8+ to reach a manifest receiver
                intent.putExtra("state", state);
                intent.putExtra("source", app.getPackageName());
                app.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Re-read THIS app's own token. Used by receivers — never touches peers, never broadcasts. */
    static boolean isLoggedIn(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = SecureTokenStore.get(prefs);
        return token != null && !token.trim().isEmpty();
    }
}
