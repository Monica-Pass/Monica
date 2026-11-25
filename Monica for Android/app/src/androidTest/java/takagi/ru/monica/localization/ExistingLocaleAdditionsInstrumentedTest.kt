package takagi.ru.monica.localization

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExistingLocaleAdditionsInstrumentedTest {
    @Test fun everyAddedTranslationIsSelectedAndCompiledWithoutFallbackOrEscapingLoss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entries = JSONArray(instrumentation.context.assets.open("existing-locale-additions-331.json")
            .bufferedReader().use { it.readText() })
        assertTrue(entries.length() > 6_000)
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val tag = entry.getString("tag")
            val name = entry.getString("name")
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            val id = localized.resources.getIdentifier(name, "string", context.packageName)
            assertNotEquals("$tag/$name", 0, id)
            assertEquals("$tag/$name", entry.getString("expected"), localized.getString(id))
        }
    }
}
