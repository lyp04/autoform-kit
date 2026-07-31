package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

public class PrintRemoteBindingTest {
    private static final String PAIR =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String BACKEND =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String CONNECTION = "0123456789abcdefabcd";

    @Test
    public void bindsPanelProfileBackendTokenPolicyAndTarget() {
        String policy = policy("failed", "ongoing");
        PrintRemoteBinding binding = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token",
            policy, 42L, "SN000001");

        assertTrue(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token", policy));
        assertTrue(binding.identifies(42L, "SN000001"));
        assertFalse(binding.sameExecutionContext(
            "fedcba9876543210abcd", 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "secret-token", policy));
        assertFalse(binding.sameExecutionContext(
            CONNECTION, 8, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token", policy));
        assertFalse(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-b", BACKEND, "device-fingerprint",
            "secret-token", policy));
        assertFalse(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "replacement-token", policy));
        assertFalse(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "other-fingerprint",
            "secret-token", policy));
        assertFalse(binding.identifies(43L, "SN000001"));
        assertFalse(binding.identifies(42L, "SN000002"));
    }

    @Test
    public void policyHashIsOrderIndependentButSemanticsSensitive() {
        assertTrue(policy("failed", "ongoing").equals(policy("ongoing", "failed")));
        assertNotEquals(policy("failed", "ongoing"), policy("failed"));
        assertNotEquals(policy("failed"), PrintRemoteBinding.policySha256(
            true, "block", true, false, new LinkedHashSet<>(Arrays.asList("failed")),
            5, 1000L, 1, 2000L, "continue", "deferred_missing_two_pass"));
        assertNotEquals(policy("failed"), PrintRemoteBinding.policySha256(
            true, "continue", true, true,
            new LinkedHashSet<>(Arrays.asList("failed")),
            5, 1000L, 1, 2000L, "continue", "deferred_missing_two_pass"));
        assertNotEquals(policy("failed"), PrintRemoteBinding.policySha256(
            true, "block", true, true,
            new LinkedHashSet<>(Arrays.asList("failed")),
            5, 1000L, 1, 2000L, "continue", "inline_only"));
    }

    @Test
    public void derivedJobKeepsTheCapturedExecutionContext() {
        String policy = policy("failed");
        PrintRemoteBinding base = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token",
            policy, 0L, "ledger");
        PrintRemoteBinding job = base.forJob(99L, "SN000099");

        assertTrue(job.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token", policy));
        assertTrue(job.identifies(99L, "SN000099"));
    }

    @Test
    public void opaqueTokenWhitespaceIsPartOfTheSessionIdentity() {
        String policy = policy("failed");
        PrintRemoteBinding binding = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            " token ", policy, 1L, "SN000001");

        assertTrue(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            " token ", policy));
        assertFalse(binding.sameExecutionContext(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "token", policy));
    }

    @Test
    public void durableRoundTripKeepsExactOperationIdentity() {
        String policy = policy("failed");
        PrintRemoteBinding original = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token", policy, 42L, "SN000001");

        PrintRemoteBinding restored = PrintRemoteBinding.fromJson(original.toJson());

        assertTrue(original.sameExactOperation(restored));
        assertTrue(original.sameRemoteTarget(restored));
    }

    @Test
    public void unresolvedRemoteTargetStaysTheSameAcrossSessionOrPolicyChange() {
        PrintRemoteBinding original = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "secret-token", policy("failed"), 42L, "SN000001");
        PrintRemoteBinding newSessionAndPolicy = PrintRemoteBinding.capture(
            CONNECTION, 7, PAIR, "profile-a", BACKEND, "device-fingerprint",
            "replacement-token", policy("ongoing"), 42L, "SN000001");

        assertFalse(original.sameExactOperation(newSessionAndPolicy));
        assertTrue(original.sameRemoteTarget(newSessionAndPolicy));
        assertFalse(original.sameRemoteTarget(original.forJob(43L, "SN000001")));
        assertFalse(original.sameRemoteTarget(original.forJob(42L, "SN000002")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonCanonicalConnectionNamespace() {
        PrintRemoteBinding.capture("connection-a", 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "secret-token", policy("failed"), 1L, "SN000001");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUppercaseConnectionNamespace() {
        PrintRemoteBinding.capture("0123456789ABCDEFABCD", 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "secret-token", policy("failed"), 1L, "SN000001");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverlongProfileId() {
        PrintRemoteBinding.capture(CONNECTION, 7, PAIR, repeat('p', 161), BACKEND,
            "device-fingerprint", "secret-token", policy("failed"), 1L, "SN000001");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOverlongSerial() {
        PrintRemoteBinding.capture(CONNECTION, 7, PAIR, "profile-a", BACKEND,
            "device-fingerprint", "secret-token", policy("failed"), 1L,
            repeat('S', 257));
    }

    private static String policy(String... statuses) {
        return PrintRemoteBinding.policySha256(true, "block", true, true,
            new LinkedHashSet<>(Arrays.asList(statuses)), 5, 1000L, 1, 2000L,
            "continue", "deferred_missing_two_pass");
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
