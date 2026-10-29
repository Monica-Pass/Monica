package takagi.ru.monica.lansync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import takagi.ru.monica.data.isUsable
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import java.io.File
import java.util.UUID

internal data class DeviceSyncPeerState(val deviceId: String, val name: String, val status: String, val lastSyncedAt: Long)
internal data class DeviceDeletionReview(val id: String, val deviceId: String, val deviceName: String, val count: Int)
internal data class DeviceSyncProgress(val stage: String, val completed: Long = 0, val total: Long = 0, val bytes: Long = 0, val totalBytes: Long = 0)
internal data class DeviceSyncState(
    val enabled: Boolean = true, val intervalSeconds: Int = 0, val peers: List<DeviceSyncPeerState> = emptyList(),
    val phase: String = "idle", val pairing: Boolean = false, val code: String = "",
    val invitations: List<String> = emptyList(), val error: String? = null,
    val failureStage: String = "", val connectionAttempts: Int = 0, val failureKind: String = "",
    val failureOperation: String = "", val progress: DeviceSyncProgress? = null, val failureRecordName: String = "",
    val mode: DeviceSyncMode = DeviceSyncMode.BIDIRECTIONAL,
    val passkeyReview: DevicePasskeySyncReview? = null,
    val databaseId: Long? = null, val manualSync: Boolean = false,
    val activeConnections: Int = 0, val listeningPeers: Int = 0,
    val lastAttemptAt: Long = 0, val lastDiscoveryAt: Long = 0,
    val discoveredPeers: Int = 0, val retryInSeconds: Int = 0,
    val passkeyNoticeSuppressed: Boolean = false,
    val passkeyDiagnosticIssues: List<DevicePasskeySyncIssue>? = null,
    val checkingPasskeys: Boolean = false, val passkeyDiagnosticFailed: Boolean = false,
    val appVisible: Boolean = true, val nearbyAppCount: Int = 0, val nearbyDiscoveryAvailable: Boolean = true,
    val deletionReview: DeviceDeletionReview? = null
)

