package takagi.ru.monica.localization

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalIdentifiersInstrumentedTest {
    @Test fun protocolsTemplatesAndFormatNamesRemainUsableInEveryPackagedLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val contract = JSONArray(instrumentation.context.assets.open("technical-identifiers.json").bufferedReader().use { it.readText() })
        for (tag in listOf("en", "ar", "lzh", "zh-Hans-CN", "zh-Hant-HK", "zh-Hant-TW", "zh-NY", "zh-XB",
            "vi", "ja", "ru", "ko", "de", "es", "fr", "pl", "it", "pt", "pt-PT", "pt-BR", "id", "tr")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            repeat(contract.length()) { index ->
                val item = contract.getJSONObject(index)
                val name = item.getString("name")
                val id = localized.resources.getIdentifier(name, "string", context.packageName)
                val text = localized.getString(id)
                val tokens = item.getJSONArray("tokens")
                repeat(tokens.length()) { i ->
                    val token = tokens.getString(i)
                    assertTrue("$tag/$name must retain $token: $text", text.contains(token))
                }
            }
            assertEquals("otpauth://totp/...", localized.getString(takagi.ru.monica.R.string.totp_import_uri_dialog_hint))
            assertEquals("https://example.com", localized.getString(takagi.ru.monica.R.string.link_example))
        }
    }
}
