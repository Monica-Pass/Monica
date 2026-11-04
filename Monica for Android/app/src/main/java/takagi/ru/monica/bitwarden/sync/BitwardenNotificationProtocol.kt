package takagi.ru.monica.bitwarden.sync

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only known account notifications can request a normal authenticated sync. */
internal object BitwardenNotificationProtocol {
    const val MAX_FRAME_BYTES = 64 * 1024
    const val HANDSHAKE = "{\"protocol\":\"messagepack\",\"version\":1}\u001e"
    val ping: ByteArray get() = byteArrayOf(2, 0x91.toByte(), 6)

    fun hubUrl(serverUrl: String): HttpUrl? {
        val base = serverUrl.trim().toHttpUrlOrNull() ?: return null
        if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null) return null
        val officialRegion = when (base.host) {
            "vault.bitwarden.com", "api.bitwarden.com", "identity.bitwarden.com" -> "com"
            "vault.bitwarden.eu", "api.bitwarden.eu", "identity.bitwarden.eu" -> "eu"
            else -> null
        }
        if (officialRegion != null && base.scheme == "https" && base.port == 443 && base.encodedPath == "/") {
            return "https://notifications.bitwarden.$officialRegion/hub".toHttpUrlOrNull()
        }
        return base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/notifications/hub").build()
    }

    enum class Message { SYNC, HEARTBEAT, IGNORE, CLOSE }

    fun decode(body: ByteArray, userId: String, deviceId: String?): Message {
        val packet = Reader(body).readDocument() as? List<*> ?: error("Invalid hub message")
        return when (packet.firstOrNull()) {
            6L -> Message.HEARTBEAT
            7L -> Message.CLOSE
            1L -> {
                require(packet.size in 5..6 && packet[1] is Map<*, *>) { "Invalid invocation" }
                if (packet[3] == "Heartbeat") return Message.HEARTBEAT
                if (packet[3] != "ReceiveMessage") return Message.IGNORE
                val arguments = packet[4] as? List<*> ?: return Message.IGNORE
                if (arguments.size != 1) return Message.IGNORE
                val envelope = arguments[0] as? Map<*, *> ?: return Message.IGNORE
                val type = envelope.field("Type") as? Long ?: return Message.IGNORE
                if (type !in 0L..14L) return Message.IGNORE
                val context = envelope.field("ContextId") as? String
                if (!deviceId.isNullOrBlank() && context == deviceId) return Message.IGNORE
                val payload = envelope.field("Payload") as? Map<*, *> ?: return Message.IGNORE
                val owner = payload.field("UserId")
                if (owner != null && owner != userId) return Message.IGNORE
                // Organization events may omit UserId on an already authenticated account socket.
                if (owner == null && (payload.field("OrganizationId") as? String).isNullOrBlank()) return Message.IGNORE
                Message.SYNC
            }
            else -> Message.IGNORE
        }
    }

    private fun Map<*, *>.field(name: String): Any? {
        val matching = entries.filter { (it.key as? String)?.equals(name, ignoreCase = true) == true }
        require(matching.size <= 1) { "Ambiguous notification field" }
        return matching.firstOrNull()?.value
    }

    /** SignalR length prefixes may span websocket frames; both frame and object budgets are bounded. */
    class Frames {
        private var pending = byteArrayOf()
        fun accept(bytes: ByteArray, consume: (ByteArray) -> Unit) {
            require(bytes.size <= MAX_FRAME_BYTES && pending.size + bytes.size <= MAX_FRAME_BYTES + 5)
            pending += bytes
            var offset = 0
            while (offset < pending.size) {
                var length = 0L
                var prefix = 0
                var complete = false
                while (prefix < 5 && offset + prefix < pending.size) {
                    val octet = pending[offset + prefix].toInt() and 255
                    length = length or ((octet and 127).toLong() shl (prefix * 7))
                    prefix++
                    if (octet and 128 == 0) { complete = true; break }
                }
                require(prefix < 5 || complete) { "Invalid hub length" }
                require(length <= MAX_FRAME_BYTES) { "Hub message too large" }
                if (!complete || pending.size - offset - prefix < length) break
                require(length > 0)
                consume(pending.copyOfRange(offset + prefix, offset + prefix + length.toInt()))
                offset += prefix + length.toInt()
            }
            pending = pending.copyOfRange(offset, pending.size)
        }
    }

    /** Restricted MessagePack reader; dates and opaque binary values are consumed, never trusted. */
    private class Reader(private val bytes: ByteArray) {
        private var at = 0
        private var nodes = 0
        fun readDocument(): Any? {
            require(bytes.size <= MAX_FRAME_BYTES)
            val result = value(0)
            require(at == bytes.size) { "Trailing MessagePack bytes" }
            return result
        }
        private fun byte(): Int { require(at < bytes.size); return bytes[at++].toInt() and 255 }
        private fun uint(count: Int): Long {
            var result = 0L
            repeat(count) { result = (result shl 8) or byte().toLong() }
            return result
        }
        private fun count(size: Long): Int { require(size in 0..MAX_FRAME_BYTES.toLong()); return size.toInt() }
        private fun skip(size: Int) { require(size <= bytes.size - at); at += size }
        private fun string(size: Int): String {
            require(size <= 16 * 1024 && size <= bytes.size - at)
            val result = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, at, size)).toString()
            at += size
            return result
        }
        private fun array(size: Int, depth: Int): List<Any?> { require(size <= 1024); return List(size) { value(depth + 1) } }
        private fun map(size: Int, depth: Int): Map<String, Any?> {
            require(size <= 256)
            val result = LinkedHashMap<String, Any?>()
            repeat(size) {
                val key = value(depth + 1) as? String ?: error("Invalid map key")
                require(!result.containsKey(key)) { "Duplicate map key" }
                result[key] = value(depth + 1)
            }
            return result
        }
        private fun value(depth: Int): Any? {
            require(depth <= 12 && ++nodes <= 4096) { "MessagePack budget exceeded" }
            val tag = byte()
            return when (tag) {
                in 0..127 -> tag.toLong()
                in 128..143 -> map(tag and 15, depth)
                in 144..159 -> array(tag and 15, depth)
                in 160..191 -> string(tag and 31)
                192 -> null
                194 -> false
                195 -> true
                196, 197, 198 -> { skip(count(uint(1 shl (tag - 196)))); null }
                199, 200, 201 -> { val size = count(uint(1 shl (tag - 199))); byte(); skip(size); null }
                202, 203 -> { skip(if (tag == 202) 4 else 8); null }
                204, 205, 206 -> uint(1 shl (tag - 204))
                207 -> uint(8).also { require(it >= 0) }
                208 -> byte().toByte().toLong()
                209 -> uint(2).toShort().toLong()
                210 -> uint(4).toInt().toLong()
                211 -> uint(8)
                in 212..216 -> { byte(); skip(1 shl (tag - 212)); null }
                217, 218, 219 -> string(count(uint(1 shl (tag - 217))))
                220, 221 -> array(count(uint(if (tag == 220) 2 else 4)), depth)
                222, 223 -> map(count(uint(if (tag == 222) 2 else 4)), depth)
                in 224..255 -> tag.toByte().toLong()
                else -> error("Unsupported MessagePack tag")
            }
        }
    }
}
