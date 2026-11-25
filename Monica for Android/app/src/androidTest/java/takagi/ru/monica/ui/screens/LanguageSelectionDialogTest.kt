package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.ui.theme.MonicaTheme

@RunWith(AndroidJUnit4::class)
class LanguageSelectionDialogTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val selections = mutableListOf<Language>()
    private var dismissals = 0

    private fun show(currentLanguage: Language, dark: Boolean = false, fontScale: Float = 1f, localeTag: String = "zh-CN") {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(localeTag))
            this.fontScale = fontScale
        }
        val localized = context.createConfigurationContext(configuration)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale),
            ) {
                MonicaTheme(darkTheme = dark) {
                    LanguageSelectionDialog(
                        currentLanguage = currentLanguage,
                        onLanguageSelected = { selections += it },
                        onDismiss = { dismissals++ },
                    )
                }
            }
        }
    }

    @Test
    fun closeKeepsTheCurrentLanguage() {
        show(Language.CHINESE)
        compose.onNodeWithText("语言").assertIsDisplayed()
        compose.onNodeWithTag("language_chinese_primary").assertIsSelected()
        compose.onNodeWithText("简体中文").assertIsDisplayed()
        compose.onNodeWithTag("language_option_CLASSICAL_CHINESE").assertDoesNotExist()
        compose.onNodeWithTag("language_chinese_expand").performClick()
        compose.onNodeWithText("文言文（华夏）").assertIsDisplayed()
        capture("language-dialog-m3e-light.png")
        compose.onNodeWithContentDescription(chineseCloseLabel()).performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(selections.isEmpty())
        }
    }

    @Test
    fun everyLanguageRemainsReachableWithLargeText() {
        show(Language.CLASSICAL_CHINESE, dark = true, fontScale = 1.5f)
        compose.onNodeWithText("语言").assertIsDisplayed()
        compose.onNodeWithTag("language_options")
            .performScrollToNode(hasTestTag("language_chinese_primary"))
        compose.onNodeWithTag("language_chinese_primary").assertIsSelected()
        compose.onNodeWithText("文言文（华夏）").assertIsDisplayed()
        assertEquals(
            1.5f,
            compose.onNodeWithTag("language_chinese_primary")
                .fetchSemanticsNode().layoutInfo.density.fontScale,
            0.001f,
        )
        compose.onNodeWithContentDescription(chineseCloseLabel()).assertIsDisplayed()
        capture("language-dialog-m3e-dark-large-text.png")
        compose.onNodeWithTag("language_chinese_expand").performClick()
        chineseLanguageVariants.forEach { language ->
            val tag = "language_option_${language.name}"
            compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_chinese_primary"))
        compose.onNodeWithTag("language_chinese_expand").performClick()
        Language.entries.filter { it !in chineseLanguageVariants }.forEach { language ->
            val tag = "language_option_${language.name}"
            compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        compose.onNodeWithTag("language_option_FRENCH").performClick()
        compose.onNodeWithContentDescription(chineseCloseLabel()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Language.FRENCH), selections) }
    }

    @Test
    fun italianOptionUsesNativeNameAndCanBeSelected() {
        show(Language.ITALIAN, dark = true, fontScale = 1.5f)
        compose.onNodeWithTag("language_options").performScrollToNode(hasTestTag("language_option_ITALIAN"))
        compose.onNodeWithText("Italiano").assertIsDisplayed()
        compose.onNodeWithTag("language_option_ITALIAN").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(listOf(Language.ITALIAN), selections) }
        capture("language-dialog-italian-dark-large-text.png")
    }

    @Test
    fun chineseGroupContainsOnlySupportedLanguages() {
        show(Language.CHINESE)
        compose.onNodeWithTag("language_chinese_expand").performClick()
        compose.onNodeWithTag("language_option_NYA").assertDoesNotExist()
        compose.onNodeWithTag("language_option_SNOW_LEOPARD").assertDoesNotExist()
        assertEquals(listOf(Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.TRADITIONAL_CHINESE_TAIWAN, Language.CLASSICAL_CHINESE), chineseLanguageVariants)
    }

    private fun chineseCloseLabel(): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
    ).getString(R.string.close)

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test
    fun taiwanCanBeSelectedSeparatelyFromHongKong() {
        show(Language.TRADITIONAL_CHINESE)
        compose.onNodeWithText("繁體中文（香港）").assertIsDisplayed()
        compose.onNodeWithTag("language_chinese_expand").performClick()
        compose.onNodeWithTag("language_option_TRADITIONAL_CHINESE").assertIsSelected()
        compose.onNodeWithText("繁體中文（台灣）").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Language.TRADITIONAL_CHINESE_TAIWAN), selections) }
    }

    @Test
    fun taiwanLargeTextDialogKeepsTheSelectedRegionAndAllowsReturningToHongKong() {
        show(Language.TRADITIONAL_CHINESE_TAIWAN, dark = true, fontScale = 1.5f, localeTag = "zh-Hant-TW")
        compose.onNodeWithText("語言").assertIsDisplayed()
        compose.onNodeWithText("繁體中文（台灣）").assertIsDisplayed()
        compose.onNodeWithTag("language_chinese_primary").assertIsSelected()
        capture("language-dialog-taiwan-dark-large-text.png")
        compose.onNodeWithTag("language_chinese_expand").performClick()
        compose.onNodeWithTag("language_option_TRADITIONAL_CHINESE_TAIWAN").assertIsSelected()
        compose.onNodeWithText("繁體中文（香港）").performClick()
        compose.runOnIdle { assertEquals(listOf(Language.TRADITIONAL_CHINESE), selections) }
    }
}
