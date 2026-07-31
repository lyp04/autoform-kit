package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PanelConnectionAlternateCleanupReceiptTest {
    private static final String TRANSACTION = "0123456789abcdef0123456789abcdef";
    private static final String OLD_NAMESPACE = "0123456789abcdef0123";
    private static final String NEW_SECURITY_ID =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    public void validateDeduplicatesSortsAndRoundTripsDeterministically() {
        PanelConnectionAlternateCleanupReceipt receipt =
            PanelConnectionAlternateCleanupReceipt.validate(
                TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID,
                Arrays.asList("z-last.jpg", "batch/a-first.jpg", "z-last.jpg"));

        assertEquals(Arrays.asList("batch/a-first.jpg", "z-last.jpg"), receipt.photos);
        String first = receipt.toJson().toString();
        PanelConnectionAlternateCleanupReceipt parsed =
            PanelConnectionAlternateCleanupReceipt.parse(first);
        assertEquals(TRANSACTION, parsed.transactionId);
        assertEquals(OLD_NAMESPACE, parsed.oldNamespace);
        assertEquals(NEW_SECURITY_ID, parsed.newConnectionSecurityId);
        assertEquals(receipt.photos, parsed.photos);
        assertEquals(first, parsed.toJson().toString());
        assertEquals(first, receipt.toJson().toString());
    }

    @Test
    public void emptyPhotoListIsAValidCanonicalReceipt() {
        PanelConnectionAlternateCleanupReceipt receipt =
            PanelConnectionAlternateCleanupReceipt.validate(
                TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID, Collections.emptyList());

        assertTrue(receipt.photos.isEmpty());
        assertTrue(PanelConnectionAlternateCleanupReceipt.parse(
            receipt.toJson().toString()).photos.isEmpty());
    }

    @Test
    public void validatedPhotoListIsImmutable() {
        PanelConnectionAlternateCleanupReceipt receipt =
            PanelConnectionAlternateCleanupReceipt.validate(
                TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID,
                Collections.singletonList("one.jpg"));
        try {
            receipt.photos.add("two.jpg");
            fail("photo list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void rejectsUnsafeOrNonRelativePhotoPaths() {
        for (String path : new String[]{
                "", "/absolute.jpg", "../escape.jpg", "folder/../escape.jpg",
                "folder/./photo.jpg", "folder//photo.jpg", "folder/", "./photo.jpg",
                "C:\\photo.jpg", "folder\\photo.jpg", "folder:photo.jpg",
                "folder/photo name.jpg"}) {
            expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
                TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID,
                Collections.singletonList(path)));
        }
    }

    @Test
    public void rejectsInvalidIdentifiersAndDigests() {
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            "short", OLD_NAMESPACE, NEW_SECURITY_ID, Collections.emptyList()));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION.toUpperCase(), OLD_NAMESPACE, NEW_SECURITY_ID,
            Collections.emptyList()));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION, "not-a-namespace", NEW_SECURITY_ID, Collections.emptyList()));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID.substring(1),
            Collections.emptyList()));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID.toUpperCase(),
            Collections.emptyList()));
    }

    @Test
    public void parseRejectsUnknownMissingOrWrongTypedFields() throws Exception {
        JSONObject valid = validJson();

        JSONObject unknown = new JSONObject(valid.toString()).put("unexpected", true);
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(unknown.toString()));

        JSONObject missing = new JSONObject(valid.toString());
        missing.remove("oldNamespace");
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(missing.toString()));

        JSONObject wrongPhotos = new JSONObject(valid.toString()).put("photos", "one.jpg");
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(wrongPhotos.toString()));

        JSONObject wrongPathType = new JSONObject(valid.toString())
            .put("photos", new JSONArray().put(7));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(wrongPathType.toString()));
    }

    @Test
    public void parseRejectsUnknownOrNonIntegerSchema() throws Exception {
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(
            new JSONObject(validJson().toString()).put("schema", 2).toString()));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(
            validJson().toString().replace("\"schema\":1", "\"schema\":1.5")));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(
            new JSONObject(validJson().toString()).put("schema", "1").toString()));
    }

    @Test
    public void parseRejectsNonCanonicalDuplicateOrUnsortedPhotos() throws Exception {
        JSONObject duplicate = validJson().put("photos",
            new JSONArray().put("one.jpg").put("one.jpg"));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(duplicate.toString()));

        JSONObject unsorted = validJson().put("photos",
            new JSONArray().put("z.jpg").put("a.jpg"));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(unsorted.toString()));
    }

    @Test
    public void nullOrBlankReceiptAndNullPhotoCollectionFailClosed() {
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse(null));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.parse("  "));
        expectInvalid(() -> PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID, null));
    }

    private static JSONObject validJson() throws Exception {
        return PanelConnectionAlternateCleanupReceipt.validate(
            TRANSACTION, OLD_NAMESPACE, NEW_SECURITY_ID,
            Collections.singletonList("one.jpg")).toJson();
    }

    private static void expectInvalid(ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("invalid cleanup receipt must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
