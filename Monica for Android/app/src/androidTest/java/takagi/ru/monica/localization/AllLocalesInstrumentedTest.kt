package takagi.ru.monica.localization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

@RunWith(AndroidJUnit4::class)
class AllLocalesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
    private val languages = Language.entries.filter { it != Language.SYSTEM }
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

    @Before fun saveLanguage() = runBlocking {
        originalLocale = Locale.getDefault()
        originalLanguage = settings.settingsFlow.first().language
    }

    @After fun restoreLanguage() = runBlocking {
        settings.updateLanguage(originalLanguage)
        Locale.setDefault(originalLocale)
    }

    @Test fun everyLanguageCanBeSelectedPersistedAndResolved() = runBlocking {
        val cancelLabels = mapOf(
            Language.ENGLISH to "Cancel",
            Language.CHINESE to "取消",
            Language.TRADITIONAL_CHINESE to "取消",
            Language.NYA to "算了喵",
            Language.SNOW_LEOPARD to "算了嗷呜",
            Language.CLASSICAL_CHINESE to "罢",
            Language.VIETNAMESE to "Hủy",
            Language.JAPANESE to "キャンセル",
            Language.RUSSIAN to "Отмена",
            Language.KOREAN to "취소",
            Language.GERMAN to "Abbrechen",
            Language.SPANISH to "Cancelar",
            Language.FRENCH to "Annuler",
            Language.POLISH to "Anuluj",
            Language.ITALIAN to "Annulla",
        )
        assertEquals(languages.toSet(), cancelLabels.keys)
        languages.forEach { language ->
            settings.updateLanguage(language)
            withTimeout(10_000) { settings.settingsFlow.first { it.language == language } }
            assertEquals(language, SettingsManager(context).settingsFlow.first().language)
            assertEquals(language, StartupLanguageCache.read(context))
            val localized = LocaleHelper.setLocale(context, language)
            assertEquals(language, LocaleHelper.getCurrentLanguage(localized))
            assertEquals(language.name, cancelLabels.getValue(language), localized.getString(R.string.cancel))
        }
    }

    @Test fun androidCanReadAndFormatEveryStringInEveryLanguage() {
        languages.forEach { language ->
            val localized = LocaleHelper.setLocale(context, language)
            R.string::class.java.fields.forEach { field ->
                val id = field.getInt(null)
                val template = localized.getString(id)
                val arguments = mutableMapOf<Int, Any>()
                var nextArgument = 0
                var previousArgument = 0
                for (match in FORMAT.findAll(template)) {
                    val conversion = match.groupValues[3]
                    if (conversion == "%" || conversion == "n") continue
                    val explicit = match.groupValues[1].toIntOrNull()?.minus(1)
                    val index = explicit ?: if ('<' in match.groupValues[2]) previousArgument else nextArgument++
                    previousArgument = index
                    arguments[index] = when (conversion.lowercase(Locale.ROOT)) {
                        "d", "o", "x" -> 7
                        "e", "f", "g", "a" -> 1.25
                        "c" -> 'A'
                        "b" -> true
                        else -> if (conversion.startsWith('t', true)) 0L else "sample-value"
                    }
                }
                if (arguments.isNotEmpty()) {
                    val values = Array(arguments.keys.max() + 1) { arguments[it] ?: "sample-value" }
                    try {
                        localized.getString(id, *values)
                    } catch (error: RuntimeException) {
                        throw AssertionError("Cannot format $language/${field.name}: $template", error)
                    }
                }
                assertFalse("$language/${field.name} has a replacement character", template.contains('\uFFFD'))
            }
            listOf(0, 1, 2, 5, 11, 21, 22, 101).forEach { count ->
                val text = localized.resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count)
                assertTrue("$language plural $count: $text", text.contains(count.toString()))
            }
        }
    }

    @Test fun russianPluralsFollowAndroidQuantityRules() {
        val resources = LocaleHelper.setLocale(context, Language.RUSSIAN).resources
        mapOf(
            0 to "0 записей", 1 to "1 запись", 2 to "2 записи", 4 to "4 записи",
            5 to "5 записей", 11 to "11 записей", 12 to "12 записей", 14 to "14 записей",
            21 to "21 запись", 22 to "22 записи", 25 to "25 записей", 101 to "101 запись",
            102 to "102 записи", 111 to "111 записей", 112 to "112 записей",
        ).forEach { (count, expected) ->
            assertEquals(expected, resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count))
        }
    }

    @Test fun compiledSeparatorsAndAutoTypeTokensArePreservedInEveryLanguage() {
        languages.forEach { language ->
            val localized = LocaleHelper.setLocale(context, language)
            assertEquals(language.name, " · vault-7", localized.getString(R.string.sync_status_with_vault, "vault-7"))
            val comma = if (language in setOf(Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.NYA, Language.SNOW_LEOPARD, Language.CLASSICAL_CHINESE, Language.JAPANESE)) "、" else ", "
            assertEquals(language.name, comma, localized.getString(R.string.dedup_merge_list_separator))
            assertTrue(language.name, localized.getString(R.string.keepass_native_auto_type_tokens_hint)
                .contains("{USERNAME}{TAB}{PASSWORD}{ENTER}"))
        }
    }

    private companion object {
        val FORMAT = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])""")
    }
}
