// Adapted from Authnkey, Copyright (c) 2025 Michel Le Bihan (MIT).
// See docs/licenses/Authnkey-MIT.txt.
package takagi.ru.monica.passkey.hardware

/**
 * Common interface for FIDO transport (NFC or USB)
 */
interface FidoTransport {
    val transportType: TransportType
    val isConnected: Boolean

    /**
     * Verify that the transport connection is still active and usable.
     * Throws [AuthnkeyError.NotConnected] if the connection has been lost
     * (e.g. another app claimed the USB interface, or an NFC tag moved out of range).
     */
    @Throws(AuthnkeyError.NotConnected::class)
    fun reclaimConnection()

    @Throws(Exception::class)
    suspend fun sendCtapCommand(command: ByteArray): ByteArray

    fun close()
}
