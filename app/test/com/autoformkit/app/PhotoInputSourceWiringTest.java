package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the Panel policy through intent selection and private image materialization. */
public class PhotoInputSourceWiringTest {
    private static String source(String relative) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{cwd.resolve("app").resolve(relative),
                cwd.resolve(relative)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source not found: " + relative);
    }

    @Test
    public void cameraStaysInProcessWhileGalleryAndFileUsePlatformPickers() throws Exception {
        String main = source("src/com/autoformkit/app/MainActivity.java");
        String capture = source("src/com/autoformkit/app/CaptureActivity.java");
        assertTrue(main.contains("new Intent(this, CaptureActivity.class)"));
        assertTrue(!main.contains("new Intent(MediaStore.ACTION_IMAGE_CAPTURE)"));
        assertTrue(main.contains(
            "Starting internal camera for original photo bytes"));
        assertTrue(main.contains(
            "Starting internal camera for alternate-entry photo bytes"));
        assertTrue(main.contains("Starting internal camera for OCR role="));
        assertTrue(main.contains("new Intent(MediaStore.ACTION_PICK_IMAGES)"));
        assertTrue(main.contains("new Intent(Intent.ACTION_PICK,"));
        assertTrue(main.contains("new Intent(Intent.ACTION_OPEN_DOCUMENT)"));
        assertTrue(main.contains("PhotoInputSourceRules.CAMERA.equals(inputSource)\n"
            + "            || ensureCameraPermission()"));
        assertTrue(main.contains(
            "startPendingPhotoInput(target.artifact.inputSource);"));
        assertTrue(main.contains(
            "AlternateEntryRules.photoInputSource(alternateEntryConfig)"));
        assertTrue(main.contains("PrivateJpegImporter.importImage("));
        assertTrue(capture.contains("extends ComponentActivity"));
        assertTrue(capture.contains("ProcessCameraProvider.getInstance(this)"));
        assertTrue(capture.contains("new ImageCapture.Builder()"));
        assertTrue(capture.contains("previewView.getViewPort(rotation)"));
        assertTrue(capture.contains("ViewTreeObserver.OnPreDrawListener"));
        assertTrue(capture.contains("observer.addOnPreDrawListener(cameraPreDrawListener)"));
        assertTrue(capture.contains("new UseCaseGroup.Builder()"));
        assertTrue(capture.contains(".setViewPort(viewPort)"));
        assertTrue(capture.contains(
            "cameraProvider.bindToLifecycle(this, selector, useCases)"));
        assertTrue(capture.contains("setResult(RESULT_CANCELED)"));
        assertTrue(capture.contains("setResult(RESULT_OK, data)"));
        assertTrue(!capture.contains("android.hardware.Camera"));
        assertTrue(capture.contains(
            "Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM"));
        assertTrue(capture.contains(
            "captureParams.bottomMargin = dp(24)"));
        assertTrue(!capture.contains("landscape ? 58 : 24"));
        assertTrue(capture.contains("close.setOnClickListener(v -> finishCanceled())"));
        assertTrue(capture.contains("setCaptureEnabled(false)"));
        assertTrue(capture.contains("setStatus(\"saving_photo\", true)"));
        assertTrue(capture.contains("R.drawable.ic_close_camera"));
        assertTrue(capture.contains("reviewImage.setScaleType(ImageView.ScaleType.FIT_CENTER)"));
        assertTrue(capture.contains("applyRotatedStage(reviewStage"));
        assertTrue(capture.contains("private void retakePhoto()"));
        assertTrue(capture.contains("private void confirmPhoto()"));
        assertTrue(capture.contains("R.drawable.ic_retake_camera"));
        assertTrue(capture.contains("R.drawable.ic_confirm_camera"));
        assertTrue(capture.contains("reviewActions.setClipToOutline(true)"));
        assertTrue(capture.contains("ViewGroup.LayoutParams.MATCH_PARENT, dp(84)"));
        assertTrue(capture.contains("reviewParams.leftMargin = dp(18)"));
        assertTrue(capture.contains("PrivateJpegImporter.decodePreview(outputFile)"));
        assertTrue(capture.contains("showReview();"));
        assertTrue(main.contains(
            "PrivateJpegImporter.decodeOrientedBitmap(\n                new File(path)"));
    }

    @Test
    public void pickedBytesAreBoundedDecodedAndReencodedAsJpeg() throws Exception {
        String importer = source("src/com/autoformkit/app/PrivateJpegImporter.java");
        assertTrue(importer.contains("MAX_SOURCE_BYTES"));
        assertTrue(importer.contains("BitmapFactory.decodeFile"));
        assertTrue(importer.contains("Bitmap.CompressFormat.JPEG"));
        assertTrue(importer.contains("output.getFD().sync()"));
        assertTrue(importer.contains("encoded.renameTo(destination)"));
        assertTrue(importer.contains("getRotationDegrees()"));
        assertTrue(importer.contains("matrix.postRotate(exif.rotation)"));
    }
}
