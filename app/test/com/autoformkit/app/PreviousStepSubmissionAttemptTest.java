package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class PreviousStepSubmissionAttemptTest {
    private static final String A =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C =
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String D =
        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String CONNECTION = "0123456789abcdefabcd";

    private static PreviousStepSubmissionAttempt.ChainIdentity chain() {
        return PreviousStepSubmissionAttempt.ChainIdentity.of(
            CONNECTION, 17, "sample-profile", A,
            3, "SAMPLE-001", B, C, D, 2);
    }

    private static PreviousStepSubmissionAttempt.Key key(
            int completed, int order, int sourceIndex, String payload,
            int attempt, String operation) {
        return PreviousStepSubmissionAttempt.Key.of(chain(), completed,
            PreviousStepSubmissionAttempt.RecipeIdentity.of(order, sourceIndex,
                order == 1 ? PreviousStepSubmissionAttempt.RecipeKind.STATIC
                    : PreviousStepSubmissionAttempt.RecipeKind.DYNAMIC,
                order == 1 ? D : A),
            AlternateSubmissionAttempt.payloadSha256(payload), attempt, operation);
    }

    private static PreviousStepSubmissionAttempt preparedFirst() {
        return PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{\"url\":\"https://example.invalid/real-1\"}", 1, "op-1"),
            PreviousStepSubmissionAttempt.restore(null));
    }

    @Test
    public void persistedAttemptBindsDraftUnitRecipeOrderAndExactPayloadDigest()
            throws Exception {
        PreviousStepSubmissionAttempt attempt = preparedFirst();
        JSONObject root = attempt.toJson();
        JSONObject storedKey = root.getJSONObject("key");
        JSONObject storedChain = storedKey.getJSONObject("chain");
        JSONObject storedRecipe = storedKey.getJSONObject("recipe");

        assertEquals(1, root.getInt("schemaVersion"));
        assertEquals("PREPARED", root.getString("state"));
        assertEquals(CONNECTION, storedChain.getString("connectionNamespace"));
        assertEquals(17, storedChain.getInt("catalogVersion"));
        assertEquals("sample-profile", storedChain.getString("profileId"));
        assertEquals(A, storedChain.getString("draftSemanticsSha256"));
        assertEquals(3, storedChain.getInt("unitSequence"));
        assertEquals("SAMPLE-001", storedChain.getString("serial"));
        assertEquals(B, storedChain.getString("unitSnapshotSha256"));
        assertEquals(C, storedChain.getString("recipeChainSha256"));
        assertEquals(D,
            storedChain.getString("dynamicResolvedSemanticsSha256"));
        assertEquals(2, storedChain.getInt("recipeCount"));
        assertEquals(0, storedKey.getInt("completedRecipeCount"));
        assertEquals(1, storedRecipe.getInt("order"));
        assertEquals(0, storedRecipe.getInt("sourceIndex"));
        assertEquals("STATIC", storedRecipe.getString("kind"));
        assertEquals(D, storedRecipe.getString("identitySha256"));
        assertEquals(AlternateSubmissionAttempt.payloadSha256(
            "{\"url\":\"https://example.invalid/real-1\"}"),
            storedKey.getString("payloadSha256"));
    }

    @Test
    public void postingRestoresAsUncertainAndCannotPrepareAnotherPost() {
        PreviousStepSubmissionAttempt posting = preparedFirst().beginPosting(
            preparedFirst().key);
        PreviousStepSubmissionAttempt.RestoreResult restored =
            PreviousStepSubmissionAttempt.restore(posting.toJsonString());

        assertEquals(PreviousStepSubmissionAttempt.RestoreKind.RESTORED, restored.kind);
        assertEquals(PreviousStepSubmissionAttempt.State.UNCERTAIN,
            restored.attempt.state);
        assertTrue(restored.requiresWriteBack);
        assertRejected("outcome is unresolved", () ->
            PreviousStepSubmissionAttempt.prepare(
                key(0, 1, 0, "{\"different\":true}", 1, "op-new"), restored));
    }

    @Test
    public void transportUncertaintyCannotAdvanceOrRetry() {
        PreviousStepSubmissionAttempt prepared = preparedFirst();
        PreviousStepSubmissionAttempt uncertain = prepared.beginPosting(prepared.key)
            .markUncertain(prepared.key);
        PreviousStepSubmissionAttempt.RestoreResult slot =
            PreviousStepSubmissionAttempt.restore(uncertain.toJsonString());

        assertRejected("outcome is unresolved", () ->
            PreviousStepSubmissionAttempt.prepare(
                key(0, 1, 0, "{\"url\":\"new\"}", 2, "op-2"), slot));
        assertEquals(0, uncertain.completedRecipeCount());
    }

    @Test
    public void exactRemoteConfirmationTransitionsOnlyTheUncertainRecipe() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt uncertain = first.beginPosting(first.key)
            .markUncertain(first.key);

        PreviousStepSubmissionAttempt written = uncertain.applyConfirmation(
            PreviousStepSubmissionAttempt.Confirmation.of(
                uncertain.key,
                PreviousStepSubmissionAttempt.ConfirmationResult.WRITTEN));
        assertEquals(PreviousStepSubmissionAttempt.State.COMPLETED, written.state);
        assertEquals(1, written.completedRecipeCount());

        PreviousStepSubmissionAttempt absent = uncertain.applyConfirmation(
            PreviousStepSubmissionAttempt.Confirmation.of(
                uncertain.key,
                PreviousStepSubmissionAttempt.ConfirmationResult.NOT_WRITTEN));
        assertEquals(PreviousStepSubmissionAttempt.State.EXPLICITLY_REJECTED,
            absent.state);
        PreviousStepSubmissionAttempt retry = PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{\"url\":\"verified-retry\"}", 2, "op-verified-retry"),
            PreviousStepSubmissionAttempt.restore(absent.toJsonString()));
        assertEquals(PreviousStepSubmissionAttempt.State.PREPARED, retry.state);
        assertEquals(2, retry.key.attemptNumber);
    }

    @Test
    public void remoteConfirmationCannotUseAButtonOrDifferentRecipeIdentity() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt uncertain = first.beginPosting(first.key)
            .markUncertain(first.key);

        assertRejected("confirmation is required", () ->
            uncertain.applyConfirmation(null));
        assertRejected("confirmation is not allowed from PREPARED", () ->
            first.applyConfirmation(PreviousStepSubmissionAttempt.Confirmation.of(
                first.key, PreviousStepSubmissionAttempt.ConfirmationResult.WRITTEN)));
        PreviousStepSubmissionAttempt.Key wrongPayload = key(
            0, 1, 0, "{\"different\":true}", 1, "op-1");
        assertRejected("key does not match", () ->
            uncertain.applyConfirmation(PreviousStepSubmissionAttempt.Confirmation.of(
                wrongPayload,
                PreviousStepSubmissionAttempt.ConfirmationResult.NOT_WRITTEN)));
    }

    @Test
    public void explicitRejectionCanRetryOnlyTheSameRecipePosition() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt rejected = first.beginPosting(first.key)
            .markExplicitlyRejected(first.key);
        PreviousStepSubmissionAttempt.RestoreResult slot =
            PreviousStepSubmissionAttempt.restore(rejected.toJsonString());

        PreviousStepSubmissionAttempt retry = PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{\"url\":\"https://example.invalid/real-1\"}", 2, "op-2"), slot);
        assertEquals(PreviousStepSubmissionAttempt.State.PREPARED, retry.state);

        assertRejected("advance exactly once", () ->
            PreviousStepSubmissionAttempt.prepare(
                key(0, 1, 0, "{\"url\":\"https://example.invalid/real-1\"}", 1,
                    "op-reset"), slot));
        PreviousStepSubmissionAttempt changedUploadRetry =
            PreviousStepSubmissionAttempt.prepare(
                key(0, 1, 0, "{\"url\":\"changed\"}", 2, "op-changed"), slot);
        assertFalse(rejected.key.payloadSha256.equals(
            changedUploadRetry.key.payloadSha256));

        PreviousStepSubmissionAttempt.ChainIdentity changedSource =
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                CONNECTION, 17, "sample-profile", A,
                3, "SAMPLE-001", A, C, D, 2);
        PreviousStepSubmissionAttempt.Key changedSourceRetry =
            PreviousStepSubmissionAttempt.Key.of(changedSource, 0,
                PreviousStepSubmissionAttempt.RecipeIdentity.of(1, 0,
                    PreviousStepSubmissionAttempt.RecipeKind.STATIC, D),
                B, 2, "op-source-changed");
        assertRejected("position does not match", () ->
            PreviousStepSubmissionAttempt.prepare(changedSourceRetry, slot));

        PreviousStepSubmissionAttempt.Key changedRecipeRetry =
            PreviousStepSubmissionAttempt.Key.of(chain(), 0,
                PreviousStepSubmissionAttempt.RecipeIdentity.of(1, 0,
                    PreviousStepSubmissionAttempt.RecipeKind.STATIC, C),
                B, 2, "op-recipe-changed");
        assertRejected("position does not match", () ->
            PreviousStepSubmissionAttempt.prepare(changedRecipeRetry, slot));

        assertRejected("position does not match", () ->
            PreviousStepSubmissionAttempt.prepare(
                key(1, 2, 1, "{}", 1, "op-next"), slot));
    }

    @Test
    public void explicitAttemptNumberRemainsMonotonicAcrossRestore() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt rejectedOne = first.beginPosting(first.key)
            .markExplicitlyRejected(first.key);
        PreviousStepSubmissionAttempt second = PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{\"url\":\"https://example.invalid/real-1\"}", 2, "op-2"),
            PreviousStepSubmissionAttempt.restore(rejectedOne.toJsonString()));
        PreviousStepSubmissionAttempt rejectedTwo = second.beginPosting(second.key)
            .markExplicitlyRejected(second.key);

        // The durable attempt identity continues across a later invocation. The MainActivity
        // caller compares this number with the Panel-owned persistent total before another POST.
        PreviousStepSubmissionAttempt third = PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{\"url\":\"https://example.invalid/new-upload\"}", 3, "op-3"),
            PreviousStepSubmissionAttempt.restore(rejectedTwo.toJsonString()));
        assertEquals(3, third.key.attemptNumber);
        assertEquals(PreviousStepSubmissionAttempt.State.PREPARED, third.state);
    }

    @Test
    public void completedPrefixAdvancesExactlyOnceAndSurvivesRoundTrip() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt completed = first.beginPosting(first.key)
            .markAcknowledged(first.key);
        PreviousStepSubmissionAttempt.RestoreResult restored =
            PreviousStepSubmissionAttempt.restore(completed.toJsonString());

        assertEquals(1, restored.attempt.completedRecipeCount());
        assertTrue(restored.attempt.requiresRecipeContinuation());
        assertTrue(restored.attempt.recipeMatches(
            PreviousStepSubmissionAttempt.RecipeIdentity.of(1, 0,
                PreviousStepSubmissionAttempt.RecipeKind.STATIC, D)));
        assertFalse(restored.attempt.recipeMatches(
            PreviousStepSubmissionAttempt.RecipeIdentity.of(1, 0,
                PreviousStepSubmissionAttempt.RecipeKind.STATIC, C)));
        PreviousStepSubmissionAttempt second = PreviousStepSubmissionAttempt.prepare(
            key(1, 2, 4, "{\"url\":\"https://example.invalid/real-2\"}", 1, "op-2"),
            restored);
        assertEquals(PreviousStepSubmissionAttempt.State.PREPARED, second.state);

        assertRejected("cannot advance", () -> PreviousStepSubmissionAttempt.prepare(
            key(0, 1, 0, "{}", 1, "op-repeat"), restored));
    }

    @Test
    public void onlyAcknowledgingTheLastRecipeCompletesTheChain() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        assertTrue(first.requiresRecipeContinuation());
        PreviousStepSubmissionAttempt firstCompleted = first.beginPosting(first.key)
            .markAcknowledged(first.key);
        PreviousStepSubmissionAttempt second = PreviousStepSubmissionAttempt.prepare(
            key(1, 2, 1, "{\"url\":\"https://example.invalid/real-2\"}", 1, "op-2"),
            PreviousStepSubmissionAttempt.restore(firstCompleted.toJsonString()));
        assertTrue(second.requiresRecipeContinuation());
        PreviousStepSubmissionAttempt allCompleted = second.beginPosting(second.key)
            .markAcknowledged(second.key);
        assertEquals(2, allCompleted.completedRecipeCount());
        assertFalse(allCompleted.requiresRecipeContinuation());
    }

    @Test
    public void chainOrRecipeIdentityMismatchCannotOverwriteCompletedProgress() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt completed = first.beginPosting(first.key)
            .markAcknowledged(first.key);
        PreviousStepSubmissionAttempt.RestoreResult slot =
            PreviousStepSubmissionAttempt.restore(completed.toJsonString());
        PreviousStepSubmissionAttempt.ChainIdentity otherChain =
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                "fedcba9876543210fedc", 17, "sample-profile", A,
                3, "SAMPLE-001", B, C, D, 2);
        PreviousStepSubmissionAttempt.Key wrong = PreviousStepSubmissionAttempt.Key.of(
            otherChain, 1,
            PreviousStepSubmissionAttempt.RecipeIdentity.of(2, 4,
                PreviousStepSubmissionAttempt.RecipeKind.DYNAMIC, A),
            B, 1, "op-wrong");

        assertRejected("cannot advance", () ->
            PreviousStepSubmissionAttempt.prepare(wrong, slot));
        assertFalse(completed.chainMatches(otherChain));
        assertTrue(completed.chainMatches(chain()));
    }

    @Test
    public void changedResolvedDynamicSemanticsCannotContinueCompletedPrefix() {
        PreviousStepSubmissionAttempt first = preparedFirst();
        PreviousStepSubmissionAttempt completed = first.beginPosting(first.key)
            .markAcknowledged(first.key);
        PreviousStepSubmissionAttempt.ChainIdentity changedLiveTemplate =
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                CONNECTION, 17, "sample-profile", A,
                3, "SAMPLE-001", B, C, A, 2);
        PreviousStepSubmissionAttempt.Key mixedChain =
            PreviousStepSubmissionAttempt.Key.of(changedLiveTemplate, 1,
                PreviousStepSubmissionAttempt.RecipeIdentity.of(2, 4,
                    PreviousStepSubmissionAttempt.RecipeKind.DYNAMIC, A),
                B, 1, "op-mixed");

        assertRejected("cannot advance", () ->
            PreviousStepSubmissionAttempt.prepare(mixedChain,
                PreviousStepSubmissionAttempt.restore(completed.toJsonString())));
        assertFalse(completed.chainMatches(changedLiveTemplate));
    }

    @Test
    public void malformedUnknownAndWrongTypedStorageFailLocked() throws Exception {
        assertLocked("", PreviousStepSubmissionAttempt.LockReason.EMPTY_RECORD);
        assertLocked("not-json", PreviousStepSubmissionAttempt.LockReason.MALFORMED_JSON);
        JSONObject unknown = preparedFirst().toJson().put("schemaVersion", 2);
        assertLocked(unknown.toString(), PreviousStepSubmissionAttempt.LockReason.UNKNOWN_VERSION);

        PreviousStepSubmissionAttempt.RestoreResult wrongType =
            PreviousStepSubmissionAttempt.restoreStoredValue(true, Integer.valueOf(7));
        assertEquals(PreviousStepSubmissionAttempt.RestoreKind.LOCKED, wrongType.kind);
        assertEquals(PreviousStepSubmissionAttempt.LockReason.INVALID_STORAGE_TYPE,
            wrongType.lockReason);
        PreviousStepSubmissionAttempt.RestoreResult absent =
            PreviousStepSubmissionAttempt.restoreStoredValue(false, Integer.valueOf(7));
        assertEquals(PreviousStepSubmissionAttempt.RestoreKind.NONE, absent.kind);
        assertNull(absent.attempt);
    }

    @Test
    public void connectionNamespaceMustBeExactLowercaseHex20() {
        assertRejected("lowercase hex20", () ->
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                "sample-connection", 17, "sample-profile", A,
                3, "SAMPLE-001", B, C, D, 2));
        assertRejected("lowercase hex20", () ->
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                "0123456789ABCDEFABCD", 17, "sample-profile", A,
                3, "SAMPLE-001", B, C, D, 2));
    }

    private static void assertLocked(String stored,
                                     PreviousStepSubmissionAttempt.LockReason reason) {
        PreviousStepSubmissionAttempt.RestoreResult result =
            PreviousStepSubmissionAttempt.restore(stored);
        assertEquals(PreviousStepSubmissionAttempt.RestoreKind.LOCKED, result.kind);
        assertEquals(reason, result.lockReason);
    }

    private static void assertRejected(String expected, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("operation must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
