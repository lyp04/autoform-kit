package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads the remote form catalog into a candidate slot. The Panel connection stored in App
 * Settings is the only catalog source. Startup and throttled foreground checks run in the
 * background; {@link MainActivity} promotes a complete matching config/catalog candidate pair at
 * a safe boundary, never one half by itself.
 *
 * <p>A freshly downloaded catalog is staged and reported through a completion listener.
 * {@link MainActivity} can then atomically promote and hot-load it in Settings once the matching
 * config refresh has also completed. It is never injected into an already-rendered form.
 *
 * <p>Two independent gates keep an old install safe: the fetch-time gate here skips a catalog
 * whose {@code schemaVersion} exceeds {@link FormCatalog#SUPPORTED_SCHEMA_VERSION}, and the
 * load-time gate in {@link FormCatalog} ignores such a file even if it somehow landed on disk.
 *
 * <p>An unset Panel address makes synchronization a no-op and the App keeps its connection-bound
 * cache, or the bundled fictional seed when no valid cache exists.
 */
final class FormCatalogManager {
    private static final String PREFS = "form_catalog_state";
    private static final String PREF_LAST_CHECK_MS = "last_check_ms";
    /** Foreground re-checks no more than once per 10 minutes, matching UpdateManager. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 10 * 60 * 1000L;

    private final Context context;
    private final SharedPreferences prefs;
    private boolean checkedThisProcess = false;

    /** Called on the sync worker after every attempted startup/foreground synchronization. */
    interface Listener {
        void onFinished(String connectionNamespace);
    }

    FormCatalogManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void checkOnStartup() {
        check(false, null);
    }

    void checkOnStartup(Listener listener) {
        check(false, listener);
    }

    /** Called from {@code Activity#onResume()}; throttled by {@link #FOREGROUND_CHECK_INTERVAL_MS}. */
    void checkOnForeground() {
        checkOnForeground(null);
    }

    boolean foregroundCheckDue() {
        long now = System.currentTimeMillis();
        return now - prefs.getLong(PREF_LAST_CHECK_MS, 0L)
            >= FOREGROUND_CHECK_INTERVAL_MS;
    }

    boolean checkOnForeground(Listener listener) {
        if (!foregroundCheckDue()) return false;
        return check(true, listener);
    }

    /**
     * Bypasses the normal foreground throttle only for MainActivity's bounded whole-pair
     * publish-race recovery. The caller must launch the matching config refresh at the same time.
     */
    boolean checkPairedRetry(Listener listener) {
        return check(true, listener);
    }

    private boolean check(boolean force, Listener listener) {
        if (!force && checkedThisProcess) return false;
        final Config config = loadConfig();
        // Defense in depth: callers must never turn an empty/partial persisted tuple into a
        // worker thread. In particular, a base without its read key is not anonymous authority.
        if (!PanelConnectionInputRules.allowsPanelNetwork(
                config.panelBase, config.token)) return false;
        checkedThisProcess = true;
        prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
        final String connection = AppConfig.connectionNamespaceId(
            config.panelBase, config.token);
        new Thread(() -> {
            try {
                sync(config);
            } catch (Exception exc) {
                // A configured connection stays behind MainActivity's synchronization gate.
            } finally {
                notifyFinished(listener, connection);
            }
        }, "form-catalog-sync").start();
        return true;
    }

    private void sync(Config config) throws Exception {
        if (config == null
                || !PanelConnectionInputRules.allowsPanelNetwork(
                    config.panelBase, config.token)
                || !config.enabled || config.manifestUrl.isEmpty()) {
            return;
        }

        JSONObject manifest = new JSONObject(getText(
            config.manifestUrl, config.token, config.panelBase));
        if (manifest.optInt("schemaVersion", 1) > FormCatalog.SUPPORTED_SCHEMA_VERSION) {
            return; // fetch-time gate: this build predates the catalog's form shape.
        }
        long minApp = manifest.optLong("minAppVersionCode", 0L);
        long appVersion = currentVersionCodeOrNegative();
        if (minApp > 0 && appVersion >= 0 && appVersion < minApp) {
            return;
        }
        int version = FormCatalog.catalogVersion(manifest);
        String profilesUrl = manifest.optString("profilesUrl", "").trim();
        if (profilesUrl.isEmpty()) {
            return;
        }
        String expectedSha = manifest.optString("sha256", "")
            .toLowerCase(Locale.US).replace("sha256:", "").trim();
        String connection = AppConfig.connectionNamespaceId(config.panelBase, config.token);
        PanelPairCacheCoordinator.ActivePair existing =
            PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(context, connection);
        int cachedVersion = existing == null ? 0 : existing.version;
        String cachedSourceSha = existing == null ? ""
            : AppConfig.catalogSourceSha256(existing.catalogRoot);
        if (!shouldFetchPublishedCatalog(
                version, expectedSha, cachedVersion, cachedSourceSha)) return;

        byte[] bytes = getBytes(profilesUrl, config.token, config.panelBase);
        String actualSha = sha256(bytes);
        if (!expectedSha.isEmpty()) {
            if (!expectedSha.equals(actualSha)) {
                throw new IOException("Catalog SHA-256 mismatch");
            }
        }

        // Validate before it ever reaches the cache: must parse, be schema-compatible, and
        // actually contain profiles. A bad payload is dropped, leaving the prior cache/seed intact.
        JSONObject root = new JSONObject(new String(bytes, "UTF-8"));
        if (root.optInt("schemaVersion", 1) > FormCatalog.SUPPORTED_SCHEMA_VERSION) {
            return;
        }
        if (FormCatalog.catalogVersion(root) != version) {
            throw new IOException("Catalog payload version does not match manifest");
        }
        JSONArray profiles = root.optJSONArray("profiles");
        if (profiles == null || profiles.length() == 0) {
            throw new IOException("Catalog contains no profiles");
        }

        // The user can switch panels while either download is in flight. Bind the cache to the
        // captured connection and discard a stale response instead of overwriting the new panel.
        // The coordinator rechecks the captured connection under the same handoff lock used by
        // promotion and cleanup, then stamps and writes the candidate as one critical section.
        PanelPairCacheCoordinator.stageCatalogCandidate(
            context, config.panelBase, config.token, root, actualSha);
    }

    private static void notifyFinished(Listener listener, String connectionNamespace) {
        if (listener == null) return;
        try {
            listener.onFinished(connectionNamespace == null ? "" : connectionNamespace);
        } catch (Exception ignored) {
            // Listener/UI failures must not turn a completed cache write into a sync crash.
        }
    }

    private Config loadConfig() {
        Config config = new Config();
        try {
            // Catalog now follows the user-configurable panel address (AppConfig), NOT the bundled
            // asset. An unset panel base yields an empty manifestUrl, so sync() no-ops and the app
            // keeps whatever it already has (cached catalog, else the baked-in seed). Never throws.
            config.manifestUrl = AppConfig.manifestUrl(AppConfig.panelBase(context));
            config.panelBase = AppConfig.panelBase(context);
            config.token = AppConfig.catalogKey(context);
            config.enabled = PanelConnectionInputRules.allowsPanelNetwork(
                config.panelBase, config.token);
        } catch (Exception ignored) {
            config.enabled = false;
            config.manifestUrl = "";
            config.panelBase = "";
            config.token = "";
        }
        return config;
    }

    /**
     * An old App can leave an applied-version preference beside an unbound legacy cache. The first
     * connection-bound build must fetch that same catalog version again instead of becoming stuck
     * on the fictional seed. Once a usable bound cache exists, normal monotonic versioning resumes.
     */
    static boolean shouldFetchVersion(int remoteVersion, int appliedVersion,
                                      boolean hasUsableBoundCache) {
        return shouldFetchVersion(remoteVersion, appliedVersion,
            hasUsableBoundCache ? appliedVersion : 0);
    }

    static boolean shouldFetchVersion(int remoteVersion, int appliedVersion,
                                      int boundCacheVersion) {
        // The cache's own verified revision is authoritative. In particular, an old global
        // applied_version must not suppress a same-version re-fetch when the cache is absent,
        // unbound, or belongs to a different revision.
        return remoteVersion > 0 && boundCacheVersion != remoteVersion;
    }

    /**
     * A revision match alone is not publication identity. Different bytes under the same revision
     * must be downloaded so the pair coordinator can retain a visible fail-closed candidate.
     */
    static boolean shouldFetchPublishedCatalog(int remoteVersion, String remoteSourceSha,
                                               int boundCacheVersion,
                                               String boundSourceSha) {
        if (remoteVersion <= 0) return false;
        String remote = normalizeSha256(remoteSourceSha);
        String bound = normalizeSha256(boundSourceSha);
        return boundCacheVersion != remoteVersion
            || !remote.matches("[0-9a-f]{64}")
            || !remote.equals(bound);
    }

    private static String normalizeSha256(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.startsWith("sha256:") ? clean.substring(7).trim() : clean;
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

    private String getText(String url, String token, String capturedPanelBase) throws Exception {
        return new String(getBytes(url, token, capturedPanelBase), "UTF-8");
    }

    private byte[] getBytes(String url, String token, String capturedPanelBase) throws Exception {
        HttpURLConnection conn = openConnection(url, token, capturedPanelBase);
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
    private static URL panelOrigin(String capturedPanelBase) {
        try {
            String base = capturedPanelBase == null ? "" : capturedPanelBase.trim();
            return base.isEmpty() ? null : new URL(base);
        } catch (Exception exc) {
            return null;
        }
    }

    private HttpURLConnection openConnection(String url, String token,
                                             String capturedPanelBase) throws Exception {
        URL current = new URL(url);
        // Use the connection captured with this worker. Reading SharedPreferences here would let a
        // concurrent Panel switch change the credential origin while an old sync is in flight.
        URL panelOrigin = panelOrigin(capturedPanelBase);
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "AutoFormKit");
            if (token != null && !token.isEmpty()
                    && tokenAllowedForUrl(current, panelOrigin)) {
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

    static boolean tokenAllowedForUrl(String requestUrl, String capturedPanelBase) {
        try {
            return tokenAllowedForUrl(new URL(requestUrl), panelOrigin(capturedPanelBase));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean tokenAllowedForUrl(URL request, URL capturedPanelOrigin) {
        return request != null && capturedPanelOrigin != null
            && sameOrigin(request, capturedPanelOrigin);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
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

    /** Clears the catalog and its monotonic version when the configured panel identity changes. */
    static boolean clearConnectionState(Context context) {
        Context app = context.getApplicationContext();
        try {
            PanelPairCacheCoordinator.discardForConnectionChange(app);
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        } catch (Exception failure) {
            return false;
        }
    }

    private static final class Config {
        boolean enabled;
        String panelBase;
        String manifestUrl;
        String token;
    }
}
