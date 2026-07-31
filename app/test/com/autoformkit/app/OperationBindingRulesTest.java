package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class OperationBindingRulesTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final String PAIR = repeat('a', 64);
    private static final String WEB = "browser-fingerprint-0001";
    private static final String TOKEN = "opaque-session-token-value";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    private static OperationBindingRules.Binding binding(String kind) {
        return OperationBindingRules.capture(CONNECTION, 7, PAIR, WEB, TOKEN,
            NONCE, kind);
    }

    @Test
    public void exactContextMatchesButEveryIdentityChangeFails() {
        OperationBindingRules.Binding value = binding(OperationBindingRules.OCR);
        assertTrue(value.matchesContext(CONNECTION, 7, PAIR, WEB, TOKEN,
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext("fedcba9876543210fedc", 7, PAIR, WEB, TOKEN,
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext(CONNECTION, 8, PAIR, WEB, TOKEN,
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext(CONNECTION, 7, repeat('b', 64), WEB, TOKEN,
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext(CONNECTION, 7, PAIR, WEB, "new-token",
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext(CONNECTION, 7, PAIR, "new-browser", TOKEN,
            OperationBindingRules.OCR));
        assertFalse(value.matchesContext(CONNECTION, 7, PAIR, WEB, TOKEN,
            OperationBindingRules.USER_INFO));
    }

    @Test
    public void serializedBindingContainsOnlyOneWaySessionFingerprint() throws Exception {
        String raw = binding(OperationBindingRules.LOGIN).toJson().toString();
        assertFalse(raw.contains(TOKEN));
        assertFalse(raw.contains(WEB));
        assertTrue(raw.contains(OperationBindingRules.sessionFingerprint(WEB, TOKEN)));
        assertTrue(OperationBindingRules.parse(new JSONObject(raw))
            .sameAs(binding(OperationBindingRules.LOGIN)));
    }

    @Test
    public void boundValueIsPairAndSessionScopedAndRejectsUnknownFields() throws Exception {
        OperationBindingRules.Binding endpoint = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, TOKEN, NONCE,
            OperationBindingRules.OCR_ENDPOINT);
        String raw = OperationBindingRules.bindValue(
            "https://ocr.example.invalid/read", endpoint).toJson().toString();
        OperationBindingRules.BoundValue parsed =
            OperationBindingRules.parseBoundValue(raw);
        assertEquals("https://ocr.example.invalid/read", parsed.value);
        assertTrue(parsed.binding.matchesContext(CONNECTION, 7, PAIR, WEB, TOKEN,
            OperationBindingRules.OCR_ENDPOINT));
        assertFalse(parsed.binding.matchesContext(CONNECTION, 7, repeat('b', 64), WEB,
            TOKEN, OperationBindingRules.OCR_ENDPOINT));

        JSONObject poisoned = new JSONObject(raw).put("extra", true);
        assertThrows(IllegalArgumentException.class, () ->
            OperationBindingRules.parseBoundValue(poisoned.toString()));
    }

    @Test
    public void preferenceKeyDoesNotLeakSessionMaterialAndChangesWithPairOrToken() {
        String first = OperationBindingRules.scopedValuePreferenceKey("bound_", CONNECTION,
            7, PAIR, WEB, TOKEN);
        String tokenChanged = OperationBindingRules.scopedValuePreferenceKey("bound_",
            CONNECTION, 7, PAIR, WEB, "new-token");
        String pairChanged = OperationBindingRules.scopedValuePreferenceKey("bound_",
            CONNECTION, 7, repeat('b', 64), WEB, TOKEN);
        assertFalse(first.contains(TOKEN));
        assertFalse(first.contains(WEB));
        assertFalse(first.equals(tokenChanged));
        assertFalse(first.equals(pairChanged));
    }

    @Test
    public void malformedNonceKindAndNumericVersionFailClosed() throws Exception {
        JSONObject valid = binding(OperationBindingRules.CAPTCHA).toJson();
        assertThrows(IllegalArgumentException.class, () ->
            OperationBindingRules.parse(new JSONObject(valid.toString()).put("nonce", "short")));
        assertThrows(IllegalArgumentException.class, () ->
            OperationBindingRules.parse(new JSONObject(valid.toString()).put("kind", "other")));
        assertThrows(IllegalArgumentException.class, () ->
            OperationBindingRules.parse(new JSONObject(valid.toString()).put("version", 1.0)));
    }

    @Test
    public void emptySessionIsStillAnExactSessionIdentityForLocalPreview() {
        OperationBindingRules.Binding preview = OperationBindingRules.capture(
            CONNECTION, 1, PAIR, WEB, "", NONCE,
            OperationBindingRules.MAIN_PHOTO);
        assertTrue(preview.matchesContext(CONNECTION, 1, PAIR, WEB, "",
            OperationBindingRules.MAIN_PHOTO));
        assertFalse(preview.matchesContext(CONNECTION, 1, PAIR, WEB, TOKEN,
            OperationBindingRules.MAIN_PHOTO));
        assertFalse(preview.toJson().toString().contains(TOKEN));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
