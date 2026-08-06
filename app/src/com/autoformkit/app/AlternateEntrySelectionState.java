package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Device-local presentation preference for the last source selected for one alternate entry.
 *
 * <p>This is deliberately not a draft or a workflow binding. It contains no record data and never
 * authorizes a submission. Callers must resolve the stored source against the current, preflighted
 * source list every time it is used. The connection namespace keeps two Panel URL/read-key pairs
 * from sharing a selection, while omitting the catalog version lets a still-valid profile survive
 * a normal Panel publish.
 */
final class AlternateEntrySelectionState {
    static final int VERSION = 1;
    static final String PREFERENCE_PREFIX = "last_alternate_entry_source_v1_";
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "version", "connectionNamespace", "entryId", "sourceProfileId"));

    final String connectionNamespace;
    final String entryId;
    final String sourceProfileId;

    private AlternateEntrySelectionState(String connectionNamespace, String entryId,
                                         String sourceProfileId) {
        this.connectionNamespace = requiredNamespace(connectionNamespace);
        this.entryId = requiredId(entryId, "entryId");
        this.sourceProfileId = requiredId(sourceProfileId, "sourceProfileId");
    }

    static AlternateEntrySelectionState create(String connectionNamespace, String entryId,
                                               String sourceProfileId) {
        return new AlternateEntrySelectionState(
            connectionNamespace, entryId, sourceProfileId);
    }

    static AlternateEntrySelectionState parse(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw invalid("selection is empty");
            JSONObject json = new JSONObject(raw);
            rejectUnknownKeys(json);
            Object version = json.opt("version");
            if (!(version instanceof Number)
                    || ((Number) version).intValue() != VERSION
                    || ((Number) version).doubleValue() != VERSION) {
                throw invalid("unsupported selection version");
            }
            return create(requiredString(json, "connectionNamespace"),
                requiredString(json, "entryId"),
                requiredString(json, "sourceProfileId"));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("invalid selection");
        }
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("version", VERSION)
                .put("connectionNamespace", connectionNamespace)
                .put("entryId", entryId)
                .put("sourceProfileId", sourceProfileId);
        } catch (Exception impossible) {
            throw invalid("cannot serialize selection");
        }
    }

    boolean matches(String connectionNamespace, String entryId) {
        return this.connectionNamespace.equals(connectionNamespace)
            && this.entryId.equals(entryId);
    }

    /** Fixed-length preference key; neither the Panel origin nor the entry id is exposed in it. */
    static String preferenceKey(String connectionNamespace, String entryId) {
        String namespace = requiredNamespace(connectionNamespace);
        String id = requiredId(entryId, "entryId");
        return PREFERENCE_PREFIX + sha256(
            namespace.length() + ":" + namespace + "\n" + id.length() + ":" + id);
    }

    /**
     * Selection precedence for an independent-entry page.
     *
     * <p>An exact bound source (for example the source carried by a non-empty draft) is not a
     * presentation preference: it owns the meaning of the saved identifier/photos. It therefore
     * wins over remembered/current choices and must fail closed instead of displaying item zero
     * when it is no longer a unique current source. An empty exact id denotes a genuinely fresh
     * page, where the validated remembered choice may fall back to the current profile or first
     * source.</p>
     */
    static int pageSourceIndex(JSONArray currentSources, String exactBoundSourceProfileId,
                               AlternateEntrySelectionState remembered,
                               String connectionNamespace, String entryId,
                               String currentProfileId) {
        if (currentSources == null || currentSources.length() == 0) return -1;
        if (exactBoundSourceProfileId != null && !exactBoundSourceProfileId.isEmpty()) {
            return uniqueSourceIndex(currentSources, exactBoundSourceProfileId);
        }
        if (remembered != null && remembered.matches(connectionNamespace, entryId)) {
            int stored = uniqueSourceIndex(currentSources, remembered.sourceProfileId);
            if (stored >= 0) return stored;
        }
        int current = uniqueSourceIndex(currentSources, currentProfileId);
        return current >= 0 ? current : 0;
    }

    static int preferredSourceIndex(JSONArray currentSources,
                                    AlternateEntrySelectionState remembered,
                                    String connectionNamespace, String entryId,
                                    String currentProfileId) {
        return pageSourceIndex(currentSources, "", remembered,
            connectionNamespace, entryId, currentProfileId);
    }

    private static int uniqueSourceIndex(JSONArray sources, String profileId) {
        if (profileId == null || profileId.isEmpty()) return -1;
        int found = -1;
        for (int index = 0; sources != null && index < sources.length(); index++) {
            JSONObject source = sources.optJSONObject(index);
            if (source == null || !profileId.equals(source.optString("id", ""))) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static String requiredNamespace(String value) {
        String safe = value == null ? "" : value;
        if (!safe.matches("[0-9a-f]{20}")) {
            throw invalid("connectionNamespace must be a 20-character lowercase hash");
        }
        return safe;
    }

    private static String requiredId(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > 256
                || !value.equals(value.trim())) {
            throw invalid(name + " is invalid");
        }
        return value;
    }

    private static String requiredString(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string");
        return (String) value;
    }

    private static void rejectUnknownKeys(JSONObject json) {
        JSONArray names = json.names();
        for (int index = 0; names != null && index < names.length(); index++) {
            String name = names.optString(index, "");
            if (!KEYS.contains(name)) throw invalid("unknown selection field");
        }
        for (String key : KEYS) {
            if (!json.has(key)) throw invalid("missing selection field");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw invalid("cannot hash selection key");
        }
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("alternate-entry selection rejected: " + detail);
    }
}
