package takagi.ru.monica.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SecureStoreReuseInstrumentedTest {
    @Test fun coldUiConstructorRefusesToOpenProviderSynchronously() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        PreparedSecureStore.invalidate()
        val reads = AtomicInteger()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                reads.incrementAndGet()
                throw java.security.ProviderException("synthetic-cold-ui-provider")
            }
        }
        withContext(Dispatchers.Main) {
            val failure = runCatching { SecurityManager(context) }.exceptionOrNull()
            assertTrue(failure is SecureStorageUnavailableException)
            assertEquals("SECURE_STORAGE_NOT_READY", (failure as SecureStorageUnavailableException).reason)
        }
        assertEquals("A UI cache miss must return to the bounded entry gate", 0, reads.get())
    }

    @Test fun selectorChangeRevokesCachedReadsAndRuntimeUnlockWithoutOpeningNewStore() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "reuse-selector-${UUID.randomUUID()}-"
        val directory = File(base.cacheDir, prefix)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = directory.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        try {
            val initial = SecureStorageStartup.prepareWithRetry(context) as SecureStartupResult.Ready
            val before = context.getSharedPreferences(SecurePreferencesStore.MONICA, 0).all.toMap()
            SessionManager.markUnlocked()
            val selector = File(directory, "files/local-vault-recovery/active-store.json")
            selector.parentFile!!.mkdirs()
            selector.writeText("{\"id\":\"${UUID.randomUUID()}\"}")
            val filesBefore = directory.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(directory).path to it.readBytes().toList() }
            assertTrue("Unknown storage must remain password-protected", initial.manager.isMasterPasswordSet())
            val failure = runCatching { initial.manager.getLocalPasswordAlgorithm() }.exceptionOrNull()
            assertTrue("A live manager must reject actual credential reads after another selector", failure is SecureStorageUnavailableException)
            assertFalse(SessionManager.isUnlocked.value)
            assertNull(PreparedSecureStore.cached(context))
            assertEquals(before, context.getSharedPreferences(SecurePreferencesStore.MONICA, 0).all)
            assertEquals("Invalidation must not open or rewrite any store", filesBefore,
                directory.walkTopDown().filter { it.isFile }
                    .associate { it.relativeTo(directory).path to it.readBytes().toList() })
        } finally {
            SessionManager.markLocked()
            PreparedSecureStore.invalidate()
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.MONICA)
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.BITWARDEN)
            check(directory.parentFile == base.cacheDir && directory.name == prefix)
            directory.deleteRecursively()
        }
    }

    @Test fun warmUiManagersDoNotReopenProvider() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "reuse-${UUID.randomUUID()}-"
        val directory = File(base.cacheDir, prefix)
        val providerReads = AtomicInteger()
        var slow = false
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply {
                dataDir = directory.absolutePath
            }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = base.getSharedPreferences(prefix + name, mode)
                return object : SharedPreferences by prefs {
                    override fun getAll(): MutableMap<String, *> {
                        if (slow && name == SecurePreferencesStore.MONICA) {
                            providerReads.incrementAndGet()
                            Thread.sleep(250)
                        }
                        return prefs.all
                    }
                }
            }
        }
        try {
            assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
            slow = true
            withContext(Dispatchers.Main) {
                repeat(3) { assertFalse(SecurityManager(context).isMasterPasswordSet()) }
            }
            assertEquals("Warm UI constructors must reuse validated storage without provider re-entry", 0, providerReads.get())
            assertFalse("Opening storage never unlocks the vault", SessionManager.isUnlocked.value)
        } finally {
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.MONICA)
            base.deleteSharedPreferences(prefix + SecurePreferencesStore.BITWARDEN)
            directory.delete()
        }
    }
}
