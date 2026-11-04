package takagi.ru.monica.bitwarden.sync

import java.io.ByteArrayOutputStream

internal object BitwardenNotificationTestFrames {
    fun event(user: String?, type: Int = 1, device: String? = null, organization: String? = null): ByteArray =
        frame(listOf(1, emptyMap<String, Any>(), null, "ReceiveMessage", listOf(linkedMapOf(
            "ContextId" to device, "Type" to type, "Payload" to linkedMapOf(
                "UserId" to user, "OrganizationId" to organization, "Id" to "synthetic-item")))))

    fun frame(value: Any?): ByteArray {
        val body = document(value)
        val prefix = ByteArrayOutputStream()
        var length = body.size
        do { prefix.write((length and 127) or if (length > 127) 128 else 0); length = length ushr 7 } while (length != 0)
        return prefix.toByteArray() + body
    }

    fun document(value: Any?): ByteArray {
        val output = ByteArrayOutputStream()
        fun write(value: Any?) {
            when (value) {
                null -> output.write(192)
                is Int -> { require(value in 0..127); output.write(value) }
                is String -> {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    if (bytes.size < 32) output.write(160 + bytes.size)
                    else { require(bytes.size < 256); output.write(217); output.write(bytes.size) }
                    output.write(bytes)
                }
                is List<*> -> { require(value.size < 16); output.write(144 + value.size); value.forEach { write(it) } }
                is Map<*, *> -> { require(value.size < 16); output.write(128 + value.size); value.forEach { (k, v) -> write(k); write(v) } }
                else -> error("Unsupported test value")
            }
        }
        write(value)
        return output.toByteArray()
    }
}
