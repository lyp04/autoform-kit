package com.autoformkit.app;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, side-effect-free parser for a future browser-to-App Panel pairing handoff.
 *
 * <p>The v1 link is
 * {@code <applicationId>://pair/v1?panel=<encoded-origin>&ticket=<opaque>&expires=<unix-seconds>}.
 * The ticket is a short-lived, one-time redemption credential. It is deliberately not a Panel
 * read key, and this class has no API that persists it or installs a connection. A future client
 * must redeem it at {@link #REDEEM_PATH} on the exact {@link Request#panelBase}, then pass the
 * returned configuration through the existing safe connection-switch path.
 *
 * <p>A null or empty data string returns null. Merely adding this parser therefore has no effect on
 * normal launches. Any present but non-canonical or unsupported link fails closed.
 */
final class PanelPairingLinkRules {
    static final String LINK_ACTION = "pair";
    static final String LINK_PATH = "/v1";
    static final String REDEEM_PATH = "/api/app-pair/v1/redeem";
    static final long DEFAULT_CLOCK_SKEW_SECONDS = 60L;
    static final long MAX_CLOCK_SKEW_SECONDS = 300L;
    static final long MAX_FUTURE_VALIDITY_SECONDS = 600L;
    static final int MIN_TICKET_LENGTH = 32;
    static final int MAX_TICKET_LENGTH = 512;
    static final int MAX_LINK_LENGTH = 4096;
    static final int MAX_PANEL_ORIGIN_LENGTH = 512;

    enum Error {
        MALFORMED_LINK,
        WRONG_SCHEME,
        WRONG_ACTION,
        INVALID_LINK_STRUCTURE,
        INVALID_PARAMETERS,
        INVALID_PANEL_ORIGIN,
        INVALID_TICKET,
        INVALID_EXPIRATION,
        EXPIRED,
        EXPIRATION_TOO_FAR_IN_FUTURE
    }

    /** Checked external-input failure whose message never contains the link or its ticket. */
    static final class InvalidPairingLinkException extends Exception {
        final Error error;

        private InvalidPairingLinkException(Error error) {
            super(error.name());
            this.error = error;
        }
    }

    /** Immutable handoff request. The one-time ticket must never be stored as a Panel read key. */
    static final class Request {
        /** Canonical HTTPS origin without credentials, path, query, fragment, or default port. */
        final String panelBase;
        /** Opaque one-time redemption ticket; never a long-term Panel read key. */
        final String ticket;
        /** Positive Unix seconds; v1 never accepts an unlimited ticket. */
        final long expiresAtEpochSeconds;

        private Request(String panelBase, String ticket, long expiresAtEpochSeconds) {
            this.panelBase = panelBase;
            this.ticket = ticket;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        }

        String redeemUrl() {
            return panelBase + REDEEM_PATH;
        }
    }

    private PanelPairingLinkRules() {}

    /**
     * Parses one v1 handoff for the exact installed application id.
     *
     * @return an immutable request, or null only when {@code uriString} is null/empty
     */
    static Request parse(String uriString, String expectedScheme, long nowEpochSeconds)
            throws InvalidPairingLinkException {
        return parse(uriString, expectedScheme, nowEpochSeconds, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    static Request parse(String uriString, String expectedScheme, long nowEpochSeconds,
                         long allowedClockSkewSeconds)
            throws InvalidPairingLinkException {
        requireParserInputs(expectedScheme, nowEpochSeconds, allowedClockSkewSeconds);
        if (uriString == null || uriString.isEmpty()) return null;
        if (uriString.length() > MAX_LINK_LENGTH) {
            throw invalid(Error.INVALID_LINK_STRUCTURE);
        }

        final URI link;
        try {
            link = new URI(uriString);
        } catch (Exception malformed) {
            throw invalid(Error.MALFORMED_LINK);
        }
        if (!expectedScheme.equals(link.getScheme())) {
            throw invalid(Error.WRONG_SCHEME);
        }
        // Exact raw authority prevents credentials, ports, encoded aliases, and case variants from
        // being reinterpreted as the action by different URI/Intent implementations.
        if (!LINK_ACTION.equals(link.getRawAuthority())) {
            throw invalid(Error.WRONG_ACTION);
        }
        if (link.isOpaque()
                || !LINK_PATH.equals(link.getRawPath())
                || link.getRawFragment() != null
                || link.getRawQuery() == null || link.getRawQuery().isEmpty()) {
            throw invalid(Error.INVALID_LINK_STRUCTURE);
        }

        final Map<String, String> parameters;
        try {
            parameters = parseParameters(link.getRawQuery());
        } catch (IllegalArgumentException invalidParameters) {
            throw invalid(Error.INVALID_PARAMETERS);
        }
        if (parameters.size() != 3
                || !parameters.containsKey("panel")
                || !parameters.containsKey("ticket")
                || !parameters.containsKey("expires")) {
            throw invalid(Error.INVALID_PARAMETERS);
        }

        final String panelBase;
        try {
            panelBase = normalizePanelOrigin(parameters.get("panel"));
        } catch (IllegalArgumentException invalidOrigin) {
            throw invalid(Error.INVALID_PANEL_ORIGIN);
        }

        String ticket = parameters.get("ticket");
        if (ticket == null
                || ticket.length() < MIN_TICKET_LENGTH
                || ticket.length() > MAX_TICKET_LENGTH
                || !ticket.matches("[A-Za-z0-9_-]+")) {
            throw invalid(Error.INVALID_TICKET);
        }

        String rawExpiration = parameters.get("expires");
        if (rawExpiration == null || !rawExpiration.matches("[1-9][0-9]{0,18}")) {
            throw invalid(Error.INVALID_EXPIRATION);
        }
        final long expiresAt;
        try {
            expiresAt = Long.parseLong(rawExpiration);
        } catch (NumberFormatException overflow) {
            throw invalid(Error.INVALID_EXPIRATION);
        }
        if (nowEpochSeconds > expiresAt
                && nowEpochSeconds - expiresAt > allowedClockSkewSeconds) {
            throw invalid(Error.EXPIRED);
        }
        if (expiresAt > nowEpochSeconds
                && expiresAt - nowEpochSeconds
                    > MAX_FUTURE_VALIDITY_SECONDS + allowedClockSkewSeconds) {
            throw invalid(Error.EXPIRATION_TOO_FAR_IN_FUTURE);
        }
        return new Request(panelBase, ticket, expiresAt);
    }

    /** Rechecks a parsed ticket after it waited behind Capture/Scanner or a permission dialog. */
    static boolean isUsableAt(Request request, long nowEpochSeconds) {
        if (request == null || nowEpochSeconds < 0L) return false;
        return nowEpochSeconds <= request.expiresAtEpochSeconds
            || nowEpochSeconds - request.expiresAtEpochSeconds <= DEFAULT_CLOCK_SKEW_SECONDS;
    }

    private static void requireParserInputs(String expectedScheme, long nowEpochSeconds,
                                            long allowedClockSkewSeconds) {
        if (expectedScheme == null || expectedScheme.length() > 255
                || !expectedScheme.matches("[a-z][a-z0-9+.-]*")) {
            throw new IllegalArgumentException(
                "expectedScheme must be the normalized application id URI scheme");
        }
        if (nowEpochSeconds < 0L) {
            throw new IllegalArgumentException("nowEpochSeconds must not be negative");
        }
        if (allowedClockSkewSeconds < 0L
                || allowedClockSkewSeconds > MAX_CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("allowedClockSkewSeconds is outside the safe range");
        }
    }

    private static Map<String, String> parseParameters(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        String[] pairs = rawQuery.split("&", -1);
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            if (separator <= 0 || separator != pair.lastIndexOf('=')) {
                throw new IllegalArgumentException("query pair is malformed");
            }
            // Parameter names are intentionally not decoded: one canonical spelling per field.
            String key = pair.substring(0, separator);
            if (!("panel".equals(key) || "ticket".equals(key) || "expires".equals(key))) {
                throw new IllegalArgumentException("unsupported query parameter");
            }
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("duplicate query parameter");
            }
            String rawValue = pair.substring(separator + 1);
            if ("panel".equals(key)) requireEncodedOriginValue(rawValue);
            String value = decodeQueryValue(rawValue);
            // Ticket and expiry use query-safe canonical alphabets; accepting percent-encoded
            // aliases would create multiple spellings of the same credential.
            if (!"panel".equals(key) && !rawValue.equals(value)) {
                throw new IllegalArgumentException("non-canonical query value");
            }
            result.put(key, value);
        }
        return result;
    }

    /** The origin's reserved delimiters must be percent encoded in the outer handoff query. */
    private static void requireEncodedOriginValue(String rawValue) {
        boolean sawEscape = false;
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (current == '%') {
                if (i + 2 >= rawValue.length()
                        || Character.digit(rawValue.charAt(i + 1), 16) < 0
                        || Character.digit(rawValue.charAt(i + 2), 16) < 0) {
                    throw new IllegalArgumentException("invalid percent escape");
                }
                sawEscape = true;
                i += 2;
            } else if (current > 0x7f
                    || !(Character.isLetterOrDigit(current)
                        || current == '-' || current == '.'
                        || current == '_' || current == '~')) {
                throw new IllegalArgumentException("Panel origin is not encoded");
            }
        }
        if (!sawEscape) throw new IllegalArgumentException("Panel origin is not encoded");
    }

    /** Percent-decodes as strict UTF-8 without treating '+' as a space. */
    private static String decodeQueryValue(String rawValue) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(rawValue.length());
        for (int i = 0; i < rawValue.length(); i++) {
            char current = rawValue.charAt(i);
            if (current == '%') {
                if (i + 2 >= rawValue.length()) {
                    throw new IllegalArgumentException("incomplete percent escape");
                }
                int high = Character.digit(rawValue.charAt(++i), 16);
                int low = Character.digit(rawValue.charAt(++i), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("invalid percent escape");
                }
                bytes.write((high << 4) | low);
            } else {
                if (current > 0x7f) {
                    throw new IllegalArgumentException("raw query value must be ASCII");
                }
                bytes.write((byte) current);
            }
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes.toByteArray());
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(input);
            return decoded.toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("query value is not UTF-8", malformedUtf8);
        }
    }

    private static String normalizePanelOrigin(String rawOrigin) {
        if (rawOrigin == null || rawOrigin.isEmpty()
                || rawOrigin.length() > MAX_PANEL_ORIGIN_LENGTH) {
            throw new IllegalArgumentException("Panel origin is required");
        }
        final URI parsed;
        try {
            parsed = new URI(rawOrigin).parseServerAuthority();
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Panel origin is malformed", malformed);
        }
        if (parsed.isOpaque() || !"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getRawAuthority() == null || parsed.getHost() == null
                || parsed.getHost().isEmpty() || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Panel origin must be an HTTPS server origin");
        }
        String path = parsed.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("Panel origin must not contain a path");
        }
        int port = parsed.getPort();
        if (port == 0 || port > 65535) {
            throw new IllegalArgumentException("Panel origin port is invalid");
        }

        String host = parsed.getHost();
        final String normalizedHost;
        if (host.startsWith("[") && host.endsWith("]")) {
            if (host.indexOf('%') >= 0) {
                throw new IllegalArgumentException("scoped IPv6 origins are unsupported");
            }
            normalizedHost = host.toLowerCase(Locale.US);
        } else {
            if (host.endsWith(".")) {
                throw new IllegalArgumentException("absolute DNS spelling is unsupported");
            }
            try {
                normalizedHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.US);
            } catch (Exception invalidHost) {
                throw new IllegalArgumentException("Panel origin host is invalid", invalidHost);
            }
            if (normalizedHost.isEmpty() || normalizedHost.length() > 253) {
                throw new IllegalArgumentException("Panel origin host is invalid");
            }
        }
        int normalizedPort = port == 443 ? -1 : port;
        return "https://" + normalizedHost
            + (normalizedPort < 0 ? "" : ":" + normalizedPort);
    }

    private static InvalidPairingLinkException invalid(Error error) {
        return new InvalidPairingLinkException(error);
    }
}
