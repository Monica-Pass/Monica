package takagi.ru.monica.attachments.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.service.BitwardenCipherOwnership
import takagi.ru.monica.credentialexchange.BitwardenImportAttachmentQueue
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SessionManager

/** Routes edits from the saved owner, never from a not-yet-saved storage picker selection. */
class PasswordAttachmentEdits(context: Context) {
    private val context = context.applicationContext
    private val database = PasswordDatabase.getDatabase(context)
    private val facade = AttachmentContainer.facade(context)
    private val queue = BitwardenImportAttachmentQueue(context)
    private val bitwarden = BitwardenRepository.getInstance(context)

    suspend fun persist(passwordId: Long, uri: Uri, isPlusActivated: Boolean, acceptSoftLimit: Boolean) = withContext(Dispatchers.IO) {
        val entry = requireNotNull(database.passwordEntryDao().getPasswordEntryById(passwordId))
        check(!entry.isDeleted)
        val vaultId = entry.bitwardenVaultId
        if (vaultId != null) {
            queue.stagePasswordAttachment(vaultId, passwordId, uri, isPlusActivated)
            // The durable intent is already acknowledged; scheduler failure must not invite
            // re-selecting the same file and creating another local attachment.
            runCatching { bitwarden.requestLocalMutationSync(vaultId) }
        } else {
            val keepassContext = entry.keepassDatabaseId?.let { databaseId ->
                AttachmentFacade.KeePassContext(databaseId, requireNotNull(entry.keepassEntryUuid))
            }
            facade.addAttachment(AttachmentFacade.UploadRequest(
                owner = AttachmentOwner.password(passwordId),
                source = if (keepassContext != null) AttachmentSource.KEEPASS else AttachmentSource.LOCAL,
                uri = uri, isPlusActivated = isPlusActivated, keepassContext = keepassContext,
                kdbxSoftLimitAccepted = acceptSoftLimit
            ))
        }
        Unit
    }

    suspend fun remove(passwordId: Long, attachmentId: Long) = withContext(Dispatchers.IO) {
        queue.withAttachmentMutation {
            val entry = requireNotNull(database.passwordEntryDao().getPasswordEntryById(passwordId))
            check(!entry.isDeleted)
            val attachment = facade.getById(attachmentId) ?: return@withAttachmentMutation
            check(attachment.owner == AttachmentOwner.password(passwordId))
            val keepassContext = entry.keepassDatabaseId?.let { databaseId ->
                AttachmentFacade.KeePassContext(databaseId, requireNotNull(entry.keepassEntryUuid))
            }
            if (attachment.sourceEnum != AttachmentSource.BITWARDEN) {
                entry.bitwardenVaultId?.let { vaultId ->
                    if (!SessionManager.isUnlocked.value || !bitwarden.isVaultUnlocked(vaultId)) throw AttachmentError.BitwardenLocked
                }
                facade.deleteAttachment(attachmentId, keepassContext = keepassContext)
                return@withAttachmentMutation
            }
            val vaultId = requireNotNull(entry.bitwardenVaultId)
            val generation = bitwarden.getVaultSessionGeneration(vaultId)
            val lockGeneration = SessionManager.lockGeneration.value
            suspend fun requireCurrent() {
                if (!SessionManager.isUnlocked.value || SessionManager.lockGeneration.value != lockGeneration ||
                    !bitwarden.isVaultUnlocked(vaultId) || bitwarden.getVaultSessionGeneration(vaultId) != generation) {
                    throw AttachmentError.BitwardenLocked
                }
                val current = database.passwordEntryDao().getPasswordEntryById(passwordId)
                check(current != null && current.bitwardenVaultId == vaultId && current.bitwardenCipherId == entry.bitwardenCipherId && !current.isDeleted)
                check(facade.getById(attachmentId) == attachment)
            }
            requireCurrent()
            val vault = requireNotNull(database.bitwardenVaultDao().getVaultById(vaultId))
            val remoteContext = bitwarden.getAttachmentBitwardenContext(vault, entry.bitwardenCipherId)
                ?: throw AttachmentError.BitwardenLocked
            try {
                if (!remoteContext.isOnline) throw AttachmentError.Offline
                val response = remoteContext.vaultApi.getCipher("Bearer ${remoteContext.accessToken}", remoteContext.cipherId)
                if (!response.isSuccessful) throw AttachmentError.NetworkError(response.code())
                BitwardenCipherOwnership.requireWritable(requireNotNull(response.body()), remoteContext.cipherId)
                requireCurrent()
                facade.deleteAttachment(attachmentId, bitwardenContext = remoteContext)
            } finally { remoteContext.wrappingKey.clear() }
        }
    }
}
