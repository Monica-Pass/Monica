package takagi.ru.monica.data.dedup

import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.SecureItem

enum class DedupMergeSourceKind {
    MONICA_LOCAL,
    MDBX,
    KEEPASS,
    BITWARDEN
}

data class DedupMergeSourceOption(
    val key: String,
    val kind: DedupMergeSourceKind,
    val label: String,
    val passwordCount: Int,
    val secureItemCount: Int = 0,
    val passkeyCount: Int = 0
)

sealed class DedupMergeTarget {
    data object MonicaLocal : DedupMergeTarget()
    data class MdbxDatabase(
        val databaseId: Long,
        val label: String
    ) : DedupMergeTarget()
}

data class DedupMergeTargetOption(
    val target: DedupMergeTarget,
    val sourceKey: String,
    val label: String,
    val passwordCount: Int,
    val secureItemCount: Int = 0,
    val passkeyCount: Int = 0
)

enum class DedupConflictPolicy {
    MOST_COMPLETE,
    NEWEST
}

enum class DedupMergeValidationIssue {
    NEED_SOURCES,
    NEED_TARGET,
    TARGET_IS_SOURCE,
    NOTHING_TO_WRITE
}

data class DedupMergeValidation(
    val issues: Set<DedupMergeValidationIssue>
) {
    val canReview: Boolean
        get() = issues.none {
            it == DedupMergeValidationIssue.NEED_SOURCES ||
                it == DedupMergeValidationIssue.NEED_TARGET ||
                it == DedupMergeValidationIssue.TARGET_IS_SOURCE
        }

    val canExecute: Boolean
        get() = issues.isEmpty()
}

data class DedupMergeSelection(
    val sourceKeys: Set<String> = emptySet(),
    val targetOption: DedupMergeTargetOption? = null
) {
    fun toggleSource(sourceKey: String): DedupMergeSelection {
        if (sourceKey in sourceKeys) {
            return copy(sourceKeys = sourceKeys - sourceKey)
        }
        return copy(
            sourceKeys = sourceKeys + sourceKey,
            targetOption = targetOption?.takeUnless { it.sourceKey == sourceKey }
        )
    }

    fun selectTarget(option: DedupMergeTargetOption): DedupMergeSelection = copy(
        sourceKeys = sourceKeys - option.sourceKey,
        targetOption = option
    )

    fun selectAll(availableSourceKeys: Set<String>): DedupMergeSelection = copy(
        sourceKeys = availableSourceKeys - setOfNotNull(targetOption?.sourceKey)
    )

    fun validate(writableItems: Int): DedupMergeValidation {
        val issues = buildSet {
            if (sourceKeys.size < MINIMUM_SOURCE_DATABASES) {
                add(DedupMergeValidationIssue.NEED_SOURCES)
            }
            if (targetOption == null) {
                add(DedupMergeValidationIssue.NEED_TARGET)
            }
            if (targetOption?.sourceKey in sourceKeys) {
                add(DedupMergeValidationIssue.TARGET_IS_SOURCE)
            }
            if (writableItems <= 0) {
                add(DedupMergeValidationIssue.NOTHING_TO_WRITE)
            }
        }
        return DedupMergeValidation(issues)
    }

    companion object {
        const val MINIMUM_SOURCE_DATABASES = 1
    }
}

data class DedupResolvedPassword(
    val mergeKey: String,
    val entry: PasswordEntry,
    val customFields: List<CustomField>,
    val sourceEntryIds: List<Long>,
    val sourceLabels: List<String>,
    val conflictFields: Set<String>,
    val existsInTarget: Boolean = false,
    val targetHasDifferentContent: Boolean = false,
    val preferredSourceLabel: String = "",
    val attachments: List<DedupAttachmentRef> = emptyList(),
    /** All members of an explicit project must commit together. */
    val projectCopyKey: String? = null
)

data class DedupResolvedSecureItem(
    val mergeKey: String,
    val item: SecureItem,
    val sourceItemIds: List<Long>,
    val sourceLabels: List<String>,
    val conflictFields: Set<String>,
    val existsInTarget: Boolean = false,
    val targetHasDifferentContent: Boolean = false,
    val preferredSourceLabel: String = "",
    val attachments: List<DedupAttachmentRef> = emptyList()
)

data class DedupAttachmentRef(
    val attachmentId: Long,
    val contentKey: String,
    val readable: Boolean = true,
    val owner: takagi.ru.monica.attachments.model.AttachmentOwner? = null,
    val keepassDatabaseId: Long? = null,
    val keepassEntryUuid: String? = null
)

data class DedupMergeUnsupportedItem(val sourceLabel: String, val title: String)

enum class DedupPasskeySkipReason {
    REFERENCE_OR_MISSING_KEY, NONZERO_COUNTER, BOUND_PASSWORD, DEVICE_KEY, CREDENTIAL_CONFLICT, PRESERVED
}

/** Actual source project membership retained because it contains or references a Passkey. */
data class DedupPasskeyRetainedProject(
    val sourceKey: String,
    val sourceLabel: String,
    val projectKey: String,
    val title: String,
    val passwordIds: List<Long>,
    val secureItemIds: List<Long> = emptyList()
)

