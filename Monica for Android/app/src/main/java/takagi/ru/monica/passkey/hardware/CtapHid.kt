package takagi.ru.monica.passkey.hardware

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom

/** Packet I/O is separate from framing so the same production codec can be tested without USB. */
interface HidPackets {
    val inputSize: Int
    val outputSize: Int
    fun write(packet: ByteArray)
    fun read(): ByteArray?
    fun close()
}

class CtapHid(private val packets: HidPackets, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var channel = -1
    private val maxInput = limit(packets.inputSize)
    private val maxOutput = limit(packets.outputSize)

    suspend fun initialize() {
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val response = exchange(-1, 6, nonce)
        require(response.size == 17 && response.copyOfRange(0, 8).contentEquals(nonce)) { "Invalid HID INIT" }
        // The allocated CID follows the nonce, rather than the frame's CID.
        channel = ((response[8].toInt() and 255) shl 24) or ((response[9].toInt() and 255) shl 16) or
            ((response[10].toInt() and 255) shl 8) or (response[11].toInt() and 255)
        require(channel != 0 && channel != -1 && response[16].toInt() and 4 != 0) { "FIDO2 HID unavailable" }
    }

    suspend fun command(data: ByteArray): ByteArray = exchange(channel, 0x10, data)

    private suspend fun exchange(cid: Int, command: Int, data: ByteArray): ByteArray {
        require(data.size <= maxOutput) { "HID request too large" }
        val context = currentCoroutineContext()
        fun send(header: Int, offset: Int, first: Boolean): Int {
            context.ensureActive()
            val frame = ByteArray(packets.outputSize)
            putChannel(frame, cid)
            frame[4] = header.toByte()
            val start = if (first) 7 else 5
            if (first) { frame[5] = (data.size shr 8).toByte(); frame[6] = data.size.toByte() }
            val length = minOf(data.size - offset, frame.size - start)
            data.copyInto(frame, start, offset, offset + length)
            packets.write(frame)
            return offset + length
        }
        var offset = send(command or 128, 0, true)
        var seq = 0
        while (offset < data.size) offset = send(seq++, offset, false)
        val deadline = now() + 60_000
        val out = ByteArrayOutputStream()
        var length: Int? = null
        seq = 0
        try {
            while (now() < deadline) {
                context.ensureActive()
                val frame = packets.read() ?: continue
                require(frame.size == packets.inputSize) { "Short HID frame" }
                if (channel(frame) != cid) continue
                val header = frame[4].toInt() and 255
                if (header == 0xbb) {
                    require(frame[5] == 0.toByte() && frame[6] == 1.toByte() && frame[7].toInt() in 1..2) { "Invalid keepalive" }
                    continue
                }
                if (header == 0xbf) throw IOException("HID error ${frame[7].toInt() and 255}")
                if (length == null) {
                    require(header == (command or 128)) { "Unexpected HID command" }
                    length = ((frame[5].toInt() and 255) shl 8) or (frame[6].toInt() and 255)
                    require(length <= maxInput) { "HID response too large" }
                    out.write(frame, 7, minOf(length, frame.size - 7))
                } else {
                    require(seq < 128 && header == seq++) { "Invalid HID sequence" }
                    out.write(frame, 5, minOf(length - out.size(), frame.size - 5))
                }
                if (out.size() == length) return out.toByteArray()
            }
            throw IOException("Security key response timed out")
        } catch (e: Exception) {
            // A cancelled/timed out transaction must never contaminate the next request.
            runCatching {
                val cancel = ByteArray(packets.outputSize)
                putChannel(cancel, cid); cancel[4] = 0x91.toByte(); packets.write(cancel)
            }
            packets.close()
            throw e
        }
    }

    companion object {
        fun limit(size: Int): Int {
            require(size in 8..1024) { "Unsupported HID report size" }
            return minOf(size - 7 + 128 * (size - 5), 65535)
        }
        fun putChannel(frame: ByteArray, cid: Int) {
            for (i in 0..3) frame[i] = (cid ushr (24 - i * 8)).toByte()
        }
        fun channel(frame: ByteArray): Int = ((frame[0].toInt() and 255) shl 24) or
            ((frame[1].toInt() and 255) shl 16) or ((frame[2].toInt() and 255) shl 8) or (frame[3].toInt() and 255)
    }
}
