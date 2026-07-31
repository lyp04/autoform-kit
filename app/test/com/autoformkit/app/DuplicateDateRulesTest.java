package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class DuplicateDateRulesTest {
    private static final long NOW = 1_900_000_000_000L;
    private static final List<String> LEGACY_UNITS =
        Arrays.asList("seconds", "milliseconds");
    private static final List<String> LEGACY_TRANSFORMS = Arrays.asList(
        "iso_t_to_space",
        "localized_ymd_to_dashes",
        "strip_fractional_suffix",
        "strip_trailing_z",
        "truncate_after_seconds");
    private static final List<String> LEGACY_FORMATS = Arrays.asList(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd");

    @Test
    public void explicitlyConfiguredSecondsAndMillisecondsCanCoexist() {
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "1700000000", Arrays.asList("seconds", "milliseconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "1700000000000", Arrays.asList("seconds", "milliseconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            1_700_000_000.0d, Arrays.asList("seconds", "milliseconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
    }

    @Test
    public void missingOrExplicitExactNumericEpochPrecisionPreservesMilliseconds() {
        DuplicateDateRules.ParsePolicy defaultExact = strictExplicitPolicy(false);
        DuplicateDateRules.ParsePolicy explicitExact = precisionPolicy(
            DuplicateDateRules.NUMERIC_EPOCH_PRECISION_EXACT);

        assertEquals(DuplicateDateRules.NUMERIC_EPOCH_PRECISION_EXACT,
            defaultExact.numericEpochPrecision);
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, defaultExact));
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "1700000000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, explicitExact));
    }

    @Test
    public void minuteFloorAppliesOnlyToNumericEpochResults() {
        DuplicateDateRules.ParsePolicy minuteFloor = precisionPolicy(
            DuplicateDateRules.NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR);

        assertEquals(1_699_999_980_000L, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, minuteFloor));
        assertEquals(1_699_999_980_000L, DuplicateDateRules.parse(
            "1700000000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, minuteFloor));
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "2023-11-14 22:13:20", Collections.emptyList(),
            Collections.emptyList(), Collections.singletonList("yyyy-MM-dd HH:mm:ss"),
            "UTC", NOW, minuteFloor));
    }

    @Test
    public void numericUnitIsNeverGuessedWhenNotConfigured() {
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000", Collections.singletonList("milliseconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000000", Collections.singletonList("seconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "20231114", Collections.emptyList(),
            Collections.singletonList("truncate_after_seconds"),
            Collections.singletonList("yyyyMMdd"), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            20_231_114, Collections.emptyList(),
            Collections.singletonList("truncate_after_seconds"),
            Collections.singletonList("yyyyMMdd"), "UTC", NOW));
    }

    @Test
    public void configuredTextPatternUsesConfiguredTimeZoneAndConsumesTheWholeValue() {
        long parsed = DuplicateDateRules.parse(
            "2023-11-14 22:13:20", Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW);
        assertEquals(1_700_000_000_000L, parsed);
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "2023-11-14 22:13:20 trailing", Collections.emptyList(),
            Collections.emptyList(),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
    }

    @Test
    public void implausibleDatesFailClosed() {
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "946684799", Collections.singletonList("seconds"),
            Collections.emptyList(), Collections.emptyList(), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            Long.toString(NOW + 370L * 24L * 60L * 60L * 1000L),
            Collections.singletonList("milliseconds"), Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW));
    }

    @Test
    public void configuredTransformsNormalizeLegacyTextInDeclaredOrder() {
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "2023-11-14T22:13:20.987Z", Collections.emptyList(),
            Arrays.asList(
                "iso_t_to_space",
                "strip_fractional_suffix",
                "strip_trailing_z"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));

        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "2023-11-14T22:13:20Z", Collections.emptyList(),
            Arrays.asList("iso_t_to_space", "strip_trailing_z"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));

        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            "2023年11月14日22:13:20", Collections.emptyList(),
            Collections.singletonList("localized_ymd_to_dashes"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
    }

    @Test
    public void transformOrderIsObservableAndNotRearranged() {
        String value = "2023-11-14T22:13:20+00:00";
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parse(
            value, Collections.emptyList(),
            Arrays.asList("iso_t_to_space", "truncate_after_seconds"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            value, Collections.emptyList(),
            Arrays.asList("truncate_after_seconds", "iso_t_to_space"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
    }

    @Test
    public void unsupportedOrRepeatedTransformsFailClosed() {
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "2023-11-14T22:13:20", Collections.emptyList(),
            Collections.singletonList("automatic"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "2023-11-14T22:13:20", Collections.emptyList(),
            Arrays.asList("iso_t_to_space", "iso_t_to_space"),
            Collections.singletonList("yyyy-MM-dd HH:mm:ss"), "UTC", NOW));
    }

    @Test
    public void explicitEpochDigitLengthsMatchLegacyTenAndThirteenDigitOracle() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            DuplicateDateRules.ParsePolicy policy = legacyPolicy(false);
            for (String value : Arrays.asList(
                    "999999999", "1700000000", "999999999999", "1700000000000")) {
                assertEquals("value=" + value,
                    legacyTimestampMillis(value, NOW),
                    DuplicateDateRules.parse(value, LEGACY_UNITS, LEGACY_TRANSFORMS,
                        LEGACY_FORMATS, "UTC", NOW, policy));
            }

            // The old overload remains unchanged: explicit digit restrictions are opt-in.
            assertEquals(999_999_999_000L, DuplicateDateRules.parse(
                "999999999", LEGACY_UNITS, Collections.emptyList(),
                Collections.emptyList(), "UTC", NOW));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void explicitNumericTruncationMatchesLegacyNumberCoercion() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            double value = 1_700_000_000.9d;
            assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
                value, LEGACY_UNITS, Collections.emptyList(), Collections.emptyList(),
                "UTC", NOW));
            assertEquals(legacyTimestampMillis(value, NOW), DuplicateDateRules.parse(
                value, LEGACY_UNITS, Collections.emptyList(), Collections.emptyList(),
                "UTC", NOW, legacyPolicy(false)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void explicitPrefixConsumptionMatchesLegacyTrailingText() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            String value = "2023-11-14junk";
            assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW));
            assertEquals(legacyParseText(value, NOW), DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW, legacyPolicy(false)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void epochOnlyPlausibilityMatchesLegacyFormattedDateRange() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            String value = "1999-12-31";
            assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW));
            assertEquals(legacyParseText(value, NOW), DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW, legacyPolicy(false)));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void explicitDeviceTimeZoneMatchesLegacyDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        // A fictional non-UTC device zone is sufficient to prove device-vs-configured behavior;
        // do not mirror a deployment's selected zone into the public fixture.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
        try {
            String value = "2023-11-14 22:13:20";
            long legacy = legacyParseText(value, NOW);
            long device = DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW, legacyPolicy(false));
            long configuredUtc = DuplicateDateRules.parse(
                value, Collections.emptyList(), LEGACY_TRANSFORMS, LEGACY_FORMATS,
                "UTC", NOW, strictExplicitPolicy(false));
            assertEquals(legacy, device);
            assertNotEquals(legacy, configuredUtc);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void rootValueRequiresBothExplicitPathAndPolicyOptIn() {
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parseRootValue(
            "1700000000", DuplicateDateRules.ROOT_VALUE_PATH,
            LEGACY_UNITS, LEGACY_TRANSFORMS, LEGACY_FORMATS, "UTC", NOW,
            legacyPolicy(false)));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parseRootValue(
            "1700000000", "createdAt",
            LEGACY_UNITS, LEGACY_TRANSFORMS, LEGACY_FORMATS, "UTC", NOW,
            legacyPolicy(true)));
        assertEquals(1_700_000_000_000L, DuplicateDateRules.parseRootValue(
            "1700000000", DuplicateDateRules.ROOT_VALUE_PATH,
            LEGACY_UNITS, LEGACY_TRANSFORMS, LEGACY_FORMATS, "UTC", NOW,
            legacyPolicy(true)));
        assertEquals(1_699_999_980_000L, DuplicateDateRules.parseRootValue(
            "1700000000", DuplicateDateRules.ROOT_VALUE_PATH,
            LEGACY_UNITS, LEGACY_TRANSFORMS, LEGACY_FORMATS, "UTC", NOW,
            precisionPolicy(DuplicateDateRules.NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR,
                true)));
    }

    @Test
    public void malformedExplicitPoliciesFailClosed() {
        DuplicateDateRules.ParsePolicy unknownMode = new DuplicateDateRules.ParsePolicy(
            Arrays.asList(10, 13), "coerce", DuplicateDateRules.TEXT_PARSE_FULL,
            DuplicateDateRules.PLAUSIBILITY_ALL,
            DuplicateDateRules.TIME_ZONE_CONFIGURED, false);
        DuplicateDateRules.ParsePolicy duplicateLengths = new DuplicateDateRules.ParsePolicy(
            Arrays.asList(10, 10), DuplicateDateRules.NUMERIC_FRACTION_REJECT,
            DuplicateDateRules.TEXT_PARSE_FULL, DuplicateDateRules.PLAUSIBILITY_ALL,
            DuplicateDateRules.TIME_ZONE_CONFIGURED, false);
        DuplicateDateRules.ParsePolicy unknownPrecision =
            new DuplicateDateRules.ParsePolicy(
                Arrays.asList(10, 13), DuplicateDateRules.NUMERIC_FRACTION_REJECT,
                "second_round", DuplicateDateRules.TEXT_PARSE_FULL,
                DuplicateDateRules.PLAUSIBILITY_ALL,
                DuplicateDateRules.TIME_ZONE_CONFIGURED, false);

        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, unknownMode));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, duplicateLengths));
        assertFalse(unknownPrecision.valid);
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "UTC", NOW, unknownPrecision));
        assertEquals(Long.MIN_VALUE, DuplicateDateRules.parse(
            "1700000000", LEGACY_UNITS, Collections.emptyList(),
            Collections.emptyList(), "Not/A_Time_Zone", NOW,
            strictExplicitPolicy(false)));
    }

    private static DuplicateDateRules.ParsePolicy legacyPolicy(boolean rootValueEnabled) {
        return new DuplicateDateRules.ParsePolicy(
            Arrays.asList(10, 13),
            DuplicateDateRules.NUMERIC_FRACTION_TRUNCATE,
            DuplicateDateRules.TEXT_PARSE_PREFIX,
            DuplicateDateRules.PLAUSIBILITY_EPOCH_ONLY,
            DuplicateDateRules.TIME_ZONE_DEVICE,
            rootValueEnabled);
    }

    private static DuplicateDateRules.ParsePolicy strictExplicitPolicy(
            boolean rootValueEnabled) {
        return new DuplicateDateRules.ParsePolicy(
            Collections.emptyList(),
            DuplicateDateRules.NUMERIC_FRACTION_REJECT,
            DuplicateDateRules.TEXT_PARSE_FULL,
            DuplicateDateRules.PLAUSIBILITY_ALL,
            DuplicateDateRules.TIME_ZONE_CONFIGURED,
            rootValueEnabled);
    }

    private static DuplicateDateRules.ParsePolicy precisionPolicy(String precision) {
        return precisionPolicy(precision, false);
    }

    private static DuplicateDateRules.ParsePolicy precisionPolicy(
            String precision, boolean rootValueEnabled) {
        return new DuplicateDateRules.ParsePolicy(
            Collections.emptyList(),
            DuplicateDateRules.NUMERIC_FRACTION_REJECT,
            precision,
            DuplicateDateRules.TEXT_PARSE_FULL,
            DuplicateDateRules.PLAUSIBILITY_ALL,
            DuplicateDateRules.TIME_ZONE_CONFIGURED,
            rootValueEnabled);
    }

    /** Frozen, deterministic copy of the relevant v1.0.4 date parsing behavior. */
    private static long legacyParseText(String text, long nowMillis) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return Long.MIN_VALUE;
        long timestamp = legacyTimestampMillis(value, nowMillis);
        if (timestamp != Long.MIN_VALUE) return timestamp;
        String normalized = value
            .replace('T', ' ')
            .replace("年", "-")
            .replace("月", "-")
            .replace("日", " ");
        int dot = normalized.indexOf('.');
        if (dot > 0) normalized = normalized.substring(0, dot);
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.length() > 19 && normalized.charAt(10) == ' ') {
            normalized = normalized.substring(0, 19);
        }
        normalized = normalized.trim();
        for (String pattern : LEGACY_FORMATS) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                Date parsed = format.parse(normalized);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {
            }
        }
        return Long.MIN_VALUE;
    }

    private static long legacyTimestampMillis(Object value, long nowMillis) {
        try {
            String text;
            if (value instanceof Number) {
                text = String.valueOf(((Number) value).longValue());
            } else {
                text = value == null ? "" : String.valueOf(value).trim();
            }
            if (!text.matches("\\d{10}|\\d{13}")) return Long.MIN_VALUE;
            long raw = Long.parseLong(text);
            long millis = text.length() == 10 ? raw * 1000L : raw;
            Calendar min = Calendar.getInstance();
            min.set(2000, Calendar.JANUARY, 1, 0, 0, 0);
            min.set(Calendar.MILLISECOND, 0);
            Calendar max = Calendar.getInstance();
            max.setTimeInMillis(nowMillis);
            max.add(Calendar.YEAR, 1);
            return millis >= min.getTimeInMillis() && millis <= max.getTimeInMillis()
                ? millis : Long.MIN_VALUE;
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }
}
