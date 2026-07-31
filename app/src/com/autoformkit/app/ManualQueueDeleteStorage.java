package com.autoformkit.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Android storage adapter for {@link ManualQueueDeleteTransaction}.
 *
 * <p>Queue base/.bak/.tmp files are deliberately addressed as three exact physical files. Reading
 * them through {@code AtomicFile.openRead()} would normalize .bak and destroy the evidence before
 * the delete transaction can record it. The transaction receipt and tombstone do not have that
 * overlap and live at two independent, connection-scoped AtomicFile paths.</p>
 */
final class ManualQueueDeleteStorage
        implements ManualQueueDeleteTransaction.Storage {
    private static final String LEGACY_FILE_NAME = "queue-backup.json";
    private static final String SCOPED_FILE_PREFIX = "queue-backup-";
    private static final String TRANSACTION_DIRECTORY = "manual-queue-delete-v1";
    private static final String EXACT_WRITE_SUFFIX = ".manual-queue-write";

    interface PreferenceBackend {
        boolean contains(String key) throws IOException;
        Object read(String key) throws IOException;
        boolean putString(String key, String value) throws IOException;
        boolean remove(String key) throws IOException;
    }

    interface FileBackend {
        boolean exists(File file) throws IOException;
        String readUtf8(File file) throws IOException;
        void writeUtf8(File file, String value) throws IOException;
        void delete(File file) throws IOException;
    }

    private final File filesDirectory;
    private final PreferenceBackend preferences;
    private final FileBackend exactFiles;
    private final FileBackend transactionFiles;
    private final String logicalKey;
    private final String globalOwnerKey;
    private final String logicalKeyHashPrefix;

    ManualQueueDeleteStorage(Context context, SharedPreferences preferences,
                             String logicalKey, String globalOwnerKey) {
        this(requireFilesDirectory(context),
            new AndroidPreferenceBackend(preferences),
            new ExactPhysicalFileBackend(),
            new AtomicTransactionFileBackend(),
            logicalKey, globalOwnerKey);
    }

    ManualQueueDeleteStorage(File filesDirectory, PreferenceBackend preferences,
                             FileBackend exactFiles, FileBackend transactionFiles,
                             String logicalKey, String globalOwnerKey) {
        if (filesDirectory == null || preferences == null
                || exactFiles == null || transactionFiles == null) {
            throw new IllegalArgumentException(
                "files, preferences and storage backends are required");
        }
        if (logicalKey == null || logicalKey.isEmpty()
                || globalOwnerKey == null || globalOwnerKey.isEmpty()) {
            throw new IllegalArgumentException(
                "manual queue and global owner keys are required");
        }
        this.filesDirectory = filesDirectory;
        this.preferences = preferences;
        this.exactFiles = exactFiles;
        this.transactionFiles = transactionFiles;
        this.logicalKey = logicalKey;
        this.globalOwnerKey = globalOwnerKey;
        this.logicalKeyHashPrefix =
            RollbackMirrorRules.sha256(logicalKey).substring(0, 20);
    }

    @Override
    public boolean exists(String connectionNamespace,
                          ManualQueueDeleteTransaction.Slot slot)
            throws IOException {
        requireSlot(connectionNamespace, slot);
        if (isPreference(slot)) {
            return preferences.contains(preferenceKey(connectionNamespace, slot));
        }
        File file = file(connectionNamespace, slot);
        return backend(slot).exists(file);
    }

    @Override
    public String readUtf8(String connectionNamespace,
                           ManualQueueDeleteTransaction.Slot slot)
            throws IOException {
        requireSlot(connectionNamespace, slot);
        if (isPreference(slot)) {
            String key = preferenceKey(connectionNamespace, slot);
            if (!preferences.contains(key)) {
                throw new IOException("Manual queue preference slot is absent");
            }
            Object value = preferences.read(key);
            if (!(value instanceof String)) {
                throw new IOException("Manual queue preference slot is not a string");
            }
            return (String) value;
        }
        File file = file(connectionNamespace, slot);
        if (!backend(slot).exists(file)) {
            throw new IOException("Manual queue file slot is absent");
        }
        return backend(slot).readUtf8(file);
    }

    @Override
    public void writeUtf8(String connectionNamespace,
                          ManualQueueDeleteTransaction.Slot slot,
                          String value) throws IOException {
        requireSlot(connectionNamespace, slot);
        String safeValue = value == null ? "" : value;
        if (isPreference(slot)) {
            writePreferenceVerified(
                preferenceKey(connectionNamespace, slot), safeValue);
            return;
        }
        FileBackend backend = backend(slot);
        File file = file(connectionNamespace, slot);
        backend.writeUtf8(file, safeValue);
        if (!backend.exists(file) || !safeValue.equals(backend.readUtf8(file))) {
            throw new IOException("Manual queue file write readback mismatch");
        }
    }

    @Override
    public void delete(String connectionNamespace,
                       ManualQueueDeleteTransaction.Slot slot)
            throws IOException {
        requireSlot(connectionNamespace, slot);
        if (isPreference(slot)) {
            deletePreferenceVerified(preferenceKey(connectionNamespace, slot));
            return;
        }
        FileBackend backend = backend(slot);
        File file = file(connectionNamespace, slot);
        backend.delete(file);
        if (backend.exists(file)) {
            throw new IOException("Manual queue file deletion readback mismatch");
        }
    }

    /**
     * A crash after fsyncing an exact-file rename source but before rename leaves queue bytes in
     * the adapter-private sibling. PREPARED recovery will consume it, but without a receipt it must
     * block every upgrade/delete boundary instead of becoming invisible orphan data.
     */
    @Override
    public boolean auxiliaryRecoveryEvidencePresent(String connectionNamespace)
            throws IOException {
        requireSlot(connectionNamespace,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE);
        ManualQueueDeleteTransaction.Slot[] exactSlots = {
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BASE,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_BAK,
            ManualQueueDeleteTransaction.Slot.SCOPED_FILE_TMP,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BASE,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_BAK,
            ManualQueueDeleteTransaction.Slot.GLOBAL_FILE_TMP
        };
        for (ManualQueueDeleteTransaction.Slot slot : exactSlots) {
            if (exactFiles.exists(exactWriteFile(file(connectionNamespace, slot)))) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible for exact mapping tests and MainActivity wiring review. */
    File fileForTest(String connectionNamespace,
                     ManualQueueDeleteTransaction.Slot slot) {
        requireSlotUnchecked(connectionNamespace, slot);
        if (isPreference(slot)) {
            throw new IllegalArgumentException("Preference slots have no file");
        }
        return file(connectionNamespace, slot);
    }

    /** Package-visible for exact mapping tests and MainActivity wiring review. */
    String preferenceKeyForTest(String connectionNamespace,
                                ManualQueueDeleteTransaction.Slot slot) {
        requireSlotUnchecked(connectionNamespace, slot);
        if (!isPreference(slot)) {
            throw new IllegalArgumentException("File slots have no preference key");
        }
        return preferenceKey(connectionNamespace, slot);
    }

    /** Package-visible only so a JVM test can prove crash residue reaches the promotion gate. */
    File writeRemainderForTest(String connectionNamespace,
                               ManualQueueDeleteTransaction.Slot slot) {
        requireSlotUnchecked(connectionNamespace, slot);
        if (isPreference(slot)
                || slot == ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT
                || slot == ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE) {
            throw new IllegalArgumentException(
                "Only exact queue file slots have write remainders");
        }
        return exactWriteFile(file(connectionNamespace, slot));
    }

    private void writePreferenceVerified(String key, String value)
            throws IOException {
        boolean committed = false;
        IOException writeFailure = null;
        try {
            committed = preferences.putString(key, value);
        } catch (IOException failure) {
            writeFailure = failure;
        }

        boolean present = false;
        boolean matches = false;
        IOException readbackFailure = null;
        try {
            present = preferences.contains(key);
            Object readback = present ? preferences.read(key) : null;
            matches = readback instanceof String && value.equals(readback);
        } catch (IOException failure) {
            readbackFailure = failure;
        }
        if (!committed || !present || !matches || writeFailure != null
                || readbackFailure != null) {
            IOException failure = new IOException(
                "SharedPreferences put commit/readback failed");
            if (writeFailure != null) failure.addSuppressed(writeFailure);
            if (readbackFailure != null) failure.addSuppressed(readbackFailure);
            throw failure;
        }
    }

    private void deletePreferenceVerified(String key) throws IOException {
        boolean committed = false;
        IOException deleteFailure = null;
        try {
            committed = preferences.remove(key);
        } catch (IOException failure) {
            deleteFailure = failure;
        }

        boolean absent = false;
        IOException readbackFailure = null;
        try {
            absent = !preferences.contains(key);
            // Force a value read as well when a supposedly removed entry is still visible. This
            // distinguishes a stale non-string entry without ever logging its contents.
            if (!absent) preferences.read(key);
        } catch (IOException failure) {
            readbackFailure = failure;
        }
        if (!committed || !absent || deleteFailure != null
                || readbackFailure != null) {
            IOException failure = new IOException(
                "SharedPreferences remove commit/readback failed");
            if (deleteFailure != null) failure.addSuppressed(deleteFailure);
            if (readbackFailure != null) failure.addSuppressed(readbackFailure);
            throw failure;
        }
    }

    private FileBackend backend(ManualQueueDeleteTransaction.Slot slot) {
        return slot == ManualQueueDeleteTransaction.Slot.DELETE_RECEIPT
                || slot == ManualQueueDeleteTransaction.Slot.DELETE_TOMBSTONE
            ? transactionFiles : exactFiles;
    }

    private File file(String connectionNamespace,
                      ManualQueueDeleteTransaction.Slot slot) {
        File globalBase = new File(filesDirectory, LEGACY_FILE_NAME);
        File scopedBase = new File(filesDirectory,
            SCOPED_FILE_PREFIX + connectionNamespace + ".json");
        switch (slot) {
            case SCOPED_FILE_BASE:
                return scopedBase;
            case SCOPED_FILE_BAK:
                return new File(scopedBase.getPath() + ".bak");
            case SCOPED_FILE_TMP:
                return new File(scopedBase.getPath() + ".tmp");
            case GLOBAL_FILE_BASE:
                return globalBase;
            case GLOBAL_FILE_BAK:
                return new File(globalBase.getPath() + ".bak");
            case GLOBAL_FILE_TMP:
                return new File(globalBase.getPath() + ".tmp");
            case DELETE_RECEIPT:
                return transactionFile(connectionNamespace, ".receipt.json");
            case DELETE_TOMBSTONE:
                return transactionFile(connectionNamespace, ".tombstone.json");
            default:
                throw new IllegalArgumentException("Slot is not file-backed");
        }
    }

    private File transactionFile(String connectionNamespace, String suffix) {
        File directory = new File(filesDirectory, TRANSACTION_DIRECTORY);
        return new File(directory,
            connectionNamespace + "-" + logicalKeyHashPrefix + suffix);
    }

    private String preferenceKey(String connectionNamespace,
                                 ManualQueueDeleteTransaction.Slot slot) {
        switch (slot) {
            case SCOPED_PREF:
                return logicalKey + "_" + connectionNamespace;
            case GLOBAL_PREF:
                return logicalKey;
            case MIRROR_RECEIPT:
                return RollbackMirrorRules.receiptPreferenceKey(
                    connectionNamespace, logicalKey);
            case GLOBAL_OWNER:
                return globalOwnerKey;
            default:
                throw new IllegalArgumentException("Slot is not preference-backed");
        }
    }

    private static boolean isPreference(ManualQueueDeleteTransaction.Slot slot) {
        return slot == ManualQueueDeleteTransaction.Slot.SCOPED_PREF
            || slot == ManualQueueDeleteTransaction.Slot.GLOBAL_PREF
            || slot == ManualQueueDeleteTransaction.Slot.MIRROR_RECEIPT
            || slot == ManualQueueDeleteTransaction.Slot.GLOBAL_OWNER;
    }

    private static void requireSlot(String connectionNamespace,
                                    ManualQueueDeleteTransaction.Slot slot)
            throws IOException {
        if (slot == null || connectionNamespace == null
                || !connectionNamespace.matches("[0-9a-f]{20}")) {
            throw new IOException("Manual queue storage slot binding is invalid");
        }
    }

    private static void requireSlotUnchecked(String connectionNamespace,
                                             ManualQueueDeleteTransaction.Slot slot) {
        if (slot == null || connectionNamespace == null
                || !connectionNamespace.matches("[0-9a-f]{20}")) {
            throw new IllegalArgumentException(
                "Manual queue storage slot binding is invalid");
        }
    }

    private static File requireFilesDirectory(Context context) {
        if (context == null || context.getApplicationContext() == null
                || context.getApplicationContext().getFilesDir() == null) {
            throw new IllegalArgumentException("application files directory is required");
        }
        return context.getApplicationContext().getFilesDir();
    }

    private static final class AndroidPreferenceBackend
            implements PreferenceBackend {
        private final SharedPreferences preferences;

        AndroidPreferenceBackend(SharedPreferences preferences) {
            if (preferences == null) {
                throw new IllegalArgumentException("SharedPreferences are required");
            }
            this.preferences = preferences;
        }

        @Override
        public boolean contains(String key) throws IOException {
            try {
                return preferences.getAll().containsKey(key);
            } catch (RuntimeException failure) {
                throw new IOException(
                    "SharedPreferences presence read failed", failure);
            }
        }

        @Override
        public Object read(String key) throws IOException {
            try {
                Map<String, ?> values = preferences.getAll();
                return values.get(key);
            } catch (RuntimeException failure) {
                throw new IOException("SharedPreferences value read failed", failure);
            }
        }

        @Override
        public boolean putString(String key, String value) throws IOException {
            try {
                return preferences.edit().putString(key, value).commit();
            } catch (RuntimeException failure) {
                throw new IOException("SharedPreferences put failed", failure);
            }
        }

        @Override
        public boolean remove(String key) throws IOException {
            try {
                return preferences.edit().remove(key).commit();
            } catch (RuntimeException failure) {
                throw new IOException("SharedPreferences remove failed", failure);
            }
        }
    }

    /**
     * Exact physical-file backend. Its temporary name never aliases queue .bak/.tmp evidence.
     * Rename/unlink plus directory fsync gives each individual slot operation a durable boundary;
     * the higher-level receipt provides multi-slot recovery.
     */
    private static final class ExactPhysicalFileBackend implements FileBackend {
        @Override
        public boolean exists(File file) {
            return file != null && file.exists();
        }

        @Override
        public String readUtf8(File file) throws IOException {
            if (file == null || !file.exists()) {
                throw new IOException("Exact manual queue file is absent");
            }
            try (InputStream input = new FileInputStream(file)) {
                return readStreamUtf8(input);
            }
        }

        @Override
        public void writeUtf8(File file, String value) throws IOException {
            File parent = requireParent(file);
            ensureDirectoryDurable(parent);
            File temporary = exactWriteFile(file);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.getFD().sync();
            }
            try {
                Os.rename(temporary.getAbsolutePath(), file.getAbsolutePath());
                fsyncDirectory(parent);
            } catch (ErrnoException failure) {
                throw new IOException("Exact queue file rename failed", failure);
            }
        }

        @Override
        public void delete(File file) throws IOException {
            File parent = requireParent(file);
            boolean changed = unlinkIfPresent(file);
            changed |= unlinkIfPresent(exactWriteFile(file));
            if (changed) fsyncDirectory(parent);
        }
    }

    /** Logical AtomicFile backend for the independent receipt/tombstone paths. */
    private static final class AtomicTransactionFileBackend implements FileBackend {
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
            File parent = requireParent(file);
            ensureDirectoryDurable(parent);
            AtomicCacheFile.write(file,
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            fsyncDirectory(parent);
        }

        @Override
        public void delete(File file) throws IOException {
            File parent = requireParent(file);
            AtomicCacheFile.delete(file);
            fsyncDirectory(parent);
        }
    }

    private static String readStreamUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toString("UTF-8");
    }

    private static File requireParent(File file) throws IOException {
        if (file == null || file.getParentFile() == null) {
            throw new IOException("Manual queue file parent is absent");
        }
        return file.getParentFile();
    }

    private static File exactWriteFile(File file) {
        File parent = file == null ? null : file.getParentFile();
        if (parent == null) {
            throw new IllegalArgumentException(
                "Exact queue file parent is absent");
        }
        return new File(parent, file.getName() + EXACT_WRITE_SUFFIX);
    }

    private static void ensureDirectoryDurable(File directory) throws IOException {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IOException("Manual queue storage parent is not a directory");
            }
            return;
        }
        File parent = directory.getParentFile();
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create manual queue storage directory");
        }
        if (parent != null && parent.isDirectory()) fsyncDirectory(parent);
        fsyncDirectory(directory);
    }

    private static boolean unlinkIfPresent(File file) throws IOException {
        if (file == null || !file.exists()) return false;
        try {
            Os.remove(file.getAbsolutePath());
            return true;
        } catch (ErrnoException failure) {
            if (failure.errno == OsConstants.ENOENT) return false;
            throw new IOException("Exact queue file delete failed", failure);
        }
    }

    private static void fsyncDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()) {
            throw new IOException("Cannot sync manual queue storage directory");
        }
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(directory.getAbsolutePath(),
                OsConstants.O_RDONLY, 0);
            Os.fsync(descriptor);
        } catch (ErrnoException failure) {
            throw new IOException("Manual queue directory sync failed", failure);
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException closeFailure) {
                    // A completed fsync is the durable boundary. Surface close failure only when
                    // there was no earlier exception by wrapping it in an unchecked diagnostic is
                    // worse than preserving the already-synced transaction state.
                }
            }
        }
    }
}
