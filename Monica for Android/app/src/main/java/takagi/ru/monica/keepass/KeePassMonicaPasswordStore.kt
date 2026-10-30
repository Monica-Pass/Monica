package takagi.ru.monica.keepass

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassCustomFieldData
import takagi.ru.monica.utils.KeePassMonicaPasswordProjection
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.executor.KeePassAttachmentRef

internal data class KeePassMonicaPasswordDraft(
    val uuid: UUID,
    val existing: Boolean,
    val parentGroupUuid: UUID,
    val fields: List<KeePassFieldChange>,
)

internal enum class KeePassFileState { READY, BUSY, SAVED, EXTERNAL_RELOADED, RELOADED, MERGED, MERGED_CONFLICTS, RELOAD_FAILED, WRITE_FAILED }

/** An in-memory presentation adapter. Its IDs are never Room rows or KDBX ownership fields. */
internal class KeePassMonicaPasswordStore(private val service: KeePassKdbxService, private val security: SecurityManager? = null) {
    internal data class Snapshot(
        val browser: KeePassNativeBrowserSnapshot,
        val rows: List<PasswordEntry>,
        val customFields: Map<Long, List<CustomField>>,
        val secureItems: List<SecureItem>,
        val lockVersion: Long,
        val passkeys: List<PasskeyEntry>,
        val sourceBrowser: KeePassNativeBrowserSnapshot = browser,
        val passwordProjection: KeePassMonicaPasswordProjection? = null,
    )

    private val mutex = Mutex()
    private val snapshots = MutableStateFlow<Map<Long, Snapshot>>(emptyMap())
    private val _fileStates = MutableStateFlow<Map<Long, KeePassFileState>>(emptyMap())
    val fileStates = _fileStates.asStateFlow()
    private val lastSourceChecks = mutableMapOf<Long, Long>()
    private fun fileState(id: Long, state: KeePassFileState) { _fileStates.update { it + (id to state) } }
    private val active = MutableStateFlow<Set<Long>>(emptySet())
    private val identities = mutableMapOf<Long, KeePassNativeEntryIdentity>()
    private val drafts = mutableMapOf<Long, Snapshot>()
    private val secureIdentities = mutableMapOf<Long, KeePassNativeEntryIdentity>()
    private val secureDrafts = mutableMapOf<Long, Snapshot>()
    private val passkeyIdentities = mutableMapOf<Long, KeePassNativeEntryIdentity>()
    private val passkeyDrafts = mutableMapOf<Long, Snapshot>()
    private data class HistoryIdentity(val entry: KeePassNativeEntryIdentity, val index: Int, val revision: String)
    private val historyIdentities = mutableMapOf<Long, HistoryIdentity>()
    private val blocked = mutableSetOf<Long>()
    val attachmentRows: Flow<Map<AttachmentOwner, List<Attachment>>> = combine(snapshots, SessionManager.isUnlocked,
        KeePassKdbxService.nativeLockVersions) { data, unlocked, versions ->
        if (!unlocked) emptyMap() else buildMap {
            data.values.filter { it.lockVersion == (versions[it.browser.databaseId] ?: 0L) }.forEach { snapshot ->
                val owners = snapshot.rows.map { AttachmentOwner.password(it.id) to it.keepassEntryUuid } +
                    snapshot.secureItems.map { AttachmentOwner.secureItem(it.id) to it.keepassEntryUuid }
                owners.forEach { (owner, uuid) ->
                    val record = snapshot.browser.entriesByIdentity[KeePassNativeEntryIdentity(snapshot.browser.databaseId, UUID.fromString(uuid))]?.singleOrNull()
                    put(owner, record?.attachments.orEmpty().map { asset ->
                        val ref = KeePassAttachmentRef.from(asset.hash, asset.name).encode()
                        Attachment(id = attachmentId(owner, ref), parentPasswordId = owner.passwordId,
                            parentSecureItemId = owner.secureItemId, source = "KEEPASS", fileName = asset.name,
                            mimeType = java.net.URLConnection.guessContentTypeFromName(asset.name) ?: "application/octet-stream",
                            sizeBytes = (asset.binary?.rawContent?.size ?: 0).toLong(), keepassBinaryRef = ref, downloadState = "PENDING",
                            createdAt = 0L, updatedAt = 0L)
                    }.distinctBy { it.id })
                }
            }
        }
    }

    fun attachmentId(owner: AttachmentOwner, ref: String): Long = synchronized(this) {
        val identity = if (owner.kind == AttachmentOwner.Kind.PASSWORD) identities[owner.id] else secureIdentities[owner.id]
        auxiliaryId(requireNotNull(identity), "attachment:${owner.kind}:$ref")
    }

