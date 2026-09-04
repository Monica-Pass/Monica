package takagi.ru.monica.repository

import android.content.Context
import android.os.Build
import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.CustomFieldDao
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.LocalMdbxDatabaseDao
import takagi.ru.monica.data.MdbxRemoteSourceDao
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxSyncStatus
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.data.MdbxUnlockMethod
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordEntryDao
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.SecureItemDao
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.mdbx.MdbxDiagLogger
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.OneDriveMdbxFileSource
import takagi.ru.monica.utils.WebDavMdbxFileSource
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private const val MDBX_SCHEMA_FORMAT_VERSION = "MDBX-1"
private const val MDBX_LEGACY_DRAFT_FORMAT_VERSION = "MDBX-1-DRAFT"
private const val MDBX_OFFICIAL_RELEASE_LABEL = "MDBX-1.0"
private const val MDBX_ANDROID_CAPABILITY_FLAGS =
    "android-official-1.0,sky-portable,tiga-selectable,legacy-test-compatible"
private const val STEAM_MAFILE_ENTRY_TYPE = "steam-mafile"

enum class MdbxHealthSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL;

    val requiresAction: Boolean
        get() = this == ERROR || this == CRITICAL
}

data class MdbxHealthIssueDiagnostic(
    val severity: MdbxHealthSeverity,
    val category: String,
    val description: String
)

enum class MdbxHealthRepairItemKind {
    MISSING_TOMBSTONE,
    DUPLICATE_TOMBSTONES,
    ACTIVE_OBJECT_TOMBSTONE_CONFLICT
}

data class MdbxHealthRepairItem(
    val repairId: String,
    val kind: MdbxHealthRepairItemKind,
    val objectType: String,
    val objectId: String,
    val tombstoneCount: Int
)

data class MdbxHealthRepairBlocker(
    val category: String,
    val description: String
)

data class MdbxHealthRepairPlan(
    val token: String,
    val automaticItems: List<MdbxHealthRepairItem>,
    val conflictItems: List<MdbxHealthRepairItem>,
    val blockers: List<MdbxHealthRepairBlocker>,
    val canApply: Boolean
) {
    val repairableItemCount: Int
        get() = automaticItems.size + conflictItems.size
}

enum class MdbxHealthRepairChoice {
    KEEP_CONTENT,
    DELETE_OBJECT,
    CANCEL
}

data class MdbxHealthRepairDecision(
    val repairId: String,
    val choice: MdbxHealthRepairChoice
)

enum class MdbxHealthRepairStatus {
    APPLIED,
    CANCELLED,
    NO_CHANGES
}

data class MdbxHealthRepairApplyResult(
    val status: MdbxHealthRepairStatus,
    val snapshotId: String?,
    val commitId: String?,
    val repairedCount: Int,
    val alreadyCommitted: Boolean,
    val healthy: Boolean,
    val remainingIssues: List<MdbxHealthIssueDiagnostic>
)

data class MdbxVaultDiagnostics(
    val databaseId: Long,
    val filePath: String?,
    val fileExists: Boolean,
    val fileSizeBytes: Long,
    val isReadable: Boolean,
    val currentDeviceId: String? = null,
    val unavailableReason: String? = null,
    val formatVersion: String? = null,
    val releaseLabel: String? = null,
    val capabilityFlags: String? = null,
    val defaultTigaMode: String? = null,
    val integrityOk: Boolean = false,
    val integrityMessage: String? = null,
    val healthIssues: List<MdbxHealthIssueDiagnostic> = emptyList(),
    val unresolvedConflictCount: Int = 0,
    val pendingSyncCount: Int = 0,
    val commitCount: Int = 0,
    val tombstoneCount: Int = 0,
    val branchCount: Int = 0,
    val deviceCount: Int = 0,
    val snapshotCount: Int = 0,
    val folderCount: Int = 0,
    val indexedObjectCount: Int = 0,
    val entryCount: Int = 0,
    val deletedEntryCount: Int = 0,
    val attachmentCount: Int = 0,
    val externalAttachmentCount: Int = 0,
    val originalAttachmentBytes: Long = 0,
    val storedAttachmentBytes: Long = 0,
    val danglingParentCount: Int = 0,
    val danglingBranchHeadCount: Int = 0,
    val danglingDeviceHeadCount: Int = 0,
    val attachmentChunkMismatchCount: Int = 0,
    val lastSyncStatus: String,
    val lastSyncError: String? = null
) {
    val structuralIssueCount: Int
        get() = danglingParentCount + danglingBranchHeadCount +
            danglingDeviceHeadCount + attachmentChunkMismatchCount

    val healthIssueCount: Int
        get() {
            val reportedIssueCount = if (healthIssues.isNotEmpty()) {
                healthIssues.count { it.severity.requiresAction }
            } else {
                (if (!integrityOk) 1 else 0) + structuralIssueCount
            }
            return reportedIssueCount + if (!isReadable) 1 else 0
        }

    val healthNoticeCount: Int
        get() = healthIssues.count {
            it.severity == MdbxHealthSeverity.INFO || it.severity == MdbxHealthSeverity.WARNING
        }
}

data class MdbxConflictSummary(
    val conflictId: String,
    val objectType: String,
    val objectId: String,
    val baseCommitId: String,
    val localCommitId: String,
    val incomingCommitId: String,
    val conflictingFields: String,
    val createdAt: String,
    val localTitle: String? = null,
    val incomingTitle: String? = null,
    val localPayloadPreview: String? = null,
    val incomingPayloadPreview: String? = null
)

data class MdbxDeltaSummary(
    val commitId: String,
    val deviceId: String,
    val localSeq: Long,
    val commitKind: String,
    val changeScope: String,
    val changedObjectIds: String,
    val changedObjectPreview: String,
    val changedFieldSummary: String,
    val parentCount: Int,
    val createdAt: String,
    val operationId: String? = null,
    val operationKind: String? = null,
    val branchName: String? = null,
    val message: String? = null,
    val changes: List<MdbxCommitChangeSummary> = emptyList(),
    val legacy: Boolean = false
)

data class MdbxCommitChangeSummary(
    val objectType: String,
    val objectId: String,
    val action: String,
    val fields: List<String>
)

data class MdbxCommitDiff(
    val commitId: String,
    val objectType: String,
    val objectId: String,
    val displayTitle: String?,
    val storagePath: String?,
    val previousTitle: String?,
    val currentTitle: String?,
    val previousPayloadPreview: String?,
    val currentPayloadPreview: String?,
    val previousDeleted: Boolean?,
    val currentDeleted: Boolean,
    val changedFields: List<String>,
    val createdAt: String,
    val contentType: String? = null
)

data class MdbxSyncBundle(
    val bundleId: String,
    val baseCommitId: String?,
    val headCommitId: String,
    val commitCount: Int,
    val payloadJson: String,
    val payloadHash: String,
    val createdAt: String
)

data class MdbxBenchmarkResult(
    val runId: String,
    val scenario: String,
    val operationCount: Int,
    val elapsedMs: Long,
    val fileDeltaBytes: Long,
    val createdAt: String
)

data class MdbxSnapshotSummary(
    val snapshotId: String,
    val baseCommitId: String,
    val name: String,
    val snapshotType: String,
    val isFull: Boolean,
    val payloadBytes: Long,
    val createdAt: String,
    val createdByDeviceId: String,
    val autoPrune: Boolean,
    val integrityOk: Boolean
)

data class MdbxStructurePreview(
    val snapshotId: String,
    val snapshotName: String,
    val currentNodes: List<MdbxStructureNode>,
    val snapshotNodes: List<MdbxStructureNode>,
    val currentItemCount: Int,
    val snapshotItemCount: Int
)

data class MdbxStructureNode(
    val id: String,
    val parentId: String?,
    val name: String,
    val type: MdbxStructureNodeType,
    val path: String,
    val status: MdbxStructureNodeStatus,
    val childCount: Int,
    val metadata: String
)

enum class MdbxStructureNodeType {
    FOLDER,
    ENTRY
}

enum class MdbxStructureNodeStatus {
    UNCHANGED,
    ADDED,
    REMOVED,
    MODIFIED
}

data class MdbxStoredVaultEntry(
    val entryId: String,
    val entryType: String,
    val title: String,
    val payloadJson: String,
    val deleted: Boolean
)

private data class MdbxEntryMutation(
    val databaseId: Long,
    val projectId: String,
    val entryId: String,
    val entryType: String,
    val title: String,
    val payloadJson: String,
    val deleted: Boolean,
    val legacyEntryId: String? = null
)

private data class MdbxEntryDeleteMutation(
    val databaseId: Long,
    val entryId: String
)

data class MdbxStoredAttachment(
    val attachmentId: String,
    val projectId: String,
    val entryId: String?,
    val fileName: String,
    val mimeType: String,
    val contentHash: String,
    val originalSize: Long,
    val storedSize: Long,
    val wrappedCek: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deleted: Boolean,
    val blob: ByteArray
)

data class MdbxStoredFolderEntry(
    val folderId: String,
    val parentFolderId: String?,
    val name: String,
    val pathKey: String,
    val objectClock: Long
)

data class MdbxProjectTagSummary(
    val tag: String,
    val projectCount: Int
)

data class MdbxProjectSearchResult(
    val projectId: String,
    val title: String,
    val parentFolderId: String?,
    val entryTypes: List<String>,
    val tags: List<String>,
    val updatedAt: String
)

data class MdbxApplyResult(
    val appliedObjectCount: Int,
    val keptLocalObjectCount: Int,
    val conflictCount: Int,
    val tombstoneCount: Int
)

enum class MdbxConflictResolution(val storedValue: String) {
    LOCAL_WINS("local-wins"),
    INCOMING_WINS("incoming-wins"),
    CUSTOM_MERGE("custom"),
    MARK_RESOLVED("resolved")
}

/**
 * Android-side MDBX vault write boundary.
 *
 * This deliberately mirrors the schema from `mdbx-doc`/Rust `mdbx-storage`.
 * The implementation can later be swapped for the Rust/Tiga engine without
 * changing password/note/passkey call sites.
 */
