package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbxLocaleResourceTest {
    @Test
    fun mdbxAndGroupingTranslationsHaveMatchingKeysAndFormatArguments() {
        val resources = generateSequence(File(System.getProperty("user.dir") ?: ".")) {
            it.parentFile
        }.map { File(it, "app/src/main/res") }.first { it.isDirectory }

        listOf("mdbx_manager_strings.xml", "page_adjustment_strings.xml").forEach { name ->
            val english = readStrings(File(resources, "values/$name"))
            listOf("zh", "ru").forEach { language ->
                val translated = readStrings(File(resources, "values-$language/$name"))
                assertEquals("$language/$name keys", english.keys, translated.keys)
                english.forEach { (key, source) ->
                    val target = translated.getValue(key)
                    assertTrue("$language/$key is empty", target.isNotBlank())
                    assertEquals("$language/$key arguments", placeholders(source), placeholders(target))
                    if (language == "ru") {
                        assertFalse("$key contains Chinese", Regex("[\\p{IsHan}]").containsMatchIn(target))
                    }
                }
            }
        }
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%\d+\${'$'}[sd]""").findAll(text).map { it.value }.sorted().toList()

    private fun readStrings(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        repeat(nodes.length) { index ->
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            check(name !in result) { "Duplicate resource: $name" }
            result[name] = node.textContent
        }
        return result
    }
}
