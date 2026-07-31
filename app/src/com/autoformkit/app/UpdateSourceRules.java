package com.autoformkit.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Pure compatibility rules for resolving the APK's exact v1 update routes with Panel-owned public
 * repository coordinates. Android storage and network I/O stay in {@link UpdateManager}.
 *
 * <p>{@code updateSource} v1 is intentionally only {@code {version, owner, repo}}. Channel,
 * manifest asset and release tag remain the installed APK/device protocol: no saved preference is
 * stable, only a saved beta value is beta, stable uses its existing latest route and beta uses its
 * existing beta-tag route. A malformed present contract fails closed for update checks without
 * affecting the backend/form config.
 */
final class UpdateSourceRules {
    static final String CHANNEL_STABLE = "stable";
    static final String CHANNEL_BETA = "beta";
    private static final String PANEL_SOURCE_FIELD = "updateSource";
    private static final Set<String> SOURCE_KEYS =
        new HashSet<>(Arrays.asList("version", "owner", "repo"));

    private UpdateSourceRules() {}

    static final class Resolved {
        final boolean enabled;
        final String channel;
        final String owner;
        final String repo;
        final String manifestAsset;
        final String releaseTag;

        private Resolved(boolean enabled, String channel, String owner, String repo,
                         String manifestAsset, String releaseTag) {
            this.enabled = enabled;
            this.channel = channel;
            this.owner = trim(owner);
            this.repo = trim(repo);
            this.manifestAsset = trim(manifestAsset);
            this.releaseTag = trim(releaseTag);
        }
    }

    /** Resolves one exact check while retaining the v1.0.4/v1.0.6 APK channel algorithm. */
    static Resolved resolve(JSONObject apkConfig, JSONObject panelConfig,
                            String devicePreference) {
        if (apkConfig == null) throw new IllegalArgumentException("APK update config is required");
        PanelSource panel = parsePanelSource(panelConfig);
        String channel = deviceChannel(devicePreference);

        boolean enabled = apkConfig.optBoolean("enabled", false);
        String owner = apkConfig.optString("owner", "").trim();
        String repo = apkConfig.optString("repo", "").trim();
        String manifestAsset = apkConfig.optString("manifestAsset", "update.json").trim();
        if (manifestAsset.isEmpty()) manifestAsset = "update.json";
        String releaseTag = apkConfig.optString("releaseTag", "").trim();

        if (CHANNEL_BETA.equals(channel)) {
            owner = firstNonEmpty(apkConfig.optString("betaOwner", "").trim(), owner);
            repo = firstNonEmpty(apkConfig.optString("betaRepo", "").trim(), repo);
            manifestAsset = firstNonEmpty(
                apkConfig.optString("betaManifestAsset", "").trim(), manifestAsset);
            releaseTag = firstNonEmpty(
                apkConfig.optString("betaReleaseTag", "").trim(), CHANNEL_BETA);
        } else {
            manifestAsset = firstNonEmpty(
                apkConfig.optString("stableManifestAsset", "").trim(), manifestAsset);
            releaseTag = firstNonEmpty(
                apkConfig.optString("stableReleaseTag", "").trim(), releaseTag);
        }

        JSONObject apkChannels = apkConfig.optJSONObject("channels");
        JSONObject apkChannel = apkChannels == null ? null : apkChannels.optJSONObject(channel);
        if (apkChannel != null) {
            if (apkChannel.has("enabled")) enabled = apkChannel.optBoolean("enabled", enabled);
            owner = firstNonEmpty(apkChannel.optString("owner", "").trim(), owner);
            repo = firstNonEmpty(apkChannel.optString("repo", "").trim(), repo);
            manifestAsset = firstNonEmpty(
                apkChannel.optString("manifestAsset", "").trim(), manifestAsset);
            releaseTag = firstNonEmpty(
                apkChannel.optString("releaseTag", "").trim(), releaseTag);
        }

        // Only repository coordinates are Panel-owned. Every routing/asset value above remains the
        // exact installed APK behavior for the selected device-local channel.
        owner = firstNonEmpty(panel.owner, owner);
        repo = firstNonEmpty(panel.repo, repo);
        return new Resolved(enabled, channel, owner, repo, manifestAsset, releaseTag);
    }

    /** Old behavior: only exact beta selects beta; missing, stable and unknown values are stable. */
    static String deviceChannel(String value) {
        return CHANNEL_BETA.equals(trim(value)) ? CHANNEL_BETA : CHANNEL_STABLE;
    }

    private static PanelSource parsePanelSource(JSONObject panelConfig) {
        if (panelConfig == null) return PanelSource.empty();
        Object raw = panelConfig.opt(PANEL_SOURCE_FIELD);
        if (raw == null || raw == JSONObject.NULL) {
            return new PanelSource(
                panelConfig.optString("updateOwner", "").trim(),
                panelConfig.optString("updateRepo", "").trim());
        }
        if (!(raw instanceof JSONObject)) {
            throw new IllegalArgumentException("updateSource must be an object");
        }
        JSONObject source = (JSONObject) raw;
        requireOnlyKeys(source);
        Object rawVersion = source.opt("version");
        if (!(rawVersion instanceof Byte || rawVersion instanceof Short
                || rawVersion instanceof Integer || rawVersion instanceof Long)
                || ((Number) rawVersion).longValue() != 1L) {
            throw new IllegalArgumentException("updateSource.version must be integer 1");
        }
        String owner = requiredString(source, "owner", "updateSource.owner");
        String repo = requiredString(source, "repo", "updateSource.repo");
        requireSafeOwner(owner);
        requireSafeRepo(repo);

        // Old Apps always read these flat fields. A v1 source is usable only when both generations
        // receive byte-for-byte identical normalized coordinates.
        Object legacyOwnerRaw = panelConfig.opt("updateOwner");
        Object legacyRepoRaw = panelConfig.opt("updateRepo");
        if (!(legacyOwnerRaw instanceof String) || !(legacyRepoRaw instanceof String)
                || !owner.equals(legacyOwnerRaw) || !repo.equals(legacyRepoRaw)) {
            throw new IllegalArgumentException(
                "updateSource must exactly match updateOwner/updateRepo");
        }
        return new PanelSource(owner, repo);
    }

    private static void requireOnlyKeys(JSONObject value) {
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!SOURCE_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                    "updateSource contains unsupported field " + key);
            }
        }
    }

    private static String requiredString(JSONObject value, String key, String label) {
        Object raw = value.opt(key);
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = ((String) raw).trim();
        if (!normalized.equals(raw)) {
            throw new IllegalArgumentException(label + " must already be normalized");
        }
        return normalized;
    }

    private static void requireSafeOwner(String owner) {
        if (owner.length() > 39 || owner.contains("--")
                || !owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) {
            throw new IllegalArgumentException(
                "updateSource.owner is not a GitHub login segment");
        }
    }

    private static void requireSafeRepo(String repo) {
        if (repo.length() > 100 || repo.contains("..")
                || !repo.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                "updateSource.repo is not a GitHub repository segment");
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class PanelSource {
        final String owner;
        final String repo;

        PanelSource(String owner, String repo) {
            this.owner = owner;
            this.repo = repo;
        }

        static PanelSource empty() {
            return new PanelSource("", "");
        }
    }
}
