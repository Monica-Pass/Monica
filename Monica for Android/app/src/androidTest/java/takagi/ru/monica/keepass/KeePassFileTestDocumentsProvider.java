package takagi.ru.monica.keepass;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** SAF fixture root for black-box Release tests. Exposes only task-prefixed disposable copies. */
public final class KeePassFileTestDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "keepass-file-test";
    private static final String[] ROOT_COLUMNS = { "root_id", "document_id", "title", "flags", "icon", "mime_types" };
    private static final String[] DOC_COLUMNS = { "document_id", "_display_name", "mime_type", "flags", "_size", "last_modified" };
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        if (!id.matches("native-file-[a-z0-9-]+\\.kdbx")) throw new FileNotFoundException("Fixture only");
        return new File(getContext().getFilesDir(), id);
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("root_id", ROOT).add("document_id", ROOT).add("title", "Monica KDBX Tests")
            .add("flags", DocumentsContract.Root.FLAG_LOCAL_ONLY).add("icon", android.R.drawable.ic_menu_save)
            .add("mime_types", "application/octet-stream");
        return cursor;
    }
    private void add(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = ROOT.equals(id);
        File file = root ? getContext().getFilesDir() : file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("document_id", id).add("_display_name", root ? "Monica KDBX Tests" : id)
            .add("mime_type", root ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream")
            .add("flags", root ? 0 : DocumentsContract.Document.FLAG_SUPPORTS_WRITE)
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
        if (files != null) for (File file : files) if (file.getName().matches("native-file-[a-z0-9-]+\\.kdbx")) add(cursor, file.getName());
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parent, String child) { return ROOT.equals(parent) && child.matches("native-file-[a-z0-9-]+\\.kdbx"); }
}
