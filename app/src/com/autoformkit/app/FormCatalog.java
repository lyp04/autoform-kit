package com.autoformkit.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
 * <p>Resolution order: a valid, schema-compatible cached catalog wins; otherwise the bundled
 * seed. A cache written by a newer app (higher {@code schemaVersion} than this build understands)
 * is ignored, so a downgraded APK never tries to render a form shape it predates.
 */
final class FormCatalog {
    static final String SEED_ASSET = "form-profiles.seed.json";
    static final String CACHE_DIR = "form-catalog";
    static final String CACHE_FILE = "form-profiles.json";

    /**
     * Form-profile schema shape this build can render. Bump this (and the catalog's
     * {@code schemaVersion}) when the form engine learns a new shape so that older installs
     * decline a catalog they couldn't render correctly.
     *
     * <p>v1: legacy front/back uploads + A/B/C grade. v2: optional grade (a profile may omit
     * {@code gradeMap}) and {@code photoSlots} (N upload boxes, each with min/max photos).
     */
    static final int SUPPORTED_SCHEMA_VERSION = 2;

    private FormCatalog() {}

    static File cacheDir(Context context) {
        return new File(context.getFilesDir(), CACHE_DIR);
    }

    static File cacheFile(Context context) {
        return new File(cacheDir(context), CACHE_FILE);
    }

    /**
     * Active profiles array: the downloaded catalog when present, valid and schema-compatible,
     * otherwise the bundled seed. Throws only when even the seed can't be read/parsed — callers
     * treat that as fatal ({@link MainActivity}) or as "no profile" ({@link SessionAuthProvider}),
     * matching the behavior from before the remote catalog existed.
     */
    static JSONArray loadProfiles(Context context) throws IOException {
        JSONArray cached = tryLoadCachedProfiles(context);
        if (cached != null) {
            return cached;
        }
        try {
            return new JSONObject(readAsset(context, SEED_ASSET)).getJSONArray("profiles");
        } catch (IOException io) {
            throw io;
        } catch (Exception parse) {
            throw new IOException("Seed profiles unreadable: " + parse.getMessage());
        }
    }

    /** Cached catalog's profiles, or null if absent / corrupt / schema-too-new / empty. */
    private static JSONArray tryLoadCachedProfiles(Context context) {
        File file = cacheFile(context);
        if (!file.exists() || file.length() == 0L) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readFile(file));
            if (root.optInt("schemaVersion", 1) > SUPPORTED_SCHEMA_VERSION) {
                return null; // written by a newer app; this build can't render it.
            }
            JSONArray profiles = root.optJSONArray("profiles");
            if (profiles == null || profiles.length() == 0) {
                return null;
            }
            return profiles;
        } catch (Exception corrupt) {
            return null; // any problem with the cache → fall back to the bundled seed.
        }
    }

    /**
     * Global catalog settings — the {@code settings} object that rides alongside {@code profiles}
     * in the same catalog root (e.g. {@code {"notifyWebhook":"https://…","updatedAt":"…"}}). The
     * downloaded catalog wins when present and parseable; otherwise the bundled seed. Returns null
     * when neither carries a {@code settings} block (un-migrated installs), so callers cascade to
     * their own defaults. Never throws — a missing/corrupt catalog degrades to the seed, then null.
     */
    public static JSONObject loadSettings(Context context) {
        File file = cacheFile(context);
        if (file.exists() && file.length() > 0L) {
            try {
                JSONObject root = new JSONObject(readFile(file));
                if (root.optInt("schemaVersion", 1) <= SUPPORTED_SCHEMA_VERSION) {
                    JSONObject settings = root.optJSONObject("settings");
                    if (settings != null) {
                        return settings;
                    }
                }
            } catch (Exception ignored) {
                // fall through to the bundled seed.
            }
        }
        try {
            return new JSONObject(readAsset(context, SEED_ASSET)).optJSONObject("settings");
        } catch (Exception ignored) {
            return null;
        }
    }

    static String readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return readAll(input);
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
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
