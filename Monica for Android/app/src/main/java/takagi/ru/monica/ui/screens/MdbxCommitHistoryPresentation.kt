package takagi.ru.monica.ui.screens

import takagi.ru.monica.R
import takagi.ru.monica.utils.StringResolver
import java.util.Locale
import takagi.ru.monica.repository.MdbxCommitChangeSummary
import takagi.ru.monica.repository.MdbxDeltaSummary

internal enum class MdbxHistoryAction {
    CREATED,
    UPDATED,
    MOVED,
    COPIED,
    DELETED,
    RESTORED,
    MERGED,
    SYSTEM
}

internal data class MdbxCommitActionCounts(
    val created: Int = 0,
    val updated: Int = 0,
    val moved: Int = 0,
    val copied: Int = 0,
    val deleted: Int = 0,
    val restored: Int = 0
) {
    val total: Int
        get() = created + updated + moved + copied + deleted + restored

    fun summary(strings: StringResolver): String = buildList {
        if (created > 0) add(strings.get(R.string.mdbx_ui_history_added_count, created))
        if (updated > 0) add(strings.get(R.string.mdbx_ui_history_updated_count, updated))
        if (moved > 0) add(strings.get(R.string.mdbx_ui_history_moved_count, moved))
        if (copied > 0) add(strings.get(R.string.mdbx_ui_history_copied_count, copied))
        if (deleted > 0) add(strings.get(R.string.mdbx_ui_history_deleted_count, deleted))
        if (restored > 0) add(strings.get(R.string.mdbx_ui_history_restored_count, restored))
    }.joinToString(" · ")
}

internal data class MdbxCommitPresentation(
    val title: String,
    val supportingText: String,
    val primaryAction: MdbxHistoryAction,
    val actionCounts: MdbxCommitActionCounts,
    val objectCount: Int,
    val isSystemCommit: Boolean,
    val systemDescription: String?,
    val canRevert: Boolean
)

internal fun MdbxDeltaSummary.toHistoryPresentation(strings: StringResolver): MdbxCommitPresentation {
    val distinctChanges = changes.distinctBy { it.objectType to it.objectId }
    val actionCounts = distinctChanges.toActionCounts()
    val objectCount = distinctChanges.size.takeIf { it > 0 } ?: changedObjectCountFallback()
    val systemCommit = isSystemHistoryCommit()
    val primaryAction = when {
        systemCommit -> MdbxHistoryAction.SYSTEM
        commitKind.equals("merge", ignoreCase = true) || parentCount > 1 -> MdbxHistoryAction.MERGED
        actionCounts.deleted > 0 && actionCounts.deleted == actionCounts.total -> MdbxHistoryAction.DELETED
        actionCounts.restored > 0 && actionCounts.restored == actionCounts.total -> MdbxHistoryAction.RESTORED
        actionCounts.moved > 0 && actionCounts.moved == actionCounts.total -> MdbxHistoryAction.MOVED
        actionCounts.copied > 0 && actionCounts.copied == actionCounts.total -> MdbxHistoryAction.COPIED
        actionCounts.created > 0 && actionCounts.created == actionCounts.total -> MdbxHistoryAction.CREATED
        else -> MdbxHistoryAction.UPDATED
    }
    val title = operationTitle(strings, primaryAction, objectCount, distinctChanges)
    val systemDescription = if (systemCommit) systemCommitDescription(strings) else null
    val supportingText = when {
        systemDescription != null -> systemDescription
        actionCounts.summary(strings).isNotBlank() -> actionCounts.summary(strings)
        message?.isNotBlank() == true -> message.orEmpty()
        changedFieldSummary.isNotBlank() -> changedFieldSummary
        else -> strings.get(R.string.mdbx_ui_history_content_updated)
    }
    val canRevert = !systemCommit &&
        distinctChanges.isNotEmpty() &&
        distinctChanges.size <= MAX_ANDROID_REVERTABLE_OBJECTS &&
        distinctChanges.all { it.objectType.equals("entry", ignoreCase = true) }

    return MdbxCommitPresentation(
        title = title,
        supportingText = supportingText,
        primaryAction = primaryAction,
        actionCounts = actionCounts,
        objectCount = objectCount,
        isSystemCommit = systemCommit,
        systemDescription = systemDescription,
        canRevert = canRevert
    )
}

