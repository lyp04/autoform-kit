package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AlternateEntryAsyncReservationTest {
    private static String hex(char value, int length) {
        char[] out = new char[length];
        Arrays.fill(out, value);
        return new String(out);
    }

    private static AlternateEntryAsyncReservation photo() {
        return AlternateEntryAsyncReservation.create(
            AlternateEntryAsyncReservation.KIND_PHOTO, hex('1', 32), hex('2', 32),
            hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
            "exact-session\nexact-binding", hex('f', 64),
            "/private/exact-reserved-photo.jpg");
    }

    @Test
    public void strictRoundTripBindsPairGuardBaseStateAndExactPhotoPath() {
        AlternateEntryAsyncReservation parsed = AlternateEntryAsyncReservation.parse(
            photo().toJson().toString());

        assertEquals(hex('1', 32), parsed.reservationToken);
        assertEquals(hex('2', 32), parsed.resultContinuationToken);
        assertTrue(parsed.matches(AlternateEntryAsyncReservation.KIND_PHOTO,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
            "exact-session\nexact-binding", hex('f', 64),
            "/private/exact-reserved-photo.jpg"));
        assertFalse(parsed.matches(AlternateEntryAsyncReservation.KIND_PHOTO,
            hex('a', 64), hex('b', 20), 8, hex('c', 64), hex('d', 64), hex('e', 64),
            "exact-session\nexact-binding", hex('f', 64),
            "/private/exact-reserved-photo.jpg"));
        assertFalse(parsed.matches(AlternateEntryAsyncReservation.KIND_PHOTO,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
            "different-guard", hex('f', 64), "/private/exact-reserved-photo.jpg"));
        assertFalse(parsed.matches(AlternateEntryAsyncReservation.KIND_PHOTO,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
            "exact-session\nexact-binding", hex('0', 64),
            "/private/exact-reserved-photo.jpg"));
        assertFalse(parsed.matches(AlternateEntryAsyncReservation.KIND_PHOTO,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
            "exact-session\nexact-binding", hex('f', 64),
            "/private/a-later-photo.jpg"));
    }

    @Test
    public void scanCannotCarryAPathAndPhotoCannotOmitOne() {
        assertThrows(IllegalArgumentException.class, () ->
            AlternateEntryAsyncReservation.create(
                AlternateEntryAsyncReservation.KIND_SCAN, hex('1', 32), hex('2', 32),
                hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
                "guard", hex('f', 64), "/private/not-allowed.jpg"));
        assertThrows(IllegalArgumentException.class, () ->
            AlternateEntryAsyncReservation.create(
                AlternateEntryAsyncReservation.KIND_PHOTO, hex('1', 32), hex('2', 32),
                hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('d', 64), hex('e', 64),
                "guard", hex('f', 64), ""));
    }

    @Test
    public void malformedOrExtendedStoredObservationCannotBecomeAReservation() throws Exception {
        JSONObject unknown = photo().toJson().put("pageOpen", true);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryAsyncReservation.parse(unknown.toString()));

        JSONObject missingResultToken = photo().toJson();
        missingResultToken.remove("resultContinuationToken");
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryAsyncReservation.parse(missingResultToken.toString()));

        JSONObject nonIntegerVersion = photo().toJson().put("catalogVersion", 7.5d);
        assertThrows(IllegalArgumentException.class,
            () -> AlternateEntryAsyncReservation.parse(nonIntegerVersion.toString()));
    }

    @Test
    public void restartRoundTripCannotRemintOrBorrowALaterResultToken() {
        AlternateEntryAsyncReservation restored = AlternateEntryAsyncReservation.parse(
            photo().toJson().toString());
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(
                restored.connectionNamespace, restored.catalogVersion,
                restored.panelPairSha256, Collections.emptyList(), "",
                Collections.singletonList(
                    UnsafeCandidateContinuationRules.alternateReservation(
                        restored.reservationToken,
                        restored.resultContinuationToken)),
                false, false, false);

        assertNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            lease, restored.reservationToken, hex('3', 32),
            restored.connectionNamespace, restored.catalogVersion,
            restored.panelPairSha256));
        assertNotNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            lease, restored.reservationToken, restored.resultContinuationToken,
            restored.connectionNamespace, restored.catalogVersion,
            restored.panelPairSha256));
    }
}
