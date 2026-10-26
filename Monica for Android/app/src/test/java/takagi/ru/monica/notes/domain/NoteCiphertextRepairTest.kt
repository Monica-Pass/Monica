package takagi.ru.monica.notes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteCiphertextRepairTest {
    private val sealed = mutableMapOf<String, String>()

    private fun seal(plain: String): String = "MDK|${sealed.size}".also { sealed[it] = plain }
    private fun isCiphertext(value: String) = value.startsWith("MDK|") || value.startsWith("C2|") || value.startsWith("V2|")
    private fun decrypt(value: String): String = sealed[value] ?: error("unknown key")
    private fun repair(itemData: String, notes: String) = NoteCiphertextRepair.repair(itemData, notes, ::isCiphertext, ::decrypt)

    private val original = NoteContentCodec.encode("恢复码 R71263", tags = listOf("recovery")).first

    @Test
    fun `sealed item data becomes the original note json and keeps the plain notes cache`() {
        assertEquals(original to "恢复码 R71263", repair(seal(original), "恢复码 R71263"))
    }

    @Test
    fun `repeated sealing is fully unwrapped`() {
        assertEquals(original to "", repair(seal(seal(original)), ""))
    }

    @Test
    fun `note saved again from the editor recovers its original content`() {
        val ciphertext = seal(original)
        val (resaved, notesCache) = NoteContentCodec.encode(ciphertext)

        val (itemData, notes) = repair(resaved, notesCache)!!

        assertEquals(original, itemData)
        assertEquals("恢复码 R71263", notes)
        assertEquals(listOf("recovery"), NoteContentCodec.decode(itemData, notes).tags)
    }

    @Test
    fun `plain notes that only mention a prefix are left alone`() {
        assertNull(repair(original, "恢复码 R71263"))
        assertNull(repair(NoteContentCodec.encode("format: MDK|base64").first, "format: MDK|base64"))
    }

    @Test(expected = IllegalStateException::class)
    fun `ciphertext from another key is reported instead of rewritten`() {
        repair("MDK|foreign", "")
    }
}
