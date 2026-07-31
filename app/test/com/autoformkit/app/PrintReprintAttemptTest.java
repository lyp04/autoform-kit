package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class PrintReprintAttemptTest {
    private static final String CONNECTION = "0123456789abcdefabcd";
    private static final String PAIR =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String BACKEND =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String PAYLOAD =
        "1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    public void onlyConfiguredSuccessCanCompleteSentReprint() {
        assertEquals(PrintReprintAttempt.ResponseDisposition.CONFIRMED_SUCCESS,
            PrintReprintAttempt.responseDisposition(true));
        assertEquals(PrintReprintAttempt.ResponseDisposition.UNCERTAIN,
            PrintReprintAttempt.responseDisposition(false));
    }

    @Test
    public void postingRoundTripBecomesDurableUncertainAfterRecovery() {
        PrintReprintAttempt.Attempt posting = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD, binding(42L, "SN000001"));
        PrintReprintAttempt.Store stored = PrintReprintAttempt.Store.empty().add(posting);

        PrintReprintAttempt.Store parsed = PrintReprintAttempt.Store.parse(stored.serialize());
        assertTrue(parsed.hasPosting());
        assertTrue(parsed.operation(posting.operationId).binding.sameExactOperation(posting.binding));

        PrintReprintAttempt.Store recovered = parsed.recoverPosting(200L);
        assertFalse(recovered.hasPosting());
        assertEquals(PrintReprintAttempt.State.UNCERTAIN,
            recovered.operation(posting.operationId).state);
        assertEquals(200L, recovered.operation(posting.operationId).updatedAt);
    }

    @Test
    public void restartedUncertainResolvesOnlyOnSuccessfulPrintedStatus() {
        PrintReprintAttempt.Attempt posting = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD,
            binding(42L, "SN000001"));
        PrintReprintAttempt.Store recovered = PrintReprintAttempt.Store.parse(
            PrintReprintAttempt.Store.empty().add(posting).serialize())
            .recoverPosting(200L);

        assertNull(recovered.confirmedPrintedResolution(posting.binding, false, true));
        assertNull(recovered.confirmedPrintedResolution(posting.binding, true, false));
        PrintReprintAttempt.Attempt resolution =
            recovered.confirmedPrintedResolution(posting.binding, true, true);
        assertNotNull(resolution);
        assertTrue(recovered.remove(resolution.operationId).attempts.isEmpty());
    }

    @Test
    public void failedOngoingUnknownOrMissingStatusNeverResolves() {
        PrintReprintAttempt.Attempt uncertain = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD,
            binding(42L, "SN000001")).uncertain(200L);
        PrintReprintAttempt.Store store = PrintReprintAttempt.Store.empty().add(uncertain);

        assertNull(store.confirmedPrintedResolution(uncertain.binding, true, false));
        assertNull(store.confirmedPrintedResolution(
            uncertain.binding.forJob(0L, "SN000001"), true, true));
        assertNull(store.confirmedPrintedResolution(
            uncertain.binding.forJob(42L, "SN000002"), true, true));
    }

    @Test
    public void sameJobAndSerialInDifferentRemoteContextCannotResolve() {
        PrintReprintAttempt.Attempt uncertain = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD,
            binding(42L, "SN000001")).uncertain(200L);
        PrintReprintAttempt.Store store = PrintReprintAttempt.Store.empty().add(uncertain);
        PrintRemoteBinding otherConnection = PrintRemoteBinding.capture(
            "fedcba9876543210fedc", 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "replacement-token",
            uncertain.binding.policySha256, 42L, "SN000001");

        assertNull(store.confirmedPrintedResolution(otherConnection, true, true));
    }

    @Test
    public void unresolvedJobBlocksReplayAcrossSessionAndPolicyChanges() {
        PrintRemoteBinding original = binding(42L, "SN000001");
        PrintReprintAttempt.Attempt posting = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD, original);
        PrintReprintAttempt.Store store = PrintReprintAttempt.Store.empty().add(posting);
        String changedPolicy = PrintRemoteBinding.policySha256(true, "block", true, true,
            Collections.singleton("ongoing"), 5, 1000L, 1, 2000L, "continue",
            "deferred_missing_two_pass");
        PrintRemoteBinding newSessionAndPolicy = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "replacement-token", changedPolicy, 42L, "SN000001");

        assertNotNull(store.blocking(newSessionAndPolicy));
        assertNull(store.blocking(original.forJob(43L, "SN000001")));
        assertNull(store.blocking(original.forJob(42L, "SN000002")));
    }

    @Test
    public void explicitTerminalResponseRemovesOnlyItsExactAttempt() {
        PrintReprintAttempt.Attempt first = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD, binding(42L, "SN000001"));
        PrintReprintAttempt.Attempt second = PrintReprintAttempt.Attempt.posting(
            "fedcba9876543210fedcba9876543210", 101L, PAYLOAD, binding(43L, "SN000002"));
        PrintReprintAttempt.Store store = PrintReprintAttempt.Store.empty().add(first).add(second);

        PrintReprintAttempt.Store remaining = store.remove(first.operationId);

        assertNull(remaining.operation(first.operationId));
        assertNotNull(remaining.operation(second.operationId));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedJournalFailsClosed() {
        PrintReprintAttempt.Store.parse("{\"schema\":1,\"attempts\":[{\"state\":\"posting\"}]}");
    }

    @Test(expected = IllegalStateException.class)
    public void duplicateRemoteTargetCannotReplaceExistingUncertainAttempt() {
        PrintReprintAttempt.Attempt first = PrintReprintAttempt.Attempt.posting(
            "0123456789abcdef0123456789abcdef", 100L, PAYLOAD, binding(42L, "SN000001"));
        PrintReprintAttempt.Attempt duplicate = PrintReprintAttempt.Attempt.posting(
            "fedcba9876543210fedcba9876543210", 101L, PAYLOAD, binding(42L, "SN000001"));

        PrintReprintAttempt.Store.empty().add(first).add(duplicate);
    }

    private static PrintRemoteBinding binding(long jobId, String serial) {
        String policy = PrintRemoteBinding.policySha256(true, "block", true, true,
            Collections.singleton("failed"), 5, 1000L, 1, 2000L, "continue",
            "deferred_missing_two_pass");
        return PrintRemoteBinding.capture(CONNECTION, 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "secret-token", policy, jobId, serial);
    }
}
