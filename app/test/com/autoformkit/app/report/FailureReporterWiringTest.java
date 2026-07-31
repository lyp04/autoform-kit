package com.autoformkit.app.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FailureReporterWiringTest {
    @Test
    public void productionReporterUsesTheRemoveBeforePostCoordinator() throws Exception {
        String reporter = source("app/src/com/autoformkit/app/report/FailureReporter.java",
            "src/com/autoformkit/app/report/FailureReporter.java");
        String flush = section(reporter, "private void flushBlocking(",
            "/**\n     * Dequeues first");
        String attempt = section(reporter, "static AttemptOutcome attemptNext(",
            "private PostDisposition uploadOne(");
        String upload = section(reporter, "private PostDisposition uploadOne(",
            "private void installNetworkRecoveryTrigger(");

        assertTrue(flush.contains("synchronized (pairQueueLock)"));
        assertTrue(flush.contains("attemptNext(queue,"));
        assertBefore(attempt, "generationCurrent.isCurrent()",
            "queue.dequeueForAttempt()");
        assertBefore(attempt, "queue.dequeueForAttempt()",
            "poster.postEvent(dequeued.event)");
        assertEquals(2, count(attempt, "generationCurrent.isCurrent()"));
        assertEquals(1, count(upload, "NotificationClient.postEvent("));
        assertBefore(upload, "KEY_LAST_ATTEMPT_MS", "NotificationClient.postEvent(");
        assertBefore(upload, "if (result.success)", "clearConfigError()");
        assertTrue(upload.contains("KEY_LAST_UPLOAD_MS"));
        assertTrue(upload.contains("result.statusCode >= 400 && result.statusCode < 500"));
        assertTrue(upload.contains("recordConfigError(result.statusCode)"));
        assertTrue(upload.contains("recordTransportError()"));
        assertFalse(reporter.contains("queue.dropFirst("));
        assertFalse(reporter.contains("queue.snapshot("));
    }

    @Test
    public void atomicFileReplacementIsSyncedFinishedAndReadBackVerified() throws Exception {
        String queue = source("app/src/com/autoformkit/app/report/FailureQueue.java",
            "src/com/autoformkit/app/report/FailureQueue.java");
        String replace = section(queue, "private boolean replaceAndVerify(",
            "private List<String> readRows(");
        String androidStorage = section(queue,
            "private static final class AndroidAtomicStorage",
            "\n    }\n}");

        assertTrue(queue.contains("import android.util.AtomicFile;"));
        assertBefore(replace, "storage.replaceAndSync(expected)", "storage.read()");
        assertTrue(replace.contains("Arrays.equals(expected, actual)"));
        assertBefore(androidStorage, "atomicFile.startWrite()", "output.getFD().sync()");
        assertBefore(androidStorage, "output.getFD().sync()", "atomicFile.finishWrite(output)");
        assertTrue(androidStorage.contains("atomicFile.failWrite(output)"));
    }

    @Test
    public void pairGenerationChangesUnderTheSameAttemptLock() throws Exception {
        String reporter = source("app/src/com/autoformkit/app/report/FailureReporter.java",
            "src/com/autoformkit/app/report/FailureReporter.java");
        String clear = section(reporter, "public boolean clearForPanelConnectionChange()",
            "static boolean samePairGeneration(");
        assertBefore(clear, "synchronized (pairQueueLock)",
            "pairGeneration.incrementAndGet()");
    }

    @Test
    public void notificationPostOwnsWorkerLeaseAndRechecksExactInstalledPair() throws Exception {
        String client = source("app/src/com/autoformkit/app/NotificationClient.java",
            "src/com/autoformkit/app/NotificationClient.java");
        String post = section(client,
            "public static Result postEvent(Context context, Snapshot snapshot,",
            "/**\n     * Captures and publishes");
        String current = section(client,
            "private static boolean installedSnapshotStillCurrentLocked(",
            "private static String queuePairNamespace(");
        String installedCheck = section(client,
            "public static boolean installedSnapshotStillCurrent(",
            "/** Pure identity predicate");
        String diskQueue = section(client,
            "public static String currentInstalledPairQueueNamespace(Context context)",
            "/** True only for the one current process generation");

        assertBefore(post, "RemoteSideEffectGate.tryAcquireWorker(context)",
            "Resolved resolved = resolveCurrent(context, snapshot, type)");
        assertTrue(post.contains("finally"));
        assertTrue(post.contains("workerLease.close()"));
        assertTrue(installedCheck.contains(
            "synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(current.contains("sameInstalledGeneration(snapshot, installed)"));
        assertTrue(current.contains(
            "PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit("));
        assertTrue(current.contains("pair.version == snapshot.catalogVersion"));
        assertTrue(current.contains(
            "snapshot.panelPairSha256.equals(pair.pairSha256)"));
        assertTrue(diskQueue.contains(
            "PanelPairCacheCoordinator.loadActivePairIfCandidatesPermit("));
    }

    @Test
    public void diagnosticQueueIsBoundToOpaqueExactPairAndOldKeysAreRemoved() throws Exception {
        String reporter = source("app/src/com/autoformkit/app/report/FailureReporter.java",
            "src/com/autoformkit/app/report/FailureReporter.java");
        String bind = section(reporter, "private boolean bindQueueToPairLocked(",
            "private boolean pairSessionCurrent(");

        assertBefore(bind, "pairGeneration.incrementAndGet()", "queue.clear()");
        assertBefore(bind, "queue.clear()", "editor.commit()");
        assertTrue(bind.contains("KEY_QUEUE_PAIR_NAMESPACE"));
        assertTrue(bind.contains("LEGACY_KEY_CONNECTION_QUEUE_READY"));
        assertTrue(bind.contains("LEGACY_KEY_QUEUE_CONNECTION_NAMESPACE"));
        assertFalse(bind.contains("currentConnectionNamespace"));
        assertFalse(bind.contains("panelBase"));
        assertFalse(bind.contains("accessKey"));
    }

    @Test
    public void snapshotInstallReleasesHandoffBeforeReporterQueueRebind() throws Exception {
        String activity = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String publish = section(activity,
            "private void publishActiveNotificationSnapshot()",
            "private boolean activeWorkflowCanContinue()");
        String client = source("app/src/com/autoformkit/app/NotificationClient.java",
            "src/com/autoformkit/app/NotificationClient.java");

        assertBefore(publish, "NotificationClient.installActiveSnapshot(",
            "FailureReporter.get().requestFlush()");
        assertFalse(publish.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertFalse(client.contains("FailureReporter"));
    }

    private static String source(String rootRelative, String moduleRelative) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (String candidate : new String[]{rootRelative, moduleRelative}) {
            Path path = cwd.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found from " + cwd);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static void assertBefore(String source, String first, String second) {
        int firstAt = source.indexOf(first);
        int secondAt = source.indexOf(second);
        assertTrue("missing: " + first, firstAt >= 0);
        assertTrue("missing: " + second, secondAt >= 0);
        assertTrue(first + " must precede " + second, firstAt < secondAt);
    }

    private static int count(String source, String needle) {
        int result = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) result++;
        return result;
    }
}
