package takagi.ru.monica.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.*
import takagi.ru.monica.transfer.*
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.CustomFieldBackupEntry
import java.util.Date
import java.util.UUID
import takagi.ru.monica.security.SessionManager

internal suspend fun managerLocalRevision(repo: DatabaseManagerRepository): String {
    val passwords = repo.db.passwordEntryDao().getDeviceSyncLocalPasswords().sortedBy { it.id }
    val items = repo.db.secureItemDao().getDeviceSyncLocalItems().sortedBy { it.id }
    val categories = repo.db.categoryDao().getAllCategories().first().filter { it.mdbxDatabaseId == null && it.bitwardenVaultId == null }.sortedBy { it.id }
    val fields = passwords.map { it.id }.chunked(500).flatMap { repo.db.customFieldDao().getFieldsByEntryIds(it) }.sortedBy { it.id }
    val passkeys = repo.db.passkeyDao().getAllPasskeysSync().filter(ImportDestination.Local::contains).sortedBy { it.id }
    val attachments = passwords.map { it.id }.chunked(500).flatMap { repo.db.attachmentDao().getActiveByParents(it) } +
        items.map { it.id }.chunked(500).flatMap { repo.db.attachmentDao().getActiveBySecureItems(it) }
    val bytes = listOf(passwords, items, categories, fields, passkeys, attachments.sortedBy { it.id }).toString().toByteArray()
    return try { java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    finally { bytes.fill(0) }
}

internal suspend fun managerPreflightPortable(repo: DatabaseManagerRepository, source: DatabaseManagerLocation, row: MdbxStructureNode) {
    when (source.database.kind) {
        ImportDestinationKind.MDBX -> {
            val value = repo.mdbx.managerCapture(source.database.databaseId, row.id)
            try {
                check(value.summary.version == 1u && value.labels.isEmpty()) { "This native schema or label cannot be transferred losslessly to another format. Choose an MDBX destination." }
                val allowed = setOf("kind", "monica_entry_id", "room_id", "password_group_id", "website", "username", "app_package_name", "app_name",
                    "password_plain", "monica_password_encoding", "notes", "sort_order", "is_favorite", "created_at", "category_id", "mdbx_folder_id", "bound_note_room_id", "bound_note_entry_id",
                    "login_type", "ssh_key_data", "authenticator_key", "passkey_bindings", "custom_fields", "bitwarden_mode", "keepass_mode",
                    "email", "wifi_metadata", "phone", "address_line", "city", "state", "zip_code", "country", "credit_card_number_plain",
                    "credit_card_holder", "credit_card_expiry", "credit_card_cvv_plain", "item_data", "image_paths", "bound_password_entry_id")
                check(JSONObject(value.payload).keys().asSequence().all { it in allowed }) { "The entry contains fields unknown to the destination format. Its source is unchanged." }
            } finally { value.clear() }
        }
        ImportDestinationKind.KEEPASS -> {
            val entry = repo.keepass.openNativeBrowser(source.database.databaseId).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.id }
            check(entry.kind in setOf(KeePassNativeEntryKind.PASSWORD, KeePassNativeEntryKind.TOTP, KeePassNativeEntryKind.NOTE,
                KeePassNativeEntryKind.BANK_CARD, KeePassNativeEntryKind.DOCUMENT)) { "This native entry type requires a KeePass destination." }
            check(entry.history.isEmpty() && entry.customData.isEmpty() && entry.customIcon == null &&
                entry.customIconUuid == null && entry.tags.isEmpty() && entry.times?.expires != true &&
                (entry.autoType == null || entry.autoType == app.keemobile.kotpass.models.AutoTypeData(enabled = true)) &&
                entry.fields.none { it.rawValue.contains("{REF:", true) } &&
                entry.overrideUrl.isEmpty() && entry.foregroundColor == null && entry.backgroundColor == null) {
                "KeePass history or native presentation metadata cannot be represented by this destination. Choose KeePass to preserve it."
            }
        }
        else -> Unit
    }
}