    suspend fun addAttachment(owner: AttachmentOwner, fileName: String, bytes: ByteArray) = mutateAttachments(owner) { identity, revision ->
        service.addNativeAttachment(identity.databaseId, identity.entryUuid, fileName, bytes, revision).getOrThrow()
    }

    suspend fun removeAttachment(owner: AttachmentOwner, ref: String) = mutateAttachments(owner) { identity, revision ->
        val asset = requireNotNull(snapshots.value[identity.databaseId]).browser.entriesByIdentity[identity]!!.single().attachments
            .first { KeePassAttachmentRef.from(it.hash, it.name).encode() == ref }
        service.deleteNativeAttachment(identity.databaseId, identity.entryUuid, asset.hash, asset.name, revision).getOrThrow()
    }

    private suspend fun <T> mutateAttachments(owner: AttachmentOwner, mutation: suspend (KeePassNativeEntryIdentity, String) -> T): T = mutex.withLock {
        val identity = synchronized(this) { requireNotNull(if (owner.kind == AttachmentOwner.Kind.PASSWORD) identities[owner.id] else secureIdentities[owner.id]) }
        requireReadable(identity.databaseId)
        val before = requireNotNull(snapshots.value[identity.databaseId])
        val draft = synchronized(this) { if (owner.kind == AttachmentOwner.Kind.PASSWORD) drafts[owner.id] else secureDrafts[owner.id] }
        check(draft == null || draft.browser.sourceRevision == before.browser.sourceRevision) { "KeePass draft is stale; reload before changing attachments" }
        val result = mutation(identity, before.browser.sourceRevision.sha256)
        val after = load(identity.databaseId)
        // Only our own revision-checked attachment mutation may advance an existing form's baseline.
        synchronized(this) {
            listOf(drafts, secureDrafts, passkeyDrafts).forEach { map ->
                map.entries.filter { it.value.browser.databaseId == identity.databaseId && it.value.browser.sourceRevision == before.browser.sourceRevision }
                    .forEach { it.setValue(after) }
            }
        }
        result
    }
    val activeRows: Flow<Map<Long, List<PasswordEntry>>> = combine(snapshots, active, SessionManager.isUnlocked) { data, ids, unlocked ->
        ids.associateWith { id -> if (unlocked) data[id]?.rows.orEmpty() else emptyList() }
    }
    val activeSecureRows: Flow<Map<Long, List<SecureItem>>> = combine(snapshots, active, SessionManager.isUnlocked) { data, ids, unlocked ->
        ids.associateWith { id -> if (unlocked) data[id]?.secureItems.orEmpty() else emptyList() }
    }
    fun isActive(databaseId: Long): Boolean = databaseId in active.value
    fun isDatabaseAvailable(databaseId: Long): Boolean = SessionManager.isUnlocked.value && service.isDatabaseUnlocked(databaseId)
    fun canWriteDatabase(databaseId: Long): Boolean = isDatabaseAvailable(databaseId) && !service.isDatabaseReadOnly(databaseId)
    val activePasskeys: Flow<Map<Long, List<PasskeyEntry>>> = combine(snapshots, active, SessionManager.isUnlocked) { data, ids, unlocked ->
        ids.associateWith { id -> if (unlocked) data[id]?.passkeys.orEmpty() else emptyList() }
    }

    fun activate(databaseId: Long, enabled: Boolean) {
        active.update { if (enabled) it + databaseId else it - databaseId }
    }

    fun clear(databaseId: Long? = null) {
        synchronized(this) {
            if (databaseId == null) {
                snapshots.value = emptyMap()
                _fileStates.value = emptyMap()
                drafts.clear()
                secureDrafts.clear()
                passkeyDrafts.clear()
                blocked += identities.values.map { it.databaseId }
                blocked += secureIdentities.values.map { it.databaseId }
                blocked += passkeyIdentities.values.map { it.databaseId }
            } else {
                snapshots.update { it - databaseId }
                _fileStates.update { it - databaseId }
                drafts.entries.removeAll { it.value.browser.databaseId == databaseId }
                secureDrafts.entries.removeAll { it.value.browser.databaseId == databaseId }
                passkeyDrafts.entries.removeAll { it.value.browser.databaseId == databaseId }
                blocked += databaseId
            }
        }
    }

    private fun requireReadable(databaseId: Long) {
        check(SessionManager.isUnlocked.value) { "Monica is locked" }
        synchronized(this) {
            if (databaseId in blocked) {
                check(service.isDatabaseUnlocked(databaseId)) { "KeePass database is locked" }
                blocked -= databaseId
            }
        }
    }

    suspend fun refresh(databaseId: Long): Snapshot = mutex.withLock { load(databaseId) }

