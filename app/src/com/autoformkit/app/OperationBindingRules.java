package com.autoformkit.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, deployment-neutral identity rules for asynchronous backend and camera operations.
 *
 * <p>The raw login token is never serialized. A request is instead tied to a one-way digest of
 * the web-client fingerprint and the exact token snapshot, plus the active Panel pair and a
 * per-launch nonce. Callers must also keep the nonce active until the response is consumed.
 */
final class OperationBindingRules {
    static final int VERSION = 1;

    static final String CAPTCHA = "captcha";
    static final String LOGIN = "login";
    static final String AUTH_PROBE = "auth-probe";
    static final String USER_INFO = "user-info";
    static final String OCR = "ocr";
    static final String OCR_ENDPOINT = "ocr-endpoint";
    static final String MAIN_SCAN = "main-scan";
    static final String MAIN_RESCAN = "main-rescan";
    static final String MAIN_PHOTO = "main-photo";
    static final String MAIN_OCR_PHOTO = "main-ocr-photo";

    private static final Set<String> KINDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(CAPTCHA, LOGIN, AUTH_PROBE, USER_INFO, OCR, OCR_ENDPOINT,
            MAIN_SCAN, MAIN_RESCAN, MAIN_PHOTO, MAIN_OCR_PHOTO)));

    static final class Binding {
        final String connectionNamespace;
        final int catalogVersion;
        final String pairSha256;
        final String sessionFingerprint;
        final String nonce;
        final String kind;

        private Binding(String connectionNamespace, int catalogVersion, String pairSha256,
                        String sessionFingerprint, String nonce, String kind) {
            this.connectionNamespace = requiredNamespace(connectionNamespace);
            if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
            this.catalogVersion = catalogVersion;
            this.pairSha256 = requiredDigest(pairSha256, "pairSha256");
            this.sessionFingerprint = requiredDigest(
                sessionFingerprint, "sessionFingerprint");
            this.nonce = requiredNonce(nonce);
            this.kind = requiredKind(kind);
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("version", VERSION)
                    .put("connectionNamespace", connectionNamespace)
                    .put("catalogVersion", catalogVersion)
                    .put("pairSha256", pairSha256)
                    .put("sessionFingerprint", sessionFingerprint)
                    .put("nonce", nonce)
                    .put("kind", kind);
            } catch (Exception impossible) {
                throw invalid("cannot serialize operation binding");
            }
        }

        boolean sameAs(Binding other) {
            return other != null
                && connectionNamespace.equals(other.connectionNamespace)
                && catalogVersion == other.catalogVersion
                && pairSha256.equals(other.pairSha256)
                && sessionFingerprint.equals(other.sessionFingerprint)
                && nonce.equals(other.nonce)
                && kind.equals(other.kind);
        }

        boolean matchesContext(String connectionNamespace, int catalogVersion,
                               String pairSha256, String webFingerprint,
                               String token, String kind) {
            try {
                return this.connectionNamespace.equals(requiredNamespace(connectionNamespace))
                    && this.catalogVersion == catalogVersion
                    && this.pairSha256.equals(requiredDigest(pairSha256, "pairSha256"))
                    && this.sessionFingerprint.equals(
                        sessionFingerprint(webFingerprint, token))
                    && this.kind.equals(requiredKind(kind));
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    static final class BoundValue {
        final Binding binding;
        final String value;

        private BoundValue(Binding binding, String value) {
            if (binding == null) throw invalid("binding is required");
            this.binding = binding;
            this.value = requiredText(value, "value", 8192);
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("version", VERSION)
                    .put("binding", binding.toJson())
                    .put("value", value);
            } catch (Exception impossible) {
                throw invalid("cannot serialize bound value");
            }
        }
    }

    private OperationBindingRules() {}

    static Binding capture(String connectionNamespace, int catalogVersion,
                           String pairSha256, String webFingerprint,
                           String token, String nonce, String kind) {
        return new Binding(connectionNamespace, catalogVersion, pairSha256,
            sessionFingerprint(webFingerprint, token), nonce, kind);
    }

    static Binding parse(JSONObject value) {
        if (value == null || value.length() != 7
                || exactInteger(value.opt("version")) != VERSION
                || !value.has("connectionNamespace") || !value.has("catalogVersion")
                || !value.has("pairSha256") || !value.has("sessionFingerprint")
                || !value.has("nonce") || !value.has("kind")) {
            throw invalid("invalid operation binding fields");
        }
        return new Binding(requiredString(value, "connectionNamespace"),
            positiveInteger(value.opt("catalogVersion"), "catalogVersion"),
            requiredString(value, "pairSha256"),
            requiredString(value, "sessionFingerprint"),
            requiredString(value, "nonce"), requiredString(value, "kind"));
    }

    static BoundValue bindValue(String value, Binding binding) {
        return new BoundValue(binding, value);
    }

    static BoundValue parseBoundValue(String raw) {
        try {
            JSONObject value = new JSONObject(requiredText(raw, "bound value", 16384));
            if (value.length() != 3 || exactInteger(value.opt("version")) != VERSION
                    || !(value.opt("binding") instanceof JSONObject)
                    || !(value.opt("value") instanceof String)) {
                throw invalid("invalid bound value fields");
            }
            return new BoundValue(parse(value.getJSONObject("binding")),
                value.getString("value"));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("bound value is malformed");
        }
    }

    static String sessionFingerprint(String webFingerprint, String token) {
        String web = requiredText(webFingerprint, "webFingerprint", 512);
        String safeToken = token == null ? "" : token.trim();
        if (safeToken.length() > 16384) throw invalid("token is too long");
        return sha256("autoform-kit-operation-session-v1\n"
            + frame(web) + frame(safeToken));
    }

    /** Preference key contains only a digest; neither the token nor the endpoint leaks in its name. */
    static String scopedValuePreferenceKey(String prefix, String connectionNamespace,
                                           int catalogVersion, String pairSha256,
                                           String webFingerprint, String token) {
        String safePrefix = requiredText(prefix, "prefix", 64);
        if (!safePrefix.matches("[a-z0-9_]+")) throw invalid("prefix is invalid");
        String context = requiredNamespace(connectionNamespace) + "\n"
            + catalogVersion + "\n" + requiredDigest(pairSha256, "pairSha256") + "\n"
            + sessionFingerprint(webFingerprint, token);
        return safePrefix + sha256("autoform-kit-bound-value-key-v1\n" + context);
    }

    private static String frame(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe + "\n";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
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
        if (!safe.matches("[0-9a-f]{20}")) {
            throw invalid("connectionNamespace must be lowercase hex");
        }
        return safe;
    }

    private static String requiredDigest(String value, String field) {
        String safe = requiredText(value, field, 64);
        if (!safe.matches("[0-9a-f]{64}")) {
            throw invalid(field + " must be a lowercase SHA-256 digest");
        }
        return safe;
    }

    private static String requiredNonce(String value) {
        String safe = requiredText(value, "nonce", 128);
        if (safe.length() < 16 || !safe.matches("[A-Za-z0-9_-]+")) {
            throw invalid("nonce is invalid");
        }
        return safe;
    }

    private static String requiredKind(String value) {
        String safe = requiredText(value, "kind", 64);
        if (!KINDS.contains(safe)) throw invalid("kind is invalid");
        return safe;
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
