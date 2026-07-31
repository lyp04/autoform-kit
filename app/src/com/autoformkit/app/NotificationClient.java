package com.autoformkit.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Sends versioned, provider-neutral notifications through the configured panel endpoint.
 *
 * <p>The app never receives or contacts a downstream provider directly. The endpoint must be a
 * same-origin absolute path from {@code /api/config.notification}; the panel owns all downstream
 * provider credentials and payload conversion.
 */
public final class NotificationClient {
    private static final int TIMEOUT_MS = 10000;
    public static final String EVENT_SUBMISSION_SUMMARY = "submission.summary";
    public static final String EVENT_SUBMISSION_ROUND = "submission.round";
    public static final String EVENT_RUNTIME_FAILURE = "runtime.failure";

    /**
     * The notification portion of one already-verified in-memory config/catalog pair.
     *
     * <p>It deliberately contains no file paths and never reloads either cache. A publish may
     * temporarily leave config v8 and catalog v7 on disk; callers holding a v7 snapshot continue
     * to use only v7, while attempting to capture the mixed pair fails closed.
     */
    public static final class Snapshot {
        private final int catalogVersion;
        private final String connectionNamespace;
        private final String panelPairSha256;
        private final long generation;
        private final URL panelBase;
        private final String accessKey;
        private final JSONObject notification;

        private Snapshot(int catalogVersion, String connectionNamespace,
                         String panelPairSha256, long generation, URL panelBase,
                         String accessKey, JSONObject notification) {
            this.catalogVersion = catalogVersion;
            this.connectionNamespace = connectionNamespace;
            this.panelPairSha256 = panelPairSha256;
            this.generation = generation;
            this.panelBase = panelBase;
            this.accessKey = accessKey == null ? "" : accessKey;
            this.notification = notification;
        }

        private boolean acceptsConnection(String currentConnectionNamespace) {
            return connectionNamespace.equals(clean(currentConnectionNamespace));
        }

        private boolean samePair(Snapshot other) {
            return other != null
                && catalogVersion == other.catalogVersion
                && connectionNamespace.equals(other.connectionNamespace)
                && panelPairSha256.equals(other.panelPairSha256);
        }

        private Snapshot installed(long installedGeneration) {
            return new Snapshot(catalogVersion, connectionNamespace, panelPairSha256,
                installedGeneration, panelBase, accessKey, notification);
        }
    }

    // Runtime diagnostics do not have a MainActivity instance from which to receive a snapshot.
    // MainActivity updates this reference only when it atomically installs a complete active pair.
    // The object itself is immutable and every use also rechecks the current disk pair plus this
    // process-local installation generation. The handoff lock serializes generation replacement
    // with remote-worker lease acquisition.
    private static volatile Snapshot activeSnapshot;
    private static long activeGeneration;

    private NotificationClient() {}

    public static final class Result {
        public final boolean success;
        public final int statusCode;
        public final String error;

        private Result(boolean success, int statusCode, String error) {
            this.success = success;
            this.statusCode = statusCode;
            this.error = error == null ? "" : error;
        }
    }

