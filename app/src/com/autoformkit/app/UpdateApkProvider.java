package com.autoformkit.app;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class UpdateApkProvider extends ContentProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".update"; // tracks applicationId so debug (.debug) installs alongside release

    static Uri uriForFile(Context context, File file) {
        return new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath(file.getName())
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                values[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                values[i] = file.length();
            } else {
                values[i] = null;
            }
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = fileFor(uri);
        return file.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File fileFor(Uri uri) {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Provider context missing");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..") || !name.endsWith(".apk")) {
            throw new IllegalArgumentException("Invalid update apk name");
        }
        File dir = new File(context.getFilesDir(), "updates");
        try {
            String dirPath = dir.getCanonicalPath();
            File file = new File(dir, name);
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(dirPath + File.separator)) {
                throw new IllegalArgumentException("Invalid update apk path");
            }
            return file;
        } catch (IOException exc) {
            throw new IllegalArgumentException("Invalid update apk path", exc);
        }
    }
}
