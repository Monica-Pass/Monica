package takagi.ru.monica.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.security.SecurityManager
import java.util.UUID

/** Construct/read only on IO after secure startup and authentication. Secrets never enter WorkData. */
internal class FolderBackupConfig(
    val tree: String = "", val label: String = "", val password: String = "",
    val automatic: Boolean = false, val afterChanges: Boolean = true, val keep: Int = 10,
    val revision: String = "", val keyId: String = "",
) {
    val configured get() = tree.isNotBlank() && password.isNotBlank()
}

internal class FolderBackupStore(context: Context) {
    private val security = SecurityManager(context.applicationContext)
    private fun read() = security.getProtectedString(KEY)?.let(::JSONObject) ?: JSONObject()
    private fun write(value: JSONObject) = security.putProtectedString(KEY, value.toString())

    fun config(): FolderBackupConfig = synchronized(lock) {
        val j = read()
        FolderBackupConfig(j.optString("tree"), j.optString("label"), j.optString("password"),
            j.optBoolean("automatic"), j.optBoolean("afterChanges", true), j.optInt("keep", 10).coerceIn(1, 1000),
            j.optString("revision"), j.optString("keyId"))
    }

    fun configure(tree: String, label: String, password: String, automatic: Boolean,
        afterChanges: Boolean, keep: Int) = synchronized(lock) {
        require(tree.isNotBlank() && password.isNotBlank() && keep in 1..1000)
        val j = read()
        if (j.optString("password") != password || j.optString("keyId").isBlank()) j.put("keyId", UUID.randomUUID().toString())
        j.put("tree", tree).put("label", label).put("password", password)
            .put("automatic", automatic).put("afterChanges", afterChanges).put("keep", keep)
            .put("revision", UUID.randomUUID().toString())
        write(j)
    }

    fun records(): List<FolderBackupRecord> = synchronized(lock) {
        val array = read().optJSONArray("records") ?: JSONArray()
        (0 until array.length()).map { index ->
            val j = array.getJSONObject(index)
            FolderBackupRecord(j.getString("uri"), j.getString("tree"), j.getString("name"),
                j.getLong("size"), j.getString("sha256"), j.getLong("createdAt"), j.getString("keyId"),
                j.getBoolean("permanent"), j.getBoolean("complete"))
        }
    }

    fun saveRecord(record: FolderBackupRecord) = updateRecords { records ->
        check(records.size < 10_000 || records.any { it.uri == record.uri }) { "Backup history is full" }
        records.filterNot { it.uri == record.uri } + record
    }
    fun forgetRecord(uri: String) = updateRecords { records -> records.filterNot { it.uri == uri } }
    private fun updateRecords(update: (List<FolderBackupRecord>) -> List<FolderBackupRecord>) = synchronized(lock) {
        val j = read()
        val array = JSONArray()
        update(records()).forEach { r -> array.put(JSONObject().put("uri", r.uri).put("tree", r.tree)
            .put("name", r.name).put("size", r.size).put("sha256", r.sha256).put("createdAt", r.createdAt)
            .put("keyId", r.keyId).put("permanent", r.permanent).put("complete", r.complete)) }
        j.put("records", array)
        write(j)
    }

    companion object {
        private const val KEY = "folder_backup_v1"
        private val lock = Any()
    }
}
