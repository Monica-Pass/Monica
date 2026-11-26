package takagi.ru.monica.security;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** SAF fixture root for black-box Release tests. Exposes only task-prefixed disposable copies. */
public final class RecoveryBackupDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "recovery331";
    private static final String[] ROOT_COLUMNS = { "root_id", "document_id", "title", "flags", "icon", "mime_types" };
    private static final String[] DOC_COLUMNS = { "document_id", "_display_name", "mime_type", "flags", "_size", "last_modified" };
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        if (!id.matches("recovery331-[a-z0-9-]+\\.enc\\.zip")) throw new FileNotFoundException("Fixture only");
        return new File(getContext().getFilesDir(), id);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("root_id", ROOT).add("document_id", ROOT).add("title", "Monica Recovery Tests")
            .add("flags", DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_CREATE).add("icon", android.R.drawable.ic_menu_save)
            .add("mime_types", "application/zip\napplication/octet-stream");
        return cursor;
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = ROOT.equals(id);
        File file = root ? getContext().getFilesDir() : file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("document_id", id).add("_display_name", root ? "Monica Recovery Tests" : id)
            .add("mime_type", root ? DocumentsContract.Document.MIME_TYPE_DIR : "application/zip")
            .add("flags", root ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
            .add("_size", root ? 0 : file.length()).add("last_modified", file.lastModified());
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        add(cursor, documentId); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!ROOT.equals(parentDocumentId)) throw new FileNotFoundException("Fixture root only");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOC_COLUMNS : projection);
        File[] files = getContext().getFilesDir().listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("recovery331-[a-z0-9-]+\\.enc\\.zip")) add(cursor, file.getName());
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!ROOT.equals(parent)) throw new FileNotFoundException("Fixture root only");
        try { if (!file(name).createNewFile()) throw new java.io.IOException("Preserve prior fixture"); }
        catch (java.io.IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return name;
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parent, String child) { return ROOT.equals(parent) && child.matches("recovery331-[a-z0-9-]+\\.enc\\.zip"); }
}
