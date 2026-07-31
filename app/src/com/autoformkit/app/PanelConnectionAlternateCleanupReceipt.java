package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Canonical v1 receipt for finishing alternate-entry photo cleanup after a Panel switch. */
final class PanelConnectionAlternateCleanupReceipt {
    static final int SCHEMA = 1;
    static final String PREFERENCE_KEY =
        "pending_panel_connection_alternate_cleanup_v1_json";
    private static final int MAX_PHOTO_COUNT = 4096;
    private static final int MAX_RELATIVE_PATH_LENGTH = 512;
    private static final int MAX_PATH_SEGMENT_LENGTH = 128;
    private static final Set<String> KEYS = exactSet(
        "schema", "transactionId", "oldNamespace",
        "newConnectionSecurityId", "photos");

    final String transactionId;
    final String oldNamespace;
    final String newConnectionSecurityId;
    final List<String> photos;

    private PanelConnectionAlternateCleanupReceipt(
            String transactionId, String oldNamespace,
            String newConnectionSecurityId, Collection<String> photos) {
        this.transactionId = requiredLowerHex(transactionId, "transactionId", 32);
        this.oldNamespace = requiredLowerHex(oldNamespace, "oldNamespace", 20);
        this.newConnectionSecurityId = requiredLowerHex(
            newConnectionSecurityId, "newConnectionSecurityId", 64);
        if (photos == null) throw invalid("photos are required");
        if (photos.size() > MAX_PHOTO_COUNT) throw invalid("too many photo paths");
        TreeSet<String> canonical = new TreeSet<>();
        for (String path : photos) canonical.add(requiredRelativePhotoPath(path));
        this.photos = Collections.unmodifiableList(new ArrayList<>(canonical));
    }

    /** Validates values and returns their immutable canonical representation. */
    static PanelConnectionAlternateCleanupReceipt validate(
            String transactionId, String oldNamespace,
            String newConnectionSecurityId, Collection<String> photos) {
        return new PanelConnectionAlternateCleanupReceipt(
            transactionId, oldNamespace, newConnectionSecurityId, photos);
    }

    /** Parses only the exact canonical v1 JSON shape. */
    static PanelConnectionAlternateCleanupReceipt parse(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) throw invalid("receipt is empty");
            JSONObject root = new JSONObject(raw);
            requireExactKeys(root);
            if (exactInteger(root.opt("schema")) != SCHEMA) {
                throw invalid("unsupported receipt schema");
            }
            String transactionId = requiredString(root, "transactionId");
            String oldNamespace = requiredString(root, "oldNamespace");
            String newConnectionSecurityId = requiredString(
                root, "newConnectionSecurityId");
            Object rawPhotos = root.opt("photos");
            if (!(rawPhotos instanceof JSONArray)) throw invalid("photos must be an array");
            JSONArray photoArray = (JSONArray) rawPhotos;
            if (photoArray.length() > MAX_PHOTO_COUNT) throw invalid("too many photo paths");
            List<String> inputPhotos = new ArrayList<>(photoArray.length());
            for (int i = 0; i < photoArray.length(); i++) {
                Object value = photoArray.opt(i);
                if (!(value instanceof String)) {
                    throw invalid("photo path must be a string");
                }
                inputPhotos.add(requiredRelativePhotoPath((String) value));
            }
            PanelConnectionAlternateCleanupReceipt parsed = validate(
                transactionId, oldNamespace, newConnectionSecurityId, inputPhotos);
            if (!parsed.photos.equals(inputPhotos)) {
                throw invalid("photo paths must be sorted and unique");
            }
            return parsed;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("invalid cleanup receipt: " + failure.getClass().getSimpleName());
        }
    }

    /** Serializes fields and photo paths in one stable canonical order. */
    JSONObject toJson() {
        try {
            JSONArray photoArray = new JSONArray();
            for (String path : photos) photoArray.put(path);
            return new JSONObject()
                .put("schema", SCHEMA)
                .put("transactionId", transactionId)
                .put("oldNamespace", oldNamespace)
                .put("newConnectionSecurityId", newConnectionSecurityId)
                .put("photos", photoArray);
        } catch (Exception impossible) {
            throw invalid("cannot serialize cleanup receipt");
        }
    }

    private static void requireExactKeys(JSONObject root) {
        JSONArray names = root.names();
        Set<String> actual = new LinkedHashSet<>();
        for (int i = 0; names != null && i < names.length(); i++) {
            Object value = names.opt(i);
            if (!(value instanceof String)) throw invalid("invalid receipt field");
            actual.add((String) value);
        }
        if (!actual.equals(KEYS)) throw invalid("cleanup receipt fields are incomplete or unknown");
    }

    private static String requiredString(JSONObject root, String key) {
        Object value = root.opt(key);
        if (!(value instanceof String)) throw invalid(key + " must be a string");
        return (String) value;
    }

    private static int exactInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return -1;
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
            ? (int) number : -1;
    }

    private static String requiredLowerHex(String value, String field, int length) {
        String safe = value == null ? "" : value;
        if (safe.length() != length
                || !safe.equals(safe.toLowerCase(Locale.US))
                || !safe.matches("[0-9a-f]+")) {
            throw invalid(field + " must be " + length + " lowercase hexadecimal characters");
        }
        return safe;
    }

    private static String requiredRelativePhotoPath(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_RELATIVE_PATH_LENGTH
                || !value.equals(value.trim()) || value.startsWith("/")
                || value.endsWith("/") || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0 || value.indexOf('\0') >= 0) {
            throw invalid("photo path must be relative to the photos directory");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.length() > MAX_PATH_SEGMENT_LENGTH
                    || !segment.matches("[A-Za-z0-9._-]+")) {
                throw invalid("photo path contains an unsafe segment");
            }
        }
        return value;
    }

    private static Set<String> exactSet(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
