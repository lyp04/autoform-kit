package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the Activity boundaries around the pure profile isolation rules. */
public class ProfilePayloadIsolationWiringTest {
    private static String source() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
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
    public void finalPayloadIteratesPanelAllowListInsteadOfDraftKeys()
            throws Exception {
        String buildPayload = section(source(),
            "private JSONObject buildPayload(BackendAdapter adapter",
            "private JSONObject submitEnvelope");
        assertTrue(buildPayload.contains(
            "ProfileFieldRules.boundVisibleExtraIdentifierValues("));
        assertTrue(buildPayload.contains(
            "ProfileFieldRules.resultMapping(profile, unit.grade)"));
        assertFalse(buildPayload.contains("unit.pluginSns.entrySet()"));
    }

    @Test
    public void preflightRejectsStaleResultIdentifierPhotoAndArtifactMappings()
            throws Exception {
        String validation = section(source(),
            "private List<String> validateBatch(String token)",
            "private String identifierPolicyErrorText");
        for (String marker : new String[]{
            "ProfileFieldRules.resultSelectionValid(profile, unit.grade)",
            "ProfileFieldRules.unexpectedExtraIdentifierFields(",
            "ProfileFieldRules.unexpectedPhotoSlotFields(",
            "workflowArtifactFields.contains(field)",
            "count > max",
            "!unit.slotPhotos.isEmpty()"
        }) {
            assertTrue(marker, validation.contains(marker));
        }
    }

    @Test
    public void remoteBindingAlsoChecksTheRuntimeProfileAgainstCatalog()
            throws Exception {
        String binding = section(source(),
            "private boolean mainDraftSubmissionAllowed(",
            "private void requireMainDraftRemoteBinding(");
        assertTrue(binding.contains(
            "MainDraftSnapshotRules.runtimeProfileMatchesCatalog("));
        assertTrue(binding.indexOf("runtimeProfileMatchesCatalog(")
            < binding.indexOf("expected.sameAs(current)"));
    }

    @Test
    public void submissionWorkerLeaseCoversEveryPreflightEarlyReturn()
            throws Exception {
        String submit = section(source(),
            "private void submitBatch()",
            "private boolean hasRemainingSubmittableUnit(");
        assertTrue(submit.contains("new Thread(() -> {\n            try {"));
        assertEquals(1, occurrences(submit, "submitWorkerLease.close()"));
        int firstEarlyReturn = submit.indexOf(
            "if (!mainDraftSubmissionAllowed(submittedDraftBinding))");
        int close = submit.indexOf("submitWorkerLease.close()");
        assertTrue(firstEarlyReturn >= 0);
        assertTrue(close > firstEarlyReturn);
        assertTrue(submit.substring(Math.max(0, close - 320), close)
            .contains("} finally {"));
    }

    private static int occurrences(String text, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
