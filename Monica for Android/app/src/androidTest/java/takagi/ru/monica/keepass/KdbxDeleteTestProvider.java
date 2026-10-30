package takagi.ru.monica.keepass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable local content-URI files; never references user-selected documents. */
public final class KdbxDeleteTestProvider extends ContentProvider {
    private final ConcurrentHashMap<String, AtomicInteger> reads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> writes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> commits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> commitStarted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> writeOpened = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> writeFinished = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> denied = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> barriers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> blocked = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> takeover = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> takeoverReady = new ConcurrentHashMap<>();
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("delete-[a-f0-9-]+\\.kdbx")) throw new IllegalArgumentException("Fixture only");
        return new File(getContext().getFilesDir(), name);
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = file(uri);
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "reads", "writes"};
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case OpenableColumns.DISPLAY_NAME: values[i] = f.getName(); break;
                case OpenableColumns.SIZE: values[i] = f.length(); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: values[i] = f.lastModified(); break;
                case DocumentsContract.Document.COLUMN_FLAGS: values[i] = DocumentsContract.Document.FLAG_SUPPORTS_WRITE; break;
                case "reads": values[i] = reads.computeIfAbsent(f.getName(), k -> new AtomicInteger()).get(); break;
                case "writes": values[i] = writes.computeIfAbsent(f.getName(), k -> new AtomicInteger()).get(); break;
                case "commits": values[i] = commits.computeIfAbsent(f.getName(), k -> new AtomicInteger()).get(); break;
                case "commit_started_ms": values[i] = commitStarted.getOrDefault(f.getName(), 0L); break;
                case "write_opened_ms": values[i] = writeOpened.getOrDefault(f.getName(), 0L); break;
                case "write_finished_ms": values[i] = writeFinished.getOrDefault(f.getName(), 0L); break;
                case "blocked": values[i] = blocked.containsKey(f.getName()) ? 1 : 0; break;
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns); cursor.addRow(values); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("t")) {
            commitStarted.put(f.getName(), android.os.SystemClock.elapsedRealtime());
            commits.computeIfAbsent(f.getName(), k -> new AtomicInteger()).incrementAndGet();
            if (denied.containsKey(f.getName())) throw new FileNotFoundException("Injected write denial");
            java.util.concurrent.CountDownLatch barrier = barriers.get(f.getName());
            if (barrier != null) {
                blocked.put(f.getName(), true);
                try {
                    if (!barrier.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw new FileNotFoundException("Fixture barrier timeout");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Interrupted fixture"); }
                finally { blocked.remove(f.getName()); barriers.remove(f.getName()); }
            }
        }
        if (mode.contains("w") && takeover.containsKey(f.getName())) takeoverReady.put(f.getName(), true);
        if (!mode.contains("w") && takeoverReady.remove(f.getName()) != null) {
            byte[] replacement = takeover.remove(f.getName());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(f)) { output.write(replacement); }
            catch (java.io.IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        (mode.contains("w") ? writes : reads).computeIfAbsent(f.getName(), k -> new AtomicInteger()).incrementAndGet();
        if (mode.contains("w")) {
            writeOpened.put(f.getName(), android.os.SystemClock.elapsedRealtime());
            try {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode),
                    new android.os.Handler(android.os.Looper.getMainLooper()),
                    error -> writeFinished.put(f.getName(), android.os.SystemClock.elapsedRealtime()));
            } catch (java.io.IOException error) { throw new FileNotFoundException("Fixture open failed"); }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        File f = file(Uri.parse(arg));
        switch (method) {
            case "arm-other-writer": takeover.put(f.getName(), extras.getByteArray("bytes")); break;
            case "deny-write": denied.put(f.getName(), true); break;
            case "block-next-write": barriers.put(f.getName(), new java.util.concurrent.CountDownLatch(1)); break;
            case "release-write":
                java.util.concurrent.CountDownLatch barrier = barriers.get(f.getName());
                if (barrier != null) barrier.countDown();
                break;
            default: throw new IllegalArgumentException("Unknown fixture command");
        }
        return Bundle.EMPTY;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return file(uri).delete() ? 1 : 0; }
}
