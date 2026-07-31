package com.autoformkit.app;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Crash-safe promotion of a validated two-file Panel cache pair. */
final class PanelPairCacheTransaction {
    private static final int SCHEMA = 1;
    private static final Object PROCESS_LOCK = new Object();

    interface Storage {
        boolean exists(File file) throws IOException;
        String readUtf8(File file) throws IOException;
        void writeUtf8(File file, String value) throws IOException;
        void delete(File file) throws IOException;
    }

    interface Validator {
        boolean isValid(String first, String second) throws Exception;
    }

    enum Recovery {
        NONE,
        RESTORED_OLD,
        ACCEPTED_NEW
    }

    static final class Files {
        final File activeFirst;
        final File activeSecond;
        final File candidateFirst;
        final File candidateSecond;
        final File receipt;

        Files(File activeFirst, File activeSecond, File candidateFirst,
              File candidateSecond, File receipt) {
            this.activeFirst = required(activeFirst, "activeFirst");
            this.activeSecond = required(activeSecond, "activeSecond");
            this.candidateFirst = required(candidateFirst, "candidateFirst");
            this.candidateSecond = required(candidateSecond, "candidateSecond");
            this.receipt = required(receipt, "receipt");
            Set<String> distinct = new HashSet<>();
            for (File file : new File[]{activeFirst, activeSecond, candidateFirst,
                    candidateSecond, receipt}) {
                distinct.add(canonicalPath(file));
            }
            if (distinct.size() != 5) {
                throw new IllegalArgumentException("transaction files must be distinct");
            }
        }

        private static String canonicalPath(File file) {
            try {
                // The java.nio path conversion is API 26 on Android. getCanonicalPath() is
                // available from API 1 and still collapses "a/../b" and symbolic-link aliases.
                return file.getCanonicalPath();
            } catch (IOException failure) {
                throw new IllegalArgumentException(
                    "Could not resolve transaction file path", failure);
            }
        }

        private static File required(File file, String label) {
            if (file == null) throw new IllegalArgumentException(label + " is required");
            return file;
        }
    }

    /** Production adapter; each individual file operation retains AtomicFile crash safety. */
    static final class AtomicStorage implements Storage {
        @Override
        public boolean exists(File file) {
            return AtomicCacheFile.hasRecoverableCopy(file);
        }

        @Override
        public String readUtf8(File file) throws IOException {
            return AtomicCacheFile.readUtf8(file);
        }

