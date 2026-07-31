package com.autoformkit.app;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure crash-recovery protocol for explicitly deleting one Panel-bound manual queue backup.
 *
 * <p>The queue has more durable representations than the two logical files exposed by
 * {@code AtomicFile}: its base, {@code .bak} and legacy {@code .tmp} artifacts can each survive a
 * process death, and signed rollback builds also use scoped/global preference mirrors. Every one
 * of those representations is therefore an independent transaction slot. A PREPARED receipt keeps
 * the exact original bytes and presence map until every owned slot is absent. A separate COMMITTED
 * tombstone is the sole durable point after which restart recovery finishes deletion instead of
 * restoring the exact pre-delete state.</p>
 *
 * <p>The storage adapter maps scoped slots to the supplied connection namespace and maps global
 * slots to the legacy shared locations. This class never guesses global ownership: it mutates
 * global queue slots only while the global-owner preference names this exact connection. If another
 * connection takes ownership before recovery, its global bytes are preserved byte-for-byte while
 * the old connection's scoped transaction is resolved.</p>
 */
final class ManualQueueDeleteTransaction {
    private static final int SCHEMA = 1;
    private static final String PREPARED = "PREPARED";
    private static final String COMMITTED = "COMMITTED";
    private static final Object PROCESS_LOCK = new Object();

    /**
     * Physical/durable representations participating in the protocol. DELETE_RECEIPT and
     * DELETE_TOMBSTONE must themselves be connection-scoped in the storage adapter.
     */
    enum Slot {
        SCOPED_FILE_BASE,
        SCOPED_FILE_BAK,
        SCOPED_FILE_TMP,
        GLOBAL_FILE_BASE,
        GLOBAL_FILE_BAK,
        GLOBAL_FILE_TMP,
        SCOPED_PREF,
        GLOBAL_PREF,
        MIRROR_RECEIPT,
        GLOBAL_OWNER,
        DELETE_RECEIPT,
        DELETE_TOMBSTONE
    }

    /**
     * Durable string storage. Implementations must make each individual write/delete crash-safe
     * and must verify failures by throwing {@link IOException}; transaction-level atomicity is
     * provided here.
     */
    interface Storage {
        boolean exists(String connectionNamespace, Slot slot) throws IOException;
        String readUtf8(String connectionNamespace, Slot slot) throws IOException;
        void writeUtf8(String connectionNamespace, Slot slot, String value) throws IOException;
        void delete(String connectionNamespace, Slot slot) throws IOException;

        /**
         * Storage-internal crash residue that can still contain queue bytes (for example an
         * fsynced exact-file rename source). It is an upgrade/delete gate, while an existing
         * PREPARED receipt remains authoritative enough for recovery to consume that residue.
         */
        default boolean auxiliaryRecoveryEvidencePresent(String connectionNamespace)
                throws IOException {
            return false;
        }
    }

    enum DeleteResult {
        DELETED,
        ALREADY_DELETED
    }

    enum Recovery {
        NONE,
        RESTORED,
        COMMITTED
    }

    private static final Slot[] SCOPED_DATA = {
        Slot.SCOPED_FILE_BASE,
        Slot.SCOPED_FILE_BAK,
        Slot.SCOPED_FILE_TMP,
        Slot.SCOPED_PREF
    };
    private static final Slot[] GLOBAL_DATA = {
        Slot.GLOBAL_FILE_BASE,
        Slot.GLOBAL_FILE_BAK,
        Slot.GLOBAL_FILE_TMP,
        Slot.GLOBAL_PREF
    };
    private static final Slot[] SCOPED_TARGETS = {
        Slot.SCOPED_FILE_BASE,
        Slot.SCOPED_FILE_BAK,
        Slot.SCOPED_FILE_TMP,
        Slot.SCOPED_PREF,
        Slot.MIRROR_RECEIPT
    };

    private ManualQueueDeleteTransaction() {
    }

