package takagi.ru.monica.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import takagi.ru.monica.ui.screens.VersionInfoDialog

class VersionInfoDialogTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private val opened = mutableListOf<String>()
    private val visible = mutableStateOf(true)

    private fun show(fontScale: Float) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val localized = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread {
            CommonInfoTestActivity.content = {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                        LocalConfiguration provides config, LocalContext provides localized) {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            if (visible.value) VersionInfoDialog("1.0.329-26111812-03", { opened.add(it) }, { visible.value = false })
                        }
                    }
            }
        }
    }

    @After fun clearContent() {
        compose.runOnUiThread { CommonInfoTestActivity.content = {} }
    }

    @Test fun chineseDialogShowsVersionCodenameAndFourDestinations() {
        show(1f)
        compose.onNodeWithText("1.0.329-26111812-03").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("内部代号：SengokuNadeko").performScrollTo().assertIsDisplayed()
        capture("standard")
        listOf("GitHub", "官方网站", "图标来源", "图标版本").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/Monica-Pass/Monica", "https://monica-pass.github.io/MonicaDocs/",
                "https://github.com/stratumauth/app/tree/v1.4.0/icons", "https://github.com/stratumauth/app/releases/tag/v1.4.0"), opened)
        }
        compose.onNodeWithText("关闭").let {
            if (!it.isDisplayed()) it.performScrollTo()
            it.assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("version-info-dialog").assertDoesNotExist()
    }

    @Test fun largeTextStacksProjectLinksAndKeepsCreditsAndCloseReachable() {
        show(1.5f)
        val github = compose.onNodeWithText("GitHub").performScrollTo()
        val website = compose.onNodeWithText("官方网站")
        assertTrue(website.fetchSemanticsNode().boundsInRoot.top > github.fetchSemanticsNode().boundsInRoot.top)
        website.performScrollTo().assertIsDisplayed()
        capture("large")
        compose.onNodeWithText("Stratum Auth", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").let {
            if (!it.isDisplayed()) it.performScrollTo()
            it.assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("version-info-dialog").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("version-info-dialog").captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "version-links-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
