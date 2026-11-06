package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

@RunWith(Parameterized::class)
class PortugueseLocaleInstrumentedTest(private val language: Language, private val tag: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private val brazil get() = language == Language.PORTUGUESE_BRAZIL
    private val password get() = if (brazil) "Senha" else "Palavra-passe"
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

    @Test fun regionSelectionAndSystemLocaleFallbackResolveTheCorrectPack() {
        val selected = LocaleHelper.setLocale(context, language)
        assertEquals(tag, Locale.getDefault().toLanguageTag())
        assertEquals(language, LocaleHelper.getCurrentLanguage(selected))
        assertSame(selected, LocaleHelper.setLocale(selected, language))
        val tags = if (brazil) listOf("pt-BR") else listOf("pt", "pt-PT", "pt-AO")
        tags.forEach { requested ->
            val localized = context.withLocale(requested)
            assertEquals(language, LocaleHelper.getCurrentLanguage(localized))
            assertEquals("Cancelar", localized.getString(R.string.cancel))
            assertEquals(password, localized.getString(R.string.password))
            assertEquals(if (brazil) "Senha mestra" else "Palavra-passe mestra", localized.getString(R.string.master_password))
            assertEquals(if (brazil) "Salvar" else "Guardar", localized.getString(R.string.save))
            assertEquals(if (brazil) "Excluir" else "Eliminar", localized.getString(R.string.delete))
            assertEquals("Passkey", localized.getString(R.string.passkey))
            assertEquals("Token API", localized.getString(R.string.entry_type_api_token))
            assertEquals(if (brazil) "Mesclar e sincronizar" else "Unir e sincronizar", localized.getString(R.string.keepass_conflict_merge_sync))
        }
        assertEquals("Cancel", LocaleHelper.setLocale(selected, Language.ENGLISH).getString(R.string.cancel))
        assertEquals("取消", LocaleHelper.setLocale(selected, Language.CHINESE).getString(R.string.cancel))
    }

    @Test fun portuguesePluralsRespectRegionalAndroidRules() {
        val resources = context.withLocale(tag).resources
        val zeroNoun = if (brazil) "item" else "itens"
        mapOf(0 to "0 $zeroNoun", 1 to "1 item", 2 to "2 itens", 1_000_000 to "1000000 itens").forEach { (count, expected) ->
            assertEquals(expected, resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count))
        }
    }

    @Test fun selectedRegionSurvivesColdStartupCacheRead() = runBlocking {
        settings.updateLanguage(language)
        withTimeout(10_000) { settings.settingsFlow.first { it.language == language } }
        assertEquals(language, SettingsManager(context).settingsFlow.first().language)
        assertEquals(language.name, context.getSharedPreferences("monica_startup_language", Context.MODE_PRIVATE).getString("language", null))
        StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply {
            isAccessible = true
            set(null, null)
        }
        assertEquals(language, StartupLanguageCache.read(context))
        assertEquals(password, LocaleHelper.setLocale(context, StartupLanguageCache.read(context)).getString(R.string.password))
    }

    @Test fun retainedMessagesRefreshAcrossBothPortugueseRegions() {
        val strings = AppLocaleStringResolver(context)
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
        LocaleHelper.setLocale(context, Language.PORTUGUESE_PORTUGAL)
        assertEquals("Palavra-passe", strings.get(R.string.password))
        assertEquals("Guardar", strings.get(R.string.save))
        LocaleHelper.setLocale(context, Language.PORTUGUESE_BRAZIL)
        assertEquals("Senha", strings.get(R.string.password))
        assertEquals("Salvar", strings.get(R.string.save))
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
    }

    @Test fun technicalTokensAndMeaningfulSpacingSurviveCompilation() {
        val localized = context.withLocale(tag)
        assertEquals(" · conta", localized.getString(R.string.sync_status_with_vault, "conta"))
        val tokens = localized.getString(R.string.keepass_native_auto_type_tokens_hint)
        listOf("{USERNAME}", "{TAB}", "{PASSWORD}", "{ENTER}").forEach { assertTrue(tokens, tokens.contains(it)) }
        val confirmation = localized.getString(R.string.local_recovery_confirm_description)
        assertTrue(confirmation, confirmation.contains(if (brazil) "arquivos originais" else "ficheiros originais"))
        assertTrue(confirmation, confirmation.contains(if (brazil) "seus dados não serão alterados" else "seus dados não serão alterados"))
        assertFalse(confirmation, confirmation.contains('\uFFFD'))
    }

    @Test fun androidCanReadAndFormatEveryTranslatedString() {
        val localized = context.withLocale(tag)
        val contract = JSONArray(instrumentation.context.assets.open("portuguese-format-contract.json").bufferedReader().use { it.readText() })
        assertTrue("Complete source contract", contract.length() > 7_000)
        repeat(contract.length()) { index ->
            val entry = contract.getJSONObject(index)
            val name = entry.getString("name")
            val id = localized.resources.getIdentifier(name, "string", context.packageName)
            assertNotEquals("Missing packaged string: $name", 0, id)
            val template = localized.getString(id)
            assertFalse("$name has broken Unicode", template.contains('\uFFFD'))
            val types = entry.getJSONArray("arguments")
            val arguments = Array<Any>(types.length()) { argument ->
                when (types.getString(argument)) {
                    "integer" -> 7
                    "float" -> 1.25
                    "character" -> 'A'
                    "boolean" -> true
                    "date" -> 0L
                    else -> "exemplo"
                }
            }
            if (arguments.isNotEmpty()) {
                try {
                    localized.getString(id, *arguments)
                } catch (error: RuntimeException) {
                    throw AssertionError("Cannot format $tag/$name: $template", error)
                }
            }
        }
    }

    private fun Context.withLocale(requested: String) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(requested)) }
    )

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{1}")
        fun regions() = listOf(
            arrayOf<Any>(Language.PORTUGUESE_PORTUGAL, "pt-PT"),
            arrayOf<Any>(Language.PORTUGUESE_BRAZIL, "pt-BR"),
        )
    }
}
