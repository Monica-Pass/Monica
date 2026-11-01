package takagi.ru.monica.localization

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class PortugueseResourceTest {
    private data class Resource(val type: String, val formatted: Boolean, val parts: Map<String, String>)
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
        .map { File(it, "app/src/main/res") }.first { it.isDirectory }
    private val source by lazy { resources("values") }
    private val regions by lazy { listOf("values-pt", "values-pt-rBR").associateWith(::resources) }

    @Test fun bothRegionsCoverEveryCurrentTranslatableResourceAndPreserveContracts() {
        assertTrue("Current complete catalog", source.size > 7_000)
        regions.forEach { (directory, localized) ->
            assertEquals("$directory missing keys", emptySet<String>(), source.keys - localized.keys)
            source.forEach { (name, original) ->
                val translated = localized.getValue(name)
                assertEquals("$directory/$name type", original.type, translated.type)
                assertEquals("$directory/$name formatting mode", original.formatted, translated.formatted)
                assertEquals("$directory/$name parts", original.parts.keys, translated.parts.keys)
                translated.parts.forEach { (part, text) ->
                    val base = original.parts.getValue(part)
                    val label = "$directory/$name/$part"
                    if (base.isNotBlank()) assertTrue("$label is blank", text.isNotBlank())
                    if (original.formatted) assertEquals("$label placeholders", FORMAT.findAll(base).map { it.value }.sorted().toList(), FORMAT.findAll(text).map { it.value }.sorted().toList())
                    assertEquals("$label named tokens", NAMED.findAll(base).map { it.value }.sorted().toList(), NAMED.findAll(text).map { it.value }.sorted().toList())
                    assertEquals("$label newlines", base.windowed(2).count { it == "\\n" }, text.windowed(2).count { it == "\\n" })
                    assertFalse("$label broken Unicode", text.contains('\uFFFD'))
                    assertFalse("$label translation debris", Regex("ZZ(?:QX|SPLIT|XML)|ZXQ").containsMatchIn(text))
                }
            }
        }
    }

    @Test fun shortDockLabelsFitTheCompactVocabularyAndRemainDistinct() {
        val names = listOf("nav_v2_vault_short", "nav_passwords_short", "nav_authenticator_short", "nav_card_wallet_short", "nav_generator_short", "nav_notes_short", "nav_v2_send_short", "nav_passkey_short", "nav_steam_short", "nav_settings_short")
        regions.forEach { (directory, localized) ->
            val labels = names.map { localized.getValue(it).parts.getValue("text").trim('"') }
            labels.forEach { label -> assertTrue("$directory Dock too long: $label", label.length <= 6) }
            assertEquals("$directory Dock labels must identify distinct destinations", labels.size, labels.toSet().size)
        }
    }

    @Test fun regionalTermsAndSecurityConfirmationRemainExplicit() {
        val portugal = regions.getValue("values-pt")
        val brazil = regions.getValue("values-pt-rBR")
        assertEquals("Palavra-passe mestra", portugal.getValue("master_password").parts.getValue("text").trim('"'))
        assertEquals("Senha mestra", brazil.getValue("master_password").parts.getValue("text").trim('"'))
        assertEquals("Guardar", portugal.getValue("save").parts.getValue("text").trim('"'))
        assertEquals("Salvar", brazil.getValue("save").parts.getValue("text").trim('"'))
        val ptConfirmation = portugal.getValue("local_recovery_confirm_description").parts.getValue("text")
        val brConfirmation = brazil.getValue("local_recovery_confirm_description").parts.getValue("text")
        assertTrue(ptConfirmation, ptConfirmation.contains("ficheiros originais serão mantidos"))
        assertTrue(brConfirmation, brConfirmation.contains("arquivos originais serão mantidos"))
        listOf(ptConfirmation, brConfirmation).forEach { text ->
            assertTrue(text, text.contains("antes"))
            assertTrue(text, text.contains("biometria"))
            assertTrue(text, text.contains("Se cancelar"))
            assertTrue(text, text.contains("dados não serão alterados"))
        }
    }

    private fun resources(directory: String): Map<String, Resource> {
        val result = linkedMapOf<String, Resource>()
        val parser = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.newDocumentBuilder()
        val files = File(root, directory).listFiles { f -> f.extension == "xml" }.orEmpty()
        assertTrue("Missing $directory", files.isNotEmpty())
        files.sortedBy(File::getName).forEach { file ->
            val children = parser.parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                if (element.tagName !in setOf("string", "plurals", "string-array") || element.getAttribute("translatable") == "false") continue
                val parts = if (element.tagName == "string") mapOf("text" to element.textContent) else {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate { itemIndex ->
                        val item = items.item(itemIndex) as Element
                        (if (element.tagName == "plurals") item.getAttribute("quantity") else itemIndex.toString()) to item.textContent
                    }
                }
                val name = element.getAttribute("name")
                assertNull("Duplicate $directory/$name", result.put(name, Resource(element.tagName, element.getAttribute("formatted") != "false", parts)))
            }
        }
        return result
    }

    companion object {
        private val FORMAT = Regex("""(?<!%)%(?!%)(?:\d+\$)?[-+# 0,(<]*(?:\d+|\*)?(?:\.\d+|\.\*)?(?:[tT][a-zA-Z]|[a-zA-Z])""")
        private val NAMED = Regex("""\{[A-Za-z_][A-Za-z0-9_.-]*\}""")
    }
}
