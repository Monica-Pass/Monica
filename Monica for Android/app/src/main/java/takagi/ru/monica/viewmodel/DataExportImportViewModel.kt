package takagi.ru.monica.viewmodel

import takagi.ru.monica.utils.backupExportMessage

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.credentialexchange.CxfCredentialCodec
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.ImportResultSummary
import takagi.ru.monica.credentialexchange.TargetedImportCoordinator
import takagi.ru.monica.utils.BackupContent
import takagi.ru.monica.utils.CustomFieldBackupEntry
import takagi.ru.monica.data.BackupReport
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.OperationLogItemType
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PreparedSteamMaFileExport
import takagi.ru.monica.data.SteamMaFileExportCandidate
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.utils.BackupRestoreApplier
import takagi.ru.monica.utils.EncryptionHelper
import takagi.ru.monica.utils.FieldChange
import takagi.ru.monica.utils.ImportedPasswordSnapshot
import takagi.ru.monica.utils.OperationLogger
import takagi.ru.monica.utils.PasswordImportDuplicateResolver
import takagi.ru.monica.util.CsvPasswordData
import takagi.ru.monica.util.DataExportImportManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.TotpUriParser
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.utils.BackupContentScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.service.SteamLoginImportService
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import takagi.ru.monica.transfer.*

/**
 * 数据导入导出ViewModel
 */
