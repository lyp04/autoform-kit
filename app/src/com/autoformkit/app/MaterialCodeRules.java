package com.autoformkit.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Matches only material codes explicitly published in the active profile. */
final class MaterialCodeRules {
    private MaterialCodeRules() {}

    static List<String> findKnownCodes(String text, Collection<String> knownCodes,
                                       Collection<String> excludedCodes) {
        return findKnownCodes(text, knownCodes, excludedCodes, "");
    }

    static List<String> findKnownCodes(String text, Collection<String> knownCodes,
                                       Collection<String> excludedCodes, String configuredPattern) {
        String source = text == null ? "" : text;
        Set<String> excluded = excludedCodes == null
            ? Collections.emptySet() : new LinkedHashSet<>(excludedCodes);
        List<String> out = new ArrayList<>();
        if (knownCodes == null) return out;
        Set<String> candidates = new LinkedHashSet<>();
        String regex = configuredPattern == null ? "" : configuredPattern.trim();
        boolean patternConfigured = !regex.isEmpty();
        if (!regex.isEmpty()) {
            try {
                java.util.regex.Matcher matcher = Pattern.compile(regex).matcher(source);
                while (matcher.find()) {
                    String value = matcher.group();
                    if (value != null && !value.isEmpty()) candidates.add(value);
                }
            } catch (RuntimeException ignored) {
                // A configured but invalid recognizer must fail closed; do not silently change
                // matching semantics to substring search.
                return out;
            }
        }
        for (String raw : knownCodes) {
            String code = raw == null ? "" : raw.trim();
            boolean found = patternConfigured ? candidates.contains(code) : source.contains(code);
            if (!code.isEmpty() && !excluded.contains(code) && found && !out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    /**
     * Fail-closed material-code extraction for automatic recovery after a rejected submit.
     * Unlike the legacy helper above, this path never falls back to substring matching: the
     * Panel must provide a valid recognizer and the caller must pass only a configured response
     * message, never a serialized response or echoed request payload.
     */
    static List<String> findKnownCodesForAutomaticRecovery(
            String configuredMessage, Collection<String> knownCodes,
            Collection<String> excludedCodes, String configuredPattern) {
        List<String> out = new ArrayList<>();
        String regex = configuredPattern == null ? "" : configuredPattern.trim();
        if (regex.isEmpty() || knownCodes == null) return out;

        Set<String> candidates = new LinkedHashSet<>();
        try {
            java.util.regex.Matcher matcher = Pattern.compile(regex).matcher(
                configuredMessage == null ? "" : configuredMessage);
            while (matcher.find()) {
                String value = matcher.group();
                if (value != null && !value.isEmpty()) candidates.add(value);
            }
        } catch (RuntimeException ignored) {
            return out;
        }

        Set<String> excluded = excludedCodes == null
            ? Collections.emptySet() : new LinkedHashSet<>(excludedCodes);
        for (String raw : knownCodes) {
            String code = raw == null ? "" : raw.trim();
            if (!code.isEmpty() && candidates.contains(code) && !excluded.contains(code)
                    && !out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    /** Preserve source order while applying an explicit exclusion list. */
    static List<String> excludeCodes(Collection<String> codes, Collection<String> excludedCodes) {
        Set<String> excluded = excludedCodes == null
            ? Collections.emptySet() : new LinkedHashSet<>(excludedCodes);
        List<String> out = new ArrayList<>();
        if (codes == null) return out;
        for (String raw : codes) {
            String code = raw == null ? "" : raw.trim();
            if (!code.isEmpty() && !excluded.contains(code) && !out.contains(code)) {
                out.add(code);
            }
        }
        return out;
    }

    static Pattern highlightPattern(Collection<String> knownCodes) {
        if (knownCodes == null) return null;
        List<String> escaped = new ArrayList<>();
        for (String raw : knownCodes) {
            String value = raw == null ? "" : raw.trim();
            if (!value.isEmpty()) escaped.add(Pattern.quote(value));
        }
        if (escaped.isEmpty()) return null;
        // List.sort/comparingInt/reversed are unavailable on API 23 without core-library
        // desugaring. Collections.sort keeps the same longest-first ordering on every minSdk.
        Collections.sort(escaped, (left, right) -> Integer.compare(right.length(), left.length()));
        return Pattern.compile(String.join("|", escaped));
    }
}
