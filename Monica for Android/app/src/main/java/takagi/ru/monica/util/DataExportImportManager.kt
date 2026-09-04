package takagi.ru.monica.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.NoteData
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import takagi.ru.monica.util.AegisDecryptor
import org.xml.sax.InputSource

/**
 * 数据导入导出管理器
 * 负责将所有数据导出为CSV文件，以及从CSV文件导入数据
 */
class DataExportImportManager(private val context: Context) {

    /**
     * 导出数据项
     */
    data class ExportItem(
        val id: Long,
        val itemType: String,
        val title: String,
        val itemData: String,
        val notes: String,
        val isFavorite: Boolean,
        val imagePaths: String,
        val createdAt: Long,
        val updatedAt: Long,
        val categoryId: Long? = null,
        val keepassDatabaseId: Long? = null,
        val keepassGroupPath: String? = null,
        val bitwardenVaultId: Long? = null,
        val bitwardenFolderId: String? = null,
        val importedCustomFields: List<ImportedCustomField> = emptyList(),
        val importedAuthenticatorKey: String? = null,
        /** true 表示来自 Monica 自身导出的 CSV，导入时应跳过重复检测直接恢复 */
        val isFromAppExport: Boolean = false,
        val sortOrder: Int = 0
    )

    data class ImportedCustomField(
        val title: String,
        val value: String,
        val isProtected: Boolean = false
    )

    companion object {
        private val STEAM_DEVICE_ID_REGEX = Regex("^android:[0-9a-f-]+$", RegexOption.IGNORE_CASE)
    }

