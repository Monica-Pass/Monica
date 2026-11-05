package takagi.ru.monica.credentialexchange

import kotlinx.coroutines.channels.awaitClose
import android.content.Context
import android.net.Uri
import takagi.ru.monica.bitwarden.service.BitwardenSyncIssues
import takagi.ru.monica.bitwarden.service.SyncIssueKind
import takagi.ru.monica.bitwarden.service.SyncIssuePhase
import takagi.ru.monica.bitwarden.service.SyncIssueReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.executor.BitwardenAttachmentMutationGate
import takagi.ru.monica.attachments.executor.BitwardenAttachmentRecoveryStore
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.bitwarden.BitwardenVaultPremiumStore
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.security.SessionManager

/** Explicit import/editor intents only. Payloads remain encrypted in the attachment store. */
class BitwardenImportAttachmentQueue(private val context: Context) {
    private val preferences = context.getSharedPreferences("credential_import_attachments_v1", Context.MODE_PRIVATE)
    private val database = PasswordDatabase.getDatabase(context)
    private val facade = AttachmentContainer.facade(context)
    private val recovery by lazy { BitwardenAttachmentRecoveryStore(context) }

    /** Observe the exact durable intent IDs; does not read attachment payloads or claim upload success. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observePendingIds(vaultId: Long): kotlinx.coroutines.flow.Flow<Set<Long>> = kotlinx.coroutines.flow.callbackFlow {
        val key = "vault:$vaultId"
        fun publish() {
            trySend(preferences.getStringSet(key, emptySet()).orEmpty().mapNotNull {
                it.toLongOrNull()?.takeIf { id -> id > 0 && id.toString() == it }
            }.toSet())
        }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) publish()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        publish()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Serializes editor removal with promotion, so a removed local row cannot be resurrected. */
    suspend fun <T> withAttachmentMutation(action: suspend () -> T): T =
        BitwardenAttachmentMutationGate.withLock(context, action)

    /** New editor files are durable before the UI acknowledges saving them, even without a cipher. */
    suspend fun stagePasswordAttachment(vaultId: Long, passwordId: Long, uri: Uri, isPlusActivated: Boolean) =
        BitwardenAttachmentMutationGate.withLock(context) {
        val repository = BitwardenRepository.getInstance(context)
        val generation = repository.getVaultSessionGeneration(vaultId)
        val lockGeneration = SessionManager.lockGeneration.value
        suspend fun requireCurrent() {
            if (!SessionManager.isUnlocked.value || SessionManager.lockGeneration.value != lockGeneration ||
                !repository.isVaultUnlocked(vaultId) || repository.getVaultSessionGeneration(vaultId) != generation) {
                throw AttachmentError.BitwardenLocked
            }
            val entry = database.passwordEntryDao().getPasswordEntryById(passwordId)
                ?: throw AttachmentError.InvalidRemoteData
            check(!entry.isDeleted && entry.bitwardenVaultId == vaultId && entry.keepassDatabaseId == null && entry.mdbxDatabaseId == null)
            if (!BitwardenVaultPremiumStore.isPremium(context, vaultId)) throw AttachmentError.PremiumRequired
        }
        requireCurrent()
        val attachment = facade.addAttachment(AttachmentFacade.UploadRequest(
            owner = AttachmentOwner.password(passwordId), source = AttachmentSource.LOCAL,
            uri = uri, isPlusActivated = isPlusActivated
        ))
        // The user already requested this LOCAL write. Locking now must preserve its encrypted
        // source and intent; no credentials/network/publication are enabled by an ID-only queue.
        remember(vaultId, setOf(attachment.id.toString()))
        requireCurrent()
        attachment
    }

    suspend fun enqueueAttachment(vaultId: Long, attachmentId: Long) {
        val attachment = facade.getById(attachmentId) ?: throw AttachmentError.InvalidRemoteData
        check(attachment.sourceEnum == AttachmentSource.LOCAL && !attachment.isDeleted)
        val owner = requireNotNull(attachment.owner)
        val password = owner.passwordId?.let { database.passwordEntryDao().getPasswordEntryById(it) }
        val item = owner.secureItemId?.let { database.secureItemDao().getItemById(it) }
        check(password?.bitwardenVaultId == vaultId || item?.bitwardenVaultId == vaultId)
        remember(vaultId, setOf(attachmentId.toString()))
    }

