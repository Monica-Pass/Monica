package takagi.ru.monica.lansync

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.steam.importer.SteamMaFileParser
import java.io.File
import java.util.UUID

/** The real coordinator, provider, Android storage and JNI, with isolated synthetic stores. */
class NearbyAutomaticSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private inner class Fixture(id: String, peer: String, mode: DeviceSyncMode, realPresence: Boolean = false) {
        val root = File(context.cacheDir, "automatic-fixture-${UUID.randomUUID()}")
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val steam = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java).build()
        val security = SecurityManager(context)
        val preferenceName = "automatic-fixture-${UUID.randomUUID()}"
        val settings = DeviceSyncPreferences(deviceId = id, mode = mode,
            peers = mutableListOf(SavedSyncPeer(peer, "Synthetic peer", "automatic-fixture", "A".repeat(43), address = "127.0.0.1")))
        val store = DeviceSyncPreferenceStore(context, security, preferenceName).also { it.save(settings) }
        val provider = DevicePasswordSyncProvider(context, security, store, db, File(root, "replica"), steam)
        val coordinator = DeviceSyncCoordinator::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        val gate: Mutex
        init {
            coordinator.setAppVisible(true)
            if (!realPresence) set("presence", object : DeviceSyncPresence {
                override val online = kotlinx.coroutines.flow.MutableStateFlow(setOf(peer))
                override val available = true
                override fun start(deviceId: String, peers: Set<String>) = Unit
                override fun stop() = Unit
            })
            set("preferences", settings); set("store\$delegate", lazyOf(store)); set("dataProvider", lazyOf(provider))
            // The harness drives tick under the production mutex; no competing ticker.
            set("running", Job())
            gate = get("gate") as Mutex
            // Simulate approving deletion of synthetic fixture records while tick awaits the UI decision.
            (get("scope") as CoroutineScope).launch {
                coordinator.state.collect { state ->
                    state.deletionReview?.let { coordinator.confirmReceivedDeletions(it, true) }
                }
            }
            provider.onProgress = { progress ->
                DeviceSyncCoordinator::class.java.getDeclaredMethod("updateProgress", DeviceSyncProgress::class.java)
                    .apply { isAccessible = true }.invoke(coordinator, progress)
                onProgress?.invoke(progress)
            }
        }
        var onProgress: ((DeviceSyncProgress) -> Unit)? = null
        fun set(name: String, value: Any?) { DeviceSyncCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(coordinator, value) }
        fun get(name: String): Any? = DeviceSyncCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(coordinator)
        suspend fun tick() = gate.withLock {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                DeviceSyncCoordinator::class.java.getDeclaredMethod("tick", kotlin.coroutines.Continuation::class.java)
                    .apply { isAccessible = true }.invoke(coordinator, continuation)
            }
        }
        suspend fun manual() = gate.withLock {
            DeviceSyncCoordinator::class.java.getDeclaredMethod("beginManualSync").apply { isAccessible = true }.invoke(coordinator)
        }
        fun toggle(value: Boolean) {
            coordinator.setEnabled(value)
            assertEquals(value, coordinator.state.value.enabled)
            // Reopen the preference store, including while its encrypted snapshot is stale.
            assertEquals(value, DeviceSyncPreferenceStore(context, security, preferenceName).load().enabled)
        }
        suspend fun close() {
            (get("scope") as CoroutineScope).cancel()
            gate.withLock {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    DeviceSyncCoordinator::class.java.getDeclaredMethod("pause", Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                        .apply { isAccessible = true }.invoke(coordinator, false, continuation)
                }
            }
            provider.pause(); db.close(); steam.close(); context.deleteSharedPreferences(preferenceName); root.deleteRecursively()
        }
    }

    @Test fun real_presence_gates_transfers_and_unchanged_reopening_does_not_resend() = verifyRealPresence(receiverHosts = false)

    @Test fun receive_only_listener_syncs_a_fresh_database_and_reconnects_without_resending() = verifyRealPresence(receiverHosts = true)

    @Test fun hotspot_without_mdns_syncs_when_sender_hosts() = verifyRealPresence(receiverHosts = false, noMdns = true)

    @Test fun hotspot_without_mdns_syncs_when_receiver_hosts() = verifyRealPresence(receiverHosts = true, noMdns = true)

    @Test fun network_change_preserves_an_active_pairing_listener() = runBlocking {
        SessionManager.markUnlocked()
        val fixture = Fixture("pairing-network-fixture", "unused-peer", DeviceSyncMode.BIDIRECTIONAL)
        try {
            fixture.settings.peers.clear()
            fixture.toggle(false)
            fixture.coordinator.pair()
            withTimeout(15_000) {
                while (fixture.coordinator.state.value.phase != "waiting") {
                    assertNull(fixture.coordinator.state.value.error)
                    delay(20)
                }
            }
            val invitation = fixture.coordinator.state.value.invitations
            assertTrue(invitation.isNotEmpty())
            val transfer = (fixture.get("transfers") as Map<*, *>)["pairing"]!!
            val handle = transfer.javaClass.getDeclaredField("handle").apply { isAccessible = true }.getLong(transfer)
            fixture.set("networkSignature", "synthetic-previous-network")
            fixture.tick()
            assertSame("Network change must retain the listener behind the displayed QR", transfer,
                (fixture.get("transfers") as Map<*, *>)["pairing"])
            assertTrue(fixture.coordinator.state.value.pairing)
            assertEquals(invitation, fixture.coordinator.state.value.invitations)
            assertEquals("waiting", RustLanSync.request(JSONObject().put("operation", "progress").put("handle", handle)).getString("phase"))
            fixture.coordinator.cancelPairing()
            withTimeout(15_000) { while (fixture.coordinator.state.value.pairing) delay(20) }
            assertTrue(fixture.coordinator.state.value.invitations.isEmpty())
            assertTrue((fixture.get("transfers") as Map<*, *>).isEmpty())
        } finally { fixture.close(); SessionManager.markLocked() }
    }

    @Test fun manual_request_during_previous_receipts_sends_the_new_edit() = runBlocking {
        SessionManager.markUnlocked()
        val sender = Fixture("z-queued-sender", "a-queued-receiver", DeviceSyncMode.SEND_ONLY)
        val receiver = Fixture("a-queued-receiver", "z-queued-sender", DeviceSyncMode.RECEIVE_ONLY)
        try {
            val id = sender.db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                title = "Queued synthetic edit", website = "example.test", username = "fixture", password = sender.security.encryptData("first")))
            sender.tick(); receiver.tick()
            fun handle(fixture: Fixture): Long {
                val transfer = (fixture.get("transfers") as Map<*, *>).values.single()!!
                return transfer.javaClass.getDeclaredField("handle").apply { isAccessible = true }.getLong(transfer)
            }
            val handles = listOf(handle(sender), handle(receiver))
            withTimeout(15_000) {
                while (handles.any { RustLanSync.request(JSONObject().put("operation", "progress").put("handle", it)).getString("phase") != "received" }) delay(10)
            }
            // Both old snapshots are awaiting receipts. The next edit and manual request must survive their completion.
            val entry = sender.db.passwordEntryDao().getPasswordEntryById(id)!!
            sender.db.passwordEntryDao().updatePasswordEntry(entry.copy(password = sender.security.encryptData("queued")))
            sender.toggle(false); receiver.toggle(false)
            sender.manual(); receiver.manual()
            withTimeout(120_000) {
                while (receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().singleOrNull()?.let {
                    receiver.security.decryptDataIfMonicaCiphertext(it.password)
                } != "queued" || sender.coordinator.state.value.manualSync || receiver.coordinator.state.value.manualSync) {
                    sender.tick(); receiver.tick(); delay(25)
                    assertNull(sender.coordinator.state.value.error); assertNull(receiver.coordinator.state.value.error)
                }
            }
            assertFalse(sender.store.load().enabled); assertFalse(receiver.store.load().enabled)
            assertEquals(1, receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().size)
        } finally { sender.close(); receiver.close(); SessionManager.markLocked() }
    }

    private fun verifyRealPresence(receiverHosts: Boolean, noMdns: Boolean = false) = runBlocking {
        SessionManager.markUnlocked()
        val id = UUID.randomUUID().toString().replace("-", "")
        val senderId = "${if (receiverHosts) "a" else "z"}$id"
        val receiverId = "${if (receiverHosts) "z" else "a"}$id"
        val sender = Fixture(senderId, receiverId, DeviceSyncMode.SEND_ONLY, realPresence = true)
        val receiver = Fixture(receiverId, senderId, DeviceSyncMode.RECEIVE_ONLY, realPresence = true)
        if (noMdns) {
            fun absent() = object : DeviceSyncPresence {
                override val online = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
                override val available = false
                override fun start(deviceId: String, peers: Set<String>) = Unit
                override fun stop() = Unit
            }
            fun port() = java.net.DatagramSocket(0).use { it.localPort }
            val senderPort = port()
            val senderPresence = UdpDeviceSyncPresence({ emptyList() }, java.net.InetSocketAddress("127.0.0.1", senderPort), 1_000)
            val receiverPresence = UdpDeviceSyncPresence({ listOf(java.net.InetSocketAddress("127.0.0.1", senderPort)) },
                java.net.InetSocketAddress("127.0.0.1", port()), 1_000)
            sender.set("presence", ForegroundDeviceSyncPresence(context, absent(), senderPresence))
            receiver.set("presence", ForegroundDeviceSyncPresence(context, absent(), receiverPresence))
            // The AP has no gateway/known client IP. Learn the client's new address from its probe.
            for (fixture in listOf(sender, receiver)) {
                fixture.settings.peers.single().address = "192.168.199.253"
                fixture.set("discoverPeers", { request: JSONObject ->
                    assertTrue("Discovery must include the fresh UDP reply address", request.getJSONArray("targets").toString().contains("127.0.0.1"))
                    RustLanSync.request(request.put("multicast", false).put("broadcast", false)
                        .put("targets", org.json.JSONArray(listOf("127.0.0.1"))))
                })
            }
        }
        val connector = if (receiverHosts) sender else receiver
        suspend fun awaitState(message: String, allowConnectionRetry: Boolean = false, condition: () -> Boolean) {
            val deadline = System.nanoTime() + (if (noMdns) 25_000_000_000L else 90_000_000_000L)
            while (!condition() && System.nanoTime() < deadline) {
                sender.tick(); receiver.tick(); delay(50)
                for (state in listOf(sender.coordinator.state.value, receiver.coordinator.state.value)) {
                    if (allowConnectionRetry && state.manualSync &&
                        state.failureStage in setOf("connecting", "authenticating") &&
                        state.error in setOf("connection_failed", "connection_closed", "transfer_timeout")) continue
                    assertNull("$message: $state", state.error)
                }
            }
            assertTrue("$message: ${sender.coordinator.state.value} / ${receiver.coordinator.state.value}", condition())
        }
        try {
            val source = sender.db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                title = "Presence fixture", website = "example.test", username = "fixture", password = sender.security.encryptData("first")))
            assertTrue(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().isEmpty())
            repeat(5) { sender.tick(); delay(100) }
            assertEquals("waiting_for_app", sender.coordinator.state.value.phase)
            assertNull(sender.get("snapshot"))
            assertEquals(0L, sender.coordinator.state.value.lastAttemptAt)
            awaitState("Real NSD must allow initial automatic synchronization") {
                sender.settings.peers.single().lastSyncedAt > 0 && receiver.settings.peers.single().lastSyncedAt > 0
            }
            val senderSynced = sender.settings.peers.single().lastSyncedAt
            val receiverSynced = receiver.settings.peers.single().lastSyncedAt
            assertEquals("first", receiver.security.decryptDataIfMonicaCiphertext(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single().password))
            receiver.coordinator.setAppVisible(false)
            awaitState("Background peer must stop its listener and disappear") {
                sender.coordinator.state.value.phase == "waiting_for_app" && receiver.coordinator.state.value.phase == "background"
            }
            val discovery = sender.coordinator.state.value.lastDiscoveryAt
            repeat(5) { sender.tick(); receiver.tick(); delay(100) }
            assertEquals(discovery, sender.coordinator.state.value.lastDiscoveryAt)
            assertEquals(0, sender.coordinator.state.value.activeConnections)
            assertEquals(0, receiver.coordinator.state.value.activeConnections)
            receiver.coordinator.setAppVisible(true)
            awaitState("Unchanged reopened peer must be online without another transfer") {
                connector.coordinator.state.value.peers.single().status == "online"
            }
            repeat(3) { sender.tick(); receiver.tick() }
            assertEquals(senderSynced, sender.settings.peers.single().lastSyncedAt)
            assertEquals(receiverSynced, receiver.settings.peers.single().lastSyncedAt)
            val entry = sender.db.passwordEntryDao().getPasswordEntryById(source)!!
            sender.db.passwordEntryDao().updatePasswordEntry(entry.copy(password = sender.security.encryptData("second")))
            awaitState("A real edit after reopening must still synchronize") {
                receiver.settings.peers.single().lastSyncedAt > receiverSynced
            }
            assertEquals("second", receiver.security.decryptDataIfMonicaCiphertext(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single().password))
            sender.toggle(false); receiver.toggle(false)
            val beforeManual = receiver.settings.peers.single().lastSyncedAt
            val senderBeforeManual = sender.settings.peers.single().lastSyncedAt
            sender.db.passwordEntryDao().updatePasswordEntry(entry.copy(password = sender.security.encryptData("manual")))
            sender.manual(); receiver.manual()
            awaitState("Manual synchronization must also work in either connection role", allowConnectionRetry = true) {
                receiver.settings.peers.single().lastSyncedAt > beforeManual &&
                    sender.settings.peers.single().lastSyncedAt > senderBeforeManual
            }
            assertEquals("manual", receiver.security.decryptDataIfMonicaCiphertext(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single().password))
            assertEquals(1, sender.db.passwordEntryDao().getDeviceSyncLocalPasswords().size)
        } finally { sender.close(); receiver.close(); SessionManager.markLocked() }
    }

    @Test fun toggling_during_export_and_import_preserves_data_and_stops_later_automatic_rounds() = runBlocking {
        SessionManager.markUnlocked()
        val sender = Fixture("z-automatic-sender", "a-automatic-receiver", DeviceSyncMode.SEND_ONLY)
        val receiver = Fixture("a-automatic-receiver", "z-automatic-sender", DeviceSyncMode.RECEIVE_ONLY)
        val rich = NearbyVaultFixtureData(context)
        // Synthetic UI activity runs independently while export/import holds the coordinator gate.
        val activity = launch(Dispatchers.Default) {
            while (isActive) { SessionManager.refreshSession(); delay(1_000) }
        }
        try {
            repeat(610) { index -> sender.db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                title = "Bulk $index", website = "example.test", username = "fixture", password = sender.security.encryptData("synthetic-$index"))) }
            for (type in listOf(takagi.ru.monica.data.ItemType.NOTE, takagi.ru.monica.data.ItemType.TOTP, takagi.ru.monica.data.ItemType.BANK_CARD,
                takagi.ru.monica.data.ItemType.DOCUMENT, takagi.ru.monica.data.ItemType.BILLING_ADDRESS, takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT)) {
                sender.db.secureItemDao().insertItem(takagi.ru.monica.data.SecureItem(itemType = type, title = "Trash $type",
                    itemData = sender.security.encryptData("{\"content\":\"synthetic trash\"}"), isDeleted = true))
            }
            rich.seed(sender.db, sender.security)
            val keyPair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            for (count in listOf(0L, 7L)) sender.db.passkeyDao().insert(takagi.ru.monica.passkey.PasskeyPrivateKeyStore.protectPasskey(context,
                takagi.ru.monica.data.PasskeyEntry(credentialId = UUID.randomUUID().toString(), rpId = "example.test", rpName = "Counter $count",
                    userId = "Zml4dHVyZQ==", userName = "fixture", userDisplayName = "Fixture", signCount = count, backupEligible = true, backupState = false,
                    publicKey = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded), privateKeyAlias = java.util.Base64.getEncoder().encodeToString(keyPair.private.encoded))))
            val secret = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="
            SteamAccountRepository(sender.steam.steamAccountDao(), sender.security).upsertFromMaFile(SteamMaFileParser().parse(
                JSONObject().put("steamid", "76561198000000000").put("account_name", "Synthetic Steam")
                    .put("shared_secret", secret).put("identity_secret", secret).put("device_id", "android:fixture").toString()))
            var stoppedDuringExport = false
            sender.onProgress = { progress ->
                if (!stoppedDuringExport && progress.stage == "preparing") {
                    assertTrue(sender.gate.isLocked)
                    sender.toggle(false); sender.toggle(true); sender.toggle(false)
                    stoppedDuringExport = true
                }
            }
            sender.tick()
            assertTrue(stoppedDuringExport)
            assertEquals(0L, sender.coordinator.state.value.lastAttemptAt)
            assertNull(sender.coordinator.state.value.passkeyReview)
            rich.verify(sender.db, sender.security)

            sender.toggle(true); receiver.toggle(true)
            var stoppedDuringImport = false
            var passkeyPrompts = 0
            receiver.onProgress = { progress ->
                if (!stoppedDuringImport && progress.stage == "applying") {
                    assertTrue(receiver.gate.isLocked)
                    receiver.toggle(false); receiver.toggle(true); receiver.toggle(false)
                    sender.toggle(false)
                    stoppedDuringImport = true
                }
            }
            suspend fun awaitSuccess(previous: Long = 0L) {
                coroutineScope {
                    // Separate devices keep polling independently while their peer imports a large vault.
                    val devices = listOf(sender, receiver).map { fixture ->
                        launch(Dispatchers.Default) { while (isActive) { fixture.tick(); delay(500) } }
                    }
                    try {
                        withTimeout(600_000) {
                            while (sender.settings.peers.single().lastSyncedAt <= previous || receiver.settings.peers.single().lastSyncedAt <= previous) {
                                sender.coordinator.state.value.passkeyReview?.let { review ->
                                    passkeyPrompts++
                                    sender.coordinator.confirmPasskeySync(review, true)
                                    withTimeout(30_000) { while (sender.coordinator.state.value.passkeyReview != null) delay(10) }
                                }
                                assertNull(sender.coordinator.state.value.error); assertNull(receiver.coordinator.state.value.error)
                                delay(30)
                            }
                        }
                    } finally { devices.forEach { it.cancelAndJoin() } }
                }
            }
            awaitSuccess()
            assertTrue(stoppedDuringImport)
            assertFalse(sender.store.load().enabled); assertFalse(receiver.store.load().enabled)
            rich.verify(receiver.db, receiver.security)
            assertEquals(1, passkeyPrompts)
            assertTrue(sender.store.passkeyNoticeSuppressed())
            assertEquals(610, receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().size)
            assertEquals(0L, receiver.db.passkeyDao().getAllPasskeysSync().single().signCount)
            assertEquals(setOf(0L, 7L), sender.db.passkeyDao().getAllPasskeysSync().map { it.signCount }.toSet())
            val account = SteamAccountRepository(receiver.steam.steamAccountDao(), receiver.security).getAccounts().single()
            assertEquals(secret, account.sharedSecret); assertEquals(secret, account.identitySecret)
            val previous = maxOf(sender.settings.peers.single().lastSyncedAt, receiver.settings.peers.single().lastSyncedAt)
            val source = sender.db.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.title == "Bulk 0" }
            sender.db.passwordEntryDao().updatePasswordEntry(source.copy(password = "changed-after-off"))
            repeat(4) { sender.tick(); receiver.tick() }
            fun receiverSecret(entry: takagi.ru.monica.data.PasswordEntry) = receiver.security.decryptDataIfMonicaCiphertext(entry.password)
            assertEquals("synthetic-0", receiverSecret(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.title == "Bulk 0" }))
            // Automatic remains disabled, but explicitly requested synchronization still works.
            sender.manual(); receiver.manual(); awaitSuccess(previous)
            assertEquals("changed-after-off", receiverSecret(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single { it.title == "Bulk 0" }))
            rich.verify(receiver.db, receiver.security)
            assertFalse(sender.store.load().enabled); assertFalse(receiver.store.load().enabled)
            assertEquals(1, passkeyPrompts)
            assertTrue(DeviceSyncPreferenceStore(context, sender.security, sender.preferenceName).passkeyNoticeSuppressed())
        } finally {
            activity.cancelAndJoin()
            rich.clean(sender.db); rich.clean(receiver.db); sender.close(); receiver.close(); SessionManager.markLocked()
        }
    }
}