internal fun MdbxCommitChangeSummary.historyAction(): MdbxHistoryAction =
    when (action.trim().lowercase(Locale.ROOT)) {
        "create", "created", "add", "added" -> MdbxHistoryAction.CREATED
        "move", "moved" -> MdbxHistoryAction.MOVED
        "copy", "copied" -> MdbxHistoryAction.COPIED
        "delete", "deleted", "remove", "removed" -> MdbxHistoryAction.DELETED
        "restore", "restored", "revert", "reverted" -> MdbxHistoryAction.RESTORED
        else -> MdbxHistoryAction.UPDATED
    }

internal fun mdbxHistoryObjectTypeLabel(strings: StringResolver, objectType: String, contentType: String? = null): String {
    val normalizedContentType = contentType?.trim()?.lowercase(Locale.ROOT)
    return when (normalizedContentType) {
        "login", "password" -> strings.get(R.string.password)
        "note" -> strings.get(R.string.note_detail_title)
        "totp" -> strings.get(R.string.item_type_authenticator)
        "card" -> strings.get(R.string.timeline_item_card)
        "document-ref", "document" -> strings.get(R.string.item_type_document)
        "billing-address" -> strings.get(R.string.mdbx_ui_object_address)
        "payment-account" -> strings.get(R.string.mdbx_ui_object_payment_account)
        "passkey" -> strings.get(R.string.passkey)
        "steam-mafile" -> strings.get(R.string.import_type_steam_login_username_label)
        else -> when (objectType.trim().lowercase(Locale.ROOT)) {
            "entry" -> strings.get(R.string.mdbx_ui_object_entry)
            "project", "folder" -> strings.get(R.string.folder_generic)
            "attachment" -> strings.get(R.string.attachments)
            "passkey" -> strings.get(R.string.passkey)
            "object-relation" -> strings.get(R.string.folder_link)
            "object-label", "object-label-assignment" -> strings.get(R.string.keepass_native_tags)
            "vault-meta" -> strings.get(R.string.keepass_database_settings_title)
            "key-epoch" -> strings.get(R.string.mdbx_ui_object_vault_key)
            "snapshot" -> strings.get(R.string.mdbx_ui_object_snapshot)
            "branch" -> strings.get(R.string.mdbx_ui_object_sync_branch)
            else -> strings.get(R.string.mdbx_ui_object_generic)
        }
    }
}

private fun List<MdbxCommitChangeSummary>.toActionCounts(): MdbxCommitActionCounts {
    var created = 0
    var updated = 0
    var moved = 0
    var copied = 0
    var deleted = 0
    var restored = 0
    forEach { change ->
        when (change.historyAction()) {
            MdbxHistoryAction.CREATED -> created++
            MdbxHistoryAction.UPDATED -> updated++
            MdbxHistoryAction.MOVED -> moved++
            MdbxHistoryAction.COPIED -> copied++
            MdbxHistoryAction.DELETED -> deleted++
            MdbxHistoryAction.RESTORED -> restored++
            MdbxHistoryAction.MERGED,
            MdbxHistoryAction.SYSTEM -> updated++
        }
    }
    return MdbxCommitActionCounts(created, updated, moved, copied, deleted, restored)
}

private fun MdbxDeltaSummary.operationTitle(
    strings: StringResolver,
    primaryAction: MdbxHistoryAction,
    objectCount: Int,
    changes: List<MdbxCommitChangeSummary>
): String {
    val operation = operationKind?.trim()?.lowercase(Locale.ROOT)
    return when (operation) {
        "monica-initialize" -> strings.get(R.string.mdbx_ui_history_initialize)
        "monica-create-folder" -> strings.get(R.string.keepass_native_create_group)
        "monica-rename-folder" -> strings.get(R.string.keepass_native_rename_group)
        "monica-move-folder" -> strings.get(R.string.move_folder_title)
        "monica-delete-folder" -> strings.get(R.string.folder_delete)
        "monica-restore-folder" -> strings.get(R.string.mdbx_ui_history_restore_folder)
        "monica-migration-folders" -> strings.get(R.string.mdbx_ui_history_import_folders)
        "monica-project-tags" -> strings.get(R.string.mdbx_ui_history_update_folder_tags)
        "monica-delete-entries" -> actionTitle(strings, MdbxHistoryAction.DELETED, objectCount, changes)
        "revert-commit" -> strings.get(R.string.mdbx_ui_history_restore_version)
        else -> when {
            operation?.contains("attachment-create") == true -> actionTitle(
                strings,
                MdbxHistoryAction.CREATED,
                objectCount,
                changes,
                forcedType = strings.get(R.string.attachments)
            )
            operation?.contains("attachment-replace") == true -> strings.get(R.string.mdbx_ui_history_update_attachment)
            operation?.contains("snapshot") == true -> strings.get(R.string.mdbx_ui_history_update_snapshots)
            operation?.contains("key") == true && operation.contains("rotat") -> strings.get(R.string.mdbx_ui_history_rotate_key)
            else -> actionTitle(strings, primaryAction, objectCount, changes)
        }
    }
}

