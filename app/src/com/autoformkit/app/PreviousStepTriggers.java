package com.autoformkit.app;

import java.util.Locale;

/** Pure trigger rules for previous-step creation; kept separate so defaults cannot drift silently. */
final class PreviousStepTriggers {
    private PreviousStepTriggers() {
    }

    static boolean configuredAutoCreate(Object enabled, Iterable<?> grades, String unitGrade) {
        if (!Boolean.TRUE.equals(enabled)) return false;
        String wanted = normalizeGrade(unitGrade);
        if (wanted.isEmpty() || grades == null) return false;
        for (Object grade : grades) {
            if (wanted.equals(normalizeGrade(grade))) return true;
        }
        return false;
    }

    static boolean shouldCreate(boolean configuredAutoCreate, boolean manuallyConfirmedRepair) {
        return configuredAutoCreate || manuallyConfirmedRepair;
    }

    private static String normalizeGrade(Object grade) {
        return grade == null ? "" : String.valueOf(grade).trim().toUpperCase(Locale.US);
    }
}
