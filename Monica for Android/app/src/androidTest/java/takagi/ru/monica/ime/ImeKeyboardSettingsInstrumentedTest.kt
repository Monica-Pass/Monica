package takagi.ru.monica.ime

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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.ui.screens.AutofillSettingsV2Screen
import takagi.ru.monica.ui.theme.MonicaTheme

class ImeKeyboardSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothKeyboardOptionsCanBeEnabledAndDisabledFromAutofillSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AutofillPreferences(context)
        val original = preferences.imeKeyboardOptions.first()
        try {
            preferences.setImeScramblePin(false)
            preferences.setImeHidePinPreview(false)
            compose.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.US) }
                CompositionLocalProvider(LocalConfiguration provides config,
                    LocalContext provides LocalContext.current.createConfigurationContext(config)) {
                    MonicaTheme(darkTheme = true) {
                        AutofillSettingsV2Screen(onNavigateBack = {}, onNavigateToBlockedFields = {}, onNavigateToSaveBlockedTargets = {})
                    }
                }
            }
            fun toggle(tag: String, enabled: Boolean) {
                compose.onNodeWithTag(tag).performScrollTo()
                val control = compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag(tag))).onFirst()
                control.performClick()
                // Drive Compose frames while the asynchronous write updates the visible switch.
                compose.waitUntil(5000) {
                    control.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] ==
                        if (enabled) ToggleableState.On else ToggleableState.Off
                }
            }
            toggle("ime_scramble_pin_setting", true)
            withTimeout(5000) { preferences.imeKeyboardOptions.first { it.scramblePin } }
            toggle("ime_hide_pin_preview_setting", true)
            assertEquals(ImeKeyboardOptions(true, true), withTimeout(5000) {
                preferences.imeKeyboardOptions.first { it.scramblePin && it.hidePinPreview }
            })
            File(context.getExternalFilesDir("ime-improvements"), "settings.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            toggle("ime_scramble_pin_setting", false)
            toggle("ime_hide_pin_preview_setting", false)
            assertEquals(ImeKeyboardOptions(), withTimeout(5000) {
                preferences.imeKeyboardOptions.first { !it.scramblePin && !it.hidePinPreview }
            })
        } finally {
            preferences.setImeScramblePin(original.scramblePin)
            preferences.setImeHidePinPreview(original.hidePinPreview)
        }
    }
}
