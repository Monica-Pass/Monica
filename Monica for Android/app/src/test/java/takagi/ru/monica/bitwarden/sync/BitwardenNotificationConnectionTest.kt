package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BitwardenNotificationConnectionTest {
    private fun session(server: MockWebServer) = BitwardenNotificationSession(1, 1, 1, "fingerprint", "first", "local-device",
        server.url("/sub/notifications/hub"), "synthetic-secret-token", System.currentTimeMillis() + 300_000, OkHttpClient())

    @Test fun actualHubBurstPerformsOneAuthenticatedHttpPullAndWrongAccountDoesNotPull(): Unit = runBlocking {
        MockWebServer().use { server ->
            val socket = CompletableDeferred<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    assertEquals(BitwardenNotificationProtocol.HANDSHAKE, text)
                    webSocket.send("{}\u001e")
                }
            }))
            val capability = session(server)
            val pulls = AtomicInteger()
            val current = AtomicBoolean(true)
            val job = launch {
                BitwardenNotificationConnection(coalesceMs = 50).run(capability, { current.get() }, {
                    launch(Dispatchers.IO) {
                        capability.client.newCall(Request.Builder().url(server.url("/sync"))
                            .header("Authorization", "Bearer ${capability.accessToken}").build()).execute().use {
                            assertEquals("remote-record", it.body!!.string()); pulls.incrementAndGet()
                        }
                    }
                })
            }
            try {
                server.enqueue(MockResponse().setBody("remote-record"))
                val hub = withTimeout(5_000) { socket.await() }
                withTimeout(5_000) { while (pulls.get() != 1) delay(10) }
                val upgrade = withContext(Dispatchers.IO) { server.takeRequest() }
                val initialPull = withContext(Dispatchers.IO) { server.takeRequest() }
                assertEquals("/sub/notifications/hub", upgrade.path)
                assertNull(upgrade.requestUrl!!.query)
                assertEquals("Bearer synthetic-secret-token", upgrade.getHeader("Authorization"))
                assertEquals("/sync", initialPull.path)
                hub.send(BitwardenNotificationTestFrames.event("second").toByteString())
                hub.send(BitwardenNotificationTestFrames.event("first", device = "local-device").toByteString())
                delay(150); assertEquals(1, pulls.get())
                server.enqueue(MockResponse().setBody("remote-record"))
                repeat(20) { hub.send(BitwardenNotificationTestFrames.event("first").toByteString()) }
                withTimeout(5_000) { while (pulls.get() != 2) delay(10) }
                delay(150); assertEquals(2, pulls.get())
                current.set(false)
                hub.send(BitwardenNotificationTestFrames.event("first").toByteString())
                withTimeout(5_000) { job.join() }
                assertEquals(2, pulls.get())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun unauthorizedHandshakeReturnsRefreshReasonWithoutLeakingTokenInUrl(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val result = BitwardenNotificationConnection().run(session(server), { true }, { fail("401 cannot request sync") })
            assertEquals(BitwardenNotificationDisconnect.AUTHENTICATION, result)
            assertNull(server.takeRequest().requestUrl!!.query)
        }
    }

    @Test fun handshakeTimeoutAndCancellationCloseActualSockets(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val result = withTimeout(5_000) {
                BitwardenNotificationConnection(handshakeTimeoutMs = 100).run(session(server), { true }, { fail("No handshake") })
            }
            assertEquals(BitwardenNotificationDisconnect.TIMEOUT, result)
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            }))
            val job = launch { BitwardenNotificationConnection().run(session(server), { true }, {}) }
            withTimeout(5_000) { opened.await() }
            job.cancelAndJoin()
            withTimeout(5_000) { closed.await() }
        }
    }

    @Test fun redirectDoesNotForwardCredentialsToDifferentServer(): Unit = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/hub")))
            assertEquals(BitwardenNotificationDisconnect.CLOSED, BitwardenNotificationConnection().run(session(first), { true }, {}))
            assertEquals(0, second.requestCount)
        } }
    }

    @Test fun monitorReconnectsAndRequestsCatchupEachTime(): Unit = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("{}\u001e") }
            })) }
            val pulls = AtomicInteger()
            val connections = AtomicInteger()
            val session = session(server)
            val job = launch {
                BitwardenNotificationMonitor(sessionProvider = { connections.incrementAndGet(); session },
                    isCurrent = { true }, requestSync = { pulls.incrementAndGet() },
                    connection = BitwardenNotificationConnection(silenceTimeoutMs = 150, handshakeTimeoutMs = 50, coalesceMs = 10),
                    retryDelay = { 10 }).run()
            }
            try {
                withTimeout(5_000) { while (pulls.get() < 2) delay(10) }
                assertEquals(2, connections.get())
            } finally { job.cancelAndJoin() }
        }
    }
}