class DataExportImportViewModel(
    private val secureItemRepository: SecureItemRepository,
    private val passwordRepository: PasswordRepository,
    context: Context
) : ViewModel() {
    private val context = context.applicationContext

    private data class ImportedAuthenticatorDraft(
        val authenticatorKey: String,
        val totpData: TotpData?
    )

    private val strings = AppLocaleStringResolver(context)
    private val exportManager = DataExportImportManager(context)
    private val steamLoginImportService = SteamLoginImportService(strings)
    private val securityManager by lazy { SecurityManager(context) }
    private val _importProgress = MutableStateFlow<TransferProgress?>(null)
    val importProgress = _importProgress.asStateFlow()
    private val importProgressReporter = TransferProgressReporter { _importProgress.value = it; DatabaseExportJobs.reportImportProgress(it) }
    private val targetedImporter by lazy {
        TargetedImportCoordinator(this.context, passwordRepository, secureItemRepository, importProgressReporter)
    }
    private val _lastImportSummary = MutableStateFlow<ImportResultSummary?>(null)
    val lastImportSummary = _lastImportSummary.asStateFlow()
    fun clearImportSummary() { _lastImportSummary.value = null }

    suspend fun importCredentialExchange(
        decoded: CxfCredentialCodec.Decoded,
        destination: ImportDestination,
    ): Result<Int> = targetedImport {
        targetedImporter.importExchange(decoded, destination)
    }

    private suspend fun targetedImport(block: suspend () -> ImportResultSummary): Result<Int> = withContext(Dispatchers.IO) {
        _lastImportSummary.value = null
        importProgressReporter.report(TransferProgress(TransferPhase.READING))
        try {
            val summary = block()
            _lastImportSummary.value = summary
            DatabaseExportJobs.reportImportSummary(summary)
            logImportSummary("TARGETED_IMPORT", summary.imported, summary.skipped, summary.failed)
            Result.success(summary.imported)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    private val customFieldRepository by lazy {
        CustomFieldRepository(PasswordDatabase.getDatabase(context).customFieldDao())
    }

    init {
        SteamDiagLogger.initialize(context.applicationContext)
    }

    private fun parseStoredTotpData(itemData: String, fallbackIssuer: String = ""): TotpData? {
        return TotpDataResolver.parseStoredItemData(
            itemData = itemData,
            fallbackIssuer = fallbackIssuer,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    private fun parseStoredTotpData(item: SecureItem): TotpData? {
        return parseStoredTotpData(item.itemData, item.title)
    }

    private fun encodeTotpDataPreservingStorageShape(originalItemData: String, data: TotpData): String {
        val plainJson = Json.encodeToString(data)
        return if (securityManager.looksLikeMonicaCiphertext(originalItemData)) {
            securityManager.encryptDataLegacyCompat(plainJson)
        } else {
            plainJson
        }
    }

    private fun encodeSecureItemDataForLocalStorage(itemType: ItemType, itemData: String): String {
        if (itemData.isBlank()) return itemData
        if (
            itemType != ItemType.TOTP &&
            itemType != ItemType.BANK_CARD &&
            itemType != ItemType.DOCUMENT
        ) {
            return itemData
        }
        if (securityManager.looksLikeMonicaCiphertext(itemData)) {
            return itemData
        }
        return securityManager.encryptDataLegacyCompat(itemData)
    }

    private fun validatePlainZipFile(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException(strings.get(R.string.export_message_empty))
        }
        ZipFile(file).use { zip ->
            if (!zip.entries().hasMoreElements()) {
                throw IOException(strings.get(R.string.export_message_empty_zip))
            }
        }
    }

    private fun validatePlainZipStream(input: InputStream) {
        ZipInputStream(input).use { zip ->
            if (zip.nextEntry == null) {
                throw IOException(strings.get(R.string.export_message_empty_zip))
            }
        }
    }

    private fun validatePreparedBackupFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException(strings.get(R.string.export_message_empty))
        }
        val encrypted = EncryptionHelper.hasEncryptedFileHeader(file)
        if (!encrypted) {
            validatePlainZipFile(file)
        }
        return encrypted
    }

    private fun openExportOutputStream(uri: Uri): OutputStream {
        val resolver = context.contentResolver
        val modes = listOf("wt", "rwt", "w")
        var lastError: Throwable? = null
        modes.forEach { mode ->
            try {
                val stream = resolver.openOutputStream(uri, mode)
                if (stream != null) {
                    return stream
                }
            } catch (error: Throwable) {
                lastError = error
                android.util.Log.w("DataExport", "openOutputStream($mode) failed: ${error.message}")
            }
        }
        try {
            val stream = resolver.openOutputStream(uri)
            if (stream != null) {
                return stream
            }
        } catch (error: Throwable) {
            lastError = error
            android.util.Log.w("DataExport", "openOutputStream(default) failed: ${error.message}")
        }
        throw IOException(strings.get(R.string.export_message_open_failed), lastError)
    }

    private suspend fun copyZipFileToOutputUri(zipFile: File, outputUri: Uri,
        progress: TransferProgressReporter = TransferProgressReporter.None): Long = withContext(Dispatchers.IO) {
        val encrypted = validatePreparedBackupFile(zipFile)
        val expectedBytes = zipFile.length()
        val copiedBytes = openExportOutputStream(outputUri).use { output ->
            zipFile.inputStream().use { input ->
                input.copyWithProgress(output, TransferPhase.SAVING, expectedBytes, progress)
            }.also {
                output.flush()
            }
        }
        if (copiedBytes <= 0L) {
            throw IOException(strings.get(R.string.export_message_empty))
        }
        if (expectedBytes > 0L && copiedBytes != expectedBytes) {
            throw IOException(strings.get(R.string.export_message_incomplete, copiedBytes, expectedBytes))
        }
        context.contentResolver.openInputStream(outputUri)?.use { input ->
            if (encrypted) {
                if (!EncryptionHelper.hasEncryptedFileHeader(input)) {
                    throw IOException(strings.get(R.string.export_message_invalid_encrypted))
                }
            } else {
                validatePlainZipStream(input)
            }
        } ?: throw IOException(strings.get(R.string.export_message_verify_failed))
        copiedBytes
    }

    private suspend fun copyPlainFileToOutputUri(
        sourceFile: File,
        outputUri: Uri,
        validateOutput: ((InputStream) -> Unit)? = null,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Long = withContext(Dispatchers.IO) {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            throw IOException(strings.get(R.string.export_message_empty))
        }
        val expectedBytes = sourceFile.length()
        val copiedBytes = openExportOutputStream(outputUri).use { output ->
            sourceFile.inputStream().use { input ->
                input.copyWithProgress(output, TransferPhase.SAVING, expectedBytes, progress)
            }.also {
                output.flush()
            }
        }
        if (copiedBytes <= 0L) {
            throw IOException(strings.get(R.string.export_message_empty))
        }
        if (expectedBytes > 0L && copiedBytes != expectedBytes) {
            throw IOException(strings.get(R.string.export_message_incomplete, copiedBytes, expectedBytes))
        }
        validateOutput?.let { validator ->
            context.contentResolver.openInputStream(outputUri)?.use(validator)
                ?: throw IOException(strings.get(R.string.export_message_verify_failed))
        }
        copiedBytes
    }

    private fun zipBackupExportMessage(report: BackupReport): String =
        backupExportMessage(report, strings)

    sealed class SteamLoginImportState {
        data class ChallengeRequired(
            val pendingSessionId: String,
            val steamId: String,
            val challenges: List<SteamLoginImportService.SteamGuardChallenge>,
            val message: String? = null
        ) : SteamLoginImportState()

        data class Imported(
            val count: Int
        ) : SteamLoginImportState()

        data class Failure(
            val message: String
        ) : SteamLoginImportState()
    }

    /**
     * 导入数据
     */
    suspend fun importData(
        inputUri: Uri,
        formatHint: DataExportImportManager.CsvFormat? = null,
        passwordKeyboardTagHandling: DataExportImportManager.PasswordKeyboardTagHandling =
            DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD,
        destination: ImportDestination? = null
    ): Result<Int> {
        if (destination != null) return targetedImport {
            val report = exportManager.importDataWithReport(inputUri, formatHint, passwordKeyboardTagHandling).getOrThrow()
            targetedImporter.apply(csvImportContent(report.items), destination, parseFailures = report.rejectedCount)
        }
        return try {
            // 导入数据
            val result = exportManager.importData(
                inputUri = inputUri,
                formatHint = formatHint,
                passwordKeyboardTagHandling = passwordKeyboardTagHandling
            )
            
            result.fold(
                onSuccess = { items ->
                    android.util.Log.d("DataImport", "ViewModel收到 ${items.size} 条导入数据")
                    var count = 0
                    var errorCount = 0
                    var skippedCount = 0
                    
                    // Separate items into Passwords and Others
                    val passwordItems = items.filter { ItemType.valueOf(it.itemType) == ItemType.PASSWORD }
                    val otherItems = items.filter { ItemType.valueOf(it.itemType) != ItemType.PASSWORD }
                    
                    val passwordIdMap = mutableMapOf<Long, Long>() // Old ID -> New ID (or existing ID)
                    
                    // 1. Process Passwords First
                    passwordItems.forEach { exportItem ->
                        try {
                            // PASSWORD类型存入PasswordEntry表
                            val passwordData = parsePasswordData(exportItem.itemData)
                            val website = passwordData["website"] ?: ""
                            val username = passwordData["username"] ?: ""
                            val importedPassword = passwordData["password"].orEmpty()
                            val storedPassword = prepareImportedPasswordForStorage(importedPassword)
                            val importedAuthenticator = parseImportedAuthenticatorDraft(
                                rawAuthenticator = exportItem.importedAuthenticatorKey,
                                fallbackTitle = exportItem.title,
                                fallbackWebsite = website,
                                fallbackUsername = username
                            )
                            val originalId = exportItem.id
                            
                            // 来自 Monica 自身导出的 CSV，直接恢复，跳过重复检测
                            val existingEntry = if (exportItem.isFromAppExport) {
                                null
                            } else {
                                PasswordImportDuplicateResolver.findMatchingEntry(
                                    passwordRepository = passwordRepository,
                                    securityManager = securityManager,
                                    snapshot = ImportedPasswordSnapshot(
                                        title = exportItem.title,
                                        username = username,
                                        website = website,
                                        password = importedPassword,
                                        notes = exportItem.notes,
                                        email = passwordData["email"] ?: "",
                                        phone = passwordData["phone"] ?: "",
                                        authenticatorKey = importedAuthenticator?.authenticatorKey.orEmpty()
                                    ),
                                    localOnly = false
                                )
                            }
                            
                            if (existingEntry == null) {
                                val passwordEntry = PasswordEntry(
                                    id = 0, // 让数据库自动生成新ID
                                    title = exportItem.title,
                                    website = website,
                                    username = username,
                                    password = storedPassword,
                                    notes = exportItem.notes,
                                    email = passwordData["email"] ?: "",
                                    phone = passwordData["phone"] ?: "",
                                    categoryId = exportItem.categoryId,
                                    isFavorite = exportItem.isFavorite,
                                    createdAt = Date(exportItem.createdAt),
                                    updatedAt = Date(exportItem.updatedAt),
                                    keepassDatabaseId = exportItem.keepassDatabaseId,
                                    keepassGroupPath = exportItem.keepassGroupPath,
                                    authenticatorKey = importedAuthenticator?.authenticatorKey.orEmpty(),
                                    bitwardenVaultId = exportItem.bitwardenVaultId,
                                    bitwardenFolderId = exportItem.bitwardenFolderId
                                )
                                val newId = passwordRepository.insertPasswordEntry(passwordEntry)
                                if (originalId > 0 && newId > 0) {
                                    passwordIdMap[originalId] = newId
                                }
                                if (newId > 0 && exportItem.importedCustomFields.isNotEmpty()) {
                                    saveImportedCustomFields(newId, exportItem.importedCustomFields)
                                }
                                if (newId > 0 && importedAuthenticator != null) {
                                    saveImportedAuthenticator(newId, exportItem, importedAuthenticator)
                                }
                                android.util.Log.d("DataImport", "成功插入到PasswordEntry表")
                                count++
                            } else {
                                android.util.Log.d("DataImport", "跳过重复密码")
                                if (originalId > 0) {
                                    passwordIdMap[originalId] = existingEntry.id
                                }
                                skippedCount++
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("DataImport", "插入密码失败: ${e.message}", e)
                        }
                    }
                    
                    // 2. Process Other Items (SecureItems) and update bindings
                    otherItems.forEach { exportItem ->
                        try {
                            android.util.Log.d("DataImport", "处理项类型: ${exportItem.itemType}")
                            val itemType = ItemType.valueOf(exportItem.itemType)
                            
                            // 其他类型存入SecureItem表
                            // 使用智能重复检测：根据类型比较不同的唯一标识字段
                            val existingItem = secureItemRepository.findDuplicateSecureItem(
                                itemType,
                                exportItem.itemData,
                                exportItem.title
                            )
                            val isDuplicate = existingItem != null
                            
                            if (!isDuplicate) {
                                var itemData = exportItem.itemData
                                
                                // Update TOTP binding if applicable
                                if (itemType == ItemType.TOTP) {
                                    try {
                                        val totpData = parseStoredTotpData(itemData, exportItem.title)
                                            ?: throw IllegalArgumentException("Unable to parse TOTP data")
                                        val originalBoundId = totpData.boundPasswordId
                                        if (originalBoundId != null && originalBoundId > 0) {
                                            val newBoundId = passwordIdMap[originalBoundId]
                                            if (newBoundId != null) {
                                                val updatedTotpData = totpData.copy(boundPasswordId = newBoundId)
                                                itemData = encodeTotpDataPreservingStorageShape(itemData, updatedTotpData)
                                                android.util.Log.d("DataImport", "Updated TOTP boundPasswordId from $originalBoundId to $newBoundId")
                                            } else {
                                                android.util.Log.w("DataImport", "Could not map password ID for TOTP: oldId=$originalBoundId")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("DataImport", "Failed to parse/update TOTP data: ${e.message}")
                                    }
                                }
                                
                                val secureItem = SecureItem(
                                    id = 0, // 让数据库自动生成新ID
                                    itemType = itemType,
                                    title = exportItem.title,
                                    itemData = encodeSecureItemDataForLocalStorage(itemType, itemData),
                                    notes = exportItem.notes,
                                    isFavorite = exportItem.isFavorite,
                                    imagePaths = exportItem.imagePaths,
                                    createdAt = Date(exportItem.createdAt),
                                    updatedAt = Date(exportItem.updatedAt),
                                    categoryId = exportItem.categoryId,
                                    keepassDatabaseId = exportItem.keepassDatabaseId,
                                    keepassGroupPath = exportItem.keepassGroupPath,
                                    bitwardenVaultId = exportItem.bitwardenVaultId,
                                    bitwardenFolderId = exportItem.bitwardenFolderId
                                )
                                secureItemRepository.insertItem(secureItem)
                                android.util.Log.d("DataImport", "成功插入到SecureItem表")
                                count++
                            } else {
                                if (itemType == ItemType.NOTE) {
                                    val merged = mergeImportedNoteTagsIfNeeded(
                                        existingItem = existingItem,
                                        incoming = exportItem
                                    )
                                    if (merged) {
                                        android.util.Log.d("DataImport", "重复笔记已合并标签")
                                        count++
                                    } else {
                                        android.util.Log.d("DataImport", "跳过重复项")
                                        skippedCount++
                                    }
                                } else {
                                    android.util.Log.d("DataImport", "跳过重复项")
                                    skippedCount++
                                }
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("DataImport", "插入数据库失败: ${e.message}", e)
                        }
                    }
                    
                    android.util.Log.d("DataImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                    logImportSummary(
                        source = formatHint?.name ?: "CSV_AUTO",
                        importedCount = count,
                        skippedCount = skippedCount,
                        failedCount = errorCount
                    )
                    Result.success(count)
                },
                onFailure = { error ->
                    android.util.Log.e("DataImport", "导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导入KeePass CSV文件
     */
    suspend fun importKeePassCsv(inputUri: Uri, destination: ImportDestination? = null): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.KEEPASS_PASSWORD, destination = destination)
    }

    /**
     * 导入Bitwarden CSV文件
     */
    suspend fun importBitwardenCsv(inputUri: Uri, destination: ImportDestination? = null): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.BITWARDEN_PASSWORD, destination = destination)
    }

    suspend fun importBitwardenAuthenticatorCsv(
        inputUri: Uri,
        destination: ImportDestination = ImportDestination.Local,
    ): Result<Int> = targetedImport {
        val report = exportManager.importBitwardenAuthenticatorCsv(inputUri).getOrThrow()
        targetedImporter.apply(csvImportContent(report.items), destination, parseFailures = report.rejectedCount)
    }

    /**
     * 导入 Proton Pass CSV 文件
     */
    suspend fun importProtonPassCsv(inputUri: Uri, destination: ImportDestination? = null): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.PROTON_PASS_PASSWORD, destination = destination)
    }

    /**
     * 导入Chrome CSV文件
     */
    suspend fun importChromeCsv(inputUri: Uri, destination: ImportDestination? = null): Result<Int> {
        return importData(inputUri, DataExportImportManager.CsvFormat.CHROME_PASSWORD, destination = destination)
    }

    /**
     * 导入密码键盘软件 CSV 文件
     */
    suspend fun importPasswordKeyboardCsv(
        inputUri: Uri,
        tagHandling: DataExportImportManager.PasswordKeyboardTagHandling =
            DataExportImportManager.PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD,
        destination: ImportDestination? = null
    ): Result<Int> {
        return importData(
            inputUri = inputUri,
            formatHint = DataExportImportManager.CsvFormat.PASSWORD_KEYBOARD,
            passwordKeyboardTagHandling = tagHandling,
            destination = destination
        )
    }

    private fun csvImportContent(items: List<DataExportImportManager.ExportItem>): BackupContent {
        val passwords = mutableListOf<PasswordEntry>()
        val secureItems = mutableListOf<DataExportImportManager.ExportItem>()
        val fields = mutableMapOf<Long, List<CustomFieldBackupEntry>>()
        items.forEachIndexed { index, item ->
            val id = item.id.takeIf { it > 0 } ?: -(index + 1L)
            if (item.itemType != ItemType.PASSWORD.name) {
                secureItems += item.copy(id = id, keepassDatabaseId = null, keepassGroupPath = null,
                    bitwardenVaultId = null, bitwardenFolderId = null, categoryId = null)
            } else {
                val data = parsePasswordData(item.itemData)
                val authenticator = parseImportedAuthenticatorDraft(item.importedAuthenticatorKey,
                    item.title, data["website"].orEmpty(), data["username"].orEmpty())
                passwords += PasswordEntry(
                    id = id, title = item.title, website = data["website"].orEmpty(),
                    username = data["username"].orEmpty(),
                    password = data["password"].orEmpty().let { if (item.isFromAppExport) it else securityManager.encryptData(it) },
                    email = data["email"].orEmpty(), phone = data["phone"].orEmpty(), notes = item.notes,
                    isFavorite = item.isFavorite, createdAt = Date(item.createdAt), updatedAt = Date(item.updatedAt),
                    authenticatorKey = authenticator?.authenticatorKey.orEmpty(),
                )
                fields[id] = item.importedCustomFields.map { CustomFieldBackupEntry(it.title, it.value, it.isProtected) }
                authenticator?.totpData?.let { totp ->
                    secureItems += DataExportImportManager.ExportItem(
                        id = -(items.size + index + 1L), itemType = ItemType.TOTP.name,
                        title = buildImportedAuthenticatorTitle(item.title, totp),
                        itemData = Json.encodeToString(totp.copy(boundPasswordId = id, categoryId = null, keepassDatabaseId = null)),
                        notes = "", isFavorite = item.isFavorite, imagePaths = "",
                        createdAt = item.createdAt, updatedAt = item.updatedAt,
                    )
                }
            }
        }
        return BackupContent(passwords, secureItems, customFieldsMap = fields)
    }

    private fun totpImportContent(entries: List<DataExportImportManager.AegisEntry>): BackupContent {
        val now = System.currentTimeMillis()
        return BackupContent(emptyList(), entries.mapIndexed { index, entry ->
            val totp = TotpData(secret = entry.secret, issuer = entry.issuer, accountName = entry.name,
                digits = entry.digits, period = entry.period, algorithm = entry.algorithm)
            DataExportImportManager.ExportItem(
                id = index + 1L, itemType = ItemType.TOTP.name,
                title = buildImportedAuthenticatorTitle(entry.name, totp), itemData = Json.encodeToString(totp),
                notes = entry.note, isFavorite = false, imagePaths = "", createdAt = now, updatedAt = now,
            )
        })
    }

    private suspend fun saveImportedCustomFields(
        entryId: Long,
        importedFields: List<DataExportImportManager.ImportedCustomField>
    ) {
        val normalized = importedFields
            .map {
                it.copy(
                    title = it.title.trim(),
                    value = it.value
                )
            }
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.title.lowercase() to it.value }

        if (normalized.isEmpty()) return

        val customFields = normalized.mapIndexed { index, field ->
            CustomField(
                id = 0,
                entryId = entryId,
                title = field.title,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }

        try {
            customFieldRepository.insertFields(customFields)
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "保存导入自定义字段失败: entryId=$entryId", e)
        }
    }

    private fun parseImportedAuthenticatorDraft(
        rawAuthenticator: String?,
        fallbackTitle: String,
        fallbackWebsite: String,
        fallbackUsername: String
    ): ImportedAuthenticatorDraft? {
        val raw = rawAuthenticator?.trim().orEmpty()
        if (raw.isBlank()) return null

        val fallbackIssuer = fallbackWebsite.ifBlank { fallbackTitle }
        val fallbackAccount = fallbackUsername.ifBlank { fallbackTitle }

        if (raw.contains("://")) {
            val parsed = TotpUriParser.parseUri(raw)?.totpData
            if (parsed == null) {
                android.util.Log.w("DataImport", "密码键盘验证器 URI 无法解析，已仅保留到密码验证器字段")
                return ImportedAuthenticatorDraft(
                    authenticatorKey = raw,
                    totpData = null
                )
            }

            val normalizedSecret = normalizeTotpSecret(parsed.secret)
            if (normalizedSecret.isBlank()) return null

            return ImportedAuthenticatorDraft(
                authenticatorKey = normalizedSecret,
                totpData = parsed.copy(
                    secret = normalizedSecret,
                    issuer = parsed.issuer.ifBlank { fallbackIssuer },
                    accountName = parsed.accountName.ifBlank { fallbackAccount }
                )
            )
        }

        val normalizedSecret = normalizeTotpSecret(raw)
        if (normalizedSecret.isBlank()) return null

        return ImportedAuthenticatorDraft(
            authenticatorKey = normalizedSecret,
            totpData = TotpData(
                secret = normalizedSecret,
                issuer = fallbackIssuer,
                accountName = fallbackAccount
            )
        )
    }

    private suspend fun saveImportedAuthenticator(
        entryId: Long,
        exportItem: DataExportImportManager.ExportItem,
        importedAuthenticator: ImportedAuthenticatorDraft
    ) {
        val baseTotpData = importedAuthenticator.totpData ?: return
        val totpData = baseTotpData.copy(
            boundPasswordId = entryId,
            categoryId = exportItem.categoryId,
            keepassDatabaseId = exportItem.keepassDatabaseId
        )
        val itemData = Json.encodeToString(totpData)
        val title = buildImportedAuthenticatorTitle(exportItem.title, totpData)

        try {
            val existingItem = secureItemRepository.findDuplicateSecureItem(
                itemType = ItemType.TOTP,
                itemData = itemData,
                title = title
            )
            if (existingItem != null) {
                return
            }

            val secureItem = SecureItem(
                id = 0,
                itemType = ItemType.TOTP,
                title = title,
                itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                notes = "",
                isFavorite = exportItem.isFavorite,
                imagePaths = "",
                createdAt = Date(exportItem.createdAt),
                updatedAt = Date(exportItem.updatedAt),
                categoryId = exportItem.categoryId,
                keepassDatabaseId = exportItem.keepassDatabaseId,
                keepassGroupPath = exportItem.keepassGroupPath,
                bitwardenVaultId = exportItem.bitwardenVaultId,
                bitwardenFolderId = exportItem.bitwardenFolderId
            )
            secureItemRepository.insertItem(secureItem)
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "保存导入验证器失败: entryId=$entryId", e)
        }
    }

    private fun buildImportedAuthenticatorTitle(passwordTitle: String, totpData: TotpData): String {
        return takagi.ru.monica.util.suggestedTotpTitle(totpData, passwordTitle)
    }

    private fun normalizeTotpSecret(secret: String): String {
        return secret
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .uppercase(Locale.ROOT)
    }

    private suspend fun mergeImportedNoteTagsIfNeeded(
        existingItem: SecureItem,
        incoming: DataExportImportManager.ExportItem
    ): Boolean {
        val incomingDecoded = NoteContentCodec.decode(
            itemData = incoming.itemData,
            fallbackNotes = incoming.notes
        )
        val incomingTags = normalizeNoteTags(incomingDecoded.tags)
        if (incomingTags.isEmpty()) return false

        val existingDecoded = NoteContentCodec.decode(
            itemData = existingItem.itemData,
            fallbackNotes = existingItem.notes
        )
        val existingTags = normalizeNoteTags(existingDecoded.tags)
        val mergedTags = normalizeNoteTags(existingTags + incomingTags)

        if (mergedTags == existingTags) {
            return false
        }

        val encoded = NoteContentCodec.encode(
            content = existingDecoded.content,
            tags = mergedTags,
            isMarkdown = existingDecoded.isMarkdown
        )

        secureItemRepository.updateItem(
            existingItem.copy(
                itemData = encoded.first,
                notes = encoded.second,
                updatedAt = Date()
            )
        )
        return true
    }

    private fun normalizeNoteTags(tags: List<String>): List<String> {
        return tags.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }
    
    /**
     * 解析密码数据字符串
     * 格式: username:xxx;password:xxx;email:xxx;url:xxx
     */
    private fun parsePasswordData(data: String): Map<String, String> = CsvPasswordData.decode(data)

    private fun prepareImportedPasswordForStorage(password: String): String {
        if (password.isBlank()) return password
        if (isMonicaEncryptedValue(password)) return password
        return securityManager.encryptDataLegacyCompat(password)
    }

    private fun isMonicaEncryptedValue(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("MDK|") ||
            trimmed.startsWith("V2|") ||
            trimmed.startsWith("C2|")
    }

    /**
     * 导入Aegis JSON文件
     */
    suspend fun importAegisJson(inputUri: Uri, destination: ImportDestination? = null): Result<Int> {
        if (destination != null) return targetedImport {
            val entries = exportManager.importAegisJson(inputUri).getOrThrow()
            targetedImporter.apply(totpImportContent(entries), destination)
        }
        return try {
            // 首先检查是否为加密文件
            val isEncryptedResult = exportManager.isEncryptedAegisFile(inputUri)
            if (isEncryptedResult.getOrDefault(false)) {
                // 如果是加密文件，返回错误提示
                Result.failure(Exception(strings.get(R.string.import_message_aegis_unencrypted)))
            } else {
                // 处理未加密的Aegis文件
                val result = exportManager.importAegisJson(inputUri)
                
                result.fold(
                    onSuccess = { entries ->
                        android.util.Log.d("AegisImport", "ViewModel收到 ${entries.size} 条Aegis条目")
                        var count = 0
                        var errorCount = 0
                        var skippedCount = 0
                        
                        entries.forEach { aegisEntry ->
                            try {
                                // 检查是否已存在相同的条目（基于issuer和name）
                                val existingItems = secureItemRepository.getItemsByType(ItemType.TOTP).first()
                                val isDuplicate = existingItems.any { item ->
                                    try {
                                        val totpData = parseStoredTotpData(item) ?: return@any false
                                        totpData.issuer == aegisEntry.issuer && totpData.accountName == aegisEntry.name
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                                
                                if (isDuplicate) {
                                    android.util.Log.d("AegisImport", "跳过重复条目")
                                    skippedCount++
                                } else {
                                    // 创建新的TOTP条目
                                    val totpData = TotpData(
                                        secret = aegisEntry.secret,
                                        issuer = aegisEntry.issuer,
                                        accountName = aegisEntry.name,
                                        period = aegisEntry.period,
                                        digits = aegisEntry.digits,
                                        algorithm = aegisEntry.algorithm
                                    )
                                    
                                    val itemData = Json.encodeToString(totpData)
                                    val title = takagi.ru.monica.util.suggestedTotpTitle(totpData)
                                    
                                    val secureItem = SecureItem(
                                        id = 0,
                                        itemType = ItemType.TOTP,
                                        title = title,
                                        itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                                        notes = aegisEntry.note,
                                        isFavorite = false,
                                        imagePaths = "",
                                        createdAt = Date(),
                                        updatedAt = Date()
                                    )
                                    
                                    secureItemRepository.insertItem(secureItem)
                                    count++
                                    android.util.Log.d("AegisImport", "成功插入TOTP条目")
                                }
                            } catch (e: Exception) {
                                errorCount++
                                android.util.Log.e("AegisImport", "插入数据库失败: ${e.message}", e)
                            }
                        }
                        
                        android.util.Log.d("AegisImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                        logImportSummary(
                            source = "AEGIS_JSON",
                            importedCount = count,
                            skippedCount = skippedCount,
                            failedCount = errorCount
                        )
                        Result.success(count)
                    },
                    onFailure = { error ->
                        android.util.Log.e("AegisImport", "导入失败: ${error.message}", error)
                        Result.failure(error)
                    }
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AegisImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导入加密的Aegis JSON文件
     */
    suspend fun importEncryptedAegisJson(inputUri: Uri, password: String, destination: ImportDestination? = null): Result<Int> {
        if (destination != null) return targetedImport {
            val entries = exportManager.importEncryptedAegisJson(inputUri, password).getOrThrow()
            targetedImporter.apply(totpImportContent(entries), destination)
        }
        return try {
            val result = exportManager.importEncryptedAegisJson(inputUri, password)
            
            result.fold(
                onSuccess = { entries ->
                    android.util.Log.d("EncryptedAegisImport", "ViewModel收到 ${entries.size} 条Aegis条目")
                    var count = 0
                    var errorCount = 0
                    var skippedCount = 0
                    
                    entries.forEach { aegisEntry ->
                        try {
                            // 检查是否已存在相同的条目（基于issuer和name）
                            val existingItems = secureItemRepository.getItemsByType(ItemType.TOTP).first()
                            val isDuplicate = existingItems.any { item ->
                                try {
                                    val totpData = parseStoredTotpData(item) ?: return@any false
                                    totpData.issuer == aegisEntry.issuer && totpData.accountName == aegisEntry.name
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            
                            if (isDuplicate) {
                                android.util.Log.d("EncryptedAegisImport", "跳过重复条目")
                                skippedCount++
                            } else {
                                // 创建新的TOTP条目
                                val totpData = TotpData(
                                    secret = aegisEntry.secret,
                                    issuer = aegisEntry.issuer,
                                    accountName = aegisEntry.name,
                                    period = aegisEntry.period,
                                    digits = aegisEntry.digits,
                                    algorithm = aegisEntry.algorithm
                                )
                                
                                val itemData = Json.encodeToString(totpData)
                                val title = takagi.ru.monica.util.suggestedTotpTitle(totpData)
                                
                                val secureItem = SecureItem(
                                    id = 0,
                                    itemType = ItemType.TOTP,
                                    title = title,
                                    itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
                                    notes = aegisEntry.note,
                                    isFavorite = false,
                                    imagePaths = "",
                                    createdAt = Date(),
                                    updatedAt = Date()
                                )
                                
                                secureItemRepository.insertItem(secureItem)
                                count++
                                android.util.Log.d("EncryptedAegisImport", "成功插入TOTP条目")
                            }
                        } catch (e: Exception) {
                            errorCount++
                            android.util.Log.e("EncryptedAegisImport", "插入数据库失败: ${e.message}", e)
                        }
                    }
                    
                    android.util.Log.d("EncryptedAegisImport", "导入完成: 成功=$count, 跳过=$skippedCount, 失败=$errorCount")
                    logImportSummary(
                        source = "AEGIS_JSON_ENCRYPTED",
                        importedCount = count,
                        skippedCount = skippedCount,
                        failedCount = errorCount
                    )
                    Result.success(count)
                },
                onFailure = { error ->
                    android.util.Log.e("EncryptedAegisImport", "导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("EncryptedAegisImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 导入Steam maFile
     */
    suspend fun importSteamMaFile(inputUri: Uri): Result<Int> {
        return try {
            exportManager.importSteamMaFileWithMetadata(inputUri).fold(
                onSuccess = { steamEntry ->
                    insertSteamGuardEntry(steamEntry)
                },
                onFailure = { error ->
                    android.util.Log.e("SteamImport", "导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("SteamImport", "导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 导入 Steam App 共存令牌（设备ID + SteamGuard JSON）
     */
    suspend fun importSteamAppCoexist(
        deviceIdInput: String,
        steamGuardJson: String,
        customName: String?
    ): Result<Int> {
        return try {
            exportManager.importSteamAppCoexist(deviceIdInput, steamGuardJson, customName).fold(
                onSuccess = { steamEntry ->
                    insertSteamGuardEntry(steamEntry)
                },
                onFailure = { error ->
                    android.util.Log.e("SteamImport", "共存导入失败: ${error.message}", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("SteamImport", "共存导入异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Steam 登录导入（第一阶段）：账号密码登录并返回挑战状态或 token
     */
    suspend fun beginSteamLoginImport(
        userName: String,
        password: String,
        customName: String? = null
    ): SteamLoginImportState {
        val result = steamLoginImportService.beginLogin(userName, password)
        return consumeSteamLoginResult(result, customName)
    }

    /**
     * Steam 登录导入（第一阶段）：提交挑战验证码
     */
    suspend fun submitSteamLoginImportCode(
        pendingSessionId: String,
        code: String,
        confirmationType: Int,
        customName: String? = null
    ): SteamLoginImportState {
        val result = steamLoginImportService.submitSteamGuardCode(
            pendingSessionId = pendingSessionId,
            code = code,
            confirmationType = confirmationType
        )
        return consumeSteamLoginResult(result, customName)
    }

    fun clearSteamLoginImportSession(sessionId: String) {
        steamLoginImportService.clearPendingSession(sessionId)
    }

    private suspend fun consumeSteamLoginResult(
        result: SteamLoginImportService.LoginResult,
        customName: String?
    ): SteamLoginImportState {
        return when (result) {
            is SteamLoginImportService.LoginResult.ChallengeRequired -> {
                SteamLoginImportState.ChallengeRequired(
                    pendingSessionId = result.pendingSessionId,
                    steamId = result.steamId,
                    challenges = result.challenges,
                    message = result.message
                )
            }

            is SteamLoginImportService.LoginResult.ReadyForImport -> {
                val importResult = importSteamAppCoexist(
                    deviceIdInput = result.payload.deviceId,
                    steamGuardJson = result.payload.steamGuardJson,
                    customName = customName
                )
                importResult.fold(
                    onSuccess = { count ->
                        SteamLoginImportState.Imported(count = count)
                    },
                    onFailure = { error ->
                        SteamLoginImportState.Failure(error.message ?: strings.get(R.string.import_data_error))
                    }
                )
            }

            is SteamLoginImportService.LoginResult.Failure -> {
                SteamLoginImportState.Failure(result.message)
            }
        }
    }

    private suspend fun insertSteamGuardEntry(
        steamEntry: DataExportImportManager.SteamGuardImportEntry
    ): Result<Int> {
        val existingItems = secureItemRepository.getItemsByType(ItemType.TOTP).first()
        val normalizedName = steamEntry.name.trim()
        val isDuplicate = existingItems.any { item ->
            try {
                val totpData = parseStoredTotpData(item) ?: return@any false
                if (totpData.otpType != takagi.ru.monica.data.model.OtpType.STEAM) {
                    return@any false
                }

                if (totpData.steamFingerprint.isNotBlank() &&
                    totpData.steamFingerprint == steamEntry.fingerprint
                ) {
                    return@any true
                }

                totpData.secret == steamEntry.secretBase32 &&
                    totpData.issuer.equals(steamEntry.issuer, ignoreCase = true) &&
                    totpData.accountName.trim().equals(normalizedName, ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        if (isDuplicate) {
            android.util.Log.d("SteamImport", "跳过重复条目")
            return Result.failure(Exception(strings.get(R.string.import_message_steam_exists)))
        }

        val totpData = TotpData(
            secret = steamEntry.secretBase32,
            issuer = steamEntry.issuer,
            accountName = steamEntry.name,
            period = 30,
            digits = 5,
            algorithm = "SHA1",
            otpType = takagi.ru.monica.data.model.OtpType.STEAM,
            steamFingerprint = steamEntry.fingerprint,
            steamDeviceId = steamEntry.deviceId,
            steamSerialNumber = steamEntry.serialNumber,
            steamSharedSecretBase64 = steamEntry.sharedSecretBase64,
            steamRevocationCode = steamEntry.revocationCode,
            steamIdentitySecret = steamEntry.identitySecret,
            steamTokenGid = steamEntry.tokenGid,
            steamRawJson = steamEntry.rawSteamGuardJson
        )

        val itemData = Json.encodeToString(totpData)
        val title = if (steamEntry.name.isNotBlank()) {
            "Steam: ${steamEntry.name}"
        } else {
            "Steam Guard"
        }

        val secureItem = SecureItem(
            id = 0,
            itemType = ItemType.TOTP,
            title = title,
            itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData),
            notes = "",
            isFavorite = false,
            imagePaths = "",
            createdAt = Date(),
            updatedAt = Date()
        )

        secureItemRepository.insertItem(secureItem)
        android.util.Log.d("SteamImport", "成功插入Steam Guard")
        logImportSummary(
            source = "STEAM_GUARD",
            importedCount = 1
        )
        return Result.success(1)
    }

    /**
     * 导出完整备份 (ZIP格式，WebDAV兼容)
     * @param outputUri 导出文件的URI
     * @param preferences 备份偏好设置（选择要导出的内容类型）
     */
    suspend fun prepareZipBackup(
        preferences: takagi.ru.monica.data.BackupPreferences = takagi.ru.monica.data.BackupPreferences(),
        backupEncryptionPassword: String? = null,
        source: ImportDestination = ImportDestination.Local,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<Pair<File, String>> = withContext(Dispatchers.IO) {
        try {
            Result.success(DatabaseArchiveExporter(context).prepare(source, preferences, backupEncryptionPassword, progress))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun writePreparedZipBackup(
        outputUri: Uri,
        zipFile: File,
        successMessage: String,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<String> {
        return try {
            copyZipFileToOutputUri(zipFile, outputUri, progress)
            Result.success(successMessage)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("DataExport", "写入ZIP失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun exportZipBackup(
        outputUri: Uri,
        preferences: takagi.ru.monica.data.BackupPreferences = takagi.ru.monica.data.BackupPreferences(),
        backupEncryptionPassword: String? = null,
        source: ImportDestination = ImportDestination.Local,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<String> {
        var preparedFile: File? = null
        return try {
            val (zipFile, message) = prepareZipBackup(
                preferences = preferences,
                backupEncryptionPassword = backupEncryptionPassword,
                source = source,
                progress = progress,
            ).getOrThrow()
            preparedFile = zipFile
            writePreparedZipBackup(outputUri, zipFile, message, progress)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("DataExport", "导出ZIP失败: ${e.message}", e)
            Result.failure(e)
        } finally {
            preparedFile?.delete()
        }
    }

    suspend fun exportKdbxBackup(
        uri: Uri, password: String, source: ImportDestination,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<String> = withContext(Dispatchers.IO) {
        var prepared: File? = null
        try {
            val (file, count) = takagi.ru.monica.transfer.DatabaseKdbxExporter(context).prepare(source, password, progress)
            prepared = file
            copyPlainFileToOutputUri(file, uri, progress = progress)
            Result.success(context.getString(R.string.transfer_export_complete, count.toLong()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        finally { prepared?.delete() }
    }

    suspend fun exportSteamMaFile(
        uri: Uri, accountIds: Set<Long>, source: ImportDestination,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<String> {
        var prepared: PreparedSteamMaFileExport? = null
        return try {
            progress.report(TransferProgress(TransferPhase.PREPARING))
            val export = prepareSteamMaFileExport(accountIds, source).getOrThrow()
            prepared = export
            writePreparedSteamMaFileExport(uri, export, progress)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        finally { prepared?.file?.delete() }
    }

    suspend fun loadSteamMaFileExportCandidates(source: ImportDestination = ImportDestination.Local): Result<List<SteamMaFileExportCandidate>> = withContext(Dispatchers.IO) {
        try {
            val accounts = takagi.ru.monica.transfer.DatabaseExportSnapshotLoader(context).loadSteamAccounts(source)
            val candidates = accounts
                .filter { account -> account.sharedSecret.isNotBlank() }
                .map { account ->
                    val title = account.displayName
                        .ifBlank { account.accountName }
                        .ifBlank { "Steam" }
                    val subtitle = account.visibleSteamId
                        .ifBlank { account.accountName }
                        .ifBlank { context.getString(R.string.steam_mafile_export_unknown_account) }
                    SteamMaFileExportCandidate(
                        id = account.id,
                        title = title,
                        subtitle = subtitle
                    )
                }
            Result.success(candidates)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("DataExport", "加载Steam maFile导出账号失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun prepareSteamMaFileExport(accountIds: Set<Long>, source: ImportDestination = ImportDestination.Local): Result<PreparedSteamMaFileExport> = withContext(Dispatchers.IO) {
        try {
            val selectedIds = accountIds
            if (selectedIds.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.steam_mafile_export_no_selection)))
            }

            val accounts = takagi.ru.monica.transfer.DatabaseExportSnapshotLoader(context).loadSteamAccounts(source)
            val selectedAccounts = accounts
                .filter { account -> account.id in selectedIds && account.sharedSecret.isNotBlank() }

            if (selectedAccounts.isEmpty()) {
                return@withContext Result.failure(Exception(context.getString(R.string.steam_mafile_export_empty)))
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val prepared = if (selectedAccounts.size == 1) {
                val account = selectedAccounts.first()
                val fileName = SteamMaFileBackupCodec.fileName(account)
                val tempFile = File(context.cacheDir, "steam_mafile_export_${System.nanoTime()}_$fileName")
                tempFile.writeText(SteamMaFileBackupCodec.encode(account), Charsets.UTF_8)
                if (!tempFile.isFile || tempFile.length() <= 0L) {
                    throw IOException(strings.get(R.string.export_message_empty_mafile))
                }
                PreparedSteamMaFileExport(
                    file = tempFile,
                    fileName = fileName,
                    mimeType = "application/json",
                    successMessage = context.getString(R.string.steam_mafile_export_success_single),
                    accountCount = 1
                )
            } else {
                val fileName = "steam_mafiles_$timestamp.zip"
                val tempFile = File(context.cacheDir, "steam_mafile_export_${System.nanoTime()}.zip")
                val usedFileNames = mutableSetOf<String>()
                ZipOutputStream(tempFile.outputStream()).use { zip ->
                    selectedAccounts.forEach { account ->
                        val entryName = uniqueSteamMaFileName(
                            SteamMaFileBackupCodec.fileName(account),
                            usedFileNames
                        )
                        val bytes = SteamMaFileBackupCodec.encode(account).toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                validatePlainZipFile(tempFile)
                PreparedSteamMaFileExport(
                    file = tempFile,
                    fileName = fileName,
                    mimeType = "application/zip",
                    successMessage = context.getString(
                        R.string.steam_mafile_export_success_multi,
                        selectedAccounts.size
                    ),
                    accountCount = selectedAccounts.size
                )
            }

            Result.success(prepared)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("DataExport", "准备Steam maFile导出失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun writePreparedSteamMaFileExport(
        outputUri: Uri,
        preparedExport: PreparedSteamMaFileExport,
        progress: TransferProgressReporter = TransferProgressReporter.None,
    ): Result<String> {
        return try {
            val validator: ((InputStream) -> Unit)? = if (preparedExport.fileName.endsWith(".zip", ignoreCase = true)) {
                ::validatePlainZipStream
            } else {
                { input ->
                    if (input.readBytes().isEmpty()) {
                        throw IOException(strings.get(R.string.export_message_empty_mafile))
                    }
                }
            }
            copyPlainFileToOutputUri(preparedExport.file, outputUri, validator, progress)
            Result.success(preparedExport.successMessage)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("DataExport", "写入Steam maFile导出失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun uniqueSteamMaFileName(fileName: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(fileName)) return fileName

        val extensionIndex = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val baseName = fileName.substring(0, extensionIndex).ifBlank { "steam" }
        val extension = fileName.substring(extensionIndex)
        var suffix = 2
        while (true) {
            val candidate = "$baseName-$suffix$extension"
            if (usedNames.add(candidate)) return candidate
            suffix++
        }
    }

    /**
     * 导入完整备份 (ZIP格式)
     * @param inputUri 用户选择的ZIP文件URI
     */
    suspend fun importZipBackup(inputUri: Uri, decryptPassword: String? = null, destination: ImportDestination? = null,
        restoreMonicaConfig: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) { importZipBackupOnIo(inputUri, decryptPassword, destination, restoreMonicaConfig) }

    private suspend fun importZipBackupOnIo(inputUri: Uri, decryptPassword: String?, destination: ImportDestination?, restoreMonicaConfig: Boolean): Result<Int> {
        return try {
            // A file import must ask for this archive's password before copying a large file.
            // The cloud-backup password is unrelated to the archive the user selected.
            val encrypted = context.contentResolver.openInputStream(inputUri)?.use(EncryptionHelper::hasEncryptedFileHeader)
                ?: throw IOException(strings.get(R.string.bitwarden_message_file_unreadable))
            if (encrypted && decryptPassword.isNullOrEmpty()) {
                _importProgress.value = null
                return Result.failure(takagi.ru.monica.utils.WebDavHelper.PasswordRequiredException(
                    strings.get(R.string.backup_password_required)))
            }
            importProgressReporter.report(TransferProgress(TransferPhase.READING))
            val webDavHelper = takagi.ru.monica.utils.WebDavHelper(context)
            
            // 1. 将Uri内容复制到临时文件
            val tempFile = java.io.File(context.cacheDir, "import_temp_${System.nanoTime()}.zip")
            try {
                val length = runCatching { context.contentResolver.openFileDescriptor(inputUri, "r")?.use { it.statSize } }
                    .getOrNull()?.takeIf { it > 0 }
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        input.copyWithProgress(output, TransferPhase.READING, length, importProgressReporter)
                    }
                } ?: throw IOException(strings.get(R.string.bitwarden_message_file_unreadable))
                // 2. 调用 restoreFromBackupFile 解析备份
                val result = webDavHelper.restoreFromBackupFile(
                    tempFile, decryptPassword, restoreMonicaConfig = restoreMonicaConfig, importDataOnly = destination != null,
                    progress = importProgressReporter,
                )
                
                result.fold(
                    onSuccess = { restoreResult ->
                        if (destination != null) return targetedImport {
                            targetedImporter.apply(
                                restoreResult.content, destination,
                                parseFailures = restoreResult.report.failedItems.size,
                            )
                        }
                        val stats = BackupRestoreApplier.applyRestoreResult(
                            context = context,
                            restoreResult = restoreResult,
                            passwordRepository = passwordRepository,
                            secureItemRepository = secureItemRepository,
                            localOnlyDedup = true,
                            logTag = "DataImport",
                            progress = importProgressReporter,
                        )
                        logImportSummary(
                            source = "ZIP_BACKUP",
                            importedCount = stats.totalImported()
                        )
                        Result.success(stats.totalImported())
                    },
                    onFailure = { error ->
                        // 如果是密码错误，抛出特定的异常以便UI处理
                        if (error is takagi.ru.monica.utils.WebDavHelper.PasswordRequiredException) {
                            return Result.failure(error)
                        }
                        Result.failure(error)
                    }
                )
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("DataImport", "导入ZIP失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ==================== Stratum Auth Import ====================
    
    suspend fun isStratumFileEncrypted(inputUri: Uri): Boolean {
        return exportManager.isStratumFileEncrypted(inputUri).getOrDefault(false)
    }
    
    suspend fun importStratum(inputUri: Uri, password: String? = null, destination: ImportDestination? = null): Result<Int> {
        return try {
            val fileType = exportManager.detectStratumFileType(inputUri).getOrNull()
                ?: return Result.failure(Exception("Cannot detect file type"))
            val entriesResult = when (fileType) {
                takagi.ru.monica.util.StratumDecryptor.StratumFileType.MODERN_ENCRYPTED,
                takagi.ru.monica.util.StratumDecryptor.StratumFileType.LEGACY_ENCRYPTED -> {
                    if (password.isNullOrEmpty()) return Result.failure(Exception("Password required"))
                    exportManager.importEncryptedStratum(inputUri, password)
                }
                takagi.ru.monica.util.StratumDecryptor.StratumFileType.UNENCRYPTED -> exportManager.importStratumJson(inputUri)
                takagi.ru.monica.util.StratumDecryptor.StratumFileType.NOT_STRATUM -> {
                    val txtResult = exportManager.importStratumTxt(inputUri)
                    if (txtResult.isSuccess) {
                        txtResult
                    } else {
                        exportManager.importStratumHtml(inputUri)
                    }
                }
            }
            entriesResult.fold(
                onSuccess = { list ->
                    if (destination == null) insertTotpEntries(list)
                    else targetedImport { targetedImporter.apply(totpImportContent(list), destination) }
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
    
    suspend fun importStratumTxt(inputUri: Uri): Result<Int> {
        return try {
            exportManager.importStratumTxt(inputUri).fold(onSuccess = { insertTotpEntries(it) }, onFailure = { Result.failure(it) })
        } catch (e: Exception) { Result.failure(e) }
    }
    
    suspend fun importStratumHtml(inputUri: Uri): Result<Int> {
        return try {
            exportManager.importStratumHtml(inputUri).fold(onSuccess = { insertTotpEntries(it) }, onFailure = { Result.failure(it) })
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private suspend fun insertTotpEntries(entries: List<DataExportImportManager.AegisEntry>): Result<Int> {
        var count = 0
        var skippedCount = 0
        var failedCount = 0
        val existingItems = secureItemRepository.getAllItems().first().filter { it.itemType == ItemType.TOTP }
        val existingSecrets = existingItems.mapNotNull { parseStoredTotpData(it)?.secret }.toSet()
        for (entry in entries) {
            try {
                if (entry.secret in existingSecrets) {
                    skippedCount++
                    continue
                }
                val totpData = TotpData(secret = entry.secret, issuer = entry.issuer, accountName = entry.name, digits = entry.digits, period = entry.period, algorithm = entry.algorithm)
                val itemData = Json.encodeToString(totpData)
                val item = SecureItem(id = 0, itemType = ItemType.TOTP, title = entry.issuer.ifBlank { entry.name }, itemData = encodeSecureItemDataForLocalStorage(ItemType.TOTP, itemData), notes = entry.note, isFavorite = false, imagePaths = "", createdAt = Date(), updatedAt = Date(), categoryId = null, keepassDatabaseId = null, keepassGroupPath = null, bitwardenVaultId = null, bitwardenFolderId = null)
                secureItemRepository.insertItem(item)
                count++
            } catch (e: Exception) {
                failedCount++
            }
        }
        logImportSummary(
            source = "STRATUM_TOTP",
            importedCount = count,
            skippedCount = skippedCount,
            failedCount = failedCount
        )
        return Result.success(count)
    }

    private fun logImportSummary(
        source: String,
        importedCount: Int,
        skippedCount: Int = 0,
        failedCount: Int = 0
    ) {
        if (importedCount <= 0 && skippedCount <= 0 && failedCount <= 0) return

        val details = mutableListOf(
            FieldChange(
                fieldName = context.getString(R.string.import_data),
                oldValue = source,
                newValue = source
            ),
            FieldChange(
                fieldName = "Imported",
                oldValue = "0",
                newValue = importedCount.toString()
            )
        )
        if (skippedCount > 0) {
            details += FieldChange(
                fieldName = "Skipped",
                oldValue = "0",
                newValue = skippedCount.toString()
            )
        }
        if (failedCount > 0) {
            details += FieldChange(
                fieldName = "Failed",
                oldValue = "0",
                newValue = failedCount.toString()
            )
        }

        OperationLogger.logCreate(
            itemType = OperationLogItemType.CATEGORY,
            itemId = System.currentTimeMillis(),
            itemTitle = "${context.getString(R.string.import_data)} · $source",
            details = details
        )
    }

}
