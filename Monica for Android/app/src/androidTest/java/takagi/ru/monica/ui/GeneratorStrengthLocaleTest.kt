package takagi.ru.monica.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.ResultCard
import takagi.ru.monica.util.PasswordGenerator
import java.util.Locale
import java.io.File

class GeneratorStrengthLocaleTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tags = listOf("en", "zh", "zh-CN", "zh-NY", "zh-XB", "lzh", "zh-Hant", "zh-Hant-TW",
        "ar", "de", "es", "fr", "id", "it", "ja", "ko", "pl", "pt-PT", "pt-BR", "ru", "tr", "vi")
    private var tag by mutableStateOf("en")
    private var value by mutableStateOf("a")
    private fun localized(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
    })

    @Test fun allFiveStrengthLabelsHaveLocalizedResourcesForEverySupportedLocale() {
        val ids = listOf(R.string.strength_very_weak, R.string.strength_weak, R.string.strength_fair,
            R.string.strength_strong, R.string.strength_very_strong)
        assertEquals(ids, PasswordGenerator.StrengthLevel.entries.map { it.labelRes })
        for (locale in tags) {
            val labels = ids.map { localized(locale).getString(it) }
            assertTrue(locale, labels.all { it.isNotBlank() })
            assertEquals(locale, 5, labels.distinct().size)
            // “Fair” describes password strength; translations for justice or a trade fair are incorrect.
            mapOf("ar" to "متوسط", "fr" to "Moyen", "id" to "Sedang", "it" to "Media", "tr" to "Orta")[locale]?.let {
                assertEquals(locale, it, localized(locale).getString(R.string.strength_fair))
            }
            if (!locale.startsWith("zh") && locale != "lzh") {
                assertTrue(locale, labels.none { it in listOf("非常弱", "弱", "中等", "强", "非常强") })
            }
        }
    }

    @Test fun actualResultCardChangesLanguageAndStrengthWithoutChangingThePassword() {
        compose.setContent {
            val local = remember(tag) { localized(tag) }
            CompositionLocalProvider(LocalContext provides local, LocalConfiguration provides local.resources.configuration,
                LocalLayoutDirection provides if (tag == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(LocalDensity.current.density, if (tag == "ar") 1.5f else 1f)) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    Box(Modifier.width(360.dp)) { ResultCard(value, onCopy = {}) }
                }
            }
        }
        val long = "Example-Only-9fG!tY6@uL3#vN8%kR2&jP5+".repeat(4)
        for (locale in tags) for (password in listOf("a", long)) {
            compose.runOnIdle { tag = locale; value = password }
            val level = PasswordGenerator.analyzePasswordStrength(password, localized(locale)).level
            compose.onNodeWithText(localized(locale).getString(level.labelRes)).assertIsDisplayed()
            compose.onNodeWithText(password).assertExists()
            if (locale == "ar") {
                compose.onNodeWithText("非常强").assertDoesNotExist()
                if (password == long) compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    File(context.filesDir, "folder-backup-generator-ar.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
