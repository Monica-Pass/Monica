package takagi.ru.monica.notes.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.security.SecurityManager

/**
 * Notes store plain item data and their readers never decrypt it. Nearby sync and
 * database-archive restores used to write received notes as vault ciphertext, so the
 * list and detail pages showed the raw `MDK|...` payload. Restores the plain shape in
 * place; edit dates and sync ownership stay unchanged.
 */
internal object NoteCiphertextRepair {
    private const val MAX_LAYERS = 3
    private val mutex = Mutex()

    suspend fun run(dao: SecureItemDao, security: SecurityManager): Int = mutex.withLock {
        var changed = 0
        for (item in dao.getNotesContainingCiphertext()) {
            // Ciphertext that cannot be opened now (locked vault, other key) stays byte-for-byte unchanged.
            val (itemData, notes) = runCatching {
                repair(item.itemData, item.notes, security::looksLikeMonicaCiphertext, security::decryptData)
            }.getOrNull() ?: continue
            changed += dao.compareAndSetNoteData(item.id, item.itemData, item.notes, itemData, notes)
        }
        changed
    }

    /** Returns the repaired (itemData, notes) pair, or null when the note holds no vault ciphertext. */
    internal fun repair(
        itemData: String,
        notes: String,
        isCiphertext: (String) -> Boolean,
        decrypt: (String) -> String
    ): Pair<String, String>? {
        val plain = unwrap(itemData, isCiphertext, decrypt)
            // Saving an affected note from the editor wrapped the ciphertext as its content.
            ?: savedContent(itemData)
                ?.let { unwrap(it, isCiphertext, decrypt) }
                ?.takeIf { it.trimStart().startsWith("{") }
            ?: return null
        val repairedNotes = if (isCiphertext(notes.trim())) NoteContentCodec.decode(plain, "").content else notes
        return plain to repairedNotes
    }

    private fun unwrap(value: String, isCiphertext: (String) -> Boolean, decrypt: (String) -> String): String? {
        var current = value.trim()
        if (!isCiphertext(current)) return null
        repeat(MAX_LAYERS) {
            current = decrypt(current)
            if (!isCiphertext(current.trim())) return current
        }
        return null
    }

    private fun savedContent(itemData: String): String? {
        val json = runCatching { Json.parseToJsonElement(itemData.trim()) }.getOrNull() as? JsonObject ?: return null
        return (json["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
