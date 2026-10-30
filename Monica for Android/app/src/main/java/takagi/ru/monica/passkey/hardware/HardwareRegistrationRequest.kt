package takagi.ru.monica.passkey.hardware

import android.content.Context
import android.content.Intent
import android.nfc.Tag
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object HardwareRegistrationRequest {
    fun allowsHardware(source: Intent): Boolean = runCatching {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(source)?.callingRequest
            as? CreatePublicKeyCredentialRequest ?: return false
        JSONObject(request.requestJson).optJSONObject("authenticatorSelection")?.optString("authenticatorAttachment") != "platform"
    }.getOrDefault(false)

    /** Preserve the actual framework request, including caller and clientDataHash. */
    fun forward(context: Context, source: Intent, nfcTag: Tag? = null): Intent {
        check(allowsHardware(source)) { "Hardware registration is unavailable for this request" }
        return Intent(source).setClass(context, HardwareSecurityKeyActivity::class.java)
            // The framework entry may be a new task; the hardware screen must return to this activity.
            .setFlags(0)
            .setAction(HardwareSecurityKeyActivity.ACTION_CREATE)
            .setData(android.net.Uri.parse("monica-hardware://create/${java.util.UUID.randomUUID()}"))
            .also { if (nfcTag != null) it.putExtra(HardwareSecurityKeyActivity.EXTRA_NFC_TAG, nfcTag) }
    }
}
