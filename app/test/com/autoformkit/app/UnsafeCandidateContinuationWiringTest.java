package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UnsafeCandidateContinuationWiringTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
            cwd.resolve("src/com/autoformkit/app/MainActivity.java")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    @Test
    public void unitCreationAndDraftRestoreShareCandidateHandoffLock() throws Exception {
        String source = mainActivitySource();
        String add = section(source,
            "private UnitRecord addSnRecord(String sn, String grade, String source)",
            "private void addBaseSn()");
        assertTrue(add.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(add.contains("if (panelConnectionSyncBlocked())"));
        assertTrue(add.indexOf("panelConnectionSyncBlocked()")
            < add.indexOf("addSnRecordAtReadyBoundary"));

        String restore = section(source,
            "private int restorePreparedDraftContents(JSONObject draft)",
            "private void restoreCurrentProfileDraftOrEmpty()");
        assertTrue(restore.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(restore.contains("if (panelConnectionSyncBlocked())"));
        assertTrue(restore.indexOf("panelConnectionSyncBlocked()")
            < restore.indexOf("units.add(unit)"));
    }

    @Test
    public void activeContinuationUsesFiniteLeaseInsteadOfPageOpenAlone() throws Exception {
        String source = mainActivitySource();
        String active = section(source,
            "private boolean activeWorkflowCanContinue()",
            "private boolean ensurePanelReadyForUse()");
        assertTrue(active.contains("unsafeCandidatesBlockActiveUse()"));
        assertTrue(active.contains("unsafeContinuationAllowsCurrentWork()"));

        String lease = section(source,
            "private boolean unsafeContinuationAllowsCurrentWork()",
            "private boolean authorizeMainWorkerForUnsafeCandidate()");
        assertTrue(lease.contains("liveMainUnitSequences()"));
        assertTrue(lease.contains("currentPanelPairSha256()"));
        assertTrue(lease.contains("activeCatalogVersion"));
    }

    @Test
    public void manualPrintCannotStartBehindUnsafeBarrier() throws Exception {
        String source = mainActivitySource();
        String print = section(source,
            "private synchronized boolean beginPrintRemoteWorker(",
            "private synchronized void endPrintRemoteWorker()");
        assertTrue(print.contains("unsafeCandidatesBlockActiveUse()"));
        assertTrue(print.indexOf("unsafeCandidatesBlockActiveUse()")
            < print.indexOf("RemoteSideEffectGate.tryAcquireWorker(this)"));
    }

    @Test
    public void aNewBoundOperationCannotCreateANonceBehindTheBarrier() throws Exception {
        String source = mainActivitySource();
        String operation = section(source,
            "private OperationBindingRules.Binding beginBoundOperation(",
            "private boolean boundOperationMatches(");
        assertTrue(operation.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(operation.indexOf("panelConnectionSyncBlocked()")
            < operation.indexOf("UUID.randomUUID()"));
        assertTrue(operation.indexOf("panelConnectionSyncBlocked()")
            < operation.indexOf("activeOperationNonces.put"));
    }
}
