package takagi.ru.monica.bitwarden.service

import android.content.Context
import androidx.room.withTransaction
import java.time.Instant
import java.util.Date
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.CipherCreateRequest
import takagi.ru.monica.bitwarden.api.CipherUpdateRequest
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.bitwarden.BitwardenPasswordHistoryState
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** One Room transaction owns the UI rows, encrypted source mapping and local mutation generation. */
internal class BitwardenPasswordHistoryStore(
    private val context: Context,
    private val db: PasswordDatabase = PasswordDatabase.getDatabase(context),
    private val security: SecurityManager = SecurityManager(context),
    private val vaultSessionGeneration: (Long) -> Long = { BitwardenRepository.getInstance(context).getVaultSessionGeneration(it) },
    private val vaultUnlocked: (Long) -> Boolean = { BitwardenRepository.getInstance(context).isVaultUnlocked(it) }
) {
    private val history = db.passwordHistoryDao()
    private val states = db.bitwardenPasswordHistoryStateDao()
    private val entries = db.passwordEntryDao()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Serializable private data class Row(val localId: Long?, val wire: JsonElement?)
    @Serializable private data class Binding(val entryId: Long, val vaultId: Long, val cipherId: String,
        val revision: String, val localVersion: Long, val ackVersion: Long)
    @Serializable private data class Payload(
        val binding: Binding? = null,
        val initialized: Boolean = false,
        val clearRemote: Boolean = false,
        val rows: List<Row> = emptyList(),
        val lastCaptureToken: String? = null,
        val capturedPasswordDigests: Set<String> = emptySet(),
        val capturedHiddenDigests: Set<String> = emptySet(),
        val permissionsVerified: Boolean = false,
        val canEdit: Boolean = false
    )

    data class UploadPlan(
        val entry: PasswordEntry,
        val session: Session,
        val version: Long,
        val prepared: BitwardenPasswordHistory.Prepared
    )
    data class CreatePlan(val entry: PasswordEntry, val session: Session, val rows: List<PasswordHistoryEntry>, val version: Long,
        val request: CipherCreateRequest, val prepared: BitwardenPasswordHistory.Prepared)

    /** Scalars only: lock then unlock never reauthorizes an in-flight history operation. */
    data class Session(val vaultId: Long, val globalGeneration: Long, val vaultGeneration: Long)

    fun captureSession(vaultId: Long): Session {
        val value = Session(vaultId, SessionManager.lockGeneration.value, vaultSessionGeneration(vaultId))
        check(isSessionCurrent(value)) { "Bitwarden vault is locked" }
        return value
    }
    fun isSessionCurrent(session: Session): Boolean =
        SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == session.globalGeneration &&
            vaultUnlocked(session.vaultId) && vaultSessionGeneration(session.vaultId) == session.vaultGeneration

    private inner class AuthorizedScope {
        private val globalGeneration = SessionManager.lockGeneration.value
        private val sessions = mutableMapOf<Long, Session>()
        fun authorize(vaultId: Long): Session = sessions.getOrPut(vaultId) {
            captureSession(vaultId).also { check(it.globalGeneration == globalGeneration) { "History session changed" } }
        }.also { check(isSessionCurrent(it)) { "History session changed" } }
        fun expect(session: Session) {
            check(isSessionCurrent(session) && session.globalGeneration == globalGeneration) { "History session changed" }
            sessions[session.vaultId] = session
        }
        fun verify() { sessions.values.forEach { check(isSessionCurrent(it)) { "History session changed" } } }
    }

    private suspend fun <T> authorizedTransaction(vaultId: Long? = null, expected: Session? = null,
        action: suspend AuthorizedScope.() -> T): T = db.withTransaction {
        val scope = AuthorizedScope()
        if (expected != null) scope.expect(expected) else vaultId?.let { scope.authorize(it) }
        val result = scope.action()
        scope.verify()
        result
    }

    /** Keep a pending local login invisible to sync until its fields are committed too. */
    suspend fun <T> commitLocalCreate(vaultId: Long, create: suspend () -> T): T =
        authorizedTransaction(vaultId) { create() }

    /** A new remote ID does not exist yet, so freeze the exact encrypted local row list instead. */
    suspend fun prepareCreate(entry: PasswordEntry, request: CipherCreateRequest, key: SymmetricCryptoKey): CreatePlan = authorizedTransaction(entry.bitwardenVaultId) {
        check(entry.bitwardenCipherId.isNullOrEmpty() && entry.bitwardenVaultId != null && request.type == 1)
        requireUiWrite(entry)
        check(entries.getPasswordEntryById(entry.id) == entry) { "Password changed before create" }
        seed(entry.id)
        val pendingState = states.get(entry.id)
        requireUnboundState(entry, pendingState)
        val rows = history.getHistoryByEntryIdSync(entry.id).asReversed()
        val baseline = CipherApiResponse(type = 1, login = request.login, fields = request.fields)
        val prepared = BitwardenPasswordHistory.prepare(CipherUpdateRequest(type = 1, name = request.name,
            login = request.login, fields = request.fields), baseline, key,
            desired = rows.map { BitwardenPasswordHistory.DesiredRecord(it.id, null,
                security.decryptData(it.password), Instant.ofEpochMilli(it.lastUsedAt.time).toString()) }, initialized = true)
        CreatePlan(entry, authorize(requireNotNull(entry.bitwardenVaultId)), rows, pendingState?.localVersion ?: 0L, request.copy(passwordHistory = prepared.request.passwordHistory), prepared)
    }
    suspend fun isCurrent(plan: CreatePlan): Boolean = db.withTransaction {
        isSessionCurrent(plan.session) &&
            entries.getPasswordEntryById(plan.entry.id) == plan.entry && history.getHistoryByEntryIdSync(plan.entry.id).asReversed() == plan.rows &&
            (states.get(plan.entry.id)?.localVersion ?: 0L) == plan.version &&
            isSessionCurrent(plan.session)
    }
    /** Parent binds the response ID first in the same transaction; unchanged was checked before binding. */
    suspend fun acknowledgeCreate(plan: CreatePlan, response: CipherApiResponse, key: SymmetricCryptoKey, unchanged: Boolean) = authorizedTransaction(plan.entry.bitwardenVaultId, plan.session) {
        val entry = entries.getPasswordEntryById(plan.entry.id) ?: return@authorizedTransaction
        requireBinding(entry, response)
        val remote = BitwardenPasswordHistory.decode(response.passwordHistory, key)
        if (unchanged) {
            val preferred = Payload(initialized = true, rows = plan.prepared.records.map { Row(it.localId, it.wire) })
            replace(entry, null, remote, response.revisionDate, 0L, 0L, preferred, canEdit = canEdit(response))
        } else {
            val sent = plan.prepared.records.filter { it.localId != null && it.wire in remote.map { r -> r.wire } }.associateBy { it.localId }
            val desired = history.getHistoryByEntryIdSync(entry.id).asReversed().map { row -> Row(row.id, sent[row.id]?.wire) }
            // A clear/delete during POST is a deliberate complete desired list, including empty.
            val next = Payload(initialized = true, clearRemote = true, rows = desired, permissionsVerified = true, canEdit = canEdit(response))
            states.put(sealedState(entry, response.id, response.revisionDate, next, 1L, 0L))
        }
    }

    /** A created identity is retained even when its history echo is incomplete, avoiding duplicate POSTs. */
    suspend fun retainUnconfirmedCreate(plan: CreatePlan, response: CipherApiResponse) =
        authorizedTransaction(plan.entry.bitwardenVaultId, plan.session) {
            val entry = requireNotNull(entries.getPasswordEntryById(plan.entry.id))
            requireBinding(entry, response)
            val currentRows = history.getHistoryByEntryIdSync(entry.id).asReversed()
            val historyChangedWhilePosting = currentRows != plan.rows || (states.get(entry.id)?.localVersion ?: 0L) != plan.version
            val desired = currentRows.map { Row(it.id, null) }
            // A late clear/delete is a complete desired list even when the response omitted its
            // history echo. Merging it with the next GET would otherwise resurrect removed rows.
            val payload = Payload(initialized = historyChangedWhilePosting, clearRemote = historyChangedWhilePosting,
                rows = desired, permissionsVerified = true, canEdit = canEdit(response))
            states.put(sealedState(entry, response.id, response.revisionDate, payload, 1L, 0L))
        }

    /** Parent has just rebound an uncertain earlier POST in the same Room transaction. */
    suspend fun reconcileRebound(entryId: Long, source: CipherApiResponse, key: SymmetricCryptoKey,
        session: Session): Boolean = authorizedTransaction(expected = session) {
        val entry = requireNotNull(entries.getPasswordEntryById(entryId))
        requireBinding(entry, source)
        require(entry.bitwardenRevisionDate == source.revisionDate) { "Rebound history revision changed" }
        val pendingState = states.get(entryId)
        val pending = pendingState?.takeIf { it.cipherId == pendingId(entryId) }?.also {
            require(it.entryId == entryId && it.vaultId == entry.bitwardenVaultId && it.serverRevision.isEmpty()) {
                "Pending history ownership changed"
            }
        }?.let(::read)
        require(pendingState == null || pending != null) { "Rebound history was already bound" }
        val local = seed(entryId)
        val rows = history.getHistoryByEntryIdSync(entryId).associateBy { it.id }
        val prepared = BitwardenPasswordHistory.prepare(CipherUpdateRequest(type = 1, name = requireNotNull(source.name),
            login = source.login, fields = source.fields), source, key,
            desired = local.rows.map { row ->
                val stored = requireNotNull(rows[row.localId])
                BitwardenPasswordHistory.DesiredRecord(stored.id, null, security.decryptData(stored.password),
                    Instant.ofEpochMilli(stored.lastUsedAt.time).toString())
            }, initialized = pending?.initialized == true, clearRemote = pending?.clearRemote == true)
        val needsUpload = !BitwardenCipherDocument.confirmsPasswordHistory(prepared.request.passwordHistory, source.passwordHistory)
        replace(entry, null, BitwardenPasswordHistory.decode(prepared.request.passwordHistory, key),
            source.revisionDate, if (needsUpload) 1L else 0L, 0L,
            preferred = Payload(rows = prepared.records.map { Row(it.localId, it.wire) }), canEdit = canEdit(source))
        needsUpload
    }

    /** The password edit and its old-password history become visible to sync in one transaction. */
    suspend fun persistPasswordTransition(expected: PasswordEntry, updated: PasswordEntry, oldPlain: String, session: Session? = null): Boolean = authorizedTransaction(updated.bitwardenVaultId, session) {
        val unboundLogin = updated.bitwardenVaultId != null && updated.bitwardenCipherId.isNullOrEmpty() &&
            updated.bitwardenCipherType == 1 && !updated.loginType.equals("ssh_key", true)
        if (!isBoundLogin(updated) && !unboundLogin) return@authorizedTransaction false
        check(entries.getPasswordEntryById(expected.id) == expected) { "Password changed while editing" }
        check(updated.id == expected.id && updated.bitwardenCipherId == expected.bitwardenCipherId &&
            updated.bitwardenVaultId == expected.bitwardenVaultId) { "Password ownership changed" }
        check(oldPlain == security.decryptData(expected.password)) { "Password history snapshot changed" }
        val newPlain = security.decryptData(updated.password)
        requireUiWrite(updated)
        entries.update(updated.copy(bitwardenLocalModified = true))
        if (oldPlain.isNotEmpty() && oldPlain != newPlain) {
            if (unboundLogin) {
                history.insert(PasswordHistoryEntry(entryId = updated.id, password = protect(oldPlain), lastUsedAt = Date()))
                history.trimToLimit(updated.id, BitwardenPasswordHistory.MAX_ENTRIES)
            } else captureSnapshot(updated.id, oldPlain)
        }
        true
    }

    /** Persist actual hidden-field transitions with the field set, before requesting sync. */
    suspend fun persistHiddenFieldTransition(expected: PasswordEntry, drafts: List<CustomFieldDraft>,
        session: Session? = null): Boolean = authorizedTransaction(expected.bitwardenVaultId, session) {
        if (!isNativeLogin(expected)) return@authorizedTransaction false
        check(entries.getPasswordEntryById(expected.id) == expected) { "Field owner changed while editing" }
        requireUiWrite(expected)
        val old = db.customFieldDao().getFieldsByEntryIdSync(expected.id)
        val valid = drafts.filter { it.title.isNotBlank() && (!it.isPreset || it.value.isNotEmpty()) }
        val desired = valid.map { Triple(it.title, security.decryptDataIfMonicaCiphertext(it.value), it.isProtected) }
        val remaining = desired.toMutableList()
        val changed = old.mapNotNull { field ->
            val plain = security.decryptDataIfMonicaCiphertext(field.value)
            val exact = Triple(field.title, plain, field.isProtected)
            val index = remaining.indexOf(exact)
            if (index >= 0) { remaining.removeAt(index); null }
            else if (field.isProtected && field.title.isNotEmpty() && plain.isNotEmpty()) "${field.title}: $plain"
            else null
        }
        val entities = valid.zip(desired).mapIndexed { index, (draft, value) ->
            // Current fields retain the shared editor/search/rule-storage contract.
            // Only snapshots below and the history state use the MDK history envelope.
            draft.copy(value = value.second).toCustomField(expected.id, index)
        }
        db.customFieldDao().replaceFieldsForEntry(expected.id, entities)
        val updated = expected.copy(bitwardenLocalModified = true, updatedAt = Date())
        entries.update(updated)
        if (changed.isNotEmpty()) {
            if (!isBoundLogin(updated)) {
                changed.forEach { history.insert(PasswordHistoryEntry(entryId = updated.id, password = protect(it), lastUsedAt = Date())) }
                history.trimToLimit(updated.id, BitwardenPasswordHistory.MAX_ENTRIES)
            } else {
                val state = states.get(updated.id)
                val payload = state?.let(::read) ?: seed(updated.id)
                val captures = changed.map { value ->
                    Row(history.insert(PasswordHistoryEntry(entryId = updated.id, password = protect(value), lastUsedAt = Date())), null)
                }
                val next = payload.copy(rows = BitwardenPasswordHistory.retainRecent(payload.rows + captures) { it.wire },
                    capturedHiddenDigests = payload.capturedHiddenDigests + changed.map(::digest))
                removeUnusedRows(updated.id, next)
                dirty(updated, state, next)
            }
        }
        true
    }

    /** Import only after the owning cipher is accepted; never overwrites pending local changes. */
    suspend fun refresh(entryId: Long, source: CipherApiResponse, key: SymmetricCryptoKey, session: Session? = null): Boolean {
        return authorizedTransaction(expected = session) {
            val entry = entries.getPasswordEntryById(entryId) ?: return@authorizedTransaction false
            requireBinding(entry, source)
            val remote = BitwardenPasswordHistory.decode(source.passwordHistory, key)
            if (entry.bitwardenRevisionDate != source.revisionDate) return@authorizedTransaction false
            val state = states.get(entryId)
            if (entry.bitwardenLocalModified || state?.let { it.localVersion != it.ackVersion } == true) return@authorizedTransaction false
            if (state != null && state.serverRevision == source.revisionDate && read(state).initialized) return@authorizedTransaction true
            replace(entry, state, remote, source.revisionDate,
                version = state?.localVersion ?: 0L, ack = state?.ackVersion ?: 0L, canEdit = canEdit(source))
            true
        }
    }

    /** Called before mutation sync is requested by the existing native password editor. */
    suspend fun captureSnapshot(entryId: Long, plainPassword: String): Boolean = authorizedTransaction {
        val entry = entries.getPasswordEntryById(entryId) ?: return@authorizedTransaction false
        if (!isNativeLogin(entry)) return@authorizedTransaction false
        requireUiWrite(entry)
        if (plainPassword.isEmpty()) return@authorizedTransaction true
        if (!isBoundLogin(entry)) {
            seed(entryId)
            val latest = history.getHistoryByEntryIdSync(entryId).firstOrNull()
            if (latest == null || security.decryptData(latest.password) != plainPassword) {
                history.insert(PasswordHistoryEntry(entryId = entryId, password = protect(plainPassword), lastUsedAt = Date()))
                history.trimToLimit(entryId, BitwardenPasswordHistory.MAX_ENTRIES)
                entries.update(entry.copy(bitwardenLocalModified = true))
            }
            return@authorizedTransaction true
        }
        val state = states.get(entryId)
        val payload = state?.let(::read) ?: seed(entryId)
        val captureToken = digest(entry.password + "|" + entry.updatedAt.time)
        if (payload.lastCaptureToken == captureToken) {
            return@authorizedTransaction true
        }
        val row = PasswordHistoryEntry(entryId = entryId, password = protect(plainPassword), lastUsedAt = Date())
        val id = history.insert(row)
        val next = payload.copy(rows = BitwardenPasswordHistory.retainRecent(payload.rows + Row(id, null)) { it.wire },
            lastCaptureToken = captureToken,
            capturedPasswordDigests = payload.capturedPasswordDigests + digest(plainPassword))
        removeUnusedRows(entryId, next)
        dirty(entry, state, next)
        true
    }

    /** Returns false for ordinary local rows, allowing the caller to retain its existing behavior. */
    suspend fun deleteHistory(historyId: Long, session: Session? = null): Boolean = authorizedTransaction(expected = session) {
        val row = history.getById(historyId) ?: return@authorizedTransaction true
        val entry = entries.getPasswordEntryById(row.entryId) ?: return@authorizedTransaction true
        if (!isNativeLogin(entry)) return@authorizedTransaction false
        requireUiWrite(entry)
        if (!isBoundLogin(entry)) {
            requireUnboundState(entry, states.get(entry.id))
            history.deleteById(historyId)
            retainUnboundIntent(entry, clearAll = false)
            return@authorizedTransaction true
        }
        val state = states.get(entry.id)
        val payload = state?.let(::read) ?: seed(entry.id)
        // The local row ID identifies exactly one occurrence, even when wire records are identical.
        require(payload.rows.count { it.localId == historyId } == 1) { "History source mapping unavailable" }
        history.deleteById(historyId)
        dirty(entry, state, payload.copy(rows = payload.rows.filterNot { it.localId == historyId }))
        true
    }

    suspend fun clear(entryId: Long, session: Session? = null): Boolean = authorizedTransaction(expected = session) {
        val entry = entries.getPasswordEntryById(entryId) ?: return@authorizedTransaction true
        if (!isNativeLogin(entry)) return@authorizedTransaction false
        requireUiWrite(entry)
        if (!isBoundLogin(entry)) {
            requireUnboundState(entry, states.get(entry.id))
            history.deleteByEntryId(entryId)
            retainUnboundIntent(entry, clearAll = true)
            return@authorizedTransaction true
        }
        val state = states.get(entryId)
        val payload = state?.let(::read) ?: seed(entryId)
        history.deleteByEntryId(entryId)
        dirty(entry, state, payload.copy(clearRemote = true, rows = emptyList()))
        true
    }

    suspend fun prepare(entry: PasswordEntry, source: CipherApiResponse, request: CipherUpdateRequest,
        key: SymmetricCryptoKey, now: Instant = Instant.now()): UploadPlan = authorizedTransaction(entry.bitwardenVaultId) {
        requireBinding(entry, source)
        require(entries.getPasswordEntryById(entry.id) == entry && entry.bitwardenRevisionDate == source.revisionDate) { "History source revision changed" }
        require(canEdit(source)) { "Password history is read-only" }
        val state = states.get(entry.id)
        require(state == null || state.entryId == entry.id && state.vaultId == entry.bitwardenVaultId &&
            state.cipherId == entry.bitwardenCipherId && state.serverRevision == entry.bitwardenRevisionDate) { "History baseline changed" }
        val payload = state?.let(::read)
        val changed = state != null && state.localVersion != state.ackVersion
        val local = if (changed) payload!!.rows.map { row ->
            val stored = row.localId?.let { requireNotNull(history.getById(it)) { "History row changed" } }
            require(stored == null || stored.entryId == entry.id) { "History row owner changed" }
            BitwardenPasswordHistory.DesiredRecord(row.localId, row.wire,
                stored?.let { security.decryptData(it.password) }, stored?.let { Instant.ofEpochMilli(it.lastUsedAt.time).toString() })
        } else null
        UploadPlan(entry, authorize(requireNotNull(entry.bitwardenVaultId)), state?.localVersion ?: 0L, BitwardenPasswordHistory.prepare(request, source, key,
            desired = local, initialized = payload?.initialized == true, clearRemote = payload?.clearRemote == true,
            suppressOriginalPassword = source.login?.password?.let {
                digest(takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.decryptToString(it, key)) in payload?.capturedPasswordDigests.orEmpty()
            } == true,
            suppressHiddenChange = { digest(it) in payload?.capturedHiddenDigests.orEmpty() }, now = now))
    }

    suspend fun isCurrent(plan: UploadPlan): Boolean = db.withTransaction {
        isSessionCurrent(plan.session) &&
            entries.getPasswordEntryById(plan.entry.id) == plan.entry && (states.get(plan.entry.id)?.localVersion ?: 0L) == plan.version &&
            isSessionCurrent(plan.session)
    }

    /** Invoke before clearing the parent entry's dirty flag, in the same successful-upload transaction. */
    suspend fun acknowledge(plan: UploadPlan, response: CipherApiResponse, key: SymmetricCryptoKey) = authorizedTransaction(plan.entry.bitwardenVaultId, plan.session) {
        val entry = entries.getPasswordEntryById(plan.entry.id) ?: return@authorizedTransaction
        requireBinding(entry, response)
        val state = states.get(entry.id)
        require(state == null || state.ackVersion <= plan.version) { "History acknowledgment is obsolete" }
        val currentVersion = state?.localVersion ?: 0L
        val decoded = BitwardenPasswordHistory.decode(response.passwordHistory, key)
        if (currentVersion == plan.version) {
            val mapping = Payload(initialized = true,
                rows = plan.prepared.records.map { Row(it.localId, it.wire) })
            replace(entry, state,
                decoded, response.revisionDate, plan.version, plan.version,
                preferred = mapping, canEdit = canEdit(response))
        } else {
            // New local clear/delete/password transitions keep their desired list. Bind only IDs
            // that still exist to sent ciphertext, so an acknowledgement cannot resurrect a row.
            val payload = requireNotNull(state).let(::read)
            val returnedWires = decoded.map { it.wire }.toSet()
            val sentById = plan.prepared.records.filter { it.localId != null && it.wire in returnedWires }.associateBy { it.localId }
            val next = payload.copy(initialized = true, rows = payload.rows.map { row ->
                if (row.localId == null) row else sentById[row.localId]?.let { Row(row.localId, it.wire) } ?: row
            })
            states.put(sealedState(entry, state.cipherId, response.revisionDate, next, state.localVersion, plan.version))
        }
    }

    private suspend fun replace(entry: PasswordEntry, state: BitwardenPasswordHistoryState?,
        remote: List<BitwardenPasswordHistory.Record>, revision: String, version: Long, ack: Long,
        preferred: Payload? = null, canEdit: Boolean) {
        val old = preferred ?: state?.let(::read) ?: Payload()
        val available = old.rows.toMutableList()
        val retained = mutableSetOf<Long>()
        val rows = remote.map { record ->
            val oldIndex = available.indexOfFirst { it.wire == record.wire && it.localId != null && it.localId !in retained }
            val candidate = if (oldIndex >= 0) available.removeAt(oldIndex).localId?.let { history.getById(it) } else null
            val id = if (record.password != null && record.password.isNotEmpty() && record.lastUsedDate != null) {
                val at = Date.from(Instant.parse(record.lastUsedDate))
                if (candidate != null && candidate.entryId == entry.id) {
                    check(security.decryptData(candidate.password) == record.password) { "History mapping changed" }
                    candidate.id
                } else history.insert(PasswordHistoryEntry(entryId = entry.id, password = protect(record.password), lastUsedAt = at))
            } else null
            if (id != null) retained += id
            Row(id, record.wire)
        }
        val next = Payload(initialized = true, rows = rows, permissionsVerified = true, canEdit = canEdit)
        removeUnusedRows(entry.id, next)
        states.put(sealedState(entry, requireNotNull(entry.bitwardenCipherId), revision, next, version, ack))
    }

    private fun pendingId(entryId: Long) = "local:$entryId"
    private fun requireUnboundState(entry: PasswordEntry, state: BitwardenPasswordHistoryState?) {
        require(isNativeLogin(entry) && entry.bitwardenCipherId.isNullOrEmpty() && entry.bitwardenRevisionDate.isNullOrEmpty()) {
            "Pending history requires an unbound local item"
        }
        require(state == null || state.entryId == entry.id && state.vaultId == entry.bitwardenVaultId &&
            state.cipherId == pendingId(entry.id) && state.serverRevision.isEmpty()) { "Pending history ownership changed" }
    }
    private suspend fun retainUnboundIntent(entry: PasswordEntry, clearAll: Boolean) {
        val old = states.get(entry.id)
        requireUnboundState(entry, old)
        val payload = Payload(initialized = true, clearRemote = clearAll || old?.let(::read)?.clearRemote == true,
            rows = history.getHistoryByEntryIdSync(entry.id).asReversed().map { Row(it.id, null) },
            permissionsVerified = true, canEdit = true)
        states.put(sealedState(entry, pendingId(entry.id), "", payload, Math.addExact(old?.localVersion ?: 0L, 1L), 0L))
        entries.update(entry.copy(bitwardenLocalModified = true))
    }

    private suspend fun seed(entryId: Long) = Payload(rows = history.getHistoryByEntryIdSync(entryId).asReversed().map { row ->
        // Existing Bitwarden projections may have used the old local-history wrapper. Upgrade
        // only after successful authenticated decryption, and retain the exact occurrence ID/date.
        if (!row.password.startsWith("MDK|")) history.updatePasswordById(row.id, protect(security.decryptData(row.password)))
        Row(row.id, null)
    })
    private suspend fun removeUnusedRows(entryId: Long, payload: Payload) {
        val retained = payload.rows.mapNotNull { it.localId }.toSet()
        history.getHistoryByEntryIdSync(entryId).filterNot { it.id in retained }.forEach { history.deleteById(it.id) }
    }
    private suspend fun dirty(entry: PasswordEntry, old: BitwardenPasswordHistoryState?, payload: Payload) {
        val version = Math.addExact(old?.localVersion ?: 0L, 1L)
        states.put(sealedState(entry, requireNotNull(entry.bitwardenCipherId),
            old?.serverRevision ?: requireNotNull(entry.bitwardenRevisionDate), payload, version, old?.ackVersion ?: 0L))
        // The history generation changes even when Date/password are unchanged. Retaining the
        // edit timestamp keeps the encrypted-password transition token stable across clear/delete.
        entries.update(entry.copy(bitwardenLocalModified = true))
    }
    private fun sealedState(entry: PasswordEntry, cipherId: String, revision: String, payload: Payload,
        version: Long, ack: Long): BitwardenPasswordHistoryState {
        val vaultId = requireNotNull(entry.bitwardenVaultId)
        val binding = Binding(entry.id, vaultId, cipherId, revision, version, ack)
        return BitwardenPasswordHistoryState(entry.id, vaultId, cipherId, revision,
            protect(json.encodeToString(payload.copy(binding = binding))), version, ack)
    }
    private fun read(state: BitwardenPasswordHistoryState): Payload {
        require(state.payloadMdk.startsWith("MDK|")) { "History source requires MDK protection" }
        val payload = json.decodeFromString(Payload.serializer(), security.decryptData(state.payloadMdk))
        require(payload.binding == Binding(state.entryId, state.vaultId, state.cipherId, state.serverRevision,
            state.localVersion, state.ackVersion)) { "History sealed binding changed" }
        return payload
    }
    private fun protect(plain: String): String {
        check(SessionManager.isUnlocked.value) { "Vault authentication required" }
        val encoded = security.encryptData(plain)
        check(encoded.startsWith("MDK|") && security.decryptData(encoded) == plain) { "Password history requires MDK encryption" }
        return encoded
    }
    private fun canEdit(source: CipherApiResponse) = source.edit != false &&
        (source.organizationId.isNullOrEmpty() || source.edit == true)
    private fun isNativeLogin(entry: PasswordEntry) = entry.bitwardenVaultId != null &&
        entry.bitwardenCipherType == 1 && !entry.loginType.equals("ssh_key", true)
    private fun isBoundLogin(entry: PasswordEntry) = entry.bitwardenVaultId != null &&
        !entry.bitwardenCipherId.isNullOrEmpty() && entry.bitwardenCipherType == 1
    private suspend fun AuthorizedScope.requireUiWrite(entry: PasswordEntry) {
        authorize(requireNotNull(entry.bitwardenVaultId))
        check(SessionManager.isUnlocked.value && !entry.isDeleted &&
            vaultUnlocked(requireNotNull(entry.bitwardenVaultId))) { "Bitwarden vault is locked" }
        if (isBoundLogin(entry)) {
            var state = states.get(entry.id)
            if (state == null) {
                val session = authorize(requireNotNull(entry.bitwardenVaultId))
                val vault = requireNotNull(db.bitwardenVaultDao().getVaultById(session.vaultId)) { "History account missing" }
                val baseline = requireNotNull(BitwardenCipherSourceStore(context).read(vault,
                    requireNotNull(entry.bitwardenCipherId), requireNotNull(entry.bitwardenRevisionDate),
                    { isSessionCurrent(session) })) { "History editing requires a complete source; sync the account first" }
                val key = requireNotNull(BitwardenRepository.getInstance(context).getCachedSymmetricKey(session.vaultId))
                try {
                    BitwardenCipherKeyResolver.withCipherKey(baseline, key, "BitwardenPasswordHistory") { effective ->
                        requireBinding(entry, baseline)
                        val remote = BitwardenPasswordHistory.decode(baseline.passwordHistory, effective)
                        val local = seed(entry.id)
                        // Existing pending local snapshots are explicitly merged on the next upload.
                        if (entry.bitwardenLocalModified || local.rows.isNotEmpty()) {
                            val next = local.copy(permissionsVerified = true, canEdit = canEdit(baseline))
                            dirty(entry, null, next)
                        } else replace(entry, null, remote, baseline.revisionDate, 0L, 0L, canEdit = canEdit(baseline))
                    }
                } finally { key.clear() }
                state = states.get(entry.id)
            }
            require(state != null && state.vaultId == entry.bitwardenVaultId && state.cipherId == entry.bitwardenCipherId &&
                state.serverRevision == entry.bitwardenRevisionDate) { "History source binding changed" }
            val payload = read(state)
            check(payload.permissionsVerified && payload.canEdit) { "History editing requires an editable source" }
        } else requireUnboundState(entry, states.get(entry.id))
    }
    private fun AuthorizedScope.requireBinding(entry: PasswordEntry, source: CipherApiResponse) {
        require(isBoundLogin(entry) && entry.bitwardenCipherId == source.id && source.type == 1) { "History source changed" }
        authorize(requireNotNull(entry.bitwardenVaultId))
        // Match the nullable API contract used by cipher projection and ownership checks:
        // an explicit denial hides passwords; an omitted legacy flag is not a denial.
        require(source.viewPassword != false) { "Password history is hidden" }
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