private fun actionTitle(
    strings: StringResolver,
    action: MdbxHistoryAction,
    objectCount: Int,
    changes: List<MdbxCommitChangeSummary>,
    forcedType: String? = null
): String {
    val objectLabel = forcedType ?: changes.singleObjectTypeLabel(strings)
    val quantity = if (objectCount > 0) strings.get(R.string.mdbx_ui_history_object_quantity, objectCount, objectLabel) else objectLabel
    return when (action) {
        MdbxHistoryAction.CREATED -> strings.get(R.string.mdbx_ui_history_action_created, quantity)
        MdbxHistoryAction.UPDATED -> strings.get(R.string.mdbx_ui_history_action_updated, quantity)
        MdbxHistoryAction.MOVED -> strings.get(R.string.mdbx_ui_history_action_moved, quantity)
        MdbxHistoryAction.COPIED -> strings.get(R.string.mdbx_ui_history_action_copied, quantity)
        MdbxHistoryAction.DELETED -> strings.get(R.string.mdbx_ui_history_action_deleted, quantity)
        MdbxHistoryAction.RESTORED -> strings.get(R.string.mdbx_ui_history_action_restored, quantity)
        MdbxHistoryAction.MERGED -> strings.get(R.string.mdbx_ui_history_action_merged)
        MdbxHistoryAction.SYSTEM -> strings.get(R.string.mdbx_ui_history_system_event)
    }
}

private fun List<MdbxCommitChangeSummary>.singleObjectTypeLabel(strings: StringResolver): String {
    val labels = map { mdbxHistoryObjectTypeLabel(strings, it.objectType) }.distinct()
    return labels.singleOrNull() ?: strings.get(R.string.timeline_item_default)
}

private fun MdbxDeltaSummary.isSystemHistoryCommit(): Boolean {
    val operation = operationKind?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val scope = changeScope.trim().lowercase(Locale.ROOT)
    val kind = commitKind.trim().lowercase(Locale.ROOT)
    return operation == "monica-initialize" ||
        operation.startsWith("snapshot-") ||
        operation.startsWith("branch-") ||
        operation.contains("key-rotation") ||
        operation.contains("security-policy") ||
        scope in SYSTEM_CHANGE_SCOPES ||
        kind in SYSTEM_COMMIT_KINDS
}

private fun MdbxDeltaSummary.systemCommitDescription(strings: StringResolver): String = when {
    operationKind.equals("monica-initialize", ignoreCase = true) ->
        strings.get(R.string.mdbx_ui_history_initialize_description)
    commitKind.equals("key-rotation", ignoreCase = true) ||
        changeScope.equals("key-epoch", ignoreCase = true) ->
        strings.get(R.string.mdbx_ui_history_key_description)
    commitKind.equals("snapshot", ignoreCase = true) ||
        changeScope.equals("snapshot", ignoreCase = true) ->
        strings.get(R.string.mdbx_ui_history_snapshot_description)
    changeScope.equals("branch", ignoreCase = true) ->
        strings.get(R.string.mdbx_ui_history_branch_description)
    changeScope.equals("vault-meta", ignoreCase = true) ->
        strings.get(R.string.mdbx_ui_history_metadata_description)
    else -> message?.takeIf { it.isNotBlank() }
        ?: strings.get(R.string.mdbx_ui_history_system_description)
}

private fun MdbxDeltaSummary.changedObjectCountFallback(): Int {
    val normalized = changedObjectIds.trim()
    if (normalized.isBlank() || normalized == "[]") return 0
    return normalized.trim('[', ']')
        .split(',')
        .count { it.trim().isNotBlank() }
}

private val SYSTEM_CHANGE_SCOPES = setOf("vault-meta", "key-epoch", "snapshot", "branch")
private val SYSTEM_COMMIT_KINDS = setOf("snapshot", "key-rotation")
private const val MAX_ANDROID_REVERTABLE_OBJECTS = 500