internal suspend fun managerMoveLocal(repo: DatabaseManagerRepository, row: MdbxStructureNode, folderId: String?) {
    val category = folderId?.removePrefix("folder:")?.toLong()
    repo.db.withTransaction {
        check(SessionManager.isUnlocked.value) { "The vault is locked." }
        if (category != null) check(repo.db.categoryDao().getCategoryById(category)?.let { it.mdbxDatabaseId == null && it.bitwardenVaultId == null } == true)
        val id = row.id.substringAfter(':').toLong()
        when (row.id.substringBefore(':')) {
            "password" -> {
                val value = requireNotNull(repo.db.passwordEntryDao().getPasswordEntryById(id))
                check(ImportDestination.Local.contains(value) && !value.isDeleted)
                repo.db.passwordEntryDao().updatePasswordEntry(value.copy(categoryId = category))
            }
            "item" -> {
                val value = requireNotNull(repo.db.secureItemDao().getItemById(id))
                check(ImportDestination.Local.contains(value) && !value.isDeleted)
                repo.db.secureItemDao().updateItem(value.copy(categoryId = category))
            }
            "passkey" -> {
                val value = requireNotNull(repo.db.passkeyDao().getPasskeyByRecordId(id))
                check(ImportDestination.Local.contains(value))
                repo.db.passkeyDao().update(value.copy(categoryId = category))
            }
            else -> error("Unsupported record type")
        }
    }
}

