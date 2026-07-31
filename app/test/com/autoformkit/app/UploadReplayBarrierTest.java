package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class UploadReplayBarrierTest {
    private static final String CONNECTION = "0123456789abcdefabcd";
    private static final String OTHER_CONNECTION = "fedcba9876543210fedc";
    private static final String A = repeat('a', 64);
    private static final String B = repeat('b', 64);
    private static final String C = repeat('c', 64);
    private static final String D = repeat('d', 64);
    private static final String E = repeat('e', 64);
    private static final String F = repeat('f', 64);
    private static final String OPERATION = "operation-00000001";

    private interface JsonMutation {
        void apply(JSONObject root) throws Exception;
    }

    private static UploadReplayBarrier.Identity mainIdentity() {
        return mainIdentity(CONNECTION, 17, "sample-main", A, B, C, D, E, OPERATION);
    }

    private static UploadReplayBarrier.Identity mainIdentity(
            String connectionNamespace, int catalogVersion, String profileId,
            String panelPairSha256, String bindingFingerprintSha256,
            String backendFingerprintSha256, String sessionFingerprintSha256,
            String sourceSnapshotSha256, String operationId) {
        return UploadReplayBarrier.Identity.main(connectionNamespace, catalogVersion,
            profileId, panelPairSha256, bindingFingerprintSha256,
            backendFingerprintSha256, sessionFingerprintSha256,
            sourceSnapshotSha256, operationId);
    }

    private static UploadReplayBarrier.Identity alternateIdentity() {
        return alternateIdentity("sample-source", "sample-target");
    }

    private static UploadReplayBarrier.Identity alternateIdentity(
            String sourceProfileId, String targetProfileId) {
        return UploadReplayBarrier.Identity.alternate(CONNECTION, 17,
            sourceProfileId, targetProfileId, A, B, C, D, E, OPERATION);
    }

    private static UploadReplayBarrier mainBarrier() {
        return UploadReplayBarrier.prepare(
            mainIdentity(), UploadReplayBarrier.restore(null));
    }

    @Test
    public void mainIdentityRoundTripsWithExactNonSensitiveSchema() throws Exception {
        UploadReplayBarrier barrier = mainBarrier();
        JSONObject root = barrier.toJson();
        JSONObject identity = root.getJSONObject("identity");

        assertEquals(3, root.length());
        assertEquals(1, root.getInt("schemaVersion"));
        assertEquals("STARTED", root.getString("state"));
        assertEquals(10, identity.length());
        assertEquals("MAIN", identity.getString("flow"));
        assertEquals(CONNECTION, identity.getString("connectionNamespace"));
        assertEquals(17, identity.getInt("catalogVersion"));
        assertEquals("sample-main", identity.getString("profileId"));
        assertFalse(identity.has("sourceProfileId"));
        assertFalse(identity.has("targetProfileId"));
        assertEquals(A, identity.getString("panelPairSha256"));
        assertEquals(B, identity.getString("bindingFingerprintSha256"));
        assertEquals(C, identity.getString("backendFingerprintSha256"));
        assertEquals(D, identity.getString("sessionFingerprintSha256"));
        assertEquals(E, identity.getString("sourceSnapshotSha256"));
        assertEquals(OPERATION, identity.getString("operationId"));

        UploadReplayBarrier.RestoreResult restored =
            UploadReplayBarrier.restore(barrier.toJsonString());
        assertEquals(UploadReplayBarrier.RestoreKind.RESTORED, restored.kind);
        assertEquals(UploadReplayBarrier.State.STARTED, restored.barrier.state);
        assertEquals(mainIdentity(), restored.barrier.identity);
        assertTrue(restored.barrier.matches(mainIdentity()));
        assertNull(restored.lockReason);
    }

    @Test
    public void alternateIdentityUsesOnlySourceAndTargetProfileKeys() throws Exception {
        UploadReplayBarrier barrier = UploadReplayBarrier.prepare(
            alternateIdentity(), UploadReplayBarrier.restore(null));
        JSONObject identity = barrier.toJson().getJSONObject("identity");

        assertEquals(11, identity.length());
        assertEquals("ALTERNATE", identity.getString("flow"));
        assertEquals("sample-source", identity.getString("sourceProfileId"));
        assertEquals("sample-target", identity.getString("targetProfileId"));
        assertFalse(identity.has("profileId"));

        UploadReplayBarrier.RestoreResult restored =
            UploadReplayBarrier.restore(barrier.toJsonString());
        assertEquals(UploadReplayBarrier.RestoreKind.RESTORED, restored.kind);
        assertEquals(alternateIdentity(), restored.barrier.identity);
    }

    @Test
    public void durableRecordContainsNoRemoteOrUnitDataFields() {
        String serialized = mainBarrier().toJsonString().toLowerCase(java.util.Locale.US);

        assertFalse(serialized.contains("\"url\""));
        assertFalse(serialized.contains("\"serial\""));
        assertFalse(serialized.contains("\"sn\""));
        assertFalse(serialized.contains("credential"));
        assertFalse(serialized.contains("\"token\""));
        assertFalse(serialized.contains("payload"));
        assertFalse(serialized.contains("requestheader"));
    }

    @Test
    public void nullAloneMeansAbsentAndEveryCorruptRepresentationLocks() {
        UploadReplayBarrier.RestoreResult absent = UploadReplayBarrier.restore(null);
        assertEquals(UploadReplayBarrier.RestoreKind.NONE, absent.kind);
        assertNull(absent.barrier);
        assertNull(absent.lockReason);

        assertLocked("", UploadReplayBarrier.LockReason.EMPTY_RECORD);
        assertLocked("   ", UploadReplayBarrier.LockReason.EMPTY_RECORD);
        assertLocked("not-json", UploadReplayBarrier.LockReason.MALFORMED_JSON);
        assertLocked("[]", UploadReplayBarrier.LockReason.MALFORMED_JSON);
        assertLocked("null", UploadReplayBarrier.LockReason.MALFORMED_JSON);
        assertLocked("{}", UploadReplayBarrier.LockReason.INVALID_SCHEMA);
    }

    @Test
    public void wrongTypedStoredValuesLockInsteadOfLookingAbsent() {
        UploadReplayBarrier.RestoreResult absent =
            UploadReplayBarrier.restoreStoredValue(false, Integer.valueOf(7));
        assertEquals(UploadReplayBarrier.RestoreKind.NONE, absent.kind);

        assertStoredValueLocked(null);
        assertStoredValueLocked(Integer.valueOf(7));
        assertStoredValueLocked(Boolean.TRUE);
        assertStoredValueLocked(new java.util.LinkedHashSet<String>());
        assertStoredValueLocked(new JSONObject());
    }

    @Test
    public void processRestartKeepsStartedBarrierBlocking() {
        UploadReplayBarrier started = mainBarrier();
        UploadReplayBarrier.RestoreResult restored =
            UploadReplayBarrier.restore(started.toJsonString());

        assertEquals(UploadReplayBarrier.RestoreKind.RESTORED, restored.kind);
        assertEquals(UploadReplayBarrier.State.STARTED, restored.barrier.state);
        assertTrue(restored.barrier.matches(mainIdentity()));
        assertRejected("slot is not empty", () ->
            UploadReplayBarrier.prepare(mainIdentity(), restored));
    }

    @Test
    public void prepareRequiresAuthoritativelyEmptySlot() {
        UploadReplayBarrier prepared = UploadReplayBarrier.prepare(
            mainIdentity(), UploadReplayBarrier.restoreStoredValue(false, "ignored"));
        assertEquals(UploadReplayBarrier.State.STARTED, prepared.state);

        assertRejected("requested identity is required", () ->
            UploadReplayBarrier.prepare(null, UploadReplayBarrier.restore(null)));
        assertRejected("restored slot is required", () ->
            UploadReplayBarrier.prepare(mainIdentity(), null));
        assertRejected("slot is not empty", () -> UploadReplayBarrier.prepare(
            differentMainOperation(), UploadReplayBarrier.restore(prepared.toJsonString())));
        assertRejected("slot is not empty", () -> UploadReplayBarrier.prepare(
            mainIdentity(), UploadReplayBarrier.restore("{broken")));
    }

    @Test
    public void matchesRequiresEveryMainIdentityComponent() {
        UploadReplayBarrier barrier = mainBarrier();

        assertTrue(barrier.matches(mainIdentity()));
        assertFalse(barrier.matches(null));
        assertFalse(barrier.matches(mainIdentity(
            OTHER_CONNECTION, 17, "sample-main", A, B, C, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 18, "sample-main", A, B, C, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main-other", A, B, C, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main", F, B, C, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main", A, F, C, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main", A, B, F, D, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, F, E, OPERATION)));
        assertFalse(barrier.matches(mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, F, OPERATION)));
        assertFalse(barrier.matches(differentMainOperation()));
        assertFalse(barrier.matches(alternateIdentity()));
    }

    @Test
    public void matchesRequiresBothAlternateProfileIds() {
        UploadReplayBarrier barrier = UploadReplayBarrier.prepare(
            alternateIdentity(), UploadReplayBarrier.restore(null));

        assertTrue(barrier.matches(alternateIdentity()));
        assertFalse(barrier.matches(alternateIdentity(
            "sample-source-other", "sample-target")));
        assertFalse(barrier.matches(alternateIdentity(
            "sample-source", "sample-target-other")));
    }

    @Test
    public void versionStateAndFlowAreExactEnumsAndTypes() throws Exception {
        JSONObject unknownVersion = mainBarrier().toJson().put("schemaVersion", 2);
        assertLocked(unknownVersion.toString(), UploadReplayBarrier.LockReason.UNKNOWN_VERSION);

        String integerVersion = mainBarrier().toJsonString();
        String fractionalVersion = integerVersion.replace(
            "\"schemaVersion\":1", "\"schemaVersion\":1.0");
        assertFalse(integerVersion.equals(fractionalVersion));
        assertLocked(fractionalVersion, UploadReplayBarrier.LockReason.UNKNOWN_VERSION);

        JSONObject versionString = mainBarrier().toJson().put("schemaVersion", "1");
        assertLocked(versionString.toString(), UploadReplayBarrier.LockReason.UNKNOWN_VERSION);
        JSONObject unknownState = mainBarrier().toJson().put("state", "COMPLETED");
        assertLocked(unknownState.toString(), UploadReplayBarrier.LockReason.UNKNOWN_STATE);
        JSONObject lowerState = mainBarrier().toJson().put("state", "started");
        assertLocked(lowerState.toString(), UploadReplayBarrier.LockReason.UNKNOWN_STATE);
        JSONObject numericState = mainBarrier().toJson().put("state", 1);
        assertLocked(numericState.toString(), UploadReplayBarrier.LockReason.INVALID_SCHEMA);

        JSONObject unknownFlow = mainBarrier().toJson();
        unknownFlow.getJSONObject("identity").put("flow", "OTHER");
        assertLocked(unknownFlow.toString(), UploadReplayBarrier.LockReason.UNKNOWN_FLOW);
        JSONObject lowerFlow = mainBarrier().toJson();
        lowerFlow.getJSONObject("identity").put("flow", "main");
        assertLocked(lowerFlow.toString(), UploadReplayBarrier.LockReason.UNKNOWN_FLOW);
        JSONObject numericFlow = mainBarrier().toJson();
        numericFlow.getJSONObject("identity").put("flow", 1);
        assertLocked(numericFlow.toString(), UploadReplayBarrier.LockReason.INVALID_SCHEMA);
    }

    @Test
    public void rootAndConditionalIdentityKeysAreExact() throws Exception {
        assertMutationLocked(root -> root.put("future", true));
        assertMutationLocked(root -> root.remove("state"));
        assertMutationLocked(root -> root.put("identity", "not-an-object"));
        assertMutationLocked(root -> root.getJSONObject("identity").put("future", true));
        assertMutationLocked(root -> root.getJSONObject("identity").remove("operationId"));
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("sourceProfileId", "sample-source"));

        JSONObject alternateWithMainKey = UploadReplayBarrier.prepare(
            alternateIdentity(), UploadReplayBarrier.restore(null)).toJson();
        alternateWithMainKey.getJSONObject("identity").put("profileId", "sample-main");
        assertLocked(alternateWithMainKey.toString(),
            UploadReplayBarrier.LockReason.INVALID_SCHEMA);

        JSONObject wrongConditionalShape = mainBarrier().toJson();
        wrongConditionalShape.getJSONObject("identity").put("flow", "ALTERNATE");
        assertLocked(wrongConditionalShape.toString(),
            UploadReplayBarrier.LockReason.INVALID_SCHEMA);
    }

    @Test
    public void everyPersistedIdentityFieldHasAnExactType() throws Exception {
        String[] textFields = {
            "connectionNamespace", "profileId", "panelPairSha256",
            "bindingFingerprintSha256", "backendFingerprintSha256",
            "sessionFingerprintSha256", "sourceSnapshotSha256", "operationId"
        };
        for (String field : textFields) {
            assertMutationLocked(root -> root.getJSONObject("identity").put(field, 7));
            assertMutationLocked(root -> root.getJSONObject("identity")
                .put(field, JSONObject.NULL));
        }
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("catalogVersion", "17"));
        String integerCatalog = mainBarrier().toJsonString();
        String fractionalCatalog = integerCatalog.replace(
            "\"catalogVersion\":17", "\"catalogVersion\":17.0");
        assertFalse(integerCatalog.equals(fractionalCatalog));
        assertLocked(fractionalCatalog, UploadReplayBarrier.LockReason.INVALID_SCHEMA);
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("catalogVersion", 0));
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("catalogVersion", -1));
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("catalogVersion", Long.MAX_VALUE));
    }

    @Test
    public void digestsNamespaceCatalogAndProfileIdsAreStrict() {
        assertRejected("lowercase hex20", () -> mainIdentity(
            "0123456789ABCDEFABCD", 17, "sample-main", A, B, C, D, E, OPERATION));
        assertRejected("lowercase hex20", () -> mainIdentity(
            "0123456789abcdefabc", 17, "sample-main", A, B, C, D, E, OPERATION));
        assertRejected("catalogVersion must be positive", () -> mainIdentity(
            CONNECTION, 0, "sample-main", A, B, C, D, E, OPERATION));
        assertEquals(Integer.MAX_VALUE, mainIdentity(
            CONNECTION, Integer.MAX_VALUE, "sample-main", A, B, C, D, E, OPERATION)
            .catalogVersion);

        assertRejected("profileId is required", () -> mainIdentity(
            CONNECTION, 17, "", A, B, C, D, E, OPERATION));
        assertRejected("profileId is required", () -> mainIdentity(
            CONNECTION, 17, " sample-main", A, B, C, D, E, OPERATION));
        assertRejected("profileId contains a control", () -> mainIdentity(
            CONNECTION, 17, "sample\nmain", A, B, C, D, E, OPERATION));
        assertEquals(UploadReplayBarrier.MAX_PROFILE_ID_LENGTH,
            mainIdentity(CONNECTION, 17,
                repeat('p', UploadReplayBarrier.MAX_PROFILE_ID_LENGTH),
                A, B, C, D, E, OPERATION).profileId.length());
        assertRejected("profileId is too long", () -> mainIdentity(
            CONNECTION, 17,
            repeat('p', UploadReplayBarrier.MAX_PROFILE_ID_LENGTH + 1),
            A, B, C, D, E, OPERATION));
        assertRejected("must differ", () -> alternateIdentity("same-profile", "same-profile"));

        assertRejected("panelPairSha256 must be", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A.substring(1), B, C, D, E, OPERATION));
        assertRejected("bindingFingerprintSha256 must be", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B.toUpperCase(java.util.Locale.US),
            C, D, E, OPERATION));
        assertRejected("backendFingerprintSha256 must be", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, "z" + C.substring(1),
            D, E, OPERATION));
        assertRejected("sessionFingerprintSha256 is required", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, "", E, OPERATION));
        assertRejected("sourceSnapshotSha256 must be", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, F.substring(1), OPERATION));
    }

    @Test
    public void operationIdAcceptsUuidTextButHasExactLengthAndAlphabet() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(uuid, mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, E, uuid).operationId);
        assertEquals(UploadReplayBarrier.MIN_OPERATION_ID_LENGTH,
            mainIdentity(CONNECTION, 17, "sample-main", A, B, C, D, E,
                repeat('a', UploadReplayBarrier.MIN_OPERATION_ID_LENGTH))
                .operationId.length());
        assertEquals(UploadReplayBarrier.MAX_OPERATION_ID_LENGTH,
            mainIdentity(CONNECTION, 17, "sample-main", A, B, C, D, E,
                repeat('a', UploadReplayBarrier.MAX_OPERATION_ID_LENGTH))
                .operationId.length());
        assertRejected("operationId is invalid", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, E,
            repeat('a', UploadReplayBarrier.MIN_OPERATION_ID_LENGTH - 1)));
        assertRejected("operationId is too long", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, E,
            repeat('a', UploadReplayBarrier.MAX_OPERATION_ID_LENGTH + 1)));
        assertRejected("operationId is invalid", () -> mainIdentity(
            CONNECTION, 17, "sample-main", A, B, C, D, E,
            "operation/00000001"));
    }

    @Test
    public void malformedPersistedDigestsAndLengthsStayLocked() throws Exception {
        String[] digestFields = {
            "panelPairSha256", "bindingFingerprintSha256", "backendFingerprintSha256",
            "sessionFingerprintSha256", "sourceSnapshotSha256"
        };
        for (String field : digestFields) {
            assertMutationLocked(root -> root.getJSONObject("identity")
                .put(field, A.substring(1)));
            assertMutationLocked(root -> root.getJSONObject("identity")
                .put(field, A.toUpperCase(java.util.Locale.US)));
        }
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("connectionNamespace", "0123456789ABCDEFABCD"));
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("profileId", repeat('p', UploadReplayBarrier.MAX_PROFILE_ID_LENGTH + 1)));
        assertMutationLocked(root -> root.getJSONObject("identity")
            .put("operationId", "short"));
    }

    private static UploadReplayBarrier.Identity differentMainOperation() {
        return mainIdentity(CONNECTION, 17, "sample-main", A, B, C, D, E,
            "operation-00000002");
    }

    private static void assertMutationLocked(JsonMutation mutation) throws Exception {
        JSONObject changed = mainBarrier().toJson();
        mutation.apply(changed);
        assertLocked(changed.toString(), UploadReplayBarrier.LockReason.INVALID_SCHEMA);
    }

    private static void assertStoredValueLocked(Object value) {
        UploadReplayBarrier.RestoreResult result =
            UploadReplayBarrier.restoreStoredValue(true, value);
        assertEquals(UploadReplayBarrier.RestoreKind.LOCKED, result.kind);
        assertEquals(UploadReplayBarrier.LockReason.INVALID_STORAGE_TYPE, result.lockReason);
        assertNull(result.barrier);
    }

    private static void assertLocked(String persisted, UploadReplayBarrier.LockReason reason) {
        UploadReplayBarrier.RestoreResult result = UploadReplayBarrier.restore(persisted);
        assertEquals(UploadReplayBarrier.RestoreKind.LOCKED, result.kind);
        assertEquals(reason, result.lockReason);
        assertNull(result.barrier);
    }

    private static void assertRejected(String messagePart, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
            return;
        }
        throw new AssertionError("expected rejection containing: " + messagePart);
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index += 1) out.append(value);
        return out.toString();
    }
}
