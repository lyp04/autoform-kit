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
    public void cameraGalleryAndFileUseDistinctPlatformPaths() throws Exception {
        String main = source("src/com/autoformkit/app/MainActivity.java");
        assertTrue(main.contains("new Intent(MediaStore.ACTION_IMAGE_CAPTURE)"));
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
    }

    @Test
    public void pickedBytesAreBoundedDecodedAndReencodedAsJpeg() throws Exception {
        String importer = source("src/com/autoformkit/app/PrivateJpegImporter.java");
        assertTrue(importer.contains("MAX_SOURCE_BYTES"));
        assertTrue(importer.contains("BitmapFactory.decodeFile"));
        assertTrue(importer.contains("Bitmap.CompressFormat.JPEG"));
        assertTrue(importer.contains("output.getFD().sync()"));
        assertTrue(importer.contains("encoded.renameTo(destination)"));
    }
}
