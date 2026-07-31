package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PanelPairingRedeemerTest {
    @Test
    public void acceptsOnlyTheVersionedMinimalSuccessResponse() {
        PanelPairingRedeemer.Result result = response(200, "application/json; charset=utf-8",
            "{\"version\":1,\"accessKey\":\"EXAMPLE_returned_key_123\"}");

        assertTrue(result.succeeded());
        assertEquals("EXAMPLE_returned_key_123", result.accessKey);

        PanelPairingRedeemer.Result reverseOrder = response(200, "application/json",
            "{\"accessKey\":\"EXAMPLE+base64/value==\",\"version\":1}");
        assertTrue(reverseOrder.succeeded());
    }

    @Test
    public void redirectHttpAndContentTypeFailuresStayGeneric() {
        assertFailure(response(302, "application/json", "{}"),
            PanelPairingRedeemer.Error.REDIRECT);
        assertFailure(response(404, "application/json", "{}"),
            PanelPairingRedeemer.Error.HTTP_STATUS);
        assertFailure(response(200, "text/html", "{}"),
            PanelPairingRedeemer.Error.CONTENT_TYPE);
    }

    @Test
    public void unknownMissingOrWrongTypedFieldsFailClosed() {
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE\",\"extra\":true}");
        assertInvalid("{\"version\":1}");
        assertInvalid("{\"version\":2,\"accessKey\":\"EXAMPLE\"}");
        assertInvalid("{\"version\":1.5,\"accessKey\":\"EXAMPLE\"}");
        assertInvalid("{\"version\":1,\"accessKey\":123}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE\","
            + "\"accessKey\":\"SECOND\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE\"} trailing");
        assertInvalid("not-json");
    }

    @Test
    public void accessKeyCannotBeBlankPaddedOrContainControls() {
        assertInvalid("{\"version\":1,\"accessKey\":\"\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\" padded \"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE:key\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE=key\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE_key===\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"line\\nbreak\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"nul\\u0000byte\"}");
    }

    @Test
    public void acceptsOnlyJsonWhitespace() {
        assertTrue(response(200, "application/json",
            " \t\r\n{\n\"version\" : 1, \"accessKey\" : \"EXAMPLE_key\"\r}\t")
            .succeeded());
        assertInvalid("\u000b{\"version\":1,\"accessKey\":\"EXAMPLE_key\"}");
        assertInvalid("{\"version\":1,\"accessKey\":\"EXAMPLE_key\"}\f");
    }

    @Test
    public void malformedUtf8AndOversizedBodiesAreRejected() {
        PanelPairingRedeemer.Result malformed = PanelPairingRedeemer.parseHttpResponseForTest(
            200, "application/json", new byte[]{(byte) 0xc3, (byte) 0x28});
        assertFailure(malformed, PanelPairingRedeemer.Error.INVALID_RESPONSE);

        byte[] oversized = new byte[16 * 1024 + 1];
        PanelPairingRedeemer.Result tooLarge = PanelPairingRedeemer.parseHttpResponseForTest(
            200, "application/json", oversized);
        assertFailure(tooLarge, PanelPairingRedeemer.Error.RESPONSE_TOO_LARGE);
    }

    private static PanelPairingRedeemer.Result response(
            int status, String contentType, String body) {
        return PanelPairingRedeemer.parseHttpResponseForTest(
            status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(String body) {
        assertFailure(response(200, "application/json", body),
            PanelPairingRedeemer.Error.INVALID_RESPONSE);
    }

    private static void assertFailure(
            PanelPairingRedeemer.Result result, PanelPairingRedeemer.Error expected) {
        assertFalse(result.succeeded());
        assertEquals("", result.accessKey);
        assertEquals(expected, result.error);
    }
}
