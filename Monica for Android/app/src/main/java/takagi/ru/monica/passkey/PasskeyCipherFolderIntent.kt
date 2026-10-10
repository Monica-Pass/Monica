package takagi.ru.monica.passkey

import android.content.Context
import org.json.JSONObject
import java.util.UUID
import takagi.ru.monica.data.PasskeyEntry

/** Persist explicit moves separately from legacy rows whose source folder was never populated. */
internal object PasskeyCipherFolderIntent {
    data class Change(val token: String, val folderId: String?)
    private val lock = Any()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("passkey_cipher_folder_intent_v1", Context.MODE_PRIVATE)
    private fun key(row: PasskeyEntry) = "${row.bitwardenVaultId}:${row.bitwardenCipherId}:${row.id}"

    suspend fun <T> recordMove(context: Context?, before: PasskeyEntry?, after: PasskeyEntry, commit: suspend () -> T): T {
        if (context == null || before == null || after.id <= 0 || after.bitwardenVaultId == null ||
            after.bitwardenCipherId.isNullOrBlank() || before.bitwardenVaultId != after.bitwardenVaultId ||
            before.bitwardenCipherId != after.bitwardenCipherId || before.bitwardenFolderId == after.bitwardenFolderId) return commit()
        val store = prefs(context)
        val name = key(after)
        val value = JSONObject().put("token", UUID.randomUUID().toString())
            .put("folder", after.bitwardenFolderId ?: JSONObject.NULL).toString()
        val previous = synchronized(lock) {
            val old = store.getString(name, null)
            check(store.edit().putString(name, value).commit()) { "Unable to persist passkey folder move" }
            old
        }
        return try { commit() } catch (error: Throwable) {
            synchronized(lock) {
                if (store.getString(name, null) == value) {
                    val editor = store.edit()
                    if (previous == null) editor.remove(name) else editor.putString(name, previous)
                    check(editor.commit()) { "Unable to restore pending passkey folder move" }
                }
            }
            throw error
        }
    }

    fun pending(context: Context, row: PasskeyEntry): Change? = synchronized(lock) {
        val raw = prefs(context).getString(key(row), null) ?: return@synchronized null
        val json = JSONObject(raw)
        val folder = if (json.isNull("folder")) null else json.getString("folder")
        // A write-ahead intent may survive a failed Room write; never apply it to another row state.
        if (folder != row.bitwardenFolderId) return@synchronized null
        Change(json.getString("token"), folder)
    }

    fun complete(context: Context, row: PasskeyEntry, change: Change?) = synchronized(lock) {
        if (change != null && pending(context, row) == change) {
            check(prefs(context).edit().remove(key(row)).commit()) { "Unable to finish passkey folder move" }
        }
    }

    fun discard(context: Context, row: PasskeyEntry) = synchronized(lock) {
        prefs(context).edit().remove(key(row)).apply()
    }
}
