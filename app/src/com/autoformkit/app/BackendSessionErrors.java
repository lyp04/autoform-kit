package com.autoformkit.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Classifies backend responses that mean the authenticated session is no longer usable. */
final class BackendSessionErrors {
    private BackendSessionErrors() {
    }

    /**
     * Backend-specific session signals supplied by the panel at runtime.
     *
     * <p>The app deliberately has no customer/backend business codes or messages baked into it.
     * Codes are matched after string normalization; messages are case-insensitive substrings.
     */
    static final class Policy {
        private final Set<String> apiCodes = new HashSet<>();
        private final List<String> messagePatterns = new ArrayList<>();

        Policy(Iterable<?> codes, Iterable<String> patterns) {
            if (codes != null) {
                for (Object code : codes) {
                    String normalized = normalizeCode(code);
                    if (!normalized.isEmpty()) apiCodes.add(normalized);
                }
            }
            if (patterns != null) {
                for (String pattern : patterns) {
                    String normalized = normalizeMessage(pattern);
                    if (!normalized.isEmpty()) messagePatterns.add(normalized);
                }
            }
        }

        static Policy empty() {
            return new Policy(null, null);
        }

        boolean matchesApiCode(Object code) {
            String normalized = normalizeCode(code);
            return !normalized.isEmpty() && apiCodes.contains(normalized);
        }

        boolean matchesMessage(String value) {
            String normalized = normalizeMessage(value);
            if (normalized.isEmpty()) return false;
            for (String pattern : messagePatterns) {
                if (normalized.contains(pattern)) return true;
            }
            return false;
        }

        private static String normalizeCode(Object code) {
            if (code == null) return "";
            return String.valueOf(code).trim();
        }

        private static String normalizeMessage(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.US);
        }
    }

    static final class SessionInvalidException extends IOException {
        SessionInvalidException(String message) {
            super(message);
        }
    }

    static boolean isInvalidHttpStatus(int status) {
        return status == 401 || status == 403;
    }

    static boolean isInvalidApiCode(Object code, Policy policy) {
        return policy != null && policy.matchesApiCode(code);
    }

    static boolean isInvalidMessage(String value, Policy policy) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("session expired")
                || lower.contains("session invalid")
                || lower.contains("unauthorized")
                || lower.contains("unauthenticated")
                || (lower.contains("token") && (lower.contains("expired") || lower.contains("invalid")))
                || (policy != null && policy.matchesMessage(text));
    }

    static boolean isSessionInvalid(Throwable error) {
        return find(error) != null;
    }

    static SessionInvalidException find(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SessionInvalidException) {
                return (SessionInvalidException) current;
            }
        }
        return null;
    }
}
