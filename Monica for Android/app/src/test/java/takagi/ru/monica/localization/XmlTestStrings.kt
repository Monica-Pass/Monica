package takagi.ru.monica.localization

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver

/** Reads the shipped translations for presentation tests that run without Android. */
internal fun xmlTestStrings(language: String): StringResolver {
    val resourceDirectory = generateSequence(File(System.getProperty("user.dir") ?: ".")) {
        it.parentFile
    }.map { File(it, "app/src/main/res") }.first { it.isDirectory }
    val values = mutableMapOf<String, String>()
    listOf("values", "values-$language").forEach { directory ->
        File(resourceDirectory, directory).listFiles { file -> file.extension == "xml" }
            .orEmpty().forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("string")
                repeat(nodes.length) { index ->
                    val node = nodes.item(index)
                    values[node.attributes.getNamedItem("name").nodeValue] = node.textContent
                        .replace("\\n", "\n")
                        .replace("\\'", "'")
                        .replace("\\\"", "\"")
                        .replace("\\u0020", " ")
                }
            }
    }
    val resourceNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    return StringResolver { id, arguments ->
        val template = values.getValue(resourceNames.getValue(id))
        if (arguments.isEmpty()) template
        else String.format(Locale.forLanguageTag(language), template, *arguments)
    }
}