    /**
     * 从CSV文件导入数据
     * @param inputUri 输入文件的URI
     * @param formatHint 格式提示，如果提供则跳过自动检测
     * @return 导入的数据项列表
     */
    suspend fun importData(
        inputUri: Uri,
        formatHint: CsvFormat? = null,
        passwordKeyboardTagHandling: PasswordKeyboardTagHandling = PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
    ): Result<List<ExportItem>> = withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<ExportItem>()
            var lineCount = 0
            var errorCount = 0
            var csvFormat: CsvFormat = CsvFormat.UNKNOWN
            var headerIndexMap: Map<String, Int>? = null
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("无法读取文件，请检查文件是否存在"))
            
            inputStream.use { input ->
                // 尝试UTF-8，如果失败则尝试GBK
                val reader = try {
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                } catch (e: Exception) {
                    android.util.Log.w("DataImport", "UTF-8解码失败，尝试GBK", e)
                    BufferedReader(InputStreamReader(input, Charset.forName("GBK")))
                }
                
                reader.use { 
                    var firstLine = readCsvRecord(reader)
                    if (firstLine == null) {
                        return@withContext Result.failure(Exception("文件为空"))
                    }
                    
                    // 跳过BOM标记（如果存在）
                    if (firstLine.startsWith("\uFEFF")) {
                        firstLine = firstLine.substring(1)
                        android.util.Log.d("DataImport", "Skipped UTF-8 BOM in first CSV record")
                    }
                    
                    // 检测CSV格式
                    csvFormat = formatHint ?: detectCsvFormat(firstLine)
                    android.util.Log.d("DataImport", "检测到格式: $csvFormat")
                    
                    // 如果第一行是标题，跳过它
                    val isHeader = isHeaderLine(firstLine, csvFormat)
                    
                    android.util.Log.d("DataImport", "是否为标题行: $isHeader")
                    
                    if (isHeader) {
                        when (csvFormat) {
                            CsvFormat.KEEPASS_PASSWORD,
                            CsvFormat.BITWARDEN_PASSWORD,
                            CsvFormat.PROTON_PASS_PASSWORD -> {
                                val headers = parseCsvLine(firstLine)
                                headerIndexMap = buildHeaderIndexMap(headers)
                            }

                            CsvFormat.PASSWORD_KEYBOARD -> {
                                val firstLineFields = parseCsvLine(firstLine)
                                val headerLine = if (isPasswordKeyboardSignature(firstLineFields)) {
                                    readCsvRecord(reader)
                                } else {
                                    firstLine
                                }
                                headerLine?.let { headerIndexMap = buildHeaderIndexMap(parseCsvLine(it)) }
                            }

                            else -> Unit
                        }
                    }
                    
                    if (!isHeader && firstLine.isNotBlank()) {
                        // 第一行就是数据，处理它
                        lineCount++
                        try {
                            val fields = parseCsvLine(firstLine)
                            android.util.Log.d("DataImport", "第一行字段数: ${fields.size}")
                            val item = createExportItemFromFormat(
                                fields = fields,
                                format = csvFormat,
                                headerIndexMap = headerIndexMap,
                                passwordKeyboardTagHandling = passwordKeyboardTagHandling
                            )
                            if (item != null) {
                                items.add(item)
                                android.util.Log.d("DataImport", "成功添加第一行数据")
                            } else {
                                android.util.Log.w("DataImport", "第一行数据无效")
                                errorCount++
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DataImport", "处理第一行失败: ${e.message}", e)
                            errorCount++
                        }
                    }
                    
                    // 读取剩余数据行
                    var record: String?
                    while (true) {
                        record = readCsvRecord(reader)
                        if (record == null) break
                        val currentLine = record
                        lineCount++
                        if (currentLine.isNotBlank()) {
                            try {
                                val fields = parseCsvLine(currentLine)
                                android.util.Log.d("DataImport", "第${lineCount}行字段数: ${fields.size}")
                                val item = createExportItemFromFormat(
                                    fields = fields,
                                    format = csvFormat,
                                    headerIndexMap = headerIndexMap,
                                    passwordKeyboardTagHandling = passwordKeyboardTagHandling
                                )
                                if (item != null) {
                                    items.add(item)
                                    android.util.Log.d("DataImport", "成功添加第${lineCount}行数据")
                                } else {
                                    android.util.Log.w("DataImport", "第${lineCount}行数据无效")
                                    errorCount++
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DataImport", "处理第${lineCount}行失败: ${e.message}", e)
                                errorCount++
                            }
                        }
                    }
                    android.util.Log.d("DataImport", "总行数: $lineCount, 成功: ${items.size}, 错误: $errorCount")
                }
            }

            if (items.isEmpty()) {
                Result.failure(Exception("未能导入任何数据，请检查文件格式"))
            } else {
                Result.success(items)
            }
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "导入异常", e)
            Result.failure(Exception("导入失败：${e.message ?: "文件格式错误"}"))
        }
    }

    /**
     * 创建导出项
     */
    private fun createExportItem(fields: List<String>): ExportItem {
        val itemType = fields.getOrNull(1)
            ?.trim()
            ?.uppercase()
            .orEmpty()
        return ExportItem(
            id = fields[0].toLongOrNull() ?: 0,
            itemType = itemType,
            title = fields[2],
            itemData = fields[3],
            notes = fields[4],
            isFavorite = fields[5].toBoolean(),
            imagePaths = fields[6],
            createdAt = fields[7].toLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = fields[8].toLongOrNull() ?: System.currentTimeMillis(),
            categoryId = fields.getOrNull(9)?.toLongOrNull(),
            keepassDatabaseId = fields.getOrNull(10)?.toLongOrNull(),
            keepassGroupPath = fields.getOrNull(11)?.takeIf { it.isNotBlank() },
            bitwardenVaultId = fields.getOrNull(12)?.toLongOrNull(),
            bitwardenFolderId = fields.getOrNull(13)?.takeIf { it.isNotBlank() },
            isFromAppExport = true
        )
    }
    
    /**
     * CSV格式类型
     */
    enum class CsvFormat {
        APP_EXPORT,        // 应用导出格式 (9个字段)
        CHROME_PASSWORD,   // Chrome密码格式 (name,url,username,password,note)
        KEEPASS_PASSWORD,  // KeePass CSV 格式
        BITWARDEN_PASSWORD, // Bitwarden CSV 格式
        PROTON_PASS_PASSWORD, // Proton Pass CSV 格式
        PASSWORD_KEYBOARD, // 密码键盘软件 CSV 格式
        ALIPAY_TRANSACTION, // 支付宝交易明细格式
        UNKNOWN
    }

    enum class PasswordKeyboardTagHandling {
        CONVERT_TO_CUSTOM_FIELD,
        DROP
    }

    private enum class PasswordKeyboardRecordType {
        PASSWORD,
        NOTE,
        CARD
    }
    
    /**
     * 检测CSV格式
     */
    private fun detectCsvFormat(firstLine: String): CsvFormat {
        val lowerLine = firstLine.lowercase()
        val firstLineFields = parseCsvLine(firstLine)
        val normalizedHeaders = firstLineFields.map { it.trim().lowercase() }
        val headerSet = normalizedHeaders.toSet()
        return when {
            // 密码键盘软件格式（签名行）: SecretInputExportFile,password|normal,ver4
            isPasswordKeyboardSignature(firstLineFields) ->
                CsvFormat.PASSWORD_KEYBOARD

            // 支付宝格式检测
            lowerLine.contains("交易时间") && lowerLine.contains("收/支") &&
            lowerLine.contains("金额") ->
                CsvFormat.ALIPAY_TRANSACTION

            // Monica 应用自身导出格式：ID,Type,Title,Data,Notes,...
            // 必须在 PASSWORD_KEYBOARD 之前检测，否则 Title+Notes 会被误判为 normal 类型
            lowerLine.contains("type") && lowerLine.contains("title") &&
            lowerLine.contains("data") && headerSet.contains("id") ->
                CsvFormat.APP_EXPORT

            // Proton Pass CSV: type,name,url,email,username,password,note,totp,createTime,modifyTime,vault
            headerSet.contains("type") &&
            headerSet.contains("name") &&
            headerSet.contains("url") &&
            headerSet.contains("email") &&
            headerSet.contains("username") &&
            headerSet.contains("password") &&
            headerSet.contains("totp") ->
                CsvFormat.PROTON_PASS_PASSWORD

            // Chrome 标准导出头：name,url,username,password,note
            // 需要在密码键盘表头检测之前判断，避免被误识别为 password_keyboard
            headerSet.contains("name") &&
            headerSet.contains("url") &&
            headerSet.contains("username") &&
            headerSet.contains("password") ->
                CsvFormat.CHROME_PASSWORD

            // 密码键盘软件格式（表头行）
            // password: username,password,title,remarks,url,tag,custom
            // normal: title,remarks,tag,custom
            // card: cardNo,title,password,date,remarks,tag
            headerSet.let { headers ->
                val hasTitle = headers.contains("title")
                val hasRemarks =
                    headers.contains("remarks") || headers.contains("remark") || headers.contains("notes") || headers.contains("note")
                val hasCardShape =
                    (headers.contains("cardno") ||
                        headers.contains("card_no") ||
                        headers.contains("cardnumber") ||
                        headers.contains("card number") ||
                        headers.contains("卡号")) &&
                        hasTitle
                val hasPasswordShape =
                    headers.contains("username") && headers.contains("password") && hasTitle && hasRemarks
                val hasNormalShape = hasTitle && hasRemarks && !headers.contains("password")
                hasPasswordShape || hasNormalShape || hasCardShape
            } -> CsvFormat.PASSWORD_KEYBOARD

            lowerLine.contains("login_username") &&
            lowerLine.contains("login_password") ->
                CsvFormat.BITWARDEN_PASSWORD
            
            lowerLine.contains("title") && 
            (lowerLine.contains("user name") || lowerLine.contains("username")) &&
            lowerLine.contains("password") -> 
                CsvFormat.KEEPASS_PASSWORD
            
            lowerLine.contains("type") && lowerLine.contains("title") && 
            lowerLine.contains("data") -> 
                CsvFormat.APP_EXPORT
            
            else -> {
                // 根据字段数量推测
                val fields = firstLineFields
                when {
                    fields.size >= 9 && fields.firstOrNull()?.trim()?.toLongOrNull() != null -> CsvFormat.APP_EXPORT
                    fields.size == 5 -> CsvFormat.CHROME_PASSWORD
                    fields.size >= 12 -> CsvFormat.ALIPAY_TRANSACTION
                    else -> CsvFormat.UNKNOWN
                }
            }
        }
    }
    
    /**
     * 根据格式创建导出项
     */
    private fun createExportItemFromFormat(
        fields: List<String>,
        format: CsvFormat,
        headerIndexMap: Map<String, Int>? = null,
        passwordKeyboardTagHandling: PasswordKeyboardTagHandling = PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD
    ): ExportItem? {
        return try {
            when (format) {
                CsvFormat.APP_EXPORT -> {
                    if (fields.size >= 9) {
                        createExportItem(fields)
                    } else null
                }
                
                CsvFormat.CHROME_PASSWORD -> {
                    if (fields.size >= 4) {
                        // Chrome格式: name,url,username,password,note
                        val name = fields.getOrNull(0)?.trim() ?: ""
                        val url = fields.getOrNull(1)?.trim() ?: ""
                        val username = fields.getOrNull(2)?.trim() ?: ""
                        val password = fields.getOrNull(3)?.trim() ?: ""
                        val note = fields.getOrNull(4)?.trim() ?: ""
                        
                        // 跳过空记录
                        if (name.isBlank() && username.isBlank() && password.isBlank()) {
                            return null
                        }
                        
                        // 转换为密码条目格式 (使用应用的格式: username:xxx;password:xxx;website:xxx)
                        val passwordData = buildString {
                            append("username:$username;")
                            append("password:$password")
                            if (url.isNotEmpty()) {
                                append(";website:$url")
                            }
                        }
                        
                        ExportItem(
                            id = 0,
                            itemType = "PASSWORD",
                            title = name.ifBlank { url.ifBlank { username } },
                            itemData = passwordData,
                            notes = note,
                            isFavorite = false,
                            imagePaths = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else null
                }
                
                CsvFormat.KEEPASS_PASSWORD -> {
                    if (fields.size >= 3) {
                        val title = getFieldValue(fields, headerIndexMap, listOf("title", "标题", "account", "账户", "name", "名称")) 
                            ?: fields.getOrNull(0)?.trim().orEmpty()
                        val username = getFieldValue(fields, headerIndexMap, listOf("user name", "username", "user_name", "login name", "login", "login_username", "用户名", "账号", "登录名"))
                            ?: fields.getOrNull(1)?.trim().orEmpty()
                        val password = getFieldValue(fields, headerIndexMap, listOf("password", "pass", "pwd", "login_password", "密码", "口令"))
                            ?: fields.getOrNull(2)?.trim().orEmpty()
                        val url = getFieldValue(fields, headerIndexMap, listOf("url", "website", "web site", "web_site", "location", "address", "login_uri", "网址", "链接", "地址"))
                            ?: fields.getOrNull(3)?.trim().orEmpty()
                        val note = getFieldValue(fields, headerIndexMap, listOf("notes", "note", "comment", "comments", "description", "备注", "注释", "描述"))
                            ?: fields.getOrNull(4)?.trim().orEmpty()
                        
                        if (title.isBlank() && username.isBlank() && password.isBlank()) {
                            return null
                        }
                        
                        val passwordData = buildString {
                            append("username:$username;")
                            append("password:$password")
                            if (url.isNotEmpty()) {
                                append(";website:$url")
                            }
                        }
                        
                        ExportItem(
                            id = 0,
                            itemType = "PASSWORD",
                            title = title.ifBlank { url.ifBlank { username } },
                            itemData = passwordData,
                            notes = note,
                            isFavorite = false,
                            imagePaths = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else null
                }

                CsvFormat.BITWARDEN_PASSWORD -> {
                    if (fields.size >= 4) {
                        val type = getFieldValue(fields, headerIndexMap, listOf("type"))
                            ?: fields.getOrNull(2)?.trim().orEmpty()
                        if (type.isNotBlank() && type.lowercase() != "login") {
                            return null
                        }

                        val title = getFieldValue(fields, headerIndexMap, listOf("name", "title", "标题"))
                            ?: fields.getOrNull(3)?.trim().orEmpty()
                        val username = getFieldValue(fields, headerIndexMap, listOf("login_username", "username", "user name", "user_name"))
                            ?: fields.getOrNull(8)?.trim().orEmpty()
                        val password = getFieldValue(fields, headerIndexMap, listOf("login_password", "password", "pass", "pwd"))
                            ?: fields.getOrNull(9)?.trim().orEmpty()
                        val url = getFieldValue(fields, headerIndexMap, listOf("login_uri", "url", "website", "web site", "web_site"))
                            ?: fields.getOrNull(7)?.trim().orEmpty()
                        val note = getFieldValue(fields, headerIndexMap, listOf("notes", "note", "comment", "comments", "description"))
                            ?: fields.getOrNull(4)?.trim().orEmpty()
                        val isFavorite = parseBooleanLike(
                            getFieldValue(fields, headerIndexMap, listOf("favorite", "favourite", "isfavorite"))
                                ?: fields.getOrNull(1)?.trim().orEmpty()
                        )

                        if (title.isBlank() && username.isBlank() && password.isBlank() && url.isBlank()) {
                            return null
                        }

                        val passwordData = buildString {
                            append("username:$username;")
                            append("password:$password")
                            if (url.isNotEmpty()) {
                                append(";website:$url")
                            }
                        }

                        ExportItem(
                            id = 0,
                            itemType = "PASSWORD",
                            title = title.ifBlank { url.ifBlank { username } },
                            itemData = passwordData,
                            notes = note,
                            isFavorite = isFavorite,
                            imagePaths = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    } else null
                }

                CsvFormat.PROTON_PASS_PASSWORD -> {
                    if (fields.size >= 4) {
                        val type = getFieldValue(fields, headerIndexMap, listOf("type"))
                            ?: fields.getOrNull(0)?.trim().orEmpty()
                        if (type.isNotBlank() && type.lowercase() != "login") {
                            return null
                        }

                        val title = getFieldValue(fields, headerIndexMap, listOf("name", "title"))
                            ?: fields.getOrNull(1)?.trim().orEmpty()
                        val url = getFieldValue(fields, headerIndexMap, listOf("url", "website", "uri"))
                            ?: fields.getOrNull(2)?.trim().orEmpty()
                        val email = getFieldValue(fields, headerIndexMap, listOf("email", "mail"))
                            ?: fields.getOrNull(3)?.trim().orEmpty()
                        val username = getFieldValue(fields, headerIndexMap, listOf("username", "user name", "user_name", "login"))
                            ?: fields.getOrNull(4)?.trim().orEmpty()
                        val password = getFieldValue(fields, headerIndexMap, listOf("password", "pass", "pwd"))
                            ?: fields.getOrNull(5)?.trim().orEmpty()
                        val note = getFieldValue(fields, headerIndexMap, listOf("note", "notes", "description"))
                            ?: fields.getOrNull(6)?.trim().orEmpty()
                        val totp = getFieldValue(fields, headerIndexMap, listOf("totp", "otp", "2fa", "authenticator"))
                            ?: fields.getOrNull(7)?.trim().orEmpty()
                        val createdAt = parseEpochSecondsOrMillis(
                            getFieldValue(fields, headerIndexMap, listOf("createtime", "created", "createdat"))
                                ?: fields.getOrNull(8)?.trim().orEmpty()
                        )
                        val updatedAt = parseEpochSecondsOrMillis(
                            getFieldValue(fields, headerIndexMap, listOf("modifytime", "modified", "updated", "updatedat"))
                                ?: fields.getOrNull(9)?.trim().orEmpty()
                        )
                        val vault = getFieldValue(fields, headerIndexMap, listOf("vault", "vaultname"))
                            ?: fields.getOrNull(10)?.trim().orEmpty()

                        if (title.isBlank() && username.isBlank() && email.isBlank() && password.isBlank() && url.isBlank()) {
                            return null
                        }

                        val passwordData = buildString {
                            append("username:${username.ifBlank { email }};")
                            append("password:$password")
                            if (url.isNotEmpty()) {
                                append(";website:$url")
                            }
                            if (email.isNotEmpty()) {
                                append(";email:$email")
                            }
                        }

                        val importedCustomFields = buildList {
                            if (vault.isNotBlank()) {
                                add(
                                    ImportedCustomField(
                                        title = "Proton Vault",
                                        value = vault,
                                        isProtected = false
                                    )
                                )
                            }
                        }

                        ExportItem(
                            id = 0,
                            itemType = "PASSWORD",
                            title = title.ifBlank { url.ifBlank { username.ifBlank { email.ifBlank { "Imported Password" } } } },
                            itemData = passwordData,
                            notes = note,
                            isFavorite = false,
                            imagePaths = "",
                            createdAt = createdAt ?: System.currentTimeMillis(),
                            updatedAt = updatedAt ?: System.currentTimeMillis(),
                            importedCustomFields = importedCustomFields,
                            importedAuthenticatorKey = totp.trim().takeIf { it.isNotBlank() }
                        )
                    } else null
                }

                CsvFormat.PASSWORD_KEYBOARD -> {
                    if (fields.size >= 2) {
                        val recordType = resolvePasswordKeyboardRecordType(fields, headerIndexMap)
                        when (recordType) {
                            PasswordKeyboardRecordType.NOTE -> {
                                return createPasswordKeyboardNoteExportItem(fields, headerIndexMap)
                            }

                            PasswordKeyboardRecordType.CARD -> {
                                return createPasswordKeyboardCardExportItem(
                                    fields = fields,
                                    headerIndexMap = headerIndexMap,
                                    passwordKeyboardTagHandling = passwordKeyboardTagHandling
                                )
                            }

                            PasswordKeyboardRecordType.PASSWORD -> Unit
                        }

                        // 密码键盘软件格式: username,password,title,remarks,url,tag,custom
                        val username = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("username", "user name", "user_name", "login", "login_username", "用户名", "账号")
                        ) ?: fields.getOrNull(0)?.trim().orEmpty()
                        val password = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("password", "pass", "pwd", "login_password", "密码", "口令")
                        ) ?: fields.getOrNull(1)?.trim().orEmpty()
                        val title = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("title", "name", "标题", "名称")
                        ) ?: fields.getOrNull(2)?.trim().orEmpty()
                        val remarks = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("remarks", "remark", "notes", "note", "comment", "description", "备注", "说明")
                        ) ?: fields.getOrNull(3)?.trim().orEmpty()
                        val rawUrl = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("url", "website", "web site", "web_site", "link", "链接", "网址")
                        ) ?: fields.getOrNull(4)?.trim().orEmpty()
                        val url = normalizePasswordKeyboardWebsite(rawUrl)
                        val tag = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("tag", "tags", "label", "labels", "标签", "分类")
                        ) ?: fields.getOrNull(5)?.trim().orEmpty()
                        val custom = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf("custom", "custom_fields", "customfields", "extra", "extend", "扩展", "自定义字段")
                        ) ?: fields.getOrNull(6)?.trim().orEmpty()
                        val validator = getFieldValue(
                            fields,
                            headerIndexMap,
                            listOf(
                                "validator",
                                "totp",
                                "otp",
                                "2fa",
                                "authenticator",
                                "verification",
                                "verification_code",
                                "验证器",
                                "动态码",
                                "二步验证"
                            )
                        ) ?: fields.getOrNull(7)?.trim().orEmpty()

                        if (title.isBlank() && username.isBlank() && password.isBlank() && url.isBlank()) {
                            return null
                        }

                        val passwordData = buildString {
                            append("username:$username;")
                            append("password:$password")
                            if (url.isNotEmpty()) {
                                append(";website:$url")
                            }
                        }

                        val importedCustomFields = buildList {
                            if (
                                passwordKeyboardTagHandling == PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD &&
                                tag.isNotBlank()
                            ) {
                                add(
                                    ImportedCustomField(
                                        title = "标签",
                                        value = tag,
                                        isProtected = false
                                    )
                                )
                            }
                            addAll(parsePasswordKeyboardCustomFields(custom))
                        }

                        val normalizedImportedCustomFields = importedCustomFields
                            .map {
                                it.copy(
                                    title = it.title.trim(),
                                    value = it.value.trim()
                                )
                            }
                            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
                            .distinctBy { it.title.lowercase() to it.value }

                        ExportItem(
                            id = 0,
                            itemType = "PASSWORD",
                            title = title.ifBlank { url.ifBlank { username.ifBlank { "Imported Password" } } },
                            itemData = passwordData,
                            notes = remarks,
                            isFavorite = false,
                            imagePaths = "",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            importedCustomFields = normalizedImportedCustomFields,
                            importedAuthenticatorKey = validator
                                .trim()
                                .takeIf { it.isNotBlank() }
                        )
                    } else null
                }
                
                CsvFormat.ALIPAY_TRANSACTION -> {
                    // 支付宝格式在专门的方法中处理,这里返回null
                    null
                }
                
                CsvFormat.UNKNOWN -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "创建导出项失败: ${e.message}", e)
            null
        }
    }
    
    private fun buildHeaderIndexMap(headers: List<String>): Map<String, Int> {
        return headers.mapIndexedNotNull { index, header ->
            val normalized = header.trim().lowercase()
            if (normalized.isNotBlank()) normalized to index else null
        }.toMap()
    }
    
    private fun getFieldValue(
        fields: List<String>,
        headerIndexMap: Map<String, Int>?,
        keys: List<String>
    ): String? {
        if (headerIndexMap == null) return null
        val index = keys.firstNotNullOfOrNull { key ->
            headerIndexMap[key]
        } ?: return null
        return fields.getOrNull(index)?.trim()
    }

    private fun isHeaderLine(firstLine: String, format: CsvFormat): Boolean {
        return when (format) {
            CsvFormat.APP_EXPORT -> {
                val headers = parseCsvLine(firstLine).map { it.trim().lowercase() }.toSet()
                headers.contains("type") &&
                    headers.contains("title") &&
                    headers.contains("data")
            }
            CsvFormat.CHROME_PASSWORD -> {
                val headers = parseCsvLine(firstLine).map { it.trim().lowercase() }.toSet()
                headers.contains("name") &&
                    headers.contains("url") &&
                    headers.contains("username") &&
                    headers.contains("password")
            }
            CsvFormat.KEEPASS_PASSWORD -> {
                val headers = parseCsvLine(firstLine).map { it.trim().lowercase() }.toSet()
                val knownHeaders = setOf("title", "user name", "username", "password", "url", "notes", "name", "account")
                headers.intersect(knownHeaders).size >= 2
            }
            CsvFormat.BITWARDEN_PASSWORD -> {
                val headers = parseCsvLine(firstLine).map { it.trim().lowercase() }.toSet()
                headers.contains("login_username") && headers.contains("login_password")
            }
            CsvFormat.PROTON_PASS_PASSWORD -> {
                val headers = parseCsvLine(firstLine).map { it.trim().lowercase() }.toSet()
                headers.contains("type") &&
                    headers.contains("name") &&
                    headers.contains("url") &&
                    headers.contains("email") &&
                    headers.contains("username") &&
                    headers.contains("password") &&
                    headers.contains("totp")
            }
            CsvFormat.PASSWORD_KEYBOARD -> {
                val fields = parseCsvLine(firstLine)
                val normalized = fields.map { it.trim().lowercase() }.toSet()
                val hasCardHeader =
                    (normalized.contains("cardno") ||
                        normalized.contains("card_no") ||
                        normalized.contains("cardnumber") ||
                        normalized.contains("card number") ||
                        normalized.contains("卡号")) &&
                        normalized.contains("title")
                val hasPasswordHeader =
                    normalized.contains("username") && normalized.contains("password") && normalized.contains("title")
                val hasNormalHeader =
                    normalized.contains("title") &&
                        (normalized.contains("remarks") || normalized.contains("remark") || normalized.contains("notes") || normalized.contains("note")) &&
                        !normalized.contains("password")
                isPasswordKeyboardSignature(fields) ||
                    hasCardHeader ||
                    hasPasswordHeader ||
                    hasNormalHeader
            }
            CsvFormat.ALIPAY_TRANSACTION,
            CsvFormat.UNKNOWN -> false
        }
    }

    private fun isPasswordKeyboardSignature(fields: List<String>): Boolean {
        val normalized = fields.map { it.trim().lowercase() }
        val first = normalized.getOrNull(0).orEmpty()
        val second = normalized.getOrNull(1).orEmpty()
        return first == "secretinputexportfile" &&
            (second == "password" || second == "normal" || second == "card")
    }

    private fun resolvePasswordKeyboardRecordType(
        fields: List<String>,
        headerIndexMap: Map<String, Int>?
    ): PasswordKeyboardRecordType {
        if (headerIndexMap == null) return PasswordKeyboardRecordType.PASSWORD

        val hasCardNo = listOf(
            "cardno",
            "card_no",
            "cardnumber",
            "card number",
            "卡号"
        ).any { headerIndexMap.containsKey(it) }

        val hasPassword = listOf(
            "password",
            "pass",
            "pwd",
            "login_password",
            "密码",
            "口令"
        ).any { headerIndexMap.containsKey(it) }

        val hasTitle = listOf("title", "name", "标题", "名称").any { headerIndexMap.containsKey(it) }
        val hasRemarks = listOf(
            "remarks",
            "remark",
            "notes",
            "note",
            "comment",
            "description",
            "content",
            "备注",
            "说明",
            "正文",
            "内容"
        ).any { headerIndexMap.containsKey(it) }

        if (hasCardNo) {
            return PasswordKeyboardRecordType.CARD
        }

        if (!hasPassword && hasTitle && hasRemarks) {
            return PasswordKeyboardRecordType.NOTE
        }

        if (hasPassword) {
            return PasswordKeyboardRecordType.PASSWORD
        }

        return if (hasTitle && hasRemarks) {
            PasswordKeyboardRecordType.NOTE
        } else {
            PasswordKeyboardRecordType.PASSWORD
        }
    }

    private fun createPasswordKeyboardNoteExportItem(
        fields: List<String>,
        headerIndexMap: Map<String, Int>?
    ): ExportItem? {
        val title = getFieldValue(
            fields,
            headerIndexMap,
            listOf("title", "name", "标题", "名称")
        ) ?: fields.getOrNull(0)?.trim().orEmpty()

        val content = getFieldValue(
            fields,
            headerIndexMap,
            listOf(
                "remarks",
                "remark",
                "notes",
                "note",
                "comment",
                "description",
                "content",
                "备注",
                "说明",
                "正文",
                "内容"
            )
        ) ?: fields.getOrNull(1)?.trim().orEmpty()

        val rawTag = getFieldValue(
            fields,
            headerIndexMap,
            listOf("tag", "tags", "label", "labels", "标签", "分类")
        ) ?: fields.getOrNull(2)?.trim().orEmpty()

        if (title.isBlank() && content.isBlank() && rawTag.isBlank()) {
            return null
        }

        val noteData = NoteData(
            content = content,
            tags = parsePasswordKeyboardNoteTags(rawTag),
            isMarkdown = false
        )

        return ExportItem(
            id = 0,
            itemType = "NOTE",
            title = title.ifBlank { "Imported Note" },
            itemData = Json.encodeToString(noteData),
            notes = content,
            isFavorite = false,
            imagePaths = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun createPasswordKeyboardCardExportItem(
        fields: List<String>,
        headerIndexMap: Map<String, Int>?,
        passwordKeyboardTagHandling: PasswordKeyboardTagHandling
    ): ExportItem? {
        val cardNumber = getFieldValue(
            fields,
            headerIndexMap,
            listOf("cardno", "card_no", "cardnumber", "card number", "number", "卡号")
        ) ?: fields.getOrNull(0)?.trim().orEmpty()

        val title = getFieldValue(
            fields,
            headerIndexMap,
            listOf("title", "name", "标题", "名称")
        ) ?: fields.getOrNull(1)?.trim().orEmpty()

        val cardPassword = getFieldValue(
            fields,
            headerIndexMap,
            listOf("password", "pass", "pwd", "pin", "card_password", "密码", "卡密", "支付密码")
        ) ?: fields.getOrNull(2)?.trim().orEmpty()

        val rawDate = getFieldValue(
            fields,
            headerIndexMap,
            listOf("date", "expiry", "expire", "exp", "expire_date", "expiry_date", "有效期", "到期", "日期")
        ) ?: fields.getOrNull(3)?.trim().orEmpty()

        val remarks = getFieldValue(
            fields,
            headerIndexMap,
            listOf("remarks", "remark", "notes", "note", "comment", "description", "备注", "说明")
        ) ?: fields.getOrNull(4)?.trim().orEmpty()

        val rawTag = getFieldValue(
            fields,
            headerIndexMap,
            listOf("tag", "tags", "label", "labels", "标签", "分类")
        ) ?: fields.getOrNull(5)?.trim().orEmpty()

        val rawCustom = getFieldValue(
            fields,
            headerIndexMap,
            listOf("custom", "custom_fields", "customfields", "extra", "extend", "扩展", "自定义字段")
        ) ?: fields.getOrNull(6)?.trim().orEmpty()

        if (cardNumber.isBlank() && title.isBlank() && cardPassword.isBlank() && rawDate.isBlank() && remarks.isBlank()) {
            return null
        }

        val parsedExpiry = parsePasswordKeyboardCardExpiry(rawDate)
        val importedCustomFields = buildList {
            if (
                passwordKeyboardTagHandling == PasswordKeyboardTagHandling.CONVERT_TO_CUSTOM_FIELD &&
                rawTag.isNotBlank()
            ) {
                add(
                    ImportedCustomField(
                        title = "标签",
                        value = rawTag,
                        isProtected = false
                    )
                )
            }
            if (rawDate.isNotBlank() && parsedExpiry == null) {
                add(
                    ImportedCustomField(
                        title = "日期",
                        value = rawDate,
                        isProtected = false
                    )
                )
            }
            addAll(parsePasswordKeyboardCustomFields(rawCustom))
        }

        val normalizedImportedCustomFields = importedCustomFields
            .map {
                it.copy(
                    title = it.title.trim(),
                    value = it.value.trim()
                )
            }
            .filter { it.title.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.title.lowercase() to it.value }

        val secureCustomFields = normalizedImportedCustomFields.map { field ->
            SecureCustomField(
                label = field.title,
                value = field.value,
                type = if (field.isProtected) SecureCustomFieldType.HIDDEN else SecureCustomFieldType.TEXT
            )
        }

        val bankCardData = BankCardData(
            cardNumber = cardNumber,
            cardholderName = "",
            expiryMonth = parsedExpiry?.first.orEmpty(),
            expiryYear = parsedExpiry?.second.orEmpty(),
            pin = cardPassword,
            customFields = secureCustomFields
        )

        return ExportItem(
            id = 0,
            itemType = "BANK_CARD",
            title = title.ifBlank { cardNumber.ifBlank { "Imported Card" } },
            itemData = Json.encodeToString(bankCardData),
            notes = remarks,
            isFavorite = false,
            imagePaths = "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun parsePasswordKeyboardCardExpiry(rawDate: String): Pair<String, String>? {
        val normalizedRaw = rawDate.trim()
        if (normalizedRaw.isBlank()) return null

        val tokens = normalizedRaw
            .split(Regex("[^0-9]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (tokens.size >= 2) {
            val first = tokens[0]
            val second = tokens[1]
            val fromPair = when {
                first.length == 4 && second.length in 1..2 -> second to first
                second.length == 4 && first.length in 1..2 -> first to second
                first.length in 1..2 && second.length in 2..4 -> first to second
                else -> null
            }
            fromPair?.let { (monthRaw, yearRaw) ->
                val month = monthRaw.toIntOrNull()
                val year = normalizePasswordKeyboardCardExpiryYear(yearRaw)
                if (month != null && month in 1..12 && year != null) {
                    return month.toString().padStart(2, '0') to year
                }
            }
        }

        val compact = normalizedRaw.filter { it.isDigit() }
        if (compact.length == 4) {
            val month = compact.substring(0, 2).toIntOrNull()
            val year = normalizePasswordKeyboardCardExpiryYear(compact.substring(2))
            if (month != null && month in 1..12 && year != null) {
                return month.toString().padStart(2, '0') to year
            }
        }
        if (compact.length == 6) {
            val month = compact.substring(0, 2).toIntOrNull()
            val year = normalizePasswordKeyboardCardExpiryYear(compact.substring(2))
            if (month != null && month in 1..12 && year != null) {
                return month.toString().padStart(2, '0') to year
            }
        }

        return null
    }

    private fun normalizePasswordKeyboardCardExpiryYear(rawYear: String): String? {
        val digits = rawYear.filter { it.isDigit() }
        return when (digits.length) {
            2 -> "20$digits"
            4 -> digits
            else -> null
        }
    }

    private fun normalizePasswordKeyboardWebsite(rawUrl: String): String {
        val raw = rawUrl.trim()
        if (raw.isBlank()) return raw

        val fromJson = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(raw)
        }.getOrNull()?.let(::extractWebsiteTextFromJsonElement)

        if (!fromJson.isNullOrBlank()) {
            return fromJson
        }

        if (raw.startsWith("[") && raw.endsWith("]")) {
            val inner = raw.removePrefix("[").removeSuffix("]").trim()
            val unquoted = inner
                .removePrefix("\"")
                .removeSuffix("\"")
                .removePrefix("'")
                .removeSuffix("'")
                .trim()
            if (unquoted.isNotBlank()) {
                return unquoted
            }
        }

        return raw
    }

    private fun extractWebsiteTextFromJsonElement(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> {
                val priorityKeys = listOf("url", "link", "website", "value", "name", "title", "text")
                priorityKeys.firstNotNullOfOrNull { key ->
                    element[key]?.let(::extractWebsiteTextFromJsonElement)
                } ?: element.values.asSequence()
                    .mapNotNull(::extractWebsiteTextFromJsonElement)
                    .firstOrNull()
            }

            is kotlinx.serialization.json.JsonArray -> element.asSequence()
                .mapNotNull(::extractWebsiteTextFromJsonElement)
                .firstOrNull()

            else -> element.jsonPrimitive.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parsePasswordKeyboardNoteTags(rawTag: String): List<String> {
        val normalizedRaw = rawTag.trim()
        if (normalizedRaw.isBlank()) return emptyList()

        val fromJson = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(normalizedRaw)
        }.getOrNull()?.let { element ->
            when (element) {
                is JsonObject -> element["tags"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                is kotlinx.serialization.json.JsonArray -> element
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                else -> null
            }
        } ?: emptyList()

        if (fromJson.isNotEmpty()) {
            return fromJson.distinctBy { it.lowercase(Locale.ROOT) }
        }

        return normalizedRaw
            .split(Regex("[,，;；|\\n\\t]+")).asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun parsePasswordKeyboardCustomFields(rawCustom: String): List<ImportedCustomField> {
        val raw = rawCustom.trim()
        if (raw.isBlank() || raw == "{}" || raw == "[]") return emptyList()

        return try {
            val element = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> {
                    element.entries.mapNotNull { (key, valueElement) ->
                        val value = jsonElementAsDisplayText(valueElement)
                        if (key.isBlank() || value.isBlank()) {
                            null
                        } else {
                            ImportedCustomField(
                                title = key,
                                value = value,
                                isProtected = looksSensitiveField(key)
                            )
                        }
                    }
                }

                is kotlinx.serialization.json.JsonArray -> {
                    element.mapIndexedNotNull { index, item ->
                        val itemObj = item as? JsonObject
                        if (itemObj != null) {
                            val title = itemObj["title"]?.jsonPrimitive?.contentOrNull
                                ?: itemObj["name"]?.jsonPrimitive?.contentOrNull
                                ?: itemObj["key"]?.jsonPrimitive?.contentOrNull
                                ?: "custom_${index + 1}"
                            val valueElement = itemObj["value"] ?: itemObj["val"] ?: itemObj["data"]
                            val value = valueElement?.let(::jsonElementAsDisplayText).orEmpty()
                            if (title.isBlank() || value.isBlank()) {
                                null
                            } else {
                                ImportedCustomField(
                                    title = title,
                                    value = value,
                                    isProtected = looksSensitiveField(title)
                                )
                            }
                        } else {
                            val value = jsonElementAsDisplayText(item)
                            if (value.isBlank()) {
                                null
                            } else {
                                ImportedCustomField(
                                    title = "custom_${index + 1}",
                                    value = value,
                                    isProtected = false
                                )
                            }
                        }
                    }
                }

                else -> {
                    listOf(
                        ImportedCustomField(
                            title = "custom",
                            value = jsonElementAsDisplayText(element),
                            isProtected = false
                        )
                    )
                }
            }
        } catch (_: Exception) {
            listOf(
                ImportedCustomField(
                    title = "custom",
                    value = raw,
                    isProtected = false
                )
            )
        }
    }

    private fun jsonElementAsDisplayText(element: JsonElement): String {
        return when (element) {
            is JsonObject -> element.toString()
            is kotlinx.serialization.json.JsonArray -> element.toString()
            else -> element.jsonPrimitive.contentOrNull ?: element.toString()
        }
    }

    private fun looksSensitiveField(title: String): Boolean {
        val lower = title.trim().lowercase(Locale.ROOT)
        return lower.contains("password") ||
            lower.contains("secret") ||
            lower.contains("token") ||
            lower.contains("otp") ||
            lower.contains("验证码") ||
            lower.contains("密钥")
    }

    private fun parseBooleanLike(value: String): Boolean {
        return when (value.trim().lowercase()) {
            "1", "true", "yes", "y" -> true
            else -> false
        }
    }

    private fun parseEpochSecondsOrMillis(value: String): Long? {
        val timestamp = value.trim().toLongOrNull() ?: return null
        return if (timestamp in 1..9_999_999_999L) {
            timestamp * 1000
        } else {
            timestamp
        }
    }

    /**
     * 读取一条完整的CSV记录，支持包含换行的带引号字段
     */
    private fun readCsvRecord(reader: BufferedReader): String? {
        val builder = StringBuilder()
        var inQuotes = false
        var line: String?

        // 初始行
        line = reader.readLine() ?: return null
        builder.append(line)
        inQuotes = toggleQuoteState(builder.toString())

        // 如果引号未闭合，继续读取下一行并追加，直到闭合或文件结束
        while (inQuotes) {
            val next = reader.readLine() ?: break
            builder.append('\n').append(next)
            inQuotes = toggleQuoteState(builder.toString())
        }

        return builder.toString()
    }

    /**
     * 根据CSV引号规则检测当前文本是否处于未闭合的引号状态
     */
    private fun toggleQuoteState(text: String): Boolean {
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && inQuotes && i + 1 < text.length && text[i + 1] == '"' -> {
                    // 转义的引号，跳过
                    i++
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                }
            }
            i++
        }
        return inQuotes
    }

    /**
     * 解析CSV行（处理带引号的字段）
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0
        
        try {
            while (i < line.length) {
                val char = line[i]
                
                when {
                    char == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                        // 转义的引号
                        currentField.append('"')
                        i++
                    }
                    char == '"' -> {
                        inQuotes = !inQuotes
                    }
                    char == ',' && !inQuotes -> {
                        fields.add(currentField.toString().trim())
                        currentField.clear()
                    }
                    else -> {
                        currentField.append(char)
                    }
                }
                i++
            }
            fields.add(currentField.toString().trim())
        } catch (e: Exception) {
            android.util.Log.e("DataImport", "解析CSV行失败: length=${line.length}", e)
            // 返回当前已解析的字段
        }
        
        return fields
    }

/**
     * 从Aegis JSON文件导入TOTP数据
     * @param inputUri 输入文件的URI
     * @return 导入的数据项列表
     */
    suspend fun importAegisJson(
        inputUri: Uri
    ): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("无法读取文件，请检查文件是否存在"))
        
            inputStream.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val content = reader.readText()
                
                // 解析JSON
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(content).jsonObject
                
                // 检查是否为加密的vault
                val dbField = root["db"]
                if (dbField != null && dbField is kotlinx.serialization.json.JsonPrimitive) {
                    // 这是一个加密的vault，我们无法解密它
                    return@withContext Result.failure(Exception("无法导入加密的Aegis备份文件。请导出未加密的JSON文件。"))
                }
                
                // 尝试解析未加密的数据库格式
                val entriesArray = try {
                    // 首先尝试从db字段中获取entries
                    val dbObj = dbField?.jsonObject
                    val dbEntries = if (dbObj != null) {
                        dbObj["entries"]?.jsonArray
                    } else {
                        null
                    }
                    dbEntries ?: root["entries"]?.jsonArray
                } catch (e: Exception) {
                    // 如果上面的方法失败，尝试直接解析根对象中的entries数组
                    try {
                        root["entries"]?.jsonArray
                    } catch (ex: Exception) {
                        null
                    }
                }
                
                if (entriesArray == null) {
                    return@withContext Result.failure(Exception("无效的Aegis JSON格式：未找到entries数组"))
                }
                
                val entries = mutableListOf<AegisEntry>()
                var parsedCount = 0
                var errorCount = 0
                
                entriesArray.forEach { element ->
                    try {
                        // 确保element是JsonObject类型
                        if (element is kotlinx.serialization.json.JsonObject) {
                            val entryObj = element
                            val type = entryObj["type"]?.jsonPrimitive?.content ?: "totp"
                            
                            // 只处理TOTP条目
                            if (type.lowercase() == "totp") {
                                val uuid = entryObj["uuid"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString()
                                val name = entryObj["name"]?.jsonPrimitive?.content ?: ""
                                val issuer = entryObj["issuer"]?.jsonPrimitive?.content ?: ""
                                val note = entryObj["note"]?.jsonPrimitive?.content ?: ""
                                
                                // 获取info对象
                                val infoObj = entryObj["info"]?.jsonObject
                                if (infoObj != null) {
                                    val secret = infoObj["secret"]?.jsonPrimitive?.content ?: ""
                                    val algo = infoObj["algo"]?.jsonPrimitive?.content ?: "SHA1"
                                    val digits = infoObj["digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6
                                    val period = infoObj["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
                                    
                                    if (secret.isNotBlank()) {
                                        val entry = AegisEntry(
                                            uuid = uuid,
                                            name = name,
                                            issuer = issuer,
                                            note = note,
                                            secret = secret,
                                            algorithm = algo,
                                            digits = digits,
                                            period = period
                                        )
                                        entries.add(entry)
                                        parsedCount++
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorCount++
                        android.util.Log.e("AegisImport", "解析条目失败", e)
                    }
                }
                
                android.util.Log.d("AegisImport", "成功解析 $parsedCount 条目，$errorCount 个错误")
                
                if (entries.isEmpty()) {
                    Result.failure(Exception("未能从Aegis文件中导入任何有效的TOTP条目"))
                } else {
                    Result.success(entries)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AegisImport", "导入Aegis文件失败", e)
            Result.failure(Exception("导入Aegis文件失败：${e.message ?: "未知错误"}"))
        }
    }

    // Aegis条目数据类
    data class AegisEntry(
        val uuid: String,
        val name: String,
        val issuer: String,
        val note: String,
        val secret: String,
        val algorithm: String,
        val digits: Int,
        val period: Int
    )

    data class SteamGuardImportEntry(
        val name: String,
        val issuer: String = "Steam",
        val note: String = "",
        val secretBase32: String,
        val deviceId: String,
        val serialNumber: String,
        val sharedSecretBase64: String,
        val revocationCode: String,
        val identitySecret: String,
        val tokenGid: String,
        val rawSteamGuardJson: String,
        val fingerprint: String
    )
    
    /**
     * 检查文件是否为加密的Aegis文件
     * @param inputUri 输入文件的URI
     * @return 如果是加密文件返回true，否则返回false
     */
    suspend fun isEncryptedAegisFile(inputUri: Uri): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.success(false)
        
            inputStream.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val content = reader.readText()
                
                val decryptor = AegisDecryptor()
                Result.success(decryptor.isEncryptedAegisFile(content))
            }
        } catch (e: Exception) {
            android.util.Log.e("AegisCheck", "检查Aegis文件失败", e)
            Result.success(false)
        }
    }
    
    /**
     * 从加密的Aegis JSON文件导入TOTP数据
     * @param inputUri 输入文件的URI
     * @param password 解密密码
     * @return 导入的数据项列表
     */
    suspend fun importEncryptedAegisJson(
        inputUri: Uri,
        password: String
    ): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("无法读取文件，请检查文件是否存在"))
        
            inputStream.use { input ->
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val content = reader.readText()
                
                // 解析JSON
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(content).jsonObject
                
                // 获取header信息
                val header = root["header"]?.jsonObject
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少header"))
                
                // 获取slots信息
                val slots = header["slots"]?.jsonArray
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少slots"))
                
                if (slots.isEmpty()) {
                    return@withContext Result.failure(Exception("无效的Aegis文件格式：slots为空"))
                }
                
                // 获取第一个slot
                val slot = slots[0].jsonObject
                val slotType = slot["type"]?.jsonPrimitive?.content?.toIntOrNull()
                if (slotType != 1) {
                    return@withContext Result.failure(Exception("不支持的slot类型: $slotType"))
                }
                
                val salt = slot["salt"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少salt"))
                
                val key = slot["key"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少key"))
                
                val keyParams = slot["key_params"]?.jsonObject
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少key_params"))
                
                val nonce = keyParams["nonce"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少nonce"))
                
                val tag = keyParams["tag"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少tag"))
                
                // 获取加密的db数据
                val encryptedDb = root["db"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("无效的Aegis文件格式：缺少db"))
                
                // 解密数据
                val decryptor = AegisDecryptor()
                val keyParamsObj = AegisDecryptor.KeyParams(nonce, tag)
                
                // 首先解密主密钥
                val decryptedKey = try {
                    decryptor.decryptMasterKey(password, salt, keyParamsObj, key)
                } catch (e: Exception) {
                    android.util.Log.e("EncryptedAegisImport", "解密主密钥失败", e)
                    return@withContext Result.failure(Exception("解密主密钥失败：密码错误或文件损坏"))
                }
                
                // 验证解密后的主密钥长度
                if (decryptedKey.size != 32) {
                    return@withContext Result.failure(Exception("解密主密钥失败：密钥长度不正确"))
                }
                
                // 然后使用主密钥解密db字段
                val dbNonce = header["params"]?.jsonObject?.get("nonce")?.jsonPrimitive?.content ?: nonce
                val dbTag = header["params"]?.jsonObject?.get("tag")?.jsonPrimitive?.content ?: tag
                val dbKeyParams = AegisDecryptor.KeyParams(dbNonce, dbTag)
                
                val decryptedDbData = try {
                    // 注意：db字段可能是Base64编码的，而不是十六进制字符串
                    decryptor.decryptWithKeyBase64(decryptedKey, dbKeyParams, encryptedDb)
                } catch (e: Exception) {
                    android.util.Log.e("EncryptedAegisImport", "解密db数据失败", e)
                    return@withContext Result.failure(Exception("解密db数据失败：密码错误或文件损坏"))
                }
                
                // 解析解密后的JSON
                val decryptedContent = String(decryptedDbData, Charsets.UTF_8)
                val decryptedRoot = json.parseToJsonElement(decryptedContent).jsonObject
                val entriesArray = decryptedRoot["entries"]?.jsonArray
                    ?: return@withContext Result.failure(Exception("无效的解密数据：未找到entries数组"))
                
                val entries = mutableListOf<AegisEntry>()
                var parsedCount = 0
                var errorCount = 0
                
                entriesArray.forEach { element ->
                    try {
                        val entryObj = element.jsonObject
                        val type = entryObj["type"]?.jsonPrimitive?.content ?: "totp"
                        
                        // 只处理TOTP条目
                        if (type.lowercase() == "totp") {
                            val uuid = entryObj["uuid"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString()
                            val name = entryObj["name"]?.jsonPrimitive?.content ?: ""
                            val issuer = entryObj["issuer"]?.jsonPrimitive?.content ?: ""
                            val note = entryObj["note"]?.jsonPrimitive?.content ?: ""
                            
                            // 获取info对象
                            val infoObj = entryObj["info"]?.jsonObject
                            if (infoObj != null) {
                                val secret = infoObj["secret"]?.jsonPrimitive?.content ?: ""
                                val algo = infoObj["algo"]?.jsonPrimitive?.content ?: "SHA1"
                                val digits = infoObj["digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6
                                val period = infoObj["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
                                
                                if (secret.isNotBlank()) {
                                    val entry = AegisEntry(
                                        uuid = uuid,
                                        name = name,
                                        issuer = issuer,
                                        note = note,
                                        secret = secret,
                                        algorithm = algo,
                                        digits = digits,
                                        period = period
                                    )
                                    entries.add(entry)
                                    parsedCount++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        errorCount++
                        android.util.Log.e("EncryptedAegisImport", "解析条目失败", e)
                    }
                }
                
                android.util.Log.d("EncryptedAegisImport", "成功解析 $parsedCount 条目，$errorCount 个错误")
                
                if (entries.isEmpty()) {
                    Result.failure(Exception("未能从Aegis文件中导入任何有效的TOTP条目"))
                } else {
                    Result.success(entries)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("EncryptedAegisImport", "导入加密Aegis文件失败", e)
            Result.failure(Exception("导入加密Aegis文件失败：${e.message ?: "未知错误"}"))
        }
    }
    
    /**
     * 从Steam maFile导入验证器数据
     * @param inputUri 输入文件的URI
     * @return 导入的AegisEntry
     */
    suspend fun importSteamMaFileWithMetadata(
        inputUri: Uri,
        customName: String? = null
    ): Result<SteamGuardImportEntry> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("无法读取文件，请检查文件是否存在"))

            inputStream.use { input ->
                val content = BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(content).jsonObject
                parseSteamGuardPayload(
                    root = root,
                    rawSteamGuardJson = content,
                    deviceIdInput = root["device_id"]?.jsonPrimitive?.contentOrNull,
                    customName = customName,
                    requireDeviceId = false
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("SteamImport", "导入失败", e)
            Result.failure(Exception("导入失败：${e.message ?: "未知错误"}"))
        }
    }

    suspend fun importSteamAppCoexist(
        deviceIdInput: String,
        steamGuardJson: String,
        customName: String? = null
    ): Result<SteamGuardImportEntry> = withContext(Dispatchers.IO) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(steamGuardJson).jsonObject
            parseSteamGuardPayload(
                root = root,
                rawSteamGuardJson = steamGuardJson,
                deviceIdInput = deviceIdInput,
                customName = customName,
                requireDeviceId = true
            )
        } catch (e: Exception) {
            android.util.Log.e("SteamImport", "Steam App 共存导入失败", e)
            Result.failure(Exception("导入失败：${e.message ?: "未知错误"}"))
        }
    }

    /**
     * 从Steam maFile导入验证器数据（兼容旧调用，返回简化Aegis条目）
     */
    suspend fun importSteamMaFile(
        inputUri: Uri
    ): Result<AegisEntry> = withContext(Dispatchers.IO) {
        importSteamMaFileWithMetadata(inputUri).map { entry ->
            AegisEntry(
                uuid = java.util.UUID.randomUUID().toString(),
                name = entry.name,
                issuer = entry.issuer,
                note = entry.note,
                secret = entry.secretBase32,
                algorithm = "SHA1",
                digits = 5,
                period = 30
            )
        }
    }

    private fun parseSteamGuardPayload(
        root: JsonObject,
        rawSteamGuardJson: String,
        deviceIdInput: String?,
        customName: String?,
        requireDeviceId: Boolean
    ): Result<SteamGuardImportEntry> {
        val sharedSecret = root["shared_secret"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (sharedSecret.isBlank()) {
            return Result.failure(Exception("无效的 SteamGuard 内容：缺少 shared_secret"))
        }

        val serialNumber = root["serial_number"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (serialNumber.isBlank()) {
            return Result.failure(Exception("无效的 SteamGuard 内容：缺少 serial_number"))
        }

        val normalizedDeviceId = normalizeSteamDeviceId(deviceIdInput?.takeIf { it.isNotBlank() })
            ?: if (requireDeviceId) null else buildFallbackSteamDeviceId(sharedSecret, serialNumber)
        if (normalizedDeviceId == null) {
            return Result.failure(Exception("无效的设备ID（格式应为 android:xxxx）"))
        }

        val decodedBytes = decodeSteamSharedSecret(sharedSecret)
            ?: return Result.failure(Exception("无效的 Steam shared_secret 格式"))

        val secretBase32 = base32Encode(decodedBytes)
        val accountName = customName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: root["account_name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "Steam Guard"

        val revocationCode = root["revocation_code"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val identitySecret = root["identity_secret"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val tokenGid = root["token_gid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val fingerprint = buildSteamFingerprint(sharedSecret, normalizedDeviceId, serialNumber)

        return Result.success(
            SteamGuardImportEntry(
                name = accountName,
                secretBase32 = secretBase32,
                deviceId = normalizedDeviceId,
                serialNumber = serialNumber,
                sharedSecretBase64 = sharedSecret,
                revocationCode = revocationCode,
                identitySecret = identitySecret,
                tokenGid = tokenGid,
                rawSteamGuardJson = rawSteamGuardJson,
                fingerprint = fingerprint
            )
        )
    }

    private fun normalizeSteamDeviceId(rawInput: String?): String? {
        if (rawInput.isNullOrBlank()) return null
        val candidate = extractUuidFromXml(rawInput.trim()) ?: rawInput.trim()
        val normalized = if (candidate.startsWith("android:", ignoreCase = true)) {
            candidate
        } else {
            "android:$candidate"
        }.lowercase(Locale.US)

        return normalized.takeIf { STEAM_DEVICE_ID_REGEX.matches(it) }
    }

    private fun extractUuidFromXml(input: String): String? {
        if (!input.contains("<") && !input.contains("?xml", ignoreCase = true)) return null

        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(input)))
            val nodes = doc.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val attrs = node.attributes ?: continue
                val nameAttr = attrs.getNamedItem("name")?.nodeValue ?: continue
                if (nameAttr == "uuidKey") {
                    return node.textContent?.trim()
                }
            }
        }.getOrNull()

        val regex = Regex("<string\\s+name\\s*=\\s*['\"]uuidKey['\"][^>]*>([^<]+)</string>", RegexOption.IGNORE_CASE)
        return regex.find(input)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun decodeSteamSharedSecret(sharedSecret: String): ByteArray? {
        return runCatching {
            android.util.Base64.decode(sharedSecret, android.util.Base64.DEFAULT)
        }.getOrElse {
            runCatching {
                android.util.Base64.decode(
                    sharedSecret,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
            }.getOrNull()
        }
    }

    private fun buildSteamFingerprint(sharedSecret: String, deviceId: String, serialNumber: String): String {
        val source = "$sharedSecret|$deviceId|$serialNumber"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildFallbackSteamDeviceId(sharedSecret: String, serialNumber: String): String {
        val source = "$sharedSecret|$serialNumber"
        val digest = MessageDigest.getInstance("MD5").digest(source.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val uuidLike = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        return "android:$uuidLike"
    }
    
    /**
     * Base32编码（用于将Steam的Base64密钥转换为TOTP标准的Base32格式）
     */
    private fun base32Encode(data: ByteArray): String {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        
        var buffer = 0
        var bitsLeft = 0
        
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                result.append(base32Chars[index])
                bitsLeft -= 5
            }
        }
        
        // 处理剩余的位
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(base32Chars[index])
        }
        
        return result.toString()
    }
    
    // ==================== Stratum Auth Import ====================

    suspend fun detectStratumFileType(inputUri: Uri): Result<StratumDecryptor.StratumFileType> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            inputStream.use { input ->
                val data = input.readBytes()
                val decryptor = StratumDecryptor()
                Result.success(decryptor.detectFileType(data))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importStratumJson(inputUri: Uri): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            inputStream.use { input ->
                val content = input.readBytes().toString(Charsets.UTF_8)
                parseStratumJson(content)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    suspend fun importEncryptedStratum(inputUri: Uri, password: String): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            inputStream.use { input ->
                val data = input.readBytes()
                val decryptor = StratumDecryptor()
                val decryptedJson = try {
                    decryptor.decrypt(data, password)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("Wrong password or corrupted file"))
                }
                parseStratumJson(decryptedJson)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    suspend fun importStratumTxt(inputUri: Uri): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            inputStream.use { input ->
                val content = input.readBytes().toString(Charsets.UTF_8)
                val entries = content
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("otpauth://", ignoreCase = true) }
                    .mapNotNull { parseOtpauthUri(it) }
                    .toList()

                if (entries.isEmpty()) {
                    Result.failure(Exception("No valid TOTP entries found"))
                } else {
                    Result.success(entries)
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    suspend fun importStratumHtml(inputUri: Uri): Result<List<AegisEntry>> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(Exception("Cannot read file"))
            inputStream.use { input ->
                val content = input.readBytes().toString(Charsets.UTF_8)
                val entries = Regex("""otpauth://[^\s"'<>]+""")
                    .findAll(content)
                    .map { it.value.replace("&amp;", "&") }
                    .mapNotNull { parseOtpauthUri(it) }
                    .toList()

                if (entries.isEmpty()) {
                    Result.failure(Exception("No valid TOTP entries found"))
                } else {
                    Result.success(entries)
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    private fun parseStratumJson(jsonContent: String): Result<List<AegisEntry>> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(jsonContent).jsonObject
            val authenticatorsArray = root["Authenticators"]?.jsonArray
                ?: return Result.failure(Exception("Invalid Stratum format"))

            val entries = mutableListOf<AegisEntry>()
            authenticatorsArray.forEach { element ->
                runCatching {
                    val obj = element.jsonObject
                    val type = obj["Type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
                    val issuer = obj["Issuer"]?.jsonPrimitive?.content.orEmpty()
                    val username = obj["Username"]?.jsonPrimitive?.content.orEmpty()
                    val secret = obj["Secret"]?.jsonPrimitive?.content.orEmpty()
                    if (secret.isBlank()) return@forEach

                    val algorithmCode = obj["Algorithm"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val algorithm = when (algorithmCode) {
                        1 -> "SHA256"
                        2 -> "SHA512"
                        else -> "SHA1"
                    }
                    val digits = obj["Digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6
                    val period = obj["Period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30
                    val actualDigits = if (type == 4) 5 else digits

                    entries.add(
                        AegisEntry(
                            uuid = java.util.UUID.randomUUID().toString(),
                            name = username.ifBlank { issuer },
                            issuer = issuer,
                            note = "",
                            secret = secret,
                            algorithm = algorithm,
                            digits = actualDigits,
                            period = period
                        )
                    )
                }
            }

            if (entries.isEmpty()) {
                Result.failure(Exception("No valid TOTP entries found"))
            } else {
                Result.success(entries)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Parse JSON failed: ${e.message}"))
        }
    }

    private fun parseOtpauthUri(uri: String): AegisEntry? {
        return try {
            val parsed = TotpUriParser.parseUri(uri) ?: return null
            val data = parsed.totpData
            AegisEntry(
                uuid = java.util.UUID.randomUUID().toString(),
                name = data.accountName.ifBlank { parsed.label },
                issuer = data.issuer,
                note = "",
                secret = data.secret,
                algorithm = data.algorithm,
                digits = data.digits,
                period = data.period
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun isStratumFileEncrypted(inputUri: Uri): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.success(false)
            inputStream.use {
                val data = it.readBytes()
                val decryptor = StratumDecryptor()
                Result.success(decryptor.requiresPassword(data))
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }
}
