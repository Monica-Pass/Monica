package takagi.ru.monica.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import takagi.ru.monica.data.Language

object LocaleHelper {

    fun setLocale(context: Context, language: Language): Context {
        val locale = when (language) {
            Language.SYSTEM -> getSystemLocale()
            Language.ENGLISH -> Locale.ENGLISH
            Language.CHINESE -> Locale.forLanguageTag("zh-Hans-CN")
            Language.TRADITIONAL_CHINESE -> Locale.forLanguageTag("zh-Hant-HK")
            Language.CLASSICAL_CHINESE -> Locale.forLanguageTag("lzh")
            Language.VIETNAMESE -> Locale("vi", "VN")
            Language.JAPANESE -> Locale.JAPAN
            Language.RUSSIAN -> Locale("ru", "RU")
            Language.KOREAN -> Locale.KOREA
            Language.GERMAN -> Locale.GERMANY
            Language.SPANISH -> Locale("es", "ES")
            Language.FRENCH -> Locale.FRENCH
            Language.POLISH -> Locale.forLanguageTag("pl")
            Language.ITALIAN -> Locale.ITALIAN
            Language.NYA -> Locale("zh", "NY")
            Language.SNOW_LEOPARD -> Locale("zh", "XB")
        }

        return updateResources(context, locale)
    }

    private fun getSystemLocale(): Locale {
        val systemConfig = android.content.res.Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemConfig.locales[0]
        } else {
            @Suppress("DEPRECATION")
            systemConfig.locale
        }
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        // Android does not infer a Hans fallback for lzh or Hant. Keep missing
        // translations readable in Chinese before falling back to English.
        val fallbackLocales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            chineseFallbackLocales(locale)
        } else {
            null
        }

        // Most cold starts already have the requested locale (especially
        // Language.SYSTEM). Avoid cloning Configuration and creating another
        // Context in that common path while still resetting Locale.default.
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        // Some devices report zh-Hant-CN. Matching only language and country
        // would retain Hant when Chinese is selected, missing our Hans resources.
        if (currentLocale == locale &&
            (fallbackLocales == null || context.resources.configuration.locales == fallbackLocales)
        ) {
            return context
        }

        val config = Configuration(context.resources.configuration)
        if (fallbackLocales != null) {
            config.setLocales(fallbackLocales)
        } else {
            config.setLocale(locale)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    internal fun chineseFallbackLocales(locale: Locale): LocaleList? = when {
        locale.language == "lzh" -> LocaleList(locale, Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)
        isTraditionalChinese(locale) -> LocaleList(locale, Locale.forLanguageTag("zh-Hans-CN"), Locale.ENGLISH)
        else -> null
    }

    private fun isTraditionalChinese(locale: Locale): Boolean = locale.language == "zh" &&
        (locale.script == "Hant" ||
            (locale.script.isEmpty() && locale.country in listOf("TW", "HK", "MO")))

    fun getCurrentLanguage(context: Context): Language {
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }

        return when (currentLocale.language) {
            "zh" -> when {
                currentLocale.country == "NY" -> Language.NYA
                currentLocale.country == "XB" -> Language.SNOW_LEOPARD
                isTraditionalChinese(currentLocale) -> Language.TRADITIONAL_CHINESE
                else -> Language.CHINESE
            }
            "lzh" -> Language.CLASSICAL_CHINESE
            "en" -> Language.ENGLISH
            "vi" -> Language.VIETNAMESE
            "ja" -> Language.JAPANESE
            "ru" -> Language.RUSSIAN
            "ko" -> Language.KOREAN
            "de" -> Language.GERMAN
            "es" -> Language.SPANISH
            "fr" -> Language.FRENCH
            "pl" -> Language.POLISH
            "it" -> Language.ITALIAN
            else -> Language.SYSTEM
        }
    }
}
