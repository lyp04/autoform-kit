package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class SnScanRulesTest {
    private static JSONObject orderedReplayPolicy() throws Exception {
        return new JSONObject()
            .put("expectedLength", 10)
            .put("preferredPrefixes", new JSONArray().put("ZX"))
            .put("autoTextMode", "fallback")
            .put("rejectNumericOnly", true)
            .put("candidateMode", "ordered")
            .put("candidateOrder", new JSONArray()
                .put("label").put("prefix").put("general"))
            .put("minLength", 8)
            .put("maxLength", 12)
            .put("requireLetterAndDigit", true)
            .put("rejectedSubstrings", new JSONArray().put("IGNORE"))
            .put("stripLabels", new JSONArray().put("S/N"))
            .put("labelMatchMode", "compact_optional_slash")
            .put("candidateCharacterMode", "alphanumeric")
            .put("applyCandidateRulesTo", new JSONArray().put("ocr"))
            .put("caseMode", "upper")
            .put("removeWhitespace", true);
    }

    @Test
    public void orderedReplayPrefersLabelThenPrefixThenGeneral() throws Exception {
        SnScanRules.Policy policy = SnScanRules.Policy.from(orderedReplayPolicy());
        assertTrue(policy.valid);

        String selected = SnScanRules.selectTextCandidate(Arrays.asList(
            "ZX9A123456",
            "S/N: Q7B1234567",
            "R8C1234567"
        ), policy);
        assertEquals("Q7B1234567", selected);

        String prefixFallback = SnScanRules.selectTextCandidate(Arrays.asList(
            "S/N: Q7IGNORE1",
            "ZX9A123456",
            "R8C1234567"
        ), policy);
        assertEquals("ZX9A123456", prefixFallback);
        assertEquals("Q7B1234567",
            policy.normalizeForSource(" S / N： q7 b1234567 ", "ocr"));
        assertEquals("Q7B1234567", SnScanRules.selectTextCandidate(
            Arrays.asList("S   N-Q7B1234567", "ZX9A123456"), policy));
    }

    @Test
    public void rankedReplayUsesLegacySourceBonusesWithoutDeploymentConstants() throws Exception {
        JSONObject configured = orderedReplayPolicy().put("candidateMode", "ranked");
        SnScanRules.Policy policy = SnScanRules.Policy.from(configured);

        // The generic replay mirrors the established score formula: label +80, prefix +58,
        // general +0, followed by length/prefix/digit bonuses. No deployment prefix is in source.
        assertEquals("ZX9A123456", SnScanRules.selectTextCandidate(Arrays.asList(
            "S/N: Q7B1234567",
            "ZX9A123456"
        ), policy));
        assertEquals("ZX8B123456", SnScanRules.selectTextCandidate(Arrays.asList(
            "S/N: ZX8B123456",
            "ZX9A123456"
        ), policy));
    }

    @Test
    public void numericAndMixedCharacterRulesApplyToCaptureAndManualEntry() throws Exception {
        SnScanRules.Policy policy = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("rejectNumericOnly", true)
            .put("requireLetterAndDigit", true)
            .put("applyCandidateRulesTo", new JSONArray()
                .put("ocr").put("barcode").put("entered")));

        assertEquals(SnScanRules.Rejection.NUMERIC_ONLY,
            policy.enteredRejection("12345678"));
        assertEquals(SnScanRules.Rejection.MISSING_LETTER_OR_DIGIT,
            policy.enteredRejection("ABCDEFGH"));
        assertTrue(policy.acceptsEntered("AB123456"));
        assertTrue(policy.acceptsCapture("AB123456"));
    }

    @Test
    public void candidateOnlyRulesCanReplayOcrWithoutTighteningBarcodeOrEnteredValues() throws Exception {
        SnScanRules.Policy policy = SnScanRules.Policy.from(new JSONObject()
            .put("minLength", 8)
            .put("maxLength", 32)
            .put("requireLetterAndDigit", true)
            .put("rejectedSubstrings", new JSONArray().put("IGNORE"))
            .put("stripLabels", new JSONArray().put("S/N"))
            .put("applyCandidateRulesTo", new JSONArray().put("ocr"))
            .put("stripLabelsFrom", new JSONArray().put("ocr")));

        assertEquals(SnScanRules.Rejection.TOO_SHORT,
            policy.captureRejection("AB12"));
        assertEquals(SnScanRules.Rejection.NONE,
            policy.barcodeRejection("AB12"));
        assertEquals(SnScanRules.Rejection.NONE,
            policy.enteredRejection("AB12"));
        assertEquals("AB123456", policy.normalizeForSource("S/N: AB123456", "ocr"));
        assertEquals("S/N:AB123456", policy.normalizeForSource("S/N: AB123456", "entered"));
    }

    @Test
    public void alwaysAndFallbackModesKeepDistinctOcrTiming() throws Exception {
        SnScanRules.Policy always = SnScanRules.Policy.from(
            new JSONObject().put("autoTextMode", "always"));
        SnScanRules.Policy fallback = SnScanRules.Policy.from(
            new JSONObject().put("autoTextMode", "fallback"));
        SnScanRules.Policy disabled = SnScanRules.Policy.from(new JSONObject());

        assertTrue(SnScanRules.shouldReadText(always, false, false, 400, 650));
        assertFalse(SnScanRules.shouldReadText(fallback, false, false, 400, 650));
        assertTrue(SnScanRules.shouldReadText(fallback, false, false, 1300, 650));
        assertFalse(SnScanRules.shouldReadText(disabled, false, false, 5000, 5000));
        assertTrue(SnScanRules.shouldReadText(disabled, false, true, 0, 0));
    }

    @Test
    public void primaryAndSecondaryLengthsRemainIndependent() throws Exception {
        SnScanRules.Policy primary = SnScanRules.Policy.from(
            new JSONObject().put("expectedLength", 10));
        SnScanRules.Policy secondary = SnScanRules.Policy.from(
            new JSONObject().put("expectedLength", 8));

        assertTrue(primary.acceptsEntered("AB12345678"));
        assertFalse(primary.acceptsEntered("CD123456"));
        assertTrue(secondary.acceptsEntered("CD123456"));
        assertFalse(secondary.acceptsEntered("AB12345678"));
    }

    @Test
    public void expectedLengthCanApplyToCameraSourcesWithoutChangingTypedEntry() throws Exception {
        SnScanRules.Policy migratedIndependentEntry = SnScanRules.Policy.from(
            new JSONObject()
                .put("expectedLength", 10)
                .put("applyExpectedLengthTo", new JSONArray()
                    .put("ocr").put("barcode")));

        assertTrue(migratedIndependentEntry.valid);
        assertFalse(migratedIndependentEntry.appliesExpectedLengthTo("entered"));
        assertTrue(migratedIndependentEntry.appliesExpectedLengthTo("barcode"));
        assertTrue(migratedIndependentEntry.appliesExpectedLengthTo("ocr"));
        assertEquals(SnScanRules.Rejection.NONE,
            migratedIndependentEntry.enteredRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            migratedIndependentEntry.barcodeRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            migratedIndependentEntry.captureRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.NONE,
            migratedIndependentEntry.barcodeRejection("AB12345678"));
        assertEquals(SnScanRules.Rejection.NONE,
            migratedIndependentEntry.captureRejection("AB12345678"));
    }

    @Test
    public void eachExpectedLengthSourceCanBeSelectedIndependently() throws Exception {
        SnScanRules.Policy enteredOnly = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 10)
            .put("applyExpectedLengthTo", new JSONArray().put("entered")));
        SnScanRules.Policy barcodeOnly = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 10)
            .put("applyExpectedLengthTo", new JSONArray().put("barcode")));
        SnScanRules.Policy ocrOnly = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 10)
            .put("applyExpectedLengthTo", new JSONArray().put("ocr")));

        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            enteredOnly.enteredRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.NONE,
            enteredOnly.barcodeRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.NONE,
            enteredOnly.captureRejection("AB123456"));

        assertEquals(SnScanRules.Rejection.NONE,
            barcodeOnly.enteredRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            barcodeOnly.barcodeRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.NONE,
            barcodeOnly.captureRejection("AB123456"));

        assertEquals(SnScanRules.Rejection.NONE,
            ocrOnly.enteredRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.NONE,
            ocrOnly.barcodeRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            ocrOnly.captureRejection("AB123456"));
    }

    @Test
    public void missingExpectedLengthScopePreservesEstablishedAllSourceBehavior() throws Exception {
        SnScanRules.Policy legacy = SnScanRules.Policy.from(
            new JSONObject().put("expectedLength", 8));

        assertTrue(legacy.valid);
        assertTrue(legacy.appliesExpectedLengthTo("entered"));
        assertTrue(legacy.appliesExpectedLengthTo("barcode"));
        assertTrue(legacy.appliesExpectedLengthTo("ocr"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            legacy.enteredRejection("AB1234"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            legacy.barcodeRejection("AB1234"));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            legacy.captureRejection("AB1234"));
        assertEquals("AB123456", SnScanRules.selectTextCandidate(
            Arrays.asList("AB123456"), legacy));
        assertEquals("", SnScanRules.selectTextCandidate(
            Arrays.asList("AB1234567"), legacy));
    }

    @Test
    public void malformedOrMeaninglessExpectedLengthScopesFailClosed() throws Exception {
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("applyExpectedLengthTo", new JSONArray())).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("applyExpectedLengthTo", new JSONArray().put("camera"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("applyExpectedLengthTo", new JSONArray().put("ocr").put("OCR"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("applyExpectedLengthTo", new JSONArray().put(" ocr"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 8)
            .put("applyExpectedLengthTo", "ocr")).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("applyExpectedLengthTo", new JSONArray().put("ocr"))).valid);
    }

    @Test
    public void allowedLengthsDefaultToAllInputsAndAcceptOnlyConfiguredValues()
            throws Exception {
        SnScanRules.Policy policy = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 17)
            .put("allowedLengths", new JSONArray().put(16).put(17)));

        assertTrue(policy.valid);
        assertEquals(Arrays.asList(16, 17), policy.allowedLengths);
        for (String source : new String[]{SnScanRules.SOURCE_OCR,
                SnScanRules.SOURCE_BARCODE, SnScanRules.SOURCE_ENTERED}) {
            assertTrue(policy.appliesAllowedLengthsTo(source));
            assertEquals(Arrays.asList(16, 17), policy.requiredLengthsForSource(source));
            assertEquals(SnScanRules.Rejection.NONE,
                policy.rejectionForSource(repeat('A', 16), source));
            assertEquals(SnScanRules.Rejection.NONE,
                policy.rejectionForSource(repeat('A', 17), source));
            assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
                policy.rejectionForSource(repeat('A', 15), source));
        }
        assertEquals("16 或 17",
            SnScanRules.formatLengths(policy.allowedLengths, "或"));
        assertEquals("16 or 17",
            SnScanRules.formatLengths(policy.allowedLengths, "or"));
        assertEquals(repeat('A', 16), SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 16)), policy));
        assertEquals(repeat('A', 17), SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 17)), policy));
        assertEquals("", SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 15)), policy));
        assertEquals("", SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 18)), policy));
    }

    @Test
    public void allowedLengthsOverrideExpectedLengthOnlyForTheirOwnSources()
            throws Exception {
        SnScanRules.Policy migration = SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 17)
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr").put("barcode")));

        assertTrue(migration.valid);
        assertTrue(migration.appliesAllowedLengthsTo("ocr"));
        assertTrue(migration.appliesAllowedLengthsTo("barcode"));
        assertFalse(migration.appliesAllowedLengthsTo("entered"));
        assertFalse(migration.appliesExpectedLengthTo("ocr"));
        assertFalse(migration.appliesExpectedLengthTo("barcode"));
        assertTrue(migration.appliesExpectedLengthTo("entered"));
        assertEquals(SnScanRules.Rejection.NONE,
            migration.captureRejection(repeat('A', 16)));
        assertEquals(SnScanRules.Rejection.NONE,
            migration.barcodeRejection(repeat('A', 16)));
        assertEquals(SnScanRules.Rejection.WRONG_LENGTH,
            migration.enteredRejection(repeat('A', 16)));
        assertEquals(SnScanRules.Rejection.NONE,
            migration.enteredRejection(repeat('A', 17)));
    }

    @Test
    public void allowedLengthsAndScopesAreStrictAndFallbackMustBeContained()
            throws Exception {
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray())).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(16))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16.0d))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(0))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(257))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", "16,17")).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("applyAllowedLengthsTo", new JSONArray())).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr").put("ocr"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("applyAllowedLengthsTo", new JSONArray().put("camera"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("applyAllowedLengthsTo", new JSONArray().put(" ocr"))).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("expectedLength", 15)
            .put("allowedLengths", new JSONArray().put(16).put(17))).valid);
    }

    @Test
    public void allowedLengthsRespectOnlyExplicitBoundsAndSupportOneTo256()
            throws Exception {
        SnScanRules.Policy implicitBounds = SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(1).put(256)));
        assertTrue(implicitBounds.valid);
        assertEquals(SnScanRules.Rejection.NONE,
            implicitBounds.captureRejection(repeat('A', 1)));
        assertEquals(SnScanRules.Rejection.NONE,
            implicitBounds.captureRejection(repeat('A', 256)));

        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("minLength", 17)).valid);
        assertFalse(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("maxLength", 16)).valid);
        assertTrue(SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(16).put(17))
            .put("minLength", 16)
            .put("maxLength", 17)).valid);
    }

    @Test
    public void ocrTokensRequireBoundariesAndExpandOnlyForConfiguredAllowedLengths()
            throws Exception {
        String sixtyFive = repeat('A', 65);
        SnScanRules.Policy legacy = SnScanRules.Policy.from(new JSONObject());
        assertEquals("",
            SnScanRules.selectTextCandidate(Arrays.asList(sixtyFive), legacy));

        SnScanRules.Policy expanded = SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(65))
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr")));
        assertTrue(expanded.valid);
        assertEquals(sixtyFive,
            SnScanRules.selectTextCandidate(Arrays.asList(sixtyFive), expanded));
        assertEquals("", SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 66)), expanded));
        assertEquals(sixtyFive, SnScanRules.selectTextCandidate(
            Arrays.asList("#" + sixtyFive + "#"), expanded));

        SnScanRules.Policy barcodeOnly = SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(65))
            .put("applyAllowedLengthsTo", new JSONArray().put("barcode")));
        assertTrue(barcodeOnly.valid);
        assertEquals("",
            SnScanRules.selectTextCandidate(Arrays.asList(sixtyFive), barcodeOnly));

        SnScanRules.Policy prefixOnly = SnScanRules.Policy.from(new JSONObject()
            .put("allowedLengths", new JSONArray().put(65))
            .put("applyAllowedLengthsTo", new JSONArray().put("ocr"))
            .put("preferredPrefixes", new JSONArray().put("AA"))
            .put("candidateOrder", new JSONArray().put("prefix")));
        assertTrue(prefixOnly.valid);
        assertEquals("", SnScanRules.selectTextCandidate(
            Arrays.asList(repeat('A', 66)), prefixOnly));
        assertEquals(sixtyFive, SnScanRules.selectTextCandidate(
            Arrays.asList(":" + sixtyFive + ":"), prefixOnly));
    }

    @Test
    public void configuredBarcodeLabelIsStrippedWithoutABuiltInLabel() throws Exception {
        SnScanRules.Policy configured = SnScanRules.Policy.from(new JSONObject()
            .put("stripLabels", new JSONArray().put("SN:"))
            .put("stripLabelsFrom", new JSONArray().put("barcode")));

        assertTrue(configured.valid);
        assertEquals("AB123456",
            configured.normalizeForSource(" sn: ab 123456 ", "barcode"));
        assertEquals("SN:AB123456",
            configured.normalizeForSource(" sn: ab 123456 ", "entered"));
        assertEquals("SN:AB123456",
            configured.normalizeForSource(" sn: ab 123456 ", "ocr"));
        assertEquals("SN-AB123456",
            configured.normalizeForSource(" sn-ab 123456 ", "barcode"));

        SnScanRules.Policy noConfiguredLabel = SnScanRules.Policy.from(new JSONObject());
        assertEquals("SN:AB123456",
            noConfiguredLabel.normalizeForSource(" sn: ab 123456 ", "barcode"));
    }

    @Test
    public void malformedBarcodeLabelScopeFailsClosed() throws Exception {
        SnScanRules.Policy unknownSource = SnScanRules.Policy.from(new JSONObject()
            .put("stripLabels", new JSONArray().put("SN:"))
            .put("stripLabelsFrom", new JSONArray().put("camera")));
        SnScanRules.Policy wrongType = SnScanRules.Policy.from(new JSONObject()
            .put("stripLabels", new JSONArray().put("SN:"))
            .put("stripLabelsFrom", "barcode"));
        SnScanRules.Policy missingLabel = SnScanRules.Policy.from(new JSONObject()
            .put("stripLabelsFrom", new JSONArray().put("barcode")));

        assertFalse(unknownSource.valid);
        assertFalse(wrongType.valid);
        assertFalse(missingLabel.valid);
        assertEquals(SnScanRules.Rejection.INVALID_POLICY,
            unknownSource.barcodeRejection("AB123456"));
        assertEquals(SnScanRules.Rejection.INVALID_POLICY,
            SnScanRules.Policy.from(new JSONObject())
                .rejectionForSource("AB123456", "camera"));
    }

    @Test
    public void absentPolicyPreservesGenericDefaultsButMalformedPolicyFailsClosed() throws Exception {
        SnScanRules.Policy absentLegacyPolicy = SnScanRules.Policy.from(new JSONObject());
        assertTrue(absentLegacyPolicy.valid);
        assertEquals("AB123456", absentLegacyPolicy.normalize(" ab 123456 "));
        assertTrue(absentLegacyPolicy.acceptsEntered("1"));
        assertFalse(SnScanRules.shouldReadText(
            absentLegacyPolicy, false, false, 5000, 5000));

        SnScanRules.Policy malformed = SnScanRules.Policy.from(
            new JSONObject().put("expectedLength", "eight"));
        assertFalse(malformed.valid);
        assertEquals(SnScanRules.Rejection.INVALID_POLICY,
            malformed.enteredRejection("AB123456"));
        assertFalse(malformed.acceptsCapture("AB123456"));
    }

    @Test
    public void explicitScanSwitchControlsTheCameraRouteFailSafely() throws Exception {
        assertTrue(SnScanRules.cameraScanEnabled(null));
        assertTrue(SnScanRules.cameraScanEnabled(new JSONObject()));
        assertTrue(SnScanRules.cameraScanEnabled(new JSONObject().put("scan", true)));
        assertFalse(SnScanRules.cameraScanEnabled(new JSONObject().put("scan", false)));
        assertFalse(SnScanRules.cameraScanEnabled(new JSONObject().put("scan", "true")));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
