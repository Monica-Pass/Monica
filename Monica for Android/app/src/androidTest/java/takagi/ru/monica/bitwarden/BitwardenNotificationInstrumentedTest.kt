package takagi.ru.monica.bitwarden

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.*
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real MessagePack hub -> production scheduler -> authenticated Repository HTTP -> encrypted Room records. */
@RunWith(AndroidJUnit4::class)
class BitwardenNotificationInstrumentedTest {
    @Test fun twoAccountsOnlyApplyTheirAuthenticatedRemoteEventsAndReconnectCatchesUp(): Unit = runBlocking {
        scenario { f ->
            val first = f.add("first")
            val second = f.add("second")
            f.listen(first); f.listen(second)
            f.await { f.title(first) == "initial-first" && f.title(second) == "initial-second" }
            val secondPulls = second.pulls.get()
            first.title = "remote-first"; first.revision = "2026-11-04T02:00:00Z"
            first.socket!!.send(BitwardenNotificationTestFrames.event(second.user).toByteString())
            delay(700)
            assertEquals("initial-first", f.title(first))
            repeat(15) { first.socket!!.send(BitwardenNotificationTestFrames.event(first.user).toByteString()) }
            f.await { f.title(first) == "remote-first" }
            assertEquals("initial-second", f.title(second)); assertEquals(secondPulls, second.pulls.get())
            assertEquals("secret-first", SecurityManager(f.context).decryptData(f.database.passwordEntryDao()
                .getByBitwardenCipherIdInVault(first.vault.id, "same-cipher-id")!!.password))
            first.title = "missed-while-offline"; first.revision = "2026-11-04T03:00:00Z"
            first.socket!!.close(1000, "synthetic reconnect")
            f.await { first.connections.get() >= 2 && f.title(first) == "missed-while-offline" }
            assertEquals("initial-second", f.title(second))
            assertEquals(1, f.database.passwordEntryDao().getAllByBitwardenCipherIdInVault(first.vault.id, "same-cipher-id").size)
        }
    }

    @Test fun lockAndForegroundCancellationRejectLateEventsAndRetainExistingRecords(): Unit = runBlocking {
        scenario { f ->
            val first = f.add("lock")
            val listener = f.listen(first)
            f.await { f.title(first) == "initial-lock" }
            val snapshot = requireNotNull(f.repository.getNotificationSession(first.vault.id))
            f.repository.forceLock(first.vault.id)
            assertFalse(f.repository.isNotificationSessionCurrent(snapshot))
            first.title = "late-locked-change"; first.revision = "2026-11-04T02:00:00Z"
            first.socket!!.send(BitwardenNotificationTestFrames.event(first.user).toByteString())
            delay(800)
            assertEquals("initial-lock", f.title(first))
            assertNull(f.repository.getNotificationSession(first.vault.id))
            listener.cancelAndJoin()
            val another = f.add("cancel")
            val anotherListener = f.listen(another)
            f.await { f.title(another) == "initial-cancel" }
            anotherListener.cancelAndJoin()
            val pullCount = another.pulls.get()
            another.title = "background-change"
            another.socket?.send(BitwardenNotificationTestFrames.event(another.user).toByteString())
            delay(700)
            assertEquals(pullCount, another.pulls.get()); assertEquals("initial-cancel", f.title(another))
        }
    }

    @Test fun disabledAutoSyncAndDeniedNetworkDoNotConnectAndUnauthorizedHubDoesNotPull(): Unit = runBlocking {
        scenario { f ->
            val first = f.add("gates")
            f.repository.isAutoSyncEnabled = false
            assertNull(f.repository.getNotificationSession(first.vault.id))
            f.repository.isAutoSyncEnabled = true
            f.networkAllowed.set(false)
            val listener = f.listen(first)
            delay(500)
            assertEquals(0, first.connections.get()); assertEquals(0, first.pulls.get())
            f.networkAllowed.set(true)
            first.denyHub = true
            f.await { first.rejections.get() > 0 }
            delay(500)
            assertEquals(0, first.pulls.get())
            assertNull(f.title(first))
            listener.cancelAndJoin()
        }
    }

    private suspend fun scenario(block: suspend (Fixture) -> Unit) {
        val originalUnlocked = SessionManager.isUnlocked.value
        val field = SecurityManager::class.java.getDeclaredField("processCachedMdk").apply { isAccessible = true }
        val original = field.get(null)
        val synthetic = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        field.set(null, synthetic)
        SessionManager.markUnlocked()
        val fixture = Fixture()
        try { block(fixture) } finally {
            fixture.close()
            field.set(null, original); synthetic.fill(0)
            if (!originalUnlocked) SessionManager.markLocked()
        }
    }

    internal class Account(val label: String, val user: String, val token: String, val key: SymmetricCryptoKey, val vault: BitwardenVault) {
        @Volatile var title = "initial-$label"
        @Volatile var revision = "2026-11-04T01:00:00Z"
        @Volatile var socket: WebSocket? = null
        @Volatile var denyHub = false
        val pulls = AtomicInteger()
        val connections = AtomicInteger()
        val closedConnections = AtomicInteger()
        val rejections = AtomicInteger()
    }

