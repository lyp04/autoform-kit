package com.autoformkit.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Static (manifest-registered) receiver for the cross-app session bus. Works even when this app
 * is backgrounded or its process was killed — the OS revives it to handle a directed broadcast.
 *
 * <p>It reacts at the DATA layer only: re-reads this app's local token and, if a peer has cleared it
 * (logout / expiry), records a pending-logout flag for {@code MainActivity} to consume on next
 * resume. It never shows UI (a revived receiver has no UI context). Live updates while this app is in
 * the foreground are handled by a separate runtime receiver in {@code MainActivity}.
 */
public class SessionEventReceiver extends BroadcastReceiver {
    static final String PREFS = "settings";
    static final String PENDING_LOGOUT_KEY = "pending_session_logout";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String source = intent.getStringExtra("source");
        if (source != null && source.equals(context.getPackageName())) {
            return; // R4: ignore our own broadcast
        }
        boolean loggedIn = SessionBridge.isLoggedIn(context);
        if (!loggedIn) {
            // A peer cleared our shared token. Flag it for the UI to pick up on next resume, and — as
            // the hub — fan the logout out to any OTHER peers (excluding the one that told us). With a
            // single peer this fan-out is a no-op; it matters once more apps opt in.
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(PENDING_LOGOUT_KEY, true).apply();
            SessionBridge.propagateLogout(context, source);
        }
        // If still logged in (a login signal, or a harmless forged broadcast) there is nothing to do
        // here: MainActivity re-reads the token on resume / via its runtime receiver.
        Diagnostics.append(context, "SessionEventReceiver: state=" + intent.getStringExtra("state")
                + " source=" + source + " loggedIn=" + loggedIn);
    }
}
