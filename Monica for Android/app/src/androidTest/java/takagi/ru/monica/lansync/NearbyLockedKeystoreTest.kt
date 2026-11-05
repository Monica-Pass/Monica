package takagi.ru.monica.lansync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.security.SecurePreferencesStore
import takagi.ru.monica.security.SessionManager
import java.io.File
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real cold coordinator publication path without starting discovery or transfers. */
class NearbyLockedKeystoreTest {
    @Test fun lockedPublicationReadsPlainControlsWithoutOpeningFailedSecureStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val prefix = "nearby-locked-keystore-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix)
        val secureReads = AtomicInteger()
        val requestedPreferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            // A distinct directory prevents reuse of another context's PreparedSecureStore.
            override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
                dataDir = root.absolutePath
            }
            override fun getCacheDir() = File(root, "cache")
            override fun getFilesDir() = File(root, "files")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == SecurePreferencesStore.MONICA || name == SecurePreferencesStore.BITWARDEN ||
                    name.startsWith("monica_recovered_")) {
                    secureReads.incrementAndGet()
                    throw ProviderException("Synthetic unavailable secure-preference provider")
                }
                requestedPreferences += name
                return base.getSharedPreferences("$prefix-$name", mode)
            }
        }
        SessionManager.markLocked()
        val plain = context.getSharedPreferences("monica-device-sync", Context.MODE_PRIVATE)
        val coordinator = DeviceSyncCoordinator::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        fun field(name: String): Any? = DeviceSyncCoordinator::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(coordinator)
        val publish = DeviceSyncCoordinator::class.java.getDeclaredMethod("publish").apply { isAccessible = true }
        DeviceSyncCoordinator::class.java.getDeclaredField("phase").apply { isAccessible = true }
            .set(coordinator, "locked")
        try {
            publish.invoke(coordinator)
            assertTrue("Missing plain choice retains the normal enabled default", coordinator.state.value.enabled)
            assertFalse(coordinator.state.value.passkeyNoticeSuppressed)
            assertEquals("locked", coordinator.state.value.phase)
            assertEquals(0, secureReads.get())

            for (enabled in listOf(false, true, false)) {
                assertTrue(plain.edit().putBoolean("automatic-enabled", enabled)
                    .putBoolean("passkey-notice-suppressed", !enabled).commit())
                val before = plain.all.toMap()
                publish.invoke(coordinator)
                assertEquals("Publication must use the current plain automatic-sync choice", enabled,
                    coordinator.state.value.enabled)
                assertEquals(!enabled, coordinator.state.value.passkeyNoticeSuppressed)
                assertEquals("locked", coordinator.state.value.phase)
                assertFalse(SessionManager.isUnlocked.value)
                assertEquals("Publishing locked status must not attempt a secure read", 0, secureReads.get())
                assertEquals(before, plain.all)
            }
            assertFalse("The real coordinator must leave its security dependency unopened",
                (field("security\$delegate") as Lazy<*>).isInitialized())
            assertEquals(setOf("monica-device-sync"), requestedPreferences)
        } finally {
            (field("scope") as CoroutineScope).cancel()
            (field("networkObserver") as AutoCloseable).close()
            (field("presence") as DeviceSyncPresence).stop()
            instrumentation.runOnMainSync { }
            // Drain the fixture's initialization log and restore the process logger first.
            DeviceSyncDiagLogger.exportPersistedLogs()
            DeviceSyncDiagLogger.initialize(base)
            requestedPreferences.forEach { base.deleteSharedPreferences("$prefix-$it") }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile && root.name == prefix)
            root.deleteRecursively()
        }
    }
}
