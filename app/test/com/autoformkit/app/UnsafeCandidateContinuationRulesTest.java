package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class UnsafeCandidateContinuationRulesTest {
    private static final String CONNECTION = "connection-a";
    private static final String PAIR =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ALTERNATE_TOKEN =
        "0123456789abcdef0123456789abcdef";
    private static final String RESERVATION_TOKEN =
        "1123456789abcdef0123456789abcdef";
    private static final String RESULT_TOKEN =
        "2123456789abcdef0123456789abcdef";

    private static UnsafeCandidateContinuationRules.AlternateReservation reservation(
            String resultToken) {
        return UnsafeCandidateContinuationRules.alternateReservation(
            RESERVATION_TOKEN, resultToken);
    }

    @Test
    public void pageOpenWithoutPreexistingUnitsNeverBecomesAContinuationLease() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), "", Collections.emptyList(),
                false, false, false);

        assertFalse(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.singletonList(1), true, false, false, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 1, CONNECTION, 7, PAIR));
    }

    @Test
    public void onlyExactPreexistingUnitSequencesMayContinue() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Arrays.asList(2, 5), "", Collections.emptyList(), false, false, false);

        assertTrue(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Arrays.asList(5, 9), true, false, false, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
        assertTrue(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 2, CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 3, CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.singletonList(9), true, false, false, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
    }

    @Test
    public void connectionVersionAndPairHashAreAllPartOfTheLease() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.singletonList(1), ALTERNATE_TOKEN, Collections.emptyList(),
                false, false, false);

        assertFalse(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 1, "connection-b", 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 1, CONNECTION, 8, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsMainUnit(
            lease, 1, CONNECTION, 7,
                "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    }

    @Test
    public void authorizedMainWorkerMayFinishAfterItsUnitsLeaveTheVisibleQueue() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.singletonList(4), "", Collections.emptyList(),
                false, false, false);
        UnsafeCandidateContinuationRules.Lease worker =
            UnsafeCandidateContinuationRules.authorizeMainWorker(lease,
                Collections.singletonList(4), CONNECTION, 7, PAIR);

        assertNotNull(worker);
        assertTrue(UnsafeCandidateContinuationRules.permitsCurrentWork(worker,
            Collections.emptyList(), false, true, false, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
        assertNull(UnsafeCandidateContinuationRules.authorizeMainWorker(lease,
            Collections.singletonList(8), CONNECTION, 7, PAIR));
    }

    @Test
    public void alternateEntryRequiresTheExactPreBarrierToken() {
        UnsafeCandidateContinuationRules.Lease empty =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), "", Collections.emptyList(),
                false, false, false);
        UnsafeCandidateContinuationRules.Lease populated =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), ALTERNATE_TOKEN, Collections.emptyList(),
                false, false, false);

        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            empty, ALTERNATE_TOKEN, CONNECTION, 7, PAIR));
        assertTrue(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            populated, ALTERNATE_TOKEN, CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            populated, "1123456789abcdef0123456789abcdef", CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            populated, "", CONNECTION, 7, PAIR));

        UnsafeCandidateContinuationRules.Lease worker =
            UnsafeCandidateContinuationRules.authorizeAlternateWorker(
                populated, ALTERNATE_TOKEN, CONNECTION, 7, PAIR);
        assertNotNull(worker);
        assertTrue(UnsafeCandidateContinuationRules.permitsCurrentWork(worker,
            Collections.emptyList(), false, false, false, false, true,
            ALTERNATE_TOKEN, Collections.emptyList(), CONNECTION, 7, PAIR));
        assertNull(UnsafeCandidateContinuationRules.authorizeAlternateWorker(
            populated, "1123456789abcdef0123456789abcdef", CONNECTION, 7, PAIR));
    }

    @Test
    public void invalidOrInventedAlternateTokensNeverCreateAProof() {
        assertFalse(UnsafeCandidateContinuationRules.validAlternateEntryToken(""));
        assertFalse(UnsafeCandidateContinuationRules.validAlternateEntryToken("page-open"));
        assertTrue(UnsafeCandidateContinuationRules.validAlternateEntryToken(ALTERNATE_TOKEN));

        UnsafeCandidateContinuationRules.Lease invalid =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), "page-open", Collections.emptyList(),
                false, false, false);
        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            invalid, "page-open", CONNECTION, 7, PAIR));
    }

    @Test
    public void aWorkerNotAuthorizedAtTheBarrierCannotBorrowAnotherLeaseCategory() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.singletonList(1), "", Collections.emptyList(),
                false, true, false);

        assertFalse(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.emptyList(), false, true, false, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
        assertTrue(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.emptyList(), false, false, true, false, false, "",
            Collections.emptyList(),
            CONNECTION, 7, PAIR));
    }

    @Test
    public void emptyPageMayFinishOnlyTheExactPreBarrierAsyncReservation() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), "",
                Collections.singletonList(reservation(RESULT_TOKEN)),
                false, false, false);

        assertTrue(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.emptyList(), false, false, false, false, false, "",
            Collections.singletonList(RESERVATION_TOKEN), CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsCurrentWork(lease,
            Collections.emptyList(), false, false, false, false, false, "",
            Collections.singletonList(RESULT_TOKEN), CONNECTION, 7, PAIR));
        assertTrue(UnsafeCandidateContinuationRules.permitsAlternateReservation(
            lease, RESERVATION_TOKEN, CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateReservation(
            lease, RESULT_TOKEN, CONNECTION, 7, PAIR));
    }

    @Test
    public void exactReservationIsOneShotAndPromotesOnlyItsPreallocatedResultToken() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), "",
                Collections.singletonList(reservation(RESULT_TOKEN)),
                false, false, false);

        assertNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            lease, RESERVATION_TOKEN, ALTERNATE_TOKEN, CONNECTION, 7, PAIR));

        UnsafeCandidateContinuationRules.Lease consumed =
            UnsafeCandidateContinuationRules.consumeAlternateReservation(
                lease, RESERVATION_TOKEN, RESULT_TOKEN, CONNECTION, 7, PAIR);
        assertNotNull(consumed);
        assertTrue(UnsafeCandidateContinuationRules.permitsAlternateEntry(
            consumed, RESULT_TOKEN, CONNECTION, 7, PAIR));
        assertFalse(UnsafeCandidateContinuationRules.permitsAlternateReservation(
            consumed, RESERVATION_TOKEN, CONNECTION, 7, PAIR));
        assertNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            consumed, RESERVATION_TOKEN, RESULT_TOKEN, CONNECTION, 7, PAIR));
    }

    @Test
    public void reservationCannotReplaceAnExistingDraftWithADifferentToken() {
        UnsafeCandidateContinuationRules.Lease lease =
            UnsafeCandidateContinuationRules.capture(CONNECTION, 7, PAIR,
                Collections.emptyList(), ALTERNATE_TOKEN,
                Collections.singletonList(reservation(ALTERNATE_TOKEN)),
                false, false, false);

        assertNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            lease, RESERVATION_TOKEN, RESULT_TOKEN, CONNECTION, 7, PAIR));
        assertNotNull(UnsafeCandidateContinuationRules.consumeAlternateReservation(
            lease, RESERVATION_TOKEN, ALTERNATE_TOKEN, CONNECTION, 7, PAIR));
    }
}
