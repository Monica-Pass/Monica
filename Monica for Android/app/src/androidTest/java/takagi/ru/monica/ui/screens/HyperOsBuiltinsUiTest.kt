package takagi.ru.monica.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.defaultmanager.*
import takagi.ru.monica.autofill_ng.privilege.PrivilegeAccess
import takagi.ru.monica.autofill_ng.privilege.PrivilegeBackend
import takagi.ru.monica.hyperos.HyperOsModuleConnection
import takagi.ru.monica.hyperos.HyperOsModuleState
import takagi.ru.monica.hyperos.hooks.HookPolicy
import takagi.ru.monica.ui.theme.MonicaTheme

class HyperOsBuiltinsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val user get() = android.os.Process.myUid() / 100000
    @Before fun isolated() {
        check(user > 0 && InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
        check(context.packageName.startsWith("takagi.ru.monica.perf328"))
    }

    private fun systemSettings(): Map<String,String> = SettingKey.entries.associate { key ->
        key.wireName to ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "settings --user $user get secure ${key.wireName}"
        )).bufferedReader().use { it.readText().trim() }
    }
    @Test fun diagnosticNavigationMonitoringAndBackNeverWriteSystemSettings() = runBlocking {
        val before = systemSettings()
        val monitor = HyperOsDiagnostics.monitoring(context)
        val backend = PrivilegeAccess.backend(context)
        lateinit var dispatcher: OnBackPressedDispatcher
        try {
            PrivilegeAccess.select(context, PrivilegeBackend.SHIZUKU)
            HyperOsDiagnostics.setMonitoring(context, false)
            compose.setContent {
                dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                MonicaTheme { HyperOsCompatibilitySettingsScreen {} }
            }
            compose.onNodeWithTag("hyperos_diagnostics_entry").performScrollTo().performClick()
            compose.onNodeWithTag("hyperos_diagnostics_screen").assertIsDisplayed()
            compose.onNodeWithTag("hyperos_drift_toggle").performScrollTo().assertIsOff().performClick()
            compose.waitUntil(5000) { HyperOsDiagnostics.monitoring(context) }
            compose.onNodeWithTag("hyperos_diagnostics_refresh").performScrollTo().performClick()
            compose.onNodeWithTag("hyperos_diagnostics_repair").performScrollTo().performClick()
            compose.onNodeWithTag("default_manager_apply").assertIsDisplayed().assertIsNotEnabled()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("default_manager_apply").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("hyperos_diagnostics_screen").assertIsDisplayed()
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.onNodeWithTag("hyperos_compatibility_screen").assertIsDisplayed()
            assertEquals(before, systemSettings())
        } finally {
            HyperOsDiagnostics.setMonitoring(context, monitor)
            PrivilegeAccess.select(context, backend)
        }
    }
    @Test fun unsupportedFrameworkControlsStayDisabledAndNavigationReturns() {
        val before = systemSettings()
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MonicaTheme(darkTheme = true) { HyperOsCompatibilitySettingsScreen {} }
        }
        compose.onNodeWithTag("hyperos_module_entry").performScrollTo().performClick()
        for (key in HookPolicy.KEYS) compose.onNodeWithTag("hyperos_hook_$key").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("hyperos_compatibility_screen").assertIsDisplayed()
        assertEquals(before, systemSettings())
    }
    @Test fun moduleConsentCancelConfirmDisableAndLargeTextAreReachable() {
        val writes = mutableListOf<Pair<String,Boolean>>()
        var enabled by mutableStateOf(emptySet<String>())
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MonicaTheme(darkTheme = true) {
                    HyperOsModuleSettingsContent(HyperOsModuleState(true, true, enabled, setOf("com.miui.securitycenter")), false, false, {}, {}) { key, value ->
                        writes += key to value; enabled = if (value) enabled + key else enabled - key
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("hyperos_hook_${HookPolicy.RESET}")
        row.performScrollTo().performClick()
        assertTrue(writes.isEmpty())
        compose.onNodeWithTag("hyperos_hook_cancel").performClick()
        row.assertIsOff()
        row.performClick()
        compose.onNodeWithTag("hyperos_hook_confirm").performClick()
        row.assertIsOn()
        assertEquals(listOf(HookPolicy.RESET to true), writes)
        row.performClick().assertIsOff()
        assertEquals(listOf(HookPolicy.RESET to true, HookPolicy.RESET to false), writes)
        compose.onNodeWithTag("hyperos_hook_${HookPolicy.HYBRID}").performScrollTo().assertIsDisplayed()
        File(context.filesDir,"hyperos-builtins-module-dark-large.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun diagnosticsRecognizeDeclaredServiceAndKeepUnreadableStateUnknown() = runBlocking {
        val backend = PrivilegeAccess.backend(context)
        try {
            PrivilegeAccess.select(context, PrivilegeBackend.SHIZUKU)
            val result = HyperOsDiagnostics.read(context)
            assertEquals(HyperOsCheck.YES, result.declaration)
            assertEquals(HyperOsCheck.UNKNOWN, result.configuration.credential)
            assertNotEquals(HyperOsDrift.CHANGED, result.configuration.drift)
        } finally { PrivilegeAccess.select(context, backend) }
    }
    @Test fun untrustedCallerCannotSupplyFrameworkBinder() {
        try {
            context.contentResolver.call(Uri.parse("content://${context.packageName}.XposedService"), "sendBinder", null, Bundle())
            fail("Application UID must not provide a framework binder")
        } catch (_: SecurityException) { }
    }
    @Test fun noFrameworkCannotEnableOrPersistModuleOptions() = runBlocking {
        HyperOsModuleConnection.register()
        assertFalse(HyperOsModuleConnection.read().connected)
        assertTrue(runCatching { HyperOsModuleConnection.set(HookPolicy.RESET, true) }.isFailure)
        assertTrue(HyperOsModuleConnection.read().options.isEmpty())
    }
    @Test fun baselineIsUserBoundAndUnknownReadDoesNotDeleteIt() {
        val prefs = context.getSharedPreferences("hyperos_diagnostics", Context.MODE_PRIVATE)
        val before = prefs.getString("verified", null)
        try {
            val baseline = SettingsSnapshot(user, SettingKey.entries.associateWith { null })
            HyperOsDiagnostics.verified(context, baseline)
            assertEquals(baseline, HyperOsDiagnostics.baseline(context))
            assertTrue(runCatching { HyperOsDiagnostics.verified(context, baseline.copy(userId = 0)) }.isFailure)
            assertEquals(baseline, HyperOsDiagnostics.baseline(context))
            HyperOsDiagnostics.clearVerified(context)
            assertNull(HyperOsDiagnostics.baseline(context))
        } finally { prefs.edit().putString("verified", before).commit() }
    }
}
