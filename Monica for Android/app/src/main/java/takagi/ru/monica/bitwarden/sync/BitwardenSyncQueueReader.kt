package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenSyncIssueReport
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSyncQueueRecord
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager

internal data class BitwardenSyncQueueSnapshot(
    val vaultId: Long,
    val identity: String = "",
    val vaultGeneration: Long = -1,
    val lockGeneration: Long = -1,
    val locked: Boolean = true,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val items: List<BitwardenSyncQueueRecord> = emptyList(),
)

internal data class BitwardenSyncQueueSummary(val pending: Int = 0, val failed: Int = 0)

internal fun BitwardenVault.queueIdentity(): String = accountKey + "\u0000" +
    BitwardenVaultIdentity.buildAccountKey(serverUrl, userId, BitwardenVaultIdentity.resolveCanonicalEmail(this))

/** Pure revocation check also used at rendering/click time, after an asynchronous Room emission. */
internal fun BitwardenSyncQueueSnapshot.isCurrent(
    identity: String?, vaultGeneration: Long, lockGeneration: Long, unlocked: Boolean,
): Boolean = !locked && unlocked && this.identity == identity &&
    this.vaultGeneration == vaultGeneration && this.lockGeneration == lockGeneration

internal fun summarizeBitwardenQueue(
    snapshots: Collection<BitwardenSyncQueueSnapshot>,
    reports: Map<Long, BitwardenSyncIssueReport>,
): BitwardenSyncQueueSummary {
    var pending = 0
    var failed = 0
    snapshots.filterNot { it.locked }.forEach { snapshot ->
        pending += snapshot.items.count { it.status != "FAILED" }
        val failures = snapshot.items.filter { it.status == "FAILED" }
            .mapTo(mutableSetOf()) { it.kind to it.itemId }
        reports[snapshot.vaultId]?.items?.forEach { failures += it.kind.name to it.itemId }
        failed += failures.size
    }
    return BitwardenSyncQueueSummary(pending, failed)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class BitwardenSyncQueueReader(
    private val database: PasswordDatabase,
    private val repository: BitwardenRepository,
    context: android.content.Context,
) {
    private val attachmentQueue = takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue(context.applicationContext)

    private fun attachmentRows(vaultId: Long): Flow<List<BitwardenSyncQueueRecord>> =
        attachmentQueue.observePendingIds(vaultId).distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.sorted().chunked(400).map { batch -> database.attachmentDao().observeBitwardenPendingMetadata(vaultId, batch) }) { batches -> batches.flatMap { it } }
        }

    fun observeAll(): Flow<Map<Long, BitwardenSyncQueueSnapshot>> = repository.getAllVaultsFlow()
        .map { vaults -> vaults.map { it.id }.sorted() }.distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map(::observe)) { snapshots -> snapshots.associateBy { it.vaultId } }
        }

    fun isCurrent(snapshot: BitwardenSyncQueueSnapshot, vault: BitwardenVault?): Boolean = snapshot.isCurrent(
        identity = vault?.queueIdentity(),
        vaultGeneration = repository.getVaultSessionGeneration(snapshot.vaultId),
        lockGeneration = SessionManager.lockGeneration.value,
        unlocked = SessionManager.isUnlocked.value && vault?.isLocked == false &&
            repository.isVaultUnlocked(snapshot.vaultId),
    )

    private fun observe(vaultId: Long): Flow<BitwardenSyncQueueSnapshot> = combine(
        database.bitwardenVaultDao().getVaultByIdFlow(vaultId),
        repository.getVaultSessionGenerationFlow(vaultId),
        SessionManager.isUnlocked,
        SessionManager.lockGeneration,
    ) { vault, generation, unlocked, lockGeneration ->
        BitwardenSyncQueueSnapshot(vaultId, vault?.queueIdentity().orEmpty(), generation, lockGeneration,
            locked = !unlocked || vault == null || vault.isLocked || !repository.isVaultUnlocked(vaultId))
    }.distinctUntilChanged().flatMapLatest { access ->
        if (access.locked) flowOf(access)
        else combine(database.bitwardenPendingOperationDao().observeQueueRecords(vaultId), attachmentRows(vaultId)) { ordinary, attachments ->
                (ordinary + attachments).distinctBy { it.queueKey }
            }.map { rows ->
                val sends = if (rows.any { it.kind == "SEND" }) repository.getSends(vaultId).associateBy { it.bitwardenSendId } else emptyMap()
                // Re-read identity after Room finishes; an old account or lock epoch cannot publish names.
                val vault = database.bitwardenVaultDao().getVaultById(vaultId)
                if (isCurrent(access, vault)) access.copy(items = rows.map { row ->
                    row.copy(title = (if (row.kind == "SEND") sends[row.itemId]?.name else row.title)?.filterNot(Char::isISOControl)?.take(200),
                        itemId = row.itemId.filterNot(Char::isISOControl).take(256))
                }) else BitwardenSyncQueueSnapshot(vaultId)
            }
            .onStart { emit(access.copy(loading = true)) }
            .catch { emit(access.copy(failed = true)) }
    }
}