data class DedupResolvedPasskey(
    val mergeKey: String,
    val entry: PasskeyEntry,
    val sourceEntryIds: List<Long>,
    val sourceLabels: List<String>,
    val preferredSourceLabel: String,
    val existsInTarget: Boolean = false,
    val skipReason: DedupPasskeySkipReason? = DedupPasskeySkipReason.PRESERVED,
) {
    /** Passkeys never participate in the dedup engine, including old or forged previews. */
    val writable: Boolean get() = false
}

data class DedupMergePlan(
    val selectedSources: List<DedupMergeSourceOption> = emptyList(),
    val target: DedupMergeTarget? = null,
    val conflictPolicy: DedupConflictPolicy = DedupConflictPolicy.MOST_COMPLETE,
    val totalSourcePasswords: Int = 0,
    val totalSourceSecureItems: Int = 0,
    val unsupportedSourcePasskeys: Int = 0,
    val totalSourcePasskeys: Int = unsupportedSourcePasskeys,
    val uniquePasswords: Int = 0,
    val uniqueSecureItems: Int = 0,
    val duplicateGroups: Int = 0,
    val duplicateSecureItemGroups: Int = 0,
    val passwordConflictGroups: Int = 0,
    val secureItemConflictGroups: Int = 0,
    val targetExistingDuplicates: Int = 0,
    val targetExistingSecureItems: Int = 0,
    val previewPasswords: List<DedupResolvedPassword> = emptyList(),
    val previewSecureItems: List<DedupResolvedSecureItem> = emptyList(),
    val previewPasskeys: List<DedupResolvedPasskey> = emptyList(),
    val warnings: List<String> = emptyList(),
    val unsupportedItems: List<DedupMergeUnsupportedItem> = emptyList(),
    /** Whole sources whose native contents cannot be attested; never counted as fabricated items. */
    val retainedSources: List<DedupMergeSourceOption> = emptyList(),
    val passkeyRetainedProjects: List<DedupPasskeyRetainedProject> = emptyList(),
    val mdbxTargetAddsIndependentCopies: Boolean = false,
    /** Opaque, process-only authorization binding. Contains no content hashes. */
    val reviewToken: String? = null
) {
    val writablePasswords: Int
        get() = (uniquePasswords - targetExistingDuplicates).coerceAtLeast(0)

    val writableSecureItems: Int
        get() = (uniqueSecureItems - targetExistingSecureItems).coerceAtLeast(0)

    val writableItems: Int
        get() = writablePasswords + writableSecureItems + previewPasskeys.count { it.writable }

    val targetExistingPasskeys: Int
        get() = 0

    val previewItemCount: Int
        get() = previewPasswords.size + previewSecureItems.size + previewPasskeys.size

    val totalSourceItems: Int
        get() = totalSourcePasswords + totalSourceSecureItems + totalSourcePasskeys

    val duplicateGroupsTotal: Int
        get() = duplicateGroups + duplicateSecureItemGroups

    val conflictGroupsTotal: Int
        get() = passwordConflictGroups + secureItemConflictGroups

    val skippedItems: Int
        get() = targetExistingDuplicates + targetExistingSecureItems + targetExistingPasskeys + unsupportedSourcePasskeys + unsupportedItems.size

    val consolidatedCopies: Int
        get() = (totalSourcePasswords + totalSourceSecureItems - uniquePasswords - uniqueSecureItems - unsupportedItems.size).coerceAtLeast(0)

    val reviewConflictGroups: Int
        get() = previewPasswords.count { it.conflictFields.isNotEmpty() || it.targetHasDifferentContent } +
            previewSecureItems.count { it.conflictFields.isNotEmpty() || it.targetHasDifferentContent }
}

enum class DedupMergeItemKind {
    PASSWORD,
    SECURE_ITEM,
    PASSKEY
}

data class DedupMergeFailure(
    val kind: DedupMergeItemKind,
    val label: String,
    val reason: String
)

data class DedupMergeExecutionProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentLabel: String
) {
    val fraction: Float
        get() = if (totalItems <= 0) 0f else completedItems.toFloat() / totalItems.toFloat()
}

data class DedupMergeExecutionResult(
    val insertedPasswords: Int,
    val insertedSecureItems: Int = 0,
    val insertedPasskeys: Int = 0,
    val skippedExistingPasswords: Int,
    val skippedExistingSecureItems: Int = 0,
    val skippedExistingPasskeys: Int = 0,
    val skippedUnsupportedPasskeys: Int = 0,
    val failedPasswords: Int,
    val failedSecureItems: Int = 0,
    val failedPasskeys: Int = 0,
    val targetLabel: String,
    val failures: List<DedupMergeFailure> = emptyList(),
    val cancelled: Boolean = false
) {
    val insertedItems: Int
        get() = insertedPasswords + insertedSecureItems + insertedPasskeys

    val skippedExistingItems: Int
        get() = skippedExistingPasswords + skippedExistingSecureItems + skippedExistingPasskeys

    val failedItems: Int
        get() = failedPasswords + failedSecureItems + failedPasskeys

    val hasPartialFailure: Boolean
        get() = failedItems > 0 && insertedItems > 0
}
