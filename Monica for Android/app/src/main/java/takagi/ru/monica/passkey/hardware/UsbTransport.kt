package takagi.ru.monica.passkey.hardware

import android.hardware.usb.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

class UsbTransport private constructor(val deviceId: Int, private val io: UsbPackets) : FidoTransport {
    private val hid = CtapHid(io)
    private val mutex = Mutex()
    override val transportType = TransportType.USB
    override val isConnected get() = io.connected
    override fun reclaimConnection() { check(isConnected) { "USB disconnected" } }
    override suspend fun sendCtapCommand(command: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock { try { hid.command(command) } catch (e: Exception) { io.close(); throw e } }
    }
    override fun close() = io.close()

    private class UsbPackets(
        val connection: UsbDeviceConnection, val intf: UsbInterface,
        val input: UsbEndpoint, val output: UsbEndpoint
    ) : HidPackets {
        @Volatile var connected = true
        override val inputSize get() = input.maxPacketSize
        override val outputSize get() = output.maxPacketSize
        private fun transfer(endpoint: UsbEndpoint, buffer: ByteBuffer): Int {
            check(connected) { "USB disconnected" }
            val request = UsbRequest()
            try {
                check(request.initialize(connection, endpoint) && request.queue(buffer)) { "USB transfer failed" }
                val completed = connection.requestWait(750)
                if (completed !== request) throw IOException("Unexpected USB transfer")
                return buffer.position()
            } finally { request.cancel(); request.close() }
        }
        override fun write(packet: ByteArray) {
            check(transfer(output, ByteBuffer.wrap(packet)) == packet.size) { "Short USB write" }
        }
        override fun read(): ByteArray? {
            val buffer = ByteBuffer.allocate(inputSize)
            return try { buffer.array().copyOf(transfer(input, buffer)) } catch (_: TimeoutException) { null }
        }
        override fun close() {
            connected = false
            runCatching { connection.releaseInterface(intf) }
            runCatching { connection.close() }
        }
    }

    companion object {
        // A HID keyboard is not a FIDO interface. Inspect the HID report usage page (0xF1D0).
        fun candidates(manager: UsbManager): List<UsbDevice> = manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { index ->
                val intf = device.getInterface(index)
                intf.interfaceClass == UsbConstants.USB_CLASS_HID && intf.interfaceSubclass == 0 && intf.interfaceProtocol == 0 &&
                    endpoints(intf) != null
            }
        }
        private fun endpoints(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            val list = (0 until intf.endpointCount).map(intf::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
            val input = list.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
            val output = list.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
            return input to output
        }
        fun hasFidoUsage(report: ByteArray): Boolean {
            var pos = 0
            var page = -1
            while (pos < report.size) {
                val tag = report[pos++].toInt() and 255
                if (tag == 0xfe) { if (pos + 2 > report.size) return false; val length = report[pos].toInt() and 255; pos += 2 + length; continue }
                val size = when (tag and 3) { 3 -> 4; else -> tag and 3 }
                if (pos + size > report.size) return false
                var value = 0
                for (i in 0 until size) value = value or ((report[pos + i].toInt() and 255) shl (8 * i))
                if (tag and 0xfc == 0x04) page = value
                if (tag and 0xfc == 0xa0 && page == 0xf1d0 && value == 1) return true
                pos += size
            }
            return false
        }
        suspend fun connect(manager: UsbManager, device: UsbDevice): UsbTransport {
            var opened: UsbTransport? = null
            return try { withContext(Dispatchers.IO) { open(manager, device).also { opened = it } } }
            catch (e: Exception) { opened?.close(); throw e }
        }
        private suspend fun open(manager: UsbManager, device: UsbDevice): UsbTransport {
            check(manager.hasPermission(device)) { "USB permission required" }
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB key")
            try {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass != 3 || intf.interfaceSubclass != 0 || intf.interfaceProtocol != 0) continue
                    val endpoints = endpoints(intf) ?: continue
                    val report = ByteArray(4096)
                    val length = connection.controlTransfer(0x81, 6, 0x2200, intf.id, report, report.size, 1000)
                    if (length <= 0 || !hasFidoUsage(report.copyOf(length))) continue
                    CtapHid.limit(endpoints.first.maxPacketSize); CtapHid.limit(endpoints.second.maxPacketSize)
                    check(connection.claimInterface(intf, true)) { "USB key busy" }
                    val result = UsbTransport(device.deviceId, UsbPackets(connection, intf, endpoints.first, endpoints.second))
                    result.hid.initialize()
                    return result
                }
                throw IOException("No FIDO2 HID interface")
            } catch (e: Exception) { connection.close(); throw e }
        }
    }
}
