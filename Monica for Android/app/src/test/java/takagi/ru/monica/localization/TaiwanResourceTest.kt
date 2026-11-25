package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import takagi.ru.monica.data.Language

class TaiwanResourceTest {
    private fun values(directory: String): Map<String, String> {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .map { File(it, "app/src/main/res/$directory") }.first { it.isDirectory }
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return root.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val children = parser.parse(file).documentElement.childNodes
            (0 until children.length).mapNotNull { children.item(it) as? Element }
                .filter { it.tagName == "string" }.map { it.getAttribute("name") to it.textContent }
        }.toMap()
    }

    @Test fun taiwanUsesRegionalTermsAndRetainsTechnicalTokens() {
        val tw = values("values-b+zh+Hant+TW")
        assertEquals("設定", tw.getValue("settings"))
        assertEquals("資料庫", tw.getValue("password_picker_filter_database"))
        assertEquals("資料夾", tw.getValue("password_picker_filter_folder"))
        assertTrue(tw.getValue("software_free_description").contains("軟體"))
        assertTrue(tw.getValue("biometric_title").contains("生物辨識"))
        assertTrue(tw.getValue("biometric_login_subtitle").contains("身分"))
        assertTrue(tw.getValue("password_copied").contains("剪貼簿"))
        assertEquals("刪除未完成", tw.getValue("batch_delete_incomplete_title"))
        assertEquals("支持 Monica Pass", tw.getValue("plus_honesty_title"))
        listOf("{USERNAME}", "{TAB}", "{PASSWORD}", "{ENTER}").forEach {
            assertTrue(tw.getValue("keepass_native_auto_type_tokens_hint").contains(it))
        }
        val old = values("values-b+zh+Hant")
        assertTrue(old.getValue("software_free_description").contains("軟件"))
        assertTrue(old.getValue("biometric_login_subtitle").contains("身份"))
    }

    @Test fun existingSavedLanguageNamesAndOrdinalsStayStable() {
        val old = listOf("SYSTEM", "ENGLISH", "CHINESE", "CLASSICAL_CHINESE", "VIETNAMESE",
            "JAPANESE", "RUSSIAN", "KOREAN", "GERMAN", "SPANISH", "FRENCH", "POLISH",
            "TRADITIONAL_CHINESE", "ITALIAN", "PORTUGUESE_PORTUGAL", "PORTUGUESE_BRAZIL")
        assertEquals(old, Language.entries.take(old.size).map { it.name })
        assertEquals(old.size, Language.TRADITIONAL_CHINESE_TAIWAN.ordinal)
        val labels = values("values")
        assertEquals("繁體中文（香港）", labels.getValue("language_chinese_traditional"))
        assertEquals("繁體中文（台灣）", labels.getValue("language_chinese_traditional_taiwan"))
    }
}
