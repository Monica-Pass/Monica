package takagi.ru.monica.sync

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import takagi.ru.monica.bitwarden.sync.*
import takagi.ru.monica.localization.xmlTestStrings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BitwardenNotificationSchedulingTest {
    @Test fun remoteChangeAfterSuccessfulPullIsNotDroppedByPageThrottle() = runBlocking {
        val owner = Job()
        var count = 0
        val scheduler = BitwardenSyncOrchestrator(strings = xmlTestStrings("en"),
            scope = CoroutineScope(coroutineContext + owner), isAutoSyncEnabled = { true },
            checkNetwork = { NetworkGateResult.ALLOWED }, isVaultUnlocked = { true },
            executeSync = { _, _ -> count++; SyncExecutionOutcome.Success(0, 0, 0, 0, 0) }, nowProvider = { 100_000 })
        try {
            scheduler.requestSync(1, SyncTriggerReason.PAGE_ENTER).join()
            withTimeout(1000) { while (count < 1 || scheduler.statusByVault.value[1]?.isRunning == true) yield() }
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION).join()
            withTimeout(1000) { while (count < 2) yield() }
            assertEquals(2, count)
        } finally { owner.cancel() }
    }

    @Test fun notificationRetryDoesNotBypassAutoSyncBeingDisabled() = runBlocking {
        val owner = Job()
        var enabled = true
        var count = 0
        val scheduler = BitwardenSyncOrchestrator(strings = xmlTestStrings("en"),
            scope = CoroutineScope(coroutineContext + owner), config = SyncManagerConfig(retryBaseDelayMs = 30),
            isAutoSyncEnabled = { enabled }, checkNetwork = { NetworkGateResult.ALLOWED }, isVaultUnlocked = { true },
            executeSync = { _, _ -> count++; enabled = false; SyncExecutionOutcome.RetryableError("synthetic failure") })
        try {
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION).join()
            delay(150)
            assertEquals(1, count)
            assertEquals(SyncBlockReason.AUTO_SYNC_DISABLED, scheduler.statusByVault.value[1]?.blockedReason)
        } finally { owner.cancel() }
    }

    @Test fun queuedRemoteChangeRechecksNetworkBeforeStarting() = runBlocking {
        val owner = Job()
        var allowed = true
        var count = 0
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val scheduler = BitwardenSyncOrchestrator(strings = xmlTestStrings("en"),
            scope = CoroutineScope(coroutineContext + owner), isAutoSyncEnabled = { true },
            checkNetwork = { if (allowed) NetworkGateResult.ALLOWED else NetworkGateResult.WIFI_REQUIRED },
            isVaultUnlocked = { true }, executeSync = { _, _ ->
                count++; entered.complete(Unit); finish.await(); SyncExecutionOutcome.Success(0, 0, 0, 0, 0)
            })
        try {
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION)
            withTimeout(1000) { entered.await() }
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION).join()
            allowed = false
            finish.complete(Unit)
            delay(50)
            assertEquals(1, count)
            assertEquals(SyncBlockReason.WIFI_REQUIRED, scheduler.statusByVault.value[1]?.blockedReason)
        } finally { owner.cancel() }
    }

    @Test fun delayedNotificationDoesNotRunAfterForegroundLeaseIsRevoked(): Unit = runBlocking {
        val owner = Job()
        val active = AtomicBoolean(true)
        val count = AtomicInteger()
        val scheduler = scheduler(owner) { _, _ -> count.incrementAndGet(); success() }
        try {
            scheduler.requestSyncWithDelay(1, SyncTriggerReason.REMOTE_NOTIFICATION, 80, isStillValid = { active.get() })
            yield()
            active.set(false)
            delay(200)
            assertEquals(0, count.get())
            assertEquals(null, scheduler.statusByVault.value[1]?.queuedReason)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun quickLockThenUnlockCannotReviveAnOldNotificationGeneration(): Unit = runBlocking {
        val owner = Job()
        val generation = AtomicInteger(1)
        val count = AtomicInteger()
        val scheduler = scheduler(owner) { _, _ -> count.incrementAndGet(); success() }
        try {
            val expected = generation.get()
            scheduler.requestSyncWithDelay(1, SyncTriggerReason.REMOTE_NOTIFICATION, 80,
                isStillValid = { generation.get() == expected })
            yield()
            generation.incrementAndGet() // lock
            generation.incrementAndGet() // same account unlocked again
            delay(200)
            assertEquals(0, count.get())
            val fresh = generation.get()
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION,
                isStillValid = { generation.get() == fresh }).join()
            assertEquals(1, count.get())
        } finally { owner.cancelAndJoin() }
    }

    @Test fun retryKeepsOriginalLeaseAndDoesNotRunAfterRevocation(): Unit = runBlocking {
        val owner = Job()
        val active = AtomicBoolean(true)
        val count = AtomicInteger()
        val scheduler = BitwardenSyncOrchestrator(xmlTestStrings("en"), CoroutineScope(coroutineContext + owner),
            config = SyncManagerConfig(retryBaseDelayMs = 80), isAutoSyncEnabled = { true },
            checkNetwork = { NetworkGateResult.ALLOWED }, isVaultUnlocked = { true },
            executeSync = { _, _ -> count.incrementAndGet(); SyncExecutionOutcome.RetryableError("synthetic") })
        try {
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = { active.get() }).join()
            active.set(false)
            delay(200)
            assertEquals(1, count.get())
            assertEquals(null, scheduler.statusByVault.value[1]?.nextRetryAt)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun newNotificationLeaseReplacesOlderQueuedLeaseWithoutLosingTheFreshEvent(): Unit = runBlocking {
        val owner = Job()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = AtomicBoolean(true)
        val fresh = AtomicBoolean(true)
        val count = AtomicInteger()
        val scheduler = scheduler(owner) { _, _ ->
            if (count.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
            success()
        }
        try {
            scheduler.requestSync(1, SyncTriggerReason.MANUAL)
            withTimeout(3000) { entered.await() }
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = { old.get() }).join()
            old.set(false)
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = { fresh.get() }).join()
            release.complete(Unit)
            withTimeout(3000) { while (count.get() < 2 || scheduler.statusByVault.value[1]?.isRunning == true) delay(10) }
            assertEquals(2, count.get())
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    @Test fun revokingRunningNotificationCancelsItsWorkButPreservesQueuedManualSync(): Unit = runBlocking {
        val owner = Job()
        val active = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val scheduler = scheduler(owner) { _, _ ->
            if (count.incrementAndGet() == 1) {
                assertNotNull(currentCoroutineContext()[BitwardenNotificationSyncLease])
                entered.complete(Unit)
                try { awaitCancellation() } finally { canceled.complete(Unit) }
            }
            assertEquals(null, currentCoroutineContext()[BitwardenNotificationSyncLease])
            success()
        }
        try {
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = { active.get() })
            withTimeout(3000) { entered.await() }
            scheduler.requestSync(1, SyncTriggerReason.MANUAL).join()
            active.set(false)
            withTimeout(3000) { canceled.await(); while (count.get() < 2 || scheduler.statusByVault.value[1]?.isRunning == true) delay(10) }
            assertEquals(2, count.get())
            assertEquals(SyncTriggerReason.MANUAL, scheduler.statusByVault.value[1]?.lastTriggerReason)
            assertFalse(scheduler.statusByVault.value[1]?.isRunning == true)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun queuedNotificationRechecksVaultLockBeforeStarting(): Unit = runBlocking {
        val owner = Job()
        val unlocked = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val scheduler = BitwardenSyncOrchestrator(xmlTestStrings("en"), CoroutineScope(coroutineContext + owner),
            isAutoSyncEnabled = { true }, checkNetwork = { NetworkGateResult.ALLOWED }, isVaultUnlocked = { unlocked.get() },
            executeSync = { _, _ -> count.incrementAndGet(); entered.complete(Unit); release.await(); success() })
        try {
            scheduler.requestSync(1, SyncTriggerReason.MANUAL)
            withTimeout(3000) { entered.await() }
            scheduler.requestSync(1, SyncTriggerReason.REMOTE_NOTIFICATION).join()
            unlocked.set(false); release.complete(Unit)
            withTimeout(3000) { while (scheduler.statusByVault.value[1]?.blockedReason != SyncBlockReason.VAULT_LOCKED) delay(10) }
            assertEquals(1, count.get())
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    private fun CoroutineScope.scheduler(owner: Job, execute: suspend (Long, Boolean) -> SyncExecutionOutcome) =
        BitwardenSyncOrchestrator(xmlTestStrings("en"), CoroutineScope(coroutineContext + owner),
            isAutoSyncEnabled = { true }, checkNetwork = { NetworkGateResult.ALLOWED },
            isVaultUnlocked = { true }, executeSync = execute)

    private fun success() = SyncExecutionOutcome.Success(0, 0, 0, 0, 0)
}
