package takagi.ru.monica.utils

import takagi.ru.monica.keepass.hasKeePassSourceChangedCause

import takagi.ru.monica.R

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString
import okio.ByteString.Companion.toByteString
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.data.KeePassSyncPhase
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalKeePassDatabaseDao
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isRemoteSource
import takagi.ru.monica.data.resolvedActiveFilePath
import takagi.ru.monica.data.resolvedActiveStorageLocation
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardType
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.SecureCustomField
import takagi.ru.monica.data.model.SecureCustomFieldType
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.model.isSshKeyEntry
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.formatForDisplay
import takagi.ru.monica.keepass.KeePassDxPasskeyCodec
import takagi.ru.monica.keepass.KeePassChangeOperation
import takagi.ru.monica.keepass.KeePassChangeConflictException
import takagi.ru.monica.keepass.KeePassChangeSet
import takagi.ru.monica.keepass.KeePassChangeSetApplier
import takagi.ru.monica.keepass.KeePassChangeTarget
import takagi.ru.monica.keepass.KeePassAttachmentChangePatch
import takagi.ru.monica.keepass.KeePassEntryCreatePatch
import takagi.ru.monica.keepass.KeePassDatabaseMetaPatch
import takagi.ru.monica.keepass.KeePassEntryPresentationPatch
import takagi.ru.monica.keepass.KeePassCustomIconPoolChangePatch
import takagi.ru.monica.keepass.KeePassFieldChangePatch
import takagi.ru.monica.keepass.KeePassAutoTypeItemPatch
import takagi.ru.monica.keepass.KeePassAutoTypePatch
import takagi.ru.monica.keepass.KeePassBinaryPoolItemPatch
import takagi.ru.monica.keepass.KeePassBinaryReferencePatch
import takagi.ru.monica.keepass.KeePassCustomDataPatch
import takagi.ru.monica.keepass.KeePassCustomIconPoolItemPatch
import takagi.ru.monica.keepass.KeePassEntryFingerprint
import takagi.ru.monica.keepass.KeePassFieldChange
import takagi.ru.monica.keepass.KeePassEntryTreeSnapshot
import takagi.ru.monica.keepass.KeePassGroupTreeChangePatch
import takagi.ru.monica.keepass.KeePassGroupTreeSnapshot
import takagi.ru.monica.keepass.KeePassHistoryChangePatch
import takagi.ru.monica.keepass.KeePassPendingChangeBaseSnapshot
import takagi.ru.monica.keepass.KeePassPendingChangeRepository
import takagi.ru.monica.keepass.KeePassPendingFlushPlan
import takagi.ru.monica.keepass.KeePassPendingFlushPlanner
import takagi.ru.monica.keepass.KeePassRemoteRebase
import takagi.ru.monica.keepass.KeePassStructureChangePatch
import takagi.ru.monica.keepass.KeePassTimesPatch
import takagi.ru.monica.keepass.KeePassManagedFieldScope
import takagi.ru.monica.keepass.KeePassEntryFieldPatch
import takagi.ru.monica.keepass.KeePassFieldRegistry
import takagi.ru.monica.keepass.KeePassNativeMutation
import takagi.ru.monica.keepass.KeePassNativeBrowserBuilder
import takagi.ru.monica.keepass.KeePassNativeBrowserSnapshot
import takagi.ru.monica.keepass.KeePassNativeEntryIdentity
import takagi.ru.monica.keepass.KeePassNativeEntryRecord
import takagi.ru.monica.keepass.KeePassNativeEntryPresentationUpdate
import takagi.ru.monica.keepass.KeePassNativeCustomIconPoolUpdate
import takagi.ru.monica.keepass.KeePassCustomIconEditor
import takagi.ru.monica.keepass.KeePassNativeGroupIdentity
import takagi.ru.monica.keepass.KeePassNativeGroupRecord
import takagi.ru.monica.keepass.KeePassNativeSearch
import takagi.ru.monica.keepass.KeePassNativeSearchOptions
import takagi.ru.monica.keepass.KeePassNativeSearchResult
import takagi.ru.monica.keepass.KeePassNativeSession
import takagi.ru.monica.keepass.KeePassNativeSessionBuilder
import takagi.ru.monica.keepass.KeePassNativeSessionCache
import takagi.ru.monica.keepass.KeePassEncodeBufferPolicy
import takagi.ru.monica.keepass.KeePassDatabaseCredentialEditor
import takagi.ru.monica.keepass.KeePassDatabaseAccessPolicy
import takagi.ru.monica.keepass.KeePassDatabaseSettingsEditor
import takagi.ru.monica.keepass.KeePassDatabaseSettingsSnapshot
import takagi.ru.monica.keepass.KeePassDatabaseSettingsUpdate
import takagi.ru.monica.keepass.KeePassKeyFileChangeMode
import takagi.ru.monica.keepass.KeePassMasterCredentialChangeResult
import takagi.ru.monica.keepass.SharedPreferencesKeePassDatabaseAccessPolicy
import takagi.ru.monica.keepass.KeePassProjectionIndexGate
import takagi.ru.monica.keepass.KeePassProjectionKind
import takagi.ru.monica.keepass.KeePassProjectionRefreshDecision
import takagi.ru.monica.keepass.KeePassLosslessTransfer
import takagi.ru.monica.keepass.KeePassNativeEntryTransferPayload
import takagi.ru.monica.keepass.KeePassRecycleBinPolicy
import takagi.ru.monica.keepass.KeePassRecoveryStore
import takagi.ru.monica.keepass.KeePassRecoveryRecord
import takagi.ru.monica.keepass.KeePassRawFileOperations
import takagi.ru.monica.keepass.KeePassRemoteVersionPolicy
import takagi.ru.monica.keepass.KeePassSourceRevision
import takagi.ru.monica.keepass.KeePassSourceChangedException
import takagi.ru.monica.keepass.KeePassSourceSafety
import takagi.ru.monica.keepass.KeePassTargetFirstTransfer
import takagi.ru.monica.keepass.KeePassConflictCenter
import takagi.ru.monica.keepass.KeePassConflictDecision
import takagi.ru.monica.keepass.KeePassConflictResolutionSide
import takagi.ru.monica.keepass.KeePassRemoteConflictPreview
import takagi.ru.monica.keepass.KeePassRemoteConflictResolution
import takagi.ru.monica.keepass.KeePassDatabaseMaintenance
import takagi.ru.monica.keepass.KeePassIntegrityReport
import takagi.ru.monica.keepass.KeePassMaintenanceExecution
import takagi.ru.monica.keepass.KeePassMaintenanceOptions
import takagi.ru.monica.keepass.KeePassNativeDatabaseMerge
import takagi.ru.monica.keepass.KeePassNativeDeleteMode
import takagi.ru.monica.keepass.KeePassNativeGroupUpdate
import takagi.ru.monica.keepass.KeePassNativeManagement
import takagi.ru.monica.keepass.KeePassTemplateEngine
import takagi.ru.monica.keepass.KeePassWritePreflight
import takagi.ru.monica.keepass.KeePassWritePreflightResult
import takagi.ru.monica.keepass.KeePassPasskeySyncCodec
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments
import takagi.ru.monica.keepass.KeePassTotpCodec
import takagi.ru.monica.keepass.KeePassSecureItemPayload
import takagi.ru.monica.notes.domain.NoteContentCodec
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.attachments.executor.KeePassAttachmentRef
import takagi.ru.monica.attachments.facade.AttachmentUriMetadata
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.sync.SyncDiagnostics
import takagi.ru.monica.util.ImageManager
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.workers.KeePassRemoteUploadWorker
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class KeePassEntryData(
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val appPackageName: String = "",
    val appName: String = "",
    val email: String = "",
    val phone: String = "",
    val addressLine: String = "",
    val city: String = "",
    val state: String = "",
    val zipCode: String = "",
    val country: String = "",
    val creditCardNumber: String = "",
    val creditCardHolder: String = "",
    val creditCardExpiry: String = "",
    val creditCardCVV: String = "",
    val sshKeyData: String = "",
    val monicaLocalId: Long?,
    val entryUuid: String?,
    val groupPath: String?,
    val groupUuid: String?,
    val isInRecycleBin: Boolean,
    /** 取值 `PASSWORD` / `SSO` / `WIFI`；用于还原 [PasswordEntry.loginType]。 */
    val loginType: String = "PASSWORD",
    val ssoProvider: String = "",
    val ssoRefEntryId: Long? = null,
    /** [takagi.ru.monica.data.model.WifiData] 的 JSON，仅在 WIFI 条目上有值。 */
    val wifiMetadata: String = "",
    val customFields: List<KeePassCustomFieldData> = emptyList(),
    val hasPasskeyFields: Boolean = false
)

data class KeePassCustomFieldData(
    val title: String,
    val value: String,
    val isProtected: Boolean,
    val sortOrder: Int = 0
)

internal data class KeePassRawStringField(
    val key: String,
    val value: String,
    val isProtected: Boolean
)

internal fun isLikelyKeePassRecycleBinPath(groupPath: String?): Boolean {
    val recycleNames = setOf("recyclebin", "trash", "回收站")
    return decodeKeePassPathSegments(groupPath).any { segment ->
        segment.lowercase(Locale.ROOT).replace(" ", "") in recycleNames
    }
}

data class KeePassGroupInfo(
    val name: String,
    val path: String,
    val uuid: String?,
    val depth: Int = 0,
    val displayPath: String = path
)

data class KeePassSecureItemData(
    val item: SecureItem,
    val sourceMonicaId: Long?,
    val isInRecycleBin: Boolean
)

data class KeePassWorkspaceSnapshot(
    val passwords: List<KeePassEntryData>,
    val secureItems: List<KeePassSecureItemData>,
    val groups: List<KeePassGroupInfo>,
    val sessionRevision: String? = null
)

/**
 * Kotpass owns and closes the sink created around the supplied stream while encoding.
 * Do not flush or sync [output] after [KeePassDatabase.encode] returns: the underlying
 * [FileOutputStream] is already closed at that point.
 */
internal fun encodeKeePassDatabaseArtifactFile(
    keePassDatabase: KeePassDatabase,
    destination: File,
): KeePassSourceRevision {
    FileOutputStream(destination).use { output ->
        keePassDatabase.encode(output, contentParser = KeePassCodecSupport.contentParser,
            cipherProviders = KeePassCodecSupport.cipherProviders)
    }
    return KeePassSourceSafety.revisionOf(destination)
}

data class KeePassDatabaseDiagnostics(
    val entryCount: Int,
    val creationOptions: KeePassDatabaseCreationOptions
)

data class KeePassRestoreTarget(
    val groupPath: String?,
    val groupUuid: String?
)

data class KeePassConflictResolutionResult(
    val mergedBytes: ByteArray,
    val conflictCopyCount: Int
)

data class KeePassNativeEntryTransferResult(
    val sourceDatabaseId: Long,
    val sourceEntryUuid: String,
    val targetDatabaseId: Long,
    val targetEntryUuid: String,
    val targetGroupUuid: String,
    val moved: Boolean
)

internal data class EntryTraversalContext(
    val entry: Entry,
    val groupPath: String?,
    val groupUuid: UUID?,
    val isInRecycleBinByMeta: Boolean
)

private data class GroupTraversalContext(
    val pathKey: String?,
    val groupUuid: UUID,
    val isInRecycleBinByMeta: Boolean
)

private data class RemovedEntryContext(
    val entry: Entry,
    val previousParentUuid: UUID
)

private data class RemoteConflictEntrySnapshot(
    val entry: Entry,
    val groupPath: String?,
    val signature: String
)

private data class InternalConflictResolutionResult(
    val mergedDatabase: KeePassDatabase,
    val mergedBytes: ByteArray,
    val conflictCopyCount: Int
)

private data class RemoteSyncOutcome(
    val writeResult: FileSourceWriteResult,
    val finalBytes: ByteArray,
    val finalDatabase: KeePassDatabase,
    val finalRevision: KeePassSourceRevision,
    val conflictCopyCount: Int = 0
)

private data class CurrentRemoteConflictContext(
    val database: LocalKeePassDatabase,
    val credentials: Credentials,
    val localDatabase: KeePassDatabase,
    val localBytes: ByteArray,
    val baseDatabase: KeePassDatabase,
    val baseBytes: ByteArray,
    val remoteDatabase: KeePassDatabase,
    val remoteBytes: ByteArray,
    val remoteStat: FileSourceStat,
    val fileSource: KeePassFileSource
)

internal data class RemoteKdbxVerification(
    val bytes: ByteArray,
    val database: KeePassDatabase,
    val revision: KeePassSourceRevision
) {
    val hash: String
        get() = revision.sha256
}

data class KeePassRemoteSyncResult(
    val databaseName: String,
    val message: String
)

private enum class KeePassPasswordSkipReason {
    MONICA_SECURE_ITEM,
    PURE_PASSKEY,
    TEMPLATE,
    EMPTY
}

private data class KeePassPasswordEntryAnalysis(
    val data: KeePassEntryData?,
    val skipReason: KeePassPasswordSkipReason?,
    val hasPasskeyFields: Boolean,
    val hasTitle: Boolean,
    val hasUsername: Boolean,
    val hasPassword: Boolean,
    val hasUrl: Boolean,
    val hasNotes: Boolean,
    val fieldNames: List<String>
)

internal fun extractKeePassCustomFieldsForPasswordEntry(
    fields: List<KeePassRawStringField>,
    preserveOpaqueMetadata: Boolean = false
): List<KeePassCustomFieldData> {
    return fields
        .asSequence()
        .filter { field ->
            val normalizedKey = field.key.trim()
            normalizedKey.isNotBlank() &&
                (!isReservedKeePassPasswordFieldName(normalizedKey) ||
                    (preserveOpaqueMetadata && (normalizedKey.startsWith("Monica") ||
                        KeePassFieldRegistry.roleOf(normalizedKey) == takagi.ru.monica.keepass.KeePassFieldRole.KEEPASS_PASSKEY)))
        }
        .mapIndexed { index, field ->
            KeePassCustomFieldData(
                title = field.key,
                value = field.value,
                isProtected = field.isProtected,
                sortOrder = index
            )
        }
        .toList()
}

private fun isReservedKeePassPasswordFieldName(key: String): Boolean {
    val normalized = key.trim()
    if (normalized.isBlank()) return true
    return KeePassFieldRegistry.isReservedPasswordProjectionField(normalized)
}

class KeePassKdbxService(
    private val context: Context,
    private val dao: LocalKeePassDatabaseDao,
    private val securityManager: SecurityManager
) {
    private val strings = AppLocaleStringResolver(context)
    private val imageManager by lazy { ImageManager(context.applicationContext) }
    private val keyFileStore by lazy { KeePassKeyFileStore(context.applicationContext) }
    private val credentialTransitionStore by lazy {
        KeePassCredentialTransitionStore(
            context = context.applicationContext,
            securityManager = securityManager,
            keyFileStore = keyFileStore
        )
    }
    private val accessPolicy: KeePassDatabaseAccessPolicy by lazy {
        SharedPreferencesKeePassDatabaseAccessPolicy(context.applicationContext)
    }
    private val nativeMutation = KeePassNativeMutation()
    private val recoveryStore by lazy {
        KeePassRecoveryStore(File(context.filesDir, "keepass_recovery"), strings = strings)
    }

    companion object {
        private const val TAG = "KeePassKdbxService"
        // Keep unknown-source cache short, but keep known internal files effectively "always warm".
        private const val UNKNOWN_SOURCE_CACHE_TTL_MS = 60_000L
        private const val PENDING_CHANGE_UPLOAD_RETRY_DELAY_MILLIS = 30_000L
        // Keep only the active database plus a tiny LRU warm set. Monica users may register many KDBX files.
        private const val MAX_WARM_CACHED_DATABASES = 2
        // Disable post-write full decode verification for normal writes to reduce save latency.
        // The database is still encoded by the library and written atomically/with rollback paths.
        private const val ENABLE_POST_WRITE_DECODE_VERIFICATION = false
        private const val MIB_BYTES = 1024L * 1024L
        private const val FIELD_MONICA_LOCAL_ID = "MonicaLocalId"
        private const val FIELD_MONICA_CONFLICT_COPY = "MonicaConflictCopy"
        private const val FIELD_MONICA_ITEM_ID = "MonicaSecureItemId"
        private const val FIELD_MONICA_TOTP_DATA = "MonicaTotpData"
        private const val FIELD_MONICA_ITEM_TYPE = "MonicaItemType"
        private const val FIELD_MONICA_ITEM_DATA = "MonicaItemData"
        private const val FIELD_MONICA_IMAGE_PATHS = "MonicaImagePaths"
        private const val FIELD_MONICA_IS_FAVORITE = "MonicaIsFavorite"
        private const val FIELD_BANK_NAME = "Bank Name"
        private const val FIELD_CARD_TYPE = "Card Type"
        private const val FIELD_BILLING_ADDRESS = "Billing Address"
        private const val FIELD_BRAND = "Brand"
        private const val FIELD_NICKNAME = "Nickname"
        private const val FIELD_VALID_FROM_MONTH = "Valid From Month"
        private const val FIELD_VALID_FROM_YEAR = "Valid From Year"
        private const val FIELD_PIN = "PIN"
        private const val FIELD_IBAN = "IBAN"
        private const val FIELD_SWIFT_BIC = "SWIFT/BIC"
        private const val FIELD_ROUTING_NUMBER = "Routing Number"
        private const val FIELD_ACCOUNT_NUMBER = "Account Number"
        private const val FIELD_BRANCH_CODE = "Branch Code"
        private const val FIELD_CURRENCY = "Currency"
        private const val FIELD_CUSTOMER_SERVICE_PHONE = "Customer Service Phone"
        private const val FIELD_MONICA_PASSKEY_CREDENTIAL_ID = "MonicaPasskeyCredentialId"
        private const val FIELD_MONICA_PASSKEY_DATA = "MonicaPasskeyData"
        private const val FIELD_MONICA_PASSKEY_MODE = "MonicaPasskeyMode"
        private const val FIELD_MONICA_SSH_ALGORITHM = "MonicaSshAlgorithm"
        private const val FIELD_MONICA_SSH_KEY_SIZE = "MonicaSshKeySize"
        private const val FIELD_MONICA_SSH_PUBLIC_KEY = "MonicaSshPublicKey"
        private const val FIELD_MONICA_SSH_PRIVATE_KEY = "MonicaSshPrivateKey"
        private const val FIELD_MONICA_SSH_FINGERPRINT = "MonicaSshFingerprint"
        private const val FIELD_MONICA_SSH_COMMENT = "MonicaSshComment"
        private const val REMOTE_CONFLICT_TITLE_SUFFIX = "[远端冲突副本]"
        private const val FIELD_MONICA_SSH_FORMAT = "MonicaSshFormat"
        private const val FIELD_KEEPASS_XC_TEMPLATE = "_etm_template"
        // === WIFI 条目互通字段 ===
        // `SSID` 是 keepass2android 官方 WLan 模板的 `TemplateField_WLan_SSID`，
        // 同时也是 KeeWeb/KeePassXC 社区常见命名；把它作为"标准字段"写入以保证
        // 其他 KeePass 客户端能读懂。
        private const val FIELD_WIFI_SSID = "SSID"
        // Monica 扩展元数据（安全类型、代理、IP 等）。只有 Monica 自己解析；
        // 其它客户端看到的是一段 JSON 字符串，不影响它们的常规使用。
        private const val FIELD_MONICA_WIFI_DATA = "MonicaWifiData"
        // 快速识别标志；与 PasswordEntry.loginType 对应，避免读回来时要解析 JSON
        // 才能判断是不是 WIFI 条目。值为 "WIFI"/"SSO"/"PASSWORD"。
        private const val FIELD_MONICA_LOGIN_TYPE = "MonicaLoginType"
        private const val FIELD_APP_PACKAGE_NAME = "App Package Name"
        private const val FIELD_APP_NAME = "App Name"
        private const val FIELD_EMAIL = "Email"
        private const val FIELD_PHONE = "Phone"
        private const val FIELD_ADDRESS_LINE = "Address"
        private const val FIELD_CITY = "City"
        private const val FIELD_STATE = "State"
        private const val FIELD_POSTAL_CODE = "Postal Code"
        private const val FIELD_COUNTRY = "Country"
        private const val FIELD_CARD_NUMBER = "Card Number"
        private const val FIELD_CARD_HOLDER = "Card Holder"
        private const val FIELD_CARD_EXPIRY = "Card Expiry"
        private const val FIELD_CARD_CVV = "Card CVV"
        private const val FIELD_SSO_PROVIDER = "SSO Provider"
        private const val FIELD_MONICA_SSO_REF_ENTRY_ID = "MonicaSsoRefEntryId"
        // kotpass decode 在并发下可能触发 native 崩溃，必须跨实例串行化。
        private val globalDecodeMutex = Mutex()
        // 部分设备/ABI 下 decode 在不同工作线程切换时更易触发 native 崩溃，固定到单线程执行更稳。
        private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "KeePassDecodeThread").apply { isDaemon = true }
        }
        private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()
        // 同一个远端 KeePass 数据库只能有一个真实上传执行者。
        private val remoteUploadMutexes = mutableMapOf<Long, Mutex>()
        // 写入采用“读-改-写”原子化；不同数据库可独立排队，跨库操作按 ID 顺序加锁。
        private val databaseMutationMutexes = mutableMapOf<Long, Mutex>()
        // 同一数据库首次打开时只允许一个入口执行解码，避免验证/读取并发触发重复开库。
        private val databaseLoadMutexes = mutableMapOf<Long, Mutex>()
        // 按数据库 ID 维护进程级已解锁会话；不同调用入口共享同一次 KDBX 解码结果。
        private val loadedDatabaseCache = LinkedHashMap<Long, CachedLoadedDatabase>(4, 0.75f, true)
        private val nativeSessionCache = KeePassNativeSessionCache { databaseId, sourceRevision, database ->
            KeePassNativeSessionBuilder.build(
                databaseId = databaseId,
                sourceRevision = sourceRevision,
                database = database,
                pathKeyBuilder = ::buildKeePassPathKey
            )
        }
        private val nativeProjectionBundleCache = KeePassNativeProjectionBundleCache()
        private val projectionIndexGate = KeePassProjectionIndexGate()
        private var activeDatabaseId: Long? = null
        // 跨实例缓存失效信号：某实例更新数据库绑定后，其他实例的本地缓存应立即失效。
        private val externallyInvalidatedDatabaseIds = mutableSetOf<Long>()

        suspend fun <T> withGlobalDecodeLock(block: () -> T): T {
            return globalDecodeMutex.withLock { block() }
        }

        private fun mutationMutexForDatabase(databaseId: Long): Mutex {
            return synchronized(databaseMutationMutexes) {
                databaseMutationMutexes.getOrPut(databaseId) { Mutex() }
            }
        }

        private fun loadMutexForDatabase(databaseId: Long): Mutex {
            return synchronized(databaseLoadMutexes) {
                databaseLoadMutexes.getOrPut(databaseId) { Mutex() }
            }
        }

        private fun remoteUploadMutex(databaseId: Long): Mutex {
            return synchronized(remoteUploadMutexes) {
                remoteUploadMutexes.getOrPut(databaseId) { Mutex() }
            }
        }

        private suspend fun <T> withDatabaseMutationLocks(
            databaseIds: List<Long>,
            block: suspend () -> T
        ): T {
            val mutexes = databaseIds.distinct().sorted().map { mutationMutexForDatabase(it) }
            suspend fun lockAt(index: Int): T {
                return if (index >= mutexes.size) {
                    block()
                } else {
                    mutexes[index].withLock { lockAt(index + 1) }
                }
            }
            return lockAt(0)
        }

        @Synchronized
        fun invalidateProcessCache(databaseId: Long) {
            synchronized(loadedDatabaseCache) {
                loadedDatabaseCache.remove(databaseId)
                if (activeDatabaseId == databaseId) {
                    activeDatabaseId = null
                }
            }
            nativeSessionCache.invalidate(databaseId)
            nativeProjectionBundleCache.invalidate(databaseId)
            projectionIndexGate.invalidate(databaseId)
            externallyInvalidatedDatabaseIds += databaseId
        }

        fun markDatabaseActive(databaseId: Long) {
            synchronized(loadedDatabaseCache) {
                activeDatabaseId = databaseId
                trimLoadedDatabaseCacheLocked()
            }
        }

        fun clearActiveDatabase(databaseId: Long? = null) {
            synchronized(loadedDatabaseCache) {
                if (databaseId == null || activeDatabaseId == databaseId) {
                    activeDatabaseId = null
                }
                trimLoadedDatabaseCacheLocked()
            }
        }

        fun trimInactiveCaches() {
            synchronized(loadedDatabaseCache) {
                trimLoadedDatabaseCacheLocked()
            }
        }

        private fun trimLoadedDatabaseCacheLocked() {
            val activeId = activeDatabaseId
            var warmCount = loadedDatabaseCache.keys.count { it != activeId }
            val iterator = loadedDatabaseCache.entries.iterator()
            while (warmCount > MAX_WARM_CACHED_DATABASES && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == activeId) continue
                val evictedDatabaseId = entry.key
                iterator.remove()
                nativeSessionCache.invalidate(evictedDatabaseId)
                nativeProjectionBundleCache.invalidate(evictedDatabaseId)
                projectionIndexGate.invalidate(evictedDatabaseId)
                warmCount--
            }
        }

        @Synchronized
        private fun consumeProcessCacheInvalidation(databaseId: Long): Boolean {
            return externallyInvalidatedDatabaseIds.remove(databaseId)
        }

        fun inferCreationOptions(keePassDatabase: KeePassDatabase): KeePassDatabaseCreationOptions {
            val resolved = when (keePassDatabase) {
                is KeePassDatabase.Ver3x -> KeePassDatabaseCreationOptions(
                    formatVersion = KeePassFormatVersion.KDBX3,
                    cipherAlgorithm = resolveCipherAlgorithm(keePassDatabase.header.cipherId),
                    kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF,
                    transformRounds = keePassDatabase.header.transformRounds.toLong(),
                    memoryBytes = KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES,
                    parallelism = 1
                )
                is KeePassDatabase.Ver4x -> {
                    val kdf = keePassDatabase.header.kdfParameters
                    when (kdf) {
                        is KdfParameters.Aes -> KeePassDatabaseCreationOptions(
                            formatVersion = KeePassFormatVersion.KDBX4,
                            cipherAlgorithm = resolveCipherAlgorithm(keePassDatabase.header.cipherId),
                            kdfAlgorithm = KeePassKdfAlgorithm.AES_KDF,
                            transformRounds = kdf.rounds.toLong(),
                            memoryBytes = KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES,
                            parallelism = 1
                        )
                        is KdfParameters.Argon2 -> KeePassDatabaseCreationOptions(
                            formatVersion = KeePassFormatVersion.KDBX4,
                            cipherAlgorithm = resolveCipherAlgorithm(keePassDatabase.header.cipherId),
                            kdfAlgorithm = if (kdf.variant == KdfParameters.Argon2.Variant.Argon2id) {
                                KeePassKdfAlgorithm.ARGON2ID
                            } else {
                                KeePassKdfAlgorithm.ARGON2D
                            },
                            transformRounds = kdf.iterations.toLong(),
                            memoryBytes = kdf.memory.toLong(),
                            parallelism = kdf.parallelism.toInt()
                        )
                    }
                }
            }
            return resolved.normalized()
        }

        private fun resolveCipherAlgorithm(cipherId: UUID): KeePassCipherAlgorithm {
            return when (cipherId) {
                BaseCiphers.Aes.uuid -> KeePassCipherAlgorithm.AES
                BaseCiphers.ChaCha20.uuid -> KeePassCipherAlgorithm.CHACHA20
                TwofishCipher.uuid -> KeePassCipherAlgorithm.TWOFISH
                else -> KeePassCipherAlgorithm.AES
            }
        }
    }
    
    private data class LoadedDatabase(
        val database: LocalKeePassDatabase,
        val credentials: Credentials,
        val keePassDatabase: KeePassDatabase,
        val nativeSession: Lazy<KeePassNativeSession>,
        val nativeBrowser: Lazy<KeePassNativeBrowserSnapshot>,
        val projectionBundle: Lazy<KeePassNativeProjectionBundle>,
        val sourceEtag: String?,
        val sourceLastModified: String?,
        val sourceSignature: DatabaseSourceSignature?,
        val sourceRevision: KeePassSourceRevision
    )

    private data class CachedLoadedDatabase(
        val loaded: LoadedDatabase,
        val cachedAtMs: Long
    )

    private data class DatabaseSourceSignature(
        val sizeBytes: Long,
        val lastModifiedEpochMs: Long
    )

    private data class DatabaseSnapshot(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val signature: DatabaseSourceSignature?,
        val revision: KeePassSourceRevision
    )

    /**
     * Re-openable KDBX source.  The parser consumes a stream and the source
     * keeps only a digest and a small header in memory; large database bytes
     * remain on disk or in the provider stream.
     */
    private class DatabaseStreamSource(
        val openStream: () -> InputStream,
        val header: ByteArray,
        val revision: KeePassSourceRevision,
        val etag: String? = null,
        val lastModified: String? = null,
        private val temporaryFile: File? = null,
    ) : AutoCloseable {
        fun readBytes(): ByteArray = openStream().use { it.readBytes() }

        override fun close() {
            temporaryFile?.let { file -> runCatching { file.delete() } }
        }
    }

    private class EncodedDatabaseArtifact(
        val file: File,
        val revision: KeePassSourceRevision,
    ) : AutoCloseable {
        fun readBytes(): ByteArray = file.readBytes()
        override fun close() {
            runCatching { file.delete() }
        }
    }

    private data class MutationPlan<T>(
        val updatedDatabase: KeePassDatabase,
        val result: T,
        val beforeRemoteUpload: (suspend (LocalKeePassDatabase, KeePassSourceRevision) -> Unit)? = null,
        val afterWrite: (suspend (LocalKeePassDatabase, KeePassDatabase) -> Unit)? = null
    )

    private data class CredentialsResolution(
        val candidates: List<KeePassCredentialCandidate>
    )

    suspend fun verifyDatabase(
        databaseId: Long,
        passwordOverride: String? = null,
        keyFileUriOverride: Uri? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val diagnostics = inspectDatabase(
                databaseId = databaseId,
                passwordOverride = passwordOverride,
                keyFileUriOverride = keyFileUriOverride
            ).getOrElse { throw it }
            Result.success(diagnostics.entryCount)
        } catch (e: Exception) {
            val mapped = normalizeError(e)
            val code = (mapped as? KeePassOperationException)?.code ?: KeePassErrorCode.IO_READ_WRITE_FAILED
            Log.e(TAG, "verifyDatabase failed (databaseId=$databaseId, code=$code)", mapped)
            Result.failure(mapped)
        }
    }

    suspend fun verifyExternalDatabase(
        fileUri: Uri,
        password: String,
        keyFileUri: Uri? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val diagnostics = inspectExternalDatabase(
                fileUri = fileUri,
                password = password,
                keyFileUri = keyFileUri
            ).getOrElse { throw it }
            Result.success(diagnostics.entryCount)
        } catch (e: Exception) {
            val mapped = normalizeError(e)
            val code = (mapped as? KeePassOperationException)?.code ?: KeePassErrorCode.IO_READ_WRITE_FAILED
            Log.e(
                TAG,
                "verifyExternalDatabase failed (uri=$fileUri, keyFile=${keyFileUri != null}, code=$code)",
                mapped
            )
            Result.failure(mapped)
        }
    }

    suspend fun inspectDatabase(
        databaseId: Long,
        passwordOverride: String? = null,
        keyFileUriOverride: Uri? = null
    ): Result<KeePassDatabaseDiagnostics> = withContext(Dispatchers.IO) {
        try {
            if (passwordOverride == null && keyFileUriOverride == null) {
                val loaded = getCachedLoadedDatabase(databaseId) ?: loadDatabase(databaseId)
                return@withContext Result.success(buildDiagnostics(loaded.keePassDatabase))
            }
            val database = dao.getDatabaseById(databaseId) ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
            val credentials = buildCredentials(
                database,
                passwordOverride = passwordOverride,
                keyFileUriOverride = keyFileUriOverride
            )
            openDatabaseStreamSource(database).use { source ->
                val (keePassDatabase, _) = decodeDatabaseWithFallback(
                    source = source,
                    credentialsResolution = credentials,
                    sourceLabel = "databaseId=$databaseId",
                    sourceName = database.resolvedActiveFilePath(),
                )
                Result.success(buildDiagnostics(keePassDatabase))
            }
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun inspectExternalDatabase(
        fileUri: Uri,
        password: String,
        keyFileUri: Uri? = null
    ): Result<KeePassDatabaseDiagnostics> = withContext(Dispatchers.IO) {
        try {
            val credentials = buildCredentialsFromRaw(password = password, keyFileUri = keyFileUri)
            openUriStreamSource(fileUri, strings.get(R.string.storage_error_open_database)).use { source ->
                val (keePassDatabase, _) = decodeDatabaseWithFallback(
                    source = source,
                    credentialsResolution = credentials,
                    sourceLabel = "uri=$fileUri",
                    sourceName = fileUri.lastPathSegment ?: fileUri.toString(),
                )
                Result.success(buildDiagnostics(keePassDatabase))
            }
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun syncRemoteDatabase(databaseId: Long): Result<KeePassRemoteSyncResult> = withContext(Dispatchers.IO) {
        withRemoteDatabaseLocks(databaseId) { syncRemoteDatabaseLocked(databaseId) }
    }

    private suspend fun syncRemoteDatabaseLocked(databaseId: Long): Result<KeePassRemoteSyncResult> {
        return try {
            val database = dao.getDatabaseById(databaseId)
                ?: throw IOException(strings.get(R.string.keepass_connection_status_missing))
            val syncedDatabaseName = database.name
            if (!database.isRemoteSource() || database.sourceId == null) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_not_remote))
            }

            val remoteDb = PasswordDatabase.getDatabase(context)
            val remoteSourceDao = remoteDb.keepassRemoteSourceDao()
            val syncStateDao = remoteDb.keepassRemoteSyncStateDao()
            val syncService = RemoteKeePassSyncService(
                databaseDao = dao,
                remoteSourceDao = remoteSourceDao,
                syncStateDao = syncStateDao,
                strings = strings,
            )
            val remoteSource = remoteSourceDao.getSourceById(database.sourceId)
                ?: throw IllegalStateException(strings.get(R.string.keepass_error_remote_source_missing))
            val fileSource = createRemoteFileSource(database, remoteSource)
            val workingPath = database.workingCopyPath ?: throw IllegalStateException(strings.get(R.string.keepass_error_working_copy_missing))
            val workingFile = File(context.filesDir, workingPath)
            if (!workingFile.exists()) {
                throw IllegalStateException(strings.get(R.string.keepass_error_working_copy_missing))
            }

            val workingBytes = workingFile.readBytes()
            val workingHash = GoogleDriveKeePassSupport.sha256Hex(workingBytes)
            val syncState = syncStateDao.getState(databaseId)
            val baseHash = syncState?.baseHash
            syncService.markComparing(databaseId, workingHash)

            val remoteStat = runCatching { fileSource.stat() }.getOrDefault(FileSourceStat())
            val remoteBytes = fileSource.read()
            val remoteHash = GoogleDriveKeePassSupport.sha256Hex(remoteBytes)
            val localHasChanges = if (baseHash.isNullOrBlank()) {
                workingHash != remoteHash
            } else {
                baseHash != workingHash
            }
            val remoteHasChanges = if (baseHash.isNullOrBlank()) {
                workingHash != remoteHash
            } else {
                baseHash != remoteHash
            }

            Log.i(
                TAG,
                "KeePass remote sync compare databaseId=$databaseId source=${database.sourceType} localHash=${workingHash.take(12)} remoteHash=${remoteHash.take(12)} baseHash=${baseHash?.take(12)} localChanged=$localHasChanges remoteChanged=$remoteHasChanges remoteVersion=${(remoteStat.etag ?: remoteStat.versionToken).orEmpty()}"
            )

            if (remoteHasChanges) {
                syncService.markDownloading(databaseId, workingHash)
                if (!localHasChanges) {
                    OneDriveKeePassSupport.writeRelativeFile(context, workingPath, remoteBytes)
                    database.cacheCopyPath?.let { cachePath ->
                        OneDriveKeePassSupport.writeRelativeFile(context, cachePath, remoteBytes)
                    }
                    syncService.markSynchronized(
                        databaseId = databaseId,
                        versionToken = remoteStat.versionToken,
                        etag = remoteStat.etag,
                        baseHash = remoteHash,
                        workingHash = remoteHash
                    )
                    invalidateProcessCache(databaseId)
                    return Result.success(
                        KeePassRemoteSyncResult(syncedDatabaseName, strings.get(R.string.keepass_sync_pulled))
                    )
                }

                val conflictMessage = strings.get(R.string.keepass_sync_both_changed)
                syncService.markConflict(
                    databaseId = databaseId,
                    workingHash = workingHash,
                    failureMessage = conflictMessage
                )
                throw IllegalStateException(conflictMessage)
            }

            if (!localHasChanges) {
                syncService.markSynchronized(
                    databaseId = databaseId,
                    versionToken = remoteStat.versionToken,
                    etag = remoteStat.etag,
                    baseHash = remoteHash,
                    workingHash = workingHash
                )
                return Result.success(KeePassRemoteSyncResult(syncedDatabaseName, strings.get(R.string.keepass_sync_up_to_date)))
            }

            syncService.markUploadInProgress(databaseId, workingHash)
            val writeResult = try {
                fileSource.write(
                    workingBytes,
                    expectedVersion = syncState?.remoteEtag ?: syncState?.remoteVersionToken
                )
            } catch (error: Exception) {
                if (isRemoteVersionConflict(error)) {
                    syncService.markConflict(databaseId, workingHash, error.message ?: strings.get(R.string.keepass_sync_remote_changed))
                }
                throw error
            }
            val verifiedRemote = verifyRemoteKdbxWrite(
                database = database,
                fileSource = fileSource,
                credentials = loadDatabase(databaseId).credentials,
                expectedBytes = workingBytes,
                sourceLabel = "service-sync-upload"
            )
            database.cacheCopyPath?.let { cachePath ->
                OneDriveKeePassSupport.writeRelativeFile(context, cachePath, verifiedRemote.bytes)
            }
            syncService.markSynchronized(
                databaseId = databaseId,
                versionToken = writeResult.versionToken,
                etag = writeResult.etag,
                baseHash = verifiedRemote.hash,
                workingHash = verifiedRemote.hash
            )
            invalidateProcessCache(databaseId)
            Result.success(KeePassRemoteSyncResult(syncedDatabaseName, strings.get(R.string.keepass_sync_complete)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    private fun buildDiagnostics(keePassDatabase: KeePassDatabase): KeePassDatabaseDiagnostics {
        val (entries, hasRecycleBinMeta) = collectEntryContexts(keePassDatabase)
        val resolutionContext = KeePassFieldReferenceResolver.buildContext(entries.map { it.entry })
        val count = entries.count { context ->
            entryToData(
                entry = context.entry,
                groupPath = context.groupPath,
                groupUuid = context.groupUuid,
                isInRecycleBinByMeta = context.isInRecycleBinByMeta,
                hasRecycleBinMeta = hasRecycleBinMeta,
                resolutionContext = resolutionContext
            ) != null
        }
        return KeePassDatabaseDiagnostics(
            entryCount = count,
            creationOptions = inferCreationOptions(keePassDatabase)
        )
    }

    suspend fun createGroup(
        databaseId: Long,
        groupName: String,
        parentPath: String? = null
    ): Result<KeePassGroupInfo> = withContext(Dispatchers.IO) {
        try {
            val normalizedName = groupName.trim()
            if (normalizedName.isBlank()) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_name_required))
            }
            val groupInfo = mutateDatabase(databaseId) { loaded ->
                val parentSegments = decodeKeePassPathSegments(parentPath)
                val parentPathKey = parentPath?.takeIf { it.isNotBlank() }
                val parentGroupUuid = parentPathKey?.let {
                    findGroupUuidByPath(
                        group = loaded.keePassDatabase.content.group,
                        currentPathKey = null,
                        targetPathKey = it
                    )
                } ?: loaded.keePassDatabase.content.group.uuid
                val targetPath = buildKeePassPathKey(parentPathKey, normalizedName)
                val existedBefore = findGroupUuidByPath(
                    group = loaded.keePassDatabase.content.group,
                    currentPathKey = null,
                    targetPathKey = targetPath
                ) != null
                val result = addGroupToPath(
                    group = loaded.keePassDatabase.content.group,
                    parentSegments = parentSegments,
                    newGroupName = normalizedName,
                    currentPathKey = ""
                )
                val changeSet = if (!existedBefore) {
                    KeePassChangeSet(
                        databaseId = loaded.database.id,
                        target = KeePassChangeTarget.GROUP,
                        operation = KeePassChangeOperation.CREATE_GROUP,
                        entryUuid = null,
                        baseFingerprint = null,
                        baseGroupPath = parentPathKey,
                        baseGroupUuid = parentGroupUuid.toString(),
                        structurePatch = KeePassStructureChangePatch(
                            sourceGroupPath = result.second.path,
                            sourceGroupUuid = result.second.uuid,
                            targetGroupPath = parentPathKey,
                            targetGroupUuid = parentGroupUuid.toString(),
                            groupName = normalizedName
                        )
                    )
                } else {
                    null
                }
                val updatedDatabase = changeSet?.let { change ->
                    KeePassChangeSetApplier().apply(loaded.keePassDatabase, change).updatedDatabase
                } ?: loaded.keePassDatabase
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = result.second,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOfNotNull(changeSet), workingRevision)
                    }
                )
            }
            Result.success(groupInfo)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun renameGroup(
        databaseId: Long,
        groupPath: String,
        newName: String
    ): Result<KeePassGroupInfo> = withContext(Dispatchers.IO) {
        try {
            val normalizedName = newName.trim()
            if (normalizedName.isBlank()) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_name_required))
            }
            val pathSegments = decodeKeePassPathSegments(groupPath)
            if (pathSegments.isEmpty()) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_path))
            }
            val groupInfo = mutateDatabase(databaseId) { loaded ->
                val targetGroupUuid = findGroupUuidByPath(
                    group = loaded.keePassDatabase.content.group,
                    currentPathKey = null,
                    targetPathKey = groupPath
                ) ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                val result = renameGroupByPath(
                    group = loaded.keePassDatabase.content.group,
                    pathSegments = pathSegments,
                    newName = normalizedName,
                    currentPathKey = ""
                )
                val changeSet = KeePassChangeSet(
                    databaseId = loaded.database.id,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.RENAME_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = groupPath,
                    baseGroupUuid = targetGroupUuid.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = groupPath,
                        sourceGroupUuid = targetGroupUuid.toString(),
                        targetGroupPath = result.second.path,
                        targetGroupUuid = targetGroupUuid.toString(),
                        newGroupName = normalizedName
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = result.second,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(groupInfo)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun deleteGroup(
        databaseId: Long,
        groupPath: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val pathSegments = decodeKeePassPathSegments(groupPath)
            if (pathSegments.isEmpty()) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_path))
            }
            mutateDatabase(databaseId) { loaded ->
                val targetGroupUuid = findGroupUuidByPath(
                    group = loaded.keePassDatabase.content.group,
                    currentPathKey = null,
                    targetPathKey = groupPath
                ) ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                val result = removeGroupByPath(
                    group = loaded.keePassDatabase.content.group,
                    pathSegments = pathSegments
                )
                if (!result.second) {
                    throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                }
                val changeSet = KeePassChangeSet(
                    databaseId = loaded.database.id,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.DELETE_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = groupPath,
                    baseGroupUuid = targetGroupUuid.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = groupPath,
                        sourceGroupUuid = targetGroupUuid.toString()
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun moveGroup(
        sourceDatabaseId: Long,
        groupPath: String,
        targetDatabaseId: Long,
        targetParentPath: String? = null
    ): Result<KeePassGroupInfo> = withContext(Dispatchers.IO) {
        try {
            val sourcePathSegments = decodeKeePassPathSegments(groupPath)
            if (sourcePathSegments.isEmpty()) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_path))
            }

            val normalizedSourcePath = groupPath.trim()
            val normalizedTargetParentPath = targetParentPath?.trim()?.takeIf { it.isNotBlank() }
            val sourceParentPath = normalizedSourcePath.substringBeforeLast('/', "")
                .ifBlank { null }
            if (sourceDatabaseId == targetDatabaseId &&
                normalizedTargetParentPath == sourceParentPath
            ) {
                val movedGroup = listGroups(sourceDatabaseId).getOrThrow()
                    .firstOrNull { it.path == normalizedSourcePath }
                    ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                return@withContext Result.success(movedGroup)
            }

            if (sourceDatabaseId == targetDatabaseId &&
                !normalizedTargetParentPath.isNullOrBlank() &&
                (normalizedTargetParentPath == normalizedSourcePath ||
                    normalizedTargetParentPath.startsWith("$normalizedSourcePath/"))
            ) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_child))
            }

            val movedGroupInfo = withDatabaseMutationLocks(listOf(sourceDatabaseId, targetDatabaseId)) {
                try {
                    val sourceLoaded = getCachedLoadedDatabase(sourceDatabaseId) ?: loadDatabase(sourceDatabaseId)
                    val targetLoaded = if (sourceDatabaseId == targetDatabaseId) {
                        sourceLoaded
                    } else {
                        getCachedLoadedDatabase(targetDatabaseId) ?: loadDatabase(targetDatabaseId)
                    }
                    val sourceGroupUuid = findGroupUuidByPath(
                        group = sourceLoaded.keePassDatabase.content.group,
                        currentPathKey = null,
                        targetPathKey = normalizedSourcePath
                    ) ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                    val sourceParentGroupUuid = sourceParentPath?.let {
                        findGroupUuidByPath(
                            group = sourceLoaded.keePassDatabase.content.group,
                            currentPathKey = null,
                            targetPathKey = it
                        )
                    } ?: sourceLoaded.keePassDatabase.content.group.uuid
                    val targetParentGroupUuid = if (sourceDatabaseId == targetDatabaseId) {
                        normalizedTargetParentPath?.let {
                            findGroupUuidByPath(
                                group = sourceLoaded.keePassDatabase.content.group,
                                currentPathKey = null,
                                targetPathKey = it
                            )
                        } ?: sourceLoaded.keePassDatabase.content.group.uuid
                    } else {
                        normalizedTargetParentPath?.let {
                            findGroupUuidByPath(
                                group = targetLoaded.keePassDatabase.content.group,
                                currentPathKey = null,
                                targetPathKey = it
                            )
                        } ?: targetLoaded.keePassDatabase.content.group.uuid
                    }

                    val extracted = extractGroupByPath(
                        group = sourceLoaded.keePassDatabase.content.group,
                        pathSegments = sourcePathSegments
                    )
                    if (extracted.removedGroup == null) {
                        throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, groupPath))
                    }

                    val targetParentSegments = decodeKeePassPathSegments(normalizedTargetParentPath)
                    val groupToMove = extracted.removedGroup
                    val targetRootBeforeInsert = if (sourceDatabaseId == targetDatabaseId) {
                        extracted.updatedGroup
                    } else {
                        targetLoaded.keePassDatabase.content.group
                    }
                    val existingTargetPath = if (sourceDatabaseId == targetDatabaseId) {
                        null
                    } else {
                        findGroupPathByUuid(
                            group = targetRootBeforeInsert,
                            currentPathKey = null,
                            targetUuid = groupToMove.uuid
                        )
                    }
                    val inserted = if (existingTargetPath != null) {
                        val expectedTargetPath = buildKeePassPathKey(normalizedTargetParentPath, groupToMove.name)
                        if (existingTargetPath != expectedTargetPath) {
                            throw IllegalArgumentException(strings.get(R.string.keepass_error_group_uuid, existingTargetPath))
                        }
                        InsertedGroupResult(
                            updatedGroup = targetRootBeforeInsert,
                            groupInfo = buildGroupInfoFromPath(groupToMove, existingTargetPath)
                        )
                    } else {
                        insertGroupToPath(
                            group = targetRootBeforeInsert,
                            parentSegments = targetParentSegments,
                            groupToInsert = groupToMove,
                            currentPathKey = ""
                        )
                    }
                    val groupTreePatch = if (sourceDatabaseId == targetDatabaseId) {
                        null
                    } else {
                        buildGroupTreeChangePatch(
                            database = sourceLoaded.keePassDatabase,
                            root = groupToMove,
                            sourceRootGroupUuid = sourceGroupUuid,
                            targetParentGroupUuid = targetParentGroupUuid
                        )
                    }

                    if (sourceDatabaseId == targetDatabaseId) {
                        val moveChangeSet = KeePassChangeSet(
                            databaseId = sourceLoaded.database.id,
                            target = KeePassChangeTarget.GROUP,
                            operation = KeePassChangeOperation.MOVE_GROUP,
                            entryUuid = null,
                            baseFingerprint = null,
                            baseGroupPath = sourceParentPath,
                            baseGroupUuid = sourceParentGroupUuid.toString(),
                            structurePatch = KeePassStructureChangePatch(
                                sourceGroupPath = normalizedSourcePath,
                                sourceGroupUuid = sourceGroupUuid.toString(),
                                targetGroupPath = normalizedTargetParentPath,
                                targetGroupUuid = targetParentGroupUuid.toString(),
                                groupName = groupToMove.name
                            )
                        )
                        val updatedDatabase = KeePassChangeSetApplier().apply(
                            sourceLoaded.keePassDatabase,
                            moveChangeSet
                        ).updatedDatabase
                        writeDatabase(
                            database = sourceLoaded.database,
                            credentials = sourceLoaded.credentials,
                            keePassDatabase = updatedDatabase,
                            sourceEtag = sourceLoaded.sourceEtag,
                            sourceLastModified = sourceLoaded.sourceLastModified,
                            expectedSourceRevision = sourceLoaded.sourceRevision,
                            beforeRemoteUpload = { database, workingRevision ->
                                enqueuePendingChangeSetsIfRemote(database, listOf(moveChangeSet), workingRevision)
                            }
                        )
                    } else {
                        // Write the target first; if source cleanup fails, preserving both trees is
                        // safer than deleting source data before the target is durable. When retrying
                        // after a target-success/source-failure split, skip target insertion if the
                        // same group UUID already exists at the intended target path.
                        if (existingTargetPath == null) {
                            val createTreeChangeSet = KeePassChangeSet(
                                databaseId = targetLoaded.database.id,
                                target = KeePassChangeTarget.GROUP,
                                operation = KeePassChangeOperation.CREATE_GROUP_TREE,
                                entryUuid = null,
                                baseFingerprint = null,
                                baseGroupPath = normalizedTargetParentPath,
                                baseGroupUuid = targetParentGroupUuid.toString(),
                                structurePatch = KeePassStructureChangePatch(
                                    targetGroupPath = normalizedTargetParentPath,
                                    targetGroupUuid = targetParentGroupUuid.toString(),
                                    groupName = groupToMove.name
                                ),
                                groupTreePatch = groupTreePatch
                            )
                            val targetDatabase = KeePassChangeSetApplier().apply(
                                targetLoaded.keePassDatabase,
                                createTreeChangeSet
                            ).updatedDatabase
                            writeDatabase(
                                database = targetLoaded.database,
                                credentials = targetLoaded.credentials,
                                keePassDatabase = targetDatabase,
                                sourceEtag = targetLoaded.sourceEtag,
                                sourceLastModified = targetLoaded.sourceLastModified,
                                expectedSourceRevision = targetLoaded.sourceRevision,
                                beforeRemoteUpload = { database, workingRevision ->
                                    enqueuePendingChangeSetsIfRemote(database, listOf(createTreeChangeSet), workingRevision)
                                }
                            )
                        }
                        val deleteTreeChangeSet = KeePassChangeSet(
                            databaseId = sourceLoaded.database.id,
                            target = KeePassChangeTarget.GROUP,
                            operation = KeePassChangeOperation.DELETE_GROUP_TREE,
                            entryUuid = null,
                            baseFingerprint = null,
                            baseGroupPath = sourceParentPath,
                            baseGroupUuid = sourceParentGroupUuid.toString(),
                            structurePatch = KeePassStructureChangePatch(
                                sourceGroupPath = normalizedSourcePath,
                                sourceGroupUuid = sourceGroupUuid.toString(),
                                groupName = groupToMove.name
                            ),
                            groupTreePatch = groupTreePatch
                        )
                        val sourceDatabase = KeePassChangeSetApplier().apply(
                            sourceLoaded.keePassDatabase,
                            deleteTreeChangeSet
                        ).updatedDatabase
                        writeDatabase(
                            database = sourceLoaded.database,
                            credentials = sourceLoaded.credentials,
                            keePassDatabase = sourceDatabase,
                            sourceEtag = sourceLoaded.sourceEtag,
                            sourceLastModified = sourceLoaded.sourceLastModified,
                            expectedSourceRevision = sourceLoaded.sourceRevision,
                            beforeRemoteUpload = { database, workingRevision ->
                                enqueuePendingChangeSetsIfRemote(database, listOf(deleteTreeChangeSet), workingRevision)
                            }
                        )
                    }

                    inserted.groupInfo
                } catch (e: Exception) {
                    invalidateLoadedDatabaseCache(sourceDatabaseId)
                    if (targetDatabaseId != sourceDatabaseId) {
                        invalidateLoadedDatabaseCache(targetDatabaseId)
                    }
                    throw e
                }
            }
            Result.success(movedGroupInfo)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun readPasswordEntries(databaseId: Long): Result<List<KeePassEntryData>> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val database = loaded.database
            val bundle = loaded.projectionBundle.value
            val data = buildPasswordEntryData(
                databaseId = database.id,
                databaseName = database.name,
                entries = bundle.entries,
                hasRecycleBinMeta = bundle.hasRecycleBinMeta,
                resolutionContext = bundle.resolutionContext
            )
            dao.updateEntryCount(database.id, bundle.entries.size)
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun loadWorkspace(
        databaseId: Long,
        includeRecycleBinGroups: Boolean = false,
        allowedSecureItemTypes: Set<ItemType>? = null
    ): Result<KeePassWorkspaceSnapshot> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val database = loaded.database
            val keePassDatabase = loaded.keePassDatabase
            val bundle = loaded.projectionBundle.value
            val passwords = buildPasswordEntryData(
                databaseId = database.id,
                databaseName = database.name,
                entries = bundle.entries,
                hasRecycleBinMeta = bundle.hasRecycleBinMeta,
                resolutionContext = bundle.resolutionContext
            )
            val secureItems = mutableListOf<KeePassSecureItemData>()
            bundle.entries.forEach { context ->
                entryToSecureItemData(
                    entry = context.entry,
                    keePassDatabase = keePassDatabase,
                    databaseId = databaseId,
                    groupPath = context.groupPath,
                    groupUuid = context.groupUuid,
                    isInRecycleBinByMeta = context.isInRecycleBinByMeta,
                    hasRecycleBinMeta = bundle.hasRecycleBinMeta,
                    allowedTypes = allowedSecureItemTypes,
                    resolutionContext = bundle.resolutionContext
                )?.let(secureItems::add)
            }
            val groups = bundle.groups(includeRecycleBinGroups)
            dao.updateEntryCount(database.id, bundle.entries.size)
            Result.success(
                KeePassWorkspaceSnapshot(
                    passwords = passwords,
                    secureItems = secureItems,
                    groups = groups,
                    sessionRevision = bundle.revisionToken
                )
            )
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun listGroups(
        databaseId: Long,
        includeRecycleBin: Boolean = false
    ): Result<List<KeePassGroupInfo>> = withContext(Dispatchers.IO) {
        try {
            val bundle = loadDatabase(databaseId).projectionBundle.value
            Result.success(bundle.groups(includeRecycleBin))
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun addOrUpdatePasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>,
        resolvePassword: (PasswordEntry) -> String,
        forceSyncWrite: Boolean = false,
        customFieldsByEntryId: Map<Long, List<KeePassCustomFieldData>> = emptyMap()
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val addedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                var updatedDatabase = loaded.keePassDatabase
                var addedCount = 0
                val pendingChangeSets = mutableListOf<KeePassChangeSet>()
                entries.forEach { entry ->
                    val plainPassword = resolvePassword(entry)
                    val customFields = customFieldsByEntryId[entry.id].orEmpty()
                    val updateResult = updateEntry(
                        keePassDatabase = updatedDatabase,
                        databaseId = loaded.database.id,
                        entry = entry,
                        plainPassword = plainPassword,
                        customFields = customFields
                    )
                    pendingChangeSets += updateResult.changeSets
                    if (updateResult.changed) {
                        updatedDatabase = updateResult.database
                    } else {
                        val newEntry = buildEntry(entry, plainPassword, customFields)
                        buildCreateEntryChangeSet(
                            database = updatedDatabase,
                            databaseId = loaded.database.id,
                            target = KeePassChangeTarget.PASSWORD,
                            entry = newEntry,
                            targetGroupPath = entry.keepassGroupPath
                        )?.let { pendingChangeSets += it }
                        val updatedRoot = addEntryToGroupPath(
                            rootGroup = updatedDatabase.content.group,
                            groupPath = entry.keepassGroupPath,
                            entry = newEntry
                        )
                        updatedDatabase = updatedDatabase.modifyParentGroup { updatedRoot }
                        addedCount++
                    }
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = addedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(addedCount)
        } catch (e: Exception) {
            Result.failure(logMutationFailure("addOrUpdatePasswordEntries", databaseId, e))
        }
    }

    suspend fun updatePasswordEntry(
        databaseId: Long,
        entry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                val plainPassword = resolvePassword(entry)
                val updateResult = updateEntry(
                    keePassDatabase = loaded.keePassDatabase,
                    databaseId = loaded.database.id,
                    entry = entry,
                    plainPassword = plainPassword,
                    customFields = customFields
                )
                val pendingChangeSets = updateResult.changeSets.toMutableList()
                val updatedDatabase = if (updateResult.changed) updateResult.database else {
                    val newEntry = buildEntry(entry, plainPassword, customFields)
                    buildCreateEntryChangeSet(
                        database = loaded.keePassDatabase,
                        databaseId = loaded.database.id,
                        target = KeePassChangeTarget.PASSWORD,
                        entry = newEntry,
                        targetGroupPath = entry.keepassGroupPath
                    )?.let { pendingChangeSets += it }
                    val updatedRoot = addEntryToGroupPath(
                        rootGroup = loaded.keePassDatabase.content.group,
                        groupPath = entry.keepassGroupPath,
                        entry = newEntry
                    )
                    loaded.keePassDatabase.modifyParentGroup { updatedRoot }
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun readNativeDatabaseSettings(
        databaseId: Long
    ): Result<KeePassDatabaseSettingsSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val loaded = getCachedLoadedDatabase(databaseId) ?: loadDatabase(databaseId)
            KeePassDatabaseSettingsEditor.snapshot(
                databaseId = databaseId,
                database = loaded.keePassDatabase,
                readOnly = accessPolicy.isReadOnly(databaseId)
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(normalizeError(it)) }
        )
    }

    internal suspend fun updateNativeDatabaseSettings(
        databaseId: Long,
        update: KeePassDatabaseSettingsUpdate
    ): Result<KeePassDatabaseSettingsSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val readOnly = accessPolicy.isReadOnly(databaseId)
            val snapshot = mutateDatabase(databaseId) { loaded ->
                val updated = KeePassDatabaseSettingsEditor.apply(
                    database = loaded.keePassDatabase,
                    update = update
                )
                MutationPlan(
                    updatedDatabase = updated,
                    result = KeePassDatabaseSettingsEditor.snapshot(
                        databaseId = databaseId,
                        database = updated,
                        readOnly = readOnly
                    ),
                    afterWrite = { registration, writtenDatabase ->
                        updateStoredDatabaseMetadata(registration, writtenDatabase)
                    }
                )
            }
            invalidateLoadedDatabaseCache(databaseId)
            snapshot
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(normalizeError(it)) }
        )
    }

    internal suspend fun changeMasterCredentials(
        databaseId: Long,
        newPassword: String,
        keyFileMode: KeePassKeyFileChangeMode,
        replacementKeyFileUri: Uri? = null,
        keepInternalKeyFileCopy: Boolean = false
    ): Result<KeePassMasterCredentialChangeResult> = withContext(Dispatchers.IO) {
        runCatching {
            withDatabaseMutationLocks(listOf(databaseId)) {
                val loaded = getCachedLoadedDatabase(databaseId) ?: loadDatabase(databaseId)
                accessPolicy.requireWritable(databaseId)
                val registration = loaded.database
                val keyFileBytes = when (keyFileMode) {
                    KeePassKeyFileChangeMode.KEEP_CURRENT -> keyFileStore.read(registration)
                    KeePassKeyFileChangeMode.REMOVE -> null
                    KeePassKeyFileChangeMode.REPLACE -> {
                        val uri = requireNotNull(replacementKeyFileUri) {
                            "Replacement key file is required"
                        }
                        readKeyFileBytes(uri)
                    }
                }
                require(newPassword.isNotBlank() || keyFileBytes != null) {
                    "A master password or key file is required"
                }

                val keyFileName = when (keyFileMode) {
                    KeePassKeyFileChangeMode.KEEP_CURRENT -> registration.keyFileName
                    KeePassKeyFileChangeMode.REMOVE -> null
                    KeePassKeyFileChangeMode.REPLACE -> replacementKeyFileUri
                        ?.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() }
                        ?: "keyfile"
                }
                val transitionCopy = credentialTransitionStore.prepare(
                    databaseId = databaseId,
                    password = newPassword,
                    keyFileBytes = keyFileBytes,
                    keyFileName = keyFileName
                )
                var writeMayHaveChangedSource = false
                try {
                    val newCredentials = buildExactCredentials(newPassword, keyFileBytes)
                    val updatedDatabase = KeePassDatabaseCredentialEditor.replace(
                        database = loaded.keePassDatabase,
                        credentials = newCredentials
                    )

                    // Credential changes use a stricter verification path than normal edits:
                    // encode and reopen in memory before touching either local or remote bytes.
                    val previewBytes = encodeDatabase(updatedDatabase)
                    decodeDatabase(
                        bytes = previewBytes,
                        credentials = newCredentials,
                        sourceName = registration.resolvedActiveFilePath()
                    )

                    writeMayHaveChangedSource = true
                    writeDatabase(
                        database = registration,
                        credentials = newCredentials,
                        keePassDatabase = updatedDatabase,
                        sourceEtag = loaded.sourceEtag,
                        sourceLastModified = loaded.sourceLastModified,
                        expectedSourceRevision = loaded.sourceRevision
                    )

                    val retainedKeyFile = when (keyFileMode) {
                        KeePassKeyFileChangeMode.KEEP_CURRENT -> {
                            if (registration.keyFileInternalPath.isNullOrBlank() &&
                                keepInternalKeyFileCopy &&
                                transitionCopy != null
                            ) {
                                transitionCopy
                            } else {
                                null
                            }
                        }
                        KeePassKeyFileChangeMode.REMOVE -> null
                        KeePassKeyFileChangeMode.REPLACE -> transitionCopy
                            ?.takeIf { keepInternalKeyFileCopy }
                    }
                    replacementKeyFileUri?.let { uri ->
                        context.contentResolver.persistKeePassKeyFileReadPermission(uri)
                    }
                    val options = inferCreationOptions(updatedDatabase)
                    val updatedRegistration = registration.copy(
                        encryptedPassword = newPassword
                            .takeIf { it.isNotEmpty() }
                            ?.let(securityManager::encryptData),
                        keyFileUri = when (keyFileMode) {
                            KeePassKeyFileChangeMode.KEEP_CURRENT -> registration.keyFileUri
                            KeePassKeyFileChangeMode.REMOVE -> null
                            KeePassKeyFileChangeMode.REPLACE -> replacementKeyFileUri?.toString()
                        },
                        keyFileInternalPath = when (keyFileMode) {
                            KeePassKeyFileChangeMode.KEEP_CURRENT ->
                                registration.keyFileInternalPath ?: retainedKeyFile?.relativePath
                            KeePassKeyFileChangeMode.REMOVE -> null
                            KeePassKeyFileChangeMode.REPLACE -> retainedKeyFile?.relativePath
                        },
                        keyFileName = when (keyFileMode) {
                            KeePassKeyFileChangeMode.KEEP_CURRENT ->
                                registration.keyFileName ?: retainedKeyFile?.fileName
                            KeePassKeyFileChangeMode.REMOVE -> null
                            KeePassKeyFileChangeMode.REPLACE -> keyFileName
                        },
                        keyFileFingerprint = keyFileBytes?.let(KeePassKeyFileStore::fingerprint),
                        entryCount = countEntries(updatedDatabase.content.group),
                        kdbxMajorVersion = options.formatVersion.majorVersion,
                        cipherAlgorithm = options.cipherAlgorithm.name,
                        kdfAlgorithm = options.kdfAlgorithm.name,
                        kdfTransformRounds = options.transformRounds,
                        kdfMemoryBytes = options.memoryBytes,
                        kdfParallelism = options.parallelism,
                        lastAccessedAt = System.currentTimeMillis()
                    )
                    dao.updateDatabase(updatedRegistration)
                    credentialTransitionStore.clear(databaseId)
                    invalidateLoadedDatabaseCache(databaseId)

                    cleanupUnreferencedInternalKeyFile(
                        registration.keyFileInternalPath,
                        retainedPath = updatedRegistration.keyFileInternalPath,
                        databaseId = databaseId
                    )
                    cleanupUnreferencedInternalKeyFile(
                        transitionCopy?.relativePath,
                        retainedPath = updatedRegistration.keyFileInternalPath,
                        databaseId = databaseId
                    )

                    KeePassMasterCredentialChangeResult(
                        settings = KeePassDatabaseSettingsEditor.snapshot(
                            databaseId = databaseId,
                            database = updatedDatabase,
                            readOnly = false
                        ),
                        retainedInternalKeyFile = !updatedRegistration.keyFileInternalPath.isNullOrBlank()
                    )
                } catch (error: Throwable) {
                    // Before writeDatabase starts, the original source is known to be untouched.
                    // Once writing starts, retain the encrypted transition fallback because the
                    // local or remote source may already contain the new master key.
                    if (!writeMayHaveChangedSource) {
                        credentialTransitionStore.clear(databaseId)
                        cleanupUnreferencedInternalKeyFile(
                            transitionCopy?.relativePath,
                            retainedPath = registration.keyFileInternalPath,
                            databaseId = databaseId
                        )
                    }
                    throw error
                }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(normalizeError(it)) }
        )
    }

    internal suspend fun openNativeSession(databaseId: Long): Result<KeePassNativeSession> = withContext(Dispatchers.IO) {
        try {
            Result.success(loadDatabase(databaseId).nativeSession.value)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun openNativeBrowser(databaseId: Long): Result<KeePassNativeBrowserSnapshot> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(loadDatabase(databaseId).nativeBrowser.value)
            } catch (e: Exception) {
                Result.failure(normalizeError(e))
            }
        }

    internal suspend fun searchNativeEntries(
        databaseId: Long,
        options: KeePassNativeSearchOptions,
        now: Instant = Instant.now()
    ): Result<KeePassNativeSearchResult> = withContext(Dispatchers.IO) {
        try {
            val browser = loadDatabase(databaseId).nativeBrowser.value
            Result.success(KeePassNativeSearch.search(browser, options, now))
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun replaceNativeEntryFields(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = applyNativeEntryChange(
        databaseId = databaseId,
        entryUuid = entryUuid,
        expectedRevisionToken = expectedRevisionToken
    ) { current ->
        KeePassChangeSet(
            databaseId = databaseId,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.FIELD_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            fieldPatch = KeePassFieldChangePatch(
                managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = fields,
                baseFields = current.fields.map { (name, value) ->
                    takagi.ru.monica.keepass.KeePassFieldBaseValue(
                        name = name,
                        value = runCatching { value.content }.getOrDefault(""),
                        protected = value is EntryValue.Encrypted
                    )
                },
                replaceAllFields = true
            )
        )
    }

    internal suspend fun replaceNativeEntryFieldsAndPresentation(
        databaseId: Long,
        entryUuid: UUID,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = applyNativeEntryChange(
        databaseId = databaseId,
        entryUuid = entryUuid,
        expectedRevisionToken = expectedRevisionToken,
    ) { current ->
        buildNativeEntryEditChangeSet(databaseId, entryUuid, current, fields, presentation)
    }

    private fun buildNativeEntryEditChangeSet(
        databaseId: Long,
        entryUuid: UUID,
        current: Entry,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate,
    ): KeePassChangeSet {
        val generatedIcon = presentation.customIcon?.let { payload ->
            val (uuid, icon) = KeePassCustomIconEditor.newIcon(
                bytes = payload.bytes,
                name = payload.name.orEmpty(),
            )
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(icon.data, Base64.NO_WRAP),
                name = icon.name,
                lastModifiedEpochMillis = icon.lastModified?.toEpochMilli(),
            ) to uuid
        }
        val selectedUuid = presentation.customIconUuid ?: generatedIcon?.second
        return KeePassChangeSet(
            databaseId = databaseId,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.ENTRY_EDIT_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            fieldPatch = KeePassFieldChangePatch(
                managedScope = KeePassManagedFieldScope.EXPLICIT_ONLY,
                replacementFields = fields,
                baseFields = current.fields.map { (name, value) ->
                    takagi.ru.monica.keepass.KeePassFieldBaseValue(
                        name = name,
                        value = runCatching { value.content }.getOrDefault(""),
                        protected = value is EntryValue.Encrypted,
                    )
                },
                replaceAllFields = true,
            ),
            entryPresentationPatch = KeePassEntryPresentationPatch(
                predefinedIconName = presentation.predefinedIcon?.name,
                customIconUuid = selectedUuid?.toString(),
                clearCustomIcon = presentation.clearCustomIcon,
                customIcon = generatedIcon?.first,
                removeCustomIconUuid = presentation.removeCustomIconUuid?.toString(),
                basePresentationSignature = KeePassEntryFingerprint.buildPresentation(current),
                autoType = presentation.autoType,
                tags = presentation.tags,
                expires = presentation.expires,
                expiryTimeEpochMillis = presentation.expiryTime?.toEpochMilli(),
                basePropertiesSignature = if (presentation.tags != null || presentation.expires != null || presentation.expiryTime != null) {
                    KeePassEntryFingerprint.buildProperties(current)
                } else null,
            ),
        )
    }

    /** Stages the entire editor draft before the single file write, including every attachment. */
    internal suspend fun saveNativeEntryDraft(
        databaseId: Long,
        entryUuid: UUID?,
        parentGroupUuid: UUID?,
        fields: List<KeePassFieldChange>,
        presentation: KeePassNativeEntryPresentationUpdate?,
        sourceUris: List<Uri>,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            validateNativeFieldNames(fields)
            val attachments = readNativeAttachmentPayloads(sourceUris)
            val savedUuid = entryUuid ?: UUID.randomUUID()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                val original = entryUuid?.let { requireUniqueNativeEntry(loaded.nativeSession.value, it).entry }
                val parent = if (original == null) {
                    requireUniqueNativeGroup(loaded.nativeSession.value, requireNotNull(parentGroupUuid))
                } else null
                requirePreflightAllowed(KeePassWritePreflight.evaluateRuntime(
                    currentDatabaseBytes = loaded.sourceRevision.sizeBytes,
                    incomingPayloadBytes = attachments.sumOf { it.bytes.size.toLong() } +
                        (presentation?.customIcon?.bytes?.size?.toLong() ?: 0L),
                ))
                var staged = if (original == null) {
                    KeePassNativeManagement.createEntry(
                        loaded.keePassDatabase, requireNotNull(parentGroupUuid),
                        fields.map { field -> field.name to if (field.protected) {
                            EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                        } else EntryValue.Plain(field.value) }, savedUuid,
                    )
                } else loaded.keePassDatabase
                val current = requireNotNull(findEntryByUuid(staged.content.group, savedUuid))
                val edit = buildNativeEntryEditChangeSet(
                    databaseId, savedUuid, current, fields, presentation ?: KeePassNativeEntryPresentationUpdate(),
                )
                val applier = KeePassChangeSetApplier()
                staged = applier.apply(staged, edit).updatedDatabase
                val changes = mutableListOf(edit)
                attachments.forEach { payload ->
                    val binary = BinaryData.Uncompressed(false, payload.bytes).toCompressed()
                    val change = KeePassChangeSet(
                        databaseId = databaseId,
                        target = KeePassChangeTarget.UNKNOWN_ENTRY,
                        operation = KeePassChangeOperation.ADD_ATTACHMENT,
                        entryUuid = savedUuid.toString(),
                        baseFingerprint = KeePassEntryFingerprint.build(requireNotNull(findEntryByUuid(staged.content.group, savedUuid))),
                        attachmentPatch = KeePassAttachmentChangePatch(
                            fileName = payload.fileName,
                            binaryHash = binary.hash.hex(),
                            contentBase64 = Base64.encodeToString(payload.bytes, Base64.NO_WRAP),
                        ),
                    )
                    staged = applier.apply(staged, change).updatedDatabase
                    changes += change
                }
                val finalEntry = requireNotNull(findEntryByUuid(staged.content.group, savedUuid))
                val committedEntry = if (original == null) finalEntry.copy(history = emptyList()) else {
                    nativeMutation.editEntry(original, staged.content.meta, staged.binaries) {
                        finalEntry.copy(history = original.history, times = finalEntry.times?.copy(
                            lastModificationTime = original.times?.lastModificationTime,
                            lastAccessTime = original.times?.lastAccessTime,
                            usageCount = original.times?.usageCount ?: 0,
                        ))
                    }
                }
                staged = staged.modifyParentGroup { updateEntryInGroup(this, savedUuid, committedEntry) }
                val pendingChanges = if (original == null) listOf(requireNotNull(buildCreateEntryChangeSet(
                    staged, databaseId, KeePassChangeTarget.UNKNOWN_ENTRY, committedEntry,
                    parent?.legacyPath, parentGroupUuid,
                ))) else changes
                MutationPlan(
                    updatedDatabase = staged,
                    result = Unit,
                    beforeRemoteUpload = { database, revision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChanges, revision)
                    },
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, savedUuid))
        } catch (error: OutOfMemoryError) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private fun validateNativeFieldNames(fields: List<KeePassFieldChange>) {
        require(fields.isNotEmpty()) { "KeePass entry requires at least one field" }
        val names = fields.map { it.name }
        require(names.none(String::isBlank)) { "KeePass field name cannot be blank" }
        require(names.size == names.distinct().size) { "KeePass field names must be unique" }
    }

    internal suspend fun replaceNativeEntryPresentation(
        databaseId: Long,
        entryUuid: UUID,
        update: KeePassNativeEntryPresentationUpdate,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = applyNativeEntryChange(
        databaseId = databaseId,
        entryUuid = entryUuid,
        expectedRevisionToken = expectedRevisionToken,
    ) { current ->
        val generatedIcon = update.customIcon?.let { payload ->
            val (uuid, icon) = KeePassCustomIconEditor.newIcon(
                bytes = payload.bytes,
                name = payload.name.orEmpty(),
            )
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(icon.data, Base64.NO_WRAP),
                name = icon.name,
                lastModifiedEpochMillis = icon.lastModified?.toEpochMilli(),
            ) to uuid
        }
        val selectedUuid = update.customIconUuid ?: generatedIcon?.second
        KeePassChangeSet(
            databaseId = databaseId,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.ENTRY_PRESENTATION_PATCH,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            entryPresentationPatch = KeePassEntryPresentationPatch(
                predefinedIconName = update.predefinedIcon?.name,
                customIconUuid = selectedUuid?.toString(),
                clearCustomIcon = update.clearCustomIcon,
                customIcon = generatedIcon?.first,
                removeCustomIconUuid = update.removeCustomIconUuid?.toString(),
                basePresentationSignature = KeePassEntryFingerprint.buildPresentation(current),
                autoType = update.autoType,
                tags = update.tags,
                expires = update.expires,
                expiryTimeEpochMillis = update.expiryTime?.toEpochMilli(),
                basePropertiesSignature = if (update.tags != null || update.expires != null || update.expiryTime != null) {
                    KeePassEntryFingerprint.buildProperties(current)
                } else null,
            ),
        )
    }

    internal suspend fun updateNativeCustomIconPool(
        databaseId: Long,
        update: KeePassNativeCustomIconPoolUpdate,
        expectedRevisionToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                val upsert = update.upsert.map { item ->
                    val existing = loaded.keePassDatabase.content.meta.customIcons[item.uuid]
                    val bytes = item.bytes ?: existing?.data
                        ?: throw IllegalArgumentException("Custom icon not found: ${item.uuid}")
                    KeePassCustomIconPoolItemPatch(
                        uuid = item.uuid.toString(),
                        dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        name = item.name ?: existing?.name,
                        lastModifiedEpochMillis = item.lastModified?.toEpochMilli()
                            ?: existing?.lastModified?.toEpochMilli(),
                    )
                }
                val changeSet = KeePassChangeSet(
                    databaseId = databaseId,
                    target = KeePassChangeTarget.UNKNOWN_ENTRY,
                    operation = KeePassChangeOperation.CUSTOM_ICON_POOL_PATCH,
                    entryUuid = null,
                    baseFingerprint = null,
                    customIconPoolPatch = KeePassCustomIconPoolChangePatch(
                        upsert = upsert,
                        removeUuids = update.remove.map(UUID::toString),
                    ),
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet,
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    },
                )
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun restoreNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = applyNativeEntryChange(
        databaseId = databaseId,
        entryUuid = entryUuid,
        expectedRevisionToken = expectedRevisionToken
    ) { current ->
        val snapshot = current.history.getOrNull(historyIndex)
            ?: throw IllegalArgumentException("KeePass history version not found: $historyIndex")
        KeePassChangeSet(
            databaseId = databaseId,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.RESTORE_HISTORY,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            historyPatch = KeePassHistoryChangePatch(
                historyIndex = historyIndex,
                expectedSnapshotFingerprint = KeePassEntryFingerprint.build(snapshot)
            )
        )
    }

    internal suspend fun deleteNativeEntryHistory(
        databaseId: Long,
        entryUuid: UUID,
        historyIndex: Int,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = applyNativeEntryChange(
        databaseId = databaseId,
        entryUuid = entryUuid,
        expectedRevisionToken = expectedRevisionToken
    ) { current ->
        val snapshot = current.history.getOrNull(historyIndex)
            ?: throw IllegalArgumentException("KeePass history version not found: $historyIndex")
        KeePassChangeSet(
            databaseId = databaseId,
            target = KeePassChangeTarget.UNKNOWN_ENTRY,
            operation = KeePassChangeOperation.DELETE_HISTORY,
            entryUuid = entryUuid.toString(),
            baseFingerprint = KeePassEntryFingerprint.build(current),
            historyPatch = KeePassHistoryChangePatch(
                historyIndex = historyIndex,
                expectedSnapshotFingerprint = KeePassEntryFingerprint.build(snapshot)
            )
        )
    }

    internal suspend fun createNativeGroup(
        databaseId: Long,
        parentGroupUuid: UUID,
        name: String,
        expectedRevisionToken: String,
        properties: KeePassNativeGroupUpdate? = null
    ): Result<KeePassNativeGroupRecord> = withContext(Dispatchers.IO) {
        try {
            val normalizedName = name.trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("KeePass group name cannot be empty")
            val newGroupUuid = UUID.randomUUID()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                val parent = requireUniqueNativeGroup(loaded.nativeSession.value, parentGroupUuid)
                val changeSet = KeePassChangeSet(
                    databaseId = databaseId,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.CREATE_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = parent.legacyPath,
                    baseGroupUuid = parentGroupUuid.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupUuid = newGroupUuid.toString(),
                        targetGroupUuid = parentGroupUuid.toString(),
                        groupName = normalizedName
                    )
                )
                val createdDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase
                val updatedDatabase = properties?.let { update ->
                    KeePassNativeManagement.updateGroup(
                        database = createdDatabase,
                        groupUuid = newGroupUuid,
                        update = update.copy(name = normalizedName),
                    )
                } ?: createdDatabase
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(loadUniqueNativeGroupRecord(databaseId, newGroupUuid))
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun renameNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = withContext(Dispatchers.IO) {
        try {
            val normalizedName = newName.trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("KeePass group name cannot be empty")
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                val current = requireUniqueNativeGroup(loaded.nativeSession.value, groupUuid)
                val changeSet = KeePassChangeSet(
                    databaseId = databaseId,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.RENAME_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = current.legacyPath,
                    baseGroupUuid = groupUuid.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = current.legacyPath,
                        sourceGroupUuid = groupUuid.toString(),
                        newGroupName = normalizedName
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(loadUniqueNativeGroupRecord(databaseId, groupUuid))
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun moveNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                val current = requireUniqueNativeGroup(loaded.nativeSession.value, groupUuid)
                val target = requireUniqueNativeGroup(loaded.nativeSession.value, targetParentGroupUuid)
                if (current.parentGroup == target.identity) {
                    return@mutateDatabase MutationPlan(loaded.keePassDatabase, Unit)
                }
                val changeSet = KeePassChangeSet(
                    databaseId = databaseId,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.MOVE_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = current.legacyPath,
                    baseGroupUuid = current.parentGroup?.groupUuid?.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = current.legacyPath,
                        sourceGroupUuid = groupUuid.toString(),
                        targetGroupPath = target.legacyPath,
                        targetGroupUuid = targetParentGroupUuid.toString(),
                        groupName = current.group.name
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(loadUniqueNativeGroupRecord(databaseId, groupUuid))
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun moveNativeGroups(
        databaseId: Long,
        groupUuids: Set<UUID>,
        targetParentGroupUuid: UUID,
        expectedRevisionToken: String,
    ): Result<List<KeePassNativeGroupRecord>> = withContext(Dispatchers.IO) {
        try {
            require(groupUuids.isNotEmpty()) { strings.get(R.string.keepass_error_select_folder) }
            var movedUuids = emptySet<UUID>()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                groupUuids.forEach { uuid -> requireUniqueNativeGroup(loaded.nativeSession.value, uuid) }
                val target = requireUniqueNativeGroup(loaded.nativeSession.value, targetParentGroupUuid)
                movedUuids = groupUuids
                val changeSets = groupUuids.sortedBy(UUID::toString).map { uuid ->
                    val current = requireUniqueNativeGroup(loaded.nativeSession.value, uuid)
                    KeePassChangeSet(
                        databaseId = databaseId,
                        target = KeePassChangeTarget.GROUP,
                        operation = KeePassChangeOperation.MOVE_GROUP,
                        entryUuid = null,
                        baseFingerprint = null,
                        baseGroupPath = current.legacyPath,
                        baseGroupUuid = current.parentGroup?.groupUuid?.toString(),
                        structurePatch = KeePassStructureChangePatch(
                            sourceGroupPath = current.legacyPath,
                            sourceGroupUuid = uuid.toString(),
                            targetGroupPath = target.legacyPath,
                            targetGroupUuid = targetParentGroupUuid.toString(),
                            groupName = current.group.name,
                        ),
                    )
                }
                val updated = KeePassNativeManagement.moveGroups(
                    database = loaded.keePassDatabase,
                    groupUuids = groupUuids,
                    targetParentGroupUuid = targetParentGroupUuid,
                    strings = strings,
                )
                MutationPlan(
                    updatedDatabase = updated,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, changeSets, workingRevision)
                    },
                )
            }
            Result.success(movedUuids.map { loadUniqueNativeGroupRecord(databaseId, it) })
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun deleteNativeGroup(
        databaseId: Long,
        groupUuid: UUID,
        expectedRevisionToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                val current = requireUniqueNativeGroup(loaded.nativeSession.value, groupUuid)
                val changeSet = KeePassChangeSet(
                    databaseId = databaseId,
                    target = KeePassChangeTarget.GROUP,
                    operation = KeePassChangeOperation.DELETE_GROUP,
                    entryUuid = null,
                    baseFingerprint = null,
                    baseGroupPath = current.legacyPath,
                    baseGroupUuid = current.parentGroup?.groupUuid?.toString(),
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = current.legacyPath,
                        sourceGroupUuid = groupUuid.toString(),
                        groupName = current.group.name
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal suspend fun createNativeEntry(
        databaseId: Long,
        parentGroupUuid: UUID,
        fields: List<KeePassFieldChange>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            require(fields.isNotEmpty()) { "KeePass entry requires at least one field" }
            val normalizedNames = fields.map { it.name }
            require(normalizedNames.none { it.isBlank() }) { "KeePass field name cannot be blank" }
            require(normalizedNames.size == normalizedNames.distinct().size) {
                "KeePass field names must be unique"
            }
            val entryUuid = UUID.randomUUID()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                requireUniqueNativeGroup(loaded.nativeSession.value, parentGroupUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.createEntry(
                        database = loaded.keePassDatabase,
                        targetGroupUuid = parentGroupUuid,
                        fields = fields.map { field ->
                            field.name to if (field.protected) {
                                EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                            } else {
                                EntryValue.Plain(field.value)
                            }
                        },
                        entryUuid = entryUuid
                    ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun createNativeEntryWithAttachmentsFromUris(
        databaseId: Long,
        parentGroupUuid: UUID,
        fields: List<KeePassFieldChange>,
        sourceUris: List<Uri>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            require(fields.isNotEmpty()) { "KeePass entry requires at least one field" }
            val normalizedNames = fields.map { it.name }
            require(normalizedNames.none { it.isBlank() }) { "KeePass field name cannot be blank" }
            require(normalizedNames.size == normalizedNames.distinct().size) {
                "KeePass field names must be unique"
            }
            val attachments = readNativeAttachmentPayloads(sourceUris)
            val entryUuid = UUID.randomUUID()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                requireUniqueNativeGroup(loaded.nativeSession.value, parentGroupUuid)
                requirePreflightAllowed(
                    KeePassWritePreflight.evaluateRuntime(
                        currentDatabaseBytes = loaded.sourceRevision.sizeBytes,
                        incomingPayloadBytes = attachments.sumOf { payload -> payload.bytes.size.toLong() }
                    )
                )
                val created = KeePassNativeManagement.createEntry(
                    database = loaded.keePassDatabase,
                    targetGroupUuid = parentGroupUuid,
                    fields = fields.map { field ->
                        field.name to if (field.protected) {
                            EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                        } else {
                            EntryValue.Plain(field.value)
                        }
                    },
                    entryUuid = entryUuid
                )
                val withAttachments = attachments.fold(created) { database, payload ->
                    KeePassNativeManagement.addAttachment(
                        database = database,
                        entryUuid = entryUuid,
                        fileName = payload.fileName,
                        bytes = payload.bytes
                    )
                }
                MutationPlan(updatedDatabase = withAttachments, result = Unit)
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: OutOfMemoryError) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun duplicateNativeEntry(
        databaseId: Long,
        entryUuid: UUID,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            val copiedUuid = UUID.randomUUID()
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                requireUniqueNativeGroup(loaded.nativeSession.value, targetGroupUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.duplicateEntry(
                        database = loaded.keePassDatabase,
                        sourceEntryUuid = entryUuid,
                        targetGroupUuid = targetGroupUuid,
                        newEntryUuid = copiedUuid
                    ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, copiedUuid))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    /** Saves a complete native entry as a KeePass entry template. */
    internal suspend fun saveNativeEntryAsTemplate(
        databaseId: Long,
        entryUuid: UUID,
        titleOverride: String? = null,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            var templateUuid: UUID? = null
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                val mutation = KeePassTemplateEngine.saveAsTemplate(
                    database = loaded.keePassDatabase,
                    sourceEntryUuid = entryUuid,
                    titleOverride = titleOverride,
                )
                templateUuid = mutation.entryUuid
                MutationPlan(
                    updatedDatabase = mutation.database,
                    result = Unit,
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, requireNotNull(templateUuid)))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    /** Creates a normal entry from a template while preserving all native metadata. */
    internal suspend fun instantiateNativeTemplate(
        databaseId: Long,
        templateEntryUuid: UUID,
        targetGroupUuid: UUID,
        titleOverride: String? = null,
        expectedRevisionToken: String,
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            var createdUuid: UUID? = null
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, templateEntryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, templateEntryUuid)
                requireUniqueNativeGroup(loaded.nativeSession.value, targetGroupUuid)
                val mutation = KeePassTemplateEngine.instantiate(
                    database = loaded.keePassDatabase,
                    templateEntryUuid = templateEntryUuid,
                    targetGroupUuid = targetGroupUuid,
                    titleOverride = titleOverride,
                )
                createdUuid = mutation.entryUuid
                MutationPlan(
                    updatedDatabase = mutation.database,
                    result = Unit,
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, requireNotNull(createdUuid)))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun deleteNativeTemplate(
        databaseId: Long,
        templateEntryUuid: UUID,
        expectedRevisionToken: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, templateEntryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, templateEntryUuid)
                MutationPlan(
                    updatedDatabase = KeePassTemplateEngine.deleteTemplate(
                        loaded.keePassDatabase,
                        templateEntryUuid,
                    ),
                    result = Unit,
                )
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun moveNativeEntries(
        databaseId: Long,
        entryUuids: Set<UUID>,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<List<KeePassNativeEntryRecord>> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                entryUuids.forEach { uuid -> requireUniqueNativeEntry(loaded.nativeSession.value, uuid) }
                requireUniqueNativeGroup(loaded.nativeSession.value, targetGroupUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.moveEntries(
                        loaded.keePassDatabase,
                        entryUuids,
                        targetGroupUuid
                    ),
                    result = Unit
                )
            }
            Result.success(entryUuids.map { uuid -> loadUniqueNativeEntryRecord(databaseId, uuid) })
        } catch (error: Exception) {
            Result.failure(logMutationFailure("moveNativeEntries", databaseId, error))
        }
    }

    internal suspend fun deleteNativeEntries(
        databaseId: Long,
        entryUuids: Set<UUID>,
        mode: KeePassNativeDeleteMode,
        expectedRevisionToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                entryUuids.forEach { uuid -> requireUniqueNativeEntry(loaded.nativeSession.value, uuid) }
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.deleteEntries(
                        loaded.keePassDatabase,
                        entryUuids,
                        mode
                    ),
                    result = Unit
                )
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun renameNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = renameNativeAttachment(
        databaseId = databaseId,
        entryUuid = entryUuid,
        attachmentHashHex = attachmentHashHex,
        currentName = null,
        newName = newName,
        expectedRevisionToken = expectedRevisionToken
    )

    internal suspend fun renameNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        newName: String,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.renameAttachment(
                        loaded.keePassDatabase,
                        entryUuid,
                        attachmentHashHex.toByteStringHash(),
                        currentName,
                        newName
                    ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun addNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        fileName: String,
        bytes: ByteArray,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                requirePreflightAllowed(
                    KeePassWritePreflight.evaluateRuntime(
                        currentDatabaseBytes = loaded.sourceRevision.sizeBytes,
                        incomingPayloadBytes = bytes.size.toLong()
                    )
                )
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.addAttachment(
                        database = loaded.keePassDatabase,
                        entryUuid = entryUuid,
                        fileName = fileName,
                        bytes = bytes
                    ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: OutOfMemoryError) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun addNativeAttachmentFromUri(
        databaseId: Long,
        entryUuid: UUID,
        sourceUri: Uri,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            val metadata = AttachmentUriMetadata.resolve(context, sourceUri)
            val loaded = loadDatabase(databaseId)
            assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
            requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
            val descriptorSize = runCatching {
                context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            }.getOrNull()
            val declaredSize = metadata.sizeBytes.takeIf { it >= 0L } ?: descriptorSize
            if (declaredSize != null) {
                requirePreflightAllowed(
                    KeePassWritePreflight.evaluateRuntime(
                        currentDatabaseBytes = loaded.sourceRevision.sizeBytes,
                        incomingPayloadBytes = declaredSize
                    )
                )
            }
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { input -> input.readBytes() }
                ?: throw IOException("Unable to read selected attachment")
            Result.success(
                addNativeAttachment(
                    databaseId = databaseId,
                    entryUuid = entryUuid,
                    fileName = metadata.fileName,
                    bytes = bytes,
                    expectedRevisionToken = expectedRevisionToken
                ).getOrThrow()
            )
        } catch (error: OutOfMemoryError) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun addNativeAttachmentsFromUris(
        databaseId: Long,
        entryUuid: UUID,
        sourceUris: List<Uri>,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            if (sourceUris.isEmpty()) {
                return@withContext Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
            }
            val attachments = readNativeAttachmentPayloads(sourceUris)
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                requirePreflightAllowed(
                    KeePassWritePreflight.evaluateRuntime(
                        currentDatabaseBytes = loaded.sourceRevision.sizeBytes,
                        incomingPayloadBytes = attachments.sumOf { payload -> payload.bytes.size.toLong() }
                    )
                )
                val updated = attachments.fold(loaded.keePassDatabase) { database, payload ->
                    KeePassNativeManagement.addAttachment(
                        database = database,
                        entryUuid = entryUuid,
                        fileName = payload.fileName,
                        bytes = payload.bytes
                    )
                }
                MutationPlan(updatedDatabase = updated, result = Unit)
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: OutOfMemoryError) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private data class NativeAttachmentPayload(
        val fileName: String,
        val bytes: ByteArray
    )

    private fun readNativeAttachmentPayloads(sourceUris: List<Uri>): List<NativeAttachmentPayload> {
        return sourceUris.distinctBy(Uri::toString).map { sourceUri ->
            val metadata = AttachmentUriMetadata.resolve(context, sourceUri)
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { input -> input.readBytes() }
                ?: throw IOException("Unable to read selected attachment: ${metadata.fileName}")
            NativeAttachmentPayload(metadata.fileName, bytes)
        }
    }

    internal suspend fun deleteNativeAttachment(
        databaseId: Long,
        entryUuid: UUID,
        attachmentHashHex: String,
        currentName: String?,
        expectedRevisionToken: String
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken, entryUuid)
                requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.deleteAttachment(
                        database = loaded.keePassDatabase,
                        entryUuid = entryUuid,
                        hash = attachmentHashHex.toByteStringHash(),
                        currentName = currentName
                    ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeEntryRecord(databaseId, entryUuid))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun updateNativeGroupProperties(
        databaseId: Long,
        groupUuid: UUID,
        update: KeePassNativeGroupUpdate,
        expectedRevisionToken: String
    ): Result<KeePassNativeGroupRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                requireUniqueNativeGroup(loaded.nativeSession.value, groupUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeManagement.updateGroup(
                    loaded.keePassDatabase,
                    groupUuid,
                    update
                ),
                    result = Unit
                )
            }
            Result.success(loadUniqueNativeGroupRecord(databaseId, groupUuid))
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun inspectNativeDatabaseIntegrity(
        databaseId: Long
    ): Result<KeePassIntegrityReport> = withContext(Dispatchers.IO) {
        runCatching { KeePassDatabaseMaintenance.inspect(loadDatabase(databaseId).keePassDatabase) }
            .fold(
                onSuccess = Result.Companion::success,
                onFailure = { error -> Result.failure(normalizeError(error)) }
            )
    }

    internal suspend fun repairNativeDatabase(
        databaseId: Long,
        options: KeePassMaintenanceOptions = KeePassMaintenanceOptions()
    ): Result<KeePassMaintenanceExecution> = withContext(Dispatchers.IO) {
        try {
            accessPolicy.requireWritable(databaseId)
            val registration = dao.getDatabaseById(databaseId)
                ?: throw IllegalArgumentException("KeePass database not found: $databaseId")
            val recovery = openDatabaseStreamSource(registration).use { source ->
                recoveryStore.create(databaseId, source.openStream())
            }
            val repair = mutateDatabase(databaseId) { loaded ->
                val result = KeePassDatabaseMaintenance.repair(loaded.keePassDatabase, options)
                MutationPlan(updatedDatabase = result.database, result = result)
            }
            Result.success(
                KeePassMaintenanceExecution(
                    result = repair,
                    recoveryRecord = recoveryStore.list(databaseId)
                        .firstOrNull { record -> record.file == recovery.file }
                        ?: KeePassRecoveryRecord(
                            databaseId = databaseId,
                            file = recovery.file,
                            createdAt = recovery.createdAt,
                            revision = recovery.revision,
                            verified = recoveryStore.verify(recovery),
                            expectedHashPrefix = recovery.revision.sha256.take(12)
                        )
                )
            )
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal fun listRecoveryCopies(databaseId: Long): List<KeePassRecoveryRecord> =
        recoveryStore.list(databaseId)

    internal suspend fun exportRecoveryCopy(
        record: KeePassRecoveryRecord,
        destinationUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val revision = KeePassSourceSafety.revisionOf(record.file)
            require(record.verified && revision.sha256.startsWith(record.expectedHashPrefix)) {
                "KeePass recovery copy verification failed"
            }
            record.file.inputStream().use { input ->
                writeUriCopyVerified(destinationUri, input, revision)
            }
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error -> Result.failure(normalizeError(error)) }
        )
    }

    internal suspend fun deleteRecoveryCopy(record: KeePassRecoveryRecord): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!recoveryStore.delete(record)) throw IOException("Unable to delete KeePass recovery copy")
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error -> Result.failure(normalizeError(error)) }
            )
        }

    internal suspend fun restoreRecoveryCopy(
        databaseId: Long,
        record: KeePassRecoveryRecord
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            accessPolicy.requireWritable(databaseId)
            val loaded = loadDatabase(databaseId)
            require(record.databaseId == databaseId) { "Recovery copy belongs to another database" }
            val recoveryRevision = KeePassSourceSafety.revisionOf(record.file)
            require(record.verified && recoveryRevision.sha256.startsWith(record.expectedHashPrefix)) {
                "KeePass recovery copy verification failed"
            }
            val restoredDatabase = decodeDatabase(
                input = record.file.inputStream(),
                credentials = loaded.credentials,
                header = readFileHeader(record.file),
                sourceName = "recovery:${record.file.name}",
            )
            withDatabaseMutationLocks(listOf(databaseId)) {
                writeRawDatabaseFile(
                    database = loaded.database,
                    credentials = loaded.credentials,
                    keePassDatabase = restoredDatabase,
                    file = record.file,
                    sourceRevision = recoveryRevision,
                    expectedSourceRevision = loaded.sourceRevision,
                )
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun saveNativeDatabaseCopy(
        databaseId: Long,
        destinationUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val database = dao.getDatabaseById(databaseId)
                ?: throw IllegalArgumentException("KeePass database not found: $databaseId")
            openDatabaseStreamSource(database).use { source ->
                source.openStream().use { input ->
                    writeUriCopyVerified(destinationUri, input, source.revision)
                }
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    internal suspend fun mergeNativeDatabaseFrom(
        databaseId: Long,
        sourceUri: Uri,
        sourcePassword: String,
        sourceKeyFileUri: Uri?,
        targetGroupUuid: UUID,
        expectedRevisionToken: String
    ): Result<KeePassNativeBrowserSnapshot> = withContext(Dispatchers.IO) {
        try {
            accessPolicy.requireWritable(databaseId)
            val sourceCredentials = buildCredentialsFromRaw(sourcePassword, sourceKeyFileUri)
            val source = openUriStreamSource(
                sourceUri,
                "Unable to read the KeePass source database",
            )
            source.use {
                val preflight = KeePassWritePreflight.evaluateRuntime(
                    currentDatabaseBytes = loadDatabase(databaseId).sourceRevision.sizeBytes,
                    incomingPayloadBytes = source.revision.sizeBytes,
                )
                requirePreflightAllowed(preflight)
                val (sourceDatabase, _) = decodeDatabaseWithFallback(
                    source = source,
                    credentialsResolution = sourceCredentials,
                    sourceLabel = "merge-from",
                    sourceName = sourceUri.lastPathSegment,
                )
            val registration = dao.getDatabaseById(databaseId)
                ?: throw IllegalArgumentException("KeePass database not found: $databaseId")
            openDatabaseStreamSource(registration).use { current ->
                current.openStream().use { input -> recoveryStore.create(databaseId, input) }
            }
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(expectedRevisionToken, loaded.nativeSession.value.revisionToken)
                requireUniqueNativeGroup(loaded.nativeSession.value, targetGroupUuid)
                MutationPlan(
                    updatedDatabase = KeePassNativeDatabaseMerge.mergeFrom(
                        loaded.keePassDatabase,
                        sourceDatabase,
                        targetGroupUuid
                    ),
                    result = Unit
                )
            }
            Result.success(openNativeBrowser(databaseId).getOrThrow())
            }
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private suspend fun applyNativeEntryChange(
        databaseId: Long,
        entryUuid: UUID,
        expectedRevisionToken: String,
        buildChangeSet: (Entry) -> KeePassChangeSet
    ): Result<KeePassNativeEntryRecord> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                assertNativeRevision(
                    expectedRevisionToken = expectedRevisionToken,
                    actualRevisionToken = loaded.nativeSession.value.revisionToken,
                    entryUuid = entryUuid
                )
                val current = requireUniqueNativeEntry(loaded.nativeSession.value, entryUuid).entry
                val changeSet = buildChangeSet(current)
                MutationPlan(
                    updatedDatabase = KeePassChangeSetApplier().apply(
                        loaded.keePassDatabase,
                        changeSet
                    ).updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            val browser = loadDatabase(databaseId).nativeBrowser.value
            val identity = KeePassNativeEntryIdentity(databaseId, entryUuid)
            val records = browser.entriesByIdentity[identity].orEmpty()
            if (records.size != 1) {
                throw IllegalStateException("KeePass entry identity is ambiguous after mutation: $entryUuid")
            }
            Result.success(records.single())
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    private fun assertNativeRevision(
        expectedRevisionToken: String,
        actualRevisionToken: String,
        entryUuid: UUID? = null
    ) {
        if (expectedRevisionToken.isBlank() || expectedRevisionToken != actualRevisionToken) {
            throw KeePassChangeConflictException(
                changeId = "native-editor",
                entryUuid = entryUuid?.toString(),
                reason = "Database changed after the native editor was opened"
            )
        }
    }

    private fun requireUniqueNativeEntry(
        session: KeePassNativeSession,
        entryUuid: UUID
    ): takagi.ru.monica.keepass.KeePassNativeEntryNode {
        val identity = KeePassNativeEntryIdentity(session.databaseId, entryUuid)
        val nodes = session.entriesByIdentity[identity].orEmpty()
        if (nodes.isEmpty()) throw NoSuchElementException("KeePass entry not found: $entryUuid")
        if (nodes.size != 1) throw IllegalStateException("KeePass entry UUID is duplicated: $entryUuid")
        return nodes.single()
    }

    private fun requireUniqueNativeGroup(
        session: KeePassNativeSession,
        groupUuid: UUID
    ): takagi.ru.monica.keepass.KeePassNativeGroupNode {
        val identity = KeePassNativeGroupIdentity(session.databaseId, groupUuid)
        val nodes = session.groupsByIdentity[identity].orEmpty()
        if (nodes.isEmpty()) throw NoSuchElementException("KeePass group not found: $groupUuid")
        if (nodes.size != 1) throw IllegalStateException("KeePass group UUID is duplicated: $groupUuid")
        return nodes.single()
    }

    private suspend fun loadUniqueNativeGroupRecord(
        databaseId: Long,
        groupUuid: UUID
    ): KeePassNativeGroupRecord {
        val browser = loadDatabase(databaseId).nativeBrowser.value
        val records = browser.groupsByIdentity[KeePassNativeGroupIdentity(databaseId, groupUuid)].orEmpty()
        if (records.size != 1) {
            throw IllegalStateException("KeePass group identity is ambiguous after mutation: $groupUuid")
        }
        return records.single()
    }

    private suspend fun loadUniqueNativeEntryRecord(
        databaseId: Long,
        entryUuid: UUID
    ): KeePassNativeEntryRecord {
        val browser = loadDatabase(databaseId).nativeBrowser.value
        val records = browser.entriesByIdentity[KeePassNativeEntryIdentity(databaseId, entryUuid)].orEmpty()
        if (records.size != 1) {
            throw IllegalStateException("KeePass entry identity is ambiguous after mutation: $entryUuid")
        }
        return records.single()
    }

    internal suspend fun projectionRefreshDecision(
        databaseId: Long,
        kind: KeePassProjectionKind,
        forceRefresh: Boolean = false
    ): Result<KeePassProjectionRefreshDecision> = withContext(Dispatchers.IO) {
        try {
            val revisionToken = loadDatabase(databaseId).nativeSession.value.revisionToken
            Result.success(
                KeePassProjectionRefreshDecision(
                    revisionToken = revisionToken,
                    needsRefresh = forceRefresh || projectionIndexGate.needsRefresh(
                        databaseId = databaseId,
                        revisionToken = revisionToken,
                        kind = kind
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal fun markProjectionIndexed(
        databaseId: Long,
        revisionToken: String,
        kinds: Set<KeePassProjectionKind>
    ) {
        projectionIndexGate.markIndexed(databaseId, revisionToken, kinds)
    }

    suspend fun copyNativeEntry(
        sourceDatabaseId: Long,
        sourceEntryUuid: String,
        targetDatabaseId: Long,
        targetGroupPath: String? = null,
        targetEntryUuid: String? = null
    ): Result<KeePassNativeEntryTransferResult> {
        return transferNativeEntry(
            sourceDatabaseId = sourceDatabaseId,
            sourceEntryUuid = sourceEntryUuid,
            targetDatabaseId = targetDatabaseId,
            targetGroupPath = targetGroupPath,
            targetEntryUuid = targetEntryUuid,
            moveSource = false
        )
    }

    suspend fun moveNativeEntry(
        sourceDatabaseId: Long,
        sourceEntryUuid: String,
        targetDatabaseId: Long,
        targetGroupPath: String? = null
    ): Result<KeePassNativeEntryTransferResult> {
        return transferNativeEntry(
            sourceDatabaseId = sourceDatabaseId,
            sourceEntryUuid = sourceEntryUuid,
            targetDatabaseId = targetDatabaseId,
            targetGroupPath = targetGroupPath,
            targetEntryUuid = sourceEntryUuid,
            moveSource = true
        )
    }

    private suspend fun transferNativeEntry(
        sourceDatabaseId: Long,
        sourceEntryUuid: String,
        targetDatabaseId: Long,
        targetGroupPath: String?,
        targetEntryUuid: String?,
        moveSource: Boolean
    ): Result<KeePassNativeEntryTransferResult> = withContext(Dispatchers.IO) {
        val sourceUuid = parseUuid(sourceEntryUuid)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid source entry uuid"))
        val resolvedTargetUuid = if (moveSource) {
            sourceUuid
        } else {
            targetEntryUuid?.takeIf { it.isNotBlank() }?.let { requested ->
                parseUuid(requested)
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid target entry uuid"))
            } ?: UUID.randomUUID()
        }

        try {
            val result = withDatabaseMutationLocks(listOf(sourceDatabaseId, targetDatabaseId)) {
                val sourceLoaded = getCachedLoadedDatabase(sourceDatabaseId) ?: loadDatabase(sourceDatabaseId)
                val targetLoaded = if (sourceDatabaseId == targetDatabaseId) {
                    sourceLoaded
                } else {
                    getCachedLoadedDatabase(targetDatabaseId) ?: loadDatabase(targetDatabaseId)
                }
                val targetGroupUuid = resolveNativeTargetGroupUuid(
                    database = targetLoaded.keePassDatabase,
                    groupPath = targetGroupPath
                )
                val payload = KeePassLosslessTransfer.captureEntry(
                    sourceDatabase = sourceLoaded.keePassDatabase,
                    sourceEntryUuid = sourceUuid,
                    targetEntryUuid = resolvedTargetUuid
                )
                val transferResult = KeePassNativeEntryTransferResult(
                    sourceDatabaseId = sourceDatabaseId,
                    sourceEntryUuid = sourceUuid.toString(),
                    targetDatabaseId = targetDatabaseId,
                    targetEntryUuid = resolvedTargetUuid.toString(),
                    targetGroupUuid = targetGroupUuid.toString(),
                    moved = moveSource
                )

                if (sourceDatabaseId == targetDatabaseId) {
                    val updatedDatabase = if (moveSource) {
                        KeePassLosslessTransfer.relocateEntry(
                            database = sourceLoaded.keePassDatabase,
                            sourceEntryUuid = sourceUuid,
                            targetGroupUuid = targetGroupUuid
                        )
                    } else {
                        KeePassLosslessTransfer.insertEntry(
                            targetDatabase = targetLoaded.keePassDatabase,
                            targetGroupUuid = targetGroupUuid,
                            payload = payload
                        )
                    }
                    if (updatedDatabase != sourceLoaded.keePassDatabase) {
                        writeDatabase(
                            database = sourceLoaded.database,
                            credentials = sourceLoaded.credentials,
                            keePassDatabase = updatedDatabase,
                            sourceEtag = sourceLoaded.sourceEtag,
                            sourceLastModified = sourceLoaded.sourceLastModified,
                            expectedSourceRevision = sourceLoaded.sourceRevision
                        )
                        updateStoredEntryCount(sourceLoaded.database.id, updatedDatabase)
                    }
                    verifyNativeEntryPersisted(
                        loaded = sourceLoaded,
                        targetGroupUuid = targetGroupUuid,
                        payload = payload
                    )
                    return@withDatabaseMutationLocks transferResult
                }

                KeePassTargetFirstTransfer.execute(
                    persistTarget = {
                        val updatedTarget = KeePassLosslessTransfer.insertEntry(
                            targetDatabase = targetLoaded.keePassDatabase,
                            targetGroupUuid = targetGroupUuid,
                            payload = payload
                        )
                        writeDatabase(
                            database = targetLoaded.database,
                            credentials = targetLoaded.credentials,
                            keePassDatabase = updatedTarget,
                            sourceEtag = targetLoaded.sourceEtag,
                            sourceLastModified = targetLoaded.sourceLastModified,
                            expectedSourceRevision = targetLoaded.sourceRevision
                        )
                        updateStoredEntryCount(targetLoaded.database.id, updatedTarget)
                        transferResult
                    },
                    verifyTarget = {
                        verifyNativeEntryPersisted(
                            loaded = targetLoaded,
                            targetGroupUuid = targetGroupUuid,
                            payload = payload
                        )
                        requireRemoteTargetSynchronized(targetLoaded.database)
                    },
                    removeSource = {
                        if (moveSource) {
                            val updatedSource = KeePassLosslessTransfer.removeEntry(
                                sourceDatabase = sourceLoaded.keePassDatabase,
                                sourceEntryUuid = sourceUuid
                            )
                            writeDatabase(
                                database = sourceLoaded.database,
                                credentials = sourceLoaded.credentials,
                                keePassDatabase = updatedSource,
                                sourceEtag = sourceLoaded.sourceEtag,
                                sourceLastModified = sourceLoaded.sourceLastModified,
                                expectedSourceRevision = sourceLoaded.sourceRevision
                            )
                            updateStoredEntryCount(sourceLoaded.database.id, updatedSource)
                        }
                    }
                )
            }
            Result.success(result)
        } catch (e: Exception) {
            invalidateLoadedDatabaseCache(sourceDatabaseId)
            invalidateLoadedDatabaseCache(targetDatabaseId)
            Result.failure(normalizeError(e))
        }
    }

    private fun resolveNativeTargetGroupUuid(
        database: KeePassDatabase,
        groupPath: String?
    ): UUID {
        val resolvedPath = groupPath?.trim()?.takeIf { it.isNotBlank() }
            ?: return database.content.group.uuid
        return findGroupUuidByPath(
            group = database.content.group,
            currentPathKey = null,
            targetPathKey = resolvedPath
        ) ?: throw IllegalArgumentException("KeePass target group not found: $resolvedPath")
    }

    private suspend fun verifyNativeEntryPersisted(
        loaded: LoadedDatabase,
        targetGroupUuid: UUID,
        payload: KeePassNativeEntryTransferPayload
    ) {
        val persistedDatabase = openDatabaseStreamSource(loaded.database).use { source ->
            decodeDatabase(
                input = source.openStream(),
                credentials = loaded.credentials,
                header = source.header,
                sourceName = loaded.database.resolvedActiveFilePath(),
            )
        }
        val persistedContext = collectEntryContexts(persistedDatabase).first
            .firstOrNull { it.entry.uuid == payload.entry.uuid }
            ?: throw IOException("KeePass target entry verification failed: ${payload.entry.uuid}")
        if (persistedContext.groupUuid != targetGroupUuid ||
            !KeePassLosslessTransfer.entriesEquivalent(payload.entry, persistedContext.entry)
        ) {
            throw IOException("KeePass target entry content verification failed: ${payload.entry.uuid}")
        }
        payload.binaryPool.forEach { (hash, expected) ->
            val actual = persistedDatabase.binaries[hash]
                ?: throw IOException("KeePass target binary verification failed: $hash")
            if (actual.memoryProtection != expected.memoryProtection ||
                !actual.rawContent.contentEquals(expected.rawContent)
            ) {
                throw IOException("KeePass target binary content verification failed: $hash")
            }
        }
        payload.customIcons.forEach { (uuid, expected) ->
            val actual = persistedDatabase.content.meta.customIcons[uuid]
                ?: throw IOException("KeePass target custom icon verification failed: $uuid")
            if (!actual.data.contentEquals(expected.data) ||
                actual.name != expected.name ||
                actual.lastModified != expected.lastModified
            ) {
                throw IOException("KeePass target custom icon content verification failed: $uuid")
            }
        }
    }

    private suspend fun requireRemoteTargetSynchronized(database: LocalKeePassDatabase) {
        if (!database.isRemoteSource()) return
        val state = PasswordDatabase.getDatabase(context)
            .keepassRemoteSyncStateDao()
            .getState(database.id)
            ?: throw IOException("KeePass remote target has no verified synchronization state")
        if (state.hasLocalChanges ||
            state.hasRemoteChanges ||
            state.syncPhase != KeePassSyncPhase.IDLE ||
            state.failureCode != null
        ) {
            throw IOException(
                state.failureMessage
                    ?: "KeePass remote target is pending synchronization; source entry was retained"
            )
        }
    }

    private suspend fun updateStoredEntryCount(databaseId: Long, database: KeePassDatabase) {
        dao.updateEntryCount(databaseId, countEntries(database.content.group))
    }

    // ================================================================
    // Attachment API（供 takagi.ru.monica.attachments.executor 使用）
    //
    // 对应 .kiro/specs/monica-android-attachments Requirement 6。
    // 所有方法都通过既有的 `mutateDatabase` / `loadDatabase` 通道，不另起 IO 路径，
    // 以保证 kdbx 保存时机、锁与缓存行为与密码/安全项一致。
    // ================================================================

    /** 附件元数据视图（供 executor 层构造 [takagi.ru.monica.attachments.model.Attachment] 使用）。 */
    data class KeePassAttachmentInfo(
        val hashHex: String,
        val fileName: String,
        val sizeBytes: Long,
        val memoryProtection: Boolean
    )

    /**
     * 读取某个条目的全部附件元数据（不读字节）。
     *
     * 要求数据库已解锁（即已通过正常流程 load 过）。
     */
    suspend fun readEntryAttachments(
        databaseId: Long,
        entryUuid: String
    ): Result<List<KeePassAttachmentInfo>> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val targetUuid = parseUuid(entryUuid)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid entry uuid"))
            val entry = findEntryByUuid(loaded.keePassDatabase.content.group, targetUuid)
                ?: return@withContext Result.failure(NoSuchElementException("Entry not found"))
            val pool = loaded.keePassDatabase.binaries
            val infos = entry.binaries.map { ref ->
                val data = pool[ref.hash]
                KeePassAttachmentInfo(
                    hashHex = ref.hash.hex(),
                    fileName = ref.name,
                    sizeBytes = (data?.rawContent?.size ?: 0).toLong(),
                    memoryProtection = data?.memoryProtection ?: false
                )
            }
            Result.success(infos)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    /**
     * 读取某个附件的明文字节（kotpass 已在内部处理解压）。
     */
    suspend fun readAttachmentBytes(
        databaseId: Long,
        entryUuid: String,
        hashHex: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val targetUuid = parseUuid(entryUuid)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid entry uuid"))
            val entry = findEntryByUuid(loaded.keePassDatabase.content.group, targetUuid)
                ?: return@withContext Result.failure(NoSuchElementException("Entry not found"))
            val requestedRef = KeePassAttachmentRef.decode(hashHex)
            val matchRef = entry.binaries.firstOrNull {
                it.hash.hex().equals(requestedRef.hashHex, ignoreCase = true) &&
                    (requestedRef.fileName == null || it.name == requestedRef.fileName)
            }
                ?: return@withContext Result.failure(NoSuchElementException("Attachment not found in entry"))
            val data = loaded.keePassDatabase.binaries[matchRef.hash]
                ?: return@withContext Result.failure(NoSuchElementException("Attachment missing in binary pool"))
            // 用 inputStream 读回解压后的明文字节；避免直接访问 BinaryData.content / BinaryData.rawContent
            // 避免不同 kotpass 版本里 abstract property 访问差异带来的符号解析问题。
            val bytes = data.inputStream().use { it.readBytes() }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    /**
     * 为条目新增一个附件：把字节写入 `Meta.binaries` 池（若 hash 已存在则复用），
     * 再把对应 [BinaryReference] 追加到 `Entry.binaries`。
     */
    suspend fun addAttachmentToEntry(
        databaseId: Long,
        entryUuid: String,
        fileName: String,
        bytes: ByteArray,
        memoryProtection: Boolean = false,
        compressed: Boolean = true
    ): Result<KeePassAttachmentInfo> = withContext(Dispatchers.IO) {
        try {
            val targetUuid = parseUuid(entryUuid)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid entry uuid"))

            val loadedForPreflight = loadDatabase(databaseId)
            requirePreflightAllowed(
                KeePassWritePreflight.evaluateRuntime(
                    currentDatabaseBytes = loadedForPreflight.sourceRevision.sizeBytes,
                    incomingPayloadBytes = bytes.size.toLong()
                )
            )

            val info = mutateDatabase(databaseId) { loaded ->
                val oldDb = loaded.keePassDatabase
                val existingEntry = findEntryByUuid(oldDb.content.group, targetUuid)
                    ?: throw NoSuchElementException("Entry not found")
                val baseFingerprint = buildConflictEntrySignature(existingEntry)

                val binaryData: BinaryData = if (compressed) {
                    BinaryData.Uncompressed(memoryProtection, bytes).toCompressed()
                } else {
                    BinaryData.Uncompressed(memoryProtection, bytes)
                }
                val hash = binaryData.hash

                // 1. 手工替换 Entry.binaries；避开 kotpass 的 inline modifyEntry
                val newRef = BinaryReference(hash = hash, name = fileName)
                val updatedEntry = existingEntry.copy(
                    binaries = existingEntry.binaries + newRef
                )

                // 2. 先更新 Entry 到 Group 树
                val rootRewriter: (Group) -> Group = { root ->
                    updateEntryInGroup(root, targetUuid, updatedEntry)
                }
                val dbWithUpdatedEntry = oldDb.modifyParentGroup(rootRewriter)

                // 3. 再写二进制池（未映射时 put；已存在相同 hash 则复用）
                val updatedDatabase = dbWithUpdatedEntry.modifyBinaries { pool ->
                    if (pool.containsKey(hash)) pool else pool + (hash to binaryData)
                }

                val resultInfo = KeePassAttachmentInfo(
                    hashHex = hash.hex(),
                    fileName = fileName,
                    sizeBytes = bytes.size.toLong(),
                    memoryProtection = memoryProtection
                )
                val changeSet = KeePassChangeSet(
                    databaseId = loaded.database.id,
                    target = KeePassChangeTarget.UNKNOWN_ENTRY,
                    operation = KeePassChangeOperation.ADD_ATTACHMENT,
                    entryUuid = targetUuid.toString(),
                    baseFingerprint = baseFingerprint,
                    attachmentPatch = KeePassAttachmentChangePatch(
                        fileName = fileName,
                        binaryHash = hash.hex(),
                        protected = memoryProtection,
                        compressed = compressed,
                        contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    )
                )
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = resultInfo,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(info)
        } catch (e: OutOfMemoryError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    /**
     * 从条目中删除一个附件；若该 hash 在所有 Entry 里都不再被引用，从 `Meta.binaries` 池释放。
     */
    suspend fun deleteAttachmentFromEntry(
        databaseId: Long,
        entryUuid: String,
        hashHex: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val targetUuid = parseUuid(entryUuid)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid entry uuid"))
            val removed = mutateDatabase(databaseId) { loaded ->
                val oldDb = loaded.keePassDatabase
                val existingEntry = findEntryByUuid(oldDb.content.group, targetUuid)
                    ?: throw NoSuchElementException("Entry not found")
                val baseFingerprint = buildConflictEntrySignature(existingEntry)
                val requestedRef = KeePassAttachmentRef.decode(hashHex)
                val targetRef = existingEntry.binaries
                    .firstOrNull {
                        it.hash.hex().equals(requestedRef.hashHex, ignoreCase = true) &&
                            (requestedRef.fileName == null || it.name == requestedRef.fileName)
                    }
                if (targetRef == null) {
                    return@mutateDatabase MutationPlan(updatedDatabase = oldDb, result = false)
                }
                val updatedEntry = existingEntry.copy(
                    binaries = existingEntry.binaries - targetRef
                )
                val rootRewriter: (Group) -> Group = { root ->
                    updateEntryInGroup(root, targetUuid, updatedEntry)
                }
                val rootReplaced = oldDb.modifyParentGroup(rootRewriter)
                // 判断该 hash 是否还被任何条目引用；不再引用时从池释放。
                val stillReferenced = anyEntryReferencesHash(
                    rootReplaced.content.group,
                    targetRef.hash
                )
                val compacted = if (stillReferenced) {
                    rootReplaced
                } else {
                    rootReplaced.modifyBinaries { pool -> pool - targetRef.hash }
                }
                val binaryData = oldDb.binaries[targetRef.hash]
                val changeSet = KeePassChangeSet(
                    databaseId = loaded.database.id,
                    target = KeePassChangeTarget.UNKNOWN_ENTRY,
                    operation = KeePassChangeOperation.REMOVE_ATTACHMENT,
                    entryUuid = targetUuid.toString(),
                    baseFingerprint = baseFingerprint,
                    attachmentPatch = KeePassAttachmentChangePatch(
                        fileName = targetRef.name,
                        binaryHash = targetRef.hash.hex(),
                        protected = binaryData?.memoryProtection ?: false
                    )
                )
                MutationPlan(
                    updatedDatabase = compacted,
                    result = true,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, listOf(changeSet), workingRevision)
                    }
                )
            }
            Result.success(removed)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    /** 仅判断 kdbx 是否已解锁（有 cache 命中），不会触发解密。 */
    fun isDatabaseUnlocked(databaseId: Long): Boolean {
        return synchronized(loadedDatabaseCache) { loadedDatabaseCache.containsKey(databaseId) }
    }

    internal fun isDatabaseReadOnly(databaseId: Long): Boolean = accessPolicy.isReadOnly(databaseId)

    internal fun setDatabaseReadOnly(databaseId: Long, readOnly: Boolean) {
        accessPolicy.setReadOnly(databaseId, readOnly)
    }

    internal fun lockDatabase(databaseId: Long) {
        invalidateProcessCache(databaseId)
    }

    private fun findEntryByUuid(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        for (child in group.groups) {
            findEntryByUuid(child, uuid)?.let { return it }
        }
        return null
    }

    private fun updateEntryInGroup(group: Group, targetUuid: UUID, replacement: Entry): Group {
        val newEntries = group.entries.map { if (it.uuid == targetUuid) replacement else it }
        val newGroups = group.groups.map { updateEntryInGroup(it, targetUuid, replacement) }
        return group.copy(entries = newEntries, groups = newGroups)
    }

    private fun anyEntryReferencesHash(group: Group, hash: okio.ByteString): Boolean {
        if (group.entries.any { entry -> entry.binaries.any { it.hash == hash } }) return true
        return group.groups.any { anyEntryReferencesHash(it, hash) }
    }

    // ================================================================
    // Attachment API end
    // ================================================================

    suspend fun addPasswordEntry(
        databaseId: Long,
        entry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        forceSyncWrite: Boolean = false,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val plainPassword = resolvePassword(entry)
                val newEntry = buildEntry(entry, plainPassword, customFields)
                val updatedRoot = addEntryToGroupPath(
                    rootGroup = loaded.keePassDatabase.content.group,
                    groupPath = entry.keepassGroupPath,
                    entry = newEntry
                )
                val updatedDatabase = loaded.keePassDatabase.modifyParentGroup { updatedRoot }
                MutationPlan(updatedDatabase = updatedDatabase, result = Unit)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun deletePasswordEntries(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val removedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val deleteResult = applyEntryStructureChanges(
                    loaded = loaded,
                    targets = entries,
                    targetType = KeePassChangeTarget.PASSWORD,
                    operation = KeePassChangeOperation.PERMANENT_DELETE,
                    matcher = { entry, target, resolutionContext ->
                        matchesPasswordEntry(entry, target, resolutionContext)
                    }
                )
                MutationPlan(
                    updatedDatabase = deleteResult.database,
                    result = deleteResult.changedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, deleteResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(removedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun movePasswordEntriesToRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val movedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val moveResult = applyEntryStructureChanges(
                    loaded = loaded,
                    targets = entries,
                    targetType = KeePassChangeTarget.PASSWORD,
                    operation = KeePassChangeOperation.MOVE_TO_RECYCLE_BIN,
                    matcher = { entry, target, resolutionContext ->
                        matchesPasswordEntry(entry, target, resolutionContext)
                    }
                )
                MutationPlan(
                    updatedDatabase = moveResult.database,
                    result = moveResult.changedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, moveResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(movedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun restorePasswordEntriesFromRecycleBin(
        databaseId: Long,
        entries: List<PasswordEntry>,
        forceSyncWrite: Boolean = false
    ): Result<Map<Long, KeePassRestoreTarget>> = withContext(Dispatchers.IO) {
        try {
            val restoredTargets = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val restoreResult = restoreEntriesFromRecycleBin(
                    loaded = loaded,
                    targets = entries,
                    targetType = KeePassChangeTarget.PASSWORD,
                    matcher = { entry, target, resolutionContext ->
                        matchesPasswordEntry(entry, target, resolutionContext)
                    },
                    roomId = { it.id },
                    preferredGroupPath = { it.keepassGroupPath },
                    preferredGroupUuid = { it.keepassGroupUuid }
                )
                MutationPlan(
                    updatedDatabase = restoreResult.database,
                    result = restoreResult.targetsByRoomId,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, restoreResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(restoredTargets)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun readSecureItems(
        databaseId: Long,
        allowedTypes: Set<ItemType>? = null
    ): Result<List<KeePassSecureItemData>> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val keePassDatabase = loaded.keePassDatabase
            val bundle = loaded.projectionBundle.value
            val data = mutableListOf<KeePassSecureItemData>()
            bundle.entries.forEach { context ->
                entryToSecureItemData(
                    entry = context.entry,
                    keePassDatabase = keePassDatabase,
                    databaseId = databaseId,
                    groupPath = context.groupPath,
                    groupUuid = context.groupUuid,
                    isInRecycleBinByMeta = context.isInRecycleBinByMeta,
                    hasRecycleBinMeta = bundle.hasRecycleBinMeta,
                    allowedTypes = allowedTypes,
                    resolutionContext = bundle.resolutionContext
                )?.let(data::add)
            }
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun readPasskeyEntries(databaseId: Long): Result<List<PasskeyEntry>> = withContext(Dispatchers.IO) {
        try {
            val bundle = loadDatabase(databaseId).projectionBundle.value
            val data = bundle.entries.mapNotNull { context ->
                entryToPasskey(
                    entry = context.entry,
                    databaseId = databaseId,
                    groupPath = context.groupPath,
                    groupUuid = context.groupUuid,
                    resolutionContext = bundle.resolutionContext
                )
            }
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun addOrUpdateSecureItems(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val itemsWithPhotoUpdates = items.map { item ->
                item to buildSecureItemPhotoUpdates(item)
            }
            val addedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                var updatedDatabase = loaded.keePassDatabase
                var addedCount = 0
                val pendingChangeSets = mutableListOf<KeePassChangeSet>()
                itemsWithPhotoUpdates.forEach { (item, photoUpdates) ->
                    val updateResult = updateSecureItemInternal(
                        keePassDatabase = updatedDatabase,
                        databaseId = loaded.database.id,
                        item = item,
                        photoUpdates = photoUpdates
                    )
                    pendingChangeSets += updateResult.changeSets
                    if (updateResult.changed) {
                        updatedDatabase = updateResult.database
                    } else {
                        val newEntry = buildSecureItemEntry(item)
                        buildCreateEntryChangeSet(
                            database = updatedDatabase,
                            databaseId = loaded.database.id,
                            target = KeePassChangeTarget.SECURE_ITEM,
                            entry = newEntry,
                            targetGroupPath = item.keepassGroupPath
                        )?.let { pendingChangeSets += it }
                        val updatedRoot = addEntryToGroupPath(
                            rootGroup = updatedDatabase.content.group,
                            groupPath = item.keepassGroupPath,
                            entry = newEntry
                        )
                        val databaseWithEntry = updatedDatabase.modifyParentGroup { updatedRoot }
                        val photoSync = KeePassSecureItemPhotoAttachments.synchronize(
                            database = databaseWithEntry,
                            entryUuid = newEntry.uuid,
                            itemType = item.itemType,
                            updates = photoUpdates
                        )
                        pendingChangeSets += buildSecureItemPhotoChangeSets(
                            databaseId = loaded.database.id,
                            entryUuid = newEntry.uuid,
                            baseFingerprint = buildConflictEntrySignature(newEntry),
                            changes = photoSync.changes
                        )
                        updatedDatabase = photoSync.database
                        addedCount++
                    }
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = addedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    }
                )
            }
            Result.success(addedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun addOrUpdatePasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val addedCount = mutateDatabase(databaseId = databaseId) { loaded ->
                var updatedDatabase = loaded.keePassDatabase
                var addedCount = 0
                val pendingChangeSets = mutableListOf<KeePassChangeSet>()
                passkeys.forEach { passkey ->
                    val updateResult = updatePasskeyInternal(
                        keePassDatabase = updatedDatabase,
                        databaseId = loaded.database.id,
                        passkey = passkey
                    )
                    pendingChangeSets += updateResult.changeSets
                    if (updateResult.changed) {
                        updatedDatabase = updateResult.database
                    } else {
                        val newEntry = buildPasskeyEntry(passkey)
                        buildCreateEntryChangeSet(
                            database = updatedDatabase,
                            databaseId = loaded.database.id,
                            target = KeePassChangeTarget.PASSKEY,
                            entry = newEntry,
                            targetGroupPath = passkey.keepassGroupPath
                        )?.let { pendingChangeSets += it }
                        val updatedRoot = addEntryToGroupPath(
                            rootGroup = updatedDatabase.content.group,
                            groupPath = passkey.keepassGroupPath,
                            entry = newEntry
                        )
                        updatedDatabase = updatedDatabase.modifyParentGroup { updatedRoot }
                        addedCount++
                    }
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = addedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    }
                )
            }
            Result.success(addedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun updateSecureItem(
        databaseId: Long,
        item: SecureItem
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val photoUpdates = buildSecureItemPhotoUpdates(item)
            mutateDatabase(databaseId) { loaded ->
                val updateResult = updateSecureItemInternal(
                    keePassDatabase = loaded.keePassDatabase,
                    databaseId = loaded.database.id,
                    item = item,
                    photoUpdates = photoUpdates
                )
                val pendingChangeSets = updateResult.changeSets.toMutableList()
                val updatedDatabase = if (updateResult.changed) {
                    updateResult.database
                } else {
                    val newEntry = buildSecureItemEntry(item)
                    buildCreateEntryChangeSet(
                        database = loaded.keePassDatabase,
                        databaseId = loaded.database.id,
                        target = KeePassChangeTarget.SECURE_ITEM,
                        entry = newEntry,
                        targetGroupPath = item.keepassGroupPath
                    )?.let { pendingChangeSets += it }
                    val updatedRoot = addEntryToGroupPath(
                        rootGroup = loaded.keePassDatabase.content.group,
                        groupPath = item.keepassGroupPath,
                        entry = newEntry
                    )
                    val databaseWithEntry = loaded.keePassDatabase.modifyParentGroup { updatedRoot }
                    val photoSync = KeePassSecureItemPhotoAttachments.synchronize(
                        database = databaseWithEntry,
                        entryUuid = newEntry.uuid,
                        itemType = item.itemType,
                        updates = photoUpdates
                    )
                    pendingChangeSets += buildSecureItemPhotoChangeSets(
                        databaseId = loaded.database.id,
                        entryUuid = newEntry.uuid,
                        baseFingerprint = buildConflictEntrySignature(newEntry),
                        changes = photoSync.changes
                    )
                    photoSync.database
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun updatePasskey(
        databaseId: Long,
        passkey: PasskeyEntry
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            mutateDatabase(databaseId) { loaded ->
                val updateResult = updatePasskeyInternal(
                    keePassDatabase = loaded.keePassDatabase,
                    databaseId = loaded.database.id,
                    passkey = passkey
                )
                val pendingChangeSets = updateResult.changeSets.toMutableList()
                val updatedDatabase = if (updateResult.changed) {
                    updateResult.database
                } else {
                    val newEntry = buildPasskeyEntry(passkey)
                    buildCreateEntryChangeSet(
                        database = loaded.keePassDatabase,
                        databaseId = loaded.database.id,
                        target = KeePassChangeTarget.PASSKEY,
                        entry = newEntry,
                        targetGroupPath = passkey.keepassGroupPath
                    )?.let { pendingChangeSets += it }
                    val updatedRoot = addEntryToGroupPath(
                        rootGroup = loaded.keePassDatabase.content.group,
                        groupPath = passkey.keepassGroupPath,
                        entry = newEntry
                    )
                    loaded.keePassDatabase.modifyParentGroup { updatedRoot }
                }
                MutationPlan(
                    updatedDatabase = updatedDatabase,
                    result = Unit,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, pendingChangeSets, workingRevision)
                    }
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun resolveRestoreTarget(
        databaseId: Long,
        preferredGroupPath: String?,
        preferredGroupUuid: String? = null
    ): Result<KeePassRestoreTarget> = withContext(Dispatchers.IO) {
        try {
            val (_, _, keePassDatabase) = loadDatabase(databaseId)
            val groupContextIndex = buildGroupTraversalContextIndex(keePassDatabase)
            val preferredUuid = parseUuid(preferredGroupUuid)
            if (preferredUuid != null) {
                val preferredContext = groupContextIndex[preferredUuid]
                if (preferredContext != null) {
                    val preferredInRecycle = preferredContext.isInRecycleBinByMeta
                    if (!preferredInRecycle) {
                        return@withContext Result.success(
                            KeePassRestoreTarget(
                                groupPath = preferredContext.pathKey,
                                groupUuid = preferredContext.groupUuid.toString()
                            )
                        )
                    }
                }
            }
            if (preferredGroupPath.isNullOrBlank()) {
                return@withContext Result.success(KeePassRestoreTarget(groupPath = null, groupUuid = null))
            }
            val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
            if (recycleBinUuid != null) {
                val inRecycleByMeta = isGroupPathInRecycleBinByMeta(
                    group = keePassDatabase.content.group,
                    currentPathKey = null,
                    targetPathKey = preferredGroupPath,
                    recycleBinUuid = recycleBinUuid,
                    parentInRecycleBin = false
                )
                if (inRecycleByMeta != null) {
                    val resolvedPath = if (inRecycleByMeta) null else preferredGroupPath
                    val resolvedUuid = resolvedPath?.let {
                        findGroupUuidByPath(
                            group = keePassDatabase.content.group,
                            currentPathKey = null,
                            targetPathKey = it
                        )?.toString()
                    }
                    return@withContext Result.success(
                        KeePassRestoreTarget(
                            groupPath = resolvedPath,
                            groupUuid = resolvedUuid
                        )
                    )
                }
            }
            Result.success(
                KeePassRestoreTarget(
                    groupPath = preferredGroupPath,
                    groupUuid = findGroupUuidByPath(
                        group = keePassDatabase.content.group,
                        currentPathKey = null,
                        targetPathKey = preferredGroupPath
                    )?.toString()
                )
            )
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun resolveRestoreTargetForPassword(
        databaseId: Long,
        target: PasswordEntry
    ): Result<KeePassRestoreTarget> = withContext(Dispatchers.IO) {
        try {
            val (_, _, keePassDatabase) = loadDatabase(databaseId)
            val groupContextIndex = buildGroupTraversalContextIndex(keePassDatabase)
            val (entries, hasRecycleBinMeta) = collectEntryContexts(keePassDatabase)
            val resolutionContext = KeePassFieldReferenceResolver.buildContext(entries.map { it.entry })
            val matched = entries.firstOrNull { context ->
                matchesPasswordEntry(context.entry, target, resolutionContext)
            }
                ?: return@withContext resolveRestoreTarget(
                    databaseId = databaseId,
                    preferredGroupPath = target.keepassGroupPath,
                    preferredGroupUuid = target.keepassGroupUuid
                )
            Result.success(
                resolveRestoreTargetFromEntryContext(
                    entryContext = matched,
                    hasRecycleBinMeta = hasRecycleBinMeta,
                    groupContextIndex = groupContextIndex
                )
            )
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun resolveRestoreGroupPathForPassword(
        databaseId: Long,
        target: PasswordEntry
    ): Result<String?> = withContext(Dispatchers.IO) {
        resolveRestoreTargetForPassword(databaseId, target).map { it.groupPath }
    }

    suspend fun resolveRestoreTargetForSecureItem(
        databaseId: Long,
        target: SecureItem
    ): Result<KeePassRestoreTarget> = withContext(Dispatchers.IO) {
        try {
            val (_, _, keePassDatabase) = loadDatabase(databaseId)
            val groupContextIndex = buildGroupTraversalContextIndex(keePassDatabase)
            val (entries, hasRecycleBinMeta) = collectEntryContexts(keePassDatabase)
            val resolutionContext = KeePassFieldReferenceResolver.buildContext(entries.map { it.entry })
            val matched = entries.firstOrNull { context ->
                matchesSecureItemEntry(context.entry, target, resolutionContext)
            }
                ?: return@withContext resolveRestoreTarget(
                    databaseId = databaseId,
                    preferredGroupPath = target.keepassGroupPath,
                    preferredGroupUuid = target.keepassGroupUuid
                )
            Result.success(
                resolveRestoreTargetFromEntryContext(
                    entryContext = matched,
                    hasRecycleBinMeta = hasRecycleBinMeta,
                    groupContextIndex = groupContextIndex
                )
            )
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun resolveRestoreGroupPathForSecureItem(
        databaseId: Long,
        target: SecureItem
    ): Result<String?> = withContext(Dispatchers.IO) {
        resolveRestoreTargetForSecureItem(databaseId, target).map { it.groupPath }
    }

    suspend fun deleteSecureItems(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val removedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val deleteResult = applyEntryStructureChanges(
                    loaded = loaded,
                    targets = items,
                    targetType = KeePassChangeTarget.SECURE_ITEM,
                    operation = KeePassChangeOperation.PERMANENT_DELETE,
                    matcher = { entry, target, resolutionContext ->
                        matchesSecureItemEntry(entry, target, resolutionContext)
                    }
                )
                MutationPlan(
                    updatedDatabase = deleteResult.database,
                    result = deleteResult.changedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, deleteResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(removedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun deletePasskeys(
        databaseId: Long,
        passkeys: List<PasskeyEntry>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val removedCount = mutateDatabase(databaseId = databaseId) { loaded ->
                val deleteResult = applyEntryStructureChanges(
                    loaded = loaded,
                    targets = passkeys,
                    targetType = KeePassChangeTarget.PASSKEY,
                    operation = KeePassChangeOperation.PERMANENT_DELETE,
                    matcher = { entry, target, resolutionContext ->
                        matchesPasskeyEntry(entry, target, resolutionContext)
                    }
                )
                MutationPlan(
                    updatedDatabase = deleteResult.database,
                    result = deleteResult.changedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, deleteResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(removedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun moveSecureItemsToRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val movedCount = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val moveResult = applyEntryStructureChanges(
                    loaded = loaded,
                    targets = items,
                    targetType = KeePassChangeTarget.SECURE_ITEM,
                    operation = KeePassChangeOperation.MOVE_TO_RECYCLE_BIN,
                    matcher = { entry, target, resolutionContext ->
                        matchesSecureItemEntry(entry, target, resolutionContext)
                    }
                )
                MutationPlan(
                    updatedDatabase = moveResult.database,
                    result = moveResult.changedCount,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, moveResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(movedCount)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    suspend fun restoreSecureItemsFromRecycleBin(
        databaseId: Long,
        items: List<SecureItem>,
        forceSyncWrite: Boolean = false
    ): Result<Map<Long, KeePassRestoreTarget>> = withContext(Dispatchers.IO) {
        try {
            val restoredTargets = mutateDatabase(
                databaseId = databaseId,
                forceSyncWrite = forceSyncWrite
            ) { loaded ->
                val restoreResult = restoreEntriesFromRecycleBin(
                    loaded = loaded,
                    targets = items,
                    targetType = KeePassChangeTarget.SECURE_ITEM,
                    matcher = { entry, target, resolutionContext ->
                        matchesSecureItemEntry(entry, target, resolutionContext)
                    },
                    roomId = { it.id },
                    preferredGroupPath = { it.keepassGroupPath },
                    preferredGroupUuid = { it.keepassGroupUuid }
                )
                MutationPlan(
                    updatedDatabase = restoreResult.database,
                    result = restoreResult.targetsByRoomId,
                    beforeRemoteUpload = { database, workingRevision ->
                        enqueuePendingChangeSetsIfRemote(database, restoreResult.changeSets, workingRevision)
                    },
                    afterWrite = { database, writtenDatabase ->
                        dao.updateEntryCount(database.id, countEntries(writtenDatabase.content.group))
                    }
                )
            }
            Result.success(restoredTargets)
        } catch (e: Exception) {
            Result.failure(normalizeError(e))
        }
    }

    internal fun buildEntry(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Entry {
        val base = Entry(
            uuid = parseUuid(entry.keepassEntryUuid) ?: UUID.randomUUID(),
            fields = buildEntryFields(entry, plainPassword, customFields)
        )
        return nativeMutation.initializeEntry(applyPasswordEntryPresentation(base, entry))
    }

    private fun buildUpdatedEntry(
        existingEntry: Entry,
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Entry {
        val base = buildPasswordEntryFieldPatch(entry, plainPassword, customFields, existingEntry).applyTo(existingEntry)
        return applyPasswordEntryPresentation(base, entry)
    }

    private fun buildPasswordEntryFieldPatch(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList(),
        existingEntry: Entry? = null
    ): KeePassEntryFieldPatch {
        val replacementFields = buildEntryFields(entry, plainPassword, customFields)
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = { name ->
                KeePassFieldRegistry.isPasswordEntryOverlayField(name) ||
                    (entry.isApiKeyEntry() && takagi.ru.monica.data.model.ApiKeyEntryFields.owns(name)) ||
                    (entry.loginType == takagi.ru.monica.data.model.GpgEntryFields.TYPE &&
                        takagi.ru.monica.data.model.GpgEntryFields.owns(name))
            },
            // Explicit removals survive conversion to the persisted change-set patch.
            // GPG custom fields are not part of the generic PASSWORD managed scope.
            removeFieldNames = replacementFields.keys + customFields.map { it.title.trim() } +
                (if (entry.isApiKeyEntry()) {
                    existingEntry?.fields?.keys.orEmpty().filter(takagi.ru.monica.data.model.ApiKeyEntryFields::owns)
                } else emptyList()) +
                if (entry.loginType == takagi.ru.monica.data.model.GpgEntryFields.TYPE) {
                    existingEntry?.fields?.keys.orEmpty().filter(takagi.ru.monica.data.model.GpgEntryFields::owns)
                } else emptyList()
        )
    }

    private fun applyPasswordEntryPresentation(
        base: Entry,
        entry: PasswordEntry
    ): Entry {
        // WIFI 条目使用 IRCommunication 图标，与 keepass2android WLan 模板一致。
        return if (entry.isWifiEntry()) {
            base.copy(icon = app.keemobile.kotpass.constants.PredefinedIcon.IRCommunication)
        } else {
            base
        }
    }

    internal fun buildPasskeyEntry(passkey: PasskeyEntry): Entry {
        return Entry(
            uuid = UUID.randomUUID(),
            fields = buildPasskeyFields(passkey),
            times = buildPasskeyTimeData(passkey)
        )
    }

    private fun buildPasskeyTimeData(passkey: PasskeyEntry): TimeData {
        val createdAt = epochMillisToInstant(passkey.createdAt)
        val lastUsedAt = epochMillisToInstant(passkey.lastUsedAt.takeIf { it > 0L } ?: passkey.createdAt)
        val now = Instant.now()
        return TimeData(
            creationTime = createdAt,
            lastAccessTime = lastUsedAt,
            lastModificationTime = now,
            locationChanged = now,
            expiryTime = createdAt,
            expires = false,
            usageCount = passkey.useCount
        )
    }

    private fun epochMillisToInstant(value: Long): Instant {
        return runCatching { Instant.ofEpochMilli(value.takeIf { it > 0L } ?: System.currentTimeMillis()) }
            .getOrDefault(Instant.now())
    }

    private fun buildEntryFields(
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): EntryFields {
        val monicaId = if (entry.id > 0) entry.id.toString() else ""
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain(entry.title),
            "UserName" to EntryValue.Plain(entry.username),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(plainPassword)),
            "URL" to EntryValue.Plain(entry.website),
            "Notes" to EntryValue.Plain(entry.notes)
        )
        if (monicaId.isNotEmpty()) {
            pairs.add(FIELD_MONICA_LOCAL_ID to EntryValue.Plain(monicaId))
        }
        appendPasswordCompatibilityFields(pairs, entry)
        // WIFI 条目：写入与 keepass2android WLan 模板兼容的标准字段（SSID + Password），
        // 并把 Monica 专属扩展 (安全类型、代理、IP 设置等) 塞到 MonicaWifiData JSON。
        // 这样其他 KeePass 客户端能读到 SSID/Password 基本信息，Monica 自己读回时
        // 则能无损还原完整配置。
        if (entry.isWifiEntry()) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("WIFI")
            val wifi = takagi.ru.monica.data.model.WifiData.fromJsonOrEmpty(entry.wifiMetadata)
            val ssidForStandardField = wifi.ssid.ifBlank { entry.title }
            if (ssidForStandardField.isNotBlank()) {
                pairs += FIELD_WIFI_SSID to EntryValue.Plain(ssidForStandardField)
            }
            if (entry.wifiMetadata.isNotBlank()) {
                pairs += FIELD_MONICA_WIFI_DATA to EntryValue.Plain(entry.wifiMetadata)
            }
        }
        if (entry.isSshKeyEntry()) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("SSH_KEY")
        }
        SshKeyDataCodec.decode(entry.sshKeyData)?.let { ssh ->
            if (ssh.algorithm.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_ALGORITHM to EntryValue.Plain(ssh.algorithm)
            }
            if (ssh.keySize > 0) {
                pairs += FIELD_MONICA_SSH_KEY_SIZE to EntryValue.Plain(ssh.keySize.toString())
            }
            if (ssh.publicKeyOpenSsh.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_PUBLIC_KEY to EntryValue.Plain(ssh.publicKeyOpenSsh)
            }
            if (ssh.privateKeyOpenSsh.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_PRIVATE_KEY to EntryValue.Encrypted(
                    EncryptedValue.fromString(ssh.privateKeyOpenSsh)
                )
            }
            if (ssh.fingerprintSha256.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_FINGERPRINT to EntryValue.Plain(ssh.fingerprintSha256)
            }
            if (ssh.comment.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_COMMENT to EntryValue.Plain(ssh.comment)
            }
            if (ssh.format.isNotBlank()) {
                pairs += FIELD_MONICA_SSH_FORMAT to EntryValue.Plain(ssh.format)
            }
        }
        appendKeePassCustomFields(pairs, customFields)
        return EntryFields.of(*pairs.toTypedArray())
    }

    private fun appendPasswordCompatibilityFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        entry: PasswordEntry
    ) {
        fun addPlain(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Plain(value)
            }
        }

        fun addProtected(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Encrypted(EncryptedValue.fromString(value))
            }
        }

        addPlain(FIELD_APP_PACKAGE_NAME, entry.appPackageName)
        addPlain(FIELD_APP_NAME, entry.appName)
        addPlain(FIELD_EMAIL, entry.email)
        addPlain(FIELD_PHONE, entry.phone)
        addPlain(FIELD_ADDRESS_LINE, entry.addressLine)
        addPlain(FIELD_CITY, entry.city)
        addPlain(FIELD_STATE, entry.state)
        addPlain(FIELD_POSTAL_CODE, entry.zipCode)
        addPlain(FIELD_COUNTRY, entry.country)
        addProtected(FIELD_CARD_NUMBER, entry.creditCardNumber)
        addPlain(FIELD_CARD_HOLDER, entry.creditCardHolder)
        addPlain(FIELD_CARD_EXPIRY, entry.creditCardExpiry)
        addProtected(FIELD_CARD_CVV, entry.creditCardCVV)

        if (entry.loginType.equals("SSO", ignoreCase = true)) {
            pairs += FIELD_MONICA_LOGIN_TYPE to EntryValue.Plain("SSO")
            addPlain(FIELD_SSO_PROVIDER, entry.ssoProvider)
            entry.ssoRefEntryId?.let { addPlain(FIELD_MONICA_SSO_REF_ENTRY_ID, it.toString()) }
        }
    }

    private fun appendKeePassCustomFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        customFields: List<KeePassCustomFieldData>
    ) {
        val usedKeys = pairs.mapTo(mutableSetOf()) { it.first.trim().lowercase(Locale.ROOT) }
        customFields
            .asSequence()
            .sortedWith(compareBy<KeePassCustomFieldData> { it.sortOrder }.thenBy { it.title })
            .forEach { field ->
                val key = field.title
                if (key.isBlank() || key.startsWith("_etm_")) return@forEach
                if (!usedKeys.add(key.lowercase(Locale.ROOT))) return@forEach
                val value = if (field.isProtected) {
                    EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                } else {
                    EntryValue.Plain(field.value)
                }
                pairs += key to value
            }
    }

    private fun buildPasskeyFields(
        passkey: PasskeyEntry,
        existingEntry: Entry? = null
    ): EntryFields {
        val readableTitle = passkey.rpName.ifBlank { passkey.rpId }.ifBlank { "Passkey" }
        val portablePasskey = passkey.copy(
            privateKeyAlias = PasskeyPrivateKeyStore.resolve(context, passkey.privateKeyAlias).orEmpty()
        )
        val payload = KeePassPasskeySyncCodec.encode(portablePasskey)
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain("$readableTitle [Passkey]"),
            "UserName" to EntryValue.Plain(passkey.userName.ifBlank { passkey.userDisplayName }),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("")),
            "URL" to EntryValue.Plain(
                when {
                    passkey.rpId.isBlank() -> ""
                    "://" in passkey.rpId -> passkey.rpId
                    else -> "https://${passkey.rpId}"
                }
            ),
            "Notes" to EntryValue.Plain(passkey.notes),
            FIELD_MONICA_PASSKEY_CREDENTIAL_ID to EntryValue.Plain(passkey.credentialId),
            FIELD_MONICA_PASSKEY_MODE to EntryValue.Plain(PasskeyEntry.MODE_KEEPASS_COMPAT),
            FIELD_MONICA_PASSKEY_DATA to EntryValue.Encrypted(EncryptedValue.fromString(payload))
        )
        pairs += KeePassDxPasskeyCodec.buildCustomFieldPairs(
            passkey = passkey,
            existingFieldValue = { fieldName ->
                existingEntry?.let { getFieldValue(it, fieldName) }.orEmpty()
            },
            exportPrivateKeyPem = { keyMaterial ->
                PasskeyPrivateKeyStore.exportPem(context, keyMaterial)
            }
        )
        return EntryFields.of(*pairs.toTypedArray())
    }

    private fun buildUpdatedPasskeyEntry(
        existingEntry: Entry,
        passkey: PasskeyEntry
    ): Entry {
        return buildPasskeyEntryFieldPatch(passkey, existingEntry).applyTo(existingEntry)
    }

    private fun buildPasskeyEntryFieldPatch(
        passkey: PasskeyEntry,
        existingEntry: Entry? = null
    ): KeePassEntryFieldPatch {
        val replacementFields = preserveExistingLoginFields(buildPasskeyFields(passkey, existingEntry), existingEntry)
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = KeePassFieldRegistry::isPasskeyEntryOverlayField,
            removeFieldNames = replacementFields.keys
        )
    }

    internal fun buildSecureItemEntry(item: SecureItem): Entry {
        return nativeMutation.initializeEntry(
            Entry(
                uuid = parseUuid(item.keepassEntryUuid) ?: UUID.randomUUID(),
                fields = buildSecureItemFields(item)
            )
        )
    }

    private fun buildUpdatedSecureItemEntry(
        existingEntry: Entry,
        item: SecureItem
    ): Entry {
        return buildSecureItemEntryFieldPatch(item, existingEntry).applyTo(existingEntry)
    }

    private fun buildSecureItemEntryFieldPatch(item: SecureItem, existingEntry: Entry? = null): KeePassEntryFieldPatch {
        if (item.itemType == ItemType.TOTP && existingEntry != null &&
            !getFieldValue(existingEntry, FIELD_MONICA_ITEM_TYPE).equals("TOTP", ignoreCase = true)) {
            // A native login can also carry OTP and passkey credentials. Editing its OTP is
            // a field patch, never a conversion of the entry into a Monica-only secure item.
            val portable = portableSecureItemDataForKeePass(item)
            val fields = mutableListOf<Pair<String, EntryValue>>()
            appendKeePassTotpFields(fields, item, portable)
            require(fields.isNotEmpty()) { "Authenticator data cannot be represented as KeePass OTP fields" }
            fields += FIELD_MONICA_TOTP_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portable))
            return KeePassEntryFieldPatch.fromEntryFields(
                replacementFields = EntryFields.of(*fields.toTypedArray()),
                removeManagedField = { false },
                removeFieldNames = existingEntry.fields.keys.filter(KeePassTotpCodec::isOtpField) + fields.map { it.first }
            )
        }
        val replacementFields = preserveExistingLoginFields(buildSecureItemFields(item), existingEntry)
        val removeManagedField = if (item.itemType == ItemType.TOTP) {
            { name: String ->
                KeePassFieldRegistry.isSecureItemOverlayField(name) ||
                    KeePassFieldRegistry.isKeePassTotpField(name)
            }
        } else {
            KeePassFieldRegistry::isSecureItemOverlayField
        }
        return KeePassEntryFieldPatch.fromEntryFields(
            replacementFields = replacementFields,
            removeManagedField = removeManagedField,
            removeFieldNames = replacementFields.keys + if (item.itemType == ItemType.TOTP)
                existingEntry?.fields?.keys.orEmpty().filter(KeePassTotpCodec::isOtpField) else emptyList()
        )
    }

    private fun preserveExistingLoginFields(fields: EntryFields, existing: Entry?): EntryFields {
        if (existing == null) return fields
        return EntryFields.of(*fields.filterNot { (name, value) ->
            name in setOf("UserName", "Password", "URL") && value.content.isEmpty() && existing.fields.containsKey(name)
        }.map { it.key to it.value }.toTypedArray())
    }

    private suspend fun buildSecureItemPhotoUpdates(
        item: SecureItem
    ): Map<
        KeePassSecureItemPhotoAttachments.Slot,
        KeePassSecureItemPhotoAttachments.SlotUpdate
    > {
        if (item.itemType != ItemType.BANK_CARD && item.itemType != ItemType.DOCUMENT) {
            return emptyMap()
        }
        val paths = decodeSecureItemImagePaths(item.imagePaths)
            ?: return KeePassSecureItemPhotoAttachments.Slot.entries.associateWith {
                KeePassSecureItemPhotoAttachments.SlotUpdate.Preserve
            }

        return KeePassSecureItemPhotoAttachments.Slot.entries.associateWith { slot ->
            val index = when (slot) {
                KeePassSecureItemPhotoAttachments.Slot.FRONT -> 0
                KeePassSecureItemPhotoAttachments.Slot.BACK -> 1
            }
            val localFileName = paths.getOrNull(index).orEmpty()
            if (localFileName.isBlank()) {
                KeePassSecureItemPhotoAttachments.SlotUpdate.Remove
            } else {
                val bytes = imageManager.readImageBytes(localFileName)
                if (bytes != null) {
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Replace(bytes)
                } else {
                    Log.w(
                        TAG,
                        "Secure item photo cache unavailable; preserving existing KDBX binary " +
                            "itemId=${item.id} type=${item.itemType} slot=$slot"
                    )
                    KeePassSecureItemPhotoAttachments.SlotUpdate.Preserve
                }
            }
        }
    }

    private fun decodeSecureItemImagePaths(raw: String): List<String>? {
        if (raw.isBlank()) return listOf("", "")
        return runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
    }

    private suspend fun hydrateSecureItemImagePaths(
        itemType: ItemType,
        entry: Entry,
        keePassDatabase: KeePassDatabase,
        legacyImagePaths: String
    ): String {
        if (itemType != ItemType.BANK_CARD && itemType != ItemType.DOCUMENT) {
            return legacyImagePaths
        }
        val managedPhotos = KeePassSecureItemPhotoAttachments.readManagedPhotos(
            database = keePassDatabase,
            entry = entry,
            itemType = itemType
        )
        if (managedPhotos.isEmpty()) return legacyImagePaths

        val legacyPaths = decodeSecureItemImagePaths(legacyImagePaths).orEmpty()
        val resolvedPaths = KeePassSecureItemPhotoAttachments.Slot.entries.map { slot ->
            val index = when (slot) {
                KeePassSecureItemPhotoAttachments.Slot.FRONT -> 0
                KeePassSecureItemPhotoAttachments.Slot.BACK -> 1
            }
            val legacyPath = legacyPaths.getOrNull(index).orEmpty()
            val managedPhoto = managedPhotos[slot]
            if (managedPhoto == null) {
                ""
            } else {
                imageManager.saveImageBytes(
                    bytes = managedPhoto.bytes,
                    stableId = managedPhoto.hashHex
                ) ?: legacyPath
            }
        }
        return Json.encodeToString(resolvedPaths)
    }

    private fun buildSecureItemPhotoChangeSets(
        databaseId: Long,
        entryUuid: UUID,
        baseFingerprint: String,
        changes: List<KeePassSecureItemPhotoAttachments.Change>
    ): List<KeePassChangeSet> {
        return changes.map { change ->
            val contentBase64 = if (change.operation == KeePassChangeOperation.ADD_ATTACHMENT) {
                Base64.encodeToString(requireNotNull(change.bytes), Base64.NO_WRAP)
            } else {
                null
            }
            KeePassChangeSet(
                databaseId = databaseId,
                target = KeePassChangeTarget.SECURE_ITEM,
                operation = change.operation,
                entryUuid = entryUuid.toString(),
                baseFingerprint = baseFingerprint,
                attachmentPatch = KeePassAttachmentChangePatch(
                    fileName = change.fileName,
                    binaryHash = change.binaryHash,
                    protected = change.protected,
                    compressed = change.compressed,
                    contentBase64 = contentBase64
                )
            )
        }
    }

    private fun buildSecureItemFields(item: SecureItem): EntryFields {
        val monicaId = if (item.id > 0) item.id.toString() else ""
        val portableItemData = portableSecureItemDataForKeePass(item)
        val noteForExternal = if (item.itemType == ItemType.NOTE) {
            val decoded = NoteContentCodec.decodeFromItem(item)
            NoteContentCodec.toExternalReadableContent(decoded.content)
        } else {
            item.notes
        }
        val pairs = mutableListOf<Pair<String, EntryValue>>(
            "Title" to EntryValue.Plain(item.title),
            "UserName" to EntryValue.Plain(""),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("")),
            "URL" to EntryValue.Plain(""),
            "Notes" to EntryValue.Plain(noteForExternal),
            FIELD_MONICA_ITEM_TYPE to EntryValue.Plain(item.itemType.name),
            FIELD_MONICA_IMAGE_PATHS to EntryValue.Plain(item.imagePaths),
            FIELD_MONICA_IS_FAVORITE to EntryValue.Plain(item.isFavorite.toString())
        )
        if (item.itemType == ItemType.BANK_CARD) {
            CardWalletDataCodec.parseBankCardData(portableItemData)?.let { cardData ->
                appendBankCardFields(pairs, cardData)
            }
        } else {
            pairs += FIELD_MONICA_ITEM_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portableItemData))
            if (item.itemType == ItemType.TOTP) {
                appendKeePassTotpFields(pairs, item, portableItemData)
            }
        }
        if (monicaId.isNotEmpty()) {
            pairs.add(FIELD_MONICA_ITEM_ID to EntryValue.Plain(monicaId))
        }
        return EntryFields.of(*pairs.toTypedArray())
    }

    private fun appendKeePassTotpFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        item: SecureItem,
        portableItemData: String
    ) {
        val totpData = TotpDataResolver.parseStoredItemData(
            itemData = portableItemData,
            fallbackIssuer = item.title,
            fallbackAccountName = item.title
        ) ?: return
        val fields = KeePassTotpCodec.toKeePassFields(totpData, item.title)
        fields.forEach { (name, value) ->
            val entryValue = if (KeePassTotpCodec.isSecretField(name))
                EntryValue.Encrypted(EncryptedValue.fromString(value)) else EntryValue.Plain(value)
            pairs += name to entryValue
        }
    }

    private fun appendBankCardFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        cardData: BankCardData
    ) {
        fun addPlain(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Plain(value)
            }
        }

        fun addProtected(name: String, value: String) {
            if (value.isNotBlank()) {
                pairs += name to EntryValue.Encrypted(EncryptedValue.fromString(value))
            }
        }

        val billingAddressDisplay = CardWalletDataCodec.parseBillingAddress(cardData.billingAddress)
            .formatForDisplay()
            .ifBlank { cardData.billingAddress }

        addProtected(FIELD_CARD_NUMBER, cardData.cardNumber)
        addPlain(FIELD_CARD_HOLDER, cardData.cardholderName)
        addPlain("Expiry Month", cardData.expiryMonth)
        addPlain("Expiry Year", cardData.expiryYear)
        addProtected(FIELD_CARD_CVV, cardData.cvv)
        addPlain(FIELD_BANK_NAME, cardData.bankName)
        addPlain(FIELD_CARD_TYPE, cardData.cardType.name)
        addPlain(FIELD_BILLING_ADDRESS, billingAddressDisplay)
        addPlain(FIELD_BRAND, cardData.brand)
        addPlain(FIELD_NICKNAME, cardData.nickname)
        addPlain(FIELD_VALID_FROM_MONTH, cardData.validFromMonth)
        addPlain(FIELD_VALID_FROM_YEAR, cardData.validFromYear)
        addProtected(FIELD_PIN, cardData.pin)
        addProtected(FIELD_IBAN, cardData.iban)
        addProtected(FIELD_SWIFT_BIC, cardData.swiftBic)
        addProtected(FIELD_ROUTING_NUMBER, cardData.routingNumber)
        addProtected(FIELD_ACCOUNT_NUMBER, cardData.accountNumber)
        addPlain(FIELD_BRANCH_CODE, cardData.branchCode)
        addPlain(FIELD_CURRENCY, cardData.currency)
        addPlain(FIELD_CUSTOMER_SERVICE_PHONE, cardData.customerServicePhone)
        appendSecureItemCustomFields(pairs, cardData.customFields)
    }

    private fun appendSecureItemCustomFields(
        pairs: MutableList<Pair<String, EntryValue>>,
        customFields: List<SecureCustomField>
    ) {
        val usedKeys = pairs.mapTo(mutableSetOf()) { it.first.trim().lowercase(Locale.ROOT) }
        customFields
            .asSequence()
            .filter { it.isValid() }
            .forEach { field ->
                val key = field.label.trim()
                if (key.isBlank() || key.startsWith("_etm_")) return@forEach
                if (!usedKeys.add(key.lowercase(Locale.ROOT))) return@forEach
                val value = if (field.type == SecureCustomFieldType.HIDDEN) {
                    EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                } else {
                    EntryValue.Plain(field.value)
                }
                pairs += key to value
            }
    }

    private fun portableSecureItemDataForKeePass(item: SecureItem): String {
        val itemData = item.itemData
        if (itemData.isBlank() || !securityManager.looksLikeMonicaCiphertext(itemData)) {
            return itemData
        }
        return runCatching { securityManager.decryptData(itemData) }.getOrElse { error ->
            throw IllegalStateException(
                "Cannot write encrypted secure item data to KeePass for itemId=${item.id}",
                error
            )
        }
    }

    private fun updateEntry(
        keePassDatabase: KeePassDatabase,
        databaseId: Long?,
        entry: PasswordEntry,
        plainPassword: String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): KeePassEntryUpdateResult {
        val resolutionContext = buildResolutionContext(keePassDatabase)
        val (entryContexts, _) = collectEntryContexts(keePassDatabase)
        val matchedContext = entryContexts.firstOrNull { context ->
            matchesPasswordEntry(context.entry, entry, resolutionContext)
        }
        if (matchedContext == null) {
            return KeePassEntryUpdateResult(
                database = keePassDatabase,
                changed = false,
                changeSets = emptyList()
            )
        }

        val fieldPatch = buildPasswordEntryFieldPatch(entry, plainPassword, customFields, matchedContext.entry)
        val resolvedDatabaseId = requireNotNull(databaseId) {
            "Foreground KeePass password update requires a database id"
        }
        val changeSets = buildFieldUpdateChangeSets(
            database = keePassDatabase,
            databaseId = resolvedDatabaseId,
            target = KeePassChangeTarget.PASSWORD,
            matchedContext = matchedContext,
            targetGroupPath = entry.keepassGroupPath,
            fieldPatch = fieldPatch.toChangePatch(
                managedScope = KeePassManagedFieldScope.PASSWORD,
                baseEntry = matchedContext.entry
            ),
            includeMoveChange = true
        )
        val updatedDatabase = KeePassChangeSetApplier().applyAll(
            database = keePassDatabase,
            changes = changeSets
        ).updatedDatabase
        return KeePassEntryUpdateResult(
            database = updatedDatabase,
            changed = true,
            changeSets = changeSets
        )
    }

    private fun updateSecureItemInternal(
        keePassDatabase: KeePassDatabase,
        databaseId: Long?,
        item: SecureItem,
        photoUpdates: Map<
            KeePassSecureItemPhotoAttachments.Slot,
            KeePassSecureItemPhotoAttachments.SlotUpdate
        > = emptyMap()
    ): KeePassEntryUpdateResult {
        val resolutionContext = buildResolutionContext(keePassDatabase)
        val (entryContexts, _) = collectEntryContexts(keePassDatabase)
        val matchedContext = entryContexts.firstOrNull { context ->
            matchesSecureItemEntry(context.entry, item, resolutionContext)
        } ?: return KeePassEntryUpdateResult(
            database = keePassDatabase,
            changed = false,
            changeSets = emptyList()
        )
        val resolvedDatabaseId = requireNotNull(databaseId) {
            "Foreground KeePass secure-item update requires a database id"
        }
        val fieldPatch = buildSecureItemEntryFieldPatch(item, matchedContext.entry)
        val matchedEntry = matchedContext.entry
        val photoPreview = KeePassSecureItemPhotoAttachments.synchronize(
            database = keePassDatabase,
            entryUuid = matchedEntry.uuid,
            itemType = item.itemType,
            updates = photoUpdates
        )
        val fieldChangeSets = buildFieldUpdateChangeSets(
            database = keePassDatabase,
            databaseId = resolvedDatabaseId,
            target = KeePassChangeTarget.SECURE_ITEM,
            matchedContext = matchedContext,
            targetGroupPath = item.keepassGroupPath,
            fieldPatch = fieldPatch.toChangePatch(
                managedScope = if (item.itemType == ItemType.TOTP) KeePassManagedFieldScope.EXPLICIT_ONLY else KeePassManagedFieldScope.SECURE_ITEM,
                baseEntry = matchedContext.entry
            ),
            includeMoveChange = true
        )
        val photoChangeSets = buildSecureItemPhotoChangeSets(
            databaseId = resolvedDatabaseId,
            entryUuid = matchedEntry.uuid,
            baseFingerprint = buildConflictEntrySignature(matchedEntry),
            changes = photoPreview.changes
        )
        val changeSets = fieldChangeSets + photoChangeSets
        val updatedDatabase = KeePassChangeSetApplier().applyAll(
            database = keePassDatabase,
            changes = changeSets
        ).updatedDatabase
        return KeePassEntryUpdateResult(
            database = updatedDatabase,
            changed = true,
            changeSets = changeSets
        )
    }

    private fun updatePasskeyInternal(
        keePassDatabase: KeePassDatabase,
        databaseId: Long?,
        passkey: PasskeyEntry
    ): KeePassEntryUpdateResult {
        val resolutionContext = buildResolutionContext(keePassDatabase)
        val (entryContexts, _) = collectEntryContexts(keePassDatabase)
        val matchedContext = entryContexts.firstOrNull { context ->
            matchesPasskeyEntry(context.entry, passkey, resolutionContext)
        }
        if (matchedContext == null) {
            return KeePassEntryUpdateResult(
                database = keePassDatabase,
                changed = false,
                changeSets = emptyList()
            )
        }
        val existingEntry = matchedContext.entry
        val fieldPatch = buildPasskeyEntryFieldPatch(passkey, existingEntry)
        val resolvedDatabaseId = requireNotNull(databaseId) {
            "Foreground KeePass passkey update requires a database id"
        }
        val changeSets = buildFieldUpdateChangeSets(
            database = keePassDatabase,
            databaseId = resolvedDatabaseId,
            target = KeePassChangeTarget.PASSKEY,
            matchedContext = matchedContext,
            targetGroupPath = passkey.keepassGroupPath,
            fieldPatch = fieldPatch.toChangePatch(
                managedScope = KeePassManagedFieldScope.PASSKEY,
                baseEntry = matchedContext.entry
            ),
            includeMoveChange = true
        )
        val updatedDatabase = KeePassChangeSetApplier().applyAll(
            database = keePassDatabase,
            changes = changeSets
        ).updatedDatabase
        return KeePassEntryUpdateResult(
            database = updatedDatabase,
            changed = true,
            changeSets = changeSets
        )
    }

    private fun buildFieldUpdateChangeSets(
        database: KeePassDatabase,
        databaseId: Long?,
        target: KeePassChangeTarget,
        matchedContext: EntryTraversalContext?,
        targetGroupPath: String?,
        fieldPatch: KeePassFieldChangePatch,
        includeMoveChange: Boolean
    ): List<KeePassChangeSet> {
        if (databaseId == null || databaseId <= 0 || matchedContext == null) {
            return emptyList()
        }
        val baseFingerprint = buildConflictEntrySignature(matchedContext.entry)
        val entryUuid = matchedContext.entry.uuid.toString()
        val sourceGroupUuid = matchedContext.groupUuid?.toString()
        val changes = mutableListOf(
            KeePassChangeSet(
                databaseId = databaseId,
                target = target,
                operation = KeePassChangeOperation.FIELD_PATCH,
                entryUuid = entryUuid,
                baseFingerprint = baseFingerprint,
                baseGroupPath = matchedContext.groupPath,
                baseGroupUuid = sourceGroupUuid,
                fieldPatch = fieldPatch
            )
        )
        if (includeMoveChange && matchedContext.groupUuid != null) {
            val targetPath = targetGroupPath?.takeIf { it.isNotBlank() }
            val targetGroupUuid = if (targetPath == null) {
                database.content.group.uuid
            } else {
                findGroupUuidByPath(
                    group = database.content.group,
                    currentPathKey = null,
                    targetPathKey = targetPath
                )
            }
            if (targetGroupUuid != null && targetGroupUuid != matchedContext.groupUuid) {
                changes += KeePassChangeSet(
                    databaseId = databaseId,
                    target = target,
                    operation = KeePassChangeOperation.MOVE_ENTRY,
                    entryUuid = entryUuid,
                    baseFingerprint = baseFingerprint,
                    baseGroupPath = matchedContext.groupPath,
                    baseGroupUuid = sourceGroupUuid,
                    structurePatch = KeePassStructureChangePatch(
                        sourceGroupPath = matchedContext.groupPath,
                        sourceGroupUuid = sourceGroupUuid,
                        targetGroupPath = targetPath,
                        targetGroupUuid = targetGroupUuid.toString()
                    )
                )
            }
        }
        return changes
    }

    private fun buildCreateEntryChangeSet(
        database: KeePassDatabase,
        databaseId: Long,
        target: KeePassChangeTarget,
        entry: Entry,
        targetGroupPath: String?,
        targetGroupUuidOverride: UUID? = null,
    ): KeePassChangeSet? {
        val resolvedTargetPath = targetGroupPath?.takeIf { it.isNotBlank() }
        val targetGroupUuid = targetGroupUuidOverride ?: if (resolvedTargetPath == null) {
            database.content.group.uuid
        } else {
            findGroupUuidByPath(
                group = database.content.group,
                currentPathKey = null,
                targetPathKey = resolvedTargetPath
            )
        } ?: return null
        val referencedBinaryHashes = linkedSetOf<String>()
        val referencedCustomIconUuids = linkedSetOf<UUID>()
        val nativeEntry = entry.toEntryTreeSnapshot(
            referencedBinaryHashes = referencedBinaryHashes,
            referencedCustomIconUuids = referencedCustomIconUuids,
        )
        val binaryPool = referencedBinaryHashes.map { hashHex ->
            val hash = hashHex.toByteStringHash()
            val binary = database.binaries[hash]
                ?: throw IllegalStateException("KeePass binary pool is missing create-entry attachment: $hashHex")
            KeePassBinaryPoolItemPatch(
                hash = hashHex,
                protected = binary.memoryProtection,
                compressed = binary is BinaryData.Compressed,
                contentBase64 = Base64.encodeToString(
                    binary.rawContent,
                    Base64.NO_WRAP,
                ),
                rawStorageContent = true,
            )
        }
        val customIconPool = referencedCustomIconUuids.map { uuid ->
            val icon = database.content.meta.customIcons[uuid]
                ?: throw IllegalStateException("KeePass custom icon pool is missing create-entry icon: $uuid")
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(icon.data, Base64.NO_WRAP),
                name = icon.name,
                lastModifiedEpochMillis = icon.lastModified?.toEpochMilli(),
            )
        }
        return KeePassChangeSet(
            databaseId = databaseId,
            target = target,
            operation = KeePassChangeOperation.CREATE_ENTRY,
            entryUuid = entry.uuid.toString(),
            baseFingerprint = null,
            entryPatch = KeePassEntryCreatePatch(
                targetGroupPath = resolvedTargetPath,
                targetGroupUuid = targetGroupUuid.toString(),
                fields = entry.fields.map { (name, value) ->
                    takagi.ru.monica.keepass.KeePassFieldChange(
                        name = name,
                        value = runCatching { value.content }.getOrDefault(""),
                        protected = value is EntryValue.Encrypted
                    )
                },
                iconName = entry.icon?.name,
                nativeEntry = nativeEntry,
                binaryPool = binaryPool,
                customIconPool = customIconPool,
            ),
            databaseMetaPatch = database.content.meta.entryTemplatesGroup?.let { uuid ->
                KeePassDatabaseMetaPatch(
                    entryTemplatesGroupUuid = uuid.toString(),
                    entryTemplatesGroupChangedEpochMillis = database.content.meta.entryTemplatesGroupChanged?.toEpochMilli(),
                )
            },
        )
    }

    private fun removeEntry(
        keePassDatabase: KeePassDatabase,
        entry: PasswordEntry
    ): Pair<KeePassDatabase, Int> {
        val resolutionContext = buildResolutionContext(keePassDatabase)
        val matcher: (Entry) -> Boolean = { existing ->
            matchesPasswordEntry(existing, entry, resolutionContext)
        }
        val result = removeEntryInGroup(keePassDatabase.content.group, matcher)
        val updatedDatabase = if (result.second > 0) {
            keePassDatabase.modifyParentGroup { result.first }
        } else {
            keePassDatabase
        }
        return updatedDatabase to result.second
    }

    private fun removeSecureItem(
        keePassDatabase: KeePassDatabase,
        item: SecureItem
    ): Pair<KeePassDatabase, Int> {
        val resolutionContext = buildResolutionContext(keePassDatabase)
        val matcher: (Entry) -> Boolean = { existing ->
            matchesSecureItemEntry(existing, item, resolutionContext)
        }
        val result = removeEntryInGroup(keePassDatabase.content.group, matcher)
        val updatedDatabase = if (result.second > 0) {
            keePassDatabase.modifyParentGroup { result.first }
        } else {
            keePassDatabase
        }
        return updatedDatabase to result.second
    }

    private fun updateEntryInGroup(
        group: Group,
        matcher: (Entry) -> Boolean,
        updater: (Entry) -> Entry
    ): Pair<Group, Boolean> {
        var updated = false
        val newEntries = group.entries.map { entry ->
            if (!updated && matcher(entry)) {
                updated = true
                updater(entry)
            } else {
                entry
            }
        }
        val newGroups = group.groups.map { sub ->
            val result = updateEntryInGroup(sub, matcher, updater)
            if (result.second) {
                updated = true
            }
            result.first
        }
        return group.copy(entries = newEntries, groups = newGroups) to updated
    }

    private fun removeEntryInGroup(
        group: Group,
        matcher: (Entry) -> Boolean
    ): Pair<Group, Int> {
        val filteredEntries = group.entries.filterNot { matcher(it) }
        var removedCount = group.entries.size - filteredEntries.size
        val newGroups = group.groups.map { sub ->
            val result = removeEntryInGroup(sub, matcher)
            removedCount += result.second
            result.first
        }
        return group.copy(entries = filteredEntries, groups = newGroups) to removedCount
    }

    private fun removeAndCollectEntriesInGroup(
        group: Group,
        matcher: (Entry) -> Boolean,
        inRecycleBin: Boolean,
        recycleBinUuid: UUID?,
        removedEntries: MutableList<RemovedEntryContext>
    ): Pair<Group, Int> {
        val currentInRecycle = inRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        var removedCount = 0

        val keptEntries = mutableListOf<Entry>()
        group.entries.forEach { entry ->
            val shouldRemove = matcher(entry) && !currentInRecycle
            if (shouldRemove) {
                removedEntries += RemovedEntryContext(
                    entry = entry,
                    previousParentUuid = group.uuid
                )
                removedCount++
            } else {
                keptEntries += entry
            }
        }

        val newGroups = group.groups.map { sub ->
            val result = removeAndCollectEntriesInGroup(
                group = sub,
                matcher = matcher,
                inRecycleBin = currentInRecycle,
                recycleBinUuid = recycleBinUuid,
                removedEntries = removedEntries
            )
            removedCount += result.second
            result.first
        }

        return group.copy(entries = keptEntries, groups = newGroups) to removedCount
    }

    private data class KeePassRecycleRestoreResult(
        val database: KeePassDatabase,
        val targetsByRoomId: Map<Long, KeePassRestoreTarget>,
        val changeSets: List<KeePassChangeSet>
    )

    private data class KeePassStructureApplyResult(
        val database: KeePassDatabase,
        val changedCount: Int,
        val changeSets: List<KeePassChangeSet>
    )

    private data class KeePassEntryUpdateResult(
        val database: KeePassDatabase,
        val changed: Boolean,
        val changeSets: List<KeePassChangeSet>
    )

    private fun <T> applyEntryStructureChanges(
        loaded: LoadedDatabase,
        targets: List<T>,
        targetType: KeePassChangeTarget,
        operation: KeePassChangeOperation,
        matcher: (Entry, T, KeePassEntryResolutionContext) -> Boolean
    ): KeePassStructureApplyResult {
        require(
            operation == KeePassChangeOperation.MOVE_TO_RECYCLE_BIN ||
                operation == KeePassChangeOperation.PERMANENT_DELETE
        ) {
            "Unsupported KeePass structure operation for entry apply: $operation"
        }
        if (targets.isEmpty()) {
            return KeePassStructureApplyResult(
                database = loaded.keePassDatabase,
                changedCount = 0,
                changeSets = emptyList()
            )
        }

        val preparedDatabase = if (operation == KeePassChangeOperation.MOVE_TO_RECYCLE_BIN) {
            KeePassRecycleBinPolicy().ensure(loaded.keePassDatabase).database
        } else {
            loaded.keePassDatabase
        }
        val recycleBinUuid = if (operation == KeePassChangeOperation.MOVE_TO_RECYCLE_BIN) {
            resolveRecycleBinUuid(preparedDatabase.content.meta)
                ?: throw IllegalStateException("KeePass recycle bin unavailable after repair")
        } else {
            resolveRecycleBinUuid(preparedDatabase.content.meta)
        }
        val recycleBinPath = recycleBinUuid?.let {
            findGroupPathByUuid(
                group = preparedDatabase.content.group,
                currentPathKey = null,
                targetUuid = it
            )
        }
        if (operation == KeePassChangeOperation.MOVE_TO_RECYCLE_BIN && recycleBinPath == null) {
            throw IllegalStateException("KeePass recycle bin path unavailable")
        }

        val (entryContexts, hasRecycleBinMeta) = collectEntryContexts(preparedDatabase)
        val resolutionContext = KeePassFieldReferenceResolver.buildContext(entryContexts.map { it.entry })
        val applier = KeePassChangeSetApplier()
        var currentDatabase = preparedDatabase
        var changedCount = 0
        val appliedChangeSets = mutableListOf<KeePassChangeSet>()
        val usedEntryUuids = mutableSetOf<UUID>()

        targets.forEach { target ->
            val context = entryContexts.firstOrNull { candidate ->
                candidate.entry.uuid !in usedEntryUuids &&
                    candidate.matchesStructureOperationScope(
                        operation = operation,
                        hasRecycleBinMeta = hasRecycleBinMeta
                    ) &&
                    matcher(candidate.entry, target, resolutionContext)
            } ?: return@forEach
            val parentUuid = context.groupUuid
                ?: throw IllegalStateException("KeePass entry parent group unavailable for ${context.entry.uuid}")

            val structurePatch = when (operation) {
                KeePassChangeOperation.MOVE_TO_RECYCLE_BIN -> KeePassStructureChangePatch(
                    sourceGroupPath = context.groupPath,
                    sourceGroupUuid = parentUuid.toString(),
                    targetGroupPath = recycleBinPath,
                    targetGroupUuid = recycleBinUuid!!.toString(),
                    recycleBinGroupUuid = recycleBinUuid.toString(),
                    previousParentGroupUuid = parentUuid.toString()
                )
                KeePassChangeOperation.PERMANENT_DELETE -> KeePassStructureChangePatch(
                    sourceGroupPath = context.groupPath,
                    sourceGroupUuid = parentUuid.toString(),
                    recycleBinGroupUuid = recycleBinUuid?.toString(),
                    previousParentGroupUuid = context.entry.previousParentGroup?.toString()
                )
                else -> error("Unsupported KeePass structure operation: $operation")
            }

            val changeSet = KeePassChangeSet(
                databaseId = loaded.database.id,
                target = targetType,
                operation = operation,
                entryUuid = context.entry.uuid.toString(),
                baseFingerprint = buildConflictEntrySignature(context.entry),
                baseGroupPath = context.groupPath,
                baseGroupUuid = parentUuid.toString(),
                structurePatch = structurePatch
            )

            currentDatabase = applier.apply(currentDatabase, changeSet).updatedDatabase
            usedEntryUuids += context.entry.uuid
            appliedChangeSets += changeSet
            changedCount++
        }

        return KeePassStructureApplyResult(
            database = currentDatabase,
            changedCount = changedCount,
            changeSets = appliedChangeSets
        )
    }

    private fun EntryTraversalContext.matchesStructureOperationScope(
        operation: KeePassChangeOperation,
        hasRecycleBinMeta: Boolean
    ): Boolean {
        return when (operation) {
            KeePassChangeOperation.MOVE_TO_RECYCLE_BIN -> !isInKeePassRecycleBin(hasRecycleBinMeta)
            KeePassChangeOperation.PERMANENT_DELETE -> true
            else -> false
        }
    }

    private fun <T> restoreEntriesFromRecycleBin(
        loaded: LoadedDatabase,
        targets: List<T>,
        targetType: KeePassChangeTarget,
        matcher: (Entry, T, KeePassEntryResolutionContext) -> Boolean,
        roomId: (T) -> Long,
        preferredGroupPath: (T) -> String?,
        preferredGroupUuid: (T) -> String?
    ): KeePassRecycleRestoreResult {
        if (targets.isEmpty()) {
            return KeePassRecycleRestoreResult(
                database = loaded.keePassDatabase,
                targetsByRoomId = emptyMap(),
                changeSets = emptyList()
            )
        }

        val recycleBinUuid = resolveRecycleBinUuid(loaded.keePassDatabase.content.meta)
            ?: throw IllegalStateException("KeePass recycle bin unavailable")
        val (entryContexts, hasRecycleBinMeta) = collectEntryContexts(loaded.keePassDatabase)
        val resolutionContext = KeePassFieldReferenceResolver.buildContext(entryContexts.map { it.entry })
        val groupContextIndex = buildGroupTraversalContextIndex(loaded.keePassDatabase)
        val applier = KeePassChangeSetApplier()
        var currentDatabase = loaded.keePassDatabase
        val restoredTargets = linkedMapOf<Long, KeePassRestoreTarget>()
        val appliedChangeSets = mutableListOf<KeePassChangeSet>()
        val usedEntryUuids = mutableSetOf<UUID>()

        targets.forEach { target ->
            val targetRoomId = roomId(target)
            val context = entryContexts.firstOrNull { candidate ->
                candidate.entry.uuid !in usedEntryUuids &&
                    candidate.isInKeePassRecycleBin(hasRecycleBinMeta) &&
                    matcher(candidate.entry, target, resolutionContext)
            } ?: return@forEach

            val restoreTarget = resolveRestoreTargetFromEntryContext(
                entryContext = context,
                hasRecycleBinMeta = hasRecycleBinMeta,
                groupContextIndex = groupContextIndex
            ).withFallback(
                preferredGroupPath = preferredGroupPath(target),
                preferredGroupUuid = preferredGroupUuid(target),
                groupContextIndex = groupContextIndex
            )
            val previousParentUuid = context.entry.previousParentGroup
                ?: parseUuid(restoreTarget.groupUuid)
                ?: throw IllegalStateException("KeePass recycle bin restore target unavailable for ${context.entry.uuid}")

            val changeSet = KeePassChangeSet(
                databaseId = loaded.database.id,
                target = targetType,
                operation = KeePassChangeOperation.RESTORE_FROM_RECYCLE_BIN,
                entryUuid = context.entry.uuid.toString(),
                baseFingerprint = buildConflictEntrySignature(context.entry),
                baseGroupPath = context.groupPath,
                baseGroupUuid = context.groupUuid?.toString(),
                structurePatch = KeePassStructureChangePatch(
                    sourceGroupPath = context.groupPath,
                    sourceGroupUuid = context.groupUuid?.toString(),
                    targetGroupPath = restoreTarget.groupPath,
                    targetGroupUuid = restoreTarget.groupUuid ?: previousParentUuid.toString(),
                    recycleBinGroupUuid = recycleBinUuid.toString(),
                    previousParentGroupUuid = previousParentUuid.toString()
                )
            )

            currentDatabase = applier.apply(currentDatabase, changeSet).updatedDatabase
            usedEntryUuids += context.entry.uuid
            appliedChangeSets += changeSet
            restoredTargets[targetRoomId] = restoreTarget.copy(
                groupUuid = restoreTarget.groupUuid ?: previousParentUuid.toString()
            )
        }

        return KeePassRecycleRestoreResult(
            database = currentDatabase,
            targetsByRoomId = restoredTargets,
            changeSets = appliedChangeSets
        )
    }

    private fun EntryTraversalContext.isInKeePassRecycleBin(hasRecycleBinMeta: Boolean): Boolean {
        return isInRecycleBinByMeta
    }

    private fun KeePassRestoreTarget.withFallback(
        preferredGroupPath: String?,
        preferredGroupUuid: String?,
        groupContextIndex: Map<UUID, GroupTraversalContext>
    ): KeePassRestoreTarget {
        if (!groupUuid.isNullOrBlank() || !groupPath.isNullOrBlank()) {
            return this
        }
        val preferredUuid = parseUuid(preferredGroupUuid)
        if (preferredUuid != null) {
            val preferredContext = groupContextIndex[preferredUuid]
            if (preferredContext != null && !preferredContext.isInRecycleBinByMeta) {
                return KeePassRestoreTarget(
                    groupPath = preferredContext.pathKey,
                    groupUuid = preferredContext.groupUuid.toString()
                )
            }
        }
        if (!preferredGroupPath.isNullOrBlank() && groupContextIndex.values.none {
                it.pathKey == preferredGroupPath && it.isInRecycleBinByMeta
            }) {
            return KeePassRestoreTarget(
                groupPath = preferredGroupPath,
                groupUuid = preferredGroupUuid
            )
        }
        return this
    }

    private fun addEntryToGroupPath(
        rootGroup: Group,
        groupPath: String?,
        entry: Entry
    ): Group {
        val segments = decodeKeePassPathSegments(groupPath)
        if (segments.isEmpty()) {
            return rootGroup.copy(entries = rootGroup.entries + entry)
        }
        return addEntryToGroupPathSegments(rootGroup, segments, entry)
    }

    private fun addEntryToGroupPathSegments(
        group: Group,
        segments: List<String>,
        entry: Entry
    ): Group {
        if (segments.isEmpty()) {
            return group.copy(entries = group.entries + entry)
        }

        val childName = segments.first()
        val childIndex = group.groups.indexOfFirst { it.name == childName }
        val childGroup = if (childIndex >= 0) {
            group.groups[childIndex]
        } else {
            nativeMutation.initializeGroup(Group(uuid = UUID.randomUUID(), name = childName))
        }

        val updatedChild = addEntryToGroupPathSegments(
            group = childGroup,
            segments = segments.drop(1),
            entry = entry
        )

        val updatedGroups = group.groups.toMutableList()
        if (childIndex >= 0) {
            updatedGroups[childIndex] = updatedChild
        } else {
            updatedGroups.add(updatedChild)
        }
        return group.copy(groups = updatedGroups)
    }

    private fun matchByKey(
        entry: Entry,
        target: PasswordEntry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val title = getStandardTitle(entry, resolutionContext)
        val username = getStandardUsername(entry, resolutionContext)
        val url = getStandardUrl(entry, resolutionContext)
        return title.equals(target.title, true) &&
            username.equals(target.username, true) &&
            url.equals(target.website, true)
    }

    private fun matchesPasswordEntry(
        entry: Entry,
        target: PasswordEntry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val targetUuid = parseUuid(target.keepassEntryUuid)
        if (targetUuid != null) return entry.uuid == targetUuid
        val monicaId = getFieldValue(entry, FIELD_MONICA_LOCAL_ID, resolutionContext).toLongOrNull()
        if (monicaId != null && target.id > 0 && monicaId == target.id) {
            return true
        }
        return matchByKey(entry, target, resolutionContext)
    }

    private fun matchSecureItemByKey(
        entry: Entry,
        target: SecureItem,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val title = getFieldValue(entry, "Title", resolutionContext)
        val itemType = getFieldValue(entry, FIELD_MONICA_ITEM_TYPE, resolutionContext)
        return title.equals(target.title, true) &&
            itemType.equals(target.itemType.name, true)
    }

    private fun matchesSecureItemEntry(
        entry: Entry,
        target: SecureItem,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val targetUuid = parseUuid(target.keepassEntryUuid)
        if (targetUuid != null) return entry.uuid == targetUuid
        val monicaId = getFieldValue(entry, FIELD_MONICA_ITEM_ID, resolutionContext).toLongOrNull()
        if (monicaId != null && target.id > 0 && monicaId == target.id) {
            return true
        }
        return matchSecureItemByKey(entry, target, resolutionContext)
    }

    private fun matchesPasskeyEntry(
        entry: Entry,
        target: PasskeyEntry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val targetCredentialId = PasskeyCredentialIdCodec.normalize(target.credentialId) ?: target.credentialId
        val credentialId = getFieldValue(entry, FIELD_MONICA_PASSKEY_CREDENTIAL_ID, resolutionContext)
        if (credentialId.isNotBlank()) {
            val normalized = PasskeyCredentialIdCodec.normalize(credentialId) ?: credentialId
            if (normalized == targetCredentialId) {
                return true
            }
        }

        val keepassDxCredentialId = getFieldValue(entry, KeePassDxPasskeyCodec.FIELD_CREDENTIAL_ID, resolutionContext)
        if (keepassDxCredentialId.isNotBlank()) {
            val normalized = PasskeyCredentialIdCodec.normalize(keepassDxCredentialId) ?: keepassDxCredentialId
            if (normalized == targetCredentialId) {
                return true
            }
        }

        val payload = getFieldValue(entry, FIELD_MONICA_PASSKEY_DATA, resolutionContext)
        val decoded = KeePassPasskeySyncCodec.decode(
            raw = payload,
            databaseId = target.keepassDatabaseId ?: -1L,
            groupPath = target.keepassGroupPath,
            groupUuid = null
        )
        val decodedCredentialId = PasskeyCredentialIdCodec.normalize(decoded?.credentialId) ?: decoded?.credentialId
        return decodedCredentialId == targetCredentialId
    }

    private fun entryToData(
        entry: Entry,
        groupPath: String?,
        groupUuid: UUID?,
        isInRecycleBinByMeta: Boolean,
        hasRecycleBinMeta: Boolean,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): KeePassEntryData? = analyzePasswordEntry(
        entry = entry,
        groupPath = groupPath,
        groupUuid = groupUuid,
        isInRecycleBinByMeta = isInRecycleBinByMeta,
        hasRecycleBinMeta = hasRecycleBinMeta,
        resolutionContext = resolutionContext
    ).data

    private fun analyzePasswordEntry(
        entry: Entry,
        groupPath: String?,
        groupUuid: UUID?,
        isInRecycleBinByMeta: Boolean,
        hasRecycleBinMeta: Boolean,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): KeePassPasswordEntryAnalysis {
        fun result(
            data: KeePassEntryData? = null,
            skipReason: KeePassPasswordSkipReason? = null,
            hasPasskeyFields: Boolean,
            title: String,
            username: String,
            password: String,
            url: String,
            notes: String
        ) = KeePassPasswordEntryAnalysis(
            data = data,
            skipReason = skipReason,
            hasPasskeyFields = hasPasskeyFields,
            hasTitle = title.isNotBlank(),
            hasUsername = username.isNotBlank(),
            hasPassword = password.isNotBlank(),
            hasUrl = url.isNotBlank(),
            hasNotes = notes.isNotBlank(),
            fieldNames = entry.fields.keys.sorted()
        )

        val title = getStandardTitle(entry, resolutionContext)
        val username = getStandardUsername(entry, resolutionContext)
        val password = resolveEntryPassword(entry, resolutionContext)
        val url = getStandardUrl(entry, resolutionContext)
        val notes = getStandardNotes(entry, resolutionContext)
        val hasPasskeyFields = isPasskeyEntry(entry, resolutionContext)

        // Only hide an entry when its specialized projection can actually be read.
        if (resolveMonicaSecureItemPayload(entry, resolutionContext) != null) {
            return result(
                skipReason = KeePassPasswordSkipReason.MONICA_SECURE_ITEM,
                hasPasskeyFields = hasPasskeyFields,
                title = title,
                username = username,
                password = password,
                url = url,
                notes = notes
            )
        }
        if (
            hasPasskeyFields &&
            entryToPasskey(entry, -1L, groupPath, groupUuid, resolutionContext) != null &&
            username.isBlank() &&
            password.isBlank() &&
            url.isBlank() &&
            notes.isBlank()
        ) {
            return result(
                skipReason = KeePassPasswordSkipReason.PURE_PASSKEY,
                hasPasskeyFields = hasPasskeyFields,
                title = title,
                username = username,
                password = password,
                url = url,
                notes = notes
            )
        }
        if (isEnhancedEntryTemplate(entry, title, username, password, url, notes, resolutionContext)) {
            Log.d(TAG, "Skip KeePassXC template entry from password sync")
            return result(
                skipReason = KeePassPasswordSkipReason.TEMPLATE,
                hasPasskeyFields = hasPasskeyFields,
                title = title,
                username = username,
                password = password,
                url = url,
                notes = notes
            )
        }
        val sshKeyData = SshKeyDataCodec.encode(
            SshKeyData(
                algorithm = getFieldValue(entry, FIELD_MONICA_SSH_ALGORITHM, resolutionContext),
                keySize = getFieldValue(entry, FIELD_MONICA_SSH_KEY_SIZE, resolutionContext).toIntOrNull() ?: 0,
                publicKeyOpenSsh = getFieldValue(entry, FIELD_MONICA_SSH_PUBLIC_KEY, resolutionContext),
                privateKeyOpenSsh = getFieldValue(entry, FIELD_MONICA_SSH_PRIVATE_KEY, resolutionContext),
                fingerprintSha256 = getFieldValue(entry, FIELD_MONICA_SSH_FINGERPRINT, resolutionContext),
                comment = getFieldValue(entry, FIELD_MONICA_SSH_COMMENT, resolutionContext),
                format = getFieldValue(entry, FIELD_MONICA_SSH_FORMAT, resolutionContext)
                    .ifBlank { SshKeyData.FORMAT_OPENSSH }
            )
        )
        // Empty standard fields are valid: custom strings, binaries and history still belong to this UUID.
        val monicaId = getFieldValue(entry, FIELD_MONICA_LOCAL_ID, resolutionContext).toLongOrNull()
        val inRecycleBin = resolveRecycleBinFlag(
            groupPath = groupPath,
            isInRecycleBinByMeta = isInRecycleBinByMeta,
            hasRecycleBinMeta = hasRecycleBinMeta
        )
        // WIFI 识别：优先使用 Monica 自己写的元数据（包含安全类型/代理/IP 等），
        // 兜底看 keepass2android WLan 模板的 SSID 字段；两者都有的话 Monica 元
        // 数据更完整，优先用它。
        val monicaLoginType = getFieldValue(entry, FIELD_MONICA_LOGIN_TYPE, resolutionContext)
        val monicaWifiJson = getFieldValue(entry, FIELD_MONICA_WIFI_DATA, resolutionContext)
        val ssidField = getFieldValue(entry, FIELD_WIFI_SSID, resolutionContext)
        val appPackageName = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_APP_PACKAGE_NAME,
            "AppPackageName",
            "MonicaAppPackageName",
            "AndroidAppPackageName",
            "PackageName"
        )
        val appName = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_APP_NAME,
            "AppName",
            "MonicaAppName",
            "Application",
            "Application Name"
        )
        val email = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_EMAIL, "E-mail", "Mail")
        val phone = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_PHONE, "Phone Number", "Telephone")
        val addressLine = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_ADDRESS_LINE, "Address Line")
        val city = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CITY)
        val state = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_STATE, "Province")
        val zipCode = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_POSTAL_CODE, "PostalCode", "Zip Code", "ZipCode")
        val country = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_COUNTRY)
        val creditCardNumber = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_CARD_NUMBER,
            "CardNumber",
            "Credit Card Number",
            "CreditCardNumber"
        )
        val creditCardHolder = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_CARD_HOLDER,
            "CardHolder",
            "Credit Card Holder",
            "CreditCardHolder"
        )
        val creditCardExpiry = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_CARD_EXPIRY,
            "CardExpiry",
            "Expiration Date",
            "Expiry Date"
        )
        val creditCardCVV = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CARD_CVV, "CardCVV", "CVV", "CVC")
        val ssoProvider = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_SSO_PROVIDER,
            "SsoProvider",
            "MonicaSsoProvider"
        )
        val ssoRefEntryId = getFieldValueIgnoreCase(
            entry,
            resolutionContext,
            FIELD_MONICA_SSO_REF_ENTRY_ID,
            "SsoRefEntryId",
            "MonicaSsoRefId"
        ).toLongOrNull()
        val customFields = extractKeePassCustomFieldsForPasswordEntry(
            entry.fields.map { (key, value) ->
                KeePassRawStringField(
                    key = key,
                    value = getFieldValue(entry, key, resolutionContext),
                    isProtected = value is EntryValue.Encrypted
                )
            },
            preserveOpaqueMetadata = getFieldValue(entry, FIELD_MONICA_ITEM_TYPE, resolutionContext).isNotBlank() || hasPasskeyFields
        )
        val (resolvedLoginType, resolvedWifiJson) = when {
            getFieldValue(entry, "monica_gpg_type", resolutionContext) == "GPG_KEY" -> "GPG_KEY" to ""
            getFieldValue(entry, takagi.ru.monica.data.model.ApiKeyEntryFields.MARKER, resolutionContext) == takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE ->
                takagi.ru.monica.data.model.ApiKeyEntryFields.TYPE to ""
            monicaLoginType.equals("WIFI", ignoreCase = true) && monicaWifiJson.isNotBlank() ->
                "WIFI" to monicaWifiJson
            monicaLoginType.equals("WIFI", ignoreCase = true) -> {
                val wifi = takagi.ru.monica.data.model.WifiData(ssid = ssidField.ifBlank { title })
                "WIFI" to wifi.toJson()
            }
            ssidField.isNotBlank() -> {
                // 外部客户端（kp2a/KeePassXC 手动填 SSID 字段）写入的兼容条目。
                val wifi = takagi.ru.monica.data.model.WifiData(ssid = ssidField)
                "WIFI" to wifi.toJson()
            }
            monicaLoginType.equals("SSO", ignoreCase = true) || ssoProvider.isNotBlank() -> "SSO" to ""
            monicaLoginType.equals("SSH_KEY", ignoreCase = true) -> "SSH_KEY" to ""
            else -> "PASSWORD" to ""
        }
        val data = KeePassEntryData(
            title = title,
            username = username,
            password = password,
            url = url,
            notes = notes,
            appPackageName = appPackageName,
            appName = appName,
            email = email,
            phone = phone,
            addressLine = addressLine,
            city = city,
            state = state,
            zipCode = zipCode,
            country = country,
            creditCardNumber = creditCardNumber,
            creditCardHolder = creditCardHolder,
            creditCardExpiry = creditCardExpiry,
            creditCardCVV = creditCardCVV,
            sshKeyData = sshKeyData,
            monicaLocalId = monicaId,
            entryUuid = entry.uuid.toString(),
            groupPath = groupPath,
            groupUuid = groupUuid?.toString(),
            isInRecycleBin = inRecycleBin,
            loginType = resolvedLoginType,
            ssoProvider = ssoProvider,
            ssoRefEntryId = ssoRefEntryId,
            wifiMetadata = resolvedWifiJson,
            customFields = customFields,
            hasPasskeyFields = hasPasskeyFields
        )
        return result(
            data = data,
            hasPasskeyFields = hasPasskeyFields,
            title = title,
            username = username,
            password = password,
            url = url,
            notes = notes
        )
    }

    private fun buildPasswordEntrySkipSample(
        context: EntryTraversalContext,
        analysis: KeePassPasswordEntryAnalysis,
        reason: KeePassPasswordSkipReason
    ): String {
        val uuidSuffix = context.entry.uuid.toString().takeLast(8)
        val fields = analysis.fieldNames
            .take(18)
            .joinToString("|")
            .ifBlank { "-" }
        val moreFields = (analysis.fieldNames.size - 18).takeIf { it > 0 }?.let { "+$it" } ?: ""
        return "reason=$reason, uuidSuffix=$uuidSuffix, group=${context.groupPath ?: "<root>"}, " +
            "hasPasskey=${analysis.hasPasskeyFields}, hasTitle=${analysis.hasTitle}, " +
            "hasUser=${analysis.hasUsername}, hasPassword=${analysis.hasPassword}, " +
            "hasUrl=${analysis.hasUrl}, hasNotes=${analysis.hasNotes}, fields=$fields$moreFields"
    }

    private fun buildPasswordEntryData(
        databaseId: Long,
        databaseName: String,
        entries: List<EntryTraversalContext>,
        hasRecycleBinMeta: Boolean,
        resolutionContext: KeePassEntryResolutionContext?
    ): List<KeePassEntryData> {
        val skipCounts = mutableMapOf<KeePassPasswordSkipReason, Int>()
        var passkeyFieldCount = 0
        var importedWithPasskeyFields = 0
        val skippedSamples = mutableListOf<String>()
        val data = entries.mapNotNull { context ->
            val analysis = analyzePasswordEntry(
                entry = context.entry,
                groupPath = context.groupPath,
                groupUuid = context.groupUuid,
                isInRecycleBinByMeta = context.isInRecycleBinByMeta,
                hasRecycleBinMeta = hasRecycleBinMeta,
                resolutionContext = resolutionContext
            )
            if (analysis.hasPasskeyFields) {
                passkeyFieldCount++
            }
            if (analysis.data != null && analysis.hasPasskeyFields) {
                importedWithPasskeyFields++
            }
            analysis.skipReason?.let { reason ->
                skipCounts[reason] = (skipCounts[reason] ?: 0) + 1
                if (skippedSamples.size < 8) {
                    skippedSamples += buildPasswordEntrySkipSample(context, analysis, reason)
                }
            }
            analysis.data
        }
        Log.i(
            TAG,
            "KeePass password sync summary: databaseId=$databaseId, " +
                "total=${entries.size}, imported=${data.size}, passkeyFieldEntries=$passkeyFieldCount, " +
                "importedWithPasskeyFields=$importedWithPasskeyFields, " +
                "skippedSecureItem=${skipCounts[KeePassPasswordSkipReason.MONICA_SECURE_ITEM] ?: 0}, " +
                "skippedPurePasskey=${skipCounts[KeePassPasswordSkipReason.PURE_PASSKEY] ?: 0}, " +
                "skippedTemplate=${skipCounts[KeePassPasswordSkipReason.TEMPLATE] ?: 0}, " +
                "skippedEmpty=${skipCounts[KeePassPasswordSkipReason.EMPTY] ?: 0}"
        )
        if (data.isEmpty() || skipCounts.isNotEmpty()) {
            Log.i(TAG, "KeePass password sync skippedSampleCount=${skippedSamples.size}")
        }
        return data
    }

    private suspend fun entryToSecureItemData(
        entry: Entry,
        keePassDatabase: KeePassDatabase,
        databaseId: Long,
        groupPath: String?,
        groupUuid: UUID?,
        isInRecycleBinByMeta: Boolean,
        hasRecycleBinMeta: Boolean,
        allowedTypes: Set<ItemType>?,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): KeePassSecureItemData? {
        val payload = resolveMonicaSecureItemPayload(entry, resolutionContext)
        if (payload != null && (allowedTypes == null || payload.type in allowedTypes)) {
            val itemType = payload.type

            val itemData = payload.data

            val title = getStandardTitle(entry, resolutionContext)
            val notes = getStandardNotes(entry, resolutionContext)
            val legacyImagePaths = getFieldValue(entry, FIELD_MONICA_IMAGE_PATHS, resolutionContext)
            val imagePaths = hydrateSecureItemImagePaths(
                itemType = itemType,
                entry = entry,
                keePassDatabase = keePassDatabase,
                legacyImagePaths = legacyImagePaths
            )
            val isFavorite = getFieldValue(entry, FIELD_MONICA_IS_FAVORITE, resolutionContext).toBoolean()
            val sourceMonicaId = getFieldValue(entry, FIELD_MONICA_ITEM_ID, resolutionContext).toLongOrNull()
            val now = Date()
            val inRecycleBin = resolveRecycleBinFlag(
                groupPath = groupPath,
                isInRecycleBinByMeta = isInRecycleBinByMeta,
                hasRecycleBinMeta = hasRecycleBinMeta
            )

            return KeePassSecureItemData(
                item = SecureItem(
                    id = 0,
                    itemType = itemType,
                    title = title.ifBlank { "Untitled" },
                    notes = notes,
                    isFavorite = isFavorite,
                    createdAt = now,
                    updatedAt = now,
                    itemData = itemData,
                    imagePaths = imagePaths,
                    keepassDatabaseId = databaseId,
                    keepassGroupPath = groupPath,
                    keepassEntryUuid = entry.uuid.toString(),
                    keepassGroupUuid = groupUuid?.toString(),
                    isDeleted = inRecycleBin,
                    deletedAt = if (inRecycleBin) now else null
                ),
                sourceMonicaId = sourceMonicaId,
                isInRecycleBin = inRecycleBin
            )
        }

        val allowTotp = allowedTypes == null || allowedTypes.contains(ItemType.TOTP)
        if (!allowTotp) return null

        val parsedTotp = parseStandardTotpFromEntry(entry, resolutionContext) ?: return null
        val title = getStandardTitle(entry, resolutionContext)
        val notes = getStandardNotes(entry, resolutionContext)
        val now = Date()
        val inRecycleBin = resolveRecycleBinFlag(
            groupPath = groupPath,
            isInRecycleBinByMeta = isInRecycleBinByMeta,
            hasRecycleBinMeta = hasRecycleBinMeta
        )
        val fallbackTitle = parsedTotp.issuer.ifBlank { parsedTotp.accountName }.ifBlank { "Untitled" }

        return KeePassSecureItemData(
            item = SecureItem(
                id = 0,
                itemType = ItemType.TOTP,
                title = title.ifBlank { fallbackTitle },
                notes = notes,
                isFavorite = false,
                createdAt = now,
                updatedAt = now,
                itemData = Json.encodeToString(TotpData.serializer(), parsedTotp),
                imagePaths = "",
                keepassDatabaseId = databaseId,
                keepassGroupPath = groupPath,
                keepassEntryUuid = entry.uuid.toString(),
                keepassGroupUuid = groupUuid?.toString(),
                isDeleted = inRecycleBin,
                deletedAt = if (inRecycleBin) now else null
            ),
            sourceMonicaId = getFieldValue(entry, FIELD_MONICA_ITEM_ID, resolutionContext).toLongOrNull(),
            isInRecycleBin = inRecycleBin
        )
    }

    private fun buildStructuredSecureItemDataFromEntry(
        itemType: ItemType,
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): String? {
        return when (itemType) {
            ItemType.BANK_CARD -> buildBankCardItemDataFromEntry(entry, resolutionContext)
            ItemType.NOTE -> Json.encodeToString(takagi.ru.monica.data.model.NoteData.serializer(),
                takagi.ru.monica.data.model.NoteData(content = getStandardNotes(entry, resolutionContext)))
            else -> null
        }
    }

    private fun resolveMonicaSecureItemPayload(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): KeePassSecureItemPayload? {
        val type = getFieldValue(entry, FIELD_MONICA_ITEM_TYPE, resolutionContext)
        if (type.isBlank()) return null
        return KeePassSecureItemPayload.resolve(
            typeName = type,
            raw = getFieldValue(entry, FIELD_MONICA_ITEM_DATA, resolutionContext),
            standardTotp = if (type.trim().equals("TOTP", true)) parseStandardTotpFromEntry(entry, resolutionContext) else null,
            rebuild = { buildStructuredSecureItemDataFromEntry(it, entry, resolutionContext) }
        )
    }

    private fun buildBankCardItemDataFromEntry(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): String? {
        val expiryMonth = getFieldValueIgnoreCase(entry, resolutionContext, "Expiry Month")
        val expiryYear = getFieldValueIgnoreCase(entry, resolutionContext, "Expiry Year")
        val fallbackExpiry = splitCardExpiry(
            getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_EXPIRY,
                "CardExpiry",
                "Expiration Date",
                "Expiry Date"
            )
        )
        val data = BankCardData(
            cardNumber = getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_NUMBER,
                "CardNumber",
                "Credit Card Number",
                "CreditCardNumber"
            ),
            cardholderName = getFieldValueIgnoreCase(
                entry,
                resolutionContext,
                FIELD_CARD_HOLDER,
                "CardHolder",
                "Credit Card Holder",
                "CreditCardHolder"
            ),
            expiryMonth = expiryMonth.ifBlank { fallbackExpiry.first },
            expiryYear = expiryYear.ifBlank { fallbackExpiry.second },
            cvv = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CARD_CVV, "CardCVV", "CVV", "CVC"),
            bankName = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BANK_NAME),
            cardType = parseKeePassCardType(getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CARD_TYPE)),
            billingAddress = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BILLING_ADDRESS),
            brand = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BRAND),
            nickname = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_NICKNAME),
            validFromMonth = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_VALID_FROM_MONTH),
            validFromYear = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_VALID_FROM_YEAR),
            pin = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_PIN),
            iban = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_IBAN),
            swiftBic = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_SWIFT_BIC),
            routingNumber = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_ROUTING_NUMBER),
            accountNumber = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_ACCOUNT_NUMBER),
            branchCode = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_BRANCH_CODE),
            currency = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CURRENCY),
            customerServicePhone = getFieldValueIgnoreCase(entry, resolutionContext, FIELD_CUSTOMER_SERVICE_PHONE),
            customFields = extractStructuredSecureItemCustomFields(entry, resolutionContext)
        )
        val hasAnyCardField = listOf(
            data.cardNumber,
            data.cardholderName,
            data.expiryMonth,
            data.expiryYear,
            data.cvv,
            data.bankName,
            data.billingAddress,
            data.brand,
            data.nickname,
            data.pin,
            data.iban,
            data.swiftBic,
            data.routingNumber,
            data.accountNumber,
            data.branchCode,
            data.currency,
            data.customerServicePhone
        ).any { it.isNotBlank() } || data.customFields.isNotEmpty()

        return if (hasAnyCardField) {
            CardWalletDataCodec.encodeBankCardData(data)
        } else {
            null
        }
    }

    private fun splitCardExpiry(raw: String): Pair<String, String> {
        val normalized = raw.trim()
        if (normalized.isBlank()) return "" to ""
        val parts = normalized.split("/", "-", ".", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 2) return "" to normalized
        return parts[0] to parts[1]
    }

    private fun parseKeePassCardType(raw: String): CardType {
        return when (raw.trim().uppercase(Locale.ROOT).replace(" ", "_")) {
            "DEBIT", "DEBIT_CARD" -> CardType.DEBIT
            "PREPAID", "PREPAID_CARD" -> CardType.PREPAID
            else -> CardType.CREDIT
        }
    }

    private fun extractStructuredSecureItemCustomFields(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext?
    ): List<SecureCustomField> {
        val reserved = (baseSecureItemFieldNames() + bankCardSecureItemFieldNames())
            .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        return entry.fields.mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isBlank() || normalizedKey.startsWith("_etm_")) return@mapNotNull null
            if (normalizedKey.lowercase(Locale.ROOT) in reserved) return@mapNotNull null
            val content = getFieldValue(entry, key, resolutionContext)
            if (content.isBlank()) return@mapNotNull null
            SecureCustomField(
                label = normalizedKey,
                value = content,
                type = if (value is EntryValue.Encrypted) {
                    SecureCustomFieldType.HIDDEN
                } else {
                    SecureCustomFieldType.TEXT
                }
            )
        }
    }

    private fun baseSecureItemFieldNames(): Set<String> {
        return setOf(
            "Title",
            "UserName",
            "Password",
            "URL",
            "Notes",
            FIELD_MONICA_ITEM_TYPE,
            FIELD_MONICA_ITEM_DATA,
            FIELD_MONICA_IMAGE_PATHS,
            FIELD_MONICA_IS_FAVORITE,
            FIELD_MONICA_ITEM_ID
        )
    }

    private fun bankCardSecureItemFieldNames(): Set<String> {
        return setOf(
            FIELD_CARD_NUMBER,
            "CardNumber",
            "Credit Card Number",
            "CreditCardNumber",
            FIELD_CARD_HOLDER,
            "CardHolder",
            "Credit Card Holder",
            "CreditCardHolder",
            FIELD_CARD_EXPIRY,
            "CardExpiry",
            "Expiration Date",
            "Expiry Date",
            "Expiry Month",
            "Expiry Year",
            FIELD_CARD_CVV,
            "CardCVV",
            "CVV",
            "CVC",
            FIELD_BANK_NAME,
            FIELD_CARD_TYPE,
            FIELD_BILLING_ADDRESS,
            FIELD_BRAND,
            FIELD_NICKNAME,
            FIELD_VALID_FROM_MONTH,
            FIELD_VALID_FROM_YEAR,
            FIELD_PIN,
            FIELD_IBAN,
            FIELD_SWIFT_BIC,
            FIELD_ROUTING_NUMBER,
            FIELD_ACCOUNT_NUMBER,
            FIELD_BRANCH_CODE,
            FIELD_CURRENCY,
            FIELD_CUSTOMER_SERVICE_PHONE
        )
    }

    private fun entryToPasskey(
        entry: Entry,
        databaseId: Long,
        groupPath: String?,
        groupUuid: UUID?,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): PasskeyEntry? {
        if (isMonicaConflictCopy(entry, resolutionContext)) {
            return null
        }
        val title = getFieldValue(entry, "Title", resolutionContext)
        val notes = getFieldValue(entry, "Notes", resolutionContext)
        val rawCredentialId = getFieldValue(entry, FIELD_MONICA_PASSKEY_CREDENTIAL_ID, resolutionContext)
        val rawPayload = getFieldValue(entry, FIELD_MONICA_PASSKEY_DATA, resolutionContext)
        val decoded = if (rawCredentialId.isBlank() && rawPayload.isBlank()) {
            null
        } else {
            KeePassPasskeySyncCodec.decode(
                raw = rawPayload,
                databaseId = databaseId,
                groupPath = groupPath,
                groupUuid = groupUuid?.toString()
            )
        }

        val passkey = decoded?.copy(
            credentialId = rawCredentialId.ifBlank { decoded.credentialId },
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            bitwardenVaultId = null,
            bitwardenFolderId = null,
            bitwardenCipherId = null,
            syncStatus = "NONE",
            passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
        ) ?: KeePassDxPasskeyCodec.decode(
            getField = { key -> getFieldValue(entry, key, resolutionContext) },
            title = title,
            notes = notes,
            databaseId = databaseId,
            groupPath = groupPath,
            groupUuid = groupUuid?.toString(),
            createdAt = entry.times?.creationTime?.toEpochMilli() ?: System.currentTimeMillis(),
            lastUsedAt = entry.times?.lastAccessTime?.toEpochMilli()
                ?: entry.times?.creationTime?.toEpochMilli()
                ?: System.currentTimeMillis(),
            useCount = entry.times?.usageCount ?: 0
        )

        return passkey?.copy(
            keepassDatabaseId = databaseId,
            keepassGroupPath = groupPath,
            bitwardenVaultId = null,
            bitwardenFolderId = null,
            bitwardenCipherId = null,
            syncStatus = "NONE",
            passkeyMode = PasskeyEntry.MODE_KEEPASS_COMPAT
        )?.let { PasskeyPrivateKeyStore.protectPasskey(context, it) }
    }

    private fun resolveRecycleBinUuid(meta: Meta): UUID? {
        if (!meta.recycleBinEnabled) return null
        return meta.recycleBinUuid
    }

    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    private fun resolveRecycleBinFlag(
        groupPath: String?,
        isInRecycleBinByMeta: Boolean,
        hasRecycleBinMeta: Boolean
    ): Boolean {
        // A group name is ordinary user content; only the database's recycle-bin UUID is authoritative.
        return isInRecycleBinByMeta
    }

    /**
     * 标准 Password 字段为空时，尝试从常见自定义受保护字段中提取密码。
     */
    private fun resolveEntryPassword(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String {
        fun isLikelyLabelValue(value: String, key: String? = null): Boolean {
            val normalized = value.trim().lowercase(Locale.ROOT)
            if (normalized.isBlank()) return true
            val labelTokens = setOf("password", "pass", "pwd", "pin", "密码", "口令")
            if (normalized in labelTokens) return true
            if (key != null && normalized == key.trim().lowercase(Locale.ROOT)) return true
            return false
        }

        val standardPassword = getStandardPassword(entry, resolutionContext)
        if (standardPassword.isNotBlank() && !isLikelyLabelValue(standardPassword, "Password")) {
            return standardPassword
        }
        var fallback = standardPassword.takeIf { it.isNotBlank() }

        val prioritizedKeys = listOf(
            "密码", "口令", "PIN", "Pin", "pin", "pwd", "PWD", "pass", "Pass", "password", "Password"
        )
        prioritizedKeys.forEach { key ->
            val value = getFieldValueIgnoreCase(entry, resolutionContext, key)
            if (value.isBlank()) return@forEach
            if (!isLikelyLabelValue(value, key)) return value
            if (fallback.isNullOrBlank()) fallback = value
        }

        entry.fields.forEach { (key, value) ->
            if (!KeePassFieldRegistry.isPasswordSecretFallbackCandidateField(key)) return@forEach
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

    private fun getStandardTitle(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String = getFieldValueIgnoreCase(entry, resolutionContext, "Title", "Name")

    private fun getStandardUsername(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String = getFieldValueIgnoreCase(entry, resolutionContext, "UserName", "Username", "User", "Login")

    private fun getStandardPassword(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String = getFieldValueIgnoreCase(entry, resolutionContext, "Password", "Pass", "pass", "pwd", "PWD", "密码", "口令")

    private fun getStandardUrl(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String = getFieldValueIgnoreCase(entry, resolutionContext, "URL", "Url", "Website", "URI")

    private fun getStandardNotes(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String = getFieldValueIgnoreCase(entry, resolutionContext, "Notes", "Note", "Comment")

    private fun getFieldValue(
        entry: Entry,
        key: String,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): String {
        return KeePassFieldReferenceResolver.getFieldValue(entry, key, resolutionContext)
    }

    private fun getFieldValueIgnoreCase(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null,
        vararg keys: String
    ): String {
        return KeePassFieldReferenceResolver.getFieldValueIgnoreCase(
            entry = entry,
            context = resolutionContext,
            *keys
        )
    }

    private fun isEnhancedEntryTemplate(
        entry: Entry,
        title: String,
        username: String,
        password: String,
        url: String,
        notes: String,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val templateFlag = getFieldValue(entry, FIELD_KEEPASS_XC_TEMPLATE, resolutionContext)
            .trim()
            .lowercase(Locale.ROOT)
        if (templateFlag.isEmpty() || templateFlag == "0" || templateFlag == "false") {
            return false
        }
        if (title.isBlank() || username.isNotBlank() || password.isNotBlank() || url.isNotBlank() || notes.isNotBlank()) {
            return false
        }

        val standardFields = setOf("Title", "UserName", "Password", "URL", "Notes")
        val hasFilledUserField = entry.fields.keys.any { key ->
            key !in standardFields &&
                !key.startsWith("_etm_") &&
                getFieldValue(entry, key, resolutionContext).isNotBlank()
        }
        return !hasFilledUserField
    }

    private fun isPasskeyEntry(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        if (getFieldValue(entry, FIELD_MONICA_PASSKEY_CREDENTIAL_ID, resolutionContext).isNotBlank()) {
            return true
        }
        if (getFieldValue(entry, FIELD_MONICA_PASSKEY_DATA, resolutionContext).isNotBlank()) {
            return true
        }
        return KeePassDxPasskeyCodec.isPasskey { key ->
            getFieldValue(entry, key, resolutionContext)
        }
    }

    private fun isMonicaConflictCopy(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): Boolean {
        val marker = getFieldValue(entry, FIELD_MONICA_CONFLICT_COPY, resolutionContext)
            .trim()
            .lowercase(Locale.ROOT)
        return marker == "true" || marker == "1" || marker == "yes"
    }

    private fun parseStandardTotpFromEntry(
        entry: Entry,
        resolutionContext: KeePassEntryResolutionContext? = null
    ): TotpData? {
        val standard = KeePassTotpCodec.parseFields(
            getField = { getFieldValueIgnoreCase(entry, resolutionContext, it) },
            issuer = getStandardTitle(entry, resolutionContext),
            accountName = getStandardUsername(entry, resolutionContext),
            link = getStandardUrl(entry, resolutionContext)
        )
        return KeePassSecureItemPayload.resolveTotp(getFieldValue(entry, FIELD_MONICA_TOTP_DATA, resolutionContext), standard)
    }

    private fun buildResolutionContext(
        keePassDatabase: KeePassDatabase
    ): KeePassEntryResolutionContext? {
        val entries = { entrySequence(keePassDatabase.content.group) }
        if (!entries().any(::containsReferenceToken)) return null
        return KeePassFieldReferenceResolver.buildContext(entries().asIterable())
    }

    private fun entrySequence(group: Group): Sequence<Entry> = sequence {
        val pending = ArrayDeque<Group>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            yieldAll(current.entries)
            current.groups.asReversed().forEach(pending::addLast)
        }
    }

    private fun containsReferenceToken(entry: Entry): Boolean {
        return entry.fields.values.any { value ->
            runCatching { value.content.contains("{REF:", ignoreCase = true) }
                .getOrDefault(false)
        }
    }

    private fun buildGroupTraversalContextIndex(
        keePassDatabase: KeePassDatabase
    ): Map<UUID, GroupTraversalContext> {
        val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
        val result = mutableMapOf<UUID, GroupTraversalContext>()
        collectGroupTraversalContext(
            group = keePassDatabase.content.group,
            currentPathKey = null,
            recycleBinUuid = recycleBinUuid,
            parentInRecycleBin = false,
            result = result
        )
        return result
    }

    private fun collectGroupTraversalContext(
        group: Group,
        currentPathKey: String?,
        recycleBinUuid: UUID?,
        parentInRecycleBin: Boolean,
        result: MutableMap<UUID, GroupTraversalContext>
    ) {
        val inRecycleBin = parentInRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        result[group.uuid] = GroupTraversalContext(
            pathKey = currentPathKey,
            groupUuid = group.uuid,
            isInRecycleBinByMeta = inRecycleBin
        )
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            collectGroupTraversalContext(
                group = child,
                currentPathKey = childPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycleBin,
                result = result
            )
        }
    }

    private fun resolveRestoreTargetFromEntryContext(
        entryContext: EntryTraversalContext,
        hasRecycleBinMeta: Boolean,
        groupContextIndex: Map<UUID, GroupTraversalContext>
    ): KeePassRestoreTarget {
        val inRecycleBin = entryContext.isInRecycleBinByMeta
        if (!inRecycleBin) {
            return KeePassRestoreTarget(
                groupPath = entryContext.groupPath,
                groupUuid = entryContext.groupUuid?.toString()
            )
        }

        val previousParentUuid = entryContext.entry.previousParentGroup
        if (previousParentUuid != null) {
            val previousParentContext = groupContextIndex[previousParentUuid]
            if (previousParentContext != null) {
                val previousInRecycleBin = previousParentContext.isInRecycleBinByMeta
                if (!previousInRecycleBin) {
                    return KeePassRestoreTarget(
                        groupPath = previousParentContext.pathKey,
                        groupUuid = previousParentContext.groupUuid.toString()
                    )
                }
            }
        }

        return KeePassRestoreTarget(groupPath = null, groupUuid = null)
    }

    private fun isGroupPathInRecycleBinByMeta(
        group: Group,
        currentPathKey: String?,
        targetPathKey: String,
        recycleBinUuid: UUID,
        parentInRecycleBin: Boolean
    ): Boolean? {
        val inRecycle = parentInRecycleBin || group.uuid == recycleBinUuid
        if (currentPathKey == targetPathKey) {
            return inRecycle
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = isGroupPathInRecycleBinByMeta(
                group = child,
                currentPathKey = childPathKey,
                targetPathKey = targetPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycle
            )
            if (childResult != null) {
                return childResult
            }
        }
        return null
    }

    private fun collectEntryContexts(
        keePassDatabase: KeePassDatabase
    ): Pair<List<EntryTraversalContext>, Boolean> {
        val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
        val hasRecycleBinMeta = recycleBinUuid != null
        val entries = mutableListOf<EntryTraversalContext>()
        collectEntriesWithGroupPath(
            group = keePassDatabase.content.group,
            currentPathKey = null,
            recycleBinUuid = recycleBinUuid,
            parentInRecycleBin = false,
            entries = entries
        )
        return entries to hasRecycleBinMeta
    }

    private fun collectEntriesWithGroupPath(
        group: Group,
        currentPathKey: String?,
        recycleBinUuid: UUID?,
        parentInRecycleBin: Boolean,
        entries: MutableList<EntryTraversalContext>
    ) {
        val inRecycleBin = parentInRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        group.entries.forEach { entry ->
            entries.add(
                EntryTraversalContext(
                    entry = entry,
                    groupPath = currentPathKey,
                    groupUuid = group.uuid,
                    isInRecycleBinByMeta = inRecycleBin
                )
            )
        }
        group.groups.forEach { child ->
            val nextPathKey = buildKeePassPathKey(currentPathKey, child.name)
            collectEntriesWithGroupPath(
                group = child,
                currentPathKey = nextPathKey,
                recycleBinUuid = recycleBinUuid,
                parentInRecycleBin = inRecycleBin,
                entries = entries
            )
        }
    }

    private fun collectGroups(
        group: Group,
        parentPathKey: String,
        depth: Int,
        result: MutableList<KeePassGroupInfo>,
        recycleBinUuid: UUID?,
        includeRecycleBin: Boolean,
        parentInRecycleBin: Boolean
    ) {
        val inRecycleBin = parentInRecycleBin || (recycleBinUuid != null && group.uuid == recycleBinUuid)
        if (!includeRecycleBin && inRecycleBin) {
            return
        }
        val name = group.name.ifBlank { "(未命名)" }
        val currentPathKey = buildKeePassPathKey(parentPathKey, name)
        val currentDisplayPath = decodeKeePassPathForDisplay(currentPathKey)
        result.add(
            KeePassGroupInfo(
                name = name,
                path = currentPathKey,
                uuid = group.uuid.toString(),
                depth = depth,
                displayPath = currentDisplayPath
            )
        )
        group.groups.forEach { child ->
            collectGroups(
                group = child,
                parentPathKey = currentPathKey,
                depth = depth + 1,
                result = result,
                recycleBinUuid = recycleBinUuid,
                includeRecycleBin = includeRecycleBin,
                parentInRecycleBin = inRecycleBin
            )
        }
    }

    private fun buildGroupInfoList(
        keePassDatabase: KeePassDatabase,
        includeRecycleBin: Boolean
    ): List<KeePassGroupInfo> {
        val recycleBinUuid = resolveRecycleBinUuid(keePassDatabase.content.meta)
        val groups = mutableListOf<KeePassGroupInfo>()
        keePassDatabase.content.group.groups.forEach { group ->
            collectGroups(
                group = group,
                parentPathKey = "",
                depth = 0,
                result = groups,
                recycleBinUuid = recycleBinUuid,
                includeRecycleBin = includeRecycleBin,
                parentInRecycleBin = false
            )
        }
        return groups.sortedBy { it.displayPath }
    }

    private fun findGroupPathByUuid(
        group: Group,
        currentPathKey: String?,
        targetUuid: UUID
    ): String? {
        if (group.uuid == targetUuid) {
            return currentPathKey
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = findGroupPathByUuid(
                group = child,
                currentPathKey = childPathKey,
                targetUuid = targetUuid
            )
            if (childResult != null) {
                return childResult
            }
        }
        return null
    }

    private fun findGroupUuidByPath(
        group: Group,
        currentPathKey: String?,
        targetPathKey: String
    ): UUID? {
        if (currentPathKey == targetPathKey) {
            return group.uuid
        }
        group.groups.forEach { child ->
            val childPathKey = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = findGroupUuidByPath(
                group = child,
                currentPathKey = childPathKey,
                targetPathKey = targetPathKey
            )
            if (childResult != null) return childResult
        }
        return null
    }

    private fun addGroupToPath(
        group: Group,
        parentSegments: List<String>,
        newGroupName: String,
        currentPathKey: String
    ): Pair<Group, KeePassGroupInfo> {
        if (parentSegments.isEmpty()) {
            val existing = group.groups.firstOrNull { it.name.equals(newGroupName, ignoreCase = true) }
            if (existing != null) {
                val existingPath = buildKeePassPathKey(currentPathKey, existing.name)
                return group to KeePassGroupInfo(
                    name = existing.name,
                    path = existingPath,
                    uuid = existing.uuid.toString(),
                    depth = decodeKeePassPathSegments(existingPath).size - 1,
                    displayPath = decodeKeePassPathForDisplay(existingPath)
                )
            }

            val newGroup = nativeMutation.initializeGroup(
                Group(
                    uuid = UUID.randomUUID(),
                    name = newGroupName
                )
            )
            val newPath = buildKeePassPathKey(currentPathKey, newGroupName)
            return group.copy(groups = group.groups + newGroup) to KeePassGroupInfo(
                name = newGroupName,
                path = newPath,
                uuid = newGroup.uuid.toString(),
                depth = decodeKeePassPathSegments(newPath).size - 1,
                displayPath = decodeKeePassPathForDisplay(newPath)
            )
        }

        val nextSegment = parentSegments.first()
        val childIndex = group.groups.indexOfFirst { it.name == nextSegment }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_parent_group, nextSegment))
        }

        val child = group.groups[childIndex]
        val childPath = buildKeePassPathKey(currentPathKey, child.name)
        val childResult = addGroupToPath(
            group = child,
            parentSegments = parentSegments.drop(1),
            newGroupName = newGroupName,
            currentPathKey = childPath
        )

        val updatedGroups = group.groups.toMutableList()
        updatedGroups[childIndex] = childResult.first
        return group.copy(groups = updatedGroups) to childResult.second
    }

    private fun renameGroupByPath(
        group: Group,
        pathSegments: List<String>,
        newName: String,
        currentPathKey: String
    ): Pair<Group, KeePassGroupInfo> {
        val targetName = pathSegments.firstOrNull()
            ?: throw IllegalArgumentException(strings.get(R.string.keepass_error_group_path))
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_group_missing, targetName))
        }

        val child = group.groups[childIndex]
        val updatedGroups = group.groups.toMutableList()

        return if (pathSegments.size == 1) {
            val conflict = group.groups.anyIndexed { index, sibling ->
                index != childIndex && sibling.name.equals(newName, ignoreCase = true)
            }
            if (conflict) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_exists))
            }

            val renamed = child.copy(name = newName)
            updatedGroups[childIndex] = renamed
            val newPath = buildKeePassPathKey(currentPathKey, newName)
            group.copy(groups = updatedGroups) to KeePassGroupInfo(
                name = newName,
                path = newPath,
                uuid = renamed.uuid.toString(),
                depth = decodeKeePassPathSegments(newPath).size - 1,
                displayPath = decodeKeePassPathForDisplay(newPath)
            )
        } else {
            val childPath = buildKeePassPathKey(currentPathKey, child.name)
            val childResult = renameGroupByPath(
                group = child,
                pathSegments = pathSegments.drop(1),
                newName = newName,
                currentPathKey = childPath
            )
            updatedGroups[childIndex] = childResult.first
            group.copy(groups = updatedGroups) to childResult.second
        }
    }

    private fun removeGroupByPath(
        group: Group,
        pathSegments: List<String>
    ): Pair<Group, Boolean> {
        val targetName = pathSegments.firstOrNull() ?: return group to false
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) return group to false

        val updatedGroups = group.groups.toMutableList()
        return if (pathSegments.size == 1) {
            updatedGroups.removeAt(childIndex)
            group.copy(groups = updatedGroups) to true
        } else {
            val child = group.groups[childIndex]
            val childResult = removeGroupByPath(child, pathSegments.drop(1))
            if (!childResult.second) return group to false
            updatedGroups[childIndex] = childResult.first
            group.copy(groups = updatedGroups) to true
        }
    }

    private data class ExtractedGroupResult(
        val updatedGroup: Group,
        val removedGroup: Group?
    )

    private fun extractGroupByPath(
        group: Group,
        pathSegments: List<String>
    ): ExtractedGroupResult {
        val targetName = pathSegments.firstOrNull() ?: return ExtractedGroupResult(group, null)
        val childIndex = group.groups.indexOfFirst { it.name == targetName }
        if (childIndex < 0) return ExtractedGroupResult(group, null)

        val updatedGroups = group.groups.toMutableList()
        return if (pathSegments.size == 1) {
            val removedGroup = updatedGroups.removeAt(childIndex)
            ExtractedGroupResult(
                updatedGroup = group.copy(groups = updatedGroups),
                removedGroup = removedGroup
            )
        } else {
            val child = group.groups[childIndex]
            val childResult = extractGroupByPath(child, pathSegments.drop(1))
            if (childResult.removedGroup == null) {
                return ExtractedGroupResult(group, null)
            }
            updatedGroups[childIndex] = childResult.updatedGroup
            ExtractedGroupResult(
                updatedGroup = group.copy(groups = updatedGroups),
                removedGroup = childResult.removedGroup
            )
        }
    }

    private data class InsertedGroupResult(
        val updatedGroup: Group,
        val groupInfo: KeePassGroupInfo
    )

    private fun buildGroupInfoFromPath(
        group: Group,
        pathKey: String
    ): KeePassGroupInfo {
        return KeePassGroupInfo(
            name = group.name,
            path = pathKey,
            uuid = group.uuid.toString(),
            depth = decodeKeePassPathSegments(pathKey).size - 1,
            displayPath = decodeKeePassPathForDisplay(pathKey)
        )
    }

    private fun insertGroupToPath(
        group: Group,
        parentSegments: List<String>,
        groupToInsert: Group,
        currentPathKey: String
    ): InsertedGroupResult {
        if (parentSegments.isEmpty()) {
            val conflict = group.groups.any { sibling ->
                sibling.uuid != groupToInsert.uuid && sibling.name.equals(groupToInsert.name, ignoreCase = true)
            }
            if (conflict) {
                throw IllegalArgumentException(strings.get(R.string.keepass_error_group_exists))
            }
            val newPath = buildKeePassPathKey(currentPathKey, groupToInsert.name)
            return InsertedGroupResult(
                updatedGroup = group.copy(groups = group.groups + groupToInsert),
                groupInfo = KeePassGroupInfo(
                    name = groupToInsert.name,
                    path = newPath,
                    uuid = groupToInsert.uuid.toString(),
                    depth = decodeKeePassPathSegments(newPath).size - 1,
                    displayPath = decodeKeePassPathForDisplay(newPath)
                )
            )
        }

        val nextSegment = parentSegments.first()
        val childIndex = group.groups.indexOfFirst { it.name == nextSegment }
        if (childIndex < 0) {
            throw IllegalArgumentException(strings.get(R.string.keepass_error_parent_group, nextSegment))
        }

        val child = group.groups[childIndex]
        val childPath = buildKeePassPathKey(currentPathKey, child.name)
        val childResult = insertGroupToPath(
            group = child,
            parentSegments = parentSegments.drop(1),
            groupToInsert = groupToInsert,
            currentPathKey = childPath
        )
        val updatedGroups = group.groups.toMutableList()
        updatedGroups[childIndex] = childResult.updatedGroup
        return InsertedGroupResult(
            updatedGroup = group.copy(groups = updatedGroups),
            groupInfo = childResult.groupInfo
        )
    }

    private fun buildGroupTreeChangePatch(
        database: KeePassDatabase,
        root: Group,
        sourceRootGroupUuid: UUID?,
        targetParentGroupUuid: UUID?
    ): KeePassGroupTreeChangePatch {
        val referencedBinaryHashes = linkedSetOf<String>()
        val referencedCustomIconUuids = linkedSetOf<UUID>()
        val snapshot = root.toGroupTreeSnapshot(
            referencedBinaryHashes = referencedBinaryHashes,
            referencedCustomIconUuids = referencedCustomIconUuids
        )
        val binaryPool = referencedBinaryHashes.map { hashHex ->
            val hash = hashHex.toByteStringHash()
            val binaryData = database.binaries[hash]
                ?: throw IllegalStateException("KeePass binary pool is missing group tree attachment: $hashHex")
            KeePassBinaryPoolItemPatch(
                hash = hashHex,
                protected = binaryData.memoryProtection,
                compressed = binaryData is BinaryData.Compressed,
                contentBase64 = Base64.encodeToString(
                    binaryData.rawContent,
                    Base64.NO_WRAP
                ),
                rawStorageContent = true,
            )
        }
        val customIconPool = referencedCustomIconUuids.map { uuid ->
            val customIcon = database.content.meta.customIcons[uuid]
                ?: throw IllegalStateException("KeePass custom icon pool is missing group tree icon: $uuid")
            KeePassCustomIconPoolItemPatch(
                uuid = uuid.toString(),
                dataBase64 = Base64.encodeToString(customIcon.data, Base64.NO_WRAP),
                name = customIcon.name,
                lastModifiedEpochMillis = customIcon.lastModified?.toEpochMilli()
            )
        }
        return KeePassGroupTreeChangePatch(
            root = snapshot,
            binaryPool = binaryPool,
            customIconPool = customIconPool,
            sourceRootGroupUuid = sourceRootGroupUuid?.toString(),
            targetParentGroupUuid = targetParentGroupUuid?.toString()
        )
    }

    private fun Group.toGroupTreeSnapshot(
        referencedBinaryHashes: MutableSet<String>,
        referencedCustomIconUuids: MutableSet<UUID>
    ): KeePassGroupTreeSnapshot {
        customIconUuid?.let(referencedCustomIconUuids::add)
        return KeePassGroupTreeSnapshot(
            uuid = uuid.toString(),
            name = name,
            notes = notes,
            iconName = icon.name,
            customIconUuid = customIconUuid?.toString(),
            expanded = expanded,
            defaultAutoTypeSequence = defaultAutoTypeSequence.orEmpty(),
            enableAutoType = enableAutoType.name,
            enableSearching = enableSearching.name,
            lastTopVisibleEntryUuid = lastTopVisibleEntry?.toString(),
            previousParentGroupUuid = previousParentGroup?.toString(),
            tags = tags,
            times = times?.toKeePassTimesPatch(),
            customData = customData.toKeePassCustomDataPatchList(),
            entries = entries.map {
                it.toEntryTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            },
            groups = groups.map {
                it.toGroupTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            }
        )
    }

    private fun Entry.toEntryTreeSnapshot(
        referencedBinaryHashes: MutableSet<String>,
        referencedCustomIconUuids: MutableSet<UUID>
    ): KeePassEntryTreeSnapshot {
        customIconUuid?.let(referencedCustomIconUuids::add)
        return KeePassEntryTreeSnapshot(
            uuid = uuid.toString(),
            fields = fields.map { (name, value) ->
                KeePassFieldChange(
                    name = name,
                    value = value.content,
                    protected = value is EntryValue.Encrypted
                )
            },
            binaries = binaries.map { reference ->
                val hashHex = reference.hash.hex()
                referencedBinaryHashes += hashHex
                KeePassBinaryReferencePatch(
                    name = reference.name,
                    hash = hashHex
                )
            },
            history = history.map {
                it.toEntryTreeSnapshot(referencedBinaryHashes, referencedCustomIconUuids)
            },
            iconName = icon.name,
            customIconUuid = customIconUuid?.toString(),
            foregroundColor = foregroundColor,
            backgroundColor = backgroundColor,
            overrideUrl = overrideUrl,
            autoType = autoType?.toKeePassAutoTypePatch(),
            tags = tags,
            times = times?.toKeePassTimesPatch(),
            customData = customData.toKeePassCustomDataPatchList(),
            previousParentGroupUuid = previousParentGroup?.toString(),
            qualityCheck = qualityCheck
        )
    }

    private fun TimeData.toKeePassTimesPatch(): KeePassTimesPatch {
        return KeePassTimesPatch(
            creationTimeEpochMillis = creationTime?.toEpochMilli(),
            lastModificationTimeEpochMillis = lastModificationTime?.toEpochMilli(),
            lastAccessTimeEpochMillis = lastAccessTime?.toEpochMilli(),
            expiryTimeEpochMillis = expiryTime?.toEpochMilli(),
            expires = expires,
            usageCount = usageCount,
            locationChangedEpochMillis = locationChanged?.toEpochMilli()
        )
    }

    private fun Map<String, CustomDataValue>.toKeePassCustomDataPatchList(): List<KeePassCustomDataPatch> {
        return map { (key, value) ->
            KeePassCustomDataPatch(
                key = key,
                value = value.value,
                lastModifiedEpochMillis = value.lastModified?.toEpochMilli()
            )
        }
    }

    private fun AutoTypeData.toKeePassAutoTypePatch(): KeePassAutoTypePatch {
        return KeePassAutoTypePatch(
            enabled = enabled,
            obfuscation = obfuscation.name,
            defaultSequence = defaultSequence.orEmpty(),
            items = items.map { it.toKeePassAutoTypeItemPatch() }
        )
    }

    private fun AutoTypeItem.toKeePassAutoTypeItemPatch(): KeePassAutoTypeItemPatch {
        return KeePassAutoTypeItemPatch(
            window = window,
            keystrokeSequence = keystrokeSequence
        )
    }

    private fun String.toByteStringHash(): ByteString {
        val normalized = trim()
        require(normalized.length % 2 == 0) { "Invalid hex length" }
        val bytes = ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toByteString()
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        for (index in indices) {
            if (predicate(index, this[index])) return true
        }
        return false
    }

    private suspend fun <T> mutateDatabase(
        databaseId: Long,
        forceSyncWrite: Boolean = false,
        mutation: (LoadedDatabase) -> MutationPlan<T>
    ): T {
        return withDatabaseMutationLocks(listOf(databaseId)) {
            try {
                if (forceSyncWrite && shouldForceReloadForWrite(databaseId)) {
                    invalidateLoadedDatabaseCache(databaseId)
                }
                val loaded = getCachedLoadedDatabase(databaseId) ?: loadDatabase(databaseId)
                val plan = mutation(loaded)
                writeDatabase(
                    database = loaded.database,
                    credentials = loaded.credentials,
                    keePassDatabase = plan.updatedDatabase,
                    sourceEtag = loaded.sourceEtag,
                    sourceLastModified = loaded.sourceLastModified,
                    expectedSourceRevision = loaded.sourceRevision,
                    beforeRemoteUpload = plan.beforeRemoteUpload
                )
                plan.afterWrite?.invoke(loaded.database, plan.updatedDatabase)
                plan.result
            } catch (e: Exception) {
                invalidateLoadedDatabaseCache(databaseId)
                throw logMutationFailure("mutateDatabase", databaseId, e)
            }
        }
    }

    private fun shouldForceReloadForWrite(databaseId: Long): Boolean {
        val cached = synchronized(loadedDatabaseCache) { loadedDatabaseCache[databaseId] } ?: return false
        return cached.loaded.sourceSignature == null
    }

    private suspend fun loadDatabase(databaseId: Long): LoadedDatabase {
        getCachedLoadedDatabase(databaseId)?.let { return it }
        return loadMutexForDatabase(databaseId).withLock {
            getCachedLoadedDatabase(databaseId)?.let { return@withLock it }
            val database = dao.getDatabaseById(databaseId) ?: throw Exception(strings.get(R.string.keepass_connection_status_missing))
            val credentials = buildCredentials(database)
            val source = openDatabaseStreamSource(database)
            try {
                val (keePassDatabase, resolvedCredentials) = decodeDatabaseWithFallback(
                    source = source,
                    credentialsResolution = credentials,
                    sourceLabel = "databaseId=$databaseId",
                    sourceName = database.resolvedActiveFilePath()
                )
            val nativeSession = nativeSessionCache.deferred(
                databaseId = database.id,
                sourceRevision = source.revision,
                database = keePassDatabase
            )
            val loaded = LoadedDatabase(
                database = database,
                credentials = resolvedCredentials,
                keePassDatabase = keePassDatabase,
                nativeSession = nativeSession,
                nativeBrowser = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    KeePassNativeBrowserBuilder.build(nativeSession.value)
                },
                projectionBundle = nativeProjectionBundleCache.deferred(nativeSession),
                sourceEtag = source.etag,
                sourceLastModified = source.lastModified,
                sourceSignature = currentSourceSignature(database),
                sourceRevision = source.revision
            )
                cacheLoadedDatabase(loaded)
                loaded
            } finally {
                source.close()
            }
        }
    }

    private suspend fun decodeDatabase(
        bytes: ByteArray,
        credentials: Credentials,
        sourceName: String? = null,
        logFailure: Boolean = true
    ): KeePassDatabase {
        return withContext(decodeDispatcher) {
            withGlobalDecodeLock {
                try {
                    KeePassFormatInspector.ensureKdbxSupported(bytes = bytes, sourceName = sourceName, strings = strings)
                    KeePassDatabase.decode(
                        ByteArrayInputStream(bytes),
                        credentials,
                        contentParser = KeePassCodecSupport.contentParser,
                        cipherProviders = KeePassCodecSupport.cipherProviders
                    )
                } catch (t: Throwable) {
                    val mapped = normalizeError(t)
                    if (logFailure) {
                        Log.e(
                            TAG,
                            "KDBX decode failed code=${(mapped as? KeePassOperationException)?.code ?: KeePassErrorCode.IO_READ_WRITE_FAILED}. ${databaseHeaderSummary(bytes)}",
                            mapped
                        )
                    }
                    throw mapped
                }
            }
        }
    }

    private suspend fun decodeDatabaseWithFallback(
        bytes: ByteArray,
        credentialsResolution: CredentialsResolution,
        sourceLabel: String,
        sourceName: String? = null
    ): Pair<KeePassDatabase, Credentials> {
        val candidates = credentialsResolution.candidates
        if (candidates.isEmpty()) {
            throw IllegalStateException(strings.get(R.string.keepass_error_no_credentials))
        }

        var lastError: Throwable? = null
        val attemptedLabels = mutableListOf<String>()
        candidates.forEachIndexed { index, candidate ->
            val isLast = index == candidates.lastIndex
            attemptedLabels += candidate.label
            try {
                val database = decodeDatabase(
                    bytes = bytes,
                    credentials = candidate.credentials,
                    sourceName = sourceName,
                    logFailure = isLast
                )
                if (index > 0) {
                    Log.w(
                        TAG,
                        "KDBX decoded using credential fallback ($sourceLabel, candidate=${candidate.label})"
                    )
                }
                return database to candidate.credentials
            } catch (error: Throwable) {
                val mapped = normalizeError(error)
                lastError = mapped
                val isInvalidCredential =
                    mapped is KeePassOperationException &&
                        mapped.code == KeePassErrorCode.INVALID_CREDENTIAL
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
                message = KeePassCredentialSupport.buildInvalidCredentialMessage(attemptedLabels, strings = strings),
                cause = lastError
            )
        }

        throw (lastError ?: KeePassOperationException(
            code = KeePassErrorCode.IO_READ_WRITE_FAILED,
            message = strings.get(R.string.runtime_kdbx_decode_failed)
        ))
    }

    private suspend fun decodeDatabaseWithFallback(
        source: DatabaseStreamSource,
        credentialsResolution: CredentialsResolution,
        sourceLabel: String,
        sourceName: String? = null,
    ): Pair<KeePassDatabase, Credentials> {
        val candidates = credentialsResolution.candidates
        if (candidates.isEmpty()) throw IllegalStateException(strings.get(R.string.keepass_error_no_credentials))
        var lastError: Throwable? = null
        val attemptedLabels = mutableListOf<String>()
        candidates.forEachIndexed { index, candidate ->
            val isLast = index == candidates.lastIndex
            attemptedLabels += candidate.label
            try {
                val database = decodeDatabase(
                    input = source.openStream(),
                    credentials = candidate.credentials,
                    header = source.header,
                    sourceName = sourceName,
                    logFailure = isLast,
                )
                if (index > 0) {
                    Log.w(TAG, "KDBX stream decoded using credential fallback ($sourceLabel, candidate=${candidate.label})")
                }
                return database to candidate.credentials
            } catch (error: Throwable) {
                val mapped = normalizeError(error)
                lastError = mapped
                val invalid = mapped is KeePassOperationException &&
                    mapped.code == KeePassErrorCode.INVALID_CREDENTIAL
                if (!invalid || isLast) throw mapped
            }
        }
        if (lastError is KeePassOperationException &&
            (lastError as KeePassOperationException).code == KeePassErrorCode.INVALID_CREDENTIAL
        ) {
            throw KeePassOperationException(
                code = KeePassErrorCode.INVALID_CREDENTIAL,
                message = KeePassCredentialSupport.buildInvalidCredentialMessage(attemptedLabels, strings = strings),
                cause = lastError,
            )
        }
        throw (lastError ?: KeePassOperationException(
            code = KeePassErrorCode.IO_READ_WRITE_FAILED,
            message = strings.get(R.string.runtime_kdbx_decode_failed),
        ))
    }

    private fun databaseHeaderSummary(bytes: ByteArray): String {
        val headerLength = bytes.size.coerceAtMost(16)
        val headerHex = buildString {
            for (index in 0 until headerLength) {
                if (index > 0) append(' ')
                append(String.format(Locale.US, "%02X", bytes[index].toInt() and 0xFF))
            }
        }
        return "bytes=${bytes.size}, header[$headerLength]=$headerHex"
    }

    private fun buildCredentials(
        database: LocalKeePassDatabase,
        passwordOverride: String? = null,
        keyFileUriOverride: Uri? = null
    ): CredentialsResolution {
        val encryptedDbPassword = database.encryptedPassword
        val kdbxPassword = passwordOverride ?: (encryptedDbPassword?.let { securityManager.decryptData(it) } ?: "")
        val pending = if (passwordOverride == null && keyFileUriOverride == null) {
            credentialTransitionStore.read(database.id)
        } else {
            null
        }
        val currentCandidates = runCatching {
            val keyFileBytes = keyFileUriOverride?.let(::readKeyFileBytes) ?: keyFileStore.read(database)
            resolveCredentials(kdbxPassword, keyFileBytes).candidates
        }.getOrElse { error ->
            if (pending == null) {
                throw KeePassOperationException(
                    code = KeePassErrorCode.KEY_FILE_UNAVAILABLE,
                    message = context.getString(
                        takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
                    ),
                    cause = error,
                )
            }
            Log.w(TAG, "Registered KeePass key file is unavailable; trying pending credential transition", error)
            emptyList()
        }
        val pendingCandidates = pending
            ?.let { credentials ->
                KeePassCredentialSupport.buildCredentialCandidates(
                    password = credentials.password,
                    keyFileBytes = credentials.keyFileBytes
                ).map { candidate -> candidate.copy(label = "pending/${candidate.label}") }
            }
            .orEmpty()
        return CredentialsResolution(candidates = currentCandidates + pendingCandidates)
    }

    private fun buildCredentialsFromRaw(password: String, keyFileUri: Uri? = null): CredentialsResolution {
        val keyFileBytes = keyFileUri?.let { uri ->
            readKeyFileBytes(uri)
        }
        return resolveCredentials(password, keyFileBytes)
    }

    private fun readKeyFileBytes(uri: Uri): ByteArray {
        return context.contentResolver.readKeePassKeyFileBytes(
            uri = uri,
            unavailableMessage = context.getString(
                takagi.ru.monica.R.string.local_keepass_key_file_unavailable_error
            )
        )
    }

    private fun readBytesFromUri(uri: Uri, missingMessage: String): ByteArray {
        return try {
            openExternalInputBytes(uri)
                ?: throw FileNotFoundException(missingMessage)
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private fun openExternalInputBytes(uri: Uri): ByteArray? {
        val descriptorBytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.readBytes()
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "openFileDescriptor r failed, retry with input stream", error)
        }.getOrNull()
        if (descriptorBytes != null) return descriptorBytes

        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun openExternalInputStream(uri: Uri): InputStream? {
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { fd ->
                ParcelFileDescriptor.AutoCloseInputStream(fd)
            }
        }.getOrNull()
        return descriptor ?: context.contentResolver.openInputStream(uri)
    }

    private fun openDatabaseStreamSource(database: LocalKeePassDatabase): DatabaseStreamSource {
        return try {
            if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
                val file = File(context.filesDir, database.resolvedActiveFilePath())
                if (!file.isFile) throw FileNotFoundException(strings.get(R.string.keepass_operation_database_file_missing))
                DatabaseStreamSource(
                    openStream = { file.inputStream() },
                    header = readFileHeader(file),
                    revision = KeePassSourceSafety.revisionOf(file),
                )
            } else {
                val uri = Uri.parse(database.resolvedActiveFilePath())
                openUriStreamSource(uri, strings.get(R.string.storage_error_open_database))
            }
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private fun openUriStreamSource(uri: Uri, missingMessage: String): DatabaseStreamSource {
        val temporary = File.createTempFile("keepass-source-", ".kdbx", context.cacheDir)
        try {
            val input = openExternalInputStream(uri) ?: throw FileNotFoundException(missingMessage)
            val revision = copyStreamAndRevision(input, temporary.outputStream())
            return DatabaseStreamSource(
                openStream = { temporary.inputStream() },
                header = readFileHeader(temporary),
                revision = revision,
                temporaryFile = temporary,
            )
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private suspend fun decodeDatabase(
        input: InputStream,
        credentials: Credentials,
        header: ByteArray,
        sourceName: String? = null,
        logFailure: Boolean = true,
    ): KeePassDatabase {
        return withContext(decodeDispatcher) {
            withGlobalDecodeLock {
                try {
                    KeePassFormatInspector.ensureKdbxSupportedHeader(header, sourceName, strings = strings)
                    input.use { stream ->
                        KeePassDatabase.decode(
                            stream,
                            credentials,
                            contentParser = KeePassCodecSupport.contentParser,
                            cipherProviders = KeePassCodecSupport.cipherProviders,
                        )
                    }
                } catch (t: Throwable) {
                    val mapped = normalizeError(t)
                    if (logFailure) {
                        Log.e(
                            TAG,
                            "KDBX stream decode failed code=${(mapped as? KeePassOperationException)?.code ?: KeePassErrorCode.IO_READ_WRITE_FAILED}. ${databaseHeaderSummary(header)}",
                            mapped,
                        )
                    }
                    throw mapped
                }
            }
        }
    }

    private fun readFileHeader(file: File, maxBytes: Int = 32): ByteArray {
        file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            val count = input.read(buffer)
            return if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }
    }

    /** Streams a source to a destination while hashing both sides. */
    private fun copyStreamAndRevision(input: InputStream, output: OutputStream): KeePassSourceRevision {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        input.use { source ->
            output.use { target ->
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    target.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
                target.flush()
            }
        }
        return KeePassSourceRevision(
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            sizeBytes = size
        )
    }

    private fun resolveCredentials(password: String, keyFileBytes: ByteArray?): CredentialsResolution {
        val candidates = KeePassCredentialSupport.buildCredentialCandidates(
            password = password,
            keyFileBytes = keyFileBytes
        )
        return CredentialsResolution(candidates = candidates)
    }

    private fun buildExactCredentials(password: String, keyFileBytes: ByteArray?): Credentials {
        return when {
            keyFileBytes == null -> Credentials.from(EncryptedValue.fromString(password))
            password.isBlank() -> Credentials.from(keyFileBytes)
            else -> Credentials.from(EncryptedValue.fromString(password), keyFileBytes)
        }
    }

    private fun countEntries(group: Group): Int {
        var count = 0
        val pending = ArrayDeque<Group>()
        pending.addLast(group)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            count += current.entries.size
            current.groups.forEach(pending::addLast)
        }
        return count
    }

    private fun cleanupUnreferencedInternalKeyFile(
        candidatePath: String?,
        retainedPath: String?,
        databaseId: Long
    ) {
        val path = candidatePath?.takeIf { it.isNotBlank() } ?: return
        if (path == retainedPath) return
        val stillReferenced = dao.getAllDatabasesSync().any { database ->
            database.id != databaseId && database.keyFileInternalPath == path
        }
        if (!stillReferenced) {
            runCatching { keyFileStore.deleteInternal(path) }
                .onFailure { error -> Log.w(TAG, "Unable to clean unused KeePass key-file copy", error) }
        }
    }

    private suspend fun updateStoredDatabaseMetadata(
        registration: LocalKeePassDatabase,
        database: KeePassDatabase
    ) {
        val options = inferCreationOptions(database)
        val meta = database.content.meta
        dao.updateDatabase(
            registration.copy(
                name = meta.name.ifBlank { registration.name },
                description = meta.description.takeIf { it.isNotBlank() },
                entryCount = countEntries(database.content.group),
                kdbxMajorVersion = options.formatVersion.majorVersion,
                cipherAlgorithm = options.cipherAlgorithm.name,
                kdfAlgorithm = options.kdfAlgorithm.name,
                kdfTransformRounds = options.transformRounds,
                kdfMemoryBytes = options.memoryBytes,
                kdfParallelism = options.parallelism,
                lastAccessedAt = System.currentTimeMillis()
            )
        )
    }

    private fun readDatabaseBytes(database: LocalKeePassDatabase): ByteArray {
        return readDatabaseSnapshot(database).bytes
    }

    private fun readDatabaseSnapshot(database: LocalKeePassDatabase): DatabaseSnapshot {
        return try {
            if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
                val file = File(context.filesDir, database.resolvedActiveFilePath())
                if (!file.exists()) throw FileNotFoundException(strings.get(R.string.keepass_operation_database_file_missing))
                val signature = DatabaseSourceSignature(
                    sizeBytes = file.length(),
                    lastModifiedEpochMs = file.lastModified()
                )
                val bytes = file.readBytes()
                DatabaseSnapshot(
                    bytes = bytes,
                    etag = null,
                    lastModified = null,
                    signature = signature,
                    revision = KeePassSourceSafety.revisionOf(bytes)
                )
            } else {
                val uri = Uri.parse(database.resolvedActiveFilePath())
                val bytes = readBytesFromUri(uri, strings.get(R.string.storage_error_open_database))
                DatabaseSnapshot(
                    bytes = bytes,
                    etag = null,
                    lastModified = null,
                    signature = null,
                    revision = KeePassSourceSafety.revisionOf(bytes)
                )
            }
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private suspend fun writeDatabase(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        keePassDatabase: KeePassDatabase,
        sourceEtag: String? = null,
        sourceLastModified: String? = null,
        expectedSourceRevision: KeePassSourceRevision? = null,
        beforeRemoteUpload: (suspend (LocalKeePassDatabase, KeePassSourceRevision) -> Unit)? = null
    ) {
        accessPolicy.requireWritable(database.id)
        encodeDatabaseArtifact(keePassDatabase).use { artifact ->
            val encodedRevision = artifact.revision
            if (ENABLE_POST_WRITE_DECODE_VERIFICATION) {
                decodeDatabase(
                    input = artifact.file.inputStream(),
                    credentials = credentials,
                    header = readFileHeader(artifact.file),
                )
            }
            if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
                writeInternal(database, artifact.file)
            } else {
                writeExternal(
                    database = database,
                    encodedFile = artifact.file,
                    targetRevision = encodedRevision,
                    expectedSourceRevision = expectedSourceRevision,
                )
            }
            var resolvedSourceEtag = sourceEtag
            var resolvedSourceLastModified = sourceLastModified
            var resolvedDatabase = keePassDatabase
            var persistedRevision = encodedRevision
            if (database.isRemoteSource()) {
                beforeRemoteUpload?.invoke(database, encodedRevision)
                val bytes = artifact.readBytes()
                val syncOutcome = syncRemoteWorkingCopy(
                    database = database,
                    credentials = credentials,
                    bytes = bytes,
                    sourceRevision = encodedRevision,
                )
                if (syncOutcome != null) {
                    resolvedSourceEtag = syncOutcome.writeResult.etag
                    resolvedSourceLastModified = syncOutcome.writeResult.lastModified?.toString()
                    resolvedDatabase = syncOutcome.finalDatabase
                    persistedRevision = syncOutcome.finalRevision
                } else {
                    markRemoteWritePending(database, encodedRevision)
                    enqueueRemoteWorkingCopyUpload(database.id)
                }
            }
            cachePersistedDatabase(
                database = database,
                credentials = credentials,
                keePassDatabase = resolvedDatabase,
                sourceRevision = persistedRevision,
                sourceEtag = resolvedSourceEtag,
                sourceLastModified = resolvedSourceLastModified,
            )
        }
    }

    private fun cachePersistedDatabase(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        keePassDatabase: KeePassDatabase,
        sourceRevision: KeePassSourceRevision,
        sourceEtag: String?,
        sourceLastModified: String?,
    ) {
        val nativeSession = nativeSessionCache.deferred(
            databaseId = database.id,
            sourceRevision = sourceRevision,
            database = keePassDatabase,
        )
        cacheLoadedDatabase(
            LoadedDatabase(
                database = database,
                credentials = credentials,
                keePassDatabase = keePassDatabase,
                nativeSession = nativeSession,
                nativeBrowser = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    KeePassNativeBrowserBuilder.build(nativeSession.value)
                },
                projectionBundle = nativeProjectionBundleCache.deferred(nativeSession),
                sourceEtag = sourceEtag,
                sourceLastModified = sourceLastModified,
                sourceSignature = currentSourceSignature(database),
                sourceRevision = sourceRevision,
            )
        )
    }

    private fun keePassPendingChangeRepository(): KeePassPendingChangeRepository {
        return KeePassPendingChangeRepository(
            PasswordDatabase.getDatabase(context).keepassPendingChangeDao()
        )
    }

    private suspend fun enqueuePendingChangeSetsIfRemote(
        database: LocalKeePassDatabase,
        changeSets: List<KeePassChangeSet>,
        workingRevision: KeePassSourceRevision
    ) {
        if (changeSets.isEmpty() || !database.isRemoteSource() || database.sourceId == null) {
            return
        }
        val remoteDb = PasswordDatabase.getDatabase(context)
        val syncState = remoteDb.keepassRemoteSyncStateDao().getState(database.id)
        val baseSnapshot = KeePassPendingChangeBaseSnapshot(
            remoteVersionToken = syncState?.remoteVersionToken,
            remoteEtag = syncState?.remoteEtag,
            remoteLastModified = syncState?.remoteLastModified,
            baseHash = syncState?.baseHash,
            workingHashAtChange = workingRevision.sha256
        )
        val repository = keePassPendingChangeRepository()
        changeSets.forEach { changeSet ->
            repository.enqueue(changeSet, baseSnapshot)
        }
    }

    private suspend fun syncRemoteWorkingCopy(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        bytes: ByteArray,
        sourceRevision: KeePassSourceRevision
    ): RemoteSyncOutcome? {
        if (!database.isRemoteSource() || database.sourceId == null) {
            return null
        }

        val remoteDb = PasswordDatabase.getDatabase(context)
        val syncService = RemoteKeePassSyncService(
            databaseDao = dao,
            remoteSourceDao = remoteDb.keepassRemoteSourceDao(),
            syncStateDao = remoteDb.keepassRemoteSyncStateDao(),
            strings = strings,
        )
        val syncStateDao = remoteDb.keepassRemoteSyncStateDao()
        return try {
            when (database.sourceType) {
                KeePassDatabaseSourceType.REMOTE_WEBDAV -> {
                    val remoteSource = remoteDb.keepassRemoteSourceDao().getSourceById(database.sourceId)
                        ?: throw IllegalStateException(strings.get(R.string.keepass_error_remote_source_missing))
                    val expectedRemoteVersion = syncStateDao.getState(database.id)?.let { state ->
                        state.remoteEtag ?: state.remoteVersionToken
                    }
                    val fileSource = WebDavKeePassSupport.createFileSource(remoteSource, securityManager, strings = strings)
                    val writeResult = fileSource.write(bytes, expectedVersion = expectedRemoteVersion)
                    val verifiedRemote = verifyRemoteKdbxWrite(
                        database = database,
                        fileSource = fileSource,
                        credentials = credentials,
                        expectedBytes = bytes,
                        expectedRevision = sourceRevision,
                        sourceLabel = "foreground-webdav"
                    )
                    database.cacheCopyPath?.let { cachePath ->
                        writeInternalRelative(cachePath, verifiedRemote.bytes)
                    }
                    syncService.markSynchronized(
                        databaseId = database.id,
                        versionToken = writeResult.versionToken,
                        etag = writeResult.etag,
                        baseHash = verifiedRemote.hash,
                        workingHash = verifiedRemote.hash
                    )
                    RemoteSyncOutcome(
                        writeResult = writeResult,
                        finalBytes = verifiedRemote.bytes,
                        finalDatabase = verifiedRemote.database,
                        finalRevision = verifiedRemote.revision
                    )
                }
                KeePassDatabaseSourceType.REMOTE_ONEDRIVE -> {
                    val remoteSourceDao = remoteDb.keepassRemoteSourceDao()
                    val remoteSource = remoteSourceDao.getSourceById(database.sourceId)
                        ?: throw IllegalStateException(strings.get(R.string.keepass_error_remote_source_missing))
                    val expectedRemoteVersion = syncStateDao.getState(database.id)?.let { state ->
                        state.remoteEtag ?: state.remoteVersionToken
                    }
                    val fileSource = OneDriveKeePassSupport.createFileSource(context, remoteSource)
                    val syncOutcome = try {
                        val writeResult = fileSource.write(bytes, expectedVersion = expectedRemoteVersion)
                        val verifiedRemote = verifyRemoteKdbxWrite(
                            database = database,
                            fileSource = fileSource,
                            credentials = credentials,
                            expectedBytes = bytes,
                            expectedRevision = sourceRevision,
                            sourceLabel = "foreground-onedrive"
                        )
                        RemoteSyncOutcome(
                            writeResult = writeResult,
                            finalBytes = verifiedRemote.bytes,
                            finalDatabase = verifiedRemote.database,
                            finalRevision = verifiedRemote.revision
                        )
                    } catch (error: Exception) {
                        if (!isRemoteVersionConflict(error)) {
                            throw error
                        }
                        throw KeePassSourceChangedException(
                            strings.get(R.string.keepass_sync_both_changed)
                        )
                    }
                    if ((remoteSource.itemId.isNullOrBlank() || remoteSource.driveId.isNullOrBlank()) &&
                        (!syncOutcome.writeResult.remoteId.isNullOrBlank() || !syncOutcome.writeResult.driveId.isNullOrBlank())
                    ) {
                        remoteSourceDao.updateSource(
                            remoteSource.copy(
                                itemId = syncOutcome.writeResult.remoteId ?: remoteSource.itemId,
                                driveId = syncOutcome.writeResult.driveId ?: remoteSource.driveId,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    database.cacheCopyPath?.let { cachePath ->
                        writeInternalRelative(cachePath, syncOutcome.finalBytes)
                    }
                    syncService.markSynchronized(
                        databaseId = database.id,
                        versionToken = syncOutcome.writeResult.versionToken,
                        etag = syncOutcome.writeResult.etag,
                        baseHash = syncOutcome.finalRevision.sha256,
                        workingHash = syncOutcome.finalRevision.sha256
                    )
                    syncOutcome
                }
                KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE -> {
                    val remoteSource = remoteDb.keepassRemoteSourceDao().getSourceById(database.sourceId)
                        ?: throw IllegalStateException(strings.get(R.string.keepass_error_remote_source_missing))
                    val expectedRemoteVersion = syncStateDao.getState(database.id)?.let { state ->
                        KeePassRemoteVersionPolicy.preferred(
                            versionToken = state.remoteVersionToken,
                            etag = state.remoteEtag
                        )
                    }
                    val fileSource = GoogleDriveKeePassSupport.createFileSource(context, remoteSource)
                    val writeResult = fileSource.write(bytes, expectedVersion = expectedRemoteVersion)
                    val verifiedRemote = verifyRemoteKdbxWrite(
                        database = database,
                        fileSource = fileSource,
                        credentials = credentials,
                        expectedBytes = bytes,
                        expectedRevision = sourceRevision,
                        sourceLabel = "foreground-googledrive"
                    )
                    database.cacheCopyPath?.let { cachePath ->
                        writeInternalRelative(cachePath, verifiedRemote.bytes)
                    }
                    syncService.markSynchronized(
                        databaseId = database.id,
                        versionToken = writeResult.versionToken,
                        etag = writeResult.etag,
                        baseHash = verifiedRemote.hash,
                        workingHash = verifiedRemote.hash
                    )
                    RemoteSyncOutcome(
                        writeResult = writeResult,
                        finalBytes = verifiedRemote.bytes,
                        finalDatabase = verifiedRemote.database,
                        finalRevision = verifiedRemote.revision
                    )
                }
                else -> null
            }
        } catch (error: Exception) {
            if (isRemoteVersionConflict(error)) {
                syncService.markConflict(
                    databaseId = database.id,
                    workingHash = sourceRevision.sha256,
                    failureMessage = error.message ?: strings.get(R.string.keepass_sync_both_changed)
                )
            } else {
                syncService.markLocalChanges(database.id, sourceRevision.sha256)
                syncService.markSyncFailure(
                    databaseId = database.id,
                    failureCode = "REMOTE_WRITE_FAILED",
                    failureMessage = error.message ?: strings.get(R.string.keepass_operation_remote_sync_failed)
                )
            }
            Log.w(TAG, "Remote working copy sync failed for db=${database.id}", error)
            null
        }
    }

    private suspend fun markRemoteWritePending(
        database: LocalKeePassDatabase,
        sourceRevision: KeePassSourceRevision
    ) {
        if (!database.isRemoteSource() || database.sourceId == null) {
            return
        }
        val remoteDb = PasswordDatabase.getDatabase(context)
        val syncService = RemoteKeePassSyncService(
            databaseDao = dao,
            remoteSourceDao = remoteDb.keepassRemoteSourceDao(),
            syncStateDao = remoteDb.keepassRemoteSyncStateDao(),
            strings = strings,
        )
        syncService.markLocalChanges(
            databaseId = database.id,
            workingHash = sourceRevision.sha256
        )
    }

    private fun enqueueRemoteWorkingCopyUpload(databaseId: Long) {
        val taskId = SyncDiagnostics.nextTaskId("kp-upload")
        val target = "keepass:$databaseId"
        val trigger = "REMOTE_WORKING_COPY_UPLOAD"
        SyncDiagnostics.queued(
            taskId = taskId,
            target = target,
            trigger = trigger,
            detail = "worker=true"
        )
        val startedAt = SyncDiagnostics.start(
            taskId = taskId,
            target = target,
            trigger = trigger,
            detail = "worker=true"
        )
        KeePassRemoteUploadWorker.enqueue(context, databaseId)
        SyncDiagnostics.success(
            taskId = taskId,
            target = target,
            trigger = trigger,
            startedAt = startedAt,
            detail = "worker_enqueued=true"
        )
    }

    suspend fun flushPendingRemoteUpload(databaseId: Long): Boolean = withContext(Dispatchers.IO) {
        val database = dao.getDatabaseById(databaseId)
        if (database == null || !database.isRemoteSource() || database.sourceId == null) {
            return@withContext false
        }
        val workingBytes = readRemoteWorkingCopyBytes(database) ?: return@withContext false
        uploadRemoteWorkingCopyIfCurrent(
            databaseId = databaseId,
            expectedHash = GoogleDriveKeePassSupport.sha256Hex(workingBytes)
        )
        true
    }

    private suspend fun uploadRemoteWorkingCopyIfCurrent(
        databaseId: Long,
        expectedHash: String
    ) {
        remoteUploadMutex(databaseId).withLock {
            uploadRemoteWorkingCopyIfCurrentLocked(databaseId, expectedHash)
        }
    }

    private suspend fun uploadRemoteWorkingCopyIfCurrentLocked(
        databaseId: Long,
        expectedHash: String
    ) {
        val remoteDb = PasswordDatabase.getDatabase(context)
        val remoteSourceDao = remoteDb.keepassRemoteSourceDao()
        val syncStateDao = remoteDb.keepassRemoteSyncStateDao()
        val syncService = RemoteKeePassSyncService(
            databaseDao = dao,
            remoteSourceDao = remoteSourceDao,
            syncStateDao = syncStateDao,
            strings = strings,
        )
        val database = dao.getDatabaseById(databaseId)
        if (database == null || !database.isRemoteSource() || database.sourceId == null) {
            return
        }
        val workingBytes = readRemoteWorkingCopyBytes(database) ?: return
        val workingHash = GoogleDriveKeePassSupport.sha256Hex(workingBytes)
        val syncState = syncStateDao.getState(databaseId)
        if (syncState?.hasLocalChanges == false && syncState.workingHash == workingHash) {
            return
        }
        if (syncState?.workingHash != null && syncState.workingHash != expectedHash && syncState.workingHash != workingHash) {
            return
        }

        val pendingRepository = keePassPendingChangeRepository()
        val pendingPlan = KeePassPendingFlushPlanner(pendingRepository).prepare(databaseId)
        pendingPlan.blocked.forEach { blocked ->
            pendingRepository.markBlocked(
                id = blocked.pendingId,
                reason = "${blocked.reason}: ${blocked.message}"
            )
        }
        pendingPlan.ready.forEach { item ->
            pendingRepository.markInProgress(item.pendingId)
        }

        var conflictFileSource: KeePassFileSource? = null
        try {
            syncService.markUploadInProgress(databaseId, workingHash)
            val remoteSource = remoteSourceDao.getSourceById(database.sourceId)
                ?: throw IllegalStateException(strings.get(R.string.keepass_error_remote_source_missing))
            val fileSource = createRemoteFileSource(database, remoteSource)
            conflictFileSource = fileSource
            val credentials = loadDatabase(databaseId).credentials
            val expectedRemoteVersion = when (database.sourceType) {
                KeePassDatabaseSourceType.REMOTE_ONEDRIVE,
                KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE,
                KeePassDatabaseSourceType.REMOTE_WEBDAV -> {
                    syncStateDao.getState(databaseId)?.let { state ->
                        state.remoteEtag ?: state.remoteVersionToken
                    }
                }
                else -> null
            }
            val writeResult = fileSource.write(workingBytes, expectedVersion = expectedRemoteVersion)
            val verifiedRemote = verifyRemoteKdbxWrite(
                database = database,
                fileSource = fileSource,
                credentials = credentials,
                expectedBytes = workingBytes,
                sourceLabel = "worker-upload"
            )
            updateOneDriveRemoteSourceBindingIfNeeded(
                database = database,
                writeResult = writeResult
            )
            database.cacheCopyPath?.let { cachePath ->
                writeInternalRelative(cachePath, verifiedRemote.bytes)
            }

            val latestBytes = readRemoteWorkingCopyBytes(database)
            val latestHash = latestBytes?.let { GoogleDriveKeePassSupport.sha256Hex(it) }
            if (latestHash == null || latestHash == workingHash) {
                syncService.markSynchronized(
                    databaseId = database.id,
                    versionToken = writeResult.versionToken,
                    etag = writeResult.etag,
                    baseHash = verifiedRemote.hash,
                    workingHash = verifiedRemote.hash
                )
                pendingPlan.ready.forEach { item ->
                    pendingRepository.markCompleted(item.pendingId)
                }
                pendingPlan.blocked.forEach { item ->
                    pendingRepository.markCompleted(item.pendingId)
                }
            } else {
                syncService.markUploadedButLocalChanged(
                    databaseId = database.id,
                    versionToken = writeResult.versionToken,
                    etag = writeResult.etag,
                    baseHash = verifiedRemote.hash,
                    workingHash = latestHash
                )
                pendingPlan.ready.forEach { item ->
                    pendingRepository.markCompleted(item.pendingId)
                }
                pendingPlan.blocked.forEach { item ->
                    pendingRepository.markCompleted(item.pendingId)
                }
                enqueueRemoteWorkingCopyUpload(database.id)
            }
        } catch (error: Exception) {
            val latestHash = readRemoteWorkingCopyBytes(database)
                ?.let { GoogleDriveKeePassSupport.sha256Hex(it) }
                ?: workingHash
            val fileSource = conflictFileSource
            var rebaseFailure: Throwable? = null
            if (isRemoteVersionConflict(error) && fileSource != null) {
                val rebaseResult = runCatching {
                    rebasePendingChangesOntoLatestRemote(
                        database = database,
                        fileSource = fileSource,
                        syncService = syncService,
                        pendingPlan = pendingPlan,
                        originalWorkingHash = workingHash
                    )
                }.onFailure { failure ->
                    rebaseFailure = failure
                }.getOrNull()
                if (rebaseResult != null) {
                    pendingPlan.ready.forEach { item ->
                        pendingRepository.markCompleted(item.pendingId)
                    }
                    val currentWorkingBytes = readRemoteWorkingCopyBytes(database)
                    val currentWorkingHash = currentWorkingBytes
                        ?.let { GoogleDriveKeePassSupport.sha256Hex(it) }
                        ?: rebaseResult.rebasedHash
                    if (currentWorkingHash == rebaseResult.rebasedHash) {
                        syncService.markSynchronized(
                            databaseId = database.id,
                            versionToken = rebaseResult.writeResult.versionToken,
                            etag = rebaseResult.writeResult.etag,
                            baseHash = rebaseResult.rebasedHash,
                            workingHash = rebaseResult.rebasedHash
                        )
                    } else {
                        syncService.markUploadedButLocalChanged(
                            databaseId = database.id,
                            versionToken = rebaseResult.writeResult.versionToken,
                            etag = rebaseResult.writeResult.etag,
                            baseHash = rebaseResult.rebasedHash,
                            workingHash = currentWorkingHash
                        )
                        enqueueRemoteWorkingCopyUpload(database.id)
                    }
                    return
                }
                syncService.markConflict(
                    databaseId = database.id,
                    workingHash = latestHash,
                    failureMessage = rebaseFailure?.message
                        ?: error.message
                        ?: strings.get(R.string.keepass_sync_remote_changed)
                )
            } else {
                syncService.markLocalChanges(database.id, latestHash)
                syncService.markSyncFailure(
                    databaseId = database.id,
                    failureCode = "REMOTE_BACKGROUND_UPLOAD_FAILED",
                    failureMessage = error.message ?: strings.get(R.string.keepass_operation_remote_sync_failed)
                )
            }
            pendingPlan.ready.forEach { item ->
                pendingRepository.markFailed(
                    id = item.pendingId,
                    error = rebaseFailure ?: error,
                    retryDelayMillis = PENDING_CHANGE_UPLOAD_RETRY_DELAY_MILLIS
                )
            }
            throw error
        }
    }

    private data class KeePassRemoteRebaseResult(
        val writeResult: FileSourceWriteResult,
        val rebasedHash: String
    )

    private suspend fun rebasePendingChangesOntoLatestRemote(
        database: LocalKeePassDatabase,
        fileSource: KeePassFileSource,
        syncService: RemoteKeePassSyncService,
        pendingPlan: KeePassPendingFlushPlan,
        originalWorkingHash: String
    ): KeePassRemoteRebaseResult? {
        if (!pendingPlan.hasReadyChanges) {
            return null
        }
        KeePassRemoteRebase.requireNoBlockedChanges(pendingPlan)
        val remoteStat = fileSource.stat()
        val currentRemoteVersion = remoteStat.etag ?: remoteStat.versionToken
        if (currentRemoteVersion.isNullOrBlank()) {
            return null
        }

        syncService.markDownloading(database.id, originalWorkingHash)
        val credentials = loadDatabase(database.id).credentials
        val remoteBytes = fileSource.read()
        val remoteDatabase = decodeDatabase(
            bytes = remoteBytes,
            credentials = credentials,
            sourceName = "databaseId=${database.id}:pending-rebase"
        )
        val rebaseResult = KeePassRemoteRebase.applyReadyChanges(remoteDatabase, pendingPlan)
            ?: return null
        val rebasedBytes = encodeDatabase(rebaseResult.updatedDatabase)
        val writeResult = fileSource.write(
            bytes = rebasedBytes,
            expectedVersion = currentRemoteVersion
        )
        val verifiedRemote = verifyRemoteKdbxWrite(
            database = database,
            fileSource = fileSource,
            credentials = credentials,
            expectedBytes = rebasedBytes,
            sourceLabel = "pending-rebase"
        )
        updateOneDriveRemoteSourceBindingIfNeeded(
            database = database,
            writeResult = writeResult
        )

        database.cacheCopyPath?.let { cachePath ->
            writeInternalRelative(cachePath, verifiedRemote.bytes)
        }
        val latestWorkingHash = readRemoteWorkingCopyBytes(database)
            ?.let { GoogleDriveKeePassSupport.sha256Hex(it) }
        if (latestWorkingHash == null || latestWorkingHash == originalWorkingHash) {
            database.workingCopyPath?.let { workingCopyPath ->
                writeInternalRelative(workingCopyPath, verifiedRemote.bytes)
            }
        }

        return KeePassRemoteRebaseResult(
            writeResult = writeResult,
            rebasedHash = verifiedRemote.hash
        )
    }

    private suspend fun createRemoteFileSource(
        database: LocalKeePassDatabase,
        remoteSource: takagi.ru.monica.data.KeepassRemoteSource
    ): KeePassFileSource = when (database.sourceType) {
        KeePassDatabaseSourceType.REMOTE_WEBDAV -> WebDavKeePassSupport.createFileSource(remoteSource, securityManager, strings = strings)
        KeePassDatabaseSourceType.REMOTE_ONEDRIVE -> OneDriveKeePassSupport.createFileSource(context, remoteSource)
        KeePassDatabaseSourceType.REMOTE_GOOGLE_DRIVE -> GoogleDriveKeePassSupport.createFileSource(context, remoteSource)
        else -> throw IllegalStateException(strings.get(R.string.keepass_error_source_type, database.sourceType))
    }

    private suspend fun updateOneDriveRemoteSourceBindingIfNeeded(
        database: LocalKeePassDatabase,
        writeResult: FileSourceWriteResult
    ) {
        if (database.sourceType != KeePassDatabaseSourceType.REMOTE_ONEDRIVE || database.sourceId == null) {
            return
        }
        if (writeResult.remoteId.isNullOrBlank() && writeResult.driveId.isNullOrBlank()) {
            return
        }
        val remoteDb = PasswordDatabase.getDatabase(context)
        val remoteSourceDao = remoteDb.keepassRemoteSourceDao()
        val remoteSource = remoteSourceDao.getSourceById(database.sourceId) ?: return
        if (!remoteSource.itemId.isNullOrBlank() && !remoteSource.driveId.isNullOrBlank()) {
            return
        }
        remoteSourceDao.updateSource(
            remoteSource.copy(
                itemId = writeResult.remoteId ?: remoteSource.itemId,
                driveId = writeResult.driveId ?: remoteSource.driveId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun readRemoteWorkingCopyBytes(database: LocalKeePassDatabase): ByteArray? {
        val workingCopyPath = database.workingCopyPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(context.filesDir, workingCopyPath)
        return if (file.exists()) file.readBytes() else null
    }

    internal suspend fun verifyRemoteKdbxWrite(
        databaseId: Long,
        fileSource: KeePassFileSource,
        expectedBytes: ByteArray,
        sourceLabel: String
    ): RemoteKdbxVerification = withContext(Dispatchers.IO) {
        val database = dao.getDatabaseById(databaseId)
            ?: throw IllegalStateException(strings.get(R.string.keepass_connection_status_missing))
        val credentials = loadDatabase(databaseId).credentials
        verifyRemoteKdbxWrite(
            database = database,
            fileSource = fileSource,
            credentials = credentials,
            expectedBytes = expectedBytes,
            sourceLabel = sourceLabel
        )
    }

    private suspend fun verifyRemoteKdbxWrite(
        database: LocalKeePassDatabase,
        fileSource: KeePassFileSource,
        credentials: Credentials,
        expectedBytes: ByteArray,
        expectedRevision: KeePassSourceRevision = KeePassSourceSafety.revisionOf(expectedBytes),
        sourceLabel: String
    ): RemoteKdbxVerification {
        val expectedHash = expectedRevision.sha256
        return try {
            val remoteBytes = fileSource.read()
            val remoteRevision = KeePassSourceSafety.revisionOf(remoteBytes)
            if (remoteRevision != expectedRevision) {
                throw IOException(
                    strings.get(R.string.keepass_error_upload_verification, remoteBytes.size, expectedBytes.size)
                )
            }
            val decoded = decodeDatabase(
                bytes = remoteBytes,
                credentials = credentials,
                sourceName = "databaseId=${database.id}:$sourceLabel"
            )
            Log.i(
                TAG,
                "Remote KDBX write verified db=${database.id} source=${database.sourceType} label=$sourceLabel bytes=${remoteBytes.size} hash=${remoteRevision.sha256.take(12)}"
            )
            RemoteKdbxVerification(
                bytes = remoteBytes,
                database = decoded,
                revision = remoteRevision
            )
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Remote KDBX write verification failed db=${database.id} source=${database.sourceType} label=$sourceLabel expectedBytes=${expectedBytes.size} expectedHash=${expectedHash.take(12)}",
                error
            )
            throw error
        }
    }

    private suspend fun getCachedLoadedDatabase(databaseId: Long): LoadedDatabase? {
        if (consumeProcessCacheInvalidation(databaseId)) {
            invalidateLoadedDatabaseCache(databaseId)
            return null
        }

        val now = System.currentTimeMillis()
        val cached = synchronized(loadedDatabaseCache) { loadedDatabaseCache[databaseId] } ?: return null

        val latestDatabase = runCatching { dao.getDatabaseById(databaseId) }.getOrNull() ?: return null
        val previous = cached.loaded.database
        val configChanged =
            latestDatabase.filePath != previous.filePath ||
                latestDatabase.storageLocation != previous.storageLocation ||
                latestDatabase.sourceType != previous.sourceType ||
                latestDatabase.sourceId != previous.sourceId ||
                latestDatabase.workingCopyPath != previous.workingCopyPath ||
                latestDatabase.cacheCopyPath != previous.cacheCopyPath ||
                latestDatabase.keyFileUri != previous.keyFileUri ||
                latestDatabase.keyFileInternalPath != previous.keyFileInternalPath ||
                latestDatabase.keyFileFingerprint != previous.keyFileFingerprint ||
                latestDatabase.encryptedPassword != previous.encryptedPassword
        if (configChanged) {
            invalidateLoadedDatabaseCache(databaseId)
            return null
        }

        // Internal storage: keep cache warm as long as underlying file signature is unchanged.
        val cachedSignature = cached.loaded.sourceSignature
        if (cachedSignature != null) {
            val currentSignature = currentSourceSignature(latestDatabase)
            if (currentSignature == null || currentSignature != cachedSignature) {
                invalidateLoadedDatabaseCache(databaseId)
                return null
            }
        } else if (now - cached.cachedAtMs > UNKNOWN_SOURCE_CACHE_TTL_MS) {
            // External URI source does not have cheap signature checks, so keep a bounded cache window.
            invalidateLoadedDatabaseCache(databaseId)
            return null
        }

        return cached.loaded.copy(database = latestDatabase)
    }

    private fun currentSourceSignature(database: LocalKeePassDatabase): DatabaseSourceSignature? {
        if (database.resolvedActiveStorageLocation() != KeePassStorageLocation.INTERNAL) return null
        val file = File(context.filesDir, database.resolvedActiveFilePath())
        if (!file.exists()) return null
        return DatabaseSourceSignature(
            sizeBytes = file.length(),
            lastModifiedEpochMs = file.lastModified()
        )
    }

    private fun cacheLoadedDatabase(loaded: LoadedDatabase) {
        synchronized(loadedDatabaseCache) {
            loadedDatabaseCache[loaded.database.id] = CachedLoadedDatabase(
                loaded = loaded,
                cachedAtMs = System.currentTimeMillis()
            )
            trimLoadedDatabaseCacheLocked()
        }
    }

    private fun invalidateLoadedDatabaseCache(databaseId: Long) {
        synchronized(loadedDatabaseCache) {
            loadedDatabaseCache.remove(databaseId)
        }
        nativeSessionCache.invalidate(databaseId)
        nativeProjectionBundleCache.invalidate(databaseId)
    }

    private fun encodeDatabase(
        keePassDatabase: KeePassDatabase,
        estimatedSizeBytes: Long? = null
    ): ByteArray {
        return ByteArrayOutputStream(
            KeePassEncodeBufferPolicy.initialCapacity(estimatedSizeBytes)
        ).use { output ->
            keePassDatabase.encode(output, contentParser = KeePassCodecSupport.contentParser,
                cipherProviders = KeePassCodecSupport.cipherProviders)
            output.toByteArray()
        }
    }

    private fun encodeDatabaseArtifact(keePassDatabase: KeePassDatabase): EncodedDatabaseArtifact {
        val file = File.createTempFile("keepass-encoded-", ".kdbx", context.cacheDir)
        try {
            val revision = encodeKeePassDatabaseArtifactFile(keePassDatabase, file)
            return EncodedDatabaseArtifact(file, revision)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun writeInternal(database: LocalKeePassDatabase, encodedFile: File) {
        try {
            val file = File(context.filesDir, database.resolvedActiveFilePath())
            writeInternalFile(file, encodedFile)
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private fun writeInternal(database: LocalKeePassDatabase, bytes: ByteArray) {
        try {
            val file = File(context.filesDir, database.resolvedActiveFilePath())
            writeInternalFile(file, bytes)
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private fun writeInternalRelative(relativePath: String, bytes: ByteArray) {
        try {
            val file = File(context.filesDir, relativePath)
            writeInternalFile(file, bytes)
        } catch (t: Throwable) {
            throw normalizeError(t)
        }
    }

    private fun writeInternalFile(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException(strings.get(R.string.storage_error_invalid_path))
        if (!parent.exists()) parent.mkdirs()
        val tempFile = File(parent, "${file.name}.tmp")
        val backupFile = File(parent, "${file.name}.bak")
        FileOutputStream(tempFile).use {
            it.write(bytes)
            it.flush()
            it.fd.sync()
        }
        if (file.exists()) {
            if (backupFile.exists()) backupFile.delete()
            if (!file.renameTo(backupFile)) {
                backupFile.delete()
            }
        }
        val renamed = tempFile.renameTo(file)
        if (!renamed) {
            FileOutputStream(file).use {
                it.write(bytes)
                it.flush()
                it.fd.sync()
            }
            tempFile.delete()
        }
        if (backupFile.exists()) backupFile.delete()
    }

    private fun writeInternalFile(file: File, encodedFile: File) {
        val parent = file.parentFile ?: throw IOException(strings.get(R.string.storage_error_invalid_path))
        if (!parent.exists() && !parent.mkdirs()) throw IOException(strings.get(R.string.storage_error_create_directory))
        val tempFile = File(parent, "${file.name}.tmp")
        val backupFile = File(parent, "${file.name}.bak")
        encodedFile.inputStream().use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (file.exists()) {
            if (backupFile.exists()) backupFile.delete()
            if (!file.renameTo(backupFile)) backupFile.delete()
        }
        if (!tempFile.renameTo(file)) {
            tempFile.inputStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            tempFile.delete()
        }
        if (backupFile.exists()) backupFile.delete()
    }

    private fun writeExternal(
        database: LocalKeePassDatabase,
        bytes: ByteArray,
        targetRevision: KeePassSourceRevision,
        expectedSourceRevision: KeePassSourceRevision?,
    ) {
        val temporary = File.createTempFile("keepass-raw-write-", ".kdbx", context.cacheDir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            writeExternal(database, temporary, targetRevision, expectedSourceRevision)
        } finally {
            temporary.delete()
        }
    }

    private fun writeExternal(
        database: LocalKeePassDatabase,
        encodedFile: File,
        targetRevision: KeePassSourceRevision,
        expectedSourceRevision: KeePassSourceRevision?
    ) {
        val uri = Uri.parse(database.resolvedActiveFilePath())
        val originalRevision = openExternalInputStream(uri)?.use(KeePassSourceSafety::revisionOf)
            ?: throw IOException(strings.get(R.string.storage_error_read_database))
        expectedSourceRevision?.let { expected ->
            KeePassSourceSafety.requireUnchanged(
                expectedRevision = expected,
                currentRevision = originalRevision,
                sourceLabel = uri.toString(),
                strings = strings,
            )
        }
        val recoveryCopy = openExternalInputStream(uri)?.use { input ->
            recoveryStore.create(database.id, input)
        } ?: throw IOException(strings.get(R.string.storage_error_recovery_copy))
        try {
            writeExternalFile(uri, encodedFile)
            val writtenRevision = openExternalInputStream(uri)?.use(KeePassSourceSafety::revisionOf)
                ?: throw IOException(strings.get(R.string.storage_error_verify_database))
            if (writtenRevision != targetRevision) {
                throw IOException(strings.get(R.string.storage_error_write_verification))
            }
            if (!recoveryStore.deleteVerified(recoveryCopy)) {
                Log.w(TAG, "Verified KeePass recovery copy could not be removed: ${recoveryCopy.file}")
            }
            recoveryStore.prune(database.id)
        } catch (e: Exception) {
            val restored = runCatching {
                writeExternalFile(uri, recoveryCopy.file)
                val restoredRevision = openExternalInputStream(uri)?.use(KeePassSourceSafety::revisionOf)
                if (restoredRevision != originalRevision) {
                    throw IOException(strings.get(R.string.storage_error_rollback_verification))
                }
            }.isSuccess
            if (restored) {
                recoveryStore.deleteVerified(recoveryCopy)
            } else {
                Log.e(
                    TAG,
                    "External KeePass restore failed; retained recovery copy at ${recoveryCopy.file}"
                )
            }
            throw normalizeError(e)
        }
    }

    private fun writeExternalBytes(uri: Uri, bytes: ByteArray) {
        openExternalOutputStream(uri)?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException(strings.get(R.string.storage_error_write_database))
    }

    private fun openExternalOutputStream(uri: Uri) =
        try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "openOutputStream wt failed, retry with rwt", e)
            context.contentResolver.openOutputStream(uri, "rwt")
        }

    internal suspend fun inspectCurrentRemoteConflict(
        databaseId: Long
    ): Result<KeePassRemoteConflictPreview> = withContext(Dispatchers.IO) {
        withRemoteDatabaseLocks(databaseId) { inspectCurrentRemoteConflictLocked(databaseId) }
    }

    private suspend fun <T> withRemoteDatabaseLocks(databaseId: Long, block: suspend () -> T): T =
        withDatabaseMutationLocks(listOf(databaseId)) {
            // Keep the same order as a local edit followed by a foreground upload.
            remoteUploadMutex(databaseId).withLock { block() }
        }

    private suspend fun inspectCurrentRemoteConflictLocked(
        databaseId: Long
    ): Result<KeePassRemoteConflictPreview> {
        return try {
            val context = loadCurrentRemoteConflictContext(databaseId)
            Result.success(
                KeePassRemoteConflictPreview(
                    snapshot = KeePassConflictCenter.inspect(
                        context.baseDatabase,
                        context.localDatabase,
                        context.remoteDatabase
                    ),
                    localRevision = KeePassSourceSafety.revisionOf(context.localBytes),
                    remoteRevision = KeePassSourceSafety.revisionOf(context.remoteBytes),
                    baseRevision = KeePassSourceSafety.revisionOf(context.baseBytes),
                    remoteVersionToken = context.remoteStat.etag ?: context.remoteStat.versionToken,
                    remoteSizeBytes = context.remoteBytes.size.toLong()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private fun writeExternalFile(uri: Uri, sourceFile: File) {
        openExternalOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: throw IOException(strings.get(R.string.storage_error_write_database))
    }

    internal suspend fun resolveCurrentRemoteConflict(
        databaseId: Long,
        decision: KeePassConflictDecision,
        expectedLocalRevision: String,
        expectedRemoteRevision: String,
        selections: Map<String, KeePassConflictResolutionSide> = emptyMap()
    ): Result<KeePassRemoteConflictResolution> = withContext(Dispatchers.IO) {
        withRemoteDatabaseLocks(databaseId) {
            resolveCurrentRemoteConflictLocked(
                databaseId, decision, expectedLocalRevision, expectedRemoteRevision, selections
            )
        }
    }

    private suspend fun resolveCurrentRemoteConflictLocked(
        databaseId: Long,
        decision: KeePassConflictDecision,
        expectedLocalRevision: String,
        expectedRemoteRevision: String,
        selections: Map<String, KeePassConflictResolutionSide>
    ): Result<KeePassRemoteConflictResolution> {
        return try {
            val context = loadCurrentRemoteConflictContext(databaseId)
            val localRevision = KeePassSourceSafety.revisionOf(context.localBytes)
            val remoteRevision = KeePassSourceSafety.revisionOf(context.remoteBytes)
            require(localRevision.sha256 == expectedLocalRevision) {
                "The local KeePass database changed after conflict review; reopen the conflict center"
            }
            require(remoteRevision.sha256 == expectedRemoteRevision) {
                "The remote KeePass database changed after conflict review; reopen the conflict center"
            }
            val resolved = if (decision == KeePassConflictDecision.MERGE) {
                KeePassConflictCenter.resolveSelected(
                    baseDatabase = context.baseDatabase,
                    localDatabase = context.localDatabase,
                    remoteDatabase = context.remoteDatabase,
                    selections = selections
                )
            } else {
                KeePassConflictCenter.resolve(
                    baseDatabase = context.baseDatabase,
                    localDatabase = context.localDatabase,
                    remoteDatabase = context.remoteDatabase,
                    decision = decision
                )
            }
            if (resolved.cancelled || resolved.database == null) {
                return Result.success(
                    KeePassRemoteConflictResolution(
                        decision = decision,
                        conflictCopyCount = 0,
                        finalRevision = null,
                        cancelled = true,
                        retainedRecoveryCopies = 0
                    )
                )
            }

            accessPolicy.requireWritable(databaseId)
            val pendingRepository = keePassPendingChangeRepository()
            val reviewedChangeIds = pendingRepository.getUnfinishedChangesByDatabase(databaseId).map { it.id }
            val finalDatabase = resolved.database
            val finalBytes = encodeDatabase(finalDatabase)
            requirePreflightAllowed(
                KeePassWritePreflight.evaluateRuntime(
                    currentDatabaseBytes = maxOf(context.localBytes.size, context.remoteBytes.size).toLong(),
                    incomingPayloadBytes = finalBytes.size.toLong()
                )
            )
            decodeDatabase(finalBytes, context.credentials, sourceName = "conflict-resolution-preview")
            val retainedCopies = listOf(context.localBytes, context.remoteBytes)
                .distinctBy { bytes -> KeePassSourceSafety.revisionOf(bytes).sha256 }
                .map { bytes -> recoveryStore.create(databaseId, bytes) }

            val remoteDb = PasswordDatabase.getDatabase(this@KeePassKdbxService.context)
            val syncService = RemoteKeePassSyncService(
                databaseDao = dao,
                remoteSourceDao = remoteDb.keepassRemoteSourceDao(),
                syncStateDao = remoteDb.keepassRemoteSyncStateDao(),
                strings = strings,
            )
            val finalWriteResult = if (decision == KeePassConflictDecision.USE_REMOTE) {
                FileSourceWriteResult(
                    versionToken = context.remoteStat.versionToken,
                    etag = context.remoteStat.etag,
                    lastModified = context.remoteStat.lastModified,
                    remoteId = context.remoteStat.remoteId,
                    driveId = context.remoteStat.driveId
                )
            } else {
                context.fileSource.write(
                    finalBytes,
                    expectedVersion = requireNotNull(context.remoteStat.etag ?: context.remoteStat.versionToken) {
                        "Remote version information is unavailable; refresh the comparison before writing"
                    }
                )
            }
            val verifiedBytes = if (decision == KeePassConflictDecision.USE_REMOTE) {
                context.remoteBytes
            } else {
                verifyRemoteKdbxWrite(
                    database = context.database,
                    fileSource = context.fileSource,
                    credentials = context.credentials,
                    expectedBytes = finalBytes,
                    sourceLabel = "conflict-center"
                ).bytes
            }
            val verifiedDatabase = if (decision == KeePassConflictDecision.USE_REMOTE) {
                context.remoteDatabase
            } else {
                decodeDatabase(verifiedBytes, context.credentials, sourceName = "conflict-center-verified")
            }
            val finalRevision = KeePassSourceSafety.revisionOf(verifiedBytes)
            withContext(NonCancellable) {
                writeInternalRelative(requireNotNull(context.database.workingCopyPath), verifiedBytes)
                writeInternalRelative(requireNotNull(context.database.cacheCopyPath), verifiedBytes)
                remoteDb.withTransaction {
                    updateOneDriveRemoteSourceBindingIfNeeded(context.database, finalWriteResult)
                    val latestRegistration = requireNotNull(dao.getDatabaseById(databaseId))
                    // Metadata writes copy the registration, so do this before marking it in sync.
                    updateStoredDatabaseMetadata(latestRegistration, verifiedDatabase)
                    syncService.markSynchronized(
                        databaseId = databaseId,
                        versionToken = finalWriteResult.versionToken,
                        etag = finalWriteResult.etag,
                        baseHash = finalRevision.sha256,
                        workingHash = finalRevision.sha256
                    )
                    pendingRepository.settleConflictChanges(
                        databaseId = databaseId,
                        changeIds = reviewedChangeIds,
                        discardLocalChanges = decision == KeePassConflictDecision.USE_REMOTE
                    )
                }
                invalidateProcessCache(databaseId)
            }
            recoveryStore.prune(databaseId, keepNewest = 6)
            Result.success(
                KeePassRemoteConflictResolution(
                    decision = decision,
                    conflictCopyCount = resolved.conflictCopyCount,
                    finalRevision = finalRevision,
                    cancelled = false,
                    retainedRecoveryCopies = retainedCopies.size
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private suspend fun loadCurrentRemoteConflictContext(
        databaseId: Long
    ): CurrentRemoteConflictContext {
        val database = dao.getDatabaseById(databaseId)
            ?: throw IllegalArgumentException("KeePass database not found: $databaseId")
        require(database.isRemoteSource() && database.sourceId != null) {
            "The selected KeePass database is not a remote database"
        }
        val remoteDb = PasswordDatabase.getDatabase(context)
        val remoteSource = remoteDb.keepassRemoteSourceDao().getSourceById(database.sourceId)
            ?: throw IllegalStateException("KeePass remote source not found")
        val fileSource = createRemoteFileSource(database, remoteSource)
        val remoteStat = fileSource.stat()
        val remoteBytes = fileSource.read()
        val loaded = loadDatabase(databaseId)
        val localBytes = readDatabaseSnapshot(database).bytes
        val cachePath = database.cacheCopyPath
            ?: throw IllegalStateException("KeePass conflict base copy is unavailable")
        val cacheFile = File(context.filesDir, cachePath)
        if (!cacheFile.isFile) throw IllegalStateException("KeePass conflict base copy is unavailable")
        val baseBytes = cacheFile.readBytes()
        return CurrentRemoteConflictContext(
            database = database,
            credentials = loaded.credentials,
            localDatabase = loaded.keePassDatabase,
            localBytes = localBytes,
            baseDatabase = decodeDatabase(
                baseBytes,
                loaded.credentials,
                sourceName = "databaseId=$databaseId:conflict-base",
                logFailure = false
            ),
            baseBytes = baseBytes,
            remoteDatabase = decodeDatabase(
                remoteBytes,
                loaded.credentials,
                sourceName = "databaseId=$databaseId:conflict-remote"
            ),
            remoteBytes = remoteBytes,
            remoteStat = remoteStat,
            fileSource = fileSource
        )
    }

    private suspend fun writeRawDatabaseBytes(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        keePassDatabase: KeePassDatabase,
        bytes: ByteArray,
        expectedSourceRevision: KeePassSourceRevision?
    ) {
        accessPolicy.requireWritable(database.id)
        val sourceRevision = KeePassSourceSafety.revisionOf(bytes)
        decodeDatabase(bytes, credentials, sourceName = "databaseId=${database.id}:raw-write-verify")
        if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
            writeInternal(database, bytes)
        } else {
            writeExternal(
                database = database,
                bytes = bytes,
                targetRevision = sourceRevision,
                expectedSourceRevision = expectedSourceRevision
            )
        }
        var resolvedDatabase = keePassDatabase
        var persistedRevision = sourceRevision
        var sourceEtag: String? = null
        var sourceLastModified: String? = null
        if (database.isRemoteSource()) {
            val outcome = syncRemoteWorkingCopy(
                database = database,
                credentials = credentials,
                bytes = bytes,
                sourceRevision = sourceRevision
            )
            if (outcome != null) {
                resolvedDatabase = outcome.finalDatabase
                persistedRevision = outcome.finalRevision
                sourceEtag = outcome.writeResult.etag
                sourceLastModified = outcome.writeResult.lastModified?.toString()
            } else {
                markRemoteWritePending(database, sourceRevision)
                enqueueRemoteWorkingCopyUpload(database.id)
            }
        }
        cachePersistedDatabase(
            database = database,
            credentials = credentials,
            keePassDatabase = resolvedDatabase,
            sourceRevision = persistedRevision,
            sourceEtag = sourceEtag,
            sourceLastModified = sourceLastModified,
        )
        updateStoredDatabaseMetadata(database, resolvedDatabase)
    }

    /** Restores a verified raw KDBX file without first materialising it as a ByteArray. */
    private suspend fun writeRawDatabaseFile(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        keePassDatabase: KeePassDatabase,
        file: File,
        sourceRevision: KeePassSourceRevision,
        expectedSourceRevision: KeePassSourceRevision?,
    ) {
        accessPolicy.requireWritable(database.id)
        require(KeePassSourceSafety.revisionOf(file) == sourceRevision) {
            "KeePass recovery source changed before restore"
        }
        if (database.resolvedActiveStorageLocation() == KeePassStorageLocation.INTERNAL) {
            writeInternal(database, file)
        } else {
            writeExternal(
                database = database,
                encodedFile = file,
                targetRevision = sourceRevision,
                expectedSourceRevision = expectedSourceRevision,
            )
        }
        var sourceEtag: String? = null
        var sourceLastModified: String? = null
        if (database.isRemoteSource()) {
            // Existing remote source APIs still accept byte arrays. Keep that allocation
            // at the actual network boundary while local restore remains fully streamed.
            val outcome = syncRemoteWorkingCopy(
                database = database,
                credentials = credentials,
                bytes = file.readBytes(),
                sourceRevision = sourceRevision,
            )
            if (outcome != null) {
                sourceEtag = outcome.writeResult.etag
                sourceLastModified = outcome.writeResult.lastModified?.toString()
            } else {
                markRemoteWritePending(database, sourceRevision)
                enqueueRemoteWorkingCopyUpload(database.id)
            }
        }
        cachePersistedDatabase(
            database = database,
            credentials = credentials,
            keePassDatabase = keePassDatabase,
            sourceRevision = sourceRevision,
            sourceEtag = sourceEtag,
            sourceLastModified = sourceLastModified,
        )
        updateStoredDatabaseMetadata(database, keePassDatabase)
    }

    private fun writeUriCopyVerified(uri: Uri, bytes: ByteArray) {
        openExternalOutputStream(uri)?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Unable to write the KeePass destination")
        val written = readBytesFromUri(uri, "Unable to verify the KeePass destination")
        if (KeePassSourceSafety.revisionOf(written) != KeePassSourceSafety.revisionOf(bytes)) {
            throw IOException("KeePass destination verification failed")
        }
    }

    private fun writeUriCopyVerified(
        uri: Uri,
        input: InputStream,
        expectedRevision: KeePassSourceRevision,
    ) {
        openExternalOutputStream(uri)?.use { output ->
            val copiedRevision = KeePassSourceSafety.copyAndRevision(input, output)
            output.flush()
            if (copiedRevision != expectedRevision) {
                throw IOException("KeePass source changed while copying")
            }
        } ?: throw IOException("Unable to write the KeePass destination")
        val writtenRevision = openExternalInputStream(uri)?.use(KeePassSourceSafety::revisionOf)
            ?: throw IOException("Unable to verify the KeePass destination")
        if (writtenRevision != expectedRevision) {
            throw IOException("KeePass destination verification failed")
        }
    }

    private fun requirePreflightAllowed(result: KeePassWritePreflightResult) {
        if (result.allowed) return
        val requiredMiB = (result.additionalBytesRequired + MIB_BYTES - 1L) / MIB_BYTES
        throw IOException(
            "Insufficient memory for this KeePass operation; approximately $requiredMiB MiB more is required"
        )
    }

    suspend fun resolveRemoteConflict(
        databaseId: Long,
        remoteBytes: ByteArray
    ): Result<KeePassConflictResolutionResult> = withContext(Dispatchers.IO) {
        try {
            val loaded = loadDatabase(databaseId)
            val resolved = resolveRemoteConflictInternal(
                database = loaded.database,
                credentials = loaded.credentials,
                localDatabase = loaded.keePassDatabase,
                remoteBytes = remoteBytes
            )
            Result.success(
                KeePassConflictResolutionResult(
                    mergedBytes = resolved.mergedBytes,
                    conflictCopyCount = resolved.conflictCopyCount
                )
            )
        } catch (error: Exception) {
            Result.failure(normalizeError(error))
        }
    }

    private fun isRemoteVersionConflict(error: Throwable): Boolean {
        return error.hasKeePassSourceChangedCause() ||
            error.message?.contains("远端文件已变化", ignoreCase = true) == true ||
            error.message?.contains(strings.get(R.string.keepass_sync_remote_changed), ignoreCase = true) == true ||
            error.message?.contains("remote file changed", ignoreCase = true) == true ||
            error.message?.contains("version conflict", ignoreCase = true) == true
    }

    private suspend fun resolveRemoteConflictInternal(
        database: LocalKeePassDatabase,
        credentials: Credentials,
        localDatabase: KeePassDatabase,
        remoteBytes: ByteArray
    ): InternalConflictResolutionResult {
        val cachePath = database.cacheCopyPath
            ?: throw IllegalStateException(strings.get(R.string.keepass_error_conflict_cache_missing))
        val cacheFile = File(context.filesDir, cachePath)
        if (!cacheFile.exists()) {
            throw IllegalStateException(strings.get(R.string.keepass_error_conflict_cache_missing))
        }

        val remoteDatabase = decodeDatabase(
            bytes = remoteBytes,
            credentials = credentials,
            sourceName = "databaseId=${database.id}:remote-conflict"
        )
        val baseDatabase = decodeDatabase(
            bytes = cacheFile.readBytes(),
            credentials = credentials,
            sourceName = "databaseId=${database.id}:base-conflict",
            logFailure = false
        )
        val mergedResult = KeePassConflictCenter.resolve(
            baseDatabase = baseDatabase,
            localDatabase = localDatabase,
            remoteDatabase = remoteDatabase,
            decision = KeePassConflictDecision.MERGE
        )
        val mergedDatabase = mergedResult.database
            ?: throw IllegalStateException("KeePass conflict merge was cancelled")
        return InternalConflictResolutionResult(
            mergedDatabase = mergedDatabase,
            mergedBytes = encodeDatabase(mergedDatabase),
            conflictCopyCount = mergedResult.conflictCopyCount
        )
    }

    private fun mergeDatabasesForConflictResolution(
        baseDatabase: KeePassDatabase,
        localDatabase: KeePassDatabase,
        remoteDatabase: KeePassDatabase
    ): Pair<KeePassDatabase, Int> {
        val baseEntries = buildConflictEntrySnapshotMap(baseDatabase)
        val localEntries = buildConflictEntrySnapshotMap(localDatabase)
        val remoteEntries = buildConflictEntrySnapshotMap(remoteDatabase)

        var mergedRoot = remoteDatabase.content.group
        var conflictCopyCount = 0

        val candidateUuids = (baseEntries.keys + localEntries.keys).toSet()
        candidateUuids.forEach { uuid ->
            val baseSnapshot = baseEntries[uuid]
            val localSnapshot = localEntries[uuid]
            val remoteSnapshot = remoteEntries[uuid]

            val localChanged = hasConflictSnapshotChanged(baseSnapshot, localSnapshot)
            if (!localChanged) {
                return@forEach
            }

            val remoteChanged = hasConflictSnapshotChanged(baseSnapshot, remoteSnapshot)
            if (localSnapshot == null) {
                if (!remoteChanged) {
                    mergedRoot = removeEntryInGroup(mergedRoot) { it.uuid == uuid }.first
                }
                return@forEach
            }

            if (remoteChanged && remoteSnapshot != null && hasConflictSnapshotChanged(localSnapshot, remoteSnapshot)) {
                val conflictCopy = buildRemoteConflictCopy(remoteSnapshot.entry)
                if (!containsConflictCopyInGroupPath(
                        rootGroup = mergedRoot,
                        groupPath = remoteSnapshot.groupPath,
                        candidate = conflictCopy
                    )
                ) {
                    mergedRoot = addEntryToGroupPath(
                        rootGroup = mergedRoot,
                        groupPath = remoteSnapshot.groupPath,
                        entry = conflictCopy
                    )
                    conflictCopyCount++
                }
            }

            val withoutExisting = removeEntryInGroup(mergedRoot) { it.uuid == uuid }.first
            mergedRoot = addEntryToGroupPath(
                rootGroup = withoutExisting,
                groupPath = localSnapshot.groupPath,
                entry = localSnapshot.entry
            )
        }

        return remoteDatabase.modifyParentGroup { mergedRoot } to conflictCopyCount
    }

    private fun buildConflictEntrySnapshotMap(
        keePassDatabase: KeePassDatabase
    ): Map<UUID, RemoteConflictEntrySnapshot> {
        return collectEntryContexts(keePassDatabase)
            .first
            .associate { context ->
                context.entry.uuid to RemoteConflictEntrySnapshot(
                    entry = context.entry,
                    groupPath = context.groupPath,
                    signature = buildConflictEntrySignature(context.entry)
                )
            }
    }

    private fun hasConflictSnapshotChanged(
        base: RemoteConflictEntrySnapshot?,
        candidate: RemoteConflictEntrySnapshot?
    ): Boolean {
        return when {
            base == null -> candidate != null
            candidate == null -> true
            else -> base.groupPath != candidate.groupPath || base.signature != candidate.signature
        }
    }

    private fun buildRemoteConflictCopy(entry: Entry): Entry {
        val currentTitle = getFieldValue(entry, "Title").ifBlank { "Untitled" }
        val normalizedConflictTitle = normalizeRemoteConflictTitle(currentTitle)
        var hasConflictMarker = false
        val updatedFields = entry.fields
            .mapNotNull { (key, value) ->
                if (key == FIELD_MONICA_LOCAL_ID || key == FIELD_MONICA_ITEM_ID) {
                    return@mapNotNull null
                }
                if (key == FIELD_MONICA_CONFLICT_COPY) {
                    hasConflictMarker = true
                    return@mapNotNull key to EntryValue.Plain("true")
                }
                if (key == "Title") {
                    key to EntryValue.Plain(normalizedConflictTitle)
                } else {
                    key to value
                }
            }
            .toMutableList()
        if (updatedFields.none { it.first == "Title" }) {
            updatedFields += "Title" to EntryValue.Plain(normalizedConflictTitle)
        }
        if (!hasConflictMarker) {
            updatedFields += FIELD_MONICA_CONFLICT_COPY to EntryValue.Plain("true")
        }
        return entry.copy(
            uuid = UUID.randomUUID(),
            fields = EntryFields.of(*updatedFields.toTypedArray())
        )
    }

    private fun containsConflictCopyInGroupPath(
        rootGroup: Group,
        groupPath: String?,
        candidate: Entry
    ): Boolean {
        val targetSignature = buildConflictEntrySignature(candidate)
        return collectEntriesInGroupPath(rootGroup, groupPath)
            .any { existing -> buildConflictEntrySignature(existing) == targetSignature }
    }

    private fun collectEntriesInGroupPath(
        rootGroup: Group,
        groupPath: String?
    ): List<Entry> {
        val collected = mutableListOf<Entry>()
        fun walk(group: Group, currentPath: String?) {
            if (currentPath == groupPath) {
                collected += group.entries
            }
            group.groups.forEach { child ->
                val nextPath = buildKeePassPathKey(currentPath, child.name)
                walk(child, nextPath)
            }
        }
        walk(rootGroup, null)
        return collected
    }

    private fun buildConflictEntrySignature(entry: Entry): String {
        return KeePassEntryFingerprint.build(entry)
    }

    private fun normalizeRemoteConflictTitle(title: String): String {
        val normalized = normalizeRemoteConflictTitleForSignature(title)
        val hasSuffix = normalized.endsWith(" $REMOTE_CONFLICT_TITLE_SUFFIX") ||
            normalized == REMOTE_CONFLICT_TITLE_SUFFIX
        if (hasSuffix) {
            return normalized
        }
        val baseTitle = normalized.ifBlank { "Untitled" }
        return "$baseTitle $REMOTE_CONFLICT_TITLE_SUFFIX"
    }

    private fun normalizeRemoteConflictTitleForSignature(title: String): String {
        val suffixPattern = Regex("\\s*\\Q$REMOTE_CONFLICT_TITLE_SUFFIX\\E(?:\\s*\\Q$REMOTE_CONFLICT_TITLE_SUFFIX\\E)*\\s*$")
        val baseTitle = title
            .replace(suffixPattern, "")
            .trim()
        val hadSuffix = suffixPattern.containsMatchIn(title)
        return if (hadSuffix) {
            val normalizedBase = baseTitle.ifBlank { "Untitled" }
            "$normalizedBase $REMOTE_CONFLICT_TITLE_SUFFIX"
        } else {
            baseTitle
        }
    }

    private fun normalizeError(throwable: Throwable): Throwable {
        if (throwable is KeePassOperationException || throwable is IllegalArgumentException) {
            return throwable
        }
        return throwable.toKeePassOperationException(strings)
    }

    private fun logMutationFailure(
        operation: String,
        databaseId: Long,
        error: Throwable,
    ): Throwable {
        val normalized = normalizeError(error)
        val root = normalized.rootCause()
        val code = (normalized as? KeePassOperationException)?.code
            ?: KeePassErrorCode.IO_READ_WRITE_FAILED
        Log.e(
            TAG,
            "$operation failed db=$databaseId code=$code root=${root.javaClass.name}: ${root.message.orEmpty()}",
            normalized,
        )
        return normalized
    }
}
