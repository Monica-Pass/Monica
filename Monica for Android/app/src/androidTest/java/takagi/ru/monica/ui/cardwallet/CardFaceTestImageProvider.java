package takagi.ru.monica.ui.cardwallet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runs in the test APK's own process, which has no application Kotlin runtime on its classpath. */
public final class CardFaceTestImageProvider extends ContentProvider {
    private final Set<String> opened = ConcurrentHashMap.newKeySet();

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        return "gif".equals(kind(uri)) ? "image/gif" : "image/jpeg";
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"card.jpg", -1});
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("missing".equals(kind(uri)) || !opened.add(uri.toString())) {
            throw new FileNotFoundException("Source is only readable once");
        }
        try {
            final byte[] bytes;
            switch (kind(uri)) {
                case "gif":
                    bytes = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT);
                    break;
                case "rotated": bytes = rotatedImage(); break;
                default: bytes = largeJpeg();
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(bytes);
                } catch (IOException ignored) {
                    // The reader may close early when checking a rejected source.
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }, "card-face-image-provider").start();
            return pipe[0];
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Image fixture could not be opened");
            failure.initCause(error);
            throw failure;
        }
    }

    private String kind(Uri uri) {
        return uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0);
    }

    private byte[] largeJpeg() {
        Random random = new Random(42);
        int[] pixels = new int[1024 * 1024];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, 1024, 1024, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
            byte[] bytes = output.toByteArray();
            if (bytes.length <= 1024 * 1024) throw new IllegalStateException("Fixture must exceed one megabyte");
            return bytes;
        } finally {
            bitmap.recycle();
        }
    }

    private byte[] rotatedImage() throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.RED);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(800f, 0f, 1600f, 1000f, paint);
        File file = new File(getContext().getCacheDir(), "card-face-" + UUID.randomUUID() + ".jpg");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
            }
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_180));
            exif.saveAttributes();
            try (FileInputStream input = new FileInputStream(file)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                return output.toByteArray();
            }
        } finally {
            bitmap.recycle();
            file.delete();
        }
    }
}
