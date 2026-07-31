package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Strict device-local proof for one alternate-entry activity result started before a Panel
 * candidate barrier.
 *
 * <p>A pending path or an Activity guard is only an observation.  Neither may create old-pair
 * work after restart.  This reservation is created durably while {@code HANDOFF_LOCK} proves that
 * no unsafe candidate exists, allocates the only token that its result may establish, and binds
 * the result to the exact pair, entry, account, logical base state and (for a photo) output path.</p>
 */
final class AlternateEntryAsyncReservation {
    static final int VERSION = 1;
    static final String KIND_SCAN = "scan";
    static final String KIND_PHOTO = "photo";

    private static final Set<String> KEYS = setOf(
        "version", "kind", "reservationToken", "resultContinuationToken",
        "accountFingerprint", "connectionNamespace", "catalogVersion",
        "panelPairSha256", "bindingFingerprint", "backendFingerprint",
        "operationGuardSha256", "baseStateSha256", "outputPath");

    final String kind;
    final String reservationToken;
    final String resultContinuationToken;
    final String accountFingerprint;
    final String connectionNamespace;
    final int catalogVersion;
    final String panelPairSha256;
    final String bindingFingerprint;
    final String backendFingerprint;
    final String operationGuardSha256;
    final String baseStateSha256;
    final String outputPath;

    private AlternateEntryAsyncReservation(
            String kind, String reservationToken, String resultContinuationToken,
            String accountFingerprint, String connectionNamespace, int catalogVersion,
            String panelPairSha256, String bindingFingerprint, String backendFingerprint,
            String operationGuardSha256, String baseStateSha256, String outputPath) {
        this.kind = requiredKind(kind);
        this.reservationToken = requiredHash(reservationToken, "reservationToken", 32);
        this.resultContinuationToken = requiredHash(
            resultContinuationToken, "resultContinuationToken", 32);
        this.accountFingerprint = requiredHash(
            accountFingerprint, "accountFingerprint", 64);
        this.connectionNamespace = requiredHash(
            connectionNamespace, "connectionNamespace", 20);
        if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
        this.catalogVersion = catalogVersion;
        this.panelPairSha256 = requiredHash(panelPairSha256, "panelPairSha256", 64);
        this.bindingFingerprint = requiredHash(
            bindingFingerprint, "bindingFingerprint", 64);
        this.backendFingerprint = requiredHash(
            backendFingerprint, "backendFingerprint", 64);
        this.operationGuardSha256 = requiredHash(
            operationGuardSha256, "operationGuardSha256", 64);
        this.baseStateSha256 = requiredHash(baseStateSha256, "baseStateSha256", 64);
        this.outputPath = exactText(outputPath, "outputPath", 4096);
        if (KIND_PHOTO.equals(this.kind) && this.outputPath.isEmpty()) {
            throw invalid("photo outputPath is required");
        }
        if (KIND_SCAN.equals(this.kind) && !this.outputPath.isEmpty()) {
            throw invalid("scan outputPath must be empty");
        }
    }

    static AlternateEntryAsyncReservation create(
            String kind, String reservationToken, String resultContinuationToken,
            String accountFingerprint, String connectionNamespace, int catalogVersion,
            String panelPairSha256, String bindingFingerprint, String backendFingerprint,
            String operationGuard, String baseStateSha256, String outputPath) {
        return new AlternateEntryAsyncReservation(kind, reservationToken,
            resultContinuationToken, accountFingerprint, connectionNamespace, catalogVersion,
            panelPairSha256, bindingFingerprint, backendFingerprint,
            sha256(operationGuard), baseStateSha256, outputPath);
    }

