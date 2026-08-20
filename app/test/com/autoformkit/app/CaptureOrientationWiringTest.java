package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the fixed camera window, rotating chrome, and WYSIWYG viewport. */
public class CaptureOrientationWiringTest {
    @Test
    public void captureWindowLocksAtEntryInsteadOfPlayingSystemRotationAnimations()
            throws Exception {
        String manifest = source("app/AndroidManifest.xml", "AndroidManifest.xml");
        String declaration = section(manifest,
            "android:name=\".CaptureActivity\"", "/>" );

        assertTrue(declaration.contains("android:screenOrientation=\"locked\""));
        assertFalse(declaration.contains("android:screenOrientation=\"fullSensor\""));
        assertFalse(declaration.contains("android:screenOrientation=\"portrait\""));
        assertTrue(declaration.contains(
            "android:configChanges=\"keyboard|keyboardHidden|navigation|orientation|screenSize\""));
    }

    @Test
    public void onlyChromeAndReviewStageFollowGravityWhileShutterStaysFixed()
            throws Exception {
        String capture = source(
            "app/src/com/autoformkit/app/CaptureActivity.java",
            "src/com/autoformkit/app/CaptureActivity.java");
        String build = section(capture, "private void buildUi(",
            "private void applyOrientedChrome()");
        String chrome = section(capture, "private void applyOrientedChrome()",
            "private void createOrientationListener()");
        String listener = section(capture, "private void createOrientationListener()",
            "private void updateBaseDisplayRotation()");

        assertTrue(build.contains("orientedChrome.addView(close, closeParams);"));
        assertTrue(build.contains("Gravity.LEFT | Gravity.TOP"));
        assertTrue(build.contains("orientedChrome.addView(title, titleParams);"));
        assertTrue(build.contains("orientedChrome.addView(statusText, hintParams);"));
        assertTrue(build.contains("captureParams.bottomMargin = dp(24);"));
        assertFalse(capture.contains("captureButton.setLayoutParams"));
        assertFalse(capture.contains("Configuration.ORIENTATION_LANDSCAPE"));
        assertFalse(capture.contains("applyCameraLayout"));

        assertTrue(chrome.contains("applyRotatedStage(orientedChrome"));
        assertTrue(chrome.contains("applyRotatedStage(reviewControlsStage"));
        assertTrue(chrome.contains("applyRotatedStage(reviewStage"));
        assertTrue(chrome.contains("reviewing && validSurfaceRotation(capturedRotation)"));
        assertTrue(chrome.contains("reviewQuarterTurn ? cameraRoot.getHeight()"));
        assertTrue(chrome.contains("float rotation = delta == 270 ? -90f : delta;"));
        assertTrue(chrome.contains("relativeRotationDegrees"));
        assertFalse(chrome.contains("captureButton"));
        assertFalse(chrome.contains("reviewActions"));
        assertFalse(chrome.contains("previewView"));

        assertTrue(listener.contains("new OrientationEventListener(this)"));
        assertTrue(listener.contains("snapTargetRotation"));
        assertTrue(listener.contains("applyOrientedChrome();"));
        assertTrue(listener.contains("if (!captureInFlight && imageCapture != null)"));
        assertTrue(listener.contains("hasFreshOrientationSample = true;"));
        assertFalse(listener.contains("scheduleCameraViewportRebind"));
        assertFalse(listener.contains("captureButton"));
        assertFalse(listener.contains("reviewActions"));
        assertTrue(capture.contains("private void showOpeningNoticeOverlay()"));
        assertTrue(capture.contains("orientedChrome.addView(shade"));
        assertFalse(capture.contains("new AlertDialog.Builder(this)"));
    }

    @Test
    public void previewUsesLockedDisplayButEachPhotoUsesTheGravityTarget()
            throws Exception {
        String capture = source(
            "app/src/com/autoformkit/app/CaptureActivity.java",
            "src/com/autoformkit/app/CaptureActivity.java");
        String bind = section(capture, "private void bindCameraUseCases()",
            "private void takePicture()");
        String take = section(capture, "private void takePicture()",
            "private void captureFailed(");

        assertTrue(bind.contains("previewView.getViewPort(rotation)"));
        assertTrue(bind.contains("new Preview.Builder().setTargetRotation(rotation).build()"));
        assertTrue(bind.contains(
            "setImageCaptureTargetRotation(capture, currentCaptureTargetRotation())"));
        assertTrue(bind.contains(".setViewPort(viewPort)"));
        assertTrue(bind.contains(".addUseCase(preview)"));
        assertTrue(bind.contains(".addUseCase(capture)"));
        assertTrue(bind.contains("cameraProvider.bindToLifecycle(this, selector, useCases)"));
        assertFalse(bind.contains("bindToLifecycle(this, selector, preview, capture)"));

        int frozen = take.indexOf("capturedRotation = currentCaptureTargetRotation();");
        int inFlight = take.indexOf("captureInFlight = true;", frozen);
        int setTarget = take.indexOf(
            "setImageCaptureTargetRotation(imageCapture, capturedRotation);", inFlight);
        int photo = take.indexOf("imageCapture.takePicture", setTarget);
        assertTrue(frozen >= 0 && inFlight > frozen && setTarget > inFlight && photo > setTarget);
    }

    @Test
    public void sensorAndViewportWaitersFollowTheStartedLifecycle() throws Exception {
        String capture = source(
            "app/src/com/autoformkit/app/CaptureActivity.java",
            "src/com/autoformkit/app/CaptureActivity.java");
        String start = section(capture, "protected void onStart()",
            "protected void onStop()");
        String stop = section(capture, "protected void onStop()",
            "public boolean onKeyDown(");
        String clear = section(capture, "private void clearCameraBindWaiter()",
            "private void bindCameraUseCases()");
        String destroy = section(capture, "protected void onDestroy()",
            "public void onRequestPermissionsResult(");

        assertTrue(start.contains("cameraActivityStarted = true;"));
        assertTrue(start.contains("orientationListener.canDetectOrientation()"));
        assertTrue(start.contains("orientationListener.enable();"));
        assertTrue(start.contains("registerDisplayListener(displayListener, null)"));
        assertTrue(stop.contains("cameraActivityStarted = false;"));
        assertTrue(stop.contains("orientationListener.disable();"));
        assertTrue(stop.contains("clearCameraBindWaiter();"));
        assertTrue(stop.contains("unregisterDisplayListener(displayListener)"));
        assertTrue(clear.contains("ViewTreeObserver observer = cameraPreDrawObserver;"));
        assertTrue(clear.contains("observer.removeOnPreDrawListener(listener)"));
        assertTrue(destroy.contains("orientationListener.disable();"));
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        if (startAt < 0 || endAt <= startAt) {
            throw new AssertionError("source section not found: " + start);
        }
        return source.substring(startAt, endAt);
    }

    private static String source(String... candidates) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (String candidate : candidates) {
            Path path = cwd.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found");
    }
}
