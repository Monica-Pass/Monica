package takagi.ru.monica.transfer

import takagi.ru.monica.repository.readMdbxSshKeyData
import android.content.Context
import app.keemobile.kotpass.database.modifiers.binaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.backup.copyAttachmentPayload
import takagi.ru.monica.attachments.backup.writeVerifiedAttachmentPayload
import takagi.ru.monica.attachments.storage.readAttachmentKey
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.ImportDestinationKind
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.isExternalSteamMaFileEntry
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.data.*
import takagi.ru.monica.utils.*
import java.util.Date
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@Serializable
data class NativeTokenBackup(
    val title: String,
    val payload: String,
    val metadata: String,
    val favorite: Boolean = false,
)

internal data class ExportAttachment(
    val owner: AttachmentOwner,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val sha256Hex: String? = null,
    val ownerTitle: String = "",
    val localAttachmentId: Long? = null,
    val source: AttachmentSource? = null,
    private val copyTo: suspend (OutputStream) -> Unit,
) {
    suspend fun writeTo(output: OutputStream) =
        writeVerifiedAttachmentPayload(output, sizeBytes, sha256Hex, copyTo)

    // Kotpass requires a byte array. ZIP export streams directly into the archive.
    suspend fun read(): ByteArray = ByteArrayOutputStream().use { output ->
        writeTo(output)
        output.toByteArray()
    }
}

/** A source is resolved once. Writers cannot broaden it by querying global tables. */
internal data class DatabaseExportSnapshot(
    val source: ImportDestination,
    val passwords: List<PasswordEntry>,
    val secureItems: List<SecureItem>,
    val passkeys: List<PasskeyEntry>,
    val fields: Map<Long, List<CustomFieldBackupEntry>>,
    val categories: Map<Long, String>,
    val steamAccounts: List<SteamAccount>,
    val attachments: List<ExportAttachment>,
    val nativeTokens: List<NativeTokenBackup> = emptyList(),
)

internal class DatabaseExportSnapshotLoader(context: Context) {
    private val context = context.applicationContext
    private val db = PasswordDatabase.getDatabase(this.context)
    private val strings = AppLocaleStringResolver(context)
    private val security = SecurityManager(this.context)

