package takagi.ru.monica.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Private, non-exported accounting; reservations precede hashing, including aborted attempts. */
internal class RecoveryAttemptLimiter(context: Context) {
    private val app = context.applicationContext
    private val directory = File(context.applicationInfo.dataDir, "no_backup/recovery-attempts")
    private val file = AtomicFile(File(directory, "state.json"))
    private val monitor = monitors.getOrPut(directory.absolutePath) { Any() }
    data class Status(val attempts: Int, val retryAfterMillis: Long) {
        val blocked get() = retryAfterMillis > 0
    }
    companion object {
        const val MAX_ATTEMPTS = 3
        const val COOLDOWN = 15 * 60_000L
        private val monitors = ConcurrentHashMap<String, Any>()
    }
    private fun <T> locked(action: () -> T) = synchronized(monitor) {
        check(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(File(directory, "state.lock"), "rw").channel.use { channel ->
            channel.lock().use { action() }
        }
    }
    private fun boot() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject().put("count", 0)
        return file.openRead().use {
            val bytes = it.readBytesLimited()
            JSONObject(bytes.toString(Charsets.UTF_8)).also { obj ->
                require(obj.getInt("count") in 0..MAX_ATTEMPTS)
                if (obj.getInt("count") > 0) {
                    require(obj.getLong("wall") > 0 && obj.getLong("elapsed") >= 0)
                    obj.getInt("boot")
                }
            }
        }
    }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val bytes = ByteArray(4097)
        var size = 0
        while (size < bytes.size) {
            val n = read(bytes, size, bytes.size - size)
            if (n < 0) break
            size += n
        }
        require(size <= 4096)
        return bytes.copyOf(size)
    }
    private fun save(state: JSONObject) {
        val out = file.startWrite()
        try { out.write(state.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    private fun statusOf(state: JSONObject): Status {
        val count = state.getInt("count")
        if (count == 0) return Status(0, 0)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val wallAge = nowWall - state.getLong("wall")
        val sameBoot = boot() >= 0 && boot() == state.getInt("boot")
        val elapsedAge = nowElapsed - state.getLong("elapsed")
        // Rollback or unknown/restarted monotonic clock renews the bounded cooldown;
        // a wall-clock jump alone cannot clear a same-boot limit.
        if (wallAge < 0 || (sameBoot && elapsedAge < 0)) {
            state.put("wall", nowWall).put("elapsed", nowElapsed).put("boot", boot())
            save(state)
            return Status(count, if (count >= MAX_ATTEMPTS) COOLDOWN else 0)
        }
        val age = if (sameBoot) minOf(wallAge, elapsedAge) else wallAge
        if (age >= COOLDOWN) { save(JSONObject().put("count", 0)); return Status(0, 0) }
        return Status(count, if (count >= MAX_ATTEMPTS) COOLDOWN - age else 0)
    }
    fun status(): Status = try { locked { statusOf(read()) } }
        catch (_: Exception) { Status(MAX_ATTEMPTS, COOLDOWN) }
    fun reserve(): Boolean = try {
        locked {
            val state = read()
            val status = statusOf(state)
            if (status.blocked) false else {
                val next = if (status.attempts == 0) JSONObject().put("wall", System.currentTimeMillis())
                    .put("elapsed", SystemClock.elapsedRealtime()).put("boot", boot()) else state
                save(next.put("count", status.attempts + 1))
                true
            }
        }
    } catch (_: Exception) { false }
    fun clearAfterAuthenticatedRecovery() = locked { save(JSONObject().put("count", 0)) }
}
