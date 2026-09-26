package takagi.ru.monica.repository;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

public final class StaleSizeContentProvider extends ContentProvider {
    public static final String FILE_NAME = "stale-size-target.mdbx";
    public static final long STALE_REPORTED_SIZE = 1L;
    public static Uri uriFor(Context testContext, boolean corrupted) {
        return Uri.parse("content://" + testContext.getPackageName() + ".stale-size/"
            + (corrupted ? "corrupted/" : "") + FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        String[] columns = projection != null
            ? projection
            : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                row[index] = FILE_NAME;
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                row[index] = STALE_REPORTED_SIZE;
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return backingFile().delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = backingFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        if (!mode.contains("w") && uriFor(Objects.requireNonNull(getContext()), true).equals(uri)) {
            return corruptedReadDescriptor(file);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    private ParcelFileDescriptor corruptedReadDescriptor(File file) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (
                    FileInputStream input = new FileInputStream(file);
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                ) {
                    byte[] buffer = new byte[128 * 1024];
                    boolean corrupted = false;
                    while (true) {
                        int count = input.read(buffer);
                        if (count < 0) {
                            break;
                        }
                        if (!corrupted && count > 0) {
                            buffer[0] = (byte) (buffer[0] ^ 0x01);
                            corrupted = true;
                        }
                        output.write(buffer, 0, count);
                    }
                } catch (IOException ignored) {
                    // The reader observes an incomplete stream and publication validation fails.
                }
            }, "mdbx-stale-size-corrupt-reader");
            writer.start();
            return pipe[0];
        } catch (IOException error) {
            FileNotFoundException wrapped = new FileNotFoundException(
                "Cannot create corrupted test stream"
            );
            wrapped.initCause(error);
            throw wrapped;
        }
    }

    private File backingFile() {
        return new File(Objects.requireNonNull(getContext()).getFilesDir(), FILE_NAME);
    }
}