        @Override
        public void writeUtf8(File file, String value) throws IOException {
            AtomicCacheFile.write(file, value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(File file) throws IOException {
            AtomicCacheFile.delete(file);
            if (AtomicCacheFile.hasRecoverableCopy(file)) {
                throw new IOException("Could not delete atomic cache file");
            }
        }
    }

    private PanelPairCacheTransaction() {
    }

    static void promote(Storage storage, Validator validator, Files files) throws IOException {
        requireDependencies(storage, validator, files);
        synchronized (PROCESS_LOCK) {
            if (storage.exists(files.receipt)) {
                throw new IOException("Unresolved Panel pair transaction receipt");
            }

            Snapshot oldFirst = Snapshot.capture(storage, files.activeFirst);
            Snapshot oldSecond = Snapshot.capture(storage, files.activeSecond);
            Snapshot newFirst = Snapshot.required(storage, files.candidateFirst);
            Snapshot newSecond = Snapshot.required(storage, files.candidateSecond);
            requireValid(validator, newFirst.value, newSecond.value, "candidate");

            Receipt prepared = new Receipt(State.PREPARED,
                oldFirst.descriptor, oldSecond.descriptor,
                newFirst.descriptor, newSecond.descriptor);
            writeReceipt(storage, files.receipt, prepared);
            try {
                stageOldAndActivateNew(storage, files.candidateFirst, files.activeFirst,
                    oldFirst, newFirst);
                stageOldAndActivateNew(storage, files.candidateSecond, files.activeSecond,
                    oldSecond, newSecond);
                requireActiveNew(storage, validator, files, prepared);

                Receipt committed = prepared.withState(State.COMMITTED);
                writeReceipt(storage, files.receipt, committed);
                acceptCommittedNew(storage, files);
            } catch (IOException | RuntimeException failure) {
                try {
                    recoverLocked(storage, validator, files);
                } catch (IOException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
        }
    }

    static Recovery recover(Storage storage, Validator validator, Files files)
            throws IOException {
        requireDependencies(storage, validator, files);
        synchronized (PROCESS_LOCK) {
            return recoverLocked(storage, validator, files);
        }
    }

    private static Recovery recoverLocked(Storage storage, Validator validator, Files files)
            throws IOException {
        if (!storage.exists(files.receipt)) return Recovery.NONE;
        Receipt receipt = Receipt.parse(storage.readUtf8(files.receipt));
        // COMMITTED is written only after both active files passed their hashes and semantic
        // validator. Recovery must therefore finish that exact commit whenever the hashes still
        // match, even if mutable outside state (for example the selected Panel connection) changed
        // before restart. Re-running that mutable validator here could wrongly downgrade a
        // committed transaction after one old candidate backup had already been removed.
        if (receipt.state == State.COMMITTED && activeNewMatchesReceipt(
                storage, files, receipt)) {
            acceptCommittedNew(storage, files);
            return Recovery.ACCEPTED_NEW;
        }

        String oldFirst = resolveOld(storage, receipt.oldFirst,
            files.activeFirst, files.candidateFirst);
        String oldSecond = resolveOld(storage, receipt.oldSecond,
            files.activeSecond, files.candidateSecond);
        restore(storage, files.activeFirst, receipt.oldFirst, oldFirst);
        restore(storage, files.activeSecond, receipt.oldSecond, oldSecond);
        verify(storage, files.activeFirst, receipt.oldFirst);
        verify(storage, files.activeSecond, receipt.oldSecond);
        finishRollback(storage, files);
        return Recovery.RESTORED_OLD;
    }

    private static void stageOldAndActivateNew(Storage storage, File candidate, File active,
                                                Snapshot oldValue, Snapshot newValue)
            throws IOException {
        if (oldValue.descriptor.present) storage.writeUtf8(candidate, oldValue.value);
        else storage.delete(candidate);
        storage.writeUtf8(active, newValue.value);
        verify(storage, active, newValue.descriptor);
    }

    private static void requireActiveNew(Storage storage, Validator validator, Files files,
                                         Receipt receipt) throws IOException {
        verify(storage, files.activeFirst, receipt.newFirst);
        verify(storage, files.activeSecond, receipt.newSecond);
        requireValid(validator, storage.readUtf8(files.activeFirst),
            storage.readUtf8(files.activeSecond), "active");
    }

    private static boolean activeNewMatchesReceipt(Storage storage, Files files,
                                                   Receipt receipt) {
        try {
            verify(storage, files.activeFirst, receipt.newFirst);
            verify(storage, files.activeSecond, receipt.newSecond);
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private static String resolveOld(Storage storage, Descriptor expected,
                                     File active, File candidate) throws IOException {
        if (!expected.present) return null;
        IOException readFailure = null;
        for (File source : new File[]{active, candidate}) {
            try {
                if (!storage.exists(source)) continue;
                String value = storage.readUtf8(source);
                if (expected.sha256.equals(sha256(value))) return value;
            } catch (IOException failure) {
                readFailure = failure;
            }
        }
        IOException missing = new IOException("Old Panel cache component is unavailable");
        if (readFailure != null) missing.addSuppressed(readFailure);
        throw missing;
    }

    private static void restore(Storage storage, File active, Descriptor expected, String value)
            throws IOException {
        if (expected.present) storage.writeUtf8(active, value);
        else storage.delete(active);
    }

    private static void verify(Storage storage, File file, Descriptor expected)
            throws IOException {
        boolean present = storage.exists(file);
        if (present != expected.present) {
            throw new IOException("Panel cache component presence mismatch");
        }
        if (present && !expected.sha256.equals(sha256(storage.readUtf8(file)))) {
            throw new IOException("Panel cache component hash mismatch");
        }
    }

    /**
     * Keep the COMMITTED receipt until both old candidate backups are gone. If cleanup is
     * interrupted, recovery can accept the already-verified new active pair and finish deleting
     * the backups. Removing the receipt first would leave an unmarked old candidate pair that a
     * later refresh could mistake for a newly downloaded pair and silently promote backwards.
     */
    private static void acceptCommittedNew(Storage storage, Files files) throws IOException {
        storage.delete(files.candidateFirst);
        storage.delete(files.candidateSecond);
        storage.delete(files.receipt);
    }

    /** Keep PREPARED/failed-COMMITTED recovery evidence until the old active pair is complete. */
    private static void finishRollback(Storage storage, Files files) throws IOException {
        storage.delete(files.candidateFirst);
        storage.delete(files.candidateSecond);
        storage.delete(files.receipt);
    }

    private static void writeReceipt(Storage storage, File receiptFile, Receipt receipt)
            throws IOException {
        storage.writeUtf8(receiptFile, receipt.toJson().toString());
        Receipt stored = Receipt.parse(storage.readUtf8(receiptFile));
        if (!receipt.sameAs(stored)) throw new IOException("Transaction receipt readback mismatch");
    }

    private static void requireValid(Validator validator, String first, String second,
                                     String label) throws IOException {
        try {
            if (!validator.isValid(first, second)) {
                throw new IOException(label + " Panel cache pair is invalid");
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException(label + " Panel cache validation failed", failure);
        }
    }

    private static void requireDependencies(Storage storage, Validator validator, Files files) {
        if (storage == null || validator == null || files == null) {
            throw new IllegalArgumentException("storage, validator and files are required");
        }
    }

    private static String sha256(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte item : digest) out.append(String.format(Locale.US, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private enum State {
        PREPARED,
        COMMITTED
    }

    private static final class Snapshot {
        final Descriptor descriptor;
        final String value;

        private Snapshot(Descriptor descriptor, String value) {
            this.descriptor = descriptor;
            this.value = value;
        }

        static Snapshot capture(Storage storage, File file) throws IOException {
            if (!storage.exists(file)) return new Snapshot(Descriptor.absent(), null);
            String value = storage.readUtf8(file);
            return new Snapshot(Descriptor.present(sha256(value)), value);
        }

        static Snapshot required(Storage storage, File file) throws IOException {
            Snapshot value = capture(storage, file);
            if (!value.descriptor.present) {
                throw new IOException("Candidate Panel cache component is absent");
            }
            return value;
        }
    }

    private static final class Descriptor {
        final boolean present;
        final String sha256;

        private Descriptor(boolean present, String sha256) {
            this.present = present;
            this.sha256 = sha256;
        }

        static Descriptor absent() {
            return new Descriptor(false, null);
        }

        static Descriptor present(String sha256) {
            return new Descriptor(true, sha256);
        }

        JSONObject toJson() throws IOException {
            try {
                JSONObject out = new JSONObject().put("present", present);
                if (present) out.put("sha256", sha256);
                return out;
            } catch (Exception failure) {
                throw new IOException("Cannot serialize transaction descriptor", failure);
            }
        }

        static Descriptor parse(JSONObject value) throws IOException {
            if (value == null || !(value.opt("present") instanceof Boolean)) {
                throw new IOException("Invalid transaction receipt descriptor");
            }
            boolean present = value.optBoolean("present");
            Set<String> expected = present
                ? setOf("present", "sha256") : setOf("present");
            if (!hasExactKeys(value, expected)) {
                throw new IOException("Unexpected transaction receipt descriptor fields");
            }
            if (!present) return absent();
            Object digest = value.opt("sha256");
            if (!(digest instanceof String) || !((String) digest).matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid transaction receipt digest");
            }
            return present((String) digest);
        }

        boolean sameAs(Descriptor other) {
            return other != null && present == other.present
                && (sha256 == null ? other.sha256 == null : sha256.equals(other.sha256));
        }
    }

    private static final class Receipt {
        final State state;
        final Descriptor oldFirst;
        final Descriptor oldSecond;
        final Descriptor newFirst;
        final Descriptor newSecond;

        Receipt(State state, Descriptor oldFirst, Descriptor oldSecond,
                Descriptor newFirst, Descriptor newSecond) {
            this.state = state;
            this.oldFirst = oldFirst;
            this.oldSecond = oldSecond;
            this.newFirst = newFirst;
            this.newSecond = newSecond;
        }

        Receipt withState(State next) {
            return new Receipt(next, oldFirst, oldSecond, newFirst, newSecond);
        }

        JSONObject toJson() throws IOException {
            try {
                return new JSONObject()
                    .put("schema", SCHEMA)
                    .put("state", state.name())
                    .put("old", pair(oldFirst, oldSecond))
                    .put("new", pair(newFirst, newSecond));
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Cannot serialize transaction receipt", failure);
            }
        }

        static Receipt parse(String raw) throws IOException {
            try {
                JSONObject root = new JSONObject(raw);
                if (!hasExactKeys(root, setOf("schema", "state", "old", "new"))
                        || !(root.opt("schema") instanceof Number)
                        || ((Number) root.opt("schema")).intValue() != SCHEMA
                        || ((Number) root.opt("schema")).doubleValue() != SCHEMA
                        || !(root.opt("state") instanceof String)) {
                    throw new IOException("Invalid Panel pair transaction receipt");
                }
                State state;
                try {
                    state = State.valueOf((String) root.opt("state"));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Unknown Panel pair transaction state", failure);
                }
                Descriptor[] oldPair = parsePair(root.optJSONObject("old"));
                Descriptor[] newPair = parsePair(root.optJSONObject("new"));
                if (!newPair[0].present || !newPair[1].present) {
                    throw new IOException("Committed candidate pair must be complete");
                }
                return new Receipt(state, oldPair[0], oldPair[1], newPair[0], newPair[1]);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Malformed Panel pair transaction receipt", failure);
            }
        }

        boolean sameAs(Receipt other) {
            return other != null && state == other.state
                && oldFirst.sameAs(other.oldFirst) && oldSecond.sameAs(other.oldSecond)
                && newFirst.sameAs(other.newFirst) && newSecond.sameAs(other.newSecond);
        }

        private static JSONObject pair(Descriptor first, Descriptor second) throws IOException {
            try {
                return new JSONObject().put("first", first.toJson())
                    .put("second", second.toJson());
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Cannot serialize transaction pair", failure);
            }
        }

        private static Descriptor[] parsePair(JSONObject value) throws IOException {
            if (value == null || !hasExactKeys(value, setOf("first", "second"))) {
                throw new IOException("Invalid transaction receipt pair");
            }
            return new Descriptor[]{
                Descriptor.parse(value.optJSONObject("first")),
                Descriptor.parse(value.optJSONObject("second"))
            };
        }
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /** Android's JSONObject exposes keys() rather than the desktop library's keySet(). */
    private static boolean hasExactKeys(JSONObject value, Set<String> expected) {
        if (value == null || expected == null) return false;
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        return actual.equals(expected);
    }
}
