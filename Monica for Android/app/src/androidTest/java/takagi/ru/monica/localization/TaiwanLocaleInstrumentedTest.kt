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
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

class TaiwanLocaleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private lateinit var originalLocale: Locale
    private lateinit var originalLanguage: Language

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

    @Test fun explicitSelectionAndSystemTaiwanTagsUseTheTaiwanPack() {
        val selected = LocaleHelper.setLocale(context, Language.TRADITIONAL_CHINESE_TAIWAN)
        assertEquals("zh-Hant-TW", Locale.getDefault().toLanguageTag())
        assertEquals(Language.TRADITIONAL_CHINESE_TAIWAN, LocaleHelper.getCurrentLanguage(selected))
        assertSame(selected, LocaleHelper.setLocale(selected, Language.TRADITIONAL_CHINESE_TAIWAN))
        assertEquals("zh-Hant-TW,zh-Hans-CN,en", selected.resources.configuration.locales.toLanguageTags())
        for (tag in listOf("zh-TW", "zh-Hant-TW")) {
            val localized = context.localized(tag)
            assertEquals(tag, Language.TRADITIONAL_CHINESE_TAIWAN, LocaleHelper.getCurrentLanguage(localized))
            assertTrue(tag, localized.getString(R.string.software_free_description).contains("軟體"))
            assertTrue(tag, localized.getString(R.string.biometric_title).contains("生物辨識"))
            assertEquals("刪除未完成", localized.getString(R.string.batch_delete_incomplete_title))
        }
        assertEquals(Language.CHINESE, LocaleHelper.getCurrentLanguage(context.localized("zh-Hans-TW")))
    }

    @Test fun hongKongMacauAndGenericHantKeepTheirExistingRegionalChoice() {
        val selected = LocaleHelper.setLocale(context, Language.TRADITIONAL_CHINESE)
        assertEquals("zh-Hant-HK", selected.resources.configuration.locales[0].toLanguageTag())
        for (tag in listOf("zh-HK", "zh-Hant-HK", "zh-MO", "zh-Hant-MO", "zh-Hant")) {
            val localized = context.localized(tag)
            assertEquals(tag, Language.TRADITIONAL_CHINESE, LocaleHelper.getCurrentLanguage(localized))
            assertTrue(tag, localized.getString(R.string.software_free_description).contains("軟件"))
            assertTrue(tag, localized.getString(R.string.biometric_login_subtitle).contains("身份"))
        }
    }

    @Test fun savedTaiwanAndExistingHongKongSettingsSurviveFreshCacheReads() = runBlocking {
        for (language in listOf(Language.TRADITIONAL_CHINESE_TAIWAN, Language.TRADITIONAL_CHINESE)) {
            settings.updateLanguage(language)
            withTimeout(10_000) { settings.settingsFlow.first { it.language == language } }
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

    @Test fun retainedMessagesRefreshAcrossTaiwanHongKongAndEnglish() {
        val strings = AppLocaleStringResolver(context)
        LocaleHelper.setLocale(context, Language.TRADITIONAL_CHINESE_TAIWAN)
        assertTrue(strings.get(R.string.biometric_login_subtitle).contains("身分"))
        assertTrue(strings.get(R.string.software_free_description).contains("軟體"))
        LocaleHelper.setLocale(context, Language.TRADITIONAL_CHINESE)
        assertTrue(strings.get(R.string.biometric_login_subtitle).contains("身份"))
        assertTrue(strings.get(R.string.software_free_description).contains("軟件"))
        LocaleHelper.setLocale(context, Language.ENGLISH)
        assertEquals("Password", strings.get(R.string.password))
    }

    @Test fun taiwanPluralsAndTechnicalTokensSurviveCompilation() {
        val localized = context.localized("zh-Hant-TW")
        for (count in listOf(0, 1, 2, 1_000_000)) {
            val text = localized.resources.getQuantityString(R.plurals.vault_v2_hierarchy_item_count, count, count)
            assertTrue(text, text.contains(count.toString()))
            assertTrue(text, text.contains("項目"))
        }
        val tokens = localized.getString(R.string.keepass_native_auto_type_tokens_hint)
        listOf("{USERNAME}", "{TAB}", "{PASSWORD}", "{ENTER}").forEach { assertTrue(tokens, tokens.contains(it)) }
        assertEquals("通行金鑰", localized.getString(R.string.passkey))
        assertEquals("支持 Monica Pass", localized.getString(R.string.plus_honesty_title))
    }

    @Test fun recoveryWarningsKeepTheirScopeAndDataRetentionConditions() {
        val localized = context.localized("zh-Hant-TW")
        val limits = localized.getString(R.string.recovery_protection_limits)
        listOf("獨立加密備份", "不保證", "硬體綁定憑證", "鎖定", "重新驗證").forEach {
            assertTrue(limits, limits.contains(it))
        }
        val explanation = localized.getString(R.string.recovery_protection_explanation)
        assertTrue(explanation, explanation.contains("不會取代密碼庫或裝置金鑰"))
        assertTrue(localized.getString(R.string.recovery_material_invalid).contains("保留原檔案"))
    }

    @Test fun everyTaiwanPackagedValueMatchesItsSourceAndFormatsSuccessfully() {
        val localized = context.localized("zh-Hant-TW")
        val contract = JSONArray(instrumentation.context.assets.open("taiwan-resource-contract.json")
            .bufferedReader().use { it.readText() })
        assertTrue("Complete current resource contract", contract.length() > 7_300)
        repeat(contract.length()) { index ->
            val entry = contract.getJSONObject(index)
            val name = entry.getString("name")
            val type = entry.getString("type")
            val id = localized.resources.getIdentifier(name, if (type == "string-array") "array" else type, context.packageName)
            assertNotEquals("Missing $name", 0, id)
            val expected = entry.getJSONObject("values")
            when (type) {
                "string" -> {
                    assertEquals(name, expected.getString("text"), localized.getString(id))
                    val types = entry.getJSONArray("arguments")
                    val args = Array<Any>(types.length()) { argument ->
                        when (types.getString(argument)) {
                            "integer" -> 7
                            "float" -> 1.25
                            "character" -> 'A'
                            "boolean" -> true
                            "date" -> 0L
                            else -> "範例"
                        }
                    }
                    if (args.isNotEmpty()) localized.getString(id, *args)
                }
                "string-array" -> {
                    val actual = localized.resources.getStringArray(id)
                    assertEquals(name, expected.length(), actual.size)
                    actual.forEachIndexed { i, text -> assertEquals("$name/$i", expected.getString(i.toString()), text) }
                }
                "plurals" -> assertEquals(name, expected.getString("other"), localized.resources.getQuantityString(id, 2))
            }
        }
    }

    private fun Context.localized(tag: String) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )
}
