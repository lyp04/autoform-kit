package com.autoformkit.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copies a one-shot picker URI into private storage as validated JPEG bytes. */
final class PrivateJpegImporter {
    private static final long MAX_SOURCE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_EDGE = 2560;
    private static final int JPEG_QUALITY = 94;

    private PrivateJpegImporter() {}

    static void importImage(Context context, Uri source, File destination) throws IOException {
        if (context == null || source == null || destination == null
                || !"content".equals(source.getScheme())) {
            throw new IOException("Selected image is unavailable");
        }
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory()) {
            throw new IOException("Cannot create private photo directory");
        }
        if (destination.exists()) {
            throw new IOException("Private photo destination already exists");
        }

        File raw = File.createTempFile("picked-image-", ".source", context.getCacheDir());
        File encoded = null;
        try {
            copyBounded(context, source, raw);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 100_000 || bounds.outHeight > 100_000) {
                throw new IOException("Selected file is not a supported image");
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            Bitmap bitmap = BitmapFactory.decodeFile(raw.getAbsolutePath(), options);
            if (bitmap == null) throw new IOException("Selected image cannot be decoded");
            try {
                Bitmap transformed = orientAndScale(bitmap, raw);
                if (transformed != bitmap) {
                    bitmap.recycle();
                    bitmap = transformed;
                }
                Bitmap jpegBitmap = flattenAlpha(bitmap);
                try {
                    encoded = File.createTempFile(
                        "." + destination.getName() + "-", ".importing", parent);
                    try (FileOutputStream output = new FileOutputStream(encoded)) {
                        if (!jpegBitmap.compress(Bitmap.CompressFormat.JPEG,
                                JPEG_QUALITY, output)) {
                            throw new IOException("Selected image cannot be encoded");
                        }
                        output.flush();
                        output.getFD().sync();
                    }
                    if (encoded.length() <= 0 || !encoded.renameTo(destination)) {
                        throw new IOException("Selected image cannot be saved");
                    }
                    encoded = null;
                } finally {
                    if (jpegBitmap != bitmap) jpegBitmap.recycle();
                }
            } finally {
                if (!bitmap.isRecycled()) bitmap.recycle();
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("Selected image is too large to process", error);
        } finally {
            if (encoded != null) encoded.delete();
            raw.delete();
        }
    }

    private static void copyBounded(Context context, Uri source, File raw) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(raw)) {
            if (input == null) throw new IOException("Selected image cannot be opened");
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SOURCE_BYTES) {
                    throw new IOException("Selected image is too large");
                }
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
            if (total <= 0L) throw new IOException("Selected image is empty");
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > MAX_EDGE && sample <= 512) sample *= 2;
        return sample;
    }

    private static Bitmap orientAndScale(Bitmap source, File raw) throws IOException {
        ExifTransform exif = exifTransform(raw);
        float scale = Math.min(1f,
            (float) MAX_EDGE / Math.max(source.getWidth(), source.getHeight()));
        if (exif.rotation == 0 && !exif.flipped && scale >= 1f) return source;
        Matrix matrix = new Matrix();
        if (scale < 1f) matrix.postScale(scale, scale);
        if (exif.flipped) matrix.postScale(-1f, 1f);
        if (exif.rotation != 0) matrix.postRotate(exif.rotation);
        return Bitmap.createBitmap(
            source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static ExifTransform exifTransform(File source) {
        try {
            ExifInterface exif = new ExifInterface(source.getAbsolutePath());
            return new ExifTransform(exif.getRotationDegrees(), exif.isFlipped());
        } catch (IOException ignored) {
            // A decodable image with absent or malformed EXIF is still a valid selection.
            return new ExifTransform(0, false);
        }
    }

    private static final class ExifTransform {
        final int rotation;
        final boolean flipped;

        ExifTransform(int rotation, boolean flipped) {
            this.rotation = rotation;
            this.flipped = flipped;
        }
    }

    private static Bitmap flattenAlpha(Bitmap source) {
        if (!source.hasAlpha()) return source;
        Bitmap flattened = Bitmap.createBitmap(
            source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return flattened;
    }
}
