package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PanelPairingLinkRulesTest {
    private static final String SCHEME = "com.autoformkit.app";
    private static final long NOW = 2_000_000_000L;
    private static final String TICKET =
        "Abcdefghijklmnopqrstuvwxyz012345_-";

    @Test
    public void absentIntentDataIsAnExplicitNoOp() throws Exception {
        assertNull(PanelPairingLinkRules.parse(null, SCHEME, NOW));
        assertNull(PanelPairingLinkRules.parse("", SCHEME, NOW));
    }

    @Test
    public void parsesPackageBoundOneTimeTicketAndNormalizesHttpsOrigin() throws Exception {
        PanelPairingLinkRules.Request request = parse(
            "panel=HTTPS%3A%2F%2FPanel.Example%3A443%2F"
                + "&ticket=" + TICKET + "&expires=" + (NOW + 120L));

        assertEquals("https://panel.example", request.panelBase);
        assertEquals(TICKET, request.ticket);
        assertEquals(NOW + 120L, request.expiresAtEpochSeconds);
        assertEquals("https://panel.example/api/app-pair/v1/redeem", request.redeemUrl());
    }

    @Test
    public void nonDefaultPortIsPreserved() throws Exception {
        PanelPairingLinkRules.Request request = parse(
            "ticket=" + TICKET + "&panel=https%3A%2F%2FPANEL.example%3A8443"
                + "&expires=" + (NOW + 120L));

        assertEquals("https://panel.example:8443", request.panelBase);
    }

    @Test
    public void bracketedIpv6OriginRemainsUnambiguous() throws Exception {
        PanelPairingLinkRules.Request request = parse(
            "panel=https%3A%2F%2F%5B2001%3ADB8%3A%3A1%5D%3A8443"
                + "&ticket=" + TICKET + "&expires=" + (NOW + 120L));

        assertEquals("https://[2001:db8::1]:8443", request.panelBase);
    }

    @Test
    public void expiryAllowsOnlyBoundedClockSkewAndFutureLifetime() throws Exception {
        PanelPairingLinkRules.parse(linkWithExpiration(NOW - 60L), SCHEME, NOW);
        assertInvalid(linkWithExpiration(NOW - 61L),
            PanelPairingLinkRules.Error.EXPIRED);

        PanelPairingLinkRules.parse(linkWithExpiration(NOW + 660L), SCHEME, NOW);
        assertInvalid(linkWithExpiration(NOW + 661L),
            PanelPairingLinkRules.Error.EXPIRATION_TOO_FAR_IN_FUTURE);

        PanelPairingLinkRules.parse(linkWithExpiration(NOW - 5L), SCHEME, NOW, 5L);
        assertInvalid(linkWithExpiration(NOW - 5L), 4L,
            PanelPairingLinkRules.Error.EXPIRED);

        PanelPairingLinkRules.Request delayed = PanelPairingLinkRules.parse(
            linkWithExpiration(NOW + 10L), SCHEME, NOW);
        assertEquals(true, PanelPairingLinkRules.isUsableAt(delayed, NOW + 70L));
        assertEquals(false, PanelPairingLinkRules.isUsableAt(delayed, NOW + 71L));
    }

    @Test
    public void callerCannotDisableExpiryWithAnUnboundedClockSkew() {
        assertThrows(IllegalArgumentException.class,
            () -> PanelPairingLinkRules.parse(validLink(), SCHEME, NOW, -1L));
        assertThrows(IllegalArgumentException.class,
            () -> PanelPairingLinkRules.parse(validLink(), SCHEME, NOW, 301L));
        assertThrows(IllegalArgumentException.class,
            () -> PanelPairingLinkRules.parse(validLink(), SCHEME, -1L));
        assertThrows(IllegalArgumentException.class,
            () -> PanelPairingLinkRules.parse(validLink(), "NOT AN APP ID", NOW));
    }

    @Test
    public void schemeMustMatchTheExactInstalledApplicationId() throws Exception {
        assertInvalid(validLink().replace(SCHEME + ":", "com.autoformkit.other:"),
            PanelPairingLinkRules.Error.WRONG_SCHEME);
        assertInvalid(validLink().replace(SCHEME + ":", "Com.Autoformkit.App:"),
            PanelPairingLinkRules.Error.WRONG_SCHEME);

        PanelPairingLinkRules.Request debug = PanelPairingLinkRules.parse(
            validLink().replace(SCHEME + ":", SCHEME + ".debug:"),
            SCHEME + ".debug", NOW);
        assertEquals(TICKET, debug.ticket);
    }

    @Test
    public void actionAndVersionPathMustBeExact() throws Exception {
        assertInvalid(validLink().replace("//pair/", "//connect/"),
            PanelPairingLinkRules.Error.WRONG_ACTION);
        assertInvalid(validLink().replace("//pair/", "//PAIR/"),
            PanelPairingLinkRules.Error.WRONG_ACTION);
        assertInvalid(validLink().replace("//pair/", "//pair@other/"),
            PanelPairingLinkRules.Error.WRONG_ACTION);
        assertInvalid(validLink().replace("/v1?", "/v2?"),
            PanelPairingLinkRules.Error.INVALID_LINK_STRUCTURE);
        assertInvalid(validLink().replace("/v1?", "/v1/?"),
            PanelPairingLinkRules.Error.INVALID_LINK_STRUCTURE);
        assertInvalid(validLink() + "#fragment",
            PanelPairingLinkRules.Error.INVALID_LINK_STRUCTURE);
    }

    @Test
    public void panelValueMustBePercentEncodedAndOnlyAnHttpsOrigin() throws Exception {
        assertParametersInvalid("panel=https://panel.example&ticket=" + TICKET
            + "&expires=" + (NOW + 120L));
        assertOriginInvalid("http://panel.example");
        assertOriginInvalid("https://" + "user" + ":" + "password" + "@panel.example");
        assertOriginInvalid("https://panel.example/api");
        assertOriginInvalid("https://panel.example?mode=pair");
        assertOriginInvalid("https://panel.example#pair");
        assertOriginInvalid("https://panel.example:0");
        assertOriginInvalid("https://panel.example:65536");
        assertOriginInvalid("https://panel.example.");
        assertOriginInvalid("https://exa_mple.example");
    }

    @Test
    public void ticketUsesAConstrainedUrlSafeAlphabetAndLength() throws Exception {
        parse(queryWithTicket(repeat("a", 32)));
        parse(queryWithTicket(repeat("z", 512)));
        assertTicketInvalid(repeat("a", 31));
        assertTicketInvalid(repeat("a", 513));
        assertTicketInvalid("abcdefghijklmnopqrstuvwxyz01234+");
        assertTicketInvalid("abcdefghijklmnopqrstuvwxyz01234/");
        assertParametersInvalid(queryWithTicket("abcdefghijklmnopqrstuvwxyz01234="));
        assertParametersInvalid(queryWithTicket("abcdefghijklmnopqrstuvwxyz%3001234"));
    }

    @Test
    public void longTermKeyUnknownDuplicateOrMissingFieldsFailClosed() throws Exception {
        assertParametersInvalid(validQuery() + "&readKey=must-never-be-accepted");
        assertParametersInvalid(validQuery() + "&ticket=" + TICKET);
        assertParametersInvalid("panel=https%3A%2F%2Fpanel.example&ticket=" + TICKET);
        assertParametersInvalid("panel=https%3A%2F%2Fpanel.example&expires=" + (NOW + 120L));
        assertParametersInvalid("%70anel=https%3A%2F%2Fpanel.example&ticket=" + TICKET
            + "&expires=" + (NOW + 120L));
        assertParametersInvalid("panel=https%3A%2F%2Fpanel.example&&ticket=" + TICKET
            + "&expires=" + (NOW + 120L));
    }

    @Test
    public void expirationIsMandatoryCanonicalAndFinite() throws Exception {
        assertExpirationInvalid("");
        assertExpirationInvalid("0");
        assertExpirationInvalid("01");
        assertExpirationInvalid("-1");
        assertParametersInvalid("panel=https%3A%2F%2Fpanel.example&ticket=" + TICKET
            + "&expires=%32");
        assertExpirationInvalid("9223372036854775808");
    }

    @Test
    public void malformedPresentDataIsNeverMistakenForNoLink() throws Exception {
        assertInvalid(" ", PanelPairingLinkRules.Error.MALFORMED_LINK);
        assertInvalid("not a uri", PanelPairingLinkRules.Error.MALFORMED_LINK);
        assertInvalid(SCHEME + "://pair/v1?panel=https%ZZ%2F%2Fpanel.example&ticket="
            + TICKET + "&expires=" + (NOW + 120L),
            PanelPairingLinkRules.Error.MALFORMED_LINK);
    }

    @Test
    public void exportedEntryHasABoundedMainThreadParseSurface() throws Exception {
        assertInvalid(validLink() + repeat("x", PanelPairingLinkRules.MAX_LINK_LENGTH),
            PanelPairingLinkRules.Error.INVALID_LINK_STRUCTURE);
        assertOriginInvalid("https://" + repeat("a", 500) + ".example");
    }

    private static PanelPairingLinkRules.Request parse(String query) throws Exception {
        return PanelPairingLinkRules.parse(SCHEME + "://pair/v1?" + query, SCHEME, NOW);
    }

    private static String validLink() {
        return SCHEME + "://pair/v1?" + validQuery();
    }

    private static String validQuery() {
        return "panel=https%3A%2F%2Fpanel.example&ticket=" + TICKET
            + "&expires=" + (NOW + 120L);
    }

    private static String queryWithTicket(String ticket) {
        return "panel=https%3A%2F%2Fpanel.example&ticket=" + ticket
            + "&expires=" + (NOW + 120L);
    }

    private static String linkWithExpiration(long expiration) {
        return SCHEME + "://pair/v1?panel=https%3A%2F%2Fpanel.example&ticket=" + TICKET
            + "&expires=" + expiration;
    }

    private static void assertOriginInvalid(String origin) throws Exception {
        assertInvalid(SCHEME + "://pair/v1?panel=" + encode(origin) + "&ticket=" + TICKET
            + "&expires=" + (NOW + 120L), PanelPairingLinkRules.Error.INVALID_PANEL_ORIGIN);
    }

    private static void assertTicketInvalid(String ticket) throws Exception {
        assertInvalid(SCHEME + "://pair/v1?" + queryWithTicket(ticket),
            PanelPairingLinkRules.Error.INVALID_TICKET);
    }

    private static void assertParametersInvalid(String query) throws Exception {
        assertInvalid(SCHEME + "://pair/v1?" + query,
            PanelPairingLinkRules.Error.INVALID_PARAMETERS);
    }

    private static void assertExpirationInvalid(String expiration) throws Exception {
        assertInvalid(SCHEME + "://pair/v1?panel=https%3A%2F%2Fpanel.example&ticket="
            + TICKET + "&expires=" + expiration,
            PanelPairingLinkRules.Error.INVALID_EXPIRATION);
    }

    private static void assertInvalid(String link, PanelPairingLinkRules.Error expected)
            throws Exception {
        assertInvalid(link, PanelPairingLinkRules.DEFAULT_CLOCK_SKEW_SECONDS, expected);
    }

    private static void assertInvalid(String link, long skew,
                                      PanelPairingLinkRules.Error expected) throws Exception {
        try {
            PanelPairingLinkRules.parse(link, SCHEME, NOW, skew);
        } catch (PanelPairingLinkRules.InvalidPairingLinkException failure) {
            assertEquals(expected, failure.error);
            assertEquals(expected.name(), failure.getMessage());
            return;
        }
        throw new AssertionError("Expected " + expected);
    }

    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte item : bytes) {
            int current = item & 0xff;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_'
                    || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append(String.format(java.util.Locale.US, "%%%02X", current));
            }
        }
        return encoded.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> expected, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Unexpected exception", failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
