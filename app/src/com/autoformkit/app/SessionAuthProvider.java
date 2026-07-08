package com.autoformkit.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Optional cross-app single-sign-on bridge for opt-in peer apps (see {@link SessionBridge}).
 *
 * Exposes the current login token, the web-client fingerprint that token's session was established
 * with, and the currently-selected template context for reading, and accepts a token (plus
 * fingerprint) written back so both apps stay logged in as one. Sharing the fingerprint matters: the
 * web session is tied to it, so a reader presenting a different fingerprint with the same token can
 * be flagged as "logged in elsewhere".
 *
 * Security: peer apps may be signed with different keys, so a signature-level permission cannot be
 * relied on. Access is gated purely by a caller package allow-list ({@link SessionBridge#SESSION_PEERS},
 * empty by default): only a listed caller may read or write; everyone else is rejected (query ->
 * null, insert -> SecurityException).
 */
public class SessionAuthProvider extends ContentProvider {
    static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".session"; // tracks applicationId; debug gets .debug.session
    // Allowed callers (read + write) live in SessionBridge.SESSION_PEERS (empty by default). This
    // provider and the session bus gate on the same list.

    private static final String PREFS_NAME = "settings";
    private static final String LAST_PROFILE_ID_KEY = "last_profile_id";
    private static final String WEB_FINGERPRINT_KEY = "web_client_fingerprint";

    private static final String PATH_TOKEN = "token";
    private static final String PATH_PROFILE = "profile";
    private static final String PATH_FINGERPRINT = "fingerprint";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!isCallerAllowed()) {
            return null;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        String path = uri.getLastPathSegment();
        if (PATH_TOKEN.equals(path)) {
            return tokenCursor(prefs);
        }
        if (PATH_FINGERPRINT.equals(path)) {
            return fingerprintCursor(prefs);
        }
        if (PATH_PROFILE.equals(path)) {
            return profileCursor(prefs);
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!isCallerAllowed()) {
            throw new SecurityException("Caller not allowed");
        }
        String path = uri.getLastPathSegment();
        if (!PATH_TOKEN.equals(path)) {
            return null;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        String token = values == null ? null : values.getAsString(PATH_TOKEN);
        // Never clear the shared session through a write: ignore an empty/missing token instead of
        // letting SecureTokenStore.put("") wipe it. (An allowed-but-malformed insert could otherwise
        // log the other app out.) Explicit logout is intentionally local-only in each app.
        if (token == null || token.isEmpty()) {
            return uri;
        }
        SecureTokenStore.put(prefs, token);
        // Keep the fingerprint in lock-step with the token when the caller supplies one, so the
        // shared session presents a consistent web identity from whichever app made the call.
        String fingerprint = values == null ? null : values.getAsString(PATH_FINGERPRINT);
        if (fingerprint != null && !fingerprint.isEmpty()) {
            prefs.edit().putString(WEB_FINGERPRINT_KEY, fingerprint).apply();
        }
        notifyTokenChange(uri);
        return uri;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!isCallerAllowed()) {
            throw new SecurityException("Caller not allowed");
        }
        if (!PATH_TOKEN.equals(uri.getLastPathSegment())) {
            return 0;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return 0;
        }
        // Explicit cross-app logout. Clear the token but KEEP the fingerprint: it is this device's
        // stable web identity, unrelated to the login lifetime, and reusing it on the next login
        // avoids a "logged in elsewhere" rejection.
        SecureTokenStore.clear(prefs);
        notifyTokenChange(uri);
        return 1;
    }

    private void notifyTokenChange(Uri uri) {
        Context context = getContext();
        if (context != null) {
            context.getContentResolver().notifyChange(uri, null);
        }
    }

    private boolean isCallerAllowed() {
        // Gate on the caller package. The OS guarantees a caller can't claim a package name that
        // belongs to an already-installed app, so while a listed peer is installed this is safe.
        // HARDENING TODO: to defend against a peer being uninstalled and its package name reclaimed
        // by a rogue app, pin the peer's signing cert (PackageManager.GET_SIGNING_CERTIFICATES,
        // compare SHA-256) here.
        String caller = getCallingPackage();
        return caller != null && SessionBridge.SESSION_PEERS.contains(caller);
    }

    private SharedPreferences prefs() {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private Cursor tokenCursor(SharedPreferences prefs) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"token"}, 1);
        String token = SecureTokenStore.get(prefs);
        cursor.addRow(new Object[]{token == null ? "" : token});
        return cursor;
    }

    private Cursor fingerprintCursor(SharedPreferences prefs) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"fingerprint"}, 1);
        cursor.addRow(new Object[]{prefs.getString(WEB_FINGERPRINT_KEY, "")});
        return cursor;
    }

    private Cursor profileCursor(SharedPreferences prefs) {
        String[] columns = new String[]{
            "templateId", "sku", "warehouseId", "searchText", "model", "displayName", "brand"
        };
        MatrixCursor empty = new MatrixCursor(columns, 0);

        String profileId = prefs.getString(LAST_PROFILE_ID_KEY, "");
        if (profileId == null || profileId.isEmpty()) {
            return empty;
        }
        JSONObject profile = findProfile(profileId);
        if (profile == null) {
            return empty;
        }

        JSONObject template = profile.optJSONObject("template");
        int templateId = template == null ? 0 : template.optInt("id", 0);
        String sku = template == null ? "" : template.optString("sku", "");
        int warehouseId = template == null ? 0 : template.optInt("warehouseId", 0);
        String searchText = profile.optString("searchText", "");
        String model = profile.optString("model", "");
        String displayName = profile.optString("displayName", "");
        String brand = profile.optString("brand", "");

        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(new Object[]{templateId, sku, warehouseId, searchText, model, displayName, brand});
        return cursor;
    }

    private JSONObject findProfile(String profileId) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        try {
            // Resolve through FormCatalog so this cross-app provider serves the same active
            // catalog (downloaded override or bundled seed) as the in-app workflow.
            JSONArray profiles = FormCatalog.loadProfiles(context);
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile != null && profileId.equals(profile.optString("id", ""))) {
                    return profile;
                }
            }
            return null;
        } catch (Exception exc) {
            return null;
        }
    }

}
