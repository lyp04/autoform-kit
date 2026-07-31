package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Locale;

/** Pure proof checks for binding a cache written by a pre-binding App release. */
final class LegacyPanelCacheMigrationRules {
    static final String PROOF_FIELD = "_autoFormKitLegacyCacheProof";

    private LegacyPanelCacheMigrationRules() {}

    /**
     * A legacy pair is eligible only when the Panel cached an explicit proof for the exact saved
     * address/key and the proof hashes the exact catalog bytes beside it. Unknown, partial, stale,
     * or cross-Panel caches fail closed and must use the normal online synchronization path.
     */
    static boolean canMigrate(String panelBase, String catalogKey,
                              JSONObject config, String catalogText) {
        if (cleanBase(panelBase).isEmpty() || config == null
                || catalogText == null || catalogText.isEmpty()) return false;
        try {
            JSONObject proof = config.optJSONObject(PROOF_FIELD);
            if (proof == null || positiveInteger(proof, "version") != 1) return false;
            if (!cleanBase(panelBase).equals(cleanBase(
                    proof.optString("panelBase", "")))) return false;
            if (!validDigest(proof.optString("keySha256", ""))
                    || !proof.optString("keySha256", "").equals(
                        sha256(catalogKey == null ? "" : catalogKey))) return false;
            if (!validDigest(proof.optString("catalogSha256", ""))
                    || !proof.optString("catalogSha256", "").equals(
                        sha256(catalogText))) return false;

            JSONObject catalog = new JSONObject(catalogText);
            int configVersion = positiveInteger(config, "catalogVersion");
            int proofVersion = positiveInteger(proof, "catalogVersion");
            int catalogVersion = positiveInteger(catalog, "version");
            int schemaVersion = positiveInteger(catalog, "schemaVersion");
            JSONArray profiles = catalog.optJSONArray("profiles");
            return configVersion > 0
                && configVersion == proofVersion
                && configVersion == catalogVersion
                && schemaVersion > 0
                && schemaVersion <= FormCatalog.SUPPORTED_SCHEMA_VERSION
                && profiles != null
                && profiles.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            return "";
        }
    }

    private static int positiveInteger(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) return 0;
        long number = ((Number) raw).longValue();
        return number > 0L && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String cleanBase(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
