package takagi.ru.monica.lansync

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class NearbyHotspotPresenceTest {
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue(message, condition())
    }

    @Test fun gateway_probe_discovers_both_roles_ignores_unpaired_devices_and_expires() {
        val hostPort = DatagramSocket(0).use { it.localPort }
        val clientPort = DatagramSocket(0).use { it.localPort }
        val host = UdpDeviceSyncPresence({ emptyList() }, InetSocketAddress("127.0.0.1", hostPort), 500, 1_500)
        val client = UdpDeviceSyncPresence({ listOf(InetSocketAddress("127.0.0.1", hostPort)) },
            InetSocketAddress("127.0.0.1", clientPort), 500, 1_500)
        try {
            host.start("host", setOf("client"))
            DatagramSocket().use { stranger ->
                for (text in listOf("monica-presence/1 hello stranger", "wrong-protocol hello client", "monica-presence/1 unknown client")) {
                    val bytes = text.toByteArray()
                    stranger.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", hostPort)))
                }
            }
            Thread.sleep(600)
            assertTrue(host.online.value.isEmpty())
            client.start("client", setOf("host"))
            await("Client probing only the gateway must bring both peers online") {
                host.online.value == setOf("client") && client.online.value == setOf("host")
            }
            assertEquals(setOf("127.0.0.1"), host.addresses)
            assertEquals(setOf("127.0.0.1"), client.addresses)
            host.stop()
            await("Missing announcements must expire even if goodbye is lost") { client.online.value.isEmpty() }
            assertTrue(host.addresses.isEmpty()); assertTrue(client.addresses.isEmpty())
            host.start("host", setOf("client"))
            await("Reopening restores both peers") { host.online.value.isNotEmpty() && client.online.value.isNotEmpty() }
        } finally { host.stop(); client.stop() }
        assertFalse(host.available); assertFalse(client.available)
        assertTrue(host.online.value.isEmpty()); assertTrue(client.online.value.isEmpty())
    }

    @Test fun local_targets_reject_public_addresses_and_hostnames() {
        for (value in listOf("8.8.8.8", "255.255.255.255", "example.test", "::1", "999.1.1.1", "127.1")) {
            assertNull(value, DeviceSyncLocalNetwork.parseLocal(value))
        }
        for (value in listOf("192.168.43.1", "192.168.43.255", "172.20.10.1", "10.0.2.2", "127.0.0.1")) {
            assertNotNull(value, DeviceSyncLocalNetwork.parseLocal(value))
        }
    }
}
