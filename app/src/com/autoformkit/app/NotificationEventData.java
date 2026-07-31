package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Exact provider-neutral payloads accepted by the Panel notification proxy. */
public final class NotificationEventData {
    static final int MAX_ROUND_COUNT = 1_000_000;
    static final int MAX_ROUND_ITEMS = 100;
    static final int MAX_LABEL_LENGTH = 160;
    static final int MAX_IDENTIFIER_LENGTH = 128;
    static final int MAX_DETAIL_LENGTH = 512;
    static final int MAX_TIMESTAMP_LENGTH = 40;

    private static final Pattern ISO_DATE_TIME = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$");
    private static final Set<String> ROUND_FIELDS = new HashSet<>(Arrays.asList(
        "success", "profileLabel", "operatorLabel", "completedAt", "submittedCount",
        "missingItems", "newMissingItems", "recoveredItems", "errors",
        "unconfirmedIdentifiers", "networkAffectedIdentifiers"));
    private static final Set<String> MISSING_ITEM_FIELDS = new HashSet<>(Arrays.asList(
        "label", "affectedCount"));

    private NotificationEventData() {}

    /**
     * Formats a notification completion time as RFC 3339 without relying on the API-24-only
     * {@code SimpleDateFormat} {@code XXX} pattern.
     */
    static String formatCompletedAt(long epochMillis, TimeZone timeZone) {
        if (timeZone == null) throw new IllegalArgumentException("timeZone is required");
        SimpleDateFormat formatter = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        formatter.setTimeZone(timeZone);
        String compact = formatter.format(new Date(epochMillis));
        int offsetStart = compact.length() - 5;
        if (offsetStart < 0) {
            throw new IllegalStateException("formatted timestamp has no numeric offset");
        }
        String offset = compact.substring(offsetStart);
        if ("+0000".equals(offset)) {
            return compact.substring(0, offsetStart) + "Z";
        }
        return compact.substring(0, compact.length() - 2)
            + ":" + compact.substring(compact.length() - 2);
    }

    public static final class MissingItem {
        private final String label;
        private final int affectedCount;

        private MissingItem(String label, int affectedCount) {
            requireString(label, MAX_LABEL_LENGTH, false, "missing item label");
            requireCount(affectedCount, 1, "missing item affectedCount");
            this.label = label;
            this.affectedCount = affectedCount;
        }
    }

    public static MissingItem missingItem(String label, int affectedCount) {
        return new MissingItem(label, affectedCount);
    }

    public static JSONObject submissionSummary(boolean success, int submittedCount,
                                                int errorCount, int unconfirmedPrintCount,
                                                int missingMaterialTypeCount,
                                                int newMissingMaterialTypeCount,
                                                int recoveredMaterialTypeCount,
                                                int networkAffectedCount) {
        JSONObject data = new JSONObject();
        try {
            data.put("success", success);
            data.put("submittedCount", nonNegative(submittedCount));
            data.put("errorCount", nonNegative(errorCount));
            data.put("unconfirmedPrintCount", nonNegative(unconfirmedPrintCount));
            data.put("missingMaterialTypeCount", nonNegative(missingMaterialTypeCount));
            data.put("newMissingMaterialTypeCount", nonNegative(newMissingMaterialTypeCount));
            data.put("recoveredMaterialTypeCount", nonNegative(recoveredMaterialTypeCount));
            data.put("networkAffectedCount", nonNegative(networkAffectedCount));
        } catch (Exception impossible) {}
        return data;
    }

    /** Strict v3 round event. Invalid input is rejected instead of being truncated or guessed. */
    public static JSONObject submissionRound(boolean success, String profileLabel,
                                             String operatorLabel, String completedAt,
                                             int submittedCount,
                                             List<MissingItem> missingItems,
                                             List<String> newMissingItems,
                                             List<String> recoveredItems,
                                             List<String> errors,
                                             List<String> unconfirmedIdentifiers,
                                             List<String> networkAffectedIdentifiers) {
        requireString(profileLabel, MAX_LABEL_LENGTH, false, "profileLabel");
        requireString(operatorLabel, MAX_LABEL_LENGTH, true, "operatorLabel");
        requireString(completedAt, MAX_TIMESTAMP_LENGTH, false, "completedAt");
        if (!ISO_DATE_TIME.matcher(completedAt).matches()) {
            throw new IllegalArgumentException("completedAt must be ISO-8601 with an explicit offset");
        }
        requireCount(submittedCount, 0, "submittedCount");
        requireList(missingItems, "missingItems");
        requireStringList(newMissingItems, MAX_LABEL_LENGTH, "newMissingItems");
        requireStringList(recoveredItems, MAX_LABEL_LENGTH, "recoveredItems");
        requireStringList(errors, MAX_DETAIL_LENGTH, "errors");
        requireStringList(unconfirmedIdentifiers, MAX_IDENTIFIER_LENGTH,
            "unconfirmedIdentifiers");
        requireStringList(networkAffectedIdentifiers, MAX_IDENTIFIER_LENGTH,
            "networkAffectedIdentifiers");

        JSONObject data = new JSONObject();
        JSONArray missing = new JSONArray();
        for (MissingItem item : missingItems) {
            if (item == null) throw new IllegalArgumentException("missingItems contains null");
            JSONObject value = new JSONObject();
            try {
                value.put("label", item.label);
                value.put("affectedCount", item.affectedCount);
            } catch (Exception impossible) {}
            missing.put(value);
        }
        try {
            data.put("success", success);
            data.put("profileLabel", profileLabel);
            data.put("operatorLabel", operatorLabel);
            data.put("completedAt", completedAt);
            data.put("submittedCount", submittedCount);
            data.put("missingItems", missing);
            data.put("newMissingItems", stringArray(newMissingItems));
            data.put("recoveredItems", stringArray(recoveredItems));
            data.put("errors", stringArray(errors));
            data.put("unconfirmedIdentifiers", stringArray(unconfirmedIdentifiers));
            data.put("networkAffectedIdentifiers", stringArray(networkAffectedIdentifiers));
        } catch (Exception impossible) {}
        if (!isValidSubmissionRound(data)) {
            throw new IllegalArgumentException("submission round data is invalid");
        }
        return data;
    }

