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
class PolishLocaleInstrumentedTest {
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

    @Test fun polishLanguageAndPolandRegionResolveTheCompletePack() {
        val polish = LocaleHelper.setLocale(context, Language.POLISH)
        assertEquals("pl", Locale.getDefault().toLanguageTag())
        assertEquals(Language.POLISH, LocaleHelper.getCurrentLanguage(polish))
        assertSame(polish, LocaleHelper.setLocale(polish, Language.POLISH))
        for (tag in listOf("pl", "pl-PL")) {
            val localized = context.withLocale(tag)
            assertEquals(Language.POLISH, LocaleHelper.getCurrentLanguage(localized))
            assertEquals("Anuluj", localized.getString(R.string.cancel))
            assertEquals("Hasło główne", localized.getString(R.string.master_password))
            assertEquals("Klucze dostępu", localized.getString(R.string.passkey))
            assertEquals("Token API", localized.getString(R.string.entry_type_api_token))
            assertEquals("Scal i synchronizuj", localized.getString(R.string.keepass_conflict_merge_sync))
            assertEquals("Stan bazy", localized.getString(R.string.mdbx_ui_manager_health_title))
        }
        val english = LocaleHelper.setLocale(polish, Language.ENGLISH)
        assertEquals("Cancel", english.getString(R.string.cancel))
        val chinese = LocaleHelper.setLocale(english, Language.CHINESE)
        assertEquals("取消", chinese.getString(R.string.cancel))
    }

    @Test fun polishPluralsFollowAndroidQuantityRules() {
        val resources = context.withLocale("pl-PL").resources
        mapOf(
            0 to "0 wpisów", 1 to "1 wpis", 2 to "2 wpisy", 4 to "4 wpisy",
            5 to "5 wpisów", 11 to "11 wpisów", 12 to "12 wpisów", 14 to "14 wpisów",
            21 to "21 wpisów", 22 to "22 wpisy", 25 to "25 wpisów",
            101 to "101 wpisów", 102 to "102 wpisy", 112 to "112 wpisów",
        ).forEach { (count, expected) ->
            assertEquals(expected, resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count))
        }
    }

    @Test fun theNativeLanguageNameIsAvailableFromEveryExistingLanguage() {
        Language.entries.forEach { language ->
            assertEquals(language.name, "Polski", LocaleHelper.setLocale(context, language).getString(R.string.language_polish))
        }
    }

    @Test fun selectedPolishSurvivesAColdStartupCacheRead() = runBlocking {
        settings.updateLanguage(Language.POLISH)
        withTimeout(10_000) { settings.settingsFlow.first { it.language == Language.POLISH } }
        assertEquals(Language.POLISH, SettingsManager(context).settingsFlow.first().language)
        assertEquals("POLISH", context.getSharedPreferences("monica_startup_language", Context.MODE_PRIVATE).getString("language", null))
        // Simulate the cache state of a fresh process, preserving the user's settings in @After.
        StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply {
            isAccessible = true
            set(null, null)
        }
        assertEquals(Language.POLISH, StartupLanguageCache.read(context))
        assertEquals("Hasło", LocaleHelper.setLocale(context, StartupLanguageCache.read(context)).getString(R.string.password))
    }

    @Test fun retainedServiceMessagesRefreshAfterSwitchingLanguage() {
        val strings = AppLocaleStringResolver(context)
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
        LocaleHelper.setLocale(context, Language.POLISH)
        assertEquals("Hasło", strings.get(R.string.password))
        assertEquals("Wysłano kod e-mail. Sprawdź skrzynkę odbiorczą i spam", strings.get(R.string.legacy_ui_email_code_sent))
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
    }

    @Test fun compiledSeparatorsAndKeePassTokensRemainUsable() {
        val polish = context.withLocale("pl")
        assertEquals(" · konto", polish.getString(R.string.sync_status_with_vault, "konto"))
        assertEquals(" · Domyślnie: 7", polish.getString(R.string.password_field_customization_default_value_suffix, "7"))
        assertEquals("Np. {USERNAME}{TAB}{PASSWORD}{ENTER}", polish.getString(R.string.keepass_native_auto_type_tokens_hint))
    }

    @Test fun androidCanReadAndFormatEveryPackagedStringInPolish() {
        val polish = context.withLocale("pl")
        R.string::class.java.fields.forEach { field ->
            val id = field.getInt(null)
            val template = polish.getString(id)
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
                    else -> if (conversion.startsWith('t', true)) 0L else "przykład"
                }
            }
            if (arguments.isNotEmpty()) {
                val values = Array(arguments.keys.max() + 1) { arguments[it] ?: "przykład" }
                try {
                    polish.getString(id, *values)
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
