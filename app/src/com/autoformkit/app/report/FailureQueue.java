package com.autoformkit.app.report;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Small JSONL queue for disposable runtime diagnostics.
 *
 * <p>Every mutation is a synchronous {@link AtomicFile} replacement followed by an exact read-back
 * verification. In particular, {@link #dequeueForAttempt()} removes and verifies one physical row
 * before returning its event to a network caller. A failed or unverifiable replacement never
 * returns an event, so it can never be followed by a diagnostic socket attempt.
 */
public class FailureQueue {
    private static final String TAG = "FailureQueue";
    private static final String FILE_NAME = "failure-queue.jsonl";
    private static final int MAX_LINES = 2000;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    // AtomicFile deliberately provides no locking. There is normally one FailureQueue, but the
    // process lock also keeps a test/reinitialization instance from racing a verified replacement.
    private static final Object PROCESS_LOCK = new Object();

    private static final Set<String> EVENT_KEYS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("stage", "errCode", "subphase", "ts", "fp", "ctx")));
    private static final Set<String> CONTEXT_KEYS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("android_sdk", "app_version", "git_head", "net_active",
            "net_validated", "net_captive", "net_internet", "net_not_metered",
            "net_vpn")));

    interface Storage {
        boolean hasRecoverableCopy();
        byte[] read() throws IOException;
        void replaceAndSync(byte[] bytes) throws IOException;
    }

    public enum DequeueKind {
        EVENT,
        EMPTY,
        MALFORMED_REMOVED,
        STORAGE_FAILURE
    }

    public static final class DequeueResult {
        public final DequeueKind kind;
        public final FailureEvent event;

        private DequeueResult(DequeueKind kind, FailureEvent event) {
            this.kind = kind;
            this.event = event;
        }

        static DequeueResult event(FailureEvent event) {
            return new DequeueResult(DequeueKind.EVENT, event);
        }

        static DequeueResult of(DequeueKind kind) {
            return new DequeueResult(kind, null);
        }
    }

    private final Storage storage;

    public FailureQueue(Context context) {
        this(new AndroidAtomicStorage(new File(
            context.getApplicationContext().getFilesDir(), FILE_NAME)));
    }

    FailureQueue(Storage storage) {
        if (storage == null) throw new IllegalArgumentException("storage is required");
        this.storage = storage;
    }

    /** Persist one event completely before it becomes eligible for delivery. */
    public boolean enqueue(FailureEvent event) {
        if (event == null) return false;
        synchronized (PROCESS_LOCK) {
            try {
                List<String> rows = readRows();
                rows.add(event.toJson().toString());
                if (rows.size() > MAX_LINES) {
                    rows = new ArrayList<>(rows.subList(rows.size() - MAX_LINES / 2,
                        rows.size()));
                }
                return replaceAndVerify(rows);
            } catch (JSONException | IOException | RuntimeException error) {
                logWarning("enqueue failed", error);
                return false;
            }
        }
    }

    /**
     * Atomically removes exactly the first physical row and verifies the complete remaining file.
     * A malformed first row is cleaned on its own and never changes the index of a later valid row
     * in the same operation. Only a successfully removed and verified valid row returns an event.
     */
    public DequeueResult dequeueForAttempt() {
        synchronized (PROCESS_LOCK) {
            try {
                List<String> rows = readRows();
                if (rows.isEmpty()) return DequeueResult.of(DequeueKind.EMPTY);

                String first = rows.get(0);
                FailureEvent event = parseQueueRow(first);
                List<String> remaining = new ArrayList<>(rows.subList(1, rows.size()));
                if (!replaceAndVerify(remaining)) {
                    return DequeueResult.of(DequeueKind.STORAGE_FAILURE);
                }
                return event == null
                    ? DequeueResult.of(DequeueKind.MALFORMED_REMOVED)
                    : DequeueResult.event(event);
            } catch (IOException | RuntimeException error) {
                logWarning("dequeue failed", error);
                return DequeueResult.of(DequeueKind.STORAGE_FAILURE);
            }
        }
    }

    public int size() {
        synchronized (PROCESS_LOCK) {
            try {
                return readRows().size();
            } catch (IOException | RuntimeException ignored) {
                return 0;
            }
        }
    }

    /** Remove diagnostic-only rows and verify the durable empty snapshot. */
    public boolean clear() {
        synchronized (PROCESS_LOCK) {
            try {
                if (!storage.hasRecoverableCopy()) return true;
                return replaceAndVerify(Collections.emptyList());
            } catch (IOException | RuntimeException error) {
                logWarning("clear failed", error);
                return false;
            }
        }
    }

    private boolean replaceAndVerify(List<String> rows) throws IOException {
        byte[] expected = encodeRows(rows);
        storage.replaceAndSync(expected);
        byte[] actual = storage.read();
        return Arrays.equals(expected, actual);
    }

    private List<String> readRows() throws IOException {
        if (!storage.hasRecoverableCopy()) return new ArrayList<>();
        byte[] bytes = storage.read();
        if (bytes.length == 0) return new ArrayList<>();
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Diagnostic queue is oversized");
        String value = decodeUtf8Strict(bytes);
        String[] split = value.split("\n", -1);
        int count = split.length;
        if (count > 0 && split[count - 1].isEmpty()) count--;
        List<String> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) rows.add(split[i]);
        return rows;
    }

    private static byte[] encodeRows(List<String> rows) {
        if (rows == null || rows.isEmpty()) return new byte[0];
        StringBuilder value = new StringBuilder();
        for (String row : rows) value.append(row == null ? "" : row).append('\n');
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String decodeUtf8Strict(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("Diagnostic queue is not UTF-8", invalid);
        }
    }

    /** Strict queue-v3 row parser. Legacy/free-form or partially written rows are disposable. */
    private static FailureEvent parseQueueRow(String row) {
        try {
            if (row == null || row.isEmpty()) return null;
            JSONObject json = new JSONObject(row);
            if (!hasExactKeys(json, EVENT_KEYS)
                    || !(json.get("stage") instanceof String)
                    || !(json.get("errCode") instanceof String)
                    || !(json.get("subphase") instanceof String)
                    || !(json.get("fp") instanceof String)
                    || !(json.get("ts") instanceof Number)) return null;
            JSONObject context = json.optJSONObject("ctx");
            if (context == null || !hasExactKeys(context, CONTEXT_KEYS)) return null;
            for (String key : CONTEXT_KEYS) {
                if (!(context.get(key) instanceof String)) return null;
            }
            return FailureEvent.fromJson(json);
        } catch (JSONException | RuntimeException invalid) {
            return null;
        }
    }

    private static boolean hasExactKeys(JSONObject json, Set<String> expected) {
        if (json == null || json.length() != expected.size()) return false;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) if (!expected.contains(keys.next())) return false;
        return true;
    }

    private static void logWarning(String message, Throwable error) {
        // android.jar methods throw in plain JVM tests; logging must never change queue semantics.
        try { Log.w(TAG, message, error); }
        catch (Throwable ignored) {}
    }

    private static final class AndroidAtomicStorage implements Storage {
        private final File file;
        private final AtomicFile atomicFile;

        AndroidAtomicStorage(File file) {
            this.file = file;
            this.atomicFile = new AtomicFile(file);
        }

        @Override public boolean hasRecoverableCopy() {
            return file.exists() || new File(file.getPath() + ".bak").exists();
        }

        @Override public byte[] read() throws IOException {
            try (FileInputStream input = atomicFile.openRead()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                return output.toByteArray();
            }
        }

        @Override public void replaceAndSync(byte[] bytes) throws IOException {
            File parent = file.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                throw new IOException("Cannot create diagnostic queue directory");
            }
            FileOutputStream output = null;
            try {
                output = atomicFile.startWrite();
                output.write(bytes == null ? new byte[0] : bytes);
                output.flush();
                output.getFD().sync();
                atomicFile.finishWrite(output);
                output = null;
            } catch (IOException | RuntimeException failure) {
                if (output != null) atomicFile.failWrite(output);
                throw failure;
            }
        }
    }
}
