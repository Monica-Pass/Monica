package takagi.ru.monica.passkey.hardware

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.passkey.MonicaCredentialProviderService
import takagi.ru.monica.passkey.PasskeyCreateActivity

@SdkSuppress(minSdkVersion = 34)
class HardwareRegistrationFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val presence = PasskeyCreateActivity::class.java.getDeclaredField("hardwarePresence\$delegate").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun device(activity: PasskeyCreateActivity, connected: Boolean) {
        // Only replace this activity's discovery hint; transport and request validation remain real.
        (presence.get(activity) as MutableState<HardwareKeyPresence>).value = HardwareKeyPresence(usbCount = if (connected) 1 else 0)
    }

    private fun source(): Intent {
        val json = """{"challenge":"AQIDBA","rp":{"id":"example.com","name":"Example"},"user":{"id":"BQYH","name":"hardware-flow-fixture","displayName":"Hardware fixture"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
        val pkg = context.packageManager.getPackageInfo("com.android.chrome", PackageManager.GET_SIGNING_CERTIFICATES)
        val caller = android.service.credentials.CallingAppInfo("com.android.chrome", pkg.signingInfo!!, "https://login.example.com")
        val request = CreatePublicKeyCredentialRequest(json, clientDataHash = ByteArray(32) { it.toByte() })
        return Intent(context, PasskeyCreateActivity::class.java)
            .putExtra("android.service.credentials.extra.CREATE_CREDENTIAL_REQUEST", android.service.credentials.CreateCredentialRequest(caller, request.type, request.credentialData))
            .putExtra(MonicaCredentialProviderService.EXTRA_REQUEST_JSON, json)
            .putExtra(MonicaCredentialProviderService.EXTRA_RP_ID, "example.com")
            .putExtra(MonicaCredentialProviderService.EXTRA_USER_NAME, "hardware-flow-fixture")
            .putExtra(MonicaCredentialProviderService.EXTRA_USER_DISPLAY_NAME, "Hardware fixture")
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun hardwareSelectionSurvivesRecreationAndCancellationWithoutSoftwareWrite() {
        val dao = PasswordDatabase.getDatabase(context).passkeyDao()
        val before = runBlocking { dao.getAllPasskeysSync() }
        ActivityScenario.launch<PasskeyCreateActivity>(source()).use { scenario ->
            scenario.onActivity { device(it, true) }
            compose.onNodeWithTag("passkey-save-location").performScrollTo().performClick()
            compose.onNodeWithTag("passkey-target-hardware").assertIsDisplayed()
            screenshot("hardware-key-register-picker.png")
            compose.onNodeWithTag("passkey-target-hardware").performClick()
            compose.onNodeWithTag("passkey-create-confirm").assertIsEnabled()
            screenshot("hardware-key-register-selected.png")
            scenario.onActivity { device(it, false) }
            compose.onNodeWithTag("passkey-create-confirm").assertIsNotEnabled()
            scenario.recreate()
            compose.onNodeWithTag("passkey-create-confirm").assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.bind_password)).assertDoesNotExist()
            scenario.onActivity { device(it, true) }
            compose.onNodeWithTag("passkey-create-confirm").performClick()
            var hardware: HardwareSecurityKeyActivity? = null
            compose.waitUntil(10000) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    hardware = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<HardwareSecurityKeyActivity>().firstOrNull()
                }
                hardware != null
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val cancel = HardwareSecurityKeyActivity::class.java.getDeclaredMethod("cancelAndFinish").apply { isAccessible = true }
                cancel.invoke(hardware)
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("passkey-create-confirm").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("passkey-create-confirm").assertIsNotEnabled()
            scenario.onActivity { assertFalse(it.isFinishing) }
            assertTrue("Hardware cancellation must preserve software records", before == runBlocking { dao.getAllPasskeysSync() })
        }
    }

    @Test fun completedHardwareResponseReturnsToFrameworkWithoutSoftwareWrite() {
        val dao = PasswordDatabase.getDatabase(context).passkeyDao()
        val before = runBlocking { dao.getAllPasskeysSync() }
        val response = """{"id":"AQI","rawId":"AQI","type":"public-key","authenticatorAttachment":"cross-platform","response":{"attestationObject":"oA"}}"""
        ActivityScenario.launchActivityForResult<PasskeyCreateActivity>(source()).use { scenario ->
            scenario.onActivity { device(it, true) }
            compose.onNodeWithTag("passkey-save-location").performScrollTo().performClick()
            compose.onNodeWithTag("passkey-target-hardware").performClick()
            compose.onNodeWithTag("passkey-create-confirm").performClick()
            var hardware: HardwareSecurityKeyActivity? = null
            compose.waitUntil(10000) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    hardware = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<HardwareSecurityKeyActivity>().firstOrNull()
                }
                hardware != null
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Only synthesize the transport result; exercise the production child/parent return path.
                val complete = HardwareSecurityKeyActivity::class.java.getDeclaredMethod("returnCredential", String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                complete.invoke(hardware, response, true)
            }
            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            val credential = PendingIntentHandler.retrieveCreateCredentialResponse("androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL", result.resultData) as CreatePublicKeyCredentialResponse
            assertEquals(response, credential.registrationResponseJson)
            assertTrue("Hardware completion must preserve software records", before == runBlocking { dao.getAllPasskeysSync() })
        }
    }
}
