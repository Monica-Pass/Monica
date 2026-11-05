package takagi.ru.monica.lansync

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import takagi.ru.monica.security.SecurityManager
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
class DeviceSyncModeTest {
    @Test fun protected_preferences_keep_direction_and_existing_pairing_on_reload() {
        val context = RuntimeEnvironment.getApplication()
        val store = DeviceSyncPreferenceStore(context, SecurityManager(context))
        val original = DeviceSyncPreferences(deviceId = "phone")
        original.peers += SavedSyncPeer("desktop", "Computer", "pair", "A".repeat(43))
        for (mode in DeviceSyncMode.entries) {
            original.mode = mode
            original.databaseId = 42L
            original.enabled = false
            store.save(original)
            val reloaded = store.load()
            assertEquals(mode, reloaded.mode)
            assertEquals(42L, reloaded.databaseId)
            assertFalse(reloaded.enabled)
            assertEquals(original.replicaKey, reloaded.replicaKey)
            assertEquals(original.peers.single().trustedKey, reloaded.peers.single().trustedKey)
        }
    }

    @Test fun legacy_preferences_default_to_bidirectional_and_unknown_modes_are_rejected() {
        val context = RuntimeEnvironment.getApplication()
        val security = SecurityManager(context)
        val store = DeviceSyncPreferenceStore(context, security)
        store.save(DeviceSyncPreferences(deviceId = "phone"))
        val preferences = context.getSharedPreferences("monica-device-sync", Context.MODE_PRIVATE)
        val json = JSONObject(security.decryptData(requireNotNull(preferences.getString("protected", null))))
        json.remove("sync_mode")
        preferences.edit().putString("protected", security.encryptData(json.toString())).commit()
        assertEquals(DeviceSyncMode.BIDIRECTIONAL, store.load().mode)
        assertNull(store.load().databaseId)
        json.put("sync_mode", "unknown-mode")
        preferences.edit().putString("protected", security.encryptData(json.toString())).commit()
        assertThrows(IllegalArgumentException::class.java) { store.load() }
    }

    @Test fun receive_only_native_exchange_needs_no_outgoing_vault_and_sends_no_content_key() {
        DeviceVaultProviderTest.loadNative()
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "direction-${UUID.randomUUID()}")
        val outgoing = DevicePairingSnapshot.prepare(File(root, "out"), "sender")
        val senderIn = File(root, "sender-in")
        val receiverIn = File(root, "receiver-in")
        fun device(id: String, mode: DeviceSyncMode) = JSONObject().put("device_id", id).put("name", id)
            .put("content_type", "vault").put("vault_content_version", 2).put("sync_mode", mode.wireName)
        val sender = RustLanSync.request(JSONObject().put("operation", "start").put("protocol", 2)
            .put("listen_address", "127.0.0.1").put("vault_id", "monica-passwords-v1")
            .put("outgoing_directory", outgoing.directory.absolutePath).put("incoming_directory", senderIn.absolutePath)
            .put("content_key", outgoing.contentKey).put("device", device("sender", DeviceSyncMode.SEND_ONLY)))
        val handles = mutableListOf(sender.getLong("handle"))
        try {
            val receiver = RustLanSync.request(JSONObject().put("operation", "connect").put("protocol", 2)
                .put("invitation", "monica-lan:2:127.0.0.1:${sender.getInt("port")}:${sender.getString("code")}")
                .put("vault_id", "monica-passwords-v1").put("incoming_directory", receiverIn.absolutePath)
                .put("device", device("receiver", DeviceSyncMode.RECEIVE_ONLY)))
            handles += receiver.getLong("handle")
            val acknowledged = mutableSetOf<Long>()
            val deadline = System.nanoTime() + 10_000_000_000L
            var final = emptyList<JSONObject>()
            while (System.nanoTime() < deadline) {
                final = handles.map { RustLanSync.request(JSONObject().put("operation", "progress").put("handle", it)) }
                final.forEachIndexed { index, progress ->
                    assertNotEquals(progress.optString("error"), "failed", progress.getString("phase"))
                    if (progress.getString("phase") == "received" && acknowledged.add(handles[index]))
                        RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", handles[index]).put("success", true))
                }
                if (final.all { it.getString("phase") == "completed" }) break
                Thread.sleep(10)
            }
            assertTrue("Native direction exchange timed out", final.all { it.getString("phase") == "completed" })
            assertTrue(requireNotNull(senderIn.listFiles()).isEmpty())
            assertArrayEquals(File(outgoing.directory, "changes.mdbxsync").readBytes(), File(receiverIn, "changes.mdbxsync").readBytes())
            val senderPeer = final[0].getJSONObject("peer")
            assertFalse(senderPeer.getBoolean("receive_content"))
            assertEquals("", senderPeer.getJSONObject("pairing").getString("content_key"))
            assertFalse(final[1].getJSONObject("peer").getBoolean("send_content"))
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
        fail("Native direction session did not release")
    }
}
