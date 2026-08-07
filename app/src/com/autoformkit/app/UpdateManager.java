package com.autoformkit.app;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.system.Os;
import android.system.StructStat;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class UpdateManager {
    private static final String CONFIG_ASSET = "update-config.json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    static final String PREFS = "update_state";
    private static final String PREF_LEGACY_PENDING_APK = "pending_apk_path";
    static final String PREF_PENDING_INSTALL = "pending_install_v2_json";
    static final String PREF_HANDOFF_BINDING = "pending_handoff_binding_sha256";
    static final String PREF_HANDOFF_OPENED_BINDING =
        "pending_handoff_opened_binding_sha256";
    static final String PREF_HANDOFF_IDENTITY =
        "pending_handoff_identity_sha256";
    private static final String PREF_UPDATE_CHANNEL = "update_channel";
    private static final String PREF_LAST_CHECK_MS = "last_check_ms";
    private static final String CHANNEL_STABLE = "stable";
    private static final String CHANNEL_BETA = "beta";
    private static final SecureRandom HANDOFF_RANDOM = new SecureRandom();
    /** Foreground re-checks no more than once per 10 minutes so a quick task-switch
     *  doesn't replay the network probe. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 10 * 60 * 1000L;

    private final Activity activity;
    private final SharedPreferences prefs;
    /** Exact Panel/update configuration already checked in this process. An empty value means
     *  startup ran before a complete Panel pair was available and must be retried when it is. */
    private String checkedConfigIdentity = "";
    private boolean updateCheckActive = false;
    private boolean retryAfterPanelReady = false;
    private int operationGeneration = 0;
    private boolean pendingResumeActive = false;
    private boolean installValidationActive = false;
    private boolean pendingRetryDialogActive = false;

    UpdateManager(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    static boolean uploadReplayBarrierPresent(Context context) {
        return context != null
            && context.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
                .contains(MainActivity.UPLOAD_REPLAY_BARRIER_KEY);
    }

    /** Five durable side-effect slots plus every active in-process remote worker. */
    static boolean remoteSideEffectBlockingStatePresent(Context context) {
        return RemoteSideEffectGate.blockingStatePresent(context);
    }

    /** True while an installer URI can still be opened; suspended retry metadata is not active. */
    static boolean installerHandoffActive(Context context) {
        if (context == null) return true;
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            SharedPreferences state = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
            return state.contains(PREF_HANDOFF_BINDING)
                || state.contains(PREF_HANDOFF_OPENED_BINDING);
        }
    }

    void checkOnStartup() {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return;
        check(false);
    }

    /**
     * The first startup probe can legitimately beat Panel synchronization. In that case there is
     * no trustworthy update source yet, so the probe must not consume this process's automatic
     * check. MainActivity calls this again after a complete config/catalog pair becomes READY.
     */
    void checkAfterPanelReady() {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return;
        synchronized (this) {
            if (updateCheckActive) {
                retryAfterPanelReady = true;
                return;
            }
        }
        check(false);
    }

    /** Called from {@link Activity#onResume()}; throttled by {@link #FOREGROUND_CHECK_INTERVAL_MS}. */
    void checkOnForeground() {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong(PREF_LAST_CHECK_MS, 0L);
        if (now - last < FOREGROUND_CHECK_INTERVAL_MS) return;
        check(true);
    }

    void checkNow() {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return;
        check(true);
    }

    private void check(boolean force) {
        synchronized (this) {
            // Startup, foreground and Panel-ready callbacks can arrive almost together. One
            // source-bound probe is sufficient; a Panel-ready callback is explicitly replayed
            // below only when the source changed or startup had no complete pair.
            if (updateCheckActive) return;
            updateCheckActive = true;
        }
        new Thread(() -> {
            Config config = null;
            String configIdentity = "";
            try {
                config = loadConfig();
                if (!config.panelReady) return;
                configIdentity = checkIdentity(config);
                synchronized (UpdateManager.this) {
                    if (!force && configIdentity.equals(checkedConfigIdentity)) return;
                }
                // Do not start the foreground throttle until a complete, current Panel pair has
                // actually resolved the update policy. A pre-Panel startup no-op must be retryable.
                prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
                if (!config.enabled || config.source == null) return;
                final int generation = nextOperationGeneration();
                requireCurrentSource(config.source, generation, "release check");
                UpdateInfo update = findUpdate(config, generation);
                if (update == null) return;
                requireCurrentSource(config.source, generation, "update dialog");
                showUpdateDialog(update, generation);
            } catch (Exception exc) {
                // Update checks must never block the form workflow.
                Diagnostics.append(activity,
                    "Update check deferred: " + exc.getClass().getSimpleName());
            } finally {
                boolean configurationStillCurrent = config != null && config.panelReady
                    && checkIdentityStillCurrent(configIdentity);
                boolean retry;
                synchronized (UpdateManager.this) {
                    if (configurationStillCurrent) {
                        checkedConfigIdentity = configIdentity;
                    }
                    updateCheckActive = false;
                    retry = retryAfterPanelReady && !configurationStillCurrent;
                    retryAfterPanelReady = false;
                }
                if (retry) check(false);
            }
        }, "update-check").start();
    }

    private boolean checkIdentityStillCurrent(String expectedIdentity) {
        if (expectedIdentity == null || expectedIdentity.isEmpty()) return false;
        try {
            Config current = loadConfig();
            return current.panelReady && expectedIdentity.equals(checkIdentity(current));
        } catch (Exception error) {
            return false;
        }
    }

    private static String checkIdentity(Config config) {
        if (config == null || !config.panelReady) return "";
        return config.connectionNamespace + "\n"
            + config.panelPairSha256 + "\n"
            + config.enabled + "\n"
            + config.channel + "\n"
            + config.owner + "\n"
            + config.repo + "\n"
            + config.manifestAsset + "\n"
            + config.releaseTag;
    }

    private synchronized int nextOperationGeneration() {
        operationGeneration++;
        return operationGeneration;
    }

    private synchronized boolean isCurrentGeneration(int generation) {
        return generation == operationGeneration;
    }

    static String currentChannel(Context context) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return CHANNEL_STABLE;
        try {
            return UpdateSourceRules.deviceChannel(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_UPDATE_CHANNEL, CHANNEL_STABLE));
        } catch (Exception invalidStoredType) {
            return CHANNEL_STABLE;
        }
    }

    static String toggleChannel(Activity activity) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) return CHANNEL_STABLE;
        String next = CHANNEL_BETA.equals(currentChannel(activity)) ? CHANNEL_STABLE : CHANNEL_BETA;
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit()
            .putString(PREF_UPDATE_CHANNEL, next)
            .apply();
        return next;
    }

    void resumePendingInstall() {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) {
            clearPendingPreferences();
            return;
        }
        rejectLegacyPendingPath();
        synchronized (this) {
            if (pendingResumeActive) return;
            pendingResumeActive = true;
        }
        new Thread(() -> {
            PendingInstall pending = null;
            try {
                pending = readPendingInstall();
                if (pending == null) return;
                // A successful install replaces this package but preserves its private data.
                // The first onResume in that installed version is therefore the only reliable
                // success acknowledgement available to an ACTION_VIEW based installer flow.
                if (currentVersionCode() >= pending.metadata.versionCode) {
                    discardPendingInstall(pending);
                    return;
                }
                Config current = loadConfig();
                if (!current.enabled || current.source == null
                        || !pending.source.sameAs(current.source)
                        || !pending.metadata.matchesSource(current.source)) {
                    discardPendingInstall(pending);
                    return;
                }
                File apk = privateUpdateFile(pending.metadata.apkName);
                ValidatedApk validated = validatePendingApk(pending, apk);
                if (!pending.metadata.matchesValidated(apk.getName(),
                        pending.metadata.manifestSha256, validated.apkSha256,
                        validated.packageName, validated.versionCode, validated.versionName,
                        validated.signerSetSha256, validated.apkLength,
                        validated.apkLastModified)) {
                    discardPendingInstall(pending);
                    return;
                }
                // Re-read the Panel source after hashing/parsing the APK. A Panel switch during
                // validation must not hand the old file to the installer.
                Config afterValidation = loadConfig();
                if (!afterValidation.enabled || afterValidation.source == null
                        || !pending.source.sameAs(afterValidation.source)) {
                    discardPendingInstall(pending);
                    return;
                }
                PendingInstall capturedPending = pending;
                activity.runOnUiThread(() -> requestInstall(capturedPending));
            } catch (Exception error) {
                if (pending == null) clearPendingPreferences();
                else discardPendingInstall(pending);
            } finally {
                synchronized (UpdateManager.this) {
                    pendingResumeActive = false;
                }
            }
        }, "update-resume-validate").start();
    }

    private Config loadConfig() throws Exception {
        return loadConfig(activity);
    }

    private static Config loadConfig(Context context) throws Exception {
        JSONObject json = new JSONObject(readAsset(context, CONFIG_ASSET));
        // Capture config, catalog revision and pair digest under one pair-read lock. Reading the
        // two AtomicFiles independently could authorize an update from mixed same-revision halves.
        String expectedConnection = AppConfig.connectionNamespaceId(
            AppConfig.panelBase(context), AppConfig.catalogKey(context));
        PanelPairCacheCoordinator.ActivePair panelPair =
            PanelPairCacheCoordinator.loadActivePairIfNoCandidates(
                context, expectedConnection);
        JSONObject panelCfg = panelPair == null ? null : panelPair.config;
        boolean compatiblePanelPair = panelPair != null;
        String panelPairSha256 = panelPair == null ? "" : panelPair.pairSha256;
        SharedPreferences updatePrefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String devicePreference = CHANNEL_STABLE;
        try {
            devicePreference = updatePrefs.getString(
                PREF_UPDATE_CHANNEL, CHANNEL_STABLE);
        } catch (Exception invalidStoredType) {
            devicePreference = CHANNEL_STABLE;
        }
        UpdateSourceRules.Resolved resolved = AppConfig.resolveUpdateSource(
            json, panelCfg, devicePreference);
        Config config = new Config();
        config.panelReady = compatiblePanelPair && panelCfg != null;
        config.connectionNamespace = expectedConnection;
        config.panelPairSha256 = panelPairSha256;
        config.channel = resolved.channel;
        config.enabled = resolved.enabled;
        config.owner = resolved.owner;
        config.repo = resolved.repo;
        config.manifestAsset = resolved.manifestAsset;
        config.releaseTag = resolved.releaseTag;
        if (config.owner.isEmpty() || config.repo.isEmpty()) {
            config.enabled = false;
        }
        if (config.enabled && panelCfg != null && compatiblePanelPair) {
            try {
                config.source = UpdateInstallRules.SourceBinding.capture(
                    AppConfig.connectionNamespaceId(
                        AppConfig.panelBase(context), AppConfig.catalogKey(context)),
                    AppConfig.catalogVersion(panelCfg), panelPairSha256, config.channel,
                    config.owner, config.repo, config.manifestAsset, config.releaseTag);
            } catch (IllegalArgumentException unsafeSource) {
                config.enabled = false;
            }
        } else {
            config.enabled = false;
        }
        return config;
    }

    private UpdateInfo findUpdate(Config config, int generation) throws Exception {
        requireCurrentSource(config.source, generation, "release metadata GET");
        String releaseUrl = releaseUrl(config);
        JSONObject release = new JSONObject(getText(releaseUrl, "application/vnd.github+json"));
        requireCurrentSource(config.source, generation, "release metadata response");
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;

        JSONObject manifestAsset = findAsset(assets, config.manifestAsset);
        if (manifestAsset == null) return null;
        requireCurrentSource(config.source, generation, "manifest GET");
        String manifestText = getText(
            manifestAsset.getString("url"), "application/octet-stream");
        requireCurrentSource(config.source, generation, "manifest response");
        JSONObject manifest = new JSONObject(manifestText);

        String packageName = manifest.optString("packageName", "");
        if (!activity.getPackageName().equals(packageName)) {
            throw new IOException("Update package mismatch: " + packageName);
        }
        long remoteVersion = manifest.optLong("versionCode", 0);
        long currentVersion = currentVersionCode();
        if (remoteVersion <= currentVersion) return null;

        String apkAssetName = manifest.optString("apkAsset", "");
        if (!UpdateInstallRules.isSafeApkName(apkAssetName)) {
            throw new IOException("update.json contains an unsafe apkAsset");
        }
        JSONObject apkAsset = findAsset(assets, apkAssetName);
        if (apkAsset == null) throw new IOException("Release asset not found: " + apkAssetName);

        UpdateInfo update = new UpdateInfo();
        update.config = config;
        update.source = config.source;
        update.versionCode = remoteVersion;
        update.versionName = manifest.optString("versionName", String.valueOf(remoteVersion));
        update.packageName = packageName;
        update.notes = manifest.optString("notes", "");
        update.apkAsset = apkAssetName;
        update.apkUrl = apkAsset.getString("url");
        update.sha256 = UpdateManifestRules.requireSha256(manifest.optString("sha256", ""));
        update.manifestSha256 = UpdateInstallRules.sha256(manifestText);
        return update;
    }

    private String releaseUrl(Config config) throws IOException {
        String base = "https://api.github.com/repos/" + config.owner + "/" + config.repo + "/releases";
        if (config.releaseTag == null || config.releaseTag.isEmpty()) {
            return base + "/latest";
        }
        return base + "/tags/" + encodePathSegment(config.releaseTag);
    }

    private String encodePathSegment(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private JSONObject findAsset(JSONArray assets, String name) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (name.equals(asset.optString("name"))) {
                return asset;
            }
        }
        return null;
    }

    /**
     * Dialogs here are shown after background network work (the update check and the
     * download both run off the main thread). By the time the UI callback fires the
     * activity may have finished or been recreated, leaving a dead window token —
     * calling {@code AlertDialog.show()} then throws
     * {@link android.view.WindowManager.BadTokenException}. Guard every post-async UI
     * action with this check.
     */
    private boolean activityAlive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Update dialogs run outside MainActivity's {@code t()}, so pick the string by the
     *  language the user chose in settings (same prefs file/key MainActivity uses). */
    private String s(String zh, String en, String es) {
        String lang;
        try {
            lang = activity.getSharedPreferences("settings", Activity.MODE_PRIVATE).getString("lang", "zh");
        } catch (Exception ignored) {
            lang = "zh";
        }
        if ("en".equals(lang)) return en;
        if ("es".equals(lang)) return es;
        return zh;
    }

    private void showUpdateDialog(UpdateInfo update, int generation) {
        activity.runOnUiThread(() -> {
            if (!activityAlive() || !sourceStillCurrent(update.source, generation)) return;
            StringBuilder message = new StringBuilder();
            message.append(s("更新通道: ", "Update channel: ", "Canal de actualización: ")).append(channelLabel(update.config.channel)).append("\n");
            message.append(s("当前版本: ", "Current version: ", "Versión actual: ")).append(currentVersionName()).append("\n");
            message.append(s("最新版本: ", "Latest version: ", "Última versión: ")).append(update.versionName).append(" (").append(update.versionCode).append(")");
            if (!update.notes.isEmpty()) {
                message.append("\n\n").append(update.notes);
            }
            new AlertDialog.Builder(activity)
                .setTitle(s("发现新版本", "Update available", "Actualización disponible"))
                .setMessage(message.toString())
                .setNegativeButton(s("稍后", "Later", "Más tarde"), null)
                .setPositiveButton(s("下载并安装", "Download & install", "Descargar e instalar"),
                    (dialog, which) -> downloadAndInstall(update, generation))
                .show();
        });
    }

    private String channelLabel(String channel) {
        return CHANNEL_BETA.equals(channel) ? "Beta" : s("正式版", "Stable", "Estable");
    }

    private void downloadAndInstall(UpdateInfo update, int generation) {
        if (!sourceStillCurrent(update.source, generation)) return;
        Toast.makeText(activity, s("开始下载更新...", "Downloading update...", "Descargando actualización..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File partial = null;
            try {
                requireCurrentSource(update.source, generation, "APK download");
                String localName = UpdateInstallRules.localApkName(
                    update.versionCode, update.sha256);
                File apk = privateUpdateFile(localName);
                // Keep an .apk suffix so PackageManager parses the staging file consistently on
                // vendor Android builds; the private, non-exported provider never grants this name.
                partial = new File(updateDir(), localName + ".download.apk");
                final File capturedPartial = partial;
                final long[] lastSourceGuardBytes = {0L};
                downloadAsset(update.apkUrl, capturedPartial, (done, total) -> {
                    if (done - lastSourceGuardBytes[0] >= 1024L * 1024L
                            || (total > 0L && done >= total)) {
                        requireCurrentSource(
                            update.source, generation, "APK download stream");
                        lastSourceGuardBytes[0] = done;
                        notifyDownloadProgress(update, done, total);
                    }
                });
                requireCurrentSource(update.source, generation, "APK validation");
                validateDownload(update, partial);
                requireCurrentSource(update.source, generation, "APK publish");
                if (apk.exists() && !apk.delete()) {
                    throw new IOException("Cannot replace previous validated update file.");
                }
                if (!partial.renameTo(apk)) {
                    throw new IOException("Cannot publish validated update file.");
                }
                partial = null;
                if (!apk.setReadOnly()) {
                    throw new IOException("Cannot lock validated update file read-only.");
                }
                // Re-hash and re-parse the final inode after the rename. Do not rely on validation
                // of a staging path when the provider will expose the final path.
                ValidatedApk finalValidation = validateDownload(update, apk);
                requireCurrentSource(update.source, generation, "pending install persist");
                PendingInstall pending = pendingInstall(update, apk, finalValidation);
                if (!writePendingInstall(pending)) {
                    throw new IOException("Cannot persist pending update metadata.");
                }
                requireCurrentSource(update.source, generation, "download completion UI");
                notifyDownloadDone(update);
                activity.runOnUiThread(() -> {
                    if (!sourceStillCurrent(update.source, generation)) {
                        discardPendingInstall(pending);
                        return;
                    }
                    requestInstall(pending);
                });
            } catch (Exception exc) {
                if (partial != null) deletePrivateUpdateFile(partial);
                cancelDownloadNotification();
                activity.runOnUiThread(() -> {
                    if (!activityAlive() || !sourceStillCurrent(update.source, generation)) return;
                    new AlertDialog.Builder(activity)
                        .setTitle(s("更新失败", "Update failed", "Error de actualización"))
                        .setMessage(exc.getMessage())
                        .setPositiveButton("OK", null)
                        .show();
                });
            }
        }, "update-download").start();
    }

    // ===== 下载进度通知：常驻进度条（静默通道），完成后换成可滑走的「下载完成」。 =====
    private static final String DOWNLOAD_CHANNEL_ID = "update_download";
    private static final int DOWNLOAD_NOTIFICATION_ID = 1002;
    private long lastProgressNotifyMs = 0;
    private int lastProgressPercent = -1;

    private NotificationManager notificationManager() {
        return (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification.Builder downloadNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                s("更新下载", "Update download", "Descarga de actualización"),
                NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager().createNotificationChannel(channel);
            return new Notification.Builder(activity, DOWNLOAD_CHANNEL_ID);
        }
        return new Notification.Builder(activity);
    }

    private void notifyDownloadProgress(UpdateInfo update, long done, long total) {
        // 节流：进度没变不刷；变了也至少间隔 300ms（100% 除外），避免通知风暴。
        int percent = total > 0 ? (int) (done * 100 / total) : -1;
        long now = System.currentTimeMillis();
        if (percent < 0) {
            if (now - lastProgressNotifyMs < 500) return;
        } else {
            if (percent == lastProgressPercent) return;
            if (now - lastProgressNotifyMs < 300 && percent < 100) return;
        }
        lastProgressPercent = percent;
        lastProgressNotifyMs = now;
        Notification.Builder builder = downloadNotificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(s("正在下载更新 ", "Downloading update ", "Descargando actualización ") + update.versionName)
            .setOnlyAlertOnce(true)
            .setOngoing(true);
        if (percent >= 0) {
            builder.setProgress(100, percent, false)
                .setContentText(percent + "% · " + (done / (1024 * 1024)) + "MB / " + (total / (1024 * 1024)) + "MB");
        } else {
            builder.setProgress(0, 0, true)
                .setContentText((done / (1024 * 1024)) + "MB");
        }
        try {
            notificationManager().notify(DOWNLOAD_NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
            // 无通知权限等情况：进度通知只是锦上添花，绝不影响下载本身。
        }
    }

    private void notifyDownloadDone(UpdateInfo update) {
        Notification.Builder builder = downloadNotificationBuilder()
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(s("更新下载完成 ", "Update downloaded ", "Actualización descargada ") + update.versionName)
            .setContentText(s("等待安装", "Waiting to install", "Esperando instalación"))
            .setOngoing(false)
            .setAutoCancel(true);
        try {
            notificationManager().notify(DOWNLOAD_NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelDownloadNotification() {
        try {
            notificationManager().cancel(DOWNLOAD_NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    private ValidatedApk validateDownload(UpdateInfo update, File apk) throws Exception {
        return validateApk(apk, update.sha256, update.packageName, update.versionCode,
            update.versionName);
    }

    private ValidatedApk validatePendingApk(PendingInstall pending, File apk)
            throws Exception {
        if (pending == null || pending.metadata == null
                || !pending.metadata.matchesSource(pending.source)) {
            throw new IOException("Pending update binding is invalid.");
        }
        return validateApk(apk, pending.metadata.apkSha256,
            pending.metadata.packageName, pending.metadata.versionCode,
            pending.metadata.versionName);
    }

    private ValidatedApk validateApk(File apk, String expectedSha256,
                                     String expectedPackage, long expectedVersion,
                                     String expectedVersionName) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() <= 0L
                || !isPrivateUpdateFile(apk)) {
            throw new IOException("Update APK is missing or outside the private update directory.");
        }
        String actual = sha256(apk);
        if (!expectedSha256.equals(actual)) {
            throw new IOException("APK SHA-256 mismatch.");
        }
        PackageManager manager = activity.getPackageManager();
        PackageInfo info = manager.getPackageArchiveInfo(
            apk.getAbsolutePath(), signingFlags());
        if (info == null) throw new IOException("Downloaded file is not a valid APK.");
        if (!expectedPackage.equals(info.packageName)
                || !activity.getPackageName().equals(info.packageName)) {
            throw new IOException("Downloaded APK package mismatch.");
        }
        long downloadedVersion = versionCode(info);
        if (downloadedVersion <= currentVersionCode()
                || downloadedVersion != expectedVersion) {
            throw new IOException("Downloaded APK version mismatch.");
        }
        String downloadedVersionName = info.versionName == null ? "" : info.versionName.trim();
        if (!expectedVersionName.equals(downloadedVersionName)) {
            throw new IOException("Downloaded APK version name mismatch.");
        }
        PackageInfo installed = manager.getPackageInfo(
            activity.getPackageName(), signingFlags());
        Set<String> installedCurrent = currentSignerDigests(installed);
        Set<String> candidateHistory = signingHistoryDigests(info);
        if (!UpdateInstallRules.hasSignerContinuity(installedCurrent, candidateHistory)) {
            throw new IOException("Downloaded APK signer does not continue the installed app signer.");
        }
        String signerSetSha256 = signerSetSha256(candidateHistory);
        return new ValidatedApk(actual, info.packageName, downloadedVersion,
            downloadedVersionName, signerSetSha256, apk.length(), apk.lastModified());
    }

    private void requestInstall(PendingInstall pending) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) {
            discardPendingInstall(pending);
            return;
        }
        if (!activityAlive()) return;
        if (remoteSideEffectBlockingStatePresent(activity)) {
            new AlertDialog.Builder(activity)
                .setTitle(s("远程操作待确认", "Remote operation needs confirmation",
                    "La operación remota requiere confirmación"))
                .setMessage(s(
                    "设备上有尚未安全结束的上传、提交、上一工序、补打操作，或旧版本待返回的拍照状态。为防止旧版本重放、照片错配或丢失锁定信息，完成原流程核对前不能安装更新。",
                    "An upload, submission, previous-step action, reprint, or camera return from an older version has not reached a safe local terminal state. To prevent replay, a mismatched photo, or loss of its lock, finish reconciliation against the original workflow before installing an update.",
                    "Una carga, envío, operación previa, reimpresión o retorno de cámara de una versión anterior no alcanzó un estado local final seguro. Para evitar repetirla, asociar una foto incorrecta o perder su bloqueo, complete la conciliación con el flujo original antes de instalar una actualización."))
                .setPositiveButton(s("知道了", "OK", "Aceptar"), null)
                .show();
            return;
        }
        if (!pendingSourceStillCurrent(pending)) {
            discardPendingInstall(pending);
            return;
        }
        if (handoffWasIssued(pending)) {
            // Returning to our Activity means the previous installer handoff is no longer the
            // foreground attempt. Revoke its capability before offering a new token. Keep the
            // identity marker so "Later" remains durable across another process restart.
            suspendIssuedHandoff(pending);
            showPendingInstallRetry(pending);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                .setTitle(s("需要安装权限", "Install permission needed", "Se requiere permiso de instalación"))
                .setMessage(s("请允许本应用安装未知来源应用，返回后会继续安装更新。",
                        "Please allow this app to install unknown apps; the update will continue when you return.",
                        "Permita que esta app instale apps de origen desconocido; la actualización continuará al volver."))
                .setNegativeButton(s("稍后", "Later", "Más tarde"), null)
                .setPositiveButton(s("去设置", "Settings", "Ajustes"), (dialog, which) -> openInstallSettings())
                .show();
            return;
        }
        validateAndLaunchPending(pending);
    }

    private void showPendingInstallRetry(PendingInstall pending) {
        synchronized (this) {
            if (pendingRetryDialogActive) return;
            pendingRetryDialogActive = true;
        }
        if (!activityAlive()) {
            endPendingRetryDialog();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(s("更新尚未安装", "Update not installed", "Actualización no instalada"))
            .setMessage(s("上次安装已取消或未完成。是否继续安装已验证的更新？",
                "The previous install was cancelled or did not finish. Retry the verified update?",
                "La instalación anterior se canceló o no terminó. ¿Reintentar la actualización verificada?"))
            .setNegativeButton(s("稍后", "Later", "Más tarde"), null)
            .setPositiveButton(s("继续安装", "Retry install", "Reintentar"), (ignored, which) -> {
                if (clearIssuedHandoff(pending)) requestInstall(pending);
            })
            .create();
        dialog.setOnDismissListener(ignored -> endPendingRetryDialog());
        dialog.show();
    }

    private synchronized void endPendingRetryDialog() {
        pendingRetryDialogActive = false;
    }

    private void validateAndLaunchPending(PendingInstall pending) {
        synchronized (this) {
            if (installValidationActive) return;
            installValidationActive = true;
        }
        new Thread(() -> {
            boolean handedToUi = false;
            try {
                if (remoteSideEffectBlockingStatePresent(activity)) return;
                if (!pendingSourceStillCurrent(pending)) {
                    discardPendingInstall(pending);
                    return;
                }
                File apk = privateUpdateFile(pending.metadata.apkName);
                ValidatedApk validated = validatePendingApk(pending, apk);
                if (!pending.metadata.matchesValidated(apk.getName(),
                        pending.metadata.manifestSha256, validated.apkSha256,
                        validated.packageName, validated.versionCode, validated.versionName,
                        validated.signerSetSha256, validated.apkLength,
                        validated.apkLastModified)
                        || !pendingSourceStillCurrent(pending)) {
                    discardPendingInstall(pending);
                    return;
                }
                if (remoteSideEffectBlockingStatePresent(activity)) return;
                activity.runOnUiThread(() -> {
                    try {
                        launchInstaller(pending, apk);
                    } finally {
                        endInstallValidation();
                    }
                });
                handedToUi = true;
            } catch (Exception error) {
                discardPendingInstall(pending);
            } finally {
                if (!handedToUi) endInstallValidation();
            }
        }, "update-install-final-validate").start();
    }

    private synchronized void endInstallValidation() {
        installValidationActive = false;
    }

    private void launchInstaller(PendingInstall pending, File apk) {
        if (!activityAlive()) return;
        if (remoteSideEffectBlockingStatePresent(activity)) {
            requestInstall(pending);
            return;
        }
        if (!pendingSourceStillCurrent(pending)
                || apk.length() != pending.metadata.apkLength
                || apk.lastModified() != pending.metadata.apkLastModified) {
            discardPendingInstall(pending);
            return;
        }
        String handoffToken = issueHandoffToken(pending);
        if (handoffToken.isEmpty()) {
            Toast.makeText(activity, s(
                "无法准备安装交付，请稍后重试。",
                "Could not prepare the installer handoff. Please retry.",
                "No se pudo preparar la instalación. Inténtelo de nuevo."),
                Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = UpdateApkProvider.uriForFile(activity, apk, handoffToken);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, APK_MIME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (Exception exc) {
            revokeUnopenedHandoff(pending, handoffToken);
            new AlertDialog.Builder(activity)
                .setTitle(s("无法安装", "Cannot install", "No se puede instalar"))
                .setMessage(s("系统没有可用的 APK 安装器。",
                        "No APK installer is available on this device.",
                        "No hay instalador de APK disponible en el dispositivo."))
                .setPositiveButton("OK", null)
                .show();
        }
    }

    private void requireCurrentSource(UpdateInstallRules.SourceBinding expected,
                                      int generation, String phase) throws IOException {
        if (!sourceStillCurrent(expected, generation)) {
            throw new IOException("Update source changed before " + phase);
        }
    }

    private boolean sourceStillCurrent(UpdateInstallRules.SourceBinding expected,
                                       int generation) {
        if (expected == null || !isCurrentGeneration(generation)) return false;
        try {
            Config current = loadConfig();
            return current.enabled && expected.sameAs(current.source)
                && isCurrentGeneration(generation);
        } catch (Exception error) {
            return false;
        }
    }

    private boolean pendingSourceStillCurrent(PendingInstall pending) {
        if (pending == null || pending.source == null || pending.metadata == null
                || !pending.metadata.matchesSource(pending.source)) return false;
        return sourceBindingStillCurrent(activity, pending.source);
    }

    static boolean sourceBindingStillCurrent(Context context,
                                             UpdateInstallRules.SourceBinding expected) {
        if (context == null || expected == null || !BuildConfig.AUTO_UPDATE_ENABLED) {
            return false;
        }
        try {
            Config current = loadConfig(context);
            return current.enabled && expected.sameAs(current.source);
        } catch (Exception error) {
            return false;
        }
    }

    private PendingInstall pendingInstall(UpdateInfo update, File apk,
                                          ValidatedApk validated) {
        UpdateInstallRules.PendingMetadata metadata =
            new UpdateInstallRules.PendingMetadata(apk.getName(),
                update.manifestSha256, validated.apkSha256, validated.packageName,
                validated.versionCode, validated.versionName, update.source.sha256,
                validated.signerSetSha256, validated.apkLength,
                validated.apkLastModified);
        return new PendingInstall(update.source, metadata);
    }

    private boolean writePendingInstall(PendingInstall pending) {
        try {
            JSONObject source = new JSONObject()
                .put("connectionNamespace", pending.source.connectionNamespace)
                .put("catalogVersion", pending.source.catalogVersion)
                .put("panelPairSha256", pending.source.panelPairSha256)
                .put("channel", pending.source.channel)
                .put("owner", pending.source.owner)
                .put("repo", pending.source.repo)
                .put("manifestAsset", pending.source.manifestAsset)
                .put("releaseTag", pending.source.releaseTag)
                .put("sha256", pending.source.sha256);
            UpdateInstallRules.PendingMetadata metadata = pending.metadata;
            JSONObject root = new JSONObject()
                .put("schema", UpdateInstallRules.PENDING_SCHEMA)
                .put("source", source)
                .put("apkName", metadata.apkName)
                .put("manifestSha256", metadata.manifestSha256)
                .put("apkSha256", metadata.apkSha256)
                .put("packageName", metadata.packageName)
                .put("versionCode", metadata.versionCode)
                .put("versionName", metadata.versionName)
                .put("sourceSha256", metadata.sourceSha256)
                .put("signerSetSha256", metadata.signerSetSha256)
                .put("apkLength", metadata.apkLength)
                .put("apkLastModified", metadata.apkLastModified);
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                return prefs.edit().putString(PREF_PENDING_INSTALL, root.toString())
                    .remove(PREF_LEGACY_PENDING_APK)
                    .remove(PREF_HANDOFF_BINDING)
                    .remove(PREF_HANDOFF_OPENED_BINDING)
                    .remove(PREF_HANDOFF_IDENTITY)
                    .commit();
            }
        } catch (Exception error) {
            return false;
        }
    }

    private PendingInstall readPendingInstall() {
        JSONObject root = null;
        try {
            Object raw = prefs.getAll().get(PREF_PENDING_INSTALL);
            if (raw == null) return null;
            if (!(raw instanceof String)) throw new IOException("Pending update metadata type is invalid.");
            root = new JSONObject((String) raw);
            return parsePendingInstall(root);
        } catch (Exception error) {
            synchronized (UpdateInstallRules.HANDOFF_LOCK) {
                if (root != null) {
                    deletePrivateUpdateFileByName(root.optString("apkName", ""));
                }
                clearPendingPreferences();
            }
            return null;
        }
    }

    static PendingInstall parsePendingInstallJson(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) {
            throw new IOException("Pending update metadata is missing.");
        }
        return parsePendingInstall(new JSONObject(raw));
    }

    private static PendingInstall parsePendingInstall(JSONObject root) throws Exception {
        if (root.getInt("schema") != UpdateInstallRules.PENDING_SCHEMA) {
            throw new IOException("Pending update metadata schema is unsupported.");
        }
        JSONObject sourceJson = root.getJSONObject("source");
        UpdateInstallRules.SourceBinding source =
            UpdateInstallRules.SourceBinding.capture(
                sourceJson.getString("connectionNamespace"),
                sourceJson.getInt("catalogVersion"),
                sourceJson.getString("panelPairSha256"),
                sourceJson.getString("channel"), sourceJson.getString("owner"),
                sourceJson.getString("repo"), sourceJson.getString("manifestAsset"),
                sourceJson.getString("releaseTag"));
        if (!UpdateInstallRules.digestEquals(
                source.sha256, sourceJson.getString("sha256"))) {
            throw new IOException("Pending update source digest mismatch.");
        }
        UpdateInstallRules.PendingMetadata metadata =
            new UpdateInstallRules.PendingMetadata(root.getString("apkName"),
                root.getString("manifestSha256"), root.getString("apkSha256"),
                root.getString("packageName"), root.getLong("versionCode"),
                root.getString("versionName"), root.getString("sourceSha256"),
                root.getString("signerSetSha256"), root.getLong("apkLength"),
                root.getLong("apkLastModified"));
        if (!metadata.matchesSource(source)) {
            throw new IOException("Pending update metadata is not bound to its source.");
        }
        return new PendingInstall(source, metadata);
    }

    private String issueHandoffToken(PendingInstall pending) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                if (remoteSideEffectBlockingStatePresent(activity)
                        || !storedPendingMatches(pending)) return "";
                byte[] random = new byte[32];
                HANDOFF_RANDOM.nextBytes(random);
                String token = hex(random);
                String binding = UpdateInstallRules.handoffBindingSha256(
                    token, pending.source, pending.metadata);
                String identity = UpdateInstallRules.pendingIdentitySha256(
                    pending.source, pending.metadata);
                boolean saved = prefs.edit()
                    .putString(PREF_HANDOFF_BINDING, binding)
                    .remove(PREF_HANDOFF_OPENED_BINDING)
                    .putString(PREF_HANDOFF_IDENTITY, identity)
                    .commit();
                return saved ? token : "";
            } catch (Exception error) {
                return "";
            }
        }
    }

    private boolean handoffWasIssued(PendingInstall pending) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!storedPendingMatches(pending)) return false;
            Object raw = prefs.getAll().get(PREF_HANDOFF_IDENTITY);
            return raw instanceof String && UpdateInstallRules.digestEquals(
                (String) raw, UpdateInstallRules.pendingIdentitySha256(
                    pending.source, pending.metadata));
        }
    }

    private void suspendIssuedHandoff(PendingInstall pending) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!handoffWasIssued(pending)) return;
            // Removing both capability bindings makes the old URI unusable. The identity remains
            // as the durable evidence which drives the retry prompt.
            prefs.edit()
                .remove(PREF_HANDOFF_BINDING)
                .remove(PREF_HANDOFF_OPENED_BINDING)
                .commit();
        }
    }

    private boolean clearIssuedHandoff(PendingInstall pending) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (!handoffWasIssued(pending)) return false;
            return prefs.edit()
                .remove(PREF_HANDOFF_BINDING)
                .remove(PREF_HANDOFF_OPENED_BINDING)
                .remove(PREF_HANDOFF_IDENTITY)
                .commit();
        }
    }

    private void revokeUnopenedHandoff(PendingInstall pending, String token) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                String expected = UpdateInstallRules.handoffBindingSha256(
                    token, pending.source, pending.metadata);
                Object stored = prefs.getAll().get(PREF_HANDOFF_BINDING);
                if (stored instanceof String
                        && UpdateInstallRules.digestEquals(expected, (String) stored)) {
                    prefs.edit().remove(PREF_HANDOFF_BINDING).apply();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean storedPendingMatches(PendingInstall expected) {
        try {
            Object raw = prefs.getAll().get(PREF_PENDING_INSTALL);
            if (!(raw instanceof String)) return false;
            PendingInstall stored = parsePendingInstallJson((String) raw);
            return UpdateInstallRules.digestEquals(
                UpdateInstallRules.pendingIdentitySha256(
                    expected.source, expected.metadata),
                UpdateInstallRules.pendingIdentitySha256(
                    stored.source, stored.metadata));
        } catch (Exception error) {
            return false;
        }
    }

    /** Old releases persisted an arbitrary path with no manifest/source/signature binding. */
    private void rejectLegacyPendingPath() {
        try {
            Object raw = prefs.getAll().get(PREF_LEGACY_PENDING_APK);
            if (raw instanceof String) {
                File legacy = new File((String) raw);
                if (isPrivateUpdateFile(legacy)
                        && UpdateInstallRules.isSafeApkName(legacy.getName())) {
                    deletePrivateUpdateFile(legacy);
                }
            }
        } catch (Exception ignored) {
        } finally {
            prefs.edit().remove(PREF_LEGACY_PENDING_APK).apply();
        }
    }

    private void discardPendingInstall(PendingInstall pending) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            if (pending != null && pending.metadata != null) {
                deletePrivateUpdateFileByName(pending.metadata.apkName);
            }
            clearPendingPreferences();
        }
    }

    private void clearPendingPreferences() {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            prefs.edit().remove(PREF_PENDING_INSTALL)
                .remove(PREF_LEGACY_PENDING_APK)
                .remove(PREF_HANDOFF_BINDING)
                .remove(PREF_HANDOFF_OPENED_BINDING)
                .remove(PREF_HANDOFF_IDENTITY)
                .apply();
        }
    }

    private File privateUpdateFile(String name) throws IOException {
        if (!UpdateInstallRules.isSafeApkName(name)) {
            throw new IOException("Unsafe update APK name.");
        }
        File file = new File(updateDir(), name);
        if (!isPrivateUpdateFile(file)) throw new IOException("Unsafe update APK path.");
        return file;
    }

    private boolean isPrivateUpdateFile(File file) {
        if (file == null) return false;
        try {
            String directory = updateDir().getCanonicalPath();
            String candidate = file.getCanonicalPath();
            return candidate.startsWith(directory + File.separator);
        } catch (IOException error) {
            return false;
        }
    }

    private void deletePrivateUpdateFileByName(String name) {
        if (!UpdateInstallRules.isSafeApkName(name)) return;
        try {
            deletePrivateUpdateFile(privateUpdateFile(name));
        } catch (IOException ignored) {
        }
    }

    private void deletePrivateUpdateFile(File file) {
        if (isPrivateUpdateFile(file) && file.isFile()) {
            // A validated APK is read-only; restore owner write permission before deletion.
            file.setWritable(true, true);
            file.delete();
        }
    }

    /**
     * Revalidates the exact descriptor that the provider will return. Opening the descriptor
     * before hashing pins the inode, so a pathname unlink/rename cannot swap the bytes after the
     * manager's earlier path validation.
     */
    static void validateProviderHandoff(Context context, PendingInstall pending, File apk,
                                        ParcelFileDescriptor descriptor) throws Exception {
        if (context == null || remoteSideEffectBlockingStatePresent(context)
                || pending == null || pending.source == null
                || pending.metadata == null || descriptor == null
                || !pending.metadata.matchesSource(pending.source)
                || !pending.metadata.apkName.equals(apk == null ? "" : apk.getName())) {
            throw new IOException("Provider pending update binding is invalid.");
        }
        if (!sourceBindingStillCurrent(context, pending.source)) {
            throw new IOException("Update source changed before provider handoff.");
        }
        FileDescriptor fd = descriptor.getFileDescriptor();
        if (fd == null || !fd.valid()) {
            throw new IOException("Provider update descriptor is invalid.");
        }
        StructStat before = Os.fstat(fd);
        if (before.st_size != pending.metadata.apkLength
                || !statModifiedMatchesPending(
                    before, pending.metadata.apkLastModified)
                || (before.st_mode & 0222) != 0) {
            throw new IOException("Provider update inode metadata changed.");
        }

        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageArchiveInfo(
            apk.getAbsolutePath(), signingFlags());
        if (info == null) throw new IOException("Provider update is not a valid APK.");
        if (!pending.metadata.packageName.equals(info.packageName)
                || !context.getPackageName().equals(info.packageName)) {
            throw new IOException("Provider update package mismatch.");
        }
        long candidateVersion = versionCode(info);
        long installedVersion = versionCode(manager.getPackageInfo(
            context.getPackageName(), signingFlags()));
        if (candidateVersion <= installedVersion
                || candidateVersion != pending.metadata.versionCode) {
            throw new IOException("Provider update version mismatch.");
        }
        String candidateVersionName =
            info.versionName == null ? "" : info.versionName.trim();
        if (!pending.metadata.versionName.equals(candidateVersionName)) {
            throw new IOException("Provider update version name mismatch.");
        }
        Set<String> installedCurrent = currentSignerDigests(manager.getPackageInfo(
            context.getPackageName(), signingFlags()));
        Set<String> candidateHistory = signingHistoryDigests(info);
        if (!UpdateInstallRules.hasSignerContinuity(installedCurrent, candidateHistory)) {
            throw new IOException("Provider update signer continuity failed.");
        }
        String candidateSignerSet = signerSetSha256(candidateHistory);
        if (!UpdateInstallRules.digestEquals(
                pending.metadata.signerSetSha256, candidateSignerSet)) {
            throw new IOException("Provider update signer set mismatch.");
        }

        String actualSha256 = sha256(fd, pending.metadata.apkLength);
        StructStat after = Os.fstat(fd);
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino
                || before.st_size != after.st_size
                || before.st_mode != after.st_mode
                || (after.st_mode & 0222) != 0
                || !sameStatModified(before, after)
                || !statModifiedMatchesPending(
                    after, pending.metadata.apkLastModified)
                || !UpdateInstallRules.digestEquals(
                    pending.metadata.apkSha256, actualSha256)
                || !pending.metadata.matchesValidated(
                    apk.getName(), pending.metadata.manifestSha256, actualSha256,
                    info.packageName, candidateVersion, candidateVersionName,
                    candidateSignerSet, after.st_size,
                    pending.metadata.apkLastModified)
                || remoteSideEffectBlockingStatePresent(context)
                || !sourceBindingStillCurrent(context, pending.source)) {
            throw new IOException("Provider update descriptor no longer matches pending metadata.");
        }
    }

    /**
     * StructStat only exposes nanosecond mtime on API 27+. On API 23-26, compare the
     * descriptor's second-resolution mtime to the second containing File.lastModified().
     * This timestamp is only an additional mutation guard: the handoff still pins and
     * verifies the same device/inode, size, read-only mode and complete SHA-256 digest.
     */
    private static boolean statModifiedMatchesPending(StructStat stat,
                                                      long expectedMillis)
            throws IOException {
        if (stat == null || expectedMillis <= 0L) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return statModifiedMillisApi27(stat) == expectedMillis;
        }
        return stat.st_mtime == expectedMillis / 1000L;
    }

    private static boolean sameStatModified(StructStat before, StructStat after)
            throws IOException {
        if (before == null || after == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return statModifiedMillisApi27(before) == statModifiedMillisApi27(after);
        }
        return before.st_mtime == after.st_mtime;
    }

    @TargetApi(Build.VERSION_CODES.O_MR1)
    private static long statModifiedMillisApi27(StructStat stat) throws IOException {
        try {
            return Math.addExact(Math.multiplyExact(stat.st_mtim.tv_sec, 1000L),
                stat.st_mtim.tv_nsec / 1_000_000L);
        } catch (ArithmeticException overflow) {
            throw new IOException("Provider update timestamp is invalid.", overflow);
        }
    }

    private static String sha256(FileDescriptor fd, long expectedLength) throws Exception {
        if (expectedLength <= 0L) throw new IOException("Provider update length is invalid.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long offset = 0L;
        while (offset < expectedLength) {
            int wanted = (int) Math.min(buffer.length, expectedLength - offset);
            int read = Os.pread(fd, buffer, 0, wanted, offset);
            if (read <= 0) throw new IOException("Provider update descriptor was truncated.");
            digest.update(buffer, 0, read);
            offset += read;
        }
        if (Os.pread(fd, buffer, 0, 1, offset) != 0) {
            throw new IOException("Provider update descriptor grew during validation.");
        }
        return hex(digest.digest());
    }

    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static Set<String> currentSignerDigests(PackageInfo info) throws Exception {
        if (Build.VERSION.SDK_INT >= 28 && info != null && info.signingInfo != null) {
            return signatureDigests(info.signingInfo.getApkContentsSigners());
        }
        return signatureDigests(info == null ? null : info.signatures);
    }

    private static Set<String> signingHistoryDigests(PackageInfo info) throws Exception {
        if (Build.VERSION.SDK_INT >= 28 && info != null && info.signingInfo != null) {
            SigningInfo signing = info.signingInfo;
            Signature[] signatures = signing.hasPastSigningCertificates()
                ? signing.getSigningCertificateHistory() : signing.getApkContentsSigners();
            return signatureDigests(signatures);
        }
        return signatureDigests(info == null ? null : info.signatures);
    }

    private static Set<String> signatureDigests(Signature[] signatures) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        if (signatures == null) return out;
        for (Signature signature : signatures) {
            if (signature != null) out.add(sha256(signature.toByteArray()));
        }
        return out;
    }

    private static String signerSetSha256(Set<String> digests) throws IOException {
        if (digests == null || digests.isEmpty()) throw new IOException("APK signer is missing.");
        List<String> ordered = new ArrayList<>(digests);
        Collections.sort(ordered);
        StringBuilder canonical = new StringBuilder("autoform-kit/apk-signers/v1\n");
        for (String digest : ordered) {
            canonical.append(digest.length()).append(':').append(digest).append(';');
        }
        return UpdateInstallRules.sha256(canonical.toString());
    }

    private void openInstallSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())
            );
        } else {
            intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException exc) {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private interface DownloadProgress {
        void onProgress(long done, long total) throws Exception;
    }

    private void downloadAsset(String url, File outputFile, DownloadProgress progress) throws Exception {
        File dir = outputFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create update directory.");
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Cannot replace previous update file.");
        }
        HttpURLConnection conn = openConnection(url, "application/octet-stream");
        try (InputStream input = responseStream(conn); FileOutputStream output = new FileOutputStream(outputFile)) {
            // openConnection 已解析完重定向，这里是终点响应的长度；拿不到则 -1（走不定长进度）。
            // 不用 getContentLengthLong()——它要 API 24，而 minSdk 是 23。
            long total = -1;
            try {
                total = Long.parseLong(conn.getHeaderField("Content-Length"));
            } catch (Exception ignored) {
            }
            long done = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                done += read;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally {
            conn.disconnect();
        }
    }

    private String getText(String url, String accept) throws Exception {
        HttpURLConnection conn = openConnection(url, accept);
        try (InputStream input = responseStream(conn)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url, String accept) throws Exception {
        URL current = requireHttps(new URL(url));
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", accept);
            conn.setRequestProperty("User-Agent", "AutoFormKit");
            // Public repo → anonymous fetch, no Authorization header.
            if ("api.github.com".equalsIgnoreCase(current.getHost())) {
                conn.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without location: " + code);
                }
                current = requireHttps(new URL(current, location));
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private URL requireHttps(URL url) throws IOException {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Update transport must use HTTPS.");
        }
        return url;
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

    private static String readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        }
    }

    private File updateDir() {
        return new File(activity.getFilesDir(), "updates");
    }

    private long currentVersionCode() throws PackageManager.NameNotFoundException {
        return versionCode(activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0));
    }

    private String currentVersionName() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName + " (" + versionCode(info) + ")";
        } catch (Exception exc) {
            return "unknown";
        }
    }

    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(value == null ? new byte[0] : value);
        return hex(digest);
    }

    private static String hex(byte[] value) {
        byte[] safe = value == null ? new byte[0] : value;
        StringBuilder builder = new StringBuilder(safe.length * 2);
        for (byte b : safe) {
            builder.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static final class Config {
        boolean panelReady;
        String connectionNamespace;
        String panelPairSha256;
        boolean enabled;
        String channel;
        String owner;
        String repo;
        String manifestAsset;
        String releaseTag;
        UpdateInstallRules.SourceBinding source;
    }

    private static final class UpdateInfo {
        Config config;
        UpdateInstallRules.SourceBinding source;
        long versionCode;
        String versionName;
        String packageName;
        String notes;
        String apkAsset;
        String apkUrl;
        String sha256;
        String manifestSha256;
    }

    static final class PendingInstall {
        final UpdateInstallRules.SourceBinding source;
        final UpdateInstallRules.PendingMetadata metadata;

        PendingInstall(UpdateInstallRules.SourceBinding source,
                       UpdateInstallRules.PendingMetadata metadata) {
            this.source = source;
            this.metadata = metadata;
        }
    }

    private static final class ValidatedApk {
        final String apkSha256;
        final String packageName;
        final long versionCode;
        final String versionName;
        final String signerSetSha256;
        final long apkLength;
        final long apkLastModified;

        ValidatedApk(String apkSha256, String packageName, long versionCode,
                     String versionName, String signerSetSha256, long apkLength,
                     long apkLastModified) {
            this.apkSha256 = apkSha256;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.signerSetSha256 = signerSetSha256;
            this.apkLength = apkLength;
            this.apkLastModified = apkLastModified;
        }
    }
}
