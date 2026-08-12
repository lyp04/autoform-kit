package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Pure fail-closed binding rules for the main unfinished-record draft.
 *
 * <p>The Panel remains the only owner of form and backend behavior. This class stores only a
 * device-local fingerprint of the exact Panel semantics under which a queue was captured. It
 * never contains a backend default, deployment value, or field-name guess.
 */
final class MainDraftSnapshotRules {
    static final int DRAFT_VERSION = 3;
    static final int BINDING_VERSION = 1;
    static final int LEGACY_RECEIPT_VERSION = 1;
    static final int LEGACY_COMPATIBILITY_MIN_RELEASE_CODE = 8;
    static final String BINDING_FIELD = "_autoFormKitDraftBinding";
    private static final String CACHE_BINDING_FIELD = "_autoFormKitCache";
    private static final String LEGACY_RECEIPT_PREFIX =
        "legacy_main_draft_migration_receipt_v1_";

    enum RestoreKind {
        EXACT,
        MIGRATE_VERIFIED_LEGACY,
        BLOCKED
    }

    static final class RestoreDecision {
        final RestoreKind kind;
        final String reason;

        private RestoreDecision(RestoreKind kind, String reason) {
            this.kind = kind;
            this.reason = reason == null ? "" : reason;
        }

        boolean allowed() {
            return kind != RestoreKind.BLOCKED;
        }
    }

    static final class Binding {
        final String connectionNamespace;
        final int catalogVersion;
        final String profileId;
        final String semanticsSha256;

        private Binding(String connectionNamespace, int catalogVersion,
                        String profileId, String semanticsSha256) {
            this.connectionNamespace = requiredNamespace(connectionNamespace);
            if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
            this.catalogVersion = catalogVersion;
            this.profileId = requiredText(profileId, "profileId", 256);
            this.semanticsSha256 = requiredDigest(semanticsSha256, "semanticsSha256");
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("version", BINDING_VERSION)
                    .put("connectionNamespace", connectionNamespace)
                    .put("catalogVersion", catalogVersion)
                    .put("profileId", profileId)
                    .put("semanticsSha256", semanticsSha256);
            } catch (Exception impossible) {
                throw invalid("cannot serialize draft binding");
            }
        }

