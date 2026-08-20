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
    public void rotatedAnalysisMapsTheImageProxyCropIntoUprightCoordinates() {
        assertEquals(1280, ScannerActivity.orientedImageWidth(1280, 720, 0));
        assertEquals(720, ScannerActivity.orientedImageHeight(1280, 720, 0));
        assertEquals(720, ScannerActivity.orientedImageWidth(1280, 720, 90));
        assertEquals(1280, ScannerActivity.orientedImageHeight(1280, 720, 90));
        assertEquals(720, ScannerActivity.orientedImageWidth(1280, 720, 270));
        assertEquals(1280, ScannerActivity.orientedImageHeight(1280, 720, -90));

        assertCrop(ScannerActivity.uprightFrameGeometry(
            1000, 600, 0, 100f, 50f, 800f, 500f),
            100f, 50f, 800f, 500f);
        assertCrop(ScannerActivity.uprightFrameGeometry(
            1000, 600, 90, 100f, 50f, 800f, 500f),
            100f, 100f, 550f, 800f);
        assertCrop(ScannerActivity.uprightFrameGeometry(
            1000, 600, 180, 100f, 50f, 800f, 500f),
            200f, 100f, 900f, 550f);
        assertCrop(ScannerActivity.uprightFrameGeometry(
            1000, 600, 270, 100f, 50f, 800f, 500f),
            50f, 200f, 500f, 900f);
    }

    @Test
    public void guideChecksUseTheRotatedViewportCropRatherThanTheWholeBuffer() {
        ScannerActivity.UprightFrameGeometry geometry =
            ScannerActivity.uprightFrameGeometry(
                1000, 600, 0, 100f, 50f, 800f, 500f);

        assertTrue(ScannerActivity.textCandidateInsideGuide(
            400f, 240f, 500f, 300f, geometry));
        assertFalse(ScannerActivity.textCandidateInsideGuide(
            400f, 120f, 500f, 180f, geometry));
    }

    @Test
    public void adjacentLineCombinationRequiresBothRealLinesToTouchTheGuide() {
        ScannerActivity.UprightFrameGeometry geometry =
            ScannerActivity.UprightFrameGeometry.fullFrame(1000, 2000);

        assertTrue(ScannerActivity.adjacentLinesEligible(
            380f, 690f, 620f, 750f,
            360f, 760f, 640f, 830f, geometry));
        assertFalse(ScannerActivity.adjacentLinesEligible(
            360f, 160f, 640f, 230f,
            360f, 760f, 640f, 830f, geometry));
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
    public void textCallbacksCannotCrossManualSessionGenerations() {
        assertTrue(ScannerActivity.textRecognitionCallbackCurrent(
            false, false, 7L, 7L, 0L, 8_000L));
        assertFalse(ScannerActivity.textRecognitionCallbackCurrent(
            false, false, 6L, 7L, 0L, 8_000L));
        assertTrue(ScannerActivity.textRecognitionCallbackCurrent(
            false, true, 7L, 7L, 10_000L, 8_000L));
        assertFalse(ScannerActivity.textRecognitionCallbackCurrent(
            false, true, 7L, 7L, 7_999L, 8_000L));
        assertFalse(ScannerActivity.textRecognitionCallbackCurrent(
            true, false, 7L, 7L, 0L, 8_000L));
    }

    @Test
    public void scannerWiresGeometryAndAtomicManualSessionIntoRecognition() throws Exception {
        String source = scannerSource();
        assertTrue(source.contains("line.getBoundingBox(), line.getAngle(), true"));
        assertTrue(source.contains("if (!hasGuideCandidate || candidate.inGuide)"));
        assertTrue(source.contains("candidate.score + locatedCandidate.geometryScore"));
        assertTrue(source.contains("new UseCaseGroup.Builder()"));
        assertTrue(source.contains(".setViewPort(viewPort)"));
        assertTrue(source.contains("imageProxy.getCropRect()"));
        assertTrue(source.contains("addAdjacentLineCandidates("));
        assertFalse(source.contains("block.getText()"));
        assertTrue(source.contains("new AtomicLong(0L)"));
        assertTrue(source.contains("manualTextGeneration.incrementAndGet()"));
        assertTrue(source.contains("recognitionGeneration"));
        assertTrue(source.contains("manualTextSampleDue("));
        assertTrue(source.contains("expireManualTextSession(now);"));
        assertFalse(source.contains("boolean manualTextRequested"));
        assertFalse(source.contains("manualTextRequested = false"));
    }

    private static void assertCrop(ScannerActivity.UprightFrameGeometry geometry,
                                   float left, float top, float right, float bottom) {
        assertTrue(geometry.valid);
        assertEquals(left, geometry.cropLeft, 0.001f);
        assertEquals(top, geometry.cropTop, 0.001f);
        assertEquals(right, geometry.cropRight, 0.001f);
        assertEquals(bottom, geometry.cropBottom, 0.001f);
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
