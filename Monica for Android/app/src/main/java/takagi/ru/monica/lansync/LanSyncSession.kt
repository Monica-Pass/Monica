package takagi.ru.monica.lansync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.io.File
import java.util.UUID

internal data class LanSyncState(
    val phase: String = "idle",
    val busy: Boolean = false,
    val discovering: Boolean = false,
    val devices: List<LanPeer> = emptyList(),
    val invitations: List<String> = emptyList(),
    val code: String = "",
    val transferred: Long = 0,
    val total: Long = 0,
    val conflicts: Int = 0,
    val error: String? = null,
    val importedDatabaseId: Long? = null
)

/** A foreground, explicitly paired session. No network listener survives leaving this screen. */
internal class LanSyncSession(context: Context, private val vaults: MdbxViewModel) : AutoCloseable {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LanSyncState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun discover() {
        if (mutableState.value.discovering) return
        scope.launch {
            mutableState.value = mutableState.value.copy(discovering = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) { RustLanSync.request(DeviceSyncLocalNetwork.withNetworks(application, JSONObject().put("operation", "discover")
                    .put("targets", org.json.JSONArray(DeviceSyncLocalNetwork.targets(application))))) }
                val devices = result.getJSONArray("devices")
                mutableState.value = mutableState.value.copy(devices = (0 until devices.length()).map { index ->
                    val device = devices.getJSONObject(index)
                    LanPeer(device.getString("name"), device.getString("address"), device.getInt("port"))
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = (error as? LanSyncException)?.code ?: "discovery_unavailable")
            } finally { mutableState.value = mutableState.value.copy(discovering = false) }
        }
    }

    fun host(databaseId: Long) = run(databaseId, null, "", "")
    fun connect(databaseId: Long?, invitation: String, password: String, name: String) = run(databaseId, invitation.trim(), password, name)

    private fun run(databaseId: Long?, invitation: String?, password: String, name: String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(phase = "preparing", busy = true, error = null,
            invitations = emptyList(), code = "", total = 0, transferred = 0, conflicts = 0, importedDatabaseId = null)
        operation = scope.launch {
            val root = File(application.cacheDir, "lan-sync/${UUID.randomUUID()}")
            val outgoing = File(root, "outgoing")
            val incoming = File(root, "incoming")
            var handle: Long? = null
            val wifi = application.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicast = wifi?.createMulticastLock("monica-lan-sync")?.apply { setReferenceCounted(false) }
            try {
                multicast?.acquire()
                val vaultId = if (databaseId != null) withContext(Dispatchers.IO) {
                    vaults.prepareLanSyncArtifacts(databaseId, outgoing, invitation == null)
                } else ""
                val request = JSONObject().put("operation", if (invitation == null) "start" else "connect")
                    .put("vault_id", vaultId).put("incoming_directory", incoming.absolutePath)
                    .put("name", Build.MODEL)
                if (databaseId != null) request.put("outgoing_directory", outgoing.absolutePath)
                if (invitation != null) request.put("invitation", invitation)
                val started = withContext(Dispatchers.IO) {
                    RustLanSync.request(DeviceSyncLocalNetwork.withNetworks(application, request)).also { handle = it.getLong("handle") }
                }
                val links = started.optJSONArray("invitations")
                mutableState.value = mutableState.value.copy(
                    invitations = if (links == null) emptyList() else (0 until links.length()).map(links::getString),
                    code = started.optString("code"))
                var applied = false
                while (true) {
                    ensureActive()
                    val progress = withContext(Dispatchers.IO) {
                        RustLanSync.request(JSONObject().put("operation", "progress").put("handle", handle))
                    }
                    val phase = progress.getString("phase")
                    mutableState.value = mutableState.value.copy(phase = phase,
                        transferred = progress.optLong("transferred"), total = progress.optLong("total"))
                    when (phase) {
                        "received" -> if (!applied) {
                            applied = true
                            mutableState.value = mutableState.value.copy(phase = "merging", invitations = emptyList(), code = "")
                            try {
                                // Native apply is atomic. Complete the matching projection refresh even if the view is closed.
                                val merged = withContext(NonCancellable + Dispatchers.IO) {
                                    if (databaseId == null) vaults.importLanSyncVault(incoming, password, name)
                                    else databaseId to vaults.applyLanSyncArtifacts(databaseId, incoming)
                                }
                                mutableState.value = mutableState.value.copy(conflicts = merged.second,
                                    importedDatabaseId = merged.first.takeIf { databaseId == null })
                                withContext(Dispatchers.IO) { RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", handle).put("success", true)) }
                            } catch (error: Exception) {
                                withContext(NonCancellable + Dispatchers.IO) { runCatching {
                                    RustLanSync.request(JSONObject().put("operation", "acknowledge").put("handle", handle).put("success", false))
                                } }
                                throw error
                            }
                        }
                        "completed" -> break
                        "failed", "cancelled" -> throw LanSyncException(progress.optString("error", "failed"))
                    }
                    delay(100)
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(phase = "cancelled")
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(phase = "failed", error = (error as? LanSyncException)?.code ?: "vault_operation_failed")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    var nativeStopped = handle == null
                    handle?.let { id ->
                        runCatching { RustLanSync.request(JSONObject().put("operation", "cancel").put("handle", id)) }
                        for (attempt in 0 until 60) {
                            val phase = runCatching { RustLanSync.request(JSONObject().put("operation", "progress").put("handle", id)).optString("phase") }.getOrNull()
                            if (phase in listOf(null, "cancelled", "completed", "failed")) break
                            delay(100)
                        }
                        nativeStopped = runCatching { RustLanSync.request(JSONObject().put("operation", "release").put("handle", id)) }.isSuccess
                    }
                    // root is a freshly generated child of this app's private cache directory.
                    if (nativeStopped && root.canonicalPath.startsWith(File(application.cacheDir, "lan-sync").canonicalPath + File.separator)) root.deleteRecursively()
                }
                if (multicast?.isHeld == true) multicast.release()
                mutableState.value = mutableState.value.copy(busy = false, invitations = emptyList(), code = "")
            }
        }
    }

    fun cancel() { operation?.cancel() }
    override fun close() { scope.cancel() }
}
