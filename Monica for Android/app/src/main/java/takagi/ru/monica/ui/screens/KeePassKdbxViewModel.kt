package takagi.ru.monica.ui.screens

import takagi.ru.monica.utils.AppLocaleStringResolver

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import takagi.ru.monica.R
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.KeePassCodecSupport
import takagi.ru.monica.utils.KeePassCredentialSupport
import takagi.ru.monica.utils.KeePassEntryResolutionContext
import takagi.ru.monica.utils.KeePassErrorCode
import takagi.ru.monica.utils.KeePassFieldReferenceResolver
import takagi.ru.monica.utils.KeePassOperationException
import takagi.ru.monica.utils.KeePassFormatInspector
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.KeePassPortableSecretExportException
import takagi.ru.monica.utils.KeePassPortableSecretExportPolicy
import takagi.ru.monica.utils.toKeePassOperationException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * KeePass KDBX 本地导入/导出 ViewModel
 */
class KeePassKdbxViewModel {
    
    companion object {
        private const val TAG = "KeePassKdbxVM"
    }
    
    // ==================== 本地 KDBX 导出和导入 ====================
    
    /**
     * 导出数据为 .kdbx 格式到本地 OutputStream
     * 供导出页面使用，不需要 WebDAV 配置
     * 
     * @param context Android Context
     * @param outputStream 输出流
     * @param kdbxPassword 用于加密 .kdbx 文件的密码
     */
    suspend fun exportToLocalKdbx(
        context: Context,
        outputStream: OutputStream,
        kdbxPassword: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting local KDBX export...")
            exportToKdbxStream(context, outputStream, kdbxPassword)
            
            // 返回导出的条目数
            val database = PasswordDatabase.getDatabase(context)
            val passwordCount = database.passwordEntryDao().getAllPasswordEntriesSync().size
            val totpCount = database.secureItemDao().getActiveItemsByTypeSync(ItemType.TOTP).size
            val totalCount = passwordCount + totpCount
            
            Log.d(TAG, "Local KDBX export completed: $passwordCount passwords, $totpCount TOTP")
            Result.success(totalCount)
        } catch (e: Exception) {
            Log.e(TAG, "Local KDBX export failed", e)
            Result.failure(e.toKeePassOperationException(AppLocaleStringResolver(context)))
        }
    }
    
    /**
     * 从本地 URI 导入 .kdbx 文件
     * 供导入页面使用，不需要 WebDAV 配置
     *
     * @param context Android Context
     * @param sourceUri KDBX 文件 URI
     * @param kdbxPassword 用于解密 .kdbx 文件的密码
     * @return 导入的条目数量
     */
    suspend fun importFromLocalKdbx(
        context: Context,
        sourceUri: Uri,
        kdbxPassword: String,
        keyFileUri: Uri? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting local KDBX import")
            val importedCount = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                parseKdbxAndInsertToDb(
                    context = context,
                    sourceUri = sourceUri,
                    inputStream = inputStream,
                    kdbxPassword = kdbxPassword,
                    keyFileUri = keyFileUri
                )
            } ?: throw Exception(context.getString(R.string.import_data_error))
            Log.d(TAG, "Local KDBX import completed: $importedCount entries")
            Result.success(importedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Local KDBX import failed", e)
            Result.failure(e.toKeePassOperationException(AppLocaleStringResolver(context)))
        }
    }
    
    private suspend fun bindLocalDatabase(
        context: Context,
        sourceUri: Uri,
        kdbxPassword: String,
        keyFileUri: Uri?,
        entryCount: Int,
        creationOptions: KeePassDatabaseCreationOptions
    ): Long {
        val appDb = PasswordDatabase.getDatabase(context)
        val keepassDao = appDb.localKeePassDatabaseDao()
        val securityManager = SecurityManager(context)
        val encryptedPassword = if (kdbxPassword.isNotBlank()) {
            securityManager.encryptData(kdbxPassword)
        } else {
            null
        }

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                sourceUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (keyFileUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    keyFileUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }

        val allDatabases = keepassDao.getAllDatabasesSync()
        val uriPath = sourceUri.toString()
        val displayName = (
            DocumentFile.fromSingleUri(context, sourceUri)?.name
                ?: sourceUri.lastPathSegment?.substringAfterLast('/')
                ?: "ImportedKeePass"
            ).removeSuffix(".kdbx")
                .removeSuffix(".kdb")
        val existing = allDatabases.firstOrNull { it.filePath == uriPath }

        return if (existing != null) {
            keepassDao.updateDatabase(
                existing.copy(
                    name = displayName,
                    encryptedPassword = encryptedPassword,
                    keyFileUri = keyFileUri?.toString() ?: existing.keyFileUri,
                    storageLocation = KeePassStorageLocation.EXTERNAL,
                    lastAccessedAt = System.currentTimeMillis(),
                    entryCount = entryCount,
                    kdbxMajorVersion = creationOptions.formatVersion.majorVersion,
                    cipherAlgorithm = creationOptions.cipherAlgorithm.name,
                    kdfAlgorithm = creationOptions.kdfAlgorithm.name,
                    kdfTransformRounds = creationOptions.transformRounds,
                    kdfMemoryBytes = creationOptions.memoryBytes,
                    kdfParallelism = creationOptions.parallelism
                )
            )
            KeePassKdbxService.invalidateProcessCache(existing.id)
            existing.id
        } else {
            val newId = keepassDao.insertDatabase(
                LocalKeePassDatabase(
                    name = displayName,
                    filePath = uriPath,
                    keyFileUri = keyFileUri?.toString(),
                    storageLocation = KeePassStorageLocation.EXTERNAL,
                    encryptedPassword = encryptedPassword,
                    description = "Imported local KDBX",
                    entryCount = entryCount,
                    kdbxMajorVersion = creationOptions.formatVersion.majorVersion,
                    cipherAlgorithm = creationOptions.cipherAlgorithm.name,
                    kdfAlgorithm = creationOptions.kdfAlgorithm.name,
                    kdfTransformRounds = creationOptions.transformRounds,
                    kdfMemoryBytes = creationOptions.memoryBytes,
                    kdfParallelism = creationOptions.parallelism,
                    isDefault = allDatabases.isEmpty()
                )
            )
            KeePassKdbxService.invalidateProcessCache(newId)
            newId
        }
    }
    
    // ==================== KDBX 导出和导入实现 ====================
    
    /**
     * 将本地数据导出为 KDBX 格式
     * 使用 kotpass 库生成真正的 KeePass 数据库文件
     * 
     * @param context Android Context
     * @param outputStream 输出流
     * @param kdbxPassword 数据库密码
     */
    private suspend fun exportToKdbxStream(
        context: Context,
        outputStream: OutputStream,
        kdbxPassword: String
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "exportToKdbxStream: Starting KDBX export")
        
        // 1. 从数据库读取所有密码条目
        val database = PasswordDatabase.getDatabase(context)
        val passwordDao = database.passwordEntryDao()
        val secureItemDao = database.secureItemDao()
        val passwords = passwordDao.getAllPasswordEntriesSync()
        
        // 获取所有 TOTP 验证器条目
        val totpItems = secureItemDao.getActiveItemsByTypeSync(ItemType.TOTP)
        
        // 获取 SecurityManager 用于解密密码
        val securityManager = takagi.ru.monica.security.SecurityManager(context)
        
        Log.d(TAG, "Found ${passwords.size} password entries and ${totpItems.size} TOTP entries to export")
        
        // 2. 创建凭证
        val credentials = Credentials.from(EncryptedValue.fromString(kdbxPassword))
        
        // 3. 创建密码 KeePass 条目列表
        val passwordEntries = passwords.map { password ->
            val decryptedPassword = KeePassPortableSecretExportPolicy.resolve(
                storedValue = password.password,
                entryTitle = password.title,
                decrypt = securityManager::decryptData
            )
            
            Entry(
                uuid = UUID.randomUUID(),
                fields = EntryFields.of(
                    BasicField.Title() to EntryValue.Plain(password.title),
                    BasicField.UserName() to EntryValue.Plain(password.username),
                    BasicField.Password() to EntryValue.Encrypted(
                        EncryptedValue.fromString(decryptedPassword)
                    ),
                    BasicField.Url() to EntryValue.Plain(password.website),
                    BasicField.Notes() to EntryValue.Plain(buildString {
                        if (password.notes.isNotEmpty()) {
                            append(password.notes)
                        }
                        if (password.email.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("Email: ${password.email}")
                        }
                        if (password.phone.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("Phone: ${password.phone}")
                        }
                    })
                )
            )
        }
        
        // 4. 创建 TOTP KeePass 条目列表
        val totpEntries = totpItems.mapNotNull { item ->
            try {
                val totpData = TotpDataResolver.parseStoredItemData(
                    itemData = item.itemData,
                    fallbackIssuer = item.title,
                    decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
                ) ?: return@mapNotNull null
                
                // 解密 secret
                val decryptedSecret = KeePassPortableSecretExportPolicy.resolve(
                    storedValue = totpData.secret,
                    entryTitle = item.title,
                    decrypt = securityManager::decryptDataIfMonicaCiphertext
                )
                
                // 构建 otpauth:// URI (KeePass 标准 TOTP 格式)
                val otpUri = buildOtpAuthUri(
                    secret = decryptedSecret,
                    issuer = totpData.issuer.ifEmpty { item.title },
                    accountName = totpData.accountName,
                    algorithm = totpData.algorithm,
                    digits = totpData.digits,
                    period = totpData.period
                )
                
                Entry(
                    uuid = UUID.randomUUID(),
                    fields = EntryFields.of(
                        BasicField.Title() to EntryValue.Plain(item.title),
                        BasicField.UserName() to EntryValue.Plain(totpData.accountName),
                        BasicField.Password() to EntryValue.Encrypted(
                            EncryptedValue.fromString("")
                        ),
                        BasicField.Url() to EntryValue.Plain(totpData.link),
                        BasicField.Notes() to EntryValue.Plain(item.notes),
                        // KeePass TOTP 标准字段 - otp
                        "otp" to EntryValue.Plain(otpUri),
                        // 备用字段 - 一些 KeePass 插件使用这些
                        "TOTP Seed" to EntryValue.Plain(decryptedSecret),
                        "TOTP Settings" to EntryValue.Plain("${totpData.period};${totpData.digits}")
                    )
                )
            } catch (e: KeePassPortableSecretExportException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse TOTP data during KDBX export: ${e.message}")
                null
            }
        }
        
        // 5. 创建分组结构
        val passwordGroup = Group(
            uuid = UUID.randomUUID(),
            name = context.getString(R.string.item_type_password),
            entries = passwordEntries
        )
        
        val totpGroup = Group(
            uuid = UUID.randomUUID(),
            name = context.getString(R.string.item_type_authenticator),
            entries = totpEntries
        )
        
        // 6. 创建 KeePass 数据库
        val meta = Meta(
            generator = "Monica Password Manager",
            name = "Monica Export"
        )
        
        val keePassDatabase = KeePassDatabase.Ver4x.create(
            rootName = "Monica",
            meta = meta,
            credentials = credentials
        ).modifyParentGroup {
            copy(groups = listOf(passwordGroup, totpGroup))
        }
        
        // 7. 编码并写入输出流
        keePassDatabase.encode(outputStream, cipherProviders = KeePassCodecSupport.cipherProviders)
        
        Log.d(TAG, "KDBX export completed successfully with ${passwords.size} passwords and ${totpItems.size} TOTP entries")
    }
    
    /**
     * 构建 otpauth:// URI
     */
    private fun buildOtpAuthUri(
        secret: String,
        issuer: String,
        accountName: String,
        algorithm: String,
        digits: Int,
        period: Int
    ): String {
        val label = if (issuer.isNotEmpty() && accountName.isNotEmpty()) {
            "${URLEncoder.encode(issuer, "UTF-8")}:${URLEncoder.encode(accountName, "UTF-8")}"
        } else if (accountName.isNotEmpty()) {
            URLEncoder.encode(accountName, "UTF-8")
        } else {
            URLEncoder.encode(issuer, "UTF-8")
        }
        
        return buildString {
            append("otpauth://totp/")
            append(label)
            append("?secret=")
            append(secret.replace(" ", "").uppercase())
            if (issuer.isNotEmpty()) {
                append("&issuer=")
                append(URLEncoder.encode(issuer, "UTF-8"))
            }
            if (algorithm != "SHA1") {
                append("&algorithm=")
                append(algorithm)
            }
            if (digits != 6) {
                append("&digits=")
                append(digits)
            }
            if (period != 30) {
                append("&period=")
                append(period)
            }
        }
    }
    
    /**
     * 解析 KDBX 文件并导入到数据库
     * 支持密码和 TOTP 验证器的导入
     * 
     * @param context Android Context
     * @param inputStream KDBX 文件输入流
     * @param kdbxPassword 数据库密码
     * @return 导入的条目数量（密码 + TOTP）
     */
    private suspend fun parseKdbxAndInsertToDb(
        context: Context,
        sourceUri: Uri,
        inputStream: InputStream,
        kdbxPassword: String,
        keyFileUri: Uri?
    ): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "parseKdbxAndInsertToDb: Starting KDBX import")
        
        try {
            // 1. 读取 KDBX 数据和可选密钥文件
            val kdbxBytes = inputStream.readBytes()
            KeePassFormatInspector.ensureKdbxSupported(
                bytes = kdbxBytes,
                sourceName = sourceUri.lastPathSegment ?: sourceUri.toString(),
                strings = AppLocaleStringResolver(context),
            )
            val keyFileBytes = keyFileUri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception(context.getString(R.string.import_data_error))
            }

            // 2. 创建凭据候选并解码 KDBX（兼容 XML/HEX/RAW keyfile 及历史空密码组合）
            val candidates = KeePassCredentialSupport.buildCredentialCandidates(
                password = kdbxPassword,
                keyFileBytes = keyFileBytes
            )
            val keePassDatabase = KeePassKdbxService.withGlobalDecodeLock {
                var lastError: Throwable? = null
                val attemptedLabels = mutableListOf<String>()
                candidates.forEachIndexed { index, candidate ->
                    val isLast = index == candidates.lastIndex
                    attemptedLabels += candidate.label
                    try {
                        val decoded = KeePassDatabase.decode(
                            kdbxBytes.inputStream(),
                            candidate.credentials,
                            contentParser = KeePassCodecSupport.contentParser,
                            cipherProviders = KeePassCodecSupport.cipherProviders
                        )
                        if (index > 0) {
                            Log.w(TAG, "Decoded KDBX with credential fallback: ${candidate.label}")
                        }
                        return@withGlobalDecodeLock decoded
                    } catch (decodeError: Throwable) {
                        val mapped = decodeError.toKeePassOperationException(AppLocaleStringResolver(context))
                        lastError = mapped
                        val isInvalidCredential = mapped.code == KeePassErrorCode.INVALID_CREDENTIAL
                        if (!isInvalidCredential || isLast) {
                            throw mapped
                        }
                    }
                }
                val allInvalidCredential = lastError is KeePassOperationException &&
                    (lastError as KeePassOperationException).code == KeePassErrorCode.INVALID_CREDENTIAL
                if (allInvalidCredential) {
                    throw KeePassOperationException(
                        code = KeePassErrorCode.INVALID_CREDENTIAL,
                        message = KeePassCredentialSupport.buildInvalidCredentialMessage(attemptedLabels, strings = AppLocaleStringResolver(context)),
                        cause = lastError
                    )
                }
                throw (lastError ?: IllegalStateException(context.getString(R.string.runtime_kdbx_decode_failed)))
            }
            
            // 3. 获取所有条目（保留分组路径）
            val allEntries = mutableListOf<Pair<Entry, String?>>()
            collectEntriesWithGroupPath(keePassDatabase.content.group, null, allEntries)
            val resolutionContext = KeePassFieldReferenceResolver.buildContext(allEntries.map { it.first })
            Log.d(TAG, "Found ${allEntries.size} entries in KDBX file")
            
            // 4. 准备数据库和安全管理器
            val database = PasswordDatabase.getDatabase(context)
            val passwordDao = database.passwordEntryDao()
            val secureItemDao = database.secureItemDao()
            val securityManager = takagi.ru.monica.security.SecurityManager(context)
            val keepassCredentialCount = allEntries.count { (entry, _) ->
                isImportablePasswordEntry(
                    entry = entry,
                    resolutionContext = resolutionContext,
                    hasTotpPayload = hasTotpPayload(entry, resolutionContext)
                )
            }
            val creationOptions = KeePassKdbxService.inferCreationOptions(keePassDatabase)
            val keepassDatabaseId = bindLocalDatabase(
                context = context,
                sourceUri = sourceUri,
                kdbxPassword = kdbxPassword,
                keyFileUri = keyFileUri,
                entryCount = keepassCredentialCount,
                creationOptions = creationOptions
            )
            
            var passwordImportedCount = 0
            var totpImportedCount = 0
            
            allEntries.forEach { (entry, groupPath) ->
                try {
                    // 安全获取字段值的辅助函数
                    fun getFieldValue(key: String): String {
                        return try {
                            KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get field '$key': ${e.message}")
                            ""
                        }
                    }
                    
                    val title = getFieldValue("Title")
                    val username = getFieldValue("UserName")
                    val password = resolveEntryPassword(entry, resolutionContext)
                    val url = getFieldValue("URL")
                    val notes = getFieldValue("Notes")
                    val monicaItemType = getFieldValue("MonicaItemType")

                    // Monica 安全项映射条目不应按密码导入。
                    if (monicaItemType.isNotBlank()) {
                        return@forEach
                    }
                    
                    // 检查是否是 TOTP 条目（检查 otp 字段或 TOTP Seed 字段）
                    val otpField = getFieldValue("otp")
                    val totpSeed = getFieldValue("TOTP Seed")
                    val totpSettings = getFieldValue("TOTP Settings")
                    val hasTotpPayload = otpField.isNotEmpty() || totpSeed.isNotEmpty()
                    val shouldImportPassword = isImportablePasswordEntry(entry, resolutionContext, hasTotpPayload)

                    // 先导入密码，便于后续 TOTP 绑定到同一账号。
                    var passwordIdForBinding: Long? = null
                    if (shouldImportPassword) {
                        val normalizedPassword = normalizeImportedPassword(password, securityManager)
                        val encryptedPassword = encryptImportedPasswordForDisplay(
                            plainPassword = normalizedPassword,
                            securityManager = securityManager
                        )
                        val existingEntry = passwordDao.findDuplicateEntryInKeePass(
                            databaseId = keepassDatabaseId,
                            title = title,
                            username = username,
                            website = url,
                            groupPath = groupPath
                        )
                        val sshKeyData = resolveSshKeyData(entry, resolutionContext)
                        val (wifiLoginType, wifiMetadataJson) = resolveWifiMetadata(
                            entry = entry,
                            title = title,
                            resolutionContext = resolutionContext
                        )

                        val isNewPasswordEntry = existingEntry == null
                        val insertedPasswordId = if (existingEntry != null) {
                            val updated = existingEntry.copy(
                                title = title,
                                username = username,
                                password = encryptedPassword,
                                website = url,
                                notes = notes,
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = groupPath,
                                sshKeyData = sshKeyData,
                                loginType = wifiLoginType,
                                wifiMetadata = wifiMetadataJson,
                                isDeleted = false,
                                deletedAt = null,
                                updatedAt = Date()
                            )
                            passwordDao.update(updated)
                            Log.d(TAG, "Updated existing password during KDBX import")
                            existingEntry.id
                        } else {
                            val passwordEntry = takagi.ru.monica.data.PasswordEntry(
                                title = title,
                                username = username,
                                password = encryptedPassword,
                                website = url,
                                notes = notes,
                                createdAt = Date(),
                                updatedAt = Date(),
                                keepassDatabaseId = keepassDatabaseId,
                                keepassGroupPath = groupPath,
                                sshKeyData = sshKeyData,
                                loginType = wifiLoginType,
                                wifiMetadata = wifiMetadataJson
                            )
                            val newPasswordId = passwordDao.insertPasswordEntry(passwordEntry)
                            passwordImportedCount++
                            newPasswordId
                        }

                        passwordIdForBinding = insertedPasswordId

                        // 导入/更新自定义字段（KeePass 中的非标准字段）
                        if (insertedPasswordId > 0) {
                            val customFieldDao = database.customFieldDao()
                            val standardFields = setOf(
                                "Title", "UserName", "Password", "URL", "Notes",
                                "otp", "TOTP Seed", "TOTP Settings", "MonicaItemType",
                                "MonicaItemData", "MonicaSecureItemId", "MonicaImagePaths", "MonicaIsFavorite",
                                "MonicaSshAlgorithm", "MonicaSshKeySize", "MonicaSshPublicKey",
                                "MonicaSshPrivateKey", "MonicaSshFingerprint", "MonicaSshComment", "MonicaSshFormat",
                                // WIFI 互通字段：已映射到 loginType/wifiMetadata，不再作为自定义字段导入
                                "SSID", "MonicaLoginType", "MonicaWifiData",
                                // 非标准密码别名：已用于主密码提取，不再作为自定义字段重复导入
                                "密码", "口令", "PIN", "Pin", "pin", "pwd", "PWD", "pass", "Pass", "password"
                            )

                            val importedCustomFields = mutableListOf<CustomField>()
                            var sortOrder = 0
                            entry.fields.forEach { (fieldKey, fieldValue) ->
                                if (fieldKey !in standardFields && !fieldKey.startsWith("_etm_")) {
                                    try {
                                        val fieldContent = fieldValue.content
                                        if (fieldContent.isNotEmpty()) {
                                            val isProtected = fieldValue is EntryValue.Encrypted
                                            importedCustomFields += CustomField(
                                                id = 0,
                                                entryId = insertedPasswordId,
                                                title = fieldKey,
                                                value = if (isProtected) securityManager.encryptData(fieldContent) else fieldContent,
                                                isProtected = isProtected,
                                                sortOrder = sortOrder++
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to import custom field '$fieldKey': ${e.message}")
                                    }
                                }
                            }

                            if (importedCustomFields.isNotEmpty()) {
                                if (isNewPasswordEntry) {
                                    importedCustomFields.forEach { customFieldDao.insert(it) }
                                } else {
                                    val existingFields = customFieldDao.getFieldsByEntryIdSync(insertedPasswordId)
                                    val existingByTitle = existingFields.associateBy { it.title.lowercase(Locale.ROOT) }
                                    importedCustomFields.forEach { imported ->
                                        val existing = existingByTitle[imported.title.lowercase(Locale.ROOT)]
                                        if (existing != null) {
                                            customFieldDao.update(
                                                existing.copy(
                                                    value = imported.value,
                                                    isProtected = imported.isProtected,
                                                    sortOrder = imported.sortOrder
                                                )
                                            )
                                        } else {
                                            customFieldDao.insert(imported)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (hasTotpPayload) {
                        // 这是一个 TOTP 条目
                        val totpData = if (otpField.startsWith("otpauth://")) {
                            parseOtpAuthUri(otpField, securityManager)
                        } else if (totpSeed.isNotEmpty()) {
                            // 使用 TOTP Seed 和 TOTP Settings
                            val (period, digits) = parseTotpSettings(totpSettings)
                            TotpData(
                                secret = securityManager.encryptData(totpSeed),
                                issuer = title,
                                accountName = username,
                                period = period,
                                digits = digits,
                                algorithm = "SHA1",
                                link = url
                            )
                        } else {
                            null
                        }
                        
                        if (totpData != null) {
                            val boundTotpData = if (passwordIdForBinding != null) {
                                totpData.copy(boundPasswordId = passwordIdForBinding)
                            } else {
                                totpData
                            }
                            val normalizedTitle = title.ifEmpty { totpData.issuer }
                            val duplicateByTitle = secureItemDao.findDuplicateItem(ItemType.TOTP, normalizedTitle)
                            val existingItem = duplicateByTitle?.takeIf { candidate ->
                                (candidate.keepassDatabaseId == keepassDatabaseId && candidate.keepassGroupPath == groupPath) ||
                                    candidate.isLocalOnlyItem()
                            }
                            if (existingItem != null) {
                                val updatedItem = existingItem.copy(
                                    title = normalizedTitle,
                                    notes = notes,
                                    itemData = Json.encodeToString(TotpData.serializer(), boundTotpData),
                                    keepassDatabaseId = keepassDatabaseId,
                                    keepassGroupPath = groupPath,
                                    isDeleted = false,
                                    deletedAt = null,
                                    updatedAt = Date()
                                )
                                secureItemDao.updateItem(updatedItem)
                                Log.d(TAG, "Updated existing TOTP during KDBX import")
                            } else {
                                val secureItem = SecureItem(
                                    itemType = ItemType.TOTP,
                                    title = normalizedTitle,
                                    notes = notes,
                                    itemData = Json.encodeToString(TotpData.serializer(), boundTotpData),
                                    createdAt = Date(),
                                    updatedAt = Date(),
                                    keepassDatabaseId = keepassDatabaseId,
                                    keepassGroupPath = groupPath
                                )
                                
                                secureItemDao.insertItem(secureItem)
                                totpImportedCount++
                                Log.d(TAG, "Imported TOTP during KDBX import")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to import entry: ${e.message}")
                }
            }
            
            val totalImported = passwordImportedCount + totpImportedCount
            Log.d(TAG, "KDBX import completed: $passwordImportedCount passwords, $totpImportedCount TOTP entries")
            totalImported
        } catch (e: Exception) {
            Log.e(TAG, "KDBX parsing failed", e)
            throw e.toKeePassOperationException(AppLocaleStringResolver(context))
        }
    }
    
    /**
     * 递归收集所有组中的条目
     */
    private fun collectEntries(group: Group, entries: MutableList<Entry>) {
        entries.addAll(group.entries)
        group.groups.forEach { subGroup ->
            collectEntries(subGroup, entries)
        }
    }

    private fun collectEntriesWithGroupPath(
        group: Group,
        currentPath: String?,
        entries: MutableList<Pair<Entry, String?>>
    ) {
        group.entries.forEach { entry ->
            entries.add(entry to currentPath)
        }
        group.groups.forEach { subGroup ->
            val nextPath = if (currentPath.isNullOrBlank()) {
                subGroup.name
            } else {
                "$currentPath/${subGroup.name}"
            }
            collectEntriesWithGroupPath(subGroup, nextPath, entries)
        }
    }

    private fun hasTotpPayload(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        return KeePassFieldReferenceResolver.getFieldValue(entry, "otp", resolutionContext).isNotBlank() ||
            KeePassFieldReferenceResolver.getFieldValue(entry, "TOTP Seed", resolutionContext).isNotBlank()
    }

    private fun resolveSshKeyData(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String {
        fun field(key: String): String = KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)
        return SshKeyDataCodec.encode(
            SshKeyData(
                algorithm = field("MonicaSshAlgorithm"),
                keySize = field("MonicaSshKeySize").toIntOrNull() ?: 0,
                publicKeyOpenSsh = field("MonicaSshPublicKey"),
                privateKeyOpenSsh = field("MonicaSshPrivateKey"),
                fingerprintSha256 = field("MonicaSshFingerprint"),
                comment = field("MonicaSshComment"),
                format = field("MonicaSshFormat").ifBlank { SshKeyData.FORMAT_OPENSSH }
            )
        )
    }

    /**
     * 从一个 KeePass entry 解析 WIFI 信息，返回 (loginType, wifiMetadataJson)。
     *
     * 优先级：
     *  1. Monica 自己写入的 `MonicaLoginType + MonicaWifiData`（完整保真）
     *  2. `MonicaLoginType=WIFI` 但没有 JSON 时，基于 SSID 构造一个最小 WifiData
     *  3. keepass2android 等外部客户端只写了 `SSID` 字段 —— 识别为 WIFI 条目，
     *     安全性默认 WPA2/WPA3（家庭网络绝大多数）
     *  4. 都没有：普通 PASSWORD 登录
     */
    private fun resolveWifiMetadata(
        entry: Entry,
        title: String,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Pair<String, String> {
        fun field(key: String): String =
            KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)

        val monicaLoginType = field("MonicaLoginType")
        val monicaWifiJson = field("MonicaWifiData")
        val ssidField = field("SSID")

        return when {
            monicaLoginType.equals("WIFI", ignoreCase = true) && monicaWifiJson.isNotBlank() ->
                "WIFI" to monicaWifiJson
            monicaLoginType.equals("WIFI", ignoreCase = true) -> {
                val wifi = takagi.ru.monica.data.model.WifiData(
                    ssid = ssidField.ifBlank { title }
                )
                "WIFI" to wifi.toJson()
            }
            ssidField.isNotBlank() -> {
                val wifi = takagi.ru.monica.data.model.WifiData(ssid = ssidField)
                "WIFI" to wifi.toJson()
            }
            monicaLoginType.equals("SSO", ignoreCase = true) -> "SSO" to ""
            monicaLoginType.equals("SSH_KEY", ignoreCase = true) -> "SSH_KEY" to ""
            else -> "PASSWORD" to ""
        }
    }

    /**
     * 某些数据库会把密码存在自定义受保护字段（例如“密码”/“PIN”），
     * 标准 Password 字段为空时做一次兜底提取，避免导入后密码为空。
     */
    private fun resolveEntryPassword(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String {
        fun contentOf(key: String): String = KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)
        fun isLikelyLabelValue(value: String, key: String? = null): Boolean {
            val normalized = value.trim().lowercase(Locale.ROOT)
            if (normalized.isBlank()) return true
            val labelTokens = setOf("password", "pass", "pwd", "pin", "密码", "口令")
            if (normalized in labelTokens) return true
            if (key != null && normalized == key.trim().lowercase(Locale.ROOT)) return true
            return false
        }

        val standardPassword = contentOf("Password")
        if (standardPassword.isNotBlank() && !isLikelyLabelValue(standardPassword, "Password")) {
            return standardPassword
        }
        var fallback = standardPassword.takeIf { it.isNotBlank() }

        val prioritizedKeys = listOf(
            "密码", "口令", "PIN", "Pin", "pin", "pwd", "PWD", "pass", "Pass", "password", "Password"
        )
        prioritizedKeys.forEach { key ->
            val value = contentOf(key)
            if (value.isBlank()) return@forEach
            if (!isLikelyLabelValue(value, key)) return value
            if (fallback.isNullOrBlank()) fallback = value
        }

        val standardFields = setOf(
            "Title", "UserName", "Password", "URL", "Notes",
            "otp", "TOTP Seed", "TOTP Settings", "MonicaItemType",
            "MonicaItemData", "MonicaSecureItemId", "MonicaImagePaths", "MonicaIsFavorite"
        )
        entry.fields.forEach { (key, value) ->
            if (key in standardFields || key.startsWith("_etm_")) return@forEach
            if (value is EntryValue.Encrypted) {
                val content = KeePassFieldReferenceResolver.resolveValue(
                    rawValue = runCatching { value.content }.getOrDefault(""),
                    currentEntry = entry,
                    context = resolutionContext
                )
                if (content.isBlank()) return@forEach
                if (!isLikelyLabelValue(content, key)) return content
                if (fallback.isNullOrBlank()) fallback = content
            }
        }

        return fallback ?: ""
    }

    private fun isImportablePasswordEntry(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null,
        hasTotpPayload: Boolean = false
    ): Boolean {
        fun field(key: String): String = KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)

        // 带 Monica 安全项标记的条目不是密码项（避免计数虚高）。
        if (field("MonicaItemType").isNotBlank()) return false

        val username = field("UserName")
        val password = field("Password")
        val url = field("URL")
        val title = field("Title")
        val notes = field("Notes")

        // 含 TOTP 的条目只有在具备账号凭据时才作为密码导入，避免纯验证器被错误导入为密码。
        if (hasTotpPayload) {
            return username.isNotBlank() || password.isNotBlank() || url.isNotBlank()
        }

        if (username.isNotBlank() || password.isNotBlank() || url.isNotBlank()) {
            return true
        }

        if (title.isNotBlank() || notes.isNotBlank()) {
            return true
        }

        val standardFields = setOf(
            "Title", "UserName", "Password", "URL", "Notes",
            "otp", "TOTP Seed", "TOTP Settings", "MonicaItemType",
            "MonicaItemData", "MonicaSecureItemId", "MonicaImagePaths", "MonicaIsFavorite"
        )
        return entry.fields.any { (key, value) ->
            key !in standardFields && runCatching { value.content.isNotBlank() }.getOrDefault(false)
        }
    }

    /**
     * 解析 otpauth:// URI 并返回 TotpData
     * 格式: otpauth://totp/LABEL?secret=SECRET&issuer=ISSUER&algorithm=SHA1&digits=6&period=30
     */
    private fun parseOtpAuthUri(
        uri: String,
        securityManager: takagi.ru.monica.security.SecurityManager
    ): TotpData? {
        try {
            if (!uri.startsWith("otpauth://")) {
                return null
            }
            
            val url = java.net.URI(uri)
            val type = url.host // totp 或 hotp
            if (type != "totp") {
                Log.w(TAG, "Only TOTP is supported, got: $type")
                // 仍然尝试解析
            }
            
            // 解析 label (path 部分)
            val path = java.net.URLDecoder.decode(url.path.trimStart('/'), "UTF-8")
            val (issuer, accountName) = if (path.contains(":")) {
                val parts = path.split(":", limit = 2)
                parts[0] to parts[1]
            } else {
                "" to path
            }
            
            // 解析查询参数
            val params = mutableMapOf<String, String>()
            url.query?.split("&")?.forEach { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0].lowercase()] = java.net.URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            
            val secret = params["secret"] ?: return null
            val finalIssuer = params["issuer"] ?: issuer
            val algorithm = params["algorithm"]?.uppercase() ?: "SHA1"
            val digits = params["digits"]?.toIntOrNull() ?: 6
            val period = params["period"]?.toIntOrNull() ?: 30
            
            // 加密 secret
            val encryptedSecret = securityManager.encryptData(secret)
            
            return TotpData(
                secret = encryptedSecret,
                issuer = finalIssuer,
                accountName = accountName,
                algorithm = algorithm,
                digits = digits,
                period = period
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse otpauth URI", e)
            return null
        }
    }
    
    /**
     * 解析 TOTP Settings 字段
     * 格式: "period;digits" 例如 "30;6"
     */
    private fun parseTotpSettings(settings: String): Pair<Int, Int> {
        return try {
            val parts = settings.split(";")
            val period = parts.getOrNull(0)?.toIntOrNull() ?: 30
            val digits = parts.getOrNull(1)?.toIntOrNull() ?: 6
            period to digits
        } catch (e: Exception) {
            30 to 6
        }
    }

    /**
     * 防止“已加密字符串再次被当作明文导入”导致 UI 显示密文。
     * 若 payload 可被当前安全上下文解开，则使用解开的值；否则保留原值。
     */
    private fun normalizeImportedPassword(
        rawPassword: String,
        securityManager: SecurityManager
    ): String {
        if (rawPassword.isBlank()) return rawPassword

        var current = rawPassword
        repeat(3) {
            val candidate = runCatching { securityManager.decryptData(current) }
                .getOrDefault(current)
            if (candidate == current || candidate.isBlank()) {
                return current
            }
            current = candidate
        }
        return current
    }

    /**
     * Prefer default encryption, but guarantee same-session readability for imported secrets.
     * This avoids first-import blank password when MDK auth state is temporarily unavailable.
     */
    private fun encryptImportedPasswordForDisplay(
        plainPassword: String,
        securityManager: SecurityManager
    ): String {
        if (plainPassword.isEmpty()) {
            return securityManager.encryptData(plainPassword)
        }

        val primaryEncrypted = securityManager.encryptData(plainPassword)
        val primaryReadable = runCatching { securityManager.decryptData(primaryEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false
        if (primaryReadable) {
            return primaryEncrypted
        }

        Log.w(TAG, "Imported password encrypted payload is not immediately readable; fallback to legacy V1")
        val legacyEncrypted = securityManager.encryptDataLegacyCompat(plainPassword)
        val legacyReadable = runCatching { securityManager.decryptData(legacyEncrypted) }
            .getOrNull()
            ?.let { it == plainPassword }
            ?: false
        return if (legacyReadable) {
            legacyEncrypted
        } else {
            Log.w(TAG, "Legacy fallback is still unreadable; keep primary encrypted payload")
            primaryEncrypted
        }
    }
}
