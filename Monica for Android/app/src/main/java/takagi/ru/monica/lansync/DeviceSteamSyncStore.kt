package takagi.ru.monica.lansync

import androidx.room.withTransaction
import org.json.JSONObject
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.steam.data.SteamAccount
import takagi.ru.monica.steam.data.SteamAccountEntity
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.steam.importer.SteamMaFilePayload

/** The Steam page has its own encrypted Room store, separate from authenticator entries. */
internal class DeviceSteamSyncStore(
    private val database: SteamDatabase,
    private val security: SecurityManager,
    private val folder: suspend (Long?) -> JSONObject?
) {
    private val dao = database.steamAccountDao()
    private val repository = SteamAccountRepository(dao, security)

    private fun identities() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS device_sync_steam_identity (global_id TEXT PRIMARY KEY NOT NULL, " +
                "local_id INTEGER UNIQUE NOT NULL REFERENCES steam_accounts(id) ON DELETE CASCADE, payload TEXT NOT NULL)"
        )
    }

    suspend fun snapshots(): List<DevicePasswordSnapshot> = database.withTransaction {
        identities()
        dao.getAccounts().map { snapshot(it) }
    }

    private suspend fun snapshot(entity: SteamAccountEntity): DevicePasswordSnapshot {
        identities()
        var global = ""
        var saved = JSONObject()
        database.openHelper.readableDatabase.query(
            "SELECT global_id,payload FROM device_sync_steam_identity WHERE local_id=?", arrayOf(entity.id)
        ).use {
            if (it.moveToFirst()) {
                global = it.getString(0)
                saved = JSONObject(security.decryptData(it.getString(1)))
            }
        }
        val account = repository.decryptEntity(entity)
        val parent = folder(account.categoryId)
        val payload = saved.optJSONObject("payload") ?: JSONObject()
        payload.put("kind", "steam_mafile").put("mafile_json", SteamMaFileBackupCodec.encode(account))
            .put("sort_order", account.sortOrder).put("created_at", account.createdAt)
        saved.put("entry_type", "steam-mafile").put("title", account.displayName)
            .put("folder", parent?.getString("title").orEmpty()).put("payload", payload)
        saved.remove("folder_path")
        parent?.let { saved.put("folder_path", it.getJSONObject("payload").getJSONArray("path")) }
        DeviceVaultShape.validate(saved)
        return DevicePasswordSnapshot("steam:${entity.id}", global, "steam", DevicePasskeyShape.guard(saved), false, saved)
    }

    suspend fun apply(
        globalId: String, address: String?, expected: String?, deleted: Boolean,
        data: JSONObject, categoryId: Long?
    ): DevicePasswordSnapshot? = database.withTransaction {
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        identities()
        val mapped = address?.removePrefix("steam:")?.toLongOrNull() ?: database.openHelper.readableDatabase.query(
            "SELECT local_id FROM device_sync_steam_identity WHERE global_id=?", arrayOf(globalId)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        val previous = mapped?.let { dao.getById(it) }
        val current = previous?.let { snapshot(it) }
        if (current?.guard != expected && !(previous == null && expected.isNullOrEmpty())) return@withTransaction null
        if (deleted) {
            previous?.let { repository.delete(it.id) }
            return@withTransaction DevicePasswordSnapshot(address.orEmpty(), globalId, "steam", "", true, JSONObject())
        }
        val parsed = parse(data)
        if ((data.optJSONArray("assets")?.length() ?: 0) != 0) throw LanSyncException("unsupported_vault_content")
        val payload = data.getJSONObject("payload")
        val now = System.currentTimeMillis()
        val account = SteamAccount(
            id = previous?.id ?: 0L, steamId = parsed.steamId, accountName = parsed.accountName,
            displayName = data.getString("title"), deviceId = parsed.deviceId, sharedSecret = parsed.sharedSecret,
            identitySecret = parsed.identitySecret, revocationCode = parsed.revocationCode, tokenGid = parsed.tokenGid,
            accessToken = parsed.accessToken, refreshToken = parsed.refreshToken, steamLoginSecure = parsed.steamLoginSecure,
            rawSteamGuardJson = parsed.rawJson, selected = previous?.selected ?: (dao.count() == 0),
            sortOrder = if (payload.has("sort_order")) payload.getInt("sort_order") else previous?.sortOrder ?: dao.nextSortOrder(),
            createdAt = if (payload.has("created_at")) payload.getLong("created_at") else previous?.createdAt ?: now,
            updatedAt = now, categoryId = categoryId
        )
        val encrypted = repository.encryptEntity(account)
        val id = if (previous == null) dao.insertCopy(encrypted) else { dao.update(encrypted); previous.id }
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO device_sync_steam_identity(global_id,local_id,payload) VALUES(?,?,?)",
            arrayOf<Any?>(globalId, id, security.encryptData(data.toString()))
        )
        snapshot(requireNotNull(dao.getById(id)))
    }

    companion object {
        fun parse(data: JSONObject): SteamMaFilePayload {
            try {
                if (data.getString("entry_type") != "steam-mafile") throw IllegalArgumentException()
                val payload = data.getJSONObject("payload")
                val raw = payload.get("mafile_json") as? String ?: throw IllegalArgumentException()
                // This entry is already decrypted by the transport. Never invoke encrypted-file import here.
                if (!raw.trimStart().startsWith("{")) throw IllegalArgumentException()
                for (field in listOf("sort_order", "created_at")) {
                    if (payload.has(field) && (payload.opt(field) !is Number || payload.getLong(field) < 0)) {
                        throw IllegalArgumentException()
                    }
                }
                if (payload.has("sort_order") && payload.getLong("sort_order") > Int.MAX_VALUE) throw IllegalArgumentException()
                return SteamMaFileParser().parse(raw, allowMissingSteamId = true)
            } catch (_: Exception) {
                // Parser exceptions may include input. Keep credential material out of diagnostics.
                throw LanSyncException("invalid_vault_payload")
            }
        }
    }
}
