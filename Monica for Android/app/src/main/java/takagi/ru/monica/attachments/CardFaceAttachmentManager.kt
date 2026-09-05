package takagi.ru.monica.attachments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.model.CardFaceAttachment
import takagi.ru.monica.data.model.CardFaceConfig
import takagi.ru.monica.repository.SecureItemRepository
import java.util.Date

/**
 * Stores the encrypted image referenced by a secure item's [CardFaceConfig].
 *
 * The metadata stays in itemData while this class routes the image through the existing attachment
 * backend (local/MDBX mirror, KeePass binary, or Bitwarden attachment).
 */
class CardFaceAttachmentManager(
    private val repository: SecureItemRepository,
    private val attachmentFacade: AttachmentFacade?,
    private val bitwardenRepository: BitwardenRepository?
) {
    fun requireUploadAllowed(bitwardenVaultId: Long?) {
        if (bitwardenVaultId != null && bitwardenRepository?.isVaultPremium(bitwardenVaultId) != true) {
            throw AttachmentError.PremiumRequired
        }
    }

    suspend fun update(
        secureItemId: Long,
        config: CardFaceConfig?,
        imageBytes: ByteArray?,
        previousAttachmentName: String?,
        sourceItemId: Long? = null,
        ownerBackendChanged: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var copiedBytes: ByteArray? = null
        try {
        runCatching {
            val facade = requireNotNull(attachmentFacade) { "Attachment storage is unavailable" }
            val item = requireNotNull(repository.getItemById(secureItemId)) { "Secure item no longer exists" }
            val owner = AttachmentOwner.secureItem(secureItemId)
            val newName = config?.imageAttachmentName
            if (imageBytes == null && newName != null && sourceItemId != null && sourceItemId != secureItemId &&
                facade.list(owner).none { it.fileName == newName }
            ) {
                copiedBytes = readImage(sourceItemId, newName)
            }
            val bytesToSave = imageBytes ?: copiedBytes
            if (bytesToSave == null && (config != null || previousAttachmentName.isNullOrBlank())) {
                return@runCatching Unit
            }

            if (bytesToSave != null && newName != null) requireUploadAllowed(item.bitwardenVaultId)
            val keepassContext = item.keepassDatabaseId?.let { databaseId ->
                item.keepassEntryUuid
                    ?.takeIf(String::isNotBlank)
                    ?.let { entryUuid -> AttachmentFacade.KeePassContext(databaseId, entryUuid) }
            }
            val bitwardenVault = item.bitwardenVaultId?.let { vaultId ->
                bitwardenRepository?.getAllVaultsFlow()?.first()?.firstOrNull { it.id == vaultId }
            }
            val bitwardenContext = bitwardenVault?.let { vault ->
                item.bitwardenCipherId
                    ?.takeIf(String::isNotBlank)
                    ?.let { cipherId ->
                        bitwardenRepository?.fetchAttachmentCipherSnapshot(vault, cipherId)?.context
                    }
                    ?: bitwardenRepository?.getAttachmentBitwardenContext(vault, item.bitwardenCipherId)
            }

            if (bytesToSave != null && newName != null) {
                require(CardFaceAttachment.isManagedFileName(newName)) {
                    "Invalid managed card-face name"
                }
                val source = when {
                    bitwardenContext?.isOnline == true -> AttachmentSource.BITWARDEN
                    keepassContext != null -> AttachmentSource.KEEPASS
                    else -> AttachmentSource.LOCAL
                }
                val saved = facade.addInlineAttachment(
                    AttachmentFacade.InlineUploadRequest(
                        owner = owner,
                        source = source,
                        fileName = newName,
                        mimeType = CardFaceAttachment.MIME_TYPE,
                        bytes = bytesToSave,
                        isPlusActivated = true,
                        bitwardenPremium = bitwardenVault?.let { vault ->
                            bitwardenRepository?.isVaultPremium(vault.id) == true
                        } ?: true,
                        bitwardenContext = bitwardenContext,
                        keepassContext = keepassContext,
                        kdbxSoftLimitAccepted = true
                    )
                )

                // When Bitwarden is offline, keep the old remote attachment until the pending
                // local image has been promoted successfully by the next sync.
                val cleanupDeferredToBitwardenSync = bitwardenVault != null &&
                    source == AttachmentSource.LOCAL && !ownerBackendChanged
                if (!cleanupDeferredToBitwardenSync) {
                    facade.list(owner)
                        .filter { attachment ->
                            attachment.id != saved.id &&
                                (attachment.fileName == newName ||
                                    (!previousAttachmentName.isNullOrBlank() &&
                                        attachment.fileName == previousAttachmentName))
                        }
                        .forEach { old ->
                            // KeePass replaces a binary by name. Deleting the old row remotely
                            // after a retry would also delete the image we have just saved.
                            if (ownerBackendChanged ||
                                (old.sourceEnum == AttachmentSource.KEEPASS &&
                                    saved.sourceEnum == AttachmentSource.KEEPASS && old.fileName == newName)
                            ) {
                                facade.forgetLocalAttachment(old.id)
                            } else {
                                facade.deleteAttachment(
                                    attachmentId = old.id,
                                    bitwardenContext = bitwardenContext,
                                    keepassContext = keepassContext
                                )
                            }
                        }
                }

                if (item.bitwardenVaultId != null && source == AttachmentSource.LOCAL) {
                    repository.updateItem(
                        item.copy(
                            bitwardenLocalModified = true,
                            syncStatus = "PENDING",
                            updatedAt = Date()
                        )
                    )
                }
                item.bitwardenVaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
            } else if (config == null && !previousAttachmentName.isNullOrBlank()) {
                if (bitwardenVault == null || ownerBackendChanged) {
                    facade.list(owner)
                        .filter { it.fileName == previousAttachmentName }
                        .forEach { old ->
                            if (ownerBackendChanged) {
                                facade.forgetLocalAttachment(old.id)
                            } else {
                                facade.deleteAttachment(
                                    attachmentId = old.id,
                                    bitwardenContext = bitwardenContext,
                                    keepassContext = keepassContext
                                )
                            }
                        }
                } else {
                    repository.updateItem(
                        item.copy(
                            bitwardenLocalModified = true,
                            syncStatus = "PENDING",
                            updatedAt = Date()
                        )
                    )
                }
                item.bitwardenVaultId?.let { bitwardenRepository?.requestLocalMutationSync(it) }
            }
            Unit
        }.onFailure { if (it is CancellationException) throw it }
        } finally {
            copiedBytes?.fill(0)
        }
    }

    /** Read before moving an owner, while its source backend identity is still valid. */
    suspend fun readImage(secureItemId: Long, fileName: String): ByteArray = withContext(Dispatchers.IO) {
        val facade = requireNotNull(attachmentFacade)
        val item = requireNotNull(repository.getItemById(secureItemId))
        val attachment = facade.list(AttachmentOwner.secureItem(secureItemId))
            .firstOrNull { it.fileName == fileName }
            ?: error("Card face attachment is unavailable")
        val keepass = item.keepassDatabaseId?.let { databaseId ->
            item.keepassEntryUuid?.takeIf(String::isNotBlank)?.let {
                AttachmentFacade.KeePassContext(databaseId, it)
            }
        }
        val vault = item.bitwardenVaultId?.let { id ->
            bitwardenRepository?.getAllVaultsFlow()?.first()?.firstOrNull { it.id == id }
        }
        val bitwarden = vault?.let {
            item.bitwardenCipherId?.takeIf(String::isNotBlank)?.let { cipherId ->
                bitwardenRepository?.fetchAttachmentCipherSnapshot(vault, cipherId)?.context
            } ?: bitwardenRepository?.getAttachmentBitwardenContext(vault, item.bitwardenCipherId)
        }
        facade.readAttachmentBytes(attachment.id, 25 * 1024 * 1024, bitwarden, keepass)
    }
}
