package takagi.ru.monica.autofill_ng

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.screens.AutofillSettingsV2Screen
import takagi.ru.monica.ui.theme.MonicaTheme

class HyperOsSaveCompatibilitySettingsTest {
    @get:Rule val compose = createComposeRule()

    /** Host-only setup in an explicitly owned test user; not a UI regression result. */
    @Test fun prepareCompatibilityForExternalColdSaveDriver() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(android.os.Process.myUid() / 100000 > 0 && args.getString("autofillIsolatedUser") == "true" &&
            args.getString("hyperosColdSaveSetup") == "true")
        val mode = requireNotNull(args.getString("hyperosSaveCompatibility"))
        require(mode == "true" || mode == "false")
        AutofillPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
            .setHyperOsSaveCompatibilityEnabled(mode.toBoolean())
    }

    @Test fun compatibilityCanBeEnabledAndDisabledFromSaveSettings() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(android.os.Process.myUid() / 100000 > 0 && args.getString("autofillIsolatedUser") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AutofillPreferences(context)
        val original = preferences.isHyperOsSaveCompatibilityEnabled.first()
        val locale = if (args.getString("hyperosScreenshotLocale") == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US
        try {
            preferences.setHyperOsSaveCompatibilityEnabled(false)
            compose.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
                CompositionLocalProvider(LocalConfiguration provides config,
                    LocalContext provides LocalContext.current.createConfigurationContext(config)) {
                    MonicaTheme(darkTheme = false) {
                        AutofillSettingsV2Screen(onNavigateBack = {}, onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                    }
                }
            }
            val row = compose.onNodeWithTag("hyperos_save_compatibility_setting")
            row.performScrollTo()
            val control = compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("hyperos_save_compatibility_setting"))).onFirst()
            compose.waitUntil(5000) { control.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off }
            control.performClick()
            compose.waitUntil(5000) { control.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On }
            assertTrue(withTimeout(5000) { AutofillPreferences(context).isHyperOsSaveCompatibilityEnabled.first { it } })
            if (!android.provider.Settings.canDrawOverlays(context)) {
                compose.onNodeWithTag("hyperos_save_overlay_permission").performScrollTo().assertIsDisplayed()
            }
            File(context.filesDir, "hyperos-save-toggle-${locale.language}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            row.performScrollTo()
            control.performClick()
            compose.waitUntil(5000) { control.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off }
            assertFalse(withTimeout(5000) { AutofillPreferences(context).isHyperOsSaveCompatibilityEnabled.first { !it } })
        } finally {
            preferences.setHyperOsSaveCompatibilityEnabled(original)
        }
    }
}
