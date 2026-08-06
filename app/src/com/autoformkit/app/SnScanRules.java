package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deployment-neutral scanner and OCR rules supplied by the active profile. */
final class SnScanRules {
    static final String SOURCE_LABEL = "label";
    static final String SOURCE_PREFIX = "prefix";
    static final String SOURCE_GENERAL = "general";
    static final String SOURCE_OCR = "ocr";
    static final String SOURCE_BARCODE = "barcode";
    static final String SOURCE_ENTERED = "entered";
    static final String MODE_RANKED = "ranked";
    static final String MODE_ORDERED = "ordered";
    static final String AUTO_TEXT_ALWAYS = "always";
    static final String AUTO_TEXT_FALLBACK = "fallback";

    private static final long AUTO_TEXT_PRIMARY_DELAY_MS = 350L;
    private static final long AUTO_TEXT_FALLBACK_DELAY_MS = 1200L;
    private static final long AUTO_TEXT_INTERVAL_MS = 650L;
    private static final Pattern LETTER = Pattern.compile("[A-Z]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final List<String> DEFAULT_ORDER;
    private static final Set<String> ALL_VALUE_SOURCES;

    static {
        List<String> order = new ArrayList<>();
        order.add(SOURCE_PREFIX);
        order.add(SOURCE_GENERAL);
        DEFAULT_ORDER = Collections.unmodifiableList(order);
        Set<String> sources = new LinkedHashSet<>();
        sources.add(SOURCE_OCR);
        sources.add(SOURCE_BARCODE);
        sources.add(SOURCE_ENTERED);
        ALL_VALUE_SOURCES = Collections.unmodifiableSet(sources);
    }

    private SnScanRules() {}

    enum Rejection {
        NONE,
        INVALID_POLICY,
        EMPTY,
        WRONG_LENGTH,
        TOO_SHORT,
        TOO_LONG,
        NUMERIC_ONLY,
        MISSING_LETTER_OR_DIGIT,
        REJECTED_SUBSTRING,
        INVALID_CHARACTERS
    }

    /**
     * Parsed scanner policy. Missing optional fields preserve the generic scanner's established
     * defaults; malformed configured fields invalidate the policy instead of silently widening it.
     */
    static final class Policy {
        final boolean valid;
        final int expectedLength;
        final Set<String> applyExpectedLengthTo;
        final List<Integer> allowedLengths;
        final Set<String> applyAllowedLengthsTo;
        final int minLength;
        final int maxLength;
        final boolean minLengthConfigured;
        final boolean maxLengthConfigured;
        final List<String> preferredPrefixes;
        final String autoTextMode;
        final boolean rejectNumericOnly;
        final String candidateMode;
        final List<String> candidateOrder;
        final boolean requireLetterAndDigit;
        final List<String> rejectedSubstrings;
        final List<String> stripLabels;
        final Set<String> applyCandidateRulesTo;
        final Set<String> stripLabelsFrom;
        final String labelMatchMode;
        final String candidateCharacterMode;
        final String caseMode;
        final boolean removeWhitespace;

        private Policy(boolean valid, int expectedLength, Set<String> applyExpectedLengthTo,
                       List<Integer> allowedLengths, Set<String> applyAllowedLengthsTo,
                       int minLength, int maxLength,
                       boolean minLengthConfigured, boolean maxLengthConfigured,
                       List<String> preferredPrefixes, String autoTextMode,
                       boolean rejectNumericOnly, String candidateMode,
                       List<String> candidateOrder, boolean requireLetterAndDigit,
                       List<String> rejectedSubstrings, List<String> stripLabels,
                       Set<String> applyCandidateRulesTo, Set<String> stripLabelsFrom,
                       String labelMatchMode, String candidateCharacterMode,
                       String caseMode, boolean removeWhitespace) {
            this.valid = valid;
            this.expectedLength = expectedLength;
            this.applyExpectedLengthTo = applyExpectedLengthTo;
            this.allowedLengths = allowedLengths;
            this.applyAllowedLengthsTo = applyAllowedLengthsTo;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.minLengthConfigured = minLengthConfigured;
            this.maxLengthConfigured = maxLengthConfigured;
            this.preferredPrefixes = preferredPrefixes;
            this.autoTextMode = autoTextMode;
            this.rejectNumericOnly = rejectNumericOnly;
            this.candidateMode = candidateMode;
            this.candidateOrder = candidateOrder;
            this.requireLetterAndDigit = requireLetterAndDigit;
            this.rejectedSubstrings = rejectedSubstrings;
            this.stripLabels = stripLabels;
            this.applyCandidateRulesTo = applyCandidateRulesTo;
            this.stripLabelsFrom = stripLabelsFrom;
            this.labelMatchMode = labelMatchMode;
            this.candidateCharacterMode = candidateCharacterMode;
            this.caseMode = caseMode;
            this.removeWhitespace = removeWhitespace;
        }

        static Policy from(JSONObject raw) {
            JSONObject value = raw == null ? new JSONObject() : raw;
            boolean valid = true;

            IntValue expected = positiveInteger(value, "expectedLength", 0);
            IntegerList allowedLengths = positiveIntegerList(value, "allowedLengths");
            IntValue min = positiveInteger(value, "minLength", 6);
            IntValue max = positiveInteger(value, "maxLength", 64);
            valid &= expected.valid && allowedLengths.valid && min.valid && max.valid;
            if (min.value > max.value) valid = false;
            if (expected.value > 0 && (expected.value < min.value || expected.value > max.value)) {
                // Only configured candidate bounds constrain expectedLength. The implicit 6..64
                // candidate defaults must not invalidate an otherwise valid legacy expected length.
                if (min.configured || max.configured) valid = false;
            }
            if (allowedLengths.configured) {
                for (int allowedLength : allowedLengths.values) {
                    if ((min.configured && allowedLength < min.value)
                            || (max.configured && allowedLength > max.value)) {
                        valid = false;
                    }
                }
            }
            // expectedLength may remain as an old-App fallback while a new App uses the set. The
            // fallback must still be one of the newly accepted lengths so the two policies do not
            // describe contradictory identities.
            if (expected.configured && allowedLengths.configured
                    && !allowedLengths.values.contains(expected.value)) {
                valid = false;
            }

            StringValue autoText = oneOf(value, "autoTextMode", "",
                new String[]{"", AUTO_TEXT_ALWAYS, AUTO_TEXT_FALLBACK});
            StringValue candidateMode = oneOf(value, "candidateMode", MODE_RANKED,
                new String[]{MODE_RANKED, MODE_ORDERED});
            StringValue caseMode = oneOf(value, "caseMode", "upper",
                new String[]{"upper", "preserve"});
            StringValue labelMatchMode = oneOf(value, "labelMatchMode", "literal",
                new String[]{"literal", "compact_optional_slash"});
            StringValue candidateCharacterMode = oneOf(value, "candidateCharacterMode", "identifier",
                new String[]{"identifier", "alphanumeric"});
            BooleanValue rejectNumeric = bool(value, "rejectNumericOnly", false);
            BooleanValue requireMixed = bool(value, "requireLetterAndDigit", false);
            BooleanValue removeWhitespace = bool(value, "removeWhitespace", true);
            valid &= autoText.valid && candidateMode.valid && caseMode.valid
                && labelMatchMode.valid && candidateCharacterMode.valid
                && rejectNumeric.valid && requireMixed.valid && removeWhitespace.valid;

            StringList prefixes = stringList(value, "preferredPrefixes", true);
            if (!prefixes.configured) prefixes = stringList(value, "preferredSnPrefixes", true);
            StringList rejected = stringList(value, "rejectedSubstrings", false);
            StringList labels = stringList(value, "stripLabels", false);
            StringList order = stringList(value, "candidateOrder", false);
            StringList ruleScopes = stringList(value, "applyCandidateRulesTo", false);
            StringList stripScopes = sourceList(value, "stripLabelsFrom");
            StringList expectedLengthScopes = sourceList(value, "applyExpectedLengthTo");
            StringList allowedLengthScopes = sourceList(value, "applyAllowedLengthsTo");
            valid &= prefixes.valid && rejected.valid && labels.valid && order.valid
                && ruleScopes.valid && stripScopes.valid && expectedLengthScopes.valid
                && allowedLengthScopes.valid;

            List<String> normalizedOrder = order.configured ? normalizeOrder(order.values) : DEFAULT_ORDER;
            if (order.configured && normalizedOrder.size() != order.values.size()) valid = false;
            if (order.configured && normalizedOrder.isEmpty()) valid = false;
            if (MODE_ORDERED.equals(candidateMode.value) && !order.configured) valid = false;
            if (normalizedOrder.contains(SOURCE_LABEL) && labels.values.isEmpty()) valid = false;
            if (normalizedOrder.contains(SOURCE_PREFIX) && prefixes.values.isEmpty()
                    && MODE_ORDERED.equals(candidateMode.value)) valid = false;
            if ("alphanumeric".equals(candidateCharacterMode.value)) {
                for (String prefix : prefixes.values) {
                    if (!prefix.matches("[A-Za-z0-9]+")) valid = false;
                }
            }
            Set<String> normalizedScopes = normalizeRuleScopes(ruleScopes);
            if (ruleScopes.configured && normalizedScopes.size() != ruleScopes.values.size()) valid = false;
            if (!normalizedScopes.contains(SOURCE_OCR)) valid = false;
            Set<String> normalizedStripScopes = normalizeSources(stripScopes,
                Collections.singleton(SOURCE_OCR));
            if (stripScopes.configured && normalizedStripScopes.size() != stripScopes.values.size()) {
                valid = false;
            }
            if (stripScopes.configured && !normalizedStripScopes.isEmpty()
                    && labels.values.isEmpty()) {
                valid = false;
            }
            Set<String> normalizedExpectedLengthScopes = normalizeSources(
                expectedLengthScopes, ALL_VALUE_SOURCES);
            if (expectedLengthScopes.configured
                    && (normalizedExpectedLengthScopes.isEmpty()
                        || normalizedExpectedLengthScopes.size()
                            != expectedLengthScopes.values.size())) {
                valid = false;
            }
            if (expectedLengthScopes.configured && !expected.configured) valid = false;
            Set<String> normalizedAllowedLengthScopes = normalizeSources(
                allowedLengthScopes, ALL_VALUE_SOURCES);
            if (allowedLengthScopes.configured
                    && (normalizedAllowedLengthScopes.isEmpty()
                        || normalizedAllowedLengthScopes.size()
                            != allowedLengthScopes.values.size())) {
                valid = false;
            }
            if (allowedLengthScopes.configured && !allowedLengths.configured) valid = false;

            return new Policy(
                valid,
                expected.value,
                normalizedExpectedLengthScopes,
                allowedLengths.values,
                normalizedAllowedLengthScopes,
                min.value,
                max.value,
                min.configured,
                max.configured,
                normalizeTokens(prefixes.values, true),
                autoText.value,
                rejectNumeric.value,
                candidateMode.value,
                normalizedOrder,
                requireMixed.value,
                normalizeTokens(rejected.values, false),
                normalizeTokens(labels.values, false),
                normalizedScopes,
                normalizedStripScopes,
                labelMatchMode.value,
                candidateCharacterMode.value,
                caseMode.value,
                removeWhitespace.value
            );
        }

        String normalize(String raw) {
            return normalizeForSource(raw, SOURCE_ENTERED);
        }

        String normalizeForSource(String raw, String source) {
            String value = raw == null ? "" : raw.trim();
            if ("upper".equals(caseMode)) value = value.toUpperCase(Locale.US);
            if (stripLabelsFrom.contains(source)) {
                value = stripLeadingLabel(value, stripLabels, labelMatchMode);
            }
            if (removeWhitespace) value = value.replaceAll("\\s+", "");
            return value.trim();
        }

        Rejection enteredRejection(String normalized) {
            return rejection(normalized, SOURCE_ENTERED);
        }

        Rejection captureRejection(String normalized) {
            return rejection(normalized, SOURCE_OCR);
        }

        Rejection barcodeRejection(String normalized) {
            return rejection(normalized, SOURCE_BARCODE);
        }

        Rejection rejectionForSource(String normalized, String source) {
            if (!ALL_VALUE_SOURCES.contains(source)) return Rejection.INVALID_POLICY;
            return rejection(normalized, source);
        }

        boolean appliesExpectedLengthTo(String source) {
            return expectedLength > 0 && applyExpectedLengthTo.contains(source)
                && !appliesAllowedLengthsTo(source);
        }

        boolean appliesAllowedLengthsTo(String source) {
            return !allowedLengths.isEmpty() && applyAllowedLengthsTo.contains(source);
        }

        boolean matchesConfiguredLength(String source, int actualLength) {
            if (appliesAllowedLengthsTo(source)) return allowedLengths.contains(actualLength);
            return !appliesExpectedLengthTo(source) || actualLength == expectedLength;
        }

        List<Integer> requiredLengthsForSource(String source) {
            if (appliesAllowedLengthsTo(source)) return allowedLengths;
            if (appliesExpectedLengthTo(source)) {
                return Collections.singletonList(expectedLength);
            }
            return Collections.emptyList();
        }

        boolean acceptsEntered(String normalized) {
            return enteredRejection(normalized) == Rejection.NONE;
        }

        boolean acceptsCapture(String normalized) {
            return captureRejection(normalized) == Rejection.NONE;
        }

        private Rejection rejection(String normalized, String source) {
            if (!valid) return Rejection.INVALID_POLICY;
            String value = normalized == null ? "" : normalized;
            if (value.isEmpty()) return Rejection.EMPTY;
            if (!matchesConfiguredLength(source, value.length())) {
                return Rejection.WRONG_LENGTH;
            }
            if (rejectNumericOnly && isPureNumeric(value)) return Rejection.NUMERIC_ONLY;
            if (!applyCandidateRulesTo.contains(source)) return Rejection.NONE;
            String allowedPattern = "alphanumeric".equals(candidateCharacterMode)
                ? "[A-Z0-9]+" : "[A-Z0-9._/-]+";
            if (!value.toUpperCase(Locale.US).matches(allowedPattern)) {
                return Rejection.INVALID_CHARACTERS;
            }
            boolean discreteLengthApplied = !requiredLengthsForSource(source).isEmpty();
            if (value.length() < minLength
                    && (minLengthConfigured || !discreteLengthApplied)) {
                return Rejection.TOO_SHORT;
            }
            if (value.length() > maxLength
                    && (maxLengthConfigured || !discreteLengthApplied)) {
                return Rejection.TOO_LONG;
            }
            if (requireLetterAndDigit
                    && (!LETTER.matcher(value).find() || !DIGIT.matcher(value).find())) {
                return Rejection.MISSING_LETTER_OR_DIGIT;
            }
            String comparison = value.toUpperCase(Locale.US);
            for (String rejected : rejectedSubstrings) {
                if (!rejected.isEmpty() && comparison.contains(rejected.toUpperCase(Locale.US))) {
                    return Rejection.REJECTED_SUBSTRING;
                }
            }
            return Rejection.NONE;
        }
    }

    static final class Candidate {
        final String value;
        final String source;
        final int score;
        final int encounter;

        Candidate(String value, String source, int score, int encounter) {
            this.value = value;
            this.source = source;
            this.score = score;
            this.encounter = encounter;
        }
    }

    static void addTextCandidates(List<Candidate> candidates, String raw, int lineBonus,
                                  Policy policy) {
        if (candidates == null || policy == null || !policy.valid || raw == null || raw.trim().isEmpty()) {
            return;
        }
        String line = "upper".equals(policy.caseMode) ? raw.toUpperCase(Locale.US) : raw;
        Set<String> enabled = new LinkedHashSet<>(policy.candidateOrder);

        if (enabled.contains(SOURCE_LABEL)) {
            for (String label : policy.stripLabels) {
                Matcher labelMatcher = labelPattern(label, policy.labelMatchMode).matcher(line);
                while (labelMatcher.find()) {
                    int start = labelMatcher.end();
                    while (start < line.length() && isLabelSeparator(line.charAt(start))) start++;
                    Matcher matcher = candidateTokenPattern(policy).matcher(
                        line.substring(start));
                    if (matcher.find() && matcher.start() == 0) {
                        addCandidate(candidates, matcher.group(1), SOURCE_LABEL, 80 + lineBonus, policy);
                    }
                }
            }
        }

        String compact = compactOcrLine(line);
        if (enabled.contains(SOURCE_PREFIX)) {
            for (String prefix : policy.preferredPrefixes) {
                Matcher matcher = prefixedCandidatePattern(policy, prefix).matcher(compact);
                while (matcher.find()) {
                    addCandidate(candidates, matcher.group(1), SOURCE_PREFIX, 58 + lineBonus, policy);
                }
            }
        }

        if (enabled.contains(SOURCE_GENERAL)) {
            Matcher matcher = candidateTokenPattern(policy).matcher(line);
            while (matcher.find()) {
                addCandidate(candidates, matcher.group(1), SOURCE_GENERAL, lineBonus, policy);
            }
        }
    }

    static String selectBest(List<Candidate> candidates, Policy policy) {
        if (candidates == null || candidates.isEmpty() || policy == null || !policy.valid) return "";
        List<Candidate> copy = new ArrayList<>(candidates);
        if (MODE_ORDERED.equals(policy.candidateMode)) {
            Map<String, Integer> sourceRank = new LinkedHashMap<>();
            for (int i = 0; i < policy.candidateOrder.size(); i++) {
                sourceRank.put(policy.candidateOrder.get(i), i);
            }
            Collections.sort(copy, (left, right) -> {
                int a = sourceRank.containsKey(left.source) ? sourceRank.get(left.source) : Integer.MAX_VALUE;
                int b = sourceRank.containsKey(right.source) ? sourceRank.get(right.source) : Integer.MAX_VALUE;
                if (a != b) return Integer.compare(a, b);
                return Integer.compare(left.encounter, right.encounter);
            });
        } else {
            // Keep minSdk 23 support: Comparator.comparingInt/reversed/thenComparingInt are
            // exposed only from Android API 24 without core-library desugaring.
            Collections.sort(copy, (left, right) -> {
                if (left.score != right.score) return Integer.compare(right.score, left.score);
                return Integer.compare(left.encounter, right.encounter);
            });
        }
        return copy.get(0).value;
    }

    static String selectTextCandidate(Collection<String> lines, Policy policy) {
        List<Candidate> candidates = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) addTextCandidates(candidates, line, 0, policy);
        }
        return selectBest(candidates, policy);
    }

    static boolean shouldReadText(Policy policy, boolean ocrOnly, boolean manualRequested,
                                  long elapsedMs, long sinceLastAttemptMs) {
        if (manualRequested) return true;
        if (ocrOnly) {
            return elapsedMs >= AUTO_TEXT_PRIMARY_DELAY_MS
                && sinceLastAttemptMs >= AUTO_TEXT_INTERVAL_MS;
        }
        if (policy == null || !policy.valid || policy.autoTextMode.isEmpty()) return false;
        long delay = AUTO_TEXT_ALWAYS.equals(policy.autoTextMode)
            ? AUTO_TEXT_PRIMARY_DELAY_MS : AUTO_TEXT_FALLBACK_DELAY_MS;
        return elapsedMs >= delay && sinceLastAttemptMs >= AUTO_TEXT_INTERVAL_MS;
    }

    private static Set<String> normalizeRuleScopes(StringList configured) {
        return normalizeSources(configured, Collections.singleton(SOURCE_OCR));
    }

    private static Set<String> normalizeSources(StringList configured,
                                                Collection<String> defaults) {
        Set<String> result = new LinkedHashSet<>();
        if (!configured.configured) {
            result.addAll(defaults);
            return Collections.unmodifiableSet(result);
        }
        for (String raw : configured.values) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
            if (SOURCE_OCR.equals(value) || SOURCE_BARCODE.equals(value)
                    || SOURCE_ENTERED.equals(value)) {
                result.add(value);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean hasPreferredPrefix(String value, Collection<String> prefixes) {
        if (value == null || prefixes == null) return false;
        String comparison = value.toUpperCase(Locale.US);
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty()
                    && comparison.startsWith(prefix.toUpperCase(Locale.US))) return true;
        }
        return false;
    }

    /** Formats a configured discrete length set without embedding deployment-specific values. */
    static String formatLengths(List<Integer> lengths, String conjunction) {
        if (lengths == null || lengths.isEmpty()) return "";
        if (lengths.size() == 1) return String.valueOf(lengths.get(0));
        String joiner = conjunction == null || conjunction.trim().isEmpty()
            ? "or" : conjunction.trim();
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lengths.size(); index++) {
            if (index > 0) {
                out.append(index == lengths.size() - 1
                    ? " " + joiner + " " : ", ");
            }
            out.append(lengths.get(index));
        }
        return out.toString();
    }

    static boolean cameraScanEnabled(JSONObject plugin) {
        if (plugin == null || !plugin.has("scan")) return true;
        return plugin.opt("scan") instanceof Boolean && (Boolean) plugin.opt("scan");
    }

    /** Compatibility helper for tests and old intent callers; profile parsing uses Policy.from. */
    static List<String> normalizePrefixes(String[] configured) {
        List<String> raw = new ArrayList<>();
        if (configured != null) Collections.addAll(raw, configured);
        return normalizeTokens(raw, true);
    }

    private static void addCandidate(List<Candidate> candidates, String raw, String source,
                                     int bonus, Policy policy) {
        String value = normalizeCandidate(raw, policy);
        if (!policy.acceptsCapture(value)) return;
        int score = bonus + Math.min(24, value.length());
        if (hasPreferredPrefix(value, policy.preferredPrefixes)) score += 60;
        if (DIGIT.matcher(value).find()) score += 16;
        if (value.length() >= 12 && value.length() <= 20) score += 14;
        candidates.add(new Candidate(value, source, score, candidates.size()));
    }

    private static String normalizeCandidate(String raw, Policy policy) {
        String value = policy.normalizeForSource(raw, SOURCE_OCR);
        return "alphanumeric".equals(policy.candidateCharacterMode)
            ? value.replaceAll("[^A-Za-z0-9]", "")
            : value.replaceAll("[^A-Za-z0-9._/-]", "");
    }

    private static String compactOcrLine(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._:：/-]", "");
    }

    private static String stripLeadingLabel(String raw, List<String> labels, String matchMode) {
        if (raw == null || labels == null || labels.isEmpty()) return raw == null ? "" : raw;
        for (String label : labels) {
            Matcher matcher = labelPattern(label, matchMode).matcher(raw);
            if (!matcher.find() || matcher.start() != 0) continue;
            int index = matcher.end();
            while (index < raw.length() && isLabelSeparator(raw.charAt(index))) index++;
            return raw.substring(index);
        }
        return raw;
    }

    private static boolean isLabelSeparator(char value) {
        return Character.isWhitespace(value) || value == ':' || value == '：' || value == '#'
            || value == '=' || value == '-';
    }

    private static Pattern labelPattern(String label, String matchMode) {
        if (!"compact_optional_slash".equals(matchMode)) {
            return Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE);
        }
        String compact = label == null ? "" : label.replaceAll("[\\s/]+", "");
        if (compact.isEmpty()) return Pattern.compile("(?!x)x");
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0) pattern.append("[\\s/]*");
            pattern.append(Pattern.quote(String.valueOf(compact.charAt(i))));
        }
        return Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static Pattern candidateTokenPattern(Policy policy) {
        int maximum = candidateTokenMaximum(policy);
        String characters = candidateCharacterClass(policy);
        String body = "alphanumeric".equals(policy.candidateCharacterMode)
            ? "[A-Z0-9]{1," + maximum + "}"
            : "[A-Z0-9][A-Z0-9._/-]{0," + (maximum - 1) + "}";
        return Pattern.compile("(?<!" + characters + ")(" + body + ")(?!"
            + characters + ")", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern prefixedCandidatePattern(Policy policy, String prefix) {
        String characters = candidateCharacterClass(policy);
        String body = Pattern.quote(prefix.toUpperCase(Locale.US))
            + candidateSuffixPattern(policy);
        return Pattern.compile("(?<!" + characters + ")(" + body + ")(?!"
            + characters + ")", Pattern.CASE_INSENSITIVE);
    }

    private static String candidateSuffixPattern(Policy policy) {
        int maximum = candidateTokenMaximum(policy);
        return "alphanumeric".equals(policy.candidateCharacterMode)
            ? "[A-Z0-9]{0," + (maximum - 1) + "}"
            : "[A-Z0-9._/-]{0," + (maximum - 1) + "}";
    }

    private static String candidateCharacterClass(Policy policy) {
        return "alphanumeric".equals(policy.candidateCharacterMode)
            ? "[A-Z0-9]" : "[A-Z0-9._/-]";
    }

    private static int candidateTokenMaximum(Policy policy) {
        int maximum = 64;
        if (policy != null && policy.appliesAllowedLengthsTo(SOURCE_OCR)) {
            for (int allowed : policy.allowedLengths) maximum = Math.max(maximum, allowed);
        }
        return maximum;
    }

    private static boolean isPureNumeric(String value) {
        return value != null && value.matches("[0-9]+");
    }

    private static List<String> normalizeOrder(List<String> raw) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : raw) {
            String item = value == null ? "" : value.trim().toLowerCase(Locale.US);
            if (!(SOURCE_LABEL.equals(item) || SOURCE_PREFIX.equals(item) || SOURCE_GENERAL.equals(item))) {
                continue;
            }
            if (!seen.add(item)) continue;
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> normalizeTokens(List<String> raw, boolean identifierCharactersOnly) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            String item = value == null ? "" : value.trim().toUpperCase(Locale.US);
            if (item.isEmpty()) continue;
            if (identifierCharactersOnly && !item.matches("[A-Z0-9._/-]+")) continue;
            result.add(item);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static IntValue positiveInteger(JSONObject value, String key, int fallback) {
        if (!value.has(key)) return new IntValue(fallback, false, true);
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) return new IntValue(fallback, true, false);
        double number = ((Number) raw).doubleValue();
        int integer = ((Number) raw).intValue();
        return new IntValue(integer, true, number == integer && integer > 0 && integer <= 256);
    }

    private static IntegerList positiveIntegerList(JSONObject value, String key) {
        if (!value.has(key)) {
            return new IntegerList(Collections.emptyList(), false, true);
        }
        Object raw = value.opt(key);
        if (!(raw instanceof JSONArray)) {
            return new IntegerList(Collections.emptyList(), true, false);
        }
        JSONArray array = (JSONArray) raw;
        List<Integer> items = new ArrayList<>();
        Set<Integer> unique = new LinkedHashSet<>();
        boolean valid = array.length() > 0 && array.length() <= 256;
        for (int index = 0; index < array.length(); index++) {
            Object item = array.opt(index);
            if (!(item instanceof Byte || item instanceof Short
                    || item instanceof Integer || item instanceof Long)) {
                valid = false;
                continue;
            }
            long exact = ((Number) item).longValue();
            if (exact < 1L || exact > 256L || !unique.add((int) exact)) {
                valid = false;
                continue;
            }
            items.add((int) exact);
        }
        return new IntegerList(
            Collections.unmodifiableList(items), true, valid);
    }

    private static BooleanValue bool(JSONObject value, String key, boolean fallback) {
        if (!value.has(key)) return new BooleanValue(fallback, true);
        Object raw = value.opt(key);
        return raw instanceof Boolean
            ? new BooleanValue((Boolean) raw, true)
            : new BooleanValue(fallback, false);
    }

    private static StringValue oneOf(JSONObject value, String key, String fallback, String[] allowed) {
        if (!value.has(key)) return new StringValue(fallback, true);
        Object raw = value.opt(key);
        if (!(raw instanceof String)) return new StringValue(fallback, false);
        String configured = ((String) raw).trim();
        for (String item : allowed) {
            if (item.equals(configured)) return new StringValue(configured, true);
        }
        return new StringValue(fallback, false);
    }

    private static StringList stringList(JSONObject value, String key, boolean identifierCharactersOnly) {
        if (!value.has(key)) return new StringList(Collections.emptyList(), false, true);
        Object raw = value.opt(key);
        if (!(raw instanceof JSONArray)) return new StringList(Collections.emptyList(), true, false);
        JSONArray array = (JSONArray) raw;
        List<String> items = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        boolean valid = array.length() <= 32;
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) {
                valid = false;
                continue;
            }
            String token = ((String) item).trim();
            if (token.isEmpty() || token.length() > 64
                    || (identifierCharactersOnly && !token.matches("[A-Za-z0-9._/-]+"))) {
                valid = false;
                continue;
            }
            String duplicateKey = token.toUpperCase(Locale.US);
            if (!unique.add(duplicateKey)) {
                valid = false;
                continue;
            }
            items.add(token);
        }
        return new StringList(items, true, valid);
    }

    private static StringList sourceList(JSONObject value, String key) {
        if (!value.has(key)) return new StringList(Collections.emptyList(), false, true);
        Object raw = value.opt(key);
        if (!(raw instanceof JSONArray)) {
            return new StringList(Collections.emptyList(), true, false);
        }
        JSONArray array = (JSONArray) raw;
        List<String> items = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        boolean valid = array.length() <= ALL_VALUE_SOURCES.size();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) {
                valid = false;
                continue;
            }
            String configured = (String) item;
            String source = configured.trim();
            if (!configured.equals(source) || !ALL_VALUE_SOURCES.contains(source)
                    || !unique.add(source)) {
                valid = false;
            }
            items.add(source);
        }
        return new StringList(items, true, valid);
    }

    private static final class IntValue {
        final int value;
        final boolean configured;
        final boolean valid;
        IntValue(int value, boolean configured, boolean valid) {
            this.value = value;
            this.configured = configured;
            this.valid = valid;
        }
    }

    private static final class IntegerList {
        final List<Integer> values;
        final boolean configured;
        final boolean valid;
        IntegerList(List<Integer> values, boolean configured, boolean valid) {
            this.values = values;
            this.configured = configured;
            this.valid = valid;
        }
    }

    private static final class BooleanValue {
        final boolean value;
        final boolean valid;
        BooleanValue(boolean value, boolean valid) { this.value = value; this.valid = valid; }
    }

    private static final class StringValue {
        final String value;
        final boolean valid;
        StringValue(String value, boolean valid) { this.value = value; this.valid = valid; }
    }

    private static final class StringList {
        final List<String> values;
        final boolean configured;
        final boolean valid;
        StringList(List<String> values, boolean configured, boolean valid) {
            this.values = values;
            this.configured = configured;
            this.valid = valid;
        }
    }
}