    public static boolean isConfigured(Context context) {
        try {
            return resolve(context, activeSnapshot, "") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isConfigured(Context context, String eventType) {
        try {
            return resolve(context, activeSnapshot, eventType) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isConfigured(Context context, Snapshot snapshot, String eventType) {
        try {
            return resolve(context, snapshot, eventType) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isDiagnosticsConfigured(Context context) {
        return isDiagnosticsConfigured(context, activeSnapshot);
    }

    public static boolean isDiagnosticsConfigured(Context context, Snapshot snapshot) {
        try {
            Resolved resolved = resolve(context, snapshot, EVENT_RUNTIME_FAILURE);
            return resolved != null && resolved.diagnosticsEnabled;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Returns the immutable snapshot only for the exact installed connection + complete pair. */
    public static Snapshot captureInstalledSnapshot(Context context) {
        if (context == null) return null;
        Snapshot snapshot = activeSnapshot;
        return installedSnapshotStillCurrent(context, snapshot) ? snapshot : null;
    }

    /** Opaque Panel/key identity; the exact queue partition additionally binds version + pair. */
    public static String currentConnectionNamespace(Context context) {
        if (context == null) return "";
        return AppConfig.connectionNamespaceId(
            AppConfig.panelBase(context), AppConfig.catalogKey(context));
    }

    public static Result postEvent(Context context, String type, JSONObject data) {
        return postEvent(context, activeSnapshot, type, data);
    }

    public static Result postEvent(Context context, Snapshot snapshot,
                                   String type, JSONObject data) {
        RemoteSideEffectGate.WorkerLease workerLease = null;
        try {
            if (context == null || !installedSnapshotStillCurrent(context, snapshot)) {
                return unavailable("notification snapshot changed");
            }
            // Pair promotion and APK handoff both recheck this process-wide lease count under
            // UpdateInstallRules.HANDOFF_LOCK. Acquiring before the final pair check therefore
            // makes the check + socket attempt one indivisible remote-side-effect interval.
            workerLease = RemoteSideEffectGate.tryAcquireWorker(context);
            if (workerLease == null) return unavailable("notification handoff busy");
            Resolved resolved = resolveCurrent(context, snapshot, type);
            if (resolved == null) return new Result(false, 0, "notification disabled");
            if (EVENT_RUNTIME_FAILURE.equals(type) && !resolved.diagnosticsEnabled) {
                return new Result(false, 0, "diagnostics disabled");
            }

            JSONObject body = payload(resolved.version, type, data);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) resolved.url.openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("User-Agent", "AutoFormKit");
                if (!resolved.accessKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + resolved.accessKey);
                }
                try (OutputStream output = conn.getOutputStream()) {
                    output.write(payload);
                }
                int status = conn.getResponseCode();
                return new Result(status >= 200 && status < 300, status,
                    status >= 200 && status < 300 ? "" : "HTTP " + status);
            } finally {
                conn.disconnect();
            }
        } catch (Exception error) {
            // Endpoint, host, key and provider errors are intentionally not reflected into local
            // diagnostics or a later remote diagnostic event.
            return unavailable("notification transport failed");
        } finally {
            if (workerLease != null) workerLease.close();
        }
    }

    /**
     * Captures and publishes the exact active pair for diagnostics and returns it for explicit
     * submission calls. Invalid, sample, unconfigured, or mixed-revision input clears the runtime
     * snapshot instead of falling back to either disk cache.
     */
    static Snapshot installActiveSnapshot(Context context, JSONObject config,
                                          JSONObject settings, int catalogVersion,
                                          String panelPairSha256) {
        Snapshot captured = captureActiveSnapshot(
            context, config, settings, catalogVersion, panelPairSha256);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            Snapshot current = activeSnapshot;
            if (captured == null || !snapshotMatchesDiskPairLocked(context, captured)) {
                advanceGenerationLocked();
                activeSnapshot = null;
                return null;
            }
            if (current != null && current.samePair(captured)
                    && installedSnapshotStillCurrentLocked(context, current)) {
                return current;
            }
            long generation = advanceGenerationLocked();
            Snapshot installed = captured.installed(generation);
            activeSnapshot = installed;
            return installed;
        }
    }

    static void clearActiveSnapshot() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            advanceGenerationLocked();
            activeSnapshot = null;
        }
    }

    static Snapshot captureActiveSnapshot(Context context, JSONObject config,
                                          JSONObject settings, int catalogVersion,
                                          String panelPairSha256) {
        if (context == null) return null;
        String panelBase = AppConfig.panelBase(context);
        String accessKey = AppConfig.catalogKey(context);
        return captureSnapshot(panelBase, accessKey,
            AppConfig.connectionNamespaceId(panelBase, accessKey),
            config, settings, catalogVersion, panelPairSha256);
    }

    /** Pure factory kept package-visible so revision-race behavior can be unit tested. */
    static Snapshot captureSnapshot(String panelBase, String accessKey,
                                    String connectionNamespace, JSONObject config,
                                    JSONObject settings, int catalogVersion,
                                    String panelPairSha256) {
        try {
            String baseValue = stripTrailingSlash(panelBase);
            String keyValue = clean(accessKey);
            String expectedConnection = AppConfig.connectionNamespaceId(baseValue, keyValue);
            String pairSha = clean(panelPairSha256).toLowerCase(java.util.Locale.US);
            if (baseValue.isEmpty()
                    || !expectedConnection.equals(clean(connectionNamespace))
                    || !pairSha.matches("[0-9a-f]{64}")
                    || !PanelBootstrapRules.pairCompatible(config != null,
                        AppConfig.catalogVersion(config), settings != null, catalogVersion)
                    || !CatalogSafetyRules.allowsRemoteOperations(settings)) {
                return null;
            }
            URL base = new URL(baseValue);
            String protocol = base.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return null;
            }
            JSONObject source = config.optJSONObject("notification");
            JSONObject notification = source == null
                ? null : new JSONObject(source.toString());
            return new Snapshot(catalogVersion, expectedConnection, pairSha, 0L,
                base, keyValue, notification);
        } catch (Exception invalid) {
            return null;
        }
    }

    static JSONObject payload(String type, JSONObject data) throws Exception {
        return payload(2, type, data);
    }

    static JSONObject payload(int version, String type, JSONObject data) throws Exception {
        if (version == 3) {
            if (!EVENT_SUBMISSION_ROUND.equals(type)
                    || !NotificationEventData.isValidSubmissionRound(data)) {
                throw new IllegalArgumentException("invalid version 3 notification event");
            }
        } else if (version != 2) {
            throw new IllegalArgumentException("unsupported notification version");
        }
        JSONObject body = new JSONObject();
        body.put("version", version);
        body.put("type", type == null ? "" : type);
        body.put("data", data == null ? new JSONObject() : new JSONObject(data.toString()));
        return body;
    }

    private static Resolved resolve(Context context, Snapshot snapshot,
                                    String eventType) throws Exception {
        return resolveCurrent(context, snapshot, eventType);
    }

    private static Resolved resolveCurrent(Context context, Snapshot snapshot,
                                           String eventType) throws Exception {
        if (!installedSnapshotStillCurrent(context, snapshot)) return null;
        return resolve(snapshot, eventType, currentConnectionNamespace(context));
    }

    private static Resolved resolve(Snapshot snapshot, String eventType,
                                    String currentConnectionNamespace) throws Exception {
        if (snapshot == null || !snapshot.acceptsConnection(currentConnectionNamespace)) {
            return null;
        }
        JSONObject notification = snapshot.notification;
        int version = notification == null ? 0 : notification.optInt("version", 0);
        if (notification == null
            || (version != 2 && version != 3)
            || !notification.optBoolean("enabled", false)) {
            return null;
        }
        if (eventType != null && !eventType.isEmpty()) {
            JSONArray types = notification.optJSONArray("eventTypes");
            boolean found = false;
            for (int i = 0; types != null && i < types.length(); i++) {
                if (eventType.equals(types.optString(i, ""))) { found = true; break; }
            }
            if (!found) return null;
        }
        if (version == 3 && !EVENT_SUBMISSION_ROUND.equals(eventType)) return null;
        String endpoint = notification.optString("endpoint", "").trim();
        if (!endpoint.startsWith("/") || endpoint.startsWith("//")) return null;
        URL base = snapshot.panelBase;
        URL target = new URL(base, endpoint);
        if (!sameOrigin(base, target)) throw new IOException("Notification endpoint changed origin");
        return new Resolved(version, target, snapshot.accessKey,
            notification.optBoolean("diagnosticsEnabled", false));
    }

    static String resolvedEndpoint(Snapshot snapshot, String currentConnectionNamespace,
                                   String eventType) {
        try {
            Resolved resolved = resolve(snapshot, eventType, currentConnectionNamespace);
            return resolved == null ? "" : resolved.url.toString();
        } catch (Exception invalid) {
            return "";
        }
    }

    /**
     * Opaque, non-secret partition for diagnostic queue ownership. It contains no Panel URL,
     * access key, endpoint, profile value or raw pair digest.
     */
    public static String queuePairNamespace(Snapshot snapshot) {
        return snapshot == null ? "" : queuePairNamespace(snapshot.connectionNamespace,
            snapshot.catalogVersion, snapshot.panelPairSha256);
    }

    /**
     * Returns the exact coherent disk-pair partition even before MainActivity publishes its
     * in-memory notification snapshot. This lets process-start queue recovery preserve only rows
     * belonging to that pair while keeping transport disabled until installation completes.
     */
    public static String currentInstalledPairQueueNamespace(Context context) {
        if (context == null) return "";
        try {
            String connection = currentConnectionNamespace(context);
            // Unsafe/same-revision/malformed/cross-connection candidates are a hard barrier.
            // Strictly newer valid candidate halves deliberately retain legacy old-pair
            // availability until their safe-boundary promotion.
            PanelPairCacheCoordinator.ActivePair pair =
                PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(
                    context, connection);
            if (pair == null) return "";
            return queuePairNamespace(connection, pair.version, pair.pairSha256);
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    /** True only for the one current process generation and the exact coherent disk pair. */
    public static boolean installedSnapshotStillCurrent(Context context, Snapshot snapshot) {
        if (context == null || snapshot == null) return false;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            return installedSnapshotStillCurrentLocked(context, snapshot);
        }
    }

    /** Pure identity predicate for JVM race tests; no deployment values are exposed. */
    static boolean samePairIdentity(Snapshot left, Snapshot right) {
        return left != null && left.samePair(right);
    }

    /** Pure generation predicate for JVM race tests. Detached factory snapshots never pass it. */
    static boolean sameInstalledGeneration(Snapshot left, Snapshot right) {
        return left != null && right != null
            && sameInstalledGeneration(left.generation, right.generation)
            && left.samePair(right);
    }

    static boolean sameInstalledGeneration(long left, long right) {
        return left > 0L && left == right;
    }

    private static boolean installedSnapshotStillCurrentLocked(
            Context context, Snapshot snapshot) {
        Snapshot installed = activeSnapshot;
        if (snapshot == null || installed == null
                || !sameInstalledGeneration(snapshot, installed)
                || !snapshot.acceptsConnection(currentConnectionNamespace(context))) {
            return false;
        }
        return snapshotMatchesDiskPairLocked(context, snapshot);
    }

    private static boolean snapshotMatchesDiskPairLocked(
            Context context, Snapshot snapshot) {
        if (context == null || snapshot == null) return false;
        String connection = currentConnectionNamespace(context);
        if (!snapshot.acceptsConnection(connection)) return false;
        // The coordinator classifies both candidate slots and reads the active pair in one lock,
        // giving postEvent one linearization point: an unsafe barrier either predates worker
        // authorization (reject) or follows its lease.
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(
                context, connection);
        return pair != null
            && pair.version == snapshot.catalogVersion
            && snapshot.panelPairSha256.equals(pair.pairSha256);
    }

    private static String queuePairNamespace(String connectionNamespace,
                                             int catalogVersion,
                                             String panelPairSha256) {
        String connection = clean(connectionNamespace);
        String pairSha = clean(panelPairSha256).toLowerCase(java.util.Locale.US);
        if (connection.isEmpty() || catalogVersion <= 0
                || !pairSha.matches("[0-9a-f]{64}")) return "";
        return UpdateInstallRules.sha256("autoform-kit/diagnostic-pair/v1\n"
            + connection + "\n" + catalogVersion + "\n" + pairSha);
    }

    private static long advanceGenerationLocked() {
        activeGeneration = activeGeneration == Long.MAX_VALUE ? 1L : activeGeneration + 1L;
        return activeGeneration;
    }

    private static Result unavailable(String reason) {
        return new Result(false, 0, reason);
    }

    private static boolean sameOrigin(URL left, URL right) {
        return left.getProtocol().equalsIgnoreCase(right.getProtocol())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    }

    private static String stripTrailingSlash(String value) {
        String out = clean(value);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Resolved {
        final int version;
        final URL url;
        final String accessKey;
        final boolean diagnosticsEnabled;

        Resolved(int version, URL url, String accessKey, boolean diagnosticsEnabled) {
            this.version = version;
            this.url = url;
            this.accessKey = accessKey == null ? "" : accessKey;
            this.diagnosticsEnabled = diagnosticsEnabled;
        }
    }
}