/** Shared backup/import codecs keep extended credentials and attachment handling consistent. */
internal suspend fun managerTransferPortable(repo: DatabaseManagerRepository, source: DatabaseManagerLocation,
    target: DatabaseManagerLocation, row: MdbxStructureNode, copy: Boolean, batch: DatabaseManagerTransferContext) {
    managerPreflightPortable(repo, source, row)
    val loader = DatabaseExportSnapshotLoader(repo.context)
    val sourceRevision = repo.browse(source).revision
    val snapshot = batch.portableSnapshot?.takeIf { batch.portableSnapshotRevision == sourceRevision }
        ?: loader.load(source.database, BackupPreferences()).also {
            batch.portableSnapshot = it
            batch.portableSnapshotRevision = sourceRevision
        }
    val logicalId = if (source.database.mdbxId != null) {
        JSONObject(repo.mdbx.nativeObject(source.database.databaseId, row.id).payload).optString("monica_entry_id").ifBlank { row.id }
    } else row.id
    fun passwordId(value: PasswordEntry) = when (source.database.kind) {
        ImportDestinationKind.LOCAL -> "password:${value.id}"
        ImportDestinationKind.MDBX -> value.replicaGroupId
        else -> value.keepassEntryUuid
    }
    fun itemId(value: SecureItem) = when (source.database.kind) {
        ImportDestinationKind.LOCAL -> "item:${value.id}"
        ImportDestinationKind.MDBX -> value.replicaGroupId
        else -> value.keepassEntryUuid
    }
    val password = snapshot.passwords.singleOrNull { passwordId(it) == logicalId && !it.isDeleted }
    val item = snapshot.secureItems.singleOrNull { itemId(it) == logicalId && !it.isDeleted }
    check(password != null || item != null) { "This type cannot be converted losslessly. Use a destination with the same database format." }
    if (password != null) check(password.boundNoteId == null && password.passkeyBindings.isBlank() && password.ssoRefEntryId == null) {
        "This entry references other records. Copy its embedded content first, or use a database with the same format."
    }
    if (item != null) check(item.imagePaths.isBlank() || item.imagePaths == "[]") {
        "This entry has legacy image references. Convert them to attachments before transferring between formats."
    }
    if (item != null && source.database.keepassId != null) {
        val native = repo.keepass.openNativeBrowser(source.database.databaseId).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.id }
        // Older writers emitted these literal defaults; missing fields now carry the
        // same meaning. Every other native field still requires an exact match.
        val portableFields = mapOf("MonicaImagePaths" to "", "MonicaIsFavorite" to "false") +
            repo.kdbx.portableSecureItemFields(item)
        check(native.fields.all { it.name == "MonicaSecureItemId" || portableFields[it.name] == it.rawValue }) {
            "This entry has native fields that cannot be transferred losslessly. Choose a KeePass destination."
        }
    }
    val nativeCapture = if (!copy && source.database.mdbxId != null) repo.mdbx.managerCapture(source.database.databaseId, row.id) else null
    val keepassRevision = if (!copy && source.database.keepassId != null) repo.keepass.openNativeBrowser(source.database.databaseId).getOrThrow().sourceRevision.sha256 else null
    val sourceOwner = password?.let { AttachmentOwner.password(it.id) } ?: AttachmentOwner.secureItem(item!!.id)
    val assets = snapshot.attachments.filter { it.owner == sourceOwner }
    check(assets.sumOf { it.sizeBytes } <= 64L * 1024 * 1024) { "Attachments exceed the safe transfer limit (64 MiB)." }
    val attachmentBytes = mutableListOf<Pair<ExportAttachment, ByteArray>>()
    try {
        // A later deletion capture must never authorize removing newer data than the copied snapshot.
        check(repo.browse(source).revision == sourceRevision) { "The source changed. Refresh and select again." }
        // Validate all attachment bytes before creating a destination entry.
        assets.forEach { attachmentBytes += it to it.read() }
        val targetFolder = when (target.database.kind) {
            ImportDestinationKind.LOCAL -> target.folderId?.removePrefix("folder:")
            ImportDestinationKind.KEEPASS -> target.folderId?.let { id ->
                repo.keepass.openNativeBrowser(target.database.databaseId).getOrThrow().groups.single { it.identity.groupUuid.toString() == id }.legacyPath
            }
            else -> target.folderId
        }
        val writer = ImportDestinationWriter(repo.context, target.database,
            PasswordRepository(repo.db.passwordEntryDao()), SecureItemRepository(repo.db.secureItemDao()), destinationFolder = targetFolder)
        writer.validate()
        val fields = password?.let { snapshot.fields[it.id].orEmpty() }.orEmpty()
        val mappedPassword = password?.let { original -> original.copy(passwordGroupId = original.passwordGroupId?.let {
            batch.passwordGroups.getOrPut(it) { UUID.randomUUID().toString() }
        }) }
        val id = if (mappedPassword != null) writer.insertPassword(mappedPassword, fields) else writer.insertSecureItem(item!!)
        fields.forEachIndexed { order, field ->
            repo.db.customFieldDao().insert(CustomField(entryId = id, title = field.title, value = field.value,
                isProtected = field.isProtected, sortOrder = order))
        }
        val newOwner = if (password != null) AttachmentOwner.password(id) else AttachmentOwner.secureItem(id)
        // MDBX attachment uploads mirror immediately and require a committed native parent.
        // KeePass uploads are staged locally until the final finish() call instead.
        if (target.database.mdbxId != null && attachmentBytes.isNotEmpty()) {
            writer.finish()
            check(writer.supplementalFailures == 0 && writer.uncommittedPasswordCount == 0 && writer.uncommittedSecureItemCount == 0) {
                "The destination write was incomplete. The source was retained."
            }
        }
        val facade = AttachmentContainer.facade(repo.context)
        val plus = SettingsManager(repo.context).settingsFlow.first().isPlusActivated
        attachmentBytes.forEach { (asset, bytes) ->
            facade.addInlineAttachment(AttachmentFacade.InlineUploadRequest(newOwner, AttachmentSource.LOCAL,
                asset.fileName, asset.mimeType, bytes, plus))
        }
        writer.finish()
        check(writer.supplementalFailures == 0 && writer.uncommittedPasswordCount == 0 && writer.uncommittedSecureItemCount == 0) {
            "The destination write was incomplete. The source was retained."
        }
        val targetSnapshot = loader.load(target.database, BackupPreferences())
        val storedPassword = if (password != null) requireNotNull(repo.db.passwordEntryDao().getPasswordEntryById(id)) else null
        val storedItem = if (item != null) requireNotNull(repo.db.secureItemDao().getItemById(id)) else null
        val targetPassword = storedPassword?.let { stored -> targetSnapshot.passwords.singleOrNull {
            when (target.database.kind) {
                ImportDestinationKind.MDBX -> it.replicaGroupId == stored.replicaGroupId
                ImportDestinationKind.KEEPASS -> it.keepassEntryUuid == stored.keepassEntryUuid
                else -> it.id == id
            }
        } }
        val targetItem = storedItem?.let { stored -> targetSnapshot.secureItems.singleOrNull {
            when (target.database.kind) {
                ImportDestinationKind.MDBX -> it.replicaGroupId == stored.replicaGroupId
                ImportDestinationKind.KEEPASS -> it.keepassEntryUuid == stored.keepassEntryUuid
                else -> it.id == id
            }
        } }
        fun plain(value: String) = repo.security.decryptDataIfMonicaCiphertext(value)
        if (password != null) {
            check(targetPassword != null && managerPasswordContent(requireNotNull(mappedPassword), ::plain) == managerPasswordContent(targetPassword, ::plain)) {
                "The destination did not preserve all credential fields. The source was retained."
            }
            check(targetSnapshot.fields[targetPassword.id].orEmpty() == fields) { "Custom field verification failed. The source was retained." }
        } else {
            check(targetItem != null && targetItem.title == item!!.title && plain(targetItem.itemData) == plain(item.itemData) &&
                targetItem.itemType == item.itemType && targetItem.notes == item.notes) { "Entry content verification failed. The source was retained." }
        }
        val verifyOwner = targetPassword?.let { AttachmentOwner.password(it.id) } ?: AttachmentOwner.secureItem(targetItem!!.id)
        val copiedAssets = targetSnapshot.attachments.filter { it.owner == verifyOwner }
        check(copiedAssets.size == assets.size) { "Attachment count differs. The source was retained." }
        fun assetKey(name: String, mime: String, bytes: ByteArray) = listOf(name, mime, NativeApiTokenAssets.digest(bytes))
        val expectedAssets = attachmentBytes.map { (asset, bytes) -> assetKey(asset.fileName, asset.mimeType, bytes) }.sortedBy { it.toString() }
        val actualAssets = copiedAssets.map { actual ->
            val bytes = actual.read()
            try { assetKey(actual.fileName, actual.mimeType, bytes) }
            finally { bytes.fill(0) }
        }.sortedBy { it.toString() }
        check(expectedAssets == actualAssets) { "Attachment content differs. The source was retained." }
        check(SessionManager.isUnlocked.value) { "The vault is locked; both copies were retained." }
        if (!copy) when (source.database.kind) {
            ImportDestinationKind.MDBX -> repo.mdbx.managerDeleteVerifiedSource(source.database.databaseId, requireNotNull(nativeCapture))
            ImportDestinationKind.KEEPASS -> repo.keepass.deleteNativeEntries(source.database.databaseId, setOf(UUID.fromString(row.id)),
                KeePassNativeDeleteMode.RECYCLE_BIN, requireNotNull(keepassRevision)).getOrThrow()
            ImportDestinationKind.LOCAL -> repo.db.withTransaction {
                if (password != null) {
                    check(repo.db.passwordEntryDao().getPasswordEntryById(password.id) == password) { "The source changed; both copies were retained." }
                    val liveFields = repo.db.customFieldDao().getFieldsByEntryIds(listOf(password.id)).sortedBy { it.sortOrder }
                        .map { CustomFieldBackupEntry(it.title, it.value, it.isProtected) }
                    check(liveFields == fields) { "The source fields changed; both copies were retained." }
                    val liveAssets = repo.db.attachmentDao().getActiveByParent(password.id)
                    check(liveAssets.size == assets.size && liveAssets.all { live -> assets.any {
                        it.localAttachmentId == live.id && it.sha256Hex == live.sha256Hex && it.fileName == live.fileName && it.sizeBytes == live.sizeBytes
                    } }) { "The source attachments changed; both copies were retained." }
                    repo.db.passwordEntryDao().updatePasswordEntry(password.copy(isDeleted = true, deletedAt = Date()))
                } else {
                    check(repo.db.secureItemDao().getItemById(item!!.id) == item) { "The source changed; both copies were retained." }
                    val liveAssets = repo.db.attachmentDao().getActiveBySecureItem(item.id)
                    check(liveAssets.size == assets.size && liveAssets.all { live -> assets.any {
                        it.localAttachmentId == live.id && it.sha256Hex == live.sha256Hex && it.fileName == live.fileName && it.sizeBytes == live.sizeBytes
                    } }) { "The source attachments changed; both copies were retained." }
                    repo.db.secureItemDao().updateItem(item.copy(isDeleted = true, deletedAt = Date()))
                }
            }
            else -> error("Unsupported source")
        }
    } finally { attachmentBytes.forEach { it.second.fill(0) }; nativeCapture?.clear() }
}

internal fun managerPasswordContent(value: PasswordEntry, plain: (String) -> String): PasswordEntry = value.copy(
    id = 0, createdAt = Date(0), updatedAt = Date(0), categoryId = null, keepassDatabaseId = null,
    keepassEntryUuid = null, keepassGroupUuid = null, keepassGroupPath = null, mdbxDatabaseId = null, mdbxFolderId = null,
    bitwardenVaultId = null, bitwardenCipherId = null, bitwardenFolderId = null, bitwardenRevisionDate = null,
    bitwardenLocalModified = false, replicaGroupId = null, password = plain(value.password),
    authenticatorKey = plain(value.authenticatorKey), creditCardNumber = plain(value.creditCardNumber), creditCardCVV = plain(value.creditCardCVV),
)
