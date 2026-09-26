package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
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
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

@RunWith(AndroidJUnit4::class)
class ItalianLocaleInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings = SettingsManager(context)
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

    @Test fun italianLanguageAndItalyRegionResolveTheCompletePack() {
        val italian = LocaleHelper.setLocale(context, Language.ITALIAN)
        assertEquals("it", Locale.getDefault().toLanguageTag())
        assertEquals(Language.ITALIAN, LocaleHelper.getCurrentLanguage(italian))
        assertSame(italian, LocaleHelper.setLocale(italian, Language.ITALIAN))
        for (tag in listOf("it", "it-IT")) {
            val localized = context.withLocale(tag)
            assertEquals(Language.ITALIAN, LocaleHelper.getCurrentLanguage(localized))
            assertEquals("Annulla", localized.getString(R.string.cancel))
            assertEquals("Password principale", localized.getString(R.string.master_password))
            assertEquals("Passkey", localized.getString(R.string.passkey))
            assertEquals("Token API", localized.getString(R.string.entry_type_api_token))
            assertEquals("Unisci e sincronizza", localized.getString(R.string.keepass_conflict_merge_sync))
            assertEquals("Stato del database", localized.getString(R.string.mdbx_ui_manager_health_title))
        }
        val english = LocaleHelper.setLocale(italian, Language.ENGLISH)
        assertEquals("Cancel", english.getString(R.string.cancel))
        val chinese = LocaleHelper.setLocale(english, Language.CHINESE)
        assertEquals("取消", chinese.getString(R.string.cancel))
    }

    @Test fun italianPluralsFollowAndroidQuantityRules() {
        val resources = context.withLocale("it-IT").resources
        mapOf(0 to "0 elementi", 1 to "1 elemento", 2 to "2 elementi", 1_000_000 to "1000000 elementi",
        ).forEach { (count, expected) ->
            assertEquals(expected, resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count))
        }
    }

    @Test fun languageNameIsPlayfulOnlyInChineseContexts() {
        Language.entries.filter { it != Language.SYSTEM }.forEach { language ->
            val chinese = language in setOf(Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.CLASSICAL_CHINESE, Language.NYA, Language.SNOW_LEOPARD)
            assertEquals(language.name, if (chinese) "超级马里奥语" else "Italiano",
                LocaleHelper.setLocale(context, language).getString(R.string.language_italian))
        }
    }

    @Test fun selectedItalianSurvivesAColdStartupCacheRead() = runBlocking {
        settings.updateLanguage(Language.ITALIAN)
        withTimeout(10_000) { settings.settingsFlow.first { it.language == Language.ITALIAN } }
        assertEquals(Language.ITALIAN, SettingsManager(context).settingsFlow.first().language)
        assertEquals("ITALIAN", context.getSharedPreferences("monica_startup_language", Context.MODE_PRIVATE).getString("language", null))
        // Simulate the cache state of a fresh process, preserving the user's settings in @After.
        StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply {
            isAccessible = true
            set(null, null)
        }
        assertEquals(Language.ITALIAN, StartupLanguageCache.read(context))
        assertEquals("Password", LocaleHelper.setLocale(context, StartupLanguageCache.read(context)).getString(R.string.password))
    }

    @Test fun retainedServiceMessagesRefreshAfterSwitchingLanguage() {
        val strings = AppLocaleStringResolver(context)
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
        LocaleHelper.setLocale(context, Language.ITALIAN)
        assertEquals("Password", strings.get(R.string.password))
        assertEquals("Codice inviato via email. Controlla la posta in arrivo e lo spam.", strings.get(R.string.legacy_ui_email_code_sent))
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
    }

    @Test fun compiledSeparatorsAndKeePassTokensRemainUsable() {
        val italian = context.withLocale("it")
        assertEquals(", ", italian.getString(R.string.dedup_merge_list_separator))
        assertEquals(" · account", italian.getString(R.string.sync_status_with_vault, "account"))
        assertEquals(" · Predefinito: 7", italian.getString(R.string.password_field_customization_default_value_suffix, "7"))
        assertEquals("Es. {USERNAME}{TAB}{PASSWORD}{ENTER}", italian.getString(R.string.keepass_native_auto_type_tokens_hint))
    }

    @Test fun androidCanReadAndFormatEveryPackagedStringInItalian() {
        val italian = context.withLocale("it")
        R.string::class.java.fields.forEach { field ->
            val id = field.getInt(null)
            val template = italian.getString(id)
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
                    else -> if (conversion.startsWith('t', true)) 0L else "esempio"
                }
            }
            if (arguments.isNotEmpty()) {
                val values = Array(arguments.keys.max() + 1) { arguments[it] ?: "esempio" }
                try {
                    italian.getString(id, *values)
                } catch (error: RuntimeException) {
                    throw AssertionError("Cannot format ${field.name}: $template", error)
                }
            }
            assertFalse("${field.name} contains a replacement character", template.contains('\uFFFD'))
        }
    }

    private fun Context.withLocale(tag: String) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )

    private companion object {
        val FORMAT = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])""")
    }
}
