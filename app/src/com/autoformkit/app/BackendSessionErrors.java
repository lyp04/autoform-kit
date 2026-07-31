package com.autoformkit.app;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        private final Set<Integer> httpStatuses = new HashSet<>();
        private final Set<String> apiCodes = new HashSet<>();
        private final List<String> messagePatterns = new ArrayList<>();

        Policy(Iterable<?> codes, Iterable<String> patterns) {
            this(null, codes, patterns);
        }

        Policy(Iterable<?> statuses, Iterable<?> codes, Iterable<String> patterns) {
            if (statuses != null) {
                for (Object status : statuses) {
                    Integer normalized = normalizeHttpStatus(status);
                    if (normalized != null) httpStatuses.add(normalized);
                }
            }
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
            return new Policy(null, null, null);
        }

        boolean matchesHttpStatus(int status) {
            return httpStatuses.contains(status);
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
            return value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        }

        private static Integer normalizeHttpStatus(Object status) {
            if (status instanceof Number) {
                int value = ((Number) status).intValue();
                return value >= 100 && value <= 599 ? value : null;
            }
            if (status == null) return null;
            try {
                int value = Integer.parseInt(String.valueOf(status).trim());
                return value >= 100 && value <= 599 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    static final class SessionInvalidException extends IOException {
        SessionInvalidException(String message) {
            super(message);
        }
    }

    static boolean isInvalidHttpStatus(int status, Policy policy) {
        return policy != null && policy.matchesHttpStatus(status);
    }

    static boolean isInvalidApiCode(Object code, Policy policy) {
        return policy != null && policy.matchesApiCode(code);
    }

    static boolean isInvalidMessage(String value, Policy policy) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return false;
        return policy != null && policy.matchesMessage(text);
    }

    /**
     * Classify only the code field and scalar message leaves declared by the Panel adapter.
     * Request/data echoes and nested objects are intentionally outside the authentication signal.
     */
    static boolean isInvalidStructuredResponse(
            JSONObject body, BackendAdapter.Response response, Policy policy) {
        if (body == null || response == null || policy == null) return false;
        return isInvalidApiCode(response.code(body), policy)
            || isInvalidMessage(response.configuredMessage(body), policy);
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
