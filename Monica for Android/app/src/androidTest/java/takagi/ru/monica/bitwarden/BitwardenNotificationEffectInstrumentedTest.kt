package takagi.ru.monica.bitwarden

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.BitwardenNotificationTestFrames
import takagi.ru.monica.bitwarden.sync.SyncTriggerReason
import takagi.ru.monica.bitwarden.ui.BitwardenForegroundNotificationEffect
import takagi.ru.monica.bitwarden.ui.notificationNetwork
import takagi.ru.monica.bitwarden.viewmodel.BitwardenViewModel
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.sync.SyncPhase
import takagi.ru.monica.sync.SyncTarget
import takagi.ru.monica.sync.SyncTaskRunner
import java.util.concurrent.atomic.AtomicInteger

/** Actual Compose effect + Activity lifecycle + actual ViewModel/coordinator/HTTP/Room, without a substitute monitor. */
@RunWith(AndroidJUnit4::class)
class BitwardenNotificationEffectInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var diagnostics: suspend () -> String = { "fixture not initialized" }

    @Test fun actualEffectStopsOnCreatedRejectsLateEventsAndCatchesUpOnResumed(): Unit = runBlocking {
        scenario("lifecycle") { f, account, _, _ ->
            await("lifecycle initial catch-up") { f.title(account) == "initial-lifecycle" }
            account.title = "foreground-update"
            account.revision = "2026-11-04T02:00:00Z"
            account.socket!!.send(BitwardenNotificationTestFrames.event(account.user).toByteString())
            await("lifecycle foreground event") { f.title(account) == "foreground-update" }
            val firstSocket = account.socket!!
            val opened = account.connections.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await("background socket closed") { account.closedConnections.get() >= opened }
            val stoppedPulls = account.pulls.get()
            account.title = "changed-while-background"
            account.revision = "2026-11-04T03:00:00Z"
            firstSocket.send(BitwardenNotificationTestFrames.event(account.user).toByteString())
            delay(1200)
            assertEquals(stoppedPulls, account.pulls.get())
            assertEquals(opened, account.connections.get())
            assertEquals("foreground-update", f.title(account))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await("lifecycle resumed catch-up") { account.connections.get() > opened && f.title(account) == "changed-while-background" }
            val row = requireNotNull(f.database.passwordEntryDao().getByBitwardenCipherIdInVault(account.vault.id, "same-cipher-id"))
            assertEquals("secret-lifecycle", SecurityManager(context).decryptData(row.password))
            assertEquals(1, f.database.passwordEntryDao().getAllByBitwardenCipherIdInVault(account.vault.id, "same-cipher-id").size)
        }
    }

    @Test fun anotherSettingsViewModelClosesAndRestartsActualEffectWithoutUpdatingRootFlow(): Unit = runBlocking {
        scenario("preferences") { f, account, rootModel, settingsModel ->
            await("preferences initial catch-up") { f.title(account) == "initial-preferences" }
            assertTrue(rootModel.isAutoSyncEnabledFlow.value)
            val opened = account.connections.get()
            onMain { settingsModel.isAutoSyncEnabled = false }
            await("preferences disabled socket close") { !f.repository.isAutoSyncEnabled && account.closedConnections.get() >= opened }
            // Reproduces independent ViewModels: only the real preferences listener can stop the effect.
            assertTrue(rootModel.isAutoSyncEnabledFlow.value)
            assertFalse(settingsModel.isAutoSyncEnabledFlow.value)
            val stoppedPulls = account.pulls.get()
            account.title = "changed-while-disabled"
            account.revision = "2026-11-04T02:00:00Z"
            account.socket!!.send(BitwardenNotificationTestFrames.event(account.user).toByteString())
            delay(1200)
            assertEquals(stoppedPulls, account.pulls.get())
            assertEquals(opened, account.connections.get())
            assertEquals("initial-preferences", f.title(account))
            onMain { settingsModel.isAutoSyncEnabled = true }
            await("preferences enabled catch-up") { account.connections.get() > opened && f.title(account) == "changed-while-disabled" }
        }
    }

    @Test fun leavingActualEffectDuringStartupGraceRevokesDelayedSyncUntilNewForegroundSession(): Unit = runBlocking {
        scenario("startup") { f, account, rootModel, _ ->
            await("startup notification queued before grace") { account.connections.get() > 0 && rootModel.syncStatusByVault.value[account.vault.id]?.queuedReason == SyncTriggerReason.REMOTE_NOTIFICATION }
            assertEquals(0, account.pulls.get())
            val opened = account.connections.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await("background socket closed") { account.closedConnections.get() >= opened }
            // Production grace is eight seconds; the test leaves it intact and waits past it.
            delay(9000)
            assertEquals(0, account.pulls.get())
            assertEquals(opened, account.connections.get())
            assertNull(f.title(account))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await("startup resumed catch-up") { account.connections.get() > opened && f.title(account) == "initial-startup" }
        }
    }

    private suspend fun scenario(
        label: String,
        block: suspend (BitwardenNotificationInstrumentedTest.Fixture, BitwardenNotificationInstrumentedTest.Account,
            BitwardenViewModel, BitwardenViewModel) -> Unit,
    ) {
        val repository = BitwardenRepository.getInstance(context)
        check(repository.getAllVaults().none { it.syncEnabled && repository.isVaultUnlocked(it.id) }) {
            "Run only in the dedicated synthetic test user; another unlocked account must not be monitored"
        }
        assertNotNull("A real active network with INTERNET capability is required", notificationNetwork(context, false))
        val wasUnlocked = SessionManager.isUnlocked.value
        val field = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val previousMdk = field.get(null)
        val syntheticMdk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val rootStore = ViewModelStore()
        val settingsStore = ViewModelStore()
        val enabled = mutableStateOf(true)
        var fixture: BitwardenNotificationInstrumentedTest.Fixture? = null
        var accountId: Long? = null
        var primaryFailure: Throwable? = null
        try {
            field.set(null, syntheticMdk)
            onMain { SessionManager.markUnlocked() }
            val f = BitwardenNotificationInstrumentedTest.Fixture().also { fixture = it }
            val account = f.add(label)
            accountId = account.vault.id
            lateinit var rootModel: BitwardenViewModel
            lateinit var settingsModel: BitwardenViewModel
            onMain {
                val factory = ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application)
                rootModel = ViewModelProvider(rootStore, factory)[BitwardenViewModel::class.java]
                settingsModel = ViewModelProvider(settingsStore, factory)[BitwardenViewModel::class.java]
            }
            val callbacks = AtomicInteger()
            diagnostics = {
                val status = rootModel.syncStatusByVault.value[account.vault.id]
                val central = SyncTaskRunner.statuses.first()[SyncTarget.BitwardenVault(account.vault.id).stableKey]
                "activity=${compose.activityRule.scenario.state}, appUnlocked=${SessionManager.isUnlocked.value}, " +
                    "vaultUnlocked=${f.repository.isVaultUnlocked(account.vault.id)}, " +
                    "auto=${f.repository.isAutoSyncEnabled}, wifiOnly=${f.repository.isSyncOnWifiOnly}, " +
                    "network=${notificationNetwork(context, f.repository.isSyncOnWifiOnly) != null}, " +
                    "opened=${account.connections.get()}, closed=${account.closedConnections.get()}, " +
                    "rejected=${account.rejections.get()}, callbacks=${callbacks.get()}, pulls=${account.pulls.get()}, " +
                    "rowPresent=${f.title(account) != null}, running=${status?.isRunning}, " +
                    "queued=${status?.queuedReason}, blocked=${status?.blockedReason}, retry=${status?.retryAttempt}, " +
                    "centralPhase=${central?.phase}, centralQueued=${central?.queuedCount}"
            }
            compose.setContent {
                rootModel.syncStatusByVault.collectAsState()
                BitwardenForegroundNotificationEffect(enabled.value,
                    onSyncRequested = { vaultId, stillValid ->
                        callbacks.incrementAndGet()
                        rootModel.requestNotificationSync(vaultId, stillValid)
                    })
            }
            block(f, account, rootModel, settingsModel)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                try {
                    onMain { enabled.value = false; rootStore.clear(); settingsStore.clear() }
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    compose.waitForIdle()
                    accountId?.let { id ->
                        await("cleanup central task stopped") { SyncTaskRunner.statuses.first()[SyncTarget.BitwardenVault(id).stableKey]?.let {
                            it.phase != SyncPhase.RUNNING && it.queuedCount == 0
                        } != false }
                    }
                } finally {
                    try { fixture?.close() } finally {
                        if (!wasUnlocked) onMain { SessionManager.markLocked() }
                        field.set(null, previousMdk)
                        syntheticMdk.fill(0)
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            } finally {
                diagnostics = { "fixture closed" }
            }
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private suspend fun await(stage: String, condition: suspend () -> Boolean) {
        Log.i("BWNotificationEffectTest", "Awaiting $stage")
        try {
            withTimeout(30_000) {
                while (true) {
                    // collectAsState needs a Compose frame; real network/lifecycle work uses wall time.
                    compose.mainClock.advanceTimeByFrame()
                    if (condition()) break
                    delay(50)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val state = runCatching { diagnostics() }.getOrElse { "diagnostic failed: ${it.javaClass.simpleName}" }
            throw AssertionError("Notification Effect timed out at $stage; $state", timeout)
        }
        Log.i("BWNotificationEffectTest", "Completed $stage")
    }
}
