package takagi.ru.monica.lansync

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises the packaged Android JNI over a real encrypted loopback connection. */
class NearbySyncNativeInstrumentedTest {
    @Test fun receiveOnlyDoesNotSendVaultArtifactsOrContentKey() = verifyReceiveOnly(receiverHosts = false)

    @Test fun receiveOnlyListenerDoesNotRequireOrSendVaultArtifactsOrContentKey() = verifyReceiveOnly(receiverHosts = true)

    @Test fun wifiBoundDiscoveryAndReceiveOnlyTransferWhenSenderHosts() = verifyReceiveOnly(receiverHosts = false, wifi = true)

    @Test fun wifiBoundDiscoveryAndReceiveOnlyTransferWhenReceiverHosts() = verifyReceiveOnly(receiverHosts = true, wifi = true)

    @Test fun missingWifiNetworkStopsDiscoveryWithoutUsingAnotherNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bindings = DeviceSyncLocalNetwork.withNetworks(context, JSONObject()).getJSONObject("network_handles")
        org.junit.Assume.assumeTrue("Needs a Wi-Fi interface", bindings.keys().hasNext())
        val ip = bindings.keys().next()
        val failure = runCatching { RustLanSync.request(JSONObject().put("operation", "discover")
            .put("targets", org.json.JSONArray(listOf(ip))).put("multicast", false).put("broadcast", false)
            .put("network_handles", JSONObject().put(ip, 1))) }.exceptionOrNull()
        assertTrue(failure is LanSyncException)
        assertEquals("discovery_unavailable", (failure as LanSyncException).code)
    }

    private fun verifyReceiveOnly(receiverHosts: Boolean, wifi: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bindings = DeviceSyncLocalNetwork.withNetworks(context, JSONObject()).getJSONObject("network_handles")
        if (wifi) org.junit.Assume.assumeTrue("Needs a Wi-Fi interface", bindings.keys().hasNext())
        val address = if (wifi) bindings.keys().next() else "127.0.0.1"
        val root = File(context.cacheDir, "nearby-native-test-${UUID.randomUUID()}")
        val outgoing = DevicePairingSnapshot.prepare(File(root, "out"), "sender")
        val senderIn = File(root, "sender-in")
        val receiverIn = File(root, "receiver-in")
        val handles = mutableListOf<Long>()
        fun device(id: String, mode: String) = JSONObject().put("device_id", id).put("name", id)
            .put("content_type", "vault").put("vault_content_version", 2).put("sync_mode", mode)
        try {
            val senderRequest = JSONObject().put("protocol", 2)
                .put("listen_address", address).put("vault_id", "monica-passwords-v1")
                .put("outgoing_directory", outgoing.directory.absolutePath).put("incoming_directory", senderIn.absolutePath)
                .put("content_key", outgoing.contentKey).put("device", device("sender", "send_only"))
            val receiverRequest = JSONObject().put("protocol", 2).put("listen_address", address)
                .put("vault_id", "monica-passwords-v1").put("incoming_directory", receiverIn.absolutePath)
                .put("device", device("receiver", "receive_only"))
            if (wifi) { senderRequest.put("network_handles", bindings); receiverRequest.put("network_handles", bindings) }
            val listener = RustLanSync.request((if (receiverHosts) receiverRequest else senderRequest).put("operation", "start"))
            handles += listener.getLong("handle")
            if (wifi) {
                val found = RustLanSync.request(JSONObject().put("operation", "discover").put("multicast", false).put("broadcast", false)
                    .put("targets", org.json.JSONArray(listOf(address))).put("network_handles", bindings)).getJSONArray("devices")
                assertTrue("Wi-Fi-bound UDP must discover the active listener", (0 until found.length()).any {
                    found.getJSONObject(it).optString("device_id") == if (receiverHosts) "receiver" else "sender"
                })
            }
            val connector = RustLanSync.request((if (receiverHosts) senderRequest else receiverRequest).put("operation", "connect")
                .put("invitation", "monica-lan:2:$address:${listener.getInt("port")}:${listener.getString("code")}"))
            handles += connector.getLong("handle")
            val receiverIndex = if (receiverHosts) 0 else 1
            val senderIndex = 1 - receiverIndex
            val acknowledged = mutableSetOf<Long>()
            val deadline = System.nanoTime() + 30_000_000_000L
            var final = emptyList<JSONObject>()
            while (System.nanoTime() < deadline) {
                final = handles.map { RustLanSync.request(JSONObject().put("operation", "progress").put("handle", it)) }
                final.forEachIndexed { index, progress ->
                    assertNotEquals(progress.optString("error"), "failed", progress.getString("phase"))
                    if (progress.getString("phase") == "received" && acknowledged.add(handles[index])) {
                        // Verify the actual received ciphertext before acknowledging its receipt.
                        if (index == receiverIndex) assertArrayEquals(File(outgoing.directory, "changes.mdbxsync").readBytes(),
                            File(receiverIn, "changes.mdbxsync").readBytes())
                        RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", handles[index]).put("success", true))
                    }
                }
                if (final.all { it.getString("phase") == "completed" }) break
                Thread.sleep(10)
            }
            assertTrue("Packaged JNI transfer timed out", final.all { it.getString("phase") == "completed" })
            assertTrue(requireNotNull(senderIn.listFiles()).isEmpty())
            val senderPeer = final[senderIndex].getJSONObject("peer")
            assertFalse(senderPeer.getBoolean("receive_content"))
            assertEquals("", senderPeer.getJSONObject("pairing").getString("content_key"))
            assertFalse(final[receiverIndex].getJSONObject("peer").getBoolean("send_content"))
        } finally {
            handles.forEach(::close)
            root.deleteRecursively()
        }
    }

    private fun close(handle: Long) {
        RustLanSync.request(JSONObject().put("operation", "cancel").put("handle", handle))
        repeat(200) {
            if (runCatching { RustLanSync.request(JSONObject().put("operation", "release").put("handle", handle)) }.isSuccess) return
            Thread.sleep(10)
        }
        fail("Packaged JNI session did not release")
    }
}
