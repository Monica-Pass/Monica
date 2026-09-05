package takagi.ru.monica.ui

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordPageContentType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenPendingOperation
import takagi.ru.monica.data.isKeePassOwned
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.model.TimelinePasswordRecreatedEntry
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import takagi.ru.monica.viewmodel.BankCardViewModel
import takagi.ru.monica.viewmodel.BillingAddressViewModel
import takagi.ru.monica.viewmodel.DocumentViewModel
import takagi.ru.monica.viewmodel.NoteViewModel
import takagi.ru.monica.viewmodel.PasskeyViewModel
import takagi.ru.monica.viewmodel.TotpViewModel
import takagi.ru.monica.ui.password.PasswordAggregateListItemUi
import takagi.ru.monica.ui.password.PasswordAggregateWalletItemType

internal data class PasswordBatchAggregateSelection(
    val bankCards: List<SecureItem> = emptyList(),
    val documents: List<SecureItem> = emptyList(),
    val billingAddresses: List<SecureItem> = emptyList(),
    val notes: List<SecureItem> = emptyList(),
    val totpItems: List<SecureItem> = emptyList(),
    val passkeys: List<PasskeyEntry> = emptyList()
) {
    val hasKeePassOwned: Boolean
        get() = bankCards.any { it.isKeePassOwned() } ||
            documents.any { it.isKeePassOwned() } ||
            billingAddresses.any { it.isKeePassOwned() } ||
            notes.any { it.isKeePassOwned() } ||
            totpItems.any { it.isKeePassOwned() } ||
            passkeys.any { it.isKeePassOwned() }

    val hasItems: Boolean
        get() = bankCards.isNotEmpty() ||
            documents.isNotEmpty() ||
            billingAddresses.isNotEmpty() ||
            notes.isNotEmpty() ||
            totpItems.isNotEmpty() ||
            passkeys.isNotEmpty()

    val secureItems: List<SecureItem>
        get() = totpItems + notes + bankCards + documents + billingAddresses
}

internal data class PasswordBatchMoveViewModels(
    val totpViewModel: TotpViewModel? = null,
    val bankCardViewModel: BankCardViewModel? = null,
    val documentViewModel: DocumentViewModel? = null,
    val billingAddressViewModel: BillingAddressViewModel? = null,
    val noteViewModel: NoteViewModel? = null,
    val passkeyViewModel: PasskeyViewModel? = null,
)

internal fun PasswordListAggregateUiState.toPasswordBatchMoveViewModels():
    PasswordBatchMoveViewModels = PasswordBatchMoveViewModels(
        totpViewModel = totpViewModel,
        bankCardViewModel = bankCardViewModel,
        documentViewModel = documentViewModel,
        billingAddressViewModel = billingAddressViewModel,
        noteViewModel = noteViewModel,
        passkeyViewModel = passkeyViewModel,
    )

internal data class MixedPasswordBatchMoveResult(
    val successCount: Int,
    val failedCount: Int,
    val blockedPasskeyCount: Int,
    val copiedPasswordIds: List<Long>,
    val copiedPasswordIdPairs: List<Pair<Long, Long>> = emptyList(),
    val keepassFailureMessages: List<String> = emptyList(),
)

internal fun PasswordListAggregateUiState.resolveBatchAggregateSelection(
    selectedSupplementaryItems: List<PasswordAggregateListItemUi>
): PasswordBatchAggregateSelection {
    if (selectedSupplementaryItems.isEmpty()) {
        return PasswordBatchAggregateSelection()
    }

    val bankCardIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.BANK_CARD
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val documentIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.DOCUMENT
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val billingAddressIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.BILLING_ADDRESS
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val noteIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.NOTE }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val totpIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.AUTHENTICATOR }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val passkeyIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.PASSKEY }
        .mapNotNullTo(linkedSetOf()) { it.passkeyRecordId }

    return PasswordBatchAggregateSelection(
        bankCards = bankCards.filter { it.id in bankCardIds },
        documents = documents.filter { it.id in documentIds },
        billingAddresses = billingAddresses.filter { it.id in billingAddressIds },
        notes = notes.filter { it.id in noteIds },
        totpItems = totpItems.filter { it.id in totpIds },
        passkeys = passkeys.filter { it.id in passkeyIds }
    )
}

