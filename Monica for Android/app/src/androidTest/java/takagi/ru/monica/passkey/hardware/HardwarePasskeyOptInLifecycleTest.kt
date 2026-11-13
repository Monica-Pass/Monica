package takagi.ru.monica.passkey.hardware

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.passkey.MonicaCredentialProviderService
import takagi.ru.monica.passkey.PasskeyCreateActivity

@SdkSuppress(minSdkVersion = 34)
class HardwarePasskeyOptInLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preference: HardwarePasskeyPreferenceFixture
    private val monitor = PasskeyCreateActivity::class.java.getDeclaredField("hardwarePresenceMonitor").apply { isAccessible = true }
    private val presence = PasskeyCreateActivity::class.java.getDeclaredField("hardwarePresence\$delegate").apply { isAccessible = true }

    @Before fun setUp() { preference = HardwarePasskeyPreferenceFixture(context); preference.removeChoice() }
    @After fun restore() = preference.restore()

    private fun source(): Intent {
        val json = """{"challenge":"AQIDBA","rp":{"id":"example.com","name":"Example"},"user":{"id":"BQYH","name":"hardware-opt-in-fixture","displayName":"Opt-in fixture"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
        val caller = trustedHardwareBrowserCaller(context)
        val request = CreatePublicKeyCredentialRequest(json, clientDataHash = ByteArray(32) { it.toByte() })
        return Intent(context, PasskeyCreateActivity::class.java)
            .putExtra("android.service.credentials.extra.CREATE_CREDENTIAL_REQUEST", android.service.credentials.CreateCredentialRequest(caller, request.type, request.credentialData))
            .putExtra(MonicaCredentialProviderService.EXTRA_REQUEST_JSON, json)
            .putExtra(MonicaCredentialProviderService.EXTRA_RP_ID, "example.com")
            .putExtra(MonicaCredentialProviderService.EXTRA_USER_NAME, "hardware-opt-in-fixture")
            .putExtra(MonicaCredentialProviderService.EXTRA_USER_DISPLAY_NAME, "Opt-in fixture")
    }

    @Test fun defaultOffRegistrationDoesNotCreateDiscoveryMonitor() {
        ActivityScenario.launch<PasskeyCreateActivity>(source()).use { scenario ->
            compose.onNodeWithTag("passkey-save-location").performScrollTo().assertIsDisplayed()
            scenario.onActivity { assertNull(monitor.get(it)) }
            compose.onNodeWithText(context.getString(R.string.bind_password)).assertExists()
            compose.onNodeWithTag("passkey-save-location").performClick()
            compose.onNodeWithTag("passkey-target-hardware").assertDoesNotExist()
            compose.onNodeWithTag("passkey-target-software").assertDoesNotExist()
        }
    }

    @Test fun disablingAnOpenRegistrationClosesDiscoveryAndDropsSelectedHardwareTarget() {
        HardwarePasskeyPreferences.setEnabled(context, true)
        ActivityScenario.launch<PasskeyCreateActivity>(source()).use { scenario ->
            var oldMonitor: HardwareKeyPresenceMonitor? = null
            compose.onNodeWithTag("passkey-save-location").performScrollTo()
            scenario.onActivity {
                oldMonitor = monitor.get(it) as HardwareKeyPresenceMonitor
                @Suppress("UNCHECKED_CAST")
                (presence.get(it) as MutableState<HardwareKeyPresence>).value = HardwareKeyPresence(usbCount = 1)
            }
            compose.onNodeWithTag("passkey-save-location").performClick()
            compose.onNodeWithTag("passkey-target-hardware").performClick()
            compose.onNodeWithText(context.getString(R.string.bind_password)).assertDoesNotExist()
            scenario.onActivity { HardwarePasskeyPreferences.setEnabled(it, false) }
            compose.waitUntil(5_000) {
                var stopped = false
                scenario.onActivity { stopped = monitor.get(it) == null }
                stopped
            }
            scenario.onActivity {
                for (field in listOf("registered", "resumed")) {
                    val value = HardwareKeyPresenceMonitor::class.java.getDeclaredField(field).apply { isAccessible = true }.getBoolean(oldMonitor)
                    assertFalse("Discovery $field must be false", value)
                }
                assertNull(oldMonitor!!.detectedNfcTag)
                @Suppress("UNCHECKED_CAST")
                assertFalse((presence.get(it) as MutableState<HardwareKeyPresence>).value.available)
            }
            compose.onNodeWithText(context.getString(R.string.bind_password)).assertExists()
            compose.onNodeWithTag("passkey-save-location").performScrollTo().performClick()
            compose.onNodeWithTag("passkey-target-hardware").assertDoesNotExist()
        }
    }

    @Test fun managementTogglePersistsAcrossRecreationAndBackKeepsChoice() {
        ActivityScenario.launchActivityForResult<HardwareSecurityKeyActivity>(Intent(context, HardwareSecurityKeyActivity::class.java)).use { scenario ->
            val title = context.getString(R.string.hardware_key_enable)
            compose.onNodeWithText(title).assertIsOff().performClick().assertIsOn()
            scenario.recreate()
            compose.onNodeWithText(title).assertIsOn()
            compose.onNodeWithText(context.getString(R.string.hardware_key_find_usb)).assertExists()
            compose.onNodeWithText(title).performClick().assertIsOff()
            compose.onNodeWithText(context.getString(R.string.hardware_key_find_usb)).assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText(title).assertIsOff()
            compose.onNodeWithContentDescription(context.getString(R.string.hardware_key_back)).performClick()
            assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            assertFalse(HardwarePasskeyPreferences.isEnabled(context))
        }
    }

    @Test fun disabledCachedProviderDestinationsReturnCancellationForBothRequestTypes() {
        // These actions are the destinations stored in earlier provider PendingIntents.
        for (action in listOf(HardwareSecurityKeyActivity.ACTION_CREATE, HardwareSecurityKeyActivity.ACTION_GET)) {
            val target = source().setClass(context, HardwareSecurityKeyActivity::class.java).setAction(action)
            ActivityScenario.launchActivityForResult<HardwareSecurityKeyActivity>(target).use { scenario ->
                val result = scenario.result
                assertEquals(Activity.RESULT_CANCELED, result.resultCode)
                if (action == HardwareSecurityKeyActivity.ACTION_CREATE) {
                    assertTrue(PendingIntentHandler.retrieveCreateCredentialException(result.resultData) is CreateCredentialCancellationException)
                } else {
                    assertTrue(PendingIntentHandler.retrieveGetCredentialException(result.resultData) is GetCredentialCancellationException)
                }
            }
        }
    }

    @Test fun disablingRejectsACompletedHardwareResponseBeforeObserverCanRun() {
        HardwarePasskeyPreferences.setEnabled(context, true)
        val target = source().setClass(context, HardwareSecurityKeyActivity::class.java).setAction(HardwareSecurityKeyActivity.ACTION_CREATE)
        ActivityScenario.launchActivityForResult<HardwareSecurityKeyActivity>(target).use { scenario ->
            scenario.onActivity {
                HardwarePasskeyPreferences.setEnabled(it, false)
                // Same main-thread callback: prove the final gate does not rely on flow delivery.
                val complete = HardwareSecurityKeyActivity::class.java.getDeclaredMethod("returnCredential", String::class.java,
                    Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                complete.invoke(it, """{"id":"AQI","rawId":"AQI","type":"public-key","response":{"attestationObject":"oA"}}""", true)
            }
            val result = scenario.result
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
            assertTrue(PendingIntentHandler.retrieveCreateCredentialException(result.resultData) is CreateCredentialCancellationException)
        }
    }
}
