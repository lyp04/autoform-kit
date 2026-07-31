package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Durable, exact proof that an alternate-entry draft existed before a Panel candidate barrier. */
final class AlternateEntryContinuationProof {
    static final int VERSION = 1;
    private static final Set<String> KEYS = setOf(
        "version", "token", "connectionNamespace", "catalogVersion", "panelPairSha256",
        "bindingFingerprint", "stateSha256");

    final String token;
    final String connectionNamespace;
    final int catalogVersion;
    final String panelPairSha256;
    final String bindingFingerprint;
    final String stateSha256;

    private AlternateEntryContinuationProof(String token, String connectionNamespace,
                                            int catalogVersion, String panelPairSha256,
                                            String bindingFingerprint, String stateSha256) {
        this.token = requiredHash(token, "token", 32);
        this.connectionNamespace = requiredHash(
            connectionNamespace, "connectionNamespace", 20);
        if (catalogVersion <= 0) {
            throw invalid("catalogVersion must be positive");
        }
        this.catalogVersion = catalogVersion;
        this.panelPairSha256 = requiredHash(
            panelPairSha256, "panelPairSha256", 64);
        this.bindingFingerprint = requiredHash(
            bindingFingerprint, "bindingFingerprint", 64);
        this.stateSha256 = requiredHash(stateSha256, "stateSha256", 64);
    }

    static AlternateEntryContinuationProof create(
            String token, String connectionNamespace, int catalogVersion,
            String panelPairSha256, String bindingFingerprint, String stateSha256) {
        return new AlternateEntryContinuationProof(token, connectionNamespace,
            catalogVersion, panelPairSha256, bindingFingerprint, stateSha256);
    }

    static AlternateEntryContinuationProof parse(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw invalid("proof is empty");
            JSONObject json = new JSONObject(raw);
            rejectUnknownKeys(json);
            Object version = json.opt("version");
            if (!(version instanceof Number)
                    || ((Number) version).intValue() != VERSION
                    || ((Number) version).doubleValue() != VERSION) {
                throw invalid("unsupported proof version");
            }
            Object catalogVersion = json.opt("catalogVersion");
            if (!(catalogVersion instanceof Number)
                    || ((Number) catalogVersion).doubleValue()
                        != ((Number) catalogVersion).intValue()) {
                throw invalid("catalogVersion must be an integer");
            }
            return create(requiredString(json, "token"),
                requiredString(json, "connectionNamespace"),
                ((Number) catalogVersion).intValue(),
                requiredString(json, "panelPairSha256"),
                requiredString(json, "bindingFingerprint"),
                requiredString(json, "stateSha256"));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("invalid proof: " + error.getClass().getSimpleName());
        }
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("version", VERSION)
                .put("token", token)
                .put("connectionNamespace", connectionNamespace)
                .put("catalogVersion", catalogVersion)
                .put("panelPairSha256", panelPairSha256)
                .put("bindingFingerprint", bindingFingerprint)
                .put("stateSha256", stateSha256);
        } catch (Exception impossible) {
            throw invalid("cannot serialize proof");
        }
    }

    boolean matches(String connectionNamespace, int catalogVersion,
                    String panelPairSha256, String bindingFingerprint,
                    String stateSha256) {
        return this.connectionNamespace.equals(clean(connectionNamespace))
            && this.catalogVersion == catalogVersion
            && this.panelPairSha256.equals(clean(panelPairSha256).toLowerCase(Locale.US))
            && this.bindingFingerprint.equals(
                clean(bindingFingerprint).toLowerCase(Locale.US))
            && this.stateSha256.equals(clean(stateSha256).toLowerCase(Locale.US));
    }

    private static void rejectUnknownKeys(JSONObject json) {
        JSONArray names = json.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "");
            if (!KEYS.contains(key)) throw invalid("unknown proof field " + key);
        }
        for (String key : KEYS) {
            if (!json.has(key)) throw invalid("missing proof field " + key);
        }
    }

    private static String requiredString(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string");
        return (String) value;
    }

    private static String requiredHash(String value, String field, int length) {
        String safe = clean(value).toLowerCase(Locale.US);
        if (!safe.equals(value) || safe.length() != length
                || !safe.matches("[0-9a-f]+")) {
            throw invalid(field + " must be a lowercase hex fingerprint");
        }
        return safe;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
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
