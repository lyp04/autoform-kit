package com.autoformkit.app;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Deterministic duplicate-date parsing controlled entirely by the backend adapter. */
final class DuplicateDateRules {
    static final String TRANSFORM_ISO_T_TO_SPACE = "iso_t_to_space";
    static final String TRANSFORM_LOCALIZED_YMD_TO_DASHES = "localized_ymd_to_dashes";
    static final String TRANSFORM_STRIP_FRACTIONAL_SUFFIX = "strip_fractional_suffix";
    static final String TRANSFORM_STRIP_TRAILING_Z = "strip_trailing_z";
    static final String TRANSFORM_TRUNCATE_AFTER_SECONDS = "truncate_after_seconds";

    static final String NUMERIC_FRACTION_REJECT = "reject";
    static final String NUMERIC_FRACTION_TRUNCATE = "truncate";
    static final String NUMERIC_EPOCH_PRECISION_EXACT = "exact";
    static final String NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR = "minute_floor";
    static final String TEXT_PARSE_FULL = "full";
    static final String TEXT_PARSE_PREFIX = "prefix";
    static final String PLAUSIBILITY_ALL = "all";
    static final String PLAUSIBILITY_EPOCH_ONLY = "epoch_only";
    static final String TIME_ZONE_CONFIGURED = "configured";
    static final String TIME_ZONE_DEVICE = "device";
    static final String ROOT_VALUE_PATH = "$";

    private DuplicateDateRules() {
    }

    static long parse(Object rawValue, List<String> epochUnits, List<String> dateTransforms,
                      List<String> dateFormats, String timeZone, long nowMillis) {
        return parseInternal(rawValue, epochUnits, dateTransforms, dateFormats, timeZone,
            nowMillis, ParsePolicy.strictDefaults(), false);
    }

    /**
     * Parses with explicitly selected compatibility semantics. Invalid or incomplete policy values
     * fail closed. The original overload intentionally retains the current strict behavior.
     */
    static long parse(Object rawValue, List<String> epochUnits, List<String> dateTransforms,
                      List<String> dateFormats, String timeZone, long nowMillis,
                      ParsePolicy policy) {
        return parseInternal(rawValue, epochUnits, dateTransforms, dateFormats, timeZone,
            nowMillis, policy, true);
    }

    /**
     * Explicit opt-in for a duplicate-response item whose configured date path is {@code "$"}.
     * Callers must still resolve ordinary non-root paths themselves.
     */
    static long parseRootValue(Object rootValue, String configuredPath,
                               List<String> epochUnits, List<String> dateTransforms,
                               List<String> dateFormats, String timeZone, long nowMillis,
                               ParsePolicy policy) {
        if (policy == null || !policy.valid || !policy.rootValueEnabled
                || !ROOT_VALUE_PATH.equals(configuredPath)) {
            return Long.MIN_VALUE;
        }
        return parseInternal(rootValue, epochUnits, dateTransforms, dateFormats, timeZone,
            nowMillis, policy, true);
    }