    /** Only the visible list polls. Draft baselines remain unchanged so stale editors cannot overwrite it. */
    suspend fun checkForExternalChanges(databaseId: Long): Boolean = mutex.withLock {
        val before = snapshots.value[databaseId] ?: return@withLock false
        if (!SessionManager.isUnlocked.value || !service.isDatabaseUnlocked(databaseId) ||
            before.lockVersion != (KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L)) return@withLock false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastSourceChecks[databaseId] ?: 0L) < 1500L) return@withLock false
        lastSourceChecks[databaseId] = now
        try {
            if (service.currentNativeSourceRevision(databaseId) == before.browser.sourceRevision) {
                if (_fileStates.value[databaseId] == KeePassFileState.RELOAD_FAILED) fileState(databaseId, KeePassFileState.RELOADED)
                return@withLock false
            }
            service.reloadNativeDatabase(databaseId)
            load(databaseId)
            fileState(databaseId, KeePassFileState.EXTERNAL_RELOADED)
            true
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            fileState(databaseId, KeePassFileState.RELOAD_FAILED)
            false
        }
    }

    suspend fun reload(databaseId: Long, refreshRemoteSource: (suspend () -> Unit)? = null): Snapshot = mutex.withLock {
        requireReadable(databaseId)
        fileState(databaseId, KeePassFileState.BUSY)
        try {
            val previousRevision = snapshots.value[databaseId]?.browser?.sourceRevision
            if (refreshRemoteSource == null) service.reloadNativeDatabase(databaseId) else refreshRemoteSource()
            load(databaseId).also { fileState(databaseId,
                if (previousRevision != null && previousRevision != it.browser.sourceRevision) KeePassFileState.EXTERNAL_RELOADED else KeePassFileState.RELOADED) }
        } catch (error: Exception) {
            fileState(databaseId, KeePassFileState.RELOAD_FAILED)
            throw error
        }
    }

    suspend fun saveDatabase(databaseId: Long) = mutex.withLock {
        requireReadable(databaseId)
        val before = snapshots.value[databaseId] ?: load(databaseId)
        fileState(databaseId, KeePassFileState.BUSY)
        try {
            service.saveNativeDatabase(databaseId, before.browser.sourceRevision.sha256)
            load(databaseId)
            fileState(databaseId, KeePassFileState.SAVED)
        } catch (error: Exception) {
            fileState(databaseId, KeePassFileState.WRITE_FAILED)
            throw error
        }
    }

    suspend fun mergeDatabase(databaseId: Long, source: android.net.Uri, password: String, keyFile: android.net.Uri?) = mutex.withLock {
        requireReadable(databaseId)
        val before = snapshots.value[databaseId] ?: load(databaseId)
        fileState(databaseId, KeePassFileState.BUSY)
        try {
            val conflicts = service.synchronizeNativeDatabaseFrom(databaseId, source, password, keyFile, before.browser.sourceRevision.sha256)
            load(databaseId)
            fileState(databaseId, if (conflicts > 0) KeePassFileState.MERGED_CONFLICTS else KeePassFileState.MERGED)
        } catch (error: Exception) {
            fileState(databaseId, KeePassFileState.WRITE_FAILED)
            throw error
        }
    }

    private suspend fun load(databaseId: Long): Snapshot {
        requireReadable(databaseId)
        val lockVersion = KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L
        val previous = snapshots.value[databaseId]?.takeIf { it.lockVersion == lockVersion }
        // Card/document projections also restore encrypted photo files, which may
        // be evicted without changing the original KDBX session. Keep rehydrating them.
        val reusable = previous?.takeIf { snapshot -> snapshot.secureItems.none {
            it.itemType == ItemType.BANK_CARD || it.itemType == ItemType.DOCUMENT
        } }
        val read = service.loadMonicaWorkspace(databaseId, reusable?.sourceBrowser, previous?.passwordProjection).getOrThrow()
        val workspace = read.workspace
        if (workspace == null) {
            synchronized(this) {
                requireReadable(databaseId)
                check((KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L) == lockVersion) { "KeePass database was locked while reading" }
                check(previous != null && snapshots.value[databaseId] === previous) { "KeePass snapshot was invalidated while reading" }
                return previous
            }
        }
        val browser = read.sourceBrowser.copy(passwordEntryUuids = workspace.passwords.asSequence()
            .filterNot { it.isInRecycleBin }.map { UUID.fromString(it.entryUuid) }.toHashSet())
        check(workspace.sessionRevision == browser.sourceRevision.sha256) { "KeePass database changed while reading" }
        check((KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L) == lockVersion) { "KeePass database was locked while reading" }
        requireReadable(databaseId)
        val custom = linkedMapOf<Long, List<CustomField>>()
        val rows = workspace.passwords.filterNot { it.isInRecycleBin }.map { source ->
            val uuid = UUID.fromString(requireNotNull(source.entryUuid))
            val identity = KeePassNativeEntryIdentity(databaseId, uuid)
            val record = browser.entriesByIdentity[identity]?.singleOrNull()
                ?: error("KeePass entry identity is missing or ambiguous")
            val id = idFor(identity)
            custom[id] = source.customFields.filterNot { it.title in UI_FIELDS }.mapIndexed { index, field ->
                CustomField(id = auxiliaryId(identity, "field:${field.title}"), entryId = id, title = field.title,
                    value = field.value, isProtected = field.isProtected, sortOrder = index)
            }.let { fields -> previous?.customFields?.get(id)?.takeIf { it == fields } ?: fields }
            source.toRow(id, databaseId).copy(
                isFavorite = record.field("MonicaIsFavorite")?.displayValue.equals("true", true),
                passwordGroupId = record.field("MonicaPasswordGroupId")?.displayValue?.takeIf(String::isNotBlank),
                replicaGroupId = record.field("MonicaReplicaGroupId")?.displayValue?.takeIf(String::isNotBlank),
                customIconType = record.field("MonicaCustomIconType")?.displayValue ?: "NONE",
                customIconValue = record.field("MonicaCustomIconValue")?.displayValue?.takeIf(String::isNotBlank),
                isArchived = record.field("MonicaIsArchived")?.displayValue.equals("true", true),
                sortOrder = record.field("MonicaSortOrder")?.displayValue?.toIntOrNull() ?: 0,
                isGroupCover = record.field("MonicaIsGroupCover")?.displayValue.equals("true", true),
                boundNoteId = record.field("MonicaBoundNoteId")?.displayValue?.toLongOrNull(),
                categoryId = record.field("MonicaCategoryId")?.displayValue?.toLongOrNull(),
                passkeyBindings = record.field("MonicaPasskeyBindings")?.displayValue.orEmpty(),
            )
        }
        val secureRows = workspace.secureItems.filterNot { it.isInRecycleBin }.map { source ->
            val identity = KeePassNativeEntryIdentity(databaseId, UUID.fromString(source.item.keepassEntryUuid))
            check(browser.entriesByIdentity[identity]?.size == 1) { "KeePass secure item identity is missing or ambiguous" }
            val id = auxiliaryId(identity, "secure:${source.item.itemType}")
            synchronized(this) { secureIdentities[id] = identity }
            source.item.copy(id = id, itemData = if (source.item.itemType == ItemType.NOTE)
                security?.encryptData(source.item.itemData) ?: source.item.itemData else source.item.itemData)
        }
        val passkeys = workspace.passkeys.map { (uuid, row) ->
            val identity = KeePassNativeEntryIdentity(databaseId, uuid)
            check(browser.entriesByIdentity[identity]?.size == 1) { "KeePass passkey identity is missing or ambiguous" }
            val id = auxiliaryId(identity, "passkey")
            synchronized(this) { passkeyIdentities[id] = identity }
            row.copy(id = id)
        }
        val previousRows = previous?.rows?.associateBy { it.id }.orEmpty()
        val presentedRows = rows.map { row ->
            val record = browser.entriesByIdentity[KeePassNativeEntryIdentity(databaseId, UUID.fromString(row.keepassEntryUuid))]!!.single()
            val ssoUuid = record.field("MonicaSsoRefEntryUuid")?.displayValue?.takeIf(String::isNotBlank)
            val noteUuid = record.field("MonicaBoundNoteUuid")?.displayValue?.takeIf(String::isNotBlank)
            val legacySso = row.ssoRefEntryId?.let { legacyId -> workspace.passwords.singleOrNull { it.monicaLocalId == legacyId }?.entryUuid }
            row.copy(ssoRefEntryId = (ssoUuid ?: legacySso)?.let { uuid -> rows.singleOrNull { it.keepassEntryUuid == uuid }?.id } ?: row.ssoRefEntryId,
                boundNoteId = noteUuid?.let { uuid -> secureRows.singleOrNull { it.itemType == ItemType.NOTE && it.keepassEntryUuid == uuid }?.id } ?: row.boundNoteId)
                .let { updated -> previousRows[row.id]?.takeIf { it == updated } ?: updated }
        }
        return Snapshot(browser, presentedRows, custom, secureRows, lockVersion, passkeys, read.sourceBrowser, read.passwordProjection).also { snapshot ->
            synchronized(this) {
                requireReadable(databaseId)
                check((KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L) == lockVersion) { "KeePass database was locked while reading" }
                snapshots.update { it + (databaseId to snapshot) }
                _fileStates.update { if (databaseId in it) it else it + (databaseId to KeePassFileState.READY) }
            }
        }
    }

    fun rows(databaseId: Long): Flow<List<PasswordEntry>> = flow {
        if (snapshots.value[databaseId] == null && SessionManager.isUnlocked.value) {
            try { refresh(databaseId) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // A startup lock or failed initial read must not end this subscription.
                // The visible screen's explicit refresh can publish a valid snapshot later.
            }
        }
        emitAll(combine(snapshots, SessionManager.isUnlocked, KeePassKdbxService.nativeLockVersions) { data, unlocked, versions ->
            if (unlocked && data[databaseId]?.lockVersion == (versions[databaseId] ?: 0L)) data[databaseId]?.rows.orEmpty() else emptyList()
        })
    }

    fun get(id: Long, forEdit: Boolean = false): PasswordEntry? = synchronized(this) {
        val identity = identities[id] ?: return null
        val snapshot = snapshots.value[identity.databaseId] ?: return null
        if (!SessionManager.isUnlocked.value || snapshot.lockVersion != (KeePassKdbxService.nativeLockVersions.value[identity.databaseId] ?: 0L)) return null
        snapshot.rows.firstOrNull { it.id == id }?.also { if (forEdit) drafts[id] = snapshot }
    }

    fun databaseId(id: Long): Long? = synchronized(this) { identities[id]?.databaseId }

    fun hasOriginalHistory(id: Long): Boolean = synchronized(this) {
        check(get(id) != null) { "KeePass entry is unavailable" }
        val identity = requireNotNull(identities[id])
        snapshots.value[identity.databaseId]?.browser?.entriesByIdentity?.get(identity)?.singleOrNull()?.history?.isNotEmpty() == true
    }

    fun getSecure(id: Long, forEdit: Boolean = false): SecureItem? = synchronized(this) {
        val identity = secureIdentities[id] ?: return null
        val snapshot = snapshots.value[identity.databaseId] ?: return null
        if (!SessionManager.isUnlocked.value || snapshot.lockVersion != (KeePassKdbxService.nativeLockVersions.value[identity.databaseId] ?: 0L)) return null
        snapshot.secureItems.firstOrNull { it.id == id }?.also { if (forEdit) secureDrafts.putIfAbsent(id, snapshot) }
    }
    fun secureFlow(id: Long): Flow<SecureItem?> = combine(snapshots, SessionManager.isUnlocked) { _, _ -> getSecure(id) }
    fun getPasskey(id: Long, forEdit: Boolean = false): PasskeyEntry? = synchronized(this) {
        val identity = passkeyIdentities[id] ?: return null
        val snapshot = snapshots.value[identity.databaseId] ?: return null
        if (!SessionManager.isUnlocked.value || snapshot.lockVersion != (KeePassKdbxService.nativeLockVersions.value[identity.databaseId] ?: 0L)) return null
        snapshot.passkeys.firstOrNull { it.id == id }?.also { if (forEdit) passkeyDrafts.putIfAbsent(id, snapshot) }
    }
    suspend fun savePasskey(row: PasskeyEntry) = mutex.withLock {
        val identity = requireNotNull(synchronized(this) { passkeyIdentities[row.id] })
        requireReadable(identity.databaseId)
        check(row.keepassDatabaseId == identity.databaseId && row.mdbxDatabaseId == null && row.bitwardenVaultId == null) { "KeePass passkey ownership changed" }
        val origin = synchronized(this) { passkeyDrafts[row.id] } ?: requireNotNull(snapshots.value[identity.databaseId])
        service.saveMonicaPasskey(identity.databaseId, identity.entryUuid, row, origin.browser.sourceRevision.sha256).getOrThrow()
        load(identity.databaseId)
        synchronized(this) { passkeyDrafts.remove(row.id) }
    }
    suspend fun deletePasskey(row: PasskeyEntry) = mutex.withLock {
        val identity = requireNotNull(synchronized(this) { passkeyIdentities[row.id] })
        requireReadable(identity.databaseId)
        check(row.keepassDatabaseId == identity.databaseId) { "KeePass passkey ownership changed" }
        val snapshot = requireNotNull(snapshots.value[identity.databaseId])
        if (snapshot.passkeys.none { it.id == row.id }) return@withLock
        service.deleteNativeEntries(identity.databaseId, setOf(identity.entryUuid), KeePassNativeDeleteMode.PERMANENT,
            snapshot.browser.sourceRevision.sha256).getOrThrow()
        load(identity.databaseId)
    }
    suspend fun saveSecure(item: SecureItem): SecureItem = mutex.withLock {
        val databaseId = requireNotNull(item.keepassDatabaseId)
        requireReadable(databaseId)
        val snapshot = snapshots.value[databaseId] ?: load(databaseId)
        val origin = synchronized(this) { secureDrafts[item.id] } ?: snapshot
        val identity = synchronized(this) { secureIdentities[item.id] }
        check(item.id == 0L || identity?.databaseId == databaseId) { "KeePass secure item ownership changed" }
        val record = identity?.let { origin.browser.entriesByIdentity[it]?.singleOrNull() }
        check(item.id == 0L || record != null) { "KeePass secure item no longer exists" }
        check(item.mdbxDatabaseId == null && item.bitwardenVaultId == null) { "KeePass ownership changed" }
        val uuid = identity?.entryUuid ?: UUID.randomUUID()
        service.saveMonicaSecureItem(databaseId, uuid, record != null, item,
            origin.browser.sourceRevision.sha256).getOrThrow()
        val after = load(databaseId)
        val saved = requireNotNull(after.secureItems.singleOrNull { it.keepassEntryUuid == uuid.toString() && it.itemType == item.itemType })
        synchronized(this) { secureDrafts.remove(saved.id) }
        saved
    }
    suspend fun deleteSecure(item: SecureItem, recycle: Boolean) = mutex.withLock {
        val databaseId = requireNotNull(item.keepassDatabaseId)
        requireReadable(databaseId)
        val identity = requireNotNull(synchronized(this) { secureIdentities[item.id] })
        check(identity.databaseId == databaseId && identity.entryUuid.toString() == item.keepassEntryUuid) { "KeePass selection identity changed" }
        val snapshot = snapshots.value[databaseId] ?: error("KeePass database must be reloaded")
        if (snapshot.secureItems.none { it.id == item.id }) return@withLock
        service.deleteNativeEntries(databaseId, setOf(identity.entryUuid),
            if (recycle) KeePassNativeDeleteMode.RECYCLE_BIN else KeePassNativeDeleteMode.PERMANENT,
            snapshot.browser.sourceRevision.sha256).getOrThrow()
        load(databaseId)
    }

    fun matchesScope(row: PasswordEntry, scope: KeePassPasswordNativeScope): Boolean = synchronized(this) {
        val snapshot = snapshots.value[scope.databaseId] ?: return false
        val identity = identities[row.id] ?: return false
        val record = snapshot.browser.entriesByIdentity[identity]?.singleOrNull() ?: return false
        val group = scope.initialGroup(snapshot.browser)
        !record.isInRecycleBin && record.parentGroup in snapshot.browser.descendantGroupIdentities(group) &&
            (!scope.starred || row.isFavorite) && (!scope.uncategorized || record.parentGroup == snapshot.browser.rootGroup.identity)
    }

    fun fields(id: Long): List<CustomField> = synchronized(this) {
        if (get(id) == null) emptyList() else snapshots.value[identities[id]?.databaseId]?.customFields?.get(id).orEmpty()
    }

    fun fieldsFlow(id: Long): Flow<List<CustomField>> = combine(snapshots, SessionManager.isUnlocked) { _, _ -> fields(id) }

    fun history(id: Long): Flow<List<PasswordHistoryEntry>> = combine(snapshots, SessionManager.isUnlocked) { data, unlocked ->
        val identity = synchronized(this) { identities[id] }
        if (!unlocked || identity == null || get(id) == null) emptyList() else {
            data[identity.databaseId]?.browser?.entriesByIdentity?.get(identity)?.singleOrNull()?.history.orEmpty()
                .map { version ->
                    val historyId = auxiliaryId(identity, "history:${version.index}:${version.times?.lastModificationTime}")
                    synchronized(this) { historyIdentities[historyId] = HistoryIdentity(identity, version.index,
                        requireNotNull(data[identity.databaseId]).browser.sourceRevision.sha256) }
                    PasswordHistoryEntry(
                    id = historyId, entryId = id,
                    password = version.fields.firstOrNull { it.name == "Password" }?.displayValue.orEmpty(),
                    lastUsedAt = Date(version.times?.lastModificationTime?.toEpochMilli() ?: 0L),
                ) }.filter { it.password.isNotEmpty() }
        }
    }

    suspend fun save(databaseId: Long, entries: List<PasswordEntry>, fields: Map<Long, List<CustomFieldDraft>>,
                     removedIds: List<Long> = emptyList()): List<PasswordEntry> = mutex.withLock {
        requireReadable(databaseId)
        val snapshot = snapshots.value[databaseId] ?: load(databaseId)
        val origins = synchronized(this) { entries.mapNotNull { drafts[it.id] } }
        val revision = origins.firstOrNull()?.browser?.sourceRevision?.sha256 ?: snapshot.browser.sourceRevision.sha256
        check(origins.all { it.browser.sourceRevision.sha256 == revision }) { "KeePass drafts have different source revisions" }
        val prepared = entries.map { row ->
            check(row.keepassDatabaseId == databaseId && row.mdbxDatabaseId == null && row.bitwardenVaultId == null) { "KeePass ownership changed" }
            val origin = synchronized(this) { drafts[row.id] } ?: snapshot
            val identity = synchronized(this) { identities[row.id] }
            check(row.id == 0L || identity?.databaseId == databaseId) { "Stale KeePass UI entry" }
            val record = identity?.let { origin.browser.entriesByIdentity[it]?.singleOrNull() }
            check(row.id == 0L || record != null) { "KeePass entry no longer exists" }
            val uuid = record?.identity?.entryUuid ?: UUID.randomUUID()
            val parent = if (row.keepassGroupUuid != null) {
                origin.browser.groupsByIdentity[KeePassNativeGroupIdentity(databaseId, UUID.fromString(row.keepassGroupUuid))]?.singleOrNull()
                    ?: error("KeePass group is missing or ambiguous")
            } else if (!row.keepassGroupPath.isNullOrBlank()) {
                origin.browser.groups.singleOrNull { it.legacyPath == row.keepassGroupPath }
                    ?: error("KeePass group is missing or ambiguous")
            } else origin.browser.rootGroup
            val fieldDrafts = fields[row.id]?.mapIndexed { index, field ->
                KeePassCustomFieldData(field.title, field.value, field.isProtected, index)
            } ?: origin.customFields[row.id].orEmpty().map { KeePassCustomFieldData(it.title, it.value, it.isProtected, it.sortOrder) }
            val ssoIdentity = row.ssoRefEntryId?.takeIf(::isUiId)?.let { ref -> requireNotNull(synchronized(this) { identities[ref] }) }
            val noteIdentity = row.boundNoteId?.takeIf(::isUiId)?.let { ref -> requireNotNull(synchronized(this) { secureIdentities[ref] }) }
            check(ssoIdentity == null || ssoIdentity.databaseId == databaseId) { "KeePass SSO reference belongs to another database" }
            check(noteIdentity == null || noteIdentity.databaseId == databaseId) { "KeePass note reference belongs to another database" }
            val portableRow = row.copy(
                ssoRefEntryId = if (ssoIdentity != null) origin.browser.entriesByIdentity[ssoIdentity]?.singleOrNull()?.field("MonicaLocalId")?.rawValue?.toLongOrNull() else row.ssoRefEntryId,
                boundNoteId = if (noteIdentity != null) origin.browser.entriesByIdentity[noteIdentity]?.singleOrNull()?.field("MonicaSecureItemId")?.rawValue?.toLongOrNull() else row.boundNoteId,
            )
            val priorRow = origin.rows.firstOrNull { it.id == row.id }
            val ssoUuid = ssoIdentity?.entryUuid?.toString() ?: record?.field("MonicaSsoRefEntryUuid")?.rawValue
                ?.takeIf { row.ssoRefEntryId == priorRow?.ssoRefEntryId }
            val noteUuid = noteIdentity?.entryUuid?.toString() ?: record?.field("MonicaBoundNoteUuid")?.rawValue
                ?.takeIf { row.boundNoteId == priorRow?.boundNoteId }
            KeePassMonicaPasswordDraft(uuid, record != null, parent.identity.groupUuid,
                service.buildMonicaPasswordFields(record, portableRow,
                    origin.customFields[row.id].orEmpty().map { it.title }.toSet(), fieldDrafts, ssoUuid, noteUuid))
        }
        val removed = synchronized(this) { removedIds.map {
            requireNotNull(identities[it]).also { identity -> check(identity.databaseId == databaseId) }.entryUuid
        }.toSet() }
        service.saveMonicaPasswordDrafts(databaseId, prepared, removed, revision).getOrThrow()
        val after = load(databaseId)
        prepared.map { draft ->
            val id = idFor(KeePassNativeEntryIdentity(databaseId, draft.uuid))
            requireNotNull(after.rows.firstOrNull { it.id == id }).also { synchronized(this) { drafts[id] = after } }
        }
    }

    suspend fun delete(entries: List<PasswordEntry>, recycle: Boolean) = mutex.withLock {
        entries.groupBy { requireNotNull(it.keepassDatabaseId) }.forEach { (databaseId, rows) ->
            requireReadable(databaseId)
            val snapshot = snapshots.value[databaseId] ?: error("KeePass database must be reloaded")
            val uuids = rows.map { row -> requireNotNull(synchronized(this) { identities[row.id] }).entryUuid }.toSet()
            check(rows.all { row -> synchronized(this) { identities[row.id] } ==
                KeePassNativeEntryIdentity(databaseId, UUID.fromString(row.keepassEntryUuid)) }) { "KeePass selection identity changed" }
            service.deleteNativeEntries(databaseId, uuids,
                if (recycle) KeePassNativeDeleteMode.RECYCLE_BIN else KeePassNativeDeleteMode.PERMANENT,
                snapshot.browser.sourceRevision.sha256).getOrThrow()
            load(databaseId)
        }
    }

    suspend fun moveToDatabase(row: PasswordEntry, targetId: Long, groupPath: String?) = mutex.withLock {
        val identity = requireNotNull(synchronized(this) { identities[row.id] })
        requireReadable(identity.databaseId)
        check(get(row.id) == row) { "KeePass selection changed" }
        service.moveNativeEntry(identity.databaseId, identity.entryUuid.toString(), targetId, groupPath).getOrThrow()
        load(identity.databaseId)
        load(targetId)
    }

    private fun idFor(identity: KeePassNativeEntryIdentity): Long = synchronized(this) {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${identity.databaseId}:${identity.entryUuid}".toByteArray(Charsets.UTF_8))
        val id = ID_BASE or (ByteBuffer.wrap(bytes).long and (ID_BASE - 1))
        check(identities[id] == null || identities[id] == identity) { "KeePass UI identity collision" }
        identities[id] = identity
        id
    }

    suspend fun deleteHistory(historyId: Long) = mutex.withLock {
        val identity = synchronized(this) { requireNotNull(historyIdentities[historyId]) }
        requireReadable(identity.entry.databaseId)
        service.deleteNativeEntryHistory(identity.entry.databaseId, identity.entry.entryUuid,
            identity.index, identity.revision).getOrThrow()
        load(identity.entry.databaseId)
    }

    suspend fun clearHistory(entryId: Long) = mutex.withLock {
        val identity = synchronized(this) { requireNotNull(identities[entryId]) }
        requireReadable(identity.databaseId)
        var snapshot = snapshots.value[identity.databaseId] ?: error("KeePass database must be reloaded")
        val history = snapshot.browser.entriesByIdentity[identity]?.singleOrNull()?.history.orEmpty()
        history.indices.reversed().forEach { index ->
            service.deleteNativeEntryHistory(identity.databaseId, identity.entryUuid, index,
                snapshot.browser.sourceRevision.sha256).getOrThrow()
            snapshot = load(identity.databaseId)
        }
    }

    private fun auxiliaryId(identity: KeePassNativeEntryIdentity, suffix: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${identity.databaseId}:${identity.entryUuid}:$suffix".toByteArray(Charsets.UTF_8))
        val id = ID_BASE or (ByteBuffer.wrap(bytes).long and (ID_BASE - 1))
        check(synchronized(this) { identities[id] == null }) { "KeePass UI identity collision" }
        return id
    }

    private fun KeePassEntryData.toRow(id: Long, databaseId: Long) = PasswordEntry(
        id = id, title = title, username = username, password = password, website = url, notes = notes,
        appPackageName = appPackageName, appName = appName, email = email, phone = phone,
        addressLine = addressLine, city = city, state = state, zipCode = zipCode, country = country,
        creditCardNumber = creditCardNumber, creditCardHolder = creditCardHolder, creditCardExpiry = creditCardExpiry, creditCardCVV = creditCardCVV,
        sshKeyData = sshKeyData, authenticatorKey = authenticatorKey, loginType = loginType, ssoProvider = ssoProvider,
        ssoRefEntryId = ssoRefEntryId, wifiMetadata = wifiMetadata,
        keepassDatabaseId = databaseId, keepassEntryUuid = entryUuid, keepassGroupPath = groupPath, keepassGroupUuid = groupUuid,
        createdAt = Date(createdAtMillis ?: 0L), updatedAt = Date(updatedAtMillis ?: 0L),
    )

    companion object {
        private const val ID_BASE = 0x4000000000000000L
        fun isUiId(id: Long): Boolean = id >= ID_BASE
        val UI_FIELDS = setOf("MonicaIsFavorite", "MonicaPasswordGroupId", "MonicaReplicaGroupId", "MonicaCustomIconType", "MonicaCustomIconValue", "MonicaIsArchived", "MonicaSortOrder", "MonicaIsGroupCover", "MonicaBoundNoteId", "MonicaPasskeyBindings", "MonicaCategoryId", "MonicaSsoRefEntryUuid", "MonicaBoundNoteUuid")
    }
}
