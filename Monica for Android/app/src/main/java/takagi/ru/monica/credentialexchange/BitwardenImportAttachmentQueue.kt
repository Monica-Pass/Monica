package takagi.ru.monica.credentialexchange

import kotlinx.coroutines.channels.awaitClose
import android.content.Context
import android.net.Uri
import takagi.ru.monica.bitwarden.service.BitwardenSyncIssues
import takagi.ru.monica.bitwarden.service.SyncIssueKind
import takagi.ru.monica.bitwarden.service.SyncIssuePhase
import takagi.ru.monica.bitwarden.service.SyncIssueReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import takagi.ru.monica.attachments.AttachmentContainer
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
    suspend fun <T> withAttachmentMutation(action: suspend () -> T): T = mutex.withLock { action() }

    /** New editor files are durable before the UI acknowledges saving them, even without a cipher. */
    suspend fun stagePasswordAttachment(vaultId: Long, passwordId: Long, uri: Uri, isPlusActivated: Boolean) = mutex.withLock {
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
        try {
            requireCurrent()
            val key = "vault:$vaultId"
            check(preferences.edit().putStringSet(key,
                preferences.getStringSet(key, emptySet()).orEmpty() + attachment.id.toString()).commit())
        } catch (error: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                facade.forgetLocalAttachment(attachment.id)
            }
            throw error
        }
        attachment
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
        apiManager: BitwardenApiManager, issues: BitwardenSyncIssues.Session? = null): Int = mutex.withLock {
        val key = "vault:${vault.id}"
        val pending = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (pending.isEmpty()) return@withLock 0
        val api = apiManager.getVaultApi(vault)
        val repository = BitwardenRepository.getInstance(context)
        val generation = repository.getVaultSessionGeneration(vault.id)
        val lockGeneration = SessionManager.lockGeneration.value
        var failures = 0
        for (id in pending.toList()) {
            val attachment = id.toLongOrNull()?.let { facade.getById(it) }
            val owner = attachment?.owner
            if (attachment == null || owner == null || attachment.isDeleted || attachment.sourceEnum != AttachmentSource.LOCAL) {
                pending.remove(id)
                continue
            }
            val password = owner.passwordId?.let { database.passwordEntryDao().getPasswordEntryById(it) }
            val item = owner.secureItemId?.let { database.secureItemDao().getItemById(it) }
            if ((password?.bitwardenVaultId != vault.id && item?.bitwardenVaultId != vault.id) ||
                password?.isDeleted == true || item?.isDeleted == true) {
                pending.remove(id)
                continue
            }
            val cipherId = password?.bitwardenCipherId ?: item?.bitwardenCipherId
            if (cipherId.isNullOrBlank() || !BitwardenVaultPremiumStore.isPremium(context, vault.id)) {
                failures++
                issues?.record("${cipherId ?: "local:${owner.passwordId ?: owner.secureItemId}"}/attachment:$id",
                    SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE, SyncIssueReason.PENDING_PARENT, attachment.fileName)
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
                pending.remove(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failures++
                issues?.record("$cipherId/attachment:$id", SyncIssueKind.ATTACHMENT, SyncIssuePhase.CREATE,
                    SyncIssueReason.ATTACHMENT, attachment.fileName)
            }
            // Persist acknowledgements as we go; retries never discard the encrypted local source.
            check(preferences.edit().putStringSet(key, pending).commit())
        }
        check(preferences.edit().putStringSet(key, pending).commit())
        failures
    }

    companion object { private val mutex = Mutex() }
}