    private static long parseInternal(Object rawValue, List<String> epochUnits,
                                      List<String> dateTransforms, List<String> dateFormats,
                                      String timeZone, long nowMillis, ParsePolicy policy,
                                      boolean explicitPolicy) {
        if (policy == null || !policy.valid) return Long.MIN_VALUE;
        if (rawValue == null) return Long.MIN_VALUE;
        boolean stringValue = rawValue instanceof String;
        String text;
        if (rawValue instanceof Number) {
            double numeric = ((Number) rawValue).doubleValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)
                    || numeric < Long.MIN_VALUE || numeric > Long.MAX_VALUE) {
                return Long.MIN_VALUE;
            }
            if (numeric != Math.rint(numeric)
                    && !NUMERIC_FRACTION_TRUNCATE.equals(policy.numericFractionPolicy)) {
                return Long.MIN_VALUE;
            }
            text = Long.toString(((Number) rawValue).longValue());
        } else {
            text = String.valueOf(rawValue).trim();
        }
        if (text.isEmpty()) return Long.MIN_VALUE;

        TimeZone zone = resolvedTimeZone(timeZone, policy.timeZoneSource, explicitPolicy);
        if (zone == null) return Long.MIN_VALUE;

        List<String> units = epochUnits == null ? Collections.emptyList() : epochUnits;
        if (text.matches("-?\\d+")) {
            int digitCount = text.charAt(0) == '-' ? text.length() - 1 : text.length();
            if (!policy.epochDigitLengths.isEmpty()
                    && !policy.epochDigitLengths.contains(digitCount)) {
                return Long.MIN_VALUE;
            }
            for (String unit : units) {
                try {
                    long value = Long.parseLong(text);
                    long millis;
                    if ("seconds".equals(unit)) millis = Math.multiplyExact(value, 1000L);
                    else if ("milliseconds".equals(unit)) millis = value;
                    else continue;
                    if (plausible(millis, nowMillis,
                            explicitPolicy ? zone : TimeZone.getTimeZone("UTC"))) {
                        return applyNumericEpochPrecision(
                            millis, policy.numericEpochPrecision);
                    }
                } catch (ArithmeticException | NumberFormatException ignored) {
                    // Try the next explicitly configured unit, if any.
                }
            }
            // Integer values are epoch candidates only. Never reinterpret them as formatted text
            // or run text transforms after all explicitly configured units have failed.
            return Long.MIN_VALUE;
        }

        if (stringValue) {
            text = applyTransforms(text, dateTransforms);
            if (text == null || text.isEmpty()) return Long.MIN_VALUE;
        }

        List<String> formats = dateFormats == null ? Collections.emptyList() : dateFormats;
        for (String pattern : formats) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                format.setTimeZone(zone);
                ParsePosition position = new ParsePosition(0);
                Date parsed = format.parse(text, position);
                boolean consumed = TEXT_PARSE_PREFIX.equals(policy.textParseConsumption)
                    ? position.getIndex() > 0 : position.getIndex() == text.length();
                boolean plausible = PLAUSIBILITY_EPOCH_ONLY.equals(policy.plausibilityScope)
                    || plausible(parsed == null ? Long.MIN_VALUE : parsed.getTime(),
                        nowMillis, explicitPolicy ? zone : TimeZone.getTimeZone("UTC"));
                if (parsed != null && consumed && plausible) {
                    return parsed.getTime();
                }
            } catch (RuntimeException ignored) {
                // Invalid private pattern: fail closed; Panel validation and replay tests catch it.
            }
        }
        return Long.MIN_VALUE;
    }

    private static long applyNumericEpochPrecision(long millis, String precision) {
        if (NUMERIC_EPOCH_PRECISION_EXACT.equals(precision)) return millis;
        if (NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR.equals(precision)) {
            return Math.floorDiv(millis, 60_000L) * 60_000L;
        }
        return Long.MIN_VALUE;
    }

    private static TimeZone resolvedTimeZone(String configuredTimeZone, String source,
                                             boolean validateConfigured) {
        if (TIME_ZONE_DEVICE.equals(source)) return TimeZone.getDefault();
        if (!TIME_ZONE_CONFIGURED.equals(source)) return null;
        String value = configuredTimeZone == null ? "" : configuredTimeZone;
        if (validateConfigured && !isKnownTimeZone(value)) return null;
        return TimeZone.getTimeZone(value);
    }

    private static boolean isKnownTimeZone(String value) {
        if (value == null || value.isEmpty()) return false;
        for (String id : TimeZone.getAvailableIDs()) {
            if (id.equals(value)) return true;
        }
        return false;
    }

    static boolean isSupportedTransform(String transform) {
        return TRANSFORM_ISO_T_TO_SPACE.equals(transform)
            || TRANSFORM_LOCALIZED_YMD_TO_DASHES.equals(transform)
            || TRANSFORM_STRIP_FRACTIONAL_SUFFIX.equals(transform)
            || TRANSFORM_STRIP_TRAILING_Z.equals(transform)
            || TRANSFORM_TRUNCATE_AFTER_SECONDS.equals(transform);
    }

    private static String applyTransforms(String text, List<String> configuredTransforms) {
        String transformed = text;
        List<String> transforms = configuredTransforms == null
            ? Collections.emptyList() : configuredTransforms;
        Set<String> seen = new LinkedHashSet<>();
        for (String transform : transforms) {
            if (!isSupportedTransform(transform) || !seen.add(transform)) return null;
            switch (transform) {
                case TRANSFORM_ISO_T_TO_SPACE:
                    transformed = transformed.replace('T', ' ');
                    break;
                case TRANSFORM_LOCALIZED_YMD_TO_DASHES:
                    transformed = transformed
                        .replace("年", "-")
                        .replace("月", "-")
                        .replace("日", " ");
                    break;
                case TRANSFORM_STRIP_FRACTIONAL_SUFFIX:
                    int dot = transformed.indexOf('.');
                    if (dot > 0) transformed = transformed.substring(0, dot);
                    break;
                case TRANSFORM_STRIP_TRAILING_Z:
                    if (transformed.endsWith("Z")) {
                        transformed = transformed.substring(0, transformed.length() - 1).trim();
                    }
                    break;
                case TRANSFORM_TRUNCATE_AFTER_SECONDS:
                    if (transformed.length() > 19 && transformed.charAt(10) == ' ') {
                        transformed = transformed.substring(0, 19);
                    }
                    break;
                default:
                    return null;
            }
        }
        return transformed.trim();
    }

    static boolean plausible(long millis, long nowMillis) {
        return plausible(millis, nowMillis, TimeZone.getTimeZone("UTC"));
    }

    private static boolean plausible(long millis, long nowMillis, TimeZone timeZone) {
        TimeZone zone = timeZone == null ? TimeZone.getTimeZone("UTC") : timeZone;
        Calendar min = Calendar.getInstance(zone);
        min.clear();
        min.set(2000, Calendar.JANUARY, 1, 0, 0, 0);
        Calendar max = Calendar.getInstance(zone);
        max.setTimeInMillis(nowMillis);
        max.add(Calendar.YEAR, 1);
        return millis >= min.getTimeInMillis() && millis <= max.getTimeInMillis();
    }

    /** Closed compatibility policy; unsupported values make every parse fail closed. */
    static final class ParsePolicy {
        final List<Integer> epochDigitLengths;
        final String numericFractionPolicy;
        final String numericEpochPrecision;
        final String textParseConsumption;
        final String plausibilityScope;
        final String timeZoneSource;
        final boolean rootValueEnabled;
        final boolean valid;

        ParsePolicy(List<Integer> epochDigitLengths, String numericFractionPolicy,
                    String textParseConsumption, String plausibilityScope,
                    String timeZoneSource, boolean rootValueEnabled) {
            this(epochDigitLengths, numericFractionPolicy, NUMERIC_EPOCH_PRECISION_EXACT,
                textParseConsumption, plausibilityScope, timeZoneSource, rootValueEnabled);
        }

        ParsePolicy(List<Integer> epochDigitLengths, String numericFractionPolicy,
                    String numericEpochPrecision, String textParseConsumption,
                    String plausibilityScope, String timeZoneSource,
                    boolean rootValueEnabled) {
            List<Integer> lengths = epochDigitLengths == null
                ? null : new ArrayList<>(epochDigitLengths);
            boolean validLengths = lengths != null;
            Set<Integer> seen = new LinkedHashSet<>();
            if (lengths != null) {
                for (Integer length : lengths) {
                    if (length == null || length < 1 || length > 19 || !seen.add(length)) {
                        validLengths = false;
                        break;
                    }
                }
            }
            this.epochDigitLengths = lengths == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(lengths);
            this.numericFractionPolicy = numericFractionPolicy;
            this.numericEpochPrecision = numericEpochPrecision;
            this.textParseConsumption = textParseConsumption;
            this.plausibilityScope = plausibilityScope;
            this.timeZoneSource = timeZoneSource;
            this.rootValueEnabled = rootValueEnabled;
            this.valid = validLengths
                && (NUMERIC_FRACTION_REJECT.equals(numericFractionPolicy)
                    || NUMERIC_FRACTION_TRUNCATE.equals(numericFractionPolicy))
                && (NUMERIC_EPOCH_PRECISION_EXACT.equals(numericEpochPrecision)
                    || NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR.equals(
                        numericEpochPrecision))
                && (TEXT_PARSE_FULL.equals(textParseConsumption)
                    || TEXT_PARSE_PREFIX.equals(textParseConsumption))
                && (PLAUSIBILITY_ALL.equals(plausibilityScope)
                    || PLAUSIBILITY_EPOCH_ONLY.equals(plausibilityScope))
                && (TIME_ZONE_CONFIGURED.equals(timeZoneSource)
                    || TIME_ZONE_DEVICE.equals(timeZoneSource));
        }

        private static ParsePolicy strictDefaults() {
            return new ParsePolicy(Collections.emptyList(), NUMERIC_FRACTION_REJECT,
                TEXT_PARSE_FULL, PLAUSIBILITY_ALL, TIME_ZONE_CONFIGURED, false);
        }
    }
}
