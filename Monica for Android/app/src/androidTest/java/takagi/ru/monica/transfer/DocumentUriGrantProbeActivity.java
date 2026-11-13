package takagi.ru.monica.transfer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Standalone test-APK system-picker caller; no target APK or Kotlin runtime dependency. */
@SuppressWarnings("deprecation")
public class DocumentUriGrantProbeActivity extends Activity {
    private static final int REQUEST_DOCUMENT = 901;
    static final byte[] MARKER = "Owned synthetic document probe\n".getBytes(StandardCharsets.UTF_8);
    private boolean serviceDispatchedForProof;

    private String label() {
        String value = getIntent().getStringExtra("probe_label");
        require(value != null && value.matches("[a-z0-9-]{1,64}"), "Invalid owned probe label");
        return value;
    }

    private boolean requestedGrants() { return getIntent().getBooleanExtra("request_grants", false); }
    private boolean persistGrant() { return getIntent().getBooleanExtra("persist_grant", true); }

    private String writeMode() {
        String value = getIntent().getStringExtra("probe_mode");
        if (value == null) value = "w";
        require(value.equals("w") || value.equals("wt") || value.equals("rwt"), "Invalid probe write mode");
        return value;
    }

    private String probeMime() {
        String value = getIntent().getStringExtra("probe_mime");
        if (value == null) value = "text/plain";
        require(value.equals("text/plain") || value.equals("application/zip") ||
                value.equals("application/octet-stream"), "Invalid probe MIME");
        return value;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        show("Owned document grant probe: " + label());
        if (savedInstanceState == null) {
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType(probeMime());
            picker.putExtra(Intent.EXTRA_TITLE, "Monica_owned_uri_probe_" + label() + ".txt");
            if (requestedGrants()) picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(picker, REQUEST_DOCUMENT);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOCUMENT) return;
        final Uri uri = data == null ? null : data.getData();
        final int flags = data == null ? 0 : data.getFlags();
        final String mode = writeMode();
        final JSONObject result = new JSONObject();
        put(result, "label", label()); put(result, "requestedGrants", requestedGrants());
        put(result, "resultCode", resultCode); put(result, "returnedFlags", flags);
        put(result, "writeMode", mode); put(result, "persistRequested", persistGrant());
        put(result, "uri", uri == null ? null : uri.toString());
        put(result, "callerUid", Process.myUid()); put(result, "callerPackage", getPackageName());
        JSONArray clipUris = new JSONArray();
        if (data != null && data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri clipUri = clip.getItemAt(index).getUri();
                clipUris.put(clipUri == null ? JSONObject.NULL : clipUri.toString());
            }
        }
        put(result, "clipUris", clipUris);
        put(result, "callerUserHandle", Process.myUserHandle().toString());
        put(result, "callerUserIdFromUid", Process.myUid() / 100000);
        put(result, "contextApplicationUid", getApplicationContext().getApplicationInfo().uid);
        put(result, "requestedMime", probeMime());
        if (uri != null && uri.getAuthority() != null) {
            ProviderInfo provider = getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            JSONObject info = null;
            if (provider != null) {
                info = new JSONObject();
                put(info, "authority", provider.authority); put(info, "package", provider.packageName);
                put(info, "name", provider.name); put(info, "processName", provider.processName);
                put(info, "applicationUid", provider.applicationInfo.uid); put(info, "flags", provider.flags);
                put(info, "multiprocess", provider.multiprocess); put(info, "exported", provider.exported);
                put(info, "grantUriPermissions", provider.grantUriPermissions);
            }
            put(result, "resolvedProvider", info);
        }
        if (resultCode == RESULT_OK && uri != null) {
            put(result, "activityReadGranted", granted(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION));
            put(result, "activityWriteGranted", granted(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            put(result, "applicationWriteGranted", getApplicationContext().checkUriPermission(uri,
                    Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED);
            put(result, "scope", "Only the exact owned ACTION_CREATE_DOCUMENT result URI");
            final int returnedModes = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            int persistedBeforeModes = 0;
            for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (!permission.getUri().equals(uri)) continue;
                if (permission.isReadPermission()) persistedBeforeModes |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (permission.isWritePermission()) persistedBeforeModes |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                break;
            }
            put(result, "persistedBeforeModes", persistedBeforeModes);
            writeAndRead(result, "activityBeforePersist", getContentResolver(), uri, mode);
            writeAndRead(result, "applicationBeforePersist", getApplicationContext().getContentResolver(), uri, mode);
            put(result, "activityWriteSucceeded", result.optBoolean("activityBeforePersistWriteSucceeded"));
            boolean attemptPersist = persistGrant() &&
                    (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && returnedModes != 0;
            put(result, "persistAttempted", attemptPersist);
            boolean persisted = attemptPersist && attempt(result, "persistReturnedGrantSucceeded", null,
                    () -> getContentResolver().takePersistableUriPermission(uri, returnedModes));
            writeAndRead(result, "activityAfterPersist", getContentResolver(), uri, mode);
            writeAndRead(result, "applicationAfterPersist", getApplicationContext().getContentResolver(), uri, mode);
            put(result, "applicationWriteSucceeded", result.optBoolean("applicationAfterPersistWriteSucceeded"));
            final int newlyPersistedModes = returnedModes & ~persistedBeforeModes;
            if (persisted && newlyPersistedModes != 0) {
                attempt(result, "releaseNewPersistedModesSucceeded", null,
                        () -> getContentResolver().releasePersistableUriPermission(uri, newlyPersistedModes));
            }
        }
        File output = new File(getFilesDir(), "document-grant-" + label() + ".json");
        require(!output.exists(), "Do not overwrite an earlier probe");
        boolean serviceProbe = getIntent().getBooleanExtra("service_probe", false);
        if (serviceProbe && resultCode == RESULT_OK && uri != null) {
            require(!persistGrant(), "Service-owner comparison requires --ez persist_grant false");
            require("content".equals(uri.getScheme()), "Expected owned document content URI");
            boolean attach = getIntent().getBooleanExtra("attach_service_grants", false);
            int modes = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Intent service = new Intent(this, DocumentUriGrantProbeService.class);
            service.putExtra("probe_label", label()); service.putExtra("probe_mode", mode);
            service.putExtra("owned_result_uri", uri.toString()); service.putExtra("owned_caller_uid", Process.myUid());
            service.putExtra("returned_grant_modes", modes); service.putExtra("attach_service_grants", attach);
            service.putExtra("activity_removal_requested_at", SystemClock.elapsedRealtime());
            if (attach) {
                service.setData(uri);
                service.setClipData(ClipData.newRawUri("Owned document grant probe", uri));
                service.addFlags(modes);
            }
            try {
                put(result, "serviceDispatched", startService(service) != null);
                put(result, "attachServiceGrants", attach); put(result, "serviceIntentFlags", service.getFlags());
            } catch (Exception error) {
                put(result, "serviceDispatched", false); put(result, "serviceStartException", error.getClass().getName());
                put(result, "serviceStartMessage", message(error));
            }
            writeNewJson(output, result);
            serviceDispatchedForProof = result.optBoolean("serviceDispatched");
            if (serviceDispatchedForProof) { finishAndRemoveTask(); return; }
        } else writeNewJson(output, result);
        show("Probe recorded: " + label() + "\n" + pretty(result));
    }

    private boolean granted(Uri uri, int modes) {
        return checkUriPermission(uri, Process.myPid(), Process.myUid(), modes) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (serviceDispatchedForProof) {
            JSONObject result = new JSONObject();
            put(result, "label", label()); put(result, "callerUid", Process.myUid());
            put(result, "callerPid", Process.myPid()); put(result, "destroyedElapsedRealtime", SystemClock.elapsedRealtime());
            writeNewJson(new File(getFilesDir(), "document-probe-destroy-" + label() + ".json"), result);
        }
    }

    private void show(String text) {
        TextView view = new TextView(this); view.setText(text); setContentView(view);
    }

    @FunctionalInterface interface ProbeAction { void run() throws Exception; }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void put(JSONObject result, String key, Object value) {
        try { result.put(key, value == null ? JSONObject.NULL : value); }
        catch (JSONException error) { throw new IllegalStateException("Cannot record probe result", error); }
    }

    static String message(Exception error) {
        String text = error.getMessage();
        return text == null ? null : text.substring(0, Math.min(text.length(), 2048));
    }

    static String pretty(JSONObject result) {
        try { return result.toString(2); }
        catch (JSONException error) { throw new IllegalStateException("Cannot serialize probe result", error); }
    }

    static boolean attempt(JSONObject result, String name, String mode, ProbeAction action) {
        put(result, name + "Mode", mode);
        try { action.run(); put(result, name, true); return true; }
        catch (Exception error) {
            put(result, name, false); put(result, name + "Exception", error.getClass().getName());
            put(result, name + "Message", message(error)); return false;
        }
    }

    static void writeAndRead(JSONObject result, String prefix, ContentResolver resolver, Uri uri, String mode) {
        attempt(result, prefix + "WriteSucceeded", mode, () -> {
            OutputStream opened = resolver.openOutputStream(uri, mode);
            require(opened != null, "Provider returned no output stream");
            try (OutputStream output = opened) { output.write(MARKER); output.flush(); }
        });
        attempt(result, prefix + "ReadSucceeded", "r", () -> {
            InputStream opened = resolver.openInputStream(uri);
            require(opened != null, "Provider returned no input stream");
            try (InputStream input = opened) {
                byte[] bytes = new byte[MARKER.length + 1];
                int count = 0;
                while (count < bytes.length) {
                    int read = input.read(bytes, count, bytes.length - count);
                    if (read < 0) break;
                    if (read == 0) continue;
                    count += read;
                }
                put(result, prefix + "ReadBytes", count);
                require(count == MARKER.length && Arrays.equals(Arrays.copyOf(bytes, count), MARKER),
                        "The exact returned document did not contain the synthetic marker");
            }
        });
    }

    static void writeNewJson(File output, JSONObject result) {
        try {
            require(output.createNewFile(), "Do not overwrite an earlier probe receipt");
            try (OutputStream stream = new FileOutputStream(output, false)) {
                stream.write(pretty(result).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { throw new IllegalStateException("Cannot retain owned probe receipt", error); }
    }
}