    suspend fun load(source: ImportDestination, preferences: BackupPreferences): DatabaseExportSnapshot = withContext(Dispatchers.IO) {
        source.bitwardenId?.let { id ->
            check(db.bitwardenVaultDao().getVaultById(id)?.isConnected == true &&
                BitwardenRepository.getInstance(context).isVaultUnlocked(id)) {
                strings.get(R.string.exchange_destination_unlock)
            }
        }
        val roomPasswords = (db.passwordEntryDao().getAllPasswordEntriesSync() + db.passwordEntryDao().getDeletedEntriesSync())
            .distinctBy { it.id }.filter(source::contains)
        val roomItems = (db.secureItemDao().getAllItems().first() + db.secureItemDao().getDeletedItemsSync())
            .distinctBy { it.id }.filter(source::contains)
        val roomKeys = db.passkeyDao().getAllPasskeysSync().filter(source::contains)
        val fields = roomPasswords.map { it.id }.chunked(500).flatMap { db.customFieldDao().getFieldsByEntryIds(it) }
            .groupBy { it.entryId }.mapValues { (_, value) -> value.sortedBy { it.sortOrder }.map {
                CustomFieldBackupEntry(it.title, it.value, it.isProtected)
            } }.toMutableMap()
        val categories = db.categoryDao().getAllCategories().first().associate { it.id to it.name }.toMutableMap()
        var nextId = 1L shl 50
        fun newId() = nextId++
        val folderIds = mutableMapOf<String, Long>()
        fun folder(path: String?): Long? = path?.takeIf { it.isNotBlank() }?.let {
            folderIds.getOrPut(it) { newId().also { id -> categories[id] = path } }
        }
        var passwords = roomPasswords
        var items = roomItems
        var keys = roomKeys
        val attachments = mutableListOf<ExportAttachment>()
        val tokens = mutableListOf<NativeTokenBackup>()
        val kpService by lazy { KeePassKdbxService(context, db.localKeePassDatabaseDao(), security) }
        val mdbx by lazy { MdbxRepositoryFactory.create(context, db, security) }

        when (source.kind) {
            ImportDestinationKind.KEEPASS -> {
                val workspace = kpService.loadWorkspace(source.databaseId, includeRecycleBinGroups = true).getOrThrow()
                passwords = workspace.passwords.filter { !it.hasPasskeyFields || it.password.isNotEmpty() }.map { entry ->
                    val previous = roomPasswords.firstOrNull { it.keepassEntryUuid == entry.entryUuid }
                    val id = previous?.id ?: newId()
                    fields[id] = entry.customFields.map { CustomFieldBackupEntry(it.title, it.value, it.isProtected) }
                    (previous ?: PasswordEntry(title = entry.title, website = "", username = "", password = "")).copy(
                        id = id, title = entry.title, username = entry.username, website = entry.url,
                        // Keep literal cipher-like passwords distinct from app-encrypted values.
                        password = security.encryptData(entry.password), notes = entry.notes,
                        appPackageName = entry.appPackageName, appName = entry.appName, email = entry.email,
                        phone = entry.phone, sshKeyData = entry.sshKeyData, loginType = entry.loginType,
                        addressLine = entry.addressLine, city = entry.city, state = entry.state,
                        zipCode = entry.zipCode, country = entry.country,
                        creditCardNumber = entry.creditCardNumber, creditCardHolder = entry.creditCardHolder,
                        creditCardExpiry = entry.creditCardExpiry, creditCardCVV = entry.creditCardCVV,
                        ssoProvider = entry.ssoProvider, ssoRefEntryId = entry.ssoRefEntryId, wifiMetadata = entry.wifiMetadata,
                        categoryId = folder(entry.groupPath), keepassDatabaseId = source.databaseId,
                        keepassEntryUuid = entry.entryUuid, isDeleted = entry.isInRecycleBin,
                    )
                }
                items = workspace.secureItems.map { entry ->
                    val previous = roomItems.firstOrNull { it.keepassEntryUuid == entry.item.keepassEntryUuid }
                    entry.item.copy(id = previous?.id ?: newId(), categoryId = folder(entry.item.keepassGroupPath),
                        keepassDatabaseId = source.databaseId, isDeleted = entry.isInRecycleBin)
                }
                keys = kpService.readPasskeyEntries(source.databaseId).getOrThrow()
                if (preferences.includeImages) {
                    val session = KeePassWorkspaceRepository(kpService).openNativeSession(source.databaseId).getOrThrow()
                    val ownerByUuid = passwords.associate { it.keepassEntryUuid to AttachmentOwner.password(it.id) } +
                        items.associate { it.keepassEntryUuid to AttachmentOwner.secureItem(it.id) }
                    for (node in session.entryNodes) {
                        val owner = ownerByUuid[node.entry.uuid.toString()] ?: continue
                        for (ref in node.entry.binaries) {
                            val data = session.database.binaries[ref.hash] ?: error("Missing KDBX attachment")
                            attachments += ExportAttachment(owner, ref.name, "application/octet-stream",
                                0L, 0L) { output -> data.inputStream().use { copyAttachmentPayload(it, output) } }
                        }
                    }
                }
            }
            ImportDestinationKind.MDBX -> {
                val record = checkNotNull(db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)) {
                    strings.get(R.string.exchange_destination_unavailable)
                }
                val stored = mdbx.readStoredEntries(source.databaseId)
                val nativeFolders = mdbx.listFolders(source.databaseId).associate { it.folderId to it.pathKey }
                fun JSONObject.category() = folder(nativeFolders[optString("mdbx_folder_id")])
                passwords = stored.filter { it.entryType == "login" || it.entryType == "password" }.map { entry ->
                    val data = JSONObject(entry.payloadJson)
                    val previous = roomPasswords.firstOrNull { mdbxPasswordObjectId(it) == entry.entryId }
                    val id = previous?.id ?: newId()
                    val custom = data.optJSONArray("custom_fields")
                    fields[id] = (0 until (custom?.length() ?: 0)).map { i ->
                        val field = custom!!.getJSONObject(i)
                        CustomFieldBackupEntry(field.getString("title"), field.getString("value"), field.optBoolean("is_protected"))
                    }
                    (previous ?: PasswordEntry(title = entry.title, website = "", username = "", password = "")).copy(
                        id = id, title = entry.title, username = data.optString("username"), website = data.optString("website"),
                        password = security.encryptData(if (data.has("password_plain")) data.getString("password_plain")
                            else security.decryptDataIfMonicaCiphertext(data.optString("password"))),
                        notes = data.optString("notes"), appPackageName = data.optString("app_package_name"),
                        appName = data.optString("app_name"), authenticatorKey = security.encryptData(data.optString("authenticator_key")),
                        sshKeyData = data.readMdbxSshKeyData(previous?.sshKeyData.orEmpty()),
                        loginType = data.optString("login_type", "PASSWORD"), categoryId = data.category(),
                        sortOrder = data.optInt("sort_order"),
                        mdbxDatabaseId = source.databaseId, replicaGroupId = entry.entryId, isDeleted = entry.deleted,
                    ).let { MdbxPasswordContentFields.readInto(data, it, previous) }
                }
                val itemTypes = mapOf("note" to ItemType.NOTE, "totp" to ItemType.TOTP, "card" to ItemType.BANK_CARD,
                    "document-ref" to ItemType.DOCUMENT, "billing-address" to ItemType.BILLING_ADDRESS,
                    "payment-account" to ItemType.PAYMENT_ACCOUNT)
                items = stored.filter { it.entryType in itemTypes }.map { entry ->
                    val data = JSONObject(entry.payloadJson)
                    val previous = roomItems.firstOrNull { mdbxSecureItemObjectId(it) == entry.entryId }
                    SecureItem(id = previous?.id ?: newId(), itemType = itemTypes.getValue(entry.entryType), title = entry.title,
                        itemData = security.encryptData(data.optString("item_data")), notes = data.optString("notes"),
                        imagePaths = data.optString("image_paths"), categoryId = data.category(),
                        mdbxDatabaseId = source.databaseId, replicaGroupId = entry.entryId, isDeleted = entry.deleted,
                        createdAt = previous?.createdAt ?: Date(), updatedAt = previous?.updatedAt ?: Date(),
                        isFavorite = previous?.isFavorite ?: false, sortOrder = data.optInt("sort_order"))
                }
                keys = stored.filter { it.entryType == "passkey" && !it.deleted }.map { entry ->
                    val data = JSONObject(entry.payloadJson)
                    val previous = roomKeys.firstOrNull { it.credentialId == data.getString("credential_id") }
                    (previous ?: PasskeyEntry(credentialId = data.getString("credential_id"), rpId = data.getString("rp_id"),
                        rpName = data.optString("rp_name", entry.title), userId = data.getString("user_id"),
                        userName = data.optString("user_name"), userDisplayName = data.optString("user_display_name"),
                        privateKeyAlias = "", publicKey = "")).copy(privateKeyAlias = data.getString("private_key_alias"),
                        publicKeyAlgorithm = data.optInt("public_key_algorithm", -7), publicKey = data.optString("public_key"),
                        signCount = data.optLong("sign_count"), notes = data.optString("notes"),
                        transports = data.optString("transports", "internal"), aaguid = data.optString("aaguid"),
                        passkeyMode = data.optString("passkey_mode", PasskeyEntry.MODE_BW_COMPAT),
                        mdbxDatabaseId = source.databaseId, categoryId = data.category())
                }
                if (record.engineTypeEnum == MdbxEngineType.RUST_MDBX2 && preferences.includePasswords) {
                    val rust = Mdbx2Repository(context, db.localMdbxDatabaseDao(), security)
                    val native = rust.listNativeApiTokens(source.databaseId)
                    rust.withReadVaultForSync(source.databaseId) { _, vault ->
                        for (entry in stored.filter { it.entryType == ApiTokenPayload.NATIVE_TYPE && !it.deleted }) {
                            tokens += NativeTokenBackup(entry.title, entry.payloadJson,
                                NativeApiTokenExtrasStore.read(vault, entry.entryId)?.payload ?: ApiTokenMetadata.empty(),
                                native.firstOrNull { it.entryId == entry.entryId }?.isFavorite == true)
                        }
                    }
                }
                if (preferences.includeImages) {
                    val ownerById = passwords.associate { it.replicaGroupId to AttachmentOwner.password(it.id) } +
                        items.associate { it.replicaGroupId to AttachmentOwner.secureItem(it.id) }
                    // Native attachment reads are performed only for this source database.
                    for (attachment in mdbx.readStoredAttachments(source.databaseId).filterNot { it.deleted }) {
                        val owner = ownerById[attachment.entryId] ?: continue
                        attachments += ExportAttachment(owner, attachment.fileName, attachment.mimeType,
                            attachment.originalSize, attachment.createdAtMillis) { output ->
                            val storage = takagi.ru.monica.attachments.storage.AttachmentStorage(context)
                            val relative = "transfer-${java.util.UUID.randomUUID()}.enc"
                            val file = storage.absolutePathOf(relative)
                            try {
                                file.parentFile?.mkdirs()
                                file.writeBytes(attachment.blob)
                                val cek = readAttachmentKey(attachment.wrappedCek) { stored ->
                                    val wrapped = MdbxAttachmentCekPayload.toLocalWrappedCek(stored, security::encryptData)
                                    takagi.ru.monica.attachments.storage.AttachmentKeyVault(security).unwrap(wrapped)
                                }
                                try { storage.openDecryptedStream(relative, cek).use { copyAttachmentPayload(it, output) } }
                                finally { cek.fill(0) }
                            } finally { file.delete() }
                        }
                    }
                }
            }
            else -> Unit
        }
        val steam = if (!preferences.includeAuthenticators) emptyList() else loadSteamAccounts(source)
        passwords = passwords.filterNot { it.isExternalSteamMaFileEntry() }
        if (preferences.includeImages && source.kind in setOf(ImportDestinationKind.LOCAL, ImportDestinationKind.BITWARDEN)) {
            val facade = AttachmentContainer.facade(context)
            val owners = passwords.map { AttachmentOwner.password(it.id) to it.bitwardenCipherId } +
                items.map { AttachmentOwner.secureItem(it.id) to it.bitwardenCipherId }
            for ((owner, cipherId) in owners) for (attachment in facade.list(owner)) {
                attachments += ExportAttachment(owner, attachment.fileName, attachment.mimeType, attachment.sizeBytes, attachment.createdAt,
                    sha256Hex = attachment.sha256Hex, localAttachmentId = attachment.id, source = attachment.sourceEnum) { output ->
                    val bw = source.bitwardenId?.takeIf { attachment.sourceEnum == AttachmentSource.BITWARDEN }?.let { id ->
                        val vault = checkNotNull(db.bitwardenVaultDao().getVaultById(id))
                        BitwardenRepository.getInstance(context).getAttachmentBitwardenContext(vault, checkNotNull(cipherId))
                    }
                    try { facade.copyAttachmentTo(attachment.id, output, bitwardenContext = bw) }
                    finally { bw?.wrappingKey?.encKey?.fill(0); bw?.wrappingKey?.macKey?.fill(0) }
                }
            }
        }
        val ownerTitles = passwords.associate { AttachmentOwner.password(it.id) to it.title } +
            items.associate { AttachmentOwner.secureItem(it.id) to it.title }
        DatabaseExportSnapshot(source, passwords, items, keys, fields, categories, steam,
            attachments.map { it.copy(ownerTitle = ownerTitles[it.owner].orEmpty()) }, tokens)
    }

    suspend fun loadSteamAccounts(source: ImportDestination): List<SteamAccount> = withContext(Dispatchers.IO) {
        val kpService by lazy { KeePassKdbxService(context, db.localKeePassDatabaseDao(), security) }
        val mdbx by lazy { MdbxRepositoryFactory.create(context, db, security) }
        when (source.kind) {
            ImportDestinationKind.LOCAL -> SteamAccountRepository(SteamDatabase.getDatabase(context).steamAccountDao(), security).getAccounts()
            ImportDestinationKind.KEEPASS -> SteamKeePassAccountStore(kpService).loadAccounts(source.databaseId).map { it.account }
            ImportDestinationKind.MDBX -> SteamMdbxAccountStore(mdbx).loadAccounts(source.databaseId).map { it.account }
            ImportDestinationKind.BITWARDEN -> SteamBitwardenAccountStore(db, BitwardenRepository.getInstance(context),
                AttachmentContainer.facade(context)).loadAccounts(source.databaseId).map { it.account }
        }
    }
}
