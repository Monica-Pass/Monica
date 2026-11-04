package takagi.ru.monica.bitwarden.sync

import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.bitwarden.BitwardenVault

class BitwardenNotificationProtocolTest {
    @Test fun officialAndSelfHostedEndpointsKeepRegionAndSubpathWithoutUsingEvents() {
        assertEquals("https://notifications.bitwarden.com/hub", BitwardenNotificationProtocol.hubUrl("https://vault.bitwarden.com")?.toString())
        assertEquals("https://notifications.bitwarden.eu/hub", BitwardenNotificationProtocol.hubUrl("https://vault.bitwarden.eu/")?.toString())
        assertEquals("https://vault.example.invalid/bw/notifications/hub", BitwardenNotificationProtocol.hubUrl("https://vault.example.invalid/bw/")?.toString())
        assertEquals("http://192.168.1.2:8080/bw/notifications/hub", BitwardenNotificationProtocol.hubUrl("http://192.168.1.2:8080/bw")?.toString())
        assertNull(BitwardenNotificationProtocol.hubUrl("https://user:secret@example.invalid"))
        assertNull(BitwardenNotificationProtocol.hubUrl("https://example.invalid/?access_token=secret"))
    }

    @Test fun accountTypeAndDeviceAreCheckedBeforeScheduling() {
        fun decode(user: String?, type: Int = 1, device: String? = null, org: String? = null): BitwardenNotificationProtocol.Message {
            var result = BitwardenNotificationProtocol.Message.IGNORE
            BitwardenNotificationProtocol.Frames().accept(BitwardenNotificationTestFrames.event(user, type, device, org)) {
                result = BitwardenNotificationProtocol.decode(it, "first", "this-device")
            }
            return result
        }
        assertEquals(BitwardenNotificationProtocol.Message.SYNC, decode("first"))
        assertEquals(BitwardenNotificationProtocol.Message.SYNC, decode("first", 14))
        assertEquals(BitwardenNotificationProtocol.Message.SYNC, decode("first", 11))
        assertEquals(BitwardenNotificationProtocol.Message.IGNORE, decode("second"))
        assertEquals(BitwardenNotificationProtocol.Message.IGNORE, decode("first", 120))
        assertEquals(BitwardenNotificationProtocol.Message.IGNORE, decode("first", device = "this-device"))
        assertEquals(BitwardenNotificationProtocol.Message.IGNORE, decode(null))
        assertEquals(BitwardenNotificationProtocol.Message.SYNC, decode(null, org = "organization"))
    }

    @Test fun fragmentedAndBatchedFramesAreReadExactlyOnce() {
        val parser = BitwardenNotificationProtocol.Frames()
        val events = BitwardenNotificationTestFrames.event("first") + BitwardenNotificationProtocol.ping
        val messages = mutableListOf<BitwardenNotificationProtocol.Message>()
        events.forEach { b -> parser.accept(byteArrayOf(b)) { messages += BitwardenNotificationProtocol.decode(it, "first", null) } }
        assertEquals(listOf(BitwardenNotificationProtocol.Message.SYNC, BitwardenNotificationProtocol.Message.HEARTBEAT), messages)
        parser.accept(events) { messages += BitwardenNotificationProtocol.decode(it, "first", null) }
        assertEquals(4, messages.size)
    }

    @Test fun malformedLengthsDeepObjectsAndDuplicateIdentityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BitwardenNotificationProtocol.Frames().accept(ByteArray(5) { 0xff.toByte() }) {} }
        assertThrows(IllegalArgumentException::class.java) { BitwardenNotificationProtocol.Frames().accept(ByteArray(65 * 1024)) {} }
        assertThrows(IllegalArgumentException::class.java) { BitwardenNotificationProtocol.decode(ByteArray(14) { 0x91.toByte() } + byteArrayOf(0xc0.toByte()), "first", null) }
        val duplicate = BitwardenNotificationTestFrames.document(listOf(1, emptyMap<String, Any>(), null, "ReceiveMessage",
            listOf(mapOf("Type" to 1, "Payload" to linkedMapOf("UserId" to "first", "userId" to "second")))))
        assertThrows(IllegalArgumentException::class.java) { BitwardenNotificationProtocol.decode(duplicate, "first", null) }
    }

    @Test fun tokenRotationAndSyncTimestampsDoNotChangeConnectionIdentityButTlsDoes() {
        val vault = BitwardenVault(id = 7, email = "a@example.invalid", userId = "first", serverUrl = "https://example.invalid")
        assertEquals(notificationAccountFingerprint(vault), notificationAccountFingerprint(vault.copy(lastSyncAt = 99, encryptedAccessToken = "opaque", updatedAt = 99)))
        assertNotEquals(notificationAccountFingerprint(vault), notificationAccountFingerprint(vault.copy(tlsCaCertificatePem = "custom-ca")))
        assertNotEquals(notificationAccountFingerprint(vault), notificationAccountFingerprint(vault.copy(userId = "second")))
        assertNotEquals(notificationAccountFingerprint(vault), notificationAccountFingerprint(vault.copy(serverUrl = "https://other.invalid")))
    }
}
