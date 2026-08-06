package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for discrete scanner-length policy propagation into both Android UIs. */
public class AllowedLengthsWiringTest {
    private static String source(String file) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/" + file),
            cwd.resolve("src/com/autoformkit/app/" + file)
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(file + " not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    @Test
    public void mainActivityUsesTheExactSourceScopeForEntryOcrAndBatchErrors()
            throws Exception {
        String main = source("MainActivity.java");
        String validation = section(main,
            "private boolean validateIdentifierValue(String value, boolean secondary, String source,",
            "private static boolean isIdentifierValueSource(String source)");
        assertTrue(validation.contains("policy.requiredLengthsForSource(source)"));
        assertTrue(validation.contains(
            "identifierLengthMessage(secondary, required, value.length())"));

        String batch = section(main,
            "private List<String> validateBatch(String token)",
            "private PhotoStep nextPhotoStep()");
        assertTrue(batch.contains("unit.sn.length(), unit.snSource"));
        assertTrue(batch.contains("unit.baseSn.length(), unit.baseSnSource"));
        assertTrue(batch.contains("policy.requiredLengthsForSource(source)"));

        String alternateOcr = section(main,
            "private void showAlternateEntryOcrCandidates(",
            "private void captureAlternateEntryPhoto()");
        assertTrue(alternateOcr.contains(
            "policy.requiredLengthsForSource(\n                SnScanRules.SOURCE_OCR)"));
        assertTrue(alternateOcr.contains(
            "identifierExpectedOnlyMessage(false, required)"));
    }

    @Test
    public void legacyExpectedLengthFallbackRemainsAlongsideAllowedLengths()
            throws Exception {
        String main = source("MainActivity.java");
        String effective = section(main,
            "private JSONObject effectiveScannerConfig(JSONObject sourceProfile, boolean secondary)",
            "private JSONObject invalidScannerConfig()");
        assertTrue(effective.contains("!configured.has(\"expectedLength\")"));
        assertFalse(effective.contains("!configured.has(\"allowedLengths\")"));
        assertTrue(effective.contains(
            "configured.put(\"expectedLength\", sourceProfile.opt(\"expectedSnLength\"))"));
    }

    @Test
    public void scannerScoresRejectsAndPromptsWithDiscreteLengths()
            throws Exception {
        String scanner = source("ScannerActivity.java");
        String barcode = section(scanner,
            "private String barcodeResult(",
            "private String textResult(");
        assertTrue(barcode.contains(
            "scannerPolicy.requiredLengthsForSource(\n                            SnScanRules.SOURCE_BARCODE)"));
        assertTrue(barcode.contains(
            "scannerPolicy.matchesConfiguredLength("));
        assertTrue(barcode.contains(
            "ignoredWrongLengthSource = SnScanRules.SOURCE_BARCODE"));

        String status = section(scanner,
            "private String statusMessage()",
            "private String displayIdentifierLabel()");
        assertTrue(status.contains("wrongLengthStatus(label, required)"));
        assertTrue(status.contains("withAllowedLengthHint("));
        assertTrue(status.contains("SnScanRules.formatLengths(lengths,"));
        assertFalse(status.contains("2352"));
    }
}