    static AlternateEntryAsyncReservation parse(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw invalid("reservation is empty");
            JSONObject json = new JSONObject(raw);
            rejectUnknownKeys(json);
            Object version = json.opt("version");
            if (!(version instanceof Number)
                    || ((Number) version).intValue() != VERSION
                    || ((Number) version).doubleValue() != VERSION) {
                throw invalid("unsupported reservation version");
            }
            Object catalogVersion = json.opt("catalogVersion");
            if (!(catalogVersion instanceof Number)
                    || ((Number) catalogVersion).doubleValue()
                        != ((Number) catalogVersion).intValue()) {
                throw invalid("catalogVersion must be an integer");
            }
            return new AlternateEntryAsyncReservation(
                requiredString(json, "kind"),
                requiredString(json, "reservationToken"),
                requiredString(json, "resultContinuationToken"),
                requiredString(json, "accountFingerprint"),
                requiredString(json, "connectionNamespace"),
                ((Number) catalogVersion).intValue(),
                requiredString(json, "panelPairSha256"),
                requiredString(json, "bindingFingerprint"),
                requiredString(json, "backendFingerprint"),
                requiredString(json, "operationGuardSha256"),
                requiredString(json, "baseStateSha256"),
                requiredString(json, "outputPath"));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("invalid reservation: " + error.getClass().getSimpleName());
        }
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("version", VERSION)
                .put("kind", kind)
                .put("reservationToken", reservationToken)
                .put("resultContinuationToken", resultContinuationToken)
                .put("accountFingerprint", accountFingerprint)
                .put("connectionNamespace", connectionNamespace)
                .put("catalogVersion", catalogVersion)
                .put("panelPairSha256", panelPairSha256)
                .put("bindingFingerprint", bindingFingerprint)
                .put("backendFingerprint", backendFingerprint)
                .put("operationGuardSha256", operationGuardSha256)
                .put("baseStateSha256", baseStateSha256)
                .put("outputPath", outputPath);
        } catch (Exception impossible) {
            throw invalid("cannot serialize reservation");
        }
    }

    boolean matches(String expectedKind, String accountFingerprint,
                    String connectionNamespace, int catalogVersion,
                    String panelPairSha256, String bindingFingerprint,
                    String backendFingerprint, String operationGuard,
                    String baseStateSha256, String outputPath) {
        return kind.equals(expectedKind)
            && this.accountFingerprint.equals(clean(accountFingerprint))
            && this.connectionNamespace.equals(clean(connectionNamespace))
            && this.catalogVersion == catalogVersion
            && this.panelPairSha256.equals(lower(panelPairSha256))
            && this.bindingFingerprint.equals(lower(bindingFingerprint))
            && this.backendFingerprint.equals(lower(backendFingerprint))
            && this.operationGuardSha256.equals(sha256(operationGuard))
            && this.baseStateSha256.equals(lower(baseStateSha256))
            && this.outputPath.equals(outputPath == null ? "" : outputPath);
    }

    private static String requiredKind(String value) {
        String safe = exactText(value, "kind", 16);
        if (!KIND_SCAN.equals(safe) && !KIND_PHOTO.equals(safe)) {
            throw invalid("kind is invalid");
        }
        return safe;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw invalid("cannot hash reservation input");
        }
    }

    private static void rejectUnknownKeys(JSONObject json) {
        JSONArray names = json.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "");
            if (!KEYS.contains(key)) throw invalid("unknown reservation field " + key);
        }
        for (String key : KEYS) {
            if (!json.has(key)) throw invalid("missing reservation field " + key);
        }
    }

    private static String requiredString(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string");
        return (String) value;
    }

    private static String requiredHash(String value, String field, int length) {
        String safe = exactText(value, field, length);
        if (safe.length() != length || !safe.matches("[0-9a-f]+")) {
            throw invalid(field + " must be a lowercase hex fingerprint");
        }
        return safe;
    }

    private static String exactText(String value, String field, int maxLength) {
        if (value == null) throw invalid(field + " must be a string");
        if (!value.equals(value.trim())) throw invalid(field + " has surrounding whitespace");
        if (value.length() > maxLength) throw invalid(field + " is too long");
        return value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.US);
    }

    private static Set<String> setOf(String... values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Collections.addAll(out, values);
        return Collections.unmodifiableSet(out);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
