package takagi.ru.monica.lansync

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NearbyHotspotInvitationTest {
    private val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
    private val pairing = "monica-lan:2:192.168.43.1,10.0.0.1:54321:$key"
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    @Test fun roundTripPreservesWifiCredentialsAndTheAuthenticatedInvitation() {
        val created = requireNotNull(NearbyHotspotInvitation.create("Monica 热点", "a secure passphrase", pairing))
        val decoded = requireNotNull(NearbyHotspotInvitation.parse(created.link))
        assertEquals("Monica 热点", decoded.ssid)
        assertEquals("a secure passphrase", decoded.passphrase)
        assertEquals(pairing, decoded.pairing)
        assertFalse(created.toString().contains(created.ssid))
        assertFalse(created.toString().contains(created.passphrase))
        assertFalse(created.toString().contains(key))
        assertFalse(created.link.contains(created.passphrase))
    }

    @Test fun rejectsOversizedOrMalformedWifiNamesAndPasswords() {
        for (ssid in listOf("", "x".repeat(33), "热".repeat(11), "name\u0000suffix", "name\nother")) {
            assertNull(NearbyHotspotInvitation.create(ssid, "password", pairing))
        }
        for (password in listOf("", "1234567", "x".repeat(64), "密码密码", "password\n")) {
            assertNull(NearbyHotspotInvitation.create("test", password, pairing))
        }
        assertNotNull(NearbyHotspotInvitation.create("热".repeat(10), "x".repeat(63), pairing))
    }

    @Test fun rejectsUntrustedProtocolEndpointsAndKeySubstitution() {
        for (link in listOf(pairing.replace("monica-lan:2", "monica-lan:1"),
            pairing.replace("192.168.43.1,10.0.0.1", "8.8.8.8"),
            pairing.replace("192.168.43.1,10.0.0.1", "example.com"),
            pairing.replace("192.168.43.1,10.0.0.1", "192.168.43.1,192.168.43.1"),
            pairing.replace(":54321:", ":0:"), pairing.dropLast(1), "https://example.com")) {
            assertNull(NearbyHotspotInvitation.create("test", "password", link))
        }
    }

    @Test fun rejectsNoncanonicalEncodingInvalidUtf8AndExtraFields() {
        val valid = requireNotNull(NearbyHotspotInvitation.create("test", "password", pairing)).link
        val parts = valid.split(':')
        assertNull(NearbyHotspotInvitation.parse(valid + ":extra"))
        assertNull(NearbyHotspotInvitation.parse(valid.replace("monica-hotspot:1", "monica-hotspot:2")))
        assertNull(NearbyHotspotInvitation.parse(parts.toMutableList().apply { this[2] += "=" }.joinToString(":")))
        assertNull(NearbyHotspotInvitation.parse(parts.toMutableList().apply { this[2] = encode(byteArrayOf(0xc3.toByte(), 0x28)) }.joinToString(":")))
        assertNull(NearbyHotspotInvitation.parse("x".repeat(NearbyHotspotInvitation.MAX_LENGTH + 1)))
        assertNull(NearbyHotspotInvitation.parse(parts.toMutableList().apply { this[3] = encode("short".toByteArray()) }.joinToString(":")))
    }
}
