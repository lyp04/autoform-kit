package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Lifecycle wiring guard for the automatic update check's Panel-readiness retry. */
public class AutoUpdateReadinessWiringTest {
    @Test
    public void incompleteStartupProbeDoesNotConsumeAutomaticCheckOrThrottle() throws Exception {
        String update = source("app/src/com/autoformkit/app/UpdateManager.java",
            "src/com/autoformkit/app/UpdateManager.java");
        String check = between(update, "private void check(boolean force)",
            "private boolean checkIdentityStillCurrent(");

        assertBefore(check, "config = loadConfig()", "if (!config.panelReady) return");
        assertBefore(check, "if (!config.panelReady) return",
            "putLong(PREF_LAST_CHECK_MS");
        assertBefore(check, "if (!config.panelReady) return",
            "nextOperationGeneration()");
        assertTrue(check.contains("retryAfterPanelReady && !configurationStillCurrent"));
    }

    @Test
    public void readyPanelPairTriggersTheDeferredAutomaticCheck() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String finished = between(main, "private void handlePanelRefreshFinished(",
            "private void schedulePanelPairRetry(");

        assertBefore(finished, "after.mode == PanelBootstrapRules.Mode.READY",
            "updateManager.checkAfterPanelReady()");
        assertBefore(finished, "activePair != null && updateManager != null",
            "updateManager.checkAfterPanelReady()");
    }

    private static String source(String repositoryPath, String modulePath) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{cwd.resolve(repositoryPath), cwd.resolve(modulePath)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + repositoryPath);
    }

    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) throw new AssertionError("source markers not found");
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("missing marker: " + first, firstIndex >= 0);
        assertTrue("missing marker: " + second, secondIndex >= 0);
        assertTrue("expected ordering", firstIndex < secondIndex);
    }
}
