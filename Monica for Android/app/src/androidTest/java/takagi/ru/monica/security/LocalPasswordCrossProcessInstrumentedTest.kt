package takagi.ru.monica.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real app :ime PID and Android encrypted preference files. No in-memory process simulation. */
class LocalPasswordCrossProcessInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun staleImeHandleIsRejectedAfterMainMigrationAndFreshImeOpensNewProfile() {
        val fixture = LocalKdfProcessFixture(context, UUID.randomUUID().toString())
        fixture.seedLegacy()
        try {
            val main = fixture.freshManager()
            assertTrue(main.unlockVaultWithPassword(fixture.password))
            val recordBefore = fixture.recordFile.readBytes()
            Remote(fixture.id).use { remote ->
                val opened = remote.call(LocalKdfProcessTestService.OPEN)
                assertRemote(opened)
                assertTrue(opened.getBoolean("success"))
                assertTrue(main.resetMasterPassword(fixture.password, fixture.password,
                    SecurityManager.LocalPasswordChangeAttempt()))
                val stale = remote.call(LocalKdfProcessTestService.READ_EXISTING)
                assertRemote(stale)
                assertFalse("Existing secondary preference/key handle must fail after publication", stale.getBoolean("success"))
                val fresh = remote.call(LocalKdfProcessTestService.OPEN)
                assertRemote(fresh)
                assertTrue("Fresh secondary process cache must load the new credential generation", fresh.getBoolean("success"))
                assertTrue(fixture.verifyContents(fixture.freshManager().also {
                    assertTrue(it.unlockVaultWithPassword(fixture.password))
                }))
                assertArrayEquals(recordBefore, fixture.recordFile.readBytes())
                receipt("stale-handle", opened, JSONObject().put("staleRejected", true).put("freshSucceeded", true))
            }
        } finally { fixture.cleanup() }
    }

    @Test fun twoRealProcessCredentialWritersCannotOverwriteEachOthersGenerationOrUnknownValue() {
        val fixture = LocalKdfProcessFixture(context, UUID.randomUUID().toString())
        fixture.seedLegacy()
        try {
            val main = fixture.freshManager()
            assertTrue(main.unlockVaultWithPassword(fixture.password))
            val recordBefore = fixture.recordFile.readBytes()
            Remote(fixture.id).use { remote ->
                val opened = remote.call(LocalKdfProcessTestService.OPEN)
                assertRemote(opened)
                assertTrue(opened.getBoolean("success"))
                val pool = Executors.newSingleThreadExecutor()
                try {
                    // Both capture and verify the same original credential generation before
                    // crossing the real file-lock publication boundary in separate processes.
                    val secondary = remote.send(LocalKdfProcessTestService.RACE)
                    val primary = pool.submit<Boolean> {
                        var callbacks = 0
                        main.resetMasterPassword(fixture.password, fixture.password,
                            SecurityManager.LocalPasswordChangeAttempt()) {
                            if (++callbacks == 2) fixture.waitForOtherWriter("main")
                        }
                    }
                    val primaryResult = primary.get(60, TimeUnit.SECONDS)
                    val secondaryReply = secondary.get(60, TimeUnit.SECONDS)
                    assertRemote(secondaryReply)
                    val secondaryResult = secondaryReply.getBoolean("success")
                    assertEquals("One coherent commit; the stale writer must reject", 1,
                        listOf(primaryResult, secondaryResult).count { it })
                    val fresh = fixture.freshManager()
                    assertTrue(fresh.unlockVaultWithPassword(fixture.password))
                    assertTrue(fixture.verifyContents(fresh))
                    assertArrayEquals(recordBefore, fixture.recordFile.readBytes())
                    assertTrue(remote.call(LocalKdfProcessTestService.OPEN).getBoolean("success"))
                    receipt("competing-writers", opened, JSONObject().put("mainCommitted", primaryResult)
                        .put("imeCommitted", secondaryResult).put("unknownValueRetained", true))
                } finally { pool.shutdownNow() }
            }
        } finally { fixture.cleanup() }
    }

    private fun assertRemote(reply: Bundle) {
        assertNotEquals(Process.myPid(), reply.getInt("pid"))
        assertEquals(Process.myUid(), reply.getInt("uid"))
        assertEquals(context.packageName + ":ime", reply.getString("process"))
    }

    private fun receipt(name: String, reply: Bundle, detail: JSONObject) {
        val dir = File(context.filesDir, "local-password-kdf-327").apply { mkdirs() }
        File(dir, "process-$name.json").writeText(detail.put("mainPid", Process.myPid())
            .put("secondaryPid", reply.getInt("pid")).put("uid", Process.myUid())
            .put("process", reply.getString("process")).put("scope", "Debug-only synthetic service in real app :ime; not a full keyboard UI test")
            .toString(2))
    }

    private inner class Remote(private val fixtureId: String) : AutoCloseable {
        private val thread = HandlerThread("kdf-process-replies").apply { start() }
        private val next = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
        private val replies = Messenger(Handler(thread.looper) { message ->
            pending.remove(message.arg1)?.complete(message.data)
            true
        })
        private val connected = CountDownLatch(1)
        private var remote: Messenger? = null
        private val intent = Intent().setComponent(ComponentName(context.packageName, LocalKdfProcessTestService::class.java.name))
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = Messenger(checkNotNull(service)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                pending.values.forEach { it.completeExceptionally(IllegalStateException("Fixture process disconnected")) }
            }
        }
        init {
            assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            assertTrue("Fixture service binding", connected.await(15, TimeUnit.SECONDS))
        }
        fun send(command: Int): CompletableFuture<Bundle> {
            val id = next.incrementAndGet()
            val future = CompletableFuture<Bundle>()
            pending[id] = future
            checkNotNull(remote).send(Message.obtain(null, command).apply {
                arg1 = id; replyTo = replies; data = Bundle().apply { putString("fixture", fixtureId) }
            })
            return future
        }
        fun call(command: Int): Bundle = send(command).get(60, TimeUnit.SECONDS)
        override fun close() {
            try { call(LocalKdfProcessTestService.CLOSE) }
            finally {
                context.unbindService(connection)
                context.stopService(intent)
                thread.quitSafely()
            }
        }
    }
}
