package com.autoformkit.app.report;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.autoformkit.app.BuildConfig;
import com.autoformkit.app.NotificationClient;

import org.json.JSONObject;

import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal runtime-failure telemetry.
 *
 * <p>Reporting requires two independent Panel choices: a configured {@code runtime.failure}
 * event template and {@code diagnosticsPolicy.enabled:true}. The payload is an exact structured
 * allowlist. Caller messages, context, identifiers, backend responses, host names, logs,
 * breadcrumbs, local paths, crash text and stack traces never enter the queue or network request.
 */
public class FailureReporter {
    private static final String TAG = "FailureReporter";
    private static final long DEDUP_WINDOW_MS = 5 * 60_000L;
    private static final long PERIODIC_FLUSH_INTERVAL_MS = 10 * 60_000L;

    private static final String PREFS = "crash_reporter";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_QUEUE_SCHEMA = "queue_schema";
    private static final int QUEUE_SCHEMA = 4;
    private static final String KEY_LAST_UPLOAD_MS = "last_upload_ms";
    private static final String KEY_LAST_ATTEMPT_MS = "last_attempt_ms";
    private static final String KEY_LAST_CONFIG_ERROR_STATUS = "last_config_error_status";
    private static final String KEY_LAST_CONFIG_ERROR_MS = "last_config_error_ms";
    private static final String KEY_LAST_TRANSPORT_ERROR_MS = "last_transport_error_ms";
    private static final String KEY_PAIR_QUEUE_READY = "pair_queue_ready";
    private static final String KEY_QUEUE_PAIR_NAMESPACE = "queue_pair_namespace";
    private static final String LEGACY_KEY_CONNECTION_QUEUE_READY =
        "connection_queue_ready";
    private static final String LEGACY_KEY_QUEUE_CONNECTION_NAMESPACE =
        "queue_connection_namespace";

    private static FailureReporter instance;

