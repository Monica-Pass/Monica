package takagi.ru.monica.passkey.hardware

import android.content.Intent
import android.content.pm.PackageManager
import androidx.credentials.*
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

@SdkSuppress(minSdkVersion = 34)
class HardwareProviderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = """{"challenge":"AQIDBA","rp":{"id":"example.com","name":"Example"},"user":{"id":"BQYH","name":"fixture","displayName":"Fixture"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""

    @Test fun frameworkCreateRequestSuppliesTrustedCallerAndHashRatherThanIntentExtras() {
        val pkg = context.packageManager.getPackageInfo("com.android.chrome", PackageManager.GET_SIGNING_CERTIFICATES)
        val caller = android.service.credentials.CallingAppInfo("com.android.chrome", pkg.signingInfo!!, "https://login.example.com")
        val hash = ByteArray(32) { it.toByte() }
        val request = CreatePublicKeyCredentialRequest(json, clientDataHash = hash)
        val intent = Intent().putExtra("android.service.credentials.extra.CREATE_CREDENTIAL_REQUEST",
            android.service.credentials.CreateCredentialRequest(caller, request.type, request.credentialData))
            .putExtra("REQUEST_JSON", "{malicious}").putExtra("RP_ID", "evil.example")
        val final = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)!!
        val credential = final.callingRequest as CreatePublicKeyCredentialRequest
        assertEquals(json, credential.requestJson)
        assertArrayEquals(hash, credential.clientDataHash)
        assertEquals("https://login.example.com", HardwareRequestOrigin.resolve(context, final.callingAppInfo, "example.com", credential.clientDataHash))
        val activityIntent = Intent(intent).setClass(context, HardwareSecurityKeyActivity::class.java).setAction(HardwareSecurityKeyActivity.ACTION_CREATE)
        ActivityScenario.launch<HardwareSecurityKeyActivity>(activityIntent).use { scenario ->
            var actual: HardwareWebAuthn? = null
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                scenario.onActivity { activity ->
                    val delegate = HardwareSecurityKeyActivity::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
                    actual = (delegate.value as HardwareKeyUiState).request
                }
                if (actual == null) Thread.sleep(25)
            } while (actual == null && System.nanoTime() < deadline)
            assertEquals("example.com", actual?.rpId)
            assertArrayEquals(hash, actual!!.clientHash)
        }
    }

    @Test fun fakeDelegatedOriginWithMonicaCertificateIsRejected() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val caller = androidx.credentials.provider.CallingAppInfo(context.packageName, pkg.signingInfo!!, "https://example.com")
        assertThrows(IllegalStateException::class.java) { HardwareRequestOrigin.resolve(context, caller, "example.com", ByteArray(32)) }
    }

    @Test fun frameworkResponsesPreserveHardwareWebauthnJsonAndCancellationType() {
        val createJson = JSONObject().put("id", "AQI").put("rawId", "AQI").put("type", "public-key").put("authenticatorAttachment", "cross-platform")
            .put("response", JSONObject().put("attestationObject", "oA")).toString()
        val create = Intent()
        PendingIntentHandler.setCreateCredentialResponse(create, CreatePublicKeyCredentialResponse(createJson))
        assertEquals(createJson, (PendingIntentHandler.retrieveCreateCredentialResponse("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", create) as CreatePublicKeyCredentialResponse).registrationResponseJson)
        val getJson = JSONObject().put("id", "AQI").put("rawId", "AQI").put("type", "public-key").put("response", JSONObject().put("signature", "AQI")).toString()
        val get = Intent()
        PendingIntentHandler.setGetCredentialResponse(get, GetCredentialResponse(PublicKeyCredential(getJson)))
        assertEquals(getJson, (PendingIntentHandler.retrieveGetCredentialResponse(get)!!.credential as PublicKeyCredential).authenticationResponseJson)
        val cancelled = Intent()
        PendingIntentHandler.setCreateCredentialException(cancelled, androidx.credentials.exceptions.CreateCredentialCancellationException())
        assertTrue(PendingIntentHandler.retrieveCreateCredentialException(cancelled) is androidx.credentials.exceptions.CreateCredentialCancellationException)
    }
}
