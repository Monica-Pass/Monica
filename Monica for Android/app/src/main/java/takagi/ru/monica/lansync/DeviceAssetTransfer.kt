package takagi.ru.monica.lansync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

internal class DeviceAssetTransfer(directory: File, private val key: String) {
    private val path = File(directory, "assets.monica").absolutePath
    private var offsets: Map<String, Long>? = null
    fun contains(id: String): Boolean {
        if(!File(path).isFile) return false
        return index().containsKey(id)
    }
    private fun index(): Map<String, Long> = offsets ?: RustLanSync.request(JSONObject().put("operation", "asset_list").put("path", path)).getJSONArray("items").let { list ->
        buildMap { for(i in 0 until list.length()) { val item = list.getJSONObject(i); if(put(item.getString("id"), item.getLong("offset")) != null) throw LanSyncException("invalid_asset") } }
    }.also { offsets = it }
    suspend fun append(id: String, bytes: ByteArray, progress: (Long) -> Unit = {}) =
        bytes.inputStream().use { append(id, bytes.size.toLong(), it, progress) }

    suspend fun append(id: String, size: Long, source: InputStream, progress: (Long) -> Unit = {}) {
        if (size !in 0..DeviceAssetInputStream.MAX_BYTES) throw LanSyncException("asset_limit")
        if (!id.matches(Regex("[0-9a-fA-F]{64}"))) throw LanSyncException("invalid_asset")
        val buffer = ByteArray(DeviceAssetInputStream.CHUNK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var completed = 0L
        var index = 0
        try {
            do {
                val count = minOf(buffer.size.toLong(), size - completed).toInt()
                var written = 0
                while (written < count) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer, written, count - written)
                    if (n < 0) throw LanSyncException("invalid_asset")
                    if (n == 0) {
                        val one = source.read()
                        if (one < 0) throw LanSyncException("invalid_asset")
                        buffer[written++] = one.toByte()
                    } else written += n
                }
                currentCoroutineContext().ensureActive()
                digest.update(buffer, 0, count)
                completed += count
                val final = completed == size
                if (final && (source.read() != -1 || digest.digest().joinToString("") { "%02x".format(it) } != id.lowercase())) {
                    throw LanSyncException("invalid_asset")
                }
                val plain = buffer.copyOf(count)
                val encoded = try { Base64.getEncoder().encodeToString(plain) } finally { plain.fill(0) }
                RustLanSync.request(JSONObject().put("operation", "asset_append").put("path", path).put("key", key).put("id", id)
                    .put("index", index++).put("final", final).put("data", encoded))
                buffer.fill(0)
                offsets = null
                progress(completed)
            } while (completed < size)
        } finally { buffer.fill(0) }
    }

    suspend fun openStream(descriptor: JSONObject): InputStream {
        val id = descriptor.getString("id")
        val size = descriptor.getLong("size")
        if (size !in 0..DeviceAssetInputStream.MAX_BYTES) throw LanSyncException("asset_limit")
        if (!id.matches(Regex("[0-9a-fA-F]{64}"))) throw LanSyncException("invalid_asset")
        val indexMap = index()
        var offset = indexMap[id] ?: throw LanSyncException("asset_unavailable")
        val context = currentCoroutineContext()
        return DeviceAssetInputStream(id, size, { context.ensureActive() }) {
            val result = RustLanSync.request(JSONObject().put("operation", "asset_read").put("path", path).put("key", key).put("offset", offset))
            val next = result.getLong("next")
            if (next <= offset) throw LanSyncException("invalid_asset")
            offset = next
            DeviceAssetInputStream.Chunk(result.getString("id"), result.getInt("index"), result.getBoolean("final"),
                Base64.getDecoder().decode(result.getString("data")))
        }
    }

    suspend fun read(descriptor: JSONObject): ByteArray {
        val size = descriptor.getLong("size")
        if (size !in 0..DeviceAssetInputStream.MAX_BYTES) throw LanSyncException("asset_limit")
        val bytes = ByteArray(size.toInt())
        try {
            openStream(descriptor).use { stream ->
                var written = 0
                while (written < bytes.size) {
                    val n = stream.read(bytes, written, bytes.size - written)
                    if (n <= 0) throw LanSyncException("invalid_asset")
                    written += n
                }
                if (stream.read() != -1) throw LanSyncException("invalid_asset")
            }
            return bytes
        } catch(error: Throwable) { bytes.fill(0); throw error }
    }
}
