package takagi.ru.monica.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.repository.MdbxCommitChangeSummary
import takagi.ru.monica.repository.MdbxDeltaSummary

class MdbxLocalizationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rememberedHistoryRefreshesWhenTheAppLanguageChanges() {
        var language by mutableStateOf("zh")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val delta = MdbxDeltaSummary(
            commitId = "sample-commit",
            deviceId = "sample-device",
            localSeq = 1,
            commitKind = "change",
            changeScope = "entry",
            changedObjectIds = "[\"entry-1\"]",
            changedObjectPreview = "",
            changedFieldSummary = "",
            parentCount = 1,
            createdAt = "2026-09-11T00:00:00Z",
            operationKind = "monica-upsert-entries",
            changes = listOf(MdbxCommitChangeSummary("entry", "entry-1", "create", emptyList()))
        )
        compose.setContent {
            val context = remember(language) { base.withLanguage(language) }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration
            ) {
                val strings = rememberScreenStrings()
                val presentation = remember(delta, strings) { delta.toHistoryPresentation(strings) }
                Text(presentation.title)
            }
        }

        compose.onNodeWithText("添加了1 个条目").assertIsDisplayed()
        compose.runOnIdle { language = "en" }
        compose.onNodeWithText("Added: Entry × 1").assertIsDisplayed()
        compose.runOnIdle { language = "ru" }
        compose.onNodeWithText("Добавление: Запись × 1").assertIsDisplayed()
        compose.runOnIdle { language = "zh" }
        compose.onNodeWithText("添加了1 个条目").assertIsDisplayed()
    }

    @Test
    fun androidResolvesAndFormatsEveryNewMdbxMessage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val fields = R.string::class.java.fields.filter { it.name.startsWith("mdbx_ui_") }
        assertTrue(fields.isNotEmpty())
        listOf("en", "ru", "zh").forEach { language ->
            val context = base.withLanguage(language)
            fields.forEach { field ->
                val id = field.getInt(null)
                val template = context.getString(id)
                val argumentCount = Regex("""%(\d+)\${'$'}s""").findAll(template)
                    .maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                val text = if (argumentCount == 0) template
                else context.getString(id, *Array<Any>(argumentCount) { "42" })
                assertTrue("$language/${field.name} is empty", text.isNotBlank())
                assertFalse("$language/${field.name} still has a placeholder", text.contains("%1${'$'}s"))
                if (language != "zh") {
                    assertFalse("$language/${field.name} contains Chinese", Regex("[\\p{IsHan}]").containsMatchIn(text))
                }
            }
        }
    }

    private fun Context.withLanguage(language: String): Context = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    )
}
