package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict device-local snapshot for an unfinished, exactly bound alternate-entry draft. */
final class AlternateEntryDraftState {
    static final int VERSION = 1;
    private static final int MAX_TOGGLES = 32;
    private static final Set<String> KEYS = setOf(
        "version", "accountFingerprint", "connectionNamespace", "bindingFingerprint",
        "backendFingerprint", "entryId", "sourceProfileId", "returnProfileId", "serial",
        "serialSource", "photos", "toggles");

    final String accountFingerprint;
    final String connectionNamespace;
    final String bindingFingerprint;
    final String backendFingerprint;
    final String entryId;
    final String sourceProfileId;
    final String returnProfileId;
    final String serial;
    final String serialSource;
    final List<String> photos;
    final Map<String, Boolean> toggles;

    static final class PhotoEvidence {
        final String path;
        final long length;
        final long lastModified;

        private PhotoEvidence(String path, long length, long lastModified) {
            this.path = requiredText(path, "photo evidence path", 4096);
            if (length < 0L || lastModified < 0L) {
                throw invalid("photo evidence metadata must not be negative");
            }
            this.length = length;
            this.lastModified = lastModified;
        }

        static PhotoEvidence of(String path, long length, long lastModified) {
            return new PhotoEvidence(path, length, lastModified);
        }
    }

    private AlternateEntryDraftState(String accountFingerprint, String connectionNamespace,
                                     String bindingFingerprint, String backendFingerprint,
                                     String entryId, String sourceProfileId,
                                     String returnProfileId, String serial, String serialSource,
                                     List<String> photos, Map<String, Boolean> toggles) {
        this.accountFingerprint = requiredHash(accountFingerprint, "accountFingerprint", 64);
        this.connectionNamespace = requiredHash(connectionNamespace, "connectionNamespace", 20);
        this.bindingFingerprint = requiredHash(bindingFingerprint, "bindingFingerprint", 64);
        this.backendFingerprint = requiredHash(backendFingerprint, "backendFingerprint", 64);
        this.entryId = requiredText(entryId, "entryId", 256);
        this.sourceProfileId = requiredText(sourceProfileId, "sourceProfileId", 256);
        this.returnProfileId = optionalText(returnProfileId, "returnProfileId", 256);
        this.serial = optionalText(serial, "serial", 512);
        this.serialSource = requiredSerialSource(serialSource);

        LinkedHashSet<String> uniquePhotos = new LinkedHashSet<>();
        if (photos != null) {
            for (String photo : photos) {
                String path = requiredText(photo, "photo", 4096);
                if (!uniquePhotos.add(path)) throw invalid("duplicate photo path");
            }
        }
        this.photos = Collections.unmodifiableList(new ArrayList<>(uniquePhotos));

        LinkedHashMap<String, Boolean> safeToggles = new LinkedHashMap<>();
        if (toggles != null) {
            if (toggles.size() > MAX_TOGGLES) throw invalid("too many toggles");
            for (Map.Entry<String, Boolean> toggle : toggles.entrySet()) {
                String key = requiredText(toggle.getKey(), "toggle key", 256);
                if (toggle.getValue() == null) throw invalid("toggle value must be boolean");
                safeToggles.put(key, toggle.getValue());
            }
        }
        this.toggles = Collections.unmodifiableMap(safeToggles);
        if (this.serial.isEmpty() && this.photos.isEmpty()) {
            throw invalid("draft has no pending data");
        }
    }

    static AlternateEntryDraftState create(String accountFingerprint,
                                           String connectionNamespace,
                                           String bindingFingerprint,
                                           String backendFingerprint,
                                           String entryId, String sourceProfileId,
                                           String returnProfileId, String serial,
                                           String serialSource,
                                           List<String> photos,
                                           Map<String, Boolean> toggles) {
        return new AlternateEntryDraftState(accountFingerprint, connectionNamespace,
            bindingFingerprint, backendFingerprint, entryId, sourceProfileId,
            returnProfileId, serial, serialSource, photos, toggles);
    }

