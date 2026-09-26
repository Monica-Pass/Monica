package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import takagi.ru.monica.data.Language

class LocaleResourceCoverageTest {
    private data class Resource(
        val type: String,
        val values: Map<String, String>,
        val formatted: Boolean,
    )

    private val localeDirectories = mapOf(
        Language.CHINESE to "values-zh",
        Language.TRADITIONAL_CHINESE to "values-b+zh+Hant",
        Language.CLASSICAL_CHINESE to "values-b+lzh",
        Language.VIETNAMESE to "values-vi",
        Language.JAPANESE to "values-ja",
        Language.RUSSIAN to "values-ru",
        Language.KOREAN to "values-ko",
        Language.GERMAN to "values-de",
        Language.SPANISH to "values-es",
        Language.FRENCH to "values-fr",
        Language.POLISH to "values-pl",
        Language.ITALIAN to "values-it",
    )
    private val base by lazy { resources("values") }

    @Test
    fun theResourceContractCoversEveryAdvertisedLanguage() {
        assertEquals(
            "New language options must also receive full resource checks",
            // Nya and snow leopard intentionally inherit Chinese; their supplied
            // entries have separate format checks.
            Language.entries.toSet() -
                setOf(Language.SYSTEM, Language.ENGLISH, Language.NYA, Language.SNOW_LEOPARD),
            localeDirectories.keys,
        )
    }

    @Test
    fun everyLocaleIncludesEveryTranslatableResourceAcrossAllModules() {
        localeDirectories.forEach { (language, directory) ->
            val localized = resources(directory)
            assertEquals("$language missing resources", emptySet<String>(), base.keys - localized.keys)
            base.forEach { (name, source) ->
                val translated = localized.getValue(name)
                assertEquals("$language/$name type", source.type, translated.type)
                assertEquals("$language/$name formatting mode", source.formatted, translated.formatted)
                if (source.type == "plurals") {
                    assertTrue(
                        "$language/$name must include " + requiredQuantities(language),
                        translated.values.keys.containsAll(requiredQuantities(language)),
                    )
                } else {
                    assertEquals("$language/$name parts", source.values.keys, translated.values.keys)
                }
                translated.values.forEach { (part, text) ->
                    val sourceText = source.values[part] ?: source.values.getValue("other")
                    val label = "$language/$name/$part"
                    if (sourceText.isNotBlank()) assertTrue("$label is empty", text.isNotBlank())
                    if (source.formatted) {
                        assertEquals("$label format arguments", tokens(FORMAT, sourceText), tokens(FORMAT, text))
                    }
                    assertEquals("$label named tokens", tokens(NAMED_TOKEN, sourceText), tokens(NAMED_TOKEN, text))
                    assertEquals("$label explicit line breaks", lineBreaks(sourceText), lineBreaks(text))
                    assertFalse("$label contains translation debris", GUARD_TOKEN.containsMatchIn(text))
                }
            }
        }
    }

    @Test
    fun recentlyAddedFeatureMessagesHaveTranslationsInsteadOfEnglishCopies() {
        // These phrases require translation in every supported language. Brands,
        // native language names and shared words such as French "Note" are excluded.
        val names = listOf(
            "legacy_ui_bitwarden_settings",
            "legacy_ui_bitwarden_login",
            "legacy_ui_unlock_vault",
            "legacy_ui_auto_sync_hint",
            "legacy_ui_bitwarden_login_to_sync",
            "legacy_ui_sync_failed_detail",
            "legacy_ui_sync_queue_empty",
            "keepass_conflict_merge_sync",
            "mdbx_ui_manager_health_title",
            "autofill_v2_default_bitwarden_desc",
            "notification_validator_ready",
        )
        localeDirectories.forEach { (language, directory) ->
            val localized = resources(directory)
            names.forEach { name ->
                assertFalse(
                    "$language/$name still uses its English default",
                    base.getValue(name).values == localized.getValue(name).values,
                )
            }
        }
    }

    private fun resources(directory: String): Map<String, Resource> {
        val resourceRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
            it.parentFile
        }.map { File(it, "app/src/main/res") }.first { it.isDirectory }
        val files = File(resourceRoot, directory).listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        val result = linkedMapOf<String, Resource>()
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        files.sortedBy(File::getName).forEach { file ->
            val children = parser.parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") ||
                    element.getAttribute("translatable") == "false"
                ) continue
                val name = element.getAttribute("name")
                val values = if (element.tagName == "string") mapOf("text" to element.textContent) else {
                    val items = element.getElementsByTagName("item")
                    val parts = linkedMapOf<String, String>()
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex) as Element
                        val part = if (element.tagName == "plurals") item.getAttribute("quantity") else itemIndex.toString()
                        assertNull("Duplicate $directory/$name/$part", parts.put(part, item.textContent))
                    }
                    parts
                }
                assertNull(
                    "Duplicate $directory/$name in " + file.name,
                    result.put(name, Resource(element.tagName, values, element.getAttribute("formatted") != "false")),
                )
            }
        }
        assertTrue("No translatable resources in $directory", result.isNotEmpty())
        return result
    }

    private fun requiredQuantities(language: Language): Set<String> = when (language) {
        Language.CHINESE, Language.TRADITIONAL_CHINESE, Language.CLASSICAL_CHINESE, Language.JAPANESE,
        Language.KOREAN, Language.VIETNAMESE -> setOf("other")
        Language.RUSSIAN, Language.POLISH -> setOf("one", "few", "many", "other")
        else -> setOf("one", "other")
    }

    private fun tokens(pattern: Regex, text: String) = pattern.findAll(text).map { it.value }.sorted().toList()
    private fun lineBreaks(text: String) = text.windowed(2).count { it == "\\n" }

    private companion object {
        val FORMAT = Regex("""(?<!%)%(?!%)(?:\d+\$)?[-+# 0,(<]*(?:\d+|\*)?(?:\.\d+|\.\*)?(?:[tT][a-zA-Z]|[a-zA-Z])""")
        val NAMED_TOKEN = Regex("""\{[A-Za-z_][A-Za-z0-9_.-]*\}""")
        val GUARD_TOKEN = Regex("ZZ(?:QX|SPLIT|XML)|ZXQ|\uFFFD")
    }
}