    /**
     * Starts and completes one explicit delete. A mirror conflict is transaction evidence, not an
     * instruction to erase harder, so callers must pass the current blocked-mirror state.
     */
    static DeleteResult delete(Storage storage, String connectionNamespace, String logicalKey,
                               String exactBackupValue, boolean blockedMirror)
            throws IOException {
        requireDependencies(storage, connectionNamespace, logicalKey);
        if (exactBackupValue == null || exactBackupValue.isEmpty()) {
            throw new IOException("Manual queue backup is absent");
        }
        synchronized (PROCESS_LOCK) {
            if (blockedMirror) {
                throw new IOException("Manual queue mirror is blocked");
            }
            if (storage.auxiliaryRecoveryEvidencePresent(connectionNamespace)) {
                throw new IOException("Manual queue storage recovery residue is unresolved");
            }
            if (storage.exists(connectionNamespace, Slot.DELETE_RECEIPT)) {
                throw new IOException("Unresolved manual queue delete receipt");
            }
            if (storage.exists(connectionNamespace, Slot.DELETE_TOMBSTONE)) {
                Tombstone tombstone = Tombstone.parseFor(storage.readUtf8(
                    connectionNamespace, Slot.DELETE_TOMBSTONE),
                    connectionNamespace, logicalKey);
                if (!tombstone.backupSha256.equals(sha256(exactBackupValue))) {
                    throw new IOException(
                        "Committed queue deletion belongs to a different backup");
                }
                if (!ownedEvidencePresent(
                        storage, connectionNamespace, tombstone.backupSha256)) {
                    return DeleteResult.ALREADY_DELETED;
                }
                throw new IOException("Committed queue deletion conflicts with backup evidence");
            }

            Receipt receipt = Receipt.capture(storage, connectionNamespace,
                logicalKey, exactBackupValue);
            try {
                writeReceipt(storage, receipt);
                deletePreparedTargets(storage, receipt);
                Tombstone tombstone = Tombstone.from(receipt);
                writeTombstone(storage, tombstone);
                finishCommitted(storage, receipt, tombstone);
                return DeleteResult.DELETED;
            } catch (IOException | RuntimeException failure) {
                try {
                    Recovery recovered = recoverLocked(
                        storage, connectionNamespace, logicalKey);
                    // The COMMITTED tombstone is the operation's durable success point. A storage
                    // call may report failure after persisting it (for example a conservative
                    // directory-fsync error); once recovery proves and finishes that commit, do
                    // not tell the user that the exact backup was restored.
                    if (recovered == Recovery.COMMITTED) return DeleteResult.DELETED;
                } catch (IOException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
        }
    }

    /**
     * Resolves a process-death window. PREPARED without a valid matching tombstone restores the
     * exact captured state. A valid matching tombstone completes deletion.
     */
    static Recovery recover(Storage storage, String connectionNamespace, String logicalKey)
            throws IOException {
        requireDependencies(storage, connectionNamespace, logicalKey);
        synchronized (PROCESS_LOCK) {
            return recoverLocked(storage, connectionNamespace, logicalKey);
        }
    }

    private static Recovery recoverLocked(Storage storage, String connectionNamespace,
                                          String logicalKey) throws IOException {
        boolean receiptPresent =
            storage.exists(connectionNamespace, Slot.DELETE_RECEIPT);
        boolean tombstonePresent =
            storage.exists(connectionNamespace, Slot.DELETE_TOMBSTONE);
        if (!receiptPresent) {
            if (!tombstonePresent) return Recovery.NONE;
            Tombstone tombstone = Tombstone.parseFor(storage.readUtf8(
                connectionNamespace, Slot.DELETE_TOMBSTONE),
                connectionNamespace, logicalKey);
            if (ownedEvidencePresent(
                    storage, connectionNamespace, tombstone.backupSha256)) {
                throw new IOException(
                    "Committed queue deletion has unresolved recoverable evidence");
            }
            return Recovery.COMMITTED;
        }

        Receipt receipt = Receipt.parseFor(storage.readUtf8(
            connectionNamespace, Slot.DELETE_RECEIPT),
            connectionNamespace, logicalKey);
        if (tombstonePresent) {
            String rawTombstone = storage.readUtf8(
                connectionNamespace, Slot.DELETE_TOMBSTONE);
            Tombstone tombstone;
            try {
                tombstone = Tombstone.parseFor(
                    rawTombstone, connectionNamespace, logicalKey);
            } catch (IOException invalidTombstone) {
                restorePrepared(storage, receipt);
                deleteVerified(storage, connectionNamespace, Slot.DELETE_TOMBSTONE);
                deleteVerified(storage, connectionNamespace, Slot.DELETE_RECEIPT);
                return Recovery.RESTORED;
            }
            if (!tombstone.matches(receipt)) {
                throw new IOException("Queue deletion receipt and tombstone do not match");
            }
            finishCommitted(storage, receipt, tombstone);
            return Recovery.COMMITTED;
        }

        restorePrepared(storage, receipt);
        deleteVerified(storage, connectionNamespace, Slot.DELETE_RECEIPT);
        return Recovery.RESTORED;
    }

    /**
     * Upgrade/Panel-pair promotion gate. Valid committed tombstones are not backup evidence, but
     * every physical queue artifact, an unresolved receipt, a malformed tombstone, ambiguous
     * global ownership, or the in-memory blocked-mirror flag is.
     */
    static boolean blocksPanelPromotion(Storage storage, String connectionNamespace,
                                        String logicalKey, boolean blockedMirror)
            throws IOException {
        requireDependencies(storage, connectionNamespace, logicalKey);
        synchronized (PROCESS_LOCK) {
            if (blockedMirror
                    || storage.exists(connectionNamespace, Slot.DELETE_RECEIPT)) {
                return true;
            }
            String committedBackupSha = "";
            if (storage.exists(connectionNamespace, Slot.DELETE_TOMBSTONE)) {
                try {
                    Tombstone tombstone = Tombstone.parseFor(storage.readUtf8(
                        connectionNamespace, Slot.DELETE_TOMBSTONE),
                        connectionNamespace, logicalKey);
                    committedBackupSha = tombstone.backupSha256;
                } catch (IOException invalid) {
                    return true;
                }
            }
            return ownedEvidencePresent(
                storage, connectionNamespace, committedBackupSha);
        }
    }

    /**
     * Positive proof for a caller handling an earlier I/O exception. Absence of every backup slot
     * is not by itself deletion success; only a valid tombstone with no receipt or remaining owned
     * evidence is.
     */
    static boolean committedDeletionComplete(Storage storage, String connectionNamespace,
                                             String logicalKey) throws IOException {
        requireDependencies(storage, connectionNamespace, logicalKey);
        synchronized (PROCESS_LOCK) {
            if (storage.exists(connectionNamespace, Slot.DELETE_RECEIPT)
                    || !storage.exists(connectionNamespace, Slot.DELETE_TOMBSTONE)) {
                return false;
            }
            Tombstone tombstone = Tombstone.parseFor(storage.readUtf8(
                connectionNamespace, Slot.DELETE_TOMBSTONE),
                connectionNamespace, logicalKey);
            return !ownedEvidencePresent(
                storage, connectionNamespace, tombstone.backupSha256);
        }
    }

    /**
     * An explicit new save may retire an old successful-delete tombstone, but only before any new
     * queue mirror is written. This prevents a stale tombstone from hiding a later backup.
     */
    static void clearCommittedTombstoneForNewSave(Storage storage,
                                                   String connectionNamespace,
                                                   String logicalKey)
            throws IOException {
        requireDependencies(storage, connectionNamespace, logicalKey);
        synchronized (PROCESS_LOCK) {
            if (storage.exists(connectionNamespace, Slot.DELETE_RECEIPT)) {
                throw new IOException("Unresolved manual queue delete receipt");
            }
            if (storage.auxiliaryRecoveryEvidencePresent(connectionNamespace)) {
                throw new IOException("Manual queue storage recovery residue is unresolved");
            }
            if (!storage.exists(connectionNamespace, Slot.DELETE_TOMBSTONE)) return;
            Tombstone tombstone = Tombstone.parseFor(storage.readUtf8(
                connectionNamespace, Slot.DELETE_TOMBSTONE),
                connectionNamespace, logicalKey);
            if (ownedEvidencePresent(
                    storage, connectionNamespace, tombstone.backupSha256)) {
                throw new IOException("Cannot clear deletion tombstone after backup write began");
            }
            deleteVerified(storage, connectionNamespace, Slot.DELETE_TOMBSTONE);
        }
    }

    private static void deletePreparedTargets(Storage storage, Receipt receipt)
            throws IOException {
        MutationScope scope = mutationScope(storage, receipt, true);
        for (Slot slot : SCOPED_TARGETS) {
            deleteVerified(storage, receipt.connectionNamespace, slot);
        }
        if (scope.mutateGlobals) {
            for (Slot slot : GLOBAL_DATA) {
                deleteVerified(storage, receipt.connectionNamespace, slot);
            }
        }
    }

    private static void finishCommitted(Storage storage, Receipt receipt,
                                        Tombstone tombstone) throws IOException {
        if (!tombstone.matches(receipt)) {
            throw new IOException("Queue deletion commit marker does not match receipt");
        }
        MutationScope scope = mutationScope(storage, receipt, true);
        for (Slot slot : SCOPED_TARGETS) {
            deleteVerified(storage, receipt.connectionNamespace, slot);
        }
        if (scope.mutateGlobals) {
            for (Slot slot : GLOBAL_DATA) {
                deleteVerified(storage, receipt.connectionNamespace, slot);
            }
        }
        // Keep and re-verify the durable COMMITTED marker. It prevents an old .bak/.tmp or stale
        // preference from being mistaken for a live backup if external code recreates one later.
        if (!storage.exists(receipt.connectionNamespace, Slot.DELETE_TOMBSTONE)) {
            throw new IOException("Queue deletion tombstone disappeared during cleanup");
        }
        Tombstone durable = Tombstone.parseFor(storage.readUtf8(
            receipt.connectionNamespace, Slot.DELETE_TOMBSTONE),
            receipt.connectionNamespace, receipt.logicalKey);
        if (!tombstone.sameAs(durable)) {
            throw new IOException("Queue deletion tombstone changed during cleanup");
        }
        deleteVerified(storage, receipt.connectionNamespace, Slot.DELETE_RECEIPT);
    }

    private static void restorePrepared(Storage storage, Receipt receipt)
            throws IOException {
        MutationScope scope = mutationScope(storage, receipt, false);
        for (Slot slot : SCOPED_TARGETS) {
            restoreSlot(storage, receipt, slot);
        }
        if (scope.mutateGlobals) {
            for (Slot slot : GLOBAL_DATA) restoreSlot(storage, receipt, slot);
        }
        for (Slot slot : SCOPED_TARGETS) verifyExact(storage, receipt, slot);
        if (scope.mutateGlobals) {
            for (Slot slot : GLOBAL_DATA) verifyExact(storage, receipt, slot);
        }
    }

    /**
     * Performs a no-mutation compatibility pass before any recovery/delete changes a slot.
     * Scoped data must be either the captured value or absent due to this transaction. Global data
     * is touched only while the exact connection still owns the legacy namespace.
     */
    private static MutationScope mutationScope(Storage storage, Receipt receipt,
                                               boolean deleting) throws IOException {
        for (Slot slot : SCOPED_TARGETS) {
            requireCompatibleCurrent(storage, receipt, slot, deleting);
        }
        if (!receipt.includesGlobal) return new MutationScope(false);

        Owner owner = readOwner(storage, receipt.connectionNamespace);
        if (owner.kind == OwnerKind.CURRENT) {
            for (Slot slot : GLOBAL_DATA) {
                requireCompatibleCurrent(storage, receipt, slot, deleting);
            }
            return new MutationScope(true);
        }
        if (owner.kind == OwnerKind.MALFORMED) {
            throw new IOException("Global rollback owner is malformed");
        }

        // Another Panel may have replaced the global rollback mirror after this connection's
        // receipt was prepared. Never delete or restore those bytes. A slot that still equals the
        // old baseline is ambiguous, however, and must remain blocked rather than being reported
        // as a completed deletion.
        if (deleting) {
            for (Slot slot : GLOBAL_DATA) {
                SavedSlot expected = receipt.slots.get(slot);
                if (expected == null || !expected.present
                        || !storage.exists(receipt.connectionNamespace, slot)) continue;
                String current = storage.readUtf8(receipt.connectionNamespace, slot);
                if (expected.value(receipt.backupValue).equals(current)) {
                    throw new IOException(
                        "Old connection backup remains in a foreign global mirror");
                }
            }
        }
        return new MutationScope(false);
    }

    private static void requireCompatibleCurrent(Storage storage, Receipt receipt, Slot slot,
                                                 boolean deleting) throws IOException {
        SavedSlot expected = receipt.slots.get(slot);
        if (expected == null) {
            throw new IOException("Queue delete receipt omits a required slot");
        }
        if (!storage.exists(receipt.connectionNamespace, slot)) return;
        String current = storage.readUtf8(receipt.connectionNamespace, slot);
        if (!expected.present) {
            throw new IOException("Queue slot appeared after transaction preparation");
        }
        if (!expected.value(receipt.backupValue).equals(current)) {
            throw new IOException("Queue slot changed after transaction preparation");
        }
    }

    private static void restoreSlot(Storage storage, Receipt receipt, Slot slot)
            throws IOException {
        SavedSlot expected = receipt.slots.get(slot);
        if (expected.present) {
            storage.writeUtf8(receipt.connectionNamespace, slot,
                expected.value(receipt.backupValue));
        } else {
            storage.delete(receipt.connectionNamespace, slot);
        }
    }

    private static void verifyExact(Storage storage, Receipt receipt, Slot slot)
            throws IOException {
        SavedSlot expected = receipt.slots.get(slot);
        boolean present = storage.exists(receipt.connectionNamespace, slot);
        if (present != expected.present) {
            throw new IOException("Restored queue slot presence mismatch");
        }
        if (present && !expected.value(receipt.backupValue).equals(
                storage.readUtf8(receipt.connectionNamespace, slot))) {
            throw new IOException("Restored queue slot value mismatch");
        }
    }

    private static boolean ownedEvidencePresent(Storage storage, String connectionNamespace,
                                                String committedBackupSha256)
            throws IOException {
        if (storage.auxiliaryRecoveryEvidencePresent(connectionNamespace)) return true;
        for (Slot slot : SCOPED_DATA) {
            if (storage.exists(connectionNamespace, slot)) return true;
        }
        if (storage.exists(connectionNamespace, Slot.MIRROR_RECEIPT)) return true;

        boolean anyGlobal = false;
        for (Slot slot : GLOBAL_DATA) {
            anyGlobal |= storage.exists(connectionNamespace, slot);
        }
        if (!anyGlobal) return false;
        Owner owner = readOwner(storage, connectionNamespace);
        // A foreign, syntactically valid owner isolates this connection from its global mirrors.
        // Missing/malformed ownership cannot prove that the surviving bytes belong elsewhere. A
        // committed tombstone additionally detects exact old bytes even after B takes ownership.
        if (owner.kind != OwnerKind.FOREIGN) return true;
        if (committedBackupSha256 == null || committedBackupSha256.isEmpty()) return false;
        for (Slot slot : GLOBAL_DATA) {
            if (storage.exists(connectionNamespace, slot)
                    && committedBackupSha256.equals(sha256(
                        storage.readUtf8(connectionNamespace, slot)))) {
                return true;
            }
        }
        return false;
    }

    private static Owner readOwner(Storage storage, String connectionNamespace)
            throws IOException {
        if (!storage.exists(connectionNamespace, Slot.GLOBAL_OWNER)) {
            return new Owner(OwnerKind.ABSENT, "");
        }
        String value = storage.readUtf8(connectionNamespace, Slot.GLOBAL_OWNER);
        if (!value.matches("[0-9a-f]{20}")) {
            return new Owner(OwnerKind.MALFORMED, value);
        }
        return new Owner(value.equals(connectionNamespace)
            ? OwnerKind.CURRENT : OwnerKind.FOREIGN, value);
    }

    private static void writeReceipt(Storage storage, Receipt receipt) throws IOException {
        String raw = receipt.toJson().toString();
        storage.writeUtf8(receipt.connectionNamespace, Slot.DELETE_RECEIPT, raw);
        Receipt stored = Receipt.parseFor(storage.readUtf8(
            receipt.connectionNamespace, Slot.DELETE_RECEIPT),
            receipt.connectionNamespace, receipt.logicalKey);
        if (!receipt.sameAs(stored)) {
            throw new IOException("Manual queue delete receipt readback mismatch");
        }
    }

    private static void writeTombstone(Storage storage, Tombstone tombstone)
            throws IOException {
        String raw = tombstone.toJson().toString();
        storage.writeUtf8(tombstone.connectionNamespace, Slot.DELETE_TOMBSTONE, raw);
        Tombstone stored = Tombstone.parseFor(storage.readUtf8(
            tombstone.connectionNamespace, Slot.DELETE_TOMBSTONE),
            tombstone.connectionNamespace, tombstone.logicalKey);
        if (!tombstone.sameAs(stored)) {
            throw new IOException("Manual queue delete tombstone readback mismatch");
        }
    }

    private static void deleteVerified(Storage storage, String connectionNamespace, Slot slot)
            throws IOException {
        storage.delete(connectionNamespace, slot);
        if (storage.exists(connectionNamespace, slot)) {
            throw new IOException("Could not delete manual queue transaction slot");
        }
    }

    private static void requireDependencies(Storage storage, String connectionNamespace,
                                            String logicalKey) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        if (connectionNamespace == null
                || !connectionNamespace.matches("[0-9a-f]{20}")) {
            throw new IllegalArgumentException("connection namespace is invalid");
        }
        if (logicalKey == null || logicalKey.isEmpty()) {
            throw new IllegalArgumentException("logical key is required");
        }
    }

    private static final class MutationScope {
        final boolean mutateGlobals;

        MutationScope(boolean mutateGlobals) {
            this.mutateGlobals = mutateGlobals;
        }
    }

    private enum OwnerKind {
        ABSENT,
        CURRENT,
        FOREIGN,
        MALFORMED
    }

    private static final class Owner {
        final OwnerKind kind;
        final String value;

        Owner(OwnerKind kind, String value) {
            this.kind = kind;
            this.value = value == null ? "" : value;
        }
    }

    private enum ValueSource {
        BACKUP,
        LITERAL
    }

    private static final class SavedSlot {
        final boolean present;
        final ValueSource source;
        final String literal;

        private SavedSlot(boolean present, ValueSource source, String literal) {
            this.present = present;
            this.source = source;
            this.literal = literal;
        }

        static SavedSlot absent() {
            return new SavedSlot(false, null, null);
        }

        static SavedSlot backup() {
            return new SavedSlot(true, ValueSource.BACKUP, null);
        }

        static SavedSlot literal(String value) {
            return new SavedSlot(true, ValueSource.LITERAL, value == null ? "" : value);
        }

        String value(String backupValue) throws IOException {
            if (!present) throw new IOException("Absent queue slot has no value");
            if (source == ValueSource.BACKUP) return backupValue;
            if (source == ValueSource.LITERAL && literal != null) return literal;
            throw new IOException("Queue slot value source is invalid");
        }

        JSONObject toJson() throws IOException {
            try {
                JSONObject out = new JSONObject().put("present", present);
                if (!present) return out;
                out.put("source", source.name());
                if (source == ValueSource.LITERAL) {
                    out.put("value", literal);
                    out.put("sha256", sha256(literal));
                }
                return out;
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Cannot serialize queue slot descriptor", failure);
            }
        }

        static SavedSlot parse(JSONObject value) throws IOException {
            if (value == null || !(value.opt("present") instanceof Boolean)) {
                throw new IOException("Invalid queue slot descriptor");
            }
            boolean present = value.optBoolean("present");
            if (!present) {
                if (!hasExactKeys(value, setOf("present"))) {
                    throw new IOException("Unexpected absent queue slot fields");
                }
                return absent();
            }
            if (!(value.opt("source") instanceof String)) {
                throw new IOException("Queue slot source is absent");
            }
            ValueSource source;
            try {
                source = ValueSource.valueOf((String) value.opt("source"));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Queue slot source is invalid", invalid);
            }
            if (source == ValueSource.BACKUP) {
                if (!hasExactKeys(value, setOf("present", "source"))) {
                    throw new IOException("Unexpected backup slot fields");
                }
                return backup();
            }
            if (!hasExactKeys(value,
                    setOf("present", "source", "value", "sha256"))
                    || !(value.opt("value") instanceof String)
                    || !(value.opt("sha256") instanceof String)) {
                throw new IOException("Invalid literal queue slot fields");
            }
            String literal = (String) value.opt("value");
            if (!sha256(literal).equals(value.optString("sha256", ""))) {
                throw new IOException("Literal queue slot digest mismatch");
            }
            return literal(literal);
        }

        boolean sameAs(SavedSlot other) {
            return other != null && present == other.present
                && source == other.source
                && (literal == null ? other.literal == null : literal.equals(other.literal));
        }
    }

    private static final class Receipt {
        final String transactionId;
        final String connectionNamespace;
        final String logicalKey;
        final String logicalKeySha256;
        final String backupValue;
        final String backupSha256;
        final boolean includesGlobal;
        final EnumMap<Slot, SavedSlot> slots;

        private Receipt(String transactionId, String connectionNamespace, String logicalKey,
                        String logicalKeySha256, String backupValue, String backupSha256,
                        boolean includesGlobal, EnumMap<Slot, SavedSlot> slots) {
            this.transactionId = transactionId;
            this.connectionNamespace = connectionNamespace;
            this.logicalKey = logicalKey;
            this.logicalKeySha256 = logicalKeySha256;
            this.backupValue = backupValue;
            this.backupSha256 = backupSha256;
            this.includesGlobal = includesGlobal;
            this.slots = slots;
        }

        static Receipt capture(Storage storage, String connectionNamespace,
                               String logicalKey, String backupValue) throws IOException {
            EnumMap<Slot, SavedSlot> slots = new EnumMap<>(Slot.class);
            boolean ownedDataPresent = false;
            for (Slot slot : SCOPED_DATA) {
                SavedSlot saved = captureBackupSlot(
                    storage, connectionNamespace, slot, backupValue);
                slots.put(slot, saved);
                ownedDataPresent |= saved.present;
            }

            if (storage.exists(connectionNamespace, Slot.MIRROR_RECEIPT)) {
                String mirror = storage.readUtf8(
                    connectionNamespace, Slot.MIRROR_RECEIPT);
                try {
                    if (!RollbackMirrorRules.receiptMatches(
                            new JSONObject(mirror), connectionNamespace,
                            logicalKey, backupValue)) {
                        throw new IOException(
                            "Rollback mirror receipt does not match the queue backup");
                    }
                } catch (IOException failure) {
                    throw failure;
                } catch (Exception invalid) {
                    throw new IOException("Rollback mirror receipt is malformed", invalid);
                }
                slots.put(Slot.MIRROR_RECEIPT, SavedSlot.literal(mirror));
            } else {
                slots.put(Slot.MIRROR_RECEIPT, SavedSlot.absent());
            }

            Owner owner = readOwner(storage, connectionNamespace);
            if (owner.kind == OwnerKind.MALFORMED) {
                throw new IOException("Global rollback owner is malformed");
            }
            boolean anyGlobal = anyExists(storage, connectionNamespace, GLOBAL_DATA);
            boolean includesGlobal = owner.kind == OwnerKind.CURRENT;
            if (anyGlobal && owner.kind == OwnerKind.ABSENT) {
                throw new IOException("Global queue mirror has no durable owner");
            }
            if (includesGlobal) {
                for (Slot slot : GLOBAL_DATA) {
                    SavedSlot saved = captureBackupSlot(
                        storage, connectionNamespace, slot, backupValue);
                    slots.put(slot, saved);
                    ownedDataPresent |= saved.present;
                }
            } else if (owner.kind == OwnerKind.FOREIGN) {
                for (Slot slot : GLOBAL_DATA) {
                    if (storage.exists(connectionNamespace, slot)
                            && backupValue.equals(storage.readUtf8(
                                connectionNamespace, slot))) {
                        throw new IOException(
                            "Current queue bytes remain under a foreign global owner");
                    }
                }
            }
            if (!ownedDataPresent) {
                throw new IOException("Manual queue has no owned recoverable copy");
            }

            return new Receipt(
                UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.US),
                connectionNamespace, logicalKey, sha256(logicalKey),
                backupValue, sha256(backupValue), includesGlobal, slots);
        }

        private static SavedSlot captureBackupSlot(Storage storage,
                                                   String connectionNamespace,
                                                   Slot slot, String backupValue)
                throws IOException {
            if (!storage.exists(connectionNamespace, slot)) return SavedSlot.absent();
            String current = storage.readUtf8(connectionNamespace, slot);
            if (!backupValue.equals(current)) {
                throw new IOException("Manual queue copies are not byte-identical");
            }
            return SavedSlot.backup();
        }

        JSONObject toJson() throws IOException {
            try {
                JSONObject slotJson = new JSONObject();
                for (Slot slot : expectedSlots(includesGlobal)) {
                    slotJson.put(slot.name(), slots.get(slot).toJson());
                }
                return new JSONObject()
                    .put("schema", SCHEMA)
                    .put("state", PREPARED)
                    .put("transactionId", transactionId)
                    .put("connectionNamespace", connectionNamespace)
                    .put("logicalKeySha256", logicalKeySha256)
                    .put("backupValue", backupValue)
                    .put("backupSha256", backupSha256)
                    .put("includesGlobal", includesGlobal)
                    .put("slots", slotJson);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Cannot serialize manual queue delete receipt", failure);
            }
        }

        static Receipt parseFor(String raw, String connectionNamespace,
                                String logicalKey) throws IOException {
            try {
                JSONObject root = new JSONObject(raw);
                Set<String> keys = setOf("schema", "state", "transactionId",
                    "connectionNamespace", "logicalKeySha256", "backupValue",
                    "backupSha256", "includesGlobal", "slots");
                if (!hasExactKeys(root, keys)
                        || exactInteger(root.opt("schema")) != SCHEMA
                        || !PREPARED.equals(root.opt("state"))
                        || !(root.opt("transactionId") instanceof String)
                        || !(root.opt("connectionNamespace") instanceof String)
                        || !(root.opt("logicalKeySha256") instanceof String)
                        || !(root.opt("backupValue") instanceof String)
                        || !(root.opt("backupSha256") instanceof String)
                        || !(root.opt("includesGlobal") instanceof Boolean)
                        || root.optJSONObject("slots") == null) {
                    throw new IOException("Invalid manual queue delete receipt");
                }
                String transactionId = root.optString("transactionId", "");
                String receiptConnection =
                    root.optString("connectionNamespace", "");
                String keySha = root.optString("logicalKeySha256", "");
                String backupValue = root.optString("backupValue", "");
                String backupSha = root.optString("backupSha256", "");
                if (!transactionId.matches("[0-9a-f]{32}")
                        || !connectionNamespace.equals(receiptConnection)
                        || !sha256(logicalKey).equals(keySha)
                        || backupValue.isEmpty()
                        || !sha256(backupValue).equals(backupSha)) {
                    throw new IOException("Manual queue delete receipt binding is invalid");
                }
                boolean includesGlobal = root.optBoolean("includesGlobal");
                JSONObject slotJson = root.optJSONObject("slots");
                List<Slot> expected = expectedSlots(includesGlobal);
                Set<String> expectedNames = new HashSet<>();
                for (Slot slot : expected) expectedNames.add(slot.name());
                if (!hasExactKeys(slotJson, expectedNames)) {
                    throw new IOException("Manual queue delete receipt slots are incomplete");
                }
                EnumMap<Slot, SavedSlot> slots = new EnumMap<>(Slot.class);
                boolean dataPresent = false;
                for (Slot slot : expected) {
                    SavedSlot saved = SavedSlot.parse(slotJson.optJSONObject(slot.name()));
                    if (slot == Slot.MIRROR_RECEIPT) {
                        if (saved.present && saved.source != ValueSource.LITERAL) {
                            throw new IOException("Mirror receipt slot must be literal");
                        }
                    } else if (saved.present && saved.source != ValueSource.BACKUP) {
                        throw new IOException("Queue data slot must use the backup value");
                    } else {
                        dataPresent |= saved.present;
                    }
                    slots.put(slot, saved);
                }
                if (!dataPresent) {
                    throw new IOException("Manual queue delete receipt has no queue data");
                }
                if (slots.get(Slot.MIRROR_RECEIPT).present) {
                    String mirror = slots.get(Slot.MIRROR_RECEIPT).literal;
                    if (!RollbackMirrorRules.receiptMatches(
                            new JSONObject(mirror), connectionNamespace,
                            logicalKey, backupValue)) {
                        throw new IOException(
                            "Stored rollback mirror receipt is not queue-bound");
                    }
                }
                return new Receipt(transactionId, connectionNamespace,
                    logicalKey, keySha, backupValue, backupSha,
                    includesGlobal, slots);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Malformed manual queue delete receipt", failure);
            }
        }

        boolean sameAs(Receipt other) {
            if (other == null
                    || !transactionId.equals(other.transactionId)
                    || !connectionNamespace.equals(other.connectionNamespace)
                    || !logicalKeySha256.equals(other.logicalKeySha256)
                    || !backupValue.equals(other.backupValue)
                    || !backupSha256.equals(other.backupSha256)
                    || includesGlobal != other.includesGlobal
                    || slots.size() != other.slots.size()) return false;
            for (Slot slot : slots.keySet()) {
                if (!slots.get(slot).sameAs(other.slots.get(slot))) return false;
            }
            return true;
        }
    }

