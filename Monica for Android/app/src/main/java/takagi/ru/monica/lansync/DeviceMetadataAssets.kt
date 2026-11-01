package takagi.ru.monica.lansync

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.attachments.storage.AttachmentKeyVault
import takagi.ru.monica.attachments.storage.AttachmentStorage
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import java.security.MessageDigest
import java.util.UUID
import java.io.InputStream
import java.io.FilterInputStream

/** Metadata files have no PasswordEntry/SecureItem parent. Keep them outside the
 * ordinary attachment table and its orphan collector, using the same encryption. */
internal class DeviceMetadataAssets(context: Context, private val database: PasswordDatabase,
    private val security: SecurityManager) {
    private val storage = AttachmentStorage(context, AttachmentStorage.Scope.DEVICE_METADATA)
    private val keys = AttachmentKeyVault(security)

    internal class Blob(val descriptor: JSONObject, val path: String, val wrappedKey: String) {
        override fun toString() = "MetadataBlob"
    }

    private fun table() = database.openHelper.writableDatabase.execSQL(
        "CREATE TABLE IF NOT EXISTS device_sync_metadata_assets(owner TEXT NOT NULL,portable_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(owner,portable_id))")

    private fun rows(owner: String): List<Blob> {
        table()
        return database.openHelper.readableDatabase.query(
            "SELECT payload FROM device_sync_metadata_assets WHERE owner=? ORDER BY portable_id", arrayOf(owner)).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val data = JSONObject(security.decryptDataIfMonicaCiphertext(cursor.getString(0)))
                add(Blob(data.getJSONObject("descriptor"), data.getString("path"), data.getString("wrapped_key")))
            }
        } }
    }

    fun snapshot(snapshot: DevicePasswordSnapshot): DevicePasswordSnapshot = snapshot.copy(
        assets = rows(snapshot.localId).map { blob -> DeviceVaultAsset(blob.descriptor.getString("id"), "", 0,
            blob.descriptor.getLong("size"), metadata = blob) })

    suspend fun read(blob: Blob): ByteArray {
        val size = blob.descriptor.getLong("size")
        if (size !in 0..MAX_BYTES) throw LanSyncException("asset_limit")
        val bytes = ByteArray(size.toInt())
        try {
            openStream(blob).use { stream ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = stream.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) throw LanSyncException("invalid_asset")
                    offset += count
                }
                if (stream.read() != -1) throw LanSyncException("invalid_asset")
            }
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    suspend fun openStream(blob: Blob): InputStream {
        val size = blob.descriptor.getLong("size")
        val id = blob.descriptor.getString("id")
        if (size !in 0..MAX_BYTES) throw LanSyncException("asset_limit")
        if (!id.matches(Regex("[0-9a-fA-F]{64}"))) throw LanSyncException("invalid_asset")
        // Only locally generated UUID paths are valid; never trust portable paths.
        require(Regex("[0-9a-f-]{36}\\.enc").matches(blob.path)) { "invalid_asset_path" }
        val cek = keys.unwrap(blob.wrappedKey)
        val source = try { storage.openDecryptedStream(blob.path, cek) }
            catch (failure: Throwable) { cek.fill(0); throw failure }
        val owned = object : FilterInputStream(source) {
            override fun close() {
                try { super.close() } finally { cek.fill(0) }
            }
        }
        val context = currentCoroutineContext()
        return DeviceAssetInputStream.verified(owned, id, size) { context.ensureActive() }
    }

    /** The encrypted row and owner metadata commit together. Blob deletion is
     * deferred until commit; rollback deletes only this attempt's new blobs. */
    suspend fun <T> transaction(block: suspend Mutation.() -> T): T {
        val mutation = Mutation()
        var committed = false
        try {
            val result = database.withTransaction { mutation.block() }
            committed = true
            return result
        } finally {
            withContext(NonCancellable) {
                (if (committed) mutation.obsolete else mutation.created).forEach { storage.delete(it) }
            }
        }
    }

    inner class Mutation internal constructor() {
        internal val created = mutableListOf<String>()
        internal val obsolete = mutableListOf<String>()

        suspend fun adopt(owner: String, incoming: JSONObject, purge: Boolean,
            reader: suspend (JSONObject) -> ByteArray): JSONObject =
            adoptStream(owner, incoming, purge) { DeviceAssetInputStream.ownedBytes(reader(it)) }

        suspend fun adoptStream(owner: String, incoming: JSONObject, purge: Boolean,
            reader: suspend (JSONObject) -> InputStream): JSONObject {
            val existing = rows(owner)
            val data = if(purge) JSONObject() else JSONObject(incoming.toString())
            val desired = if (purge) JSONArray() else data.optJSONArray("assets")
            if (desired == null) {
                if (existing.isNotEmpty()) data.put("assets", JSONArray(existing.map { JSONObject(it.descriptor.toString()) }))
                return data
            }
            require(desired.length() <= 64) { "asset_limit" }
            val kept = mutableSetOf<String>()
            val saved = JSONArray()
            for (index in 0 until desired.length()) {
                val descriptor = JSONObject(desired.getJSONObject(index).toString())
                val portable = descriptor.optString("attachment_id").takeIf { it.isNotBlank() }
                    ?: existing.firstOrNull { it.descriptor.getString("id") == descriptor.getString("id") &&
                        it.descriptor.getString("file_name") == descriptor.getString("file_name") &&
                        it.descriptor.getString("attachment_id") !in kept }?.descriptor?.getString("attachment_id")
                    ?: UUID.randomUUID().toString()
                require(kept.add(portable)) { "invalid_asset_identity" }
                descriptor.put("attachment_id", portable)
                val old = existing.firstOrNull { it.descriptor.getString("attachment_id") == portable }
                val same = old != null && old.descriptor.getString("id") == descriptor.getString("id") &&
                    old.descriptor.getLong("size") == descriptor.getLong("size") && storage.exists(old.path)
                val blob = if (same) {
                    openStream(old!!).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        try { while (stream.read(buffer) != -1) currentCoroutineContext().ensureActive() }
                        finally { buffer.fill(0) }
                    }
                    Blob(descriptor, old.path, old.wrappedKey)
                } else {
                    val context = currentCoroutineContext()
                    val id = descriptor.getString("id")
                    val size = descriptor.getLong("size")
                    if (size !in 0..MAX_BYTES || !id.matches(Regex("[0-9a-fA-F]{64}"))) throw LanSyncException("invalid_asset")
                    val written = DeviceAssetInputStream.verified(reader(descriptor), id, size) {
                        context.ensureActive()
                    }.use { storage.writeEncrypted(it) }
                    created += written.relativePath
                    try { Blob(descriptor, written.relativePath, keys.wrap(written.cek)) }
                    finally { written.cek.fill(0) }
                }
                val record = JSONObject().put("descriptor", descriptor).put("path", blob.path).put("wrapped_key", blob.wrappedKey)
                database.openHelper.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO device_sync_metadata_assets(owner,portable_id,payload) VALUES(?,?,?)",
                    arrayOf(owner, portable, security.encryptDataLegacyCompat(record.toString())))
                if (old != null && old.path != blob.path) obsolete += old.path
                saved.put(descriptor)
            }
            existing.filter { it.descriptor.getString("attachment_id") !in kept }.forEach { old ->
                database.openHelper.writableDatabase.execSQL("DELETE FROM device_sync_metadata_assets WHERE owner=? AND portable_id=?",
                    arrayOf(owner, old.descriptor.getString("attachment_id")))
                obsolete += old.path
            }
            if (!purge) data.put("assets", saved)
            return data
        }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        internal fun validate(descriptor: JSONObject, bytes: ByteArray) {
            val expected = descriptor.getLong("size")
            if (expected !in 0..MAX_BYTES || bytes.size.toLong() != expected ||
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } != descriptor.getString("id"))
                throw LanSyncException("invalid_asset")
        }
    }
}
