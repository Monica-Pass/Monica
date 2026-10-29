package takagi.ru.monica.passkey.hardware

import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Short APDU command chaining works even when the phone/key lacks extended APDUs. */
class NfcApdu(private val maxFrame: Int, private val transceive: (ByteArray) -> ByteArray) {
    suspend fun command(command: ByteArray): ByteArray {
        require(command.isNotEmpty() && command.size <= 65535 && maxFrame >= 12)
        val context = currentCoroutineContext()
        val chunkSize = minOf(255, maxFrame - 6)
        var offset = 0
        var response: ByteArray
        do {
            context.ensureActive()
            val count = minOf(chunkSize, command.size - offset)
            val more = offset + count < command.size
            val apdu = byteArrayOf(if (more) 0x90.toByte() else 0x80.toByte(), 0x10, 0, 0, count.toByte()) +
                command.copyOfRange(offset, offset + count) + if (more) byteArrayOf() else byteArrayOf(0)
            response = transceive(apdu)
            if (more) require(response.contentEquals(byteArrayOf(0x90.toByte(), 0))) { "NFC command chaining refused" }
            offset += count
        } while (offset < command.size)
        val out = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 60_000_000_000L
        var rounds = 0
        while (true) {
            context.ensureActive()
            require(response.size >= 2 && ++rounds <= 512 && System.nanoTime() < deadline) { "Invalid NFC response" }
            val sw = ((response[response.size - 2].toInt() and 255) shl 8) or (response.last().toInt() and 255)
            if (sw == 0x9100) {
                // CTAP NFC keepalive: use NFCCTAP_GETRESPONSE, not ISO GET RESPONSE.
                require(response.size == 3 && response[0].toInt() in 1..2) { "Invalid NFC keepalive" }
                kotlinx.coroutines.delay(100)
                response = transceive(byteArrayOf(0x80.toByte(), 0x11, 0, 0, 0))
                continue
            }
            out.write(response, 0, response.size - 2)
            require(out.size() <= 65535) { "NFC response too large" }
            if (sw == 0x9000) return out.toByteArray()
            if (sw and 0xff00 != 0x6100) throw IOException("NFC status ${sw.toString(16)}")
            response = transceive(byteArrayOf(0, 0xc0.toByte(), 0, 0, sw.toByte()))
        }
    }
}

class NfcTransport private constructor(private val isoDep: IsoDep) : FidoTransport {
    private val mutex = Mutex()
    private val apdu = NfcApdu(isoDep.maxTransceiveLength, isoDep::transceive)
    override val transportType = TransportType.NFC
    override val isConnected get() = runCatching { isoDep.isConnected }.getOrDefault(false)
    override fun reclaimConnection() { check(isConnected) { "NFC disconnected" } }
    override suspend fun sendCtapCommand(command: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock { try { apdu.command(command) } catch (e: Exception) { close(); throw e } }
    }
    override fun close() { runCatching { isoDep.close() } }
    companion object {
        suspend fun connect(tag: Tag): NfcTransport {
            var opened: NfcTransport? = null
            return try { withContext(Dispatchers.IO) { open(tag).also { opened = it } } }
            catch (e: Exception) { opened?.close(); throw e }
        }
        private fun open(tag: Tag): NfcTransport {
            val dep = IsoDep.get(tag) ?: throw IOException("Not a FIDO NFC key")
            try {
                dep.connect(); dep.timeout = 3000
                val response = dep.transceive(byteArrayOf(0, 0xa4.toByte(), 4, 0, 8, 0xa0.toByte(), 0, 0, 6, 0x47, 0x2f, 0, 1, 0))
                require(response.size >= 2 && response.takeLast(2) == listOf(0x90.toByte(), 0.toByte())) { "FIDO applet unavailable" }
                return NfcTransport(dep)
            } catch (e: Exception) { dep.close(); throw e }
        }
    }
}
