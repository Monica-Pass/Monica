package takagi.ru.monica.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.SystemClock
import androidx.room.InvalidationTracker
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.LocalVaultRecovery
import takagi.ru.monica.security.SecurePreferencesStore
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import java.io.File
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real scheduler/Room callback boundary with deterministic provider faults; no key mutation. */
class ChangeTriggeredBackupKeystoreTest {
    @Test
    fun providerFailureDoesNotEscapeRoomCallbackOrReadStorageOnMain() = runBlocking {
        val fixture = Fixture(blockFirstRead = false)
        try {
            fixture.startFromHealthyStorage()
            fixture.invalidateOnMain()
            withTimeout(5_000) { fixture.entered.await() }
            fixture.awaitIdle()

            assertTrue("The callback must actually reach the failing provider", fixture.reads.get() > 0)
            assertFalse("Provider access must remain off the Room callback's main thread", fixture.readOnMain.get())
            fixture.assertPreservedAndLocked()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repeatedInvalidationsShareOnePendingReadAndStopRevokesItsRetry() = runBlocking {
        val fixture = Fixture(blockFirstRead = true)
        try {
            fixture.startFromHealthyStorage()
            fixture.invalidateOnMain()
            withTimeout(5_000) { fixture.entered.await() }
            assertTrue("The provider fixture must still be waiting", fixture.release.count == 1L)
            assertTrue("The scheduler must retain its one pending check", fixture.isChecking())

            fixture.invalidateOnMain(repetitions = 24)
            assertEquals("Repeated callbacks must share the held provider read", 1, fixture.reads.get())
            assertFalse("A slow provider must not run on the callback's main thread", fixture.readOnMain.get())

            fixture.stop()
            withTimeout(5_000) { while (fixture.isChecking()) delay(10) }
            assertTrue("The cancelled native/provider slot must remain held until the read exits",
                SecureStorageStartup.operationInProgress)
            fixture.invalidateOnMain(repetitions = 4)
            assertEquals("A stopped observer must not start another provider read", 1, fixture.reads.get())

            fixture.release.countDown()
            fixture.awaitIdle()
            assertEquals("Stopping must revoke retries after the held read finally fails", 1, fixture.reads.get())
            fixture.assertPreservedAndLocked()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(private val blockFirstRead: Boolean) {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directoryName = "backup-keystore-callback-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, directoryName).apply { check(mkdirs()) }
        val reads = AtomicInteger()
        val readOnMain = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        private var scheduler: ChangeTriggeredBackupScheduler? = null
        private var observer: InvalidationTracker.Observer? = null
        private var before: Map<String, PreferenceSnapshot> = emptyMap()

        private val faultContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
                dataDir = directory.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != SecurePreferencesStore.MONICA) return base.getSharedPreferences(name, mode)
                val attempt = reads.incrementAndGet()
                val onMain = Looper.myLooper() == Looper.getMainLooper()
                if (onMain) readOnMain.set(true)
                entered.complete(Unit)
                // Fail immediately if an old implementation calls the provider on Main: the
                // negative control must report the escaping exception without freezing the UI.
                if (blockFirstRead && attempt == 1 && !onMain) {
                    check(release.await(10, TimeUnit.SECONDS)) { "Fixture provider was not released" }
                }
                throw ProviderException("Synthetic backup callback provider failure")
            }
        }

        suspend fun startFromHealthyStorage() {
            withTimeout(5_000) { while (SecureStorageStartup.operationInProgress) delay(10) }
            assertFalse("This callback test requires no concurrent bulk-import suppression",
                ChangeTriggeredBackupScheduler.isSuppressed())
            val activeName = LocalVaultRecovery(base).activeStore()?.first ?: SecurePreferencesStore.MONICA
            assertTrue("Run this regression with the existing healthy test-user vault",
                base.getSharedPreferences(activeName, Context.MODE_PRIVATE).all.isNotEmpty())
            before = setOf(SecurePreferencesStore.MONICA, activeName).associateWith { snapshot(it) }
            SessionManager.markLocked()
            assertTrue("The real healthy store must enable the maintenance gate before fault injection",
                SecureStorageStartup.prepareWithRetry(base) is SecureStartupResult.Ready)
            assertTrue(SecureStorageStartup.readyForMaintenance)
            assertFalse(SessionManager.isUnlocked.value)

            // Resolve the existing application singletons using the real context first. The
            // fault context is solely a provider/cache-miss fixture, not another database.
            WorkManager.getInstance(base)
            PasswordDatabase.getDatabase(base)
            val created = ChangeTriggeredBackupScheduler(faultContext)
            scheduler = created
            observer = ChangeTriggeredBackupScheduler::class.java.getDeclaredField("observer").run {
                isAccessible = true
                get(created) as InvalidationTracker.Observer
            }
            created.start()
            assertEquals("Registering the observer must not read secure preferences", 0, reads.get())
        }

        suspend fun invalidateOnMain(repetitions: Int = 1) = withContext(Dispatchers.Main) {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching {
                repeat(repetitions) { checkNotNull(observer).onInvalidated(setOf("password_entries")) }
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("Provider failure must not escape InvalidationTracker.Observer.onInvalidated", result.isSuccess)
            assertTrue("Room callbacks must return without waiting for the provider (elapsed ${elapsed}ms)", elapsed < 500)
        }

        fun isChecking(): Boolean {
            val current = scheduler ?: return false
            return ChangeTriggeredBackupScheduler::class.java.getDeclaredField("checking").run {
                isAccessible = true
                (get(current) as AtomicBoolean).get()
            }
        }

        suspend fun awaitIdle() = withTimeout(5_000) {
            while (isChecking() || SecureStorageStartup.operationInProgress) delay(10)
        }

        fun stop() { scheduler?.stop() }

        fun assertPreservedAndLocked() {
            assertFalse("A failed callback check must keep maintenance disabled", SecureStorageStartup.readyForMaintenance)
            assertFalse("A provider failure must not unlock the main session", SessionManager.isUnlocked.value)
            assertFalse("A provider failure must not publish runtime key material", SecurityManager.hasRuntimeUnlockCache())
            before.forEach { (name, original) ->
                val after = snapshot(name)
                assertEquals("Original encrypted preference values must be preserved", original.values, after.values)
                assertEquals("Original preference file presence must be preserved", original.files.keys, after.files.keys)
                original.files.forEach { (suffix, bytes) ->
                    assertArrayEquals("Original encrypted preference XML must remain byte-for-byte unchanged",
                        bytes, after.files.getValue(suffix))
                }
            }
            assertTrue("The failing context must never create a store or selector",
                directory.walkTopDown().none { it.isFile })
        }

        private fun snapshot(name: String): PreferenceSnapshot {
            val raw = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            val file = File(base.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val files = listOf("", ".bak").mapNotNull { suffix ->
                File(file.path + suffix).takeIf { it.exists() }?.let { suffix to it.readBytes() }
            }.toMap()
            return PreferenceSnapshot(raw.all.toMap(), files)
        }

        suspend fun close() {
            release.countDown()
            try {
                scheduler?.stop()
                withTimeout(5_000) {
                    // Old APKs have no checking field. Cleanup must preserve the callback
                    // assertion as their failure, rather than mask it with reflection failure.
                    while (runCatching { isChecking() }.getOrDefault(false) || SecureStorageStartup.operationInProgress) delay(10)
                }
            } finally {
                check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == directoryName)
                check(directory.deleteRecursively())
            }
        }
    }

    private class PreferenceSnapshot(val values: Map<String, *>, val files: Map<String, ByteArray>)
}
