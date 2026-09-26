package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/**
 * The 喵喵语 pack is generated from values-zh by scripts/generate_nya_strings.py
 * and shipped as the fake-region variant values-zh-rNY (Locale("zh", "NY")).
 * The 芝士雪豹语 pack is values-zh-rNY with 喵 replaced by 嗷呜, shipped as
 * values-zh-rXB (Locale("zh", "XB")).
 * Missing keys, including short dock labels, intentionally fall back to
 * values-zh. Validate supplied translations without requiring full coverage
 * or a suffix on every label.
 */
class NyaResourceCoverageTest {
    private data class Resource(val type: String, val values: Map<String, String>)

    @Test
    fun suppliedTranslationsPreserveResourceFormats() {
        val source = resources("values-zh")
        listOf(
            "values-zh-rNY" to "Nya",
            "values-zh-rXB" to "Snow leopard",
        ).forEach { (directory, label) ->
            val variant = resources(directory)
            assertTrue("$label resources must have a Chinese source", source.keys.containsAll(variant.keys))
            variant.forEach { (name, translated) ->
                val sourceText = source.getValue(name)
                assertEquals("$name type", sourceText.type, translated.type)
                assertEquals("$name quantities or array indices", sourceText.values.keys, translated.values.keys)
                sourceText.values.forEach { (part, sourceValue) ->
                    val text = translated.values.getValue(part)
                    if (sourceValue.isNotBlank()) assertTrue("$name/$part is empty", text.isNotBlank())
                    assertEquals("$name/$part format arguments", placeholders(sourceValue), placeholders(text))
                    assertEquals("$name/$part explicit line breaks", sourceValue.windowed(2).count { it == "\\n" },
                        text.windowed(2).count { it == "\\n" })
                    assertFalse("$name/$part has translation debris", Regex("ZXQ|ZZQX|ZZSPLIT|ZZXML|\\uFFFD").containsMatchIn(text))
                    for (brand in listOf("Monica", "KeePass", "Bitwarden", "Steam", "WebDAV", "MDBX")) {
                        if (sourceValue.contains(brand, ignoreCase = true)) {
                            assertTrue("$name/$part must preserve $brand", text.contains(brand, ignoreCase = true))
                        }
                    }
                }
            }
        }
    }

    private fun resources(directory: String): Map<String, Resource> {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (root.parentFile != null && !File(root, "settings.gradle.kts").exists() && !File(root, "settings.gradle").exists()) {
            root = root.parentFile.canonicalFile
        }
        val files = File(root, "app/src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        val result = linkedMapOf<String, Resource>()
        files.sortedBy(File::getName).forEach { file ->
            val children = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") || element.getAttribute("translatable") == "false") continue
                val name = element.getAttribute("name")
                val values = if (element.tagName == "string") mapOf("text" to element.textContent) else {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate { itemIndex ->
                        val item = items.item(itemIndex) as Element
                        (if (element.tagName == "plurals") item.getAttribute("quantity") else itemIndex.toString()) to item.textContent
                    }
                }
                assertNull("Duplicate $name in ${file.name}", result.put(name, Resource(element.tagName, values)))
            }
        }
        return result
    }

    private fun placeholders(value: String) = Regex("""%(?:\d+\$)?[-+# 0,(]*(?:\d+|\*)?(?:\.\d+|\.\*)?[a-zA-Z]""")
        .findAll(value).map { it.value }.sorted().toList()
}
