package takagi.ru.monica.credentialexchange;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Separate test-APK UID. Only UUID synthetic cache documents, never a user URI or tree grant. */
public final class TransferExportDocumentTestProvider extends ContentProvider {
    private static final int MODES = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private final ConcurrentHashMap<String, Boolean> owned = new ConcurrentHashMap<>();
    @Override public boolean onCreate() { return true; }

    private String targetPackage() {
        String testPackage = getContext().getPackageName();
        if (!testPackage.endsWith(".test")) throw new SecurityException("Expected instrumentation package");
        return testPackage.substring(0, testPackage.length() - 5);
    }
    private void requireCaller() {
        try {
            int caller = Binder.getCallingUid();
            int target = getContext().getPackageManager().getApplicationInfo(targetPackage(), 0).uid;
            if (caller != target && caller != Process.myUid()) throw new SecurityException("Unexpected fixture caller");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
    }
    private String token(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("UUID fixture only");
        return value;
    }
    private Uri uri(String token) {
        return new Uri.Builder().scheme("content").authority(getContext().getPackageName() + ".export-documents")
            .appendPath(token(token)).build();
    }
    private File file(String token) {
        return new File(new File(getContext().getCacheDir(), "transfer-export-documents"), token(token) + ".zip");
    }
    private void grant(String token, boolean allow) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (allow) getContext().grantUriPermission(targetPackage(), uri(token), MODES);
            else getContext().revokeUriPermission(uri(token), MODES);
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private String ownedToken(Uri uri) throws FileNotFoundException {
        if (!getContext().getPackageName().concat(".export-documents").equals(uri.getAuthority()) || uri.getPathSegments().size() != 1)
            throw new FileNotFoundException("Exact fixture document required");
        String token = token(uri.getLastPathSegment());
        if (!owned.containsKey(token)) throw new FileNotFoundException("Unknown synthetic document");
        return token;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireCaller();
        String token = token(arg);
        File file = file(token);
        Bundle result = new Bundle();
        if ("create".equals(method)) {
            if (owned.putIfAbsent(token, Boolean.TRUE) != null) throw new IllegalStateException("Fixture already exists");
            try {
                if ((!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) || !file.createNewFile())
                    throw new IOException("Do not replace an existing fixture");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write("Owned unmodified synthetic export document".getBytes(StandardCharsets.UTF_8));
                }
                if (extras != null && extras.getBoolean("grant", false)) grant(token, true);
                result.putString("uri", uri(token).toString());
            } catch (IOException error) { owned.remove(token); throw new IllegalStateException(error); }
        } else if ("inspect".equals(method)) {
            if (!owned.containsKey(token)) throw new IllegalArgumentException("Unknown fixture");
            if (file.length() > 8L * 1024 * 1024) throw new IllegalStateException("Fixture size limit");
            try { result.putByteArray("bytes", Files.readAllBytes(file.toPath())); }
            catch (IOException error) { throw new IllegalStateException(error); }
        } else if ("release".equals(method)) {
            if (!owned.containsKey(token)) throw new IllegalArgumentException("Unknown fixture");
            grant(token, false);
            if (file.exists() && !file.delete()) throw new IllegalStateException("Synthetic cleanup failed");
            owned.remove(token);
            file.getParentFile().delete();
        } else throw new IllegalArgumentException("Unknown fixture operation");
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireCaller();
        if (!mode.equals("r") && !mode.equals("w") && !mode.equals("wt") && !mode.equals("rwt"))
            throw new FileNotFoundException("Unsupported fixture mode");
        return ParcelFileDescriptor.open(file(ownedToken(uri)), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        requireCaller();
        try {
            File file = file(ownedToken(uri));
            String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            Object[] values = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[index])) values[index] = file.length();
            }
            MatrixCursor cursor = new MatrixCursor(columns); cursor.addRow(values); return cursor;
        } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
