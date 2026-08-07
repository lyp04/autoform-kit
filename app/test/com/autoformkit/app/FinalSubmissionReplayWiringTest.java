package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source wiring guard for the production opt-out from permanent final-POST locks. */
public class FinalSubmissionReplayWiringTest {
    @Test
    public void unconfirmedMainPostDoesNotBlockTheNextOperatorRetry() throws Exception {
        String source = mainActivitySource();
        assertTrue(source.contains(
            "DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED = false"));

        String startup = section(source,
            "protected void onCreate(Bundle savedInstanceState)",
            "public void onWindowFocusChanged(boolean hasFocus)");
        assertTrue(startup.contains("discardDisabledFinalSubmissionReplayBarriers();"));

        String blocking = section(source,
            "private AlternateSubmissionAttempt.RestoreResult blockingMainSubmissionAttempt()",
            "private void showMainSubmissionBlock(");
        assertTrue(blocking.contains(
            "if (!DURABLE_FINAL_SUBMISSION_REPLAY_BARRIER_ENABLED"));
        assertTrue(blocking.contains("discardReplayableFinalSubmissionAttempt(result, true)"));
        assertTrue(blocking.contains("return null;"));

        String post = section(source,
            "private JournaledSubmissionResponse postMainSubmissionOnce(",
            "private void confirmMainSubmissionRejected(");
        assertTrue(post.contains("clearMainSubmissionAttempt();"));
        assertTrue(post.contains("throw transportOrResponseError;"));
        assertTrue(post.indexOf("writeMainSubmissionAttempt(attempt)")
            < post.indexOf("postEndpointJsonExact("));
    }

    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity.java not found");
    }

    private static String section(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) throw new AssertionError("source markers not found");
        return value.substring(start, end);
    }
}
