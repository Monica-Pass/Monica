package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import takagi.ru.monica.data.Language

class IndonesianTurkishResourceTest {
    @Test fun existingLanguageNamesAndPositionsRemainStable() {
        val previous = listOf("SYSTEM", "ENGLISH", "CHINESE", "CLASSICAL_CHINESE", "VIETNAMESE",
            "JAPANESE", "RUSSIAN", "KOREAN", "GERMAN", "SPANISH", "FRENCH", "POLISH",
            "TRADITIONAL_CHINESE", "ITALIAN", "PORTUGUESE_PORTUGAL", "PORTUGUESE_BRAZIL",
            "TRADITIONAL_CHINESE_TAIWAN")
        assertEquals(previous, Language.entries.take(previous.size).map { it.name })
        assertEquals(previous.size, Language.INDONESIAN.ordinal)
        assertEquals(previous.size + 1, Language.TURKISH.ordinal)
    }

    @Test fun terminologyAndRecoveryConditionsAreLocalized() {
        val expected = mapOf("values-in" to listOf("Kata sandi", "Kunci sandi", "Setelan"),
            "values-tr" to listOf("Şifre", "Geçiş anahtarı", "Ayarlar"))
        expected.forEach { (folder, terms) ->
            val values = strings(folder)
            assertEquals(terms[0], values.getValue("password"))
            assertEquals(terms[1], values.getValue("passkey"))
            assertEquals(terms[2], values.getValue("settings"))
            listOf("{USERNAME}", "{TAB}", "{PASSWORD}", "{ENTER}").forEach {
                assertTrue(values.getValue("keepass_native_auto_type_tokens_hint").contains(it))
            }
            val limits = values.getValue("recovery_protection_limits")
            if (folder == "values-in") {
                listOf("tidak menjamin", "perangkat keras", "verifikasi ulang").forEach { assertTrue(limits, limits.contains(it)) }
            } else {
                listOf("garanti etmez", "donanıma bağlı", "yeniden doğrulama").forEach { assertTrue(limits, limits.contains(it)) }
            }
        }
    }

    private fun strings(folder: String): Map<String, String> {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { File(it, "app/src/main/res/$folder") }.first { it.isDirectory }
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        return root.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val children = parser.parse(file).documentElement.childNodes
            (0 until children.length).mapNotNull { children.item(it) as? Element }
                .filter { it.tagName == "string" }.map { it.getAttribute("name") to it.textContent }
        }.toMap()
    }
}
