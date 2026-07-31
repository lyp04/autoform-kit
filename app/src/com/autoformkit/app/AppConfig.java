package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Panel-provided runtime configuration (versioned backend adapter, session policy, notifications,
 * branding, and update channel).
 *
 * <p>The app is deliberately backend-agnostic: it has NO built-in server. A user points it at a
 * form system by entering a <b>panel address</b> (+ access key) in Settings, stored in the app's
 * {@code "settings"} prefs as {@link #KEY_PANEL_BASE}/{@link #KEY_CATALOG_KEY}. Both DEFAULT TO
 * EMPTY — there is no migration/prefill from any bundled asset; the user must fill them in.
 *
 * <p>{@link #refresh} fetches {@code <panelBase>/api/config} into a candidate slot. The candidate
 * becomes active only together with the matching catalog at a safe boundary; {@link #load} returns
 * the config from one coherently read active pair. A failed/absent/old-shape fetch never replaces
 * the prior pair. When a Panel address is configured, {@code MainActivity}'s bootstrap gate remains
 * locked until both active halves are valid for that exact connection; it never falls through to a
 * bundled endpoint or to an unbound legacy cache.
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
    private static final String CANDIDATE_FILE = "app-config.candidate.json";
    static final String CACHE_BINDING_FIELD = "_autoFormKitCache";
    static final String CACHE_SOURCE_SHA_FIELD = "catalogSourceSha256";

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

    /** Cached panel config JSON, or null if absent/corrupt. */
    static JSONObject load(Context context) {
        String connection = connectionNamespaceId(panelBase(context), catalogKey(context));
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(context, connection);
        return pair == null ? null : pair.config;
    }

    /**
     * Fetch {@code <panelBase>/api/config} on a background thread and cache it. NEVER throws and
     * NEVER blocks the caller. A blank panel base, a failed fetch, or a payload without a complete
     * supported backend adapter leaves the previous cache (or none) untouched. {@code listener}
     * (optional) is invoked with the parsed config on success or null otherwise, so the caller can
     * hot-swap its in-memory copy.
     */
    static void refresh(Context context, String panelBase, String key, Listener listener) {
        final String base = stripTrailingSlash(panelBase == null ? "" : panelBase.trim());
        final String token = key == null ? "" : key.trim();
        if (!PanelConnectionInputRules.allowsPanelNetwork(base, token)) {
            notify(listener, null); // empty/partial tuple has no authority to contact a Panel.
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            JSONObject parsed = null;
            try {
                byte[] bytes = get(base + CONFIG_PATH, token);
                JSONObject json = new JSONObject(new String(bytes, "UTF-8")); // validate before caching
                // The coordinator repeats the connection check while holding the same lock used
                // by pair promotion and connection cleanup, closing the old-response TOCTOU.
                if (hasUsablePayload(json)
                        && PanelPairCacheCoordinator.stageConfigCandidate(
                            app, base, token, json)) {
                    parsed = json;
                } else if (hasUsablePayload(json)
                        && connectionMatches(app, base, token)) {
                    parsed = json; // exact same active revision/content: successful no-op.
                }
            } catch (Exception ignored) {
                // Panel config is best-effort; failure must never disturb login/submit.
            }
            notify(listener, parsed);
        }, "app-config-refresh").start();
    }

    /**
     * A bootstrap config must carry the current versioned adapter plus the mandatory login,
     * upload, and submit contract. Optional workflow capabilities remain profile-gated, but an
     * older login-only config must never unlock a form that could later upload or POST with guessed
     * fields.
     */
    static boolean hasUsablePayload(JSONObject json) {
        if (json == null || json.optJSONObject("backendAdapter") == null) return false;
        BackendAdapter adapter = BackendAdapter.from(json);
        return catalogVersion(json) > 0
            && adapter.isSupported()
            && adapter.missingForLogin().isEmpty()
            && adapter.missingForSubmit(false, false, false, false).isEmpty();
    }

    /** Strict positive catalog revision advertised by /api/config, or zero when invalid/missing. */
    static int catalogVersion(JSONObject json) {
        if (json == null) return 0;
        Object raw = json.opt("catalogVersion");
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) return 0;
        long value = ((Number) raw).longValue();
        return value > 0L && value <= Integer.MAX_VALUE ? (int) value : 0;
    }

    /**
     * Resolves Panel-owned public repository coordinates over the APK's exact update protocol.
     * Channel, tag and manifest asset remain device/APK values; the optional v1 structured source
     * must exactly duplicate the flat coordinates still consumed by old Apps.
     */
    static UpdateSourceRules.Resolved resolveUpdateSource(
            JSONObject apkConfig, JSONObject panelConfig,
            String devicePreference) {
        return UpdateSourceRules.resolve(apkConfig, panelConfig, devicePreference);
    }

    static boolean connectionMatches(Context context, String panelBase, String key) {
        return stripTrailingSlash(panelBase == null ? "" : panelBase.trim())
                .equals(AppConfig.panelBase(context))
            && fingerprint(key).equals(fingerprint(AppConfig.catalogKey(context)));
    }

    static void stampConnection(JSONObject json, String panelBase, String key) {
        if (json == null) return;
        try {
            json.put(CACHE_BINDING_FIELD, new JSONObject()
                .put("panelBase", stripTrailingSlash(panelBase == null ? "" : panelBase.trim()))
                .put("keyFingerprint", fingerprint(key)));
        } catch (Exception ignored) {}
    }

    /** Adds the exact downloaded catalog-byte digest to its local-only cache binding. */
    static void stampCatalogSource(JSONObject json, String sourceSha256) {
        if (json == null || sourceSha256 == null
                || !sourceSha256.matches("[0-9a-f]{64}")) return;
        try {
            JSONObject binding = json.optJSONObject(CACHE_BINDING_FIELD);
            if (binding != null) binding.put(CACHE_SOURCE_SHA_FIELD, sourceSha256);
        } catch (Exception ignored) {}
    }

    static String catalogSourceSha256(JSONObject json) {
        try {
            String value = json.optJSONObject(CACHE_BINDING_FIELD)
                .optString(CACHE_SOURCE_SHA_FIELD, "");
            return value.matches("[0-9a-f]{64}") ? value : "";
        } catch (Exception invalid) {
            return "";
        }
    }

    static boolean isBoundToConnection(JSONObject json, String panelBase, String key) {
        if (json == null) return false;
        JSONObject binding = json.optJSONObject(CACHE_BINDING_FIELD);
        if (binding == null) return false;
        return stripTrailingSlash(panelBase == null ? "" : panelBase.trim())
                .equals(binding.optString("panelBase", ""))
            && fingerprint(key).equals(binding.optString("keyFingerprint", ""));
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            return "";
        }
    }

    /** Stable, non-secret namespace for panel-bound local state such as drafts. */
    static String connectionNamespaceId(String panelBase, String key) {
        String source = stripTrailingSlash(panelBase == null ? "" : panelBase.trim())
            + "\n" + fingerprint(key);
        String value = fingerprint(source);
        return value.length() > 20 ? value.substring(0, 20) : value;
    }

    /** Full-strength non-secret connection identity for credential-realm separation. */
    static String connectionSecurityId(String panelBase, String key) {
        String source = stripTrailingSlash(panelBase == null ? "" : panelBase.trim())
            + "\n" + fingerprint(key);
        return fingerprint(source);
    }

    private static void notify(Listener listener, JSONObject result) {
        if (listener == null) return;
        try {
            listener.onResult(result);
        } catch (Exception ignored) {
            // a listener bug must not turn a best-effort refresh into a crash.
        }
    }

    // ---- disk cache (same crash-recoverable storage contract as FormCatalog) ------------------

    private static File cacheDir(Context context) {
        return new File(context.getFilesDir(), CACHE_DIR);
    }

    static File cacheFile(Context context) {
        return new File(cacheDir(context), CACHE_FILE);
    }

    static File candidateCacheFile(Context context) {
        return new File(cacheDir(context), CANDIDATE_FILE);
    }

    /** Exact legacy/current cache text for the one-time verified upgrade migration. */
    static String readRawCache(Context context) throws IOException {
        // Do not preflight the base file with exists()/length(). AtomicFile.openRead() must be
        // allowed to restore its complete .bak after a process dies between base -> backup and the
        // replacement write; checking only the base would incorrectly report the cache absent.
        return AtomicCacheFile.readUtf8(cacheFile(context));
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
        // The access key is for the exact panel origin only — never leak it across a redirect.
        URL panelOrigin = current;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "AutoFormKit");
            if (!token.isEmpty() && sameOrigin(current, panelOrigin)) {
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

    private static boolean sameOrigin(URL left, URL right) {
        return left.getProtocol().equalsIgnoreCase(right.getProtocol())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
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
