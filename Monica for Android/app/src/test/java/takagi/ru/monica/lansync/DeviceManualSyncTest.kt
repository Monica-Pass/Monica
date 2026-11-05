package takagi.ru.monica.lansync

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamDatabase
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class,
    shadows = [DeviceVaultProviderTest.SecureStoreShadow::class, DeviceVaultProviderTest.PreparedStoreShadow::class, DeviceVaultProviderTest.SecurityShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceManualSyncTest {
    @Test fun cancellation_interrupts_a_snapshot_wait_and_does_not_replay_a_queued_sync() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val fixture = Fixture(RuntimeEnvironment.getApplication(), "z-interrupt", "a-interrupt", DeviceSyncMode.SEND_ONLY)
        ReflectionHelpers.setField(fixture.coordinator, "running", Job())
        val gate = ReflectionHelpers.getField<Mutex>(fixture.coordinator, "gate")
        val providerGate = ReflectionHelpers.getField<Mutex>(fixture.provider, "gate")
        providerGate.lock()
        fixture.request()
        val work = launch(Dispatchers.Default) { gate.withLock { fixture.tick() } }
        try {
            withTimeout(5_000) { while (ReflectionHelpers.getField<Job?>(fixture.coordinator, "activeSyncWork") == null) delay(10) }
            fixture.coordinator.cancelSync()
            withTimeout(2_000) { work.join() }
            providerGate.unlock()
            withTimeout(5_000) { while (fixture.coordinator.state.value.phase != "cancelled") delay(10) }
            assertEquals(0L, fixture.coordinator.state.value.lastAttemptAt)
            gate.lock()
            val request = fixture.settings.syncRequest
            fixture.coordinator.syncNow()
            fixture.coordinator.cancelSync()
            gate.unlock()
            withTimeout(5_000) { while (fixture.coordinator.state.value.phase != "cancelled") delay(10) }
            assertEquals("Queued work from before Cancel must not run later", request, fixture.settings.syncRequest)
        } finally {
            if (providerGate.isLocked) providerGate.unlock()
            work.cancel()
            fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain()
        }
    }

    @Test fun cancellation_is_visible_without_waiting_for_preparation_gate_and_stale_progress_cannot_restore_it() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val fixture = Fixture(RuntimeEnvironment.getApplication(), "z-cancel", "a-cancel", DeviceSyncMode.SEND_ONLY)
        ReflectionHelpers.setField(fixture.coordinator, "running", Job())
        val gate = ReflectionHelpers.getField<Mutex>(fixture.coordinator, "gate")
        gate.lock()
        try {
            fixture.request()
            fixture.coordinator.cancelSync()
            assertEquals("Cancel must respond while export holds the gate", "cancelling", fixture.coordinator.state.value.phase)
            ReflectionHelpers.callInstanceMethod<Void>(fixture.coordinator, "updateProgress",
                ClassParameter.from(DeviceSyncProgress::class.java, DeviceSyncProgress("preparing", 1, 2)))
            assertEquals("cancelling", fixture.coordinator.state.value.phase)
            assertNull(fixture.coordinator.state.value.progress)
            gate.unlock()
            withTimeout(5_000) { while (fixture.coordinator.state.value.phase != "cancelled") delay(10) }
            assertFalse(fixture.coordinator.state.value.manualSync)
            assertFalse(fixture.store.load().enabled)
        } finally {
            if (gate.isLocked) gate.unlock()
            fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain()
        }
    }

    private class Fixture(context: Context, id: String, peer: String, mode: DeviceSyncMode) {
        val online = kotlinx.coroutines.flow.MutableStateFlow(setOf(peer))
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).allowMainThreadQueries().setQueryExecutor { it.run() }.build()
        val steam = Room.inMemoryDatabaseBuilder(context, SteamDatabase::class.java).allowMainThreadQueries().build()
        val settings = DeviceSyncPreferences(deviceId = id, enabled = false, mode = mode,
            peers = mutableListOf(SavedSyncPeer(peer, "Fixture", "manual-pair", "A".repeat(43), address = "127.0.0.1")))
        val preferenceName = "manual-${UUID.randomUUID()}"
        val store = DeviceSyncPreferenceStore(context, SecurityManager(context), preferenceName).also { it.save(settings) }
        val provider = DevicePasswordSyncProvider(context, SecurityManager(context), store, db,
            File(context.cacheDir, "manual-${UUID.randomUUID()}.replica"), steam)
        val coordinator = ReflectionHelpers.callConstructor(DeviceSyncCoordinator::class.java, ClassParameter.from(Context::class.java, context)).also {
            it.setAppVisible(true)
            ReflectionHelpers.setField(it, "presence", object : DeviceSyncPresence {
                override val online = this@Fixture.online
                override val available = true
                override fun start(deviceId: String, peers: Set<String>) = Unit
                override fun stop() = Unit
            })
            ReflectionHelpers.setField(it, "preferences", settings)
            ReflectionHelpers.setField(it, "store\$delegate", lazyOf(store))
            ReflectionHelpers.setField(it, "dataProvider", lazyOf(provider))
        }
        suspend fun tick() = invoke("tick")
        fun request() { ReflectionHelpers.callInstanceMethod<Void>(coordinator, "beginManualSync") }
        fun expireConnectionWindow() { ReflectionHelpers.setField(coordinator, "manualDeadline", 0L) }
        suspend fun invoke(name: String): Any? = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = DeviceSyncCoordinator::class.java.getDeclaredMethod(name, kotlin.coroutines.Continuation::class.java)
            method.isAccessible = true; method.invoke(coordinator, continuation)
        }
        suspend fun close() {
            ReflectionHelpers.getField<CoroutineScope>(coordinator, "scope").cancel()
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                val method = DeviceSyncCoordinator::class.java.getDeclaredMethod("pause", Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                method.isAccessible = true; method.invoke(coordinator, false, continuation)
            }
            provider.pause(); db.close(); steam.close()
        }
    }

    @Test fun deletion_confirmation_does_not_wait_for_receive_gate_and_ignores_stale_answers() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val fixture=Fixture(RuntimeEnvironment.getApplication(),"review-local","review-peer",DeviceSyncMode.BIDIRECTIONAL)
        ReflectionHelpers.setField(fixture.coordinator,"running",Job())
        val gate=ReflectionHelpers.getField<Mutex>(fixture.coordinator,"gate")
        gate.lock()
        suspend fun review(): Boolean = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            val method=DeviceSyncCoordinator::class.java.getDeclaredMethod("reviewReceivedDeletions",org.json.JSONObject::class.java,
                Int::class.javaPrimitiveType,kotlin.coroutines.Continuation::class.java)
            method.isAccessible=true
            method.invoke(fixture.coordinator,org.json.JSONObject().put("device_id","review-peer").put("name","Synthetic browser"),2,continuation)
        }
        try {
            val work=async { review() }
            withTimeout(5000) { while(fixture.coordinator.state.value.deletionReview==null) delay(10) }
            val pending=fixture.coordinator.state.value.deletionReview!!
            assertEquals(2,pending.count)
            fixture.coordinator.confirmReceivedDeletions(pending.copy(id="stale"),true)
            assertFalse(work.isCompleted)
            fixture.coordinator.confirmReceivedDeletions(pending,false)
            assertFalse(withTimeout(5000) { work.await() })
            assertNull(fixture.coordinator.state.value.deletionReview)
            fixture.coordinator.confirmReceivedDeletions(pending,true)
            val cancelled=async { review() }
            withTimeout(5000) { while(fixture.coordinator.state.value.deletionReview==null) delay(10) }
            ReflectionHelpers.setField(fixture.coordinator,"activeSyncWork",cancelled)
            fixture.coordinator.cancelSync()
            assertNull(fixture.coordinator.state.value.deletionReview)
            assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } finally { gate.unlock(); fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    }

    @Test fun batch_forget_removes_only_requested_pairs_and_persists_the_rest() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val fixture=Fixture(RuntimeEnvironment.getApplication(),"batch-local","remove-one",DeviceSyncMode.BIDIRECTIONAL)
        ReflectionHelpers.setField(fixture.coordinator,"running",Job())
        fixture.settings.peers+=SavedSyncPeer("remove-two","Second fixture","pair-two","A".repeat(43))
        fixture.settings.peers+=SavedSyncPeer("keep","Kept fixture","pair-three","A".repeat(43))
        fixture.store.save(fixture.settings)
        try {
            fixture.coordinator.forgetDevices(setOf("remove-one","remove-two","already-absent"))
            withTimeout(5000) { while(fixture.coordinator.state.value.peers.map { it.deviceId }!=listOf("keep")) delay(10) }
            assertEquals(listOf("keep"),fixture.store.load().peers.map { it.deviceId })
        } finally { fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    }

    @Test fun absent_peer_never_prepares_or_probes_and_background_rejects_manual_work() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        SessionManager.markUnlocked()
        val fixture = Fixture(RuntimeEnvironment.getApplication(), "z-idle", "a-closed", DeviceSyncMode.SEND_ONLY)
        var starts = 0
        var stops = 0
        ReflectionHelpers.setField(fixture.coordinator, "presence", object : DeviceSyncPresence {
            override val online = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
            override val available = true
            override fun start(deviceId: String, peers: Set<String>) { starts++ }
            override fun stop() { stops++ }
        })
        try {
            fixture.settings.enabled = true; fixture.store.save(fixture.settings)
            ReflectionHelpers.setField(fixture.coordinator, "preferences", null)
            repeat(20) { fixture.tick() }
            assertTrue(starts > 0)
            assertEquals("waiting_for_app", fixture.coordinator.state.value.phase)
            assertEquals(0L, fixture.coordinator.state.value.lastAttemptAt)
            assertEquals(0L, fixture.coordinator.state.value.lastDiscoveryAt)
            assertNull(ReflectionHelpers.getField<Any?>(fixture.coordinator, "snapshot"))
            assertTrue(ReflectionHelpers.getField<Set<String>>(fixture.coordinator, "force").isEmpty())
            fixture.coordinator.setAppVisible(false)
            fixture.request(); fixture.tick()
            assertEquals("background", fixture.coordinator.state.value.phase)
            assertFalse(fixture.coordinator.state.value.manualSync)
            assertTrue(stops > 0)
            val before = starts
            repeat(20) { fixture.tick() }
            assertEquals(before, starts)
            assertEquals(0L, fixture.coordinator.state.value.lastAttemptAt)
        } finally { fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    }

    @Test fun manual_send_only_to_receive_only_runs_once_without_enabling_automatic_sync() = runBlocking { withTimeout(90_000) {
        DeviceVaultProviderTest.loadNative()
        Dispatchers.setMain(Dispatchers.Unconfined)
        SessionManager.markUnlocked()
        val context = RuntimeEnvironment.getApplication()
        val sender = Fixture(context, "z-sender", "a-receiver", DeviceSyncMode.SEND_ONLY)
        val receiver = Fixture(context, "a-receiver", "z-sender", DeviceSyncMode.RECEIVE_ONLY)
        try {
            // Manual fallback must work even when NSD cannot see either paired app.
            sender.online.value = emptySet(); receiver.online.value = emptySet()
            val source = sender.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Manual fixture",
                website = "example.test", username = "fixture", password = "first-secret"))
            sender.tick(); receiver.tick()
            assertNull(ReflectionHelpers.getField<Any?>(sender.coordinator, "snapshot"))
            assertTrue(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().isEmpty())
            sender.request(); receiver.request()
            sender.tick()
            assertTrue(sender.coordinator.state.value.lastAttemptAt > 0)
            DeviceSyncEditGate.hold().use {
                val receiveDeadline = System.nanoTime() + 60_000_000_000L
                while (receiver.coordinator.state.value.phase != "received" && System.nanoTime() < receiveDeadline) {
                    sender.tick(); receiver.tick()
                    assertNull(sender.coordinator.state.value.error)
                    assertNull(receiver.coordinator.state.value.error)
                    Thread.sleep(30)
                }
                assertEquals("received", receiver.coordinator.state.value.phase)
                receiver.expireConnectionWindow()
                receiver.tick()
                assertNull(receiver.coordinator.state.value.error)
                assertTrue(receiver.coordinator.state.value.manualSync)
                assertTrue(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().isEmpty())
            }
            val deadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < deadline && (sender.settings.peers.single().lastSyncedAt == 0L || receiver.settings.peers.single().lastSyncedAt == 0L)) {
                sender.tick(); receiver.tick()
                assertNull(sender.coordinator.state.value.error)
                assertNull(receiver.coordinator.state.value.error)
                Thread.sleep(30)
            }
            assertTrue(sender.settings.peers.single().lastSyncedAt > 0L)
            assertTrue(receiver.settings.peers.single().lastSyncedAt > 0L)
            assertTrue(receiver.coordinator.state.value.lastDiscoveryAt > 0)
            assertEquals(1, receiver.coordinator.state.value.discoveredPeers)
            assertEquals(0, receiver.coordinator.state.value.activeConnections)
            assertFalse(sender.store.load().enabled); assertFalse(receiver.store.load().enabled)
            assertFalse(sender.coordinator.state.value.manualSync); assertFalse(receiver.coordinator.state.value.manualSync)
            val received = receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().single()
            assertEquals("first-secret", SecurityManager(context).decryptDataIfMonicaCiphertext(received.password))
            sender.db.passwordEntryDao().updatePasswordEntry(sender.db.passwordEntryDao().getPasswordEntryById(source)!!.copy(password = "second-secret"))
            repeat(4) { sender.tick(); receiver.tick() }
            assertEquals("first-secret", SecurityManager(context).decryptDataIfMonicaCiphertext(receiver.db.passwordEntryDao().getPasswordEntryById(received.id)!!.password))
            // Re-enable automatic sync and verify a later edit arrives without another request.
            sender.online.value = setOf("a-receiver"); receiver.online.value = setOf("z-sender")
            sender.settings.enabled = true; sender.store.save(sender.settings)
            receiver.settings.enabled = true; receiver.store.save(receiver.settings)
            val automaticDeadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < automaticDeadline) {
                // Native I/O uses wall time; Robolectric's cadence clock must advance explicitly.
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
                sender.tick(); receiver.tick()
                val password = receiver.db.passwordEntryDao().getPasswordEntryById(received.id)!!.password
                if (SecurityManager(context).decryptDataIfMonicaCiphertext(password) == "second-secret") break
                Thread.sleep(30)
            }
            assertEquals("second-secret", SecurityManager(context).decryptDataIfMonicaCiphertext(receiver.db.passwordEntryDao().getPasswordEntryById(received.id)!!.password))
            assertFalse(sender.coordinator.state.value.manualSync); assertFalse(receiver.coordinator.state.value.manualSync)
        } finally {
            sender.close(); receiver.close(); SessionManager.markLocked(); Dispatchers.resetMain()
        }
    } }

    @Test fun receiver_write_failure_reaches_sender_as_rejection_and_retry_completes() = runBlocking { withTimeout(90000) {
        DeviceVaultProviderTest.loadNative(); Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val context = RuntimeEnvironment.getApplication()
        val sender = Fixture(context,"z-reject-sender","a-reject-receiver",DeviceSyncMode.SEND_ONLY)
        val receiver = Fixture(context,"a-reject-receiver","z-reject-sender",DeviceSyncMode.RECEIVE_ONLY)
        try {
            sender.db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title="Rejected fixture",website="",username="",password="synthetic"))
            receiver.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON password_entries BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END")
            sender.request(); receiver.request()
            val deadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < deadline && sender.coordinator.state.value.error == null) {
                sender.tick(); receiver.tick(); Thread.sleep(30)
            }
            assertEquals("peer_merge_failed",sender.coordinator.state.value.error)
            assertEquals("peer_apply",sender.coordinator.state.value.failureOperation)
            assertEquals("applying",receiver.coordinator.state.value.failureStage)
            assertEquals(0L,sender.settings.peers.single().lastSyncedAt)
            assertEquals(0L,receiver.settings.peers.single().lastSyncedAt)
            assertTrue(receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().isEmpty())
            receiver.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_fixture")
            // Finish native rejection cleanup before explicitly requesting the retry.
            repeat(3) { receiver.tick(); sender.tick() }
            sender.request(); receiver.request()
            val retryDeadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < retryDeadline && (sender.settings.peers.single().lastSyncedAt == 0L || receiver.settings.peers.single().lastSyncedAt == 0L)) {
                sender.tick(); receiver.tick(); Thread.sleep(30)
            }
            assertTrue(sender.settings.peers.single().lastSyncedAt > 0L)
            assertTrue(receiver.settings.peers.single().lastSyncedAt > 0L)
            assertEquals(1,receiver.db.passwordEntryDao().getDeviceSyncLocalPasswords().size)
        } finally { sender.close(); receiver.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    } }

    @Test fun manual_connection_window_expires_when_the_peer_never_connects() = runBlocking {
        DeviceVaultProviderTest.loadNative()
        Dispatchers.setMain(Dispatchers.Unconfined)
        SessionManager.markUnlocked()
        val sender = Fixture(RuntimeEnvironment.getApplication(), "z-timeout", "a-absent", DeviceSyncMode.SEND_ONLY)
        try {
            sender.request(); sender.tick()
            assertTrue(sender.coordinator.state.value.manualSync)
            sender.expireConnectionWindow(); sender.tick()
            assertEquals("manual_timeout", sender.coordinator.state.value.failureOperation)
            assertFalse(sender.coordinator.state.value.manualSync)
            assertFalse(sender.store.load().enabled)
            assertTrue(ReflectionHelpers.getField<Map<String, Any>>(sender.coordinator, "transfers").isEmpty())
        } finally {
            sender.close(); SessionManager.markLocked(); Dispatchers.resetMain()
        }
    }

    @Test fun passkey_notice_is_remembered_only_after_valid_confirmation_and_can_be_restored() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val context = RuntimeEnvironment.getApplication()
        val fixture = Fixture(context, "notice-sender", "notice-receiver", DeviceSyncMode.SEND_ONLY)
        ReflectionHelpers.setField(fixture.coordinator, "running", Job())
        val consent = ReflectionHelpers.getField<DevicePasskeySyncConsent>(fixture.coordinator, "passkeyConsent")
        val gate = ReflectionHelpers.getField<Mutex>(fixture.coordinator, "gate")
        val issue = DevicePasskeySyncIssue("fixture", "Synthetic account", "example.test",
            takagi.ru.monica.passkey.PasskeyPortability.COUNTER_HISTORY, skipForCounter = true)
        suspend fun await(check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(10) }
        try {
            val stale = consent.review(listOf(issue), setOf("phone"))!!
            val current = consent.review(listOf(issue.copy(identity = "new")), setOf("phone"))!!
            fixture.coordinator.confirmPasskeySync(stale, true)
            await { fixture.coordinator.state.value.passkeyReview == current }
            assertFalse(fixture.store.passkeyNoticeSuppressed())
            fixture.coordinator.cancelPasskeySync()
            await { fixture.coordinator.state.value.passkeyReview == null }
            assertFalse(fixture.store.passkeyNoticeSuppressed())
            val accepted = gate.withLock { consent.review(listOf(issue), setOf("phone"))!! }
            fixture.coordinator.confirmPasskeySync(accepted, true)
            await { fixture.store.passkeyNoticeSuppressed() }
            val reopened = DeviceSyncPreferenceStore(context, SecurityManager(context), fixture.preferenceName)
            assertTrue(reopened.passkeyNoticeSuppressed())
            fixture.store.save(fixture.settings)
            assertTrue(reopened.passkeyNoticeSuppressed())
            val restartedConsent = DevicePasskeySyncConsent()
            assertNull(restartedConsent.review(listOf(issue), setOf("another-device"), reopened.passkeyNoticeSuppressed()))
            assertTrue(issue.skipForCounter)
            fixture.coordinator.setPasskeyNoticeSuppressed(false)
            withTimeout(10_000) {
                while (gate.withLock { consent.review(listOf(issue), setOf("phone")) } == null) delay(10)
            }
            assertFalse(reopened.passkeyNoticeSuppressed())
        } finally { fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    }

    @Test fun passkey_diagnostics_do_not_start_sync_or_change_its_phase_and_close_idle_replica() = runBlocking {
        DeviceVaultProviderTest.loadNative(); Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val fixture = Fixture(RuntimeEnvironment.getApplication(), "diagnostic-sender", "diagnostic-peer", DeviceSyncMode.SEND_ONLY)
        ReflectionHelpers.setField(fixture.coordinator, "running", Job())
        try {
            fixture.coordinator.refreshPasskeyDiagnostics()
            withTimeout(15_000) { while (fixture.coordinator.state.value.passkeyDiagnosticIssues == null) delay(10) }
            assertFalse(fixture.coordinator.state.value.checkingPasskeys)
            assertEquals("idle", fixture.coordinator.state.value.phase)
            assertEquals(0L, fixture.coordinator.state.value.lastAttemptAt)
            assertNull(ReflectionHelpers.getField<Any?>(fixture.provider, "handle"))
            fixture.settings.databaseId = 999L
            fixture.coordinator.refreshPasskeyDiagnostics()
            withTimeout(15_000) { while (!fixture.coordinator.state.value.passkeyDiagnosticFailed) delay(10) }
            assertEquals("idle", fixture.coordinator.state.value.phase)
            assertNull(fixture.coordinator.state.value.error)
            assertNull(ReflectionHelpers.getField<Any?>(fixture.provider, "handle"))
        } finally { fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain() }
    }

    @Test fun automatic_toggle_is_immediate_and_persisted_while_sync_holds_the_gate() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined); SessionManager.markUnlocked()
        val context = RuntimeEnvironment.getApplication()
        val fixture = Fixture(context, "z-toggle", "a-toggle", DeviceSyncMode.SEND_ONLY)
        // Drive the real action API, but hold the same gate as a long export/import.
        ReflectionHelpers.setField(fixture.coordinator, "running", Job())
        val gate = ReflectionHelpers.getField<Mutex>(fixture.coordinator, "gate")
        gate.lock()
        try {
            for (phase in listOf("preparing", "sending", "applying", "confirming", "failed", "waiting")) {
                ReflectionHelpers.setField(fixture.coordinator, "phase", phase)
                ReflectionHelpers.callInstanceMethod<Void>(fixture.coordinator, "publish")
                for (enabled in listOf(true, false, true, false)) {
                    fixture.coordinator.setEnabled(enabled)
                    assertEquals("Switch must update during $phase", enabled, fixture.coordinator.state.value.enabled)
                    assertEquals("Setting must persist during $phase", enabled, fixture.store.load().enabled)
                    // Progress updates and saves of the older snapshot must not undo a tap.
                    fixture.store.save(fixture.settings)
                    ReflectionHelpers.callInstanceMethod<Void>(fixture.coordinator, "publish")
                    assertEquals(enabled, fixture.coordinator.state.value.enabled)
                    assertEquals(enabled, fixture.store.load().enabled)
                    assertEquals(phase, fixture.coordinator.state.value.phase)
                }
            }
        } finally {
            ReflectionHelpers.getField<CoroutineScope>(fixture.coordinator, "scope").cancel()
            gate.unlock(); fixture.close(); SessionManager.markLocked(); Dispatchers.resetMain()
        }
    }
}
