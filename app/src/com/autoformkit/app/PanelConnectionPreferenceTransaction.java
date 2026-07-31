package com.autoformkit.app;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small crash-recovery helper for the preference keys changed by one Panel switch. */
final class PanelConnectionPreferenceTransaction {
    private PanelConnectionPreferenceTransaction() {}

    static Map<String, ?> snapshot(SharedPreferences preferences) {
        if (preferences == null) {
            throw new IllegalArgumentException("preferences are required");
        }
        return new HashMap<>(preferences.getAll());
    }

    /**
     * Restores only the keys staged by a failed Panel switch.
     *
     * <p>Android updates the SharedPreferences in-memory map before its disk write completes. A
     * false commit must therefore be followed by this restore before the Activity can do more work.
     * Even if the restore's disk write also reports failure, its synchronous commit has already put
     * the complete old values back in this process; the first failed write left the old durable
     * snapshot authoritative for the next process.</p>
     */
    static boolean restore(SharedPreferences preferences, Map<String, ?> before,
                           Set<String> touchedKeys) {
        if (preferences == null || before == null || touchedKeys == null
                || touchedKeys.isEmpty()) return false;
        // Validate the complete restore plan before staging any part of it.
        for (String prefKey : touchedKeys) {
            if (prefKey == null || prefKey.isEmpty()) return false;
            if (before.containsKey(prefKey) && !supported(before.get(prefKey))) return false;
        }

        SharedPreferences.Editor rollback = preferences.edit();
        for (String prefKey : touchedKeys) {
            if (!before.containsKey(prefKey)) {
                rollback.remove(prefKey);
                continue;
            }
            Object value = before.get(prefKey);
            if (value instanceof String) {
                rollback.putString(prefKey, (String) value);
            } else if (value instanceof Boolean) {
                rollback.putBoolean(prefKey, (Boolean) value);
            } else if (value instanceof Integer) {
                rollback.putInt(prefKey, (Integer) value);
            } else if (value instanceof Long) {
                rollback.putLong(prefKey, (Long) value);
            } else if (value instanceof Float) {
                rollback.putFloat(prefKey, (Float) value);
            } else {
                Set<String> strings = new HashSet<>();
                for (Object item : (Set<?>) value) strings.add((String) item);
                rollback.putStringSet(prefKey, strings);
            }
        }
        return rollback.commit();
    }

    private static boolean supported(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Float) return true;
        if (!(value instanceof Set)) return false;
        for (Object item : (Set<?>) value) {
            if (!(item instanceof String)) return false;
        }
        return true;
    }
}
