package takagi.ru.monica.lansync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.security.SessionManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic late-native-exit seam; no foreground ticks or credential stores. */
class NearbyRetirementCleanupTest {
    @Test fun lateWorkerIsReleasedAndCiphertextCleanedAfterBackgroundPauseWithoutTicks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionManager.markUnlocked()
        val coordinator = DeviceSyncCoordinator::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        fun get(name: String) = DeviceSyncCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(coordinator)
        fun set(name: String, value: Any?) { DeviceSyncCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(coordinator,value) }
        val gate = get("gate") as Mutex
        val scope = get("scope") as CoroutineScope
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            DeviceSyncCoordinator::class.java.getDeclaredMethod("ensureCacheRoot", kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(coordinator, continuation)
        }
        val root = get("root") as File
        val source = File(root,"out-synthetic").also { check(it.mkdirs()) }
        val incoming = File(root,"in-synthetic").also { check(it.mkdirs()) }
        File(source,"changes.mdbxsync").writeBytes(byteArrayOf(1,2,3))
        val retired = AtomicBoolean(false)
        val releases = AtomicInteger(0)
        val control: (JSONObject) -> JSONObject = { request ->
            when(request.getString("operation")) {
                "progress" -> JSONObject().put("phase",if(retired.get()) "cancelled" else "transferring")
                "cancel" -> JSONObject()
                "release" -> { check(retired.get()); releases.incrementAndGet(); JSONObject() }
                else -> error("Unexpected control operation")
            }
        }
        suspend fun pause() = gate.withLock {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                DeviceSyncCoordinator::class.java.getDeclaredMethod("pause",Boolean::class.javaPrimitiveType,kotlin.coroutines.Continuation::class.java)
                    .apply { isAccessible = true }.invoke(coordinator,false,continuation)
            }
        }
        try {
            set("running",Job()); set("transferControl",control)
            coordinator.setAppVisible(false)
            val snapshot = DevicePasswordExport("synthetic",source,"A".repeat(43))
            val type = Class.forName("takagi.ru.monica.lansync.DeviceSyncCoordinator\$Transfer")
            val transfer = type.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(777L,incoming,snapshot,false,"synthetic")
            @Suppress("UNCHECKED_CAST")
            (get("transfers") as MutableMap<String,Any>)["synthetic-peer"] = transfer
            @Suppress("UNCHECKED_CAST")
            (get("snapshots") as MutableSet<File>).add(source)
            set("snapshot",snapshot)
            pause()
            assertTrue("Source must stay owned while the native worker is active",source.exists())
            assertEquals(0,releases.get())
            assertTrue(type.getDeclaredField("discardResume").apply { isAccessible = true }.getBoolean(transfer))
            // Simulate a quick return before the old native worker exits; discard is sticky.
            gate.withLock { coordinator.setAppVisible(true); set("paused",false) }
            retired.set(true)
            withTimeout(10_000) { while(gate.withLock { (get("retiring") as Map<*,*>).isNotEmpty() }) delay(25) }
            assertEquals(1,releases.get())
            assertFalse(source.exists()); assertFalse(incoming.exists())
            gate.withLock { assertNull(get("retirementCleanup")) }
        } finally {
            retired.set(true)
            pause(); scope.cancel(); SessionManager.markLocked()
            (get("cacheLease") as? AutoCloseable)?.close()
            check(root.canonicalFile.parentFile == File(context.cacheDir,"device-sync").canonicalFile)
            root.deleteRecursively()
        }
    }
}
