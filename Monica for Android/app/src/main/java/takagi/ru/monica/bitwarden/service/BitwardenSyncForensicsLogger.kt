package takagi.ru.monica.bitwarden.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.api.SyncResponse
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenSyncRawEntryRecord
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.SettingsManager
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BitwardenSyncForensicsSummary(
    val cipherId: String,
    val cipherType: Int,
    val syncOutcome: String,
    val deleted: Boolean,
    val revisionMillis: Long? = null,
    val customFieldCount: Int = 0,
    val fieldMetrics: Map<String, Int> = emptyMap(),
    val message: String? = null
)

@Serializable
private data class BitwardenSyncForensicsEvent(
    val schemaVersion: Int = 1,
    val vaultId: Long,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val durationMs: Long,
    val serverCipherCount: Int,
    val activeServerCipherCount: Int,
    val folderCount: Int,
    val sendCount: Int,
    val syncResult: String,
    val syncMessage: String? = null,
    val ciphersAdded: Int = 0,
    val ciphersUpdated: Int = 0,
    val conflictsDetected: Int = 0,
    val droppedCipherSummaries: Int = 0,
    val cipherSummaries: List<BitwardenSyncForensicsSummary> = emptyList()
)

@Serializable
private data class BitwardenSyncRawExchangeEvent(
    val schemaVersion: Int = 2,
    val vaultId: Long,
    val capturedAtMs: Long,
    val operation: String,
    val method: String,
    val endpoint: String,
    val requestSizeBytes: Int? = null,
    val responseSizeBytes: Int? = null,
    val requestDigest: String? = null,
    val responseDigest: String? = null,
    val requestKeySample: String? = null,
    val responseKeySample: String? = null,
    val requestPreview: String? = null,
    val responseCode: Int? = null,
    val responsePreview: String? = null,
    val success: Boolean,
    val error: String? = null
)

object BitwardenSyncForensicsLogger {

    private const val LOG_DIR_NAME = "bitwarden_sync_forensics"
    private const val LOG_FILE_PREFIX = "bw_sync_forensics_"
    private const val RAW_LOG_DIR_NAME = "bitwarden_sync_raw"
    private const val RAW_LOG_FILE_PREFIX = "bw_sync_raw_"
    private const val LOG_FILE_SUFFIX = ".json"
    private const val EXTERNAL_DIR_NAME = "Monica_Bitwarden_Forensics"
    private const val EXTERNAL_RAW_DIR_NAME = "Monica_Bitwarden_Forensics_Raw"
    private const val MAX_SUMMARIES_PER_SYNC = 2000
    private const val MAX_RAW_PREVIEW_CHARS = 24_000
    private const val RAW_PREVIEW_HEAD_CHARS = 16_000
    private const val RAW_PREVIEW_TAIL_CHARS = 8_000
    private const val EXPORT_FORENSICS_FILE_MAX_CHARS = 120_000
    private const val EXPORT_RAW_FILE_MAX_CHARS = 80_000
    private const val MAX_ERROR_CHARS = 600
    private const val RAW_ENTRY_RECORD_LIMIT = 30
    private const val MAX_SYNC_CIPHER_SNAPSHOTS_PER_RUN = 40

