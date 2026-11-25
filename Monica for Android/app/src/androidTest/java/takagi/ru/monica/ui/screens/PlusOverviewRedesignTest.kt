package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.PlusFeatures

@OptIn(ExperimentalTestApi::class)
class PlusOverviewRedesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var backs = 0
    private var activations = 0
    private var deactivations = 0
    private val config get() = Configuration(context.resources.configuration).apply {
        setLocale(Locale.SIMPLIFIED_CHINESE)
    }
    private val localized get() = context.createConfigurationContext(config)
    private fun text(id: Int) = localized.getString(id)

    private fun show(active: Boolean = false, dark: Boolean = false, large: Boolean = false) {
        compose.setContent {
            var activated by remember { mutableStateOf(active) }
            var payment by remember { mutableStateOf(false) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity,
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, if (large) 2f else 1f)
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.requiredSize(if (large) 320.dp else 392.dp, 820.dp).testTag("plus_review")) {
                        if (payment) {
                            PaymentScreen(onNavigateBack = { payment = false }, onActivatePlus = {
                                activations++
                                activated = true
                            })
                        } else {
                            MonicaPlusScreen(activated, { backs++ }, { payment = true }, {
                                deactivations++
                                activated = false
                            })
                        }
                    }
                }
            }
        }
    }

    private fun scroll(key: String) = compose.onNodeWithTag("plus_content").performScrollToKey(key)
    private fun capture(name: String) {
        compose.waitUntilExactlyOneExists(hasTestTag("plus_review"), 5_000)
        val bitmap = compose.onNodeWithTag("plus_review").captureToImage().asAndroidBitmap()
        File(context.filesDir, "plus-overview-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun overviewPaymentReturnAndConfirmationKeepOriginalFlow() {
        show()
        compose.onNodeWithText(text(R.string.plus_honesty_title)).assertIsDisplayed()
        for (paragraph in text(R.string.plus_honesty_body).split("\n\n")) {
            compose.onNodeWithText(paragraph.replace("**", "")).assertExists()
        }
        val rendered = compose.onAllNodes(hasText("完全免费", substring = true)).fetchSemanticsNodes().single()
            .config[SemanticsProperties.Text].single()
        assertFalse(rendered.text.contains("**"))
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        compose.onNodeWithTag("plus_support").performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("plus_support").assertExists()
        assertEquals(0, activations)
        compose.onNodeWithTag("plus_support").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.payment_paid_button)).performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(text(R.string.payment_unlock_confirmation)).assertExists()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(0, activations)
        compose.onNodeWithText(text(R.string.payment_paid_button)).performClick()
        compose.onNodeWithText(text(R.string.payment_unlock_confirm_button)).performClick()
        compose.onNodeWithTag("plus_active_status").assertExists()
        compose.onNodeWithTag("plus_support").assertDoesNotExist()
        assertEquals(1, activations)
        assertEquals(0, deactivations)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun activeSwitchRetainsDeactivationAndFeatureAccessibility() {
        show(active = true)
        compose.onNodeWithTag("plus_support").assertDoesNotExist()
        scroll("premium_themes")
        compose.onNodeWithTag("plus_feature_premium_themes").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.plus_feature_unlocked)))
        scroll("support")
        compose.onNodeWithTag("plus_deactivate").performScrollTo().performClick()
        compose.onNodeWithTag("plus_support").assertExists()
        compose.onNodeWithTag("plus_deactivate").assertDoesNotExist()
        assertEquals(1, deactivations)
        assertEquals(0, activations)
        scroll("premium_themes")
        compose.onNodeWithTag("plus_feature_premium_themes").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.plus_feature_locked)))
    }

    @Test fun lightOverviewAndAllBenefitsAreReadable() {
        show()
        capture("light")
        for (feature in PlusFeatures.getPlaceholderFeatures()) {
            scroll(feature.id)
            compose.onNodeWithTag("plus_feature_${feature.id}").assertIsDisplayed()
            compose.onNodeWithText(text(feature.descriptionRes), substring = true).assertExists()
        }
        capture("light-benefits")
    }

    @Test fun darkActiveOverviewIsReadable() {
        show(active = true, dark = true)
        capture("dark-active")
        compose.onNodeWithTag("plus_deactivate").performScrollTo().assertIsDisplayed()
        scroll("bitwarden_sync")
        compose.onNodeWithTag("plus_feature_bitwarden_sync").assertIsDisplayed()
        capture("dark-benefits")
    }

    @Test fun narrowDoubleFontCanReachActionsAndEveryBenefit() {
        show(dark = true, large = true)
        capture("large-top")
        compose.onNodeWithTag("plus_support").performScrollTo().assertIsDisplayed()
        capture("large-action")
        for (feature in PlusFeatures.getPlaceholderFeatures()) {
            scroll(feature.id)
            compose.onNodeWithTag("plus_feature_${feature.id}").assertIsDisplayed()
        }
        capture("large-benefits")
        compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed().performClick()
        assertEquals(1, backs)
        assertEquals(0, activations)
    }

    @Test fun allSupportedLocalesResolveDedicatedOverviewCopy() {
        val languages = listOf("en", "lzh", "zh-Hant", "de", "es", "fr", "it", "ja", "ko",
            "pl", "pt", "pt-BR", "ru", "vi", "zh", "zh-CN", "zh-NY", "zh-XB")
        val english = context.createConfigurationContext(Configuration(config).apply { setLocale(Locale.ENGLISH) })
            .getString(R.string.plus_honesty_body)
        for (tag in languages) {
            val res = context.createConfigurationContext(Configuration(config).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertTrue(tag, res.getString(R.string.plus_honesty_title).isNotBlank())
            val body = res.getString(R.string.plus_honesty_body)
            assertEquals(tag, 4, body.split("\n\n").size)
            assertTrue(tag, body.contains("Monica"))
            if (tag != "en") assertNotEquals(tag, english, body)
        }
    }
}
