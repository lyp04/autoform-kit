package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ManualQueueDeleteTransactionTest {
    private static final String CONNECTION_A = "0123456789abcdef0123";
    private static final String CONNECTION_B = "fedcba9876543210fedc";
    private static final String LOGICAL_KEY = "manual_saved_queue_json";
    private static final ManualQueueDeleteTransaction.Slot[] SCOPED_DATA = {
        ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE,
        ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK,
        ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP,
        ManualQueueDeleteTransaction.Slot.SCOPED_PREF
    };
    private static final ManualQueueDeleteTransaction.Slot[] GLOBAL_DATA = {
        ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BASE,
        ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BAK,
        ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP,
        ManualQueueDeleteTransaction.Slot.GLOBAL_PREF
    };

    @Test
    public void successfulDeleteRemovesEveryPhysicalCopyAndKeepsCommitTombstone()
            throws Exception {
        String backup = backup("A");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        storage.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE, backup);
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP, backup);

        assertEquals(ManualQueueDeleteTransaction.DeleteResult.DELETED,
            ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, false));

        assertNoOwnedBackup(storage, CONNECTION_A);
        assertFalse(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
        assertTrue(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
        assertEquals(CONNECTION_A, storage.get(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER));
        assertFalse(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, false));
        assertTrue(ManualQueueDeleteTransaction.committedDeletionComplete(
            storage, CONNECTION_A, LOGICAL_KEY));
        assertEquals(ManualQueueDeleteTransaction.DeleteResult.ALREADY_DELETED,
            ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, false));
    }

    @Test
    public void crashAfterEveryDeleteMutationConvergesToExactRestoreOrFullDelete()
            throws Exception {
        String backup = backup("exhaustive");
        FakeStorage completed = completeBackup(CONNECTION_A, backup);
        ManualQueueDeleteTransaction.delete(
            completed, CONNECTION_A, LOGICAL_KEY, backup, false);
        int mutationCount = completed.mutationCount;
        assertTrue(mutationCount > 10);

        for (int crashPoint = 1; crashPoint <= mutationCount; crashPoint++) {
            FakeStorage storage = completeBackup(CONNECTION_A, backup);
            Map<String, String> exactBefore = storage.snapshot();
            storage.crashAfterMutation = crashPoint;
            assertThrows(SimulatedCrash.class,
                () -> ManualQueueDeleteTransaction.delete(
                    storage, CONNECTION_A, LOGICAL_KEY, backup, false));
            storage.disableFaults();

            ManualQueueDeleteTransaction.Recovery recovery =
                ManualQueueDeleteTransaction.recover(
                    storage, CONNECTION_A, LOGICAL_KEY);
            if (recovery == ManualQueueDeleteTransaction.Recovery.RESTORED) {
                assertEquals("crash point " + crashPoint,
                    exactBefore, storage.snapshot());
            } else {
                assertEquals("crash point " + crashPoint,
                    ManualQueueDeleteTransaction.Recovery.COMMITTED, recovery);
                assertNoOwnedBackup(storage, CONNECTION_A);
                assertTrue(storage.has(CONNECTION_A,
                    ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
                assertFalse(storage.has(CONNECTION_A,
                    ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
            }
        }
    }

    @Test
    public void rollbackRecoveryItselfIsRestartIdempotentAtEveryMutation()
            throws Exception {
        String backup = backup("rollback-restart");
        FakeStorage partial = completeBackup(CONNECTION_A, backup);
        Map<String, String> exactBefore = partial.snapshot();
        partial.crashAfterMutation = 3;
        assertThrows(SimulatedCrash.class,
            () -> ManualQueueDeleteTransaction.delete(
                partial, CONNECTION_A, LOGICAL_KEY, backup, false));
        partial.disableFaults();
        Map<String, String> preparedPartial = partial.snapshot();

        FakeStorage count = FakeStorage.from(preparedPartial);
        assertEquals(ManualQueueDeleteTransaction.Recovery.RESTORED,
            ManualQueueDeleteTransaction.recover(
                count, CONNECTION_A, LOGICAL_KEY));
        int recoveryMutations = count.mutationCount;
        assertTrue(recoveryMutations > 2);

        for (int crashPoint = 1; crashPoint <= recoveryMutations; crashPoint++) {
            FakeStorage storage = FakeStorage.from(preparedPartial);
            storage.crashAfterMutation = crashPoint;
            assertThrows(SimulatedCrash.class,
                () -> ManualQueueDeleteTransaction.recover(
                    storage, CONNECTION_A, LOGICAL_KEY));
            storage.disableFaults();
            ManualQueueDeleteTransaction.Recovery finalRecovery =
                ManualQueueDeleteTransaction.recover(
                    storage, CONNECTION_A, LOGICAL_KEY);
            assertTrue(finalRecovery == ManualQueueDeleteTransaction.Recovery.RESTORED
                || finalRecovery == ManualQueueDeleteTransaction.Recovery.NONE);
            assertEquals("recovery crash point " + crashPoint,
                exactBefore, storage.snapshot());
        }
    }

    @Test
    public void committedRecoveryItselfIsRestartIdempotentAtEveryMutation()
            throws Exception {
        String backup = backup("commit-restart");
        FakeStorage partial = completeBackup(CONNECTION_A, backup);
        partial.crashAfterWriteSlot =
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE;
        assertThrows(SimulatedCrash.class,
            () -> ManualQueueDeleteTransaction.delete(
                partial, CONNECTION_A, LOGICAL_KEY, backup, false));
        partial.disableFaults();
        Map<String, String> committedPartial = partial.snapshot();
        assertTrue(partial.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
        assertTrue(partial.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));

        FakeStorage count = FakeStorage.from(committedPartial);
        assertEquals(ManualQueueDeleteTransaction.Recovery.COMMITTED,
            ManualQueueDeleteTransaction.recover(
                count, CONNECTION_A, LOGICAL_KEY));
        int recoveryMutations = count.mutationCount;

        for (int crashPoint = 1; crashPoint <= recoveryMutations; crashPoint++) {
            FakeStorage storage = FakeStorage.from(committedPartial);
            storage.crashAfterMutation = crashPoint;
            assertThrows(SimulatedCrash.class,
                () -> ManualQueueDeleteTransaction.recover(
                    storage, CONNECTION_A, LOGICAL_KEY));
            storage.disableFaults();
            assertEquals(ManualQueueDeleteTransaction.Recovery.COMMITTED,
                ManualQueueDeleteTransaction.recover(
                    storage, CONNECTION_A, LOGICAL_KEY));
            assertNoOwnedBackup(storage, CONNECTION_A);
            assertFalse(storage.has(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
            assertTrue(storage.has(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
        }
    }

    @Test
    public void nonfatalMutationFailureSynchronouslyRestoresExactState()
            throws Exception {
        String backup = backup("io-failure");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        Map<String, String> exactBefore = storage.snapshot();
        storage.failBeforeMutation = 5;

        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, false));

        assertEquals(exactBefore, storage.snapshot());
        assertEquals(ManualQueueDeleteTransaction.Recovery.NONE,
            ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));
    }

    @Test
    public void failureReportedAfterDurableTombstoneConvergesAsSuccess()
            throws Exception {
        String backup = backup("late-commit-failure");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        storage.failAfterWriteSlot =
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE;

        assertEquals(ManualQueueDeleteTransaction.DeleteResult.DELETED,
            ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, false));

        assertNoOwnedBackup(storage, CONNECTION_A);
        assertTrue(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
        assertFalse(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
    }

    @Test
    public void corruptUncommittedTombstoneRollsBackInsteadOfAuthorizingDeletion()
            throws Exception {
        String backup = backup("invalid-tombstone");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        Map<String, String> exactBefore = storage.snapshot();
        storage.crashAfterMutation = 3;
        assertThrows(SimulatedCrash.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, false));
        storage.disableFaults();
        storage.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE, "{");

        assertEquals(ManualQueueDeleteTransaction.Recovery.RESTORED,
            ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));
        assertEquals(exactBefore, storage.snapshot());
    }

    @Test
    public void malformedReceiptAndDivergentTempFailClosedWithoutMutation()
            throws Exception {
        String backup = backup("malformed");
        FakeStorage malformedReceipt = completeBackup(CONNECTION_A, backup);
        malformedReceipt.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT, "{");
        Map<String, String> beforeReceipt = malformedReceipt.snapshot();
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.recover(
                malformedReceipt, CONNECTION_A, LOGICAL_KEY));
        assertEquals(beforeReceipt, malformedReceipt.snapshot());

        FakeStorage divergent = completeBackup(CONNECTION_A, backup);
        divergent.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP, "partial");
        Map<String, String> beforeDivergent = divergent.snapshot();
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                divergent, CONNECTION_A, LOGICAL_KEY, backup, false));
        assertEquals(beforeDivergent, divergent.snapshot());
    }

    @Test
    public void blockedMirrorIsDurableEvidenceForDeleteAndPromotion()
            throws Exception {
        String backup = backup("blocked");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        Map<String, String> before = storage.snapshot();

        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backup, true));
        assertEquals(before, storage.snapshot());
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, true));

        FakeStorage otherwiseEmpty = new FakeStorage();
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            otherwiseEmpty, CONNECTION_A, LOGICAL_KEY, true));
    }

    @Test
    public void everyRecoverableRepresentationIndependentlyBlocksPromotion()
            throws Exception {
        ManualQueueDeleteTransaction.Slot[] scopedEvidence = {
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF,
            ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT
        };
        for (ManualQueueDeleteTransaction.Slot slot : scopedEvidence) {
            FakeStorage storage = new FakeStorage();
            storage.putScoped(CONNECTION_A, slot, "evidence");
            assertTrue(slot.name(),
                ManualQueueDeleteTransaction.blocksPanelPromotion(
                    storage, CONNECTION_A, LOGICAL_KEY, false));
        }

        for (ManualQueueDeleteTransaction.Slot slot : GLOBAL_DATA) {
            FakeStorage storage = new FakeStorage();
            storage.putGlobal(
                ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER, CONNECTION_A);
            storage.putGlobal(slot, "evidence");
            assertTrue(slot.name(),
                ManualQueueDeleteTransaction.blocksPanelPromotion(
                    storage, CONNECTION_A, LOGICAL_KEY, false));
        }

        FakeStorage ownerlessGlobal = new FakeStorage();
        ownerlessGlobal.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BAK, "evidence");
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            ownerlessGlobal, CONNECTION_A, LOGICAL_KEY, false));

        FakeStorage malformedTombstone = new FakeStorage();
        malformedTombstone.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE, "{");
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            malformedTombstone, CONNECTION_A, LOGICAL_KEY, false));
    }

    @Test
    public void deletingAWithBGlobalOwnerPreservesEveryBByte()
            throws Exception {
        String backupA = backup("A-scoped");
        String backupB = backup("B-global");
        FakeStorage storage = scopedBackup(CONNECTION_A, backupA);
        putForeignGlobals(storage, CONNECTION_B, backupB);
        Map<String, String> globalsBefore = storage.globalSnapshot();

        assertEquals(ManualQueueDeleteTransaction.DeleteResult.DELETED,
            ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backupA, false));

        assertEquals(globalsBefore, storage.globalSnapshot());
        assertNoOwnedBackup(storage, CONNECTION_A);
        assertFalse(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, false));
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_B, LOGICAL_KEY, false));
    }

    @Test
    public void preparedARecoveryAfterBTakeoverRestoresAWithoutTouchingB()
            throws Exception {
        String backupA = backup("A-before-crash");
        String backupB = backup("B-after-crash");
        FakeStorage storage = completeBackup(CONNECTION_A, backupA);
        storage.crashAfterMutation = 4;
        assertThrows(SimulatedCrash.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backupA, false));
        storage.disableFaults();

        putForeignGlobals(storage, CONNECTION_B, backupB);
        Map<String, String> bBefore = storage.globalSnapshot();
        assertEquals(ManualQueueDeleteTransaction.Recovery.RESTORED,
            ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));

        assertEquals(bBefore, storage.globalSnapshot());
        for (ManualQueueDeleteTransaction.Slot slot : SCOPED_DATA) {
            assertEquals(backupA, storage.get(CONNECTION_A, slot));
        }
        assertTrue(RollbackMirrorRules.receiptMatches(
            new JSONObject(storage.get(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT)),
            CONNECTION_A, LOGICAL_KEY, backupA));
        assertFalse(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
    }

    @Test
    public void committedARecoveryAfterBTakeoverDeletesOnlyA()
            throws Exception {
        String backupA = backup("A-committed");
        String backupB = backup("B-owns-global");
        FakeStorage storage = completeBackup(CONNECTION_A, backupA);
        storage.crashAfterWriteSlot =
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE;
        assertThrows(SimulatedCrash.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backupA, false));
        storage.disableFaults();

        putForeignGlobals(storage, CONNECTION_B, backupB);
        Map<String, String> bBefore = storage.globalSnapshot();
        assertEquals(ManualQueueDeleteTransaction.Recovery.COMMITTED,
            ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));

        assertEquals(bBefore, storage.globalSnapshot());
        assertNoOwnedBackup(storage, CONNECTION_A);
        assertTrue(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
    }

    @Test
    public void foreignOwnerCannotHideExactABytesInGlobalSlot()
            throws Exception {
        String backupA = backup("A");
        FakeStorage storage = scopedBackup(CONNECTION_A, backupA);
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER, CONNECTION_B);
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BASE, backupA);
        Map<String, String> before = storage.snapshot();

        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                storage, CONNECTION_A, LOGICAL_KEY, backupA, false));
        assertEquals(before, storage.snapshot());
    }

    @Test
    public void validTombstoneCanBeClearedOnlyBeforeANewSaveBegins()
            throws Exception {
        String backup = backup("new-save");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        ManualQueueDeleteTransaction.delete(
            storage, CONNECTION_A, LOGICAL_KEY, backup, false);

        ManualQueueDeleteTransaction.clearCommittedTombstoneForNewSave(
            storage, CONNECTION_A, LOGICAL_KEY);
        assertFalse(storage.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));

        FakeStorage unsafe = completeBackup(CONNECTION_A, backup);
        ManualQueueDeleteTransaction.delete(
            unsafe, CONNECTION_A, LOGICAL_KEY, backup, false);
        unsafe.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK, backup);
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.clearCommittedTombstoneForNewSave(
                unsafe, CONNECTION_A, LOGICAL_KEY));
        assertTrue(unsafe.has(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
    }

    @Test
    public void staleArtifactAfterCommittedReceiptWasRetiredBlocksUpgrade()
            throws Exception {
        String backup = backup("stale");
        FakeStorage storage = completeBackup(CONNECTION_A, backup);
        ManualQueueDeleteTransaction.delete(
            storage, CONNECTION_A, LOGICAL_KEY, backup, false);
        storage.putScoped(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK, backup);

        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, false));
        assertFalse(ManualQueueDeleteTransaction.committedDeletionComplete(
            storage, CONNECTION_A, LOGICAL_KEY));
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));
    }

    @Test
    public void committedDigestStillDetectsABytesAfterBClaimsGlobalOwner()
            throws Exception {
        String backupA = backup("A-stale-global");
        FakeStorage storage = completeBackup(CONNECTION_A, backupA);
        ManualQueueDeleteTransaction.delete(
            storage, CONNECTION_A, LOGICAL_KEY, backupA, false);
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER, CONNECTION_B);
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP, backupA);

        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, false));
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.recover(
                storage, CONNECTION_A, LOGICAL_KEY));
    }

    @Test
    public void emptyStorageWithoutTombstoneIsNotDeletionSuccess()
            throws Exception {
        FakeStorage storage = new FakeStorage();
        assertFalse(ManualQueueDeleteTransaction.blocksPanelPromotion(
            storage, CONNECTION_A, LOGICAL_KEY, false));
        assertFalse(ManualQueueDeleteTransaction.committedDeletionComplete(
            storage, CONNECTION_A, LOGICAL_KEY));
    }

    private static FakeStorage completeBackup(String connection, String backup)
            throws Exception {
        FakeStorage storage = scopedBackup(connection, backup);
        for (ManualQueueDeleteTransaction.Slot slot : GLOBAL_DATA) {
            storage.putGlobal(slot, backup);
        }
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER, connection);
        return storage;
    }

    private static FakeStorage scopedBackup(String connection, String backup)
            throws Exception {
        FakeStorage storage = new FakeStorage();
        for (ManualQueueDeleteTransaction.Slot slot : SCOPED_DATA) {
            storage.putScoped(connection, slot, backup);
        }
        storage.putScoped(connection,
            ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT,
            RollbackMirrorRules.newReceipt(
                connection, LOGICAL_KEY, backup).toString());
        return storage;
    }

    private static void putForeignGlobals(FakeStorage storage, String owner,
                                          String value) {
        storage.putGlobal(
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER, owner);
        for (ManualQueueDeleteTransaction.Slot slot : GLOBAL_DATA) {
            storage.putGlobal(slot, value);
        }
    }

    private static String backup(String marker) throws Exception {
        JSONObject binding = new JSONObject()
            .put("connectionNamespace", CONNECTION_A)
            .put("catalogVersion", 7)
            .put("panelPairSha256",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .put("profileId", "sample-form");
        JSONObject unit = new JSONObject()
            .put("sequence", 1)
            .put("sn", "SAMPLE0001")
            .put("frontPhoto", "/private/front-" + marker + ".jpg")
            .put("backPhoto", "/private/back-" + marker + ".jpg");
        return new JSONObject()
            .put("version", MainDraftSnapshotRules.DRAFT_VERSION)
            .put("profileId", "sample-form")
            .put(MainDraftSnapshotRules.BINDING_FIELD, binding)
            .put("savedAt", 100)
            .put("marker", marker)
            .put("units", new JSONArray().put(unit))
            .toString();
    }

    private static void assertNoOwnedBackup(FakeStorage storage, String connection) {
        for (ManualQueueDeleteTransaction.Slot slot : SCOPED_DATA) {
            assertFalse(slot.name(), storage.has(connection, slot));
        }
        assertFalse(storage.has(connection,
            ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT));
        String owner = storage.get(connection,
            ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER);
        if (connection.equals(owner)) {
            for (ManualQueueDeleteTransaction.Slot slot : GLOBAL_DATA) {
                assertFalse(slot.name(), storage.has(connection, slot));
            }
        }
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static final class FakeStorage
            implements ManualQueueDeleteTransaction.Storage {
        final Map<String, String> values = new LinkedHashMap<>();
        int mutationCount;
        int crashAfterMutation = -1;
        int failBeforeMutation = -1;
        ManualQueueDeleteTransaction.Slot crashAfterWriteSlot;
        ManualQueueDeleteTransaction.Slot failAfterWriteSlot;

        static FakeStorage from(Map<String, String> values) {
            FakeStorage storage = new FakeStorage();
            storage.values.putAll(values);
            return storage;
        }

        void putScoped(String connection, ManualQueueDeleteTransaction.Slot slot,
                       String value) {
            values.put(key(connection, slot), value);
        }

        void putGlobal(ManualQueueDeleteTransaction.Slot slot, String value) {
            values.put(key(CONNECTION_A, slot), value);
        }

        boolean has(String connection, ManualQueueDeleteTransaction.Slot slot) {
            return values.containsKey(key(connection, slot));
        }

        String get(String connection, ManualQueueDeleteTransaction.Slot slot) {
            return values.get(key(connection, slot));
        }

        Map<String, String> snapshot() {
            return new LinkedHashMap<>(values);
        }

        Map<String, String> globalSnapshot() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("global/")) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
            return out;
        }

        void disableFaults() {
            crashAfterMutation = -1;
            failBeforeMutation = -1;
            crashAfterWriteSlot = null;
            failAfterWriteSlot = null;
            mutationCount = 0;
        }

        @Override
        public boolean exists(String connectionNamespace,
                              ManualQueueDeleteTransaction.Slot slot) {
            return has(connectionNamespace, slot);
        }

        @Override
        public String readUtf8(String connectionNamespace,
                               ManualQueueDeleteTransaction.Slot slot)
                throws IOException {
            if (!has(connectionNamespace, slot)) {
                throw new IOException("missing " + slot);
            }
            return get(connectionNamespace, slot);
        }

        @Override
        public void writeUtf8(String connectionNamespace,
                              ManualQueueDeleteTransaction.Slot slot,
                              String value) throws IOException {
            beforeMutation();
            values.put(key(connectionNamespace, slot), value);
            afterMutation();
            if (slot == crashAfterWriteSlot) throw new SimulatedCrash();
            if (slot == failAfterWriteSlot) {
                failAfterWriteSlot = null;
                throw new IOException("injected failure after durable write");
            }
        }

        @Override
        public void delete(String connectionNamespace,
                           ManualQueueDeleteTransaction.Slot slot)
                throws IOException {
            beforeMutation();
            values.remove(key(connectionNamespace, slot));
            afterMutation();
        }

        private void beforeMutation() throws IOException {
            int next = mutationCount + 1;
            if (next == failBeforeMutation) {
                failBeforeMutation = -1;
                throw new IOException("injected storage failure");
            }
        }

        private void afterMutation() {
            mutationCount++;
            if (mutationCount == crashAfterMutation) throw new SimulatedCrash();
        }

        private static String key(String connection,
                                  ManualQueueDeleteTransaction.Slot slot) {
            switch (slot) {
                case GLOBAL_FILE_BASE:
                case GLOBAL_FILE_BAK:
                case GLOBAL_FILE_TMP:
                case GLOBAL_PREF:
                case GLOBAL_OWNER:
                    return "global/" + slot.name();
                default:
                    return connection + "/" + slot.name();
            }
        }
    }
}
