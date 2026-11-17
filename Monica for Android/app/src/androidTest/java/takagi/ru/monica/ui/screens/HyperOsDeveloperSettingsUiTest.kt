package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.viewmodel.SettingsViewModel

class HyperOsDeveloperSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val chinese = Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    private val localized = context.createConfigurationContext(chinese)

    private fun ownUser() {
        assumeTrue(android.os.Process.myUid() / 100000 > 0 &&
            InstrumentationRegistry.getArguments().getString("autofillIsolatedUser") == "true")
    }

    @Test fun developerEntryRestoresSubpageAndBothBackActionsReturnToDeveloperSettings() {
        ownUser()
        val model = SettingsViewModel(SettingsManager(context))
        val store = ViewModelStore().apply { put("hyperos-developer-ui", model) }
        var parentBack = 0
        lateinit var dispatcher: OnBackPressedDispatcher
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent {
                dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                val registryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner,
                    LocalConfiguration provides chinese, LocalContext provides localized) {
                    MonicaTheme(darkTheme = false) {
                        DeveloperSettingsScreen(model, onNavigateBack = { parentBack++ }, onNavigateToLogs = {})
                    }
                }
            }
            compose.onNodeWithTag("developer_hyperos_compatibility_entry").performScrollTo().performClick()
            compose.onNodeWithTag("hyperos_compatibility_screen").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("hyperos_compatibility_screen").assertIsDisplayed()
            compose.onNodeWithContentDescription(localized.getString(R.string.back)).performClick()
            compose.onNodeWithTag("hyperos_compatibility_screen").assertDoesNotExist()
            compose.onNodeWithTag("developer_hyperos_compatibility_entry").performScrollTo().performClick()
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.onNodeWithTag("hyperos_compatibility_screen").assertDoesNotExist()
            compose.onNodeWithTag("developer_hyperos_compatibility_entry").performScrollTo().assertIsDisplayed()
            assertEquals(0, parentBack)
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun defaultManagerEntryOpensExistingReviewedSheetWithoutChangingSystemServices() {
        ownUser()
        val keys = listOf("autofill_service", "credential_service", "credential_service_primary")
        val original = keys.associateWith { readSecureSetting(it) }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides chinese, LocalContext provides localized) {
                MonicaTheme(darkTheme = true) { HyperOsCompatibilitySettingsScreen(onNavigateBack = {}) }
            }
        }
        compose.onNodeWithTag("hyperos_default_manager_entry").performScrollTo().performClick()
        compose.onNodeWithTag("default_manager_apply").assertIsDisplayed()
        compose.onNodeWithTag("default_manager_restore").assertIsDisplayed()
        assertEquals(original, keys.associateWith { readSecureSetting(it) })
    }

    @Test fun reducedAnimationsPersistToGeneralSettingsAndLargeControlsRemainReachable(): Unit = runBlocking {
        ownUser()
        val manager = SettingsManager(context)
        val original = manager.settingsFlow.first().reduceAnimations
        try {
            manager.updateReduceAnimations(false)
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalConfiguration provides chinese, LocalContext provides localized,
                    LocalDensity provides Density(density, 1.6f)) {
                    MonicaTheme(darkTheme = true) {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("hyperos_large_frame")) {
                            HyperOsCompatibilitySettingsScreen(onNavigateBack = {})
                        }
                    }
                }
            }
            val row = compose.onNodeWithTag("hyperos_reduce_animations_setting")
            row.performScrollTo().assertIsDisplayed().assertIsOff().performClick()
            compose.waitUntil(5000) { row.isOn() }
            assertTrue(withTimeout(5000) { SettingsManager(context).settingsFlow.first { it.reduceAnimations }.reduceAnimations })
            File(context.filesDir,"hyperos-settings-dark-large.png").outputStream().use {
                compose.onNodeWithTag("hyperos_large_frame").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG,100,it)
            }
            row.performClick()
            compose.waitUntil(5000) { row.isOff() }
            assertFalse(withTimeout(5000) { SettingsManager(context).settingsFlow.first { !it.reduceAnimations }.reduceAnimations })
            compose.onNodeWithTag("hyperos_default_manager_entry").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("hyperos_save_compatibility_setting").performScrollTo().assertIsDisplayed()
        } finally { manager.updateReduceAnimations(original) }
    }

    private fun readSecureSetting(key: String): String {
        require(key in setOf("autofill_service", "credential_service", "credential_service_primary"))
        val user = android.os.Process.myUid() / 100000
        return ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("settings --user $user get secure $key")
        ).bufferedReader().use { it.readText().trim().also { value ->
            check(!value.startsWith("Error") && !value.contains("Exception"))
        } }
    }

    private fun SemanticsNodeInteraction.isOn() = runCatching { assertIsOn(); true }.getOrDefault(false)
    private fun SemanticsNodeInteraction.isOff() = runCatching { assertIsOff(); true }.getOrDefault(false)
}
