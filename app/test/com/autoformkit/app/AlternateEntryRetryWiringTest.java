package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the no-reupload/no-unknown-replay boundary in the standalone entry flow. */
public class AlternateEntryRetryWiringTest {
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

    private static int occurrences(String source, String needle) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    @Test
    public void uploadsAndPayloadFreezeBeforeTheOnlyPostRetryLoop() throws Exception {
        String submit = section(mainActivitySource(),
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        int upload = submit.indexOf("uploadAlternateEntryImages(");
        int exactBody = submit.indexOf("byte[] exactRequestBody =");
        int key = submit.indexOf("AlternateSubmissionAttempt.Key attemptKey =");
        int loop = submit.indexOf("for (int postAttempt = 1;");
        assertTrue(upload >= 0 && upload < exactBody);
        assertTrue(exactBody < key && key < loop);
        assertEquals(1, occurrences(submit, "uploadAlternateEntryImages("));

        String retry = submit.substring(loop,
            submit.indexOf("} catch (Exception error) {", loop));
        assertFalse(retry.contains("uploadAlternateEntryImages("));
        assertFalse(retry.contains("AlternateEntryRules.resolve("));
        assertFalse(retry.contains("payload.toString()"));
        assertTrue(retry.contains(
            "BackendAdapter.ENDPOINT_SUBMIT_ENTRY, exactRequestBody"));
        assertTrue(retry.contains("postAttempt < resolution.submissionRetry.maxAttempts"));
        assertTrue(retry.contains("attempt = rejected;"));
    }

    @Test
    public void onlyExplicitRetryableRejectionContinuesAndUnknownResultsLock() throws Exception {
        String submit = section(mainActivitySource(),
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        int loop = submit.indexOf("for (int postAttempt = 1;");
        String retry = submit.substring(loop,
            submit.indexOf("} catch (Exception error) {", loop));

        assertTrue(retry.contains("boolean retryableRejection ="));
        assertTrue(retry.contains("if (retryableRejection"));
        assertTrue(retry.contains("posting.markServerRejected(attemptKey)"));
        assertTrue(retry.contains("posting.markUncertain(attemptKey)"));
        assertTrue(retry.indexOf("posting.markUncertain(attemptKey)")
            < retry.indexOf("throw new IOException(serial"));
        assertFalse(retry.contains("isTransientApiNetworkError"));
        assertFalse(retry.contains("runWithSubmissionNetworkRetry"));
    }

    @Test
    public void explicitRejectionExhaustionKeepsUploadBarrierUntilSuccess() throws Exception {
        String submit = section(mainActivitySource(),
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        int loop = submit.indexOf("for (int postAttempt = 1;");
        int outerCatch = submit.indexOf("} catch (Exception error) {", loop);
        int finalErrorBranch = submit.indexOf("if (finalError != null) {", outerCatch);
        int uploadBarrierClear = submit.indexOf(
            "clearUploadReplayBarrier(uploadIdentity)", finalErrorBranch);

        assertTrue(loop >= 0 && loop < outerCatch);
        assertTrue(outerCatch < finalErrorBranch && finalErrorBranch < uploadBarrierClear);
        assertTrue(submit.substring(loop, outerCatch)
            .contains("clearAlternateSubmissionAttempt()"));
        assertFalse(submit.substring(loop, outerCatch)
            .contains("clearUploadReplayBarrier("));
        assertTrue(submit.substring(outerCatch, finalErrorBranch)
            .contains("uploadLockedResult = hasStoredUploadReplayBarrier()"));
        assertEquals(1, occurrences(submit,
            "clearUploadReplayBarrier(uploadIdentity)"));
    }
}
