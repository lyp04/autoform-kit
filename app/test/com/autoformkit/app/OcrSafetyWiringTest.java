package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class OcrSafetyWiringTest {
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

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static int count(String source, String needle) {
        int result = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            result++;
        }
        return result;
    }

    private static void assertBefore(String source, String first, String second) {
        int firstAt = source.indexOf(first);
        int secondAt = source.indexOf(second);
        assertTrue("missing: " + first, firstAt >= 0);
        assertTrue("missing: " + second, secondAt >= 0);
        assertTrue(first + " must precede " + second, firstAt < secondAt);
    }

    @Test
    public void normalOcrPreflightsAndRechecksImmediatelyAroundTheImageSend()
            throws Exception {
        String source = mainActivitySource();
        String recognize = section(source,
            "private void recognizeSnFromPhoto(boolean baseSn, File photoFile, boolean autoCapture,",
            "private void ensureOcrUrlThenRecognize(boolean baseSn, File photoFile)");

        assertBefore(recognize,
            "ensureOcrConfigured(ocrWorkflow, adapterSnapshot)",
            "beginBoundOperation(OperationBindingRules.OCR, tokenSnapshot)");
        assertBefore(recognize,
            "RemoteSideEffectSafetyRules.executeOcr(",
            "apiSnapshot.recognizeText(");
        assertTrue(recognize.contains(
            "phase -> requireBoundOperation("));
    }

    @Test
    public void alternateOcrUsesJointProfileGateAtEntryAndSocketBoundary()
            throws Exception {
        String source = mainActivitySource();
        String recognize = section(source,
            "private void recognizeAlternateEntrySerialFromPhoto(File photoFile,",
            "private void showAlternateEntryOcrCandidates(");

        assertBefore(recognize,
            "RemoteSideEffectSafetyRules.alternateEntryOcrCapabilityErrors(",
            "beginReservedAlternateEntryBoundOperation(");
        assertBefore(recognize,
            "alternateEntryReservationMayMaterializeLocked(reservation)",
            "beginReservedAlternateEntryBoundOperation(");
        assertBefore(recognize,
            "RemoteSideEffectSafetyRules.executeAlternateEntryOcr(",
            "apiSnapshot.recognizeText(");
        assertTrue(recognize.contains(
            "phase -> requireBoundOperation("));
    }

    @Test
    public void remoteOcrEntryAndUserInfoSetupBothFailClosedBeforeWorkStarts()
            throws Exception {
        String source = mainActivitySource();
        String cameraEntry = section(source,
            "private void ensureOcrUrlThenStartCamera(boolean baseSn)",
            "private void startCameraForOcr(boolean baseSn)");
        assertBefore(cameraEntry, "ensureOcrConfigured()", "startCameraForOcr(baseSn)");

        String userInfoSetup = section(source,
            "private void fetchAndBindOcrUrl(String tokenSnapshot, boolean baseSn,",
            "private void handleOcrNoText(boolean baseSn, boolean autoCapture)");
        assertBefore(userInfoSetup,
            "ensureOcrConfigured(ocrWorkflow, adapterSnapshot)",
            "beginBoundOperation(OperationBindingRules.USER_INFO, tokenSnapshot)");
    }

    @Test
    public void everyMainActivityOcrImageSendIsWrappedAndKeepsStageBinding()
            throws Exception {
        String source = mainActivitySource();
        assertEquals(2, count(source, "apiSnapshot.recognizeText("));
        assertEquals(1, count(source,
            "RemoteSideEffectSafetyRules.executeOcr("));
        assertEquals(1, count(source,
            "RemoteSideEffectSafetyRules.executeAlternateEntryOcr("));

        String transport = section(source,
            "JSONObject recognizeText(String recognizeTextUrl, File file,",
            "private <T> T executeOnce(ApiCall<T> call)");
        assertBefore(transport,
            "requireStage(guard, \"OCR request\")",
            "url.openConnection()");
        assertBefore(transport,
            "requireStage(guard, \"OCR request\")",
            "conn.setRequestMethod(\"POST\")");
    }
}
