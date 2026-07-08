package com.autoformkit.app.report;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL queue persisted under filesDir/failure-queue.jsonl. Crash-safe enough
 * for our purposes — each event is one line, parse failures skip the line.
 *
 * <p>Capped at {@link #MAX_LINES}; on overflow the oldest half is dropped.
 */
public class FailureQueue {
    private static final String TAG = "FailureQueue";
    private static final String FILE_NAME = "failure-queue.jsonl";
    private static final int MAX_LINES = 2000;

    private final File file;

    public FailureQueue(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    public synchronized void enqueue(FailureEvent event) {
        try {
            JSONObject json = event.toJson();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                w.write(json.toString());
                w.write('\n');
            }
            trimIfNeeded();
        } catch (JSONException | IOException error) {
            Log.w(TAG, "enqueue failed", error);
        }
    }

    public synchronized List<FailureEvent> snapshot() {
        List<FailureEvent> events = new ArrayList<>();
        if (!file.exists()) {
            return events;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    events.add(FailureEvent.fromJson(new JSONObject(line)));
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "snapshot failed", error);
        }
        return events;
    }

    public synchronized void dropFirst(int count) {
        if (count <= 0 || !file.exists()) {
            return;
        }
        List<String> kept = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int idx = 0;
            while ((line = r.readLine()) != null) {
                if (idx++ >= count && !line.isEmpty()) {
                    kept.add(line);
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "dropFirst read failed", error);
            return;
        }
        writeAll(kept);
    }

    public synchronized int size() {
        if (!file.exists()) return 0;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) n++;
        } catch (IOException ignored) {
        }
        return n;
    }

    private void trimIfNeeded() {
        int current = size();
        if (current <= MAX_LINES) {
            return;
        }
        dropFirst(current - MAX_LINES / 2);
    }

    private void writeAll(List<String> lines) {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException error) {
            Log.w(TAG, "writeAll failed", error);
        }
    }
}
