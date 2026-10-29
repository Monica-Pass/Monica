package takagi.ru.monica.lansync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Availability hints only; Noise still authenticates every transfer. */
internal interface DeviceSyncPresence {
    val online: StateFlow<Set<String>>
    val available: Boolean
    val addresses: Set<String> get() = emptySet()
    fun start(deviceId: String, peers: Set<String>)
    fun stop()
}

/** Android manages mDNS presence. No application probe loop runs for absent peers. */
internal class AndroidDeviceSyncPresence(context: Context) : DeviceSyncPresence {
    private val manager = context.getSystemService(NsdManager::class.java)
    override val online = MutableStateFlow<Set<String>>(emptySet())
    @Volatile override var available = true
        private set
    private var current: Session? = null

    @Synchronized override fun start(deviceId: String, peers: Set<String>) {
        if (current?.deviceId == deviceId && current?.peers == peers) return
        stop()
        val session = Session(deviceId, peers)
        current = session
        available = manager != null
        if (manager == null) return
        try {
            manager.registerService(NsdServiceInfo().apply {
                serviceName = "monica-$deviceId"; serviceType = TYPE; port = 53318
            }, NsdManager.PROTOCOL_DNS_SD, session.registration)
            manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, session.discovery)
        } catch (_: RuntimeException) { failed(session) }
    }

    @Synchronized override fun stop() {
        current?.let { deactivate(it) }
        current = null; online.value = emptySet(); available = true
    }

    private fun deactivate(session: Session) {
        session.active = false
        runCatching { manager?.stopServiceDiscovery(session.discovery) }
        runCatching { manager?.unregisterService(session.registration) }
    }

    @Synchronized private fun failed(session: Session) {
        if (current !== session) return
        deactivate(session)
        online.value = emptySet(); available = false
        // Keep the failed session until foreground/settings change, avoiding a retry loop.
    }

    @Synchronized private fun found(session: Session, info: NsdServiceInfo, present: Boolean) {
        if (current !== session || !session.active || info.serviceType.trimEnd('.') != TYPE.trimEnd('.')) return
        // Android may append " (2)" to a colliding service name.
        val id = info.serviceName.removePrefix("monica-").substringBefore(" ")
        if (!info.serviceName.startsWith("monica-") || id !in session.peers) return
        if (present) session.services[info.serviceName] = id else session.services.remove(info.serviceName)
        online.value = session.services.values.toSet()
    }

    private inner class Session(val deviceId: String, val peers: Set<String>) {
        @Volatile var active = true
        val services = mutableMapOf<String, String>()
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (!active) runCatching { manager?.unregisterService(this) }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { failed(this@Session) }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                if (!active) runCatching { manager?.stopServiceDiscovery(this) }
            }
            override fun onServiceFound(info: NsdServiceInfo) { found(this@Session, info, true) }
            override fun onServiceLost(info: NsdServiceInfo) { found(this@Session, info, false) }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { failed(this@Session) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
    }

    private companion object { const val TYPE = "_monica-sync._udp." }
}
