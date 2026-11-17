package takagi.ru.monica.autofill_ng

import android.content.Context
import androidx.room.withTransaction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isUsable
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.data.model.isExternalSteamMaFileEntry
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.Mdbx2Repository
import takagi.ru.monica.repository.MdbxAutofillCredential
import takagi.ru.monica.repository.MdbxAutofillWallet
import takagi.ru.monica.repository.mdbxPasswordObjectId
import takagi.ru.monica.repository.mdbx2PhysicalEntryId
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassKdbxService

enum class NativeAutofillStorage { KEEPASS, MDBX }

/** Only identity and authorization metadata travel through an Autofill PendingIntent. */
data class NativeAutofillSelection(
    val databaseId: Long,
    val entryUuid: String,
    val revision: String,
    val lockVersion: Long,
    val processNonce: String,
    val generation: Long,
    val storage: NativeAutofillStorage = NativeAutofillStorage.KEEPASS,
)

/** Request-local native reads. This never opens the main application or IME session. */
class AutofillPasswordSource(
    context: Context,
    private val security: SecurityManager,
    private val generation: Long,
    private val isAuthorized: () -> Boolean,
    private val database: PasswordDatabase = PasswordDatabase.getDatabase(context.applicationContext),
    private val allowLockedDatabases: Boolean = false,
) {
    private data class NativeRows(
        val revision: String,
        val rows: Map<Long, PasswordEntry>,
        val groups: List<String>,
        val urlRules: Map<Long, List<PasswordUrlRules.Rule>>,
    )

    private data class CurrentBatch(
        val room: Map<Long, PasswordEntry>,
        val native: Map<Long, NativeRows>,
        val mdbx: Map<Long, MdbxRows>,
        val nativeLockVersions: Map<Long, Long>,
    )

    private data class MdbxRows(
        val credentials: Map<Long, MdbxAutofillCredential>,
        val wallets: Map<Long, MdbxAutofillWallet>,
    )

    private val service = KeePassKdbxService(context.applicationContext, database.localKeePassDatabaseDao(), security)
    private val repository = PasswordRepository(database.passwordEntryDao())
    private val mdbxRepository = Mdbx2Repository(context.applicationContext, database.localMdbxDatabaseDao(), security)
    private val mutex = Mutex()
    // A later explicit lock must never become a newly authorized epoch in this request.
    private val lockVersions = KeePassKdbxService.nativeLockVersions.value.toMap()
    @Volatile private var nativeRows: Map<Long, NativeRows> = emptyMap()
    @Volatile private var roomRows: Map<Long, PasswordEntry> = emptyMap()
    @Volatile private var mdbxRows: Map<Long, MdbxRows> = emptyMap()
    @Volatile private var unavailable: Set<Long> = emptySet()
    @Volatile private var unavailableMdbx: Set<Long> = emptySet()
    @Volatile private var loaded = false
    @Volatile private var revoked = false

    val groups: Map<Long, List<String>>
        get() = if (!authorized()) emptyMap() else nativeRows.filterKeys(::databaseAuthorized).mapValues { it.value.groups }

    val unavailableDatabaseIds: Set<Long>
        get() = unavailable + nativeRows.keys.filterNot(::databaseAuthorized)

    val unavailableMdbxDatabaseIds: Set<Long>
        get() = unavailableMdbx

    val nativeWalletItems: List<SecureItem>
        get() = if (authorized()) mdbxRows.values.flatMap { it.wallets.values.map { wallet -> wallet.item } } else emptyList()

    fun clear() = synchronized(this) {
        revoked = true
        nativeRows = emptyMap()
        roomRows = emptyMap()
        mdbxRows = emptyMap()
        unavailable = emptySet()
        unavailableMdbx = emptySet()
        loaded = false
    }

    suspend fun load(localOnly: Boolean = false): List<PasswordEntry> =
        load(if (localOnly) AutofillScope.Local else AutofillScope.All)

    suspend fun load(scope: AutofillScope): List<PasswordEntry> = mutex.withLock { loadLocked(scope) }

    private suspend fun loadLocked(scope: AutofillScope = AutofillScope.All): List<PasswordEntry> = withContext(Dispatchers.IO) {
        if (!authorized()) {
            clear()
            return@withContext emptyList()
        }
        // Scope reaches the original provider before opening or decoding any vault.
        val registrations = when (scope) {
            AutofillScope.All -> database.localKeePassDatabaseDao().getAllDatabases().first()
            is AutofillScope.KeePass -> database.localKeePassDatabaseDao().getAllDatabases().first()
                .filter { scope.databaseId == null || it.id == scope.databaseId }
            else -> emptyList()
        }
        val local = when (scope) {
            AutofillScope.Local -> database.passwordEntryDao().getActiveLocalAutofillCandidates().filter { it.isLocalOnlyEntry() }
            AutofillScope.All -> database.passwordEntryDao().getScopedRoomAutofillCandidates("ALL", null)
            is AutofillScope.KeePass -> database.passwordEntryDao().getScopedRoomAutofillCandidates("KEEPASS", scope.databaseId)
            is AutofillScope.Bitwarden -> database.passwordEntryDao().getScopedRoomAutofillCandidates("BITWARDEN", scope.vaultId)
            is AutofillScope.Mdbx -> emptyList()
        }.filterNot { it.isExternalSteamMaFileEntry() || isNativeId(it.id) || it.mdbxDatabaseId != null }
        val next = linkedMapOf<Long, NativeRows>()
        val failed = linkedSetOf<Long>()
        for (registration in registrations) {
            if (!authorized()) break
            if (!databaseAuthorized(registration.id)) {
                failed += registration.id
                continue
            }
            val snapshot = readNative(registration.id)
            if (snapshot == null) failed += registration.id else next[registration.id] = snapshot
        }
        val mdbxRegistrations = when (scope) {
            AutofillScope.All -> database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()
            is AutofillScope.Mdbx -> database.localMdbxDatabaseDao().getAvailableDatabasesSnapshot()
                .filter { scope.databaseId == null || it.id == scope.databaseId }
            else -> emptyList()
        }.filter { it.isUsable }
        val nextMdbx = linkedMapOf<Long, MdbxRows>()
        val failedMdbx = linkedSetOf<Long>()
        for (registration in mdbxRegistrations) {
            if (!authorized()) break
            val snapshot = readMdbx(registration.id)
            if (snapshot == null) failedMdbx += registration.id else nextMdbx[registration.id] = snapshot
        }
        synchronized(this@AutofillPasswordSource) {
            if (!authorized()) {
                clear()
                return@withContext emptyList()
            }
            // No stale Room mirror is returned for an unreadable or explicitly locked KDBX.
            nativeRows = next.filterKeys(::databaseAuthorized)
            roomRows = local.associateBy { it.id }
            mdbxRows = nextMdbx
            unavailable = failed + (next.keys - nativeRows.keys)
            unavailableMdbx = failedMdbx
            loaded = true
            local + nativeRows.values.flatMap { it.rows.values } +
                mdbxRows.values.flatMap { it.credentials.values.map { credential -> credential.entry } }
        }
    }

    suspend fun get(id: Long): PasswordEntry? = mutex.withLock {
        if (!authorized()) return@withLock null
        if (!loaded) loadLocked()
        val entry = nativeRows.values.firstNotNullOfOrNull { it.rows[id] } ?:
            mdbxRows.values.firstNotNullOfOrNull { it.credentials[id]?.entry } ?: roomRows[id]
        entry?.let { resolveLocked(it) }
    }

    /** Revalidate a short-lived capability without opening any unrelated native library. */
    suspend fun getSelected(expected: NativeAutofillSelection): PasswordEntry? = mutex.withLock {
        if (expected.storage == NativeAutofillStorage.MDBX) return@withLock getSelectedMdbx(expected)
        if (!isSelectionFromCurrentProcess(expected) || expected.generation != generation ||
            expected.revision.isBlank() || expected.lockVersion != (lockVersions[expected.databaseId] ?: 0L) ||
            !databaseAuthorized(expected.databaseId)) return@withLock null
        val id = runCatching { nativeId(expected.databaseId, expected.entryUuid) }.getOrNull() ?: return@withLock null
        val snapshot = readNative(expected.databaseId, expected.revision) ?: return@withLock null
        val entry = snapshot.rows[id] ?: return@withLock null
        synchronized(this@AutofillPasswordSource) {
            if (!databaseAuthorized(expected.databaseId)) return@withLock null
            nativeRows = nativeRows + (expected.databaseId to snapshot)
            entry.takeIf { matchesSelection(it, expected) }
        }
    }

    suspend fun resolve(entry: PasswordEntry): PasswordEntry? = mutex.withLock { resolveLocked(entry) }

    /** Editor result IDs are Room IDs. Resolve MDBX by its proven logical identity, never its title. */
    suspend fun getSavedRoomEntry(id: Long): PasswordEntry? = mutex.withLock {
        if (!authorized()) return@withLock null
        if (!loaded) loadLocked()
        val indexed = roomRows[id] ?: nativeRows.values.firstNotNullOfOrNull { it.rows[id] }
        if (indexed != null) return@withLock resolveLocked(indexed)
        withContext(Dispatchers.IO) {
            val owner = database.passwordEntryDao().getPasswordEntryById(id) ?: return@withContext null
            val databaseId = owner.mdbxDatabaseId ?: return@withContext null
            if (owner.isArchived || owner.isDeleted || owner.keepassDatabaseId != null || owner.bitwardenVaultId != null)
                return@withContext null
            val logicalId = mdbxPasswordObjectId(owner)
            val candidate = mdbxRows[databaseId]?.credentials?.values
                ?.singleOrNull { it.logicalEntryId == logicalId } ?: return@withContext null
            val resolved = resolveLocked(candidate.entry) ?: return@withContext null
            resolved.takeIf { database.passwordEntryDao().getPasswordEntryById(id) == owner && authorized() }
        }
    }

    private suspend fun resolveLocked(entry: PasswordEntry): PasswordEntry? {
        if (!isCurrent(entry)) return null
        if (isMdbx(entry)) {
            val original = mdbxRows[entry.mdbxDatabaseId]?.credentials?.get(entry.id) ?: return null
            val current = readMdbxCredential(requireNotNull(entry.mdbxDatabaseId), original.objectId, original.revision)
                ?: return null
            return current.entry.copy(id = entry.id).takeIf { it == entry && isCurrent(entry) }
        }
        if (!isNative(entry)) {
            return withContext(Dispatchers.IO) {
                val current = database.passwordEntryDao().getPasswordEntryById(entry.id)
                val nativeOwner = entry.keepassDatabaseId?.let { database.localKeePassDatabaseDao().getDatabaseById(it) }
                current?.takeIf { nativeOwner == null && it.mdbxDatabaseId == null && it == entry && !it.isDeleted && !it.isArchived && authorized() }
            }
        }
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        val before = nativeRows[databaseId] ?: return null
        val current = readNative(databaseId, before.revision) ?: return null
        return current.rows[entry.id]?.takeIf { it == entry && isCurrent(entry) }
    }

    fun isCurrent(entry: PasswordEntry): Boolean {
        if (!authorized() || entry.isDeleted || entry.isArchived) return false
        if (isMdbx(entry)) return mdbxRows[entry.mdbxDatabaseId]?.credentials?.get(entry.id)?.entry == entry
        if (!isNative(entry)) return !isNativeId(entry.id) && entry.mdbxDatabaseId == null && roomRows[entry.id] == entry
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        return databaseAuthorized(databaseId) && nativeRows[databaseId]?.rows?.get(entry.id) == entry
    }

    /** Fresh validation for this invocation only; action-time single-entry checks remain mandatory. */
    fun areCurrent(entries: List<PasswordEntry>): Boolean {
        // Preserve all(isCurrent)'s empty-list behavior while the picker is first mounting.
        if (entries.isEmpty()) return true
        val batch = beginCurrentBatch() ?: return false
        val nativeDatabaseIds = hashSetOf<Long>()
        for (entry in entries) {
            val native = isNative(entry)
            if (!isCurrentInBatch(batch, entry, native)) return false
            if (native && !isMdbx(entry)) nativeDatabaseIds += requireNotNull(entry.keepassDatabaseId)
        }
        return finishCurrentBatch(batch, nativeDatabaseIds)
    }

    private fun beginCurrentBatch(): CurrentBatch? {
        if (!authorized()) return null
        return synchronized(this) {
            if (revoked) null else CurrentBatch(roomRows, nativeRows, mdbxRows, KeePassKdbxService.nativeLockVersions.value)
        }
    }

    private fun isCurrentInBatch(batch: CurrentBatch, entry: PasswordEntry, native: Boolean): Boolean {
        if (revoked || SecurityManager.dataReadGeneration.value != generation || entry.isDeleted || entry.isArchived) return false
        if (isMdbx(entry)) return batch.mdbx[entry.mdbxDatabaseId]?.credentials?.get(entry.id)?.entry == entry
        if (!native) return !isNativeId(entry.id) && entry.mdbxDatabaseId == null && batch.room[entry.id] == entry
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        return batchDatabaseCurrent(databaseId, batch.nativeLockVersions) &&
            batch.native[databaseId]?.rows?.get(entry.id) == entry
    }

    private fun batchDatabaseCurrent(databaseId: Long, versions: Map<Long, Long>): Boolean =
        ((lockVersions[databaseId] ?: 0L) == 0L || allowLockedDatabases) &&
            (versions[databaseId] ?: 0L) == (lockVersions[databaseId] ?: 0L)

    private fun finishCurrentBatch(batch: CurrentBatch, nativeDatabaseIds: Set<Long>): Boolean {
        // Check external storage and foreground authorization again before accepting the batch.
        if (!authorized()) return false
        return synchronized(this) {
            val currentVersions = KeePassKdbxService.nativeLockVersions.value
            !revoked && SecurityManager.dataReadGeneration.value == generation &&
                roomRows === batch.room && nativeRows === batch.native && mdbxRows === batch.mdbx &&
                nativeDatabaseIds.all { batchDatabaseCurrent(it, currentVersions) }
        }
    }

    fun selection(entry: PasswordEntry): NativeAutofillSelection? {
        if (!isNative(entry) || !isCurrent(entry)) return null
        if (isMdbx(entry)) {
            val credential = mdbxRows[entry.mdbxDatabaseId]?.credentials?.get(entry.id) ?: return null
            return NativeAutofillSelection(requireNotNull(entry.mdbxDatabaseId), credential.objectId,
                credential.revision, 0L, processNonce, generation, NativeAutofillStorage.MDBX)
        }
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        val snapshot = nativeRows[databaseId] ?: return null
        return NativeAutofillSelection(databaseId, requireNotNull(entry.keepassEntryUuid), snapshot.revision,
            lockVersions[databaseId] ?: 0L, processNonce, generation)
    }

    fun matchesSelection(entry: PasswordEntry, expected: NativeAutofillSelection): Boolean =
        selection(entry) == expected

    suspend fun urlRules(entries: List<PasswordEntry>): Map<Long, List<PasswordUrlRules.Rule>> = withContext(Dispatchers.IO) {
        val batch = beginCurrentBatch() ?: return@withContext entries.associate { it.id to emptyList() }
        val localIds = arrayListOf<Long>()
        val nativeEntries = arrayListOf<PasswordEntry>()
        val nativeDatabaseIds = hashSetOf<Long>()
        val invalidIds = hashSetOf<Long>()
        for (entry in entries) {
            val native = isNative(entry)
            if (!isCurrentInBatch(batch, entry, native)) invalidIds += entry.id
            else if (isMdbx(entry)) {
                nativeEntries += entry
            } else if (native) {
                nativeEntries += entry
                nativeDatabaseIds += requireNotNull(entry.keepassDatabaseId)
            } else localIds += entry.id
        }
        val result = PasswordUrlRuleStore.load(database.customFieldDao(),
            localIds.filterNot { it in invalidIds }).toMutableMap()
        nativeEntries.forEach { entry ->
            if (isMdbx(entry)) batch.mdbx[entry.mdbxDatabaseId]?.credentials?.get(entry.id)?.urlRules
                ?.let { result[entry.id] = it }
            else batch.native[entry.keepassDatabaseId]?.urlRules?.get(entry.id)?.let { result[entry.id] = it }
        }
        // Missing rules allow default matching; invalid entries must instead carry an explicit denial.
        invalidIds.forEach { result[it] = emptyList() }
        if (finishCurrentBatch(batch, nativeDatabaseIds)) result else entries.associate { it.id to emptyList() }
    }

    suspend fun saveUriBinding(entry: PasswordEntry, applicationId: String?, webDomain: String?): Boolean = mutex.withLock {
        val current = resolveLocked(entry) ?: return@withLock false
        val appBinding = applicationId?.takeIf(String::isNotBlank)?.let {
            addOrReplaceLinkedAppBinding(current.appPackageName, current.appName, it, it)
        }
        val updatedWebsite = webDomain?.trim()?.takeIf(String::isNotEmpty)?.let { domain ->
            if (current.website.split(',').any { it.trim().equals(domain, ignoreCase = true) }) current.website
            else if (current.website.isBlank()) domain else "${current.website}, $domain"
        } ?: current.website
        if (updatedWebsite == current.website && (appBinding == null ||
                appBinding == (current.appPackageName to current.appName))) return@withLock true
        // The current MDBX FFI cannot atomically bind this patch to an expected revision.
        // Filling remains available; never compensate by changing a Room mirror.
        if (isMdbx(current)) return@withLock false
        if (isNative(current)) {
            val databaseId = requireNotNull(current.keepassDatabaseId)
            val revision = nativeRows[databaseId]?.revision ?: return@withLock false
            val saved = BoundedSecureDataRead.readChecked(isCurrent = { isCurrent(current) }) {
                val readScope = this
                runBlocking {
                    service.patchAutofillUriBinding(
                        databaseId, UUID.fromString(current.keepassEntryUuid), revision,
                        updatedWebsite.takeUnless { it == current.website },
                        appBinding?.first?.takeUnless { it == current.appPackageName },
                        appBinding?.second?.takeUnless { it == current.appName },
                        checkActive = readScope::ensureActive,
                    ).getOrThrow()
                }
            } ?: false
            // Keep the selected credential for the immediate final lock check. Its old revision
            // deliberately prevents a second resolve/write until the caller reloads the source.
            saved
        } else {
            try {
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        check(isCurrent(current)) { "Autofill request was revoked" }
                        check(database.passwordEntryDao().getPasswordEntryById(current.id) == current) { "Entry changed" }
                        repository.updatePasswordEntry(current.copy(
                            website = updatedWebsite,
                            appPackageName = appBinding?.first ?: current.appPackageName,
                            appName = appBinding?.second ?: current.appName,
                        ))
                        check(isCurrent(current)) { "Autofill request was revoked" }
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun resolveCurrentWallet(expected: SecureItem): SecureItem? = mutex.withLock {
        if (!isWalletCurrent(expected)) return@withLock null
        val databaseId = expected.mdbxDatabaseId ?: return@withLock null
        val original = mdbxRows[databaseId]?.wallets?.get(expected.id) ?: return@withLock null
        val current = BoundedSecureDataRead.readChecked(isCurrent = { isWalletCurrent(expected) }) {
            val scope = this
            runBlocking {
                val wallet = mdbxRepository.readAutofillWallet(databaseId, original.objectId, original.revision, scope::ensureActive)
                    ?: error("Native wallet is no longer available")
                check(wallet.objectId !in suppressedMdbxIds(requireNotNull(wallet.nativeVaultId),
                    database.secureItemDao().getSuppressedMdbxAutofillObjectIds(databaseId)))
                wallet
            }
        } ?: return@withLock null
        current.item.copy(id = expected.id).takeIf { it == expected && isWalletCurrent(expected) }
    }

    fun isWalletCurrent(expected: SecureItem): Boolean = authorized() && !expected.isDeleted &&
        expected.mdbxDatabaseId != null && mdbxRows[expected.mdbxDatabaseId]?.wallets?.get(expected.id)?.item == expected

    private suspend fun getSelectedMdbx(expected: NativeAutofillSelection): PasswordEntry? {
        if (!isSelectionFromCurrentProcess(expected) || expected.generation != generation ||
            expected.lockVersion != 0L || expected.revision.isBlank() || !authorized()) return null
        val id = runCatching { mdbxId(expected.databaseId, expected.entryUuid) }.getOrNull() ?: return null
        val value = readMdbxCredential(expected.databaseId, expected.entryUuid, expected.revision) ?: return null
        val credential = value.copy(entry = value.entry.copy(id = id))
        return synchronized(this) {
            if (!authorized()) return@synchronized null
            val previous = mdbxRows[expected.databaseId]
            mdbxRows = mdbxRows + (expected.databaseId to MdbxRows(
                previous?.credentials.orEmpty() + (id to credential), previous?.wallets.orEmpty()))
            credential.entry.takeIf { matchesSelection(it, expected) }
        }
    }

    private suspend fun readMdbxCredential(databaseId: Long, objectId: String, revision: String): MdbxAutofillCredential? =
        BoundedSecureDataRead.readChecked(isCurrent = ::authorized) {
            val scope = this
            runBlocking {
                val credential = mdbxRepository.readAutofillCredential(databaseId, objectId, revision, scope::ensureActive)
                    ?: error("Native credential is no longer available")
                check(credential.objectId !in suppressedMdbxIds(requireNotNull(credential.nativeVaultId),
                    database.passwordEntryDao().getSuppressedMdbxAutofillObjectIds(databaseId)))
                credential
            }
        }

    private suspend fun readMdbx(databaseId: Long): MdbxRows? =
        BoundedSecureDataRead.readChecked(isCurrent = ::authorized) {
            val scope = this
            val (snapshot, hidden) = runBlocking {
                val snapshot = mdbxRepository.readAutofillSnapshot(databaseId) { checkLoadActive(scope) }
                val vaultIds = (snapshot.credentials.map { it.nativeVaultId } + snapshot.walletItems.map { it.nativeVaultId }).distinct()
                check(vaultIds.isEmpty() || (vaultIds.size == 1 && !vaultIds.single().isNullOrBlank()))
                val vaultId = vaultIds.firstOrNull()
                snapshot to if (vaultId == null) (emptySet<String>() to emptySet()) else (
                    suppressedMdbxIds(vaultId, database.passwordEntryDao().getSuppressedMdbxAutofillObjectIds(databaseId)) to
                    suppressedMdbxIds(vaultId, database.secureItemDao().getSuppressedMdbxAutofillObjectIds(databaseId)))
            }
            ensureActive()
            val credentials = snapshot.credentials.filterNot { it.objectId in hidden.first }.map { credential ->
                checkLoadActive(scope)
                credential.copy(entry = credential.entry.copy(id = mdbxId(databaseId, credential.objectId)))
            }
            val wallets = snapshot.walletItems.filterNot { it.objectId in hidden.second }.map { wallet ->
                checkLoadActive(scope)
                wallet.copy(item = wallet.item.copy(id = mdbxWalletId(databaseId, wallet.objectId)))
            }
            check(credentials.map { it.entry.id }.distinct().size == credentials.size) { "Ambiguous native autofill identity" }
            check(wallets.map { it.item.id }.distinct().size == wallets.size) { "Ambiguous native wallet identity" }
            MdbxRows(credentials.associateBy { it.entry.id }, wallets.associateBy { it.item.id })
        }

    private fun authorized(): Boolean = !revoked && security.isDataReadCurrent(generation) && isAuthorized()

    // Native snapshots stay private until readChecked and loadLocked fully revalidate them.
    // Traversal must observe cancellation/lock immediately, without rereading encrypted
    // preferences and making system Binder calls for every field or native object.
    private fun checkLoadActive(scope: BoundedSecureDataRead.ReadScope, databaseId: Long? = null) {
        scope.ensureNotCancelled()
        check(!revoked && SecurityManager.dataReadGeneration.value == generation) { "Autofill load expired" }
        if (databaseId != null) {
            check(batchDatabaseCurrent(databaseId, KeePassKdbxService.nativeLockVersions.value)) { "KeePass load expired" }
        }
    }

    private fun suppressedMdbxIds(vaultId: String, logicalIds: List<String>): Set<String> =
        logicalIds.filter(String::isNotEmpty).mapTo(hashSetOf()) { mdbx2PhysicalEntryId(vaultId, it) }

    private fun databaseAuthorized(databaseId: Long): Boolean = authorized() &&
        ((lockVersions[databaseId] ?: 0L) == 0L || allowLockedDatabases) &&
        (KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L) == (lockVersions[databaseId] ?: 0L)

    private suspend fun readNative(databaseId: Long, revision: String? = null): NativeRows? =
        BoundedSecureDataRead.readChecked(isCurrent = { databaseAuthorized(databaseId) }) {
            val readScope = this
            val snapshot = runBlocking {
                service.readAutofillSnapshot(databaseId, revision) {
                    if (revision == null) checkLoadActive(readScope, databaseId) else readScope.ensureActive()
                }.getOrThrow()
            }
            ensureActive()
            val activeEntries = snapshot.entries.filterNot { it.isInRecycleBin }
            val rows = activeEntries.map { source ->
                val nativeReference = source.monicaPresentation["MonicaSsoRefEntryUuid"]?.let { reference ->
                    activeEntries.singleOrNull { it.entryUuid == reference }?.entryUuid
                } ?: source.ssoRefEntryId?.let { reference ->
                    activeEntries.singleOrNull { it.monicaLocalId == reference }?.entryUuid
                }
                source.toAutofillRow(databaseId).copy(
                    // A native reference must never accidentally resolve to an unrelated Room ID.
                    ssoRefEntryId = nativeReference?.let { nativeId(databaseId, it) },
                )
            }.filterNot { it.isArchived || it.isDeleted }
            check(rows.map { it.id }.distinct().size == rows.size) { "Ambiguous native autofill identity" }
            val urlRules = activeEntries.mapNotNull { source ->
                val fields = source.customFields.filter { it.title == PasswordUrlRules.FIELD }
                if (fields.isEmpty()) null else nativeId(databaseId, requireNotNull(source.entryUuid)) to
                    fields.singleOrNull()?.let { PasswordUrlRules.decode(it.value) }.orEmpty()
            }.toMap()
            NativeRows(snapshot.revision.sha256, rows.associateBy { it.id }, snapshot.groups, urlRules)
        }

    private fun KeePassEntryData.toAutofillRow(databaseId: Long): PasswordEntry {
        val uuid = UUID.fromString(requireNotNull(entryUuid))
        val meta = monicaPresentation
        return PasswordEntry(
            id = nativeId(databaseId, uuid.toString()), title = title, username = username, password = password,
            website = url, notes = notes, appPackageName = appPackageName, appName = appName,
            email = email, phone = phone, addressLine = addressLine, city = city, state = state,
            zipCode = zipCode, country = country, creditCardNumber = creditCardNumber,
            creditCardHolder = creditCardHolder, creditCardExpiry = creditCardExpiry, creditCardCVV = creditCardCVV,
            sshKeyData = sshKeyData, authenticatorKey = authenticatorKey, loginType = loginType,
            ssoProvider = ssoProvider, ssoRefEntryId = ssoRefEntryId, wifiMetadata = wifiMetadata,
            keepassDatabaseId = databaseId, keepassEntryUuid = uuid.toString(),
            keepassGroupPath = groupPath, keepassGroupUuid = groupUuid,
            createdAt = Date(createdAtMillis ?: 0L), updatedAt = Date(updatedAtMillis ?: 0L),
            isArchived = meta["MonicaIsArchived"].equals("true", true),
            isFavorite = meta["MonicaIsFavorite"].equals("true", true),
            passwordGroupId = meta["MonicaPasswordGroupId"]?.takeIf(String::isNotBlank),
            replicaGroupId = meta["MonicaReplicaGroupId"]?.takeIf(String::isNotBlank),
            customIconType = meta["MonicaCustomIconType"] ?: "NONE",
            customIconValue = meta["MonicaCustomIconValue"]?.takeIf(String::isNotBlank),
            sortOrder = meta["MonicaSortOrder"]?.toIntOrNull() ?: 0,
            isGroupCover = meta["MonicaIsGroupCover"].equals("true", true),
            categoryId = meta["MonicaCategoryId"]?.toLongOrNull(),
            passkeyBindings = meta["MonicaPasskeyBindings"].orEmpty(),
        )
    }

    companion object {
        private const val ID_BASE = 0x4000000000000000L
        private val processNonce = UUID.randomUUID().toString()

        fun nativeId(databaseId: Long, entryUuid: String): Long {
            require(databaseId > 0)
            val uuid = UUID.fromString(entryUuid)
            val digest = MessageDigest.getInstance("SHA-256").digest("$databaseId:$uuid".toByteArray(Charsets.UTF_8))
            return ID_BASE or (ByteBuffer.wrap(digest).long and (ID_BASE - 1))
        }

        fun isNative(entry: PasswordEntry): Boolean = isMdbx(entry) || entry.keepassDatabaseId?.takeIf { it > 0 }?.let { databaseId ->
            entry.keepassEntryUuid?.let { uuid -> runCatching { nativeId(databaseId, uuid) == entry.id }.getOrDefault(false) }
        } == true

        fun isNativeId(id: Long): Boolean = id < 0L || KeePassMonicaPasswordStore.isUiId(id)

        fun mdbxId(databaseId: Long, objectId: String): Long = mdbxVirtualId("login", databaseId, objectId)

        private fun mdbxWalletId(databaseId: Long, objectId: String): Long = mdbxVirtualId("wallet", databaseId, objectId)

        private fun mdbxVirtualId(kind: String, databaseId: Long, objectId: String): Long {
            require(databaseId > 0)
            val uuid = UUID.fromString(objectId)
            val digest = MessageDigest.getInstance("SHA-256").digest("mdbx:$kind:$databaseId:$uuid".toByteArray(Charsets.UTF_8))
            // Separate from positive Room and KDBX ids. Values are never inserted into Room.
            return Long.MIN_VALUE or (ByteBuffer.wrap(digest).long and Long.MAX_VALUE)
        }

        fun isMdbx(entry: PasswordEntry): Boolean = entry.mdbxDatabaseId?.takeIf { it > 0 }?.let { databaseId ->
            entry.replicaGroupId?.let { id -> runCatching { mdbxId(databaseId, id) == entry.id }.getOrDefault(false) }
        } == true && entry.keepassDatabaseId == null && entry.bitwardenVaultId == null

        fun isSelectionFromCurrentProcess(selection: NativeAutofillSelection): Boolean = selection.processNonce == processNonce

        suspend fun loadRoomEntries(database: PasswordDatabase): List<PasswordEntry> = withContext(Dispatchers.IO) {
            val registered = database.localKeePassDatabaseDao().getAllDatabases().first().map { it.id }.toSet()
            PasswordRepository(database.passwordEntryDao()).getAllPasswordEntries().first()
                .filterNot { it.keepassDatabaseId in registered || it.mdbxDatabaseId != null || isNativeId(it.id) }
        }
    }
}
