package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AlternateEntryScanRecoveryRulesTest {
    private static String hex(char value, int length) {
        char[] out = new char[length];
        Arrays.fill(out, value);
        return new String(out);
    }

    private static AlternateEntryAsyncReservation scan(String reservationToken,
                                                       String resultToken,
                                                       String binding,
                                                       String baseState,
                                                       String guard) {
        return AlternateEntryAsyncReservation.create(
            AlternateEntryAsyncReservation.KIND_SCAN, reservationToken, resultToken,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), binding, hex('e', 64),
            guard, baseState, "");
    }

    private static boolean canCancel(AlternateEntryAsyncReservation reservation,
                                     String guard,
                                     java.util.Collection<AlternateEntryScanRecoveryRules.Binding>
                                         bindings,
                                     boolean durableState, boolean pendingData,
                                     boolean photoEvidence, boolean continuationToken) {
        return AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
            reservation, hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('e', 64),
            guard, "shared-entry", bindings, durableState, pendingData,
            photoEvidence, continuationToken);
    }

    @Test
    public void switchedSourceAndToggleOnlyBaseCanCancelAfterColdStart() {
        String selectedSourceBinding = hex('d', 64);
        String defaultSourceBinding = hex('9', 64);
        String oldToggleBase = hex('f', 64);
        String coldDefaultToggleBase = hex('0', 64);
        String guard = "dead-activity\nselected-source\ntoggle=true";
        AlternateEntryAsyncReservation reservation = scan(
            hex('1', 32), hex('2', 32), selectedSourceBinding, oldToggleBase, guard);

        // A cold page rebuilt with its default source/toggle state cannot pass the ordinary exact
        // base match. The catalog still proves the abandoned scan's selected source exactly once.
        assertFalse(reservation.matches(AlternateEntryAsyncReservation.KIND_SCAN,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), defaultSourceBinding,
            hex('e', 64), guard, coldDefaultToggleBase, ""));
        assertTrue(canCancel(reservation, guard, Arrays.asList(
            new AlternateEntryScanRecoveryRules.Binding(
                "default-source", "shared-entry", defaultSourceBinding),
            new AlternateEntryScanRecoveryRules.Binding(
                "selected-source", "shared-entry", selectedSourceBinding)),
            false, false, false, false));

        // The same fallback also covers a toggle-only difference when the source binding is equal.
        assertFalse(reservation.matches(AlternateEntryAsyncReservation.KIND_SCAN,
            hex('a', 64), hex('b', 20), 7, hex('c', 64), selectedSourceBinding,
            hex('e', 64), guard, coldDefaultToggleBase, ""));
        assertTrue(canCancel(reservation, guard, Collections.singletonList(
            new AlternateEntryScanRecoveryRules.Binding(
                "selected-source", "shared-entry", selectedSourceBinding)),
            false, false, false, false));
    }

    @Test
    public void coldStartFallbackFailsClosedAroundEveryDurableOrAmbiguousState() {
        String binding = hex('d', 64);
        String guard = "dead-activity\nselected-source";
        AlternateEntryAsyncReservation reservation = scan(
            hex('1', 32), hex('2', 32), binding, hex('f', 64), guard);
        java.util.List<AlternateEntryScanRecoveryRules.Binding> unique =
            Collections.singletonList(new AlternateEntryScanRecoveryRules.Binding(
                "selected-source", "shared-entry", binding));

        assertFalse(canCancel(reservation, guard, unique, true, false, false, false));
        assertFalse(canCancel(reservation, guard, unique, false, true, false, false));
        assertFalse(canCancel(reservation, guard, unique, false, false, true, false));
        assertFalse(canCancel(reservation, guard, unique, false, false, false, true));
        assertFalse(canCancel(reservation, "different-guard", unique,
            false, false, false, false));
        assertFalse(canCancel(reservation, guard, Arrays.asList(unique.get(0), unique.get(0)),
            false, false, false, false));
        assertFalse(AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
            reservation, hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('e', 64),
            guard, "different-entry", unique, false, false, false, false));
        assertFalse(AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
            reservation, hex('a', 64), hex('b', 20), 8, hex('c', 64), hex('e', 64),
            guard, "shared-entry", unique, false, false, false, false));
        assertFalse(AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
            reservation, hex('a', 64), hex('b', 20), 7, hex('4', 64), hex('e', 64),
            guard, "shared-entry", unique, false, false, false, false));
        assertFalse(AlternateEntryScanRecoveryRules.canCancelSideEffectFreeScan(
            reservation, hex('a', 64), hex('b', 20), 7, hex('c', 64), hex('5', 64),
            guard, "shared-entry", unique, false, false, false, false));
    }

    @Test
    public void cancellationCannotConsumeANewerReservationTokenPair() {
        String binding = hex('d', 64);
        AlternateEntryAsyncReservation captured = scan(
            hex('1', 32), hex('2', 32), binding, hex('f', 64), "guard");
        AlternateEntryAsyncReservation same = AlternateEntryAsyncReservation.parse(
            captured.toJson().toString());
        AlternateEntryAsyncReservation newerReservation = scan(
            hex('3', 32), hex('4', 32), binding, hex('f', 64), "guard");
        AlternateEntryAsyncReservation newerResult = scan(
            hex('1', 32), hex('4', 32), binding, hex('f', 64), "guard");

        assertTrue(AlternateEntryScanRecoveryRules.sameReservation(captured, same));
        assertFalse(AlternateEntryScanRecoveryRules.sameReservation(
            captured, newerReservation));
        assertFalse(AlternateEntryScanRecoveryRules.sameReservation(captured, newerResult));
    }
}
