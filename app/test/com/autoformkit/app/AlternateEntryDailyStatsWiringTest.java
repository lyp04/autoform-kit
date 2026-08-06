package com.autoformkit.app;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard that an acknowledged independent entry reaches the home-page counters. */
public class AlternateEntryDailyStatsWiringTest {
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
    public void acknowledgedAndRecoveredEntriesAttemptDurableCountingBeforeDraftCleanup()
            throws Exception {
        String source = mainActivitySource();
        String finalize = section(source,
            "private boolean finalizeCompletedAlternateSubmissionLocked(",
            "private void resetAlternateEntryTogglesAfterSubmit()");
        int record = finalize.indexOf(
            "recordCompletedAlternateDailyOutput(expected, completedDraft)");
        int cleanup = finalize.indexOf(".remove(draftKey)", record);
        assertTrue("daily stats must be durable before the source draft is deleted", record >= 0);
        assertTrue("draft cleanup must follow the shared counter path", cleanup > record);
        assertFalse("an auxiliary stats failure must not block acknowledged draft cleanup",
            finalize.contains(
                "if (!recordCompletedAlternateDailyOutput(expected, completedDraft))"));

        String submit = section(source,
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        assertTrue(submit.contains(
            "finalizeCompletedAlternateSubmission(finalCompletedAttempt)"));

        String recovery = section(source,
            "private void showAlternateSubmissionBlock(",
            "private boolean finalizeCompletedAlternateSubmission(");
        assertTrue(recovery.contains(
            "finalizeCompletedAlternateSubmission(completed)"));
    }

    @Test
    public void completedTombstoneBackfillsByUniqueCurrentSourceEntryBinding()
            throws Exception {
        String source = mainActivitySource();
        String resolver = section(source,
            "private AlternateDailyStatsIdentity completedAlternateDailyStatsIdentity(",
            "private boolean recordCompletedAlternateDailyOutput(");
        assertTrue(resolver.contains("profileIndex < allProfiles.length()"));
        assertTrue(resolver.contains(
            "binding.equals(completed.key.bindingFingerprint)"));
        assertTrue(resolver.contains(
            "completed.key.target.profileId.equals("));
        assertTrue(resolver.contains("return matches == 1 ? match : null"));

        String blocking = section(source,
            "private AlternateSubmissionAttempt.RestoreResult blockingAlternateSubmissionAttempt()",
            "private void showAlternateSubmissionBlock(");
        int record = blocking.indexOf(
            "recordCompletedAlternateDailyOutput(result.attempt, null)");
        int clear = blocking.indexOf("clearAlternateSubmissionAttempt()", record);
        assertTrue("a draft-less COMPLETED tombstone must attempt backfill", record >= 0);
        assertTrue("a successful idempotent backfill may clear its tombstone", clear > record);
        assertTrue("a failed stats write must retain the tombstone without blocking production",
            blocking.contains("if (!statsRecorded)")
                && blocking.contains("return null;"));
        assertFalse("stats failure must not return the completed attempt as a submission block",
            blocking.contains(
                "if (!recordCompletedAlternateDailyOutput(result.attempt, null))"));
        assertTrue(source.contains("backfillCompletedAlternateDailyOutputAtStartup();"));
    }

    @Test
    public void independentEntriesUseTheirOwnSourceEntryCountersAndDurableCommit()
            throws Exception {
        String record = section(mainActivitySource(),
            "private boolean recordDailyAlternateOutput(",
            "private JSONObject loadDailyStats(");
        assertTrue(record.contains("DailyStatsRules.recordAlternateEntry("));
        assertTrue(record.contains("dailyAlternateStatsPreferenceKey(date)"));
        assertTrue(record.contains(".commit()"));
        assertFalse(record.contains("putMirroredRollbackPreference("));
        assertFalse(record.contains("String legacyKey = DAILY_STATS_PREFIX"));
        assertFalse(record.contains("resultKey"));

        String source = mainActivitySource();
        assertTrue(source.contains(
            "\"alternate_daily_stats_v1_\""));
        assertFalse(source.contains(
            "ALTERNATE_DAILY_STATS_PREFIX = \"daily_stats_"));
        String key = section(source,
            "private String dailyAlternateStatsPreferenceKey(",
            "private JSONObject loadDailyAlternateStats(");
        assertTrue("the private counter store must follow the exact Panel connection namespace",
            key.contains("return panelStatePreferenceKey(ALTERNATE_DAILY_STATS_PREFIX + date)"));
        assertFalse("the private counter store must never alias the rollback mirror",
            key.contains("panelStatePreferenceKey(DAILY_STATS_PREFIX + date)"));
    }
}
