package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class AlternateSubmissionAttemptTest {
    private static final String BODY_ONE =
        "{\"templateId\":4101,\"data\":{\"sample-serial\":\"SN-001\"}}";
    private static final String BODY_TWO =
        "{\"templateId\":4101,\"data\":{\"sample-serial\":\"SN-002\"}}";
    private static final String SOURCE_SNAPSHOT_SHA =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static AlternateSubmissionAttempt.TargetIdentity target() {
        return AlternateSubmissionAttempt.TargetIdentity.of(
            "sample-hidden-target", 4101, 7, "SAMPLE-HIDDEN");
    }

    private static AlternateSubmissionAttempt.Key key() {
        return key("operation-001", "SN-001", BODY_ONE, target());
    }

    private static AlternateSubmissionAttempt.Key key(
            String operationId, String serial, String body,
            AlternateSubmissionAttempt.TargetIdentity targetIdentity) {
        return keyWithBinding(
            "sample-connection-001",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            operationId, serial, body, targetIdentity);
    }

    private static AlternateSubmissionAttempt.Key keyWithBinding(
            String connection, String binding, String operationId, String serial,
            String body, AlternateSubmissionAttempt.TargetIdentity targetIdentity) {
        return keyWithSourceSnapshot(connection, binding, operationId, serial,
            SOURCE_SNAPSHOT_SHA, body, targetIdentity);
    }

    private static AlternateSubmissionAttempt.Key keyWithSourceSnapshot(
            String connection, String binding, String operationId, String serial,
            String sourceSnapshotSha, String body,
            AlternateSubmissionAttempt.TargetIdentity targetIdentity) {
        return AlternateSubmissionAttempt.Key.of(connection, binding, targetIdentity, serial,
            sourceSnapshotSha, AlternateSubmissionAttempt.payloadSha256(body), operationId);
    }

    private static AlternateSubmissionAttempt prepared() {
        return AlternateSubmissionAttempt.prepare(
            key(), AlternateSubmissionAttempt.restore(null));
    }

    @Test
    public void preparedAttemptPersistsEveryRequestBindingAndRoundTrips() throws Exception {
        AlternateSubmissionAttempt attempt = prepared();
        JSONObject json = attempt.toJson();
        JSONObject storedKey = json.getJSONObject("key");
        JSONObject storedTarget = storedKey.getJSONObject("target");

        assertEquals(1, json.getInt("schemaVersion"));
        assertEquals("PREPARED", json.getString("state"));
        assertEquals("sample-connection-001",
            storedKey.getString("connectionNamespace"));
        assertEquals(64, storedKey.getString("bindingFingerprint").length());
        assertEquals("sample-hidden-target", storedTarget.getString("profileId"));
        assertEquals("4101", storedTarget.getString("templateId"));
        assertEquals("7", storedTarget.getString("warehouseId"));
        assertEquals("SAMPLE-HIDDEN", storedTarget.getString("sku"));
        assertEquals("SN-001", storedKey.getString("serial"));
        assertEquals(SOURCE_SNAPSHOT_SHA,
            storedKey.getString("sourceSnapshotSha256"));
        assertEquals(AlternateSubmissionAttempt.payloadSha256(BODY_ONE),
            storedKey.getString("payloadSha256"));
        assertEquals("operation-001", storedKey.getString("operationId"));

        AlternateSubmissionAttempt.RestoreResult restored =
            AlternateSubmissionAttempt.restore(attempt.toJsonString());
        assertEquals(AlternateSubmissionAttempt.RestoreKind.RESTORED, restored.kind);
        assertEquals(AlternateSubmissionAttempt.State.PREPARED, restored.attempt.state);
        assertEquals(attempt.key, restored.attempt.key);
        assertFalse(restored.requiresWriteBack);
        assertNull(restored.lockReason);
    }

    @Test
    public void acknowledgedPostCompletesOnlyAfterPostingWasPersisted() {
        AlternateSubmissionAttempt attempt = prepared();
        assertRejected("completed is not allowed from PREPARED",
            () -> attempt.markPostAcknowledged(key()));

        AlternateSubmissionAttempt posting = attempt.beginPosting(key());
        assertEquals(AlternateSubmissionAttempt.State.POSTING, posting.state);
        AlternateSubmissionAttempt completed = posting.markPostAcknowledged(key());
        assertEquals(AlternateSubmissionAttempt.State.COMPLETED, completed.state);
    }

    @Test
    public void ambiguousPostCannotRetryUntilExactAbsenceIsConfirmed() {
        AlternateSubmissionAttempt uncertain = prepared().beginPosting(key())
            .markUncertain(key());
        assertEquals(AlternateSubmissionAttempt.State.UNCERTAIN, uncertain.state);

        assertRejected("posting is not allowed from UNCERTAIN",
            () -> uncertain.beginPosting(key()));

        AlternateSubmissionAttempt confirmedAbsent = uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(
                key(), AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN));
        assertEquals(AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN,
            confirmedAbsent.state);

        AlternateSubmissionAttempt retry = confirmedAbsent.beginPosting(key());
        assertEquals(AlternateSubmissionAttempt.State.POSTING, retry.state);
    }

    @Test
    public void exactWrittenConfirmationCompletesWithoutAnotherPost() {
        AlternateSubmissionAttempt uncertain = prepared().beginPosting(key())
            .markUncertain(key());
        AlternateSubmissionAttempt completed = uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(
                key(), AlternateSubmissionAttempt.ConfirmationResult.WRITTEN));

        assertEquals(AlternateSubmissionAttempt.State.COMPLETED, completed.state);
        assertRejected("posting is not allowed from COMPLETED",
            () -> completed.beginPosting(key()));
    }

    @Test
    public void explicitServerRejectionCanBeClearedWithoutRemoteConfirmation() {
        AlternateSubmissionAttempt rejected = prepared().beginPosting(key())
            .markServerRejected(key());
        assertEquals(AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN,
            rejected.state);
        assertTrue(rejected.canClearLocallyWithoutRemoteConfirmation());
        assertRejected("server rejection is not allowed from PREPARED", () ->
            prepared().markServerRejected(key()));
    }

    @Test
    public void repeatedExplicitRejectionsReuseTheExactKeyUntilAcknowledged() {
        AlternateSubmissionAttempt firstRejected = prepared().beginPosting(key())
            .markServerRejected(key());
        AlternateSubmissionAttempt secondRejected = firstRejected.beginPosting(key())
            .markServerRejected(key());
        AlternateSubmissionAttempt completed = secondRejected.beginPosting(key())
            .markPostAcknowledged(key());

        assertEquals(AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN,
            firstRejected.state);
        assertEquals(firstRejected.key, secondRejected.key);
        assertEquals(secondRejected.key, completed.key);
        assertEquals(AlternateSubmissionAttempt.State.COMPLETED, completed.state);
    }

    @Test
    public void staleOrWrongConfirmationCannotUnlockAttempt() {
        AlternateSubmissionAttempt uncertain = prepared().beginPosting(key())
            .markUncertain(key());
        AlternateSubmissionAttempt.TargetIdentity otherTarget =
            AlternateSubmissionAttempt.TargetIdentity.of(
                "another-hidden-target", 9999, 8, "OTHER-SAMPLE");
        AlternateSubmissionAttempt.Key wrongTarget = key(
            "operation-001", "SN-001", BODY_ONE, otherTarget);
        AlternateSubmissionAttempt.Key wrongSerial = key(
            "operation-001", "SN-002", BODY_ONE, target());
        AlternateSubmissionAttempt.Key wrongPayload = key(
            "operation-001", "SN-001", BODY_TWO, target());
        AlternateSubmissionAttempt.Key wrongOperation = key(
            "operation-002", "SN-001", BODY_ONE, target());
        AlternateSubmissionAttempt.Key wrongConnection = keyWithBinding(
            "another-connection", key().bindingFingerprint,
            "operation-001", "SN-001", BODY_ONE, target());
        AlternateSubmissionAttempt.Key wrongBinding = keyWithBinding(
            key().connectionNamespace,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "operation-001", "SN-001", BODY_ONE, target());
        AlternateSubmissionAttempt.Key wrongSourceSnapshot = keyWithSourceSnapshot(
            key().connectionNamespace, key().bindingFingerprint,
            "operation-001", "SN-001",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            BODY_ONE, target());

        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongTarget,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongSerial,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongPayload,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongOperation,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongConnection,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongBinding,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertRejected("key does not match", () -> uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(wrongSourceSnapshot,
                AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
        assertEquals(AlternateSubmissionAttempt.State.UNCERTAIN, uncertain.state);
    }

    @Test
    public void allMutationsRejectCallbacksForAnotherAttempt() {
        AlternateSubmissionAttempt attempt = prepared();
        AlternateSubmissionAttempt.Key stale = key(
            "operation-stale", "SN-001", BODY_ONE, target());

        assertRejected("key does not match", () -> attempt.beginPosting(stale));
        AlternateSubmissionAttempt posting = attempt.beginPosting(key());
        assertRejected("key does not match", () -> posting.markUncertain(stale));
        assertRejected("key does not match", () -> posting.markPostAcknowledged(stale));
    }

    @Test
    public void processRestoreTurnsPostingIntoUncertainAndDemandsWriteBack() throws Exception {
        AlternateSubmissionAttempt posting = prepared().beginPosting(key());
        AlternateSubmissionAttempt.RestoreResult restored =
            AlternateSubmissionAttempt.restore(posting.toJsonString());

        assertEquals(AlternateSubmissionAttempt.RestoreKind.RESTORED, restored.kind);
        assertEquals(AlternateSubmissionAttempt.State.UNCERTAIN, restored.attempt.state);
        assertEquals(posting.key, restored.attempt.key);
        assertTrue(restored.requiresWriteBack);
        assertEquals("UNCERTAIN",
            restored.attempt.toJson().getString("state"));
        assertRejected("posting is not allowed from UNCERTAIN",
            () -> restored.attempt.beginPosting(key()));
    }

    @Test
    public void restorePreservesEveryNonPostingState() {
        AlternateSubmissionAttempt prepared = prepared();
        AlternateSubmissionAttempt uncertain = prepared.beginPosting(key())
            .markUncertain(key());
        AlternateSubmissionAttempt absent = uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(
                key(), AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN));
        AlternateSubmissionAttempt completed = absent.beginPosting(key())
            .markPostAcknowledged(key());

        assertRestoresSameState(prepared);
        assertRestoresSameState(uncertain);
        assertRestoresSameState(absent);
        assertRestoresSameState(completed);
    }

    @Test
    public void operationIdCannotBeReusedForAnotherPayloadOrIdentity() {
        AlternateSubmissionAttempt completed = prepared().beginPosting(key())
            .markPostAcknowledged(key());
        AlternateSubmissionAttempt.RestoreResult slot =
            AlternateSubmissionAttempt.restore(completed.toJsonString());

        AlternateSubmissionAttempt.Key changedPayload = key(
            "operation-001", "SN-001", BODY_TWO, target());
        assertRejected("different payload", () ->
            AlternateSubmissionAttempt.prepare(changedPayload, slot));

        AlternateSubmissionAttempt.Key changedSerial = key(
            "operation-001", "SN-002", BODY_ONE, target());
        assertRejected("different submission identity", () ->
            AlternateSubmissionAttempt.prepare(changedSerial, slot));

        assertRejected("operationId has already been used", () ->
            AlternateSubmissionAttempt.prepare(key(), slot));
    }

    @Test
    public void unresolvedAttemptCannotBeSilentlyReplaced() {
        AlternateSubmissionAttempt uncertain = prepared().beginPosting(key())
            .markUncertain(key());
        AlternateSubmissionAttempt.RestoreResult slot =
            AlternateSubmissionAttempt.restore(uncertain.toJsonString());
        AlternateSubmissionAttempt.Key newOperation = key(
            "operation-002", "SN-002", BODY_TWO, target());

        assertRejected("another submission attempt is unresolved", () ->
            AlternateSubmissionAttempt.prepare(newOperation, slot));
    }

    @Test
    public void completedAttemptCanBeReplacedByNewOperation() {
        AlternateSubmissionAttempt completed = prepared().beginPosting(key())
            .markPostAcknowledged(key());
        AlternateSubmissionAttempt.Key newOperation = key(
            "operation-002", "SN-002", BODY_TWO, target());
        AlternateSubmissionAttempt next = AlternateSubmissionAttempt.prepare(
            newOperation, AlternateSubmissionAttempt.restore(completed.toJsonString()));

        assertEquals(AlternateSubmissionAttempt.State.PREPARED, next.state);
        assertEquals("operation-002", next.key.operationId);
    }

    @Test
    public void absentSlotIsDistinctFromEmptyOrCorruptedStorage() {
        AlternateSubmissionAttempt.RestoreResult absent =
            AlternateSubmissionAttempt.restore(null);
        assertEquals(AlternateSubmissionAttempt.RestoreKind.NONE, absent.kind);
        assertNull(absent.attempt);
        assertNull(absent.lockReason);

        assertLocked("", AlternateSubmissionAttempt.LockReason.EMPTY_RECORD);
        assertLocked("   ", AlternateSubmissionAttempt.LockReason.EMPTY_RECORD);
        assertLocked("not-json", AlternateSubmissionAttempt.LockReason.MALFORMED_JSON);
        assertLocked("[]", AlternateSubmissionAttempt.LockReason.MALFORMED_JSON);
    }

    @Test
    public void wrongTypedStorageFailsLockedInsteadOfLookingAbsent() {
        AlternateSubmissionAttempt.RestoreResult absent =
            AlternateSubmissionAttempt.restoreStoredValue(false, Integer.valueOf(7));
        assertEquals(AlternateSubmissionAttempt.RestoreKind.NONE, absent.kind);

        AlternateSubmissionAttempt.RestoreResult number =
            AlternateSubmissionAttempt.restoreStoredValue(true, Integer.valueOf(7));
        assertEquals(AlternateSubmissionAttempt.RestoreKind.LOCKED, number.kind);
        assertEquals(AlternateSubmissionAttempt.LockReason.INVALID_STORAGE_TYPE,
            number.lockReason);

        AlternateSubmissionAttempt.RestoreResult set =
            AlternateSubmissionAttempt.restoreStoredValue(true,
                new java.util.LinkedHashSet<String>());
        assertEquals(AlternateSubmissionAttempt.RestoreKind.LOCKED, set.kind);
        assertEquals(AlternateSubmissionAttempt.LockReason.INVALID_STORAGE_TYPE,
            set.lockReason);
    }

    @Test
    public void onlyStatesWithoutAnAmbiguousPostAreSafeToClearLocally() {
        AlternateSubmissionAttempt prepared = prepared();
        AlternateSubmissionAttempt posting = prepared.beginPosting(key());
        AlternateSubmissionAttempt uncertain = posting.markUncertain(key());
        AlternateSubmissionAttempt confirmedAbsent = uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(
                key(), AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN));
        AlternateSubmissionAttempt completed = posting.markPostAcknowledged(key());

        assertTrue(prepared.canClearLocallyWithoutRemoteConfirmation());
        assertFalse(posting.canClearLocallyWithoutRemoteConfirmation());
        assertFalse(uncertain.canClearLocallyWithoutRemoteConfirmation());
        assertTrue(confirmedAbsent.canClearLocallyWithoutRemoteConfirmation());
        assertTrue(completed.canClearLocallyWithoutRemoteConfirmation());
    }

    @Test
    public void lockedStorageCannotBeTreatedAsSafeForNewAttempt() {
        AlternateSubmissionAttempt.RestoreResult locked =
            AlternateSubmissionAttempt.restore("{broken");
        assertRejected("persisted attempt is locked", () ->
            AlternateSubmissionAttempt.prepare(key(), locked));
    }

    @Test
    public void unknownVersionAndStateFailLocked() throws Exception {
        JSONObject unknownVersion = prepared().toJson().put("schemaVersion", 2);
        assertLocked(unknownVersion.toString(),
            AlternateSubmissionAttempt.LockReason.UNKNOWN_VERSION);

        String integerVersion = prepared().toJsonString();
        String fractionalVersion = integerVersion.replace(
            "\"schemaVersion\":1", "\"schemaVersion\":1.0");
        assertFalse(integerVersion.equals(fractionalVersion));
        assertLocked(fractionalVersion,
            AlternateSubmissionAttempt.LockReason.UNKNOWN_VERSION);

        JSONObject unknownState = prepared().toJson().put("state", "RETRYABLE");
        assertLocked(unknownState.toString(),
            AlternateSubmissionAttempt.LockReason.UNKNOWN_STATE);
    }

    @Test
    public void unknownMissingAndWrongTypedFieldsFailLocked() throws Exception {
        JSONObject unknownRoot = prepared().toJson().put("future", true);
        assertLocked(unknownRoot.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject missingRoot = prepared().toJson();
        missingRoot.remove("key");
        assertLocked(missingRoot.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject unknownKey = prepared().toJson();
        unknownKey.getJSONObject("key").put("future", true);
        assertLocked(unknownKey.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject unknownTarget = prepared().toJson();
        unknownTarget.getJSONObject("key").getJSONObject("target").put("future", true);
        assertLocked(unknownTarget.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject numericSerial = prepared().toJson();
        numericSerial.getJSONObject("key").put("serial", 123);
        assertLocked(numericSerial.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject nullPayload = prepared().toJson();
        nullPayload.getJSONObject("key").put("payloadSha256", JSONObject.NULL);
        assertLocked(nullPayload.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject badDigest = prepared().toJson();
        badDigest.getJSONObject("key").put("payloadSha256", "not-a-digest");
        assertLocked(badDigest.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);

        JSONObject badSourceDigest = prepared().toJson();
        badSourceDigest.getJSONObject("key").put(
            "sourceSnapshotSha256", "not-a-digest");
        assertLocked(badSourceDigest.toString(),
            AlternateSubmissionAttempt.LockReason.INVALID_SCHEMA);
    }

    @Test
    public void invalidNewIdentityIsRejectedBeforeItCanBePersisted() {
        assertRejected("payloadSha256 must be", () ->
            AlternateSubmissionAttempt.Key.of(
                "sample-connection", "binding", target(), "SN-001",
                SOURCE_SNAPSHOT_SHA, "ABC", "op"));
        assertRejected("sourceSnapshotSha256 must be", () ->
            AlternateSubmissionAttempt.Key.of(
                "sample-connection", "binding", target(), "SN-001", "ABC",
                AlternateSubmissionAttempt.payloadSha256(BODY_ONE), "op"));
        assertRejected("operationId is required", () ->
            AlternateSubmissionAttempt.Key.of(
                "sample-connection", "binding", target(), "SN-001",
                SOURCE_SNAPSHOT_SHA,
                AlternateSubmissionAttempt.payloadSha256(BODY_ONE), " "));
        assertRejected("target.templateId is required", () ->
            AlternateSubmissionAttempt.TargetIdentity.of(
                "sample-hidden-target", null, 7, "SAMPLE-HIDDEN"));
        assertRejected("target.templateId must be a string or number", () ->
            AlternateSubmissionAttempt.TargetIdentity.of(
                "sample-hidden-target", new JSONObject(), 7, "SAMPLE-HIDDEN"));
    }

    @Test
    public void payloadDigestUsesExactUtf8PostBytes() {
        String expected = "f8582ef46add83e42785daf42e81bf275ff10fed5299751f993944a158e5f967";
        assertEquals(expected, AlternateSubmissionAttempt.payloadSha256(BODY_ONE));
        assertEquals(expected, AlternateSubmissionAttempt.payloadSha256(
            BODY_ONE.getBytes(StandardCharsets.UTF_8)));
        assertFalse(expected.equals(AlternateSubmissionAttempt.payloadSha256(BODY_ONE + " ")));
    }

    @Test
    public void confirmationIsAcceptedOnlyForUncertainState() {
        AlternateSubmissionAttempt.Confirmation confirmation =
            AlternateSubmissionAttempt.Confirmation.of(
                key(), AlternateSubmissionAttempt.ConfirmationResult.WRITTEN);
        assertRejected("confirmation is not allowed from PREPARED",
            () -> prepared().applyConfirmation(confirmation));
        assertRejected("confirmation result is required", () ->
            AlternateSubmissionAttempt.Confirmation.of(key(), null));
    }

    private static void assertRestoresSameState(AlternateSubmissionAttempt value) {
        AlternateSubmissionAttempt.RestoreResult restored =
            AlternateSubmissionAttempt.restore(value.toJsonString());
        assertEquals(AlternateSubmissionAttempt.RestoreKind.RESTORED, restored.kind);
        assertEquals(value.state, restored.attempt.state);
        assertEquals(value.key, restored.attempt.key);
        assertFalse(restored.requiresWriteBack);
    }

    private static void assertLocked(String persisted,
                                     AlternateSubmissionAttempt.LockReason reason) {
        AlternateSubmissionAttempt.RestoreResult restored =
            AlternateSubmissionAttempt.restore(persisted);
        assertEquals(AlternateSubmissionAttempt.RestoreKind.LOCKED, restored.kind);
        assertEquals(reason, restored.lockReason);
        assertNull(restored.attempt);
        assertFalse(restored.requiresWriteBack);
    }

    private static void assertRejected(String messagePart, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected rejection containing: " + messagePart);
        } catch (IllegalArgumentException expected) {
            assertTrue("actual message: " + expected.getMessage(),
                expected.getMessage() != null && expected.getMessage().contains(messagePart));
        } catch (Exception unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
