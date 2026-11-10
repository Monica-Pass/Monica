package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.LocaleHelper

/** Reads the installed application's compiled resources; never changes saved language settings. */
class PackagedLocaleFilterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var originalLocale: Locale

    @Before fun rememberDefaultLocale() { originalLocale = Locale.getDefault() }
    @After fun restoreDefaultLocale() { Locale.setDefault(originalLocale) }

    @Test fun everySelectableLanguageStillResolvesItsOwnCompiledTranslation() {
        val expected = mapOf(
            Language.ENGLISH to "Cancel",
            Language.CHINESE to "取消",
            Language.TRADITIONAL_CHINESE to "取消",
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
            Language.PORTUGUESE_PORTUGAL to "Cancelar",
            Language.PORTUGUESE_BRAZIL to "Cancelar",
        )
        assertEquals(Language.entries.filter { it != Language.SYSTEM }.toSet(), expected.keys)
        expected.forEach { (language, translation) ->
            val localized = LocaleHelper.setLocale(context, language)
            assertEquals(language, LocaleHelper.getCurrentLanguage(localized))
            assertEquals(language.name, translation, localized.getString(R.string.cancel))
        }
    }

    @Test fun classicalAndTraditionalChineseRetainTranslationsAndDefaultFallback() {
        val classical = LocaleHelper.setLocale(context, Language.CLASSICAL_CHINESE)
        assertEquals("罢", classical.getString(R.string.cancel))
        assertEquals("lzh,zh-CN,en", classical.resources.configuration.locales.toLanguageTags())
        // The old APK on API 35 selects lzh at package level. An absent lzh entry
        // uses the unqualified English value, despite the declared locale list.
        assertEquals("Update an existing password?", classical.getString(R.string.autofill_save_review_title))
        val hans = context.localized("zh-Hans-CN")
        assertEquals("更新已有密码？", hans.getString(R.string.autofill_save_review_title))
        assertEquals("删除未完成", hans.getString(R.string.batch_delete_incomplete_title))

        for (tag in listOf("zh-Hant-HK", "zh-Hant-TW", "zh-Hant-MO")) {
            val locale = Locale.forLanguageTag(tag)
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(checkNotNull(LocaleHelper.chineseFallbackLocales(locale)))
            }
            val traditional = context.createConfigurationContext(configuration)
            assertEquals("$tag,zh-Hans-CN,en", traditional.resources.configuration.locales.toLanguageTags())
            assertEquals("儲存", traditional.getString(R.string.save))
            assertEquals("Deletion incomplete", traditional.getString(R.string.batch_delete_incomplete_title))
            assertEquals(Language.TRADITIONAL_CHINESE, LocaleHelper.getCurrentLanguage(traditional))
        }
    }

    @Test fun legacyChineseRegionResourcesRemainDistinctWithoutRestoringRetiredSettings() {
        val expected = mapOf(
            "zh-CN" to "在此设备记住两步验证",
            "zh-NY" to "在这台设备记住两步验证喵",
            "zh-XB" to "这台设备记住两步验证",
        )
        expected.forEach { (tag, translation) ->
            assertEquals(tag, translation, context.localized(tag).getString(R.string.bitwarden_remember_two_factor))
        }
        assertEquals("仍在同步喵。", context.localized("zh-NY").getString(R.string.bitwarden_sync_issues_running))
    }

    @Test fun portugueseGenericPortugalAngolaAndBrazilKeepTheRegionalFallbacks() {
        for (tag in listOf("pt", "pt-PT", "pt-AO")) {
            val localized = context.localized(tag)
            assertEquals(tag, "Palavra-passe", localized.getString(R.string.password))
            assertEquals(tag, "Guardar", localized.getString(R.string.save))
        }
        val brazil = context.localized("pt-BR")
        assertEquals("Senha", brazil.getString(R.string.password))
        assertEquals("Salvar", brazil.getString(R.string.save))
    }

    @Test fun unsupportedLanguagesUseTheDefaultEnglishAppResources() {
        val english = context.localized("en-US")
        for (tag in listOf("ar", "tr", "th", "nl", "uk")) {
            val localized = context.localized(tag)
            assertEquals(tag, "Cancel", localized.getString(R.string.cancel))
            assertEquals(tag, english.getString(R.string.local_password_protection_scope),
                localized.getString(R.string.local_password_protection_scope))
        }
        // Also exercise a dependency-owned string, which must remain resolvable after filtering.
        val dependencyId = context.resources.getIdentifier("abc_action_bar_up_description", "string", context.packageName)
        assertTrue("AppCompat resource is present", dependencyId != 0)
        assertEquals(english.getString(dependencyId), context.localized("ar").getString(dependencyId))
    }

    private fun Context.localized(tag: String): Context = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )
}