    static AlternateEntryDraftState parse(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw invalid("draft is empty");
            JSONObject json = new JSONObject(raw);
            rejectUnknownKeys(json);
            Object version = json.opt("version");
            if (!(version instanceof Number) || ((Number) version).intValue() != VERSION
                    || ((Number) version).doubleValue() != VERSION) {
                throw invalid("unsupported draft version");
            }
            JSONArray photoValues = requiredArray(json, "photos");
            List<String> photos = new ArrayList<>();
            for (int i = 0; i < photoValues.length(); i++) {
                Object value = photoValues.opt(i);
                if (!(value instanceof String)) throw invalid("photo path must be a string");
                photos.add((String) value);
            }
            JSONObject toggleValues = requiredObject(json, "toggles");
            Map<String, Boolean> toggles = new LinkedHashMap<>();
            JSONArray names = toggleValues.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String name = names.getString(i);
                Object value = toggleValues.opt(name);
                if (!(value instanceof Boolean)) throw invalid("toggle value must be boolean");
                toggles.put(name, (Boolean) value);
            }
            return create(requiredString(json, "accountFingerprint"),
                requiredString(json, "connectionNamespace"),
                requiredString(json, "bindingFingerprint"),
                requiredString(json, "backendFingerprint"),
                requiredString(json, "entryId"), requiredString(json, "sourceProfileId"),
                requiredString(json, "returnProfileId"), requiredString(json, "serial"),
                requiredString(json, "serialSource"), photos, toggles);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("invalid draft: " + error.getClass().getSimpleName());
        }
    }

    JSONObject toJson() {
        try {
            JSONObject togglesJson = new JSONObject();
            for (Map.Entry<String, Boolean> toggle : toggles.entrySet()) {
                togglesJson.put(toggle.getKey(), toggle.getValue());
            }
            return new JSONObject()
                .put("version", VERSION)
                .put("accountFingerprint", accountFingerprint)
                .put("connectionNamespace", connectionNamespace)
                .put("bindingFingerprint", bindingFingerprint)
                .put("backendFingerprint", backendFingerprint)
                .put("entryId", entryId)
                .put("sourceProfileId", sourceProfileId)
                .put("returnProfileId", returnProfileId)
                .put("serial", serial)
                .put("serialSource", serialSource)
                .put("photos", new JSONArray(photos))
                .put("toggles", togglesJson);
        } catch (Exception impossible) {
            throw invalid("cannot serialize draft");
        }
    }

    boolean matches(String accountFingerprint, String connectionNamespace,
                    String bindingFingerprint, String backendFingerprint,
                    String requestedEntryId) {
        return this.accountFingerprint.equals(accountFingerprint)
            && this.connectionNamespace.equals(connectionNamespace)
            && this.bindingFingerprint.equals(bindingFingerprint)
            && this.backendFingerprint.equals(backendFingerprint)
            && this.entryId.equals(requestedEntryId);
    }

    static String accountFingerprint(String account) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (account == null ? "" : account.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            return "";
        }
    }

    /**
     * Fingerprint of the exact local source copy associated with a POST attempt.
     *
     * <p>Photo order, paths and stable file metadata are included, as are scan source and sorted
     * toggle values. This prevents a completed-attempt cleanup from deleting a newer local edit
     * which happens to reuse the same serial.
     */
    String sourceSnapshotSha256(List<PhotoEvidence> evidence) {
        if (evidence == null || evidence.size() != photos.size()) {
            throw invalid("photo evidence does not match draft");
        }
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "version", String.valueOf(VERSION));
        appendCanonical(canonical, "accountFingerprint", accountFingerprint);
        appendCanonical(canonical, "connectionNamespace", connectionNamespace);
        appendCanonical(canonical, "bindingFingerprint", bindingFingerprint);
        appendCanonical(canonical, "backendFingerprint", backendFingerprint);
        appendCanonical(canonical, "entryId", entryId);
        appendCanonical(canonical, "sourceProfileId", sourceProfileId);
        appendCanonical(canonical, "returnProfileId", returnProfileId);
        appendCanonical(canonical, "serial", serial);
        appendCanonical(canonical, "serialSource", serialSource);
        appendCanonical(canonical, "photoCount", String.valueOf(photos.size()));
        for (int index = 0; index < photos.size(); index++) {
            PhotoEvidence item = evidence.get(index);
            if (item == null || !photos.get(index).equals(item.path)) {
                throw invalid("photo evidence order does not match draft");
            }
            appendCanonical(canonical, "photoPath", item.path);
            appendCanonical(canonical, "photoLength", String.valueOf(item.length));
            appendCanonical(canonical, "photoLastModified",
                String.valueOf(item.lastModified));
        }
        List<String> toggleKeys = new ArrayList<>(toggles.keySet());
        Collections.sort(toggleKeys);
        appendCanonical(canonical, "toggleCount", String.valueOf(toggleKeys.size()));
        for (String key : toggleKeys) {
            appendCanonical(canonical, "toggleKey", key);
            appendCanonical(canonical, "toggleValue",
                String.valueOf(Boolean.TRUE.equals(toggles.get(key))));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw invalid("cannot fingerprint draft");
        }
    }

    /**
     * Fingerprint of the logical local draft used by the unsafe-candidate continuation proof.
     *
     * <p>Unlike {@link #sourceSnapshotSha256(List)}, this deliberately excludes mutable file
     * metadata. A camera reservation owns an exact path before the camera has written the bytes;
     * the reservation may therefore be discarded safely after a candidate appears without ever
     * treating the eventual camera bytes as pre-candidate form data.</p>
     */
    String continuationStateSha256() {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "version", String.valueOf(VERSION));
        appendCanonical(canonical, "accountFingerprint", accountFingerprint);
        appendCanonical(canonical, "connectionNamespace", connectionNamespace);
        appendCanonical(canonical, "bindingFingerprint", bindingFingerprint);
        appendCanonical(canonical, "backendFingerprint", backendFingerprint);
        appendCanonical(canonical, "entryId", entryId);
        appendCanonical(canonical, "sourceProfileId", sourceProfileId);
        appendCanonical(canonical, "returnProfileId", returnProfileId);
        appendCanonical(canonical, "serial", serial);
        appendCanonical(canonical, "serialSource", serialSource);
        appendCanonical(canonical, "photoCount", String.valueOf(photos.size()));
        for (String path : photos) appendCanonical(canonical, "photoPath", path);
        List<String> toggleKeys = new ArrayList<>(toggles.keySet());
        Collections.sort(toggleKeys);
        appendCanonical(canonical, "toggleCount", String.valueOf(toggleKeys.size()));
        for (String key : toggleKeys) {
            appendCanonical(canonical, "toggleKey", key);
            appendCanonical(canonical, "toggleValue",
                String.valueOf(Boolean.TRUE.equals(toggles.get(key))));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw invalid("cannot fingerprint continuation state");
        }
    }

    private static void appendCanonical(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name)
            .append('=').append(value.length()).append(':').append(value).append('\n');
    }

    private static void rejectUnknownKeys(JSONObject json) {
        JSONArray names = json.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "");
            if (!KEYS.contains(key)) throw invalid("unknown draft field " + key);
        }
        for (String key : KEYS) {
            if (!json.has(key)) throw invalid("missing draft field " + key);
        }
    }

    private static String requiredString(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string");
        return (String) value;
    }

    private static JSONArray requiredArray(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof JSONArray)) throw invalid(key + " must be an array");
        return (JSONArray) value;
    }

    private static JSONObject requiredObject(JSONObject json, String key) {
        Object value = json.opt(key);
        if (!(value instanceof JSONObject)) throw invalid(key + " must be an object");
        return (JSONObject) value;
    }

    private static String requiredHash(String value, String field, int length) {
        String safe = requiredText(value, field, length);
        if (safe.length() != length || !safe.matches("[0-9a-f]+")) {
            throw invalid(field + " must be a lowercase hex fingerprint");
        }
        return safe;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String safe = optionalText(value, field, maxLength);
        if (safe.isEmpty()) throw invalid(field + " is required");
        return safe;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) throw invalid(field + " must be a string");
        if (!value.equals(value.trim())) throw invalid(field + " has surrounding whitespace");
        if (value.length() > maxLength) throw invalid(field + " is too long");
        return value;
    }

    private static String requiredSerialSource(String value) {
        String source = requiredText(value, "serialSource", 16);
        if (!SnScanRules.SOURCE_OCR.equals(source)
                && !SnScanRules.SOURCE_BARCODE.equals(source)
                && !SnScanRules.SOURCE_ENTERED.equals(source)) {
            throw invalid("serialSource is invalid");
        }
        return source;
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