    public static synchronized void init(Context context) {
        if (instance != null) return;
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FailureQueue queue = new FailureQueue(appContext);
        boolean queueSchemaReady = storedQueueSchema(prefs) == QUEUE_SCHEMA;
        String currentPair = NotificationClient.currentInstalledPairQueueNamespace(appContext);
        String queuedPair = storedPairNamespace(prefs);
        boolean pairQueueReady = queueBoundaryIsProven(
            storedPairReady(prefs), queuedPair, currentPair);
        if (queue.size() > 0 && !currentPair.equals(queuedPair)) {
            // This also closes a process-death window after same-connection pair promotion but
            // before MainActivity installed the new in-memory notification snapshot.
            pairQueueReady = false;
        }
        if (!queueSchemaReady || !pairQueueReady) {
            // Older queue rows may contain free-form context/logs. They are diagnostic-only and
            // must never be replayed through the structured transport after an upgrade or pair
            // change. A failed clear remains visibly unready and is retried before any send.
            boolean cleared = queue.clear();
            SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_QUEUE_SCHEMA, QUEUE_SCHEMA)
                .putBoolean(KEY_PAIR_QUEUE_READY,
                    cleared && !currentPair.isEmpty())
                .remove(LEGACY_KEY_CONNECTION_QUEUE_READY)
                .remove(LEGACY_KEY_QUEUE_CONNECTION_NAMESPACE);
            if (cleared && !currentPair.isEmpty()) {
                editor.putString(KEY_QUEUE_PAIR_NAMESPACE, currentPair);
            } else {
                editor.remove(KEY_QUEUE_PAIR_NAMESPACE);
            }
            boolean saved = editor.commit();
            queueSchemaReady = saved;
            pairQueueReady = saved && cleared && !currentPair.isEmpty();
        }
        instance = new FailureReporter(appContext, prefs, queue,
            queueSchemaReady, pairQueueReady, pairQueueReady ? currentPair : "");
        Log.i(TAG, "init diagnosticsConfigured="
            + NotificationClient.isDiagnosticsConfigured(appContext)
            + " queueSize=" + queue.size());
        instance.flushAsync();
        instance.installNetworkRecoveryTrigger();
        instance.schedulePeriodicFlush();
    }

    public static FailureReporter get() {
        return instance == null ? NoopHolder.NOOP : instance;
    }

    private static final class NoopHolder {
        static final FailureReporter NOOP =
            new FailureReporter(null, null, null, false, false, "");
    }

    /** Kept as a source-compatible no-op; user-visible logs are never remote breadcrumbs. */
    public static void breadcrumb(String ignored) {}

    private final Context appContext;
    private final SharedPreferences prefs;
    private final FailureQueue queue;
    private final boolean queueSchemaReady;
    private final AtomicBoolean pairQueueReady;
    private final ExecutorService uploadExecutor;
    private final Map<String, Long> lastSeenByFingerprint = new ConcurrentHashMap<>();
    private final AtomicBoolean flushInFlight = new AtomicBoolean(false);
    private final AtomicBoolean flushRequested = new AtomicBoolean(false);
    private final AtomicLong pairGeneration = new AtomicLong(0L);
    private final Object pairQueueLock = new Object();
    private volatile String queuePairNamespace;

    enum PostDisposition {
        CONTINUE,
        STOP
    }

    enum AttemptOutcome {
        EMPTY,
        MALFORMED_CLEANED,
        DEQUEUE_FAILED,
        GENERATION_CHANGED,
        POST_CONTINUE,
        POST_STOP
    }

    interface EventPoster {
        PostDisposition postEvent(FailureEvent event);
    }

    interface GenerationCheck {
        boolean isCurrent();
    }

    private static final class PairSession {
        final long generation;
        final String pairNamespace;
        final NotificationClient.Snapshot snapshot;

        PairSession(long generation, String pairNamespace,
                    NotificationClient.Snapshot snapshot) {
            this.generation = generation;
            this.pairNamespace = pairNamespace;
            this.snapshot = snapshot;
        }
    }

    private FailureReporter(Context appContext, SharedPreferences prefs, FailureQueue queue,
                            boolean queueSchemaReady, boolean pairQueueReady,
                            String queuePairNamespace) {
        this.appContext = appContext;
        this.prefs = prefs;
        this.queue = queue;
        this.queueSchemaReady = queueSchemaReady;
        this.pairQueueReady = new AtomicBoolean(pairQueueReady);
        this.queuePairNamespace = cleanPairNamespace(queuePairNamespace);
        this.uploadExecutor = prefs == null ? null : Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "failure-report-upload");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isAvailable() {
        PairSession session = currentPairSession();
        return prefs != null && uploadExecutor != null && queueSchemaReady
            && session != null
            && NotificationClient.isDiagnosticsConfigured(appContext, session.snapshot);
    }

    public boolean isEnabled() {
        return prefs != null && prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        if (prefs == null) return;
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) flushAsync();
    }

    public int queueSize() { return queue == null ? 0 : queue.size(); }
    public long lastUploadMs() {
        return prefs == null ? 0L : prefs.getLong(KEY_LAST_UPLOAD_MS, 0L);
    }
    /** Time the event was handed to NotificationClient; a response was not necessarily received. */
    public long lastAttemptMs() {
        return prefs == null ? 0L : prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L);
    }
    /** Time of the last confirmed 2xx response. Kept separate from attempt time and 4xx drops. */
    public long lastSentMs() { return lastUploadMs(); }

    public void report(String stage, String errCode, Throwable throwable) {
        report(stage, errCode, "", throwable);
    }

    public void report(String stage, String errCode, String subphase, Throwable throwable) {
        if (prefs == null || uploadExecutor == null || !queueSchemaReady || !isEnabled()) return;
        try {
            final PairSession session = currentPairSession();
            if (session == null
                    || !NotificationClient.isDiagnosticsConfigured(
                        appContext, session.snapshot)) return;
            long now = System.currentTimeMillis();
            String safeStage = normalizedStage(stage);
            String safeCode = normalizedErrorCode(errCode, throwable);
            String safeSubphase = normalizedSubphase(subphase);
            boolean dns = isDnsStage(stage, errCode, throwable);
            String fingerprint = Fingerprint.computeFailure(
                safeStage, safeCode, safeSubphase, dns);

            synchronized (pairQueueLock) {
                if (!pairSessionCurrent(session)) return;
                Long last = lastSeenByFingerprint.get(fingerprint);
                if (last != null && now - last < DEDUP_WINDOW_MS) return;
                lastSeenByFingerprint.put(fingerprint, now);
            }

            uploadExecutor.execute(() -> {
                try {
                    FailureEvent event = new FailureEvent(
                        safeStage, safeCode, safeSubphase, snapshotRuntime(), now,
                        normalizedFingerprint(fingerprint));
                    synchronized (pairQueueLock) {
                        if (!pairSessionCurrent(session)) return;
                        if (!queue.enqueue(event)) return;
                    }
                    flushBlocking(session);
                } catch (Throwable error) {
                    Log.w(TAG, "report worker failed", error);
                }
            });
        } catch (RejectedExecutionException ignored) {
        } catch (RuntimeException error) {
            Log.w(TAG, "report failed", error);
        }
    }

    public static boolean isDnsStage(String stage, String errCode, Throwable throwable) {
        if ("dns".equalsIgnoreCase(stage)) return true;
        if (errCode != null && errCode.toLowerCase(Locale.US).contains("dns")) return true;
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException) return true;
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.US);
            if (lower.contains("unable to resolve host") || lower.contains("no address associated")) {
                return true;
            }
        }
        return false;
    }

    private LinkedHashMap<String, String> snapshotRuntime() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put("android_sdk", String.valueOf(Build.VERSION.SDK_INT));
        out.put("app_version", normalizedAppVersion(BuildConfig.VERSION_NAME));
        out.put("git_head", normalizedGitHead(BuildConfig.GIT_HEAD));
        out.put("net_active", "unknown");
        out.put("net_validated", "false");
        out.put("net_captive", "false");
        out.put("net_internet", "false");
        out.put("net_not_metered", "false");
        out.put("net_vpn", "false");
        if (appContext == null) return out;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null || manager == null
                ? null : manager.getNetworkCapabilities(active);
            out.put("net_active", describeTransport(capabilities));
            if (capabilities != null) {
                out.put("net_validated", String.valueOf(capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
                out.put("net_captive", String.valueOf(capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)));
                out.put("net_internet", String.valueOf(capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET)));
                out.put("net_not_metered", String.valueOf(capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED)));
                out.put("net_vpn", String.valueOf(capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN)));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String describeTransport(NetworkCapabilities capabilities) {
        if (capabilities == null) return "none";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        return "other";
    }

    static JSONObject runtimeEventData(FailureEvent event) {
        JSONObject data = new JSONObject();
        try {
            data.put("stage", normalizedStage(event.stage));
            data.put("errorCode", normalizedErrorCode(event.errCode, null));
            data.put("subphase", normalizedSubphase(event.subphase));
            data.put("fingerprint", normalizedFingerprint(event.fingerprint));
            data.put("appVersion", normalizedAppVersion(event.ctx.get("app_version")));
            data.put("gitHead", normalizedGitHead(event.ctx.get("git_head")));
            data.put("androidSdk", nonNegativeInt(event.ctx.get("android_sdk")));
            data.put("networkTransport", normalizedNetworkTransport(event.ctx.get("net_active")));
            data.put("networkValidated", bool(event.ctx.get("net_validated")));
            data.put("networkCaptive", bool(event.ctx.get("net_captive")));
            data.put("networkInternet", bool(event.ctx.get("net_internet")));
            data.put("networkMetered", !bool(event.ctx.get("net_not_metered")));
            data.put("networkVpn", bool(event.ctx.get("net_vpn")));
        } catch (Exception impossible) {}
        return data;
    }

    /**
     * Reconciles the disposable queue with the coherent disk pair, then returns the installed
     * in-memory snapshot only if it is the same pair. Pair publication may happen before
     * MainActivity installs the snapshot; in that interval the new empty partition is retained
     * but transport remains disabled.
     */
    private PairSession currentPairSession() {
        if (prefs == null || queue == null || !queueSchemaReady) return null;
        synchronized (pairQueueLock) {
            // Resolve inside the queue lock: a delayed old caller must never wake after another
            // reporter thread has bound/enqueued the new pair and clear that new partition.
            String diskPair = cleanPairNamespace(
                NotificationClient.currentInstalledPairQueueNamespace(appContext));
            NotificationClient.Snapshot snapshot =
                NotificationClient.captureInstalledSnapshot(appContext);
            if (!bindQueueToPairLocked(diskPair)) return null;
            if (snapshot == null
                    || !diskPair.equals(NotificationClient.queuePairNamespace(snapshot))
                    || !NotificationClient.installedSnapshotStillCurrent(
                        appContext, snapshot)) {
                return null;
            }
            return new PairSession(pairGeneration.get(), diskPair, snapshot);
        }
    }

    /**
     * Makes one exact pair the sole owner of the physical queue. The pair marker is an opaque
     * digest; no endpoint, host, key, profile, production value or raw pair digest is persisted.
     */
    private boolean bindQueueToPairLocked(String targetPairNamespace) {
        String target = cleanPairNamespace(targetPairNamespace);
        String persisted = storedPairNamespace(prefs);
        boolean persistedReady = storedPairReady(prefs);
        if (!target.isEmpty() && pairQueueReady.get()
                && target.equals(queuePairNamespace)
                && queueBoundaryIsProven(persistedReady, persisted, target)) {
            return true;
        }

        pairGeneration.incrementAndGet();
        pairQueueReady.set(false);
        queuePairNamespace = "";
        lastSeenByFingerprint.clear();
        boolean cleared = queue.clear();
        SharedPreferences.Editor editor = prefs.edit()
            .putInt(KEY_QUEUE_SCHEMA, QUEUE_SCHEMA)
            .putBoolean(KEY_PAIR_QUEUE_READY, cleared && !target.isEmpty())
            .remove(LEGACY_KEY_CONNECTION_QUEUE_READY)
            .remove(LEGACY_KEY_QUEUE_CONNECTION_NAMESPACE)
            .remove(KEY_LAST_UPLOAD_MS)
            .remove(KEY_LAST_ATTEMPT_MS)
            .remove(KEY_LAST_CONFIG_ERROR_STATUS)
            .remove(KEY_LAST_CONFIG_ERROR_MS)
            .remove(KEY_LAST_TRANSPORT_ERROR_MS);
        if (cleared && !target.isEmpty()) {
            editor.putString(KEY_QUEUE_PAIR_NAMESPACE, target);
        } else {
            editor.remove(KEY_QUEUE_PAIR_NAMESPACE);
        }
        boolean saved = editor.commit();
        boolean ready = cleared && saved && !target.isEmpty();
        pairQueueReady.set(ready);
        queuePairNamespace = ready ? target : "";
        return ready;
    }

    private boolean pairSessionCurrent(PairSession session) {
        return session != null
            && pairQueueReady.get()
            && samePairGeneration(
                session.generation, pairGeneration.get())
            && session.pairNamespace.equals(queuePairNamespace)
            && session.pairNamespace.equals(
                NotificationClient.queuePairNamespace(session.snapshot))
            && NotificationClient.installedSnapshotStillCurrent(
                appContext, session.snapshot);
    }

    private void flushAsync() {
        if (uploadExecutor == null || !queueSchemaReady) return;
        final PairSession session = currentPairSession();
        if (session == null
                || !NotificationClient.isDiagnosticsConfigured(
                    appContext, session.snapshot)) return;
        if (!flushInFlight.compareAndSet(false, true)) {
            flushRequested.set(true);
            return;
        }
        uploadExecutor.execute(() -> {
            try { flushBlocking(session); }
            catch (RuntimeException error) { Log.w(TAG, "flush failed", error); }
            finally {
                flushInFlight.set(false);
                if (flushRequested.getAndSet(false)) flushAsync();
            }
        });
    }

    private void flushBlocking(PairSession session) {
        if (prefs == null || queue == null || !queueSchemaReady
                || !pairSessionCurrent(session)
                || !isEnabled()
                || !NotificationClient.isDiagnosticsConfigured(
                    appContext, session.snapshot)) return;
        while (true) {
            AttemptOutcome outcome;
            // A pair transition waits until this old-generation attempt has ended. Pair rebinding
            // increments generation under the same lock, so it cannot clear a new partition between
            // the post-dequeue check and POST.
            synchronized (pairQueueLock) {
                if (!isEnabled()
                        || !pairSessionCurrent(session)
                        || !NotificationClient.isDiagnosticsConfigured(
                            appContext, session.snapshot)) return;
                outcome = attemptNext(queue,
                    () -> pairSessionCurrent(session),
                    event -> uploadOne(event, session.snapshot));
            }
            if (outcome == AttemptOutcome.POST_CONTINUE
                    || outcome == AttemptOutcome.MALFORMED_CLEANED) continue;
            return;
        }
    }

    /**
     * Dequeues first, verifies the generation again, and only then permits one postEvent call.
     * Repeating this method cannot replay the same row because no failure path re-enqueues it.
     */
    static AttemptOutcome attemptNext(FailureQueue queue,
                                      GenerationCheck generationCurrent,
                                      EventPoster poster) {
        if (queue == null || generationCurrent == null || poster == null) {
            return AttemptOutcome.DEQUEUE_FAILED;
        }
        if (!generationCurrent.isCurrent()) return AttemptOutcome.GENERATION_CHANGED;
        FailureQueue.DequeueResult dequeued = queue.dequeueForAttempt();
        // A pair change after durable removal intentionally loses a disposable event; it must
        // never be put back into either the old or new pair partition. Check after every
        // dequeue result so malformed cleanup and storage failures cannot cross the boundary either.
        if (!generationCurrent.isCurrent()) return AttemptOutcome.GENERATION_CHANGED;
        if (dequeued.kind == FailureQueue.DequeueKind.EMPTY) return AttemptOutcome.EMPTY;
        if (dequeued.kind == FailureQueue.DequeueKind.MALFORMED_REMOVED) {
            return AttemptOutcome.MALFORMED_CLEANED;
        }
        if (dequeued.kind != FailureQueue.DequeueKind.EVENT || dequeued.event == null) {
            return AttemptOutcome.DEQUEUE_FAILED;
        }
        try {
            return poster.postEvent(dequeued.event) == PostDisposition.CONTINUE
                ? AttemptOutcome.POST_CONTINUE : AttemptOutcome.POST_STOP;
        } catch (RuntimeException failure) {
            // The event is already durably absent. Diagnostic delivery is lossy by design and an
            // ambiguous local/provider result is never replayed.
            return AttemptOutcome.POST_STOP;
        }
    }

    private PostDisposition uploadOne(FailureEvent event,
                                      NotificationClient.Snapshot snapshot) {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply();
        NotificationClient.Result result = NotificationClient.postEvent(
            appContext, snapshot, NotificationClient.EVENT_RUNTIME_FAILURE,
            runtimeEventData(event));
        if (result.success) {
            clearConfigError();
            prefs.edit().putLong(KEY_LAST_UPLOAD_MS, System.currentTimeMillis()).apply();
            return PostDisposition.CONTINUE;
        }
        if (result.statusCode >= 400 && result.statusCode < 500) {
            recordConfigError(result.statusCode);
            return PostDisposition.CONTINUE;
        }
        recordTransportError();
        return PostDisposition.STOP;
    }

    private void installNetworkRecoveryTrigger() {
        if (appContext == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            manager.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { flushAsync(); }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "network recovery trigger install failed", error);
        }
    }

    private void schedulePeriodicFlush() {
        if (appContext == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (queue != null && queue.size() > 0) flushAsync();
                handler.postDelayed(this, PERIODIC_FLUSH_INTERVAL_MS);
            }
        };
        handler.postDelayed(tick, PERIODIC_FLUSH_INTERVAL_MS);
    }

    private void recordConfigError(int status) {
        if (prefs == null) return;
        prefs.edit().putInt(KEY_LAST_CONFIG_ERROR_STATUS, status)
            .putLong(KEY_LAST_CONFIG_ERROR_MS, System.currentTimeMillis()).apply();
    }

    private void recordTransportError() {
        if (prefs != null) prefs.edit()
            .putLong(KEY_LAST_TRANSPORT_ERROR_MS, System.currentTimeMillis()).apply();
    }

    private void clearConfigError() {
        if (prefs == null) return;
        prefs.edit().remove(KEY_LAST_CONFIG_ERROR_STATUS)
            .remove(KEY_LAST_CONFIG_ERROR_MS).remove(KEY_LAST_TRANSPORT_ERROR_MS).apply();
    }

    public int lastConfigErrorStatus() {
        return prefs == null ? 0 : prefs.getInt(KEY_LAST_CONFIG_ERROR_STATUS, 0);
    }
    public long lastConfigErrorMs() {
        return prefs == null ? 0L : prefs.getLong(KEY_LAST_CONFIG_ERROR_MS, 0L);
    }
    public long lastTransportErrorMs() {
        return prefs == null ? 0L : prefs.getLong(KEY_LAST_TRANSPORT_ERROR_MS, 0L);
    }
    public void requestFlush() { flushAsync(); }

    /**
     * Diagnostics are disposable and must not cross a Panel/key security boundary. Invalidate all
     * scheduled work first, then synchronously clear persisted rows and the exact-pair marker. A
     * future complete pair is rebound by {@link #currentPairSession()}, never guessed from a new
     * connection preference alone.
     */
    public boolean clearForPanelConnectionChange() {
        synchronized (pairQueueLock) {
            pairGeneration.incrementAndGet();
            lastSeenByFingerprint.clear();
            boolean cleared = queue == null || queue.clear();
            pairQueueReady.set(false);
            queuePairNamespace = "";
            if (prefs != null) {
                SharedPreferences.Editor editor = prefs.edit()
                    .putInt(KEY_QUEUE_SCHEMA, QUEUE_SCHEMA)
                    .putBoolean(KEY_PAIR_QUEUE_READY, false)
                    .remove(KEY_QUEUE_PAIR_NAMESPACE)
                    .remove(LEGACY_KEY_CONNECTION_QUEUE_READY)
                    .remove(LEGACY_KEY_QUEUE_CONNECTION_NAMESPACE)
                    .remove(KEY_LAST_UPLOAD_MS)
                    .remove(KEY_LAST_ATTEMPT_MS)
                    .remove(KEY_LAST_CONFIG_ERROR_STATUS)
                    .remove(KEY_LAST_CONFIG_ERROR_MS)
                    .remove(KEY_LAST_TRANSPORT_ERROR_MS);
                boolean saved = editor.commit();
                cleared = cleared && saved;
            }
            return cleared;
        }
    }

    static boolean samePairGeneration(long queuedGeneration,
                                      long currentGeneration) {
        return queuedGeneration == currentGeneration;
    }

    static boolean queueBoundaryIsProven(boolean readyMarker,
                                         String queuedPairNamespace,
                                         String currentPairNamespace) {
        return readyMarker
            && cleanPairNamespace(queuedPairNamespace).equals(queuedPairNamespace)
            && !cleanPairNamespace(queuedPairNamespace).isEmpty()
            && queuedPairNamespace.equals(cleanPairNamespace(currentPairNamespace));
    }

    private static String cleanPairNamespace(String value) {
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.US);
        return clean.matches("[0-9a-f]{64}") ? clean : "";
    }

    private static int storedQueueSchema(SharedPreferences prefs) {
        try { return prefs == null ? 0 : prefs.getInt(KEY_QUEUE_SCHEMA, 0); }
        catch (RuntimeException malformed) { return 0; }
    }

    private static boolean storedPairReady(SharedPreferences prefs) {
        try { return prefs != null && prefs.getBoolean(KEY_PAIR_QUEUE_READY, false); }
        catch (RuntimeException malformed) { return false; }
    }

    private static String storedPairNamespace(SharedPreferences prefs) {
        try {
            return prefs == null ? "" : cleanPairNamespace(
                prefs.getString(KEY_QUEUE_PAIR_NAMESPACE, ""));
        } catch (RuntimeException malformed) {
            return "";
        }
    }

    private static String normalizedStage(String value) {
        if ("uncaught".equals(value) || "print".equals(value) || "dns".equals(value)
                || "network".equals(value) || "submit".equals(value)) return value;
        return "runtime";
    }

    private static String normalizedSubphase(String value) {
        if (value == null || value.isEmpty()) return "";
        if ("process_default".equals(value) || "pre_submit".equals(value)
                || "print_adapter".equals(value) || "submit_unit".equals(value)) return value;
        return "other";
    }

    private static String normalizedErrorCode(String value, Throwable throwable) {
        if ("printer_not_ready".equals(value) || "reprint_api_failed".equals(value)
                || "reprint_api_error".equals(value)
                || "label_failed_after_retry".equals(value)
                || "null_throwable".equals(value) || "unknown_host".equals(value)
                || "io_exception".equals(value) || "security_exception".equals(value)
                || "state_exception".equals(value) || "argument_exception".equals(value)
                || "runtime_exception".equals(value) || "exception".equals(value)
                || "unknown_failure".equals(value)) return value;
        if ("UnknownHostException".equals(value)) return "unknown_host";
        if ("IOException".equals(value) || "SocketTimeoutException".equals(value)
                || "ConnectException".equals(value)) return "io_exception";
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException) return "unknown_host";
            if (current instanceof java.io.IOException) return "io_exception";
            if (current instanceof SecurityException) return "security_exception";
            if (current instanceof IllegalStateException) return "state_exception";
            if (current instanceof IllegalArgumentException) return "argument_exception";
            if (current instanceof RuntimeException) return "runtime_exception";
            if (current instanceof Exception) return "exception";
        }
        return "unknown_failure";
    }

    private static String normalizedAppVersion(String value) {
        if (value != null && value.matches(
                "[0-9]{1,5}(?:\\.[0-9]{1,5}){1,3}(?:[-+][A-Za-z0-9.-]{1,32})?")) {
            return value;
        }
        return "unknown";
    }

    private static String normalizedGitHead(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{7,40}") ? value : "unknown";
    }

    private static String normalizedFingerprint(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{8}") ? value : "00000000";
    }

    private static String normalizedNetworkTransport(String value) {
        if ("none".equals(value) || "wifi".equals(value) || "cellular".equals(value)
                || "ethernet".equals(value) || "vpn".equals(value)
                || "bluetooth".equals(value) || "other".equals(value)) return value;
        return "unknown";
    }

    private static int nonNegativeInt(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static boolean bool(String value) {
        return "true".equalsIgnoreCase(value);
    }
}