    private val fileLock = Any()
    private val fileFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val exportFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val jsonKeyRegex = Regex("\\\"([A-Za-z0-9_-]{1,64})\\\"\\s*:")
    private val sensitiveJsonFieldRegex = Regex(
        "\\\"([A-Za-z0-9_-]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"",
        setOf(RegexOption.IGNORE_CASE)
    )
    private val bearerTokenRegex = Regex(
        "(authorization\\s*:\\s*bearer\\s+)([A-Za-z0-9._~+/=-]+)",
        RegexOption.IGNORE_CASE
    )
    private val queryTokenRegex = Regex(
        "((?:access|refresh)_token=)([^&\\s]+)",
        RegexOption.IGNORE_CASE
    )
    private val endpointCipherRegex = Regex("/ciphers/([0-9a-fA-F-]{8,})")
    private val payloadCipherRegex = Regex(
        "\\\"(?:Id|id)\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{8,})\\\""
    )
    private val sensitiveJsonKeys = setOf(
        "password",
        "pwd",
        "pass",
        "token",
        "refreshToken",
        "accessToken",
        "secret",
        "privateKey",
        "totp",
        "cvv",
        "ssn",
        "passportNumber",
        "licenseNumber"
    ).map { it.lowercase(Locale.US) }.toSet()

    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    class Session internal constructor(
        val enabled: Boolean,
        val vaultId: Long,
        val startedAtMs: Long,
        val serverCipherCount: Int,
        val activeServerCipherCount: Int,
        val folderCount: Int,
        val sendCount: Int,
        val externalDirectoryUri: String?
    ) {
        private val summaries = mutableListOf<BitwardenSyncForensicsSummary>()
        private var droppedCount: Int = 0

        internal fun append(summary: BitwardenSyncForensicsSummary) {
            if (summaries.size < MAX_SUMMARIES_PER_SYNC) {
                summaries += summary
            } else {
                droppedCount++
            }
        }

        internal fun snapshotSummaries(): List<BitwardenSyncForensicsSummary> = summaries.toList()

        internal fun snapshotDroppedCount(): Int = droppedCount
    }

    fun initialize(context: Context) {
        synchronized(fileLock) {
            val logDir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val rawLogDir = File(context.applicationContext.filesDir, RAW_LOG_DIR_NAME)
            if (!rawLogDir.exists()) {
                rawLogDir.mkdirs()
            }
        }
    }