internal suspend fun executeMixedPasswordBatchMove(
    context: Context,
    action: UnifiedMoveAction,
    target: UnifiedMoveCategoryTarget,
    selectedEntries: List<PasswordEntry>,
    aggregateSelection: PasswordBatchAggregateSelection,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    localKeePassViewModel: takagi.ru.monica.viewmodel.LocalKeePassViewModel,
    securityManager: SecurityManager,
    viewModel: PasswordViewModel,
    aggregateViewModels: PasswordBatchMoveViewModels,
    bitwardenRepository: BitwardenRepository,
    passwordTargetOverrides: Map<Long, UnifiedMoveCategoryTarget> = emptyMap(),
    onProgress: ((Int, Int) -> Unit)? = null
): MixedPasswordBatchMoveResult {
    if (
        passwordTargetOverrides.isNotEmpty() &&
        target !is UnifiedMoveCategoryTarget.MdbxDatabaseTarget &&
        target !is UnifiedMoveCategoryTarget.MdbxFolderTarget
    ) {
        val passwordGroups = groupPasswordBatchEntriesByTarget(
            entries = selectedEntries,
            selectedTarget = target,
            targetOverrides = passwordTargetOverrides
        )
        val totalCount = aggregateSelection.totalItemCount(selectedEntries.size)
        var completedCount = 0
        var successCount = 0
        var failedCount = 0
        var blockedPasskeyCount = 0
        val copiedPasswordIds = mutableListOf<Long>()
        val copiedPasswordIdPairs = mutableListOf<Pair<Long, Long>>()

        suspend fun executePart(
            partTarget: UnifiedMoveCategoryTarget,
            partPasswords: List<PasswordEntry>,
            partAggregateSelection: PasswordBatchAggregateSelection
        ) {
            val partSize = partAggregateSelection.totalItemCount(partPasswords.size)
            if (partSize <= 0) return
            val baseCompleted = completedCount
            val result = executeMixedPasswordBatchMove(
                context = context,
                action = action,
                target = partTarget,
                selectedEntries = partPasswords,
                aggregateSelection = partAggregateSelection,
                categories = categories,
                keepassDatabases = keepassDatabases,
                localKeePassViewModel = localKeePassViewModel,
                securityManager = securityManager,
                viewModel = viewModel,
                aggregateViewModels = aggregateViewModels,
                bitwardenRepository = bitwardenRepository,
                passwordTargetOverrides = emptyMap(),
                onProgress = { processed, _ ->
                    onProgress?.invoke(
                        (baseCompleted + processed).coerceAtMost(totalCount),
                        totalCount
                    )
                }
            )
            completedCount += partSize
            successCount += result.successCount
            failedCount += result.failedCount
            blockedPasskeyCount += result.blockedPasskeyCount
            copiedPasswordIds += result.copiedPasswordIds
            copiedPasswordIdPairs += result.copiedPasswordIdPairs
        }

        passwordGroups.forEach { (partTarget, partPasswords) ->
            executePart(
                partTarget = partTarget,
                partPasswords = partPasswords,
                partAggregateSelection = PasswordBatchAggregateSelection()
            )
        }
        if (aggregateSelection.hasItems) {
            executePart(
                partTarget = target,
                partPasswords = emptyList(),
                partAggregateSelection = aggregateSelection
            )
        }
        onProgress?.invoke(totalCount, totalCount)
        return MixedPasswordBatchMoveResult(
            successCount = successCount,
            failedCount = failedCount,
            blockedPasskeyCount = blockedPasskeyCount,
            copiedPasswordIds = copiedPasswordIds,
            copiedPasswordIdPairs = copiedPasswordIdPairs
        )
    }

    val passwordActionResolution = resolvePasswordBatchMoveAction(
        requestedAction = action,
        selectedEntries = selectedEntries,
        target = target
    )
    val forceSupplementaryCopy = shouldForceSupplementaryKeePassCopy(
        requestedAction = action,
        hasKeePassOwnedItems = aggregateSelection.hasKeePassOwned,
        target = target
    )
    if (passwordActionResolution.showKeepassCopyOnlyHint || forceSupplementaryCopy) {
        Toast.makeText(
            context,
            context.getString(R.string.keepass_copy_only_hint),
            Toast.LENGTH_SHORT
        ).show()
    }

    val effectiveAction = if (
        passwordActionResolution.effectiveAction == UnifiedMoveAction.COPY ||
        forceSupplementaryCopy
    ) {
        UnifiedMoveAction.COPY
    } else {
        action
    }

    if (
        effectiveAction == UnifiedMoveAction.COPY &&
        action == UnifiedMoveAction.COPY &&
        aggregateSelection.passkeys.isNotEmpty()
    ) {
        Toast.makeText(
            context,
            context.getString(R.string.passkey_copy_uses_move_hint),
            Toast.LENGTH_SHORT
        ).show()
    }

    val targetRouting = resolvePasswordBatchMoveTargetRouting(target)
    val selectedIds = selectedEntries.map(PasswordEntry::id)
    val targetCategoryId = targetRouting.monicaCategoryId
    val targetKeepassDatabaseId = when (target) {
        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
        else -> null
    }
    val targetKeepassGroupPath = when (target) {
        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.groupPath
        else -> null
    }
    val targetMdbxDatabaseId = when (target) {
        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> target.databaseId
        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.databaseId
        else -> null
    }
    val targetMdbxFolderId = when (target) {
        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.folderId
        else -> null
    }
    val targetBitwardenVaultId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
        else -> null
    }
    val targetBitwardenFolderId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> ""
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
        else -> null
    }
    val isMonicaLocalTarget = targetRouting.isMonicaCopyTarget

    var successCount = 0
    var failedCount = 0
    val keepassFailureMessages = mutableListOf<String>()
    var blockedPasskeyCount = 0
    val copiedPasswordIds = mutableListOf<Long>()
    val copiedPasswordIdPairs = mutableListOf<Pair<Long, Long>>()

    val totalCount = selectedEntries.size +
        aggregateSelection.bankCards.size +
        aggregateSelection.documents.size +
        aggregateSelection.billingAddresses.size +
        aggregateSelection.notes.size +
        aggregateSelection.totpItems.size +
        aggregateSelection.passkeys.size
    var processedCount = 0

    fun reportProgress(step: Int = 1) {
        if (totalCount <= 0 || step <= 0) return
        processedCount = (processedCount + step).coerceAtMost(totalCount)
        onProgress?.invoke(processedCount, totalCount)
    }

    if (totalCount > 0) {
        onProgress?.invoke(0, totalCount)
    }

    if (effectiveAction == UnifiedMoveAction.COPY) {
        if (targetRouting.isMonicaCopyTarget) {
            selectedEntries.forEach { entry ->
                val createdId = viewModel.copyPasswordToMonicaLocal(entry, targetCategoryId)
                if (createdId != null && createdId > 0) {
                    copiedPasswordIds += createdId
                    copiedPasswordIdPairs += entry.id to createdId
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        } else if (target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget || target is UnifiedMoveCategoryTarget.MdbxFolderTarget) {
            val copiedEntries = selectedEntries.map { entry ->
                buildCopiedEntryForTarget(
                    entry,
                    passwordBatchTargetForEntry(
                        entry = entry,
                        selectedTarget = target,
                        targetOverrides = passwordTargetOverrides
                    )
                )
            }
            val createdIds = viewModel.createMdbxPasswordEntriesBatchAlreadyEncrypted(copiedEntries)
            val copiedCount = createdIds.count { it > 0 }
            val idPairs = createdIds.mapIndexedNotNull { index, createdId ->
                if (createdId > 0) {
                    selectedEntries.getOrNull(index)?.id?.let { sourceId -> sourceId to createdId }
                } else {
                    null
                }
            }
            copiedPasswordIds += createdIds.filter { it > 0 }
            copiedPasswordIdPairs += idPairs
            successCount += copiedCount
            failedCount += (selectedEntries.size - copiedCount).coerceAtLeast(0)
            if (idPairs.isNotEmpty()) {
                viewModel.copyBoundTotpsForPasswordCopies(idPairs)
            }
            reportProgress(selectedEntries.size)
        } else {
            selectedEntries.forEach { entry ->
                val createdId = viewModel.addPasswordEntryWithResultAwait(
                    buildCopiedEntryForTarget(entry, target)
                )
                if (createdId != null && createdId > 0) {
                    copiedPasswordIds += createdId
                    copiedPasswordIdPairs += entry.id to createdId
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        }

        if (targetMdbxDatabaseId != null) {
            val batchResult = viewModel.copySecureItemsToMdbxBatch(
                items = aggregateSelection.secureItems,
                databaseId = targetMdbxDatabaseId,
                folderId = targetMdbxFolderId
            )
            successCount += batchResult.successCount
            failedCount += batchResult.failedCount
            reportProgress(aggregateSelection.secureItems.size)
        } else {
        aggregateSelection.totpItems.forEach { item ->
            val totpViewModel = aggregateViewModels.totpViewModel
            if (totpViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                if (totpViewModel.copyTotpToMonicaLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val totpData = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            )
            if (totpData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val detachedTotpData = totpData.copy(
                boundPasswordId = null,
                categoryId = null,
                keepassDatabaseId = null
            )
            totpViewModel.saveTotpItem(
                id = null,
                title = item.title,
                notes = item.notes,
                totpData = detachedTotpData,
                isFavorite = item.isFavorite,
                categoryId = targetCategoryId,
                keepassDatabaseId = targetKeepassDatabaseId,
                mdbxDatabaseId = targetMdbxDatabaseId,
                mdbxFolderId = targetMdbxFolderId,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.notes.forEach { item ->
            val noteViewModel = aggregateViewModels.noteViewModel
            if (noteViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                if (noteViewModel.copyNoteToMonicaLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val decodedNote = NoteContentCodec.decodeFromItem(item)
            noteViewModel.addNote(
                content = decodedNote.content,
                title = item.title,
                tags = decodedNote.tags,
                customFields = decodedNote.customFields,
                isMarkdown = decodedNote.isMarkdown,
                isFavorite = item.isFavorite,
                categoryId = targetCategoryId,
                imagePaths = item.imagePaths,
                keepassDatabaseId = targetKeepassDatabaseId,
                keepassGroupPath = targetKeepassGroupPath,
                mdbxDatabaseId = targetMdbxDatabaseId,
                mdbxFolderId = targetMdbxFolderId,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.bankCards.forEach { item ->
            val bankCardViewModel = aggregateViewModels.bankCardViewModel
            if (bankCardViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                if (bankCardViewModel.copyCardToMonicaLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val cardData = bankCardViewModel.parseCardData(item.itemData)
            if (cardData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val copiedId = bankCardViewModel.copyCardToStorage(
                item = item,
                target = when {
                    targetBitwardenVaultId != null -> takagi.ru.monica.data.model.StorageTarget.Bitwarden(targetBitwardenVaultId, targetBitwardenFolderId)
                    targetKeepassDatabaseId != null -> takagi.ru.monica.data.model.StorageTarget.KeePass(targetKeepassDatabaseId, targetKeepassGroupPath)
                    targetMdbxDatabaseId != null -> takagi.ru.monica.data.model.StorageTarget.Mdbx(targetMdbxDatabaseId, targetMdbxFolderId)
                    else -> takagi.ru.monica.data.model.StorageTarget.MonicaLocal(targetCategoryId)
                }
            )
            if (copiedId != null) successCount++ else failedCount++
            reportProgress()
        }

        aggregateSelection.documents.forEach { item ->
            val documentViewModel = aggregateViewModels.documentViewModel
            if (documentViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                if (documentViewModel.copyDocumentToMonicaLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val documentData = documentViewModel.parseDocumentData(item.itemData)
            if (documentData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val copiedId = documentViewModel.copyDocumentToStorage(
                item = item,
                target = when {
                    targetBitwardenVaultId != null -> takagi.ru.monica.data.model.StorageTarget.Bitwarden(targetBitwardenVaultId, targetBitwardenFolderId)
                    targetKeepassDatabaseId != null -> takagi.ru.monica.data.model.StorageTarget.KeePass(targetKeepassDatabaseId, targetKeepassGroupPath)
                    targetMdbxDatabaseId != null -> takagi.ru.monica.data.model.StorageTarget.Mdbx(targetMdbxDatabaseId, targetMdbxFolderId)
                    else -> takagi.ru.monica.data.model.StorageTarget.MonicaLocal(targetCategoryId)
                }
            )
            if (copiedId != null) successCount++ else failedCount++
            reportProgress()
        }

        aggregateSelection.billingAddresses.forEach { item ->
            val billingAddressViewModel = aggregateViewModels.billingAddressViewModel
            if (billingAddressViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val createdId = if (isMonicaLocalTarget) {
                billingAddressViewModel.copyAddressToMonicaLocal(item, targetCategoryId)
            } else {
                null
            }
            if (createdId != null) {
                successCount++
            } else {
                failedCount++
            }
            reportProgress()
        }
        }

    } else {
        val oldStates = selectedEntries.map(::toLocationState)
        val newStates = selectedEntries.map { entry ->
            toMovedLocationState(
                entry,
                passwordBatchTargetForEntry(
                    entry = entry,
                    selectedTarget = target,
                    targetOverrides = passwordTargetOverrides
                )
            )
        }
        val recreatedEntries = mutableListOf<TimelinePasswordRecreatedEntry>()
        val decryptedPasswordSnapshot = selectedEntries
            .mapNotNull { entry ->
                runCatching { securityManager.decryptData(entry.password) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { plain -> entry.password to plain }
            }
            .toMap()
        var passwordProgressHandledByCallback = false
        when {
            target == UnifiedMoveCategoryTarget.Uncategorized -> {
                try {
                    val keepassEntries = selectedEntries.filter { it.isKeePassEntry() }
                    val bitwardenEntries = selectedEntries.filter { it.isBitwardenEntry() }
                    val mdbxEntries = selectedEntries.filter { it.isMdbxEntry() }
                    val localIds = selectedEntries.filter { it.isLocalOnlyEntry() }.map { it.id }

                    if (keepassEntries.isNotEmpty()) {
                        val keepassIds = keepassEntries.map { it.id }
                        val result = viewModel.moveKeePassPasswordsToMonicaCategoryAwait(
                            ids = keepassIds,
                            categoryId = null
                        )
                        if (result.isSuccess) {
                            viewModel.unarchivePasswordsAwait(keepassIds)
                            successCount += keepassIds.size
                        } else {
                            failedCount += keepassEntries.size
                        }
                    }

                    bitwardenEntries.forEach { entry ->
                        val result = viewModel.moveBitwardenPasswordToMonicaLocal(entry, null)
                        if (result.isSuccess) {
                            recreatedEntries += TimelinePasswordRecreatedEntry(
                                sourceEntryId = entry.id,
                                recreatedEntryId = result.getOrThrow()
                            )
                            successCount++
                        } else {
                            failedCount++
                        }
                    }

                    if (mdbxEntries.isNotEmpty()) {
                        viewModel.moveMdbxPasswordsToMonicaCategoryAwait(
                            entries = mdbxEntries,
                            categoryId = null
                        )
                        successCount += mdbxEntries.size
                    }

                    if (localIds.isNotEmpty()) {
                        viewModel.unarchivePasswordsAwait(localIds)
                        viewModel.movePasswordsToCategoryAwait(localIds, null)
                        successCount += localIds.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.MonicaCategory -> {
                try {
                    val keepassEntries = selectedEntries.filter { it.isKeePassEntry() }
                    val bitwardenEntries = selectedEntries.filter { it.isBitwardenEntry() }
                    val mdbxEntries = selectedEntries.filter { it.isMdbxEntry() }
                    val localIds = selectedEntries.filter { it.isLocalOnlyEntry() }.map { it.id }

                    if (keepassEntries.isNotEmpty()) {
                        val keepassIds = keepassEntries.map { it.id }
                        val result = viewModel.moveKeePassPasswordsToMonicaCategoryAwait(
                            ids = keepassIds,
                            categoryId = target.categoryId
                        )
                        if (result.isSuccess) {
                            viewModel.unarchivePasswordsAwait(keepassIds)
                            successCount += keepassIds.size
                        } else {
                            failedCount += keepassEntries.size
                        }
                    }

                    bitwardenEntries.forEach { entry ->
                        val result = viewModel.moveBitwardenPasswordToMonicaLocal(
                            entry = entry,
                            categoryId = target.categoryId
                        )
                        if (result.isSuccess) {
                            recreatedEntries += TimelinePasswordRecreatedEntry(
                                sourceEntryId = entry.id,
                                recreatedEntryId = result.getOrThrow()
                            )
                            successCount++
                        } else {
                            failedCount++
                        }
                    }

                    if (mdbxEntries.isNotEmpty()) {
                        viewModel.moveMdbxPasswordsToMonicaCategoryAwait(
                            entries = mdbxEntries,
                            categoryId = target.categoryId
                        )
                        successCount += mdbxEntries.size
                    }

                    if (localIds.isNotEmpty()) {
                        viewModel.unarchivePasswordsAwait(localIds)
                        viewModel.movePasswordsToCategoryAwait(localIds, target.categoryId)
                        successCount += localIds.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> {
                if (selectedIds.isNotEmpty()) {
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToBitwardenFolderAwait(selectedIds, target.vaultId, "")
                    successCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                if (selectedIds.isNotEmpty()) {
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToBitwardenFolderAwait(selectedIds, target.vaultId, target.folderId)
                    successCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
                try {
                    val passwordProgressBase = processedCount
                    passwordProgressHandledByCallback = true
                    val result = localKeePassViewModel.movePasswordEntriesToKdbx(
                        databaseId = target.databaseId,
                        groupPath = null,
                        entries = selectedEntries,
                        decryptPassword = { encrypted ->
                            decryptedPasswordSnapshot[encrypted]
                                ?: securityManager.decryptData(encrypted)
                                ?: ""
                        },
                        onItemProcessed = { processed, total ->
                            val passwordTotal = selectedEntries.size.takeIf { it > 0 } ?: total
                            val normalizedProcessed = processed.coerceIn(0, passwordTotal)
                            val absoluteProcessed =
                                (passwordProgressBase + normalizedProcessed).coerceAtMost(totalCount)
                            processedCount = absoluteProcessed
                            onProgress?.invoke(absoluteProcessed, totalCount)
                        }
                    )
                    if (result.isSuccess) {
                        val summary = result.getOrThrow()
                        val succeededIds = summary.targetEntryUuidsByPasswordId.keys.toList()
                        if (succeededIds.isNotEmpty()) {
                            viewModel.unarchivePasswordsAwait(succeededIds)
                            viewModel.finalizePasswordsWrittenToKeePassAwait(
                                targetEntryUuidsByPasswordId = summary.targetEntryUuidsByPasswordId,
                                databaseId = target.databaseId,
                                groupPath = null
                            )
                        }
                        successCount += summary.successCount
                        failedCount += summary.failedCount
                        keepassFailureMessages += summary.failuresByPasswordId.values
                    } else {
                        failedCount += selectedEntries.size
                        result.exceptionOrNull()?.message?.let(keepassFailureMessages::add)
                    }
                } catch (error: Exception) {
                    failedCount += selectedEntries.size
                    error.message?.let(keepassFailureMessages::add)
                }
            }

            target is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                try {
                    val passwordProgressBase = processedCount
                    passwordProgressHandledByCallback = true
                    val result = localKeePassViewModel.movePasswordEntriesToKdbx(
                        databaseId = target.databaseId,
                        groupPath = target.groupPath,
                        groupUuid = target.groupUuid,
                        entries = selectedEntries,
                        decryptPassword = { encrypted ->
                            decryptedPasswordSnapshot[encrypted]
                                ?: securityManager.decryptData(encrypted)
                                ?: ""
                        },
                        onItemProcessed = { processed, total ->
                            val passwordTotal = selectedEntries.size.takeIf { it > 0 } ?: total
                            val normalizedProcessed = processed.coerceIn(0, passwordTotal)
                            val absoluteProcessed =
                                (passwordProgressBase + normalizedProcessed).coerceAtMost(totalCount)
                            processedCount = absoluteProcessed
                            onProgress?.invoke(absoluteProcessed, totalCount)
                        }
                    )
                    if (result.isSuccess) {
                        val summary = result.getOrThrow()
                        val succeededIds = summary.targetEntryUuidsByPasswordId.keys.toList()
                        if (succeededIds.isNotEmpty()) {
                            viewModel.unarchivePasswordsAwait(succeededIds)
                            viewModel.finalizePasswordsWrittenToKeePassAwait(
                                targetEntryUuidsByPasswordId = summary.targetEntryUuidsByPasswordId,
                                databaseId = target.databaseId,
                                groupPath = target.groupPath
                            )
                        }
                        successCount += summary.successCount
                        failedCount += summary.failedCount
                        keepassFailureMessages += summary.failuresByPasswordId.values
                    } else {
                        failedCount += selectedEntries.size
                        result.exceptionOrNull()?.message?.let(keepassFailureMessages::add)
                    }
                } catch (error: Exception) {
                    failedCount += selectedEntries.size
                    error.message?.let(keepassFailureMessages::add)
                }
            }

            target is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> {
                if (selectedIds.isNotEmpty()) {
                    val folderIdsByPasswordId = selectedEntries.associate { entry ->
                        val resolvedTarget = passwordBatchTargetForEntry(
                            entry = entry,
                            selectedTarget = target,
                            targetOverrides = passwordTargetOverrides
                        )
                        val folderId = (resolvedTarget as? UnifiedMoveCategoryTarget.MdbxFolderTarget)
                            ?.folderId
                        entry.id to folderId
                    }
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToMdbxFoldersAwait(
                        databaseId = target.databaseId,
                        folderIdsByPasswordId = folderIdsByPasswordId
                    )
                    successCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
                if (selectedIds.isNotEmpty()) {
                    val folderIdsByPasswordId = selectedEntries.associate { entry ->
                        val resolvedTarget = passwordBatchTargetForEntry(
                            entry = entry,
                            selectedTarget = target,
                            targetOverrides = passwordTargetOverrides
                        )
                        val folderId = (resolvedTarget as? UnifiedMoveCategoryTarget.MdbxFolderTarget)
                            ?.folderId
                            ?: target.folderId
                        entry.id to folderId
                    }
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToMdbxFoldersAwait(
                        databaseId = target.databaseId,
                        folderIdsByPasswordId = folderIdsByPasswordId
                    )
                    successCount += selectedEntries.size
                }
            }
        }

        if (!passwordProgressHandledByCallback) {
            reportProgress(selectedEntries.size)
        }

        if (targetMdbxDatabaseId != null) {
            val batchResult = viewModel.moveSecureItemsToMdbxBatch(
                items = aggregateSelection.secureItems,
                databaseId = targetMdbxDatabaseId,
                folderId = targetMdbxFolderId
            )
            successCount += batchResult.successCount
            failedCount += batchResult.failedCount
            reportProgress(aggregateSelection.secureItems.size)
        } else {
        aggregateSelection.totpItems.forEach { item ->
            val totpViewModel = aggregateViewModels.totpViewModel
            if (totpViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    totpViewModel.moveTotpToStorage(
                        id = item.id,
                        categoryId = targetCategoryId,
                    )
                } else {
                    totpViewModel.moveTotpToMonicaLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.notes.forEach { item ->
            val noteViewModel = aggregateViewModels.noteViewModel
            if (noteViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val moved = if (isMonicaLocalTarget) {
                if (item.isLocalOnlyItem()) {
                    noteViewModel.moveNoteToStorage(
                        item = item,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                } else {
                    noteViewModel.moveNoteToMonicaLocal(item, targetCategoryId).isSuccess
                }
            } else {
                noteViewModel.moveNoteToStorage(
                    item = item,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId,
                    mdbxDatabaseId = targetMdbxDatabaseId,
                    mdbxFolderId = targetMdbxFolderId
                )
            }
            if (moved) successCount++ else failedCount++
            reportProgress()
        }

        aggregateSelection.bankCards.forEach { item ->
            val bankCardViewModel = aggregateViewModels.bankCardViewModel
            if (bankCardViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    bankCardViewModel.moveCardToStorage(
                        id = item.id,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                } else {
                    bankCardViewModel.moveCardToMonicaLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            } else {
                val moved = bankCardViewModel.moveCardToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId,
                    mdbxDatabaseId = targetMdbxDatabaseId,
                    mdbxFolderId = targetMdbxFolderId
                )
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.documents.forEach { item ->
            val documentViewModel = aggregateViewModels.documentViewModel
            if (documentViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isMonicaLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    documentViewModel.moveDocumentToStorage(
                        id = item.id,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                } else {
                    documentViewModel.moveDocumentToMonicaLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            } else {
                val moved = documentViewModel.moveDocumentToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId,
                    mdbxDatabaseId = targetMdbxDatabaseId,
                    mdbxFolderId = targetMdbxFolderId
                )
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.billingAddresses.forEach { item ->
            val billingAddressViewModel = aggregateViewModels.billingAddressViewModel
            if (billingAddressViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val moved = if (isMonicaLocalTarget) {
                billingAddressViewModel.moveAddressToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    mdbxDatabaseId = null,
                    mdbxFolderId = null
                )
            } else {
                false
            }
            if (moved) successCount++ else failedCount++
            reportProgress()
        }

        if (!isMonicaLocalTarget) {
            aggregateSelection.totpItems.forEach { item ->
                val totpViewModel = aggregateViewModels.totpViewModel
                if (totpViewModel == null) {
                    failedCount++
                    reportProgress()
                    return@forEach
                }
                val moved = totpViewModel.moveTotpToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId,
                    mdbxDatabaseId = targetMdbxDatabaseId,
                    mdbxFolderId = targetMdbxFolderId
                )
                if (moved) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        }
        }

        if (selectedEntries.isNotEmpty()) {
            val targetLabel = buildMoveTargetLabel(
                context = context,
                target = target,
                categories = categories,
                keepassDatabases = keepassDatabases
            )
            logPasswordBatchMoveTimeline(
                context = context,
                selectedEntries = selectedEntries,
                oldStates = oldStates,
                newStates = newStates,
                recreatedEntries = recreatedEntries,
                targetLabel = targetLabel
            )
        }
    }

    val movablePasskeys = aggregateSelection.passkeys
        .filter { it.boundPasswordId == null && it.syncStatus != "REFERENCE" }
    val blockedPasskeysByType = aggregateSelection.passkeys.size - movablePasskeys.size
    if (blockedPasskeysByType > 0) {
        failedCount += blockedPasskeysByType
        reportProgress(blockedPasskeysByType)
    }
    val mdbxBatchPasskeys = if (targetMdbxDatabaseId != null) {
        movablePasskeys.filter { it.keepassDatabaseId == null }
    } else {
        emptyList()
    }
    val individuallyMovedPasskeys = if (targetMdbxDatabaseId != null) {
        movablePasskeys.filter { it.keepassDatabaseId != null }
    } else {
        movablePasskeys
    }
    if (mdbxBatchPasskeys.isNotEmpty()) {
        val preparedRecordIds = mutableListOf<Long>()
        mdbxBatchPasskeys.forEach { passkey ->
            val updateResult = applyPasswordPagePasskeyStorageTarget(
                passkey = passkey,
                target = target,
                bitwardenRepository = bitwardenRepository,
                context = context
            )
            when {
                updateResult.isSuccess && passkey.id > 0L -> {
                    val queueDelete = queuePasswordPagePasskeyBitwardenDeleteAfterMove(
                        source = passkey,
                        target = target,
                        bitwardenRepository = bitwardenRepository
                    )
                    if (queueDelete.isSuccess) {
                        preparedRecordIds += passkey.id
                    } else {
                        failedCount++
                        reportProgress()
                    }
                }

                updateResult.exceptionOrNull() is PasswordPagePasskeyBitwardenMoveBlockedException -> {
                    blockedPasskeyCount++
                    failedCount++
                    reportProgress()
                }

                else -> {
                    failedCount++
                    reportProgress()
                }
            }
        }
        if (preparedRecordIds.isNotEmpty()) {
            val persisted = aggregateViewModels.passkeyViewModel?.updateMdbxDatabaseForPasskeys(
                recordIds = preparedRecordIds,
                databaseId = targetMdbxDatabaseId!!,
                folderId = targetMdbxFolderId
            )
            if (persisted?.isSuccess == true) {
                successCount += preparedRecordIds.size
            } else {
                failedCount += preparedRecordIds.size
            }
            reportProgress(preparedRecordIds.size)
        }
    }
    individuallyMovedPasskeys.forEach { passkey ->
        val updateResult = applyPasswordPagePasskeyStorageTarget(
            passkey = passkey,
            target = target,
            bitwardenRepository = bitwardenRepository,
            context = context
        )
        when {
            updateResult.isSuccess -> {
                val updatedPasskey = updateResult.getOrThrow()
                val persisted = aggregateViewModels.passkeyViewModel?.updatePasskey(updatedPasskey)
                if (persisted?.isSuccess == true) {
                    val queueDelete = queuePasswordPagePasskeyBitwardenDeleteAfterMove(
                        source = passkey,
                        target = target,
                        bitwardenRepository = bitwardenRepository
                    )
                    if (queueDelete.isFailure) {
                        android.util.Log.e(
                            "PasswordBatchMoveMixed",
                            "Bitwarden source delete failed after passkey move: ${queueDelete.exceptionOrNull()?.message}"
                        )
                        failedCount++
                    } else {
                        successCount++
                    }
                } else {
                    failedCount++
                }
            }

            updateResult.exceptionOrNull() is PasswordPagePasskeyBitwardenMoveBlockedException -> {
                blockedPasskeyCount++
                failedCount++
            }

            else -> failedCount++
        }
        reportProgress()
    }

    if (copiedPasswordIds.isNotEmpty()) {
        logPasswordBatchCopyTimeline(
            context = context,
            copiedEntryIds = copiedPasswordIds
        )
    }

    return MixedPasswordBatchMoveResult(
        successCount = successCount,
        failedCount = failedCount,
        blockedPasskeyCount = blockedPasskeyCount,
        copiedPasswordIds = copiedPasswordIds,
        copiedPasswordIdPairs = copiedPasswordIdPairs,
        keepassFailureMessages = keepassFailureMessages.distinct(),
    )
}

private suspend fun PasswordViewModel.addPasswordEntryWithResultAwait(
    entry: PasswordEntry
): Long? {
    val deferred = CompletableDeferred<Long?>()
    addPasswordEntryWithResult(
        entry = entry,
        includeDetailedLog = false,
        // 来源 entry 的 password 已经是 Monica 加密过的密文，不能再加密一次
        passwordAlreadyEncrypted = true,
        // mixed batch 的 target 已经在 buildCopiedEntryForTarget 里明确指定，不走 categoryFilter
        skipCategoryBinding = true
    ) { createdId ->
        deferred.complete(createdId)
    }
    return deferred.await()
}

private class PasswordPagePasskeyBitwardenMoveBlockedException :
    IllegalStateException("Passkey cannot be migrated to Bitwarden")

internal suspend fun applyPasswordPagePasskeyStorageTarget(
    passkey: PasskeyEntry,
    target: UnifiedMoveCategoryTarget,
    bitwardenRepository: BitwardenRepository,
    context: Context
): Result<PasskeyEntry> {
    val currentVaultId = passkey.bitwardenVaultId
    val currentCipherId = passkey.bitwardenCipherId
    val targetVaultId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
        else -> null
    }

    if (targetVaultId != null) {
        val canMoveToBitwarden = withContext(Dispatchers.IO) {
            isPasswordPagePasskeyMigratableToBitwarden(context, passkey)
        }
    if (!canMoveToBitwarden) {
            return Result.failure(PasswordPagePasskeyBitwardenMoveBlockedException())
        }
    }

    val moved = when (target) {
        UnifiedMoveCategoryTarget.Uncategorized -> passkey.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenFolderId = null,
            bitwardenVaultId = null
        )

        is UnifiedMoveCategoryTarget.MonicaCategory -> passkey.copy(
            categoryId = target.categoryId,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenFolderId = null,
            bitwardenVaultId = null
        )

        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> passkey.copy(
            bitwardenVaultId = target.vaultId,
            bitwardenFolderId = null,
            keepassGroupPath = null,
            keepassDatabaseId = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null
        )

        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> passkey.copy(
            bitwardenVaultId = target.vaultId,
            bitwardenFolderId = target.folderId,
            keepassGroupPath = null,
            keepassDatabaseId = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null
        )

        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> passkey.copy(
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = null,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenFolderId = null,
            bitwardenVaultId = null
        )

        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> passkey.copy(
            keepassDatabaseId = target.databaseId,
            keepassGroupPath = target.groupPath,
            mdbxDatabaseId = null,
            mdbxFolderId = null,
            bitwardenFolderId = null,
            bitwardenVaultId = null
        )

        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> passkey.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = null,
            bitwardenFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null
        )

        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> passkey.copy(
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            mdbxDatabaseId = target.databaseId,
            mdbxFolderId = target.folderId,
            bitwardenFolderId = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null
        )
    }

    val keepExistingCipher =
        !currentCipherId.isNullOrBlank() &&
            currentVaultId != null &&
            currentVaultId == targetVaultId

    val resolvedSyncStatus = when {
        moved.syncStatus == "REFERENCE" -> "REFERENCE"
        targetVaultId == null -> "NONE"
        keepExistingCipher -> if (passkey.syncStatus == "SYNCED") "SYNCED" else "PENDING"
        else -> "PENDING"
    }
    val resolvedMode = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> PasskeyEntry.MODE_BW_COMPAT
        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> PasskeyEntry.MODE_KEEPASS_COMPAT
        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget,
        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> passkey.passkeyMode
        else -> if (passkey.isKeePassCompatible()) {
            PasskeyEntry.MODE_KEEPASS_COMPAT
        } else {
            passkey.passkeyMode
        }
    }

    return Result.success(
        moved.copy(
            passkeyMode = resolvedMode,
            bitwardenCipherId = if (keepExistingCipher) currentCipherId else null,
            syncStatus = resolvedSyncStatus
        )
    )
}

private suspend fun queuePasswordPagePasskeyBitwardenDeleteAfterMove(
    source: PasskeyEntry,
    target: UnifiedMoveCategoryTarget,
    bitwardenRepository: BitwardenRepository
): Result<Unit> {
    val currentVaultId = source.bitwardenVaultId
    val currentCipherId = source.bitwardenCipherId
    val targetVaultId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
        else -> null
    }
    val isLeavingCurrentCipher =
        currentVaultId != null &&
            !currentCipherId.isNullOrBlank() &&
            currentVaultId != targetVaultId
    if (!isLeavingCurrentCipher) return Result.success(Unit)

    return bitwardenRepository.queueCipherDelete(
        vaultId = currentVaultId,
        cipherId = currentCipherId!!,
        itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
    )
}

private fun isPasswordPagePasskeyMigratableToBitwarden(context: Context, passkey: PasskeyEntry): Boolean {
    if (passkey.passkeyMode != PasskeyEntry.MODE_BW_COMPAT) return false
    if (passkey.syncStatus == "REFERENCE") return false
    return PasskeyPrivateKeyStore.hasBitwardenCompatiblePrivateKey(context, passkey.privateKeyAlias)
}
