package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class PanelPairCacheTransactionTest {
    private final File activeFirst = new File("/virtual/active-config.json");
    private final File activeSecond = new File("/virtual/active-catalog.json");
    private final File candidateFirst = new File("/virtual/candidate-config.json");
    private final File candidateSecond = new File("/virtual/candidate-catalog.json");
    private final File receipt = new File("/virtual/pair-receipt.json");
    private final PanelPairCacheTransaction.Files files = new PanelPairCacheTransaction.Files(
        activeFirst, activeSecond, candidateFirst, candidateSecond, receipt);
    private final PanelPairCacheTransaction.Validator validator =
        (first, second) -> first.startsWith("config-") && second.startsWith("catalog-");

    @Test
    public void promotesValidatedPairAndRemovesCandidatesAndReceipt() throws Exception {
        FakeStorage store = oldAndNew();

        PanelPairCacheTransaction.promote(store, validator, files);

        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));
        assertFalse(store.has(candidateFirst));
        assertFalse(store.has(candidateSecond));
        assertFalse(store.has(receipt));
        assertEquals(PanelPairCacheTransaction.Recovery.NONE,
            PanelPairCacheTransaction.recover(store, validator, files));
    }

    @Test
    public void promotionAlsoWorksWhenTheOldPairDoesNotExist() throws Exception {
        FakeStorage store = new FakeStorage();
        store.put(candidateFirst, "config-new");
        store.put(candidateSecond, "catalog-new");

        PanelPairCacheTransaction.promote(store, validator, files);

        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));
        assertEquals(2, store.values.size());
    }

    @Test
    public void preparedHalfCommitRestoresTheExactOldPair() throws Exception {
        FakeStorage store = oldAndNew();
        store.crashWriteFile = activeSecond;
        store.crashWriteValue = "catalog-new";

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertEquals("config-new", store.get(activeFirst));
        assertTrue(store.has(receipt));
        JSONObject prepared = new JSONObject(store.get(receipt));
        assertEquals("PREPARED", prepared.getString("state"));
        assertFalse(store.get(receipt).contains("config-old"));
        assertFalse(store.get(receipt).contains("catalog-new"));

        store.crashWriteFile = null;
        assertEquals(PanelPairCacheTransaction.Recovery.RESTORED_OLD,
            PanelPairCacheTransaction.recover(store, validator, files));
        assertEquals("config-old", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void crashAfterFirstActiveWriteRestoresExactOldPairOnRestart()
            throws Exception {
        FakeStorage store = oldAndNew();
        store.crashAfterWriteFile = activeFirst;
        store.crashAfterWriteValue = "config-new";

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
        assertEquals("PREPARED", new JSONObject(store.get(receipt)).getString("state"));

        store.crashAfterWriteFile = null;
        assertEquals(PanelPairCacheTransaction.Recovery.RESTORED_OLD,
            PanelPairCacheTransaction.recover(store, validator, files));
        assertEquals("config-old", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void committedReceiptAcceptsOnlyTheCompleteValidatedNewPair() throws Exception {
        FakeStorage store = oldAndNew();
        store.crashDeleteFile = receipt;

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertEquals("COMMITTED", new JSONObject(store.get(receipt)).getString("state"));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));

        store.crashDeleteFile = null;
        assertEquals(PanelPairCacheTransaction.Recovery.ACCEPTED_NEW,
            PanelPairCacheTransaction.recover(store, validator, files));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void committedCleanupKeepsReceiptUntilOldCandidatesAreGone() throws Exception {
        FakeStorage store = oldAndNew();
        store.crashDeleteFile = candidateSecond;

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertTrue(store.has(receipt));
        assertEquals("COMMITTED", new JSONObject(store.get(receipt)).getString("state"));
        assertFalse(store.has(candidateFirst));
        assertTrue(store.has(candidateSecond));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));

        store.crashDeleteFile = null;
        PanelPairCacheTransaction.Validator mutableOutsideStateNowRejects =
            (first, second) -> false;
        assertEquals(PanelPairCacheTransaction.Recovery.ACCEPTED_NEW,
            PanelPairCacheTransaction.recover(
                store, mutableOutsideStateNowRejects, files));
        assertFalse(store.has(candidateFirst));
        assertFalse(store.has(candidateSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void crashAfterFirstCandidateDeletionStillAcceptsExactCommittedNew()
            throws Exception {
        FakeStorage store = oldAndNew();
        store.crashAfterDeleteFile = candidateFirst;

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertFalse(store.has(candidateFirst));
        assertTrue(store.has(candidateSecond));
        assertEquals("COMMITTED", new JSONObject(store.get(receipt)).getString("state"));

        store.crashAfterDeleteFile = null;
        assertEquals(PanelPairCacheTransaction.Recovery.ACCEPTED_NEW,
            PanelPairCacheTransaction.recover(store, (first, second) -> false, files));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void crashAfterFinalReceiptDeletionLeavesOnlyExactCommittedNew()
            throws Exception {
        FakeStorage store = oldAndNew();
        store.crashAfterDeleteFile = receipt;

        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertFalse(store.has(receipt));
        assertFalse(store.has(candidateFirst));
        assertFalse(store.has(candidateSecond));
        assertEquals("config-new", store.get(activeFirst));
        assertEquals("catalog-new", store.get(activeSecond));

        store.crashAfterDeleteFile = null;
        assertEquals(PanelPairCacheTransaction.Recovery.NONE,
            PanelPairCacheTransaction.recover(store, validator, files));
    }

    @Test
    public void committedReceiptFallsBackToOldWhenNewPairIsIncomplete() throws Exception {
        FakeStorage store = oldAndNew();
        store.crashAfterWriteFile = receipt;
        store.crashAfterWriteContains = "\"state\":\"COMMITTED\"";
        assertThrows(SimulatedCrash.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        store.crashAfterWriteFile = null;

        store.put(activeSecond, "damaged-new-catalog");
        assertEquals(PanelPairCacheTransaction.Recovery.RESTORED_OLD,
            PanelPairCacheTransaction.recover(store, validator, files));
        assertEquals("config-old", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
    }

    @Test
    public void malformedReceiptFailsClosedWithoutChangingAnyFile() {
        FakeStorage store = oldAndNew();
        store.put(receipt, "{\"schema\":1,\"state\":\"UNKNOWN\",\"secret\":\"value\"}");
        Map<String, String> before = new LinkedHashMap<>(store.values);

        assertThrows(IOException.class,
            () -> PanelPairCacheTransaction.recover(store, validator, files));
        assertEquals(before, store.values);
    }

    @Test
    public void pathAliasesCannotNameTheSameTransactionFile() {
        File alias = new File("/virtual/cache/../active-config.json");

        assertThrows(IllegalArgumentException.class,
            () -> new PanelPairCacheTransaction.Files(
                activeFirst, activeSecond, alias, candidateSecond, receipt));
    }

    @Test
    public void injectedWriteFailureRunsRecoveryAndLeavesTheOldPair() {
        FakeStorage store = oldAndNew();
        store.failWriteOnceFile = activeFirst;
        store.failWriteOnceValue = "config-new";

        assertThrows(IOException.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertEquals("config-old", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
        assertFalse(store.has(receipt));
    }

    @Test
    public void runtimeFailureAfterFirstActiveMutationRunsRecovery() {
        FakeStorage store = oldAndNew();
        store.runtimeAfterWriteFile = activeFirst;
        store.runtimeAfterWriteValue = "config-new";

        assertThrows(IllegalStateException.class,
            () -> PanelPairCacheTransaction.promote(store, validator, files));
        assertEquals("config-old", store.get(activeFirst));
        assertEquals("catalog-old", store.get(activeSecond));
        assertFalse(store.has(candidateFirst));
        assertFalse(store.has(candidateSecond));
        assertFalse(store.has(receipt));
    }

    private FakeStorage oldAndNew() {
        FakeStorage store = new FakeStorage();
        store.put(activeFirst, "config-old");
        store.put(activeSecond, "catalog-old");
        store.put(candidateFirst, "config-new");
        store.put(candidateSecond, "catalog-new");
        return store;
    }

    private static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static final class FakeStorage implements PanelPairCacheTransaction.Storage {
        final Map<String, String> values = new LinkedHashMap<>();
        File crashWriteFile;
        String crashWriteValue;
        File crashAfterWriteFile;
        String crashAfterWriteValue;
        String crashAfterWriteContains;
        File crashDeleteFile;
        File crashAfterDeleteFile;
        File failWriteOnceFile;
        String failWriteOnceValue;
        File runtimeAfterWriteFile;
        String runtimeAfterWriteValue;

        void put(File file, String value) {
            values.put(key(file), value);
        }

        boolean has(File file) {
            return values.containsKey(key(file));
        }

        String get(File file) {
            return values.get(key(file));
        }

        @Override
        public boolean exists(File file) {
            return has(file);
        }

        @Override
        public String readUtf8(File file) throws IOException {
            if (!has(file)) throw new IOException("missing: " + file.getName());
            return get(file);
        }

        @Override
        public void writeUtf8(File file, String value) throws IOException {
            if (same(file, crashWriteFile) && value.equals(crashWriteValue)) {
                throw new SimulatedCrash();
            }
            if (same(file, failWriteOnceFile) && value.equals(failWriteOnceValue)) {
                failWriteOnceFile = null;
                throw new IOException("injected write failure");
            }
            put(file, value);
            if (same(file, crashAfterWriteFile)
                    && ((crashAfterWriteValue != null
                            && value.equals(crashAfterWriteValue))
                        || (crashAfterWriteContains != null
                            && value.contains(crashAfterWriteContains)))) {
                throw new SimulatedCrash();
            }
            if (same(file, runtimeAfterWriteFile)
                    && value.equals(runtimeAfterWriteValue)) {
                runtimeAfterWriteFile = null;
                throw new IllegalStateException("injected runtime failure after write");
            }
        }

        @Override
        public void delete(File file) {
            if (same(file, crashDeleteFile)) throw new SimulatedCrash();
            values.remove(key(file));
            if (same(file, crashAfterDeleteFile)) throw new SimulatedCrash();
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
