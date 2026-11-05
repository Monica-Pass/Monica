package takagi.ru.monica.lansync

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.security.SecurityManager
import java.security.SecureRandom
import java.util.UUID

internal enum class DeviceSyncMode(val wireName: String) {
    BIDIRECTIONAL("bidirectional"), SEND_ONLY("send_only"), RECEIVE_ONLY("receive_only");
    companion object {
        fun fromWire(value: String): DeviceSyncMode? = entries.firstOrNull { it.wireName == value }
    }
}

internal data class SavedSyncPeer(
    val deviceId: String, var name: String, val pairId: String, val trustedKey: String,
    var lastSyncedAt: Long = 0, var lastSentRevision: String = "", var lastReceivedRevision: String = "", var lastReceivedRequest: Long = 0,
    var address: String = ""
) { override fun toString() = "SavedSyncPeer($name)" }

internal data class DeviceSyncPreferences @JvmOverloads constructor(
    val deviceId: String = UUID.randomUUID().toString().replace("-", ""),
    val replicaKey: String = randomKey(),
    var enabled: Boolean = true,
    var intervalSeconds: Int = 0,
    var settingsVersion: Long = 0,
    var settingsWriter: String = "",
    val peers: MutableList<SavedSyncPeer> = mutableListOf(),
    var syncRequest: Long = 0,
    var mode: DeviceSyncMode = DeviceSyncMode.BIDIRECTIONAL,
    var databaseId: Long? = null
) {
    fun setInterval(seconds: Int) {
        require(seconds == 0 || seconds in 60..86400)
        intervalSeconds = seconds
        settingsVersion = Math.addExact(settingsVersion, 1)
        settingsWriter = deviceId
    }
    fun acceptInterval(seconds: Int, version: Long, writer: String): Boolean {
        if ((seconds != 0 && seconds !in 60..86400) || version < 0 || writer.length > 64 || version < settingsVersion ||
            (version == settingsVersion && writer <= settingsWriter)) return false
        intervalSeconds = seconds; settingsVersion = version; settingsWriter = writer
        return true
    }
    override fun toString() = "DeviceSyncPreferences(peers=${peers.size})"
    companion object {
        private fun randomKey(): String {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return try { Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) } finally { key.fill(0) }
        }
    }
}

internal object DeviceSyncCadence {
    fun isDue(intervalSeconds: Int, elapsedMillis: Long, changed: Boolean, force: Boolean = false): Boolean =
        force || (changed && elapsedMillis >= (if (intervalSeconds == 0) 2L else intervalSeconds.toLong()) * 1000)
}

internal class DeviceSyncPreferenceStore(context: Context, securityProvider: () -> SecurityManager,
    preferenceName: String = "monica-device-sync") {
    constructor(context: Context, security: SecurityManager, preferenceName: String = "monica-device-sync") :
        this(context, { security }, preferenceName)

    // Status publication also runs while locked, including cold input-method processes.
    // Reading its non-secret scheduling flags must not open the encrypted vault.
    private val security by lazy(securityProvider)
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private var selectionToken: String? = null
    private var selection: Long? = null
    fun selectedDatabaseId(): Long? {
        val token = preferences.getString("protected", null)
        if (token != selectionToken) {
            selection = load().databaseId
            selectionToken = token
        }
        return selection
    }
    // A local scheduling preference, separate from encrypted peer snapshots so
    // a long export/import or a stale snapshot save cannot block or undo a tap.
    fun automaticSyncEnabled(fallback: Boolean): Boolean = preferences.getBoolean("automatic-enabled", fallback)
    fun setAutomaticSyncEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("automatic-enabled", enabled).apply()
    }
    fun passkeyNoticeSuppressed(): Boolean = preferences.getBoolean("passkey-notice-suppressed", false)
    fun setPasskeyNoticeSuppressed(suppressed: Boolean) {
        preferences.edit().putBoolean("passkey-notice-suppressed", suppressed).apply()
    }
    fun hasPeers(): Boolean = automaticSyncEnabled(preferences.getBoolean("enabled", false)) && preferences.getInt("peer-count", 0) > 0
    fun load(): DeviceSyncPreferences {
        val encrypted = preferences.getString("protected", null)
            ?: return DeviceSyncPreferences(enabled = automaticSyncEnabled(true))
        val json = JSONObject(security.decryptData(encrypted))
        require(json.getInt("version") == 1)
        val result = DeviceSyncPreferences(json.getString("device_id"), json.getString("replica_key"),
            automaticSyncEnabled(json.optBoolean("enabled", true)), json.optInt("interval"), json.optLong("settings_version"), json.optString("settings_writer"))
        require(result.intervalSeconds == 0 || result.intervalSeconds in 60..86400)
        require(result.deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && result.settingsVersion >= 0)
        require(LanPairingInvitation.parse("monica-lan:2:127.0.0.1:1:${result.replicaKey}") != null)
        result.syncRequest = json.optLong("sync_request")
        result.mode = requireNotNull(DeviceSyncMode.fromWire(json.optString("sync_mode", "bidirectional")))
        result.databaseId = if (json.isNull("database_id")) null else json.getLong("database_id").also { require(it > 0) }
        val peers = json.getJSONArray("peers")
        require(peers.length() <= 8)
        for (index in 0 until peers.length()) {
            val peer = peers.getJSONObject(index)
            val saved = SavedSyncPeer(peer.getString("device_id"), peer.getString("name"), peer.getString("pair_id"), peer.getString("key"),
                peer.optLong("last_sync"), peer.optString("sent_revision"), peer.optString("received_revision"), peer.optLong("received_request"), peer.optString("address"))
            require(saved.deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}")) && saved.pairId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(LanPairingInvitation.parse("monica-lan:2:127.0.0.1:1:${saved.trustedKey}") != null)
            result.peers += saved
        }
        return result
    }
    fun save(settings: DeviceSyncPreferences) {
        val peers = JSONArray()
        settings.peers.forEach { peer -> peers.put(JSONObject().put("device_id", peer.deviceId).put("name", peer.name)
            .put("pair_id", peer.pairId).put("key", peer.trustedKey).put("last_sync", peer.lastSyncedAt)
            .put("sent_revision", peer.lastSentRevision).put("received_revision", peer.lastReceivedRevision).put("received_request", peer.lastReceivedRequest).put("address", peer.address)) }
        val json = JSONObject().put("version", 1).put("device_id", settings.deviceId).put("replica_key", settings.replicaKey)
            .put("enabled", settings.enabled).put("interval", settings.intervalSeconds).put("settings_version", settings.settingsVersion)
            .put("settings_writer", settings.settingsWriter).put("sync_request", settings.syncRequest).put("peers", peers)
            .put("sync_mode", settings.mode.wireName)
            .put("database_id", settings.databaseId ?: JSONObject.NULL)
        check(preferences.edit().putString("protected", security.encryptData(json.toString()))
            .putBoolean("enabled", settings.enabled).putInt("peer-count", settings.peers.size).commit())
    }
}
