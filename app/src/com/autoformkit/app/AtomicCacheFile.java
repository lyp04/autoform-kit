package com.autoformkit.app;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Crash-recoverable storage for process-local Panel caches and durable queue snapshots. */
final class AtomicCacheFile {
    // android.util.AtomicFile is crash-safe but deliberately provides no thread locking. Panel
    // refreshes and queue saves can race UI/provider reads, so every process-local instance shares
    // one lock. This also prevents a reader restoring .bak during another thread's write.
    private static final Object PROCESS_LOCK = new Object();

    private AtomicCacheFile() {}

    /** AtomicFile.openRead() restores the legacy .bak file; include it in existence checks. */
    static boolean hasRecoverableCopy(File file) {
        if (file == null) return false;
        synchronized (PROCESS_LOCK) {
            return file.exists() || new File(file.getPath() + ".bak").exists();
        }
    }

    /**
     * Reads the complete temp left by the pre-AtomicFile queue writer only when neither a committed
     * base nor AtomicFile's .bak exists. Callers must still validate JSON and Panel ownership before
     * atomically promoting it; a temp never competes with a committed copy.
     */
    static String readLegacyTempUtf8IfUncommitted(File file) throws IOException {
        if (file == null) throw new IOException("Cache file is absent");
        synchronized (PROCESS_LOCK) {
            if (file.exists() || new File(file.getPath() + ".bak").exists()) {
                throw new IOException("Committed cache copy exists");
            }
            File legacyTemp = new File(file.getPath() + ".tmp");
            if (!legacyTemp.exists()) throw new IOException("Legacy temp is absent");
            return readStreamUtf8(new java.io.FileInputStream(legacyTemp));
        }
    }

    static String readUtf8(File file) throws IOException {
        if (file == null) throw new IOException("Cache file is absent");
        synchronized (PROCESS_LOCK) {
            AtomicFile atomic = new AtomicFile(file);
            try (InputStream input = atomic.openRead()) {
                return readStreamUtf8(input);
            }
        }
    }

    static void write(File file, byte[] bytes) throws IOException {
        if (file == null) throw new IOException("Cache file is absent");
        synchronized (PROCESS_LOCK) {
            File parent = file.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                throw new IOException("Cannot create cache directory");
            }
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream output = null;
            try {
                output = atomic.startWrite();
                output.write(bytes == null ? new byte[0] : bytes);
                output.flush();
                output.getFD().sync();
                atomic.finishWrite(output);
                output = null;
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomic.failWrite(output);
                throw failure;
            }
        }
    }

    static void delete(File file) {
        if (file == null) return;
        synchronized (PROCESS_LOCK) {
            new AtomicFile(file).delete();
            File parent = file.getParentFile();
            if (parent != null) {
                File oldTmp = new File(parent, file.getName() + ".tmp");
                if (oldTmp.exists()) oldTmp.delete();
            }
        }
    }

    private static String readStreamUtf8(InputStream input) throws IOException {
        try (InputStream closeable = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = closeable.read(buffer)) >= 0) output.write(buffer, 0, read);
            if (output.size() == 0) throw new IOException("Cache file is empty");
            return output.toString("UTF-8");
        }
    }
}
