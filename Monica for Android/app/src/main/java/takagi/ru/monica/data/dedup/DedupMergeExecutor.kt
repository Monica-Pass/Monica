package takagi.ru.monica.data.dedup

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.LegacyImageAttachmentSupport
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.utils.StringResolver
import kotlin.coroutines.coroutineContext

internal interface DedupMergeWriter {
    suspend fun writePassword(resolved: DedupResolvedPassword)
    suspend fun writeSecureItem(resolved: DedupResolvedSecureItem)
    suspend fun writePasskey(resolved: DedupResolvedPasskey)

    /** True only after commit; false means the block was not run. A failure must roll it all back. */
    suspend fun writeLocalBatch(block: suspend () -> Unit): Boolean = false
}

internal class RepositoryDedupMergeWriter(
    private val passwordRepository: PasswordRepository,
    private val secureItemRepository: SecureItemRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val passkeyRepository: PasskeyRepository,
    private val database: PasswordDatabase,
    private val attachmentSupport: DedupAttachmentSupport? = null
) : DedupMergeWriter {
    override suspend fun writeLocalBatch(block: suspend () -> Unit): Boolean {
        database.withTransaction { block() }
        return true
    }

    override suspend fun writePassword(resolved: DedupResolvedPassword) {
        require(resolved.entry.passkeyBindings.isEmpty() && !resolved.entry.loginType.trim().equals("PASSKEY", true) &&
            resolved.customFields.none { it.title.contains("passkey", true) || it.title.contains("fido", true) }) {
            "Passkey projects are excluded from deduplication"
        }
        var insertedId: Long? = null
        try {
            val newId = passwordRepository.insertDedupCopy(resolved.entry)
            insertedId = newId
            val fields = resolved.customFields.map { field ->
                field.copy(id = 0, entryId = newId)
            }
            if (fields.isNotEmpty()) {
                customFieldRepository.insertFields(fields)
                if (resolved.entry.mdbxDatabaseId != null) {
                    // Insertion mirrors the entry before its new field IDs exist. Flush the
                    // complete entry so reopening the MDBX file retains those fields too.
                    val persisted = checkNotNull(passwordRepository.getPasswordEntryById(newId))
                    passwordRepository.updatePasswordEntry(persisted)
                }
            }
            attachmentSupport?.copy(resolved.attachments, AttachmentOwner.password(newId))
            val saved = checkNotNull(passwordRepository.getPasswordEntryById(newId))
            // MDBX creates a new physical object identity; all content columns must still agree.
            check(saved.copy(id = 0, replicaGroupId = if (saved.mdbxDatabaseId != null) resolved.entry.replicaGroupId else saved.replicaGroupId) ==
                resolved.entry.copy(id = 0)) { "Destination content verification failed" }
            val savedFields = customFieldRepository.getFieldsByEntryIdSync(newId)
                .sortedWith(compareBy<takagi.ru.monica.data.CustomField> { it.sortOrder }.thenBy { it.id })
                .map { it.copy(id = 0, entryId = 0) }
            check(savedFields == resolved.customFields.map { it.copy(id = 0, entryId = 0) }) { "Destination fields verification failed" }
        } catch (throwable: Exception) {
            val rollbackFailure = insertedId?.let { id ->
                runCatching {
                    withContext(NonCancellable) {
                        if (resolved.attachments.isNotEmpty()) {
                            attachmentSupport?.rollback(AttachmentOwner.password(id))
                        }
                        passwordRepository.deletePasswordEntryById(id)
                    }
                }.exceptionOrNull()
            }
            rollbackFailure?.let(throwable::addSuppressed)
            throw throwable
        }
    }

    override suspend fun writeSecureItem(resolved: DedupResolvedSecureItem) {
        var insertedId: Long? = null
        var images: LegacyImageAttachmentSupport.Copy? = null
        try {
            images = attachmentSupport?.images?.copy(resolved.item.imagePaths)
            val item = images?.let { resolved.item.copy(imagePaths = it.imagePaths) } ?: resolved.item
            val id = secureItemRepository.insertDedupCopy(item)
            insertedId = id
            attachmentSupport?.copy(resolved.attachments, AttachmentOwner.secureItem(id))
            if (item.mdbxDatabaseId != null) attachmentSupport?.persistImages(item.copy(id = id))
        } catch (error: Exception) {
            val failure = runCatching {
                withContext(NonCancellable) {
                    insertedId?.let { id ->
                        if (resolved.attachments.isNotEmpty() || resolved.item.mdbxDatabaseId != null) {
                            attachmentSupport?.rollback(AttachmentOwner.secureItem(id))
                        }
                        secureItemRepository.deleteItemById(id)
                    }
                    images?.let { attachmentSupport?.images?.rollback(it) }
                }
            }.exceptionOrNull()
            failure?.let(error::addSuppressed)
            throw error
        }
    }

    override suspend fun writePasskey(resolved: DedupResolvedPasskey) {
        error("Passkeys are excluded from deduplication")
    }
}