    private static final class Tombstone {
        final String transactionId;
        final String connectionNamespace;
        final String logicalKey;
        final String logicalKeySha256;
        final String backupSha256;

        private Tombstone(String transactionId, String connectionNamespace,
                          String logicalKey, String logicalKeySha256,
                          String backupSha256) {
            this.transactionId = transactionId;
            this.connectionNamespace = connectionNamespace;
            this.logicalKey = logicalKey;
            this.logicalKeySha256 = logicalKeySha256;
            this.backupSha256 = backupSha256;
        }

        static Tombstone from(Receipt receipt) {
            return new Tombstone(receipt.transactionId,
                receipt.connectionNamespace, receipt.logicalKey,
                receipt.logicalKeySha256, receipt.backupSha256);
        }

        JSONObject toJson() throws IOException {
            try {
                return new JSONObject()
                    .put("schema", SCHEMA)
                    .put("state", COMMITTED)
                    .put("transactionId", transactionId)
                    .put("connectionNamespace", connectionNamespace)
                    .put("logicalKeySha256", logicalKeySha256)
                    .put("backupSha256", backupSha256);
            } catch (Exception failure) {
                throw new IOException("Cannot serialize queue deletion tombstone", failure);
            }
        }

        static Tombstone parseFor(String raw, String connectionNamespace,
                                  String logicalKey) throws IOException {
            try {
                JSONObject root = new JSONObject(raw);
                if (!hasExactKeys(root, setOf("schema", "state", "transactionId",
                        "connectionNamespace", "logicalKeySha256", "backupSha256"))
                        || exactInteger(root.opt("schema")) != SCHEMA
                        || !COMMITTED.equals(root.opt("state"))
                        || !(root.opt("transactionId") instanceof String)
                        || !(root.opt("connectionNamespace") instanceof String)
                        || !(root.opt("logicalKeySha256") instanceof String)
                        || !(root.opt("backupSha256") instanceof String)) {
                    throw new IOException("Invalid manual queue deletion tombstone");
                }
                String transactionId = root.optString("transactionId", "");
                String receiptConnection =
                    root.optString("connectionNamespace", "");
                String keySha = root.optString("logicalKeySha256", "");
                String backupSha = root.optString("backupSha256", "");
                if (!transactionId.matches("[0-9a-f]{32}")
                        || !connectionNamespace.equals(receiptConnection)
                        || !sha256(logicalKey).equals(keySha)
                        || !backupSha.matches("[0-9a-f]{64}")) {
                    throw new IOException("Queue deletion tombstone binding is invalid");
                }
                return new Tombstone(transactionId, receiptConnection,
                    logicalKey, keySha, backupSha);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Malformed queue deletion tombstone", failure);
            }
        }

