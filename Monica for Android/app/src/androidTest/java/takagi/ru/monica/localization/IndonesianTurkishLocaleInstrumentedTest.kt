package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

class IndonesianTurkishLocaleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language
    private val languages = listOf(Language.INDONESIAN, Language.TURKISH)

    @Before fun rememberSettings() = runBlocking {
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")?.toInt()
        require(user != null && user > 0 && android.os.Process.myUid() / 100000 == user) {
            "Locale persistence tests require an explicitly owned secondary test user"
        }
        originalLocale = Locale.getDefault()
        originalLanguage = settings.settingsFlow.first().language
    }

    @After fun restoreSettings() = runBlocking {
        if (::originalLanguage.isInitialized) settings.updateLanguage(originalLanguage)
        if (::originalLocale.isInitialized) Locale.setDefault(originalLocale)
    }

    @Test fun explicitChoicesAndLegacyIndonesianTagsResolveTheRightPack() {
        val expected = mapOf(Language.INDONESIAN to "id-ID", Language.TURKISH to "tr-TR")
        expected.forEach { (language, tag) ->
            val selected = LocaleHelper.setLocale(context, language)
            assertEquals(tag, selected.resources.configuration.locales[0].toLanguageTag())
            assertEquals(language, LocaleHelper.getCurrentLanguage(selected))
            assertSame(selected, LocaleHelper.setLocale(selected, language))
        }
        for (tag in listOf("id", "id-ID", "in", "in-ID")) {
            val localized = context.localized(tag)
            assertEquals(tag, Language.INDONESIAN, LocaleHelper.getCurrentLanguage(localized))
            assertEquals(tag, "Kata sandi", localized.getString(R.string.password))
        }
        for (tag in listOf("tr", "tr-TR", "tr-CY")) {
            val localized = context.localized(tag)
            assertEquals(tag, Language.TURKISH, LocaleHelper.getCurrentLanguage(localized))
            assertEquals(tag, "Şifre", localized.getString(R.string.password))
        }
    }

    @Test fun savedChoicesSurviveFreshSettingsAndStartupCacheReads() = runBlocking {
        for (language in languages) {
            settings.updateLanguage(language)
            assertEquals(language, SettingsManager(context).settingsFlow.first().language)
            assertEquals(language.name, context.getSharedPreferences("monica_startup_language", Context.MODE_PRIVATE)
                .getString("language", null))
            StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply {
                isAccessible = true
                set(null, null)
            }
            assertEquals(language, StartupLanguageCache.read(context))
            assertEquals(language, LocaleHelper.getCurrentLanguage(LocaleHelper.setLocale(context, StartupLanguageCache.read(context))))
        }
    }

    @Test fun retainedRuntimeMessagesRefreshWithoutRecreatingTheResolver() {
        val strings = AppLocaleStringResolver(context)
        val expected = listOf("Kata sandi", "Şifre")
        languages.forEachIndexed { i, language ->
            LocaleHelper.setLocale(context, language)
            assertEquals(expected[i], strings.get(R.string.password))
        }
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
    }

    @Test fun compiledPluralsAndTechnicalTokensRemainUsable() {
        for (language in languages) {
            val localized = LocaleHelper.setLocale(context, language)
            for (count in listOf(0, 1, 2, 1_000_000)) {
                val text = localized.resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count)
                assertTrue(text, text.contains(count.toString()))
            }
            val hint = localized.getString(R.string.keepass_native_auto_type_tokens_hint)
            listOf("{USERNAME}", "{TAB}", "{PASSWORD}", "{ENTER}").forEach { assertTrue(hint, hint.contains(it)) }
        }
    }

    @Test fun everyPackagedResourceMatchesTheCompleteSourceContractAndFormats() {
        for (language in languages) {
            val localized = LocaleHelper.setLocale(context, language)
            val contract = JSONArray(instrumentation.context.assets.open("idtr-${language.name}-resource-contract.json")
                .bufferedReader().use { it.readText() })
            assertTrue("Complete current resource contract", contract.length() > 7_300)
            repeat(contract.length()) { index ->
                val entry = contract.getJSONObject(index)
                val name = entry.getString("name")
                val type = entry.getString("type")
                val id = localized.resources.getIdentifier(name, if (type == "string-array") "array" else type, context.packageName)
                assertNotEquals("Missing ${language.name}/$name", 0, id)
                val expected = entry.getJSONObject("values")
                when (type) {
                    "string" -> {
                        assertEquals("${language.name}/$name", expected.getString("text"), localized.getString(id))
                        val types = entry.getJSONArray("arguments")
                        val args = Array<Any>(types.length()) { argument ->
                            when (types.getString(argument)) {
                                "integer" -> 7
                                "float" -> 1.25
                                "character" -> 'A'
                                "boolean" -> true
                                "date" -> 0L
                                else -> "sample"
                            }
                        }
                        if (args.isNotEmpty()) localized.getString(id, *args)
                    }
                    "string-array" -> {
                        val actual = localized.resources.getStringArray(id)
                        assertEquals(name, expected.length(), actual.size)
                        actual.forEachIndexed { i, text -> assertEquals("$name/$i", expected.getString(i.toString()), text) }
                    }
                    "plurals" -> {
                        assertEquals(name, expected.getString("other"), localized.resources.getQuantityString(id, 2))
                        if (language == Language.TURKISH && expected.has("one")) {
                            assertEquals(name, expected.getString("one"), localized.resources.getQuantityString(id, 1))
                        }
                    }
                }
            }
        }
    }

    private fun Context.localized(tag: String) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )
}