    internal class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = PasswordDatabase.getDatabase(context)
        val repository = BitwardenRepository.getInstance(context)
        private val originalAuto = repository.isAutoSyncEnabled
        private val originalWifi = repository.isSyncOnWifiOnly
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val server = MockWebServer()
        private val accounts = ConcurrentHashMap<String, Account>()
        val networkAllowed = AtomicBoolean(true)
        private val scheduler = BitwardenSyncOrchestrator(repository.strings, scope,
            isAutoSyncEnabled = { repository.isAutoSyncEnabled },
            checkNetwork = { if (networkAllowed.get()) NetworkGateResult.ALLOWED else NetworkGateResult.WIFI_REQUIRED },
            isVaultUnlocked = { repository.isVaultUnlocked(it) }, executeSync = { id, _ ->
                when (val result = repository.sync(id)) {
                    is BitwardenRepository.SyncResult.Success -> SyncExecutionOutcome.Success(result.appliedChangeCount,
                        result.availableOfflineCount, result.conflictCount, result.uploadFailedCount, result.skippedDueToLocalDirtyCount)
                    else -> SyncExecutionOutcome.FatalError("Synthetic sync failed: ${result.javaClass.simpleName}")
                }
            })
        init {
            repository.isAutoSyncEnabled = true
            repository.isSyncOnWifiOnly = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val parts = request.requestUrl!!.pathSegments
                    val account = accounts[parts.firstOrNull()] ?: return MockResponse().setResponseCode(404)
                    if (request.getHeader("Authorization") != "Bearer ${account.token}") return MockResponse().setResponseCode(401)
                    if (parts.drop(1) == listOf("notifications", "hub")) {
                        if (account.denyHub) { account.rejections.incrementAndGet(); return MockResponse().setResponseCode(401) }
                        check(request.requestUrl!!.query == null)
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            private val ended = AtomicBoolean(false)
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                account.socket = webSocket; account.connections.incrementAndGet()
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                check(text == BitwardenNotificationProtocol.HANDSHAKE)
                                webSocket.send("{}\u001e")
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                if (ended.compareAndSet(false, true)) account.closedConnections.incrementAndGet()
                            }
                            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                                if (ended.compareAndSet(false, true)) account.closedConnections.incrementAndGet()
                            }
                        })
                    }
                    val profile = """{"id":"${account.user}","email":"${account.vault.email}","securityStamp":"synthetic-stamp"}"""
                    if (parts.drop(1) == listOf("api", "accounts", "profile")) return json(profile)
                    if (parts.drop(1) == listOf("api", "sync")) {
                        account.pulls.incrementAndGet()
                        fun enc(value: String) = JsonPrimitive(BitwardenCrypto.encryptString(value, account.key))
                        val cipher = JsonObject(mapOf("id" to JsonPrimitive("same-cipher-id"), "type" to JsonPrimitive(1),
                            "name" to enc(account.title), "revisionDate" to JsonPrimitive(account.revision),
                            "login" to JsonObject(mapOf("username" to enc("user-${account.label}"), "password" to enc("secret-${account.label}")))))
                        return json("""{"profile":$profile,"ciphers":[$cipher],"folders":[],"sends":[]} """)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }
        suspend fun add(label: String): Account {
            val user = UUID.randomUUID().toString()
            val key = SymmetricCryptoKey(ByteArray(32) { label.length.toByte() }, ByteArray(32) { 79 })
            val base = server.url("/$label").toString()
            val value = BitwardenVault(email = "$user@example.invalid", userId = user, accountKey = user,
                serverUrl = base, apiUrl = "$base/api/", identityUrl = "$base/identity/", isLocked = false, isConnected = true,
                accessTokenExpiresAt = System.currentTimeMillis() + 300_000)
            val vault = value.copy(id = database.bitwardenVaultDao().insert(value))
            val account = Account(label, user, "synthetic-$user", key, vault)
            accounts[label] = account
            cache<SymmetricCryptoKey>("symmetricKeyCache")[vault.id] = key
            cache<String>("accessTokenCache")[vault.id] = account.token
            return account
        }
        fun listen(account: Account): Job = scope.launch {
            val listener = currentCoroutineContext()[Job]!!
            val generation = repository.getVaultSessionGeneration(account.vault.id)
            val appGeneration = SessionManager.lockGeneration.value
            val stillValid = {
                listener.isActive && networkAllowed.get() && repository.isAutoSyncEnabled &&
                    SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == appGeneration &&
                    repository.isVaultUnlocked(account.vault.id) && repository.getVaultSessionGeneration(account.vault.id) == generation
            }
            BitwardenNotificationMonitor(
                sessionProvider = { refresh -> if (networkAllowed.get()) repository.getNotificationSession(account.vault.id, refresh) else null },
                isCurrent = { networkAllowed.get() && repository.isNotificationSessionCurrent(it) },
                requestSync = { scheduler.requestSync(it, SyncTriggerReason.REMOTE_NOTIFICATION, isStillValid = stillValid) },
                retryDelay = { 100 },
            ).run()
        }
        suspend fun title(account: Account) = database.passwordEntryDao().getByBitwardenCipherIdInVault(account.vault.id, "same-cipher-id")?.title
        suspend fun await(check: suspend () -> Boolean) = withTimeout(15_000) { while (!check()) delay(50) }
        @Suppress("UNCHECKED_CAST")
        private fun <T> cache(name: String) = repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository) as ConcurrentHashMap<Long, T>
        private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            accounts.values.forEach { account ->
                repository.logout(account.vault.id)
                account.key.clear()
            }
            server.shutdown()
            repository.isAutoSyncEnabled = originalAuto; repository.isSyncOnWifiOnly = originalWifi
        }
    }
}