/** App-owned coordinator. Leaving settings does not cancel remembered-device synchronization. */
internal class DeviceSyncCoordinator private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val appVisible = MutableStateFlow(DeviceSyncService.isAppVisible)
    private var presence: DeviceSyncPresence = ForegroundDeviceSyncPresence(app)
    private var discoverPeers: (JSONObject) -> JSONObject = RustLanSync::request
    private val security by lazy { SecurityManager(app) }
    private val store by lazy { DeviceSyncPreferenceStore(app, security) }
    private val dataProvider = lazy { DevicePasswordSyncProvider(app, security, store).also { provider -> provider.onProgress = { updateProgress(it) } } }
    private val data by dataProvider
    private val root = File(app.cacheDir, "device-sync/${UUID.randomUUID()}")
    private val mutableState = MutableStateFlow(DeviceSyncState())
    val state = mutableState.asStateFlow()
    private var preferences: DeviceSyncPreferences? = null
    private var snapshot: DevicePasswordExport? = null
    private val snapshots = mutableSetOf<File>()
    private val transfers = mutableMapOf<String, Transfer>()
    private val retiring = mutableMapOf<Long, Transfer>()
    private val attempts = mutableMapOf<String, Long>()
    private val retry = mutableMapOf<String, Pair<Int, Long>>()
    private val statuses = mutableMapOf<String, String>()
    private val force = mutableSetOf<String>()
    private val manualPeers = mutableSetOf<String>()
    private var manualDeadline = 0L
    private var manualConnectionReady = false
    private var pairing = false
    private var paused = true
    private var phase = "idle"
    private var code = ""
    private var invitations = emptyList<String>()
    private var error: String? = null
    private var failureStage = ""
    private var connectionAttempts = 0
    private var failureKind = ""
    private var failureOperation = ""
    private var failureRecordName = ""
    private var loadAfter = 0L
    private var running: Job? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var multicastAttempted = false
    private var networkSignature: String? = null
    private var dataProgress: DeviceSyncProgress? = null
    private var lastAttemptAt = 0L
    private var lastDiscoveryAt = 0L
    private var discoveredPeers = 0
    private val activeHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val receiveOnlyRevision = "receive-only-v1"
    private val passkeyConsent = DevicePasskeySyncConsent()
    private var passkeyDiagnosticIssues: List<DevicePasskeySyncIssue>? = null
    private var checkingPasskeys = false
    private var passkeyDiagnosticFailed = false
    private val cancellationRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val manualGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var activeSyncWork: Job? = null

    private val deletionDecision = java.util.concurrent.atomic.AtomicReference<Pair<DeviceDeletionReview, CompletableDeferred<Boolean>>?>(null)

    // This callback must not wait for gate: receive holds gate while awaiting it.
    fun confirmReceivedDeletions(review: DeviceDeletionReview, include: Boolean) {
        if (SessionManager.isSessionExpired()) { SessionManager.markLocked(); return }
        if (!SessionManager.isUnlocked.value || !appVisible.value || cancellationRequested.get()) return
        val pending = deletionDecision.get() ?: return
        if (pending.first == review) pending.second.complete(include)
    }

    private suspend fun reviewReceivedDeletions(peer: JSONObject, count: Int): Boolean {
        currentCoroutineContext().ensureActive()
        check(SessionManager.isUnlocked.value && appVisible.value) { "device_sync_locked" }
        val review = DeviceDeletionReview(UUID.randomUUID().toString(), peer.getString("device_id"), peer.getString("name"), count)
        val pending = review to CompletableDeferred<Boolean>()
        check(deletionDecision.compareAndSet(null, pending))
        phase = "deletion_review"; dataProgress = null; publish()
        return try {
            withTimeoutOrNull(90_000) { pending.second.await() } ?: throw LanSyncException("deletion_review_expired")
        } finally {
            deletionDecision.compareAndSet(pending, null)
            if (phase == "deletion_review") phase = "applying"
            publish()
        }
    }

    init { DeviceSyncDiagLogger.initialize(app) }

    private class Transfer(val handle: Long, val directory: File, val snapshot: DevicePasswordExport,
        val enrollment: Boolean, val advertisement: String) {
        var applyAttempted = false
        var peer: JSONObject? = null
        var stage = "connecting"
        var attempts = 0
        var remoteAddress = ""
        var applyFailure: Exception? = null
        var rejectionDeadline = 0L
        var syncRequest = 0L
    }

    fun start() {
        if (running != null) return
        val active = combine(SessionManager.isUnlocked, appVisible) { unlocked, visible -> unlocked && visible }.distinctUntilChanged()
        scope.launch {
            active.collect { foreground ->
                if (!foreground) activeHandles.forEach { handle -> runCatching { request("cancel", handle) } }
            }
        }
        running = scope.launch {
            active.collectLatest { foreground ->
                if (!foreground) {
                    gate.withLock { pause(locked = !SessionManager.isUnlocked.value); publish() }
                } else {
                    try {
                        while (currentCoroutineContext().isActive) {
                            gate.withLock { tick() }
                            delay(500)
                        }
                    } finally {
                        withContext(NonCancellable) { gate.withLock { pause(locked = !SessionManager.isUnlocked.value); publish() } }
                    }
                }
            }
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
        if (!visible) presence.stop()
        mutableState.update { it.copy(appVisible = visible) }
    }

    private fun publish() {
        val settings = preferences
        val now = SystemClock.elapsedRealtime()
        val retryAt = maxOf(loadAfter, retry.values.map { it.second }.filter { it > now }.minOrNull() ?: 0)
        val next = DeviceSyncState(automaticSyncEnabled(),
            settings?.intervalSeconds ?: state.value.intervalSeconds,
            settings?.peers?.map {
                val cached = statuses[it.deviceId] ?: "offline"
                val status = if (error != null && failureStage == "preparing") "preparation_failed"
                    else if (!automaticSyncEnabled() && it.deviceId !in manualPeers && it.deviceId !in transfers) "manual"
                    else if (it.lastSyncedAt == 0L && cached in listOf("offline", "waiting")) "first_sync_pending" else cached
                DeviceSyncPeerState(it.deviceId, it.name, status, it.lastSyncedAt)
            } ?: state.value.peers,
            phase, pairing, code, invitations, error, failureStage,
            if (error != null) connectionAttempts else transfers.values.maxOfOrNull { it.attempts } ?: 0,
            failureKind, failureOperation, dataProgress, failureRecordName,
            settings?.mode ?: state.value.mode, passkeyConsent.pending,
            settings?.databaseId ?: if (settings == null) state.value.databaseId else null, manualPeers.isNotEmpty(),
            activeConnections = transfers.values.count { it.stage in setOf("authenticating", "checking", "sending", "receiving", "transferring", "received", "applying", "remembering", "confirming") },
            listeningPeers = transfers.values.count { it.stage == "waiting" },
            lastAttemptAt = lastAttemptAt, lastDiscoveryAt = lastDiscoveryAt, discoveredPeers = discoveredPeers,
            retryInSeconds = if (automaticSyncEnabled() || manualPeers.isNotEmpty()) ((retryAt - now).coerceAtLeast(0) + 999).div(1000).toInt() else 0,
            passkeyDiagnosticIssues = passkeyDiagnosticIssues, checkingPasskeys = checkingPasskeys,
            passkeyDiagnosticFailed = passkeyDiagnosticFailed,
            appVisible = appVisible.value, nearbyAppCount = presence.online.value.size,
            nearbyDiscoveryAvailable = presence.available, deletionReview = deletionDecision.get()?.first)
        // Re-read inside the CAS loop so a concurrent tap wins over old progress.
        mutableState.update { next.copy(enabled = automaticSyncEnabled(), appVisible = appVisible.value,
            passkeyNoticeSuppressed = store.passkeyNoticeSuppressed(),
            passkeyReview = next.passkeyReview.takeUnless { store.passkeyNoticeSuppressed() }).let {
                if (cancellationRequested.get()) it.copy(phase = "cancelling", progress = null, error = null,
                    manualSync = true, passkeyReview = null, deletionReview = null) else it
            } }
        DeviceSyncService.update(app, mutableState.value)
    }

    private fun automaticSyncEnabled(): Boolean = store.automaticSyncEnabled(preferences?.enabled ?: state.value.enabled)

    private fun updateProgress(progress: DeviceSyncProgress) {
        if (cancellationRequested.get()) return
        if (checkingPasskeys) return // Inspecting local restrictions is not a sync attempt.
        dataProgress = progress; phase = progress.stage; publish()
    }

    private fun settings(): DeviceSyncPreferences {
        if (SessionManager.isSessionExpired()) {
            if (SessionManager.isUnlocked.value) SessionManager.markLocked()
            throw CancellationException("device_sync_locked")
        }
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        return (preferences ?: store.load().also { loaded ->
            preferences = loaded
            if (phase == "locked") phase = "idle"
        }).also { it.enabled = automaticSyncEnabled() }
    }

    private fun action(block: suspend () -> Unit) {
        start()
        scope.launch {
            gate.withLock {
                try { if (SessionManager.isUnlocked.value) block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { recordFailure(failure, if (pairing) phase else "preparing") }
                finally { publish() }
            }
        }
    }

    fun setInterval(seconds: Int) = action {
        settings().setInterval(seconds)
        settings().peers.forEach { force += it.deviceId }
        store.save(settings())
    }

    fun setEnabled(enabled: Boolean) {
        if (!SessionManager.isUnlocked.value) return
        store.setAutomaticSyncEnabled(enabled)
        mutableState.update { it.copy(enabled = automaticSyncEnabled()) }
        action {
            // Queued rescheduling always uses the latest choice.
            if (settings().enabled) {
                loadAfter = 0; retry.clear()
                settings().peers.forEach { force += it.deviceId }
            }
        }
    }

    fun syncNow() {
        if (cancellationRequested.get()) return
        val generation = manualGeneration.get()
        action { if (generation == manualGeneration.get()) beginManualSync() }
    }

    private fun beginManualSync() {
        if (!appVisible.value || cancellationRequested.get()) return
        val settings = settings()
        if (pairing || settings.peers.isEmpty()) return
        settings.syncRequest = Math.addExact(settings.syncRequest, 1)
        manualPeers += settings.peers.map { it.deviceId }
        manualDeadline = SystemClock.elapsedRealtime() + 120_000
        manualConnectionReady = false
        settings.peers.forEach { force += it.deviceId; retry.remove(it.deviceId) }
        error = null; failureStage = ""; failureKind = ""; failureOperation = ""; connectionAttempts = 0
        loadAfter = 0; phase = "preparing"
        store.save(settings)
    }

    fun cancelSync() {
        if (!SessionManager.isUnlocked.value || !cancellationRequested.compareAndSet(false, true)) return
        manualGeneration.incrementAndGet()
        // Feedback and cooperative cancellation must not queue behind export/import.
        mutableState.update { it.copy(phase = "cancelling", progress = null, error = null,
            manualSync = true, passkeyReview = null, deletionReview = null) }
        activeSyncWork?.cancel(CancellationException("device_sync_cancelled"))
        scope.launch {
            // Stop native workers even while the coordinator's data gate is held.
            activeHandles.forEach { handle -> runCatching { request("cancel", handle) } }
            gate.withLock {
                try {
                    manualPeers.clear(); force.clear(); retry.clear()
                    pause(locked = !SessionManager.isUnlocked.value)
                    error = null; failureStage = ""; failureKind = ""; failureOperation = ""; failureRecordName = ""
                    // Avoid immediately recreating the cancelled automatic run.
                    loadAfter = SystemClock.elapsedRealtime() + 10_000
                    if (SessionManager.isUnlocked.value && appVisible.value) phase = "cancelled"
                } finally {
                    cancellationRequested.set(false)
                    publish()
                }
            }
        }
    }

    fun setDatabase(databaseId: Long?) {
        if (deletionDecision.get() != null) cancelSync()
        changeDatabase(databaseId)
    }

    private fun changeDatabase(databaseId: Long?) = action {
        val settings = settings()
        if (settings.databaseId == databaseId) return@action
        if (pairing) throw LanSyncException("device_sync_pairing_busy")
        if (databaseId != null) {
            val selected = takagi.ru.monica.data.PasswordDatabase.getDatabase(app).localMdbxDatabaseDao().getDatabaseById(databaseId)
            if (selected == null || !selected.isUsable) throw LanSyncException("sync_database_unavailable", "selected_database")
        }
        pause(locked = false)
        settings.databaseId = databaseId
        passkeyDiagnosticIssues = null; passkeyDiagnosticFailed = false
        settings.syncRequest = Math.addExact(settings.syncRequest, 1)
        settings.peers.forEach {
            it.lastSentRevision = ""; it.lastReceivedRevision = ""
            force += it.deviceId; retry.remove(it.deviceId)
        }
        error = null; failureStage = ""; loadAfter = 0; phase = "idle"
        store.save(settings)
    }

    fun confirmPasskeySync(review: DevicePasskeySyncReview) = confirmPasskeySync(review, false)

    fun confirmPasskeySync(review: DevicePasskeySyncReview, dontAskAgain: Boolean) = action {
        if (!passkeyConsent.confirm(review)) return@action
        if (dontAskAgain) store.setPasskeyNoticeSuppressed(true)
        if (passkeyConsent.pending == null) {
            phase = "idle"
            if (manualPeers.isNotEmpty()) manualDeadline = SystemClock.elapsedRealtime() + 120_000
        }
    }

    fun cancelPasskeySync() { setEnabled(false); cancelSync() }

    fun setPasskeyNoticeSuppressed(suppressed: Boolean) {
        if (!SessionManager.isUnlocked.value) return
        store.setPasskeyNoticeSuppressed(suppressed)
        mutableState.update { it.copy(passkeyNoticeSuppressed = store.passkeyNoticeSuppressed(),
            passkeyReview = it.passkeyReview.takeUnless { store.passkeyNoticeSuppressed() }) }
        action {
            if (store.passkeyNoticeSuppressed()) {
                passkeyConsent.clearPending()
                if (phase == "passkey_review") {
                    phase = "idle"
                    if (manualPeers.isNotEmpty()) manualDeadline = SystemClock.elapsedRealtime() + 120_000
                }
            } else passkeyConsent.reset()
        }
    }

    fun refreshPasskeyDiagnostics() = action {
        checkingPasskeys = true; passkeyDiagnosticFailed = false; publish()
        try {
            store.save(settings())
            val issues = data.currentPasskeyIssues()
            passkeyDiagnosticIssues = issues.takeIf { SessionManager.isUnlocked.value }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { passkeyDiagnosticIssues = null; passkeyDiagnosticFailed = true }
        finally {
            checkingPasskeys = false
            // A diagnostic-only check must not leave an unlocked replica open.
            if (paused && dataProvider.isInitialized()) data.pause()
        }
    }

    fun setMode(mode: DeviceSyncMode) = action {
        val settings = settings()
        if (settings.mode == mode) return@action
        if (pairing) throw LanSyncException("device_sync_pairing_busy")
        val nextRequest = Math.addExact(settings.syncRequest, 1)
        transfers.values.toList().forEach { stop(it) }; transfers.clear()
        passkeyConsent.clearPending()
        manualPeers.clear()
        settings.mode = mode; settings.syncRequest = nextRequest
        snapshot = null; dataProgress = null; phase = "idle"; error = null; loadAfter = 0
        failureStage = ""; failureKind = ""; failureOperation = ""; failureRecordName = ""; connectionAttempts = 0
        settings.peers.forEach { force += it.deviceId; retry.remove(it.deviceId) }
        store.save(settings)
        cleanSnapshots()
    }

    fun forget(deviceId: String) = forgetDevices(setOf(deviceId))

    fun forgetDevices(deviceIds: Set<String>) {
        val ids = deviceIds.toSet()
        if (ids.isEmpty()) return
        if (deletionDecision.get()?.first?.deviceId in ids) cancelSync()
        action {
            ids.forEach { deviceId ->
                passkeyConsent.forget(deviceId)
                transfers.remove(deviceId)?.let { stop(it) }
                force.remove(deviceId); manualPeers.remove(deviceId); retry.remove(deviceId); statuses.remove(deviceId)
            }
            settings().peers.removeAll { it.deviceId in ids }
            store.save(settings())
            cleanSnapshots()
        }
    }

    fun pair(invitation: String? = null) = action {
        if (pairing || !appVisible.value) return@action
        DeviceSyncDiagLogger.event(if (invitation == null) "show_pairing_qr" else "connect_from_invitation")
        phase = "preparing"; failureStage = ""; failureKind = ""; failureOperation = ""; connectionAttempts = 0
        if (invitation != null && LanPairingInvitation.parse(invitation)?.version != 2) throw LanSyncException("unsupported_protocol")
        if (settings().peers.size >= 8) throw LanSyncException("too_many_devices")
        store.save(settings())
        transfers.values.toList().forEach { stop(it) }; transfers.clear()
        pairing = true; paused = false; phase = "preparing"; error = null
        acquireNetwork()
        publish()
        try {
            val directory = File(root, "out-${UUID.randomUUID()}").also { snapshots += it }
            val outgoing = DevicePairingSnapshot.prepare(directory, settings().deviceId)
            transfers["pairing"] = startTransfer(outgoing, null, invitation, true)
        }
        catch (failure: Exception) {
            pairing = false; code = ""; invitations = emptyList(); phase = "failed"
            throw failure
        }
    }

    fun cancelPairing() = action {
        transfers.remove("pairing")?.let { stop(it) }
        pairing = false; phase = "idle"; code = ""; invitations = emptyList()
    }

    private fun advertisement(revision: String): String = settings().let {
        "$revision:${it.settingsVersion}:${it.settingsWriter}:${it.syncRequest}"
    }

    private fun request(operation: String, handle: Long): JSONObject =
        RustLanSync.request(JSONObject().put("operation", operation).put("handle", handle))

    private suspend fun currentSnapshot(): DevicePasswordExport {
        if (failureStage == "preparing") {
            error = null; failureStage = ""; failureKind = ""; failureOperation = ""; failureRecordName = ""
        }
        try {
            val receiveOnly = settings().mode == DeviceSyncMode.RECEIVE_ONLY
            val revision = if (receiveOnly) receiveOnlyRevision else preparingDeviceSync("vault_revision") { data.revision() }
            val issues = if (receiveOnly) emptyList() else data.currentPasskeyIssues()
            if (!receiveOnly) { passkeyDiagnosticIssues = issues; passkeyDiagnosticFailed = false }
            snapshot?.takeIf { it.revision == revision && it.passkeyIssues == issues }?.let { return it }
            val directory = File(root, "out-${UUID.randomUUID()}").also { snapshots += it }
            snapshot = if (receiveOnly) {
                check(directory.mkdirs() || directory.isDirectory)
                DevicePasswordExport(revision, directory, "")
            } else preparingDeviceSync("vault_export") { data.prepare(directory) }
            return requireNotNull(snapshot)
        } finally {
            cleanSnapshots()
            if (dataProgress?.stage == "preparing") { dataProgress = null; if (phase == "preparing") phase = "idle" }
        }
    }

    private suspend fun startTransfer(outgoing: DevicePasswordExport, peer: SavedSyncPeer?, invitation: String?, enrollment: Boolean): Transfer {
        currentCoroutineContext().ensureActive()
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        if (!appVisible.value) throw CancellationException("device_sync_background")
        val directory = File(root, "in-${UUID.randomUUID()}")
        val settings = settings()
        val advertisement = advertisement(outgoing.revision)
        val device = JSONObject().put("device_id", settings.deviceId).put("name", Build.MODEL.take(64))
            .put("pair_id", peer?.pairId.orEmpty()).put("interval_seconds", settings.intervalSeconds)
            .put("settings_version", settings.settingsVersion).put("settings_writer", settings.settingsWriter)
            .put("sync_request", settings.syncRequest).put("revision", outgoing.revision).put("content_type", "vault").put("vault_content_version", 2)
            .put("sync_mode", (if (enrollment) DeviceSyncMode.BIDIRECTIONAL else settings.mode).wireName)
        val parameters = JSONObject().put("operation", if (invitation == null) "start" else "connect").put("protocol", 2)
            .put("name", Build.MODEL.take(64)).put("device", device).put("revision", advertisement)
            .put("vault_id", "monica-passwords-v1").put("content_key", outgoing.contentKey)
            .put("outgoing_directory", if (!enrollment && settings.mode == DeviceSyncMode.RECEIVE_ONLY) JSONObject.NULL else outgoing.directory.absolutePath)
            .put("incoming_directory", directory.absolutePath)
        invitation?.let { parameters.put("invitation", it) }
        peer?.let {
            parameters.put("expected_device_id", it.deviceId)
            if (invitation == null) parameters.put("trusted_key", it.trustedKey)
        }
        lastAttemptAt = System.currentTimeMillis()
        val started = try { RustLanSync.request(DeviceSyncLocalNetwork.withNetworks(app, parameters)) } catch (failure: Exception) { deleteOwned(directory); throw failure }
        val transfer = Transfer(started.getLong("handle"), directory, outgoing, enrollment, advertisement).apply { syncRequest = settings.syncRequest }
        activeHandles += transfer.handle
        if (!SessionManager.isUnlocked.value || !appVisible.value || !currentCoroutineContext().isActive) {
            withContext(NonCancellable) { stop(transfer) }
            throw CancellationException("device_sync_locked")
        }
        if (enrollment) {
            code = started.optString("code")
            val links = started.optJSONArray("invitations")
            invitations = links?.let { (0 until it.length()).map(it::getString) }.orEmpty()
            phase = if (invitation == null) "waiting" else "connecting"
            DeviceSyncDiagLogger.event(if (invitation == null) "pairing_qr_ready" else "pairing_connection_started")
            publish()
        }
        return transfer
    }

    private suspend fun tick() = supervisorScope {
        if (cancellationRequested.get()) return@supervisorScope
        val work = async(start = CoroutineStart.LAZY) { tickWork() }
        activeSyncWork = work
        try {
            if (cancellationRequested.get()) work.cancel()
            work.await()
        } catch (cancelled: CancellationException) {
            // End this attempt without cancelling the app-owned scheduling loop.
            currentCoroutineContext().ensureActive()
            if (!cancellationRequested.get()) throw cancelled
        } finally { activeSyncWork = null }
    }

    private suspend fun tickWork() {
        try {
            if (!appVisible.value) {
                if (!paused || preferences != null) pause(locked = false)
                return
            }
            if ((app.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true) {
                if (!paused || preferences != null) pause(locked = true)
                return
            }
            retiring.values.toList().forEach { stop(it) }
            cleanSnapshots()
            if (SystemClock.elapsedRealtime() < loadAfter) return
            val settings = settings()
            if (!settings.enabled && manualPeers.isEmpty() && !pairing && transfers.isEmpty()) {
                if (!paused) pause(locked = false)
                return
            }
            if (!pairing && settings.peers.isEmpty()) { if (!paused) pause(locked = false); return }
            paused = false
            acquireNetwork()
            val currentNetwork = DeviceSyncLocalNetwork.signature(app)
            if (networkSignature != null && networkSignature != currentNetwork) {
                presence.stop()
                // Pairing owns the displayed QR and listens on all interfaces; keep it alive.
                transfers.toMap().filterValues { !it.enrollment && it.stage in listOf("waiting", "connecting") }.forEach { (id, transfer) ->
                    stop(transfer); transfers.remove(id)
                }
                retry.clear()
            }
            networkSignature = currentNetwork
            (presence as? ForegroundDeviceSyncPresence)?.updateTargets(settings.peers.map { it.address }.filter(String::isNotEmpty).toSet())
            presence.start(settings.deviceId, settings.peers.map { it.deviceId }.toSet())
            transfers.toMap().forEach { (id, transfer) ->
                if (!transfer.enrollment && id !in manualPeers && id !in presence.online.value && transfer.stage in listOf("waiting", "connecting")) {
                    stop(transfer); transfers.remove(id); statuses[id] = "app_closed"
                    return@forEach
                }
                try { poll(id, transfer) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    recordFailure(failure, transfer.stage, transfer.attempts)
                    fail(id, transfer.enrollment)
                    stop(transfer); transfers.remove(id)
                }
            }
            // Poll first so the connection deadline cannot cancel a transfer that
            // has already connected, is transferring, or is waiting to apply edits.
            if (manualPeers.isNotEmpty() && SystemClock.elapsedRealtime() >= manualDeadline && passkeyConsent.pending == null) {
                val expired = manualPeers.filter { transfers[it]?.stage in listOf(null, "waiting", "connecting") }
                expired.forEach { id ->
                    transfers.remove(id)?.let { stop(it) }
                    manualPeers.remove(id); statuses[id] = "offline"
                }
                if (expired.isNotEmpty() && !settings.enabled) {
                    if (manualPeers.isEmpty()) pause(locked = false)
                    recordFailure(LanSyncException("connection_failed", "manual_timeout"), "connecting")
                }
            }
            if (!pairing && settings.peers.isNotEmpty() && retiring.isEmpty()) maintainConnections()
            cleanSnapshots()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            recordFailure(failure, "preparing")
            manualPeers.clear()
            loadAfter = SystemClock.elapsedRealtime() + 10_000
        } finally { publish() }
    }

    private suspend fun maintainConnections() {
        val settings = settings()
        val peers = settings.peers.filter { it.deviceId in manualPeers || settings.enabled && it.deviceId in presence.online.value }
        if (peers.isEmpty()) {
            settings.peers.forEach { statuses[it.deviceId] = "app_closed" }
            phase = "waiting_for_app"; dataProgress = null
            return
        }
        val outgoing = currentSnapshot()
        // Recheck after potentially long preparation, before consent/network work.
        if (!automaticSyncEnabled() && manualPeers.isEmpty()) return
        var discovery: JSONObject? = null
        // Pairing exchanges no vault data. Every actual send, including the first
        // automatic send after enrollment, must pass this review before JNI starts.
        if (passkeyConsent.review(outgoing.passkeyIssues, peers.map { it.deviceId }.toSet(), store.passkeyNoticeSuppressed()) != null) {
            phase = "passkey_review"; dataProgress = null
            return
        }
        if (manualPeers.isNotEmpty() && !manualConnectionReady) {
            // Give the peer a full connection window after local export/consent.
            manualDeadline = SystemClock.elapsedRealtime() + 120_000
            manualConnectionReady = true
        }
        for (peer in peers) {
            currentCoroutineContext().ensureActive()
            if (!appVisible.value) return
            if (peer.deviceId !in manualPeers && peer.deviceId !in presence.online.value) continue
            if (!automaticSyncEnabled() && peer.deviceId !in manualPeers) continue
            if (peer.deviceId in transfers || (retry[peer.deviceId]?.second ?: 0) > SystemClock.elapsedRealtime()) continue
            if (settings.deviceId > peer.deviceId) {
                transfers[peer.deviceId] = startTransfer(outgoing, peer, null, false)
                statuses[peer.deviceId] = "waiting"
                continue
            }
            if (discovery == null) {
                discovery = discoverPeers(DeviceSyncLocalNetwork.withNetworks(app, JSONObject().put("operation", "discover")
                    .put("targets", org.json.JSONArray(DeviceSyncLocalNetwork.targets(app,
                        presence.addresses + peers.map { it.address }.filter(String::isNotEmpty))))))
                lastDiscoveryAt = System.currentTimeMillis()
                val foundDevices = discovery.getJSONArray("devices")
                discoveredPeers = (0 until foundDevices.length()).map(foundDevices::getJSONObject).count { found ->
                    settings.peers.any { it.deviceId == found.optString("device_id") && it.pairId == found.optString("session") } && found.optBoolean("trusted")
                }
            }
            currentCoroutineContext().ensureActive()
            val devices = discovery.getJSONArray("devices")
            val found = (0 until devices.length()).map(devices::getJSONObject).firstOrNull {
                it.optString("device_id") == peer.deviceId && it.optString("session") == peer.pairId && it.optBoolean("trusted")
            }
            if (found == null) { statuses[peer.deviceId] = "offline"; continue }
            val parts = found.optString("revision").split(':', limit = 4)
            val changed = outgoing.revision != peer.lastSentRevision || parts.firstOrNull() != peer.lastReceivedRevision
            val version = parts.getOrNull(1)?.toLongOrNull()
            val writer = parts.getOrNull(2).orEmpty()
            val policyChanged = version != null && (version > settings.settingsVersion || version == settings.settingsVersion && writer > settings.settingsWriter)
            val requested = parts.getOrNull(3)?.toLongOrNull()?.let { it != peer.lastReceivedRequest } ?: false
            val elapsed = attempts[peer.deviceId]?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
            if (!DeviceSyncCadence.isDue(settings.intervalSeconds, elapsed, changed, policyChanged || requested || peer.deviceId in force)) {
                statuses[peer.deviceId] = if (changed) "scheduled" else "online"; continue
            }
            val alternatives = found.optJSONArray("addresses")
            val candidates = (listOf(found.getString("address")) + alternatives?.let { (0 until it.length()).map(it::getString) }.orEmpty())
                .distinct().take(8).joinToString(",")
            val link = "monica-lan:2:$candidates:${found.getInt("port")}:${peer.trustedKey}"
            if (!automaticSyncEnabled() && peer.deviceId !in manualPeers) continue
            transfers[peer.deviceId] = startTransfer(outgoing, peer, link, false)
            attempts[peer.deviceId] = SystemClock.elapsedRealtime()
            statuses[peer.deviceId] = "syncing"
        }
    }

    private suspend fun poll(id: String, transfer: Transfer) {
        val progress = request("progress", transfer.handle)
        val next = progress.getString("phase")
        // Stop idle automatic connections; established transfers finish applying
        // and exchanging receipts even after automatic scheduling is disabled.
        if (!transfer.enrollment && !automaticSyncEnabled() && id !in manualPeers && next in listOf("waiting", "connecting")) {
            stop(transfer); transfers.remove(id); statuses[id] = "manual"
            return
        }
        transfer.applyFailure?.let { failure ->
            // Let the worker send its authenticated rejection receipt before cleanup.
            // Lock/cancellation still cancels immediately; an unresponsive peer is bounded.
            if (next in listOf("completed", "failed", "cancelled") || SystemClock.elapsedRealtime() >= transfer.rejectionDeadline) throw failure
            return
        }
        if(transfer.enrollment || next != "waiting") phase = next else if(!pairing) phase = "idle"
        transfer.stage = if (next == "failed") progress.optString("failed_at", transfer.stage) else next
        transfer.attempts = progress.optInt("connection_attempt")
        if (next in listOf("sending", "receiving", "transferring")) updateProgress(DeviceSyncProgress(next, bytes = progress.optLong("transferred"), totalBytes = progress.optLong("total")))
        else if (dataProgress?.stage in listOf("sending", "receiving", "transferring")) dataProgress = null
        transfer.remoteAddress = progress.optString("remote_address", transfer.remoteAddress)
        if (transfer.enrollment) phase = next else if (next != "waiting") statuses[id] = "syncing"
        if (next == "waiting" && !transfer.enrollment) {
            val revision = if (settings().mode == DeviceSyncMode.RECEIVE_ONLY) receiveOnlyRevision else data.revision()
            if (advertisement(revision) != transfer.advertisement) { stop(transfer); transfers.remove(id) }
            return
        }
        if (next == "received" && !transfer.applyAttempted) {
            val remote = progress.getJSONObject("peer")
            val pairingOnly = transfer.enrollment && remote.optString("revision") == DevicePairingSnapshot.REVISION
            val receiveContent = !pairingOnly && settings().mode != DeviceSyncMode.SEND_ONLY && remote.optBoolean("receive_content", true)
            if (receiveContent && DeviceSyncEditGate.isEditing) return
            transfer.applyAttempted = true
            transfer.stage = "applying"
            transfer.peer = remote
            try {
                currentCoroutineContext().ensureActive()
                if (remote.getString("content_type") != "vault" || remote.optInt("vault_content_version") != 2) throw LanSyncException("unsupported_protocol")
                if (remote.getString("device_id") == settings().deviceId) throw LanSyncException("same_device")
                if (receiveContent) {
                    data.apply(transfer.directory, remote) { count -> reviewReceivedDeletions(remote, count) }
                    passkeyDiagnosticIssues = null
                }
                val settings = settings()
                settings.acceptInterval(remote.getInt("interval_seconds"), remote.getLong("settings_version"), remote.getString("settings_writer"))
                if (transfer.enrollment) {
                    val secrets = remote.getJSONObject("pairing")
                    val peer = SavedSyncPeer(remote.getString("device_id"), remote.getString("name"), secrets.getString("pair_id"), secrets.getString("trusted_key"))
                    peer.address = transfer.remoteAddress
                    settings.peers.removeAll { it.deviceId == peer.deviceId }; settings.peers += peer
                    if (settings.enabled) force += peer.deviceId
                }
                transfer.stage = "remembering"
                store.save(settings)
                currentCoroutineContext().ensureActive()
                check(SessionManager.isUnlocked.value) { "device_sync_locked" }
                RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", transfer.handle).put("success", true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val rejected = runCatching { RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", transfer.handle).put("success", false)) }
                if (rejected.isFailure) throw failure
                transfer.applyFailure = failure
                transfer.rejectionDeadline = SystemClock.elapsedRealtime() + 10_000
                recordFailure(failure, transfer.stage, transfer.attempts)
                return
            }
        }
        if (next !in listOf("completed", "failed", "cancelled")) return
        if (next == "completed") {
            phase = "completed"; dataProgress = null
            val remote = requireNotNull(transfer.peer)
            val remoteId = remote.getString("device_id")
            settings().peers.firstOrNull { it.deviceId == remoteId }?.let {
                val contentSynced = transfer.snapshot.revision != DevicePairingSnapshot.REVISION
                if (contentSynced) {
                    it.lastSyncedAt = System.currentTimeMillis()
                    it.lastSentRevision = transfer.snapshot.revision
                    it.lastReceivedRevision = remote.getString("revision")
                    it.lastReceivedRequest = remote.optLong("sync_request")
                }
                if (transfer.remoteAddress.isNotEmpty()) it.address = transfer.remoteAddress
                statuses[remoteId] = if (contentSynced) "online" else "waiting"; retry.remove(remoteId)
                // Finishing an older transfer must not consume a manual request queued during its receipts.
                if (!transfer.enrollment && transfer.syncRequest == settings().syncRequest) { force.remove(remoteId); manualPeers.remove(remoteId) }
            }
            store.save(settings())
            error = null
        } else {
            val code = progress.optString("error", "device_sync_failed")
            // A peer may replace its advertised listener as manual/settings state changes.
            // Keep the user's request through connection setup retries, within its original deadline.
            val retryManualConnection = !transfer.enrollment &&
                id in manualPeers && SystemClock.elapsedRealtime() < manualDeadline &&
                transfer.stage in setOf("connecting", "authenticating") &&
                code in setOf("connection_failed", "connection_closed", "transfer_timeout")
            fail(id, transfer.enrollment, retryManualConnection)
            if (retryManualConnection) {
                // Still waiting within the user's request; this is not a terminal failure.
                error = null; dataProgress = null; phase = "waiting"; statuses[id] = "waiting"
            } else recordFailure(LanSyncException(code, if (code == "peer_merge_failed") "peer_apply" else "transfer_$next"), transfer.stage, transfer.attempts)
        }
        if (transfer.enrollment) { pairing = false; code = ""; invitations = emptyList(); phase = next }
        stop(transfer); transfers.remove(id)
    }

    private fun fail(id: String, enrollment: Boolean, retryManualConnection: Boolean = false) {
        if (!retryManualConnection || SystemClock.elapsedRealtime() >= manualDeadline) manualPeers.remove(id)
        statuses[id] = "offline"; force += id
        val count = minOf(6, (retry[id]?.first ?: 0) + 1)
        retry[id] = count to (SystemClock.elapsedRealtime() + minOf(60, 1 shl count) * 1000L)
        if (enrollment) { pairing = false; code = ""; invitations = emptyList(); phase = "failed" }
    }

    private fun recordFailure(failure: Exception, stage: String, attempts: Int = 0) {
        if (stage == "preparing") passkeyConsent.clearPending()
        error = errorCode(failure)
        failureStage = stage
        failureKind = (failure as? LanSyncException)?.failureKind?.ifEmpty { null } ?: failure.javaClass.simpleName
        failureOperation = (failure as? LanSyncException)?.operation.orEmpty()
        failureRecordName = (failure as? LanSyncException)?.recordName.orEmpty()
        phase = "failed"; dataProgress = null
        connectionAttempts = attempts
        DeviceSyncDiagLogger.failure(stage, error.orEmpty(), failureKind, attempts, failureOperation)
    }

    private suspend fun pause(locked: Boolean) {
        withContext(NonCancellable) {
            presence.stop()
            deletionDecision.getAndSet(null)?.second?.cancel()
            manualPeers.clear()
            transfers.values.toList().forEach { stop(it) }; transfers.clear()
            retiring.values.toList().forEach { stop(it) }
            if (!paused && dataProvider.isInitialized()) data.pause()
            snapshot = null; dataProgress = null; cleanSnapshots()
            passkeyConsent.clearPending()
            if (locked) { passkeyDiagnosticIssues = null; passkeyDiagnosticFailed = false }
            pairing = false; code = ""; invitations = emptyList(); phase = if (locked) "locked" else if (!appVisible.value) "background" else "paused"
            preferences?.peers?.forEach { statuses[it.deviceId] = phase }
            publish()
            if (locked) preferences = null
            multicast?.let { if (it.isHeld) it.release() }; multicast = null
            multicastAttempted = false
            networkSignature = null
            paused = true
        }
    }

    private suspend fun stop(transfer: Transfer) {
        retiring[transfer.handle] = transfer
        try {
            request("cancel", transfer.handle)
            repeat(20) {
                if (request("progress", transfer.handle).getString("phase") in listOf("completed", "failed", "cancelled")) {
                    request("release", transfer.handle)
                    activeHandles -= transfer.handle
                    retiring.remove(transfer.handle)
                    deleteOwned(transfer.directory)
                    return
                }
                delay(50)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private fun cleanSnapshots() {
        val used = (transfers.values + retiring.values).map { it.snapshot.directory }.toMutableSet()
        snapshot?.let { used += it.directory }
        snapshots.filter { it !in used }.forEach { if (deleteOwned(it)) snapshots.remove(it) }
    }

    private fun deleteOwned(directory: File): Boolean {
        return try {
            if (directory.canonicalFile.parentFile != root.canonicalFile) return false
            !directory.exists() || directory.deleteRecursively()
        } catch (_: Exception) { false }
    }

    private fun acquireNetwork() {
        if (multicastAttempted) return
        multicastAttempted = true
        try {
            multicast = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("monica-device-sync")?.apply { setReferenceCounted(false); acquire() }
        } catch (failure: Exception) {
            // Direct QR connections and remembered-address discovery do not require multicast.
            DeviceSyncDiagLogger.failure("network", "multicast_unavailable", failure.javaClass.simpleName, 0)
        }
    }

    companion object {
        @Volatile private var instance: DeviceSyncCoordinator? = null
        fun get(context: Context): DeviceSyncCoordinator = instance ?: synchronized(this) {
            instance ?: DeviceSyncCoordinator(context).also { instance = it; it.start() }
        }
        private fun errorCode(error: Exception): String = (error as? LanSyncException)?.code ?: "device_sync_failed"
    }
}
