package takagi.ru.monica.bitwarden.service

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSend
import takagi.ru.monica.data.bitwarden.BitwardenSendIdentity
import takagi.ru.monica.data.bitwarden.BitwardenSendDao
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Raw Room rows contain only a sealed envelope plus selectors/reconciliation metadata. */
@OptIn(ExperimentalCoroutinesApi::class)
class BitwardenSendCache(context: Context) : BitwardenSendDao {
    private val database = PasswordDatabase.getDatabase(context)
    private val raw = database.bitwardenSendDao()
    private val security = SecurityManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = false }
    private val repository get() = BitwardenRepository.getInstance(context)
    private val context = context.applicationContext

    @Serializable private data class Envelope(val version: Int = 1, val account: BitwardenSendAccountIdentity,
        val vaultId: Long, val sendId: String, val send: BitwardenSend)
    private data class Session(val vault: BitwardenVault, val generation: Long, val lockGeneration: Long)

    private suspend fun session(vaultId: Long): Session {
        val vault = database.bitwardenVaultDao().getVaultById(vaultId) ?: throw SessionExpiredException()
        return Session(vault, repository.getVaultSessionGeneration(vaultId), SessionManager.lockGeneration.value)
            .also { current(it) }
    }

    private suspend fun current(session: Session) {
        if (!SessionManager.isUnlocked.value || SessionManager.lockGeneration.value != session.lockGeneration ||
            !repository.isVaultUnlocked(session.vault.id) || repository.getVaultSessionGeneration(session.vault.id) != session.generation)
            throw SessionExpiredException()
        val actual = database.bitwardenVaultDao().getVaultById(session.vault.id)
        if (actual == null || actual.isLocked || BitwardenSendAccountIdentity.from(actual) != BitwardenSendAccountIdentity.from(session.vault))
            throw SessionExpiredException()
    }

    private fun seal(send: BitwardenSend, session: Session): BitwardenSend {
        require(send.vaultId == session.vault.id && send.bitwardenSendId.isNotBlank())
        require(BitwardenSendScope.shareMatches(send, session.vault.serverUrl, bitwardenSendUrlPrefix(session.vault)))
        val content = send.copy(id = 0, encryptedCache = null, cacheVersion = 0)
        val encoded = json.encodeToString(Envelope(account = BitwardenSendAccountIdentity.from(session.vault),
            vaultId = send.vaultId, sendId = send.bitwardenSendId, send = content))
        val encrypted = security.encryptData(encoded)
        check(encrypted.startsWith("MDK|") && security.decryptData(encrypted) == encoded)
        return send.copy(name = "", notes = "", textContent = null, fileName = null, keyBase64 = null,
            shareUrl = "", accessId = "", recipientEmails = null, encryptedCache = encrypted, cacheVersion = 1)
    }

    private fun unseal(row: BitwardenSend, session: Session): BitwardenSend {
        check(row.cacheVersion == 1)
        val sealed = requireNotNull(row.encryptedCache)
        check(sealed.startsWith("MDK|"))
        val envelope = json.decodeFromString<Envelope>(security.decryptData(sealed))
        check(envelope.version == 1 && envelope.account == BitwardenSendAccountIdentity.from(session.vault) && envelope.vaultId == row.vaultId &&
            envelope.sendId == row.bitwardenSendId && envelope.send.vaultId == row.vaultId &&
            envelope.send.bitwardenSendId == row.bitwardenSendId &&
            BitwardenSendScope.shareMatches(envelope.send, session.vault.serverUrl, bitwardenSendUrlPrefix(session.vault)))
        return envelope.send.copy(id = row.id, createdAt = row.createdAt, updatedAt = row.updatedAt,
            lastSyncedAt = row.lastSyncedAt, isDirty = row.isDirty, encryptedCache = null, cacheVersion = 0)
    }

    private fun recognizedLegacy(row: BitwardenSend, session: Session): Boolean = runCatching {
        row.cacheVersion == 0 && row.encryptedCache == null && row.bitwardenSendId.isNotBlank() &&
            row.name.isNotBlank() && BitwardenSendScope.shareMatches(row, session.vault.serverUrl, bitwardenSendUrlPrefix(session.vault)) &&
            (row.isTextType && row.textContent != null || row.isFileType && !row.fileName.isNullOrBlank())
    }.getOrDefault(false)

    private class SessionExpiredException : IllegalStateException("Send session is no longer current")

    private fun verifyExisting(row: BitwardenSend, session: Session) {
        if (row.cacheVersion == 0) check(recognizedLegacy(row, session)) else unseal(row, session)
    }

    private suspend fun readList(rows: List<BitwardenSend>, access: Session): List<BitwardenSend> {
        var unavailable = 0
        val result = rows.mapNotNull { row ->
            try { read(row, access) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { current(access); unavailable++; null }
        }
        current(access)
        _unavailable.update { it + (access.vault.id to unavailable) }
        return result
    }

    private suspend fun read(row: BitwardenSend, expectedSession: Session? = null): BitwardenSend {
        val session = expectedSession ?: session(row.vaultId)
        current(session)
        val result = if (row.cacheVersion == 0) {
            check(recognizedLegacy(row, session)) { "Unrecognized legacy Send cache" }
            val replacement = seal(row, session)
            check(unseal(replacement, session).copy(id = row.id) == row)
            database.withTransaction {
                current(session)
                check(raw.getBySendId(row.vaultId, row.bitwardenSendId) == row) { "Send cache changed during migration" }
                raw.update(replacement)
                current(session)
            }
            row
        } else unseal(row, session)
        current(session)
        return result
    }

    /** Counts and write bookkeeping never load legacy plaintext columns. */
    suspend fun count(vaultId: Long): Int = raw.countByVault(vaultId)
    suspend fun identity(vaultId: Long, sendId: String): BitwardenSendIdentity? = raw.getIdentity(vaultId, sendId)
    override suspend fun countByVault(vaultId: Long): Int = raw.countByVault(vaultId)
    override suspend fun getIdentity(vaultId: Long, sendId: String): BitwardenSendIdentity? = raw.getIdentity(vaultId, sendId)

    override suspend fun getSendsByVault(vaultId: Long): List<BitwardenSend> = withContext(Dispatchers.IO) {
        if (!SessionManager.isUnlocked.value || !repository.isVaultUnlocked(vaultId)) return@withContext emptyList()
        try {
            val session = session(vaultId)
            readList(raw.getSendsByVault(vaultId), session).also { current(session) }
        } catch (_: SessionExpiredException) { emptyList() }
    }
    override suspend fun getSendsByVaults(vaultIds: List<Long>): List<BitwardenSend> = withContext(Dispatchers.IO) {
        try {
            val sessions = vaultIds.distinct().filter { SessionManager.isUnlocked.value && repository.isVaultUnlocked(it) }.associateWith { session(it) }
            sessions.flatMap { (id, access) -> readList(raw.getSendsByVault(id), access) }
                .sortedByDescending { it.updatedAt }.also { sessions.values.forEach { current(it) } }
        } catch (_: SessionExpiredException) { emptyList() }
    }
    override suspend fun getBySendId(vaultId: Long, sendId: String): BitwardenSend? = withContext(Dispatchers.IO) {
        if (!SessionManager.isUnlocked.value || !repository.isVaultUnlocked(vaultId)) return@withContext null
        try { raw.getBySendId(vaultId, sendId)?.let { read(it) } }
        catch (_: SessionExpiredException) { null }
    }
    override fun getSendsByVaultFlow(vaultId: Long): Flow<List<BitwardenSend>> = combine(
        database.bitwardenVaultDao().getVaultByIdFlow(vaultId), SessionManager.isUnlocked,
        SessionManager.lockGeneration, repository.getVaultSessionGenerationFlow(vaultId)
    ) { vault, unlocked, lock, generation ->
        if (vault != null && unlocked && !vault.isLocked && repository.isVaultUnlocked(vaultId))
            Session(vault, generation, lock) else null
    }.distinctUntilChanged().flatMapLatest { access ->
        if (access == null) flowOf(emptyList())
        else raw.getSendsByVaultFlow(vaultId).map { rows ->
            try { readList(rows, access).also { current(access) } }
            catch (_: SessionExpiredException) { emptyList() }
        }
    }
    override fun getSendsByVaultsFlow(vaultIds: List<Long>): Flow<List<BitwardenSend>> =
        if (vaultIds.isEmpty()) flowOf(emptyList()) else combine(vaultIds.distinct().map { getSendsByVaultFlow(it) }) {
            it.flatMap { rows -> rows }.sortedByDescending { row -> row.updatedAt }
        }

    override suspend fun upsert(send: BitwardenSend): Long = withContext(Dispatchers.IO) {
        val session = session(send.vaultId)
        val stored = seal(send, session)
        database.withTransaction {
            current(session)
            raw.getBySendId(send.vaultId, send.bitwardenSendId)?.let { verifyExisting(it, session) }
            raw.upsert(stored).also { current(session) }
        }
    }

    /** Prepared outside the account gate; only a current remote sync may replace an older configuration's cache. */
    internal suspend fun upsertCaptured(send: BitwardenSend, expectedVault: BitwardenVault,
        requireCurrent: () -> Unit, withPublication: suspend (suspend () -> Unit) -> Unit): Long = withContext(Dispatchers.IO) {
        requireCurrent()
        val access = session(send.vaultId)
        requireCapturedOwner(access.vault, expectedVault)
        val previous = raw.getBySendId(send.vaultId, send.bitwardenSendId)
        previous?.let { verifyRemoteReplacement(it, access) }
        val replacement = seal(send.copy(id = previous?.id ?: 0L), access)
        current(access)
        requireCurrent()
        var result = 0L
        withPublication {
            database.withTransaction {
                current(access)
                requireCurrent()
                check(raw.getBySendId(send.vaultId, send.bitwardenSendId) == previous) { "Send changed before publication" }
                result = raw.upsert(replacement)
                current(access)
                requireCurrent()
            }
        }
        result
    }

    internal suspend fun deleteNotInCaptured(vault: BitwardenVault, keepIds: List<String>, syncStartedAtMs: Long,
        requireCurrent: () -> Unit, withPublication: suspend (suspend () -> Unit) -> Unit) = withContext(Dispatchers.IO) {
        requireCurrent()
        val access = session(vault.id)
        requireCapturedOwner(access.vault, vault)
        val candidates = raw.getSendsByVault(vault.id).filter {
            it.bitwardenSendId !in keepIds && !it.isDirty && it.createdAt < syncStartedAtMs
        }.filter { row ->
            try { verifyRemoteReplacement(row, access); true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { current(access); requireCurrent(); false }
        }
        current(access)
        requireCurrent()
        withPublication {
            database.withTransaction {
                current(access)
                requireCurrent()
                candidates.forEach { row ->
                    // A local edit or a newer sync always wins over the captured deletion candidates.
                    if (raw.getBySendId(vault.id, row.bitwardenSendId) == row) raw.delete(row)
                }
                current(access)
                requireCurrent()
            }
        }
    }

    private fun requireCapturedOwner(actual: BitwardenVault, expected: BitwardenVault) {
        require(actual.id == expected.id && actual.canonicalEmail == expected.canonicalEmail &&
            (expected.userId == null || actual.userId == expected.userId))
        val current = BitwardenSendAccountIdentity.from(actual)
        // Profile sync can promote a previously unknown user ID without changing the captured transport lease.
        require(current.copy(accountKey = expected.accountKey, userId = expected.userId) ==
            BitwardenSendAccountIdentity.from(expected))
    }

    private fun verifyRemoteReplacement(row: BitwardenSend, access: Session) {
        if (row.cacheVersion == 0) { verifyExisting(row, access); return }
        check(row.cacheVersion == 1 && row.encryptedCache?.startsWith("MDK|") == true)
        val envelope = json.decodeFromString<Envelope>(security.decryptData(requireNotNull(row.encryptedCache)))
        val old = envelope.account
        val latest = BitwardenSendAccountIdentity.from(access.vault)
        check(envelope.version == 1 && envelope.vaultId == row.vaultId && envelope.sendId == row.bitwardenSendId &&
            envelope.send.vaultId == row.vaultId && envelope.send.bitwardenSendId == row.bitwardenSendId &&
            takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.validateEndpoint(old.serverUrl,
                takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.BASE).value ==
                takagi.ru.monica.bitwarden.api.BitwardenEnvironmentResolver.validateEndpoint(latest.serverUrl,
                    takagi.ru.monica.bitwarden.api.BitwardenEnvironmentField.BASE).value &&
            old.canonicalEmail == latest.canonicalEmail &&
            (old.userId == null || old.userId == latest.userId))
        val oldVault = BitwardenVault(id = row.vaultId, email = old.canonicalEmail, serverUrl = old.serverUrl,
            apiUrl = old.apiUrl, identityUrl = old.identityUrl, environmentPreset = old.environmentPreset,
            webVaultUrl = old.webVaultUrl, notificationsUrl = old.notificationsUrl)
        check(BitwardenSendScope.shareMatches(envelope.send, old.serverUrl, bitwardenSendUrlPrefix(oldVault)))
    }
    override suspend fun upsertAll(sends: List<BitwardenSend>) {
        // Prepare each encrypted replacement before one atomic batch write.
        val prepared = sends.map { value -> val session = session(value.vaultId); Triple(value, session, seal(value, session)) }
        database.withTransaction {
            prepared.forEach { current(it.second) }
            prepared.forEach { (send, access, _) ->
                raw.getBySendId(send.vaultId, send.bitwardenSendId)?.let { verifyExisting(it, access) }
            }
            raw.upsertAll(prepared.map { it.third })
            prepared.forEach { current(it.second) }
        }
    }
    override suspend fun update(send: BitwardenSend) { upsert(send) }
    override suspend fun delete(send: BitwardenSend) = raw.deleteBySendId(send.vaultId, send.bitwardenSendId)
    override suspend fun deleteByVault(vaultId: Long) = raw.deleteByVault(vaultId)
    override suspend fun deleteBySendId(vaultId: Long, sendId: String) = raw.deleteBySendId(vaultId, sendId)
    override suspend fun deleteNotInProtectingDirty(vaultId: Long, keepIds: List<String>, syncStartedAtMs: Long) {
        val access = session(vaultId)
        database.withTransaction {
            current(access)
            val protected = raw.getSendsByVault(vaultId).filter { row ->
                try { read(row, access); false }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { current(access); true }
            }.map { it.bitwardenSendId }
            raw.deleteNotInProtectingDirty(vaultId, (keepIds + protected).distinct(), syncStartedAtMs)
            current(access)
        }
    }
    override suspend fun deleteByVaultProtectingDirty(vaultId: Long, syncStartedAtMs: Long) =
        deleteNotInProtectingDirty(vaultId, emptyList(), syncStartedAtMs)
    /** Safe cache clearing cannot establish that locked/unreadable rows are disposable. */
    suspend fun clearSyncedCache(vaultId: Long, startedAt: Long) {
        try { deleteByVaultProtectingDirty(vaultId, startedAt) }
        catch (_: SessionExpiredException) { /* Preserve all rows when the account is revoked. */ }
    }
    override suspend fun clearDirty(vaultId: Long, sendId: String) = raw.clearDirty(vaultId, sendId)
    override suspend fun getDirtySendIds(vaultId: Long): List<String> = raw.getDirtySendIds(vaultId)

    companion object {
        private val _unavailable = MutableStateFlow<Map<Long, Int>>(emptyMap())
        val unavailable: StateFlow<Map<Long, Int>> = _unavailable.asStateFlow()
    }
}