internal class DedupMergeExecutor(
    private val writer: DedupMergeWriter,
    private val strings: StringResolver
) {
    suspend fun execute(
        passwords: List<DedupResolvedPassword>,
        secureItems: List<DedupResolvedSecureItem>,
        skippedExistingPasswords: Int,
        skippedExistingSecureItems: Int,
        skippedUnsupportedPasskeys: Int,
        targetLabel: String,
        passkeys: List<DedupResolvedPasskey> = emptyList(),
        skippedExistingPasskeys: Int = 0,
        onProgress: (DedupMergeExecutionProgress) -> Unit = {},
        isCurrent: () -> Boolean = { true }
    ): DedupMergeExecutionResult {
        // Reject legacy or forged executable key plans before any password/item write.
        require(passkeys.isEmpty()) { "Passkeys are excluded from deduplication" }
        val totalItems = passwords.size + secureItems.size + passkeys.size
        var completedItems = 0
        val failures = mutableListOf<DedupMergeFailure>()
        fun requireCurrent() = check(isCurrent()) { strings.get(R.string.dedup_merge_changed_review) }

        var projectPasswords = 0
        for (members in passwords.filter { it.projectCopyKey != null }.groupBy { it.projectCopyKey }.values) {
            coroutineContext.ensureActive()
            try {
                require(members.all { it.entry.isLocalOnlyEntry() && it.attachments.isEmpty() })
                check(writer.writeLocalBatch {
                    requireCurrent()
                    members.forEach { coroutineContext.ensureActive(); writer.writePassword(it) }
                    requireCurrent()
                }) { "Atomic project copy is unavailable" }
                projectPasswords += members.size
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Never split a failed project into independent row retries.
                members.forEach { failures += DedupMergeFailure(DedupMergeItemKind.PASSWORD, it.entry.title, failureReason(error)) }
            }
            completedItems += members.size
            onProgress(DedupMergeExecutionProgress(completedItems, totalItems, members.first().entry.title))
        }

        suspend fun <T> writeItems(
            items: List<T>,
            kind: DedupMergeItemKind,
            canBatch: (T) -> Boolean,
            labelOf: (T) -> String,
            write: suspend (T) -> Unit
        ): Int {
            var inserted = 0
            var index = 0
            while (index < items.size) {
                coroutineContext.ensureActive()
                requireCurrent()
                var end = index + 1
                val local = canBatch(items[index])
                if (local) {
                    while (end < items.size && end - index < LOCAL_BATCH_SIZE && canBatch(items[end])) end++
                }
                val batch = items.subList(index, end)
                // Only Room-only writes may be retried after an atomic rollback. Files and
                // private-key storage have their own per-item compensation paths.
                val committed = if (local) {
                    try {
                        writer.writeLocalBatch {
                            for (item in batch) {
                                coroutineContext.ensureActive()
                                requireCurrent()
                                write(item)
                            }
                            coroutineContext.ensureActive()
                            requireCurrent()
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        false
                    }
                } else false
                if (committed) {
                    inserted += batch.size
                    completedItems += batch.size
                    onProgress(DedupMergeExecutionProgress(completedItems, totalItems, labelOf(batch.last())))
                } else {
                    // A failed transaction has committed nothing. Retry individually so
                    // one invalid entry does not prevent the rest from being merged.
                    for (item in batch) {
                        coroutineContext.ensureActive()
                        val label = labelOf(item)
                        try {
                            requireCurrent()
                            write(item)
                            inserted++
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failures += DedupMergeFailure(kind, label, failureReason(error))
                        }
                        completedItems++
                        onProgress(DedupMergeExecutionProgress(completedItems, totalItems, label))
                    }
                }
                index = end
            }
            return inserted
        }

        val insertedPasswords = projectPasswords + writeItems(passwords.filter { it.projectCopyKey == null }, DedupMergeItemKind.PASSWORD,
            canBatch = { it.entry.id == 0L && it.entry.isLocalOnlyEntry() && it.attachments.isEmpty() },
            labelOf = { it.entry.title.ifBlank { it.entry.username.ifBlank { strings.get(R.string.dedup_merge_untitled_password) } } },
            write = writer::writePassword)
        val insertedSecureItems = writeItems(secureItems, DedupMergeItemKind.SECURE_ITEM,
            canBatch = { it.item.id == 0L && it.item.isLocalOnlyItem() && it.attachments.isEmpty() &&
                runCatching { LegacyImageAttachmentSupport.paths(it.item.imagePaths).all(String::isBlank) }.getOrDefault(false) },
            labelOf = { it.item.title.ifBlank { it.item.itemType.dedupLabel(strings) } },
            write = writer::writeSecureItem)
        return DedupMergeExecutionResult(
            insertedPasswords = insertedPasswords,
            insertedSecureItems = insertedSecureItems,
            insertedPasskeys = 0,
            skippedExistingPasswords = skippedExistingPasswords,
            skippedExistingSecureItems = skippedExistingSecureItems,
            skippedExistingPasskeys = skippedExistingPasskeys,
            skippedUnsupportedPasskeys = skippedUnsupportedPasskeys,
            failedPasswords = failures.count { it.kind == DedupMergeItemKind.PASSWORD },
            failedSecureItems = failures.count { it.kind == DedupMergeItemKind.SECURE_ITEM },
            failedPasskeys = failures.count { it.kind == DedupMergeItemKind.PASSKEY },
            targetLabel = targetLabel,
            failures = failures
        )
    }

    private companion object {
        const val LOCAL_BATCH_SIZE = 100
    }

    private fun failureReason(throwable: Throwable): String {
        val primary = readableReason(throwable)
        val rollback = throwable.suppressed.firstOrNull() ?: return primary
        val rollbackText = readableReason(rollback)
        return strings.get(R.string.dedup_merge_rollback_failed, primary, rollbackText)
    }

    private fun readableReason(error: Throwable): String = when (error) {
        AttachmentError.CryptoError -> strings.get(R.string.attachment_error_crypto)
        AttachmentError.IoError -> strings.get(R.string.attachment_error_io)
        AttachmentError.Offline -> strings.get(R.string.attachment_error_offline)
        AttachmentError.BitwardenLocked -> strings.get(R.string.attachment_error_bitwarden_locked)
        AttachmentError.KdbxLocked -> strings.get(R.string.attachment_error_kdbx_locked)
        AttachmentError.InvalidRemoteData -> strings.get(R.string.attachment_error_remote_data)
        AttachmentError.KdbxCapacityExceeded -> strings.get(R.string.attachment_error_kdbx_capacity)
        is AttachmentError.NetworkError -> strings.get(R.string.attachment_error_network, error.httpStatus?.toString() ?: "?")
        is AttachmentError.TooLarge -> strings.get(R.string.attachment_error_too_large, "${error.limitBytes / (1024 * 1024)} MiB")
        // Provider exception messages may contain a field value or source URI.
        else -> strings.get(R.string.dedup_merge_changed_review)
    }
}
