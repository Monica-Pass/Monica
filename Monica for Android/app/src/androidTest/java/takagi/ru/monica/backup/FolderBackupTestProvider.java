package takagi.ru.monica.backup;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;

/** Test APK only; exposes an isolated synthetic directory, never arbitrary paths. */
public final class FolderBackupTestProvider extends DocumentsProvider {
    private static final String ROOT = "folder332";
    private String fault = "";
    private File root() { File f = new File(getContext().getFilesDir(), ROOT); f.mkdirs(); return f; }
    private File file(String id) throws FileNotFoundException {
        if (ROOT.equals(id)) return root();
        if (!id.matches("[A-Za-z0-9_.-]{1,180}")) throw new FileNotFoundException();
        return new File(root(), id);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("fixture")) {
            fault = extras.getString("fault", "");
            if (extras.getBoolean("reset")) { for (File f : root().listFiles()) if (f.isFile()) f.delete(); }
            String target = extras.getString("target");
            if (target != null) {
                Uri tree = DocumentsContract.buildTreeDocumentUri(getContext().getPackageName() + ".folder-backups", ROOT);
                getContext().grantUriPermission(target, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            }
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }
    private static final String[] C = {"document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"};
    private void add(MatrixCursor c, String id) throws FileNotFoundException {
        File f = file(id); boolean dir = ROOT.equals(id);
        c.newRow().add("document_id", id).add("_display_name", dir ? "Monica Folder Tests" : id)
            .add("mime_type", dir ? DocumentsContract.Document.MIME_TYPE_DIR : "application/zip")
            .add("flags", dir ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE :
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
            .add("_size", dir ? 0 : f.length()).add("last_modified", f.lastModified());
    }
    @Override public Cursor queryRoots(String[] p) {
        MatrixCursor c = new MatrixCursor(p == null ? new String[]{"root_id","document_id","title","flags","mime_types"} : p);
        c.newRow().add("root_id",ROOT).add("document_id",ROOT).add("title","Monica Folder Tests")
            .add("flags", DocumentsContract.Root.FLAG_SUPPORTS_CREATE).add("mime_types","*/*"); return c;
    }
    @Override public Cursor queryDocument(String id, String[] p) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(p == null ? C : p); add(c,id); return c;
    }
    @Override public Cursor queryChildDocuments(String id, String[] p, String sort) throws FileNotFoundException {
        if (fault.equals("null-list")) return null;
        MatrixCursor c = new MatrixCursor(p == null ? C : p);
        for (File f : file(id).listFiles()) add(c,f.getName());
        if (fault.equals("partial-list")) { Bundle e = new Bundle(); e.putBoolean(DocumentsContract.EXTRA_LOADING, true); c.setExtras(e); }
        return c;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!ROOT.equals(parent)) throw new FileNotFoundException();
        try { if (!file(name).createNewFile()) throw new IOException(); } catch (IOException e) { throw new FileNotFoundException(); }
        return name;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (fault.equals("write-denied") && mode.contains("w")) throw new FileNotFoundException("Synthetic write failure");
        if (fault.equals("read-corrupt") && mode.equals("r")) {
            try (FileOutputStream out = new FileOutputStream(file(id))) { out.write(new byte[]{7}); }
            catch (IOException e) { throw new FileNotFoundException(); }
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parent, String child) { return ROOT.equals(parent) && !ROOT.equals(child); }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (ROOT.equals(id) || fault.equals("delete-denied") || !file(id).delete()) throw new FileNotFoundException();
    }
}
