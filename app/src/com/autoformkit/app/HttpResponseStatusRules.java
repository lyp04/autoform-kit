package com.autoformkit.app;

/**
 * Status-only decision made before an API response body is parsed.
 *
 * <p>Legacy deployments can return a structured business response with an HTTP 4xx/5xx status.
 * Except for an explicit session-invalid status or a transient gateway status, a present body must
 * therefore reach the configured JSON response/classification contract. Treating every HTTP error
 * as a transport failure would turn a definite rejection into an uncertain submission outcome.
 */
final class HttpResponseStatusRules {
    enum Action {
        PARSE_JSON,
        REDIRECT,
        SESSION_INVALID,
        TRANSIENT_GATEWAY,
        MISSING_BODY
    }

    private HttpResponseStatusRules() {
    }

    static Action beforeJson(int status, boolean sessionInvalid, boolean hasBody) {
        // Backend redirects are never response-contract JSON. Classify them before every
        // configurable status/body rule so a 3xx cannot become a successful login, upload, or
        // submission merely because its response body resembles the configured success shape.
        if (isRedirect(status)) return Action.REDIRECT;
        if (sessionInvalid) return Action.SESSION_INVALID;
        if (isTransientGateway(status)) return Action.TRANSIENT_GATEWAY;
        if (!hasBody) return Action.MISSING_BODY;
        return Action.PARSE_JSON;
    }

    static boolean isRedirect(int status) {
        return status >= 300 && status <= 399;
    }

    static boolean isTransientGateway(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    /** A structured HTTP error may be classified, but can never become a configured success. */
    static boolean allowsConfiguredSuccess(int status) {
        return status >= 200 && status <= 299;
    }
}
