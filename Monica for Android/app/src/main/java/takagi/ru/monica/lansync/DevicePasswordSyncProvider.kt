package takagi.ru.monica.lansync

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.data.*
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.viewmodel.MdbxViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class DevicePasswordExport(val revision: String, val directory: File, val contentKey: String,
    val passkeyIssues: List<DevicePasskeySyncIssue> = emptyList()) {
    override fun toString() = "DevicePasswordExport($revision)"
}

internal class DevicePasswordSyncProvider(context: Context, private val security: SecurityManager, private val preferences: DeviceSyncPreferenceStore,
    suppliedDatabase: PasswordDatabase? = null, private val replicaPath: File? = null,
    suppliedSteamDatabase: takagi.ru.monica.steam.data.SteamDatabase? = null) {
    private val app = context.applicationContext
    private val database = suppliedDatabase ?: PasswordDatabase.getDatabase(app)
    private val passwords = database.passwordEntryDao()
    private val fields = database.customFieldDao()
    private val categories = database.categoryDao()
    private val secureItems = database.secureItemDao()
    private val passkeys = database.passkeyDao()
    private val attachments = database.attachmentDao()
    private val attachmentStorage = takagi.ru.monica.attachments.storage.AttachmentStorage(app)
    private val attachmentKeys = takagi.ru.monica.attachments.storage.AttachmentKeyVault(security)
    private val metadataAssets = DeviceMetadataAssets(app, database, security)
    private val images = takagi.ru.monica.util.ImageManager(app)
    private var assets: List<DeviceVaultAsset> = emptyList()
    private var passkeyIssues: List<DevicePasskeySyncIssue> = emptyList()
    private var incomingAssets: DeviceAssetTransfer? = null
    private var preparedAssets: DeviceAssetTransfer? = null
    private var fallbackAssets: DeviceAssetTransfer? = null
    private val metadata = DeviceRoomVaultMetadata(database, security) { ids -> request("replica_local") { put("local_ids", JSONArray(ids)) } }
    private val steamDatabase = suppliedSteamDatabase ?: takagi.ru.monica.steam.data.SteamDatabase.getDatabase(app)
    private val steam = DeviceSteamSyncStore(steamDatabase, security) { categoryId ->
        categoryId?.let { categories.getCategoryById(it) }?.takeIf { it.isMonicaLocalCategory() }?.let(metadata::folder)
    }
    var onProgress: ((DeviceSyncProgress) -> Unit)? = null
    private val native = Mdbx2Repository(app, database.localMdbxDatabaseDao(), security, passwords, database.secureItemDao(), fields)
    private val gate = Mutex()
    private val changes = AtomicLong(1)
    private var sourceRevision: String? = null
    private var handle: Long? = null
    private var nodeId: String = ""
    private var selectedDatabaseId: Long? = null
    private val viewModels = ViewModelStore()
    private var projection: MdbxViewModel? = null
    private val pendingProjections = mutableSetOf<Long>()
    private var primaryNativeId: Long? = null
    private var observedChanges = -1L
    private var lastRevisionCheck = 0L
    private val observer = object : InvalidationTracker.Observer("password_entries", "custom_fields", "categories", "local_mdbx_databases", "secure_items", "passkeys", "password_history_entries", "attachments") {
        override fun onInvalidated(tables: Set<String>) { changes.incrementAndGet() }
    }
    private val steamObserver = object : InvalidationTracker.Observer("steam_accounts") {
        override fun onInvalidated(tables: Set<String>) { changes.incrementAndGet() }
    }
    init {
        database.invalidationTracker.addObserver(observer)
        steamDatabase.invalidationTracker.addObserver(steamObserver)
    }

    private fun request(operation: String, block: JSONObject.() -> Unit = {}): JSONObject =
        RustLanSync.request(JSONObject().put("operation", operation).put("handle", handle).apply(block))

    private fun identityTable() {
        database.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS device_sync_password_identity (global_id TEXT PRIMARY KEY NOT NULL, local_id INTEGER UNIQUE NOT NULL REFERENCES password_entries(id) ON DELETE CASCADE, entry_type TEXT NOT NULL DEFAULT 'login')")
    }

    private fun identities(): Map<Long, Pair<String, String>> {
        identityTable()
        return buildMap { database.openHelper.readableDatabase.query("SELECT local_id,global_id,entry_type FROM device_sync_password_identity").use { cursor ->
            while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getString(1) to cursor.getString(2))
        } }
    }

    private fun ensureOpen() {
        if (handle != null && selectedDatabaseId == preferences.selectedDatabaseId()) return
        val settings = preferences.load()
        handle?.let { request("replica_close") }
        handle = null; sourceRevision = null; assets = emptyList(); preparedAssets = null
        passkeyIssues = emptyList(); primaryNativeId = null
        selectedDatabaseId = settings.databaseId
        val target = selectedDatabaseId?.let { "mdbx-$it" } ?: "local"
        // Each selected store owns an independent replica and logical writer. Never
        // scan a reduced scope into the old all-vault replica (that implies deletes).
        val writer = java.util.UUID.nameUUIDFromBytes("${settings.deviceId}:$target".toByteArray()).toString()
        val path = replicaPath?.let { File(it.parentFile, "${it.name}-$target") }
            ?: File(app.filesDir, "device-sync/$target.replica")
        handle = RustLanSync.request(JSONObject().put("operation", "replica_open").put("device_id", writer)
            .put("key", settings.replicaKey).put("path", path.absolutePath)).getLong("handle")
        nodeId = writer
    }

    private suspend fun reconcile(force: Boolean = false) {
        currentCoroutineContext().ensureActive()
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        preparingDeviceSync("refresh_projection") { refreshProjections() }
        ensureOpen()
        val observed = changes.get()
        val checkedAt = android.os.SystemClock.elapsedRealtime()
        // App writes invalidate Room immediately. The fallback catches externally changed files
        // without reopening encrypted native stores on every connection-progress tick.
        if (!force && sourceRevision != null && observed == observedChanges && checkedAt - lastRevisionCheck < 5000) return
        val nativeDatabases = selectedNativeDatabases()
        val revisions = nativeDatabases.map { preparingDeviceSync("mdbx_revision_${it.id}") { "${it.id}:${native.deviceSyncRevision(it.id)}" } }
        val marker = observed.toString() + revisions.joinToString(";")
        observedChanges = observed; lastRevisionCheck = checkedAt
        if (!force && marker == sourceRevision) return
        onProgress?.invoke(DeviceSyncProgress("preparing"))
        val local = mutableListOf<DevicePasswordSnapshot>()
        val issues = mutableListOf<DevicePasskeySyncIssue>()
        if (selectedDatabaseId == null) {
            preparingDeviceSync("room_identities") { metadata.prepareIdentities() }
            local += database.withTransaction {
                val identities = identities()
                val entries = passwords.getDeviceSyncLocalPasswords()
                val fieldsById = if (entries.isEmpty()) emptyMap() else fields.getFieldsByEntryIds(entries.map { it.id }).groupBy { it.entryId }
                val categoryNames = categories.getDeviceSyncCategories().associate { it.id to it.name }
                entries.map { entry ->
                    val data = preparingDeviceSync("room_password_${entry.id}") { metadata.password(entry, fieldsById[entry.id].orEmpty(), categoryNames[entry.categoryId].orEmpty()) }
                    data.put("entry_type", identities[entry.id]?.second ?: "login")
                    val snapshot = DevicePasswordSnapshot("local:${entry.id}", identities[entry.id]?.first.orEmpty(), "local", DevicePasswordShape.guard(entry, data), entry.isDeleted, data)
                    withLocalAssets(snapshot, attachments.getAllByParent(entry.id).filter { !it.isDeleted || entry.isDeleted })
                }
            }.toMutableList()
            val folderNames = categories.getDeviceSyncCategories().associate { it.id to it.name }
            val vaultIds = vaultIdentities()
            secureItems.getDeviceSyncLocalItems().forEach { item ->
                val data = preparingDeviceSync("room_item_${item.id}") { metadata.secure(item, folderNames[item.categoryId].orEmpty()) }
                val address = "secure:${item.id}"
                local += withLocalAssets(DevicePasswordSnapshot(address, vaultIds[address].orEmpty(), "secure", DevicePasskeyShape.guard(data) + ":" + item.isDeleted, item.isDeleted, data),
                    attachments.getAllBySecureItem(item.id).filter { !it.isDeleted || item.isDeleted })
            }
            passkeys.getAllPasskeysSync().filter { it.isDeviceSyncLocal() }.forEach { item ->
                val data = preparingDeviceSync("room_passkey_${item.id}") { localPasskey(item) }
                val address = "passkey:${item.id}"
                val exported = DevicePasswordSnapshot(address, vaultIds[address] ?: DevicePasskeyShape.identity(item), "passkeys", DevicePasskeyShape.guard(data), false, data)
                local += exported
                DevicePasskeySyncIssue.from(exported, item.signCount)?.let(issues::add)
            }
            categories.getDeviceSyncCategories().filter { it.isMonicaLocalCategory() }.forEach { category ->
                val data = preparingDeviceSync("room_folder_${category.id}") { metadata.folder(category) }
                local += metadataAssets.snapshot(DevicePasswordSnapshot("folder:${category.id}", metadata.global("folder:${category.id}").orEmpty(), "folders", DevicePasskeyShape.guard(data), false, data))
            }
            local += preparingDeviceSync("room_metadata") { metadata.rootMetadata().map(metadataAssets::snapshot) }
            local += preparingDeviceSync("steam_accounts") { steam.snapshots() }
        }
        nativeDatabases.forEach {
            val exported = preparingDeviceSync("mdbx_export_${it.id}") { native.deviceSyncPasswords(it.id) }
            local += exported
            issues += exported.mapNotNull { entry -> DevicePasskeySyncIssue.from(entry) }
        }
        normalizeNativeReferences(local)
        assets = local.flatMap { it.assets }.distinctBy { it.id }
        primaryNativeId = selectedDatabaseId
        request("replica_scan_begin") { put("scopes", JSONArray(if (selectedDatabaseId == null) listOf("local", "secure", "passkeys", "folders", "metadata", "steam") else nativeDatabases.map { "mdbx:${it.id}" })) }
        var batch = JSONArray()
        var bytes = 0
        fun flush() {
            if (batch.length() > 0) request("replica_scan_batch") { put("entries", batch) }
            batch = JSONArray(); bytes = 0
        }
        local.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            val item = entry.json()
            val size = item.toString().toByteArray().size
            if (batch.length() >= 256 || bytes + size > 4 * 1024 * 1024) flush()
            batch.put(item); bytes += size
            if(index % 64 == 0 || index == local.lastIndex) onProgress?.invoke(DeviceSyncProgress("preparing", (index + 1).toLong(), local.size.toLong()))
        }
        flush()
        request("replica_scan_end")
        passkeyIssues = issues.toList()
        sourceRevision = marker
    }

    private suspend fun selectedNativeDatabases(): List<LocalMdbxDatabase> {
        val id = selectedDatabaseId ?: return emptyList()
        val selected = database.localMdbxDatabaseDao().getDatabaseById(id)
            ?.takeIf { it.isUsable } ?: throw LanSyncException("sync_database_unavailable", "selected_database")
        return listOf(selected)
    }

    private fun revisionStamp(result: JSONObject): String = result.getString("revision") + if (result.optBoolean("pending")) "~pending" else ""

    suspend fun revision(): String = gate.withLock { reconcile(); revisionStamp(request("replica_revision")) }

    suspend fun currentPasskeyIssues(): List<DevicePasskeySyncIssue> = gate.withLock { reconcile(); passkeyIssues }

    suspend fun prepare(directory: File): DevicePasswordExport = gate.withLock {
        reconcile()
        check(directory.mkdirs() || directory.isDirectory)
        val result = request("replica_export") { put("path", File(directory, "changes.mdbxsync").absolutePath); put("skip_nonzero_passkeys", true) }
        val transfer = DeviceAssetTransfer(directory, result.getString("content_key"))
        val ids = result.getJSONArray("asset_ids").let { list -> (0 until list.length()).map(list::getString).toSet() }
        val outgoingAssets = assets.filter { it.id in ids }
        val total = outgoingAssets.sumOf { it.size }; var completed = 0L
        for(asset in outgoingAssets) {
            val bytes = readAsset(asset)
            try { transfer.append(asset.id, bytes) { count -> onProgress?.invoke(DeviceSyncProgress("preparing", bytes = completed + count, totalBytes = total)) } }
            finally { bytes.fill(0) }
            completed += asset.size
        }
        preparedAssets = transfer
        DevicePasswordExport(revisionStamp(result), directory, result.getString("content_key"), passkeyIssues)
    }

    suspend fun apply(directory: File, peer: JSONObject,
        reviewDeletions: suspend (Int) -> Boolean): Int = gate.withLock {
        reconcile(force = true)
        incomingAssets = DeviceAssetTransfer(directory, peer.getJSONObject("pairing").getString("content_key"))
        var localAssetCache: File? = null
        var changed = 0
        var retries = 0
        try {
        fallbackAssets = preparedAssets
        if(assets.isNotEmpty() && (fallbackAssets == null || assets.any { fallbackAssets?.contains(it.id) != true })) {
            val cache = File(directory, "local-assets-${java.util.UUID.randomUUID()}"); check(cache.mkdirs()); localAssetCache = cache
            val keyBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes); keyBytes.fill(0)
            fallbackAssets = DeviceAssetTransfer(cache,key)
            for(asset in assets) { val bytes = readAsset(asset); try { fallbackAssets!!.append(asset.id,bytes) } finally { bytes.fill(0) } }
        }
        fun incoming(operation: String, extra: JSONObject.() -> Unit = {}) = request(operation) {
            put("path", File(directory, "changes.mdbxsync").absolutePath)
            put("content_key", peer.getJSONObject("pairing").getString("content_key")); extra()
        }
        var reviewAttempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val preview = incoming("replica_merge_preview")
            val count = preview.getInt("deletions")
            val include = count == 0 || reviewDeletions(count)
            currentCoroutineContext().ensureActive()
            check(SessionManager.isUnlocked.value) { "device_sync_locked" }
            // Re-read physical data after a possibly long user decision. A new
            // local edit or selected database invalidates the previous preview.
            reconcile(force = true)
            val latest = incoming("replica_merge_preview")
            if (latest.getString("review_revision") != preview.getString("review_revision")) {
                if (++reviewAttempts > 4) throw LanSyncException("passwords_changing")
                continue
            }
            incoming("replica_merge") {
                put("review_revision", latest.getString("review_revision"))
                put("deletion_policy", if (include) "include" else "omit")
            }
            break
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            check(SessionManager.isUnlocked.value) { "device_sync_locked" }
            val page = request("replica_pending") { put("dependency_order",true) }
            val pending = page.getJSONArray("items")
            val total = changed.toLong() + page.getLong("total")
            if (pending.length() == 0) break
            for (index in 0 until pending.length()) {
                val record = pending.getJSONObject(index).getJSONObject("record")
                if (!record.getBoolean("deleted") || record.getJSONObject("data").optBoolean("trashed")) DeviceVaultShape.validate(record.getJSONObject("data"))
            }
            val acknowledgments = JSONArray()
            var raced = false
            for (index in 0 until pending.length()) {
                currentCoroutineContext().ensureActive()
                val item = pending.getJSONObject(index)
                val record = item.getJSONObject("record")
                val localId = item.optString("local_id").takeUnless { it.isBlank() || it == "null" }
                val expected = item.optString("expected_guard").takeUnless { it == "null" }
                val id = record.getString("id")
                val original = record.getJSONObject("data")
                val nativeScope = when {
                    localId?.startsWith("mdbx:") == true -> localId.split(':',limit=3).take(2).joinToString(":")
                    localId == null && primaryNativeId != null -> "mdbx:$primaryNativeId"
                    else -> null
                }
                val data = resolveIncomingReferences(original,nativeScope)
                val deleted = record.getBoolean("deleted")
                val applied = if(localId?.startsWith("metadata:") == true || (localId==null && primaryNativeId==null && data.optString("entry_type")=="vault-metadata")) {
                    applyLocalRootMetadata(id,localId,expected,deleted,data)
                } else if (localId?.startsWith("folder:") == true || ((!deleted || data.optBoolean("trashed")) && data.optString("entry_type") == "folder" && primaryNativeId == null)) {
                    applyLocalFolder(id, localId, expected, deleted, data)
                } else if (localId?.startsWith("steam:") == true ||
                    (localId == null && primaryNativeId == null && !deleted && data.optString("entry_type") == "steam-mafile")) {
                    val category = if (deleted) null else metadata.localFolder(data) ?: data.optString("folder").takeIf { it.isNotEmpty() }?.let { name ->
                        categories.getDeviceSyncCategories().firstOrNull { it.name == name && it.isMonicaLocalCategory() }?.id
                            ?: categories.insert(Category(name = name))
                    }
                    steam.apply(id, localId, expected, deleted, data, category)
                } else if (localId?.startsWith("secure:") == true || localId?.startsWith("passkey:") == true ||
                    (!deleted && data.optString("entry_type") == "passkey" && localId == null && primaryNativeId == null)) {
                    applyLocalVault(id, localId, expected, deleted, data)
                } else if (localId?.startsWith("mdbx:") == true) {
                    val parts = localId.split(':', limit = 3)
                    val databaseId = parts[1].toLong()
                    native.applyDeviceSyncPassword(databaseId, parts[2], id, expected, deleted, data, ::readIncomingAsset)?.also { pendingProjections += databaseId }
                } else if (localId == null && primaryNativeId != null) {
                    val databaseId = requireNotNull(primaryNativeId)
                    native.applyDeviceSyncPassword(databaseId, null, id, expected, deleted, data, ::readIncomingAsset)?.also { pendingProjections += databaseId }
                } else if ((!deleted || data.optBoolean("trashed")) && data.optString("entry_type") !in listOf("login", "ssh-key")) applyLocalVault(id, localId, expected, deleted, data)
                else applyLocal(id, localId?.substringAfter("local:")?.toLongOrNull(), expected, deleted, data)
                if (applied == null) { raced = true; break }
                acknowledgments.put(JSONObject().put("record_id", id).put("clock", record.getJSONObject("clock"))
                    .put("local_id", applied.localId).put("scope", applied.scope).put("guard", applied.guard).put("data", if(applied.scope.startsWith("mdbx:")) original else applied.data))
                changed++
                onProgress?.invoke(DeviceSyncProgress("applying", changed.toLong(), total))
            }
            if (acknowledgments.length() > 0) request("replica_applied_batch") { put("items", acknowledgments) }
            if (raced) { if (++retries > 4) throw LanSyncException("passwords_changing"); reconcile(force = true) }
        }
        selectedNativeDatabases().forEach { native.resolveDeviceSyncRelations(it.id,ensureNativePasskeyTargets(it.id)) }
        refreshProjections()
        metadata.repairBindings()
        changed
        } finally {
            incomingAssets = null
            fallbackAssets = null; localAssetCache?.deleteRecursively()
            sourceRevision = null
            if (SessionManager.isUnlocked.value) withContext(NonCancellable) { refreshProjections() }
        }
    }

    private fun vaultIdentityTable() = database.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS device_sync_vault_identity(global_id TEXT PRIMARY KEY NOT NULL,local_id TEXT UNIQUE NOT NULL)")
    private fun normalizeNativeReferences(snapshots: MutableList<DevicePasswordSnapshot>) {
        val known = mutableMapOf<String,String>()
        snapshots.chunked(256).forEach { batch -> val items = request("replica_local") { put("local_ids",JSONArray(batch.map { it.localId })) }.getJSONArray("items")
            for(index in 0 until items.length()) items.getJSONObject(index).let { known[it.getString("local_id")] = it.getString("global_id") }
        }
        val copied = snapshots.filter { it.scope.startsWith("mdbx:") }.groupBy { it.globalId }.filter { (_,rows) -> rows.map { it.scope }.distinct().size > 1 }.keys
        val maps = mutableMapOf<String,MutableMap<String,String>>()
        snapshots.indices.forEach { index -> val item = snapshots[index]
            if(!item.localId.startsWith("mdbx:")) return@forEach
            val global = known[item.localId] ?: if(item.globalId in copied) {
                val hex = java.security.MessageDigest.getInstance("SHA-256").digest("device-object:$nodeId:${item.localId}".toByteArray()).joinToString("") { "%02x".format(it) }
                "${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
            } else item.globalId
            snapshots[index] = item.copy(globalId = global)
            maps.getOrPut(item.scope) { mutableMapOf() }[item.localId.removePrefix(item.scope+":").removePrefix("folder:")] = global
        }
        snapshots.forEach { item -> maps[item.scope]?.let { map -> DeviceVaultShape.remapReferences(item.data) { map[it] ?: it } } }
        val aliases = mutableMapOf<String,String>()
        snapshots.flatMap { DeviceVaultShape.referenceIds(it.data) }.distinct().chunked(256).forEach { batch ->
            val items = request("replica_resolve") { put("ids",JSONArray(batch)) }.getJSONArray("items")
            for(index in 0 until items.length()) items.getJSONObject(index).let { aliases[it.getString("id")] = it.getString("global_id") }
        }
        snapshots.forEach { DeviceVaultShape.remapReferences(it.data) { id -> aliases[id] ?: id } }
    }
    private fun resolveIncomingReferences(data: JSONObject,scope: String?): JSONObject {
        val result = JSONObject(data.toString()); val map = mutableMapOf<String,String>()
        DeviceVaultShape.referenceIds(result).chunked(256).forEach { batch ->
            val items = request("replica_resolve") { put("ids",JSONArray(batch)); if(scope != null) put("scope",scope) }.getJSONArray("items")
            for(index in 0 until items.length()) {
                val item = items.getJSONObject(index); val local = item.optString("local_id")
                map[item.getString("id")] = if(scope != null && local.startsWith(scope+":")) local.removePrefix(scope+":").removePrefix("folder:") else item.getString("global_id")
            }
        }
        DeviceVaultShape.remapReferences(result) { map[it] ?: it }; return result
    }
    private suspend fun ensureNativePasskeyTargets(databaseId: Long): Map<String,String> {
        val scope = "mdbx:$databaseId"; val targets = mutableMapOf<String,String>()
        native.deviceSyncRelationTargets(databaseId).chunked(256).forEach { batch ->
            val items = request("replica_resolve") { put("ids",JSONArray(batch)); put("scope",scope); put("include_data",true) }.getJSONArray("items")
            for(index in 0 until items.length()) {
                val item = items.getJSONObject(index); val global = item.getString("global_id"); val local = item.optString("local_id")
                val target = if(local.startsWith(scope+":")) local.removePrefix(scope+":") else global
                targets[item.getString("id")] = target
                if(native.deviceSyncObjectExists(databaseId,target)) continue
                val data = item.optJSONObject("data") ?: throw LanSyncException("reference_unavailable")
                if(item.optBoolean("deleted") || data.optString("entry_type") != "passkey") throw LanSyncException("reference_unavailable")
                DevicePasskeyShape.verify(data)
                if(native.applyDeviceSyncPassword(databaseId,null,global,null,false,resolveIncomingReferences(data,scope),::readIncomingAsset) == null) throw LanSyncException("passwords_changing")
                pendingProjections += databaseId
            }
        }
        return targets
    }
    private fun vaultIdentities(): Map<String, String> {
        vaultIdentityTable()
        return buildMap { database.openHelper.readableDatabase.query("SELECT local_id,global_id FROM device_sync_vault_identity").use { cursor ->
            while(cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        } }
    }
    private suspend fun applyLocalRootMetadata(globalId: String, address: String?, expected: String?, deleted: Boolean, data: JSONObject): DevicePasswordSnapshot? = metadataAssets.transaction {
        val owner = address ?: "metadata:$globalId"
        val previous = metadata.rootMetadata().firstOrNull { it.localId == owner }
        if(previous?.guard != expected && !(previous == null && expected.isNullOrEmpty())) return@transaction null
        val incoming = JSONObject(data.toString())
        if(!incoming.has("labels")) previous?.data?.optJSONArray("labels")?.let { incoming.put("labels",it) }
        val actual = adopt(owner, incoming, deleted && !data.optBoolean("trashed"), ::readIncomingAsset)
        metadata.applyRootMetadata(globalId, address, expected, deleted, actual)?.let(metadataAssets::snapshot)
    }

    private suspend fun applyLocalFolder(globalId: String, address: String?, expected: String?, deleted: Boolean, data: JSONObject): DevicePasswordSnapshot? = metadataAssets.transaction {
        val mapped = address ?: vaultIdentities().entries.firstOrNull { it.value == globalId }?.key
        val previous = mapped?.removePrefix("folder:")?.toLongOrNull()?.let { categories.getCategoryById(it) }
        if(previous != null && DevicePasskeyShape.guard(metadata.folder(previous)) != expected) return@transaction null
        if(previous == null && !expected.isNullOrEmpty()) return@transaction null
        if(deleted) {
            if(data.optBoolean("trashed")) throw LanSyncException("folder_trash_unavailable")
            mapped?.let { adopt(it, JSONObject(), true, ::readIncomingAsset); metadata.forget(it) }
            previous?.let {
                database.openHelper.writableDatabase.execSQL("UPDATE password_entries SET categoryId=NULL WHERE categoryId=?", arrayOf(it.id))
                database.openHelper.writableDatabase.execSQL("UPDATE secure_items SET categoryId=NULL WHERE categoryId=?", arrayOf(it.id))
                categories.delete(it)
            }
            return@transaction DevicePasswordSnapshot(mapped.orEmpty(), globalId, "folders", "", true, JSONObject())
        }
        val path = data.getJSONObject("payload").getJSONArray("path")
        require(path.length() in 1..128) { "invalid_folder" }
        var savedId = 0L
        for(index in 0 until path.length()) {
            val node = path.getJSONObject(index); val id = if(index == path.length()-1) globalId else node.getString("id")
            val known = vaultIdentities().entries.firstOrNull { it.value == id }?.key
            val existing = known?.removePrefix("folder:")?.toLongOrNull()?.let { categories.getCategoryById(it) }
            val title = if(index == path.length()-1) data.getString("title") else node.getString("title")
            savedId = existing?.id ?: categories.insert(Category(name = title))
            if(index == path.length()-1 && existing != null && existing.name != title) categories.update(existing.copy(name = title))
            database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_vault_identity(global_id,local_id) VALUES(?,?)", arrayOf(id,"folder:$savedId"))
            if(index == path.length()-1 || existing == null) {
                val partial = if(index == path.length()-1) JSONObject(data.toString()).put("title",title)
                    else JSONObject().put("title",title).put("folder","").put("entry_type","folder").put("payload",JSONObject())
                if(index == path.length()-1 && !partial.has("labels") && previous != null)
                    metadata.folder(previous).optJSONArray("labels")?.let { partial.put("labels",it) }
                partial.getJSONObject("payload").put("path",JSONArray().apply { for(i in 0..index) put(path.getJSONObject(i)) })
                metadata.save("folder:$savedId", if(index == path.length()-1) adopt("folder:$savedId", partial, false, ::readIncomingAsset) else partial)
            }
        }
        val saved = categories.getCategoryById(savedId) ?: error("device_sync_write_failed")
        val actual = metadata.folder(saved)
        metadataAssets.snapshot(DevicePasswordSnapshot("folder:$savedId", globalId, "folders", DevicePasskeyShape.guard(actual), false, actual))
    }
    private suspend fun applyLocalVault(globalId: String, address: String?, expected: String?, deleted: Boolean, data: JSONObject): DevicePasswordSnapshot? = database.withTransaction {
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        val mapped = address ?: vaultIdentities().entries.firstOrNull { it.value == globalId }?.key
        val passkey = mapped?.startsWith("passkey:") == true || data.optString("entry_type") == "passkey"
        val localId = mapped?.substringAfter(':')?.toLongOrNull()
        val scope = if(passkey) "passkeys" else "secure"
        var savedAddress: String
        val actual: JSONObject
        val guard: String
        if(passkey) {
            val previous = localId?.let { passkeys.getPasskeyByRecordId(it) }
            if(previous != null && DevicePasskeyShape.guard(localPasskey(previous)) != expected) return@withTransaction null
            if(previous == null && !expected.isNullOrEmpty()) return@withTransaction null
            if(deleted) {
                previous?.let { removed ->
                    passkeys.deleteByRecordId(removed.id)
                    // Copies in other vaults can share the same protected key reference.
                    if (passkeys.countByPrivateKeyAlias(removed.privateKeyAlias) == 0) {
                        takagi.ru.monica.passkey.PasskeyPrivateKeyStore.removeIfProtectedReference(app, removed.privateKeyAlias)
                    }
                }
                return@withTransaction DevicePasswordSnapshot(mapped.orEmpty(), globalId, scope, "", true, JSONObject())
            }
            var incoming = DevicePasskeyShape.import(app, data, localId ?: 0).copy(boundPasswordId = metadata.localPassword(data.getJSONObject("payload").optString("bound_password_entry_id")),
                categoryId = metadata.localFolder(data))
            if(previous != null) {
                if (!DevicePasskeyShape.matchesStoredPublicKey(app, previous, data.getJSONObject("payload")))
                    throw LanSyncException("passkey_conflict", "passkey_room_${previous.id}_public_key_changed", previous.rpName)
                val flags = JSONObject(data.getJSONObject("payload").toString())
                DevicePasskeyShape.withRecord("room_${previous.id}", previous.rpName) {
                    DevicePasskeyShape.retainBackupFlags(flags, JSONObject()
                        .put("backup_eligible", previous.backupEligible ?: JSONObject.NULL).put("backup_state", previous.backupState ?: JSONObject.NULL))
                    flags
                }
                incoming = incoming.copy(signCount = maxOf(previous.signCount, incoming.signCount),
                    backupEligible = if(flags.isNull("backup_eligible")) null else flags.getBoolean("backup_eligible"),
                    backupState = if(flags.isNull("backup_state")) null else flags.getBoolean("backup_state"))
                if(incoming.privateKeyAlias.isEmpty()) incoming = incoming.copy(privateKeyAlias = previous.privateKeyAlias,
                    isBackedUp = previous.isBackedUp, syncStatus = previous.syncStatus)
            }
            val duplicate = passkeys.getPasskeysByCredentialId(incoming.credentialId).firstOrNull {
                it.isDeviceSyncLocal() && it.rpId == incoming.rpId && it.id != localId
            }
            if(duplicate != null) throw LanSyncException("passkey_conflict")
            if(previous == null) passkeys.insert(incoming) else passkeys.update(incoming)
            val saved = passkeys.getPasskeysByCredentialId(incoming.credentialId).first { it.rpId == incoming.rpId && it.isDeviceSyncLocal() }
            metadata.save("passkey:${saved.id}", data)
            actual = localPasskey(saved); guard = DevicePasskeyShape.guard(actual); savedAddress = "passkey:${saved.id}"
        } else {
            val previous = localId?.let { secureItems.getItemById(it) }
            val folderNames = categories.getDeviceSyncCategories().associate { it.id to it.name }
            if(previous != null && withLocalAssets(DevicePasswordSnapshot("secure:${previous.id}", globalId, "secure",
                DevicePasskeyShape.guard(metadata.secure(previous, folderNames[previous.categoryId].orEmpty())) + ":" + previous.isDeleted,
                previous.isDeleted, metadata.secure(previous, folderNames[previous.categoryId].orEmpty())),
                attachments.getAllBySecureItem(previous.id).filter { !it.isDeleted || previous.isDeleted }).guard != expected) return@withTransaction null
            if(previous == null && !expected.isNullOrEmpty()) return@withTransaction null
            val hasContent = !deleted || data.optBoolean("trashed")
            if(previous == null && !hasContent) return@withTransaction DevicePasswordSnapshot("", globalId, scope, "", true, JSONObject())
            if(!hasContent && previous != null) {
                secureItems.deleteItem(previous)
                return@withTransaction DevicePasswordSnapshot("",globalId,scope,"",true,JSONObject())
            }
            val p = if(!hasContent) JSONObject() else data.getJSONObject("payload")
            val type = if(!hasContent) previous!!.itemType else when(data.getString("entry_type")) { "card" -> ItemType.BANK_CARD; "document-ref", "identity" -> ItemType.DOCUMENT; "billing-address" -> ItemType.BILLING_ADDRESS; "payment-account" -> ItemType.PAYMENT_ACCOUNT; "totp", "steam-mafile" -> ItemType.TOTP; "note" -> ItemType.NOTE; else -> throw LanSyncException("unsupported_vault_content") }
            val folder = data.optString("folder")
            val categoryId = metadata.localFolder(data) ?: if(folder.isEmpty()) null else categories.getDeviceSyncCategories().firstOrNull { it.name == folder && it.isMonicaLocalCategory() }?.id ?: categories.insert(Category(name=folder))
            // Notes keep plain item data, as the note editor writes it; note readers never decrypt it.
            fun storedItemData(entry: JSONObject) = DeviceVaultShape.localItemData(entry).let { if(type == ItemType.NOTE) it else security.encryptDataLegacyCompat(it) }
            val item = if(!hasContent) previous!!.copy(isDeleted = true, deletedAt = java.util.Date(), updatedAt = java.util.Date()) else SecureItem(id = previous?.id ?: 0,
                itemType = type, title = data.getString("title"), notes = p.optString("notes"), itemData = storedItemData(data),
                imagePaths = p.optString("image_paths"), categoryId = categoryId, isFavorite = p.optBoolean("is_favorite"), sortOrder = p.optInt("sort_order"),
                createdAt = java.util.Date(p.optLong("created_at", previous?.createdAt?.time ?: System.currentTimeMillis())), replicaGroupId = globalId,
                isDeleted = deleted, deletedAt = if(deleted) previous?.deletedAt ?: java.util.Date() else null)
            val savedId = if(previous == null) secureItems.insertItem(item) else { secureItems.updateItem(item); previous.id }
            if(hasContent) {
                val restored = restoreLocalAssets(data, null, savedId)
                if(restored != data.toString()) secureItems.updateItem(item.copy(id = savedId, imagePaths = JSONObject(restored).getJSONObject("payload").optString("image_paths"),
                    itemData = storedItemData(JSONObject(restored))))
            }
            val saved = secureItems.getItemById(savedId) ?: error("device_sync_write_failed")
            metadata.save("secure:$savedId", data)
            val snapshot = withLocalAssets(DevicePasswordSnapshot("secure:$savedId", globalId, scope, "", deleted, metadata.secure(saved, folder)), attachments.getAllBySecureItem(savedId).filter { !it.isDeleted || deleted })
            actual = snapshot.data; guard = snapshot.guard; savedAddress = "secure:$savedId"
        }
        vaultIdentityTable()
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_vault_identity(global_id,local_id) VALUES(?,?)", arrayOf(globalId, savedAddress))
        metadata.markBindingRepair(savedAddress)
        DevicePasswordSnapshot(savedAddress, globalId, scope, guard, deleted, preserveIncomingLinks(actual,data))
    }

    private suspend fun refreshProjections() {
        if (pendingProjections.isNotEmpty() && SessionManager.isUnlocked.value) {
            val vm = projection ?: withContext(Dispatchers.Main) {
                ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        modelClass.cast(MdbxViewModel(app as Application, database.localMdbxDatabaseDao(), database.mdbxRemoteSourceDao(), passwords,
                    database.secureItemDao(), database.passkeyDao(), database.attachmentDao(), fields, security, backgroundOnly = true))
                })["device-sync", MdbxViewModel::class.java].also { projection = it }
            }
            pendingProjections.toList().forEach { id ->
                if (SessionManager.isUnlocked.value) { vm.refreshDeviceSyncProjection(id); pendingProjections.remove(id) }
            }
        }
    }

    private suspend fun applyLocal(globalId: String, localId: Long?, expected: String?, deleted: Boolean, data: JSONObject): DevicePasswordSnapshot? = database.withTransaction {
        check(SessionManager.isUnlocked.value) { "device_sync_locked" }
        identityTable()
        val mappedId = if (localId == null) database.openHelper.readableDatabase.query(
            "SELECT local_id FROM device_sync_password_identity WHERE global_id=?", arrayOf(globalId)).use { if (it.moveToFirst()) it.getLong(0) else null } else localId
        val previous = mappedId?.let { passwords.getPasswordEntryById(it) }
        val hasContent = !deleted || data.optBoolean("trashed")
        if (previous == null && !hasContent) return@withTransaction DevicePasswordSnapshot("", globalId, "local", "", true, data)
        if (previous != null) {
            if (!previous.isLocalOnlyEntry()) return@withTransaction null
            if (localId == null) return@withTransaction null
            val previousFolder = previous.categoryId?.let { categories.getCategoryById(it)?.name }.orEmpty()
            val current = metadata.password(previous, fields.getFieldsByEntryIdSync(previous.id), previousFolder)
            current.put("entry_type", identities()[previous.id]?.second ?: "login")
            if (withLocalAssets(DevicePasswordSnapshot("local:${previous.id}", globalId, "local", DevicePasswordShape.guard(previous, current), previous.isDeleted, current),
                attachments.getAllByParent(previous.id).filter { !it.isDeleted || previous.isDeleted }).guard != expected) return@withTransaction null
        } else if (!expected.isNullOrEmpty()) return@withTransaction null
        if(!hasContent && previous != null) {
            passwords.deletePasswordEntry(previous)
            return@withTransaction DevicePasswordSnapshot("",globalId,"local","",true,JSONObject())
        }
        val folder = data.optString("folder")
        val categoryId = metadata.localFolder(data) ?: if (folder.isEmpty()) null else {
            categories.getDeviceSyncCategories().firstOrNull { it.name == folder && it.isMonicaLocalCategory() }?.id
                ?: categories.insert(Category(name = folder))
        }
        val entry = DevicePasswordShape.applyLocal(data, previous, deleted, categoryId, security)
        val id = if (previous == null) passwords.insertPasswordEntry(entry) else { passwords.updatePasswordEntry(entry); previous.id }
        if (hasContent) {
            val incoming = data.optJSONArray("custom_fields") ?: JSONArray()
            fields.replaceFieldsForEntry(id, (0 until incoming.length()).map { index ->
                val field = incoming.getJSONObject(index)
                val value = field.optString("value")
                CustomField(entryId = id, title = field.getString("title"),
                    value = if (field.optBoolean("is_protected") && value.isNotEmpty()) security.encryptDataLegacyCompat(value) else value,
                    isProtected = field.optBoolean("is_protected"), sortOrder = index)
            })
        }
        val type = if (deleted) identities()[id]?.second ?: "login" else data.getString("entry_type")
        database.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO device_sync_password_identity(global_id,local_id,entry_type) VALUES(?,?,?)", arrayOf(globalId, id, type))
        if(hasContent) {
            val restored = JSONObject(restoreLocalAssets(data, id, null)).optJSONObject("extensions")
            val icon = restored?.optString("custom_icon_value")?.takeUnless { it.isBlank() || it == "null" }
            if(icon != entry.customIconValue) passwords.updatePasswordEntry(entry.copy(id=id,customIconValue=icon))
        }
        val saved = passwords.getPasswordEntryById(id) ?: error("device_sync_write_failed")
        metadata.history(id, data)
        metadata.save("local:$id", data)
        metadata.markBindingRepair("local:$id")
        val actual = metadata.password(saved, fields.getFieldsByEntryIdSync(id), categoryId?.let { categories.getCategoryById(it)?.name }.orEmpty())
        actual.put("entry_type", type)
        val snapshot = withLocalAssets(DevicePasswordSnapshot("local:$id", globalId, "local", DevicePasswordShape.guard(saved, actual), deleted, actual), attachments.getAllByParent(id).filter { !it.isDeleted || deleted })
        snapshot.copy(data = preserveIncomingLinks(snapshot.data,data))
    }

    private fun preserveIncomingLinks(actual: JSONObject,incoming: JSONObject): JSONObject {
        for(name in listOf("extensions","payload")) {
            val source = incoming.optJSONObject(name) ?: continue
            val target = actual.optJSONObject(name) ?: continue
            for(key in listOf("bound_note_entry_id","sso_ref_entry_id","bound_password_entry_id")) if(source.has(key)) target.put(key,source.get(key))
        }
        return actual
    }

    private fun PasskeyEntry.isDeviceSyncLocal(): Boolean =
        mdbxDatabaseId == null && bitwardenVaultId == null && keepassDatabaseId == null

    private suspend fun localPasskey(item: PasskeyEntry): JSONObject {
        val current = DevicePasskeyShape.withRecord("room_${item.id}", item.rpName.ifBlank { item.rpId }) { DevicePasskeyShape.export(app, item) }
        current.getJSONObject("payload").put("bound_password_entry_id",item.boundPasswordId?.let { metadata.global("local:$it") } ?: JSONObject.NULL)
        // A legacy local record can retain a category ID owned by an MDBX mirror.
        // Keep that source record intact; do not export the foreign folder as local.
        val category = item.categoryId?.let { categories.getCategoryById(it) }?.takeIf { it.isMonicaLocalCategory() }
        current.put("folder", category?.name.orEmpty())
        if(category != null) current.put("folder_path",metadata.folder(category).getJSONObject("payload").getJSONArray("path"))
        val data = metadata.supplement("passkey:${item.id}", current)
        if (category == null) data.remove("folder_path")
        if(!data.has("folder_path") && category != null) data.put("folder_path", metadata.folder(category).getJSONObject("payload").getJSONArray("path"))
        return data
    }

    private suspend fun withLocalAssets(snapshot: DevicePasswordSnapshot, rows: List<takagi.ru.monica.attachments.model.Attachment>): DevicePasswordSnapshot {
        if(snapshot.deleted && !snapshot.data.optBoolean("trashed")) return snapshot
        var data = JSONObject(snapshot.data.toString())
        val descriptors = JSONArray(); val sources = mutableListOf<DeviceVaultAsset>()
        rows.sortedWith(compareBy({it.sha256Hex}, {it.fileName})).forEach { row ->
            if(row.source != "LOCAL" || row.localPath.isNullOrBlank() || row.wrappedCek.isNullOrBlank() || row.sha256Hex.isNullOrBlank()) throw LanSyncException("asset_unavailable")
            val hash = row.sha256Hex!!.lowercase()
            val portableId = metadata.attachmentIdentity(row.id)
            descriptors.put(JSONObject().put("id", hash).put("attachment_id", portableId).put("file_name", row.fileName).put("media_type", row.mimeType).put("size", row.sizeBytes))
            DeviceVaultShape.remapAssetPath(data,row.localPath!!,"device-asset:$hash")
            sources += DeviceVaultAsset(hash, "", 0, row.sizeBytes, local = row)
        }
        DeviceVaultShape.icons(data).forEachIndexed { index,value ->
            val bytes = DeviceVaultShape.readIcon(app,value)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            descriptors.put(JSONObject().put("id",hash).put("attachment_id","custom-icon:$index").put("file_name","icon-$index.png").put("media_type","image/png").put("size",bytes.size).put("icon",true))
            sources += DeviceVaultAsset(hash,"",0,bytes.size.toLong(),icon=value)
            bytes.fill(0); DeviceVaultShape.remapAssetPath(data,value,"device-asset:$hash")
        }
        val p = data.optJSONObject("payload")
        val imagePaths = p?.optString("image_paths").orEmpty()
        if(imagePaths.isNotBlank()) {
            val list = JSONArray(imagePaths)
            for(index in 0 until list.length()) {
                val name = list.optString(index)
                if(name.isBlank() || name.startsWith("device-asset:")) continue
                val bytes = images.readImageBytes(name) ?: throw LanSyncException("asset_unavailable")
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                descriptors.put(JSONObject().put("id", hash).put("attachment_id", "image:$index").put("file_name", "image-$index.png").put("media_type", "image/png").put("size", bytes.size).put("image", true))
                sources += DeviceVaultAsset(hash, "", 0, bytes.size.toLong(), image = name)
                bytes.fill(0); DeviceVaultShape.remapAssetPath(data,name,"device-asset:$hash")
            }
        }
        data.put("assets", descriptors)
        val guard = if(snapshot.scope == "secure") DevicePasskeyShape.guard(data) + ":${snapshot.deleted}" else DevicePasskeyShape.guard(data)
        return snapshot.copy(data = data, assets = sources, guard = guard)
    }
    private suspend fun readAsset(asset: DeviceVaultAsset): ByteArray {
        val bytes = when {
            asset.metadata != null -> metadataAssets.read(asset.metadata)
            asset.icon != null -> DeviceVaultShape.readIcon(app,asset.icon)
            asset.image != null -> images.readImageBytes(asset.image) ?: throw LanSyncException("asset_unavailable")
            asset.local != null -> {
                val cek = attachmentKeys.unwrap(asset.local.wrappedCek!!)
                try { attachmentStorage.openDecryptedStream(asset.local.localPath!!, cek).use { stream ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64*1024)
                    try { while(true) { val count = stream.read(buffer); if(count < 0) break
                        if(output.size().toLong() + count > 64L*1024*1024) throw LanSyncException("asset_limit"); output.write(buffer,0,count) }; output.toByteArray() }
                    finally { buffer.fill(0) }
                } } finally { cek.fill(0) }
            }
            else -> native.readDeviceSyncAsset(asset)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if(bytes.size.toLong() != asset.size || hash != asset.id) { bytes.fill(0); throw LanSyncException("invalid_asset") }
        return bytes
    }
    private suspend fun restoreLocalAssets(data: JSONObject, passwordId: Long?, secureId: Long?): String {
        val descriptors = data.optJSONArray("assets") ?: return data.toString()
        val existing = if(passwordId != null) attachments.getAllByParent(passwordId) else attachments.getAllBySecureItem(secureId!!)
        var materialized = data.toString()
        val keep = mutableSetOf<Long>()
        for(index in 0 until descriptors.length()) {
            val descriptor = descriptors.getJSONObject(index); val hash = descriptor.getString("id"); val name = descriptor.getString("file_name")
            if(descriptor.optBoolean("icon")) {
                val bytes = readIncomingAsset(descriptor)
                val restored = try { DeviceVaultShape.saveIcon(app,bytes,hash) } finally { bytes.fill(0) }
                materialized = DeviceVaultShape.remapAssetPath(JSONObject(materialized),"device-asset:$hash",restored).toString(); continue
            }
            if(descriptor.optBoolean("image")) {
                val bytes = readIncomingAsset(descriptor)
                val restored = try { images.saveImageBytes(bytes, "device-sync-$hash") ?: throw LanSyncException("invalid_asset") } finally { bytes.fill(0) }
                materialized = DeviceVaultShape.remapAssetPath(JSONObject(materialized),"device-asset:$hash",restored).toString(); continue
            }
            val old = existing.firstOrNull { it.id !in keep && !it.isDeleted && it.sha256Hex == hash && it.fileName == name }
            val rowId = if(old != null) old.id else {
                val bytes = readIncomingAsset(descriptor)
                val result = try { attachmentStorage.writeEncrypted(bytes.inputStream()) } finally { bytes.fill(0) }
                try { attachments.insert(takagi.ru.monica.attachments.model.Attachment(parentPasswordId = passwordId, parentSecureItemId = secureId,
                    source = "LOCAL", fileName = name, mimeType = descriptor.optString("media_type", "application/octet-stream"), sizeBytes = result.sizeBytes,
                    sha256Hex = result.sha256Hex, wrappedCek = attachmentKeys.wrap(result.cek), localPath = result.relativePath, downloadState = "DOWNLOADED",
                    createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())) } finally { result.cek.fill(0) }
            }
            descriptor.optString("attachment_id").takeIf { it.isNotBlank() }?.let { metadata.bindAttachment(rowId,it) }
            keep += rowId
        }
        existing.filter { it.id !in keep }.forEach { attachments.deleteById(it.id) }
        return materialized
    }
    private suspend fun readIncomingAsset(descriptor: JSONObject): ByteArray {
        val id = descriptor.getString("id")
        return if(incomingAssets?.contains(id) == true) incomingAssets!!.read(descriptor)
            else if(fallbackAssets?.contains(id) == true) fallbackAssets!!.read(descriptor) else throw LanSyncException("asset_unavailable")
    }

    suspend fun pause() = gate.withLock {
        handle?.let { request("replica_close") }
        handle = null; sourceRevision = null; primaryNativeId = null; preparedAssets = null; incomingAssets = null; fallbackAssets = null
        passkeyIssues = emptyList()
        withContext(Dispatchers.Main) { viewModels.clear(); projection = null }
    }
}
