package takagi.ru.monica.transfer;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static takagi.ru.monica.transfer.DocumentUriGrantProbeActivity.*;

/** Standalone non-exported same-UID test service, with framework/JSON/Java dependencies only. */
public class DocumentUriGrantProbeService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        require(intent != null, "Missing owned probe request");
        final String label = intent.getStringExtra("probe_label");
        require(label != null && label.matches("[a-z0-9-]{1,64}"), "Invalid owned probe label");
        require(intent.getIntExtra("owned_caller_uid", -1) == Process.myUid(), "Foreign probe request UID");
        String rawUri = intent.getStringExtra("owned_result_uri");
        require(rawUri != null, "Missing exact picker result URI");
        final Uri uri = Uri.parse(rawUri);
        require("content".equals(uri.getScheme()), "Expected owned document content URI");
        String selectedMode = intent.getStringExtra("probe_mode");
        final String mode = selectedMode == null ? "w" : selectedMode;
        require(mode.equals("w") || mode.equals("wt") || mode.equals("rwt"), "Invalid probe write mode");
        boolean attached = intent.getBooleanExtra("attach_service_grants", false);
        if (attached) {
            require(uri.equals(intent.getData()) && intent.getClipData() != null &&
                    intent.getClipData().getItemCount() == 1 && uri.equals(intent.getClipData().getItemAt(0).getUri()),
                    "Service grant does not match the exact owned URI");
        }
        final JSONObject result = new JSONObject();
        put(result, "label", label); put(result, "uri", uri.toString());
        put(result, "callerUid", Process.myUid()); put(result, "callerPackage", getPackageName());
        put(result, "callerPid", Process.myPid()); put(result, "attachServiceGrants", attached);
        put(result, "returnedGrantModes", intent.getIntExtra("returned_grant_modes", 0));
        put(result, "serviceIntentFlags", intent.getFlags()); put(result, "writeMode", mode);
        put(result, "activityRemovalRequestedAt", intent.getLongExtra("activity_removal_requested_at", 0));
        put(result, "scope", "Exact URI selected by the private same-UID owned picker Activity");
        new Thread(() -> {
            SystemClock.sleep(1000);
            put(result, "serviceProbeElapsedRealtime", SystemClock.elapsedRealtime());
            File destroyFile = new File(getFilesDir(), "document-probe-destroy-" + label + ".json");
            boolean destroyedBeforeProbe = false;
            if (destroyFile.isFile()) {
                try {
                    JSONObject destroyed = new JSONObject(readSmallJson(destroyFile));
                    destroyedBeforeProbe = destroyed.optInt("callerUid", -1) == Process.myUid() &&
                            destroyed.optLong("destroyedElapsedRealtime", Long.MAX_VALUE) <= SystemClock.elapsedRealtime();
                } catch (Exception error) {
                    put(result, "destroyReceiptReadException", error.getClass().getName());
                    put(result, "destroyReceiptReadMessage", message(error));
                }
            }
            put(result, "callerActivityDestroyedBeforeProbe", destroyedBeforeProbe);
            put(result, "serviceReadGranted", checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
            put(result, "serviceWriteGranted", checkUriPermission(uri, Process.myPid(), Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED);
            writeAndRead(result, "service", getContentResolver(), uri, mode);
            writeNewJson(new File(getFilesDir(), "document-service-grant-" + label + ".json"), result);
            stopSelf(startId);
        }, "OwnedDocumentGrantProbe").start();
        return START_NOT_STICKY;
    }

    private static String readSmallJson(File file) throws Exception {
        byte[] bytes = new byte[4097];
        int count = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (count < bytes.length) {
                int read = input.read(bytes, count, bytes.length - count);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
            }
        }
        require(count <= 4096, "Owned destroy receipt exceeds diagnostic bound");
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }
}