    private suspend fun remember(vaultId: Long, ids: Set<String>) = mutex.withLock {
        val key = "vault:$vaultId"
        check(preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() + ids).commit())
    }

    private suspend fun acknowledge(vaultId: Long, id: String) = mutex.withLock {
        val key = "vault:$vaultId"
        check(preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() - id).commit())
    }

    suspend fun enqueue(vaultId: Long, owners: List<AttachmentOwner>) = mutex.withLock {
        val ids = owners.flatMap { facade.list(it) }.filter { it.sourceEnum == AttachmentSource.LOCAL }.map { it.id.toString() }
        if (ids.isNotEmpty()) {
            val key = "vault:$vaultId"
            check(preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() + ids).commit())
        }
    }

    /** Called by normal vault sync after parent ciphers have been created. Failed blobs stay queued. */
    suspend fun flush(vault: BitwardenVault, accessToken: String, vaultKey: SymmetricCryptoKey,
        apiManager: BitwardenApiManager, issues: BitwardenSyncIssues.Session? = null): Int = withContext(Dispatchers.IO) {
        val key = "vault:${vault.id}"
        val pending = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val recoveryRecords = recovery.listIntentsForVaultId(vault.id).filter {
            it.phase != BitwardenAttachmentRecoveryStore.Phase.REMOVED && it.phase != BitwardenAttachmentRecoveryStore.Phase.ADOPTED
        }
        val durableIds = recoveryRecords.mapNotNull { it.localAttachmentId?.toString() }.toSet()
        if (durableIds.isNotEmpty()) { remember(vault.id, durableIds); pending += durableIds }
        if (pending.isEmpty()) return@withContext 0
        val api = apiManager.getVaultApi(vault)
        val repository = BitwardenRepository.getInstance(context)
        val generation = repository.getVaultSessionGeneration(vault.id)
        val lockGeneration = SessionManager.lockGeneration.value
        var failures = 0
        for (id in pending.toList()) {
            val attachment = id.toLongOrNull()?.let { facade.getById(it) }
            val owner = attachment?.owner
            val pendingRecovery = recoveryRecords.filter { it.localAttachmentId?.toString() == id }
            if (pendingRecovery.any { !BitwardenAttachmentRecoveryStore.matchesVault(it.binding, vault) }) {
                failures++
                issues?.record("local:attachment:$id", SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE,
                    SyncIssueReason.ATTACHMENT, null)
                continue
            }
            if (attachment == null || owner == null || attachment.isDeleted) {
                if (pendingRecovery.isNotEmpty()) {
                    failures++
                    issues?.record("${pendingRecovery.first().binding.cipherId}/attachment:$id", SyncIssueKind.ATTACHMENT,
                        SyncIssuePhase.CREATE, SyncIssueReason.ATTACHMENT, null)
                } else acknowledge(vault.id, id)
                continue
            }
            val password = owner.passwordId?.let { database.passwordEntryDao().getPasswordEntryById(it) }
            val item = owner.secureItemId?.let { database.secureItemDao().getItemById(it) }
            if ((password?.bitwardenVaultId != vault.id && item?.bitwardenVaultId != vault.id) ||
                password?.isDeleted == true || item?.isDeleted == true) {
                if (pendingRecovery.isEmpty()) acknowledge(vault.id, id)
                else {
                    failures++
                    issues?.record("${pendingRecovery.first().binding.cipherId}/attachment:$id", SyncIssueKind.ATTACHMENT,
                        SyncIssuePhase.CREATE, SyncIssueReason.ATTACHMENT, null)
                }
                continue
            }
            val cipherId = password?.bitwardenCipherId ?: item?.bitwardenCipherId
            if (attachment.sourceEnum != AttachmentSource.LOCAL) {
                if (attachment.sourceEnum == AttachmentSource.BITWARDEN && pendingRecovery.isNotEmpty() && !cipherId.isNullOrBlank()) {
                    try {
                        val currentContext = repository.getAttachmentBitwardenContext(vault, cipherId) ?: throw AttachmentError.BitwardenLocked
                        try { facade.acknowledgePublishedAttachmentRecovery(attachment.id, currentContext) }
                        finally { currentContext.wrappingKey.clear() }
                        acknowledge(vault.id, id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        failures++
                        issues?.record("$cipherId/attachment:$id", SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE,
                            SyncIssueReason.ATTACHMENT, attachment.fileName)
                    }
                } else if (pendingRecovery.isEmpty()) acknowledge(vault.id, id)
                continue
            }
            if (cipherId.isNullOrBlank() || !BitwardenVaultPremiumStore.isPremium(context, vault.id)) {
                failures++
                issues?.record("${cipherId ?: "local:${owner.passwordId ?: owner.secureItemId}"}/attachment:$id",
                    SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE,
                    if (cipherId.isNullOrBlank()) SyncIssueReason.PENDING_PARENT else SyncIssueReason.REJECTED, attachment.fileName)
                continue
            }
            try {
                suspend fun requireCurrent() {
                    if (!SessionManager.isUnlocked.value || SessionManager.lockGeneration.value != lockGeneration ||
                        !repository.isVaultUnlocked(vault.id) || repository.getVaultSessionGeneration(vault.id) != generation) {
                        throw AttachmentError.BitwardenLocked
                    }
                    val currentVault = database.bitwardenVaultDao().getVaultById(vault.id)
                        ?: throw AttachmentError.BitwardenLocked
                    check(!currentVault.isLocked && currentVault.accountKey == vault.accountKey &&
                        currentVault.serverUrl == vault.serverUrl && currentVault.apiUrl == vault.apiUrl)
                    val currentPassword = owner.passwordId?.let { database.passwordEntryDao().getPasswordEntryById(it) }
                    val currentItem = owner.secureItemId?.let { database.secureItemDao().getItemById(it) }
                    check(if (owner.passwordId != null) currentPassword != null && currentPassword.bitwardenVaultId == vault.id &&
                        currentPassword.bitwardenCipherId == cipherId && !currentPassword.isDeleted
                    else currentItem != null && currentItem.bitwardenVaultId == vault.id && currentItem.bitwardenCipherId == cipherId && !currentItem.isDeleted)
                    if (!BitwardenVaultPremiumStore.isPremium(context, vault.id)) throw AttachmentError.PremiumRequired
                    check(facade.getById(attachment.id) == attachment)
                }
                requireCurrent()
                val currentContext = repository.getAttachmentBitwardenContext(vault, cipherId)
                    ?: throw AttachmentError.BitwardenLocked
                try {
                    val response = api.getCipher("Bearer ${currentContext.accessToken}", cipherId)
                    check(response.isSuccessful)
                    val cipher = checkNotNull(response.body())
                    takagi.ru.monica.bitwarden.service.BitwardenCipherOwnership.requireWritable(cipher, cipherId)
                    facade.promoteLocalAttachmentsToBitwarden(owner, currentContext.copy(
                        wrappingKeyProvider = {
                            requireCurrent()
                            requireNotNull(currentContext.wrappingKeyProvider).invoke().also {
                                try { requireCurrent() } catch (error: Throwable) { it.clear(); throw error }
                            }
                        }), setOf(attachment.fileName), setOf(attachment.id), beforePublish = { requireCurrent() })
                } finally { currentContext.wrappingKey.clear() }
                acknowledge(vault.id, id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failures++
                issues?.record("$cipherId/attachment:$id", SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE,
                    SyncIssueReason.ATTACHMENT, attachment.fileName)
            }
            // Each acknowledgement merges with concurrent newly staged IDs; never replace a stale set.
        }
        failures
    }

    companion object { private val mutex = Mutex() }
}
