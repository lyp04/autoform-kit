package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ManualQueueDeleteStorageTest {
    private static final String CONNECTION_A = "0123456789abcdef0123";
    private static final String CONNECTION_B = "fedcba9876543210fedc";
    private static final String LOGICAL_KEY = "manual_saved_queue_json";
    private static final String GLOBAL_OWNER_KEY =
        "rollback_global_owner_namespace_v1";
    private static final String BACKUP =
        "{\"version\":3,\"profileId\":\"sample-form\",\"savedAt\":100,"
        + "\"units\":[{\"sequence\":1,\"sn\":\"SAMPLE0001\"}]}";

    @Test
    public void mapsBaseBakTmpAndTransactionFilesToExactDistinctPaths()
            throws Exception {
        Fixture fixture = new Fixture();
        ManualQueueDeleteStorage storage = fixture.storage;
        File scoped = new File(fixture.root,
            "queue-backup-" + CONNECTION_A + ".json");
        File global = new File(fixture.root, "queue-backup.json");

        assertEquals(scoped.getCanonicalPath(), storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE).getCanonicalPath());
        assertEquals(scoped.getCanonicalPath() + ".bak", storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK).getCanonicalPath());
        assertEquals(scoped.getCanonicalPath() + ".tmp", storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP).getCanonicalPath());
        assertEquals(global.getCanonicalPath(), storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BASE).getCanonicalPath());
        assertEquals(global.getCanonicalPath() + ".bak", storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BAK).getCanonicalPath());
        assertEquals(global.getCanonicalPath() + ".tmp", storage.fileForTest(
            CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP).getCanonicalPath());

        File receiptA = storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT);
        File receiptB = storage.fileForTest(CONNECTION_B,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT);
        File tombstoneA = storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE);
        assertNotEquals(receiptA.getCanonicalPath(), receiptB.getCanonicalPath());
        assertNotEquals(receiptA.getCanonicalPath(), tombstoneA.getCanonicalPath());
        assertEquals("manual-queue-delete-v1",
            receiptA.getParentFile().getName());
        assertTrue(receiptA.getName().startsWith(CONNECTION_A + "-"));
        assertTrue(tombstoneA.getName().startsWith(CONNECTION_A + "-"));
    }

    @Test
    public void mapsScopedGlobalOwnerAndMirrorPreferenceKeysExactly() {
        Fixture fixture = new Fixture();
        ManualQueueDeleteStorage storage = fixture.storage;

        assertEquals(LOGICAL_KEY + "_" + CONNECTION_A,
            storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF));
        assertEquals(LOGICAL_KEY,
            storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.GLOBAL_PREF));
        assertEquals(GLOBAL_OWNER_KEY,
            storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER));
        assertEquals(RollbackMirrorRules.receiptPreferenceKey(
                CONNECTION_A, LOGICAL_KEY),
            storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT));
        assertNotEquals(storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF),
            storage.preferenceKeyForTest(CONNECTION_B,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF));
        assertEquals(storage.preferenceKeyForTest(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.GLOBAL_PREF),
            storage.preferenceKeyForTest(CONNECTION_B,
                ManualQueueDeleteTransaction.Slot.GLOBAL_PREF));
    }

    @Test
    public void readsBaseBakAndTmpWithoutNormalizingAnyPhysicalCopy()
            throws Exception {
        Fixture fixture = new Fixture();
        ManualQueueDeleteStorage storage = fixture.storage;
        File base = storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE);
        File bak = storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK);
        File tmp = storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP);
        fixture.exact.put(base, "base");
        fixture.exact.put(bak, "bak");
        fixture.exact.put(tmp, "tmp");
        Map<String, String> before = fixture.exact.snapshot();

        assertEquals("base", storage.readUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE));
        assertEquals("bak", storage.readUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK));
        assertEquals("tmp", storage.readUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP));
        assertEquals(before, fixture.exact.snapshot());
    }

    @Test
    public void preferencePutCommitFalseStillReadsBackThenThrows()
            throws Exception {
        Fixture fixture = new Fixture();
        String key = fixture.storage.preferenceKeyForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.falseAfterPutKey = key;

        assertThrows(IOException.class,
            () -> fixture.storage.writeUtf8(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF, BACKUP));

        assertEquals(BACKUP, fixture.preferences.values.get(key));
        assertTrue(fixture.preferences.containsCalls > 0);
        assertTrue(fixture.preferences.readCalls > 0);
    }

    @Test
    public void preferenceRemoveCommitFalseStillReadsBackThenThrows()
            throws Exception {
        Fixture fixture = new Fixture();
        String key = fixture.storage.preferenceKeyForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.values.put(key, BACKUP);
        fixture.preferences.falseAfterRemoveKey = key;

        assertThrows(IOException.class,
            () -> fixture.storage.delete(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF));

        assertFalse(fixture.preferences.values.containsKey(key));
        assertTrue(fixture.preferences.containsCalls > 0);
    }

    @Test
    public void successfulCommitWithWrongReadbackAlsoThrows() {
        Fixture fixture = new Fixture();
        String key = fixture.storage.preferenceKeyForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.noopPutKey = key;

        assertThrows(IOException.class,
            () -> fixture.storage.writeUtf8(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF, BACKUP));
    }

    @Test
    public void fileBackendWriteAndDeleteAreReadBackVerified()
            throws Exception {
        Fixture writeFixture = new Fixture();
        File base = writeFixture.storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE);
        writeFixture.exact.noopWriteFile = base;
        assertThrows(IOException.class,
            () -> writeFixture.storage.writeUtf8(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE, BACKUP));

        Fixture deleteFixture = new Fixture();
        File deleteBase = deleteFixture.storage.fileForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE);
        deleteFixture.exact.put(deleteBase, BACKUP);
        deleteFixture.exact.noopDeleteFile = deleteBase;
        assertThrows(IOException.class,
            () -> deleteFixture.storage.delete(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE));
    }

    @Test
    public void receiptAndTombstoneUseOnlyAtomicBackend() throws Exception {
        Fixture fixture = new Fixture();
        fixture.storage.writeUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT, "receipt");
        fixture.storage.writeUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE, "tombstone");

        assertEquals(0, fixture.exact.values.size());
        assertEquals(2, fixture.atomic.values.size());
        assertEquals("receipt", fixture.storage.readUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
        assertEquals("tombstone", fixture.storage.readUtf8(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE));
    }

    @Test
    public void transactionRecoversExactStateWhenPreferenceCommitReportsFailure()
            throws Exception {
        Fixture fixture = new Fixture();
        seedCompleteBackup(fixture, CONNECTION_A, BACKUP);
        Map<String, String> exactBefore = fixture.exact.snapshot();
        Map<String, Object> preferencesBefore = fixture.preferences.snapshot();
        Map<String, String> atomicBefore = fixture.atomic.snapshot();
        String scopedPreference = fixture.storage.preferenceKeyForTest(
            CONNECTION_A, ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.falseAfterRemoveKey = scopedPreference;

        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                fixture.storage, CONNECTION_A, LOGICAL_KEY, BACKUP, false));

        assertEquals(exactBefore, fixture.exact.snapshot());
        assertEquals(preferencesBefore, fixture.preferences.snapshot());
        assertEquals(atomicBefore, fixture.atomic.snapshot());
        assertEquals(ManualQueueDeleteTransaction.Recovery.NONE,
            ManualQueueDeleteTransaction.recover(
                fixture.storage, CONNECTION_A, LOGICAL_KEY));
    }

    @Test
    public void recoveryCommitFailureKeepsReceiptUntilNextRestartRestores()
            throws Exception {
        Fixture fixture = new Fixture();
        seedCompleteBackup(fixture, CONNECTION_A, BACKUP);
        Map<String, String> exactBefore = fixture.exact.snapshot();
        Map<String, Object> preferencesBefore = fixture.preferences.snapshot();
        String scopedPreference = fixture.storage.preferenceKeyForTest(
            CONNECTION_A, ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.falseAfterRemoveKey = scopedPreference;
        fixture.preferences.falseAfterPutKey = scopedPreference;

        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                fixture.storage, CONNECTION_A, LOGICAL_KEY, BACKUP, false));

        assertTrue(fixture.storage.exists(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            fixture.storage, CONNECTION_A, LOGICAL_KEY, false));
        assertEquals(ManualQueueDeleteTransaction.Recovery.RESTORED,
            ManualQueueDeleteTransaction.recover(
                fixture.storage, CONNECTION_A, LOGICAL_KEY));
        assertEquals(exactBefore, fixture.exact.snapshot());
        assertEquals(preferencesBefore, fixture.preferences.snapshot());
        assertFalse(fixture.storage.exists(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT));
    }

    @Test
    public void nonStringPreferenceIsPresentEvidenceAndCannotBeReadAsQueue()
            throws Exception {
        Fixture fixture = new Fixture();
        String key = fixture.storage.preferenceKeyForTest(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF);
        fixture.preferences.values.put(key, 42);

        assertTrue(fixture.storage.exists(CONNECTION_A,
            ManualQueueDeleteTransaction.Slot.SCOPED_PREF));
        assertThrows(IOException.class,
            () -> fixture.storage.readUtf8(CONNECTION_A,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF));
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            fixture.storage, CONNECTION_A, LOGICAL_KEY, false));
    }

    @Test
    public void exactWriteCrashRemainderCannotBypassDeleteOrPromotionGate()
            throws Exception {
        Fixture fixture = new Fixture();
        File remainder = fixture.storage.writeRemainderForTest(
            CONNECTION_A, ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK);
        fixture.exact.put(remainder, BACKUP);

        assertTrue(fixture.storage.auxiliaryRecoveryEvidencePresent(CONNECTION_A));
        assertTrue(ManualQueueDeleteTransaction.blocksPanelPromotion(
            fixture.storage, CONNECTION_A, LOGICAL_KEY, false));
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.delete(
                fixture.storage, CONNECTION_A, LOGICAL_KEY, BACKUP, false));
        assertThrows(IOException.class,
            () -> ManualQueueDeleteTransaction.clearCommittedTombstoneForNewSave(
                fixture.storage, CONNECTION_A, LOGICAL_KEY));
        assertEquals(BACKUP, fixture.exact.values.get(
            FakeFiles.key(remainder)));
    }

    private static void seedCompleteBackup(Fixture fixture, String connection,
                                           String backup) throws Exception {
        ManualQueueDeleteTransaction.Slot[] exactSlots = {
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BASE,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BAK,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP
        };
        for (ManualQueueDeleteTransaction.Slot slot : exactSlots) {
            fixture.exact.put(
                fixture.storage.fileForTest(connection, slot), backup);
        }
        fixture.preferences.values.put(
            fixture.storage.preferenceKeyForTest(connection,
                ManualQueueDeleteTransaction.Slot.SCOPED_PREF), backup);
        fixture.preferences.values.put(
            fixture.storage.preferenceKeyForTest(connection,
                ManualQueueDeleteTransaction.Slot.GLOBAL_PREF), backup);
        fixture.preferences.values.put(
            fixture.storage.preferenceKeyForTest(connection,
                ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER), connection);
        fixture.preferences.values.put(
            fixture.storage.preferenceKeyForTest(connection,
                ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT),
            RollbackMirrorRules.newReceipt(
                connection, LOGICAL_KEY, backup).toString());
    }

    private static final class Fixture {
        final File root = new File("/virtual/manual-queue-files");
        final FakePreferences preferences = new FakePreferences();
        final FakeFiles exact = new FakeFiles();
        final FakeFiles atomic = new FakeFiles();
        final ManualQueueDeleteStorage storage =
            new ManualQueueDeleteStorage(root, preferences, exact, atomic,
                LOGICAL_KEY, GLOBAL_OWNER_KEY);
    }

    private static final class FakePreferences
            implements ManualQueueDeleteStorage.PreferenceBackend {
        final Map<String, Object> values = new LinkedHashMap<>();
        String falseAfterPutKey;
        String falseAfterRemoveKey;
        String noopPutKey;
        int containsCalls;
        int readCalls;

        Map<String, Object> snapshot() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public boolean contains(String key) {
            containsCalls++;
            return values.containsKey(key);
        }

        @Override
        public Object read(String key) {
            readCalls++;
            return values.get(key);
        }

        @Override
        public boolean putString(String key, String value) {
            if (!key.equals(noopPutKey)) values.put(key, value);
            if (key.equals(falseAfterPutKey)) {
                falseAfterPutKey = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean remove(String key) {
            values.remove(key);
            if (key.equals(falseAfterRemoveKey)) {
                falseAfterRemoveKey = null;
                return false;
            }
            return true;
        }
    }

    private static final class FakeFiles
            implements ManualQueueDeleteStorage.FileBackend {
        final Map<String, String> values = new LinkedHashMap<>();
        File noopWriteFile;
        File noopDeleteFile;

        void put(File file, String value) {
            values.put(key(file), value);
        }

        Map<String, String> snapshot() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public boolean exists(File file) {
            return values.containsKey(key(file));
        }

        @Override
        public String readUtf8(File file) throws IOException {
            if (!exists(file)) throw new IOException("missing fake file");
            return values.get(key(file));
        }

        @Override
        public void writeUtf8(File file, String value) {
            if (!same(file, noopWriteFile)) values.put(key(file), value);
        }

        @Override
        public void delete(File file) {
            if (!same(file, noopDeleteFile)) values.remove(key(file));
        }

        private static boolean same(File first, File second) {
            return first != null && second != null && key(first).equals(key(second));
        }

        private static String key(File file) {
            try {
                return file.getCanonicalPath();
            } catch (IOException failure) {
                throw new IllegalArgumentException(failure);
            }
        }
    }
}
