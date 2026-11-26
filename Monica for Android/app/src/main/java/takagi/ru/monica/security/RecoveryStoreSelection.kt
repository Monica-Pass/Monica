package takagi.ru.monica.security

import android.util.JsonReader
import java.io.File
import java.io.StringReader
import java.util.UUID
import org.json.JSONObject

internal sealed interface RecoveryStoreSelection {
    data object Original : RecoveryStoreSelection
    data class Keystore(val id: String) : RecoveryStoreSelection {
        val name get() = "monica_recovered_$id"
        val alias get() = "monica_recovered_key_$id"
    }
    data class Password(val id: String) : RecoveryStoreSelection
}

/** The password selector deliberately has no legacy `id`: old APKs must refuse to open it. */
internal object RecoveryStoreSelectors {
    fun read(directory: File): RecoveryStoreSelection {
        val primary = File(directory, "active-store.json")
        val backup = File(primary.path + ".bak")
        val file = if (backup.exists()) backup else primary
        if (!file.exists()) return RecoveryStoreSelection.Original
        require(file.length() in 1..4096) { "Invalid recovery selector" }
        val text = file.inputStream().use { input ->
            val bytes = ByteArray(4097)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            require(count in 1..4096)
            String(bytes, 0, count, Charsets.UTF_8)
        }
        return JsonReader(StringReader(text)).use { reader ->
            val fields = mutableSetOf<String>()
            var id: String? = null
            var kind: String? = null
            var store: String? = null
            var version: Int? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(fields.add(name)) { "Duplicate recovery selector field" }
                when (name) {
                    "id" -> id = reader.nextString()
                    "kind" -> kind = reader.nextString()
                    "store" -> store = reader.nextString()
                    "version" -> version = reader.nextInt()
                    else -> error("Unsupported recovery selector field")
                }
            }
            reader.endObject()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
            if (fields == setOf("id")) {
                RecoveryStoreSelection.Keystore(UUID.fromString(requireNotNull(id)).toString())
            } else {
                require(fields == setOf("kind", "version", "store") && kind == "password" && version == 2)
                RecoveryStoreSelection.Password(UUID.fromString(requireNotNull(store)).toString())
            }
        }
    }

    fun envelope(directory: File, selection: RecoveryStoreSelection): File =
        File(directory, if (selection is RecoveryStoreSelection.Password) "password-store-${selection.id}.json" else "recovery.json")

    fun encode(selection: RecoveryStoreSelection.Password): JSONObject =
        JSONObject().put("kind", "password").put("version", 2).put("store", selection.id)
}
