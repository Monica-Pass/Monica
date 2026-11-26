package takagi.ru.monica.localization

import android.content.Context
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.Language
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.StartupLanguageCache

class ArabicLocaleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun regionalArabicContextsAreRtlAndEnglishSwitchRestoresLtr() {
        val original = Locale.getDefault()
        try {
            for (tag in listOf("ar", "ar-EG", "ar-SA", "ar-AE", "ar-MA")) {
                val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
                assertEquals(tag, Language.ARABIC, LocaleHelper.getCurrentLanguage(localized))
                assertEquals("إلغاء", localized.getString(R.string.cancel))
                assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, localized.resources.configuration.layoutDirection)
                val english = LocaleHelper.setLocale(localized, Language.ENGLISH)
                assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, english.resources.configuration.layoutDirection)
                assertEquals("Cancel", english.getString(R.string.cancel))
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun savedLocaleAndSortSurviveFreshSettingsAndColdLanguageCache() = runBlocking {
        val user = InstrumentationRegistry.getArguments().getString("localeTestUser")!!.toInt()
        require(user > 0 && android.os.Process.myUid() / 100000 == user)
        val manager = SettingsManager(context)
        val original = manager.settingsFlow.first()
        try {
            for (language in listOf(Language.ARABIC, Language.FRENCH, Language.ENGLISH, Language.ARABIC)) {
                manager.updateLanguage(language)
                manager.updateVaultListSort(takagi.ru.monica.data.VaultListSort.UPDATED_DESC)
                val fresh = SettingsManager(context).settingsFlow.first()
                assertEquals(language, fresh.language)
                assertEquals(takagi.ru.monica.data.VaultListSort.UPDATED_DESC, fresh.vaultListSort)
                StartupLanguageCache::class.java.getDeclaredField("processLanguage").apply { isAccessible = true; set(null, null) }
                assertEquals(language, StartupLanguageCache.read(context))
            }
        } finally { manager.updateLanguage(original.language); manager.updateVaultListSort(original.vaultListSort) }
    }

    @Test fun everyPackagedArabicResourceMatchesAndAllFormatArgumentsWork() {
        val original = Locale.getDefault()
        try {
            val localized = LocaleHelper.setLocale(context, Language.ARABIC)
            val contract = JSONArray(instrumentation.context.assets.open("arabic-ARABIC-resource-contract.json").bufferedReader().use { it.readText() })
            assertTrue(contract.length() > 7_370)
            repeat(contract.length()) { index ->
                val entry = contract.getJSONObject(index)
                val name = entry.getString("name")
                val type = entry.getString("type")
                val id = localized.resources.getIdentifier(name, if (type == "string-array") "array" else type, context.packageName)
                assertNotEquals(name, 0, id)
                val expected = entry.getJSONObject("values")
                when (type) {
                    "string" -> {
                        assertEquals(name, expected.getString("text"), localized.getString(id))
                        val types = entry.getJSONArray("arguments")
                        val args = Array<Any>(types.length()) { i -> when(types.getString(i)) {
                            "integer" -> 7; "float" -> 1.25; "character" -> 'A'; "boolean" -> true; "date" -> 0L; else -> "sample"
                        } }
                        if (args.isNotEmpty()) localized.getString(id, *args)
                    }
                    "string-array" -> {
                        val actual = localized.resources.getStringArray(id)
                        assertEquals(name, expected.length(), actual.size)
                        actual.forEachIndexed { i, text -> assertEquals("$name/$i", expected.getString(i.toString()), text) }
                    }
                    "plurals" -> mapOf(0 to "zero", 1 to "one", 2 to "two", 3 to "few", 11 to "many", 100 to "other").forEach { (n, category) ->
                        assertEquals("$name/$category", expected.getString(category), localized.resources.getQuantityString(id, n))
                        assertEquals(String.format(Locale.forLanguageTag("ar"), expected.getString(category), n), localized.resources.getQuantityString(id, n, n))
                    }
                }
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun actualAndroidOtpOutputUsesAsciiInArabicLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("94287082", takagi.ru.monica.util.TotpGenerator.generateTotp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59, digits = 8))
            assertEquals("755224", takagi.ru.monica.util.TotpGenerator.generateHotp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 0))
        } finally { Locale.setDefault(original) }
    }
}
