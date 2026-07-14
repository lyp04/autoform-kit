package com.autoformkit.app;

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
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Locale;

final class UpdateManager {
    private static final String CONFIG_ASSET = "update-config.json";
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String PREFS = "update_state";
    private static final String PREF_PENDING_APK = "pending_apk_path";
    private static final String PREF_UPDATE_CHANNEL = "update_channel";
    private static final String PREF_LAST_CHECK_MS = "last_check_ms";
    private static final String CHANNEL_STABLE = "stable";
    private static final String CHANNEL_BETA = "beta";
    /** Foreground re-checks no more than once per 10 minutes so a quick task-switch
     *  doesn't replay the network probe. */
    private static final long FOREGROUND_CHECK_INTERVAL_MS = 10 * 60 * 1000L;

    private final Activity activity;
    private final SharedPreferences prefs;
    private boolean checkedThisProcess = false;

    UpdateManager(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    void checkOnStartup() {
        check(false);
    }

    /** Called from {@link Activity#onResume()}; throttled by {@link #FOREGROUND_CHECK_INTERVAL_MS}. */
    void checkOnForeground() {
        long now = System.currentTimeMillis();
        long last = prefs.getLong(PREF_LAST_CHECK_MS, 0L);
        if (now - last < FOREGROUND_CHECK_INTERVAL_MS) return;
        check(true);
    }

    void checkNow() {
        check(true);
    }

    private void check(boolean force) {
        if (!force && checkedThisProcess) return;
        checkedThisProcess = true;
        prefs.edit().putLong(PREF_LAST_CHECK_MS, System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                Config config = loadConfig();
                if (!config.enabled) return;
                UpdateInfo update = findUpdate(config);
                if (update == null) return;
                showUpdateDialog(update);
            } catch (Exception exc) {
                // Update checks must never block the form workflow.
            }
        }, "update-check").start();
    }

    static String currentChannel(Activity activity) {
        String channel = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(PREF_UPDATE_CHANNEL, CHANNEL_STABLE);
        return CHANNEL_BETA.equals(channel) ? CHANNEL_BETA : CHANNEL_STABLE;
    }

    static String toggleChannel(Activity activity) {
        String next = CHANNEL_BETA.equals(currentChannel(activity)) ? CHANNEL_STABLE : CHANNEL_BETA;
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit()
            .putString(PREF_UPDATE_CHANNEL, next)
            .apply();
        return next;
    }

    void resumePendingInstall() {
        String path = prefs.getString(PREF_PENDING_APK, "");
        if (path == null || path.isEmpty()) return;
        File apk = new File(path);
        if (!apk.exists()) {
            prefs.edit().remove(PREF_PENDING_APK).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }
        requestInstall(apk);
    }

    private Config loadConfig() throws Exception {
        JSONObject json = new JSONObject(readAsset(CONFIG_ASSET));
        Config config = new Config();
        config.channel = currentChannel(activity);
        config.enabled = json.optBoolean("enabled", false);
        config.owner = json.optString("owner", "").trim();
        config.repo = json.optString("repo", "").trim();
        config.manifestAsset = json.optString("manifestAsset", "update.json").trim();
        if (config.manifestAsset.isEmpty()) config.manifestAsset = "update.json";
        config.releaseTag = json.optString("releaseTag", "").trim();
        if (CHANNEL_BETA.equals(config.channel)) {
            config.owner = firstNonEmpty(json.optString("betaOwner", "").trim(), config.owner);
            config.repo = firstNonEmpty(json.optString("betaRepo", "").trim(), config.repo);
            config.manifestAsset = firstNonEmpty(json.optString("betaManifestAsset", "").trim(), config.manifestAsset);
            config.releaseTag = firstNonEmpty(json.optString("betaReleaseTag", "").trim(), CHANNEL_BETA);
        } else {
            config.manifestAsset = firstNonEmpty(json.optString("stableManifestAsset", "").trim(), config.manifestAsset);
            config.releaseTag = firstNonEmpty(json.optString("stableReleaseTag", "").trim(), config.releaseTag);
        }
        JSONObject channels = json.optJSONObject("channels");
        JSONObject channelConfig = channels == null ? null : channels.optJSONObject(config.channel);
        if (channelConfig != null) {
            applyChannelConfig(config, channelConfig);
        }
        // 开源:更新源(owner/repo)不写死在 app 里,改从面板 /api/config 拿(AppConfig 已缓存到本地)。
        // 自更新走 PUBLIC 仓库,匿名拉取,不需要 token。资产为空 + 面板没下发 → owner/repo 为空 → 下面判空自动关掉更新(不影响其它)。
        JSONObject panelCfg = AppConfig.load(activity);
        if (panelCfg != null) {
            config.owner = firstNonEmpty(config.owner, panelCfg.optString("updateOwner", "").trim());
            config.repo = firstNonEmpty(config.repo, panelCfg.optString("updateRepo", "").trim());
        }
        if (config.owner.isEmpty() || config.repo.isEmpty()) {
            config.enabled = false;
        }
        return config;
    }

    private void applyChannelConfig(Config config, JSONObject json) {
        if (json.has("enabled")) config.enabled = json.optBoolean("enabled", config.enabled);
        config.owner = firstNonEmpty(json.optString("owner", "").trim(), config.owner);
        config.repo = firstNonEmpty(json.optString("repo", "").trim(), config.repo);
        config.manifestAsset = firstNonEmpty(json.optString("manifestAsset", "").trim(), config.manifestAsset);
        config.releaseTag = firstNonEmpty(json.optString("releaseTag", "").trim(), config.releaseTag);
    }

    private UpdateInfo findUpdate(Config config) throws Exception {
        String releaseUrl = releaseUrl(config);
        JSONObject release = new JSONObject(getText(releaseUrl, "application/vnd.github+json"));
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;

        JSONObject manifestAsset = findAsset(assets, config.manifestAsset);
        if (manifestAsset == null) return null;
        JSONObject manifest = new JSONObject(getText(manifestAsset.getString("url"), "application/octet-stream"));

        String packageName = manifest.optString("packageName", "");
        if (!activity.getPackageName().equals(packageName)) {
            throw new IOException("Update package mismatch: " + packageName);
        }
        long remoteVersion = manifest.optLong("versionCode", 0);
        long currentVersion = currentVersionCode();
        if (remoteVersion <= currentVersion) return null;

        String apkAssetName = manifest.optString("apkAsset", "");
        if (apkAssetName.isEmpty()) throw new IOException("update.json is missing apkAsset");
        JSONObject apkAsset = findAsset(assets, apkAssetName);
        if (apkAsset == null) throw new IOException("Release asset not found: " + apkAssetName);

        UpdateInfo update = new UpdateInfo();
        update.config = config;
        update.versionCode = remoteVersion;
        update.versionName = manifest.optString("versionName", String.valueOf(remoteVersion));
        update.notes = manifest.optString("notes", "");
        update.apkAsset = apkAssetName;
        update.apkUrl = apkAsset.getString("url");
        update.sha256 = manifest.optString("sha256", "").toLowerCase(Locale.US).replace("sha256:", "").trim();
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

    private void showUpdateDialog(UpdateInfo update) {
        activity.runOnUiThread(() -> {
            if (!activityAlive()) return;
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
                .setPositiveButton(s("下载并安装", "Download & install", "Descargar e instalar"), (dialog, which) -> downloadAndInstall(update))
                .show();
        });
    }

    private String channelLabel(String channel) {
        return CHANNEL_BETA.equals(channel) ? "Beta" : s("正式版", "Stable", "Estable");
    }

    private void downloadAndInstall(UpdateInfo update) {
        Toast.makeText(activity, s("开始下载更新...", "Downloading update...", "Descargando actualización..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File apk = new File(updateDir(), update.apkAsset);
                downloadAsset(update.apkUrl, apk, (done, total) -> notifyDownloadProgress(update, done, total));
                notifyDownloadDone(update);
                validateDownload(update, apk);
                activity.runOnUiThread(() -> requestInstall(apk));
            } catch (Exception exc) {
                cancelDownloadNotification();
                activity.runOnUiThread(() -> {
                    if (!activityAlive()) return;
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

    private void validateDownload(UpdateInfo update, File apk) throws Exception {
        if (!update.sha256.isEmpty()) {
            String actual = sha256(apk);
            if (!update.sha256.equals(actual)) {
                throw new IOException("APK SHA-256 mismatch.\nExpected: " + update.sha256 + "\nActual: " + actual);
            }
        }
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null) throw new IOException("Downloaded file is not a valid APK.");
        if (!activity.getPackageName().equals(info.packageName)) {
            throw new IOException("Downloaded APK package mismatch: " + info.packageName);
        }
        long downloadedVersion = versionCode(info);
        if (downloadedVersion <= currentVersionCode()) {
            throw new IOException("Downloaded APK version is not newer.");
        }
        if (downloadedVersion != update.versionCode) {
            throw new IOException("Downloaded APK version does not match update.json.");
        }
    }

    private void requestInstall(File apk) {
        if (!activityAlive()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            prefs.edit().putString(PREF_PENDING_APK, apk.getAbsolutePath()).apply();
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
        prefs.edit().remove(PREF_PENDING_APK).apply();
        Uri uri = UpdateApkProvider.uriForFile(activity, apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, APK_MIME);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException exc) {
            new AlertDialog.Builder(activity)
                .setTitle(s("无法安装", "Cannot install", "No se puede instalar"))
                .setMessage(s("系统没有可用的 APK 安装器。",
                        "No APK installer is available on this device.",
                        "No hay instalador de APK disponible en el dispositivo."))
                .setPositiveButton("OK", null)
                .show();
        }
    }

    private void openInstallSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException exc) {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    private interface DownloadProgress {
        void onProgress(long done, long total);
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
        URL current = new URL(url);
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

    private String readAsset(String name) throws IOException {
        try (InputStream input = activity.getAssets().open(name)) {
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

    private long versionCode(PackageInfo info) {
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

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static final class Config {
        boolean enabled;
        String channel;
        String owner;
        String repo;
        String manifestAsset;
        String releaseTag;
    }

    private static final class UpdateInfo {
        Config config;
        long versionCode;
        String versionName;
        String notes;
        String apkAsset;
        String apkUrl;
        String sha256;
    }
}
