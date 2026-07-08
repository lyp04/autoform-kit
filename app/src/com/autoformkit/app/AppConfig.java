package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Panel-provided backend configuration ({@code backendApiBase}, {@code notifyWebhook}, {@code brand}).
 *
 * <p>The app is deliberately backend-agnostic: it has NO built-in server. A user points it at a
 * form system by entering a <b>panel address</b> (+ access key) in Settings, stored in the app's
 * {@code "settings"} prefs as {@link #KEY_PANEL_BASE}/{@link #KEY_CATALOG_KEY}. Both DEFAULT TO
 * EMPTY — there is no migration/prefill from any bundled asset; the user must fill them in.
 *
 * <p>{@link #refresh} fetches {@code <panelBase>/api/config} and caches it under {@code filesDir};
 * {@link #load} returns the cached JSON. Mirrors {@link FormCatalog}/{@link FormCatalogManager}:
 * atomic write, load-on-startup, and it SWALLOWS EVERY ERROR. A failed/absent/empty fetch never
 * throws and never blocks — {@code MainActivity} then resolves the backend base as "" (unconfigured),
 * which every call site treats as "skip + prompt", so a missing panel config can never crash the
 * app or fire a request at a bogus host.
 */
public final class AppConfig {
    /** App-wide SharedPreferences file — the same one {@code MainActivity} opens as {@code prefs}. */
    static final String PREFS = "settings";
    static final String KEY_PANEL_BASE = "panelBase";
    static final String KEY_CATALOG_KEY = "catalogKey";

    private static final String MANIFEST_SUFFIX = "/catalog/manifest";
    private static final String CONFIG_PATH = "/api/config";
    private static final String CACHE_DIR = "app-config";
    private static final String CACHE_FILE = "app-config.json";

    private AppConfig() {}

    /** Notified (on the fetch thread) when {@link #refresh} finishes: the config on success, else null. */
    interface Listener {
        void onResult(JSONObject configOrNull);
    }

    // ---- stored panel address (no asset fallback: empty means unconfigured) -------------------

    /** Stored panel base (trailing slashes stripped), or "" when the user hasn't configured one. */
    static String panelBase(Context context) {
        try {
            return stripTrailingSlash(prefs(context).getString(KEY_PANEL_BASE, "").trim());
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Stored catalog access key, or "" when unset. */
    static String catalogKey(Context context) {
        try {
            return prefs(context).getString(KEY_CATALOG_KEY, "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** {@code <panelBase>/catalog/manifest}; empty in → empty out (caller treats "" as "skip"). */
    static String manifestUrl(String panelBase) {
        String base = stripTrailingSlash(panelBase == null ? "" : panelBase.trim());
        return base.isEmpty() ? "" : base + MANIFEST_SUFFIX;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- cached panel config -----------------------------------------------------------------

    /** Cached notify webhook, or "" when unset/unconfigured. Safe on any thread; never throws. */
    public static String notifyWebhook(Context context) {
        JSONObject config = load(context);
        return config == null ? "" : config.optString("notifyWebhook", "").trim();
    }

    /**
     * Cached {@code endpoints} object (panel-configurable backend REST paths), or an empty object when
     * unset/unconfigured. Never null; safe on any thread; never throws. Callers pair this with a
     * per-key default equal to the current literal, so a missing/blank override changes nothing.
     */
    public static JSONObject endpoints(Context context) {
        JSONObject config = load(context);
        JSONObject nested = config == null ? null : config.optJSONObject("endpoints");
        return nested == null ? new JSONObject() : nested;
    }

    /** Resolve one backend path: the panel override for {@code key}, else {@code def} (used when blank). */
    public static String endpoint(Context context, String key, String def) {
        return endpoint(endpoints(context), key, def);
    }

    /** Same as {@link #endpoint(Context,String,String)} but against a pre-loaded {@link #endpoints} object. */
    public static String endpoint(JSONObject endpoints, String key, String def) {
        if (endpoints == null) return def;
        String value = endpoints.optString(key, def).trim();
        return value.isEmpty() ? def : value;
    }

    /** Cached panel config JSON ({@code {backendApiBase,notifyWebhook,brand}}), or null if absent/corrupt. */
    static JSONObject load(Context context) {
        try {
            File file = cacheFile(context);
            if (!file.exists() || file.length() == 0L) return null;
            return new JSONObject(readFile(file));
        } catch (Exception corrupt) {
            return null; // any problem with the cache → callers stay "unconfigured".
        }
    }

    /**
     * Fetch {@code <panelBase>/api/config} on a background thread and cache it. NEVER throws and
     * NEVER blocks the caller. A blank panel base, a failed fetch, or a payload with nothing usable
     * leaves the previous cache (or none) untouched. {@code listener} (optional) is invoked with the
     * parsed config on success or null otherwise, so the caller can hot-swap its in-memory copy.
     */
    static void refresh(Context context, String panelBase, String key, Listener listener) {
        final Context app = context.getApplicationContext();
        final String base = stripTrailingSlash(panelBase == null ? "" : panelBase.trim());
        final String token = key == null ? "" : key.trim();
        if (base.isEmpty()) {
            notify(listener, null); // unconfigured — nothing to fetch.
            return;
        }
        new Thread(() -> {
            JSONObject parsed = null;
            try {
                byte[] bytes = get(base + CONFIG_PATH, token);
                JSONObject json = new JSONObject(new String(bytes, "UTF-8")); // validate before caching
                if (hasUsable(json)) {
                    writeAtomic(app, bytes);
                    parsed = json;
                }
            } catch (Exception ignored) {
                // Panel config is best-effort; failure must never disturb login/submit.
            }
            notify(listener, parsed);
        }, "app-config-refresh").start();
    }

    private static boolean hasUsable(JSONObject json) {
        return !json.optString("backendApiBase", "").trim().isEmpty()
            || !json.optString("notifyWebhook", "").trim().isEmpty()
            || !json.optString("brand", "").trim().isEmpty()
            || !json.optString("webOrigin", "").trim().isEmpty()
            || !json.optString("webReferer", "").trim().isEmpty()
            || !json.optString("updateOwner", "").trim().isEmpty()
            || !json.optString("updateRepo", "").trim().isEmpty();
    }

    private static void notify(Listener listener, JSONObject result) {
        if (listener == null) return;
        try {
            listener.onResult(result);
        } catch (Exception ignored) {
            // a listener bug must not turn a best-effort refresh into a crash.
        }
    }

    // ---- disk cache (mirrors FormCatalogManager.writeAtomic / FormCatalog.readFile) -----------

    private static File cacheDir(Context context) {
        return new File(context.getFilesDir(), CACHE_DIR);
    }

    private static File cacheFile(Context context) {
        return new File(cacheDir(context), CACHE_FILE);
    }

    /** Write tmp + fsync + rename, so a crash mid-write can never leave a torn config file. */
    private static void writeAtomic(Context context, byte[] bytes) throws IOException {
        File dir = cacheDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create app-config directory");
        }
        File tmp = new File(dir, CACHE_FILE + ".tmp");
        File dest = cacheFile(context);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("Cannot replace existing app-config");
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("Cannot finalize app-config");
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        }
    }

    // ---- HTTP (mirrors FormCatalogManager: manual redirects, Bearer, timeouts) ----------------

    // Cap the in-memory response so a compromised/misbehaving panel can't OOM-kill the process with a
    // giant body. /api/config is tiny; 16 MB is vast headroom yet well under the heap.
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private static byte[] get(String url, String token) throws Exception {
        HttpURLConnection conn = openConnection(url, token);
        try (InputStream input = responseStream(conn)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
            }
            return output.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url, String token) throws Exception {
        URL current = new URL(url);
        // The access key is for the panel host only — never leak it across a redirect to another host.
        String panelHost = current.getHost();
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "AutoFormKit");
            if (!token.isEmpty() && current.getHost() != null && current.getHost().equalsIgnoreCase(panelHost)) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without location: " + code);
                }
                current = new URL(current, location);
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static InputStream responseStream(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (input == null) {
            throw new IOException("HTTP " + code);
        }
        if (code >= 400) {
            throw new IOException("HTTP " + code);
        }
        return input;
    }

    private static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