class MdbxVaultStore(
    private val context: Context,
    private val databaseDao: LocalMdbxDatabaseDao,
    private val securityManager: SecurityManager,
    private val remoteSourceDao: MdbxRemoteSourceDao? = null,
    private val passwordEntryDao: PasswordEntryDao? = null,
    private val secureItemDao: SecureItemDao? = null,
    private val customFieldDao: CustomFieldDao? = null
) : MdbxRepository {
    private val epochKeyCache = ConcurrentHashMap<Long, ByteArray>()
    private val vaultWriteLocks = ConcurrentHashMap<String, Mutex>()

    private val requiredCoreTables = listOf(
        "vault_meta",
        "devices",
        "folders",
        "projects",
        "entries",
        "object_index",
        "commits",
        "commit_parents",
        "device_heads",
        "branches",
        "tombstones",
        "conflicts"
    )

    private val minimumReadableTables = listOf(
        "vault_meta",
        "folders",
        "projects",
        "entries"
    )

    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("mdbx_vault_store", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank() && !existing.matches(UUID_REGEX)) {
            existing
        } else {
            buildDeviceId().also {
                prefs.edit().putString("device_id", it).apply()
            }
        }
    }

    suspend fun createInitializedVaultFile(
        displayName: String,
        tigaMode: String,
        unlockMethod: MdbxUnlockMethod = MdbxUnlockMethod.MASTER_PASSWORD,
        credential: MdbxVaultCredential
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "mdbx")
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.mdbx")
        open(file).use { db ->
            ensureSchema(db)
            initializeVaultMeta(db, displayName, tigaMode, unlockMethod, credential)
        }
        file
    }

    override suspend fun createFolder(
        databaseId: Long,
        name: String,
        parentFolderId: String?
    ): MdbxStoredFolderEntry = withContext(Dispatchers.IO) {
        var fileForLog: File? = null
        try {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
            fileForLog = file
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }

            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty()) { "Folder name cannot be empty" }

            withVaultWriteLock(file) {
                var createdFolder: MdbxStoredFolderEntry? = null
                openExistingWritableVault(file).use { db ->
                    db.beginTransaction()
                    try {
                        ensureSchema(db)
                        ensureDeviceRegistration(db)
                        val epochKey = requireEpochKey(db, dbInfo)
                        val resolvedParentFolderId = parentFolderId?.takeIf { it.isNotBlank() } ?: "root"
                        val folderId = UUID.randomUUID().toString()
                        val commitId = appendCommit(db, "folder", folderId, epochKey)
                        ensureRootFolder(db, epochKey)
                        val parentPathKey = queryString(
                            db,
                            "SELECT path_key FROM folders WHERE folder_id = ? AND deleted = 0 LIMIT 1",
                            arrayOf(resolvedParentFolderId)
                        ) ?: throw IllegalArgumentException("Parent MDBX folder not found: $resolvedParentFolderId")

                        val duplicateExists = db.rawQuery(
                            """
                            SELECT name_ct
                            FROM folders
                            WHERE parent_folder_id = ? AND deleted = 0
                            """.trimIndent(),
                            arrayOf(resolvedParentFolderId)
                        ).use { cursor ->
                            var found = false
                            while (cursor.moveToNext()) {
                                val existingName = decodeVaultText(cursor.getBlob(0), epochKey)
                                if (existingName.equals(normalizedName, ignoreCase = true)) {
                                    found = true
                                    break
                                }
                            }
                            found
                        }
                        if (duplicateExists) {
                            throw IllegalArgumentException("MDBX 文件夹已存在: $normalizedName")
                        }

                        val now = now()
                        val pathKey = if (parentPathKey == "/") "/$folderId" else "$parentPathKey/$folderId"
                        val nameCt = encrypt(normalizedName, epochKey)

                        db.execSQL(
                            """
                            INSERT INTO folders (
                                folder_id, parent_folder_id, name_ct, path_key, object_clock, head_commit_id,
                                deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
                            ) VALUES (?, ?, ?, ?, '1', ?, 0, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(folderId, resolvedParentFolderId, nameCt, pathKey, commitId, now, now, deviceId, deviceId)
                        )
                        upsertObjectIndex(
                            db = db,
                            objectType = "folder",
                            objectId = folderId,
                            parentFolderId = resolvedParentFolderId,
                            title = normalizedName,
                            entryType = "folder",
                            commitId = commitId,
                            deleted = false
                        )
                        db.execSQL(
                            """
                            INSERT INTO object_versions (
                                object_type, object_id, commit_id, project_id, entry_type,
                                title_ct, payload_ct, deleted, created_at, created_by_device_id
                            ) VALUES ('folder', ?, ?, NULL, NULL, ?, NULL, 0, ?, ?)
                            """.trimIndent(),
                            arrayOf(folderId, commitId, nameCt, now, deviceId)
                        )
                        createdFolder = MdbxStoredFolderEntry(
                            folderId = folderId,
                            parentFolderId = resolvedParentFolderId,
                            name = normalizedName,
                            pathKey = pathKey,
                            objectClock = 1L
                        )
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }

                markWorkingCopyDirtyAndFlushLocked(dbInfo, file)
                createdFolder ?: throw IllegalStateException("Failed to create MDBX folder")
            }
        } catch (error: Throwable) {
            MdbxDiagLogger.append(
                "createFolder failed databaseId=$databaseId parentFolderId=${parentFolderId ?: "null"} " +
                    "nameLength=${name.trim().length} file=${fileForLog?.absolutePath ?: "unresolved"} " +
                    "error=${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
            )
            throw error
        }
    }

    override suspend fun listFolders(databaseId: Long): List<MdbxStoredFolderEntry> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty()) return@withContext emptyList()
                    val epochKey = readEpochKeyOrNull(db, dbInfo)
                    db.rawQuery(
                        """
                        SELECT folder_id, parent_folder_id, name_ct, path_key, object_clock
                        FROM folders
                        WHERE deleted = 0 AND folder_id != 'root'
                        ORDER BY path_key ASC
                        """.trimIndent(),
                        emptyArray()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    MdbxStoredFolderEntry(
                                        folderId = cursor.getString(0),
                                        parentFolderId = if (cursor.isNull(1)) null else cursor.getString(1),
                                        name = decodeVaultText(cursor.getBlob(2), epochKey),
                                        pathKey = cursor.getString(3),
                                        objectClock = cursor.getString(4).toLongOrNull() ?: 0L
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun validateExistingVaultFile(file: File): Unit = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            throw IllegalArgumentException("MDBX file does not exist: ${file.absolutePath}")
        }
        if (!file.isFile || file.length() == 0L) {
            throw IllegalArgumentException("Selected file is empty or not a regular MDBX file")
        }
        openReadOnly(file).use { db ->
            val integrity = queryString(db, "PRAGMA integrity_check")
            if (integrity != "ok") {
                throw IllegalArgumentException("MDBX integrity check failed: $integrity")
            }
            val missingTables = missingMinimumReadableTables(db)
            if (missingTables.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
                )
            }
            requireSupportedVaultFormat(db)
        }
    }

    suspend fun readTigaModeFromVaultFile(file: File): MdbxTigaMode = withContext(Dispatchers.IO) {
        openReadOnly(file).use { db ->
            val mode = queryString(db, "SELECT default_tiga_mode FROM vault_meta LIMIT 1")
                ?: "multi"
            MdbxTigaMode.fromName(mode)
        }
    }

    suspend fun readUnlockMethodFromVaultFile(file: File): MdbxUnlockMethod =
        withContext(Dispatchers.IO) {
            openReadOnly(file).use { db ->
                MdbxUnlockMethod.fromStoredValue(
                    queryString(db, "SELECT unlock_methods FROM vault_meta LIMIT 1")
                )
            }
        }

    suspend fun validateVaultCredentialFile(
        file: File,
        credential: MdbxVaultCredential
    ): Unit = withContext(Dispatchers.IO) {
        openReadOnly(file).use { db ->
            val vaultId = queryString(db, "SELECT vault_id FROM vault_meta LIMIT 1")
                ?: throw IllegalArgumentException("MDBX vault id is missing")
            val unlockMethod = MdbxUnlockMethod.fromStoredValue(
                queryString(db, "SELECT unlock_methods FROM vault_meta LIMIT 1")
            )
            if (unlockMethod != credential.unlockMethod) {
                throw IllegalArgumentException("MDBX unlock method does not match selected credentials")
            }
            val salt = queryVaultMetaBlob(db, "credential_salt")
            val verifier = queryVaultMetaBlob(db, "credential_verifier")
            val wrappedEpochKey =
                if (tableExists(db, "key_epochs")) {
                    queryBlob(
                        db,
                        "SELECT wrapped_epoch_key_ct FROM key_epochs WHERE status = 'active' LIMIT 1"
                    )
                } else {
                    null
                }
            if (salt == null || verifier == null || wrappedEpochKey == null) {
                requireCredentialShape(credential)
                return@withContext
            }
            val kdfProfile = queryVaultMetaString(db, "credential_kdf_profile")
                ?: queryString(db, "SELECT kdf_profile_id FROM key_epochs WHERE status = 'active' LIMIT 1")
                ?: "pbkdf2-sha256:210000"
            val expectedFingerprint = queryVaultMetaString(db, "key_file_fingerprint")
            if (credential.requiresKeyFile() &&
                !expectedFingerprint.isNullOrBlank() &&
                !credential.keyFileFingerprint.equals(expectedFingerprint, ignoreCase = true)
            ) {
                throw IllegalArgumentException("MDBX key file fingerprint does not match")
            }
            val ok = MdbxVaultCrypto.verifyCredential(
                vaultId = vaultId,
                credential = credential,
                salt = salt,
                expectedVerifier = verifier,
                kdfProfile = kdfProfile
            )
            if (!ok) {
                throw IllegalArgumentException("MDBX credentials are incorrect")
            }
        }
    }

    suspend fun prepareVaultForOfficialMdbx1(
        file: File,
        credential: MdbxVaultCredential,
        tigaMode: MdbxTigaMode
    ): Unit = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            throw IllegalArgumentException("MDBX file does not exist: ${file.absolutePath}")
        }
        openExistingWritableVault(file, allowSchemaPreparation = true).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                requireSupportedVaultFormat(db)
                ensureDeviceRegistration(db)
                val now = now()
                db.execSQL(
                    """
                    UPDATE vault_meta
                    SET release_label = CASE
                            WHEN release_label IS NULL OR release_label = '' OR release_label = 'MDBX-test-compatible'
                            THEN ?
                            ELSE release_label
                        END,
                        capability_flags = CASE
                            WHEN capability_flags IS NULL OR capability_flags = '' OR capability_flags = 'legacy-test-compatible'
                            THEN ?
                            ELSE capability_flags
                        END,
                        updated_at = ?
                    """.trimIndent(),
                    arrayOf(MDBX_OFFICIAL_RELEASE_LABEL, MDBX_ANDROID_CAPABILITY_FLAGS, now)
                )
                if (!vaultHasCredentialMaterial(db)) {
                    installOfficialCredentialMaterial(db, credential, tigaMode, now)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            checkpoint(db)
        }
    }

    suspend fun getConflictCount(databaseId: Long): Int = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext 0
        val file = resolveWritableFile(dbInfo) ?: return@withContext 0
        if (!file.exists()) return@withContext 0
        runCatching {
            openReadOnly(file).use { db ->
                if (!tableExists(db, "conflicts")) return@withContext 0
                db.rawQuery(
                    "SELECT COUNT(*) FROM conflicts WHERE resolution = 'unresolved'",
                    emptyArray()
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
    }

    private fun readDiagnostics(
        db: SQLiteDatabase,
        databaseId: Long,
        dbInfo: LocalMdbxDatabase,
        file: File
    ): MdbxVaultDiagnostics {
        val integrity = queryString(db, "PRAGMA integrity_check")
        val integrityOk = integrity == "ok"
        val missingTables = missingRequiredTables(db)
        if (missingTables.isNotEmpty()) {
            return unavailableDiagnostics(
                databaseId = databaseId,
                file = file,
                reason = "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}",
                lastSyncStatus = dbInfo.lastSyncStatus,
                lastSyncError = dbInfo.lastSyncError
            )
        }

        val formatVersion = queryString(db, "SELECT format_version FROM vault_meta LIMIT 1")
        if (!isSupportedVaultFormat(formatVersion)) {
            return unavailableDiagnostics(
                databaseId = databaseId,
                file = file,
                reason = "Unsupported MDBX format version: ${formatVersion ?: "unknown"}",
                lastSyncStatus = dbInfo.lastSyncStatus,
                lastSyncError = dbInfo.lastSyncError
            )
        }

        val danglingParents =
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM commit_parents cp
                LEFT JOIN commits child ON child.commit_id = cp.commit_id
                LEFT JOIN commits parent ON parent.commit_id = cp.parent_commit_id
                WHERE child.commit_id IS NULL OR parent.commit_id IS NULL
                """.trimIndent()
            )
        val danglingBranches =
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM branches b
                LEFT JOIN commits c ON c.commit_id = b.head_commit_id
                WHERE c.commit_id IS NULL
                """.trimIndent()
            )
        val danglingDeviceHeads =
            queryLong(
                db,
                """
                SELECT COUNT(*) FROM device_heads h
                LEFT JOIN commits c ON c.commit_id = h.head_commit_id
                WHERE c.commit_id IS NULL
                """.trimIndent()
            )
        val chunkMismatches =
            if (tableExists(db, "attachments") && tableExists(db, "attachment_chunks")) {
                queryLong(
                    db,
                    """
                    SELECT COUNT(*) FROM attachments a
                    LEFT JOIN (
                        SELECT attachment_id, COUNT(*) AS actual_count
                        FROM attachment_chunks
                        GROUP BY attachment_id
                    ) c ON c.attachment_id = a.attachment_id
                    WHERE a.deleted = 0
                      AND a.chunk_count != COALESCE(c.actual_count, 0)
                    """.trimIndent()
                )
            } else {
                0
            }

        return MdbxVaultDiagnostics(
            databaseId = databaseId,
            filePath = file.absolutePath,
            fileExists = true,
            fileSizeBytes = file.length(),
            isReadable = true,
            formatVersion = formatVersion,
            releaseLabel = queryVaultMetaString(db, "release_label")
                ?: if (formatVersion == MDBX_SCHEMA_FORMAT_VERSION) MDBX_OFFICIAL_RELEASE_LABEL else "MDBX-test-compatible",
            capabilityFlags = queryVaultMetaString(db, "capability_flags"),
            defaultTigaMode = queryString(
                db,
                "SELECT default_tiga_mode FROM vault_meta LIMIT 1"
            ),
            currentDeviceId = deviceId,
            integrityOk = integrityOk,
            integrityMessage = integrity,
            unresolvedConflictCount = queryLong(
                db,
                "SELECT COUNT(*) FROM conflicts WHERE resolution = 'unresolved'"
            ).toInt(),
            pendingSyncCount = calculatePendingSyncCount(db, dbInfo),
            commitCount = queryLong(db, "SELECT COUNT(*) FROM commits").toInt(),
            tombstoneCount = queryLong(db, "SELECT COUNT(*) FROM tombstones").toInt(),
            branchCount = queryLong(db, "SELECT COUNT(*) FROM branches").toInt(),
            deviceCount = queryLong(db, "SELECT COUNT(*) FROM device_heads").toInt(),
            snapshotCount = queryLongIfTableExists(db, "snapshots"),
            folderCount = queryLongIfTableExists(db, "folders"),
            indexedObjectCount = queryLongIfTableExists(
                db,
                "object_index",
                "SELECT COUNT(*) FROM object_index WHERE deleted = 0"
            ),
            entryCount = queryLong(
                db,
                "SELECT COUNT(*) FROM entries WHERE deleted = 0"
            ).toInt(),
            deletedEntryCount = queryLong(
                db,
                "SELECT COUNT(*) FROM entries WHERE deleted = 1"
            ).toInt(),
            attachmentCount = queryLongIfTableExists(
                db,
                "attachments",
                "SELECT COUNT(*) FROM attachments WHERE deleted = 0"
            ),
            externalAttachmentCount = queryLongIfTableExists(
                db,
                "attachments",
                """
                SELECT COUNT(*) FROM attachments
                WHERE deleted = 0 AND lower(storage_mode) LIKE '%external%'
                """.trimIndent()
            ),
            originalAttachmentBytes = queryLongIfTableExistsLong(
                db,
                "attachments",
                """
                SELECT COALESCE(SUM(original_size), 0)
                FROM attachments WHERE deleted = 0
                """.trimIndent()
            ),
            storedAttachmentBytes = queryLongIfTableExistsLong(
                db,
                "attachments",
                """
                SELECT COALESCE(SUM(stored_size), 0)
                FROM attachments WHERE deleted = 0
                """.trimIndent()
            ),
            danglingParentCount = danglingParents.toInt(),
            danglingBranchHeadCount = danglingBranches.toInt(),
            danglingDeviceHeadCount = danglingDeviceHeads.toInt(),
            attachmentChunkMismatchCount = chunkMismatches.toInt(),
            lastSyncStatus = dbInfo.lastSyncStatus,
            lastSyncError = dbInfo.lastSyncError
        )
    }

    override suspend fun getVaultDiagnostics(databaseId: Long): MdbxVaultDiagnostics =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: return@withContext unavailableDiagnostics(
                    databaseId = databaseId,
                    file = null,
                    reason = "Vault record not found",
                    lastSyncStatus = "UNKNOWN",
                    lastSyncError = null
                )
            val file = resolveWritableFile(dbInfo)
            if (file == null) {
                return@withContext unavailableDiagnostics(
                    databaseId = databaseId,
                    file = null,
                    reason = "No writable local copy",
                    lastSyncStatus = dbInfo.lastSyncStatus,
                    lastSyncError = dbInfo.lastSyncError
                )
            }
            if (!file.exists()) {
                return@withContext unavailableDiagnostics(
                    databaseId = databaseId,
                    file = file,
                    reason = "Local copy is missing",
                    lastSyncStatus = dbInfo.lastSyncStatus,
                    lastSyncError = dbInfo.lastSyncError
                )
            }

            runCatching {
                openReadOnly(file).use { db ->
                    readDiagnostics(db, databaseId, dbInfo, file)
                }
            }.getOrElse { error ->
                unavailableDiagnostics(
                    databaseId = databaseId,
                    file = file,
                    reason = error.message ?: error::class.java.simpleName,
                    lastSyncStatus = dbInfo.lastSyncStatus,
                    lastSyncError = dbInfo.lastSyncError
                )
            }
        }

    override suspend fun getPendingSyncCount(databaseId: Long): Int = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext 0
        val status = runCatching { MdbxSyncStatus.valueOf(dbInfo.lastSyncStatus) }.getOrNull()
        if (status == MdbxSyncStatus.LOCAL_ONLY || status == MdbxSyncStatus.IN_SYNC) {
            return@withContext 0
        }
        val file = resolveWritableFile(dbInfo) ?: return@withContext 1
        if (!file.exists()) return@withContext 1
        runCatching {
            openReadOnly(file).use { db ->
                calculatePendingSyncCount(db, dbInfo)
            }
        }.getOrDefault(1).coerceAtLeast(1)
    }

    override suspend fun setProjectTags(
        databaseId: Long,
        projectId: String,
        tags: List<String>
    ) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        withVaultWriteLock(file) {
            openExistingWritableVault(file).use { db ->
                db.beginTransaction()
                try {
                    ensureSchema(db)
                    val epochKey = requireEpochKey(db, dbInfo)
                    requireActiveProject(db, projectId)
                    replaceProjectTags(db, projectId, tags)
                    val commitId = appendCommit(
                        db = db,
                        scope = "project-tags",
                        objectId = projectId,
                        epochKey = epochKey,
                        commitKind = "tag",
                        changedObjectIds = listOf(projectId)
                    )
                    val now = now()
                    db.execSQL(
                        """
                        UPDATE projects
                        SET object_clock = object_clock + 1,
                            head_commit_id = ?,
                            updated_at = ?,
                            updated_by_device_id = ?
                        WHERE project_id = ?
                        """.trimIndent(),
                        arrayOf(commitId, now, deviceId, projectId)
                    )
                    db.execSQL(
                        """
                        UPDATE object_index
                        SET head_commit_id = ?, updated_at = ?
                        WHERE object_type = 'project' AND object_id = ?
                        """.trimIndent(),
                        arrayOf(commitId, now, projectId)
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            markWorkingCopyDirtyAndFlushLocked(dbInfo, file)
        }
    }

    override suspend fun listProjectTags(databaseId: Long, projectId: String): List<String> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            openReadOnly(file).use { db ->
                if (!tableExists(db, "project_tags")) return@withContext emptyList()
                readProjectTags(db, projectId)
            }
        }

    override suspend fun listAllProjectTags(databaseId: Long): List<MdbxProjectTagSummary> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            openReadOnly(file).use { db ->
                if (!tableExists(db, "project_tags")) return@withContext emptyList()
                db.rawQuery(
                    """
                    SELECT tag, COUNT(*) AS project_count
                    FROM project_tags
                    GROUP BY tag
                    ORDER BY project_count DESC, tag ASC
                    """.trimIndent(),
                    emptyArray()
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                MdbxProjectTagSummary(
                                    tag = cursor.getString(0),
                                    projectCount = cursor.getInt(1)
                                )
                            )
                        }
                    }
                }
            }
        }

    override suspend fun searchProjects(
        databaseId: Long,
        query: String,
        requiredTags: List<String>
    ): List<MdbxProjectSearchResult> = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
        val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
        if (!file.exists()) return@withContext emptyList()
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val normalizedTags = normalizeProjectTags(requiredTags)
        val normalizedTagKeys = normalizedTags.map { it.lowercase(Locale.ROOT) }
        openReadOnly(file).use { db ->
            if (!tableExists(db, "projects") || !tableExists(db, "object_index")) {
                return@withContext emptyList()
            }
            val epochKey = readEpochKeyOrNull(db, dbInfo)
            db.rawQuery(
                """
                SELECT p.project_id, p.title_ct, oi.parent_id, p.updated_at
                FROM projects p
                LEFT JOIN object_index oi
                  ON oi.object_type = 'project' AND oi.object_id = p.project_id
                WHERE p.deleted = 0
                ORDER BY p.updated_at DESC, p.project_id ASC
                """.trimIndent(),
                emptyArray()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val projectId = cursor.getString(0)
                        val title = decodeVaultText(cursor.getBlob(1), epochKey)
                        val tags = if (tableExists(db, "project_tags")) {
                            readProjectTags(db, projectId)
                        } else {
                            emptyList()
                        }
                        if (normalizedQuery.isNotBlank()) {
                            val haystack = buildString {
                                append(title.lowercase(Locale.ROOT))
                                append(' ')
                                tags.forEach { append(it.lowercase(Locale.ROOT)).append(' ') }
                            }
                            if (!haystack.contains(normalizedQuery)) continue
                        }
                        if (normalizedTagKeys.isNotEmpty()) {
                            val tagKeys = tags.map { it.lowercase(Locale.ROOT) }
                            if (!tagKeys.containsAll(normalizedTagKeys)) continue
                        }
                        add(
                            MdbxProjectSearchResult(
                                projectId = projectId,
                                title = title,
                                parentFolderId = if (cursor.isNull(2)) null else cursor.getString(2),
                                entryTypes = readProjectEntryTypes(db, projectId),
                                tags = tags,
                                updatedAt = cursor.getString(3)
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun getCurrentHeadCommitId(databaseId: Long): String? =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext null
            val file = resolveWritableFile(dbInfo) ?: return@withContext null
            if (!file.exists()) return@withContext null
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty()) return@withContext null
                    queryString(
                        db,
                        "SELECT head_commit_id FROM branches WHERE branch_id = 'main' OR branch_name = 'main' LIMIT 1"
                    ) ?: queryString(
                        db,
                        "SELECT head_commit_id FROM device_heads WHERE device_id = ? LIMIT 1",
                        arrayOf(deviceId)
                    )
                }
            }.getOrNull()
        }

    override suspend fun listDeltaHistory(databaseId: Long): List<MdbxDeltaSummary> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty()) return@withContext emptyList()
                    val epochKey = readEpochKeyOrNull(db, dbInfo)
                    db.rawQuery(
                        """
                        SELECT c.commit_id, c.device_id, c.local_seq, c.commit_kind,
                               c.change_scope, c.changed_object_ids_ct, COUNT(cp.parent_commit_id) AS parent_count,
                               c.created_at
                        FROM commits c
                        LEFT JOIN commit_parents cp ON cp.commit_id = c.commit_id
                        GROUP BY c.commit_id
                        ORDER BY c.created_at DESC
                        LIMIT 120
                        """.trimIndent(),
                        emptyArray()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val commitId = cursor.getString(0)
                                val changePreview = readCommitChangePreview(db, commitId, epochKey)
                                add(
                                    MdbxDeltaSummary(
                                        commitId = commitId,
                                        deviceId = cursor.getString(1),
                                        localSeq = cursor.getLong(2),
                                        commitKind = cursor.getString(3),
                                        changeScope = cursor.getString(4),
                                        changedObjectIds = decodeVaultText(cursor.getBlob(5), epochKey),
                                        changedObjectPreview = changePreview.objectPreview,
                                        changedFieldSummary = changePreview.fieldSummary,
                                        parentCount = cursor.getInt(6),
                                        createdAt = cursor.getString(7)
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun listCommitDiff(databaseId: Long, commitId: String): List<MdbxCommitDiff> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty() || !tableExists(db, "object_versions")) {
                        return@withContext emptyList()
                    }
                    val epochKey = readEpochKeyOrNull(db, dbInfo)
                    val versions = readObjectVersionsForCommit(db, commitId)
                    versions.map { version ->
                        val previous = readPreviousObjectVersion(
                            db = db,
                            objectType = version.objectType,
                            objectId = version.objectId,
                            beforeCreatedAt = version.createdAt,
                            beforeVersionSeq = version.versionSeq
                        )
                        version.toDiff(db, previous, epochKey)
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun revertCommit(databaseId: Long, commitId: String): Int =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }
            var reverted = 0
            openExistingWritableVault(file).use { db ->
                db.beginTransaction()
                try {
                    ensureSchema(db)
                    if (!tableExists(db, "object_versions")) {
                        throw IllegalStateException("MDBX history has no object version table")
                    }
                    ensureDeviceRegistration(db)
                    val epochKey = requireEpochKey(db, dbInfo)
                    val versions = readObjectVersionsForCommit(db, commitId)
                        .filter { it.objectType == "entry" }
                    if (versions.isEmpty()) {
                        throw IllegalStateException("No entry versions found for commit ${commitId.take(8)}")
                    }
                    versions.forEach { version ->
                        val previous = readPreviousObjectVersion(
                            db = db,
                            objectType = version.objectType,
                            objectId = version.objectId,
                            beforeCreatedAt = version.createdAt,
                            beforeVersionSeq = version.versionSeq
                        ) ?: throw IllegalStateException(
                            "Cannot revert ${version.objectId}: no previous version snapshot"
                        )
                        applyEntryVersionAsRevert(db, previous, epochKey, commitKind = "revert")
                        reverted++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            if (reverted > 0) {
                markWorkingCopyDirty(dbInfo)
            }
            reverted
        }

    override suspend fun listSnapshots(databaseId: Long): List<MdbxSnapshotSummary> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty() || !tableExists(db, "snapshots")) {
                        return@withContext emptyList()
                    }
                    db.rawQuery(
                        """
                        SELECT snapshot_id, base_commit_id, name, snapshot_type, is_full,
                               length(snapshot_ct), created_at, created_by_device_id,
                               auto_prune, snapshot_hash, snapshot_ct
                        FROM snapshots
                        ORDER BY created_at DESC
                        LIMIT 200
                        """.trimIndent(),
                        emptyArray()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val snapshotCt = cursor.getBlob(10)
                                add(
                                    MdbxSnapshotSummary(
                                        snapshotId = cursor.getString(0),
                                        baseCommitId = cursor.getString(1),
                                        name = cursor.getString(2),
                                        snapshotType = cursor.getString(3),
                                        isFull = cursor.getInt(4) != 0,
                                        payloadBytes = cursor.getLong(5),
                                        createdAt = cursor.getString(6),
                                        createdByDeviceId = cursor.getString(7),
                                        autoPrune = cursor.getInt(8) != 0,
                                        integrityOk = sha256Hex(snapshotCt) == cursor.getString(9)
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun createSnapshot(
        databaseId: Long,
        name: String,
        fullSnapshot: Boolean,
        autoPrune: Boolean
    ): MdbxSnapshotSummary = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                val epochKey = requireEpochKey(db, dbInfo)
                val snapshot = createSnapshotLocked(
                    db = db,
                    name = name,
                    fullSnapshot = fullSnapshot,
                    autoPrune = autoPrune,
                    epochKey = epochKey
                )
                db.setTransactionSuccessful()
                snapshot
            } finally {
                db.endTransaction()
            }
        }.also {
            markWorkingCopyDirty(dbInfo)
        }
    }

    override suspend fun deleteSnapshot(databaseId: Long, snapshotId: String) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        openExistingWritableVault(file).use { db ->
            db.execSQL("DELETE FROM snapshots WHERE snapshot_id = ?", arrayOf(snapshotId))
        }
        markWorkingCopyDirtyAndFlush(dbInfo, file)
    }

    override suspend fun revertToSnapshot(databaseId: Long, snapshotId: String): Int =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }
            var restored = 0
            openExistingWritableVault(file).use { db ->
                db.beginTransaction()
                try {
                    ensureSchema(db)
                    val epochKey = requireEpochKey(db, dbInfo)
                    val snapshot = readSnapshotPayload(db, snapshotId, epochKey)
                        ?: throw IllegalStateException("MDBX snapshot not found: $snapshotId")
                    restored = if (snapshot.isFull) {
                        revertToEntryVersionSet(db, snapshot.entries, epochKey)
                    } else {
                        revertToCommitState(db, snapshot.baseCommitId, epochKey)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            if (restored > 0) {
                markWorkingCopyDirty(dbInfo)
            }
            restored
        }

    override suspend fun getSnapshotStructurePreview(
        databaseId: Long,
        snapshotId: String
    ): MdbxStructurePreview = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no readable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        openReadOnly(file).use { db ->
            val missingTables = missingMinimumReadableTables(db)
            if (missingTables.isNotEmpty()) {
                throw IllegalStateException(
                    "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
                )
            }
            val epochKey = requireEpochKey(db, dbInfo)
            val snapshot = readSnapshotPayload(db, snapshotId, epochKey)
                ?: throw IllegalStateException("MDBX snapshot not found: $snapshotId")
            val snapshotName = queryString(
                db,
                "SELECT name FROM snapshots WHERE snapshot_id = ? LIMIT 1",
                arrayOf(snapshotId)
            ) ?: snapshotId.take(8)
            val folders = readStructureFolders(db, epochKey)
            val projectFolderIds = readStructureProjectFolderIds(db)
            val currentEntries = readCurrentEntryVersionStates(db)
                .mapNotNull { it.toStructureEntry(folders, projectFolderIds, epochKey) }
            val snapshotEntries = if (snapshot.isFull) {
                snapshot.entries
            } else {
                readLatestEntryVersionsAtCommit(db, snapshot.baseCommitId)
            }.mapNotNull { it.toStructureEntry(folders, projectFolderIds, epochKey) }
            val currentById = currentEntries.associateBy { it.id }
            val snapshotById = snapshotEntries.associateBy { it.id }
            MdbxStructurePreview(
                snapshotId = snapshotId,
                snapshotName = snapshotName,
                currentNodes = buildStructureNodes(
                    folders = folders,
                    entries = currentEntries,
                    compareEntries = snapshotById,
                    side = StructurePreviewSide.CURRENT
                ),
                snapshotNodes = buildStructureNodes(
                    folders = folders,
                    entries = snapshotEntries,
                    compareEntries = currentById,
                    side = StructurePreviewSide.SNAPSHOT
                ),
                currentItemCount = currentEntries.count { !it.deleted },
                snapshotItemCount = snapshotEntries.count { !it.deleted }
            )
        }
    }

    override suspend fun pruneAutomaticSnapshots(
        databaseId: Long,
        keepCount: Int?,
        maxBytes: Long?
    ): Int = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        val effectiveKeep = keepCount ?: automaticSnapshotRetention(dbInfo)
        var deleted = 0
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                deleted = pruneAutomaticSnapshotsLocked(db, effectiveKeep, maxBytes)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (deleted > 0) {
            markWorkingCopyDirty(dbInfo)
        }
        deleted
    }

    override suspend fun exportSyncBundle(
        databaseId: Long,
        baseCommitId: String?
    ): MdbxSyncBundle = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no readable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        openReadOnly(file).use { db ->
            ensureReadableSchema(db)
            val payload = buildSyncBundlePayload(db, baseCommitId)
            val headCommitId = queryString(
                db,
                "SELECT head_commit_id FROM device_heads WHERE device_id = ?",
                arrayOf(deviceId)
            ) ?: queryString(db, "SELECT head_commit_id FROM branches WHERE branch_id = 'main'")
            ?: ""
            val payloadJson = payload.toString()
            val bundle = MdbxSyncBundle(
                bundleId = UUID.randomUUID().toString(),
                baseCommitId = baseCommitId,
                headCommitId = headCommitId,
                commitCount = payload.getJSONArray("commits").length(),
                payloadJson = payloadJson,
                payloadHash = sha256Hex(payloadJson.toByteArray(Charsets.UTF_8)),
                createdAt = now()
            )
            bundle
        }
    }

    override suspend fun importSyncBundle(databaseId: Long, bundle: MdbxSyncBundle): MdbxApplyResult =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }
            val expectedHash = sha256Hex(bundle.payloadJson.toByteArray(Charsets.UTF_8))
            if (expectedHash != bundle.payloadHash) {
                throw IllegalArgumentException("MDBX sync bundle hash mismatch")
            }
            val payload = JSONObject(bundle.payloadJson)
            var applied = 0
            var keptLocal = 0
            var conflicts = 0
            var tombstones = 0
            openExistingWritableVault(file).use { db ->
                db.beginTransaction()
                try {
                    ensureSchema(db)
                    val epochKey = requireEpochKey(db, dbInfo)
                    applied += importBundleRows(db, payload, epochKey)
                    val latestVersions = latestBundleEntryVersions(payload)
                    latestVersions.forEach { version ->
                        when (applyBundleEntryVersion(db, version, epochKey)) {
                            ApplyDecision.APPLIED -> applied++
                            ApplyDecision.KEPT_LOCAL -> keptLocal++
                            ApplyDecision.CONFLICT -> conflicts++
                        }
                    }
                    applied += importBundleProjectTags(db, payload)
                    tombstones = payload.optJSONArray("tombstones")?.length() ?: 0
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO sync_bundles (
                            bundle_id, base_commit_id, head_commit_id, commit_count,
                            payload_json, payload_hash, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            bundle.bundleId,
                            bundle.baseCommitId,
                            bundle.headCommitId,
                            bundle.commitCount,
                            bundle.payloadJson,
                            bundle.payloadHash,
                            bundle.createdAt
                        )
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            if (applied > 0 || tombstones > 0 || conflicts > 0) {
                markWorkingCopyDirty(dbInfo)
            }
            MdbxApplyResult(
                appliedObjectCount = applied,
                keptLocalObjectCount = keptLocal,
                conflictCount = conflicts,
                tombstoneCount = tombstones
            )
        }

    override suspend fun upsertExternalAttachmentRef(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment,
        externalUri: String
    ) = withContext(Dispatchers.IO) {
        val contentHash = attachment.sha256Hex?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("External MDBX attachment requires a content hash")
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                ensureDeviceRegistration(db)
                val epochKey = requireEpochKey(db, dbInfo)
                val attachmentId = attachmentObjectId(parentEntryId, attachment)
                val commitId = appendCommit(db, "attachment", attachmentId, epochKey)
                val now = now()
                val portableCek = attachment.wrappedCek
                    ?.takeIf { it.isNotBlank() }
                    ?.let { wrapped ->
                        MdbxAttachmentCekPayload.fromLocalWrappedCek(
                            wrappedCek = wrapped,
                            unwrapToBase64 = securityManager::decryptData
                        )
                    }
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO attachments (
                        attachment_id, project_id, entry_id, file_name_ct, media_type_ct,
                        storage_mode, content_hash, original_size, stored_size, chunk_count,
                        head_commit_id, deleted, created_at, updated_at, created_by_device_id,
                        updated_by_device_id, wrapped_cek_ct, attachment_created_at,
                        attachment_updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'external-hash-ref', ?, ?, 0, 1, ?, 0, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        attachmentId,
                        parentEntryId,
                        parentEntryId,
                        encrypt(attachment.fileName, epochKey),
                        encrypt(attachment.mimeType, epochKey),
                        contentHash,
                        attachment.sizeBytes,
                        commitId,
                        now,
                        now,
                        deviceId,
                        deviceId,
                        portableCek?.let { encrypt(it, epochKey) },
                        attachment.createdAt,
                        attachment.updatedAt
                    )
                )
                db.execSQL("DELETE FROM attachment_chunks WHERE attachment_id = ?", arrayOf(attachmentId))
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO attachment_chunks (
                        attachment_id, chunk_index, chunk_hash, chunk_ct, external_uri_ct,
                        stored_size, created_at
                    ) VALUES (?, 0, ?, NULL, ?, 0, ?)
                    """.trimIndent(),
                    arrayOf(attachmentId, contentHash, encrypt(externalUri, epochKey), now)
                )
                upsertObjectIndex(
                    db = db,
                    objectType = "attachment",
                    objectId = attachmentId,
                    parentFolderId = parentEntryId,
                    title = attachment.fileName,
                    entryType = "attachment",
                    commitId = commitId,
                    deleted = false
                )
                recordCryptoContext(db, "attachment", attachmentId, "external_uri_ct", "attachment", now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        markWorkingCopyDirtyAndFlush(dbInfo, file)
    }

    suspend fun runBenchmark(
        databaseId: Long,
        scenario: String = "metadata-commit",
        operationCount: Int = 1
    ): MdbxBenchmarkResult = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        val beforeBytes = file.takeIf { it.exists() }?.length() ?: 0L
        val started = System.nanoTime()
        val runId = UUID.randomUUID().toString()
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                val epochKey = requireEpochKey(db, dbInfo)
                repeat(operationCount.coerceAtLeast(1)) { index ->
                    appendCommit(db, "benchmark", "$runId:$index", epochKey, commitKind = "benchmark")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        val afterBytes = file.takeIf { it.exists() }?.length() ?: beforeBytes
        val result = MdbxBenchmarkResult(
            runId = runId,
            scenario = scenario,
            operationCount = operationCount.coerceAtLeast(1),
            elapsedMs = elapsedMs,
            fileDeltaBytes = afterBytes - beforeBytes,
            createdAt = now()
        )
        openExistingWritableVault(file).use { db ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO mdbx_benchmarks (
                    run_id, scenario, operation_count, elapsed_ms, file_delta_bytes, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    result.runId,
                    result.scenario,
                    result.operationCount,
                    result.elapsedMs,
                    result.fileDeltaBytes,
                    result.createdAt
                )
            )
        }
        result
    }

    override suspend fun flushPendingWorkingCopy(databaseId: Long) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        if (dbInfo.lastSyncStatus != MdbxSyncStatus.PENDING_UPLOAD.name) return@withContext
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        withVaultWriteLock(file) {
            flushWorkingCopyToSourceIfNeeded(dbInfo, file)
        }
    }

    override suspend fun flushWorkingCopy(databaseId: Long) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        withVaultWriteLock(file) {
            flushWorkingCopyToSourceIfNeeded(dbInfo, file)
        }
    }

    override suspend fun listConflicts(databaseId: Long): List<MdbxConflictSummary> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId) ?: return@withContext emptyList()
            val file = resolveWritableFile(dbInfo) ?: return@withContext emptyList()
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                openReadOnly(file).use { db ->
                    if (missingRequiredTables(db).isNotEmpty()) return@withContext emptyList()
                    db.rawQuery(
                        """
                        SELECT conflict_id, object_type, object_id, base_commit_id,
                               local_commit_id, incoming_commit_id, conflicting_fields, created_at,
                               local_title_ct, incoming_title_ct, local_payload_ct, incoming_payload_ct
                        FROM conflicts
                        WHERE resolution = 'unresolved'
                        ORDER BY created_at DESC
                        LIMIT 100
                        """.trimIndent(),
                        emptyArray()
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    MdbxConflictSummary(
                                        conflictId = cursor.getString(0),
                                        objectType = cursor.getString(1),
                                        objectId = cursor.getString(2),
                                        baseCommitId = cursor.getString(3),
                                        localCommitId = cursor.getString(4),
                                        incomingCommitId = cursor.getString(5),
                                        conflictingFields = cursor.getString(6),
                                        createdAt = cursor.getString(7),
                                        localTitle = decodeNullableVaultText(cursor, 8),
                                        incomingTitle = decodeNullableVaultText(cursor, 9),
                                        localPayloadPreview = summarizeConflictPayload(
                                            decodeNullableVaultText(cursor, 10)
                                        ),
                                        incomingPayloadPreview = summarizeConflictPayload(
                                            decodeNullableVaultText(cursor, 11)
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun resolveConflict(
        databaseId: Long,
        conflictId: String,
        resolution: MdbxConflictResolution
    ) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                val epochKey = requireEpochKey(db, dbInfo)
                val conflict = readConflictForResolution(db, conflictId)
                    ?: throw IllegalStateException("Unresolved MDBX conflict not found: $conflictId")
                if (conflict.objectType.equals("entry", ignoreCase = true)) {
                    resolveEntryConflict(
                        db = db,
                        conflict = conflict,
                        resolution = resolution,
                        epochKey = epochKey
                    )
                } else {
                    val resolutionCommitId = appendCommit(db, "conflict", conflictId, epochKey)
                    when (resolution) {
                        MdbxConflictResolution.LOCAL_WINS -> applyConflictWinnerMetadata(
                            db = db,
                            conflict = conflict,
                            winnerCommitId = conflict.localCommitId,
                            resolutionCommitId = resolutionCommitId,
                            winnerTitleCt = conflict.localTitleCt,
                            winnerPayloadCt = conflict.localPayloadCt
                        )
                        MdbxConflictResolution.INCOMING_WINS -> applyConflictWinnerMetadata(
                            db = db,
                            conflict = conflict,
                            winnerCommitId = conflict.incomingCommitId,
                            resolutionCommitId = resolutionCommitId,
                            winnerTitleCt = conflict.incomingTitleCt,
                            winnerPayloadCt = conflict.incomingPayloadCt
                        )
                        MdbxConflictResolution.CUSTOM_MERGE,
                        MdbxConflictResolution.MARK_RESOLVED -> Unit
                    }
                }
                db.execSQL(
                    """
                    UPDATE conflicts
                    SET resolution = ?, resolved_at = ?
                    WHERE conflict_id = ? AND resolution = 'unresolved'
                    """.trimIndent(),
                    arrayOf(resolution.storedValue, now(), conflictId)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        markWorkingCopyDirtyAndFlush(dbInfo, file)
    }

    suspend fun applyIncomingVaultFile(
        databaseId: Long,
        incomingFile: File
    ): MdbxApplyResult = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val localFile = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")

        withVaultWriteLock(localFile) {
            if (!localFile.exists()) {
                localFile.parentFile?.mkdirs()
                incomingFile.copyTo(localFile, overwrite = true)
                validateExistingVaultFile(localFile)
                return@withVaultWriteLock MdbxApplyResult(
                    appliedObjectCount = 0,
                    keptLocalObjectCount = 0,
                    conflictCount = 0,
                    tombstoneCount = 0
                )
            }

            val result = openReadOnly(incomingFile).use { incoming ->
                val incomingMissingTables = missingRequiredTables(incoming)
                if (incomingMissingTables.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "Unsupported incoming MDBX schema, missing tables: ${incomingMissingTables.joinToString()}"
                    )
                }
                requireSupportedVaultFormat(incoming, label = "incoming MDBX")

                openExistingWritableVault(localFile).use { local ->
                    var applied = 0
                    var keptLocal = 0
                    var conflicts = 0
                    var tombstones = 0
                    local.beginTransaction()
                    try {
                        ensureSchema(local)
                        ensureDeviceRegistration(local)
                        val localEpochKey = requireEpochKey(local, dbInfo)
                        val incomingEpochKey = resolveIncomingEpochKey(
                            local = local,
                            incoming = incoming,
                            dbInfo = dbInfo,
                            localEpochKey = localEpochKey
                        )
                        copyIncomingHistory(local, incoming)
                        copyIncomingFolders(local, incoming)
                        tombstones = copyIncomingTombstones(local, incoming)

                        readIncomingProjects(incoming).forEach { project ->
                            when (applyIncomingProject(local, incoming, project, localEpochKey, incomingEpochKey)) {
                                ApplyDecision.APPLIED -> applied++
                                ApplyDecision.KEPT_LOCAL -> keptLocal++
                                ApplyDecision.CONFLICT -> conflicts++
                            }
                        }
                        readIncomingEntries(incoming).forEach { entry ->
                            when (applyIncomingEntry(local, incoming, entry, localEpochKey, incomingEpochKey)) {
                                ApplyDecision.APPLIED -> applied++
                                ApplyDecision.KEPT_LOCAL -> keptLocal++
                                ApplyDecision.CONFLICT -> conflicts++
                            }
                        }
                        readIncomingAttachments(incoming).forEach { attachment ->
                            when (applyIncomingAttachment(local, incoming, attachment, localEpochKey, incomingEpochKey)) {
                                ApplyDecision.APPLIED -> applied++
                                ApplyDecision.KEPT_LOCAL -> keptLocal++
                                ApplyDecision.CONFLICT -> conflicts++
                            }
                        }
                        local.setTransactionSuccessful()
                    } finally {
                        local.endTransaction()
                    }
                    checkpoint(local)
                    MdbxApplyResult(
                        appliedObjectCount = applied,
                        keptLocalObjectCount = keptLocal,
                        conflictCount = conflicts,
                        tombstoneCount = tombstones
                    )
                }
            }
            flushWorkingCopyToSourceIfNeeded(dbInfo, localFile)
            result
        }
    }

    override suspend fun upsertPassword(entry: PasswordEntry) {
        passwordEntryMutation(entry)?.let { upsertEntryMutations(listOf(it)) }
    }

    override suspend fun upsertPasswords(entries: List<PasswordEntry>) {
        upsertEntryMutations(entries.mapNotNull { passwordEntryMutation(it) })
    }

    override suspend fun upsertSecureItem(item: SecureItem) {
        secureItemEntryMutation(item)?.let { upsertEntryMutations(listOf(it)) }
    }

    override suspend fun upsertSecureItems(items: List<SecureItem>) {
        upsertEntryMutations(items.mapNotNull { secureItemEntryMutation(it) })
    }

    override suspend fun upsertPasskey(passkey: PasskeyEntry) {
        passkeyEntryMutation(passkey)?.let { upsertEntryMutations(listOf(it)) }
    }

    override suspend fun upsertPasskeys(passkeys: List<PasskeyEntry>) {
        upsertEntryMutations(passkeys.mapNotNull { passkeyEntryMutation(it) })
    }

    override suspend fun deletePassword(entry: PasswordEntry) {
        passwordEntryDeleteMutation(entry)?.let { deleteEntryMutations(listOf(it)) }
    }

    override suspend fun deletePasswords(entries: List<PasswordEntry>) {
        deleteEntryMutations(entries.mapNotNull { passwordEntryDeleteMutation(it) })
    }

    override suspend fun deleteSecureItem(item: SecureItem) {
        secureItemEntryDeleteMutation(item)?.let { deleteEntryMutations(listOf(it)) }
    }

    override suspend fun deleteSecureItems(items: List<SecureItem>) {
        deleteEntryMutations(items.mapNotNull { secureItemEntryDeleteMutation(it) })
    }

    override suspend fun deletePasskey(passkey: PasskeyEntry) {
        passkeyEntryDeleteMutation(passkey)?.let { deleteEntryMutations(listOf(it)) }
    }

    override suspend fun deletePasskeys(passkeys: List<PasskeyEntry>) {
        deleteEntryMutations(passkeys.mapNotNull { passkeyEntryDeleteMutation(it) })
    }

    override suspend fun listSteamMaFileEntries(databaseId: Long): List<MdbxStoredVaultEntry> {
        return readStoredEntries(databaseId)
            .filterNot { it.deleted }
            .filter { entry ->
                entry.entryType.equals(STEAM_MAFILE_ENTRY_TYPE, ignoreCase = true) ||
                    entry.entryType.equals("steam_mafile", ignoreCase = true)
            }
    }

    override suspend fun upsertSteamMaFileEntry(
        databaseId: Long,
        entryId: String?,
        title: String,
        maFileJson: String
    ): String {
        val resolvedEntryId = entryId?.takeIf { it.isNotBlank() }
            ?: steamMaFileObjectId(maFileJson)
        val payload = JSONObject()
            .put("kind", "steam_mafile")
            .put("steamid", steamIdFromSteamMaFileJson(maFileJson).orEmpty())
            .put("account_name", accountNameFromSteamMaFileJson(maFileJson).orEmpty())
            .put("mafile_json", maFileJson)
        upsertEntryMutations(
            listOf(
                MdbxEntryMutation(
                    databaseId = databaseId,
                    projectId = resolvedEntryId,
                    entryId = resolvedEntryId,
                    entryType = STEAM_MAFILE_ENTRY_TYPE,
                    title = title,
                    payloadJson = payload.toString(),
                    deleted = false
                )
            )
        )
        return resolvedEntryId
    }

    override suspend fun deleteSteamMaFileEntry(databaseId: Long, entryId: String) {
        if (entryId.isBlank()) return
        deleteEntryMutations(listOf(MdbxEntryDeleteMutation(databaseId, entryId)))
    }

    private suspend fun passwordEntryMutation(entry: PasswordEntry): MdbxEntryMutation? {
        val databaseId = entry.mdbxDatabaseId ?: return null
        val entryId = passwordObjectId(entry)
        val payload = JSONObject()
            .put("kind", "password")
            .put("room_id", entry.id)
            .put("website", entry.website)
            .put("username", entry.username)
            .put("app_package_name", entry.appPackageName)
            .put("app_name", entry.appName)
            .put(
                "password_plain",
                runCatching { securityManager.decryptData(entry.password) }.getOrDefault(entry.password)
            )
            .put("notes", entry.notes)
            .put("sort_order", entry.sortOrder)
            .put("category_id", entry.categoryId)
            .put("mdbx_folder_id", entry.mdbxFolderId)
            .put("bound_note_room_id", entry.boundNoteId)
            .put("bound_note_entry_id", resolveBoundNoteEntryId(entry))
            .put("login_type", entry.loginType)
            .put(
                "authenticator_key",
                portableSensitiveValueForMdbx(
                    value = entry.authenticatorKey,
                    fieldName = "authenticator_key",
                    roomId = entry.id
                )
            )
            .put("passkey_bindings", entry.passkeyBindings)
            .put("custom_fields", passwordCustomFieldsPayload(entry.id))
            .put("bitwarden_mode", entry.bitwardenVaultId != null)
            .put("keepass_mode", entry.keepassDatabaseId != null)
        return MdbxEntryMutation(
            databaseId = databaseId,
            projectId = entryId,
            entryId = entryId,
            entryType = "login",
            title = entry.title,
            payloadJson = payload.toString(),
            deleted = entry.isDeleted,
            legacyEntryId = legacyPasswordObjectId(entry)
        )
    }

    private suspend fun passwordCustomFieldsPayload(entryId: Long): JSONArray {
        val fields = customFieldDao?.getFieldsByEntryIdSync(entryId).orEmpty()
        return JSONArray().also { array ->
            fields
                .filter { it.title.isNotBlank() }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .forEach { field ->
                    array.put(
                        JSONObject()
                            .put("title", field.title)
                            .put("value", field.value)
                            .put("is_protected", field.isProtected)
                            .put("sort_order", field.sortOrder)
                    )
                }
        }
    }

    private suspend fun secureItemEntryMutation(item: SecureItem): MdbxEntryMutation? {
        val databaseId = item.mdbxDatabaseId ?: return null
        val prefix = secureItemEntryPrefix(item)
        val entryId = secureItemObjectId(item, prefix)
        val payload = JSONObject()
            .put("kind", item.itemType.name.lowercase())
            .put("room_id", item.id)
            .put("notes", item.notes)
            .put("sort_order", item.sortOrder)
            .put(
                "item_data",
                portableSensitiveValueForMdbx(
                    value = item.itemData,
                    fieldName = "item_data",
                    roomId = item.id
                )
            )
            .put("image_paths", item.imagePaths)
            .put("category_id", item.categoryId)
            .put("mdbx_folder_id", item.mdbxFolderId)
            .put("bound_password_entry_id", resolveBoundPasswordEntryId(item))
            .put("bitwarden_mode", item.bitwardenVaultId != null)
            .put("keepass_mode", item.keepassDatabaseId != null)
        return MdbxEntryMutation(
            databaseId = databaseId,
            projectId = entryId,
            entryId = entryId,
            entryType = prefix,
            title = item.title,
            payloadJson = payload.toString(),
            deleted = item.isDeleted
        )
    }

    private fun portableSensitiveValueForMdbx(
        value: String,
        fieldName: String,
        roomId: Long
    ): String {
        if (value.isBlank() || !securityManager.looksLikeMonicaCiphertext(value)) {
            return value
        }

        return runCatching { securityManager.decryptData(value) }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot write encrypted $fieldName for Room item $roomId into MDBX without decrypting it",
                error
            )
        }
    }

    private fun passkeyEntryMutation(passkey: PasskeyEntry): MdbxEntryMutation? {
        val databaseId = passkey.mdbxDatabaseId ?: return null
        val entryId = passkeyObjectId(passkey)
        val payload = JSONObject()
            .put("kind", "passkey")
            .put("room_id", passkey.id)
            .put("credential_id", passkey.credentialId)
            .put("rp_id", passkey.rpId)
            .put("rp_name", passkey.rpName)
            .put("user_id", passkey.userId)
            .put("user_name", passkey.userName)
            .put("user_display_name", passkey.userDisplayName)
            .put("public_key_algorithm", passkey.publicKeyAlgorithm)
            .put("public_key", passkey.publicKey)
            .put("private_key_alias", portablePasskeyPrivateKeyForMdbx(passkey))
            .put("transports", passkey.transports)
            .put("aaguid", passkey.aaguid)
            .put("sign_count", passkey.signCount)
            .put("notes", passkey.notes)
            .put("passkey_mode", passkey.passkeyMode)
            .put("mdbx_folder_id", passkey.mdbxFolderId)
            .put("bitwarden_compatible", passkey.isBitwardenCompatible())
            .put("keepass_compatible", passkey.isKeePassCompatible())
        return MdbxEntryMutation(
            databaseId = databaseId,
            projectId = entryId,
            entryId = entryId,
            entryType = "passkey",
            title = passkey.rpName.ifBlank { passkey.rpId },
            payloadJson = payload.toString(),
            deleted = false
        )
    }

    private fun portablePasskeyPrivateKeyForMdbx(passkey: PasskeyEntry): String {
        return PasskeyPrivateKeyStore.resolve(context, passkey.privateKeyAlias).orEmpty()
    }

    private fun passwordEntryDeleteMutation(entry: PasswordEntry): MdbxEntryDeleteMutation? =
        entry.mdbxDatabaseId?.let { MdbxEntryDeleteMutation(it, passwordObjectId(entry)) }

    private fun secureItemEntryDeleteMutation(item: SecureItem): MdbxEntryDeleteMutation? =
        item.mdbxDatabaseId?.let {
            MdbxEntryDeleteMutation(it, secureItemObjectId(item, secureItemEntryPrefix(item)))
        }

    private fun passkeyEntryDeleteMutation(passkey: PasskeyEntry): MdbxEntryDeleteMutation? =
        passkey.mdbxDatabaseId?.let { MdbxEntryDeleteMutation(it, passkeyObjectId(passkey)) }

    private fun secureItemEntryPrefix(item: SecureItem): String =
        when (item.itemType) {
            ItemType.NOTE -> "note"
            ItemType.TOTP -> "totp"
            ItemType.BANK_CARD -> "card"
            ItemType.DOCUMENT -> "document-ref"
            ItemType.BILLING_ADDRESS -> "billing-address"
            ItemType.PAYMENT_ACCOUNT -> "payment-account"
            ItemType.PASSWORD -> "password"
        }

    override suspend fun upsertAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ) = withContext(Dispatchers.IO) {
        val localPath = attachment.localPath?.takeIf { it.isNotBlank() } ?: return@withContext
        val wrappedCek = attachment.wrappedCek?.takeIf { it.isNotBlank() } ?: return@withContext
        val blobFile = File(File(context.filesDir, "secure_attachments"), localPath)
        if (!blobFile.exists() || !blobFile.isFile) return@withContext
        val blob = blobFile.readBytes()
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                ensureDeviceRegistration(db)
                val epochKey = requireEpochKey(db, dbInfo)
                val attachmentId = attachmentObjectId(parentEntryId, attachment)
                val commitId = appendCommit(db, "attachment", attachmentId, epochKey)
                val now = now()
                val projectId = parentEntryId
                val portableCek = MdbxAttachmentCekPayload.fromLocalWrappedCek(
                    wrappedCek = wrappedCek,
                    unwrapToBase64 = securityManager::decryptData
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO attachments (
                        attachment_id, project_id, entry_id, file_name_ct, media_type_ct,
                        storage_mode, content_hash, original_size, stored_size, chunk_count,
                        head_commit_id, deleted, created_at, updated_at, created_by_device_id,
                        updated_by_device_id, wrapped_cek_ct, attachment_created_at,
                        attachment_updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'embedded', ?, ?, ?, 1, ?, 0, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        attachmentId,
                        projectId,
                        parentEntryId,
                        encrypt(attachment.fileName, epochKey),
                        encrypt(attachment.mimeType, epochKey),
                        attachment.sha256Hex ?: sha256Hex(blob),
                        attachment.sizeBytes,
                        blob.size.toLong(),
                        commitId,
                        now,
                        now,
                        deviceId,
                        deviceId,
                        encrypt(portableCek, epochKey),
                        attachment.createdAt,
                        attachment.updatedAt
                    )
                )
                db.execSQL("DELETE FROM attachment_chunks WHERE attachment_id = ?", arrayOf(attachmentId))
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO attachment_chunks (
                        attachment_id, chunk_index, chunk_hash, chunk_ct, external_uri_ct,
                        stored_size, created_at
                    ) VALUES (?, 0, ?, ?, NULL, ?, ?)
                    """.trimIndent(),
                    arrayOf(attachmentId, sha256Hex(blob), blob, blob.size.toLong(), now)
                )
                upsertObjectIndex(
                    db = db,
                    objectType = "attachment",
                    objectId = attachmentId,
                    parentFolderId = parentEntryId,
                    title = attachment.fileName,
                    entryType = "attachment",
                    commitId = commitId,
                    deleted = false
                )
                db.execSQL(
                    """
                    UPDATE projects
                    SET attachment_count = (
                        SELECT COUNT(*) FROM attachments
                        WHERE project_id = ? AND deleted = 0
                    ), updated_at = ?
                    WHERE project_id = ?
                    """.trimIndent(),
                    arrayOf(projectId, now, projectId)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        markWorkingCopyDirtyAndFlush(dbInfo, file)
    }

    override suspend fun deleteAttachment(
        databaseId: Long,
        parentEntryId: String,
        attachment: Attachment
    ) = withContext(Dispatchers.IO) {
        val dbInfo = databaseDao.getDatabaseById(databaseId)
            ?: throw IllegalStateException("MDBX vault not found: $databaseId")
        val file = resolveWritableFile(dbInfo)
            ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
        openExistingWritableVault(file).use { db ->
            db.beginTransaction()
            try {
                ensureSchema(db)
                ensureDeviceRegistration(db)
                val epochKey = requireEpochKey(db, dbInfo)
                val attachmentId = attachmentObjectId(parentEntryId, attachment)
                val commitId = appendCommit(db, "attachment", attachmentId, epochKey)
                val now = now()
                db.execSQL(
                    """
                    UPDATE attachments
                    SET deleted = 1, head_commit_id = ?, updated_at = ?,
                        updated_by_device_id = ?, attachment_updated_at = ?
                    WHERE attachment_id = ?
                    """.trimIndent(),
                    arrayOf(commitId, now, deviceId, System.currentTimeMillis(), attachmentId)
                )
                db.execSQL(
                    """
                    UPDATE object_index
                    SET deleted = 1, updated_at = ?, head_commit_id = ?
                    WHERE object_type = 'attachment' AND object_id = ?
                    """.trimIndent(),
                    arrayOf(now, commitId, attachmentId)
                )
                insertTombstone(db, "attachment", attachmentId)
                db.execSQL(
                    """
                    UPDATE projects
                    SET attachment_count = (
                        SELECT COUNT(*) FROM attachments
                        WHERE project_id = ? AND deleted = 0
                    ), updated_at = ?
                    WHERE project_id = ?
                    """.trimIndent(),
                    arrayOf(parentEntryId, now, parentEntryId)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        markWorkingCopyDirtyAndFlush(dbInfo, file)
    }

    private suspend fun upsertEntryMutations(mutations: List<MdbxEntryMutation>) =
        mutateEntriesByVault(mutations) { db, vaultMutations, epochKey ->
            val sharedCommit = sharedEntryCommit(db, vaultMutations.map { it.entryId }, epochKey)
            val now = now()
            vaultMutations.forEach { mutation ->
                writeEntryMutation(db, mutation, epochKey, sharedCommit, now)
            }
        }

    private suspend fun deleteEntryMutations(mutations: List<MdbxEntryDeleteMutation>) =
        mutateEntriesByVault(mutations) { db, vaultMutations, epochKey ->
            val sharedCommit = sharedEntryCommit(db, vaultMutations.map { it.entryId }, epochKey)
            val now = now()
            vaultMutations.forEach { mutation ->
                writeEntryDeleteMutation(db, mutation, epochKey, sharedCommit, now)
            }
        }

    private suspend fun <T : Any> mutateEntriesByVault(
        mutations: List<T>,
        writeMutations: (SQLiteDatabase, List<T>, ByteArray?) -> Unit
    ) = withContext(Dispatchers.IO) {
        mutations.groupBy { mutationDatabaseId(it) }.forEach { (databaseId, vaultMutations) ->
            if (vaultMutations.isEmpty()) return@forEach
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no writable local copy: ${dbInfo.name}")
            withVaultWriteLock(file) {
                openExistingWritableVault(file).use { db ->
                    db.beginTransaction()
                    try {
                        ensureSchema(db)
                        ensureDeviceRegistration(db)
                        val epochKey = requireEpochKey(db, dbInfo)
                        writeMutations(db, vaultMutations, epochKey)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
                markWorkingCopyDirtyAndFlushLocked(dbInfo, file)
            }
        }
    }

    private fun mutationDatabaseId(mutation: Any): Long =
        when (mutation) {
            is MdbxEntryMutation -> mutation.databaseId
            is MdbxEntryDeleteMutation -> mutation.databaseId
            else -> throw IllegalArgumentException("Unsupported MDBX mutation: ${mutation::class.java.name}")
        }

    private fun sharedEntryCommit(
        db: SQLiteDatabase,
        entryIds: List<String>,
        epochKey: ByteArray?
    ): String {
        val changedEntryIds = entryIds.distinct()
        return appendCommit(
            db = db,
            scope = "entry",
            objectId = changedEntryIds.firstOrNull() ?: "entry-batch",
            epochKey = epochKey,
            changedObjectIds = changedEntryIds
        )
    }

    private fun writeEntryMutation(
        db: SQLiteDatabase,
        mutation: MdbxEntryMutation,
        epochKey: ByteArray?,
        commitId: String = appendCommit(db, "entry", mutation.entryId, epochKey),
        now: String = now()
    ) {
        val folderId = folderIdFromPayload(mutation.payloadJson)
        val titleCt = encrypt(mutation.title, epochKey)
        val payloadCt = encrypt(mutation.payloadJson, epochKey)
        ensureFolder(db, folderId, epochKey)
        db.execSQL(
            """
            INSERT OR IGNORE INTO projects (
                project_id, title_ct, group_id, object_clock, head_commit_id,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(mutation.projectId, titleCt, folderId, "1", commitId, now, now, deviceId, deviceId)
        )
        db.execSQL(
            """
            UPDATE projects SET title_ct = ?, group_id = ?, object_clock = object_clock + 1,
                head_commit_id = ?, deleted = ?, updated_at = ?, updated_by_device_id = ?
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(titleCt, folderId, commitId, if (mutation.deleted) 1 else 0, now, deviceId, mutation.projectId)
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO entries (
                entry_id, project_id, entry_type, title_ct, payload_ct,
                payload_schema_version, object_clock, head_commit_id, deleted,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                mutation.entryId,
                mutation.projectId,
                mutation.entryType,
                titleCt,
                payloadCt,
                "1",
                commitId,
                if (mutation.deleted) 1 else 0,
                now,
                now,
                deviceId,
                deviceId
            )
        )
        upsertObjectIndex(
            db = db,
            objectType = "project",
            objectId = mutation.projectId,
            parentFolderId = folderId,
            title = mutation.title,
            entryType = mutation.entryType,
            commitId = commitId,
            deleted = mutation.deleted
        )
        upsertObjectIndex(
            db = db,
            objectType = "entry",
            objectId = mutation.entryId,
            parentFolderId = mutation.projectId,
            title = mutation.title,
            entryType = mutation.entryType,
            commitId = commitId,
            deleted = mutation.deleted
        )
        if (mutation.deleted) {
            insertTombstone(db, "entry", mutation.entryId)
            insertTombstone(db, "project", mutation.projectId)
        } else {
            clearTombstone(db, "entry", mutation.entryId)
            clearTombstone(db, "project", mutation.projectId)
        }
        mutation.legacyEntryId
            ?.takeIf { it != mutation.entryId }
            ?.let { legacyEntryId ->
                markLegacyEntryDeleted(db, legacyEntryId, commitId, now)
            }
        recordEntryVersion(
            db = db,
            commitId = commitId,
            projectId = mutation.projectId,
            entryId = mutation.entryId,
            entryType = mutation.entryType,
            titleCt = titleCt,
            payloadCt = payloadCt,
            deleted = mutation.deleted,
            createdAt = now
        )
    }

    private fun markLegacyEntryDeleted(
        db: SQLiteDatabase,
        legacyEntryId: String,
        commitId: String,
        now: String
    ) {
        db.execSQL(
            """
            UPDATE projects SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, legacyEntryId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_type = 'project' AND object_id = ?",
            arrayOf(now, commitId, legacyEntryId)
        )
        db.execSQL(
            """
            UPDATE entries SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, legacyEntryId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_type = 'entry' AND object_id = ?",
            arrayOf(now, commitId, legacyEntryId)
        )
        insertTombstone(db, "project", legacyEntryId)
        insertTombstone(db, "entry", legacyEntryId)
    }

    private fun writeEntryDeleteMutation(
        db: SQLiteDatabase,
        mutation: MdbxEntryDeleteMutation,
        epochKey: ByteArray?,
        commitId: String = appendCommit(db, "entry", mutation.entryId, epochKey),
        now: String = now()
    ) {
        val version = readEntryVersionState(db, mutation.entryId)
        val projectId = version?.projectId ?: mutation.entryId
        db.execSQL(
            """
            UPDATE entries SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, mutation.entryId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_id = ?",
            arrayOf(now, commitId, mutation.entryId)
        )
        db.execSQL(
            """
            UPDATE projects SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, projectId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_type = 'project' AND object_id = ?",
            arrayOf(now, commitId, projectId)
        )
        insertTombstone(db, "entry", mutation.entryId)
        insertTombstone(db, "project", projectId)
        version?.let {
            recordEntryVersion(
                db = db,
                commitId = commitId,
                projectId = projectId,
                entryId = mutation.entryId,
                entryType = it.entryType ?: "entry",
                titleCt = it.titleCt,
                payloadCt = it.payloadCt,
                deleted = true,
                createdAt = now
            )
        }
    }

    override suspend fun readStoredEntries(databaseId: Long): List<MdbxStoredVaultEntry> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no readable local copy: ${dbInfo.name}")
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }
            openReadOnly(file).use { db ->
                val epochKey = readEpochKeyOrNull(db, dbInfo)
                val missingTables = missingRequiredTables(db)
                if (missingTables.isNotEmpty()) {
                    throw IllegalStateException(
                        "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
                    )
                }
                db.rawQuery(
                    """
                    SELECT entry_id, entry_type, title_ct, payload_ct, deleted
                    FROM entries
                    ORDER BY updated_at DESC, entry_id ASC
                    """.trimIndent(),
                    emptyArray()
                ).use { cursor ->
                    val entries = buildList {
                        while (cursor.moveToNext()) {
                            add(
                                MdbxStoredVaultEntry(
                                    entryId = cursor.getString(0),
                                    entryType = cursor.getString(1),
                                    title = decodeVaultText(cursor.getBlob(2), epochKey),
                                    payloadJson = decodeVaultText(cursor.getBlob(3), epochKey),
                                    deleted = cursor.getInt(4) != 0
                                )
                            )
                        }
                    }
                    normalizeStoredEntries(entries)
                }
            }
        }

    override suspend fun readStoredAttachments(databaseId: Long): List<MdbxStoredAttachment> =
        withContext(Dispatchers.IO) {
            val dbInfo = databaseDao.getDatabaseById(databaseId)
                ?: throw IllegalStateException("MDBX vault not found: $databaseId")
            val file = resolveWritableFile(dbInfo)
                ?: throw IllegalStateException("MDBX vault has no readable local copy: ${dbInfo.name}")
            if (!file.exists()) {
                throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
            }
            openReadOnly(file).use { db ->
                val epochKey = readEpochKeyOrNull(db, dbInfo)
                val missingTables = missingRequiredTables(db)
                if (missingTables.isNotEmpty()) {
                    throw IllegalStateException(
                        "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
                    )
                }
                db.rawQuery(
                    """
                    SELECT attachment_id, project_id, entry_id, file_name_ct, media_type_ct,
                           content_hash, original_size, stored_size, wrapped_cek_ct,
                           attachment_created_at, attachment_updated_at, deleted
                    FROM attachments
                    ORDER BY created_at ASC, attachment_id ASC
                    """.trimIndent(),
                    emptyArray()
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val attachmentId = cursor.getString(0)
                            val blob = readAttachmentBlob(db, attachmentId) ?: continue
                            add(
                                MdbxStoredAttachment(
                                    attachmentId = attachmentId,
                                    projectId = cursor.getString(1),
                                    entryId = if (cursor.isNull(2)) null else cursor.getString(2),
                                    fileName = decodeVaultText(cursor.getBlob(3), epochKey),
                                    mimeType = if (cursor.isNull(4)) {
                                        "application/octet-stream"
                                    } else {
                                        decodeVaultText(cursor.getBlob(4), epochKey)
                                    },
                                    contentHash = cursor.getString(5),
                                    originalSize = cursor.getLong(6),
                                    storedSize = cursor.getLong(7),
                                    wrappedCek = if (cursor.isNull(8)) null else decodeVaultText(cursor.getBlob(8), epochKey),
                                    createdAtMillis = cursor.getLong(9).takeIf { it > 0 }
                                        ?: System.currentTimeMillis(),
                                    updatedAtMillis = cursor.getLong(10).takeIf { it > 0 }
                                        ?: System.currentTimeMillis(),
                                    deleted = cursor.getInt(11) != 0,
                                    blob = blob
                                )
                            )
                        }
                    }
                }
            }
        }

    private fun normalizeStoredEntries(
        entries: List<MdbxStoredVaultEntry>
    ): List<MdbxStoredVaultEntry> {
        if (entries.isEmpty()) return entries
        val passkeyByCredential = linkedMapOf<String, MdbxStoredVaultEntry>()
        val normalized = mutableListOf<MdbxStoredVaultEntry>()
        entries.forEach { entry ->
            if (entry.entryType != "passkey") {
                normalized += entry
                return@forEach
            }
            val credentialId = runCatching {
                JSONObject(entry.payloadJson).optString("credential_id")
            }.getOrDefault("")
            if (credentialId.isBlank()) {
                normalized += entry
                return@forEach
            }
            val canonical = entry.copy(entryId = "passkey:$credentialId")
            passkeyByCredential.putIfAbsent(credentialId, canonical)
        }
        normalized += passkeyByCredential.values
        return normalized
    }

    private suspend fun flushWorkingCopyToSourceIfNeeded(
        database: LocalMdbxDatabase,
        workingCopy: File
    ) {
        when (database.sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> {
                databaseDao.updateSyncStatus(database.id, MdbxSyncStatus.LOCAL_ONLY.name, null)
            }
            MdbxSourceType.LOCAL_EXTERNAL -> {
                runCatching {
                    checkpointWorkingCopyForFlush(database, workingCopy)
                    val targetUri = Uri.parse(database.filePath)
                    context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                        workingCopy.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Cannot open external MDBX file for writing")
                    databaseDao.updateSyncStatus(database.id, MdbxSyncStatus.IN_SYNC.name, null)
                }.onFailure { error ->
                    databaseDao.updateSyncStatus(
                        database.id,
                        MdbxSyncStatus.FAILED.name,
                        error.message
                    )
                    throw IllegalStateException(
                        "Failed to write MDBX vault back to selected local file: ${error.message}",
                        error
                    )
                }
            }
            MdbxSourceType.REMOTE_WEBDAV -> {
                runCatching {
                    checkpointWorkingCopyForFlush(database, workingCopy)
                    val sourceId = database.sourceId
                        ?: throw IllegalStateException("MDBX WebDAV source is not linked")
                    val sourceDao = remoteSourceDao
                        ?: throw IllegalStateException("MDBX WebDAV source DAO is unavailable")
                    val source = sourceDao.getSourceById(sourceId)
                        ?: throw IllegalStateException("MDBX WebDAV source not found: $sourceId")
                    val baseUrl = source.baseUrl
                        ?: throw IllegalStateException("MDBX WebDAV base URL is missing")
                    val username = source.usernameEncrypted?.let(securityManager::decryptData).orEmpty()
                    val password = source.passwordEncrypted?.let(securityManager::decryptData).orEmpty()
                    WebDavMdbxFileSource(baseUrl, username, password)
                        .overwriteFile(source.remotePath, workingCopy.readBytes())
                    databaseDao.updateSyncStatus(database.id, MdbxSyncStatus.IN_SYNC.name, null)
                }.onFailure { error ->
                    databaseDao.updateSyncStatus(
                        database.id,
                        MdbxSyncStatus.FAILED.name,
                        error.message
                    )
                    throw IllegalStateException(
                        "Failed to upload MDBX vault to WebDAV: ${error.message}",
                        error
                    )
                }
            }
            MdbxSourceType.REMOTE_ONEDRIVE -> {
                runCatching {
                    checkpointWorkingCopyForFlush(database, workingCopy)
                    val sourceId = database.sourceId
                        ?: throw IllegalStateException("MDBX OneDrive source is not linked")
                    val sourceDao = remoteSourceDao
                        ?: throw IllegalStateException("MDBX OneDrive source DAO is unavailable")
                    val source = sourceDao.getSourceById(sourceId)
                        ?: throw IllegalStateException("MDBX OneDrive source not found: $sourceId")
                    val accountId = source.usernameEncrypted?.let(securityManager::decryptData)
                        ?: throw IllegalStateException("MDBX OneDrive account ID is missing")
                    OneDriveMdbxFileSource(context, accountId)
                        .writeFile(
                            parentPath = source.remoteParentPath,
                            name = source.remotePath.substringAfterLast('/'),
                            bytes = workingCopy.readBytes()
                        )
                    databaseDao.updateSyncStatus(database.id, MdbxSyncStatus.IN_SYNC.name, null)
                }.onFailure { error ->
                    databaseDao.updateSyncStatus(
                        database.id,
                        MdbxSyncStatus.FAILED.name,
                        error.message
                    )
                    throw IllegalStateException(
                        "Failed to upload MDBX vault to OneDrive: ${error.message}",
                        error
                    )
                }
            }
        }
    }

    private suspend fun markWorkingCopyDirty(database: LocalMdbxDatabase) {
        val status = when (database.sourceTypeEnum) {
            MdbxSourceType.LOCAL_INTERNAL -> MdbxSyncStatus.LOCAL_ONLY
            MdbxSourceType.LOCAL_EXTERNAL,
            MdbxSourceType.REMOTE_WEBDAV,
            MdbxSourceType.REMOTE_ONEDRIVE -> MdbxSyncStatus.PENDING_UPLOAD
        }
        databaseDao.updateSyncStatus(database.id, status.name, null)
    }

    private suspend fun markWorkingCopyDirtyAndFlush(
        database: LocalMdbxDatabase,
        workingCopy: File
    ) {
        withVaultWriteLock(workingCopy) {
            markWorkingCopyDirtyAndFlushLocked(database, workingCopy)
        }
    }

    private suspend fun markWorkingCopyDirtyAndFlushLocked(
        database: LocalMdbxDatabase,
        workingCopy: File
    ) {
        markWorkingCopyDirty(database)
        flushWorkingCopyToSourceIfNeeded(database, workingCopy)
    }

    private fun checkpointWorkingCopyForFlush(
        database: LocalMdbxDatabase,
        workingCopy: File
    ) {
        if (database.sourceTypeEnum == MdbxSourceType.LOCAL_INTERNAL) return
        if (!workingCopy.exists()) return
        openExistingWritableVault(workingCopy).use { db ->
            checkpointForFlush(db, database)
        }
    }

    private fun open(file: File): SQLiteDatabase {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            check(parent.mkdirs() || parent.exists()) {
                "Unable to create MDBX directory: ${parent.absolutePath}"
            }
        }
        val flags = SQLiteDatabase.OPEN_READWRITE or
            SQLiteDatabase.CREATE_IF_NECESSARY or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        return SQLiteDatabase.openDatabase(file.absolutePath, null, flags).apply {
            applyConnectionPragma("PRAGMA synchronous=NORMAL")
            applyConnectionPragma("PRAGMA busy_timeout=5000")
            applyConnectionPragma("PRAGMA secure_delete=ON")
            setForeignKeyConstraintsEnabled(true)
        }
    }

    private fun SQLiteDatabase.applyConnectionPragma(sql: String) {
        rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            }
        }
    }

    private fun checkpoint(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            }
        }
    }

    private fun checkpointForFlush(db: SQLiteDatabase, database: LocalMdbxDatabase) {
        if (database.sourceTypeEnum == MdbxSourceType.LOCAL_INTERNAL) return
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(0)
            }
        }
    }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )

    private suspend fun <T> withVaultWriteLock(
        file: File,
        block: suspend () -> T
    ): T {
        val key = file.absoluteFile.path
        val lock = vaultWriteLocks.getOrPut(key) { Mutex() }
        return lock.withLock { block() }
    }

    private fun openExistingWritableVault(
        file: File,
        allowSchemaPreparation: Boolean = false
    ): SQLiteDatabase {
        if (!file.exists()) {
            throw IllegalStateException("MDBX local copy is missing: ${file.absolutePath}")
        }
        openReadOnly(file).use { db ->
            val missingTables = if (allowSchemaPreparation) {
                missingMinimumReadableTables(db)
            } else {
                missingRequiredTables(db)
            }
            if (missingTables.isNotEmpty()) {
                throw IllegalStateException(
                    "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
                )
            }
            requireSupportedVaultFormat(db)
        }
        return open(file)
    }

    private fun resolveWritableFile(database: LocalMdbxDatabase): File? {
        val path = database.workingCopyPath?.takeIf { it.isNotBlank() }
            ?: database.filePath.takeIf { it.isNotBlank() }
        return path?.let { rawPath ->
            File(rawPath).let { file ->
                if (file.isAbsolute) file else File(context.filesDir, rawPath)
            }
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS vault_meta (vault_id TEXT PRIMARY KEY NOT NULL, format_name TEXT NOT NULL DEFAULT 'Monica Database eXtended', format_version TEXT NOT NULL DEFAULT '$MDBX_SCHEMA_FORMAT_VERSION', release_label TEXT NOT NULL DEFAULT 'MDBX-test-compatible', capability_flags TEXT NOT NULL DEFAULT 'legacy-test-compatible', created_at TEXT NOT NULL, updated_at TEXT NOT NULL, default_tiga_mode TEXT NOT NULL DEFAULT 'multi', unlock_methods TEXT NOT NULL DEFAULT 'password', active_key_epoch_id TEXT NOT NULL, compat_flags TEXT NOT NULL DEFAULT '', critical_extensions TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE IF NOT EXISTS devices (device_id TEXT PRIMARY KEY NOT NULL, device_name TEXT NOT NULL, client_label TEXT NOT NULL, created_at TEXT NOT NULL, last_seen_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS folders (folder_id TEXT PRIMARY KEY NOT NULL, parent_folder_id TEXT, name_ct BLOB NOT NULL, path_key TEXT NOT NULL, object_clock TEXT NOT NULL, head_commit_id TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL, updated_by_device_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS projects (project_id TEXT PRIMARY KEY NOT NULL, title_ct BLOB NOT NULL, summary_ct BLOB, group_id TEXT, icon_ref TEXT, favorite INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, tiga_mode_override TEXT, object_clock TEXT NOT NULL, head_commit_id TEXT NOT NULL, attachment_count INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL, updated_by_device_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS entries (entry_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, entry_type TEXT NOT NULL, title_ct BLOB, payload_ct BLOB NOT NULL, payload_schema_version INTEGER NOT NULL DEFAULT 1, tiga_mode_override TEXT, object_clock TEXT NOT NULL, head_commit_id TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL, updated_by_device_id TEXT NOT NULL, FOREIGN KEY (project_id) REFERENCES projects(project_id))",
            "CREATE TABLE IF NOT EXISTS attachments (attachment_id TEXT PRIMARY KEY NOT NULL, project_id TEXT NOT NULL, entry_id TEXT, file_name_ct BLOB NOT NULL, media_type_ct BLOB, storage_mode TEXT NOT NULL, content_hash TEXT NOT NULL, original_size INTEGER NOT NULL, stored_size INTEGER NOT NULL, chunk_count INTEGER NOT NULL DEFAULT 0, head_commit_id TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL, updated_by_device_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS attachment_chunks (attachment_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, chunk_hash TEXT NOT NULL, chunk_ct BLOB, external_uri_ct BLOB, stored_size INTEGER NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY (attachment_id, chunk_index), CHECK (chunk_ct IS NOT NULL OR external_uri_ct IS NOT NULL))",
            "CREATE TABLE IF NOT EXISTS object_index (object_type TEXT NOT NULL, object_id TEXT NOT NULL, parent_id TEXT, title_key TEXT NOT NULL, entry_type TEXT, head_commit_id TEXT NOT NULL, updated_at TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (object_type, object_id))",
            "CREATE TABLE IF NOT EXISTS project_tags (project_id TEXT NOT NULL, tag TEXT NOT NULL COLLATE NOCASE, PRIMARY KEY (project_id, tag), FOREIGN KEY (project_id) REFERENCES projects(project_id))",
            "CREATE TABLE IF NOT EXISTS commits (commit_id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, local_seq INTEGER NOT NULL, commit_kind TEXT NOT NULL, change_scope TEXT NOT NULL, changed_object_ids_ct BLOB NOT NULL, vector_clock TEXT NOT NULL, message_ct BLOB, created_at TEXT NOT NULL, integrity_tag BLOB NOT NULL)",
            "CREATE TABLE IF NOT EXISTS commit_parents (commit_id TEXT NOT NULL, parent_commit_id TEXT NOT NULL, PRIMARY KEY (commit_id, parent_commit_id))",
            "CREATE TABLE IF NOT EXISTS object_versions (version_seq INTEGER PRIMARY KEY AUTOINCREMENT, object_type TEXT NOT NULL, object_id TEXT NOT NULL, commit_id TEXT NOT NULL, project_id TEXT, entry_type TEXT, title_ct BLOB, payload_ct BLOB, deleted INTEGER NOT NULL, created_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS oplog (op_id TEXT PRIMARY KEY NOT NULL, commit_id TEXT NOT NULL, device_id TEXT NOT NULL, local_seq INTEGER NOT NULL, operation TEXT NOT NULL, object_type TEXT NOT NULL, object_id TEXT NOT NULL, payload_ct BLOB NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sync_bundles (bundle_id TEXT PRIMARY KEY NOT NULL, base_commit_id TEXT, head_commit_id TEXT NOT NULL, commit_count INTEGER NOT NULL, payload_json TEXT NOT NULL, payload_hash TEXT NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS crypto_contexts (context_id TEXT PRIMARY KEY NOT NULL, object_type TEXT NOT NULL, object_id TEXT NOT NULL, field_name TEXT NOT NULL, key_purpose TEXT NOT NULL, aad TEXT NOT NULL, algorithm TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS mdbx_benchmarks (run_id TEXT PRIMARY KEY NOT NULL, scenario TEXT NOT NULL, operation_count INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, file_delta_bytes INTEGER NOT NULL, created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS device_heads (device_id TEXT PRIMARY KEY NOT NULL, head_commit_id TEXT NOT NULL, last_seen_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS branches (branch_id TEXT PRIMARY KEY NOT NULL, branch_name TEXT NOT NULL, head_commit_id TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS tombstones (tombstone_id TEXT PRIMARY KEY NOT NULL, target_object_type TEXT NOT NULL, target_object_id TEXT NOT NULL, delete_clock TEXT NOT NULL, deleted_by_device_id TEXT NOT NULL, deleted_at TEXT NOT NULL, purge_eligible_at TEXT)",
            "CREATE TABLE IF NOT EXISTS snapshots (snapshot_id TEXT PRIMARY KEY NOT NULL, base_commit_id TEXT NOT NULL, snapshot_ct BLOB NOT NULL, snapshot_hash TEXT NOT NULL, created_at TEXT NOT NULL, created_by_device_id TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS key_epochs (key_epoch_id TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL, wrapped_epoch_key_ct BLOB NOT NULL, kdf_profile_id TEXT NOT NULL, created_at TEXT NOT NULL, activated_at TEXT, retired_at TEXT)",
            "CREATE TABLE IF NOT EXISTS conflicts (conflict_id TEXT PRIMARY KEY NOT NULL, object_type TEXT NOT NULL, object_id TEXT NOT NULL, base_commit_id TEXT NOT NULL, local_commit_id TEXT NOT NULL, incoming_commit_id TEXT NOT NULL, conflicting_fields TEXT NOT NULL, resolution TEXT NOT NULL DEFAULT 'unresolved', created_at TEXT NOT NULL, resolved_at TEXT)"
        ).forEach(db::execSQL)
        ensureColumn(db, "vault_meta", "format_name", "TEXT NOT NULL DEFAULT 'Monica Database eXtended'")
        ensureColumn(db, "vault_meta", "release_label", "TEXT NOT NULL DEFAULT 'MDBX-test-compatible'")
        ensureColumn(db, "vault_meta", "capability_flags", "TEXT NOT NULL DEFAULT 'legacy-test-compatible'")
        ensureColumn(db, "vault_meta", "unlock_methods", "TEXT NOT NULL DEFAULT 'password'")
        ensureColumn(db, "vault_meta", "credential_salt", "BLOB")
        ensureColumn(db, "vault_meta", "credential_verifier", "BLOB")
        ensureColumn(db, "vault_meta", "credential_kdf_profile", "TEXT")
        ensureColumn(db, "vault_meta", "key_file_name", "TEXT")
        ensureColumn(db, "vault_meta", "key_file_fingerprint", "TEXT")
        ensureColumn(db, "attachments", "wrapped_cek_ct", "BLOB")
        ensureColumn(db, "attachments", "attachment_created_at", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "attachments", "attachment_updated_at", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "conflicts", "local_title_ct", "BLOB")
        ensureColumn(db, "conflicts", "local_payload_ct", "BLOB")
        ensureColumn(db, "conflicts", "incoming_title_ct", "BLOB")
        ensureColumn(db, "conflicts", "incoming_payload_ct", "BLOB")
        ensureColumn(db, "snapshots", "name", "TEXT NOT NULL DEFAULT ''")
        ensureColumn(db, "snapshots", "snapshot_type", "TEXT NOT NULL DEFAULT 'manual'")
        ensureColumn(db, "snapshots", "is_full", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "snapshots", "auto_prune", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "snapshots", "payload_bytes", "INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uniq_commits_device_seq ON commits(device_id, local_seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_parent ON folders(parent_folder_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_path_key ON folders(path_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_projects_group_id ON projects(group_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_project_id ON entries(project_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_type ON entries(entry_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_deleted ON entries(deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_index_title ON object_index(title_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_index_parent ON object_index(parent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_index_deleted ON object_index(deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_project_tags_tag ON project_tags(tag)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_entry_id ON attachments(entry_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_deleted ON attachments(deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commits_created_at ON commits(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_commits_device_id ON commits(device_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uniq_object_versions_commit_object ON object_versions(object_type, object_id, commit_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_versions_commit ON object_versions(commit_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_object_versions_object ON object_versions(object_type, object_id, created_at, version_seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_oplog_commit ON oplog(commit_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_oplog_object ON oplog(object_type, object_id, created_at)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uniq_crypto_contexts_field ON crypto_contexts(object_type, object_id, field_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_bundles_created_at ON sync_bundles(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mdbx_benchmarks_created_at ON mdbx_benchmarks(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_type_created_at ON snapshots(snapshot_type, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_auto_prune ON snapshots(auto_prune, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tombstones_target ON tombstones(target_object_type, target_object_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conflicts_resolution ON conflicts(resolution)")
    }

    private fun initializeVaultMeta(
        db: SQLiteDatabase,
        displayName: String,
        tigaMode: String,
        unlockMethod: MdbxUnlockMethod,
        credential: MdbxVaultCredential
    ) {
        val existing = db.rawQuery("SELECT COUNT(*) FROM vault_meta", emptyArray()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (existing > 0) return
        db.beginTransaction()
        try {
            val now = now()
            val parsedMode = MdbxTigaMode.fromName(tigaMode)
            val vaultId = UUID.randomUUID().toString()
            val commitId = UUID.randomUUID().toString()
            val epochId = UUID.randomUUID().toString()
            val keyMaterial = MdbxVaultCrypto.buildKeyMaterial(
                vaultId = vaultId,
                credential = credential,
                tigaMode = parsedMode
            )
            ensureDeviceRegistration(db, now)
            db.execSQL(
                "INSERT INTO commits (commit_id, device_id, local_seq, commit_kind, change_scope, changed_object_ids_ct, vector_clock, created_at, integrity_tag) VALUES (?, ?, 1, 'init', 'vault', ?, ?, ?, ?)",
                arrayOf(commitId, deviceId, encrypt(displayName, keyMaterial.epochKey), "{\"$deviceId\":1}", now, UUID.randomUUID().toString().toByteArray())
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO folders (
                    folder_id, parent_folder_id, name_ct, path_key, object_clock, head_commit_id,
                    deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
                ) VALUES ('root', NULL, ?, '/', '1', ?, 0, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(encrypt("Root", keyMaterial.epochKey), commitId, now, now, deviceId, deviceId)
            )
            db.execSQL(
                """
                INSERT INTO vault_meta (
                    vault_id, format_name, format_version, release_label, capability_flags,
                    created_at, updated_at,
                    default_tiga_mode, unlock_methods, active_key_epoch_id,
                    credential_salt, credential_verifier, credential_kdf_profile,
                    key_file_name, key_file_fingerprint
                ) VALUES (?, 'Monica Database eXtended', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    vaultId,
                    MDBX_SCHEMA_FORMAT_VERSION,
                    MDBX_OFFICIAL_RELEASE_LABEL,
                    MDBX_ANDROID_CAPABILITY_FLAGS,
                    now,
                    now,
                    parsedMode.name.lowercase(),
                    unlockMethod.storedValue,
                    epochId,
                    keyMaterial.salt,
                    keyMaterial.verifier,
                    keyMaterial.kdfProfile,
                    credential.keyFileName,
                    credential.keyFileFingerprint
                )
            )
            db.execSQL(
                "INSERT INTO key_epochs (key_epoch_id, status, wrapped_epoch_key_ct, kdf_profile_id, created_at, activated_at) VALUES (?, 'active', ?, ?, ?, ?)",
                arrayOf(epochId, keyMaterial.wrappedEpochKey, keyMaterial.kdfProfile, now, now)
            )
            db.execSQL(
                "INSERT INTO device_heads (device_id, head_commit_id, last_seen_at) VALUES (?, ?, ?)",
                arrayOf(deviceId, commitId, now)
            )
            db.execSQL(
                "INSERT INTO branches (branch_id, branch_name, head_commit_id, created_at, updated_at) VALUES (?, 'main', ?, ?, ?)",
                arrayOf("main", commitId, now, now)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun installOfficialCredentialMaterial(
        db: SQLiteDatabase,
        credential: MdbxVaultCredential,
        tigaMode: MdbxTigaMode,
        now: String
    ) {
        requireCredentialShape(credential)
        val vaultId = queryString(db, "SELECT vault_id FROM vault_meta LIMIT 1")
            ?: throw IllegalStateException("MDBX vault id is missing")
        val epochId = queryString(db, "SELECT active_key_epoch_id FROM vault_meta LIMIT 1")
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val keyMaterial = MdbxVaultCrypto.buildKeyMaterial(
            vaultId = vaultId,
            credential = credential,
            tigaMode = tigaMode
        )
        db.execSQL(
            """
            UPDATE vault_meta
            SET active_key_epoch_id = ?,
                unlock_methods = ?,
                default_tiga_mode = ?,
                credential_salt = ?,
                credential_verifier = ?,
                credential_kdf_profile = ?,
                key_file_name = ?,
                key_file_fingerprint = ?,
                updated_at = ?
            WHERE vault_id = ?
            """.trimIndent(),
            arrayOf(
                epochId,
                credential.unlockMethod.storedValue,
                tigaMode.name.lowercase(),
                keyMaterial.salt,
                keyMaterial.verifier,
                keyMaterial.kdfProfile,
                credential.keyFileName,
                credential.keyFileFingerprint,
                now,
                vaultId
            )
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO key_epochs (
                key_epoch_id, status, wrapped_epoch_key_ct, kdf_profile_id,
                created_at, activated_at
            ) VALUES (?, 'active', ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(epochId, keyMaterial.wrappedEpochKey, keyMaterial.kdfProfile, now, now)
        )
    }

    private fun requireCredentialShape(credential: MdbxVaultCredential) {
        if (credential.requiresPassword() && credential.password.isNullOrEmpty()) {
            throw IllegalArgumentException("MDBX master password is required")
        }
        if (credential.requiresKeyFile() && credential.keyFileBytes == null) {
            throw IllegalArgumentException("MDBX key file is required")
        }
    }

    private fun appendCommit(
        db: SQLiteDatabase,
        scope: String,
        objectId: String,
        epochKey: ByteArray? = null,
        commitKind: String = "local",
        changedObjectIds: List<String> = listOf(objectId)
    ): String {
        val now = now()
        ensureDeviceRegistration(db, now)
        val seq = db.rawQuery(
            "SELECT COALESCE(MAX(local_seq), 0) + 1 FROM commits WHERE device_id = ?",
            arrayOf(deviceId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 1L }
        val parent = db.rawQuery(
            "SELECT head_commit_id FROM device_heads WHERE device_id = ?",
            arrayOf(deviceId)
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val commitId = UUID.randomUUID().toString()
        val changedObjectIdsJson = JSONArray().apply {
            changedObjectIds.distinct().forEach { put(it) }
        }.toString()
        db.execSQL(
            "INSERT INTO commits (commit_id, device_id, local_seq, commit_kind, change_scope, changed_object_ids_ct, vector_clock, created_at, integrity_tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(commitId, deviceId, seq, commitKind, scope, encrypt(changedObjectIdsJson, epochKey), "{\"$deviceId\":$seq}", now, UUID.randomUUID().toString().toByteArray())
        )
        if (!parent.isNullOrBlank()) {
            db.execSQL(
                "INSERT OR IGNORE INTO commit_parents (commit_id, parent_commit_id) VALUES (?, ?)",
                arrayOf(commitId, parent)
            )
        }
        db.execSQL(
            "INSERT OR REPLACE INTO device_heads (device_id, head_commit_id, last_seen_at, revoked) VALUES (?, ?, ?, 0)",
            arrayOf(deviceId, commitId, now)
        )
        db.execSQL(
            "UPDATE branches SET head_commit_id = ?, updated_at = ? WHERE branch_id = 'main'",
            arrayOf(commitId, now)
        )
        insertOplog(db, commitId, seq, commitKind, scope, objectId, epochKey, now)
        if (epochKey != null && commitKind != "auto-snapshot") {
            createSnapshotLocked(
                db = db,
                name = "Auto ${commitId.take(8)}",
                fullSnapshot = false,
                autoPrune = true,
                epochKey = epochKey,
                baseCommitId = commitId,
                createdAt = now
            )
            pruneAutomaticSnapshotsLocked(db, keepCount = 20)
        }
        return commitId
    }

    private fun insertOplog(
        db: SQLiteDatabase,
        commitId: String,
        localSeq: Long,
        operation: String,
        objectType: String,
        objectId: String,
        epochKey: ByteArray?,
        createdAt: String
    ) {
        val payload = JSONObject()
            .put("commit_id", commitId)
            .put("operation", operation)
            .put("object_type", objectType)
            .put("object_id", objectId)
            .put("device_id", deviceId)
            .put("local_seq", localSeq)
            .toString()
        db.execSQL(
            """
            INSERT OR REPLACE INTO oplog (
                op_id, commit_id, device_id, local_seq, operation,
                object_type, object_id, payload_ct, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                "op:$commitId",
                commitId,
                deviceId,
                localSeq,
                operation,
                objectType,
                objectId,
                encrypt(payload, epochKey),
                createdAt
            )
        )
    }

    private fun ensureDeviceRegistration(db: SQLiteDatabase, timestamp: String = now()) {
        val label = deviceLabel()
        db.execSQL(
            """
            INSERT OR REPLACE INTO devices (
                device_id, device_name, client_label, created_at, last_seen_at, revoked
            ) VALUES (?, ?, ?, COALESCE(
                (SELECT created_at FROM devices WHERE device_id = ?),
                ?
            ), ?, 0)
            """.trimIndent(),
            arrayOf(deviceId, label, label, deviceId, timestamp, timestamp)
        )
    }

    private fun ensureRootFolder(db: SQLiteDatabase, epochKey: ByteArray? = null) {
        val now = now()
        val headCommitId = queryString(
            db,
            "SELECT head_commit_id FROM device_heads WHERE device_id = ? LIMIT 1",
            arrayOf(deviceId)
        ) ?: queryString(
            db,
            "SELECT commit_id FROM commits ORDER BY created_at DESC LIMIT 1"
        ) ?: "root"
        db.execSQL(
            """
            INSERT OR IGNORE INTO folders (
                folder_id, parent_folder_id, name_ct, path_key, object_clock, head_commit_id,
                deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES ('root', NULL, ?, '/', '1', ?, 0, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(encrypt("Root", epochKey), headCommitId, now, now, deviceId, deviceId)
        )
        db.execSQL(
            """
            UPDATE folders
            SET parent_folder_id = NULL,
                path_key = '/',
                deleted = 0,
                updated_at = ?,
                updated_by_device_id = ?
            WHERE folder_id = 'root'
            """.trimIndent(),
            arrayOf(now, deviceId)
        )
    }

    private fun ensureFolder(db: SQLiteDatabase, folderId: String, epochKey: ByteArray? = null) {
        if (folderId == "root") {
            ensureRootFolder(db, epochKey)
            return
        }
        ensureRootFolder(db, epochKey)
        val now = now()
        val name = when {
            folderId.startsWith("category:") -> "Category ${folderId.substringAfter(':')}"
            else -> folderId.substringAfterLast(':').ifBlank { folderId }
        }
        db.execSQL(
            """
            INSERT OR IGNORE INTO folders (
                folder_id, parent_folder_id, name_ct, path_key, object_clock, head_commit_id,
                deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, 'root', ?, ?, '1', COALESCE(
                (SELECT head_commit_id FROM device_heads WHERE device_id = ?),
                (SELECT commit_id FROM commits ORDER BY created_at DESC LIMIT 1)
            ), 0, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(folderId, encrypt(name, epochKey), "/$folderId", deviceId, now, now, deviceId, deviceId)
        )
    }

    private fun upsertObjectIndex(
        db: SQLiteDatabase,
        objectType: String,
        objectId: String,
        parentFolderId: String,
        title: String,
        entryType: String,
        commitId: String,
        deleted: Boolean
    ) {
        db.execSQL(
            """
            INSERT OR REPLACE INTO object_index (
                object_type, object_id, parent_id, title_key, entry_type,
                head_commit_id, updated_at, deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                objectType,
                objectId,
                parentFolderId,
                title.lowercase(Locale.ROOT),
                entryType,
                commitId,
                now(),
                if (deleted) 1 else 0
            )
        )
    }

    private fun normalizeProjectTags(tags: List<String>): List<String> =
        tags.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("\\s+"), " ") }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .take(64)
            .toList()

    private fun requireActiveProject(db: SQLiteDatabase, projectId: String) {
        val exists = queryLong(
            db,
            "SELECT COUNT(*) FROM projects WHERE project_id = ? AND deleted = 0",
            arrayOf(projectId)
        ) > 0L
        if (!exists) {
            throw IllegalArgumentException("MDBX project is missing or deleted: $projectId")
        }
    }

    private fun replaceProjectTags(
        db: SQLiteDatabase,
        projectId: String,
        tags: List<String>
    ) {
        db.execSQL("DELETE FROM project_tags WHERE project_id = ?", arrayOf(projectId))
        normalizeProjectTags(tags).forEach { tag ->
            db.execSQL(
                "INSERT OR IGNORE INTO project_tags (project_id, tag) VALUES (?, ?)",
                arrayOf(projectId, tag)
            )
        }
    }

    private fun readProjectTags(db: SQLiteDatabase, projectId: String): List<String> {
        if (!tableExists(db, "project_tags")) return emptyList()
        return db.rawQuery(
            """
            SELECT tag
            FROM project_tags
            WHERE project_id = ?
            ORDER BY tag COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(projectId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun readProjectEntryTypes(db: SQLiteDatabase, projectId: String): List<String> =
        db.rawQuery(
            """
            SELECT DISTINCT entry_type
            FROM entries
            WHERE project_id = ? AND deleted = 0
            ORDER BY entry_type ASC
            """.trimIndent(),
            arrayOf(projectId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private data class ConflictResolutionTarget(
        val objectType: String,
        val objectId: String,
        val localCommitId: String,
        val incomingCommitId: String,
        val localTitleCt: ByteArray?,
        val localPayloadCt: ByteArray?,
        val incomingTitleCt: ByteArray?,
        val incomingPayloadCt: ByteArray?
    )

    private data class ObjectVersionState(
        val versionSeq: Long,
        val objectType: String,
        val objectId: String,
        val commitId: String,
        val projectId: String?,
        val entryType: String?,
        val titleCt: ByteArray?,
        val payloadCt: ByteArray?,
        val deleted: Boolean,
        val createdAt: String,
        val createdByDeviceId: String
    )

    private data class SnapshotPayload(
        val baseCommitId: String,
        val isFull: Boolean,
        val entries: List<ObjectVersionState>
    )

    private data class CommitChangePreview(
        val objectPreview: String,
        val fieldSummary: String
    )

    private data class StructureFolderInfo(
        val id: String,
        val parentId: String?,
        val name: String,
        val path: String
    )

    private data class StructureEntryInfo(
        val id: String,
        val parentFolderId: String?,
        val title: String,
        val entryType: String,
        val deleted: Boolean,
        val contentHash: String,
        val updatedAt: String
    )

    private enum class StructurePreviewSide {
        CURRENT,
        SNAPSHOT
    }

    private data class BundleEntryVersion(
        val objectId: String,
        val commitId: String,
        val projectId: String?,
        val entryType: String?,
        val titleCt: ByteArray?,
        val payloadCt: ByteArray?,
        val deleted: Boolean,
        val createdAt: String
    )

    private fun recordEntryVersion(
        db: SQLiteDatabase,
        commitId: String,
        projectId: String,
        entryId: String,
        entryType: String,
        titleCt: ByteArray?,
        payloadCt: ByteArray?,
        deleted: Boolean,
        createdAt: String
    ) {
        db.execSQL(
            """
            INSERT INTO object_versions (
                object_type, object_id, commit_id, project_id, entry_type,
                title_ct, payload_ct, deleted, created_at, created_by_device_id
            ) VALUES ('entry', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                entryId,
                commitId,
                projectId,
                entryType,
                titleCt,
                payloadCt,
                if (deleted) 1 else 0,
                createdAt,
                deviceId
            )
        )
    }

    private fun recordCryptoContext(
        db: SQLiteDatabase,
        objectType: String,
        objectId: String,
        fieldName: String,
        keyPurpose: String,
        timestamp: String = now()
    ) {
        val contextId = "ctx:$objectType:$objectId:$fieldName"
        db.execSQL(
            """
            INSERT OR REPLACE INTO crypto_contexts (
                context_id, object_type, object_id, field_name, key_purpose,
                aad, algorithm, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'AES-256-GCM', COALESCE(
                (SELECT created_at FROM crypto_contexts WHERE context_id = ?),
                ?
            ), ?)
            """.trimIndent(),
            arrayOf(
                contextId,
                objectType,
                objectId,
                fieldName,
                keyPurpose,
                "mdbx:v1:$objectType:$objectId:$fieldName",
                contextId,
                timestamp,
                timestamp
            )
        )
    }

    private fun readEntryVersionState(
        db: SQLiteDatabase,
        entryId: String
    ): ObjectVersionState? =
        db.rawQuery(
            """
            SELECT entry_id, project_id, entry_type, title_ct, payload_ct,
                   head_commit_id, deleted, updated_at, updated_by_device_id
            FROM entries
            WHERE entry_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(entryId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ObjectVersionState(
                    versionSeq = 0L,
                    objectType = "entry",
                    objectId = cursor.getString(0),
                    projectId = cursor.getString(1),
                    entryType = cursor.getString(2),
                    titleCt = if (cursor.isNull(3)) null else cursor.getBlob(3),
                    payloadCt = if (cursor.isNull(4)) null else cursor.getBlob(4),
                    commitId = cursor.getString(5),
                    deleted = cursor.getInt(6) != 0,
                    createdAt = cursor.getString(7),
                    createdByDeviceId = cursor.getString(8)
                )
            }
        }

    private fun readObjectVersionsForCommit(
        db: SQLiteDatabase,
        commitId: String
    ): List<ObjectVersionState> =
        db.rawQuery(
            """
            SELECT version_seq, object_type, object_id, commit_id, project_id,
                   entry_type, title_ct, payload_ct, deleted, created_at,
                   created_by_device_id
            FROM object_versions
            WHERE commit_id = ?
            ORDER BY version_seq ASC
            """.trimIndent(),
            arrayOf(commitId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toObjectVersionState())
                }
            }
        }

    private fun readPreviousObjectVersion(
        db: SQLiteDatabase,
        objectType: String,
        objectId: String,
        beforeCreatedAt: String,
        beforeVersionSeq: Long
    ): ObjectVersionState? =
        db.rawQuery(
            """
            SELECT version_seq, object_type, object_id, commit_id, project_id,
                   entry_type, title_ct, payload_ct, deleted, created_at,
                   created_by_device_id
            FROM object_versions
            WHERE object_type = ? AND object_id = ?
              AND (created_at < ? OR (created_at = ? AND version_seq < ?))
            ORDER BY created_at DESC, version_seq DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(objectType, objectId, beforeCreatedAt, beforeCreatedAt, beforeVersionSeq.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toObjectVersionState() else null
        }

    private fun android.database.Cursor.toObjectVersionState(): ObjectVersionState =
        ObjectVersionState(
            versionSeq = getLong(0),
            objectType = getString(1),
            objectId = getString(2),
            commitId = getString(3),
            projectId = if (isNull(4)) null else getString(4),
            entryType = if (isNull(5)) null else getString(5),
            titleCt = if (isNull(6)) null else getBlob(6),
            payloadCt = if (isNull(7)) null else getBlob(7),
            deleted = getInt(8) != 0,
            createdAt = getString(9),
            createdByDeviceId = getString(10)
        )

    private fun ObjectVersionState.toDiff(
        db: SQLiteDatabase,
        previous: ObjectVersionState?,
        epochKey: ByteArray?
    ): MdbxCommitDiff {
        val previousTitle = previous?.titleCt?.let { decodeVaultText(it, epochKey) }
        val currentTitle = titleCt?.let { decodeVaultText(it, epochKey) }
        val previousPayload = previous?.payloadCt?.let { decodeVaultText(it, epochKey) }
        val currentPayload = payloadCt?.let { decodeVaultText(it, epochKey) }
        val displayInfo = readDiffDisplayInfo(
            db = db,
            state = this,
            previous = previous,
            currentTitle = currentTitle,
            previousTitle = previousTitle,
            currentPayload = currentPayload,
            previousPayload = previousPayload,
            epochKey = epochKey
        )
        val changedFields = buildList {
            if (previous == null) add("created")
            if (previousTitle != currentTitle) add("title")
            if (previousPayload != currentPayload) add("payload")
            if (previous?.deleted != deleted) add("deleted")
        }.ifEmpty { listOf("metadata") }
        return MdbxCommitDiff(
            commitId = commitId,
            objectType = objectType,
            objectId = objectId,
            displayTitle = displayInfo.title,
            storagePath = displayInfo.storagePath,
            previousTitle = previousTitle,
            currentTitle = currentTitle,
            previousPayloadPreview = summarizeConflictPayload(previousPayload),
            currentPayloadPreview = summarizeConflictPayload(currentPayload),
            previousDeleted = previous?.deleted,
            currentDeleted = deleted,
            changedFields = changedFields,
            createdAt = createdAt
        )
    }

    private fun readCommitChangePreview(
        db: SQLiteDatabase,
        commitId: String,
        epochKey: ByteArray?
    ): CommitChangePreview {
        if (!tableExists(db, "object_versions")) {
            return CommitChangePreview("没有对象变更", "没有字段变更")
        }
        val diffs = readObjectVersionsForCommit(db, commitId).map { version ->
            val previous = readPreviousObjectVersion(
                db = db,
                objectType = version.objectType,
                objectId = version.objectId,
                beforeCreatedAt = version.createdAt,
                beforeVersionSeq = version.versionSeq
            )
            version.toDiff(db, previous, epochKey)
        }
        val objectLabels = diffs
            .map { diff ->
                listOfNotNull(
                    diff.storagePath?.takeIf { it.isNotBlank() },
                    diff.displayTitle?.takeIf { it.isNotBlank() } ?: diff.objectId.take(8)
                ).joinToString("/")
            }
            .distinct()
        val fieldLabels = diffs
            .flatMap { it.changedFields }
            .map(::commitFieldLabel)
            .filter { it.isNotBlank() }
            .distinct()
        return CommitChangePreview(
            objectPreview = summarizeCommitObjects(objectLabels),
            fieldSummary = summarizeCommitFields(fieldLabels)
        )
    }

    private fun summarizeCommitObjects(labels: List<String>): String =
        when {
            labels.isEmpty() -> "没有对象变更"
            labels.size == 1 -> labels.first()
            else -> "${labels.first()} 等 ${labels.size} 个对象"
        }

    private fun summarizeCommitFields(labels: List<String>): String =
        when {
            labels.isEmpty() -> "没有字段变更"
            labels.size <= 3 -> labels.joinToString("、")
            else -> labels.take(3).joinToString("、") + " 等 ${labels.size} 项"
        }

    private fun commitFieldLabel(field: String): String =
        when (field.lowercase(Locale.ROOT)) {
            "created" -> "新建"
            "title" -> "标题"
            "payload" -> "内容摘要"
            "deleted" -> "删除状态"
            "metadata" -> "元数据"
            else -> field
        }

    private fun readStructureFolders(
        db: SQLiteDatabase,
        epochKey: ByteArray?
    ): Map<String, StructureFolderInfo> {
        if (!tableExists(db, "folders")) return emptyMap()
        val raw = linkedMapOf<String, Pair<String?, String>>()
        raw["root"] = null to "根目录"
        db.rawQuery(
            """
            SELECT folder_id, parent_folder_id, name_ct
            FROM folders
            WHERE deleted = 0
            ORDER BY path_key ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val folderId = cursor.getString(0)
                raw[folderId] = (if (cursor.isNull(1)) null else cursor.getString(1)) to
                    decodeVaultText(cursor.getBlob(2), epochKey)
            }
        }
        fun pathOf(folderId: String, seen: Set<String> = emptySet()): String {
            if (folderId == "root" || folderId in seen) return ""
            val row = raw[folderId] ?: return ""
            val parentPath = row.first?.let { pathOf(it, seen + folderId) }.orEmpty()
            return listOf(parentPath, row.second)
                .filter { it.isNotBlank() }
                .joinToString("/")
        }
        return raw.mapValues { (folderId, row) ->
            StructureFolderInfo(
                id = folderId,
                parentId = row.first?.takeIf { it.isNotBlank() },
                name = row.second,
                path = pathOf(folderId)
            )
        }
    }

    private fun readStructureProjectFolderIds(db: SQLiteDatabase): Map<String, String?> {
        if (!tableExists(db, "projects")) return emptyMap()
        return db.rawQuery(
            """
            SELECT project_id, group_id
            FROM projects
            WHERE deleted = 0
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1))
                }
            }
        }
    }

    private fun ObjectVersionState.toStructureEntry(
        folders: Map<String, StructureFolderInfo>,
        projectFolderIds: Map<String, String?>,
        epochKey: ByteArray?
    ): StructureEntryInfo? {
        if (objectType.lowercase(Locale.ROOT) != "entry") return null
        val title = titleCt?.let { decodeVaultText(it, epochKey) }
            ?.takeIf { it.isNotBlank() }
            ?: objectId.take(8)
        val payload = payloadCt?.let { decodeVaultText(it, epochKey) }.orEmpty()
        val folderId = (projectId?.let { projectFolderIds[it] } ?: folderIdFromPayload(payload))
            .takeIf { it.isNotBlank() }
            ?: "root"
        return StructureEntryInfo(
            id = objectId,
            parentFolderId = folderId,
            title = title,
            entryType = entryType ?: "entry",
            deleted = deleted,
            contentHash = sha256Hex("${title}\n$payload\n$deleted".toByteArray(Charsets.UTF_8)),
            updatedAt = createdAt
        )
    }

    private fun buildStructureNodes(
        folders: Map<String, StructureFolderInfo>,
        entries: List<StructureEntryInfo>,
        compareEntries: Map<String, StructureEntryInfo>,
        side: StructurePreviewSide
    ): List<MdbxStructureNode> {
        val visibleEntries = entries.filterNot { it.deleted }
        val visibleFolderIds = folders.keys
            .filter { it != "root" }
            .toMutableSet()
        visibleEntries.forEach { entry ->
            var folderId = entry.parentFolderId ?: "root"
            var guard = 0
            while (folderId.isNotBlank() && guard++ < 32) {
                if (!visibleFolderIds.add(folderId)) break
                folderId = folders[folderId]?.parentId.orEmpty()
            }
        }
        val folderChildCount = mutableMapOf<String, Int>()
        visibleEntries.forEach { entry ->
            folderChildCount[entry.parentFolderId ?: "root"] =
                (folderChildCount[entry.parentFolderId ?: "root"] ?: 0) + 1
        }
        visibleFolderIds.forEach { folderId ->
            val parentId = folders[folderId]?.parentId
            if (!parentId.isNullOrBlank()) {
                folderChildCount[parentId] = (folderChildCount[parentId] ?: 0) + 1
            }
        }
        val folderNodes = visibleFolderIds
            .filter { it != "root" }
            .mapNotNull { folderId ->
                val folder = folders[folderId] ?: return@mapNotNull null
                MdbxStructureNode(
                    id = "folder:$folderId",
                    parentId = folder.parentId?.takeIf { it != "root" }?.let { "folder:$it" },
                    name = folder.name,
                    type = MdbxStructureNodeType.FOLDER,
                    path = folder.path,
                    status = MdbxStructureNodeStatus.UNCHANGED,
                    childCount = folderChildCount[folderId] ?: 0,
                    metadata = "${folderChildCount[folderId] ?: 0} 项"
                )
            }
        val entryNodes = visibleEntries.map { entry ->
            val compare = compareEntries[entry.id]
            val status = when {
                compare == null || compare.deleted -> if (side == StructurePreviewSide.CURRENT) {
                    MdbxStructureNodeStatus.ADDED
                } else {
                    MdbxStructureNodeStatus.REMOVED
                }
                compare.contentHash != entry.contentHash ||
                    compare.parentFolderId != entry.parentFolderId ||
                    compare.title != entry.title -> MdbxStructureNodeStatus.MODIFIED
                else -> MdbxStructureNodeStatus.UNCHANGED
            }
            val folderPath = folders[entry.parentFolderId ?: "root"]?.path.orEmpty()
            MdbxStructureNode(
                id = "entry:${entry.id}",
                parentId = entry.parentFolderId
                    ?.takeIf { it != "root" && folders.containsKey(it) }
                    ?.let { "folder:$it" },
                name = entry.title,
                type = MdbxStructureNodeType.ENTRY,
                path = listOf(folderPath, entry.title).filter { it.isNotBlank() }.joinToString("/"),
                status = status,
                childCount = 0,
                metadata = entryTypeLabel(entry.entryType)
            )
        }
        return (folderNodes + entryNodes).sortedWith(
            compareBy<MdbxStructureNode> { it.parentId.orEmpty() }
                .thenBy { structureNodeTypeSortRank(it) }
                .thenBy { it.name.lowercase(Locale.ROOT) }
                .thenBy { it.path.lowercase(Locale.ROOT) }
                .thenBy { it.id }
        )
    }

    private fun structureNodeTypeSortRank(node: MdbxStructureNode): Int =
        if (node.type == MdbxStructureNodeType.FOLDER) 0 else 1

    private fun entryTypeLabel(type: String): String =
        when (type.lowercase(Locale.ROOT)) {
            "password" -> "密码"
            "totp" -> "验证器"
            "note" -> "安全笔记"
            "card" -> "银行卡"
            "document-ref" -> "文档"
            "billing-address" -> "账单地址"
            "payment-account" -> "支付方式"
            "passkey" -> "通行密钥"
            else -> type
        }

    private data class DiffDisplayInfo(
        val title: String?,
        val storagePath: String?
    )

    private fun readDiffDisplayInfo(
        db: SQLiteDatabase,
        state: ObjectVersionState,
        previous: ObjectVersionState?,
        currentTitle: String?,
        previousTitle: String?,
        currentPayload: String?,
        previousPayload: String?,
        epochKey: ByteArray?
    ): DiffDisplayInfo {
        val title = currentTitle
            ?: previousTitle
            ?: state.projectId?.let { readProjectTitle(db, it, epochKey) }
            ?: state.objectId.take(8)
        val folderId = when (state.objectType.lowercase(Locale.ROOT)) {
            "folder" -> readFolderParentId(db, state.objectId)
            "entry" -> {
                val projectId = state.projectId ?: previous?.projectId ?: state.objectId
                readProjectFolderId(db, projectId)
                    ?: currentPayload?.let(::folderIdFromPayload)
                    ?: previousPayload?.let(::folderIdFromPayload)
            }
            "project" -> readProjectFolderId(db, state.objectId)
            else -> null
        }
        return DiffDisplayInfo(
            title = title,
            storagePath = folderDisplayPath(db, folderId, epochKey)
        )
    }

    private fun readProjectTitle(db: SQLiteDatabase, projectId: String, epochKey: ByteArray?): String? =
        db.rawQuery(
            "SELECT title_ct FROM projects WHERE project_id = ? LIMIT 1",
            arrayOf(projectId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                decodeVaultText(cursor.getBlob(0), epochKey)
            } else {
                null
            }
        }

    private fun readProjectFolderId(db: SQLiteDatabase, projectId: String): String? =
        db.rawQuery(
            "SELECT group_id FROM projects WHERE project_id = ? LIMIT 1",
            arrayOf(projectId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun readFolderParentId(db: SQLiteDatabase, folderId: String): String? =
        db.rawQuery(
            "SELECT parent_folder_id FROM folders WHERE folder_id = ? LIMIT 1",
            arrayOf(folderId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun folderDisplayPath(db: SQLiteDatabase, folderId: String?, epochKey: ByteArray?): String? {
        var current = folderId?.takeIf { it.isNotBlank() && it != "root" } ?: return null
        val names = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        while (current.isNotBlank() && current != "root" && seen.add(current) && seen.size < 32) {
            val row = db.rawQuery(
                "SELECT parent_folder_id, name_ct FROM folders WHERE folder_id = ? LIMIT 1",
                arrayOf(current)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(1)) {
                    val parentId = if (cursor.isNull(0)) null else cursor.getString(0)
                    parentId to decodeVaultText(cursor.getBlob(1), epochKey)
                } else {
                    null
                }
            } ?: break
            names.addFirst(row.second)
            current = row.first.orEmpty()
        }
        return names.joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun createSnapshotLocked(
        db: SQLiteDatabase,
        name: String,
        fullSnapshot: Boolean,
        autoPrune: Boolean,
        epochKey: ByteArray,
        baseCommitId: String? = null,
        createdAt: String = now()
    ): MdbxSnapshotSummary {
        val headCommitId = baseCommitId
            ?: queryString(db, "SELECT head_commit_id FROM branches WHERE branch_id = 'main'")
            ?: queryString(db, "SELECT head_commit_id FROM device_heads WHERE device_id = ?", arrayOf(deviceId))
            ?: throw IllegalStateException("Cannot create MDBX snapshot without a commit head")
        val snapshotId = UUID.randomUUID().toString()
        val entries = if (fullSnapshot) readCurrentEntryVersionStates(db) else emptyList()
        val payloadJson = JSONObject()
            .put("format", "monica-mdbx-snapshot-v1")
            .put("snapshot_id", snapshotId)
            .put("base_commit_id", headCommitId)
            .put("snapshot_type", if (autoPrune) "auto" else "manual")
            .put("is_full", fullSnapshot)
            .put("created_at", createdAt)
            .put("entries", JSONArray().also { array ->
                entries.forEach { entry ->
                    array.put(entry.toSnapshotJson())
                }
            })
            .toString()
        val snapshotCt = encrypt(payloadJson, epochKey)
        val hash = sha256Hex(snapshotCt)
        val snapshotName = name.trim().ifBlank {
            if (autoPrune) "Auto ${headCommitId.take(8)}" else "Snapshot ${createdAt.take(19)}"
        }
        db.execSQL(
            """
            INSERT OR REPLACE INTO snapshots (
                snapshot_id, base_commit_id, name, snapshot_type, is_full,
                snapshot_ct, snapshot_hash, created_at, created_by_device_id,
                auto_prune, payload_bytes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                snapshotId,
                headCommitId,
                snapshotName,
                if (autoPrune) "auto" else "manual",
                if (fullSnapshot) 1 else 0,
                snapshotCt,
                hash,
                createdAt,
                deviceId,
                if (autoPrune) 1 else 0,
                snapshotCt.size.toLong()
            )
        )
        return MdbxSnapshotSummary(
            snapshotId = snapshotId,
            baseCommitId = headCommitId,
            name = snapshotName,
            snapshotType = if (autoPrune) "auto" else "manual",
            isFull = fullSnapshot,
            payloadBytes = snapshotCt.size.toLong(),
            createdAt = createdAt,
            createdByDeviceId = deviceId,
            autoPrune = autoPrune,
            integrityOk = true
        )
    }

    private fun readSnapshotPayload(
        db: SQLiteDatabase,
        snapshotId: String,
        epochKey: ByteArray
    ): SnapshotPayload? =
        db.rawQuery(
            """
            SELECT base_commit_id, is_full, snapshot_ct, snapshot_hash
            FROM snapshots
            WHERE snapshot_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(snapshotId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val snapshotCt = cursor.getBlob(2)
                val expectedHash = cursor.getString(3)
                if (sha256Hex(snapshotCt) != expectedHash) {
                    throw IllegalStateException("MDBX snapshot integrity check failed: $snapshotId")
                }
                val payload = JSONObject(decodeVaultText(snapshotCt, epochKey))
                SnapshotPayload(
                    baseCommitId = payload.optString("base_commit_id", cursor.getString(0)),
                    isFull = cursor.getInt(1) != 0 || payload.optBoolean("is_full", false),
                    entries = payload.optJSONArray("entries")
                        ?.let(::snapshotEntriesFromJson)
                        ?: emptyList()
                )
            }
        }

    private fun snapshotEntriesFromJson(array: JSONArray): List<ObjectVersionState> =
        buildList {
            array.forEachObject { item ->
                add(
                    ObjectVersionState(
                        versionSeq = item.optLong("version_seq", 0L),
                        objectType = item.optString("object_type", "entry"),
                        objectId = item.getString("object_id"),
                        commitId = item.getString("commit_id"),
                        projectId = item.optNullableString("project_id"),
                        entryType = item.optNullableString("entry_type"),
                        titleCt = item.optNullableString("title_ct")?.let(::base64ToBlob),
                        payloadCt = item.optNullableString("payload_ct")?.let(::base64ToBlob),
                        deleted = item.optBoolean("deleted", false),
                        createdAt = item.getString("created_at"),
                        createdByDeviceId = item.optNullableString("created_by_device_id") ?: deviceId
                    )
                )
            }
        }

    private fun ObjectVersionState.toSnapshotJson(): JSONObject =
        JSONObject()
            .put("version_seq", versionSeq)
            .put("object_type", objectType)
            .put("object_id", objectId)
            .put("commit_id", commitId)
            .put("project_id", projectId)
            .put("entry_type", entryType)
            .put("title_ct", titleCt?.let(::blobToBase64))
            .put("payload_ct", payloadCt?.let(::blobToBase64))
            .put("deleted", deleted)
            .put("created_at", createdAt)
            .put("created_by_device_id", createdByDeviceId)

    private fun readCurrentEntryVersionStates(db: SQLiteDatabase): List<ObjectVersionState> =
        db.rawQuery(
            """
            SELECT 0 AS version_seq, 'entry' AS object_type, entry_id, head_commit_id,
                   project_id, entry_type, title_ct, payload_ct, deleted, updated_at,
                   updated_by_device_id
            FROM entries
            ORDER BY updated_at ASC, entry_id ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toObjectVersionState())
            }
        }

    private fun readLatestEntryVersionsAtCommit(
        db: SQLiteDatabase,
        baseCommitId: String
    ): List<ObjectVersionState> {
        val ancestors = collectAncestors(db, baseCommitId)
        if (ancestors.isEmpty()) return emptyList()
        val latest = linkedMapOf<String, ObjectVersionState>()
        db.rawQuery(
            """
            SELECT version_seq, object_type, object_id, commit_id, project_id,
                   entry_type, title_ct, payload_ct, deleted, created_at,
                   created_by_device_id
            FROM object_versions
            WHERE object_type = 'entry'
            ORDER BY created_at ASC, version_seq ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val version = cursor.toObjectVersionState()
                if (version.commitId in ancestors) {
                    latest[version.objectId] = version
                }
            }
        }
        return latest.values.toList()
    }

    private fun revertToCommitState(
        db: SQLiteDatabase,
        baseCommitId: String,
        epochKey: ByteArray
    ): Int {
        val versions = readLatestEntryVersionsAtCommit(db, baseCommitId)
        return revertToEntryVersionSet(db, versions, epochKey)
    }

    private fun revertToEntryVersionSet(
        db: SQLiteDatabase,
        versions: List<ObjectVersionState>,
        epochKey: ByteArray
    ): Int {
        if (versions.isEmpty()) {
            throw IllegalStateException("MDBX snapshot has no restorable entry versions")
        }
        val targetVersionsById = linkedMapOf<String, ObjectVersionState>()
        versions.forEach { version ->
            targetVersionsById[version.objectId] = version
        }
        targetVersionsById.values.forEach { version ->
            applyEntryVersionAsRevert(db, version, epochKey, commitKind = "snapshot-revert")
        }
        val entriesCreatedAfterSnapshot = readCurrentEntryVersionStates(db)
            .filter { current -> !current.deleted && current.objectId !in targetVersionsById }
        entriesCreatedAfterSnapshot.forEach { current ->
            markEntryDeletedBySnapshotRevert(db, current, epochKey)
        }
        return targetVersionsById.size + entriesCreatedAfterSnapshot.size
    }

    private fun pruneAutomaticSnapshotsLocked(
        db: SQLiteDatabase,
        keepCount: Int,
        maxBytes: Long? = null
    ): Int {
        val snapshots = db.rawQuery(
            """
            SELECT snapshot_id, payload_bytes
            FROM snapshots
            WHERE auto_prune = 1
            ORDER BY created_at DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1))
            }
        }
        val deleteIds = linkedSetOf<String>()
        snapshots.drop(keepCount.coerceAtLeast(0)).forEach { deleteIds += it.first }
        maxBytes?.takeIf { it > 0 }?.let { limit ->
            var total = snapshots
                .filterNot { it.first in deleteIds }
                .sumOf { it.second }
            snapshots.asReversed().forEach { (snapshotId, bytes) ->
                if (total <= limit) return@forEach
                if (deleteIds.add(snapshotId)) total -= bytes
            }
        }
        deleteIds.forEach { id ->
            db.execSQL("DELETE FROM snapshots WHERE snapshot_id = ? AND auto_prune = 1", arrayOf(id))
        }
        return deleteIds.size
    }

    private fun automaticSnapshotRetention(dbInfo: LocalMdbxDatabase): Int =
        when (MdbxTigaMode.fromName(dbInfo.tigaMode)) {
            MdbxTigaMode.POWER -> 10
            MdbxTigaMode.MULTI -> 20
            MdbxTigaMode.SKY -> 30
        }

    private fun applyEntryVersionAsRevert(
        db: SQLiteDatabase,
        target: ObjectVersionState,
        epochKey: ByteArray,
        commitKind: String = "revert"
    ) {
        val projectId = target.projectId ?: target.objectId
        val entryType = target.entryType ?: "entry"
        val titleCt = target.titleCt ?: encrypt(target.objectId, epochKey)
        val payloadCt = target.payloadCt
            ?: throw IllegalStateException("Cannot revert ${target.objectId}: missing payload snapshot")
        val title = decodeVaultText(titleCt, epochKey)
        val payloadJson = decodeVaultText(payloadCt, epochKey)
        val folderId = folderIdFromPayload(payloadJson)
        val commitId = appendCommit(
            db = db,
            scope = "entry",
            objectId = target.objectId,
            epochKey = epochKey,
            commitKind = commitKind
        )
        val now = now()
        ensureFolder(db, folderId, epochKey)
        db.execSQL(
            """
            INSERT OR IGNORE INTO projects (
                project_id, title_ct, group_id, object_clock, head_commit_id,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, '1', ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(projectId, titleCt, folderId, commitId, now, now, deviceId, deviceId)
        )
        db.execSQL(
            """
            UPDATE projects SET title_ct = ?, group_id = ?, object_clock = object_clock + 1,
                head_commit_id = ?, deleted = 0, updated_at = ?, updated_by_device_id = ?
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(titleCt, folderId, commitId, now, deviceId, projectId)
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO entries (
                entry_id, project_id, entry_type, title_ct, payload_ct,
                payload_schema_version, object_clock, head_commit_id, deleted,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, 1, COALESCE(
                (SELECT object_clock + 1 FROM entries WHERE entry_id = ?),
                1
            ), ?, ?, COALESCE(
                (SELECT created_at FROM entries WHERE entry_id = ?),
                ?
            ), ?, COALESCE(
                (SELECT created_by_device_id FROM entries WHERE entry_id = ?),
                ?
            ), ?)
            """.trimIndent(),
            arrayOf(
                target.objectId,
                projectId,
                entryType,
                titleCt,
                payloadCt,
                target.objectId,
                commitId,
                if (target.deleted) 1 else 0,
                target.objectId,
                now,
                now,
                target.objectId,
                deviceId,
                deviceId
            )
        )
        upsertObjectIndex(
            db = db,
            objectType = "entry",
            objectId = target.objectId,
            parentFolderId = projectId,
            title = title,
            entryType = entryType,
            commitId = commitId,
            deleted = target.deleted
        )
        if (target.deleted) {
            insertTombstone(db, "entry", target.objectId)
        } else {
            clearTombstone(db, "entry", target.objectId)
        }
        recordEntryVersion(
            db = db,
            commitId = commitId,
            projectId = projectId,
            entryId = target.objectId,
            entryType = entryType,
            titleCt = titleCt,
            payloadCt = payloadCt,
            deleted = target.deleted,
            createdAt = now
        )
    }

    private fun markEntryDeletedBySnapshotRevert(
        db: SQLiteDatabase,
        current: ObjectVersionState,
        epochKey: ByteArray
    ) {
        val projectId = current.projectId ?: current.objectId
        val entryType = current.entryType ?: "entry"
        val commitId = appendCommit(
            db = db,
            scope = "entry",
            objectId = current.objectId,
            epochKey = epochKey,
            commitKind = "snapshot-revert"
        )
        val now = now()
        db.execSQL(
            """
            UPDATE entries SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, current.objectId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_type = 'entry' AND object_id = ?",
            arrayOf(now, commitId, current.objectId)
        )
        db.execSQL(
            """
            UPDATE projects SET deleted = 1, object_clock = object_clock + 1,
                head_commit_id = ?, updated_at = ?, updated_by_device_id = ?
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(commitId, now, deviceId, projectId)
        )
        db.execSQL(
            "UPDATE object_index SET deleted = 1, updated_at = ?, head_commit_id = ? WHERE object_type = 'project' AND object_id = ?",
            arrayOf(now, commitId, projectId)
        )
        insertTombstone(db, "entry", current.objectId)
        insertTombstone(db, "project", projectId)
        recordEntryVersion(
            db = db,
            commitId = commitId,
            projectId = projectId,
            entryId = current.objectId,
            entryType = entryType,
            titleCt = current.titleCt,
            payloadCt = current.payloadCt,
            deleted = true,
            createdAt = now
        )
    }

    private fun readConflictForResolution(
        db: SQLiteDatabase,
        conflictId: String
    ): ConflictResolutionTarget? =
        db.rawQuery(
            """
            SELECT object_type, object_id, local_commit_id, incoming_commit_id,
                   local_title_ct, local_payload_ct, incoming_title_ct, incoming_payload_ct
            FROM conflicts
            WHERE conflict_id = ? AND resolution = 'unresolved'
            LIMIT 1
            """.trimIndent(),
            arrayOf(conflictId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ConflictResolutionTarget(
                    objectType = cursor.getString(0),
                    objectId = cursor.getString(1),
                    localCommitId = cursor.getString(2),
                    incomingCommitId = cursor.getString(3),
                    localTitleCt = if (cursor.isNull(4)) null else cursor.getBlob(4),
                    localPayloadCt = if (cursor.isNull(5)) null else cursor.getBlob(5),
                    incomingTitleCt = if (cursor.isNull(6)) null else cursor.getBlob(6),
                    incomingPayloadCt = if (cursor.isNull(7)) null else cursor.getBlob(7)
                )
            }
        }

    private fun applyConflictWinnerMetadata(
        db: SQLiteDatabase,
        conflict: ConflictResolutionTarget,
        winnerCommitId: String,
        resolutionCommitId: String,
        winnerTitleCt: ByteArray?,
        winnerPayloadCt: ByteArray?
    ) {
        val now = now()
        when (conflict.objectType.lowercase(Locale.ROOT)) {
            "entry" -> {
                if (winnerPayloadCt != null) {
                    db.execSQL(
                        """
                        UPDATE entries
                        SET title_ct = COALESCE(?, title_ct), payload_ct = ?,
                            head_commit_id = ?, object_clock = object_clock + 1,
                            updated_at = ?, updated_by_device_id = ?
                        WHERE entry_id = ?
                        """.trimIndent(),
                        arrayOf(
                            winnerTitleCt,
                            winnerPayloadCt,
                            winnerCommitId,
                            now,
                            deviceId,
                            conflict.objectId
                        )
                    )
                } else {
                db.execSQL(
                    """
                    UPDATE entries
                    SET head_commit_id = ?, object_clock = object_clock + 1,
                        updated_at = ?, updated_by_device_id = ?
                    WHERE entry_id = ?
                    """.trimIndent(),
                    arrayOf(winnerCommitId, now, deviceId, conflict.objectId)
                )
                }
                val titleKey = winnerTitleCt
                    ?.let { decodeVaultText(it).lowercase(Locale.ROOT) }
                db.execSQL(
                    """
                    UPDATE object_index
                    SET head_commit_id = ?, updated_at = ?, deleted = 0,
                        title_key = COALESCE(?, title_key)
                    WHERE object_type = 'entry' AND object_id = ?
                    """.trimIndent(),
                    arrayOf(resolutionCommitId, now, titleKey, conflict.objectId)
                )
            }
            "project" -> {
                if (winnerTitleCt != null) {
                    db.execSQL(
                        """
                        UPDATE projects
                        SET title_ct = ?, head_commit_id = ?, object_clock = object_clock + 1,
                            updated_at = ?, updated_by_device_id = ?
                        WHERE project_id = ?
                        """.trimIndent(),
                        arrayOf(winnerTitleCt, winnerCommitId, now, deviceId, conflict.objectId)
                    )
                } else {
                db.execSQL(
                    """
                    UPDATE projects
                    SET head_commit_id = ?, object_clock = object_clock + 1,
                        updated_at = ?, updated_by_device_id = ?
                    WHERE project_id = ?
                    """.trimIndent(),
                    arrayOf(winnerCommitId, now, deviceId, conflict.objectId)
                )
                }
                val titleKey = winnerTitleCt
                    ?.let { decodeVaultText(it).lowercase(Locale.ROOT) }
                db.execSQL(
                    """
                    UPDATE object_index
                    SET head_commit_id = ?, updated_at = ?, deleted = 0,
                        title_key = COALESCE(?, title_key)
                    WHERE object_type = 'project' AND object_id = ?
                    """.trimIndent(),
                    arrayOf(resolutionCommitId, now, titleKey, conflict.objectId)
                )
            }
        }
    }

    private fun resolveEntryConflict(
        db: SQLiteDatabase,
        conflict: ConflictResolutionTarget,
        resolution: MdbxConflictResolution,
        epochKey: ByteArray
    ) {
        when (resolution) {
            MdbxConflictResolution.LOCAL_WINS -> {
                val localState = readEntryVersionState(db, conflict.objectId)
                    ?: throw IllegalStateException("Cannot resolve ${conflict.objectId}: missing local entry")
                val resolutionCommitId = appendCommit(
                    db = db,
                    scope = "entry",
                    objectId = conflict.objectId,
                    epochKey = epochKey,
                    commitKind = "merge"
                )
                updateEntryHeadForResolvedConflict(
                    db = db,
                    state = localState,
                    resolutionCommitId = resolutionCommitId,
                    epochKey = epochKey
                )
            }
            MdbxConflictResolution.INCOMING_WINS -> {
                val incomingState = readEntryVersionForCommit(
                    db,
                    conflict.objectId,
                    conflict.incomingCommitId
                ) ?: conflict.toIncomingObjectVersionState()
                    ?: throw IllegalStateException(
                        "Cannot resolve ${conflict.objectId}: missing incoming entry snapshot"
                    )
                val resolutionCommitId = appendCommit(
                    db = db,
                    scope = "entry",
                    objectId = conflict.objectId,
                    epochKey = epochKey,
                    commitKind = "merge"
                )
                applyResolvedEntryVersion(
                    db = db,
                    state = incomingState,
                    resolutionCommitId = resolutionCommitId,
                    epochKey = epochKey
                )
            }
            MdbxConflictResolution.CUSTOM_MERGE -> {
                throw IllegalStateException("Custom MDBX conflict merge needs a merged payload")
            }
            MdbxConflictResolution.MARK_RESOLVED -> {
                throw IllegalStateException("Entry conflicts must choose local or incoming")
            }
        }
    }

    private fun readEntryVersionForCommit(
        db: SQLiteDatabase,
        entryId: String,
        commitId: String
    ): ObjectVersionState? =
        db.rawQuery(
            """
            SELECT version_seq, object_type, object_id, commit_id, project_id,
                   entry_type, title_ct, payload_ct, deleted, created_at,
                   created_by_device_id
            FROM object_versions
            WHERE object_type = 'entry' AND object_id = ? AND commit_id = ?
            ORDER BY version_seq DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(entryId, commitId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toObjectVersionState() else null
        }

    private fun ConflictResolutionTarget.toIncomingObjectVersionState(): ObjectVersionState? {
        val payloadCt = incomingPayloadCt ?: return null
        return ObjectVersionState(
            versionSeq = 0L,
            objectType = "entry",
            objectId = objectId,
            commitId = incomingCommitId,
            projectId = null,
            entryType = null,
            titleCt = incomingTitleCt,
            payloadCt = payloadCt,
            deleted = false,
            createdAt = now(),
            createdByDeviceId = deviceId
        )
    }

    private fun updateEntryHeadForResolvedConflict(
        db: SQLiteDatabase,
        state: ObjectVersionState,
        resolutionCommitId: String,
        epochKey: ByteArray
    ) {
        val now = now()
        db.execSQL(
            """
            UPDATE entries
            SET object_clock = object_clock + 1, head_commit_id = ?,
                updated_at = ?, updated_by_device_id = ?
            WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(resolutionCommitId, now, deviceId, state.objectId)
        )
        upsertObjectIndex(
            db = db,
            objectType = "entry",
            objectId = state.objectId,
            parentFolderId = state.projectId ?: state.objectId,
            title = state.titleCt?.let { decodeVaultText(it, epochKey) } ?: state.objectId,
            entryType = state.entryType ?: "entry",
            commitId = resolutionCommitId,
            deleted = state.deleted
        )
        recordEntryVersion(
            db = db,
            commitId = resolutionCommitId,
            projectId = state.projectId ?: state.objectId,
            entryId = state.objectId,
            entryType = state.entryType ?: "entry",
            titleCt = state.titleCt,
            payloadCt = state.payloadCt,
            deleted = state.deleted,
            createdAt = now
        )
    }

    private fun applyResolvedEntryVersion(
        db: SQLiteDatabase,
        state: ObjectVersionState,
        resolutionCommitId: String,
        epochKey: ByteArray
    ) {
        val now = now()
        val existing = readEntryVersionState(db, state.objectId)
        val projectId = state.projectId ?: existing?.projectId
            ?: throw IllegalStateException("Cannot resolve ${state.objectId}: missing project snapshot")
        val entryType = state.entryType ?: existing?.entryType ?: "login"
        val titleCt = state.titleCt ?: existing?.titleCt
        val payloadCt = state.payloadCt
            ?: throw IllegalStateException("Cannot resolve ${state.objectId}: missing payload snapshot")
        db.execSQL(
            """
            UPDATE entries
            SET project_id = ?, entry_type = ?, title_ct = ?, payload_ct = ?,
                payload_schema_version = 1, object_clock = object_clock + 1,
                head_commit_id = ?, deleted = ?, updated_at = ?,
                updated_by_device_id = ?
            WHERE entry_id = ?
            """.trimIndent(),
            arrayOf(
                projectId,
                entryType,
                titleCt,
                payloadCt,
                resolutionCommitId,
                if (state.deleted) 1 else 0,
                now,
                deviceId,
                state.objectId
            )
        )
        upsertObjectIndex(
            db = db,
            objectType = "entry",
            objectId = state.objectId,
            parentFolderId = projectId,
            title = titleCt?.let { decodeVaultText(it, epochKey) } ?: state.objectId,
            entryType = entryType,
            commitId = resolutionCommitId,
            deleted = state.deleted
        )
        if (state.deleted) {
            insertTombstone(db, "entry", state.objectId)
        } else {
            clearTombstone(db, "entry", state.objectId)
        }
        recordEntryVersion(
            db = db,
            commitId = resolutionCommitId,
            projectId = projectId,
            entryId = state.objectId,
            entryType = entryType,
            titleCt = titleCt,
            payloadCt = payloadCt,
            deleted = state.deleted,
            createdAt = now
        )
    }

    private enum class ApplyDecision {
        APPLIED,
        KEPT_LOCAL,
        CONFLICT
    }

    private data class IncomingProject(
        val projectId: String,
        val titleCt: ByteArray,
        val summaryCt: ByteArray?,
        val groupId: String?,
        val iconRef: String?,
        val favorite: Int,
        val archived: Int,
        val deleted: Int,
        val tigaModeOverride: String?,
        val objectClock: String,
        val headCommitId: String,
        val attachmentCount: Int,
        val createdAt: String,
        val updatedAt: String,
        val createdByDeviceId: String,
        val updatedByDeviceId: String
    )

    private data class IncomingEntry(
        val entryId: String,
        val projectId: String,
        val entryType: String,
        val titleCt: ByteArray?,
        val payloadCt: ByteArray,
        val payloadSchemaVersion: Int,
        val tigaModeOverride: String?,
        val objectClock: String,
        val headCommitId: String,
        val deleted: Int,
        val createdAt: String,
        val updatedAt: String,
        val createdByDeviceId: String,
        val updatedByDeviceId: String
    )

    private data class IncomingAttachment(
        val attachmentId: String,
        val projectId: String,
        val entryId: String?,
        val fileNameCt: ByteArray,
        val mediaTypeCt: ByteArray?,
        val storageMode: String,
        val contentHash: String,
        val originalSize: Long,
        val storedSize: Long,
        val chunkCount: Int,
        val headCommitId: String,
        val deleted: Int,
        val createdAt: String,
        val updatedAt: String,
        val createdByDeviceId: String,
        val updatedByDeviceId: String,
        val wrappedCekCt: ByteArray?,
        val attachmentCreatedAt: Long,
        val attachmentUpdatedAt: Long
    )

    private data class IncomingAttachmentChunk(
        val attachmentId: String,
        val chunkIndex: Int,
        val chunkHash: String,
        val chunkCt: ByteArray?,
        val externalUriCt: ByteArray?,
        val storedSize: Long,
        val createdAt: String
    )

    private data class LocalObjectState(
        val titleCt: ByteArray?,
        val payloadCt: ByteArray?,
        val headCommitId: String,
        val deleted: Int
    )

    private fun applyIncomingProject(
        local: SQLiteDatabase,
        incomingDb: SQLiteDatabase,
        incoming: IncomingProject,
        localEpochKey: ByteArray?,
        incomingEpochKey: ByteArray?
    ): ApplyDecision {
        val localState = readLocalProject(local, incoming.projectId, localEpochKey)
        if (localState == null) {
            upsertIncomingProject(local, incoming)
            copyIncomingProjectTagsIfPresent(local, incomingDb, incoming.projectId)
            copyIncomingObjectIndex(local, incomingDb, "project", incoming.projectId)
            return ApplyDecision.APPLIED
        }
        val incomingTitle = normalizeVaultBytes(incoming.titleCt, incomingEpochKey)
        if (sameObjectState(localState, incomingTitle, null, incoming.deleted)) {
            upsertIncomingProject(local, incoming)
            copyIncomingProjectTagsIfPresent(local, incomingDb, incoming.projectId)
            copyIncomingObjectIndex(local, incomingDb, "project", incoming.projectId)
            return ApplyDecision.APPLIED
        }
        if (localState.headCommitId == incoming.headCommitId ||
            isAncestor(incomingDb, localState.headCommitId, incoming.headCommitId)
        ) {
            upsertIncomingProject(local, incoming)
            copyIncomingProjectTagsIfPresent(local, incomingDb, incoming.projectId)
            copyIncomingObjectIndex(local, incomingDb, "project", incoming.projectId)
            return ApplyDecision.APPLIED
        }
        if (isAncestor(local, incoming.headCommitId, localState.headCommitId)) {
            return ApplyDecision.KEPT_LOCAL
        }
        insertIncomingConflict(
            db = local,
            objectType = "project",
            objectId = incoming.projectId,
            localState = localState,
            incomingHeadCommitId = incoming.headCommitId,
            incomingTitleCt = incomingTitle,
            incomingPayloadCt = null,
            epochKey = localEpochKey
        )
        return ApplyDecision.CONFLICT
    }

    private fun applyIncomingEntry(
        local: SQLiteDatabase,
        incomingDb: SQLiteDatabase,
        incoming: IncomingEntry,
        localEpochKey: ByteArray?,
        incomingEpochKey: ByteArray?
    ): ApplyDecision {
        val localState = readLocalEntry(local, incoming.entryId, localEpochKey)
        if (localState == null) {
            copyIncomingProjectIfMissing(local, incomingDb, incoming.projectId)
            upsertIncomingEntry(local, incoming)
            copyIncomingObjectIndex(local, incomingDb, "entry", incoming.entryId)
            return ApplyDecision.APPLIED
        }
        val incomingTitle = incoming.titleCt?.let { normalizeVaultBytes(it, incomingEpochKey) }
        val incomingPayload = normalizeVaultBytes(incoming.payloadCt, incomingEpochKey)
        if (sameObjectState(localState, incomingTitle, incomingPayload, incoming.deleted)) {
            upsertIncomingEntry(local, incoming)
            copyIncomingObjectIndex(local, incomingDb, "entry", incoming.entryId)
            return ApplyDecision.APPLIED
        }
        if (localState.headCommitId == incoming.headCommitId ||
            isAncestor(incomingDb, localState.headCommitId, incoming.headCommitId)
        ) {
            copyIncomingProjectIfMissing(local, incomingDb, incoming.projectId)
            upsertIncomingEntry(local, incoming)
            copyIncomingObjectIndex(local, incomingDb, "entry", incoming.entryId)
            return ApplyDecision.APPLIED
        }
        if (isAncestor(local, incoming.headCommitId, localState.headCommitId)) {
            return ApplyDecision.KEPT_LOCAL
        }
        insertIncomingConflict(
            db = local,
            objectType = "entry",
            objectId = incoming.entryId,
            localState = localState,
            incomingHeadCommitId = incoming.headCommitId,
            incomingTitleCt = incomingTitle,
            incomingPayloadCt = incomingPayload,
            epochKey = localEpochKey
        )
        return ApplyDecision.CONFLICT
    }

    private fun applyIncomingAttachment(
        local: SQLiteDatabase,
        incomingDb: SQLiteDatabase,
        incoming: IncomingAttachment,
        localEpochKey: ByteArray?,
        incomingEpochKey: ByteArray?
    ): ApplyDecision {
        val localState = readLocalAttachment(local, incoming.attachmentId, localEpochKey)
        if (localState == null) {
            copyIncomingProjectIfMissing(local, incomingDb, incoming.projectId)
            upsertIncomingAttachment(local, incoming)
            copyIncomingAttachmentChunks(local, incomingDb, incoming.attachmentId)
            copyIncomingObjectIndex(local, incomingDb, "attachment", incoming.attachmentId)
            updateAttachmentCount(local, incoming.projectId)
            return ApplyDecision.APPLIED
        }
        val incomingTitle = normalizeVaultBytes(incoming.fileNameCt, incomingEpochKey)
        val incomingPayload = attachmentPayloadCt(incoming, incomingEpochKey)
        if (sameObjectState(localState, incomingTitle, incomingPayload, incoming.deleted)) {
            upsertIncomingAttachment(local, incoming)
            copyIncomingAttachmentChunks(local, incomingDb, incoming.attachmentId)
            copyIncomingObjectIndex(local, incomingDb, "attachment", incoming.attachmentId)
            updateAttachmentCount(local, incoming.projectId)
            return ApplyDecision.APPLIED
        }
        if (localState.headCommitId == incoming.headCommitId ||
            isAncestor(incomingDb, localState.headCommitId, incoming.headCommitId)
        ) {
            upsertIncomingAttachment(local, incoming)
            copyIncomingAttachmentChunks(local, incomingDb, incoming.attachmentId)
            copyIncomingObjectIndex(local, incomingDb, "attachment", incoming.attachmentId)
            updateAttachmentCount(local, incoming.projectId)
            return ApplyDecision.APPLIED
        }
        if (isAncestor(local, incoming.headCommitId, localState.headCommitId)) {
            return ApplyDecision.KEPT_LOCAL
        }
        insertIncomingConflict(
            db = local,
            objectType = "attachment",
            objectId = incoming.attachmentId,
            localState = localState,
            incomingHeadCommitId = incoming.headCommitId,
            incomingTitleCt = incomingTitle,
            incomingPayloadCt = incomingPayload,
            epochKey = localEpochKey
        )
        return ApplyDecision.CONFLICT
    }

    private fun sameObjectState(
        localState: LocalObjectState,
        incomingTitleCt: ByteArray?,
        incomingPayloadCt: ByteArray?,
        incomingDeleted: Int
    ): Boolean =
        localState.deleted == incomingDeleted &&
            localState.titleCt.contentEqualsNullable(incomingTitleCt) &&
            localState.payloadCt.contentEqualsNullable(incomingPayloadCt)

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> contentEquals(other)
        }

    private fun readLocalProject(
        db: SQLiteDatabase,
        projectId: String,
        epochKey: ByteArray?
    ): LocalObjectState? =
        db.rawQuery(
            "SELECT title_ct, head_commit_id, deleted FROM projects WHERE project_id = ? LIMIT 1",
            arrayOf(projectId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                LocalObjectState(
                    titleCt = if (cursor.isNull(0)) null else normalizeVaultBytes(cursor.getBlob(0), epochKey),
                    payloadCt = null,
                    headCommitId = cursor.getString(1),
                    deleted = cursor.getInt(2)
                )
            }
        }

    private fun readLocalEntry(
        db: SQLiteDatabase,
        entryId: String,
        epochKey: ByteArray?
    ): LocalObjectState? =
        db.rawQuery(
            "SELECT title_ct, payload_ct, head_commit_id, deleted FROM entries WHERE entry_id = ? LIMIT 1",
            arrayOf(entryId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                LocalObjectState(
                    titleCt = if (cursor.isNull(0)) null else normalizeVaultBytes(cursor.getBlob(0), epochKey),
                    payloadCt = if (cursor.isNull(1)) null else normalizeVaultBytes(cursor.getBlob(1), epochKey),
                    headCommitId = cursor.getString(2),
                    deleted = cursor.getInt(3)
                )
            }
        }

    private fun readLocalAttachment(
        db: SQLiteDatabase,
        attachmentId: String,
        epochKey: ByteArray?
    ): LocalObjectState? =
        db.rawQuery(
            """
            SELECT file_name_ct, media_type_ct, storage_mode, content_hash, original_size,
                   stored_size, chunk_count, wrapped_cek_ct, attachment_created_at,
                   attachment_updated_at, head_commit_id, deleted
            FROM attachments
            WHERE attachment_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(attachmentId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                LocalObjectState(
                    titleCt = normalizeVaultBytes(cursor.getBlob(0), epochKey),
                    payloadCt = attachmentPayloadCt(
                        mediaTypeCt = if (cursor.isNull(1)) null else cursor.getBlob(1),
                        storageMode = cursor.getString(2),
                        contentHash = cursor.getString(3),
                        originalSize = cursor.getLong(4),
                        storedSize = cursor.getLong(5),
                        chunkCount = cursor.getInt(6),
                        wrappedCekCt = if (cursor.isNull(7)) null else cursor.getBlob(7),
                        attachmentCreatedAt = cursor.getLong(8),
                        attachmentUpdatedAt = cursor.getLong(9),
                        epochKey = epochKey
                    ),
                    headCommitId = cursor.getString(10),
                    deleted = cursor.getInt(11)
                )
            }
        }

    private fun attachmentPayloadCt(
        attachment: IncomingAttachment,
        epochKey: ByteArray?
    ): ByteArray =
        attachmentPayloadCt(
            mediaTypeCt = attachment.mediaTypeCt,
            storageMode = attachment.storageMode,
            contentHash = attachment.contentHash,
            originalSize = attachment.originalSize,
            storedSize = attachment.storedSize,
            chunkCount = attachment.chunkCount,
            wrappedCekCt = attachment.wrappedCekCt,
            attachmentCreatedAt = attachment.attachmentCreatedAt,
            attachmentUpdatedAt = attachment.attachmentUpdatedAt,
            epochKey = epochKey
        )

    private fun attachmentPayloadCt(
        mediaTypeCt: ByteArray?,
        storageMode: String,
        contentHash: String,
        originalSize: Long,
        storedSize: Long,
        chunkCount: Int,
        wrappedCekCt: ByteArray?,
        attachmentCreatedAt: Long,
        attachmentUpdatedAt: Long,
        epochKey: ByteArray?
    ): ByteArray =
        JSONObject()
            .put("media_type", mediaTypeCt?.let { decodeVaultText(it, epochKey) }.orEmpty())
            .put("storage_mode", storageMode)
            .put("content_hash", contentHash)
            .put("original_size", originalSize)
            .put("stored_size", storedSize)
            .put("chunk_count", chunkCount)
            .put("wrapped_cek", wrappedCekCt?.let { decodeVaultText(it, epochKey) }.orEmpty())
            .put("attachment_created_at", attachmentCreatedAt)
            .put("attachment_updated_at", attachmentUpdatedAt)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun insertIncomingConflict(
        db: SQLiteDatabase,
        objectType: String,
        objectId: String,
        localState: LocalObjectState,
        incomingHeadCommitId: String,
        incomingTitleCt: ByteArray?,
        incomingPayloadCt: ByteArray?,
        epochKey: ByteArray?
    ) {
        val conflictId = "conflict:$objectType:$objectId:${localState.headCommitId}:$incomingHeadCommitId"
        val exists = queryLong(
            db,
            "SELECT COUNT(*) FROM conflicts WHERE conflict_id = ? AND resolution = 'unresolved'",
            arrayOf(conflictId)
        ) > 0L
        if (exists) return
        appendCommit(db, "conflict", objectId, epochKey)
        db.execSQL(
            """
            INSERT OR IGNORE INTO conflicts (
                conflict_id, object_type, object_id, base_commit_id,
                local_commit_id, incoming_commit_id, conflicting_fields,
                resolution, created_at, local_title_ct, local_payload_ct,
                incoming_title_ct, incoming_payload_ct
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'unresolved', ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                conflictId,
                objectType,
                objectId,
                findCommonCommit(db, localState.headCommitId, incomingHeadCommitId) ?: "",
                localState.headCommitId,
                incomingHeadCommitId,
                "title_ct,payload_ct,deleted",
                now(),
                localState.titleCt,
                localState.payloadCt,
                incomingTitleCt,
                incomingPayloadCt
            )
        )
    }

    private fun upsertIncomingProject(db: SQLiteDatabase, project: IncomingProject) {
        db.execSQL(
            """
            INSERT OR REPLACE INTO projects (
                project_id, title_ct, summary_ct, group_id, icon_ref, favorite, archived,
                deleted, tiga_mode_override, object_clock, head_commit_id, attachment_count,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                project.projectId,
                project.titleCt,
                project.summaryCt,
                project.groupId,
                project.iconRef,
                project.favorite,
                project.archived,
                project.deleted,
                project.tigaModeOverride,
                project.objectClock,
                project.headCommitId,
                project.attachmentCount,
                project.createdAt,
                project.updatedAt,
                project.createdByDeviceId,
                project.updatedByDeviceId
            )
        )
    }

    private fun upsertIncomingEntry(db: SQLiteDatabase, entry: IncomingEntry) {
        db.execSQL(
            """
            INSERT OR REPLACE INTO entries (
                entry_id, project_id, entry_type, title_ct, payload_ct,
                payload_schema_version, tiga_mode_override, object_clock, head_commit_id,
                deleted, created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                entry.entryId,
                entry.projectId,
                entry.entryType,
                entry.titleCt,
                entry.payloadCt,
                entry.payloadSchemaVersion,
                entry.tigaModeOverride,
                entry.objectClock,
                entry.headCommitId,
                entry.deleted,
                entry.createdAt,
                entry.updatedAt,
                entry.createdByDeviceId,
                entry.updatedByDeviceId
            )
        )
    }

    private fun upsertIncomingAttachment(db: SQLiteDatabase, attachment: IncomingAttachment) {
        db.execSQL(
            """
            INSERT OR REPLACE INTO attachments (
                attachment_id, project_id, entry_id, file_name_ct, media_type_ct,
                storage_mode, content_hash, original_size, stored_size, chunk_count,
                head_commit_id, deleted, created_at, updated_at, created_by_device_id,
                updated_by_device_id, wrapped_cek_ct, attachment_created_at,
                attachment_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                attachment.attachmentId,
                attachment.projectId,
                attachment.entryId,
                attachment.fileNameCt,
                attachment.mediaTypeCt,
                attachment.storageMode,
                attachment.contentHash,
                attachment.originalSize,
                attachment.storedSize,
                attachment.chunkCount,
                attachment.headCommitId,
                attachment.deleted,
                attachment.createdAt,
                attachment.updatedAt,
                attachment.createdByDeviceId,
                attachment.updatedByDeviceId,
                attachment.wrappedCekCt,
                attachment.attachmentCreatedAt,
                attachment.attachmentUpdatedAt
            )
        )
    }

    private fun copyIncomingAttachmentChunks(
        local: SQLiteDatabase,
        incoming: SQLiteDatabase,
        attachmentId: String
    ) {
        local.execSQL("DELETE FROM attachment_chunks WHERE attachment_id = ?", arrayOf(attachmentId))
        readIncomingAttachmentChunks(incoming, attachmentId).forEach { chunk ->
            local.execSQL(
                """
                INSERT OR REPLACE INTO attachment_chunks (
                    attachment_id, chunk_index, chunk_hash, chunk_ct, external_uri_ct,
                    stored_size, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    chunk.attachmentId,
                    chunk.chunkIndex,
                    chunk.chunkHash,
                    chunk.chunkCt,
                    chunk.externalUriCt,
                    chunk.storedSize,
                    chunk.createdAt
                )
            )
        }
    }

    private fun updateAttachmentCount(db: SQLiteDatabase, projectId: String) {
        db.execSQL(
            """
            UPDATE projects
            SET attachment_count = (
                SELECT COUNT(*) FROM attachments
                WHERE project_id = ? AND deleted = 0
            )
            WHERE project_id = ?
            """.trimIndent(),
            arrayOf(projectId, projectId)
        )
    }

    private fun copyIncomingProjectIfMissing(
        local: SQLiteDatabase,
        incomingDb: SQLiteDatabase,
        projectId: String
    ) {
        if (localProjectExists(local, projectId)) return
        readIncomingProject(incomingDb, projectId)?.let {
            upsertIncomingProject(local, it)
            copyIncomingProjectTagsIfPresent(local, incomingDb, projectId)
            copyIncomingObjectIndex(local, incomingDb, "project", projectId)
        }
    }

    private fun copyIncomingProjectTagsIfPresent(
        local: SQLiteDatabase,
        incoming: SQLiteDatabase,
        projectId: String
    ) {
        if (!tableExists(incoming, "project_tags")) return
        replaceProjectTags(local, projectId, readProjectTags(incoming, projectId))
    }

    private fun localProjectExists(db: SQLiteDatabase, projectId: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM projects WHERE project_id = ? LIMIT 1",
            arrayOf(projectId)
        ).use { cursor -> cursor.moveToFirst() }

    private fun copyIncomingHistory(local: SQLiteDatabase, incoming: SQLiteDatabase) {
        incoming.rawQuery(
            """
            SELECT device_id, device_name, client_label, created_at, last_seen_at, revoked
            FROM devices
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                local.execSQL(
                    """
                    INSERT OR IGNORE INTO devices (
                        device_id, device_name, client_label, created_at, last_seen_at, revoked
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getInt(5)
                    )
                )
            }
        }
        incoming.rawQuery(
            """
            SELECT commit_id, device_id, local_seq, commit_kind, change_scope,
                   changed_object_ids_ct, vector_clock, message_ct, created_at, integrity_tag
            FROM commits
            ORDER BY created_at ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                local.execSQL(
                    """
                    INSERT OR IGNORE INTO commits (
                        commit_id, device_id, local_seq, commit_kind, change_scope,
                        changed_object_ids_ct, vector_clock, message_ct, created_at, integrity_tag
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getBlob(5),
                        cursor.getString(6),
                        if (cursor.isNull(7)) null else cursor.getBlob(7),
                        cursor.getString(8),
                        cursor.getBlob(9)
                    )
                )
            }
        }
        incoming.rawQuery(
            "SELECT commit_id, parent_commit_id FROM commit_parents",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                local.execSQL(
                    "INSERT OR IGNORE INTO commit_parents (commit_id, parent_commit_id) VALUES (?, ?)",
                    arrayOf(cursor.getString(0), cursor.getString(1))
                )
            }
        }
        if (tableExists(incoming, "object_versions")) {
            incoming.rawQuery(
                """
                SELECT object_type, object_id, commit_id, project_id, entry_type,
                       title_ct, payload_ct, deleted, created_at, created_by_device_id
                FROM object_versions
                ORDER BY created_at ASC, version_seq ASC
                """.trimIndent(),
                emptyArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    local.execSQL(
                        """
                        INSERT OR IGNORE INTO object_versions (
                            object_type, object_id, commit_id, project_id, entry_type,
                            title_ct, payload_ct, deleted, created_at, created_by_device_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            if (cursor.isNull(3)) null else cursor.getString(3),
                            if (cursor.isNull(4)) null else cursor.getString(4),
                            if (cursor.isNull(5)) null else cursor.getBlob(5),
                            if (cursor.isNull(6)) null else cursor.getBlob(6),
                            cursor.getInt(7),
                            cursor.getString(8),
                            cursor.getString(9)
                        )
                    )
                }
            }
        }
        incoming.rawQuery(
            "SELECT device_id, head_commit_id, last_seen_at, revoked FROM device_heads",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val incomingDeviceId = cursor.getString(0)
                if (incomingDeviceId != deviceId) {
                    local.execSQL(
                        """
                        INSERT OR REPLACE INTO device_heads (
                            device_id, head_commit_id, last_seen_at, revoked
                        ) VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            incomingDeviceId,
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getInt(3)
                        )
                    )
                }
            }
        }
    }

    private fun copyIncomingFolders(local: SQLiteDatabase, incoming: SQLiteDatabase) {
        incoming.rawQuery(
            """
            SELECT folder_id, parent_folder_id, name_ct, path_key, object_clock,
                   head_commit_id, deleted, created_at, updated_at,
                   created_by_device_id, updated_by_device_id
            FROM folders
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                local.execSQL(
                    """
                    INSERT OR IGNORE INTO folders (
                        folder_id, parent_folder_id, name_ct, path_key, object_clock,
                        head_commit_id, deleted, created_at, updated_at,
                        created_by_device_id, updated_by_device_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        cursor.getString(0),
                        if (cursor.isNull(1)) null else cursor.getString(1),
                        cursor.getBlob(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getInt(6),
                        cursor.getString(7),
                        cursor.getString(8),
                        cursor.getString(9),
                        cursor.getString(10)
                    )
                )
            }
        }
    }

    private fun copyIncomingTombstones(local: SQLiteDatabase, incoming: SQLiteDatabase): Int {
        var copied = 0
        incoming.rawQuery(
            """
            SELECT tombstone_id, target_object_type, target_object_id,
                   delete_clock, deleted_by_device_id, deleted_at, purge_eligible_at
            FROM tombstones
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                local.execSQL(
                    """
                    INSERT OR IGNORE INTO tombstones (
                        tombstone_id, target_object_type, target_object_id,
                        delete_clock, deleted_by_device_id, deleted_at, purge_eligible_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        if (cursor.isNull(6)) null else cursor.getString(6)
                    )
                )
                copied++
            }
        }
        return copied
    }

    private fun copyIncomingObjectIndex(
        local: SQLiteDatabase,
        incoming: SQLiteDatabase,
        objectType: String,
        objectId: String
    ) {
        incoming.rawQuery(
            """
            SELECT object_type, object_id, parent_id, title_key, entry_type,
                   head_commit_id, updated_at, deleted
            FROM object_index
            WHERE object_type = ? AND object_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(objectType, objectId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return
            local.execSQL(
                """
                INSERT OR REPLACE INTO object_index (
                    object_type, object_id, parent_id, title_key, entry_type,
                    head_commit_id, updated_at, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    cursor.getString(0),
                    cursor.getString(1),
                    if (cursor.isNull(2)) null else cursor.getString(2),
                    cursor.getString(3),
                    if (cursor.isNull(4)) null else cursor.getString(4),
                    cursor.getString(5),
                    cursor.getString(6),
                    cursor.getInt(7)
                )
            )
        }
    }

    private fun readIncomingProjects(db: SQLiteDatabase): List<IncomingProject> =
        db.rawQuery(
            """
            SELECT project_id, title_ct, summary_ct, group_id, icon_ref, favorite,
                   archived, deleted, tiga_mode_override, object_clock, head_commit_id,
                   attachment_count, created_at, updated_at, created_by_device_id,
                   updated_by_device_id
            FROM projects
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(readIncomingProject(cursor))
                }
            }
        }

    private fun readIncomingProject(db: SQLiteDatabase, projectId: String): IncomingProject? =
        db.rawQuery(
            """
            SELECT project_id, title_ct, summary_ct, group_id, icon_ref, favorite,
                   archived, deleted, tiga_mode_override, object_clock, head_commit_id,
                   attachment_count, created_at, updated_at, created_by_device_id,
                   updated_by_device_id
            FROM projects
            WHERE project_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(projectId)
        ).use { cursor ->
            if (cursor.moveToFirst()) readIncomingProject(cursor) else null
        }

    private fun readIncomingProject(cursor: android.database.Cursor): IncomingProject =
        IncomingProject(
            projectId = cursor.getString(0),
            titleCt = cursor.getBlob(1),
            summaryCt = if (cursor.isNull(2)) null else cursor.getBlob(2),
            groupId = if (cursor.isNull(3)) null else cursor.getString(3),
            iconRef = if (cursor.isNull(4)) null else cursor.getString(4),
            favorite = cursor.getInt(5),
            archived = cursor.getInt(6),
            deleted = cursor.getInt(7),
            tigaModeOverride = if (cursor.isNull(8)) null else cursor.getString(8),
            objectClock = cursor.getString(9),
            headCommitId = cursor.getString(10),
            attachmentCount = cursor.getInt(11),
            createdAt = cursor.getString(12),
            updatedAt = cursor.getString(13),
            createdByDeviceId = cursor.getString(14),
            updatedByDeviceId = cursor.getString(15)
        )

    private fun readIncomingEntries(db: SQLiteDatabase): List<IncomingEntry> =
        db.rawQuery(
            """
            SELECT entry_id, project_id, entry_type, title_ct, payload_ct,
                   payload_schema_version, tiga_mode_override, object_clock,
                   head_commit_id, deleted, created_at, updated_at,
                   created_by_device_id, updated_by_device_id
            FROM entries
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IncomingEntry(
                            entryId = cursor.getString(0),
                            projectId = cursor.getString(1),
                            entryType = cursor.getString(2),
                            titleCt = if (cursor.isNull(3)) null else cursor.getBlob(3),
                            payloadCt = cursor.getBlob(4),
                            payloadSchemaVersion = cursor.getInt(5),
                            tigaModeOverride = if (cursor.isNull(6)) null else cursor.getString(6),
                            objectClock = cursor.getString(7),
                            headCommitId = cursor.getString(8),
                            deleted = cursor.getInt(9),
                            createdAt = cursor.getString(10),
                            updatedAt = cursor.getString(11),
                            createdByDeviceId = cursor.getString(12),
                            updatedByDeviceId = cursor.getString(13)
                        )
                    )
                }
            }
        }

    private fun readIncomingAttachments(db: SQLiteDatabase): List<IncomingAttachment> =
        db.rawQuery(
            """
            SELECT attachment_id, project_id, entry_id, file_name_ct, media_type_ct,
                   storage_mode, content_hash, original_size, stored_size, chunk_count,
                   head_commit_id, deleted, created_at, updated_at, created_by_device_id,
                   updated_by_device_id, wrapped_cek_ct, attachment_created_at,
                   attachment_updated_at
            FROM attachments
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IncomingAttachment(
                            attachmentId = cursor.getString(0),
                            projectId = cursor.getString(1),
                            entryId = if (cursor.isNull(2)) null else cursor.getString(2),
                            fileNameCt = cursor.getBlob(3),
                            mediaTypeCt = if (cursor.isNull(4)) null else cursor.getBlob(4),
                            storageMode = cursor.getString(5),
                            contentHash = cursor.getString(6),
                            originalSize = cursor.getLong(7),
                            storedSize = cursor.getLong(8),
                            chunkCount = cursor.getInt(9),
                            headCommitId = cursor.getString(10),
                            deleted = cursor.getInt(11),
                            createdAt = cursor.getString(12),
                            updatedAt = cursor.getString(13),
                            createdByDeviceId = cursor.getString(14),
                            updatedByDeviceId = cursor.getString(15),
                            wrappedCekCt = if (cursor.isNull(16)) null else cursor.getBlob(16),
                            attachmentCreatedAt = cursor.getLong(17),
                            attachmentUpdatedAt = cursor.getLong(18)
                        )
                    )
                }
            }
        }

    private fun readIncomingAttachmentChunks(
        db: SQLiteDatabase,
        attachmentId: String
    ): List<IncomingAttachmentChunk> =
        db.rawQuery(
            """
            SELECT attachment_id, chunk_index, chunk_hash, chunk_ct, external_uri_ct,
                   stored_size, created_at
            FROM attachment_chunks
            WHERE attachment_id = ?
            ORDER BY chunk_index ASC
            """.trimIndent(),
            arrayOf(attachmentId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        IncomingAttachmentChunk(
                            attachmentId = cursor.getString(0),
                            chunkIndex = cursor.getInt(1),
                            chunkHash = cursor.getString(2),
                            chunkCt = if (cursor.isNull(3)) null else cursor.getBlob(3),
                            externalUriCt = if (cursor.isNull(4)) null else cursor.getBlob(4),
                            storedSize = cursor.getLong(5),
                            createdAt = cursor.getString(6)
                        )
                    )
                }
            }
        }

    private fun readAttachmentBlob(db: SQLiteDatabase, attachmentId: String): ByteArray? {
        val chunks = readIncomingAttachmentChunks(db, attachmentId)
        if (chunks.isEmpty() || chunks.any { it.chunkCt == null }) return null
        return chunks.sortedBy { it.chunkIndex }
            .fold(ByteArray(0)) { acc, chunk -> acc + chunk.chunkCt!! }
    }

    private fun isAncestor(db: SQLiteDatabase, ancestorCommitId: String, descendantCommitId: String): Boolean {
        if (ancestorCommitId.isBlank() || descendantCommitId.isBlank()) return false
        if (ancestorCommitId == descendantCommitId) return true
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        pending.add(descendantCommitId)
        while (pending.isNotEmpty() && seen.size < 4096) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            db.rawQuery(
                "SELECT parent_commit_id FROM commit_parents WHERE commit_id = ?",
                arrayOf(current)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val parent = cursor.getString(0)
                    if (parent == ancestorCommitId) return true
                    pending.add(parent)
                }
            }
        }
        return false
    }

    private fun findCommonCommit(
        db: SQLiteDatabase,
        firstHead: String,
        secondHead: String
    ): String? {
        val firstAncestors = collectAncestors(db, firstHead)
        if (firstAncestors.isEmpty()) return null
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        pending.add(secondHead)
        while (pending.isNotEmpty() && seen.size < 4096) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current in firstAncestors) return current
            db.rawQuery(
                "SELECT parent_commit_id FROM commit_parents WHERE commit_id = ?",
                arrayOf(current)
            ).use { cursor ->
                while (cursor.moveToNext()) pending.add(cursor.getString(0))
            }
        }
        return null
    }

    private fun collectAncestors(db: SQLiteDatabase, headCommitId: String): Set<String> {
        if (headCommitId.isBlank()) return emptySet()
        val pending = ArrayDeque<String>()
        val seen = linkedSetOf<String>()
        pending.add(headCommitId)
        while (pending.isNotEmpty() && seen.size < 4096) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            db.rawQuery(
                "SELECT parent_commit_id FROM commit_parents WHERE commit_id = ?",
                arrayOf(current)
            ).use { cursor ->
                while (cursor.moveToNext()) pending.add(cursor.getString(0))
            }
        }
        return seen
    }

    private fun folderIdFromPayload(payloadJson: String): String {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return "root"
        val explicitFolderId = payload.optString("mdbx_folder_id")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("root", ignoreCase = true) }
        if (explicitFolderId != null) return explicitFolderId
        val categoryId = payload.optLong("category_id", 0L)
        return if (categoryId > 0L) "category:$categoryId" else "root"
    }

    private fun buildDeviceId(): String =
        "${deviceLabel()}-${UUID.randomUUID().toString().replace("-", "").take(8)}"

    private fun deviceLabel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val raw = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString("-")
            .ifBlank { "Android" }
        return raw
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "Android" }
    }

    private fun insertTombstone(db: SQLiteDatabase, objectType: String, objectId: String) {
        val now = now()
        db.execSQL(
            "INSERT OR REPLACE INTO tombstones (tombstone_id, target_object_type, target_object_id, delete_clock, deleted_by_device_id, deleted_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf("tombstone:$objectType:$objectId", objectType, objectId, now, deviceId, now)
        )
    }

    private fun clearTombstone(db: SQLiteDatabase, objectType: String, objectId: String) {
        db.execSQL(
            "DELETE FROM tombstones WHERE target_object_type = ? AND target_object_id = ?",
            arrayOf(objectType, objectId)
        )
    }

    private fun ensureReadableSchema(db: SQLiteDatabase) {
        val missingTables = missingRequiredTables(db)
        if (missingTables.isNotEmpty()) {
            throw IllegalStateException(
                "Unsupported MDBX schema, missing tables: ${missingTables.joinToString()}"
            )
        }
    }

    private fun buildSyncBundlePayload(
        db: SQLiteDatabase,
        baseCommitId: String?
    ): JSONObject {
        val baseCreatedAt = baseCommitId?.let {
            queryString(db, "SELECT created_at FROM commits WHERE commit_id = ?", arrayOf(it))
        }
        val commitWhere = if (baseCreatedAt == null) "" else "WHERE created_at > ?"
        val joinedCommitWhere = if (baseCreatedAt == null) "" else "WHERE c.created_at > ?"
        val commitArgs = baseCreatedAt?.let { arrayOf(it) } ?: emptyArray()
        return JSONObject()
            .put("format", "monica-mdbx-sync-bundle-v1")
            .put("device_id", deviceId)
            .put("base_commit_id", baseCommitId)
            .put("created_at", now())
            .put(
                "commits",
                queryJsonArray(
                    db,
                    """
                    SELECT commit_id, device_id, local_seq, commit_kind, change_scope,
                           changed_object_ids_ct, vector_clock, message_ct, created_at, integrity_tag
                    FROM commits
                    $commitWhere
                    ORDER BY created_at ASC, local_seq ASC
                    """.trimIndent(),
                    commitArgs
                ) { cursor ->
                    JSONObject()
                        .put("commit_id", cursor.getString(0))
                        .put("device_id", cursor.getString(1))
                        .put("local_seq", cursor.getLong(2))
                        .put("commit_kind", cursor.getString(3))
                        .put("change_scope", cursor.getString(4))
                        .put("changed_object_ids_ct", blobToBase64(cursor.getBlob(5)))
                        .put("vector_clock", cursor.getString(6))
                        .put("message_ct", if (cursor.isNull(7)) null else blobToBase64(cursor.getBlob(7)))
                        .put("created_at", cursor.getString(8))
                        .put("integrity_tag", blobToBase64(cursor.getBlob(9)))
                }
            )
            .put(
                "commit_parents",
                queryJsonArray(
                    db,
                    """
                    SELECT cp.commit_id, cp.parent_commit_id
                    FROM commit_parents cp
                    JOIN commits c ON c.commit_id = cp.commit_id
                    $joinedCommitWhere
                    ORDER BY c.created_at ASC
                    """.trimIndent(),
                    commitArgs
                ) { cursor ->
                    JSONObject()
                        .put("commit_id", cursor.getString(0))
                        .put("parent_commit_id", cursor.getString(1))
                }
            )
            .put(
                "object_versions",
                if (tableExists(db, "object_versions")) {
                    queryJsonArray(
                        db,
                        """
                        SELECT ov.object_type, ov.object_id, ov.commit_id, ov.project_id,
                               ov.entry_type, ov.title_ct, ov.payload_ct, ov.deleted,
                               ov.created_at, ov.created_by_device_id
                        FROM object_versions ov
                        JOIN commits c ON c.commit_id = ov.commit_id
                        $joinedCommitWhere
                        ORDER BY ov.created_at ASC, ov.version_seq ASC
                        """.trimIndent(),
                        commitArgs
                    ) { cursor ->
                        JSONObject()
                            .put("object_type", cursor.getString(0))
                            .put("object_id", cursor.getString(1))
                            .put("commit_id", cursor.getString(2))
                            .put("project_id", if (cursor.isNull(3)) null else cursor.getString(3))
                            .put("entry_type", if (cursor.isNull(4)) null else cursor.getString(4))
                            .put("title_ct", if (cursor.isNull(5)) null else blobToBase64(cursor.getBlob(5)))
                            .put("payload_ct", if (cursor.isNull(6)) null else blobToBase64(cursor.getBlob(6)))
                            .put("deleted", cursor.getInt(7))
                            .put("created_at", cursor.getString(8))
                            .put("created_by_device_id", cursor.getString(9))
                    }
                } else {
                    JSONArray()
                }
            )
            .put(
                "folders",
                queryJsonArray(
                    db,
                    """
                    SELECT f.folder_id, f.parent_folder_id, f.name_ct, f.path_key, f.object_clock,
                           f.head_commit_id, f.deleted, f.created_at, f.updated_at,
                           f.created_by_device_id, f.updated_by_device_id
                    FROM folders f
                    JOIN commits c ON c.commit_id = f.head_commit_id
                    $joinedCommitWhere
                    ORDER BY f.path_key ASC
                    """.trimIndent(),
                    commitArgs
                ) { cursor ->
                    JSONObject()
                        .put("folder_id", cursor.getString(0))
                        .put("parent_folder_id", if (cursor.isNull(1)) null else cursor.getString(1))
                        .put("name_ct", blobToBase64(cursor.getBlob(2)))
                        .put("path_key", cursor.getString(3))
                        .put("object_clock", cursor.getString(4))
                        .put("head_commit_id", cursor.getString(5))
                        .put("deleted", cursor.getInt(6))
                        .put("created_at", cursor.getString(7))
                        .put("updated_at", cursor.getString(8))
                        .put("created_by_device_id", cursor.getString(9))
                        .put("updated_by_device_id", cursor.getString(10))
                }
            )
            .put(
                "project_tags",
                if (tableExists(db, "project_tags")) {
                    queryJsonArray(
                        db,
                        """
                        SELECT p.project_id, pt.tag
                        FROM project_tags pt
                        JOIN projects p ON p.project_id = pt.project_id
                        WHERE p.deleted = 0
                        ORDER BY p.project_id ASC, pt.tag ASC
                        """.trimIndent(),
                        emptyArray()
                    ) { cursor ->
                        JSONObject()
                            .put("project_id", cursor.getString(0))
                            .put("tag", cursor.getString(1))
                    }
                } else {
                    JSONArray()
                }
            )
            .put(
                "tombstones",
                queryJsonArray(
                    db,
                    """
                    SELECT tombstone_id, target_object_type, target_object_id,
                           delete_clock, deleted_by_device_id, deleted_at, purge_eligible_at
                    FROM tombstones
                    """.trimIndent(),
                    emptyArray()
                ) { cursor ->
                    JSONObject()
                        .put("tombstone_id", cursor.getString(0))
                        .put("target_object_type", cursor.getString(1))
                        .put("target_object_id", cursor.getString(2))
                        .put("delete_clock", cursor.getString(3))
                        .put("deleted_by_device_id", cursor.getString(4))
                        .put("deleted_at", cursor.getString(5))
                        .put("purge_eligible_at", if (cursor.isNull(6)) null else cursor.getString(6))
                }
            )
            .put(
                "oplog",
                if (tableExists(db, "oplog")) {
                    queryJsonArray(
                        db,
                        """
                        SELECT op_id, commit_id, device_id, local_seq, operation,
                               object_type, object_id, payload_ct, created_at
                        FROM oplog
                        ORDER BY created_at ASC
                        """.trimIndent(),
                        emptyArray()
                    ) { cursor ->
                        JSONObject()
                            .put("op_id", cursor.getString(0))
                            .put("commit_id", cursor.getString(1))
                            .put("device_id", cursor.getString(2))
                            .put("local_seq", cursor.getLong(3))
                            .put("operation", cursor.getString(4))
                            .put("object_type", cursor.getString(5))
                            .put("object_id", cursor.getString(6))
                            .put("payload_ct", blobToBase64(cursor.getBlob(7)))
                            .put("created_at", cursor.getString(8))
                    }
                } else {
                    JSONArray()
                }
            )
            .put(
                "crypto_contexts",
                if (tableExists(db, "crypto_contexts")) {
                    queryJsonArray(
                        db,
                        """
                        SELECT context_id, object_type, object_id, field_name, key_purpose,
                               aad, algorithm, created_at, updated_at
                        FROM crypto_contexts
                        """.trimIndent(),
                        emptyArray()
                    ) { cursor ->
                        JSONObject()
                            .put("context_id", cursor.getString(0))
                            .put("object_type", cursor.getString(1))
                            .put("object_id", cursor.getString(2))
                            .put("field_name", cursor.getString(3))
                            .put("key_purpose", cursor.getString(4))
                            .put("aad", cursor.getString(5))
                            .put("algorithm", cursor.getString(6))
                            .put("created_at", cursor.getString(7))
                            .put("updated_at", cursor.getString(8))
                    }
                } else {
                    JSONArray()
                }
            )
    }

    private fun importBundleRows(
        db: SQLiteDatabase,
        payload: JSONObject,
        epochKey: ByteArray
    ): Int {
        var appliedFolders = 0
        payload.optJSONArray("commits")?.forEachObject { item ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO commits (
                    commit_id, device_id, local_seq, commit_kind, change_scope,
                    changed_object_ids_ct, vector_clock, message_ct, created_at, integrity_tag
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    item.getString("commit_id"),
                    item.getString("device_id"),
                    item.getLong("local_seq"),
                    item.getString("commit_kind"),
                    item.getString("change_scope"),
                    base64ToBlob(item.getString("changed_object_ids_ct")),
                    item.getString("vector_clock"),
                    item.optNullableString("message_ct")?.let(::base64ToBlob),
                    item.getString("created_at"),
                    base64ToBlob(item.getString("integrity_tag"))
                )
            )
        }
        payload.optJSONArray("commit_parents")?.forEachObject { item ->
            db.execSQL(
                "INSERT OR IGNORE INTO commit_parents (commit_id, parent_commit_id) VALUES (?, ?)",
                arrayOf(item.getString("commit_id"), item.getString("parent_commit_id"))
            )
        }
        payload.optJSONArray("object_versions")?.forEachObject { item ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO object_versions (
                    object_type, object_id, commit_id, project_id, entry_type,
                    title_ct, payload_ct, deleted, created_at, created_by_device_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    item.getString("object_type"),
                    item.getString("object_id"),
                    item.getString("commit_id"),
                    item.optNullableString("project_id"),
                    item.optNullableString("entry_type"),
                    item.optNullableString("title_ct")?.let(::base64ToBlob),
                    item.optNullableString("payload_ct")?.let(::base64ToBlob),
                    item.getInt("deleted"),
                    item.getString("created_at"),
                    item.getString("created_by_device_id")
                )
            )
        }
        payload.optJSONArray("folders")?.forEachObject { item ->
            val folderId = item.getString("folder_id")
            val incomingHeadCommitId = item.getString("head_commit_id")
            val nameCt = base64ToBlob(item.getString("name_ct"))
            val localHeadCommitId = queryString(
                db,
                "SELECT head_commit_id FROM folders WHERE folder_id = ? LIMIT 1",
                arrayOf(folderId)
            )
            if (localHeadCommitId != incomingHeadCommitId) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO folders (
                        folder_id, parent_folder_id, name_ct, path_key, object_clock,
                        head_commit_id, deleted, created_at, updated_at,
                        created_by_device_id, updated_by_device_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        folderId,
                        item.optNullableString("parent_folder_id"),
                        nameCt,
                        item.getString("path_key"),
                        item.getString("object_clock"),
                        incomingHeadCommitId,
                        item.getInt("deleted"),
                        item.getString("created_at"),
                        item.getString("updated_at"),
                        item.getString("created_by_device_id"),
                        item.getString("updated_by_device_id")
                    )
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO object_index (
                        object_type, object_id, parent_id, title_key, entry_type,
                        head_commit_id, updated_at, deleted
                    ) VALUES ('folder', ?, ?, ?, 'folder', ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        folderId,
                        item.optNullableString("parent_folder_id") ?: "root",
                        decodeVaultText(nameCt, epochKey).lowercase(Locale.ROOT),
                        incomingHeadCommitId,
                        item.getString("updated_at"),
                        item.getInt("deleted")
                    )
                )
                appliedFolders++
            }
        }
        payload.optJSONArray("tombstones")?.forEachObject { item ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO tombstones (
                    tombstone_id, target_object_type, target_object_id,
                    delete_clock, deleted_by_device_id, deleted_at, purge_eligible_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    item.getString("tombstone_id"),
                    item.getString("target_object_type"),
                    item.getString("target_object_id"),
                    item.getString("delete_clock"),
                    item.getString("deleted_by_device_id"),
                    item.getString("deleted_at"),
                    item.optNullableString("purge_eligible_at")
                )
            )
        }
        payload.optJSONArray("oplog")?.forEachObject { item ->
            db.execSQL(
                """
                INSERT OR IGNORE INTO oplog (
                    op_id, commit_id, device_id, local_seq, operation,
                    object_type, object_id, payload_ct, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    item.getString("op_id"),
                    item.getString("commit_id"),
                    item.getString("device_id"),
                    item.getLong("local_seq"),
                    item.getString("operation"),
                    item.getString("object_type"),
                    item.getString("object_id"),
                    base64ToBlob(item.getString("payload_ct")),
                    item.getString("created_at")
                )
            )
        }
        payload.optJSONArray("crypto_contexts")?.forEachObject { item ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO crypto_contexts (
                    context_id, object_type, object_id, field_name, key_purpose,
                    aad, algorithm, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    item.getString("context_id"),
                    item.getString("object_type"),
                    item.getString("object_id"),
                    item.getString("field_name"),
                    item.getString("key_purpose"),
                    item.getString("aad"),
                    item.getString("algorithm"),
                    item.getString("created_at"),
                    item.getString("updated_at")
                )
            )
        }
        return appliedFolders
    }

    private fun importBundleProjectTags(db: SQLiteDatabase, payload: JSONObject): Int {
        val tagsArray = payload.optJSONArray("project_tags") ?: return 0
        val tagsByProject = linkedMapOf<String, MutableList<String>>()
        tagsArray.forEachObject { item ->
            val projectId = item.optNullableString("project_id") ?: return@forEachObject
            val tag = item.optNullableString("tag") ?: return@forEachObject
            tagsByProject.getOrPut(projectId) { mutableListOf() }.add(tag)
        }
        var applied = 0
        tagsByProject.forEach { (projectId, tags) ->
            if (queryLong(
                    db,
                    "SELECT COUNT(*) FROM projects WHERE project_id = ? AND deleted = 0",
                    arrayOf(projectId)
                ) > 0L
            ) {
                replaceProjectTags(db, projectId, tags)
                applied++
            }
        }
        return applied
    }

    private fun latestBundleEntryVersions(payload: JSONObject): List<BundleEntryVersion> {
        val latest = linkedMapOf<String, BundleEntryVersion>()
        payload.optJSONArray("object_versions")?.forEachObject { item ->
            if (item.optString("object_type") != "entry") return@forEachObject
            val version = BundleEntryVersion(
                objectId = item.getString("object_id"),
                commitId = item.getString("commit_id"),
                projectId = item.optNullableString("project_id"),
                entryType = item.optNullableString("entry_type"),
                titleCt = item.optNullableString("title_ct")?.let(::base64ToBlob),
                payloadCt = item.optNullableString("payload_ct")?.let(::base64ToBlob),
                deleted = item.optInt("deleted") != 0,
                createdAt = item.getString("created_at")
            )
            val current = latest[version.objectId]
            if (current == null || version.createdAt >= current.createdAt) {
                latest[version.objectId] = version
            }
        }
        return latest.values.toList()
    }

    private fun applyBundleEntryVersion(
        db: SQLiteDatabase,
        version: BundleEntryVersion,
        epochKey: ByteArray
    ): ApplyDecision {
        val payloadCt = version.payloadCt ?: return ApplyDecision.KEPT_LOCAL
        val titleCt = version.titleCt ?: encrypt(version.objectId, epochKey)
        val localState = readLocalEntry(db, version.objectId, epochKey)
        val incomingTitle = normalizeVaultBytes(titleCt, epochKey)
        val incomingPayload = normalizeVaultBytes(payloadCt, epochKey)
        val incomingDeleted = if (version.deleted) 1 else 0
        if (localState != null) {
            if (sameObjectState(localState, incomingTitle, incomingPayload, incomingDeleted)) {
                return ApplyDecision.APPLIED.also {
                    upsertBundleEntryVersion(db, version, titleCt, payloadCt, epochKey)
                }
            }
            if (localState.headCommitId == version.commitId ||
                isAncestor(db, localState.headCommitId, version.commitId)
            ) {
                return ApplyDecision.APPLIED.also {
                    upsertBundleEntryVersion(db, version, titleCt, payloadCt, epochKey)
                }
            }
            if (isAncestor(db, version.commitId, localState.headCommitId)) {
                return ApplyDecision.KEPT_LOCAL
            }
            insertIncomingConflict(
                db = db,
                objectType = "entry",
                objectId = version.objectId,
                localState = localState,
                incomingHeadCommitId = version.commitId,
                incomingTitleCt = incomingTitle,
                incomingPayloadCt = incomingPayload,
                epochKey = epochKey
            )
            return ApplyDecision.CONFLICT
        }
        upsertBundleEntryVersion(db, version, titleCt, payloadCt, epochKey)
        return ApplyDecision.APPLIED
    }

    private fun upsertBundleEntryVersion(
        db: SQLiteDatabase,
        version: BundleEntryVersion,
        titleCt: ByteArray,
        payloadCt: ByteArray,
        epochKey: ByteArray
    ) {
        val payloadJson = decodeVaultText(payloadCt, epochKey)
        val title = decodeVaultText(titleCt, epochKey)
        val projectId = version.projectId ?: version.objectId
        val entryType = version.entryType ?: "entry"
        val folderId = folderIdFromPayload(payloadJson)
        ensureFolder(db, folderId, epochKey)
        db.execSQL(
            """
            INSERT OR IGNORE INTO projects (
                project_id, title_ct, group_id, object_clock, head_commit_id,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, '1', ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(projectId, titleCt, folderId, version.commitId, version.createdAt, version.createdAt, deviceId, deviceId)
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO entries (
                entry_id, project_id, entry_type, title_ct, payload_ct,
                payload_schema_version, object_clock, head_commit_id, deleted,
                created_at, updated_at, created_by_device_id, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, 1, COALESCE(
                (SELECT object_clock + 1 FROM entries WHERE entry_id = ?),
                1
            ), ?, ?, COALESCE(
                (SELECT created_at FROM entries WHERE entry_id = ?),
                ?
            ), ?, COALESCE(
                (SELECT created_by_device_id FROM entries WHERE entry_id = ?),
                ?
            ), ?)
            """.trimIndent(),
            arrayOf(
                version.objectId,
                projectId,
                entryType,
                titleCt,
                payloadCt,
                version.objectId,
                version.commitId,
                if (version.deleted) 1 else 0,
                version.objectId,
                version.createdAt,
                version.createdAt,
                version.objectId,
                deviceId,
                deviceId
            )
        )
        upsertObjectIndex(db, "entry", version.objectId, projectId, title, entryType, version.commitId, version.deleted)
        if (version.deleted) insertTombstone(db, "entry", version.objectId) else clearTombstone(db, "entry", version.objectId)
    }

    private fun unavailableDiagnostics(
        databaseId: Long,
        file: File?,
        reason: String,
        lastSyncStatus: String,
        lastSyncError: String?
    ): MdbxVaultDiagnostics =
        MdbxVaultDiagnostics(
            databaseId = databaseId,
            filePath = file?.absolutePath,
            fileExists = file?.exists() == true,
            fileSizeBytes = file?.takeIf { it.exists() }?.length() ?: 0,
            isReadable = false,
            unavailableReason = reason,
            integrityOk = false,
            integrityMessage = reason,
            lastSyncStatus = lastSyncStatus,
            lastSyncError = lastSyncError
        )

    private fun calculatePendingSyncCount(
        db: SQLiteDatabase,
        database: LocalMdbxDatabase
    ): Int {
        val status = runCatching { MdbxSyncStatus.valueOf(database.lastSyncStatus) }.getOrNull()
        if (status == MdbxSyncStatus.LOCAL_ONLY || status == MdbxSyncStatus.IN_SYNC) {
            return 0
        }
        val conflictCount = queryLongIfTableExists(
            db,
            "conflicts",
            "SELECT COUNT(*) FROM conflicts WHERE resolution = 'unresolved'"
        )
        if (status == MdbxSyncStatus.CONFLICT) {
            return conflictCount.coerceAtLeast(1)
        }
        val localOperationCount = queryPendingLocalOperationCount(db, database)
        return maxOf(conflictCount, localOperationCount, 1)
    }

    private fun queryPendingLocalOperationCount(
        db: SQLiteDatabase,
        database: LocalMdbxDatabase
    ): Int {
        if (!tableExists(db, "oplog")) return 0
        val lastSyncedIso = database.lastSyncedAt?.let {
            Instant.ofEpochMilli(it).toString()
        }
        val sql = if (lastSyncedIso == null) {
            "SELECT COUNT(*) FROM oplog WHERE device_id = ?"
        } else {
            "SELECT COUNT(*) FROM oplog WHERE device_id = ? AND created_at > ?"
        }
        val args = if (lastSyncedIso == null) {
            arrayOf(deviceId)
        } else {
            arrayOf(deviceId, lastSyncedIso)
        }
        return queryLong(db, sql, args).toInt()
    }

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray()
    ): Long =
        db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun queryString(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray()
    ): String? =
        db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun queryBlob(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray()
    ): ByteArray? =
        db.rawQuery(sql, args).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
        }

    private fun queryVaultMetaString(db: SQLiteDatabase, columnName: String): String? =
        if (tableExists(db, "vault_meta") && columnExists(db, "vault_meta", columnName)) {
            queryString(db, "SELECT $columnName FROM vault_meta LIMIT 1")
        } else {
            null
        }

    private fun queryVaultMetaBlob(db: SQLiteDatabase, columnName: String): ByteArray? =
        if (tableExists(db, "vault_meta") && columnExists(db, "vault_meta", columnName)) {
            queryBlob(db, "SELECT $columnName FROM vault_meta LIMIT 1")
        } else {
            null
        }

    private fun queryJsonArray(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String> = emptyArray(),
        row: (android.database.Cursor) -> JSONObject
    ): JSONArray =
        db.rawQuery(sql, args).use { cursor ->
            JSONArray().also { array ->
                while (cursor.moveToNext()) {
                    array.put(row(cursor))
                }
            }
        }

    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(block)
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) {
            null
        } else {
            optString(name).takeIf { it.isNotBlank() && it != "null" }
        }

    private fun blobToBase64(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun base64ToBlob(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean =
        queryLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ) > 0L

    private fun columnExists(db: SQLiteDatabase, tableName: String, columnName: String): Boolean =
        db.rawQuery("PRAGMA table_info($tableName)", emptyArray()).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == columnName) return@use true
            }
            false
        }

    private fun ensureColumn(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
        definition: String
    ) {
        if (!columnExists(db, tableName, columnName)) {
            db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
        }
    }

    private fun missingRequiredTables(db: SQLiteDatabase): List<String> =
        requiredCoreTables.filterNot { tableExists(db, it) }

    private fun missingMinimumReadableTables(db: SQLiteDatabase): List<String> =
        minimumReadableTables.filterNot { tableExists(db, it) }

    private fun isSupportedVaultFormat(formatVersion: String?): Boolean =
        formatVersion == MDBX_SCHEMA_FORMAT_VERSION ||
            formatVersion == MDBX_LEGACY_DRAFT_FORMAT_VERSION

    private fun requireSupportedVaultFormat(
        db: SQLiteDatabase,
        label: String = "MDBX"
    ) {
        val formatVersion = queryString(db, "SELECT format_version FROM vault_meta LIMIT 1")
        if (!isSupportedVaultFormat(formatVersion)) {
            throw IllegalArgumentException(
                "Unsupported $label format version: ${formatVersion ?: "unknown"}"
            )
        }
    }

    private fun queryLongIfTableExists(
        db: SQLiteDatabase,
        tableName: String,
        sql: String = "SELECT COUNT(*) FROM $tableName"
    ): Int =
        if (tableExists(db, tableName)) queryLong(db, sql).toInt() else 0

    private fun queryLongIfTableExistsLong(
        db: SQLiteDatabase,
        tableName: String,
        sql: String = "SELECT COUNT(*) FROM $tableName"
    ): Long =
        if (tableExists(db, tableName)) queryLong(db, sql) else 0L

    private fun encrypt(value: String, epochKey: ByteArray? = null): ByteArray =
        if (epochKey != null) {
            MdbxVaultCrypto.encryptText(epochKey, value)
        } else {
            value.toByteArray(Charsets.UTF_8)
        }

    private fun normalizeVaultBytes(value: ByteArray, epochKey: ByteArray?): ByteArray =
        decodeVaultText(value, epochKey).toByteArray(Charsets.UTF_8)

    private fun decodeVaultText(value: ByteArray, epochKey: ByteArray? = null): String {
        val raw = MdbxVaultCrypto.decryptText(epochKey, value)
        return runCatching { securityManager.decryptData(raw) }.getOrDefault(raw)
    }

    private fun requireEpochKey(db: SQLiteDatabase, dbInfo: LocalMdbxDatabase): ByteArray {
        if (!vaultHasCredentialMaterial(db)) {
            throw IllegalStateException("MDBX vault is missing credential material; recreate it before writing secrets")
        }
        epochKeyCache[dbInfo.id]?.let { return it }
        return readEpochKeyOrNull(db, dbInfo)
            ?.also { epochKeyCache[dbInfo.id] = it }
            ?: throw IllegalStateException("MDBX vault is locked or key file is unavailable; refusing to write plaintext")
    }

    private fun resolveIncomingEpochKey(
        local: SQLiteDatabase,
        incoming: SQLiteDatabase,
        dbInfo: LocalMdbxDatabase,
        localEpochKey: ByteArray
    ): ByteArray? =
        readEpochKeyOrNull(incoming, dbInfo)
            ?: localEpochKey.takeIf { canReuseLocalEpochKeyForIncoming(local, incoming) }

    private fun canReuseLocalEpochKeyForIncoming(
        local: SQLiteDatabase,
        incoming: SQLiteDatabase
    ): Boolean {
        val localVaultId = queryString(local, "SELECT vault_id FROM vault_meta LIMIT 1")
        val incomingVaultId = queryString(incoming, "SELECT vault_id FROM vault_meta LIMIT 1")
        if (localVaultId.isNullOrBlank() || localVaultId != incomingVaultId) return false

        val localEpochId = queryString(local, "SELECT active_key_epoch_id FROM vault_meta LIMIT 1")
        val incomingEpochId = queryString(incoming, "SELECT active_key_epoch_id FROM vault_meta LIMIT 1")
        return !localEpochId.isNullOrBlank() && localEpochId == incomingEpochId
    }

    private fun vaultHasCredentialMaterial(db: SQLiteDatabase): Boolean =
        queryVaultMetaBlob(db, "credential_salt") != null &&
            queryVaultMetaBlob(db, "credential_verifier") != null &&
            tableExists(db, "key_epochs") &&
            queryBlob(
                db,
                "SELECT wrapped_epoch_key_ct FROM key_epochs WHERE status = 'active' LIMIT 1"
            ) != null

    private fun readEpochKeyOrNull(
        db: SQLiteDatabase,
        dbInfo: LocalMdbxDatabase
    ): ByteArray? = runCatching {
        val vaultId = queryString(db, "SELECT vault_id FROM vault_meta LIMIT 1") ?: return null
        val salt = queryBlob(db, "SELECT credential_salt FROM vault_meta LIMIT 1") ?: return null
        val verifier = queryBlob(db, "SELECT credential_verifier FROM vault_meta LIMIT 1") ?: return null
        val wrappedEpochKey = queryBlob(
            db,
            "SELECT wrapped_epoch_key_ct FROM key_epochs WHERE status = 'active' LIMIT 1"
        ) ?: return null
        val kdfProfile = queryString(db, "SELECT credential_kdf_profile FROM vault_meta LIMIT 1")
            ?: queryString(db, "SELECT kdf_profile_id FROM key_epochs WHERE status = 'active' LIMIT 1")
            ?: return null
        val credential = readStoredCredential(db, dbInfo) ?: return null
        MdbxVaultCrypto.unlockEpochKey(
            vaultId = vaultId,
            credential = credential,
            salt = salt,
            expectedVerifier = verifier,
            wrappedEpochKey = wrappedEpochKey,
            kdfProfile = kdfProfile
        )
    }.getOrNull()

    private fun readStoredCredential(
        db: SQLiteDatabase,
        dbInfo: LocalMdbxDatabase
    ): MdbxVaultCredential? {
        val unlockMethod = MdbxUnlockMethod.fromStoredValue(
            queryString(db, "SELECT unlock_methods FROM vault_meta LIMIT 1")
                ?: dbInfo.unlockMethod
        )
        val password = if (
            unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD ||
            unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
        ) {
            dbInfo.encryptedPassword?.let { encrypted ->
                runCatching { securityManager.decryptData(encrypted) }.getOrNull()
            } ?: return null
        } else {
            null
        }
        val keyBytes = if (
            unlockMethod == MdbxUnlockMethod.KEY_FILE ||
            unlockMethod == MdbxUnlockMethod.MASTER_PASSWORD_AND_KEY_FILE
        ) {
            val uri = dbInfo.keyFileUri?.takeIf { it.isNotBlank() } ?: return null
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                ?: return null
        } else {
            null
        }
        return MdbxVaultCredential(
            unlockMethod = unlockMethod,
            password = password,
            keyFileBytes = keyBytes,
            keyFileName = dbInfo.keyFileName,
            keyFileFingerprint = dbInfo.keyFileFingerprint
        )
    }

    private fun legacyEncrypt(value: String): ByteArray =
        value.toByteArray(Charsets.UTF_8)

    private fun decodeNullableVaultText(cursor: android.database.Cursor, index: Int): String? =
        if (cursor.isNull(index)) null else decodeVaultText(cursor.getBlob(index))

    private fun summarizeConflictPayload(payloadJson: String?): String? {
        if (payloadJson.isNullOrBlank()) return null
        return runCatching {
            val payload = JSONObject(payloadJson)
            listOfNotNull(
                payload.optString("kind").takeIf { it.isNotBlank() },
                payload.optString("website").takeIf { it.isNotBlank() },
                payload.optString("username").takeIf { it.isNotBlank() },
                payload.optString("rp_id").takeIf { it.isNotBlank() },
                payload.optString("user_name").takeIf { it.isNotBlank() },
                payload.optString("notes").takeIf { it.isNotBlank() }?.take(80)
            ).joinToString(" · ").ifBlank { payloadJson.take(120) }
        }.getOrDefault(payloadJson.take(120))
    }

    private fun steamMaFileObjectId(maFileJson: String): String {
        val steamId = steamIdFromSteamMaFileJson(maFileJson)
        if (!steamId.isNullOrBlank()) return "steam-mafile:$steamId"
        return "steam-mafile:${sha256Hex(maFileJson.toByteArray(Charsets.UTF_8)).take(32)}"
    }

    private fun steamIdFromSteamMaFileJson(maFileJson: String): String? {
        val root = runCatching { JSONObject(maFileJson) }.getOrNull() ?: return null
        return root.optString("steamid").ifBlank { root.optString("steam_id") }
            .ifBlank { root.optString("SteamID") }
            .ifBlank { root.optString("steam64") }
            .takeIf { it.isNotBlank() }
    }

    private fun accountNameFromSteamMaFileJson(maFileJson: String): String? {
        val root = runCatching { JSONObject(maFileJson) }.getOrNull() ?: return null
        return root.optString("account_name").ifBlank { root.optString("accountName") }
            .ifBlank { root.optString("AccountName") }
            .takeIf { it.isNotBlank() }
    }

    private fun passwordObjectId(entry: PasswordEntry): String =
        mdbxPasswordObjectId(entry)

    private fun legacyPasswordObjectId(entry: PasswordEntry): String? =
        entry.replicaGroupId
            ?.takeIf(::isMdbxPasswordObjectId)
            ?.let { "password:${entry.id}" }
            ?.takeIf { it != passwordObjectId(entry) }

    private fun isMdbxPasswordObjectId(value: String): Boolean =
        value.startsWith("password:") && value.length > "password:".length

    private fun secureItemObjectId(item: SecureItem, prefix: String): String =
        item.replicaGroupId
            ?.takeIf { it.startsWith("$prefix:") }
            ?: "$prefix:${item.id}"

    private fun passkeyObjectId(passkey: PasskeyEntry): String =
        "passkey:${passkey.credentialId.ifBlank { passkey.id.toString() }}"

    private suspend fun resolveBoundNoteEntryId(entry: PasswordEntry): String? {
        val noteId = entry.boundNoteId ?: return null
        val note = secureItemDao?.getItemById(noteId) ?: return null
        if (note.itemType != ItemType.NOTE) return null
        return secureItemObjectId(note, "note")
    }

    private suspend fun resolveBoundPasswordEntryId(item: SecureItem): String? {
        if (item.itemType != ItemType.TOTP) return null
        val data = TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        ) ?: return null
        val passwordId = data.boundPasswordId ?: return null
        val password = passwordEntryDao?.getPasswordEntryById(passwordId) ?: return null
        return passwordObjectId(password)
    }

    fun passwordObjectIdForAttachment(entry: PasswordEntry): String =
        passwordObjectId(entry)

    private fun attachmentObjectId(parentEntryId: String, attachment: Attachment): String {
        val hash = attachment.sha256Hex
            ?: attachment.localPath
            ?: attachment.id.toString()
        val stablePart = listOf(
            parentEntryId,
            attachment.fileName,
            hash,
            attachment.createdAt.takeIf { it > 0L } ?: attachment.id
        ).joinToString("|")
        return "attachment:${sha256Hex(stablePart.toByteArray(Charsets.UTF_8)).take(32)}"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun now(): String = Instant.now().toString()
}
