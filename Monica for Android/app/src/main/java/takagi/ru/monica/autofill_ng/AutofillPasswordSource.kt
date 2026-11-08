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
import takagi.ru.monica.data.addOrReplaceLinkedAppBinding
import takagi.ru.monica.data.model.PasswordUrlRules
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassEntryData
import takagi.ru.monica.utils.KeePassKdbxService

/** Only identity and authorization metadata travel through an Autofill PendingIntent. */
data class NativeAutofillSelection(
    val databaseId: Long,
    val entryUuid: String,
    val revision: String,
    val lockVersion: Long,
    val processNonce: String,
    val generation: Long,
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

    private val service = KeePassKdbxService(context.applicationContext, database.localKeePassDatabaseDao(), security)
    private val repository = PasswordRepository(database.passwordEntryDao())
    private val mutex = Mutex()
    // A later explicit lock must never become a newly authorized epoch in this request.
    private val lockVersions = KeePassKdbxService.nativeLockVersions.value.toMap()
    @Volatile private var nativeRows: Map<Long, NativeRows> = emptyMap()
    @Volatile private var roomRows: Map<Long, PasswordEntry> = emptyMap()
    @Volatile private var unavailable: Set<Long> = emptySet()
    @Volatile private var loaded = false
    @Volatile private var revoked = false

    val groups: Map<Long, List<String>>
        get() = if (!authorized()) emptyMap() else nativeRows.filterKeys(::databaseAuthorized).mapValues { it.value.groups }

    val unavailableDatabaseIds: Set<Long>
        get() = unavailable + nativeRows.keys.filterNot(::databaseAuthorized)

    fun clear() = synchronized(this) {
        revoked = true
        nativeRows = emptyMap()
        roomRows = emptyMap()
        unavailable = emptySet()
        loaded = false
    }

    suspend fun load(): List<PasswordEntry> = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): List<PasswordEntry> = withContext(Dispatchers.IO) {
        if (!authorized()) {
            clear()
            return@withContext emptyList()
        }
        val registrations = database.localKeePassDatabaseDao().getAllDatabases().first()
        val registeredIds = registrations.map { it.id }.toSet()
        val local = repository.getAllPasswordEntries().first().filterNot { it.keepassDatabaseId in registeredIds }
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
        synchronized(this@AutofillPasswordSource) {
            if (!authorized()) {
                clear()
                return@withContext emptyList()
            }
            // No stale Room mirror is returned for an unreadable or explicitly locked KDBX.
            nativeRows = next.filterKeys(::databaseAuthorized)
            roomRows = local.associateBy { it.id }
            unavailable = failed + (next.keys - nativeRows.keys)
            loaded = true
            local + nativeRows.values.flatMap { it.rows.values }
        }
    }

    suspend fun get(id: Long): PasswordEntry? = mutex.withLock {
        if (!authorized()) return@withLock null
        if (!loaded) loadLocked()
        val entry = nativeRows.values.firstNotNullOfOrNull { it.rows[id] } ?: roomRows[id]
        entry?.let { resolveLocked(it) }
    }

    /** Revalidate a short-lived capability without opening any unrelated native library. */
    suspend fun getSelected(expected: NativeAutofillSelection): PasswordEntry? = mutex.withLock {
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

    private suspend fun resolveLocked(entry: PasswordEntry): PasswordEntry? {
        if (!isCurrent(entry)) return null
        if (!isNative(entry)) {
            return withContext(Dispatchers.IO) {
                val current = database.passwordEntryDao().getPasswordEntryById(entry.id)
                val nativeOwner = entry.keepassDatabaseId?.let { database.localKeePassDatabaseDao().getDatabaseById(it) }
                current?.takeIf { nativeOwner == null && it == entry && !it.isDeleted && !it.isArchived && authorized() }
            }
        }
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        val before = nativeRows[databaseId] ?: return null
        val current = readNative(databaseId, before.revision) ?: return null
        return current.rows[entry.id]?.takeIf { it == entry && isCurrent(entry) }
    }

    fun isCurrent(entry: PasswordEntry): Boolean {
        if (!authorized() || entry.isDeleted || entry.isArchived) return false
        if (!isNative(entry)) return !KeePassMonicaPasswordStore.isUiId(entry.id) && roomRows[entry.id] == entry
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        return databaseAuthorized(databaseId) && nativeRows[databaseId]?.rows?.get(entry.id) == entry
    }

    fun selection(entry: PasswordEntry): NativeAutofillSelection? {
        if (!isNative(entry) || !isCurrent(entry)) return null
        val databaseId = requireNotNull(entry.keepassDatabaseId)
        val snapshot = nativeRows[databaseId] ?: return null
        return NativeAutofillSelection(databaseId, requireNotNull(entry.keepassEntryUuid), snapshot.revision,
            lockVersions[databaseId] ?: 0L, processNonce, generation)
    }

    fun matchesSelection(entry: PasswordEntry, expected: NativeAutofillSelection): Boolean =
        selection(entry) == expected

    suspend fun urlRules(entries: List<PasswordEntry>): Map<Long, List<PasswordUrlRules.Rule>> = withContext(Dispatchers.IO) {
        if (!authorized()) return@withContext entries.associate { it.id to emptyList() }
        val result = PasswordUrlRuleStore.load(database.customFieldDao(),
            entries.filter { !isNative(it) && isCurrent(it) }.map { it.id }).toMutableMap()
        entries.forEach { entry ->
            if (!isCurrent(entry)) {
                result[entry.id] = emptyList()
            } else if (isNative(entry)) {
                nativeRows[entry.keepassDatabaseId]?.urlRules?.get(entry.id)?.let { result[entry.id] = it }
            }
        }
        if (authorized()) result else entries.associate { it.id to emptyList() }
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

    private fun authorized(): Boolean = !revoked && security.isDataReadCurrent(generation) && isAuthorized()

    private fun databaseAuthorized(databaseId: Long): Boolean = authorized() &&
        ((lockVersions[databaseId] ?: 0L) == 0L || allowLockedDatabases) &&
        (KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L) == (lockVersions[databaseId] ?: 0L)

    private suspend fun readNative(databaseId: Long, revision: String? = null): NativeRows? =
        BoundedSecureDataRead.readChecked(isCurrent = { databaseAuthorized(databaseId) }) {
            val readScope = this
            val snapshot = runBlocking {
                service.readAutofillSnapshot(databaseId, revision, readScope::ensureActive).getOrThrow()
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

        fun isNative(entry: PasswordEntry): Boolean = entry.keepassDatabaseId?.takeIf { it > 0 }?.let { databaseId ->
            entry.keepassEntryUuid?.let { uuid -> runCatching { nativeId(databaseId, uuid) == entry.id }.getOrDefault(false) }
        } == true

        fun isSelectionFromCurrentProcess(selection: NativeAutofillSelection): Boolean = selection.processNonce == processNonce

        suspend fun loadRoomEntries(database: PasswordDatabase): List<PasswordEntry> = withContext(Dispatchers.IO) {
            val registered = database.localKeePassDatabaseDao().getAllDatabases().first().map { it.id }.toSet()
            PasswordRepository(database.passwordEntryDao()).getAllPasswordEntries().first()
                .filterNot { it.keepassDatabaseId in registered }
        }
    }
}
