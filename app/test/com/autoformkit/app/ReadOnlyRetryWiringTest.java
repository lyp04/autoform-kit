package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard that keeps compatibility retries away from every remote side effect. */
public class ReadOnlyRetryWiringTest {
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
    public void onlyGetUsesTheBoundedReadRetryHelper() throws Exception {
        String source = mainActivitySource();
        assertEquals(2, occurrences(source, "executeReadOnlyWithTransientRetry("));

        String get = section(source,
            "JSONObject getJson(String path, String query, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs)",
            "JSONObject postJson(String path, JSONObject payload)");
        assertTrue(get.contains("return executeReadOnlyWithTransientRetry(() ->"));
        assertTrue(get.contains("conn.setRequestMethod(\"GET\")"));

        String sideEffects = section(source,
            "JSONObject postJson(String path, JSONObject payload)",
            "private <T> T executeReadOnlyWithTransientRetry(ApiCall<T> call)");
        assertFalse(sideEffects.contains("executeReadOnlyWithTransientRetry("));
        assertTrue(sideEffects.contains("conn.setRequestMethod(\"POST\")"));
        assertTrue(sideEffects.contains("String uploadImage(File file, String uploadName)"));
        assertTrue(sideEffects.contains("JSONObject recognizeText(String recognizeTextUrl, File file,"));
    }

    @Test
    public void everyReadAttemptRechecksTheRemoteGateAndTheRetryIsFinite() throws Exception {
        String helper = section(mainActivitySource(),
            "private <T> T executeReadOnlyWithTransientRetry(ApiCall<T> call)",
            "private <T> T executeOnce(ApiCall<T> call)");
        assertTrue(helper.contains("return executeOnce(call);"));
        assertTrue(helper.contains("ReadOnlyRetryRules.shouldRetry("));
        assertTrue(helper.contains("ReadOnlyRetryRules.RETRY_DELAY_MS"));
        assertFalse(helper.contains("while (operationsAllowedNow())"));
    }
}
