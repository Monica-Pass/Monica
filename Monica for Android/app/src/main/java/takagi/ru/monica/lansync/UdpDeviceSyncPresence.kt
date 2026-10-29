package takagi.ru.monica.lansync

import android.content.Context
import android.os.SystemClock
import android.net.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Lightweight foreground availability hints for hotspots whose system NSD cannot discover peers. */
internal class UdpDeviceSyncPresence(
    private val targets: () -> List<InetSocketAddress>,
    private val bindAddress: InetSocketAddress = InetSocketAddress(PORT),
    private val intervalMillis: Long = 15_000,
    private val expiryMillis: Long = 45_000,
    private val networkFor: (InetSocketAddress) -> Network? = { null }
) : DeviceSyncPresence {
    override val online = MutableStateFlow<Set<String>>(emptySet())
    @Volatile override var available = false
        private set
    @Volatile override var addresses = emptySet<String>()
        private set
    private var current: Session? = null
    private var attempted: Pair<String, Set<String>>? = null

    @Synchronized override fun start(deviceId: String, peers: Set<String>) {
        if (attempted == (deviceId to peers)) return
        stop()
        attempted = deviceId to peers
        if (peers.isEmpty()) return
        val socket = try { DatagramSocket(null) } catch (_: Exception) { return }
        try {
            socket.apply {
                reuseAddress = true; broadcast = true; soTimeout = 500
                bind(bindAddress)
            }
        } catch (_: Exception) { socket.close(); return }
        val session = Session(deviceId, peers, socket)
        current = session; available = true
        Thread({ run(session) }, "monica-local-presence").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        val session = current
        current = null
        attempted = null
        // A lost goodbye is harmless: remote hints also expire.
        session?.let { announce(it, "bye"); it.socket.close(); it.networkSockets.values.forEach(DatagramSocket::close) }
        online.value = emptySet(); addresses = emptySet(); available = false
    }

    private class Session(val id: String, val peers: Set<String>, val socket: DatagramSocket) {
        val seen = mutableMapOf<String, Long>()
        val replied = mutableMapOf<String, Long>()
        val endpoints = mutableMapOf<String, InetSocketAddress>()
        val networkSockets = java.util.concurrent.ConcurrentHashMap<Long, DatagramSocket>()
        @Volatile var destinations = emptyList<InetSocketAddress>()
    }

    @Synchronized private fun send(session: Session, kind: String, target: InetSocketAddress) {
        if (session.socket.isClosed) return
        if (DeviceSyncLocalNetwork.parseLocal(target.address?.hostAddress.orEmpty()) == null) return
        val bytes = "$PROTOCOL $kind ${session.id}".toByteArray(Charsets.US_ASCII)
        runCatching {
            val network = networkFor(target)
            val socket = if (network == null) session.socket else {
                session.networkSockets[network.networkHandle] ?: run {
                    if (session.networkSockets.size >= 8) return@runCatching
                    val created = DatagramSocket(0)
                    try { created.broadcast = true; created.soTimeout = 1; network.bindSocket(created) }
                    catch (failure: Exception) { created.close(); throw failure }
                    session.networkSockets[network.networkHandle] = created
                    created
                }
            }
            socket.send(DatagramPacket(bytes, bytes.size, target))
        }
    }

    private fun announce(session: Session, kind: String) {
        session.destinations.forEach { send(session, kind, it) }
    }

    @Synchronized private fun publish(session: Session, now: Long): Boolean {
        if (current !== session) return false
        session.seen.entries.removeAll { now - it.value >= expiryMillis }
        session.endpoints.keys.retainAll(session.seen.keys)
        online.value = session.seen.keys.toSet()
        addresses = session.endpoints.values.mapNotNull { it.address?.hostAddress }.toSet()
        return true
    }

    private fun run(session: Session) {
        var nextAnnouncement = 0L
        val buffer = ByteArray(160)
        try {
            while (!session.socket.isClosed) {
                val now = SystemClock.elapsedRealtime()
                if (!publish(session, now)) break
                if (now >= nextAnnouncement) {
                    session.destinations = (session.endpoints.values + runCatching { targets() }.getOrDefault(emptyList())).distinct().take(32)
                    val handles = session.destinations.mapNotNull { networkFor(it)?.networkHandle }.toSet()
                    session.networkSockets.entries.toList().forEach { (handle, socket) ->
                        if (handle !in handles) { session.networkSockets.remove(handle); socket.close() }
                    }
                    announce(session, "hello")
                    nextAnnouncement = now + intervalMillis
                }
                for (socket in listOf(session.socket) + session.networkSockets.values) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                if (packet.length >= buffer.size || DeviceSyncLocalNetwork.parseLocal(packet.address.hostAddress.orEmpty()) == null) continue
                val parts = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII).split(' ')
                if (parts.size != 3 || parts[0] != PROTOCOL || parts[1] !in setOf("hello", "reply", "bye")) continue
                val id = parts[2]
                if (id == session.id || id !in session.peers) continue
                val receivedAt = SystemClock.elapsedRealtime()
                if (parts[1] == "bye") session.seen.remove(id) else {
                    session.seen[id] = receivedAt
                    session.endpoints[id] = InetSocketAddress(packet.address, packet.port)
                }
                if (!publish(session, receivedAt)) break
                if (parts[1] == "hello" && receivedAt - (session.replied[id] ?: -1_000L) >= 1_000) {
                    session.replied[id] = receivedAt
                    send(session, "reply", InetSocketAddress(packet.address, packet.port))
                }
                }
            }
        } catch (_: Exception) {
            // NSD and direct QR connections remain usable if the local socket fails.
        } finally {
            session.socket.close()
            session.networkSockets.values.forEach(DatagramSocket::close)
            synchronized(this) {
                if (current === session) { current = null; online.value = emptySet(); addresses = emptySet(); available = false }
            }
        }
    }

    companion object {
        const val PORT = 53319
        private const val PROTOCOL = "monica-presence/1"
    }
}

/** NSD is preferred, with bounded local announcements for AP interfaces and blocked multicast. */
internal class ForegroundDeviceSyncPresence(
    context: Context,
    private val mdns: DeviceSyncPresence = AndroidDeviceSyncPresence(context),
    private val local: DeviceSyncPresence? = null
) : DeviceSyncPresence {
    private val app = context.applicationContext
    @Volatile private var remembered = emptySet<String>()
    private val udp = local ?: UdpDeviceSyncPresence({
        DeviceSyncLocalNetwork.targets(app, remembered).map { InetSocketAddress(it, UdpDeviceSyncPresence.PORT) }
    }, networkFor = { DeviceSyncLocalNetwork.networkFor(app, it.address) })
    override val online = MutableStateFlow<Set<String>>(emptySet())
    override val available get() = mdns.available || udp.available
    override val addresses get() = udp.addresses
    private var observation: Job? = null
    private var generation = 0L

    fun updateTargets(addresses: Set<String>) { remembered = addresses }

    @Synchronized override fun start(deviceId: String, peers: Set<String>) {
        mdns.start(deviceId, peers); udp.start(deviceId, peers)
        if (observation == null) {
            val epoch = ++generation
            observation = CoroutineScope(Dispatchers.IO).launch {
                combine(mdns.online, udp.online) { a, b -> a + b }.collect { found ->
                    synchronized(this@ForegroundDeviceSyncPresence) { if (generation == epoch) online.value = found }
                }
            }
        }
    }

    @Synchronized override fun stop() {
        generation++
        observation?.cancel(); observation = null
        mdns.stop(); udp.stop(); online.value = emptySet()
    }
}
