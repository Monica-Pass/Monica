package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class SecureStartupHandoffInstrumentedTest {
    @Test fun queuedHealthyStartupDoesNotObserveCompletedWorkerAsInProgress() = runBlocking {
        // Drain Application's initial maintenance startup before using isolated fixture storage.
        delay(3_000)
        awaitProviderExit()
        Fixture().use { fixture ->
            SecurityManager(fixture.context)
            val before = fixture.raw.all.toMap()
            SessionManager.markLocked()
            val entered = CompletableDeferred<Unit>()
            val releaseRead = CountDownLatch(1)
            val holdFirstRead = AtomicBoolean(true)
            val firstContext = object : ContextWrapper(fixture.context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val delegate = super.getSharedPreferences(name, mode)
                    if (name != SecurePreferencesStore.MONICA) return delegate
                    return object : SharedPreferences by delegate {
                        override fun getAll(): MutableMap<String, *> {
                            if (holdFirstRead.compareAndSet(true, false)) {
                                entered.complete(Unit)
                                check(releaseRead.await(10, TimeUnit.SECONDS))
                            }
                            return delegate.all
                        }
                    }
                }
            }
            // Two callers only: resume the eligible waiter synchronously at mutex handoff.
            // This forces the ordering that otherwise depends on a fast second dispatcher;
            // no production hooks or Keystore fault/alias changes are involved.
            val immediateResume = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
            }
            val first = async(immediateResume, start = CoroutineStart.UNDISPATCHED) {
                SecureStorageStartup.prepareWithRetry(firstContext)
            }
            var second: kotlinx.coroutines.Deferred<SecureStartupResult>? = null
            try {
                withTimeout(5_000) { entered.await() }
                val queued = async(immediateResume, start = CoroutineStart.UNDISPATCHED) {
                    SecureStorageStartup.prepareWithRetry(fixture.context)
                }
                second = queued
                assertFalse("The second caller must be queued behind the held healthy worker", queued.isCompleted)
                releaseRead.countDown()
                val firstResult = withTimeout(10_000) { first.await() }
                val secondResult = withTimeout(10_000) { queued.await() }
                assertReady("First caller", firstResult)
                assertReady("Queued caller", secondResult)
                assertFalse("A completed handoff must retire its worker slot", SecureStorageStartup.operationInProgress)
                assertTrue(SecureStorageStartup.readyForMaintenance)
                assertFalse("Opening storage does not authenticate either caller", SessionManager.isUnlocked.value)
                assertFalse(SecurityManager.hasRuntimeUnlockCache())
                assertEquals("Both healthy opens preserve the existing encrypted values", before, fixture.raw.all)
            } finally {
                releaseRead.countDown()
                first.cancelAndJoin()
                second?.cancelAndJoin()
                awaitProviderExit()
            }
        }
    }

    private fun assertReady(caller: String, result: SecureStartupResult) {
        val reason = (result as? SecureStartupResult.Blocked)?.failure?.reason ?: "none"
        assertTrue("$caller should be READY; blocked reason=$reason", result is SecureStartupResult.Ready)
    }

    private suspend fun awaitProviderExit() = withTimeout(10_000) {
        while (SecureStorageStartup.operationInProgress) delay(10)
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val prefix = "startup-handoff-${UUID.randomUUID()}-"
        private val directory = File(base.cacheDir, prefix)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = directory.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        val raw: SharedPreferences get() = context.getSharedPreferences(SecurePreferencesStore.MONICA, 0)

        override fun close() {
            SessionManager.markLocked()
            PreparedSecureStore.invalidate()
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.MONICA)
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.BITWARDEN)
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
            directory.deleteRecursively()
        }
    }
}
