package com.autoformkit.app.report;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.autoformkit.app.AppConfig;
import com.autoformkit.app.BuildConfig;
import com.autoformkit.app.Diagnostics;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-scoped sink for runtime failures. Call sites do
 * {@code FailureReporter.get().report("dns", "unknown_host", ctx, throwable)} — the
 * reporter sanitizes, fingerprints, queues, and asynchronously posts a fire-and-forget
 * text summary to the configured notify webhook (one POST per event).
 *
 * <p>Init from {@code App.onCreate()} via {@link #init(Context)}. Reporting is active
 * only while a notify webhook is configured (panel {@code notifyWebhook}); otherwise
 * events are dropped rather than queued.
 *
 * <p>Static {@link #breadcrumb(String)} captures the recent stream of user-visible
 * log lines so each failure carries the last 30 events leading up to it.
 *
 * <p>When {@code stage == "dns"} or the ctx carries a {@code dns_target} entry,
 * {@link #runDnsDiagnostics(String)} probes the target host plus a control host and
 * snapshots {@code LinkProperties} DNS servers / Private DNS state — that data is
 * appended to the issue body so we can tell whether resolution is broken globally,
 * for one host, or only via the network's configured resolver.
 */
public class FailureReporter {

    private static final String TAG = "FailureReporter";

    private static final long DEDUP_WINDOW_MS = 5 * 60_000L;
    private static final int POST_TIMEOUT_MS = 10000;
    private static final int MAX_SUMMARY_CHARS = 8000;

    private static final String PREFS = "crash_reporter";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_UPLOAD_MS = "last_upload_ms";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_LAST_CONFIG_ERROR_STATUS = "last_config_error_status";
    private static final String KEY_LAST_CONFIG_ERROR_MS = "last_config_error_ms";
    private static final String KEY_LAST_TRANSPORT_ERROR_MS = "last_transport_error_ms";
    private static final long PERIODIC_FLUSH_INTERVAL_MS = 10 * 60_000L;

    private static final int BREADCRUMB_SIZE = 30;
    private static final Deque<BreadcrumbEntry> BREADCRUMBS = new ArrayDeque<>(BREADCRUMB_SIZE);

    private static final String DNS_CONTROL_HOST = "dns.google";
    // No built-in probe targets; the failing host is derived from the throwable/ctx at report time.
    private static final String[] DNS_DEFAULT_TARGETS = {};

    private static final class BreadcrumbEntry {
        final long ts;
        final String line;
        BreadcrumbEntry(long ts, String line) { this.ts = ts; this.line = line; }
    }

    public static void breadcrumb(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (BREADCRUMBS) {
            while (BREADCRUMBS.size() >= BREADCRUMB_SIZE) BREADCRUMBS.pollFirst();
            BREADCRUMBS.addLast(new BreadcrumbEntry(System.currentTimeMillis(), line));
        }
    }

    private static String snapshotBreadcrumbs(long nowMs) {
        synchronized (BREADCRUMBS) {
            if (BREADCRUMBS.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (BreadcrumbEntry e : BREADCRUMBS) {
                long ageMs = nowMs - e.ts;
                sb.append(String.format(Locale.US, "T-%5ds | %s%n",
                        ageMs / 1000, Redactor.scrubMessage(e.line)));
            }
            return sb.toString();
        }
    }

    private static volatile String currentSessionId = "boot-" + UUID.randomUUID().toString().substring(0, 8);

    public static void newSession(String label) {
        String safe = (label == null ? "op" : label.replaceAll("[^A-Za-z0-9_-]", "_"));
        currentSessionId = safe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String currentSessionId() {
        return currentSessionId;
    }

    private static FailureReporter instance;

    public static synchronized void init(Context context) {
        if (instance != null) return;
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FailureQueue queue = new FailureQueue(appContext);
        instance = new FailureReporter(appContext, prefs, queue);
        Log.i(TAG, "init webhookConfigured=" + !instance.resolveWebhook().isEmpty()
                + " queueSize=" + queue.size());
        instance.flushAsync();
        instance.installNetworkRecoveryTrigger();
        instance.schedulePeriodicFlush();
    }

    public static FailureReporter get() {
        return instance == null ? NoopHolder.NOOP : instance;
    }

    private static final class NoopHolder {
        static final FailureReporter NOOP = new FailureReporter(null, null, null);
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final FailureQueue queue;
    private final ExecutorService uploadExecutor;
    private final Map<String, Long> lastSeenByFp = new ConcurrentHashMap<>();
    private final AtomicBoolean flushInFlight = new AtomicBoolean(false);

    private FailureReporter(Context appContext, SharedPreferences prefs, FailureQueue queue) {
        this.appContext = appContext;
        this.prefs = prefs;
        this.queue = queue;
        this.uploadExecutor = (prefs == null)
                ? null
                : Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "failure-report-upload");
                    t.setDaemon(true);
                    return t;
                });
    }

    /** The configured notify webhook (panel {@code notifyWebhook}), or "" when unset. Never throws. */
    private String resolveWebhook() {
        if (appContext == null) return "";
        try {
            return AppConfig.notifyWebhook(appContext);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Reporting is active only once the subsystem is initialized AND a notify webhook is configured. */
    public boolean isAvailable() {
        return prefs != null && uploadExecutor != null && !resolveWebhook().isEmpty();
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

    public void report(String stage, String errCode, Map<String, String> ctx, Throwable throwable) {
        report(stage, errCode, "", null, ctx, throwable);
    }

    /**
     * Caller-thread-safe: only cheap, allocation-only work happens here (fingerprint
     * + dedup check + ctx copy). Everything that can touch the network or block —
     * DNS probes, file IO, and webhook delivery — is handed off to {@code uploadExecutor}.
     * Safe to call from the main thread, including the uncaught exception handler.
     */
    public void report(String stage, String errCode, String subphase, String message,
                       Map<String, String> ctx, Throwable throwable) {
        if (!isAvailable() || !isEnabled() || uploadExecutor == null) return;
        try {
            long now = System.currentTimeMillis();
            String dnsTarget = dnsTargetFrom(ctx, throwable);
            boolean isDns = isDnsStage(stage, errCode, ctx, throwable);

            // Device/build metadata belongs in ctx (added below), not in identity. Otherwise every
            // app upgrade or Android/device change splits the same root failure into a new family.
            String fp = Fingerprint.computeFailure(
                    stage, errCode, subphase, isDns ? dnsTarget : "");

            Long lastSeen = lastSeenByFp.get(fp);
            if (lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS) {
                Log.i(TAG, "report dedup stage=" + stage + " err=" + errCode + " fp=" + fp);
                return;
            }
            lastSeenByFp.put(fp, now);
            Log.i(TAG, "report enqueue stage=" + stage + " err=" + errCode + " fp=" + fp
                    + " dnsTarget=" + dnsTarget + " isDns=" + isDns);

            Map<String, String> ctxCopy = ctx == null ? null : new LinkedHashMap<>(ctx);
            String stageF = stage;
            String errCodeF = errCode;
            String subphaseF = subphase == null ? "" : subphase;
            String messageF = message;
            Throwable throwableF = throwable;

            uploadExecutor.execute(() -> {
                try {
                    LinkedHashMap<String, String> safeCtx = sanitizeCtx(ctxCopy);
                    if (isDns) {
                        safeCtx.putAll(runDnsDiagnostics(dnsTarget));
                    }
                    appendDiagnosticsToCtx(safeCtx);
                    FailureEvent event = new FailureEvent(stageF, errCodeF, subphaseF,
                            Redactor.scrubMessage(messageF),
                            Redactor.scrubThrowable(throwableF),
                            safeCtx, now, fp);
                    queue.enqueue(event);
                    flushBlocking();
                } catch (Throwable error) {
                    Log.w(TAG, "report worker failed", error);
                }
            });
        } catch (RejectedExecutionException ignored) {
        } catch (RuntimeException error) {
            Log.w(TAG, "report() failed", error);
        }
    }

    private void appendDiagnosticsToCtx(LinkedHashMap<String, String> ctx) {
        if (appContext == null) return;
        try {
            String log = Diagnostics.readLog(appContext);
            if (log != null && !log.isEmpty()) {
                ctx.put("diagnostic_log_tail", Redactor.scrubMessage(log));
            }
        } catch (Throwable ignored) {}
        try {
            String crash = Diagnostics.readCrash(appContext);
            if (crash != null && !crash.isEmpty()) {
                ctx.put("last_crash", Redactor.scrubMessage(crash));
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isDnsStage(String stage, String errCode,
                                     Map<String, String> ctx, Throwable throwable) {
        if ("dns".equalsIgnoreCase(stage)) return true;
        if (errCode != null) {
            String e = errCode.toLowerCase(Locale.US);
            if (e.contains("unknown_host") || e.contains("dns")) return true;
        }
        if (ctx != null) {
            String target = ctx.get("dns_target");
            if (target != null && !target.trim().isEmpty()) return true;
        }
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return true;
            String msg = t.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase(Locale.US);
            if (lower.contains("unable to resolve host") || lower.contains("no address associated")) {
                return true;
            }
        }
        return false;
    }

    private static String dnsTargetFrom(Map<String, String> ctx, Throwable throwable) {
        if (ctx != null) {
            String v = ctx.get("dns_target");
            if (v != null && !v.isEmpty()) return v;
        }
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) continue;
            int s = msg.indexOf('"');
            int e = s >= 0 ? msg.indexOf('"', s + 1) : -1;
            if (s >= 0 && e > s) return msg.substring(s + 1, e);
        }
        return "";
    }

    private LinkedHashMap<String, String> sanitizeCtx(Map<String, String> src) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (src != null) {
            for (Map.Entry<String, String> e : src.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) continue;
                String kl = k.toLowerCase(Locale.US);
                if ("password".equals(kl) || kl.contains("token") || kl.contains("secret")) continue;
                if ("mac".equals(kl) || "bssid".equals(kl)) {
                    out.put(k, Redactor.maskMac(v));
                } else if ("ssid".equals(kl)) {
                    out.put(k + "_hash", Redactor.hashShort(v));
                } else {
                    out.put(k, Redactor.scrubMessage(v));
                }
            }
        }
        out.put("device_model", Build.MODEL);
        out.put("device_brand", Build.BRAND);
        out.put("android_sdk", String.valueOf(Build.VERSION.SDK_INT));
        out.put("app_version", BuildConfig.VERSION_NAME);
        out.put("git_head", BuildConfig.GIT_HEAD);
        out.put("install_id", installId());
        out.put("session_id", currentSessionId);
        out.put("locale", Locale.getDefault().toString());
        out.put("timezone", TimeZone.getDefault().getID());
        out.putAll(snapshotRuntime());
        return out;
    }

    private LinkedHashMap<String, String> snapshotRuntime() {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (appContext == null) return out;
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            out.put("wifi_enabled", wm == null ? "no_manager" : String.valueOf(wm.isWifiEnabled()));
        } catch (Throwable ignored) {}
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    out.put("net_active", "none");
                } else {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    out.put("net_active", describeTransport(caps));
                    if (caps != null) {
                        out.put("net_validated", String.valueOf(
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
                        out.put("net_captive", String.valueOf(
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)));
                        out.put("net_internet", String.valueOf(
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)));
                        out.put("net_not_metered", String.valueOf(
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)));
                        out.put("net_vpn", String.valueOf(
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)));
                    }
                }
            }
        } catch (Throwable error) {
            out.put("net_active", "error:" + error.getClass().getSimpleName());
        }
        StringBuilder perms = new StringBuilder();
        for (String p : new String[]{
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.CAMERA}) {
            try {
                int r = appContext.checkSelfPermission(p);
                String shortName = p.substring(p.lastIndexOf('.') + 1).toLowerCase(Locale.US);
                if (perms.length() > 0) perms.append(',');
                perms.append(shortName).append('=').append(r == PackageManager.PERMISSION_GRANTED ? "yes" : "no");
            } catch (Throwable ignored) {}
        }
        out.put("perms", perms.toString());
        return out;
    }

    private static String describeTransport(NetworkCapabilities caps) {
        if (caps == null) return "unknown";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        return "other";
    }

    /**
     * Probe the failing host + a control host + capture configured DNS resolvers,
     * Private DNS mode, and link properties. Runs synchronously (caller is already
     * off the main thread inside report()). Each individual probe has a hard
     * timeout via a worker pool so a totally dead DNS doesn't stall reporting.
     */
    private LinkedHashMap<String, String> runDnsDiagnostics(String target) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (appContext == null) return out;

        List<String> targets = new ArrayList<>();
        if (target != null && !target.isEmpty()) targets.add(target);
        for (String t : DNS_DEFAULT_TARGETS) {
            if (!targets.contains(t)) targets.add(t);
        }
        if (!targets.contains(DNS_CONTROL_HOST)) targets.add(DNS_CONTROL_HOST);

        if (!target.isEmpty()) out.put("dns_target", target);

        for (String host : targets) {
            out.put("dns_probe_" + safeKey(host), probeHost(host));
        }

        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = (cm == null || active == null) ? null : cm.getLinkProperties(active);
            if (lp != null) {
                StringBuilder dnsServers = new StringBuilder();
                for (InetAddress addr : lp.getDnsServers()) {
                    if (dnsServers.length() > 0) dnsServers.append(',');
                    dnsServers.append(addr.getHostAddress());
                }
                out.put("dns_servers", dnsServers.length() == 0 ? "<none>" : dnsServers.toString());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        String privateDnsName = lp.getPrivateDnsServerName();
                        out.put("dns_private_server", privateDnsName == null ? "" : privateDnsName);
                    } catch (Throwable ignored) {}
                }
                out.put("dns_link_iface", String.valueOf(lp.getInterfaceName()));
                out.put("dns_link_domains", String.valueOf(lp.getDomains()));
            } else {
                out.put("dns_servers", "<no_link_properties>");
            }
        } catch (Throwable error) {
            out.put("dns_servers", "error:" + error.getClass().getSimpleName());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String mode = Settings.Global.getString(appContext.getContentResolver(), "private_dns_mode");
                String spec = Settings.Global.getString(appContext.getContentResolver(), "private_dns_specifier");
                out.put("private_dns_mode", mode == null ? "" : mode);
                out.put("private_dns_specifier", spec == null ? "" : spec);
            } catch (Throwable ignored) {}
        }

        return out;
    }

    private static String probeHost(String host) {
        long start = System.currentTimeMillis();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            long ms = System.currentTimeMillis() - start;
            StringBuilder sb = new StringBuilder();
            sb.append("ok in ").append(ms).append("ms: ");
            for (int i = 0; i < addrs.length && i < 4; i++) {
                if (i > 0) sb.append(',');
                sb.append(addrs[i].getHostAddress());
            }
            if (addrs.length > 4) sb.append(",+").append(addrs.length - 4);
            return sb.toString();
        } catch (UnknownHostException error) {
            long ms = System.currentTimeMillis() - start;
            return "FAIL in " + ms + "ms: " + Redactor.scrubMessage(error.getMessage());
        } catch (Throwable error) {
            long ms = System.currentTimeMillis() - start;
            return "ERR in " + ms + "ms: " + error.getClass().getSimpleName() + ": "
                    + Redactor.scrubMessage(error.getMessage());
        }
    }

    private static String safeKey(String host) {
        return host == null ? "null" : host.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String installId() {
        String id = prefs.getString(KEY_INSTALL_ID, "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString().substring(0, 8);
        prefs.edit().putString(KEY_INSTALL_ID, id).apply();
        return id;
    }

    private void flushAsync() {
        if (uploadExecutor == null) return;
        if (!flushInFlight.compareAndSet(false, true)) return;
        uploadExecutor.execute(() -> {
            try { flushBlocking(); }
            catch (RuntimeException error) { Log.w(TAG, "flush failed", error); }
            finally { flushInFlight.set(false); }
        });
    }

    private void flushBlocking() {
        if (prefs == null || !isEnabled()) return;
        String webhook = resolveWebhook();
        if (webhook.isEmpty()) return; // not configured yet — leave events queued.
        List<FailureEvent> events = queue.snapshot();
        if (events.isEmpty()) return;
        Log.i(TAG, "flush start pending=" + events.size());
        int uploaded = 0;
        for (FailureEvent event : events) {
            if (!uploadOne(event, webhook)) break;
            uploaded++;
        }
        Log.i(TAG, "flush done uploaded=" + uploaded + "/" + events.size()
                + " remaining=" + (events.size() - uploaded));
        if (uploaded > 0) {
            queue.dropFirst(uploaded);
            prefs.edit().putLong(KEY_LAST_UPLOAD_MS, System.currentTimeMillis()).apply();
        }
    }

    /**
     * Fire-and-forget POST of one event's text summary to the notify webhook, in the same body
     * format the in-app notifier uses ({@code {"msg_type":"text","content":{"text":…}}}).
     * Returns true when the event is done with (delivered on 2xx, or a permanent 4xx we won't retry);
     * false on a transient failure (5xx / network) so it stays queued for the next flush.
     */
    private boolean uploadOne(FailureEvent event, String webhook) {
        try {
            int status = postText(webhook, buildSummary(event));
            if (status >= 200 && status < 300) {
                clearConfigError();
                return true;
            }
            if (status >= 400 && status < 500) {
                recordConfigError(status);
                Log.w(TAG, "notify rejected " + status + "; dropping " + event.fingerprint);
                return true; // client error won't be fixed by retrying
            }
            recordTransportError();
            Log.w(TAG, "notify post failed " + status + " for " + event.fingerprint);
            return false;
        } catch (Exception error) {
            recordTransportError();
            Log.w(TAG, "notify post failed for " + event.fingerprint, error);
            return false;
        }
    }

    private int postText(String webhookUrl, String text) throws Exception {
        JSONObject content = new JSONObject();
        content.put("text", text);
        JSONObject body = new JSONObject();
        body.put("msg_type", "text");
        body.put("content", content);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
        try {
            conn.setConnectTimeout(POST_TIMEOUT_MS);
            conn.setReadTimeout(POST_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static String buildSummary(FailureEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("[autoform-kit] failure report\n");
        sb.append("stage: ").append(event.stage).append('\n');
        sb.append("error: ").append(event.errCode);
        if (!event.subphase.isEmpty()) sb.append(" @ ").append(event.subphase);
        sb.append('\n');
        sb.append("fingerprint: ").append(event.fingerprint).append('\n');
        sb.append("time: ").append(formatTs(event.timestampMs)).append('\n');
        if (!event.message.isEmpty()) sb.append("message: ").append(event.message).append('\n');
        if (!event.ctx.isEmpty()) {
            sb.append("context:\n");
            for (Map.Entry<String, String> e : event.ctx.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        String trail = snapshotBreadcrumbs(event.timestampMs);
        if (!trail.isEmpty()) sb.append("breadcrumb:\n").append(trail);
        if (event.throwableText != null) {
            sb.append("stack:\n").append(event.throwableText).append('\n');
        }
        if (sb.length() > MAX_SUMMARY_CHARS) {
            return sb.substring(0, MAX_SUMMARY_CHARS) + "\n…(truncated)";
        }
        return sb.toString();
    }

    private void recordConfigError(int status) {
        if (prefs == null) return;
        prefs.edit()
                .putInt(KEY_LAST_CONFIG_ERROR_STATUS, status)
                .putLong(KEY_LAST_CONFIG_ERROR_MS, System.currentTimeMillis())
                .apply();
    }

    private void recordTransportError() {
        if (prefs == null) return;
        prefs.edit().putLong(KEY_LAST_TRANSPORT_ERROR_MS, System.currentTimeMillis()).apply();
    }

    private void clearConfigError() {
        if (prefs == null) return;
        if (prefs.getInt(KEY_LAST_CONFIG_ERROR_STATUS, 0) == 0
                && prefs.getLong(KEY_LAST_TRANSPORT_ERROR_MS, 0L) == 0L) return;
        prefs.edit()
                .remove(KEY_LAST_CONFIG_ERROR_STATUS)
                .remove(KEY_LAST_CONFIG_ERROR_MS)
                .remove(KEY_LAST_TRANSPORT_ERROR_MS)
                .apply();
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

    public void requestFlush() {
        flushAsync();
    }

    private void installNetworkRecoveryTrigger() {
        if (appContext == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    flushAsync();
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "network recovery trigger install failed", error);
        }
    }

    private void schedulePeriodicFlush() {
        if (appContext == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (queue != null && queue.size() > 0) flushAsync();
                handler.postDelayed(this, PERIODIC_FLUSH_INTERVAL_MS);
            }
        };
        handler.postDelayed(tick, PERIODIC_FLUSH_INTERVAL_MS);
    }

    private static String formatTs(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(new Date(ms));
    }

    public static Map<String, String> ctx(Object... kv) {
        HashMap<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null) continue;
            map.put(kv[i].toString(), kv[i + 1] == null ? "" : kv[i + 1].toString());
        }
        return map;
    }
}
