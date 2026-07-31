package com.autoformkit.app;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class UpdateApkProvider extends ContentProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".update"; // tracks applicationId so debug (.debug) installs alongside release

    static Uri uriForFile(Context context, File file, String handoffToken) {
        if (context == null || file == null
                || !UpdateInstallRules.isSafeApkName(file.getName())
                || !UpdateInstallRules.isValidHandoffToken(handoffToken)) {
            throw new IllegalArgumentException("Invalid update handoff");
        }
        try {
            File directory = new File(context.getFilesDir(), "updates");
            if (!file.getCanonicalPath().startsWith(
                    directory.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("Invalid update apk path");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid update apk path", error);
        }
        return new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .appendPath(handoffToken)
            .appendPath(file.getName())
            .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            authorize(uri, true);
        }
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            AuthorizedHandoff handoff = authorize(uri, true);
            File file = fileFor(handoff.request);
            String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                    values[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(columns[i])) {
                    values[i] = handoff.pending.metadata.apkLength;
                } else {
                    values[i] = null;
                }
            }
            cursor.addRow(values);
            return cursor;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Update APK is read-only");
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            ParcelFileDescriptor descriptor = null;
            try {
                // AOSP and OEM installers may open one content URI multiple times while staging
                // (metadata sizing, session creation, and byte copy). This authorizes one
                // short-lived URI capability, not one descriptor: every descriptor is still
                // independently pinned, fully hashed, parsed, and source-checked below.
                AuthorizedHandoff handoff = authorize(uri, true);
                File file = fileFor(handoff.request);
                descriptor = ParcelFileDescriptor.open(
                    file, ParcelFileDescriptor.MODE_READ_ONLY);
                // This re-hashes and re-parses the descriptor's exact bytes. The pathname may be
                // unlinked after open, but the returned descriptor remains pinned to this inode.
                UpdateManager.validateProviderHandoff(
                    providerContext(), handoff.pending, file, descriptor);

                SharedPreferences prefs = updatePreferences();
                Object currentRaw = prefs.getAll().get(UpdateManager.PREF_PENDING_INSTALL);
                Object currentBinding = prefs.getAll().get(
                    UpdateManager.PREF_HANDOFF_BINDING);
                Object openedBinding = prefs.getAll().get(
                    UpdateManager.PREF_HANDOFF_OPENED_BINDING);
                Object currentIdentity = prefs.getAll().get(
                    UpdateManager.PREF_HANDOFF_IDENTITY);
                String expectedIdentity = UpdateInstallRules.pendingIdentitySha256(
                    handoff.pending.source, handoff.pending.metadata);
                boolean capabilityStillCurrent = handoff.opened
                    ? openedBinding instanceof String
                        && UpdateInstallRules.digestEquals(
                            handoff.bindingSha256, (String) openedBinding)
                    : currentBinding instanceof String
                        && UpdateInstallRules.digestEquals(
                            handoff.bindingSha256, (String) currentBinding);
                if (!(currentRaw instanceof String)
                        || !handoff.pendingJson.equals(currentRaw)
                        || !(currentIdentity instanceof String)
                        || !UpdateInstallRules.digestEquals(
                            expectedIdentity, (String) currentIdentity)
                        || !capabilityStillCurrent
                        || UpdateManager.remoteSideEffectBlockingStatePresent(providerContext())
                        || !UpdateManager.sourceBindingStillCurrent(
                            providerContext(), handoff.pending.source)) {
                    throw new SecurityException("Update handoff changed during validation");
                }
                if (!handoff.opened) {
                    boolean opened = prefs.edit()
                        .remove(UpdateManager.PREF_HANDOFF_BINDING)
                        .putString(UpdateManager.PREF_HANDOFF_OPENED_BINDING,
                            handoff.bindingSha256)
                        .commit();
                    if (!opened) {
                        throw new IOException("Cannot activate update handoff capability");
                    }
                }
                ParcelFileDescriptor result = descriptor;
                descriptor = null;
                return result;
            } catch (Exception error) {
                if (descriptor != null) {
                    try {
                        descriptor.close();
                    } catch (IOException ignored) {
                    }
                }
                FileNotFoundException failure =
                    new FileNotFoundException("Update handoff validation failed");
                failure.initCause(error);
                throw failure;
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private AuthorizedHandoff authorize(Uri uri, boolean allowConsumed) {
        if (UpdateManager.remoteSideEffectBlockingStatePresent(providerContext())) {
            throw new SecurityException(
                "Update handoff blocked by unresolved remote operation");
        }
        HandoffRequest request = requestFor(uri);
        SharedPreferences prefs = updatePreferences();
        Object rawPending = prefs.getAll().get(UpdateManager.PREF_PENDING_INSTALL);
        if (!(rawPending instanceof String)) {
            throw new SecurityException("Pending update metadata is missing");
        }
        try {
            UpdateManager.PendingInstall pending =
                UpdateManager.parsePendingInstallJson((String) rawPending);
            if (!request.apkName.equals(pending.metadata.apkName)
                    || !UpdateManager.sourceBindingStillCurrent(
                        providerContext(), pending.source)) {
                throw new SecurityException("Pending update source or file changed");
            }
            String binding = UpdateInstallRules.handoffBindingSha256(
                request.token, pending.source, pending.metadata);
            Object identity = prefs.getAll().get(UpdateManager.PREF_HANDOFF_IDENTITY);
            boolean identityMatches = identity instanceof String
                && UpdateInstallRules.digestEquals(
                    UpdateInstallRules.pendingIdentitySha256(
                        pending.source, pending.metadata), (String) identity);
            Object active = prefs.getAll().get(UpdateManager.PREF_HANDOFF_BINDING);
            boolean activeMatches = active instanceof String
                && UpdateInstallRules.digestEquals(binding, (String) active);
            boolean openedMatches = false;
            if (!activeMatches && allowConsumed) {
                Object openedBinding = prefs.getAll().get(
                    UpdateManager.PREF_HANDOFF_OPENED_BINDING);
                openedMatches = openedBinding instanceof String
                    && UpdateInstallRules.digestEquals(
                        binding, (String) openedBinding);
            }
            if (!identityMatches || (!activeMatches && !openedMatches)) {
                throw new SecurityException("Invalid update handoff token");
            }
            return new AuthorizedHandoff(
                request, pending, (String) rawPending, binding, openedMatches);
        } catch (SecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new SecurityException("Invalid pending update metadata", error);
        }
    }

    private HandoffRequest requestFor(Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())) {
            throw new IllegalArgumentException("Invalid update handoff URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new IllegalArgumentException("Invalid update handoff URI");
        }
        String token = segments.get(0);
        String name = segments.get(1);
        if (!UpdateInstallRules.isValidHandoffToken(token)
                || !UpdateInstallRules.isSafeApkName(name)) {
            throw new IllegalArgumentException("Invalid update apk name");
        }
        return new HandoffRequest(token, name);
    }

    private File fileFor(HandoffRequest request) {
        Context context = providerContext();
        File dir = new File(context.getFilesDir(), "updates");
        try {
            String dirPath = dir.getCanonicalPath();
            File file = new File(dir, request.apkName);
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(dirPath + File.separator)) {
                throw new IllegalArgumentException("Invalid update apk path");
            }
            return file;
        } catch (IOException exc) {
            throw new IllegalArgumentException("Invalid update apk path", exc);
        }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context missing");
        return context;
    }

    private SharedPreferences updatePreferences() {
        return providerContext().getSharedPreferences(
            UpdateManager.PREFS, Context.MODE_PRIVATE);
    }

    private static final class HandoffRequest {
        final String token;
        final String apkName;

        HandoffRequest(String token, String apkName) {
            this.token = token;
            this.apkName = apkName;
        }
    }

    private static final class AuthorizedHandoff {
        final HandoffRequest request;
        final UpdateManager.PendingInstall pending;
        final String pendingJson;
        final String bindingSha256;
        final boolean opened;

        AuthorizedHandoff(HandoffRequest request,
                          UpdateManager.PendingInstall pending,
                          String pendingJson, String bindingSha256,
                          boolean opened) {
            this.request = request;
            this.pending = pending;
            this.pendingJson = pendingJson;
            this.bindingSha256 = bindingSha256;
            this.opened = opened;
        }
    }
}