    static boolean isValidSubmissionRound(JSONObject data) {
        if (data == null || data.length() != ROUND_FIELDS.size()) return false;
        JSONArray names = data.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            if (!ROUND_FIELDS.contains(names.optString(i, ""))) return false;
        }
        if (!(data.opt("success") instanceof Boolean)) return false;
        if (!validString(data.opt("profileLabel"), MAX_LABEL_LENGTH, false)) return false;
        if (!validString(data.opt("operatorLabel"), MAX_LABEL_LENGTH, true)) return false;
        Object completedAt = data.opt("completedAt");
        if (!validString(completedAt, MAX_TIMESTAMP_LENGTH, false)
                || !ISO_DATE_TIME.matcher((String) completedAt).matches()) return false;
        if (!validInteger(data.opt("submittedCount"), 0, MAX_ROUND_COUNT)) return false;
        if (!validMissingItems(data.optJSONArray("missingItems"))) return false;
        return validStringArray(data.optJSONArray("newMissingItems"), MAX_LABEL_LENGTH)
            && validStringArray(data.optJSONArray("recoveredItems"), MAX_LABEL_LENGTH)
            && validStringArray(data.optJSONArray("errors"), MAX_DETAIL_LENGTH)
            && validStringArray(data.optJSONArray("unconfirmedIdentifiers"), MAX_IDENTIFIER_LENGTH)
            && validStringArray(data.optJSONArray("networkAffectedIdentifiers"), MAX_IDENTIFIER_LENGTH);
    }

    private static boolean validMissingItems(JSONArray values) {
        if (values == null || values.length() > MAX_ROUND_ITEMS) return false;
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item == null || item.length() != MISSING_ITEM_FIELDS.size()) return false;
            JSONArray names = item.names();
            for (int j = 0; names != null && j < names.length(); j++) {
                if (!MISSING_ITEM_FIELDS.contains(names.optString(j, ""))) return false;
            }
            if (!validString(item.opt("label"), MAX_LABEL_LENGTH, false)
                    || !validInteger(item.opt("affectedCount"), 1, MAX_ROUND_COUNT)) return false;
        }
        return true;
    }

    private static boolean validStringArray(JSONArray values, int maxLength) {
        if (values == null || values.length() > MAX_ROUND_ITEMS) return false;
        for (int i = 0; i < values.length(); i++) {
            if (!validString(values.opt(i), maxLength, false)) return false;
        }
        return true;
    }

    private static boolean validInteger(Object value, int min, int max) {
        if (!(value instanceof Number)) return false;
        double numeric = ((Number) value).doubleValue();
        return !Double.isNaN(numeric) && !Double.isInfinite(numeric)
            && numeric == Math.rint(numeric) && numeric >= min && numeric <= max;
    }

    private static boolean validString(Object value, int maxLength, boolean allowEmpty) {
        return value instanceof String && ((String) value).length() <= maxLength
            && (allowEmpty || !((String) value).isEmpty());
    }

    private static void requireCount(int value, int min, String name) {
        if (value < min || value > MAX_ROUND_COUNT) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }

    private static void requireString(String value, int maxLength, boolean allowEmpty,
                                      String name) {
        if (!validString(value, maxLength, allowEmpty)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireList(List<?> values, String name) {
        if (values == null || values.size() > MAX_ROUND_ITEMS) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireStringList(List<String> values, int maxLength, String name) {
        requireList(values, name);
        for (String value : values) requireString(value, maxLength, false, name + " item");
    }

    private static JSONArray stringArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