        boolean matches(Receipt receipt) {
            return receipt != null
                && transactionId.equals(receipt.transactionId)
                && connectionNamespace.equals(receipt.connectionNamespace)
                && logicalKeySha256.equals(receipt.logicalKeySha256)
                && backupSha256.equals(receipt.backupSha256);
        }

        boolean sameAs(Tombstone other) {
            return other != null
                && transactionId.equals(other.transactionId)
                && connectionNamespace.equals(other.connectionNamespace)
                && logicalKeySha256.equals(other.logicalKeySha256)
                && backupSha256.equals(other.backupSha256);
        }
    }

    private static List<Slot> expectedSlots(boolean includesGlobal) {
        List<Slot> slots = new ArrayList<>(Arrays.asList(SCOPED_TARGETS));
        if (includesGlobal) slots.addAll(Arrays.asList(GLOBAL_DATA));
        return slots;
    }

    private static boolean anyExists(Storage storage, String connectionNamespace,
                                     Slot[] slots) throws IOException {
        for (Slot slot : slots) {
            if (storage.exists(connectionNamespace, slot)) return true;
        }
        return false;
    }

    private static int exactInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            return Integer.MIN_VALUE;
        }
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
            ? (int) number : Integer.MIN_VALUE;
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static boolean hasExactKeys(JSONObject value, Set<String> expected) {
        if (value == null || expected == null) return false;
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        return actual.equals(expected);
    }
}
