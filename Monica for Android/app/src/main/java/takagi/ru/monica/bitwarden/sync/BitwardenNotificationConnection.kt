package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Ephemeral transport capability. No keys, ciphertext or token-bearing URL; never persisted or logged. */
internal class BitwardenNotificationSession(
    val vaultId: Long,
    val generation: Long,
    val appGeneration: Long,
    val accountFingerprint: String,
    val userId: String,
    val deviceId: String?,
    val url: HttpUrl,
    val accessToken: String,
    val expiresAt: Long,
    val client: OkHttpClient,
) {
    override fun toString(): String = "BitwardenNotificationSession(redacted)"
}

internal enum class BitwardenNotificationDisconnect { CLOSED, AUTHENTICATION, EXPIRED, INVALID_SESSION, PROTOCOL, TIMEOUT }

/** The only output is a coalesced request to the existing authenticated sync scheduler. */
internal class BitwardenNotificationConnection(
    private val handshakeTimeoutMs: Long = 15_000,
    private val silenceTimeoutMs: Long = 90_000,
    private val coalesceMs: Long = 350,
) {
    suspend fun run(
        session: BitwardenNotificationSession,
        isCurrent: suspend () -> Boolean,
        requestSync: () -> Unit,
        onConnected: () -> Unit = {},
    ): BitwardenNotificationDisconnect = coroutineScope {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return@coroutineScope BitwardenNotificationDisconnect.INVALID_SESSION
        val disconnected = CompletableDeferred<BitwardenNotificationDisconnect>()
        val requests = Channel<Unit>(Channel.CONFLATED)
        val connected = AtomicBoolean(false)
        val lastMessage = AtomicLong(System.nanoTime())
        val openedAt = System.nanoTime()
        val frames = BitwardenNotificationProtocol.Frames()
        var handshake = byteArrayOf()
        val client = session.client.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        // Authorization stays in the header. In particular, never put it in access_token query strings.
        val request = Request.Builder().url(session.url)
            .header("Authorization", "Bearer ${session.accessToken}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(BitwardenNotificationProtocol.HANDSHAKE)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > BitwardenNotificationProtocol.MAX_FRAME_BYTES) {
                    disconnected.complete(BitwardenNotificationDisconnect.PROTOCOL)
                } else receive(text.toByteArray(Charsets.UTF_8))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > BitwardenNotificationProtocol.MAX_FRAME_BYTES) {
                    disconnected.complete(BitwardenNotificationDisconnect.PROTOCOL)
                } else receive(bytes.toByteArray())
            }
            @Synchronized private fun receive(input: ByteArray) {
                if (disconnected.isCompleted) return
                try {
                    require(input.size <= BitwardenNotificationProtocol.MAX_FRAME_BYTES)
                    lastMessage.set(System.nanoTime())
                    var remaining = input
                    if (!connected.get()) {
                        require(handshake.size + input.size <= BitwardenNotificationProtocol.MAX_FRAME_BYTES)
                        handshake += input
                        val separator = handshake.indexOf(0x1e)
                        if (separator < 0) { require(handshake.size <= 4096); return }
                        require(separator <= 4096)
                        val response = Json.parseToJsonElement(handshake.copyOfRange(0, separator).toString(Charsets.UTF_8)).jsonObject
                        require(response.isEmpty()) { "Hub handshake rejected" }
                        remaining = handshake.copyOfRange(separator + 1, handshake.size)
                        handshake = byteArrayOf()
                        connected.set(true)
                        onConnected()
                        // Initial connection and every reconnection cover events missed while disconnected.
                        requests.trySend(Unit)
                    }
                    frames.accept(remaining) { body ->
                        when (BitwardenNotificationProtocol.decode(body, session.userId, session.deviceId)) {
                            BitwardenNotificationProtocol.Message.SYNC -> requests.trySend(Unit)
                            BitwardenNotificationProtocol.Message.CLOSE -> disconnected.complete(BitwardenNotificationDisconnect.CLOSED)
                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                    disconnected.complete(BitwardenNotificationDisconnect.PROTOCOL)
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected.complete(BitwardenNotificationDisconnect.CLOSED)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                disconnected.complete(BitwardenNotificationDisconnect.CLOSED)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected.complete(if (response?.code == 401 || response?.code == 403)
                    BitwardenNotificationDisconnect.AUTHENTICATION else BitwardenNotificationDisconnect.CLOSED)
                response?.close()
            }
        })
        val dispatch = launch {
            for (ignored in requests) {
                delay(coalesceMs)
                while (requests.tryReceive().isSuccess) { /* one sync covers a burst */ }
                if (!isCurrent()) {
                    disconnected.complete(BitwardenNotificationDisconnect.INVALID_SESSION)
                    break
                }
                if (!disconnected.isCompleted) requestSync()
            }
        }
        val watchdog = launch {
            var lastPing = openedAt
            while (isActive && !disconnected.isCompleted) {
                delay(minOf(1_000, handshakeTimeoutMs))
                val now = System.nanoTime()
                when {
                    !isCurrent() -> disconnected.complete(BitwardenNotificationDisconnect.INVALID_SESSION)
                    System.currentTimeMillis() >= session.expiresAt - 30_000 -> disconnected.complete(BitwardenNotificationDisconnect.EXPIRED)
                    !connected.get() && (now - openedAt) / 1_000_000 >= handshakeTimeoutMs -> disconnected.complete(BitwardenNotificationDisconnect.TIMEOUT)
                    connected.get() && (now - lastMessage.get()) / 1_000_000 >= silenceTimeoutMs -> disconnected.complete(BitwardenNotificationDisconnect.TIMEOUT)
                    connected.get() && (now - lastPing) / 1_000_000 >= 15_000 -> {
                        socket.send(BitwardenNotificationProtocol.ping.toByteString())
                        lastPing = now
                    }
                }
            }
        }
        try { disconnected.await() } finally {
            disconnected.complete(BitwardenNotificationDisconnect.CLOSED)
            socket.cancel()
            requests.close()
            dispatch.cancel()
            watchdog.cancel()
        }
    }
}
