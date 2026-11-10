package takagi.ru.monica.lansync

import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import java.util.UUID

/** Keeps portable identities and fields that an older Room model cannot represent. */
internal class DeviceRoomVaultMetadata(private val database: PasswordDatabase, private val security: SecurityManager,
    private val lookup: (List<String>) -> JSONObject) {
    private fun tables() {
        val db = database.openHelper.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_password_identity (global_id TEXT PRIMARY KEY NOT NULL,local_id INTEGER UNIQUE NOT NULL REFERENCES password_entries(id) ON DELETE CASCADE,entry_type TEXT NOT NULL DEFAULT 'login')")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_vault_identity(global_id TEXT PRIMARY KEY NOT NULL,local_id TEXT UNIQUE NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_content_extensions(local_id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_attachment_identity(local_id INTEGER PRIMARY KEY NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,portable_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_pending_bindings(local_id TEXT PRIMARY KEY NOT NULL,guard TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_sync_vault_metadata(global_id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL)")
    }
    fun global(address: String): String? {
        tables()
        return if(address.startsWith("local:")) database.openHelper.readableDatabase.query("SELECT global_id FROM device_sync_password_identity WHERE local_id=?", arrayOf(address.substringAfter(':')))
            .use { if(it.moveToFirst()) it.getString(0) else null }
        else database.openHelper.readableDatabase.query("SELECT global_id FROM device_sync_vault_identity WHERE local_id=?", arrayOf(address)).use { if(it.moveToFirst()) it.getString(0) else null }
    }
    fun rootMetadata(): List<DevicePasswordSnapshot> {
        tables()
        return database.openHelper.readableDatabase.query("SELECT global_id,payload,deleted FROM device_sync_vault_metadata").use { cursor -> buildList {
            while(cursor.moveToNext()) { val id=cursor.getString(0); val data=JSONObject(security.decryptDataIfMonicaCiphertext(cursor.getString(1))); val deleted=cursor.getInt(2)!=0
                add(DevicePasswordSnapshot("metadata:$id",id,"metadata",DevicePasskeyShape.guard(data)+":"+deleted,deleted,data)) }
        } }
    }
    fun applyRootMetadata(id: String,address: String?,expected: String?,deleted: Boolean,data: JSONObject): DevicePasswordSnapshot? {
        val owner=address?.removePrefix("metadata:") ?: id
        val previous=rootMetadata().firstOrNull { it.globalId==owner }
        if(previous?.guard != expected && !(previous==null && expected.isNullOrEmpty())) return null
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_vault_metadata(global_id,payload,deleted) VALUES(?,?,?)",
            arrayOf<Any?>(owner,security.encryptDataLegacyCompat(data.toString()),if(deleted)1 else 0))
        return DevicePasswordSnapshot("metadata:$owner",id,"metadata",DevicePasskeyShape.guard(data)+":"+deleted,deleted,data)
    }
    suspend fun prepareIdentities() {
        tables()
        val addresses = database.passwordEntryDao().getDeviceSyncLocalPasswords().map { "local:${it.id}" } +
            database.secureItemDao().getDeviceSyncLocalItems().map { "secure:${it.id}" } +
            database.categoryDao().getDeviceSyncCategories().filter { it.isMonicaLocalCategory() }.map { "folder:${it.id}" }
        val known = mutableMapOf<String,String>()
        addresses.chunked(256).forEach { batch -> val items = lookup(batch).getJSONArray("items"); for(i in 0 until items.length()) {
            val item = items.getJSONObject(i); known[item.getString("local_id")] = item.getString("global_id")
        } }
        addresses.forEach { address ->
            val id = known[address] ?: global(address) ?: UUID.randomUUID().toString()
            if(address.startsWith("local:")) database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_password_identity(global_id,local_id,entry_type) VALUES(?,?,COALESCE((SELECT entry_type FROM device_sync_password_identity WHERE local_id=?),'login'))", arrayOf<Any?>(id, address.substringAfter(':').toLong(), address.substringAfter(':').toLong()))
            else database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_vault_identity(global_id,local_id) VALUES(?,?)", arrayOf(id,address))
        }
    }
    fun save(address: String, data: JSONObject) {
        tables()
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_content_extensions(local_id,payload) VALUES(?,?)", arrayOf(address, security.encryptDataLegacyCompat(data.toString())))
    }
    fun forget(address: String) {
        tables()
        database.openHelper.writableDatabase.execSQL("DELETE FROM device_sync_content_extensions WHERE local_id=?", arrayOf(address))
    }
    fun supplement(address: String, current: JSONObject): JSONObject {
        tables()
        val saved = database.openHelper.readableDatabase.query("SELECT payload FROM device_sync_content_extensions WHERE local_id=?", arrayOf(address)).use {
            if(it.moveToFirst()) JSONObject(security.decryptDataIfMonicaCiphertext(it.getString(0))) else JSONObject()
        }
        val oldFolder = saved.optString("folder")
        current.keys().forEach { name ->
            if(name in listOf("labels","relations","assets","trashed") && saved.has(name)) return@forEach
            if(name in listOf("payload", "extensions") && current.optJSONObject(name) != null) {
                val merged = saved.optJSONObject(name) ?: JSONObject()
                val owned = current.getJSONObject(name); owned.keys().forEach { key -> merged.put(key, owned.get(key)) }; saved.put(name, merged)
            } else saved.put(name, current.get(name))
        }
        if(oldFolder != current.optString("folder")) saved.remove("folder_path")
        return saved
    }
    fun folder(category: Category): JSONObject {
        if (!category.isMonicaLocalCategory()) throw LanSyncException("invalid_folder", "room_folder_scope")
        val address = "folder:${category.id}"
        // Local categories may also have a Bitwarden link. That does not make the
        // local records using them external. A category can also be created after
        // the initial identity scan, so assign its portable identity on demand.
        val id = global(address) ?: run {
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR IGNORE INTO device_sync_vault_identity(global_id,local_id) VALUES(?,?)",
                arrayOf(UUID.randomUUID().toString(), address))
            global(address) ?: throw LanSyncException("invalid_folder", "room_folder_identity")
        }
        val result = supplement("folder:${category.id}", JSONObject().put("title", category.name).put("folder", "").put("entry_type", "folder").put("payload",JSONObject()))
        val payload = result.getJSONObject("payload")
        val path = payload.optJSONArray("path") ?: JSONArray().put(JSONObject().put("id",id).put("title",category.name).put("parent",JSONObject.NULL))
        path.getJSONObject(path.length()-1).put("id", id).put("title", category.name); payload.put("path",path)
        return result
    }
    suspend fun password(entry: PasswordEntry, fields: List<CustomField>, folder: String): JSONObject {
        val data = DevicePasswordShape.fromLocal(entry, fields, folder, security)
        val ext = data.getJSONObject("extensions")
        ext.put("is_favorite", entry.isFavorite).put("sort_order", entry.sortOrder).put("created_at", entry.createdAt.time)
            .put("is_group_cover", entry.isGroupCover).put("password_group_id", entry.passwordGroupId ?: JSONObject.NULL)
        ext.put("bound_note_entry_id",entry.boundNoteId?.let { global("secure:$it") } ?: JSONObject.NULL)
        ext.put("sso_ref_entry_id",entry.ssoRefEntryId?.let { global("local:$it") } ?: JSONObject.NULL)
        val history = database.passwordHistoryDao().getHistoryByEntryIdSync(entry.id)
        data.put("password_history", JSONArray().apply { history.forEach { item -> put(JSONObject().put("password",security.decryptDataIfMonicaCiphertext(item.password))
            .put("lastUsedAt", java.time.Instant.ofEpochMilli(item.lastUsedAt.time).toString())) } })
        data.put("trashed", entry.isDeleted)
        val result = supplement("local:${entry.id}", data)
        if(!result.has("folder_path")) entry.categoryId?.let { database.categoryDao().getCategoryById(it) }?.let { category ->
            global("folder:${category.id}")?.let { result.put("folder_path", folder(category).getJSONObject("payload").getJSONArray("path")) }
        }
        return result
    }
    suspend fun secure(item: SecureItem, folder: String): JSONObject {
        val previous = supplement("secure:${item.id}",JSONObject())
        val current = DeviceVaultShape.fromLocal(item, folder, security)
        if(previous.optString("entry_type") == "steam-mafile") {
            val details = JSONObject(current.getJSONObject("payload").getString("item_data"))
            val raw = JSONObject(details.optString("steamRawJson").ifBlank { previous.getJSONObject("payload").getString("mafile_json") })
            listOf("steamSharedSecretBase64" to "shared_secret","steamIdentitySecret" to "identity_secret","steamDeviceId" to "device_id",
                "steamRevocationCode" to "revocation_code","steamTokenGid" to "token_gid").forEach { (field,key) -> if(details.has(field)) raw.put(key,details.get(field)) }
            current.put("entry_type","steam-mafile"); current.getJSONObject("payload").put("kind","steam_mafile").put("mafile_json",raw.toString())
        }
        val result = supplement("secure:${item.id}", current)
        val p = result.getJSONObject("payload")
        if(item.itemType == ItemType.TOTP) {
            val details = JSONObject(p.optString("item_data", "{}"))
            p.put("bound_password_entry_id",details.optLong("boundPasswordId", -1).takeIf { it > 0 }?.let { global("local:$it") } ?: JSONObject.NULL)
            details.remove("boundPasswordId"); p.put("item_data",details.toString())
        }
        if(!result.has("folder_path")) item.categoryId?.let { database.categoryDao().getCategoryById(it) }?.let { category ->
            global("folder:${category.id}")?.let { result.put("folder_path", folder(category).getJSONObject("payload").getJSONArray("path")) }
        }
        return result
    }
    fun localPassword(global: String?): Long? {
        if(global.isNullOrBlank() || global == "null") return null
        return database.openHelper.readableDatabase.query("SELECT local_id FROM device_sync_password_identity WHERE global_id=?", arrayOf(global)).use { if(it.moveToFirst()) it.getLong(0) else null }
            ?: database.openHelper.readableDatabase.query("SELECT id FROM password_entries WHERE replica_group_id=? AND bitwarden_vault_id IS NULL AND keepassDatabaseId IS NULL LIMIT 1", arrayOf(global)).use { if(it.moveToFirst()) it.getLong(0) else null }
    }
    fun localNote(global: String?): Long? {
        if(global.isNullOrBlank() || global == "null") return null
        return database.openHelper.readableDatabase.query("SELECT local_id FROM device_sync_vault_identity WHERE global_id=?", arrayOf(global)).use { if(it.moveToFirst()) it.getString(0).takeIf { id -> id.startsWith("secure:") }?.substringAfter(':')?.toLongOrNull() else null }
            ?: database.openHelper.readableDatabase.query("SELECT id FROM secure_items WHERE replica_group_id=? AND bitwarden_vault_id IS NULL AND keepass_database_id IS NULL LIMIT 1", arrayOf(global)).use { if(it.moveToFirst()) it.getLong(0) else null }
    }
    fun localFolder(data: JSONObject): Long? {
        val path = data.optJSONArray("folder_path") ?: return null
        if(path.length() == 0) return null
        val id = path.getJSONObject(path.length()-1).getString("id")
        return database.openHelper.readableDatabase.query("SELECT local_id FROM device_sync_vault_identity WHERE global_id=?", arrayOf(id))
            .use { if(it.moveToFirst()) it.getString(0).takeIf { value -> value.startsWith("folder:") }?.substringAfter(':')?.toLongOrNull() else null }
    }
    fun attachmentIdentity(localId: Long): String {
        tables()
        return database.openHelper.readableDatabase.query("SELECT portable_id FROM device_sync_attachment_identity WHERE local_id=?",arrayOf(localId))
            .use { if(it.moveToFirst()) it.getString(0) else null } ?: UUID.randomUUID().toString().also { bindAttachment(localId,it) }
    }
    fun bindAttachment(localId: Long,portableId: String) {
        tables()
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_attachment_identity(local_id,portable_id) VALUES(?,?)",arrayOf<Any?>(localId,portableId))
    }
    suspend fun history(entryId: Long, data: JSONObject) {
        val history = data.optJSONArray("password_history") ?: return
        database.passwordHistoryDao().deleteByEntryId(entryId)
        for(index in 0 until history.length()) {
            val item = history.getJSONObject(index)
            val timestamp = item.opt("lastUsedAt") ?: item.opt("last_used_at")
            val millis = when(timestamp) { is Number -> timestamp.toLong(); is String -> java.time.Instant.parse(timestamp).toEpochMilli(); else -> 0 }
            database.passwordHistoryDao().insert(PasswordHistoryEntry(entryId = entryId, password = security.encryptDataLegacyCompat(item.getString("password")), lastUsedAt = java.util.Date(millis)))
        }
    }
    private suspend fun rowGuard(address: String): String {
        val id = address.substringAfter(':').toLongOrNull() ?: return ""
        val row = when(address.substringBefore(':')) { "local" -> database.passwordEntryDao().getPasswordEntryById(id)
            "secure" -> database.secureItemDao().getItemById(id); "passkey" -> database.passkeyDao().getPasskeyByRecordId(id); else -> null } ?: return ""
        return java.security.MessageDigest.getInstance("SHA-256").digest(row.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    suspend fun markBindingRepair(address: String) {
        tables(); database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_pending_bindings(local_id,guard) VALUES(?,?)",arrayOf(address,rowGuard(address)))
    }
    suspend fun repairBindings() {
        tables()
        val pending = database.openHelper.readableDatabase.query("SELECT local_id,guard FROM device_sync_pending_bindings").use { cursor -> buildMap {
            while(cursor.moveToNext()) put(cursor.getString(0),cursor.getString(1)) } }
        val current = mutableSetOf<String>()
        for((id,guard) in pending) if(rowGuard(id) == guard) current += id
        for(entry in database.passwordEntryDao().getDeviceSyncLocalPasswords().filter { "local:${it.id}" in current }) {
            val data = supplement("local:${entry.id}",JSONObject())
            val p = data.optJSONObject("extensions") ?: continue
            val note = if(p.has("bound_note_entry_id")) localNote(p.optString("bound_note_entry_id")) else entry.boundNoteId
            val sso = if(p.has("sso_ref_entry_id")) localPassword(p.optString("sso_ref_entry_id")) else entry.ssoRefEntryId
            if(entry.boundNoteId != note || entry.ssoRefEntryId != sso) database.passwordEntryDao().updatePasswordEntry(entry.copy(boundNoteId = note,ssoRefEntryId = sso))
        }
        for(entry in database.secureItemDao().getDeviceSyncLocalItems().filter { it.itemType == ItemType.TOTP && "secure:${it.id}" in current }) {
            val p = supplement("secure:${entry.id}",JSONObject()).optJSONObject("payload") ?: continue
            if(!p.has("bound_password_entry_id")) continue
            val desired = localPassword(p.optString("bound_password_entry_id"))
            val details = JSONObject(security.decryptDataIfMonicaCiphertext(entry.itemData))
            if(details.optLong("boundPasswordId",-1) != (desired ?: -1)) {
                details.put("boundPasswordId",desired ?: JSONObject.NULL)
                database.secureItemDao().updateItem(entry.copy(itemData = security.encryptDataLegacyCompat(details.toString())))
            }
        }
        for(entry in database.passkeyDao().getAllPasskeysSync().filter { it.mdbxDatabaseId == null && it.bitwardenVaultId == null && it.keepassDatabaseId == null && "passkey:${it.id}" in current }) {
            val p = supplement("passkey:${entry.id}",JSONObject()).optJSONObject("payload") ?: continue
            if(!p.has("bound_password_entry_id")) continue
            val desired = localPassword(p.optString("bound_password_entry_id"))
            if(entry.boundPasswordId != desired) database.passkeyDao().update(entry.copy(boundPasswordId = desired))
        }
        pending.keys.forEach { id -> database.openHelper.writableDatabase.execSQL("DELETE FROM device_sync_pending_bindings WHERE local_id=?",arrayOf(id)) }
    }
}
