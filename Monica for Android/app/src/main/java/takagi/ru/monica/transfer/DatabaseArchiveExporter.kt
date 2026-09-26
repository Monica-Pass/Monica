package takagi.ru.monica.transfer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.attachments.backup.PortableAttachmentBackup
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.data.*
import takagi.ru.monica.passkey.PasskeyBackupPortabilityPolicy
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.utils.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** File export is deliberately separate from the whole-app WebDAV/OneDrive backup. */
internal class DatabaseArchiveExporter(context: Context) {
    private val context = context.applicationContext
    private val strings = AppLocaleStringResolver(context)
    private val security = SecurityManager(this.context)

    suspend fun prepare(
        source: ImportDestination,
        preferences: BackupPreferences,
        password: String?,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        require(preferences.hasDatabaseExportContent()) { strings.get(R.string.backup_select_content) }
        progress.report(TransferProgress(TransferPhase.READING))
        val snapshot = DatabaseExportSnapshotLoader(context).load(source, preferences)
        val passwords = snapshot.passwords.filter { preferences.includePasswords && !it.isDeleted }
        val items = snapshot.secureItems.filter { !it.isDeleted && includes(preferences, it.itemType) }
        val keys = snapshot.passkeys.takeIf { preferences.includePasskeys }.orEmpty()
        val tokens = snapshot.nativeTokens.takeIf { preferences.includePasswords }.orEmpty()
        val trashPasswords = snapshot.passwords.filter { preferences.includeTrash && it.isDeleted && preferences.includePasswords }
        val trashItems = snapshot.secureItems.filter { preferences.includeTrash && it.isDeleted && includes(preferences, it.itemType) }
        val passwordIds = (passwords + trashPasswords).mapTo(hashSetOf()) { it.id }
        val itemIds = (items + trashItems).mapTo(hashSetOf()) { it.id }
        val attachments = snapshot.attachments.filter {
            it.owner.passwordId in passwordIds || it.owner.secureItemId in itemIds
        }
        val encrypted = !password.isNullOrEmpty()
        check(keys.isEmpty() || encrypted) { strings.get(R.string.passkey_backup_encryption_required, keys.size) }
        check(attachments.isEmpty() || encrypted) { strings.get(R.string.transfer_attachment_encryption) }
        val file = File.createTempFile("monica-database-", ".zip", context.cacheDir)
        var encryptedFile: File? = null
        var success = false
        try {
            val total = (passwords.size + items.size + keys.size + snapshot.steamAccounts.size + tokens.size + trashPasswords.size + trashItems.size).toLong()
            var written = 0L
            ZipOutputStream(file.outputStream().buffered(64 * 1024)).use { zip ->
                // JSON compresses well at level 1; avoid thousands of intermediate JSON files.
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
                fun write(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name))
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    try { zip.write(bytes) } finally { bytes.fill(0) }
                    zip.closeEntry()
                }
                fun count() { progress.report(TransferProgress(TransferPhase.PACKING, ++written, total)) }
                write("database_export.json", JSONObject().put("version", 1).put("source", source.kind.name).toString())
                progress.report(TransferProgress(TransferPhase.PACKING, total = total))
                for (entry in passwords) {
                    currentCoroutineContext().ensureActive()
                    write("passwords/password_${entry.id}.json", passwordJson(entry, snapshot).toString())
                    count()
                }
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    val path = when (item.itemType) {
                        ItemType.NOTE -> "notes"
                        ItemType.TOTP -> "totp"
                        ItemType.BANK_CARD -> "bank_cards"
                        ItemType.DOCUMENT -> "documents"
                        ItemType.BILLING_ADDRESS -> "billing_addresses"
                        ItemType.PAYMENT_ACCOUNT -> "payment_accounts"
                        ItemType.PASSWORD -> error("Unsupported secure password record")
                    }
                    write("$path/item_${item.id}.json", itemJson(item, snapshot).toString())
                    count()
                }
                for ((index, key) in keys.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val decision = PasskeyBackupPortabilityPolicy.prepareExport(encrypted, key.privateKeyAlias,
                        { PasskeyPrivateKeyStore.resolve(security, it) }, PasskeyPrivateKeySupport::exportPkcs8Base64)
                    val material = (decision as? PasskeyBackupPortabilityPolicy.ExportDecision.Ready)?.privateKeyMaterial
                        ?: error(strings.get(R.string.passkey_backup_private_key_missing))
                    write("passkeys/passkey_$index.json", JSONObject()
                        .put("credentialId", key.credentialId).put("rpId", key.rpId).put("rpName", key.rpName)
                        .put("userId", key.userId).put("userName", key.userName).put("userDisplayName", key.userDisplayName)
                        .put("publicKeyAlgorithm", key.publicKeyAlgorithm).put("publicKey", key.publicKey)
                        .put("privateKeyAlias", material).put("createdAt", key.createdAt).put("lastUsedAt", key.lastUsedAt)
                        .put("useCount", key.useCount).put("iconUrl", key.iconUrl)
                        .put("isDiscoverable", key.isDiscoverable).put("isUserVerificationRequired", key.isUserVerificationRequired)
                        .put("transports", key.transports).put("aaguid", key.aaguid).put("signCount", key.signCount)
                        .put("notes", key.notes).put("boundPasswordId", key.boundPasswordId?.takeIf { it in passwordIds })
                        .put("passkeyMode", key.passkeyMode).toString())
                    count()
                }
                for ((index, account) in snapshot.steamAccounts.withIndex()) {
                    write("steam_mafiles/steam_$index.maFile", SteamMaFileBackupCodec.encode(account))
                    count()
                }
                if (tokens.isNotEmpty()) {
                    write("native_api_tokens.json", Json.encodeToString(tokens))
                    written += tokens.size
                    progress.report(TransferProgress(TransferPhase.PACKING, written, total))
                }
                if (preferences.includeTrash) {
                    if (trashPasswords.isNotEmpty()) write("trash/trash_passwords.json", JSONArray().also { array ->
                        trashPasswords.forEach { array.put(passwordJson(it, snapshot).put("deletedAt", it.deletedAt?.time)); count() }
                    }.toString())
                    if (trashItems.isNotEmpty()) write("trash/trash_secure_items.json", JSONArray().also { array ->
                        trashItems.forEach { array.put(itemJson(it, snapshot).put("deletedAt", it.deletedAt?.time)); count() }
                    }.toString())
                }
                if (preferences.includeTrashAndHistory && passwordIds.isNotEmpty()) {
                    val history = PasswordDatabase.getDatabase(context).passwordHistoryDao().getAllHistorySync()
                        .filter { it.entryId in passwordIds }.map { entry ->
                            PasswordHistoryBackupEntry(entry.entryId, plain(entry.password), entry.lastUsedAt.time)
                        }
                    if (history.isNotEmpty()) write("password_history.json", Json.encodeToString(history))
                }
                if (preferences.includeImages) {
                    val manifest = mutableListOf<PortableAttachmentBackup.Entry>()
                    for ((index, attachment) in attachments.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        progress.report(TransferProgress(TransferPhase.ATTACHMENTS, index.toLong(), attachments.size.toLong()))
                        val name = "${PortableAttachmentBackup.DIR_NAME}/attachment_$index.bin"
                        zip.putNextEntry(ZipEntry(name))
                        val payload = withAttachmentExportError(attachment) { attachment.writeTo(zip) }
                        zip.closeEntry()
                        manifest += PortableAttachmentBackup.Entry(attachment.owner.passwordId, attachment.owner.secureItemId,
                            attachment.fileName, attachment.mimeType, payload.sizeBytes, payload.sha256Hex,
                            name, attachment.createdAt, attachment.createdAt)
                    }
                    if (manifest.isNotEmpty()) write(PortableAttachmentBackup.MANIFEST_ENTRY, PortableAttachmentBackup.encodeManifest(manifest))
                    // Legacy images and user icons are also restricted to included owners.
                    val imageNames = items.flatMap { item -> runCatching {
                        val array = JSONArray(item.imagePaths)
                        (0 until array.length()).map { File(array.getString(it)).name }
                    }.getOrDefault(emptyList()) }.distinct()
                    for (name in imageNames) {
                        val image = File(File(context.filesDir, "secure_images"), name)
                        if (image.isFile) { zip.putNextEntry(ZipEntry("images/$name")); image.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    }
                    for (name in passwords.filter { it.customIconType.equals("UPLOADED", true) }
                        .mapNotNull { it.customIconValue?.let { value -> File(value).name } }.distinct()) {
                        val icon = File(File(context.filesDir, "password_icons"), name)
                        if (icon.isFile) { zip.putNextEntry(ZipEntry("password_icons/$name")); icon.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    }
                }
            }
            val output = if (encrypted) {
                progress.report(TransferProgress(TransferPhase.ENCRYPTING))
                File.createTempFile("monica-database-", ".enc.zip", context.cacheDir).also { target ->
                    encryptedFile = target
                    EncryptionHelper.encryptFile(file, target, checkNotNull(password), AppLocaleStringResolver(context)).getOrThrow()
                }
            } else file
            currentCoroutineContext().ensureActive()
            success = true
            output to strings.get(R.string.transfer_export_complete, total)
        } finally {
            if (!success || encrypted) file.delete()
            if (!success) encryptedFile?.delete()
        }
    }

    private fun plain(value: String) = PortableSecretExportPolicy.resolve(value, "", security::decryptDataIfMonicaCiphertext)

    private fun passwordJson(entry: PasswordEntry, snapshot: DatabaseExportSnapshot) = JSONObject()
        .put("id", entry.id).put("title", entry.title).put("username", entry.username).put("password", plain(entry.password))
        .put("website", entry.website).put("notes", entry.notes).put("isFavorite", entry.isFavorite).put("sortOrder", entry.sortOrder)
        .put("categoryName", snapshot.categories[entry.categoryId]).put("appPackageName", entry.appPackageName).put("appName", entry.appName)
        .put("email", entry.email).put("phone", entry.phone).put("addressLine", entry.addressLine).put("city", entry.city)
        .put("state", entry.state).put("zipCode", entry.zipCode).put("country", entry.country)
        .put("creditCardNumber", plain(entry.creditCardNumber)).put("creditCardHolder", entry.creditCardHolder)
        .put("creditCardExpiry", entry.creditCardExpiry).put("creditCardCVV", plain(entry.creditCardCVV))
        .put("isArchived", entry.isArchived).put("archivedAt", entry.archivedAt?.time).put("createdAt", entry.createdAt.time).put("updatedAt", entry.updatedAt.time)
        .put("authenticatorKey", plain(entry.authenticatorKey)).put("passkeyBindings", entry.passkeyBindings)
        .put("sshKeyData", entry.sshKeyData).put("loginType", entry.loginType).put("ssoProvider", entry.ssoProvider)
        .put("ssoRefEntryId", entry.ssoRefEntryId?.takeIf { id -> snapshot.passwords.any { it.id == id } }).put("boundNoteId", entry.boundNoteId?.takeIf { id -> snapshot.secureItems.any { it.id == id } }).put("wifiMetadata", entry.wifiMetadata)
        .put("customIconType", entry.customIconType).put("customIconValue", entry.customIconValue?.let {
            if (entry.customIconType.equals("UPLOADED", true)) File(it).name else it
        })
        .put("customIconUpdatedAt", entry.customIconUpdatedAt)
        .put("customFields", JSONArray(Json.encodeToString(snapshot.fields[entry.id].orEmpty())))

    private fun itemJson(item: SecureItem, snapshot: DatabaseExportSnapshot) = JSONObject()
        .put("id", item.id).put("title", item.title).put("itemType", item.itemType.name)
        .put("itemData", if (item.itemType == ItemType.TOTP) PortableTotpBackupCodec.encode(item.itemData, item.title,
            security::decryptDataIfMonicaCiphertext) else plain(item.itemData))
        .put("notes", item.notes).put("isFavorite", item.isFavorite).put("sortOrder", item.sortOrder)
        .put("imagePaths", item.imagePaths).put("createdAt", item.createdAt.time).put("updatedAt", item.updatedAt.time)
        .put("categoryName", snapshot.categories[item.categoryId])

    private fun includes(preferences: BackupPreferences, type: ItemType) = when (type) {
        ItemType.TOTP -> preferences.includeAuthenticators
        ItemType.NOTE -> preferences.includeNotes
        ItemType.BANK_CARD -> preferences.includeBankCards
        ItemType.DOCUMENT -> preferences.includeDocuments
        ItemType.BILLING_ADDRESS, ItemType.PAYMENT_ACCOUNT -> preferences.includeBankCards || preferences.includeDocuments
        ItemType.PASSWORD -> preferences.includePasswords
    }
}

internal fun BackupPreferences.hasDatabaseExportContent(): Boolean = includePasswords || includeAuthenticators ||
    includePasskeys || includeNotes || includeDocuments || includeBankCards
