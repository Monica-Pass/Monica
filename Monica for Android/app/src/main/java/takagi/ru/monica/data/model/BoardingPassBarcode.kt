package takagi.ru.monica.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Decoded original text and symbology. Never trim or regenerate it from itinerary fields. */
@Serializable
data class BoardingPassBarcode(val payload: String, val format: String)

object BoardingPassBarcodeCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(code: BoardingPassBarcode?): String = code?.let { json.encodeToString(it) }.orEmpty()
    // A present empty field explicitly removes a code. Malformed remote data fails sync
    // instead of silently replacing an existing valid code with null.
    fun decode(value: String): BoardingPassBarcode? {
        if (value.isEmpty()) return null
        return try { json.decodeFromString<BoardingPassBarcode>(value) }
        catch (_: SerializationException) {
            // Serialization diagnostics can contain input; never pass barcode text to sync logs.
            throw SerializationException("Invalid boarding pass code")
        }
    }
}