        boolean sameAs(Binding other) {
            return other != null
                && connectionNamespace.equals(other.connectionNamespace)
                && catalogVersion == other.catalogVersion
                && profileId.equals(other.profileId)
                && semanticsSha256.equals(other.semanticsSha256);
        }
    }

    private MainDraftSnapshotRules() {}

    /** Builds the identity of one immutable active Panel config/catalog/profile snapshot. */
    static Binding currentBinding(String connectionNamespace, int catalogVersion,
                                  String profileId, JSONObject catalogProfile,
                                  JSONObject appConfig, JSONObject catalogSettings) {
        String id = requiredText(profileId, "profileId", 256);
        if (catalogProfile == null || !id.equals(catalogProfile.optString("id", ""))) {
            throw invalid("catalog profile does not match profileId");
        }
        JSONObject adapter = appConfig == null ? null
            : appConfig.optJSONObject("backendAdapter");
        if (adapter == null && catalogSettings != null) {
            adapter = catalogSettings.optJSONObject("backendAdapter");
        }
        try {
            // The complete immutable profile covers template ids, payload fields, scanners,
            // photos, grade/material maps and workflow policies. The resolved adapter plus its
            // legacy endpoint/header inputs covers every upload and POST target/shape.
            JSONObject semantics = new JSONObject()
                .put("profile", new JSONObject(catalogProfile.toString()))
                .put("backendAdapter", adapter == null ? JSONObject.NULL
                    : new JSONObject(adapter.toString()))
                .put("endpoints", appConfig == null
                    || appConfig.optJSONObject("endpoints") == null
                        ? JSONObject.NULL
                        : new JSONObject(appConfig.optJSONObject("endpoints").toString()))
                .put("webOrigin", appConfig == null ? ""
                    : appConfig.optString("webOrigin", "").trim())
                .put("webReferer", appConfig == null ? ""
                    : appConfig.optString("webReferer", "").trim());
            return new Binding(connectionNamespace, catalogVersion, id,
                sha256(canonicalJson(semantics)));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("cannot fingerprint draft semantics");
        }
    }

    /**
     * Validates a stored draft without mutating it. A malformed new binding can never fall back to
     * legacy migration. Unbound v1/v2 data needs an exact persisted prewarm receipt.
     */
    static RestoreDecision evaluate(JSONObject draft, Binding current,
                                    JSONObject legacyReceipt, int currentReleaseCode,
                                    String currentPairSha256) {
        if (draft == null || current == null) return blocked("draft or current binding missing");
        Object versionRaw = draft.opt("version");
        int draftVersion = exactInteger(versionRaw);
        JSONObject storedJson = draft.optJSONObject(BINDING_FIELD);
        if (storedJson != null || draft.has(BINDING_FIELD) || draftVersion >= DRAFT_VERSION) {
            try {
                if (draftVersion != DRAFT_VERSION || storedJson == null) {
                    return blocked("unsupported bound draft version");
                }
                Binding stored = parseBinding(storedJson);
                return stored.sameAs(current)
                    ? new RestoreDecision(RestoreKind.EXACT, "")
                    : blocked("draft binding does not match active Panel semantics");
            } catch (Exception error) {
                return blocked("draft binding is malformed");
            }
        }
        if (draftVersion != 1 && draftVersion != 2) {
            return blocked("unsupported legacy draft version");
        }
        if (!legacyReceiptMatches(legacyReceipt, current, currentReleaseCode,
                currentPairSha256)) {
            return blocked("verified legacy migration receipt missing or mismatched");
        }
        return new RestoreDecision(RestoreKind.MIGRATE_VERIFIED_LEGACY, "");
    }

    static JSONObject bindVerifiedLegacy(JSONObject legacy, Binding current) {
        if (legacy == null || current == null) throw invalid("legacy draft and binding required");
        int version = exactInteger(legacy.opt("version"));
        if ((version != 1 && version != 2) || legacy.has(BINDING_FIELD)) {
            throw invalid("draft is not an unbound supported legacy draft");
        }
        try {
            return new JSONObject(legacy.toString())
                .put("version", DRAFT_VERSION)
                .put(BINDING_FIELD, current.toJson());
        } catch (Exception error) {
            throw invalid("cannot bind legacy draft");
        }
    }

    /**
     * Allows an unfinished queue to follow a strictly newer revision published by the same Panel
     * for the same profile. The caller must still be at a local safe boundary with no camera,
     * upload, submission, print, or previous-step journal in flight.
     *
     * <p>This is deliberately narrower than {@link #belongsToConnection}: malformed bindings,
     * cross-Panel moves, profile changes, same-version semantic changes and rollbacks remain
     * blocked. The draft bytes and photo paths are preserved; only their immutable binding is
     * replaced after the newer complete pair is active.</p>
     */
    static boolean canRebindToNewerSameConnection(JSONObject draft, Binding target) {
        if (draft == null || target == null
                || exactInteger(draft.opt("version")) != DRAFT_VERSION) return false;
        JSONObject storedJson = draft.optJSONObject(BINDING_FIELD);
        if (storedJson == null) return false;
        try {
            Binding stored = parseBinding(storedJson);
            return stored.connectionNamespace.equals(target.connectionNamespace)
                && stored.profileId.equals(target.profileId)
                && stored.catalogVersion < target.catalogVersion;
        } catch (Exception invalidBinding) {
            return false;
        }
    }

    static JSONObject rebindToNewerSameConnection(JSONObject draft, Binding target) {
        if (!canRebindToNewerSameConnection(draft, target)) {
            throw invalid("draft cannot follow this Panel revision");
        }
        try {
            return new JSONObject(draft.toString())
                .put(BINDING_FIELD, target.toJson());
        } catch (Exception error) {
            throw invalid("cannot rebind draft to newer Panel revision");
        }
    }

    /**
     * Proves storage ownership without requiring current catalog semantics to be unchanged.
     * A bound v3 draft may remain locked after a catalog update, but it still belongs to this
     * connection and must be preserved. Unbound v1/v2 data needs the exact cache-pair receipt.
     */
    static boolean belongsToConnection(JSONObject draft, String connectionNamespace,
                                       int catalogVersion, JSONObject legacyReceipt,
                                       int currentReleaseCode, String currentPairSha256) {
        if (draft == null || connectionNamespace == null
                || !connectionNamespace.matches("[0-9a-f]{20}")) return false;
        int draftVersion = exactInteger(draft.opt("version"));
        JSONObject storedJson = draft.optJSONObject(BINDING_FIELD);
        if (storedJson != null || draft.has(BINDING_FIELD) || draftVersion >= DRAFT_VERSION) {
            try {
                return draftVersion == DRAFT_VERSION
                    && storedJson != null
                    && parseBinding(storedJson).connectionNamespace.equals(connectionNamespace);
            } catch (Exception error) {
                return false;
            }
        }
        return (draftVersion == 1 || draftVersion == 2)
            && legacyReceiptMatches(legacyReceipt, connectionNamespace, catalogVersion,
                currentReleaseCode, currentPairSha256);
    }

    static boolean hasSelfBindingForConnection(JSONObject draft, String connectionNamespace) {
        if (draft == null || connectionNamespace == null
                || !connectionNamespace.matches("[0-9a-f]{20}")) return false;
        try {
            return exactInteger(draft.opt("version")) == DRAFT_VERSION
                && draft.optJSONObject(BINDING_FIELD) != null
                && parseBinding(draft.optJSONObject(BINDING_FIELD))
                    .connectionNamespace.equals(connectionNamespace);
        } catch (Exception error) {
            return false;
        }
    }

    static JSONObject newLegacyMigrationReceipt(String connectionNamespace,
                                                int catalogVersion,
                                                int releaseCode,
                                                String pairSha256) {
        BindingReceiptValues values = new BindingReceiptValues(connectionNamespace,
            catalogVersion, releaseCode, pairSha256);
        try {
            return new JSONObject()
                .put("version", LEGACY_RECEIPT_VERSION)
                .put("connectionNamespace", values.connectionNamespace)
                .put("catalogVersion", values.catalogVersion)
                .put("releaseCode", values.releaseCode)
                .put("pairSha256", values.pairSha256);
        } catch (Exception impossible) {
            throw invalid("cannot serialize legacy migration receipt");
        }
    }

    /**
     * True only for the exact durable cache-pair receipt created by the verified legacy-cache
     * migration on this connection and release line.
     *
     * <p>This package-level check lets other legacy state (for example the signed-v1 print ledger)
     * use the same proof without weakening the draft rules or reimplementing receipt parsing. A
     * caller must still validate the legacy value's own shape before adopting or mirroring it.
     */
    static boolean verifiedLegacyMigrationReceipt(JSONObject value,
                                                  String connectionNamespace,
                                                  int catalogVersion,
                                                  int currentReleaseCode,
                                                  String currentPairSha256) {
        return legacyReceiptMatches(value, connectionNamespace, catalogVersion,
            currentReleaseCode, currentPairSha256);
    }

    static String legacyReceiptPreferenceKey(String connectionNamespace) {
        return LEGACY_RECEIPT_PREFIX + requiredNamespace(connectionNamespace);
    }

    /** Stable hash of the exact logical config/catalog pair, excluding local cache stamps. */
    static String panelPairSha256(JSONObject appConfig, JSONObject catalog) {
        if (appConfig == null || catalog == null) return "";
        try {
            JSONObject cleanConfig = new JSONObject(appConfig.toString());
            JSONObject cleanCatalog = new JSONObject(catalog.toString());
            cleanConfig.remove(CACHE_BINDING_FIELD);
            cleanCatalog.remove(CACHE_BINDING_FIELD);
            JSONObject pair = new JSONObject()
                .put("config", cleanConfig)
                .put("catalog", cleanCatalog);
            return sha256(canonicalJson(pair));
        } catch (Exception error) {
            return "";
        }
    }

    static String semanticSha256(JSONObject value) {
        if (value == null) return "";
        try {
            return sha256(canonicalJson(value));
        } catch (Exception error) {
            return "";
        }
    }

    /** Canonical digest for an ordered JSON sequence such as a previous-step recipe chain. */
    static String semanticSha256(JSONArray value) {
        if (value == null) return "";
        try {
            return sha256(canonicalJson(value));
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Proves that the mutable Activity profile still represents the exact active catalog profile.
     *
     * <p>A pre-submit material refresh may replace only each configured group's {@code materials}
     * array. Group identity/field/order and every identifier, result, operation, photo, template
     * and workflow mapping remain catalog-owned and must still match byte-independent canonical
     * JSON. This closes the gap between the catalog profile fingerprint above and payload builders
     * that read the runtime profile.
     */
    static boolean runtimeProfileMatchesCatalog(JSONObject runtimeProfile,
                                                JSONObject catalogProfile) {
        if (runtimeProfile == null || catalogProfile == null) return false;
        try {
            String runtimeId = requiredText(
                runtimeProfile.optString("id", ""), "runtime profile.id", 256);
            String catalogId = requiredText(
                catalogProfile.optString("id", ""), "catalog profile.id", 256);
            if (!runtimeId.equals(catalogId)) return false;
            return canonicalJson(profileContractProjection(runtimeProfile)).equals(
                canonicalJson(profileContractProjection(catalogProfile)));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static JSONObject profileContractProjection(JSONObject profile)
            throws Exception {
        JSONObject projected = new JSONObject(profile.toString());
        if (!projected.has("materialGroups")) return projected;
        Object rawGroups = projected.opt("materialGroups");
        if (!(rawGroups instanceof JSONArray)) {
            throw invalid("profile.materialGroups must be an array");
        }
        JSONArray groups = (JSONArray) rawGroups;
        JSONArray projectedGroups = new JSONArray();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null || !(group.opt("materials") instanceof JSONArray)) {
                throw invalid("profile material group is malformed");
            }
            JSONObject projectedGroup = new JSONObject(group.toString());
            // Only live item rows are mutable. Keep an explicit marker so a missing/wrong-typed
            // materials property cannot compare equal to a valid catalog group.
            projectedGroup.put("materials", "__runtime_material_items_v1__");
            projectedGroups.put(projectedGroup);
        }
        projected.put("materialGroups", projectedGroups);
        return projected;
    }

    private static Binding parseBinding(JSONObject value) {
        if (value == null || value.length() != 5
                || exactInteger(value.opt("version")) != BINDING_VERSION
                || !value.has("connectionNamespace") || !value.has("catalogVersion")
                || !value.has("profileId") || !value.has("semanticsSha256")) {
            throw invalid("invalid draft binding fields");
        }
        return new Binding(requiredString(value, "connectionNamespace"),
            positiveInteger(value.opt("catalogVersion"), "catalogVersion"),
            requiredString(value, "profileId"), requiredString(value, "semanticsSha256"));
    }

    private static boolean legacyReceiptMatches(JSONObject value, Binding current,
                                                int currentReleaseCode,
                                                String currentPairSha256) {
        return current != null && legacyReceiptMatches(value, current.connectionNamespace,
            current.catalogVersion, currentReleaseCode, currentPairSha256);
    }

    private static boolean legacyReceiptMatches(JSONObject value, String connectionNamespace,
                                                int catalogVersion, int currentReleaseCode,
                                                String currentPairSha256) {
        if (value == null || value.length() != 5
                || exactInteger(value.opt("version")) != LEGACY_RECEIPT_VERSION
                || currentReleaseCode < LEGACY_COMPATIBILITY_MIN_RELEASE_CODE
                || !validDigest(currentPairSha256)) return false;
        try {
            BindingReceiptValues receipt = new BindingReceiptValues(
                requiredString(value, "connectionNamespace"),
                positiveInteger(value.opt("catalogVersion"), "catalogVersion"),
                positiveInteger(value.opt("releaseCode"), "releaseCode"),
                requiredString(value, "pairSha256"));
            return receipt.releaseCode >= LEGACY_COMPATIBILITY_MIN_RELEASE_CODE
                && receipt.releaseCode <= currentReleaseCode
                && receipt.connectionNamespace.equals(connectionNamespace)
                && receipt.catalogVersion == catalogVersion
                && receipt.pairSha256.equals(currentPairSha256);
        } catch (Exception error) {
            return false;
        }
    }

    private static final class BindingReceiptValues {
        final String connectionNamespace;
        final int catalogVersion;
        final int releaseCode;
        final String pairSha256;

        BindingReceiptValues(String connectionNamespace, int catalogVersion,
                             int releaseCode, String pairSha256) {
            this.connectionNamespace = requiredNamespace(connectionNamespace);
            if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
            if (releaseCode <= 0) throw invalid("releaseCode must be positive");
            this.catalogVersion = catalogVersion;
            this.releaseCode = releaseCode;
            this.pairSha256 = requiredDigest(pairSha256, "pairSha256");
        }
    }

    private static RestoreDecision blocked(String reason) {
        return new RestoreDecision(RestoreKind.BLOCKED, reason);
    }

    private static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder();
        appendCanonicalJson(out, value);
        return out.toString();
    }

    private static void appendCanonicalJson(StringBuilder out, Object value) {
        if (value == null || value == JSONObject.NULL) {
            out.append('n');
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            out.append('o').append(keys.size()).append('{');
            for (String key : keys) {
                appendLengthFramed(out, 'k', key);
                appendCanonicalJson(out, object.opt(key));
            }
            out.append('}');
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            out.append('a').append(array.length()).append('[');
            for (int i = 0; i < array.length(); i++) appendCanonicalJson(out, array.opt(i));
            out.append(']');
            return;
        }
        if (value instanceof String) {
            appendLengthFramed(out, 's', (String) value);
            return;
        }
        if (value instanceof Boolean) {
            out.append(Boolean.TRUE.equals(value) ? 't' : 'f');
            return;
        }
        if (value instanceof Number) {
            String raw = value.toString();
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(raw).stripTrailingZeros();
            } catch (NumberFormatException error) {
                throw invalid("non-finite JSON number");
            }
            out.append('d').append(decimal.toPlainString()).append(';');
            return;
        }
        throw invalid("unsupported JSON value");
    }

    private static void appendLengthFramed(StringBuilder out, char type, String value) {
        String safe = value == null ? "" : value;
        out.append(type).append(safe.length()).append(':').append(safe);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw invalid("SHA-256 unavailable");
        }
    }

    private static int exactInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return 0;
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static int positiveInteger(Object value, String field) {
        int number = exactInteger(value);
        if (number <= 0) throw invalid(field + " must be a positive integer");
        return number;
    }

    private static String requiredString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String)) throw invalid(key + " must be a string");
        return (String) raw;
    }

    private static String requiredNamespace(String value) {
        String safe = requiredText(value, "connectionNamespace", 20);
        if (safe.length() != 20 || !safe.matches("[0-9a-f]{20}")) {
            throw invalid("connectionNamespace must be a lowercase hex namespace");
        }
        return safe;
    }

    private static String requiredDigest(String value, String field) {
        String safe = requiredText(value, field, 64);
        if (!validDigest(safe)) throw invalid(field + " must be a lowercase SHA-256 digest");
        return safe;
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())
                || value.length() > maxLength) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
