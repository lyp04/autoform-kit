package com.autoformkit.app;

import java.io.IOException;
import java.util.Locale;

/** Classifies backend responses that mean the authenticated session is no longer usable. */
final class BackendSessionErrors {
    private BackendSessionErrors() {
    }

    static final class SessionInvalidException extends IOException {
        SessionInvalidException(String message) {
            super(message);
        }
    }

    static boolean isInvalidHttpStatus(int status) {
        return status == 401 || status == 403;
    }

    static boolean isInvalidApiCode(Object code) {
        if (code == null) return false;
        try {
            return Integer.parseInt(String.valueOf(code).trim()) == 40002;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    static boolean isInvalidMessage(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.US);
        return text.contains("强制下线")
                || text.contains("另一个地点登录")
                || text.contains("在别处登录")
                || text.contains("登录已失效")
                || text.contains("登录已过期")
                || lower.contains("logged in elsewhere")
                || lower.contains("logged in at another")
                || lower.contains("session expired")
                || lower.contains("session invalid")
                || lower.contains("forced logout")
                || lower.contains("force logout")
                || lower.contains("unauthorized")
                || lower.contains("unauthenticated")
                || (lower.contains("token") && (lower.contains("expired") || lower.contains("invalid")));
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
