package takagi.ru.monica.credentialexchange

import takagi.ru.monica.repository.readMdbxSshKeyData
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.bitwarden.sync.BitwardenMutationSyncBridge
import takagi.ru.monica.data.*
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.*
import takagi.ru.monica.transfer.*

/** One import session. New rows are scoped to a destination and native writes are awaited. */
class ImportDestinationWriter(
    private val context: Context,
    val destination: ImportDestination,
    private val passwords: PasswordRepository,
    private val secureItems: SecureItemRepository,
    private val progress: TransferProgressReporter = TransferProgressReporter.None,
) {
    private val database = PasswordDatabase.getDatabase(context)
    private val strings = AppLocaleStringResolver(context)
    private val security = SecurityManager(context)
    private val mdbx by lazy { MdbxRepositoryFactory.create(context, database, security) }
    private val passwordWriter by lazy {
        if (destination.mdbxId == null) passwords
        else PasswordRepository(database.passwordEntryDao())
    }
    private val secureItemWriter by lazy {
        if (destination.mdbxId == null) secureItems
        else SecureItemRepository(database.secureItemDao(),
            decryptSensitiveValue = security::decryptDataIfMonicaCiphertext)
    }
    // Imported rows are provisional until finish() acknowledges a native batch. Private keys
    // still go through the normal protected-key store, but no per-row native write is started.
    private val passkeys by lazy { PasskeyRepository(database.passkeyDao(), context = context) }
    private val keepassService by lazy { KeePassKdbxService(context, database.localKeePassDatabaseDao(), security) }
    private val keepass by lazy { KeePassCompatibilityBridge(KeePassWorkspaceRepository(keepassService)) }
    private val passwordIds = linkedSetOf<Long>()
    private val secureItemIds = linkedSetOf<Long>()
    private val passkeyIds = linkedSetOf<Long>()
    private val committedPasswords = mutableSetOf<Long>()
    private val committedSecureItems = mutableSetOf<Long>()
    private val committedPasskeys = mutableSetOf<Long>()
    private val touchedPasswordIds = linkedSetOf<Long>()
    private val touchedSecureItemIds = linkedSetOf<Long>()
    var supplementalFailures = 0
        private set
    fun recordSupplementalFailure() { supplementalFailures++ }
    fun trackPassword(id: Long) { touchedPasswordIds += id }
    fun trackSecureItem(id: Long) { touchedSecureItemIds += id }
    val uncommittedPasswordCount get() = (passwordIds - committedPasswords).size
    val uncommittedSecureItemCount get() = (secureItemIds - committedSecureItems).size
    val uncommittedPasskeyCount get() = (passkeyIds - committedPasskeys).size
    private var nativePasswords = emptyList<KeePassEntryData>()
    private var nativePasskeys = emptyList<PasskeyEntry>()
    private var nativeMdbxPasswords = emptyList<Pair<PasswordEntry, List<CustomField>>>()
    private val pendingPasswordFields = mutableMapOf<Long, List<Triple<String, String, Boolean>>>()
    val acceptsDeletedRecords get() = destination.kind in setOf(ImportDestinationKind.LOCAL, ImportDestinationKind.MDBX)
    private var knownNativeTokens: MutableList<NativeTokenBackup>? = null
    val isBitwarden get() = destination.bitwardenId != null

    suspend fun validate() = withContext(Dispatchers.IO) {
        when (destination.kind) {
            ImportDestinationKind.LOCAL -> Unit
            ImportDestinationKind.KEEPASS -> {
                check(database.localKeePassDatabaseDao().getDatabaseById(destination.databaseId) != null) {
                    strings.get(R.string.exchange_destination_unavailable)
                }
                check(!keepass.isDatabaseReadOnly(destination.databaseId)) {
                    strings.get(R.string.exchange_destination_readonly)
                }
                nativePasswords = keepass.loadLegacyWorkspace(destination.databaseId).getOrThrow().passwords.filterNot { it.isInRecycleBin }
                nativePasskeys = KeePassWorkspaceRepository(keepassService).readPasskeyEntries(destination.databaseId).getOrThrow()
                Unit
            }
            ImportDestinationKind.MDBX -> {
                check(database.localMdbxDatabaseDao().getDatabaseById(destination.databaseId) != null) {
                    strings.get(R.string.exchange_destination_unavailable)
                }
                val nativeEntries = mdbx.readStoredEntries(destination.databaseId).filterNot { it.deleted }
                nativeMdbxPasswords = nativeEntries.filter { it.entryType == "login" }.map { stored ->
                    val data = JSONObject(stored.payloadJson)
                    val projection = PasswordEntry(title = stored.title, username = data.optString("username"),
                        website = data.optString("website"), password = security.encryptData(data.getString("password_plain")),
                        notes = data.optString("notes"), appPackageName = data.optString("app_package_name"),
                        appName = data.optString("app_name"), loginType = data.optString("login_type", "PASSWORD"),
                        sshKeyData = data.readMdbxSshKeyData(),
                        mdbxDatabaseId = destination.databaseId, replicaGroupId = stored.entryId,
                        mdbxFolderId = data.optString("mdbx_folder_id").takeUnless { it.isBlank() || it == "null" },
                        authenticatorKey = data.optString("authenticator_key").takeIf { it.isNotEmpty() }
                            ?.let(security::encryptData).orEmpty())
                    val fields = data.optJSONArray("custom_fields")
                    projection to (0 until (fields?.length() ?: 0)).map { index ->
                        val field = fields!!.getJSONObject(index)
                        CustomField(entryId = 0, title = field.getString("title"), value = field.getString("value"),
                            isProtected = field.optBoolean("is_protected"), sortOrder = field.optInt("sort_order", index))
                    }
                }
                nativePasskeys = nativeEntries.filter { it.entryType == "passkey" }.map { stored ->
                    val data = JSONObject(stored.payloadJson)
                    PasskeyEntry(credentialId = data.getString("credential_id"), rpId = data.getString("rp_id"),
                        rpName = data.optString("rp_name", stored.title), userId = data.getString("user_id"),
                        userName = data.optString("user_name"), userDisplayName = data.optString("user_display_name"),
                        publicKeyAlgorithm = data.optInt("public_key_algorithm", -7), publicKey = data.optString("public_key"),
                        privateKeyAlias = data.getString("private_key_alias"), signCount = data.optLong("sign_count"),
                        mdbxDatabaseId = destination.databaseId)
                }
                Unit
            }
            ImportDestinationKind.BITWARDEN -> {
                val vault = database.bitwardenVaultDao().getVaultById(destination.databaseId)
                check(vault?.isConnected == true && BitwardenRepository.getInstance(context).isVaultUnlocked(destination.databaseId)) {
                    strings.get(R.string.exchange_destination_unlock)
                }
            }
        }
    }

    suspend fun findPassword(snapshot: ImportedPasswordSnapshot, importedFields: List<CustomFieldBackupEntry> = emptyList(),
        isDeleted: Boolean = false): PasswordEntry? {
        val expectedFields = importedFields.map { Triple(it.title, it.value, it.isProtected) }
        PasswordImportDuplicateResolver.findMatchingEntry(passwords, security, snapshot,
            localOnly = destination.kind == ImportDestinationKind.LOCAL,
            includeCandidate = { entry ->
                destination.contains(entry) && entry.isDeleted == isDeleted && (pendingPasswordFields[entry.id]
                    ?: database.customFieldDao().getFieldsByEntryIds(listOf(entry.id)).sortedBy { it.sortOrder }
                        .map { Triple(it.title, it.value, it.isProtected) }) == expectedFields
            }, deletedOnly = isDeleted)?.let { return it }
        if (isDeleted) return null
        nativeMdbxPasswords.firstOrNull { (entry, fields) ->
            entry.title == snapshot.title && entry.username == snapshot.username && entry.website == snapshot.website &&
                security.decryptData(entry.password) == security.decryptDataIfMonicaCiphertext(snapshot.password) &&
                entry.notes == snapshot.notes && entry.email == snapshot.email && entry.phone == snapshot.phone &&
                security.decryptDataIfMonicaCiphertext(entry.authenticatorKey) == security.decryptDataIfMonicaCiphertext(snapshot.authenticatorKey) &&
                fields.sortedBy { it.sortOrder }.map { Triple(it.title, it.value, it.isProtected) } == expectedFields
        }?.let { (entry, fields) ->
            // Rebuild only the local index. Mirroring this incomplete projection would rewrite the file.
            val id = database.passwordEntryDao().insertPasswordEntry(entry)
            fields.forEach { database.customFieldDao().insert(it.copy(entryId = id)) }
            return entry.copy(id = id)
        }
        // Native KDBX entries may not have a Room projection yet. Reuse their UUID instead of
        // duplicating an already existing credential in the actual file.
        val native = nativePasswords.firstOrNull { entry ->
            entry.title == snapshot.title && entry.username == snapshot.username && entry.url == snapshot.website &&
                entry.password == security.decryptDataIfMonicaCiphertext(snapshot.password) && entry.notes == snapshot.notes &&
                entry.email == snapshot.email && entry.phone == snapshot.phone && snapshot.authenticatorKey.isBlank() &&
                entry.customFields.sortedBy { it.sortOrder }.map { Triple(it.title, it.value, it.isProtected) } == expectedFields
        } ?: return null
        val projection = PasswordEntry(title = native.title, username = native.username,
            website = native.url, password = security.encryptData(native.password), notes = native.notes,
            appPackageName = native.appPackageName, appName = native.appName, email = native.email, phone = native.phone,
            keepassDatabaseId = destination.keepassId, keepassEntryUuid = native.entryUuid,
            keepassGroupPath = native.groupPath, keepassGroupUuid = native.groupUuid, loginType = native.loginType)
        val id = passwords.insertPasswordEntry(projection)
        native.customFields.forEach { field -> database.customFieldDao().insert(CustomField(
            entryId = id, title = field.title, value = field.value, isProtected = field.isProtected, sortOrder = field.sortOrder)) }
        return projection.copy(id = id)
    }

    suspend fun findPasskey(entry: PasskeyEntry): PasskeyEntry? = (database.passkeyDao()
        .getPasskeysByRpIdSync(entry.rpId) + nativePasskeys).firstOrNull {
            destination.contains(it) && it.rpId == entry.rpId && PasskeyCredentialIdCodec.normalize(it.credentialId) ==
                PasskeyCredentialIdCodec.normalize(entry.credentialId)
        }

    fun canImportSecureItem(type: ItemType) = destination.bitwardenId == null ||
        type in setOf(ItemType.TOTP, ItemType.NOTE, ItemType.BANK_CARD, ItemType.DOCUMENT)

    fun canImportPasskey(entry: PasskeyEntry): Boolean =
        PasskeyPrivateKeyStore.hasUsablePrivateKey(context, entry.privateKeyAlias) &&
            (destination.bitwardenId == null || entry.publicKeyAlgorithm == PasskeyEntry.ALGORITHM_ES256)

    suspend fun insertPassword(entry: PasswordEntry, importedFields: List<CustomFieldBackupEntry> = emptyList()): Long =
        passwordWriter.insertPasswordEntry(destination.password(entry)).also {
        check(it > 0)
        pendingPasswordFields[it] = importedFields.map { field -> Triple(field.title, field.value, field.isProtected) }
        passwordIds += it
        touchedPasswordIds += it
        if (destination.keepassId == null && destination.mdbxId == null) committedPasswords += it
    }

    suspend fun insertSecureItem(entry: SecureItem): Long = secureItemWriter.insertItem(destination.secureItem(entry)).also {
        check(it > 0)
        secureItemIds += it
        touchedSecureItemIds += it
        if (destination.keepassId == null && destination.mdbxId == null) committedSecureItems += it
    }

    suspend fun insertPasskey(entry: PasskeyEntry) {
        val mapped = destination.passkey(entry)
        passkeys.savePasskey(mapped)
        val id = findPasskey(mapped)?.id ?: error("Imported passkey was not persisted")
        passkeyIds += id
        if (destination.keepassId == null && destination.mdbxId == null) committedPasskeys += id
    }

    fun isNewPassword(id: Long) = id in passwordIds

    suspend fun updateNewPassword(entry: PasswordEntry) {
        check(entry.id in passwordIds)
        passwordWriter.updatePasswordEntry(entry)
    }

    suspend fun insertSteam(payload: takagi.ru.monica.steam.importer.SteamMaFilePayload) {
        when (destination.kind) {
            ImportDestinationKind.LOCAL -> takagi.ru.monica.steam.data.SteamAccountRepository(
                takagi.ru.monica.steam.data.SteamDatabase.getDatabase(context).steamAccountDao(), security
            ).upsertFromMaFile(payload)
            ImportDestinationKind.KEEPASS -> takagi.ru.monica.steam.data.SteamKeePassAccountStore(keepassService)
                .upsertPayload(destination.databaseId, payload)
            ImportDestinationKind.MDBX -> takagi.ru.monica.steam.data.SteamMdbxAccountStore(mdbx)
                .upsertPayload(destination.databaseId, payload)
            ImportDestinationKind.BITWARDEN -> takagi.ru.monica.steam.data.SteamBitwardenAccountStore(
                database, BitwardenRepository.getInstance(context), AttachmentContainer.facade(context)
            ).upsertPayload(destination.databaseId, payload)
        }
    }

    /** null means unsupported, false means already present, true means a new native record committed. */
    suspend fun insertNativeToken(token: NativeTokenBackup): Boolean? {
        val id = destination.mdbxId ?: return null
        val record = database.localMdbxDatabaseDao().getDatabaseById(id) ?: return null
        if (record.engineTypeEnum != MdbxEngineType.RUST_MDBX2) return null
        val repository = Mdbx2Repository(context, database.localMdbxDatabaseDao(), security)
        val existing = knownNativeTokens ?: repository.listNativeApiTokens(id).map { summary ->
            val value = repository.readNativeApiToken(summary)
            NativeTokenBackup(summary.title, value.payload, value.extras?.payload ?: ApiTokenMetadata.empty(), summary.isFavorite)
        }.toMutableList().also { knownNativeTokens = it }
        fun same(a: String, b: String) = kotlinx.serialization.json.Json.parseToJsonElement(a) ==
            kotlinx.serialization.json.Json.parseToJsonElement(b)
        if (existing.any { it.title == token.title && it.favorite == token.favorite &&
                same(it.payload, token.payload) && same(it.metadata, token.metadata) }) return false
        withContext(NonCancellable) {
            repository.saveNativeApiToken(id, null, token.title, token.payload,
                isFavorite = token.favorite, metadata = token.metadata)
            existing += token
        }
        return true
    }

    suspend fun finish() = withContext(Dispatchers.IO) {
        val newPasswords = passwordIds.mapNotNull { passwords.getPasswordEntryById(it) }
        val newSecureItems = secureItemIds.mapNotNull { database.secureItemDao().getItemById(it) }
        val newPasskeys = passkeyIds.mapNotNull { database.passkeyDao().getPasskeyByRecordId(it) }
        destination.keepassId?.let { id ->
            if (newPasswords.isNotEmpty()) attemptBatch {
                val customFields = passwordIds.toList().chunked(500)
                    .flatMap { database.customFieldDao().getFieldsByEntryIds(it) }
                    .groupBy { it.entryId }.mapValues { (_, fields) -> fields.map {
                        KeePassCustomFieldData(it.title, it.value, it.isProtected, it.sortOrder)
                    } }
                keepass.upsertLegacyPasswordEntries(id, newPasswords,
                    resolvePassword = { security.decryptDataIfMonicaCiphertext(it.password) },
                    forceSyncWrite = true, customFieldsByEntryId = customFields).getOrThrow()
                committedPasswords += passwordIds
            }
            if (newSecureItems.isNotEmpty()) attemptBatch {
                keepass.upsertLegacySecureItems(id, newSecureItems, forceSyncWrite = true).getOrThrow()
                committedSecureItems += secureItemIds
            }
            if (newPasskeys.isNotEmpty()) attemptBatch {
                keepass.upsertLegacyPasskeys(id, newPasskeys).getOrThrow()
                committedPasskeys += passkeyIds
            }
            // Metadata for failed native batches is not a successful import.
            discardUncommitted()
            val attachments = AttachmentContainer.facade(context)
            for (entryId in touchedPasswordIds) {
                val entry = passwords.getPasswordEntryById(entryId) ?: continue
                val uuid = entry.keepassEntryUuid ?: continue
                attemptSupplement {
                    attachments.copyAttachmentsToKeePassEntry(entry.id, entry.id, id, uuid)
                }
            }
            for (itemId in touchedSecureItemIds) {
                val item = database.secureItemDao().getItemById(itemId) ?: continue
                val uuid = item.keepassEntryUuid ?: continue
                val owner = AttachmentOwner.secureItem(item.id)
                for (attachment in attachments.list(owner).filter { it.sourceEnum == AttachmentSource.LOCAL }) attemptSupplement {
                    val bytes = attachments.readAttachmentBytes(attachment.id, maxBytes = 64 * 1024 * 1024)
                    try {
                        attachments.addInlineAttachment(takagi.ru.monica.attachments.facade.AttachmentFacade.InlineUploadRequest(
                            owner = owner, source = AttachmentSource.KEEPASS,
                            fileName = attachment.fileName, mimeType = attachment.mimeType,
                            bytes = bytes, isPlusActivated = SettingsManager(context).settingsFlow.first().isPlusActivated,
                            kdbxSoftLimitAccepted = false,
                            keepassContext = takagi.ru.monica.attachments.facade.AttachmentFacade.KeePassContext(id, uuid)))
                        attachments.forgetLocalAttachment(attachment.id)
                    } finally { bytes.fill(0) }
                }
            }
        }
        destination.mdbxId?.let { id ->
            val passwordsByObject = newPasswords.associateBy(::mdbxPasswordObjectId)
            val secureItemsByObject = newSecureItems.associateBy(::mdbxSecureItemObjectId)
            val passkeysByObject = newPasskeys.associateBy { "passkey:${it.credentialId}" }
            val total = (newPasswords.size + newSecureItems.size + newPasskeys.size).toLong()
            progress.report(TransferProgress(TransferPhase.WRITING, total = total))
            attemptSupplement(countFailure = {
                // The result already counts each uncommitted row. Only add a
                // separate error for a failure after every row was committed.
                uncommittedPasswordCount + uncommittedSecureItemCount + uncommittedPasskeyCount == 0
            }) {
                val importingContext = currentCoroutineContext()
                importingContext.ensureActive()
                withContext(NonCancellable) {
                    mdbx.upsertImportBatch(id, newPasswords, newSecureItems, newPasskeys) { objectIds ->
                        objectIds.forEach { objectId ->
                            passwordsByObject[objectId]?.let { committedPasswords += it.id }
                            secureItemsByObject[objectId]?.let { committedSecureItems += it.id }
                            passkeysByObject[objectId]?.let { committedPasskeys += it.id }
                        }
                        progress.report(TransferProgress(TransferPhase.WRITING,
                            (committedPasswords.size + committedSecureItems.size + committedPasskeys.size).toLong(), total))
                        // Finish and acknowledge the in-flight native batch before honoring
                        // cancellation; never continue writing the rest of a large import.
                        importingContext.ensureActive()
                    }
                }
            }
            discardUncommitted()
            progress.report(TransferProgress(TransferPhase.ATTACHMENTS))
            val attachments = AttachmentContainer.facade(context)
            touchedPasswordIds.filter { it !in passwordIds || it in committedPasswords }
                .forEach { entryId -> attemptSupplement { attachments.mirrorAttachmentsForPassword(entryId) } }
            touchedSecureItemIds.filter { it !in secureItemIds || it in committedSecureItems }
                .forEach { itemId -> attemptSupplement { attachments.mirrorAttachmentsForOwner(AttachmentOwner.secureItem(itemId)) } }
            attemptSupplement { mdbx.flushPendingWorkingCopy(id) }
        }
        destination.bitwardenId?.let { id ->
            attemptSupplement {
                BitwardenImportAttachmentQueue(context).enqueue(id,
                    touchedPasswordIds.map(AttachmentOwner::password) + touchedSecureItemIds.map(AttachmentOwner::secureItem))
                BitwardenMutationSyncBridge.requestLocalMutationSync(context, id)
            }
        }
    }

    private suspend fun attemptBatch(block: suspend () -> Unit) {
        // Once a native write starts, record its acknowledgement even if the caller is cancelled.
        try { withContext(NonCancellable) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Count uncommitted rows after every batch has had a chance to finish. */ }
    }

    private suspend fun attemptSupplement(countFailure: () -> Boolean = { true }, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (countFailure()) supplementalFailures++ }
    }

    /** Native writes are atomic per batch. Remove only new projections whose batch did not commit. */
    suspend fun discardUncommitted() {
        if (destination.keepassId == null && destination.mdbxId == null) return
        (passkeyIds - committedPasskeys).forEach { passkeys.deletePasskeyByRecordId(it) }
        val attachments = AttachmentContainer.facade(context)
        (secureItemIds - committedSecureItems).forEach { id ->
            attachments.list(AttachmentOwner.secureItem(id)).forEach { attachments.forgetLocalAttachment(it.id) }
            secureItemWriter.deleteItemById(id)
        }
        (passwordIds - committedPasswords).forEach { id ->
            attachments.purgeByPassword(id)
            passwordWriter.deletePasswordEntryById(id)
        }
    }

    companion object {
        suspend fun destinations(context: Context, forExport: Boolean = false): List<ImportDestinationOption> = withContext(Dispatchers.IO) {
            val db = PasswordDatabase.getDatabase(context)
            val kp = KeePassKdbxService(context, db.localKeePassDatabaseDao(), SecurityManager(context))
            val bw = BitwardenRepository.getInstance(context)
            buildList {
                add(ImportDestinationOption(ImportDestination.Local, "Monica", context.getString(R.string.database_source_local)))
                db.localKeePassDatabaseDao().getAllDatabasesSync().forEach { record ->
                    val writable = !kp.isDatabaseReadOnly(record.id)
                    add(ImportDestinationOption(ImportDestination(ImportDestinationKind.KEEPASS, record.id), record.name,
                        if (writable) "KeePass · KDBX" else context.getString(R.string.exchange_destination_readonly), writable || forExport))
                }
                db.localMdbxDatabaseDao().getAvailableDatabasesSnapshot().forEach { record ->
                    add(ImportDestinationOption(ImportDestination(ImportDestinationKind.MDBX, record.id), record.name, "MDBX"))
                }
                db.bitwardenVaultDao().getAllVaults().forEach { vault ->
                    val unlocked = vault.isConnected && bw.isVaultUnlocked(vault.id)
                    add(ImportDestinationOption(ImportDestination(ImportDestinationKind.BITWARDEN, vault.id),
                        vault.displayName ?: vault.email,
                        if (unlocked) "Bitwarden · ${vault.email}" else context.getString(R.string.exchange_destination_unlock), unlocked))
                }
            }
        }
    }
}