    suspend fun startSession(
        context: Context,
        vaultId: Long,
        response: SyncResponse
    ): Session = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        initialize(appContext)
        runCatching { BitwardenDiagLogger.initialize(appContext) }
        val settings = SettingsManager(appContext).settingsFlow.first()
        Session(
            enabled = settings.bitwardenSyncForensicsEnabled,
            vaultId = vaultId,
            startedAtMs = System.currentTimeMillis(),
            serverCipherCount = response.ciphers.size,
            activeServerCipherCount = response.ciphers.count { it.deletedDate == null },
            folderCount = response.folders.size,
            sendCount = response.sends?.size ?: 0,
            externalDirectoryUri = settings.bitwardenSyncForensicsDirectoryUri
        )
    }

    fun recordCipher(session: Session, summary: BitwardenSyncForensicsSummary) {
        if (!session.enabled) return
        synchronized(session) {
            session.append(summary)
        }
    }

    suspend fun isRawCaptureEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = SettingsManager(context.applicationContext).settingsFlow.first()
        settings.bitwardenSyncForensicsEnabled && settings.bitwardenSyncForensicsRawCaptureEnabled
    }

    suspend fun finishSession(
        context: Context,
        session: Session,
        syncResult: SyncResult
    ) = withContext(Dispatchers.IO) {
        if (!session.enabled) return@withContext

        val finishedAtMs = System.currentTimeMillis()
        val outcome = mapSyncOutcome(syncResult)
        val summaries = synchronized(session) { session.snapshotSummaries() }
        val dropped = synchronized(session) { session.snapshotDroppedCount() }

        val event = BitwardenSyncForensicsEvent(
            vaultId = session.vaultId,
            startedAtMs = session.startedAtMs,
            finishedAtMs = finishedAtMs,
            durationMs = (finishedAtMs - session.startedAtMs).coerceAtLeast(0L),
            serverCipherCount = session.serverCipherCount,
            activeServerCipherCount = session.activeServerCipherCount,
            folderCount = session.folderCount,
            sendCount = session.sendCount,
            syncResult = outcome.result,
            syncMessage = outcome.message,
            ciphersAdded = outcome.ciphersAdded,
            ciphersUpdated = outcome.ciphersUpdated,
            conflictsDetected = outcome.conflictsDetected,
            droppedCipherSummaries = dropped,
            cipherSummaries = summaries
        )

        val payload = json.encodeToString(event)
        val fileName = buildFileName(session.vaultId, session.startedAtMs)
        val internalFile = writeInternalLog(context.applicationContext, fileName, payload)
        val mirroredPath = mirrorToExternalDirectory(
            context = context.applicationContext,
            treeUriRaw = session.externalDirectoryUri,
            fileName = fileName,
            payload = payload
        )

        BitwardenDiagLogger.append(
            "[INFO][BW_FORENSICS] stored=$internalFile mirrored=${mirroredPath ?: "disabled"} result=${outcome.result}"
        )
    }

    suspend fun captureRawExchange(
        context: Context,
        vaultId: Long,
        operation: String,
        method: String,
        endpoint: String,
        requestBody: String?,
        responseCode: Int?,
        responseBody: String?,
        success: Boolean,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        initialize(appContext)
        runCatching { BitwardenDiagLogger.initialize(appContext) }

        val settings = SettingsManager(appContext).settingsFlow.first()
        if (!settings.bitwardenSyncForensicsEnabled ||
            !settings.bitwardenSyncForensicsRawCaptureEnabled
        ) {
            return@withContext
        }

        val now = System.currentTimeMillis()
        val event = BitwardenSyncRawExchangeEvent(
            vaultId = vaultId,
            capturedAtMs = now,
            operation = operation,
            method = method,
            endpoint = endpoint,
            requestSizeBytes = requestBody?.toByteArray(Charsets.UTF_8)?.size,
            responseSizeBytes = responseBody?.toByteArray(Charsets.UTF_8)?.size,
            requestDigest = requestBody?.takeIf { it.isNotBlank() }?.let { shortSha(it) },
            responseDigest = responseBody?.takeIf { it.isNotBlank() }?.let { shortSha(it) },
            requestKeySample = summarizeJsonKeys(requestBody),
            responseKeySample = summarizeJsonKeys(responseBody),
            requestPreview = sanitizeRawPayload(requestBody),
            responseCode = responseCode,
            responsePreview = sanitizeRawPayload(responseBody),
            success = success,
            error = sanitizeErrorMessage(errorMessage)
        )

        val payload = json.encodeToString(event)
        val fileName = buildRawFileName(vaultId, now, operation)
        val internalFile = writeRawInternalLog(appContext, fileName, payload)
        val mirroredPath = mirrorRawToExternalDirectory(
            context = appContext,
            treeUriRaw = settings.bitwardenSyncForensicsDirectoryUri,
            fileName = fileName,
            payload = payload
        )

        BitwardenDiagLogger.append(
            "[INFO][BW_FORENSICS_RAW] stored=$internalFile mirrored=${mirroredPath ?: "disabled"} op=$operation success=$success code=${responseCode ?: "-"}"
        )

        val detectedCipherId = extractCipherId(endpoint, responseBody, requestBody)
        if (!detectedCipherId.isNullOrBlank()) {
            val payloadAndSource = when {
                !responseBody.isNullOrBlank() -> responseBody to BitwardenSyncRawEntryRecord.SOURCE_RESPONSE
                !requestBody.isNullOrBlank() -> requestBody to BitwardenSyncRawEntryRecord.SOURCE_REQUEST
                else -> null
            }
            payloadAndSource?.let { (rawPayload, source) ->
                persistRawEntrySnapshot(
                    context = appContext,
                    vaultId = vaultId,
                    cipherId = detectedCipherId,
                    operation = operation,
                    endpoint = endpoint,
                    payload = rawPayload,
                    payloadSource = source,
                    responseCode = responseCode,
                    success = success
                )
            }
        }
    }

    suspend fun captureSyncCipherSnapshots(
        context: Context,
        vaultId: Long,
        ciphers: List<CipherApiResponse>
    ) = withContext(Dispatchers.IO) {
        if (ciphers.isEmpty()) return@withContext

        val appContext = context.applicationContext
        initialize(appContext)
        runCatching { BitwardenDiagLogger.initialize(appContext) }
        val settings = SettingsManager(appContext).settingsFlow.first()
        if (!settings.bitwardenSyncForensicsEnabled ||
            !settings.bitwardenSyncForensicsRawCaptureEnabled
        ) {
            return@withContext
        }

        ciphers.asSequence()
            .filter { it.deletedDate == null }
            .take(MAX_SYNC_CIPHER_SNAPSHOTS_PER_RUN)
            .forEach { cipher ->
                val cipherId = cipher.id.trim()
                if (cipherId.isEmpty()) return@forEach
                val payload = runCatching { cipher.sourceDocument?.toString() ?: json.encodeToString(cipher) }.getOrNull() ?: return@forEach
                persistRawEntrySnapshot(
                    context = appContext,
                    vaultId = vaultId,
                    cipherId = cipherId,
                    operation = "sync_full",
                    endpoint = "/sync/ciphers/$cipherId",
                    payload = payload,
                    payloadSource = BitwardenSyncRawEntryRecord.SOURCE_SYNC_RESPONSE,
                    responseCode = 200,
                    success = true
                )
            }
    }

    fun exportPersistedLogs(context: Context, maxFiles: Int = 20): String {
        initialize(context.applicationContext)
        val logDir = File(context.applicationContext.filesDir, LOG_DIR_NAME)
        val rawLogDir = File(context.applicationContext.filesDir, RAW_LOG_DIR_NAME)

        val files = logDir.listFiles { file ->
            file.isFile && file.name.startsWith(LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() }
            ?.take(maxFiles.coerceAtLeast(1))
            .orEmpty()

        val rawFiles = rawLogDir.listFiles { file ->
            file.isFile && file.name.startsWith(RAW_LOG_FILE_PREFIX) && file.name.endsWith(LOG_FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() }
            ?.take(maxFiles.coerceAtLeast(1))
            .orEmpty()

        if (files.isEmpty() && rawFiles.isEmpty()) return ""

        return buildString {
            if (files.isNotEmpty()) {
                appendLine("=== Bitwarden Sync Forensics Logs ===")
                files.forEach { file ->
                    val exportedAt = exportFormatter.format(Date(file.lastModified()))
                    appendLine("---- ${file.name} ($exportedAt) ----")
                    appendLine(readFileForExport(file, EXPORT_FORENSICS_FILE_MAX_CHARS))
                    appendLine()
                }
            }

            if (rawFiles.isNotEmpty()) {
                appendLine("=== Bitwarden Sync Raw Exchange Logs ===")
                rawFiles.forEach { file ->
                    val exportedAt = exportFormatter.format(Date(file.lastModified()))
                    appendLine("---- ${file.name} ($exportedAt) ----")
                    appendLine(readFileForExport(file, EXPORT_RAW_FILE_MAX_CHARS))
                    appendLine()
                }
            }
        }.trim()
    }

    private fun readFileForExport(file: File, maxChars: Int): String {
        return runCatching {
            val text = file.readText()
            if (text.length <= maxChars) {
                text
            } else {
                val headChars = (maxChars * 0.65f).toInt()
                val tailChars = maxChars - headChars
                buildString {
                    append(text.take(headChars))
                    appendLine()
                    appendLine("... truncated ${text.length - maxChars} chars from middle for developer log export ...")
                    append(text.takeLast(tailChars))
                }
            }
        }.getOrElse {
            "read_failed: ${it.message}"
        }
    }

    fun clear(context: Context) {
        initialize(context.applicationContext)
        clearLogsInDirectory(
            directory = File(context.applicationContext.filesDir, LOG_DIR_NAME),
            prefix = LOG_FILE_PREFIX
        )
        clearLogsInDirectory(
            directory = File(context.applicationContext.filesDir, RAW_LOG_DIR_NAME),
            prefix = RAW_LOG_FILE_PREFIX
        )
    }

    private fun buildFileName(vaultId: Long, startedAtMs: Long): String {
        val ts = fileFormatter.format(Date(startedAtMs))
        return "${LOG_FILE_PREFIX}${ts}_vault${vaultId}${LOG_FILE_SUFFIX}"
    }

    private fun buildRawFileName(vaultId: Long, capturedAtMs: Long, operation: String): String {
        val ts = fileFormatter.format(Date(capturedAtMs))
        val normalizedOperation = operation
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "exchange" }
            .take(40)
        return "${RAW_LOG_FILE_PREFIX}${ts}_vault${vaultId}_${normalizedOperation}${LOG_FILE_SUFFIX}"
    }

    private fun writeInternalLog(context: Context, fileName: String, payload: String): String {
        return runCatching {
            synchronized(fileLock) {
                val dir = File(context.filesDir, LOG_DIR_NAME)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, fileName)
                file.writeText(payload)
                file.absolutePath
            }
        }.getOrElse { "write_failed: ${it.message}" }
    }

    private fun writeRawInternalLog(context: Context, fileName: String, payload: String): String {
        return runCatching {
            synchronized(fileLock) {
                val dir = File(context.filesDir, RAW_LOG_DIR_NAME)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, fileName)
                file.writeText(payload)
                file.absolutePath
            }
        }.getOrElse { "write_failed: ${it.message}" }
    }

    private fun mirrorToExternalDirectory(
        context: Context,
        treeUriRaw: String?,
        fileName: String,
        payload: String
    ): String? {
        if (treeUriRaw.isNullOrBlank()) return null

        return runCatching {
            val treeUri = Uri.parse(treeUriRaw)
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@runCatching "tree_unavailable"
            val targetDir = root.findFile(EXTERNAL_DIR_NAME)
                ?.takeIf { it.isDirectory }
                ?: root.createDirectory(EXTERNAL_DIR_NAME)
                ?: root

            val targetFile = targetDir.createFile("application/json", fileName)
                ?: return@runCatching "create_file_failed"
            context.contentResolver.openOutputStream(targetFile.uri, "w")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: return@runCatching "open_stream_failed"
            targetFile.uri.toString()
        }.getOrElse { "mirror_failed: ${it.message}" }
    }

    private fun mirrorRawToExternalDirectory(
        context: Context,
        treeUriRaw: String?,
        fileName: String,
        payload: String
    ): String? {
        if (treeUriRaw.isNullOrBlank()) return null

        return runCatching {
            val treeUri = Uri.parse(treeUriRaw)
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@runCatching "tree_unavailable"
            val targetDir = root.findFile(EXTERNAL_RAW_DIR_NAME)
                ?.takeIf { it.isDirectory }
                ?: root.createDirectory(EXTERNAL_RAW_DIR_NAME)
                ?: root

            val targetFile = targetDir.createFile("application/json", fileName)
                ?: return@runCatching "create_file_failed"
            context.contentResolver.openOutputStream(targetFile.uri, "w")?.use { stream ->
                stream.write(payload.toByteArray())
            } ?: return@runCatching "open_stream_failed"
            targetFile.uri.toString()
        }.getOrElse { "mirror_failed: ${it.message}" }
    }

    private fun sanitizeRawPayload(payload: String?): String? {
        if (payload == null) return null
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return ""

        val redactedByKey = sensitiveJsonFieldRegex.replace(trimmed) { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (!isSensitiveJsonKey(key)) {
                match.value
            } else {
                "\"$key\":\"${summarizeSensitiveToken(value)}\""
            }
        }

        val redacted = queryTokenRegex
            .replace(
                bearerTokenRegex.replace(redactedByKey) { match ->
                    "${match.groupValues[1]}${summarizeSensitiveToken(match.groupValues[2])}"
                }
            ) { match ->
                "${match.groupValues[1]}${summarizeSensitiveToken(match.groupValues[2])}"
            }

        if (redacted.length <= MAX_RAW_PREVIEW_CHARS) {
            return redacted
        }

        val headLen = RAW_PREVIEW_HEAD_CHARS.coerceAtLeast(1)
        val tailLen = RAW_PREVIEW_TAIL_CHARS.coerceAtLeast(1)
        if (redacted.length <= headLen + tailLen) {
            return redacted.take(MAX_RAW_PREVIEW_CHARS)
        }
        val omitted = redacted.length - headLen - tailLen
        return buildString {
            append(redacted.take(headLen))
            append("\n...(omitted=")
            append(omitted)
            append(" chars)...\n")
            append(redacted.takeLast(tailLen))
        }
    }

    private fun sanitizeErrorMessage(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return errorMessage
        val sanitized = queryTokenRegex
            .replace(
                bearerTokenRegex.replace(errorMessage.trim()) { match ->
                    "${match.groupValues[1]}${summarizeSensitiveToken(match.groupValues[2])}"
                }
            ) { match ->
                "${match.groupValues[1]}${summarizeSensitiveToken(match.groupValues[2])}"
            }
        return sanitized.take(MAX_ERROR_CHARS)
    }

    private fun summarizeJsonKeys(payload: String?): String? {
        val source = payload?.trim().orEmpty()
        if (source.isEmpty()) return null

        val matches = jsonKeyRegex.findAll(source).map { it.groupValues[1] }.toList()
        if (matches.isEmpty()) return null

        val uniqueOrdered = LinkedHashSet<String>()
        matches.forEach { key ->
            if (uniqueOrdered.size < 14) {
                uniqueOrdered += key
            }
        }
        val uniqueCount = matches.toSet().size
        return "unique=$uniqueCount,sample=${uniqueOrdered.joinToString(",")}".take(300)
    }

    private fun isSensitiveJsonKey(key: String): Boolean {
        return sensitiveJsonKeys.contains(key.lowercase(Locale.US))
    }

    private fun summarizeSensitiveToken(value: String): String {
        if (value.isBlank()) return "<redacted empty>"
        return "<redacted len=${value.length} sha=${shortSha(value)}>"
    }

    private suspend fun persistRawEntrySnapshot(
        context: Context,
        vaultId: Long,
        cipherId: String,
        operation: String,
        endpoint: String,
        payload: String,
        payloadSource: String,
        responseCode: Int?,
        success: Boolean
    ) {
        runCatching {
            val sanitizedPayload = sanitizeRawPayload(payload)?.takeIf { it.isNotBlank() } ?: return@runCatching
            val digest = shortSha(sanitizedPayload)
            val dao = PasswordDatabase.getDatabase(context).bitwardenSyncRawEntryRecordDao()
            val latest = dao.getLatestByCipher(vaultId, cipherId)
            if (latest?.payloadDigest == digest) {
                return@runCatching
            }

            val cipherText = SecurityManager(context).encryptDataLegacyCompat(sanitizedPayload)
            dao.insert(
                BitwardenSyncRawEntryRecord(
                    vaultId = vaultId,
                    bitwardenCipherId = cipherId,
                    operation = operation,
                    endpoint = endpoint,
                    payloadCipherText = cipherText,
                    payloadDigest = digest,
                    payloadSource = payloadSource,
                    responseCode = responseCode,
                    success = success,
                    capturedAt = System.currentTimeMillis()
                )
            )
            dao.trimToLimit(vaultId, cipherId, RAW_ENTRY_RECORD_LIMIT)
        }.onFailure { error ->
            BitwardenDiagLogger.append(
                "[WARN][BW_FORENSICS_RAW_ENTRY] persist_failed vault=$vaultId cipher=$cipherId msg=${error.message}"
            )
        }
    }

    private fun extractCipherId(endpoint: String, responseBody: String?, requestBody: String?): String? {
        endpointCipherRegex.find(endpoint)?.groupValues?.getOrNull(1)?.let { return it }
        payloadCipherRegex.find(responseBody.orEmpty())?.groupValues?.getOrNull(1)?.let { return it }
        payloadCipherRegex.find(requestBody.orEmpty())?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun shortSha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun clearLogsInDirectory(directory: File, prefix: String) {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(LOG_FILE_SUFFIX)
        } ?: return
        files.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private data class SyncOutcome(
        val result: String,
        val message: String? = null,
        val ciphersAdded: Int = 0,
        val ciphersUpdated: Int = 0,
        val conflictsDetected: Int = 0
    )

    private fun mapSyncOutcome(syncResult: SyncResult): SyncOutcome {
        return when (syncResult) {
            is SyncResult.Success -> SyncOutcome(
                result = "SUCCESS",
                ciphersAdded = syncResult.ciphersAdded,
                ciphersUpdated = syncResult.ciphersUpdated,
                conflictsDetected = syncResult.conflictsDetected
            )

            is SyncResult.Error -> SyncOutcome(
                result = "ERROR",
                message = syncResult.message
            )

            is SyncResult.EmptyVaultBlocked -> SyncOutcome(
                result = "EMPTY_VAULT_BLOCKED",
                message = syncResult.reason
            )
        }
    }
}
