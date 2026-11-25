package takagi.ru.monica.localization

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.privilege.PrivilegeAccess
import takagi.ru.monica.autofill_ng.privilege.PrivilegeBackend
import takagi.ru.monica.data.Language
import takagi.ru.monica.lansync.DeviceDeletionReview
import takagi.ru.monica.ui.screens.NearbyDeletionReviewDialog
import takagi.ru.monica.ui.screens.PrivilegeBackendSelector
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager

@RunWith(Parameterized::class)
class ExistingLocaleWarningsUiTest(private val language: Language) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun languages() = listOf(Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.CLASSICAL_CHINESE,
            Language.VIETNAMESE, Language.JAPANESE, Language.RUSSIAN, Language.KOREAN,
            Language.GERMAN, Language.SPANISH, Language.FRENCH, Language.POLISH, Language.ITALIAN)
            .map { arrayOf<Any>(it) }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private val compose = createAndroidComposeRule<LocaleTestActivity>()
    private lateinit var originalLanguage: Language
    private lateinit var originalLocale: Locale
    private lateinit var originalBackend: PrivilegeBackend
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() = runBlocking {
            val user = InstrumentationRegistry.getArguments().getString("localeTestUser")?.toInt()
            require(user != null && user > 0 && android.os.Process.myUid() / 100000 == user)
            originalLanguage = settings.settingsFlow.first().language
            originalLocale = Locale.getDefault()
            originalBackend = PrivilegeAccess.backend(context)
            assertFalse("Synthetic fixture must not carry Root consent", PrivilegeAccess.hasRootConsent(context))
            settings.updateLanguage(language)
        }
        override fun after() {
            PrivilegeAccess.select(context, originalBackend)
            runBlocking { settings.updateLanguage(originalLanguage) }
            Locale.setDefault(originalLocale)
        }
    }).around(compose)

    @Test fun rootWarningCanBeReadAndCancelledWithoutAuthorizationAtLargeText() {
        PrivilegeAccess.select(context, PrivilegeBackend.ROOT)
        show(dark = true) { PrivilegeBackendSelector() }
        compose.onNodeWithTag("root-authorize").performClick()
        assertReadable(localized().getString(R.string.privilege_root_confirm_body))
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performTouchInput { swipeUp() }
        compose.onNodeWithTag("root-consent-confirm").assertIsDisplayed()
        screenshot("root-warning")
        compose.onNodeWithTag("root-consent-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("root-consent-confirm").assertDoesNotExist()
        assertFalse(PrivilegeAccess.hasRootConsent(context))
    }

    @Test fun deletionWarningPreservesTheKeepLocalChoiceAtLargeText() {
        val decisions = mutableListOf<Boolean>()
        var cancellations = 0
        show(dark = false) {
            NearbyDeletionReviewDialog(DeviceDeletionReview("locale-review", "synthetic-peer", "Test device", 7),
                { cancellations++ }, { decisions.add(it) })
        }
        assertReadable(localized().getString(R.string.nearby_deletion_review_body, "Test device", 7))
        compose.onNodeWithTag("nearby-deletions-keep").performScrollTo().assertIsDisplayed()
        screenshot("deletion-warning")
        assertTrue(decisions.isEmpty())
        compose.onNodeWithTag("nearby-deletions-keep").performClick()
        compose.runOnIdle { assertEquals(listOf(false), decisions); assertEquals(0, cancellations) }
    }

    private fun localized() = LocaleHelper.setLocale(context, language)
    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        val localized = localized()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides localized,
                LocalConfiguration provides Configuration(localized.resources.configuration).apply { fontScale = 1.5f },
                LocalDensity provides Density(density, 1.5f)) {
                MonicaTheme(darkTheme = dark) { Surface { content() } }
            }
        }
        compose.waitForIdle()
    }
    private fun assertReadable(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(1.5f, layout.layoutInput.density.fontScale, .001f)
        assertFalse("$language warning truncated", layout.multiParagraph.didExceedMaxLines)
        assertFalse("$language warning ellipsized", (0 until layout.lineCount).any(layout::isLineEllipsized))
        val width = node.fetchSemanticsNode().boundsInRoot.width
        assertTrue("$language warning clipped horizontally", (0 until layout.lineCount)
            .maxOf { layout.getLineRight(it) - layout.getLineLeft(it) } <= width + 1f)
    }
    private fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.filesDir, "locale-closeout-${language.name}-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
