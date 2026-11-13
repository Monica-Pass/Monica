package takagi.ru.monica.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ComponentName
import android.credentials.CredentialManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.passkey.MonicaCredentialProviderService
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.ui.theme.MonicaTheme

/** Clicks the production settings row; captures only the outgoing system navigation. */
@SdkSuppress(minSdkVersion = 35)
class CredentialProviderSettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val userId get() = android.os.Process.myUid() / 100000
    private val component get() = ComponentName(targetContext, MonicaCredentialProviderService::class.java)
    private var originalServices: String? = null
    private var capturedServices = false

    private enum class CredentialServiceFault { NONE, ABSENT, BROKEN }

    private class RecordingContext(
        base: Context,
        private val serviceFault: CredentialServiceFault,
        private val launch: (Intent) -> Unit,
    ) : ContextWrapper(base) {
        val attempted = mutableListOf<Intent>()
        val accepted = mutableListOf<Intent>()
        override fun getSystemService(name: String): Any? {
            if (name == Context.CREDENTIAL_SERVICE) {
                when (serviceFault) {
                    CredentialServiceFault.ABSENT -> return null
                    CredentialServiceFault.BROKEN -> throw NullPointerException("Synthetic broken system service")
                    CredentialServiceFault.NONE -> Unit
                }
            }
            return super.getSystemService(name)
        }
        override fun startActivity(intent: Intent) {
            attempted += Intent(intent)
            launch(intent)
            accepted += Intent(intent)
        }
    }

    @Before fun prepareScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(runBlocking { SecureStorageStartup.prepareForEntry(context) } is SecureStartupResult.Ready)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(5_000) {
            var ready = false
            compose.runOnUiThread {
                val activity = compose.activity
                ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    !activity.isChangingConfigurations && !activity.isDestroyed &&
                    !activity.isFinishing && activity.window.decorView.isAttachedToWindow &&
                    activity.hasWindowFocus() &&
                    activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            ready
        }
        check(userId > 0) { "Run provider-setting fixtures only in an isolated secondary test user" }
        originalServices = readCredentialServices()
        capturedServices = true
        setOurProviderEnabled(false)
    }

    private fun readCredentialServices(): String? {
        // This hidden key is not @Readable for ordinary app processes. Read through
        // shell in the same isolated user; never depend on a testOnly app flag.
        val descriptor = instrumentation.uiAutomation.executeShellCommand("settings --user $userId list secure")
        val lines = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
        check(lines.any { it.startsWith("android_id=") }) { "Unable to read current-user settings fixture" }
        val matches = lines.filter { it.startsWith("credential_service=") }
        check(matches.size <= 1) { "Ambiguous current-user credential setting" }
        return matches.singleOrNull()?.substringAfter('=')
    }

    private fun writeCredentialServices(value: String?) {
        if (value == null) {
            // Some Settings providers store putString(null) as the literal string "null".
            // Delete an originally absent key explicitly; preserve every non-null value exactly.
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "settings --user $userId delete secure credential_service"
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertEquals("Expected absent current-user credential setting", null, readCredentialServices())
            return
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(targetContext.contentResolver, "credential_service", value))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun setOurProviderEnabled(enabled: Boolean) {
        val current = readCredentialServices()
        val others = current.orEmpty().split(':').filter { token ->
            token.isNotEmpty() && ComponentName.unflattenFromString(token) != component
        }
        val updated = if (enabled) others + component.flattenToString() else others
        writeCredentialServices(updated.joinToString(":").ifEmpty { null })
        // Assert the real framework observes the requested state, without a mock manager.
        compose.waitUntil(5_000) {
            targetContext.getSystemService(CredentialManager::class.java)
                ?.isEnabledCredentialProviderService(component) == enabled
        }
    }

    @After fun restoreCredentialProviders() {
        if (capturedServices) {
            writeCredentialServices(originalServices)
            assertEquals(originalServices, readCredentialServices())
        }
    }

    private fun clickCredentialSettings(serviceFault: CredentialServiceFault = CredentialServiceFault.NONE,
        launch: (Intent) -> Unit = {}): RecordingContext {
        val context = RecordingContext(compose.activity, serviceFault, launch)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MonicaTheme {
                    AutofillSettingsV2Screen(onNavigateBack = {},
                        onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.autofill_system_passkey_settings))
            .performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        return context
    }

    @Test fun enabledProviderOpensSecuritySettingsWithoutEnableRequest() {
        setOurProviderEnabled(true)
        val context = clickCredentialSettings()
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.attempted.map { it.action })
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.accepted.map { it.action })
        }
    }

    @Test fun absentCredentialServiceOpensSecuritySettings() {
        val context = clickCredentialSettings(CredentialServiceFault.ABSENT)
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.attempted.map { it.action })
        }
    }

    @Test fun brokenCredentialServiceOpensSecuritySettings() {
        val context = clickCredentialSettings(CredentialServiceFault.BROKEN)
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.attempted.map { it.action })
        }
    }

    @Test fun clickPrefersCredentialProviderSettingsForThisApp() {
        val context = clickCredentialSettings()
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_CREDENTIAL_PROVIDER), context.attempted.map { it.action })
            assertEquals("package:${context.packageName}", context.attempted.single().dataString)
            assertEquals(1, context.accepted.size)
        }
    }

    @Test fun missingCredentialSettingsFallsBackToSecuritySettings() {
        val context = clickCredentialSettings { intent ->
            if (intent.action == Settings.ACTION_CREDENTIAL_PROVIDER) throw ActivityNotFoundException("Synthetic unavailable settings")
        }
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_CREDENTIAL_PROVIDER, Settings.ACTION_SECURITY_SETTINGS),
                context.attempted.map { it.action })
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.accepted.map { it.action })
        }
    }

    @Test fun deniedCredentialSettingsFallsBackToSecuritySettings() {
        val context = clickCredentialSettings { intent ->
            if (intent.action == Settings.ACTION_CREDENTIAL_PROVIDER) throw SecurityException("Synthetic settings denial")
        }
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_CREDENTIAL_PROVIDER, Settings.ACTION_SECURITY_SETTINGS),
                context.attempted.map { it.action })
            assertEquals(listOf(Settings.ACTION_SECURITY_SETTINGS), context.accepted.map { it.action })
        }
    }

    @Test fun unavailableCredentialAndSecuritySettingsFallBackToGeneralSettings() {
        val context = clickCredentialSettings { intent ->
            when (intent.action) {
                Settings.ACTION_CREDENTIAL_PROVIDER -> throw ActivityNotFoundException("Synthetic unavailable settings")
                Settings.ACTION_SECURITY_SETTINGS -> throw SecurityException("Synthetic settings denial")
            }
        }
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_CREDENTIAL_PROVIDER, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS),
                context.attempted.map { it.action })
            assertEquals(listOf(Settings.ACTION_SETTINGS), context.accepted.map { it.action })
        }
    }

    @Test fun allSettingsUnavailableLeaveThePageUsable() {
        val context = clickCredentialSettings { throw ActivityNotFoundException("Synthetic unavailable settings") }
        compose.runOnIdle {
            assertEquals(listOf(Settings.ACTION_CREDENTIAL_PROVIDER, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS),
                context.attempted.map { it.action })
            assertTrue(context.accepted.isEmpty())
        }
        compose.onNodeWithContentDescription(context.getString(R.string.autofill_settings_back))
            .assertIsDisplayed().assertIsEnabled()
    }
}
