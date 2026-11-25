package takagi.ru.monica.repository

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import takagi.ru.monica.data.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import uniffi.mdbx_ffi.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A screen-owned native baseline. Room is updated only as a projection of a native read/commit. */
internal class MdbxPasswordEditor(context: Context, private val db: PasswordDatabase,
    private val security: SecurityManager,
    private val readObject: (MdbxVault, String) -> MdbxNativeObjectDetail = ::readMdbxNativeObject) {
    private val sessions = Mdbx2VaultSessionExecutor(context, db.localMdbxDatabaseDao(), security, Mdbx2ExternalStorage(context))
    private val mutex = Mutex()
    private val stateLock = Any()
    private data class Baseline(val entry: PasswordEntry, val detail: MdbxNativeObjectDetail,
        val vaultId: String, val branches: List<MdbxBranchInfo>, val source: LocalMdbxDatabase)
    private val baselines = ConcurrentHashMap<Long, Baseline>()
    @Volatile private var epoch = 0L
    @Volatile private var active = false
    private var generation = -1L
    private var foregroundGeneration = -1L
    private var lockGeneration = -1L
    @Volatile private var requestAuthorized: (() -> Boolean)? = null
    private data class Projection(val databaseId: Long, val saved: List<Pair<PasswordEntry, MdbxNativeObjectDetail>>,
        val removed: List<Long>, val vaultId: String, val branches: List<MdbxBranchInfo>, val source: LocalMdbxDatabase)
    @Volatile private var pending: Projection? = null
    val isOpen: Boolean get() = active
    private data class Committed(val databaseId: Long, val rows: List<Pair<PasswordEntry, String>>,
        val removed: List<Long>, val vaultId: String, val source: LocalMdbxDatabase)
    @Volatile private var committed: Committed? = null
    private var revocation: AutoCloseable? = null
    private fun revoke() = synchronized(stateLock) {
        active = false; epoch++; baselines.clear(); pending = null; committed = null; requestAuthorized = null
    }

    // Autofill owns an authenticated request, not the main application's unlock state.
    // Its caller must recheck that request and foreground lifecycle on every use.
    fun begin(isRequestAuthorized: (() -> Boolean)? = null): AutoCloseable = synchronized(stateLock) {
        revocation?.close()
        val token = ++epoch
        baselines.clear(); pending = null; committed = null
        generation = SecurityManager.dataReadGeneration.value
        foregroundGeneration = Mdbx2NativeReadSessions.foregroundGeneration
        lockGeneration = SessionManager.lockGeneration.value
        requestAuthorized = isRequestAuthorized
        active = true
        val listener = Mdbx2NativeReadSessions.onRevoke(::revoke)
        revocation = listener
        AutoCloseable { listener.close(); synchronized(stateLock) { if (epoch == token) revoke() } }
    }
    private fun checkSession(token: Long) {
        val authorized = requestAuthorized?.invoke() ?: (
            foregroundGeneration == Mdbx2NativeReadSessions.foregroundGeneration &&
                SessionManager.isUnlocked.value && Mdbx2NativeReadSessions.isForeground)
        check(active && epoch == token && generation == SecurityManager.dataReadGeneration.value &&
            lockGeneration == SessionManager.lockGeneration.value && authorized) { "MDBX editor session expired" }
    }
    private inline fun <T> publishState(token: Long, block: () -> T): T = synchronized(stateLock) {
        checkSession(token)
        block()
    }
    fun entry(id: Long): PasswordEntry? = baselines[id]?.let { checkSession(epoch); it.entry }
    fun entries(): List<PasswordEntry> = if (baselines.isEmpty()) emptyList() else {
        checkSession(epoch); baselines.values.map { it.entry }
    }
    fun fields(id: Long): List<CustomField>? = baselines[id]?.let { checkSession(epoch); MdbxPasswordEditorPayload.fields(it.detail, id) }
    private fun decrypt(value: String): String = security.decryptDataIfMonicaCiphertext(value)
    private fun physicalId(vault: MdbxVault, entry: PasswordEntry): String {
        val replica = entry.replicaGroupId
        if (replica != null && runCatching { UUID.fromString(replica) }.isSuccess && vault.getObjectSummary(replica) != null) return replica
        return mdbx2PhysicalEntryId(vault.info().vaultId, mdbxPasswordObjectId(entry))
    }
    private fun frontier(branches: List<MdbxBranchInfo>) = branches.associate { it.branchId to it.headCommitId }
    private fun sameSource(a: LocalMdbxDatabase, b: LocalMdbxDatabase): Boolean =
        a.id == b.id && a.filePath == b.filePath && a.workingCopyPath == b.workingCopyPath &&
            a.encryptedPassword == b.encryptedPassword && a.unlockMethod == b.unlockMethod &&
            a.keyFileUri == b.keyFileUri && a.keyFileFingerprint == b.keyFileFingerprint && a.engineType == b.engineType &&
            a.sourceType == b.sourceType && a.sourceId == b.sourceId && a.externalTreeUri == b.externalTreeUri

    suspend fun load(owner: PasswordEntry): PasswordEntry = mutex.withLock {
        val token = epoch; checkSession(token)
        val databaseId = requireNotNull(owner.mdbxDatabaseId)
        val room = db.passwordEntryDao().getByMdbxDatabaseIdSync(databaseId)
        val loaded = sessions.withNativeReadVault(databaseId) { source, vault ->
            checkSession(token)
            requireMdbxAppReplicaAllowed(source)
            val before = vault.listBranches()
            val vaultId = vault.info().vaultId
            val root = Mdbx2VaultSessionExecutor.rootProjectId(vaultId)
            val original = readObject(vault, physicalId(vault, owner))
            val primary = MdbxPasswordEditorPayload.decode(original, owner, root, security::encryptData, ::decrypt)
            val group = primary.passwordGroupId
            val details = mutableListOf(original)
            if (!group.isNullOrBlank()) {
                readMdbxNativeBrowser(vault).objects.values.filter { it.type == "login" && it.id != original.summary.id }.forEach { summary ->
                    val detail = readObject(vault, summary.id)
                    val payload = Json.parseToJsonElement(detail.payload).jsonObject
                    if (payload["password_group_id"]?.jsonPrimitive?.contentOrNull == group) details += detail
                }
            }
            val knownIds = room.associateBy { physicalId(vault, it) }
            val result = details.map { detail ->
                val local = knownIds[detail.summary.id] ?: PasswordEntry(title = "", website = "", username = "", password = "", mdbxDatabaseId = databaseId)
                val decoded = MdbxPasswordEditorPayload.decode(detail, local, root, security::encryptData, ::decrypt)
                MdbxPasswordEditorPayload.fields(detail, decoded.id) // Reject unsupported fields before publishing the form.
                Baseline(decoded, detail, vaultId, before, source)
            }
            check(frontier(before) == frontier(vault.listBranches())) { "MDBX changed during editor load" }
            checkSession(token)
            result
        }
        checkSession(token)
        db.withTransaction {
            checkSession(token)
            loaded.forEach { baseline ->
                val id = if (baseline.entry.id == 0L) db.passwordEntryDao().insertPasswordEntry(baseline.entry)
                    else baseline.entry.id.also { db.passwordEntryDao().updatePasswordEntry(baseline.entry) }
                val value = baseline.copy(entry = baseline.entry.copy(id = id))
                db.customFieldDao().replaceFieldsForEntry(id, MdbxPasswordEditorPayload.fields(value.detail, id).map { it.copy(id = 0) })
                publishState(token) { baselines[id] = value }
            }
            checkSession(token)
        }
        checkSession(token)
        requireNotNull(baselines[owner.id]).entry
    }

    suspend fun save(databaseId: Long, rows: List<Pair<PasswordEntry, List<CustomFieldDraft>>>,
        removedIds: List<Long> = emptyList()): List<PasswordEntry> = withContext(Dispatchers.IO) { mutex.withLock {
        val token = epoch; checkSession(token); currentCoroutineContext().ensureActive()
        require(rows.isNotEmpty())
        // A native success followed by a projection failure must never create another object on retry.
        pending?.let { return@withLock publish(it, token) }
        committed?.let { saved ->
            // Retry projection/readback only. The original object IDs already exist durably.
            require(saved.databaseId == databaseId)
            val projection = sessions.withNativeReadVault(databaseId) { source, vault ->
                checkSession(token)
                check(sameSource(saved.source, source) && vault.info().vaultId == saved.vaultId)
                Projection(databaseId, saved.rows.map { (draft, id) -> draft to readObject(vault, id) },
                    saved.removed, saved.vaultId, vault.listBranches(), source)
            }
            publishState(token) { pending = projection }
            return@withLock publish(projection, token)
        }
        val existing = (rows.map { it.first.id } + removedIds).filter { it != 0L }.distinct().map { id ->
            requireNotNull(baselines[id]) { "Reload the MDBX entry before editing" }
        }
        require(existing.all { it.entry.mdbxDatabaseId == databaseId })
        val requestContext = currentCoroutineContext()
        try {
            sessions.withMutatingVault(databaseId) { source, vault ->
                requestContext.ensureActive(); checkSession(token)
                requireMdbxAppReplicaAllowed(source)
                val vaultId = vault.info().vaultId
                val expected = existing.firstOrNull()?.branches ?: vault.listBranches()
                check(existing.all { it.vaultId == vaultId && sameSource(it.source, source) && frontier(it.branches) == frontier(expected) })
                check(frontier(vault.listBranches()) == frontier(expected)) { "MDBX changed; reload before saving" }
                val root = Mdbx2VaultSessionExecutor.rootProjectId(vaultId)
                val commands = mutableListOf<MdbxWriteCommand>()
                val planned = rows.map { (draft, fields) ->
                    require(draft.keepassDatabaseId == null && draft.bitwardenVaultId == null && draft.mdbxDatabaseId == databaseId)
                    val baseline = baselines[draft.id]
                    val original = baseline?.detail
                    if (original != null) check(readObject(vault, original.summary.id) == original) { "MDBX entry changed" }
                    val id = original?.summary?.id ?: UUID.randomUUID().toString()
                    val folder = draft.mdbxFolderId?.takeIf(String::isNotBlank) ?: root
                    if (folder == root && vault.getCollectionSummary(root) == null && commands.none { it is MdbxWriteCommand.CreateProject })
                        commands += MdbxWriteCommand.CreateProject(root, Mdbx2VaultSessionExecutor.ROOT_PROJECT_TITLE)
                    else check(folder == root || vault.getCollectionSummary(folder)?.deleted == false) { "MDBX destination is unavailable" }
                    val noteId = if (draft.boundNoteId != null && (baseline == null || draft.boundNoteId != baseline.entry.boundNoteId)) {
                        val note = requireNotNull(db.secureItemDao().getItemById(draft.boundNoteId))
                        require(note.mdbxDatabaseId == databaseId && note.itemType == ItemType.NOTE && !note.isDeleted)
                        mdbx2PhysicalEntryId(vaultId, mdbxSecureItemObjectId(note)).also { id ->
                            val summary = requireNotNull(vault.getObjectSummary(id))
                            require(summary.objectTypeId == "note" && !summary.deleted)
                        }
                    } else null
                    val payload = MdbxPasswordEditorPayload.encode(original, baseline?.entry, draft, fields, ::decrypt, noteId)
                    require(payload.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
                    if (original == null) commands += MdbxWriteCommand.CreateEntry(id, folder, "login", draft.title, payload)
                    else {
                        if (original.summary.collectionId != folder) commands += MdbxWriteCommand.MoveEntry(id, original.summary.collectionId, folder)
                        if (original.summary.title != draft.title || Json.parseToJsonElement(original.payload) != Json.parseToJsonElement(payload))
                            commands += MdbxWriteCommand.UpdateEntry(id, folder, "login", draft.title, payload)
                    }
                    draft to id
                }
                removedIds.forEach { id ->
                    val old = requireNotNull(baselines[id]).detail
                    check(readObject(vault, old.summary.id) == old)
                    commands += MdbxWriteCommand.DeleteEntry(old.summary.id, old.summary.collectionId)
                }
                requestContext.ensureActive(); checkSession(token)
                if (commands.isNotEmpty()) vault.executeGuardedWriteOperation(vaultId, expected, UUID.randomUUID().toString(), commands)
                publishState(token) { committed = Committed(databaseId, planned, removedIds, vaultId, source) }
                val readback = planned.map { (draft, id) -> draft to readObject(vault, id) }
                val projection = Projection(databaseId, readback, removedIds, vaultId, vault.listBranches(), source)
                publishState(token) { pending = projection }
            }
        } catch (failure: Exception) {
            if (pending == null) throw failure
            // The engine commit already exists. External publication keeps its durable pending
            // status; do not compensate it with a second, destructive write from Room.
        }
        publish(requireNotNull(pending), token)
    } }

    private suspend fun publish(projection: Projection, token: Long): List<PasswordEntry> = withContext(NonCancellable) {
        val result = mutableListOf<PasswordEntry>()
        val newBaselines = mutableMapOf<Long, Baseline>()
        checkSession(token)
        db.withTransaction {
            checkSession(token)
            projection.saved.forEach { (draft, detail) ->
                val root = Mdbx2VaultSessionExecutor.rootProjectId(projection.vaultId)
                val previous = baselines[draft.id]?.entry
                val projectionBase = previous?.copy(
                    categoryId = draft.categoryId, boundNoteId = draft.boundNoteId,
                    customIconType = draft.customIconType, customIconValue = draft.customIconValue,
                    customIconUpdatedAt = draft.customIconUpdatedAt, ssoRefEntryId = draft.ssoRefEntryId,
                    updatedAt = java.util.Date()) ?: draft
                val decoded = MdbxPasswordEditorPayload.decode(detail, projectionBase, root, security::encryptData, ::decrypt)
                val id = if (draft.id == 0L) db.passwordEntryDao().insertPasswordEntry(decoded)
                    else draft.id.also { db.passwordEntryDao().updatePasswordEntry(decoded) }
                val entry = decoded.copy(id = id)
                db.customFieldDao().replaceFieldsForEntry(id, MdbxPasswordEditorPayload.fields(detail, id).map { it.copy(id = 0) })
                newBaselines[id] = Baseline(entry, detail, projection.vaultId, projection.branches, projection.source)
                result += entry
            }
            projection.removed.forEach { id ->
                db.passwordEntryDao().getPasswordEntryById(id)?.let { db.passwordEntryDao().updatePasswordEntry(it.copy(isDeleted = true, deletedAt = java.util.Date())) }
            }
            checkSession(token)
        }
        publishState(token) {
            baselines.putAll(newBaselines)
            projection.removed.forEach(baselines::remove)
            baselines.replaceAll { _, baseline -> if (baseline.entry.mdbxDatabaseId == projection.databaseId)
                baseline.copy(branches = projection.branches) else baseline }
            pending = null; committed = null
        }
        checkSession(token)
        result
    }
}
