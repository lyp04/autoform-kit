package com.autoformkit.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Single source of truth for "which form profiles are active right now".
 *
 * <p>Profiles ship baked into the APK ({@code assets/form-profiles.seed.json}) but can be
 * superseded at runtime by a remote catalog that {@link FormCatalogManager} downloads to
 * {@code filesDir/form-catalog/form-profiles.json}. Both {@link MainActivity} (the in-app
 * workflow) and {@link SessionAuthProvider} (the cross-app ContentProvider) must resolve profiles
 * through here, otherwise the two could disagree about what a given profile id means.
 *
 * <p>An unconfigured install renders the bundled fictional seed as a disabled preview. Once a
 * Panel is configured, consumers receive only a current-connection cache whose revision matches
 * the usable config cache; absence, corruption, or a half-published pair fails closed. A cache
 * written by a newer app (higher {@code schemaVersion} than this build understands) is ignored.
 */
final class FormCatalog {
    static final String SEED_ASSET = "form-profiles.seed.json";
    static final String CACHE_DIR = "form-catalog";
    static final String CACHE_FILE = "form-profiles.json";
    static final String CANDIDATE_FILE = "form-profiles.candidate.json";

    /**
     * Form-profile schema shape this build can render. Bump this (and the catalog's
     * {@code schemaVersion}) when the form engine learns a new shape so that older installs
     * decline a catalog they couldn't render correctly.
     *
     * <p>v1: basic uploads and result mapping. v2: optional result mapping and
     * {@code photoSlots} (N upload boxes, each with min/max photos). v3: optional
     * scanner {@code ocrPositionRules} for profile-owned positional OCR constraints.
     */
    static final int SUPPORTED_SCHEMA_VERSION = 3;

    private FormCatalog() {}

    /** Profiles and settings parsed from one exact, current-connection-bound cache file. */
    static final class BoundSnapshot {
        final int version;
        final JSONArray profiles;
        final JSONObject settings;

        BoundSnapshot(int version, JSONArray profiles, JSONObject settings) {
            this.version = version;
            this.profiles = profiles;
            this.settings = settings;
        }
    }

    /**
     * Exact identity of the tracked fictional seed used for local-only preview work.
     *
     * <p>This is intentionally not a {@link BoundSnapshot}: it has no Panel config and can never
     * authorize a remote operation. Its positive revision and digest exist only so local drafts,
     * scanner returns and camera returns can use the same strict identity rules as a real form.
     */
    static final class PreviewSnapshot {
        final int version;
        final JSONArray profiles;
        final JSONObject settings;
        final String pairSha256;

        private PreviewSnapshot(int version, JSONArray profiles, JSONObject settings,
                                String pairSha256) {
            this.version = version;
            this.profiles = profiles;
            this.settings = settings;
            this.pairSha256 = pairSha256;
        }
    }

    static File cacheDir(Context context) {
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
        // AtomicFile.openRead() performs .bak recovery. A base-file precheck would bypass that
        // recovery in the exact crash window this storage is meant to survive.
        return AtomicCacheFile.readUtf8(cacheFile(context));
    }

    /**
     * Active profiles array: a complete config/catalog pair for a configured Panel, otherwise the
     * bundled disabled preview for an unconfigured install. A configured but incomplete/mismatched
     * pair throws instead of exposing one independently refreshed half.
     */
    static JSONArray loadProfiles(Context context) throws IOException {
        BoundSnapshot cached = loadCompatibleBoundSnapshot(context);
        if (cached != null) {
            return cached.profiles;
        }
        if (!AppConfig.panelBase(context).isEmpty()) {
            throw new IOException("Configured Panel catalog is not synchronized");
        }
        return loadBundledPreviewProfiles(context);
    }

    /** Explicit, non-operational fallback used only to render an unconfigured/locked Settings UI. */
    static JSONArray loadBundledPreviewProfiles(Context context) throws IOException {
        return loadBundledPreviewSnapshot(context).profiles;
    }

    /** Parses one immutable asset root and derives a stable local-only binding from that root. */
    static PreviewSnapshot loadBundledPreviewSnapshot(Context context) throws IOException {
        try {
            JSONObject root = new JSONObject(readAsset(context, SEED_ASSET));
            int version = catalogVersion(root);
            JSONArray profiles = root.optJSONArray("profiles");
            JSONObject settings = root.optJSONObject("settings");
            String pairSha256 = MainDraftSnapshotRules.semanticSha256(root);
            if (version <= 0 || profiles == null || profiles.length() == 0
                    || settings == null || !settings.optBoolean("sampleCatalog", false)
                    || pairSha256.isEmpty()) {
                throw new IOException("Seed preview identity is invalid");
            }
            return new PreviewSnapshot(version, new JSONArray(profiles.toString()),
                new JSONObject(settings.toString()), pairSha256);
        } catch (IOException io) {
            throw io;
        } catch (Exception parse) {
            throw new IOException("Seed profiles unreadable: " + parse.getMessage());
        }
    }

    /**
     * Current connection's cached catalog, parsed as one atomic snapshot, or null when absent,
     * unbound, corrupt, schema-too-new, or empty. Callers that enforce the Panel synchronization
     * gate use this method instead of independently falling back profiles/settings to the seed.
     */
    static BoundSnapshot loadBoundSnapshot(Context context) {
        String connection = AppConfig.connectionNamespaceId(
            AppConfig.panelBase(context), AppConfig.catalogKey(context));
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(context, connection);
        return pair == null ? null : pair.catalog;
    }

    /** True only when the on-disk catalog is usable for the currently selected Panel connection. */
    static boolean hasUsableBoundCache(Context context) {
        return loadBoundSnapshot(context) != null;
    }

    /** Complete current-connection pair for consumers outside MainActivity's active snapshot. */
    private static BoundSnapshot loadCompatibleBoundSnapshot(Context context) {
        String connection = AppConfig.connectionNamespaceId(
            AppConfig.panelBase(context), AppConfig.catalogKey(context));
        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit(
                context, connection);
        return pair == null ? null : pair.catalog;
    }

    /** Strict positive root revision, or zero for missing/coerced/out-of-range values. */
    static int catalogVersion(JSONObject root) {
        if (root == null) return 0;
        Object raw = root.opt("version");
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) return 0;
        long value = ((Number) raw).longValue();
        return value > 0L && value <= Integer.MAX_VALUE ? (int) value : 0;
    }

    /**
     * Global catalog settings — the {@code settings} object that rides alongside {@code profiles}
     * in the same catalog root (for example {@code {"updatedAt":"…"}}). The
     * configured Panel returns settings only from a complete matching config/catalog pair. An
     * unconfigured install returns bundled preview settings. Never throws.
     */
    public static JSONObject loadSettings(Context context) {
        BoundSnapshot cached = loadCompatibleBoundSnapshot(context);
        if (cached != null) return cached.settings;
        if (!AppConfig.panelBase(context).isEmpty()) return null;
        return loadBundledPreviewSettings(context);
    }

    static JSONObject loadBundledPreviewSettings(Context context) {
        try {
            return loadBundledPreviewSnapshot(context).settings;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return readAll(input);
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }
}
