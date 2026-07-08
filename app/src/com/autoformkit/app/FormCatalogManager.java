package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads the remote form catalog and caches it for {@link FormCatalog} to serve on the next
 * launch. Deliberately mirrors {@link UpdateManager}'s shape (asset-driven config, startup +
 * throttled-foreground checks, background thread, silent failure) but is simpler: the payload is
 * a small JSON document held in memory, applied atomically, and never shown to the user.
 *
 * <p>A freshly downloaded catalog is written to disk but is NOT hot-swapped into the running
 * session — {@link MainActivity} reads profiles once in {@code onCreate}, so the new catalog takes
 * effect on the next app start. This avoids mutating the form under an in-progress queue/draft.
 *
 * <p>Two independent gates keep an old install safe: the fetch-time gate here skips a catalog
 * whose {@code schemaVersion} exceeds {@link FormCatalog#SUPPORTED_SCHEMA_VERSION}, and the
 * load-time gate in {@link FormCatalog} ignores such a file even if it somehow landed on disk.
 *
 * <p>Disabled until {@code form-catalog-config.json} ships with {@code enabled:true} and a
 * {@code manifestUrl}; absent/empty config makes this a no-op and the app keeps using the seed.
 */
final class FormCatalogManager {
    private static final String CONFIG_ASSET = "form-catalog-config.json";
    private static final String PREFS = "form_catalog_state";
    private static final String PREF_LAST_CHECK_MS = "last_check_ms";
    private static final String PREF_APPLIED_VERSION = "applied_version";
    /** Foreground re-checks no more than once per 10 minutes, matching UpdateManager. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 10 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences prefs;
    private boolean checkedThisProcess = false;

    FormCatalogManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void checkOnStartup() {
        check(false);
    }

    /** Called from {@code Activity#onResume()}; throttled by {@link #FOREGROUND_CHECK_INTERVAL_MS}. */
    void checkOnForeground() {
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(PREF_LAST_CHECK_MS, 0L) < FOREGROUND_CHECK_INTERVAL_MS) return;
        check(true);
    }

    private void check(boolean force) {
        if (!force && checkedThisProcess) return;
        checkedThisProcess = true;
        prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                sync();
            } catch (Exception exc) {
                // Catalog sync must never block (or crash) the form workflow.
            }
        }, "form-catalog-sync").start();
    }

    private void sync() throws Exception {
        Config config = loadConfig();
        if (config == null || !config.enabled || config.manifestUrl.isEmpty()) {
            return;
        }

        JSONObject manifest = new JSONObject(getText(config.manifestUrl, config.token));
        if (manifest.optInt("schemaVersion", 1) > FormCatalog.SUPPORTED_SCHEMA_VERSION) {
            return; // fetch-time gate: this build predates the catalog's form shape.
        }
        long minApp = manifest.optLong("minAppVersionCode", 0L);
        long appVersion = currentVersionCodeOrNegative();
        if (minApp > 0 && appVersion >= 0 && appVersion < minApp) {
            return;
        }
        int version = manifest.optInt("version", 0);
        if (version <= 0 || version <= prefs.getInt(PREF_APPLIED_VERSION, 0)) {
            return; // already applied (or no usable version) — nothing to do.
        }
        String profilesUrl = manifest.optString("profilesUrl", "").trim();
        if (profilesUrl.isEmpty()) {
            return;
        }
        String expectedSha = manifest.optString("sha256", "")
            .toLowerCase(Locale.US).replace("sha256:", "").trim();

        byte[] bytes = getBytes(profilesUrl, config.token);
        if (!expectedSha.isEmpty()) {
            String actual = sha256(bytes);
            if (!expectedSha.equals(actual)) {
                throw new IOException("Catalog SHA-256 mismatch. expected=" + expectedSha + " actual=" + actual);
            }
        }

        // Validate before it ever reaches the cache: must parse, be schema-compatible, and
        // actually contain profiles. A bad payload is dropped, leaving the prior cache/seed intact.
        JSONObject root = new JSONObject(new String(bytes, "UTF-8"));
        if (root.optInt("schemaVersion", 1) > FormCatalog.SUPPORTED_SCHEMA_VERSION) {
            return;
        }
        JSONArray profiles = root.optJSONArray("profiles");
        if (profiles == null || profiles.length() == 0) {
            throw new IOException("Catalog contains no profiles");
        }

        writeAtomic(bytes);
        prefs.edit().putInt(PREF_APPLIED_VERSION, version).apply();
    }

    /** Write tmp + fsync + rename, so a crash mid-write can never leave a torn catalog file. */
    private void writeAtomic(byte[] bytes) throws IOException {
        File dir = FormCatalog.cacheDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create catalog directory");
        }
        File tmp = new File(dir, FormCatalog.CACHE_FILE + ".tmp");
        File dest = FormCatalog.cacheFile(context);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("Cannot replace existing catalog");
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("Cannot finalize catalog");
        }
    }

    private Config loadConfig() {
        Config config = new Config();
        try {
            // Catalog now follows the user-configurable panel address (AppConfig), NOT the bundled
            // asset. An unset panel base yields an empty manifestUrl, so sync() no-ops and the app
            // keeps whatever it already has (cached catalog, else the baked-in seed). Never throws.
            config.manifestUrl = AppConfig.manifestUrl(AppConfig.panelBase(context));
            config.token = AppConfig.catalogKey(context);
            config.enabled = !config.manifestUrl.isEmpty();
        } catch (Exception ignored) {
            config.enabled = false;
            config.manifestUrl = "";
            config.token = "";
        }
        return config;
    }

    private long currentVersionCodeOrNegative() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception exc) {
            return -1L;
        }
    }

    // Cap the in-memory response so a compromised/misbehaving panel can't OOM-kill the process with a
    // giant body. The catalog is ~200 KB today; 16 MB is vast headroom yet well under the heap.
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private String getText(String url, String token) throws Exception {
        return new String(getBytes(url, token), "UTF-8");
    }

    private byte[] getBytes(String url, String token) throws Exception {
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

    // Host of the configured panel. The access key is scoped to this host only: the manifest is fetched
    // from it, but the manifest's profilesUrl is server-supplied and could point anywhere, so we must
    // never attach the key to a non-panel host (nor leak it across a redirect).
    private String panelHost() {
        try {
            String base = AppConfig.panelBase(context);
            return base.isEmpty() ? null : new URL(base).getHost();
        } catch (Exception exc) {
            return null;
        }
    }

    private HttpURLConnection openConnection(String url, String token) throws Exception {
        URL current = new URL(url);
        String panelHost = panelHost();
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "AutoFormKit");
            if (token != null && !token.isEmpty() && panelHost != null
                    && current.getHost() != null && current.getHost().equalsIgnoreCase(panelHost)) {
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

    private InputStream responseStream(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (input == null) {
            throw new IOException("HTTP " + code);
        }
        if (code >= 400) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            throw new IOException("HTTP " + code + ": " + output.toString("UTF-8"));
        }
        return input;
    }

    private String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static final class Config {
        boolean enabled;
        String manifestUrl;
        String token;
    }
}
