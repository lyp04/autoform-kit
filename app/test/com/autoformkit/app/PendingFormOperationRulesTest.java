package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PendingFormOperationRulesTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final String PAIR = repeat('a', 64);
    private static final String WEB = "browser-fingerprint-0001";
    private static final String TOKEN = "opaque-session-token-value";
    private static final String ID = "0123456789abcdef0123456789abcdef";

    private static MainDraftSnapshotRules.Binding draftBinding() throws Exception {
        JSONObject profile = new JSONObject()
            .put("id", "sample-form")
            .put("uploadFields", new JSONArray());
        JSONObject config = new JSONObject()
            .put("catalogVersion", 7)
            .put("backendAdapter", new JSONObject().put("version", 1));
        return MainDraftSnapshotRules.currentBinding(CONNECTION, 7, "sample-form",
            profile, config, new JSONObject());
    }

    private static PendingFormOperationRules.Target photoTarget() throws Exception {
        OperationBindingRules.Binding operation = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, TOKEN, ID, PendingFormOperationRules.PHOTO);
        return PendingFormOperationRules.create(PendingFormOperationRules.PHOTO, ID,
            draftBinding(), PAIR, 4, PendingFormOperationRules.ROLE_PHOTO,
            "slot", "detailPhoto", "/data/user/0/example/files/photo.jpg", "",
            operation);
    }

    @Test
    public void exactTargetRoundTripsAndMatchesOnlyExactDraftPairAndSession()
            throws Exception {
        PendingFormOperationRules.Target target = photoTarget();
        PendingFormOperationRules.Target parsed =
            PendingFormOperationRules.parse(target.toJson().toString());
        assertEquals("sample-form", parsed.profileId);
        assertEquals(4, parsed.unitSequence);
        assertEquals("slot", parsed.side);
        assertEquals("detailPhoto", parsed.field);
        assertTrue(parsed.matches(draftBinding(), PAIR, WEB, TOKEN));
        assertFalse(parsed.matches(draftBinding(), repeat('b', 64), WEB, TOKEN));
        assertFalse(parsed.matches(draftBinding(), PAIR, WEB, "different-token"));
    }

    @Test
    public void operationIdMustEqualBoundNonce() throws Exception {
        OperationBindingRules.Binding operation = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, TOKEN, repeat('b', 32),
            PendingFormOperationRules.SCAN);
        assertThrows(IllegalArgumentException.class, () ->
            PendingFormOperationRules.create(PendingFormOperationRules.SCAN, ID,
                draftBinding(), PAIR, 1, PendingFormOperationRules.ROLE_PRIMARY,
                "", "", "", "A", operation));
    }

    @Test
    public void photoAndScannerShapesAreStrict() throws Exception {
        OperationBindingRules.Binding photo = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, TOKEN, ID, PendingFormOperationRules.PHOTO);
        assertThrows(IllegalArgumentException.class, () ->
            PendingFormOperationRules.create(PendingFormOperationRules.PHOTO, ID,
                draftBinding(), PAIR, 1, PendingFormOperationRules.ROLE_PHOTO,
                "slot", "", "/data/photo.jpg", "", photo));

        OperationBindingRules.Binding scan = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, TOKEN, ID, PendingFormOperationRules.SCAN);
        assertThrows(IllegalArgumentException.class, () ->
            PendingFormOperationRules.create(PendingFormOperationRules.SCAN, ID,
                draftBinding(), PAIR, 1, PendingFormOperationRules.ROLE_PRIMARY,
                "front", "", "", "A", scan));
    }

    @Test
    public void unknownFieldsAndIndexLikeZeroSequenceFailClosed() throws Exception {
        JSONObject raw = photoTarget().toJson();
        assertThrows(IllegalArgumentException.class, () ->
            PendingFormOperationRules.parse(new JSONObject(raw.toString())
                .put("unitSequence", 0).toString()));
        assertThrows(IllegalArgumentException.class, () ->
            PendingFormOperationRules.parse(new JSONObject(raw.toString())
                .put("pendingIndex", 3).toString()));
    }

    @Test
    public void localPreviewCameraTargetRoundTripsWithExactEmptySession()
            throws Exception {
        OperationBindingRules.Binding operation = OperationBindingRules.capture(
            CONNECTION, 7, PAIR, WEB, "", ID, PendingFormOperationRules.PHOTO);
        PendingFormOperationRules.Target target = PendingFormOperationRules.create(
            PendingFormOperationRules.PHOTO, ID, draftBinding(), PAIR, 2,
            PendingFormOperationRules.ROLE_PHOTO, "front", "",
            "/data/user/0/example/files/local-preview.jpg", "", operation);
        PendingFormOperationRules.Target restored =
            PendingFormOperationRules.parse(target.toJson().toString());
        assertTrue(restored.matches(draftBinding(), PAIR, WEB, ""));
        assertFalse(restored.matches(draftBinding(), PAIR, WEB, TOKEN));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
