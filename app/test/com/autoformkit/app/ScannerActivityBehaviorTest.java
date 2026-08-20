package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ScannerActivityBehaviorTest {
    @Test
    public void guideRegionMatchesTheVisibleOverlayAndRejectsOtherLabels() {
        int width = 1000;
        int height = 2000;

        assertTrue(ScannerActivity.textCandidateInsideGuide(
            420f, 960f, 580f, 1080f, width, height));
        assertFalse(ScannerActivity.textCandidateInsideGuide(
            420f, 180f, 580f, 300f, width, height));
        assertFalse(ScannerActivity.textCandidateInsideGuide(
            420f, 1560f, 580f, 1680f, width, height));
    }

    @Test
    public void geometryPrefersTheCenteredStraightLine() {
        int centered = ScannerActivity.textCandidateGeometryScore(
            420f, 960f, 580f, 1080f, 0f, true, 1000, 2000);
        int guideEdge = ScannerActivity.textCandidateGeometryScore(
            70f, 720f, 170f, 840f, 0f, true, 1000, 2000);
        int tiltedCenter = ScannerActivity.textCandidateGeometryScore(
            420f, 960f, 580f, 1080f, 35f, true, 1000, 2000);
        int centeredBlock = ScannerActivity.textCandidateGeometryScore(
            420f, 960f, 580f, 1080f, 0f, false, 1000, 2000);

        assertTrue(centered > guideEdge);
        assertTrue(centered > tiltedCenter);
        assertTrue(centered > centeredBlock);
    }

    @Test
    public void rotatedAnalysisUsesTheRecognizersUprightDimensions() {
        assertEquals(1280, ScannerActivity.orientedImageWidth(1280, 720, 0));
        assertEquals(720, ScannerActivity.orientedImageHeight(1280, 720, 0));
        assertEquals(720, ScannerActivity.orientedImageWidth(1280, 720, 90));
        assertEquals(1280, ScannerActivity.orientedImageHeight(1280, 720, 90));
        assertEquals(720, ScannerActivity.orientedImageWidth(1280, 720, 270));
        assertEquals(1280, ScannerActivity.orientedImageHeight(1280, 720, -90));
    }

    @Test
    public void oneManualRequestSamplesRepeatedlyButStopsAtItsDeadline() {
        long deadline = 10_000L;
        assertTrue(ScannerActivity.manualTextSampleDue(deadline, 7_000L, 0L));
        assertFalse(ScannerActivity.manualTextSampleDue(deadline, 7_300L, 7_000L));
        assertTrue(ScannerActivity.manualTextSampleDue(deadline, 7_500L, 7_000L));
        assertFalse(ScannerActivity.manualTextSampleDue(deadline, 10_001L, 9_500L));
        assertFalse(ScannerActivity.manualTextSampleDue(0L, 7_000L, 0L));
    }

    @Test
    public void scannerWiresGeometryAndAtomicManualSessionIntoRecognition() throws Exception {
        String source = scannerSource();
        assertTrue(source.contains("line.getBoundingBox(), line.getAngle(), true"));
        assertTrue(source.contains("if (!hasGuideCandidate || candidate.inGuide)"));
        assertTrue(source.contains("candidate.score + locatedCandidate.geometryScore"));
        assertTrue(source.contains("new AtomicLong(0L)"));
        assertTrue(source.contains("manualTextSampleDue("));
        assertTrue(source.contains("expireManualTextSession(now);"));
        assertFalse(source.contains("boolean manualTextRequested"));
        assertFalse(source.contains("manualTextRequested = false"));
    }

    private static String scannerSource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/ScannerActivity.java"),
                cwd.resolve("src/com/autoformkit/app/ScannerActivity.java")}) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("ScannerActivity.java not found from " + cwd);
    }
}
