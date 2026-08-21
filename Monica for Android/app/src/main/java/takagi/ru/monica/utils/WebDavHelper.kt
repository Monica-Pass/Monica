package takagi.ru.monica.utils

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.widget.Toast
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordHistoryEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.PasswordHistoryManager
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.BackupReport
import takagi.ru.monica.data.RestoreReport
import takagi.ru.monica.data.ItemCounts
import takagi.ru.monica.data.FailedItem
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.isMdbxOwned
import takagi.ru.monica.data.resolveOwnership
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.data.model.isEmpty
import takagi.ru.monica.passkey.PasskeyBackupPortabilityPolicy
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SecurityQuestionsBackupSnapshot
import takagi.ru.monica.steam.data.SteamAccountRepository
import takagi.ru.monica.steam.data.SteamDatabase
import takagi.ru.monica.steam.importer.SteamMaFileBackupCodec
import takagi.ru.monica.steam.importer.SteamMaFileParser
import takagi.ru.monica.steam.importer.SteamMaFilePayload
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.util.DataExportImportManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

private class PasskeyBackupEncryptionRequiredException(message: String) :
    IllegalStateException(message)

private class SecurityQuestionsBackupEncryptionRequiredException(message: String) :
    IllegalStateException(message)

/**
 * 自定义字段备份数据结构
 */
@Serializable
data class CustomFieldBackupEntry(
    val title: String = "",
    val value: String = "",
    val isProtected: Boolean = false
)

@Serializable
private data class PasswordBackupEntry(
    val id: Long = 0,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val website: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val categoryId: Long? = null,
    val categoryName: String? = null,  // 
    val appPackageName: String = "",
    val appName: String = "",
    val email: String = "",
    val phone: String = "",
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val authenticatorKey: String = "",  // 
    val passkeyBindings: String = "",   // 
    val sshKeyData: String = "",
    // 
    val loginType: String = "PASSWORD",  // : PASSWORD 
    val ssoProvider: String = "",        // SSO: GOOGLE, APPLE, FACEBOOK 
    val ssoRefEntryId: Long? = null,     // 
    val customIconType: String = "NONE",
    val customIconValue: String? = null,
    val customIconUpdatedAt: Long = 0L,
    // WIFI 条目扩展元数据（JSON 序列化的 WifiData），仅 loginType=WIFI 时有值
    val wifiMetadata: String = "",
    // 
    val customFields: List<CustomFieldBackupEntry> = emptyList()
)

@Serializable
data class PasswordHistoryBackupEntry(
    val entryId: Long = 0,
    val password: String = "",
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Serializable
private data class NoteBackupEntry(
    val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val itemData: String = "",
    val isFavorite: Boolean = false,
    val imagePaths: String = "",
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val categoryName: String? = null
)

@Serializable
private data class CardWalletBackupEntry(
    val id: Long = 0,
    val itemType: String = "",
    val title: String = "",
    val itemData: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val imagePaths: String = "",
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val categoryName: String? = null
)

@Serializable
private data class PasskeyBackupEntry(
    val credentialId: String = "",
    val rpId: String = "",
    val rpName: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val publicKeyAlgorithm: Int = -7,
    val publicKey: String = "",
    val privateKeyAlias: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 0,
    val iconUrl: String? = null,
    val isDiscoverable: Boolean = true,
    val isUserVerificationRequired: Boolean = true,
    val transports: String = "internal",
    val aaguid: String = "",
    val signCount: Long = 0,
    val notes: String = "",
    val boundPasswordId: Long? = null,
    val passkeyMode: String = PasskeyEntry.MODE_LEGACY,
    val categoryName: String? = null
)

@Serializable
private data class TotpBackupEntry(
    val id: Long = 0,
    val title: String = "",
    val itemData: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val imagePaths: String = "",
    val keepassDatabaseId: Long? = null,
    val keepassGroupPath: String? = null,
    val bitwardenVaultId: Long? = null,
    val bitwardenFolderId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val categoryName: String? = null
)

private data class SteamMaFileBackupEntry(
    val fileName: String,
    val content: String
)

@Serializable
private data class CategoryBackupEntry(
    val id: Long = 0,
    val name: String = "",
    val sortOrder: Int = 0
)

@Serializable
private data class OperationLogBackupEntry(
    val id: Long = 0,
    val itemType: String = "",
    val itemId: Long = 0,
    val itemTitle: String = "",
    val operationType: String = "",
    val changesJson: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val timestamp: Long = 0,
    val isReverted: Boolean = false
)

@Serializable
private data class TrashPasswordBackupEntry(
    val id: Long = 0,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val website: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val email: String = "",
    val phone: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val authenticatorKey: String = "",
    val passkeyBindings: String = "",
    val sshKeyData: String = "",
    val deletedAt: Long? = null,
    // 
    val loginType: String = "PASSWORD",
    val ssoProvider: String = "",
    val ssoRefEntryId: Long? = null,
    val customIconType: String = "NONE",
    val customIconValue: String? = null,
    val customIconUpdatedAt: Long = 0L
)

@Serializable
private data class TrashSecureItemBackupEntry(
    val id: Long = 0,
    val title: String = "",
    val itemType: String = "",
    val itemData: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val imagePaths: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val categoryId: Long? = null
)

/**
 * 
 * 
 */
@Serializable
private data class CommonAccountTemplateBackupEntry(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val content: String = ""
)

@Serializable
private data class CommonAccountBackupEntry(
    val email: String = "",
    val phone: String = "",
    val username: String = "",
    val autoFillEnabled: Boolean = false,
    val billingAddress: String = "",
    val templates: List<CommonAccountTemplateBackupEntry> = emptyList()
)

/**
 * 
 * 
 */
@Serializable
private data class MonicaConfigBackupEntry(
    val serverUrl: String = "",
    val username: String = "",
    val encryptedPassword: String = "",  // 
    val enableEncryption: Boolean = false,
    val encryptedEncryptionPassword: String = "",  // 
    val autoBackupEnabled: Boolean = false,
    val blockedFieldSignatures: List<AutofillBlockedFieldBackupEntry> = emptyList(),
    val saveBlockedTargets: List<String> = emptyList(),
    val autofillBlacklistEnabled: Boolean? = null,
    val autofillBlacklistPackages: List<String>? = null,
    val bitwardenVaults: List<BitwardenVaultBackupEntry> = emptyList(),
)

@Serializable
private data class WebDavConnectionBackupEntry(
    val serverUrl: String = "",
    val username: String = "",
    val encryptedPassword: String = "",
    val enableEncryption: Boolean = false,
    val encryptedEncryptionPassword: String = "",
    val autoBackupEnabled: Boolean = false,
)

@Serializable
private data class AutofillBlockedFieldsBackupEntry(
    val blockedFieldSignatures: List<AutofillBlockedFieldBackupEntry> = emptyList(),
)

@Serializable
private data class AutofillBlockedFieldBackupEntry(
    val signatureKey: String = "",
    val packageName: String? = null,
    val webDomain: String? = null,
    val hints: List<String> = emptyList(),
    val blockedAt: Long = 0L,
)

@Serializable
private data class AutofillSaveBlockedTargetsBackupEntry(
    val blockedTargets: List<String> = emptyList(),
)

@Serializable
private data class AutofillBlacklistBackupEntry(
    val enabled: Boolean = true,
    val packages: List<String> = emptyList(),
)

@Serializable
private data class SecurityQuestionsBackupEntry(
    val question1Id: Int = -1,
    val question1Text: String? = null,
    val answer1Hash: String = "",
    val question2Id: Int = -1,
    val question2Text: String? = null,
    val answer2Hash: String = "",
)

@Serializable
private data class PageAdjustmentPasswordFieldVisibilityBackupEntry(
    val securityVerification: Boolean = true,
    val categoryAndNotes: Boolean = true,
    val appBinding: Boolean = true,
    val personalInfo: Boolean = true,
    val addressInfo: Boolean = true,
    val paymentInfo: Boolean = true,
)

@Serializable
private data class PageAdjustmentSettingsBackupEntry(
    val passwordListQuickFiltersEnabled: Boolean = false,
    val passwordListQuickFilterItems: List<String> = emptyList(),
    val passwordListCategoryQuickFiltersEnabled: Boolean = false,
    val passwordListQuickFoldersEnabled: Boolean = false,
    val passwordListQuickFolderStyle: String = "CLASSIC",
    val passwordListQuickFolderPathBannerEnabled: Boolean = false,
    val passwordListSystemBackToParentFolderEnabled: Boolean = false,
    val addButtonBehaviorMode: String = "DIRECT_PASSWORD",
    val addButtonMenuOrder: List<String> = emptyList(),
    val addButtonMenuEnabledActions: List<String> = emptyList(),
    val passwordPageAggregateEnabled: Boolean = false,
    val passwordPageVisibleContentTypes: List<String> = emptyList(),
    val categorySelectionUiMode: String = "DEFAULT",
    val colorSettingsVersion: Int = 0,
    val oledPureBlackEnabled: Boolean = false,
    val colorScheme: String = "DEFAULT",
    val customPrimaryColor: Long = 0xFF6650A4L,
    val customSecondaryColor: Long = 0xFF625B71L,
    val customTertiaryColor: Long = 0xFF7D5260L,
    val customNeutralColor: Long = 0xFF605D66L,
    val customNeutralVariantColor: Long = 0xFF625B71L,
    val bottomNavSettingsVersion: Int = 0,
    val bottomNavOrder: List<String> = emptyList(),
    val bottomNavVisibilityVaultV2: Boolean = false,
    val bottomNavVisibilityPasswords: Boolean = true,
    val bottomNavVisibilityAuthenticator: Boolean = true,
    val bottomNavVisibilityCardWallet: Boolean = true,
    val bottomNavVisibilityGenerator: Boolean = false,
    val bottomNavVisibilityNotes: Boolean = false,
    val bottomNavVisibilitySend: Boolean = false,
    val bottomNavVisibilityPasskey: Boolean = true,
    val bottomNavVisibilitySteam: Boolean = false,
    val useDraggableBottomNav: Boolean = false,
    val autoHideBottomNavWhenSingleTab: Boolean = false,
    val passwordListQuickAccessEnabled: Boolean = true,
    val passwordListTopModulesOrder: List<String> = emptyList(),
    val passwordCardDisplayMode: String = "SHOW_ALL",
    val passwordCardDisplayFields: List<String> = emptyList(),
    val passwordCardShowAuthenticator: Boolean = false,
    val passwordCardHideOtherContentWhenAuthenticator: Boolean = false,
    val stackCardMode: String = "AUTO",
    val passwordGroupMode: String = "smart",
    val passwordWebsiteStackMatchMode: String = "strict",
    val authenticatorCardDisplayFields: List<String> = emptyList(),
    val authenticatorCardHideCodeByDefault: Boolean = false,
    val authenticatorLayoutMode: String = takagi.ru.monica.data.AuthenticatorLayoutMode.STANDARD.name,
    val vaultV2LayoutMode: String = takagi.ru.monica.data.VaultV2LayoutMode.CLASSIC.name,
    val validatorProgressBarStyle: String = "LINEAR",
    val validatorUnifiedProgressBar: String = "ENABLED",
    val validatorSmoothProgress: Boolean = true,
    val validatorVibrationEnabled: Boolean = true,
    val copyNextCodeWhenExpiring: Boolean = false,
    val securityAnalysisAutoEnabled: Boolean = false,
    val passwordDetailSecurityAnalysisEnabled: Boolean = true,
    val steamMiniProfileBackgroundEnabled: Boolean = false,
    val autofillAuthRequired: Boolean = true,
    val iconCardsEnabled: Boolean = true,
    val appLauncherIcon: String = "MODERN",
    val appLauncherLabel: String = "MONICA_PASS",
    val passwordPageIconEnabled: Boolean = true,
    val authenticatorPageIconEnabled: Boolean = true,
    val passkeyPageIconEnabled: Boolean = true,
    val unmatchedIconHandlingStrategy: String = "DEFAULT_ICON",
    val passwordFieldSettingsVersion: Int = 0,
    val separateUsernameAccountEnabled: Boolean = false,
    val presetCustomFieldsJson: String = "[]",
    val passwordFieldVisibility: PageAdjustmentPasswordFieldVisibilityBackupEntry =
        PageAdjustmentPasswordFieldVisibilityBackupEntry(),
)

@Serializable
private data class BitwardenVaultBackupEntry(
    val id: Long = 0,
    val email: String = "",
    val userId: String? = null,
    val displayName: String? = null,
    val serverUrl: String = "",
    val identityUrl: String = "",
    val apiUrl: String = "",
    val eventsUrl: String? = null,
    val encryptedAccessToken: String? = null,
    val encryptedRefreshToken: String? = null,
    val accessTokenExpiresAt: Long? = null,
    val encryptedMasterKey: String? = null,
    val encryptedEncKey: String? = null,
    val encryptedMacKey: String? = null,
    val kdfType: Int = 0,
    val kdfIterations: Int = 0,
    val kdfMemory: Int? = null,
    val kdfParallelism: Int? = null,
    val lastSyncAt: Long? = null,
    val lastFullSyncAt: Long? = null,
    val revisionDate: String? = null,
    val isDefault: Boolean = false,
    val isConnected: Boolean = false,
    val syncEnabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
private data class BitwardenVaultsBackupEntry(
    val vaults: List<BitwardenVaultBackupEntry> = emptyList(),
)

/**
 * 
 * 
 */
@Serializable
private data class KeePassDatabaseBackupEntry(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val originalStorageLocation: String = "INTERNAL",  // INTERNAL 
    val originalFilePath: String = "",  // 
    val isDefault: Boolean = false,
    val lastSyncTime: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val keyFileName: String? = null,
    val keyFileFingerprint: String? = null,
    val keyFileEntryName: String? = null,
)

private class KeePassKeyFileBackupEncryptionRequiredException(message: String) : Exception(message)
private class KeePassKeyFileBackupUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object KeePassBackupEntryPolicy {
    enum class Kind {
        METADATA,
        KEY_FILE,
        DATABASE,
    }

    data class Entry(
        val databaseId: Long,
        val kind: Kind,
    )

    data class RestoreSetValidation(
        val canRestore: Boolean,
        val useKeyFile: Boolean = false,
        val warning: String? = null,
    )

    private val metadataName = Regex("^keepass_(\\d+)_meta\\.json$", RegexOption.IGNORE_CASE)
    private val keyFileName = Regex("^keepass_(\\d+)\\.key$", RegexOption.IGNORE_CASE)
    private val databaseName = Regex("^keepass_(\\d+)\\.kdbx$", RegexOption.IGNORE_CASE)

    fun parse(path: String): Entry? {
        val normalized = path.replace('\\', '/').trim('/')
        val segments = normalized.split('/')
        val keepassIndex = segments.indexOfLast { it.equals("keepass", ignoreCase = true) }
        if (keepassIndex < 0 || keepassIndex != segments.lastIndex - 1) return null
        val name = segments.last()

        fun match(regex: Regex, kind: Kind): Entry? {
            val id = regex.matchEntire(name)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return null
            return Entry(databaseId = id, kind = kind)
        }

        return match(metadataName, Kind.METADATA)
            ?: match(keyFileName, Kind.KEY_FILE)
            ?: match(databaseName, Kind.DATABASE)
    }

    fun requiresEncryptedBackup(
        hasInternalKeyFileCopy: Boolean,
        backupEncrypted: Boolean,
    ): Boolean = hasInternalKeyFileCopy && !backupEncrypted

    fun validateRestoreSet(
        databaseId: Long,
        hasMetadata: Boolean,
        hasDatabase: Boolean,
        declaredKeyEntryName: String?,
        hasKeyFile: Boolean,
        backupEncrypted: Boolean,
    ): RestoreSetValidation {
        if (!hasMetadata) {
            return RestoreSetValidation(false, warning = "KeePass数据库元信息缺失")
        }
        if (!hasDatabase) {
            return RestoreSetValidation(false, warning = "KeePass数据库文件缺失")
        }

        val declaredKey = declaredKeyEntryName
            ?.takeIf { it.isNotBlank() }
            ?.let(::parse)
        if (!declaredKeyEntryName.isNullOrBlank() &&
            (declaredKey == null ||
                declaredKey.kind != Kind.KEY_FILE ||
                declaredKey.databaseId != databaseId)
        ) {
            return RestoreSetValidation(false, warning = "KeePass密钥文件声明无效")
        }

        if (declaredKey != null) {
            if (!backupEncrypted) {
                return RestoreSetValidation(false, warning = "未加密备份不能恢复 KeePass 密钥文件")
            }
            if (!hasKeyFile) {
                return RestoreSetValidation(false, warning = "备份中的 KeePass 密钥文件缺失")
            }
            return RestoreSetValidation(true, useKeyFile = true)
        }

        return RestoreSetValidation(
            canRestore = true,
            useKeyFile = false,
            warning = if (hasKeyFile) "已忽略未在元信息中声明的 KeePass 密钥文件" else null,
        )
    }
}

/**
 * WebDAV 
 * 
 */
class WebDavHelper(
    private val context: Context
) {
    private var sardine: Sardine? = null
    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private val securityManager = SecurityManager(context)
    
    // 
    private val backupLock = java.util.concurrent.atomic.AtomicBoolean(false)
    
    /**
     * 
     */
    fun isBackupInProgress(): Boolean = backupLock.get()
    
    companion object {
        private const val PREFS_NAME = "webdav_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ENABLE_ENCRYPTION = "enable_encryption"
        private const val KEY_ENCRYPTION_PASSWORD = "encryption_password"
        private const val LEGACY_WEBDAV_BACKUP_FALLBACK_KEY = "Monica_WebDAV_Config_Key"
        private const val SECURE_KEY_SERVER_URL = "webdav_secure_server_url"
        private const val SECURE_KEY_USERNAME = "webdav_secure_username"
        private const val SECURE_KEY_PASSWORD = "webdav_secure_password"
        private const val SECURE_KEY_ENCRYPTION_PASSWORD = "webdav_secure_encryption_password"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val PASSWORD_META_MARKER = "[MonicaMeta]"
        private const val PERMANENT_SUFFIX = "_permanent"        
        // Backup preferences keys
        private const val KEY_BACKUP_INCLUDE_PASSWORDS = "backup_include_passwords"
        private const val KEY_BACKUP_INCLUDE_AUTHENTICATORS = "backup_include_authenticators"
        private const val KEY_BACKUP_INCLUDE_DOCUMENTS = "backup_include_documents"
        private const val KEY_BACKUP_INCLUDE_BANK_CARDS = "backup_include_bank_cards"
        private const val KEY_BACKUP_INCLUDE_PASSKEYS = "backup_include_passkeys"
        private const val KEY_BACKUP_INCLUDE_GENERATOR_HISTORY = "backup_include_generator_history"
        private const val KEY_BACKUP_INCLUDE_IMAGES = "backup_include_images"
        private const val KEY_BACKUP_INCLUDE_NOTES = "backup_include_notes"
        private const val KEY_BACKUP_INCLUDE_TIMELINE = "backup_include_timeline"
        private const val KEY_BACKUP_INCLUDE_TRASH = "backup_include_trash"
        private const val KEY_BACKUP_INCLUDE_TRASH_AND_HISTORY = "backup_include_trash_and_history"
        private const val KEY_BACKUP_INCLUDE_WEBDAV_CONFIG = "backup_include_webdav_config"
        private const val KEY_BACKUP_INCLUDE_LOCAL_KEEPASS = "backup_include_local_keepass"
        private const val BACKUP_FOLDER_NAME = "Monica_Backups"
        private const val STEAM_MAFILE_BACKUP_DIR = "steam/mafiles"
        private const val STEAM_MAFILE_BACKUP_TYPE = "Steam maFile"
    }
    
    // 
    private var enableEncryption: Boolean = false
    private var encryptionPassword: String = ""
    
    init {
        // 
        loadConfig()
    }

    private fun sanitizeSecureExportItemForMonicaRestore(
        item: DataExportImportManager.ExportItem,
        sourceFileName: String? = null
    ): DataExportImportManager.ExportItem {
        val normalizedItemType = SecureItemRestoreTypeResolver.resolve(
            rawType = item.itemType,
            itemData = item.itemData,
            sourceFileName = sourceFileName
        )?.name ?: item.itemType.trim()
        return item.copy(
            itemType = normalizedItemType,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            bitwardenVaultId = null,
            bitwardenFolderId = null
        )
    }

    private fun PageAdjustmentSettingsSnapshot.toBackupEntry(): PageAdjustmentSettingsBackupEntry {
        return PageAdjustmentSettingsBackupEntry(
            passwordListQuickFiltersEnabled = passwordListQuickFiltersEnabled,
            passwordListQuickFilterItems = passwordListQuickFilterItems,
            passwordListCategoryQuickFiltersEnabled = passwordListCategoryQuickFiltersEnabled,
            passwordListQuickFoldersEnabled = passwordListQuickFoldersEnabled,
            passwordListQuickFolderStyle = passwordListQuickFolderStyle,
            passwordListQuickFolderPathBannerEnabled = passwordListQuickFolderPathBannerEnabled,
            passwordListSystemBackToParentFolderEnabled = passwordListSystemBackToParentFolderEnabled,
            addButtonBehaviorMode = addButtonBehaviorMode,
            addButtonMenuOrder = addButtonMenuOrder,
            addButtonMenuEnabledActions = addButtonMenuEnabledActions,
            passwordPageAggregateEnabled = passwordPageAggregateEnabled,
            passwordPageVisibleContentTypes = passwordPageVisibleContentTypes,
            categorySelectionUiMode = categorySelectionUiMode,
            colorSettingsVersion = colorSettingsVersion,
            oledPureBlackEnabled = oledPureBlackEnabled,
            colorScheme = colorScheme,
            customPrimaryColor = customPrimaryColor,
            customSecondaryColor = customSecondaryColor,
            customTertiaryColor = customTertiaryColor,
            customNeutralColor = customNeutralColor,
            customNeutralVariantColor = customNeutralVariantColor,
            bottomNavSettingsVersion = bottomNavSettingsVersion,
            bottomNavOrder = bottomNavOrder,
            bottomNavVisibilityVaultV2 = bottomNavVisibilityVaultV2,
            bottomNavVisibilityPasswords = bottomNavVisibilityPasswords,
            bottomNavVisibilityAuthenticator = bottomNavVisibilityAuthenticator,
            bottomNavVisibilityCardWallet = bottomNavVisibilityCardWallet,
            bottomNavVisibilityGenerator = bottomNavVisibilityGenerator,
            bottomNavVisibilityNotes = bottomNavVisibilityNotes,
            bottomNavVisibilitySend = bottomNavVisibilitySend,
            bottomNavVisibilityPasskey = bottomNavVisibilityPasskey,
            bottomNavVisibilitySteam = bottomNavVisibilitySteam,
            useDraggableBottomNav = useDraggableBottomNav,
            autoHideBottomNavWhenSingleTab = autoHideBottomNavWhenSingleTab,
            passwordListQuickAccessEnabled = passwordListQuickAccessEnabled,
            passwordListTopModulesOrder = passwordListTopModulesOrder,
            passwordCardDisplayMode = passwordCardDisplayMode,
            passwordCardDisplayFields = passwordCardDisplayFields,
            passwordCardShowAuthenticator = passwordCardShowAuthenticator,
            passwordCardHideOtherContentWhenAuthenticator = passwordCardHideOtherContentWhenAuthenticator,
            stackCardMode = stackCardMode,
            passwordGroupMode = passwordGroupMode,
            passwordWebsiteStackMatchMode = passwordWebsiteStackMatchMode,
            authenticatorCardDisplayFields = authenticatorCardDisplayFields,
            authenticatorCardHideCodeByDefault = authenticatorCardHideCodeByDefault,
            authenticatorLayoutMode = authenticatorLayoutMode,
            vaultV2LayoutMode = vaultV2LayoutMode,
            validatorProgressBarStyle = validatorProgressBarStyle,
            validatorUnifiedProgressBar = validatorUnifiedProgressBar,
            validatorSmoothProgress = validatorSmoothProgress,
            validatorVibrationEnabled = validatorVibrationEnabled,
            copyNextCodeWhenExpiring = copyNextCodeWhenExpiring,
            securityAnalysisAutoEnabled = securityAnalysisAutoEnabled,
            passwordDetailSecurityAnalysisEnabled = passwordDetailSecurityAnalysisEnabled,
            steamMiniProfileBackgroundEnabled = steamMiniProfileBackgroundEnabled,
            autofillAuthRequired = autofillAuthRequired,
            iconCardsEnabled = iconCardsEnabled,
            appLauncherIcon = appLauncherIcon,
            appLauncherLabel = appLauncherLabel,
            passwordPageIconEnabled = passwordPageIconEnabled,
            authenticatorPageIconEnabled = authenticatorPageIconEnabled,
            passkeyPageIconEnabled = passkeyPageIconEnabled,
            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
            passwordFieldSettingsVersion = passwordFieldSettingsVersion,
            separateUsernameAccountEnabled = separateUsernameAccountEnabled,
            presetCustomFieldsJson = presetCustomFieldsJson,
            passwordFieldVisibility = PageAdjustmentPasswordFieldVisibilityBackupEntry(
                securityVerification = passwordFieldVisibility.securityVerification,
                categoryAndNotes = passwordFieldVisibility.categoryAndNotes,
                appBinding = passwordFieldVisibility.appBinding,
                personalInfo = passwordFieldVisibility.personalInfo,
                addressInfo = passwordFieldVisibility.addressInfo,
                paymentInfo = passwordFieldVisibility.paymentInfo,
            ),
        )
    }

    private suspend fun writePortableAppSettingsBackup(
        zipOut: ZipOutputStream,
        cacheBackupDir: File,
        backupEncryptPassword: String?,
        warnings: MutableList<String>,
    ) {
        try {
            val json = Json { prettyPrint = false }
            val monicaConfigDir = File(cacheBackupDir, "monica_config").apply { mkdirs() }
            val pageAdjustmentSettingsFile = File(monicaConfigDir, "page_adjustment_settings.json")
            pageAdjustmentSettingsFile.writeText(
                json.encodeToString(
                    PageAdjustmentSettingsBackupEntry.serializer(),
                    SettingsManager(context).exportPageAdjustmentSettings().toBackupEntry(),
                ),
                Charsets.UTF_8,
            )
            addFileToZip(
                zipOut,
                pageAdjustmentSettingsFile,
                "monica_config/${pageAdjustmentSettingsFile.name}",
            )
            pageAdjustmentSettingsFile.delete()

            val securityQuestionsSnapshot = securityManager.exportSecurityQuestionsForBackup()
            if (securityQuestionsSnapshot != null && backupEncryptPassword == null) {
                throw SecurityQuestionsBackupEncryptionRequiredException(
                    "密保问题包含可验证答案摘要，仅会写入已加密的备份"
                )
            }
            securityQuestionsSnapshot?.let { snapshot ->
                val securityQuestionsFile = File(monicaConfigDir, "security_questions.json")
                securityQuestionsFile.writeText(
                    json.encodeToString(
                        SecurityQuestionsBackupEntry.serializer(),
                        snapshot.toBackupEntry(),
                    ),
                    Charsets.UTF_8,
                )
                addFileToZip(
                    zipOut,
                    securityQuestionsFile,
                    "monica_config/${securityQuestionsFile.name}",
                )
                securityQuestionsFile.delete()
            }
            android.util.Log.d(
                "WebDavHelper",
                "Backup portable app settings (pageAdjustmentSettings=true, securityQuestions=${securityQuestionsSnapshot != null})",
            )
        } catch (e: SecurityQuestionsBackupEncryptionRequiredException) {
            android.util.Log.w("WebDavHelper", e.message.orEmpty())
            warnings.add(e.message.orEmpty())
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to backup portable app settings: ${e.message}")
            warnings.add("Monica应用设置备份失败: ${e.message}")
        }
    }

    private fun SecurityQuestionsBackupSnapshot.toBackupEntry(): SecurityQuestionsBackupEntry {
        return SecurityQuestionsBackupEntry(
            question1Id = question1Id,
            question1Text = question1Text,
            answer1Hash = answer1Hash,
            question2Id = question2Id,
            question2Text = question2Text,
            answer2Hash = answer2Hash,
        )
    }

    private suspend fun writeConnectionConfigBackup(
        zipOut: ZipOutputStream,
        cacheBackupDir: File,
        backupEncryptPassword: String?,
        warnings: MutableList<String>,
    ) {
        try {
            val autofillPreferences = AutofillPreferences(context)
            val blockedFieldSignatures = autofillPreferences.blockedFieldSignatureRecords.first()
                .map { record ->
                    AutofillBlockedFieldBackupEntry(
                        signatureKey = record.signatureKey,
                        packageName = record.packageName,
                        webDomain = record.webDomain,
                        hints = record.hints,
                        blockedAt = record.blockedAt,
                    )
                }
            val saveBlockedTargets = autofillPreferences.saveBlockedTargetRecords.first()
                .map { it.key }
                .distinct()
            val autofillBlacklistEnabled = autofillPreferences.isBlacklistEnabled.first()
            val autofillBlacklistPackages = autofillPreferences.blacklistPackages.first()
                .mapNotNull { it.trim().takeIf { packageName -> packageName.isNotBlank() } }
                .distinct()
                .sorted()
            val bitwardenVaults = PasswordDatabase.getDatabase(context)
                .bitwardenVaultDao()
                .getAllVaults()

            if (backupEncryptPassword == null) {
                warnings.add("未启用备份加密，已跳过 WebDAV 连接凭证和 Bitwarden Vault 密钥材料")
            }

            val encryptedWebDavPassword = backupEncryptPassword?.let {
                EncryptionHelper.encryptString(password, it)
            } ?: ""
            val encryptedEncPassword = if (
                backupEncryptPassword != null &&
                enableEncryption &&
                encryptionPassword.isNotEmpty()
            ) {
                EncryptionHelper.encryptString(encryptionPassword, backupEncryptPassword)
            } else {
                ""
            }
            val bitwardenVaultBackups = bitwardenVaults.map { vault ->
                BitwardenVaultBackupEntry(
                    id = vault.id,
                    email = vault.email,
                    userId = vault.userId,
                    displayName = vault.displayName,
                    serverUrl = vault.serverUrl,
                    identityUrl = vault.identityUrl,
                    apiUrl = vault.apiUrl,
                    eventsUrl = vault.eventsUrl,
                    encryptedAccessToken = encryptSensitiveBackupValue(vault.encryptedAccessToken, backupEncryptPassword),
                    encryptedRefreshToken = encryptSensitiveBackupValue(vault.encryptedRefreshToken, backupEncryptPassword),
                    accessTokenExpiresAt = vault.accessTokenExpiresAt,
                    encryptedMasterKey = encryptSensitiveBackupValue(vault.encryptedMasterKey, backupEncryptPassword),
                    encryptedEncKey = encryptSensitiveBackupValue(vault.encryptedEncKey, backupEncryptPassword),
                    encryptedMacKey = encryptSensitiveBackupValue(vault.encryptedMacKey, backupEncryptPassword),
                    kdfType = vault.kdfType,
                    kdfIterations = vault.kdfIterations,
                    kdfMemory = vault.kdfMemory,
                    kdfParallelism = vault.kdfParallelism,
                    lastSyncAt = vault.lastSyncAt,
                    lastFullSyncAt = vault.lastFullSyncAt,
                    revisionDate = vault.revisionDate,
                    isDefault = vault.isDefault,
                    isConnected = vault.isConnected,
                    syncEnabled = vault.syncEnabled,
                    createdAt = vault.createdAt,
                    updatedAt = vault.updatedAt,
                )
            }

            val json = Json { prettyPrint = false }
            val monicaConfigDir = File(cacheBackupDir, "monica_config").apply { mkdirs() }
            val webDavConnectionFile = File(monicaConfigDir, "webdav_connection.json")
            webDavConnectionFile.writeText(
                json.encodeToString(
                    WebDavConnectionBackupEntry.serializer(),
                    WebDavConnectionBackupEntry(
                        serverUrl = serverUrl,
                        username = username,
                        encryptedPassword = encryptedWebDavPassword,
                        enableEncryption = enableEncryption,
                        encryptedEncryptionPassword = encryptedEncPassword,
                        autoBackupEnabled = isAutoBackupEnabled(),
                    ),
                ),
                Charsets.UTF_8,
            )
            addFileToZip(
                zipOut,
                webDavConnectionFile,
                "monica_config/${webDavConnectionFile.name}",
            )
            webDavConnectionFile.delete()

            val autofillBlockedFieldsFile = File(monicaConfigDir, "autofill_blocked_fields.json")
            autofillBlockedFieldsFile.writeText(
                json.encodeToString(
                    AutofillBlockedFieldsBackupEntry.serializer(),
                    AutofillBlockedFieldsBackupEntry(blockedFieldSignatures),
                ),
                Charsets.UTF_8,
            )
            addFileToZip(
                zipOut,
                autofillBlockedFieldsFile,
                "monica_config/${autofillBlockedFieldsFile.name}",
            )
            autofillBlockedFieldsFile.delete()

            val autofillSaveBlockedTargetsFile = File(monicaConfigDir, "autofill_save_blocked_targets.json")
            autofillSaveBlockedTargetsFile.writeText(
                json.encodeToString(
                    AutofillSaveBlockedTargetsBackupEntry.serializer(),
                    AutofillSaveBlockedTargetsBackupEntry(saveBlockedTargets),
                ),
                Charsets.UTF_8,
            )
            addFileToZip(
                zipOut,
                autofillSaveBlockedTargetsFile,
                "monica_config/${autofillSaveBlockedTargetsFile.name}",
            )
            autofillSaveBlockedTargetsFile.delete()

            val autofillBlacklistFile = File(monicaConfigDir, "autofill_blacklist.json")
            autofillBlacklistFile.writeText(
                json.encodeToString(
                    AutofillBlacklistBackupEntry.serializer(),
                    AutofillBlacklistBackupEntry(
                        enabled = autofillBlacklistEnabled,
                        packages = autofillBlacklistPackages,
                    ),
                ),
                Charsets.UTF_8,
            )
            addFileToZip(
                zipOut,
                autofillBlacklistFile,
                "monica_config/${autofillBlacklistFile.name}",
            )
            autofillBlacklistFile.delete()
            if (bitwardenVaultBackups.isNotEmpty()) {
                val bitwardenVaultsFile = File(monicaConfigDir, "bitwarden_vaults.json")
                bitwardenVaultsFile.writeText(
                    json.encodeToString(
                        BitwardenVaultsBackupEntry.serializer(),
                        BitwardenVaultsBackupEntry(bitwardenVaultBackups),
                    ),
                    Charsets.UTF_8,
                )
                addFileToZip(
                    zipOut,
                    bitwardenVaultsFile,
                    "monica_config/${bitwardenVaultsFile.name}",
                )
                bitwardenVaultsFile.delete()
            }
            android.util.Log.d(
                "WebDavHelper",
                "Backup connection config (server: $serverUrl, blockedFields=${blockedFieldSignatures.size}, saveBlockedTargets=${saveBlockedTargets.size}, blacklistPackages=${autofillBlacklistPackages.size}, bitwardenVaults=${bitwardenVaultBackups.size})",
            )
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to backup connection config: ${e.message}")
            warnings.add("Monica连接配置备份失败: ${e.message}")
        }
    }

    private fun isLocalSecureItem(item: SecureItem): Boolean {
        return item.isLocalOnlyItem()
    }

    private fun restorePasswordAsMonicaLocal(backup: PasswordBackupEntry): PasswordEntry {
        val normalizedIconValue = normalizeBackupIconValue(backup.customIconType, backup.customIconValue)
        return PasswordEntry(
            id = backup.id,
            title = backup.title,
            username = backup.username,
            password = backup.password,
            website = backup.website,
            notes = backup.notes,
            isFavorite = backup.isFavorite,
            appPackageName = backup.appPackageName,
            appName = backup.appName,
            categoryId = null,
            email = backup.email,
            phone = backup.phone,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            keepassEntryUuid = null,
            keepassGroupUuid = null,
            bitwardenVaultId = null,
            bitwardenCipherId = null,
            bitwardenFolderId = null,
            bitwardenRevisionDate = null,
            bitwardenLocalModified = false,
            createdAt = Date(backup.createdAt),
            updatedAt = Date(backup.updatedAt),
            authenticatorKey = backup.authenticatorKey,
            passkeyBindings = backup.passkeyBindings,
            sshKeyData = backup.sshKeyData,
            loginType = backup.loginType,
            ssoProvider = backup.ssoProvider,
            ssoRefEntryId = backup.ssoRefEntryId,
            customIconType = backup.customIconType,
            customIconValue = normalizedIconValue,
            customIconUpdatedAt = backup.customIconUpdatedAt,
            wifiMetadata = backup.wifiMetadata
        )
    }

    private fun restoreSecureItemAsMonicaLocal(
        itemType: ItemType,
        title: String,
        itemData: String,
        notes: String,
        isFavorite: Boolean,
        imagePaths: String,
        createdAt: Long,
        updatedAt: Long
    ): DataExportImportManager.ExportItem {
        return DataExportImportManager.ExportItem(
            id = 0,
            itemType = itemType.name,
            title = title,
            itemData = itemData,
            notes = notes,
            isFavorite = isFavorite,
            imagePaths = imagePaths,
            createdAt = createdAt,
            updatedAt = updatedAt,
            categoryId = null,
            keepassDatabaseId = null,
            keepassGroupPath = null,
            bitwardenVaultId = null,
            bitwardenFolderId = null
        )
    }
    
    /**
     * 
     */
    fun configure(url: String, user: String, pass: String) {
        serverUrl = normalizeServerUrl(url)
        username = user.trim()
        password = pass
        sardine = createSardineClient()
        android.util.Log.d("WebDavHelper", "Configured WebDAV credentials")
        // 
        saveConfig()
    }

    private fun applyCredentialsIfPresent() {
        // 凭据已由 PreemptiveBasicAuthInterceptor 预置到每个请求，
        // 这里保留空实现以兼容历史调用点。
    }
    
    /**
     * 
     */
    private fun saveConfig() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_ENABLE_ENCRYPTION, enableEncryption)
            remove(KEY_SERVER_URL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(KEY_ENCRYPTION_PASSWORD)
            apply()
        }
        securityManager.putProtectedString(SECURE_KEY_SERVER_URL, serverUrl.ifBlank { null })
        securityManager.putProtectedString(SECURE_KEY_USERNAME, username.ifBlank { null })
        securityManager.putProtectedString(SECURE_KEY_PASSWORD, password.ifBlank { null })
        securityManager.putProtectedString(
            SECURE_KEY_ENCRYPTION_PASSWORD,
            encryptionPassword.ifBlank { null }
        )
    }
    
    /**
     * 
     */
    private fun loadConfig() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyConfigIfNeeded(prefs)
        val storedUrl = securityManager.getProtectedString(SECURE_KEY_SERVER_URL).orEmpty()
        val url = normalizeServerUrl(storedUrl)
        val user = securityManager.getProtectedString(SECURE_KEY_USERNAME).orEmpty()
        val pass = securityManager.getProtectedString(SECURE_KEY_PASSWORD).orEmpty()
        enableEncryption = prefs.getBoolean(KEY_ENABLE_ENCRYPTION, false)
        encryptionPassword = securityManager.getProtectedString(SECURE_KEY_ENCRYPTION_PASSWORD).orEmpty()
        
        if (url.isNotEmpty()) {
            serverUrl = url
            username = user.trim()
            password = pass
            sardine = createSardineClient()
            android.util.Log.d("WebDavHelper", "Loaded WebDAV config: encryption=$enableEncryption")
            if (url != storedUrl) {
                saveConfig()
            }
        }
    }
    
    /**
     * 
     */
    fun isConfigured(): Boolean {
        return serverUrl.isNotEmpty()
    }
    
    /**
     * 
     */
    data class WebDavConfig(
        val serverUrl: String,
        val username: String
    )
    
    fun getCurrentConfig(): WebDavConfig? {
        return if (isConfigured()) {
            WebDavConfig(serverUrl, username)
        } else {
            null
        }
    }

    /**
     * 
     * 
     */
    fun getCurrentPasswordForEdit(): String {
        return if (isConfigured()) password else ""
    }
    
    /**
     * 
     */
    fun clearConfig() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ENABLE_ENCRYPTION)
            .remove(KEY_AUTO_BACKUP_ENABLED)
            .remove(KEY_LAST_BACKUP_TIME)
            .remove(KEY_BACKUP_INCLUDE_PASSWORDS)
            .remove(KEY_BACKUP_INCLUDE_AUTHENTICATORS)
            .remove(KEY_BACKUP_INCLUDE_DOCUMENTS)
            .remove(KEY_BACKUP_INCLUDE_BANK_CARDS)
            .remove(KEY_BACKUP_INCLUDE_PASSKEYS)
            .remove(KEY_BACKUP_INCLUDE_GENERATOR_HISTORY)
            .remove(KEY_BACKUP_INCLUDE_IMAGES)
            .remove(KEY_BACKUP_INCLUDE_NOTES)
            .remove(KEY_BACKUP_INCLUDE_TIMELINE)
            .remove(KEY_BACKUP_INCLUDE_TRASH)
            .remove(KEY_BACKUP_INCLUDE_TRASH_AND_HISTORY)
            .remove(KEY_BACKUP_INCLUDE_WEBDAV_CONFIG)
            .remove(KEY_BACKUP_INCLUDE_LOCAL_KEEPASS)
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_ENCRYPTION_PASSWORD)
            .apply()
        securityManager.removeProtectedString(SECURE_KEY_SERVER_URL)
        securityManager.removeProtectedString(SECURE_KEY_USERNAME)
        securityManager.removeProtectedString(SECURE_KEY_PASSWORD)
        securityManager.removeProtectedString(SECURE_KEY_ENCRYPTION_PASSWORD)
        // 连接配置被清空后，放宽过的证书校验档位不应残留给下一台服务器。
        takagi.ru.monica.webdav.WebDavTlsSettings.reset(context)
        serverUrl = ""
        username = ""
        password = ""
        enableEncryption = false
        encryptionPassword = ""
        sardine = null
    }
    
    /**
     * 
     * @param enable 
     * @param encPassword 
     */
    fun configureEncryption(enable: Boolean, encPassword: String = "") {
        enableEncryption = enable
        encryptionPassword = if (enable) encPassword else ""
        saveConfig()
        android.util.Log.d("WebDavHelper", "Encryption configured: enabled=$enable")
    }
    
    /**
     * 
     */
    fun isEncryptionEnabled(): Boolean = enableEncryption
    
    /**
     * 
     */
    fun hasEncryptionPassword(): Boolean = enableEncryption && encryptionPassword.isNotEmpty()
    
    /**
     * 
     * @param enable 
     */
    fun configureAutoBackup(enable: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enable).apply()
        android.util.Log.d("WebDavHelper", "Auto backup configured: enabled=$enable")
    }
    
    /**
     * 
     */
    fun isAutoBackupEnabled(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
    }
    
    /**
     * 
     */
    fun shouldAutoBackup(): Boolean {
        if (!isAutoBackupEnabled()) {
            return false
        }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastBackupTime = prefs.getLong(KEY_LAST_BACKUP_TIME, 0)
        
        // 
        if (lastBackupTime == 0L) {
            android.util.Log.d("WebDavHelper", "Never backed up before, need backup")
            return true
        }
        
        val currentTime = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        
        // 
        calendar.timeInMillis = lastBackupTime
        val lastBackupDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val lastBackupYear = calendar.get(java.util.Calendar.YEAR)
        
        // 
        calendar.timeInMillis = currentTime
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)
        val currentYear = calendar.get(java.util.Calendar.YEAR)
        
        // 
        val hoursSinceLastBackup = (currentTime - lastBackupTime) / (1000 * 60 * 60)
        
        // 
        val isNewDay = (currentYear > lastBackupYear) || 
                      (currentYear == lastBackupYear && currentDay > lastBackupDay)
        
        android.util.Log.d("WebDavHelper", 
            "Last backup: year=$lastBackupYear, day=$lastBackupDay, " +
            "Current: year=$currentYear, day=$currentDay, " +
            "Hours since: $hoursSinceLastBackup, " +
            "Is new day: $isNewDay")
        
        // 1: 
        if (isNewDay) {
            android.util.Log.d("WebDavHelper", "New day detected, need backup")
            return true
        }
        
        // 2: 
        if (hoursSinceLastBackup >= 12) {
            android.util.Log.d("WebDavHelper", "More than 12 hours since last backup, need backup")
            return true
        }
        
        android.util.Log.d("WebDavHelper", "No backup needed")
        return false
    }
    
    /**
     * 
     */
    fun updateLastBackupTime() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_BACKUP_TIME, currentTime).apply()
        android.util.Log.d("WebDavHelper", "Updated last backup time: $currentTime")
    }
    
    /**
     * 
     */
    fun getLastBackupTime(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_BACKUP_TIME, 0)
    }
    
    /**
     * 
     */
    fun saveBackupPreferences(preferences: BackupPreferences) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_BACKUP_INCLUDE_PASSWORDS, preferences.includePasswords)
            putBoolean(KEY_BACKUP_INCLUDE_AUTHENTICATORS, preferences.includeAuthenticators)
            putBoolean(KEY_BACKUP_INCLUDE_DOCUMENTS, preferences.includeDocuments)
            putBoolean(KEY_BACKUP_INCLUDE_BANK_CARDS, preferences.includeBankCards)
            putBoolean(KEY_BACKUP_INCLUDE_PASSKEYS, preferences.includePasskeys)
            putBoolean(KEY_BACKUP_INCLUDE_GENERATOR_HISTORY, preferences.includeGeneratorHistory)
            putBoolean(KEY_BACKUP_INCLUDE_IMAGES, preferences.includeImages)
            putBoolean(KEY_BACKUP_INCLUDE_NOTES, preferences.includeNotes)
            putBoolean(KEY_BACKUP_INCLUDE_TIMELINE, preferences.includeTimeline)
            putBoolean(KEY_BACKUP_INCLUDE_TRASH, preferences.includeTrash)
            putBoolean(KEY_BACKUP_INCLUDE_TRASH_AND_HISTORY, preferences.includeTrashAndHistory)
            putBoolean(KEY_BACKUP_INCLUDE_WEBDAV_CONFIG, preferences.includeWebDavConfig)
            putBoolean(KEY_BACKUP_INCLUDE_LOCAL_KEEPASS, preferences.includeLocalKeePass)
            apply()
        }
        android.util.Log.d("WebDavHelper", "Saved backup preferences: $preferences")
    }
    
    /**
     * 
     * 
     */
    fun getBackupPreferences(): BackupPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return BackupPreferences(
            includePasswords = prefs.getBoolean(KEY_BACKUP_INCLUDE_PASSWORDS, true),
            includeAuthenticators = prefs.getBoolean(KEY_BACKUP_INCLUDE_AUTHENTICATORS, true),
            includeDocuments = prefs.getBoolean(KEY_BACKUP_INCLUDE_DOCUMENTS, true),
            includeBankCards = prefs.getBoolean(KEY_BACKUP_INCLUDE_BANK_CARDS, true),
            includePasskeys = prefs.getBoolean(KEY_BACKUP_INCLUDE_PASSKEYS, true),
            includeGeneratorHistory = prefs.getBoolean(KEY_BACKUP_INCLUDE_GENERATOR_HISTORY, true),
            includeImages = prefs.getBoolean(KEY_BACKUP_INCLUDE_IMAGES, true),
            includeNotes = prefs.getBoolean(KEY_BACKUP_INCLUDE_NOTES, true),
            includeTimeline = prefs.getBoolean(KEY_BACKUP_INCLUDE_TIMELINE, true),
            includeTrash = prefs.getBoolean(KEY_BACKUP_INCLUDE_TRASH, true),
            includeTrashAndHistory = prefs.getBoolean(KEY_BACKUP_INCLUDE_TRASH_AND_HISTORY, true),
            includeWebDavConfig = prefs.getBoolean(KEY_BACKUP_INCLUDE_WEBDAV_CONFIG, false),
            includeLocalKeePass = prefs.getBoolean(KEY_BACKUP_INCLUDE_LOCAL_KEEPASS, false)
        )
    }
    
    /**
     * 
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val client = sardine ?: return@withContext Result.failure(Exception("WebDAV not configured"))

            checkNetworkAndTimeSync(context)

            val candidateUrls = buildConnectionCandidates(serverUrl)
            val normalizedServer = takagi.ru.monica.webdav.WebDavUrlBuilder.normalizeServer(serverUrl)
            android.util.Log.d("WebDavHelper", "Testing connection to: ${candidateUrls.joinToString(", ")}")

            var resolvedUrl: String? = null
            var lastClassified: takagi.ru.monica.webdav.WebDavErrorClassifier.ClassifiedError? = null
            var lastCandidateTried: String = normalizedServer

            withTimeout(20_000L) {
                for (candidateUrl in candidateUrls) {
                    lastCandidateTried = candidateUrl
                    try {
                        // 单次 PROPFIND（sardine.list 默认 Depth: 1）即可确认可达性与鉴权。
                        client.list(candidateUrl)
                        resolvedUrl = candidateUrl
                        val host = takagi.ru.monica.webdav.WebDavGateway.hostOf(candidateUrl)
                        if (host.isNotEmpty()) {
                            takagi.ru.monica.webdav.WebDavBackoffState.recordSuccess(host)
                        }
                        break
                    } catch (e: Exception) {
                        val classified = takagi.ru.monica.webdav.WebDavErrorClassifier.classify(e)
                        android.util.Log.w(
                            "WebDavHelper",
                            "Probe $candidateUrl failed: kind=${classified.kind}, msg=${e.message}"
                        )
                        lastClassified = classified
                        // 终止条件：被速率限制、鉴权失败、方法不被支持时立即 fail-fast
                        if (classified.kind == takagi.ru.monica.webdav.WebDavErrorKind.RateLimited ||
                            classified.kind == takagi.ru.monica.webdav.WebDavErrorKind.AuthFailed ||
                            classified.kind == takagi.ru.monica.webdav.WebDavErrorKind.MethodNotAllowed
                        ) {
                            break
                        }
                        // 其他分类则继续尝试下一个候选
                    }
                }
            }

            val resolved = resolvedUrl
            if (resolved == null) {
                val message = buildConnectionErrorMessage(lastClassified, lastCandidateTried)
                return@withContext Result.failure(Exception(message, lastClassified?.cause))
            }
            if (resolved != serverUrl) {
                serverUrl = resolved
                saveConfig()
                android.util.Log.d("WebDavHelper", "Connection URL updated: $serverUrl")
            }
            android.util.Log.d("WebDavHelper", "Connection test SUCCESSFUL")
            return@withContext Result.success(true)

        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Connection test FAILED", e)
            val classified = takagi.ru.monica.webdav.WebDavErrorClassifier.classify(e)
            val message = buildConnectionErrorMessage(
                classified,
                takagi.ru.monica.webdav.WebDavUrlBuilder.normalizeServer(serverUrl)
            )
            Result.failure(Exception(message, e))
        }
    }

    private fun buildConnectionErrorMessage(
        classified: takagi.ru.monica.webdav.WebDavErrorClassifier.ClassifiedError?,
        normalizedUrl: String,
    ): String {
        val urlHint = if (normalizedUrl.isNotEmpty()) " ($normalizedUrl)" else ""
        val kind = classified?.kind ?: takagi.ru.monica.webdav.WebDavErrorKind.Unknown
        return when (kind) {
            takagi.ru.monica.webdav.WebDavErrorKind.RateLimited -> {
                val waitSec = ((classified?.retryAfterMillis ?: 0L) / 1000L).coerceAtLeast(1L)
                context.getString(R.string.webdav_error_rate_limited, waitSec) + urlHint
            }
            takagi.ru.monica.webdav.WebDavErrorKind.AuthFailed ->
                context.getString(R.string.webdav_error_auth_failed) + urlHint
            takagi.ru.monica.webdav.WebDavErrorKind.MethodNotAllowed ->
                context.getString(R.string.webdav_error_method_not_allowed) + urlHint
            takagi.ru.monica.webdav.WebDavErrorKind.NetworkUnreachable,
            takagi.ru.monica.webdav.WebDavErrorKind.Timeout ->
                context.getString(R.string.webdav_error_network_unreachable) + urlHint
            takagi.ru.monica.webdav.WebDavErrorKind.MalformedResponse ->
                context.getString(R.string.webdav_error_malformed_response) + urlHint
            takagi.ru.monica.webdav.WebDavErrorKind.NotFound -> "资源不存在$urlHint"
            takagi.ru.monica.webdav.WebDavErrorKind.Ok,
            takagi.ru.monica.webdav.WebDavErrorKind.Unknown -> {
                val raw = classified?.cause?.message ?: "未知错误"
                "连接测试失败: $raw$urlHint"
            }
        }
    }

    private fun portablePasswordForBackup(
        entry: PasswordEntry,
        securityManager: SecurityManager
    ): PasswordEntry {
        return entry.copy(
            password = portableSensitiveBackupValue(
                value = entry.password,
                securityManager = securityManager,
                entryTitle = entry.title
            ),
            authenticatorKey = portableSensitiveBackupValue(
                value = entry.authenticatorKey,
                securityManager = securityManager,
                entryTitle = entry.title
            )
        )
    }

    private fun portableSecureItemForBackup(
        item: SecureItem,
        securityManager: SecurityManager
    ): SecureItem {
        if (
            item.itemType != ItemType.TOTP &&
            item.itemType != ItemType.BANK_CARD &&
            item.itemType != ItemType.DOCUMENT &&
            item.itemType != ItemType.BILLING_ADDRESS &&
            item.itemType != ItemType.PAYMENT_ACCOUNT
        ) return item
        val portableItemData = if (item.itemType == ItemType.TOTP) {
            PortableTotpBackupCodec.encode(
                storedItemData = item.itemData,
                entryTitle = item.title,
                decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
            )
        } else {
            portableSensitiveBackupValue(
                value = item.itemData,
                securityManager = securityManager,
                entryTitle = item.title
            )
        }
        return item.copy(itemData = portableItemData)
    }

    private fun portableSensitiveBackupValue(
        value: String,
        securityManager: SecurityManager,
        entryTitle: String
    ): String {
        if (value.isBlank()) return value
        return PortableSecretExportPolicy.resolve(
            storedValue = value,
            entryTitle = entryTitle,
            decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext
        )
    }

    /**
     * 创建备份文件 (通用方法，用于 WebDAV 上传和本地导出)
     * @param passwords 所有密码条目
     * @param secureItems 所有其他安全数据项
     * @param preferences 备份偏好设置
     * @param allowBackupEncryption 是否允许启用备份加密
     * @param backupEncryptionPassword 本次备份显式指定的密码；为空时沿用远程备份加密配置
     * @return Result<Pair<File, BackupReport>> 包含生成的ZIP文件和备份报告
     */
    suspend fun createBackupZip(
        
        passwords: List<PasswordEntry>,
        secureItems: List<SecureItem>,
        preferences: BackupPreferences = getBackupPreferences(),
        contentScope: BackupContentScope = BackupContentScope.MONICA_LOCAL_ONLY,
        allowBackupEncryption: Boolean = true,
        backupEncryptionPassword: String? = null,
    ): Result<Pair<File, BackupReport>> = withContext(Dispatchers.IO) {
        try {
            // 验证：检查是否至少启用了一种内容类型
            if (!preferences.hasAnyEnabled()) {
                android.util.Log.w("WebDavHelper", "Backup cancelled: no content types selected")
                return@withContext Result.failure(Exception("请至少选择一种备份内容"))
            }

            android.util.Log.d(
                "WebDavHelper",
                "Creating backup zip: scope=$contentScope, preferences=$preferences, " +
                    "inputPasswords=${passwords.size}, inputSecureItems=${secureItems.size}"
            )

            // P0修复：错误跟踪
            val failedItems = mutableListOf<FailedItem>()
            val warnings = mutableListOf<String>()
            var successPasswordCount = 0
            var successNoteCount = 0
            var successImageCount = 0
            var successSteamMaFileCount = 0
            var totalPasskeyCount = 0
            var successPasskeyCount = 0
            var mdbxFallbackItemCount = 0
            val securityManager = SecurityManager(context)
            val steamMaFileBackups = if (preferences.includeAuthenticators) {
                runCatching { createSteamMaFileBackups(securityManager) }
                    .onFailure { error ->
                        android.util.Log.w("WebDavHelper", "Failed to prepare Steam maFile backups: ${error.message}")
                        warnings.add("Steam maFile备份失败: ${error.message}")
                    }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }

            // 1. 创建临时导出文件/目录
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val cacheBackupDir = File(context.cacheDir, "Monica_${timestamp}_backup")
            if (!cacheBackupDir.exists()) cacheBackupDir.mkdirs()

            val passwordsCsvFile = File(cacheBackupDir, "Monica_${timestamp}_password.csv")
            val foldersRootDir = File(cacheBackupDir, "folders")
            val passwordHistoryJsonFile = File(cacheBackupDir, "password_history.json")
            
            val historyJsonFile = File(context.cacheDir, "Monica_${timestamp}_generated_history.json")
            val zipFile = File(context.cacheDir, "monica_backup_$timestamp.zip")
            val backupEncryptPassword = BackupEncryptionPolicy.resolvePassword(
                allowBackupEncryption = allowBackupEncryption,
                explicitPassword = backupEncryptionPassword,
                configuredEncryptionEnabled = enableEncryption,
                configuredEncryptionPassword = encryptionPassword,
            )
            val shouldEncryptBackup = backupEncryptPassword != null
            val finalFile = if (shouldEncryptBackup) {
                File(context.cacheDir, "monica_backup_$timestamp.enc.zip")
            } else {
                zipFile
            }

            try {
                // 2. 根据偏好设置过滤密码数据
                val backupPasswordCandidates = if (preferences.includePasswords) passwords else emptyList()
                val includedMdbxPasswordCount = backupPasswordCandidates.count { entry ->
                    entry.isMdbxEntry() &&
                        BackupContentPolicy.shouldIncludePassword(entry, contentScope)
                }
                val filteredPasswords = backupPasswordCandidates
                    .filter { BackupContentPolicy.shouldIncludePassword(it, contentScope) }
                    .map(BackupContentPolicy::sanitizePasswordForMonicaBackup)
                    .map { portablePasswordForBackup(it, securityManager) }
                mdbxFallbackItemCount += includedMdbxPasswordCount
                val skippedExternalPasswordCount = backupPasswordCandidates.size - filteredPasswords.size
                val repairedDetachedPasswordCount =
                    backupPasswordCandidates.count(BackupContentPolicy::isLikelyDetachedKeePassPassword)
                if (skippedExternalPasswordCount > 0) {
                    warnings.add("已跳过 $skippedExternalPasswordCount 条非 Monica 本地密码")
                }
                if (repairedDetachedPasswordCount > 0) {
                    warnings.add("已按 Monica 本地修复 $repairedDetachedPasswordCount 条遗留 KeePass 标记的密码")
                }
                android.util.Log.d(
                    "WebDavHelper",
                    "Backup password selection: scope=$contentScope, candidates=${backupPasswordCandidates.size}, " +
                        "included=${filteredPasswords.size}, skipped=$skippedExternalPasswordCount"
                )
                
                // 3. 根据偏好设置过滤安全项目
                val backupSecureItemCandidates = secureItems.filter { item ->
                    when (item.itemType) {
                        ItemType.TOTP -> preferences.includeAuthenticators
                        ItemType.DOCUMENT -> preferences.includeDocuments
                        ItemType.BANK_CARD -> preferences.includeBankCards
                        ItemType.BILLING_ADDRESS -> preferences.includeBankCards || preferences.includeDocuments
                        ItemType.PAYMENT_ACCOUNT -> preferences.includeBankCards || preferences.includeDocuments
                        ItemType.NOTE -> preferences.includeNotes
                        else -> true
                    }
                }
                val includedMdbxSecureItemCount = backupSecureItemCandidates.count { item ->
                    item.isMdbxOwned() &&
                        BackupContentPolicy.shouldIncludeSecureItem(item, contentScope)
                }
                val filteredSecureItems = backupSecureItemCandidates
                    .filter { BackupContentPolicy.shouldIncludeSecureItem(it, contentScope) }
                    .map(BackupContentPolicy::sanitizeSecureItemForMonicaBackup)
                    .map { portableSecureItemForBackup(it, securityManager) }
                mdbxFallbackItemCount += includedMdbxSecureItemCount
                val skippedExternalSecureItemCount =
                    backupSecureItemCandidates.size - filteredSecureItems.size
                val repairedDetachedSecureItemCount =
                    backupSecureItemCandidates.count(BackupContentPolicy::isLikelyDetachedKeePassSecureItem)
                if (skippedExternalSecureItemCount > 0) {
                    warnings.add("已跳过 $skippedExternalSecureItemCount 条非 Monica 本地安全项")
                }
                if (repairedDetachedSecureItemCount > 0) {
                    warnings.add("已按 Monica 本地修复 $repairedDetachedSecureItemCount 条遗留 KeePass 标记的安全项")
                }
                android.util.Log.d(
                    "WebDavHelper",
                    "Backup secure item selection: scope=$contentScope, candidates=${backupSecureItemCandidates.size}, " +
                        "included=${filteredSecureItems.size}, skipped=$skippedExternalSecureItemCount"
                )

                // 分类过滤后的项目
                val totpItems = filteredSecureItems.filter { it.itemType == ItemType.TOTP }
                val cardWalletItems = filteredSecureItems.filter {
                    it.itemType == ItemType.BANK_CARD ||
                        it.itemType == ItemType.DOCUMENT ||
                        it.itemType == ItemType.BILLING_ADDRESS ||
                        it.itemType == ItemType.PAYMENT_ACCOUNT
                }
                val noteItems = filteredSecureItems.filter { it.itemType == ItemType.NOTE }

                val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                val categoryDao = database.categoryDao()
                val customFieldDao = database.customFieldDao()
                val passwordHistoryDao = database.passwordHistoryDao()
                val passkeyDao = database.passkeyDao()
                val passkeyCandidates = if (preferences.includePasskeys) {
                    passkeyDao.getAllPasskeysSync()
                } else {
                    emptyList()
                }
                val passkeysForBackup = passkeyCandidates
                    .filter { BackupContentPolicy.shouldIncludePasskey(it, contentScope) }
                totalPasskeyCount = passkeysForBackup.size
                mdbxFallbackItemCount += passkeysForBackup.count { it.isMdbxOwned() }
                val skippedExternalPasskeyCount = passkeyCandidates.size - passkeysForBackup.size
                if (skippedExternalPasskeyCount > 0) {
                    warnings.add("已跳过 $skippedExternalPasskeyCount 条非 Monica 本地通行密钥")
                }
                android.util.Log.d(
                    "WebDavHelper",
                    "Backup passkey selection: scope=$contentScope, candidates=${passkeyCandidates.size}, " +
                        "included=${passkeysForBackup.size}, skipped=$skippedExternalPasskeyCount"
                )
                if (passkeysForBackup.isNotEmpty() && !shouldEncryptBackup) {
                    throw PasskeyBackupEncryptionRequiredException(
                        context.getString(
                            R.string.passkey_backup_encryption_required,
                            passkeysForBackup.size,
                        )
                    )
                }
                val allCategories = try { categoryDao.getAllCategories().first() } catch (e: Exception) { emptyList() }
                val categoryMap = allCategories.associateBy { it.id }
                val passwordCategoryById = filteredPasswords.associate { it.id to it.categoryId }
                val pendingZipEntries = mutableListOf<String>()
                fun addFileToZipPending(entryName: String) {
                    pendingZipEntries.add(entryName)
                }

                // 4. 导出密码数据到JSON
                if (preferences.includePasswords && filteredPasswords.isNotEmpty()) {
                    val json = Json { prettyPrint = false }
                    
                    // 收集所有自定义字段用于CSV导出
                    val allCustomFieldsMap = mutableMapOf<Long, List<CustomFieldBackupEntry>>()
                    val uploadedPasswordIconFiles = mutableSetOf<String>()
                    
                    filteredPasswords.forEach { password ->
                        try {
                            val categoryName = password.categoryId?.let { id -> categoryMap[id]?.name }
                            
                            // 获取自定义字段
                            val fields = try {
                                customFieldDao.getFieldsByEntryIdSync(password.id).map { field ->
                                    CustomFieldBackupEntry(
                                        title = field.title,
                                        value = field.value,
                                        isProtected = field.isProtected
                                    )
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                            
                            // 保存到映射中供CSV导出使用
                            if (fields.isNotEmpty()) {
                                allCustomFieldsMap[password.id] = fields
                            }
                            
                            val backup = PasswordBackupEntry(
                                id = password.id,
                                title = password.title,
                                username = password.username,
                                password = password.password,
                                website = password.website,
                                notes = password.notes,
                                isFavorite = password.isFavorite,
                                categoryId = password.categoryId,
                                categoryName = categoryName,
                                appPackageName = password.appPackageName,
                                appName = password.appName,
                                email = password.email,
                                phone = password.phone,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                createdAt = password.createdAt.time,
                                updatedAt = password.updatedAt.time,
                                authenticatorKey = password.authenticatorKey,  // ✅ 直接备份验证器密钥
                                passkeyBindings = password.passkeyBindings,
                                sshKeyData = password.sshKeyData,
                                // ✅ 第三方登录(SSO)字段
                                loginType = password.loginType,
                                ssoProvider = password.ssoProvider,
                                ssoRefEntryId = password.ssoRefEntryId,
                                customIconType = password.customIconType,
                                customIconValue = normalizeBackupIconValue(password.customIconType, password.customIconValue),
                                customIconUpdatedAt = password.customIconUpdatedAt,
                                // ✅ WIFI 扩展元数据
                                wifiMetadata = password.wifiMetadata,
                                // ✅ 自定义字段
                                customFields = fields
                            )
                            if (
                                password.customIconType.equals("UPLOADED", ignoreCase = true) &&
                                !password.customIconValue.isNullOrBlank()
                            ) {
                                uploadedPasswordIconFiles.add(File(password.customIconValue).name)
                            }
                            val folderKey = toFolderKey(categoryName)
                            val targetDir = File(foldersRootDir, "$folderKey/passwords")
                            if (!targetDir.exists()) targetDir.mkdirs()
                            val fileName = "password_${password.id}_${password.createdAt.time}.json"
                            val target = File(targetDir, fileName)
                            target.writeText(json.encodeToString(PasswordBackupEntry.serializer(), backup), Charsets.UTF_8)
                            successPasswordCount++
                        } catch (e: Exception) {
                            android.util.Log.e("WebDavHelper", "导出密码失败: ${password.id}", e)
                            failedItems.add(FailedItem(
                                id = password.id,
                                type = "密码",
                                title = password.title,
                                reason = "序列化失败: ${e.message}"
                            ))
                        }
                    }
                    
                    try {
                        exportPasswordsToCSV(filteredPasswords, passwordsCsvFile, allCustomFieldsMap)
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "CSV backup failed: ${e.message}")
                    }

                    try {
                        val historyByEntryId = passwordHistoryDao.getAllHistorySync()
                            .groupBy(PasswordHistoryEntry::entryId)
                        val passwordHistoryBackups = filteredPasswords.flatMap { password ->
                            historyByEntryId[password.id].orEmpty().mapNotNull { historyEntry ->
                                normalizePasswordHistoryForBackup(historyEntry, securityManager)
                            }
                        }
                        if (passwordHistoryBackups.isNotEmpty()) {
                            passwordHistoryJsonFile.writeText(
                                Json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(PasswordHistoryBackupEntry.serializer()),
                                    passwordHistoryBackups
                                ),
                                Charsets.UTF_8
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Password history backup failed: ${e.message}")
                        warnings.add("历史密码备份失败: ${e.message}")
                    }

                    if (uploadedPasswordIconFiles.isNotEmpty()) {
                        val iconDir = File(context.filesDir, "password_icons")
                        uploadedPasswordIconFiles.forEach { fileName ->
                            val iconFile = File(iconDir, fileName)
                            if (iconFile.exists()) {
                                addFileToZipPending("password_icons/$fileName")
                            } else {
                                warnings.add("自定义图标文件缺失: $fileName")
                            }
                        }
                    }
                    
                    try {
                        if (allCategories.isNotEmpty()) {
                            val categoriesFile = File(cacheBackupDir, "categories.json")
                            val categoryBackups = allCategories.map { cat ->
                                CategoryBackupEntry(cat.id, cat.name, cat.sortOrder)
                            }
                            categoriesFile.writeText(
                                Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CategoryBackupEntry.serializer()), categoryBackups),
                                Charsets.UTF_8
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Category backup failed: ${e.message}")
                    }
                }

                // 5. 导出 TOTP（新格式：folders/<category>/authenticators/*.json）
                if (totpItems.isNotEmpty()) {
                    val json = Json { prettyPrint = false }
                    totpItems.forEach { item ->
                        try {
                            val categoryName = item.categoryId?.let { id -> categoryMap[id]?.name }
                            val backup = TotpBackupEntry(
                                id = item.id,
                                title = item.title,
                                itemData = normalizeRestoredTotpItemData(item.itemData, item.title),
                                notes = item.notes,
                                isFavorite = item.isFavorite,
                                imagePaths = item.imagePaths,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                createdAt = item.createdAt.time,
                                updatedAt = item.updatedAt.time,
                                categoryName = categoryName
                            )
                            val folderKey = toFolderKey(categoryName)
                            val targetDir = File(foldersRootDir, "$folderKey/authenticators")
                            if (!targetDir.exists()) targetDir.mkdirs()
                            val fileName = "totp_${item.id}_${item.createdAt.time}.json"
                            val target = File(targetDir, fileName)
                            target.writeText(json.encodeToString(TotpBackupEntry.serializer(), backup), Charsets.UTF_8)
                        } catch (e: Exception) {
                            failedItems.add(
                                FailedItem(
                                    id = item.id,
                                    type = "验证器",
                                    title = item.title,
                                    reason = "序列化失败: ${e.message}"
                                )
                            )
                        }
                    }
                }

                // 6. 导出卡包资料（新格式：folders/<category>/{bank_cards|documents|billing_addresses|payment_accounts}/*.json）
                if (cardWalletItems.isNotEmpty()) {
                    val json = Json { prettyPrint = false }
                    cardWalletItems.forEach { item ->
                        try {
                            val categoryName = item.categoryId?.let { id -> categoryMap[id]?.name }
                            val backup = CardWalletBackupEntry(
                                id = item.id,
                                itemType = item.itemType.name,
                                title = item.title,
                                itemData = item.itemData,
                                notes = item.notes,
                                isFavorite = item.isFavorite,
                                imagePaths = item.imagePaths,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                createdAt = item.createdAt.time,
                                updatedAt = item.updatedAt.time,
                                categoryName = categoryName
                            )
                            val folderKey = toFolderKey(categoryName)
                            val targetDir = when (item.itemType) {
                                ItemType.BANK_CARD -> File(foldersRootDir, "$folderKey/bank_cards")
                                ItemType.DOCUMENT -> File(foldersRootDir, "$folderKey/documents")
                                ItemType.BILLING_ADDRESS -> File(foldersRootDir, "$folderKey/billing_addresses")
                                ItemType.PAYMENT_ACCOUNT -> File(foldersRootDir, "$folderKey/payment_accounts")
                                else -> null
                            } ?: return@forEach
                            if (!targetDir.exists()) targetDir.mkdirs()
                            val filePrefix = when (item.itemType) {
                                ItemType.BANK_CARD -> "bank_card"
                                ItemType.DOCUMENT -> "document"
                                ItemType.BILLING_ADDRESS -> "billing_address"
                                ItemType.PAYMENT_ACCOUNT -> "payment_account"
                                else -> "secure_item"
                            }
                            val fileName = "${filePrefix}_${item.id}_${item.createdAt.time}.json"
                            val target = File(targetDir, fileName)
                            target.writeText(
                                json.encodeToString(CardWalletBackupEntry.serializer(), backup),
                                Charsets.UTF_8
                            )
                        } catch (e: Exception) {
                            failedItems.add(
                                FailedItem(
                                    id = item.id,
                                    type = when (item.itemType) {
                                        ItemType.BANK_CARD -> "卡片"
                                        ItemType.DOCUMENT -> "证件"
                                        ItemType.BILLING_ADDRESS -> "账单地址"
                                        ItemType.PAYMENT_ACCOUNT -> "支付方式"
                                        else -> "安全项"
                                    },
                                    title = item.title,
                                    reason = "序列化失败: ${e.message}"
                                )
                            )
                        }
                    }
                }

                // 6.5 导出笔记
                if (noteItems.isNotEmpty()) {
                    val json = Json { prettyPrint = false }
                    noteItems.forEach { item ->
                        try {
                            val categoryName = item.categoryId?.let { id -> categoryMap[id]?.name }
                            val backup = NoteBackupEntry(
                                id = item.id,
                                title = item.title,
                                notes = item.notes,
                                itemData = item.itemData,
                                isFavorite = item.isFavorite,
                                imagePaths = item.imagePaths,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null,
                                createdAt = item.createdAt.time,
                                updatedAt = item.updatedAt.time,
                                categoryName = categoryName
                            )
                            val folderKey = toFolderKey(categoryName)
                            val targetDir = File(foldersRootDir, "$folderKey/notes")
                            if (!targetDir.exists()) targetDir.mkdirs()
                            val fileName = "note_${item.id}_${item.createdAt.time}.json"
                            val target = File(targetDir, fileName)
                            target.writeText(json.encodeToString(NoteBackupEntry.serializer(), backup), Charsets.UTF_8)
                            successNoteCount++
                        } catch (e: Exception) {
                            failedItems.add(FailedItem(
                                id = item.id,
                                type = "笔记",
                                title = item.title,
                                reason = "序列化失败: ${e.message}"
                            ))
                        }
                    }
                }

                // 7. 创建 ZIP
                // 6.8 导出 Passkeys
                if (passkeysForBackup.isNotEmpty()) {
                    try {
                        val json = Json { prettyPrint = false }
                        passkeysForBackup.forEach { passkey ->
                                val keyDecision = PasskeyBackupPortabilityPolicy.prepareExport(
                                    encryptedBackup = shouldEncryptBackup,
                                    storedPrivateKey = passkey.privateKeyAlias,
                                    resolvePrivateKey = { stored ->
                                        PasskeyPrivateKeyStore.resolve(securityManager, stored)
                                    },
                                    normalizePrivateKey = PasskeyPrivateKeySupport::exportPkcs8Base64,
                                )
                                val portablePrivateKey = when (keyDecision) {
                                    is PasskeyBackupPortabilityPolicy.ExportDecision.Ready -> {
                                        keyDecision.privateKeyMaterial
                                    }
                                    PasskeyBackupPortabilityPolicy.ExportDecision.EncryptionRequired -> {
                                        throw PasskeyBackupEncryptionRequiredException(
                                            context.getString(
                                                R.string.passkey_backup_encryption_required,
                                                passkeysForBackup.size,
                                            )
                                        )
                                    }
                                    PasskeyBackupPortabilityPolicy.ExportDecision.PrivateKeyMissing -> {
                                        failedItems.add(
                                            FailedItem(
                                                id = passkey.id,
                                                type = context.getString(R.string.backup_content_passkeys),
                                                title = passkey.displayTitle(),
                                                reason = context.getString(
                                                    R.string.passkey_backup_private_key_missing
                                                ),
                                            )
                                        )
                                        return@forEach
                                    }
                                }
                                val derivedCategoryId = passkey.categoryId
                                    ?: passkey.boundPasswordId?.let { passwordCategoryById[it] }
                                val categoryName = derivedCategoryId?.let { id -> categoryMap[id]?.name }
                                val backup = PasskeyBackupEntry(
                                    credentialId = passkey.credentialId,
                                    rpId = passkey.rpId,
                                    rpName = passkey.rpName,
                                    userId = passkey.userId,
                                    userName = passkey.userName,
                                    userDisplayName = passkey.userDisplayName,
                                    publicKeyAlgorithm = passkey.publicKeyAlgorithm,
                                    publicKey = passkey.publicKey,
                                    privateKeyAlias = portablePrivateKey,
                                    createdAt = passkey.createdAt,
                                    lastUsedAt = passkey.lastUsedAt,
                                    useCount = passkey.useCount,
                                    iconUrl = passkey.iconUrl,
                                    isDiscoverable = passkey.isDiscoverable,
                                    isUserVerificationRequired = passkey.isUserVerificationRequired,
                                    transports = passkey.transports,
                                    aaguid = passkey.aaguid,
                                    signCount = passkey.signCount,
                                    notes = passkey.notes,
                                    boundPasswordId = passkey.boundPasswordId,
                                    passkeyMode = normalizePasskeyMode(passkey.passkeyMode),
                                    categoryName = categoryName
                                )
                                val folderKey = toFolderKey(categoryName)
                                val targetDir = File(foldersRootDir, "$folderKey/passkeys")
                                if (!targetDir.exists()) targetDir.mkdirs()
                                val safeId = passkey.credentialId.replace("/", "_")
                                val fileName = "passkey_${safeId}.json"
                                val target = File(targetDir, fileName)
                                target.writeText(json.encodeToString(PasskeyBackupEntry.serializer(), backup), Charsets.UTF_8)
                                successPasskeyCount++
                        }
                    } catch (e: PasskeyBackupEncryptionRequiredException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Failed to backup passkeys: ${e.message}")
                        throw e
                    }
                }

                if (mdbxFallbackItemCount > 0) {
                    warnings.add(
                        "已备份 $mdbxFallbackItemCount 条 MDBX2 内容；通用备份恢复时会转换为 Monica 本地条目，不会重建 MDBX2 数据库文件"
                    )
                }

                val localKeePassDatabasesForBackup = if (preferences.includeLocalKeePass) {
                    runCatching {
                        takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                            .localKeePassDatabaseDao()
                            .getAllDatabasesSync()
                    }
                } else {
                    null
                }
                val databaseWithInternalKeyFile = localKeePassDatabasesForBackup
                    ?.getOrNull()
                    ?.firstOrNull { !it.keyFileInternalPath.isNullOrBlank() }
                if (KeePassBackupEntryPolicy.requiresEncryptedBackup(
                        hasInternalKeyFileCopy = databaseWithInternalKeyFile != null,
                        backupEncrypted = shouldEncryptBackup,
                    )
                ) {
                    throw KeePassKeyFileBackupEncryptionRequiredException(
                        "KeePass数据库“${databaseWithInternalKeyFile?.name.orEmpty()}”包含内部密钥文件副本，请先启用备份加密"
                    )
                }

                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    if (foldersRootDir.exists()) {
                        addDirectoryToZip(zipOut, foldersRootDir, "folders")
                    }
                    if (steamMaFileBackups.isNotEmpty()) {
                        val steamMaFilesDir = File(cacheBackupDir, "steam_mafiles")
                        if (!steamMaFilesDir.exists()) steamMaFilesDir.mkdirs()
                        steamMaFileBackups.forEach { backup ->
                            try {
                                val maFile = File(steamMaFilesDir, backup.fileName)
                                maFile.writeText(backup.content, Charsets.UTF_8)
                                addFileToZip(zipOut, maFile, "$STEAM_MAFILE_BACKUP_DIR/${backup.fileName}")
                                successSteamMaFileCount++
                            } catch (e: Exception) {
                                failedItems.add(
                                    FailedItem(
                                        id = 0,
                                        type = STEAM_MAFILE_BACKUP_TYPE,
                                        title = backup.fileName,
                                        reason = "写入失败: ${e.message}"
                                    )
                                )
                            }
                        }
                    }
                    if (preferences.includePasswords && passwordsCsvFile.exists()) {
                        addFileToZip(zipOut, passwordsCsvFile, passwordsCsvFile.name)
                    }
                    if (preferences.includePasswords && passwordHistoryJsonFile.exists()) {
                        addFileToZip(zipOut, passwordHistoryJsonFile, passwordHistoryJsonFile.name)
                    }
                    if (preferences.includeGeneratorHistory) {
                        try {
                            val historyManager = PasswordHistoryManager(context)
                            val historyJson = historyManager.exportHistoryJson()
                            historyJsonFile.writeText(historyJson, Charsets.UTF_8)
                            addFileToZip(zipOut, historyJsonFile, historyJsonFile.name)
                        } catch (e: Exception) {
                            android.util.Log.w("WebDavHelper", "Failed to export history: ${e.message}")
                        }
                    }
                    if (preferences.includeImages) {
                        try {
                            val imageFileNames = extractAllImageFileNames(filteredSecureItems)
                            val imageDir = File(context.filesDir, "secure_images")
                            imageFileNames.forEach { fileName ->
                                val imageFile = File(imageDir, fileName)
                                if (imageFile.exists()) {
                                    addFileToZip(zipOut, imageFile, "images/$fileName")
                                    successImageCount++
                                } else {
                                    warnings.add("图片文件缺失: $fileName")
                                }
                            }
                        } catch (e: Exception) {
                            warnings.add("图片备份失败: ${e.message}")
                        }
                    }

                    if (pendingZipEntries.isNotEmpty()) {
                        val iconDir = File(context.filesDir, "password_icons")
                        pendingZipEntries.forEach { entryName ->
                            val fileName = entryName.substringAfterLast("/")
                            val iconFile = File(iconDir, fileName)
                            if (iconFile.exists()) {
                                addFileToZip(zipOut, iconFile, entryName)
                            } else {
                                warnings.add("自定义图标文件缺失: $fileName")
                            }
                        }
                    }
                    
                    // 7.5 备份操作历史记录 (时间线)
                    if (preferences.includeTimeline) {
                        try {
                            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                            val operationLogDao = database.operationLogDao()
                            val allLogs = operationLogDao.getAllLogsSync()
                            
                            if (allLogs.isNotEmpty()) {
                                val logBackups = allLogs.map { log ->
                                    OperationLogBackupEntry(
                                        id = log.id,
                                        itemType = log.itemType,
                                        itemId = log.itemId,
                                        itemTitle = log.itemTitle,
                                        operationType = log.operationType,
                                        changesJson = log.changesJson,
                                        deviceId = log.deviceId,
                                        deviceName = log.deviceName,
                                        timestamp = log.timestamp,
                                        isReverted = log.isReverted
                                    )
                                }
                                val json = Json { prettyPrint = false }
                                val timelineJson = json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(OperationLogBackupEntry.serializer()),
                                    logBackups
                                )
                                val timelineFile = File(cacheBackupDir, "timeline_history.json")
                                timelineFile.writeText(timelineJson, Charsets.UTF_8)
                                addFileToZip(zipOut, timelineFile, timelineFile.name)
                                timelineFile.delete()
                                android.util.Log.d("WebDavHelper", "Backup ${allLogs.size} timeline entries")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("WebDavHelper", "Failed to backup timeline: ${e.message}")
                            warnings.add("操作历史备份失败: ${e.message}")
                        }
                    }
                    
                    // 7.6 备份回收站数据
                    if (preferences.includeTrash) {
                        try {
                            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                            val passwordEntryDao = database.passwordEntryDao()
                            val secureItemDao = database.secureItemDao()
                            
                            // 获取已删除的密码
                            val deletedPasswords = passwordEntryDao.getDeletedEntriesSync()
                            // 获取已删除的安全项目
                            val deletedSecureItems = secureItemDao.getDeletedItemsSync()
                            
                            val json = Json { prettyPrint = false }
                            val trashDir = File(cacheBackupDir, "trash")
                            if (!trashDir.exists()) trashDir.mkdirs()
                            
                            // 备份已删除的密码
                            if (deletedPasswords.isNotEmpty()) {
                                val categoryDao = database.categoryDao()
                                val allCategories = try { categoryDao.getAllCategories().first() } catch (e: Exception) { emptyList() }
                                val categoryMap = allCategories.associateBy { it.id }
                                
                                val trashPasswordBackups = deletedPasswords.map { deletedPassword ->
                                    val password = portablePasswordForBackup(deletedPassword, securityManager)
                                    val categoryName = password.categoryId?.let { id -> categoryMap[id]?.name }
                                    TrashPasswordBackupEntry(
                                        id = password.id,
                                        title = password.title,
                                        username = password.username,
                                        password = password.password,
                                        website = password.website,
                                        notes = password.notes,
                                        isFavorite = password.isFavorite,
                                        categoryId = password.categoryId,
                                        categoryName = categoryName,
                                        email = password.email,
                                        phone = password.phone,
                                        createdAt = password.createdAt.time,
                                        updatedAt = password.updatedAt.time,
                                        authenticatorKey = password.authenticatorKey,
                                        passkeyBindings = password.passkeyBindings,
                                        sshKeyData = password.sshKeyData,
                                        deletedAt = password.deletedAt?.time,
                                        // ✅ 第三方登录(SSO)字段
                                        loginType = password.loginType,
                                        ssoProvider = password.ssoProvider,
                                        ssoRefEntryId = password.ssoRefEntryId,
                                        customIconType = password.customIconType,
                                        customIconValue = password.customIconValue,
                                        customIconUpdatedAt = password.customIconUpdatedAt
                                    )
                                }
                                val trashPasswordsFile = File(trashDir, "trash_passwords.json")
                                trashPasswordsFile.writeText(
                                    json.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(TrashPasswordBackupEntry.serializer()),
                                        trashPasswordBackups
                                    ),
                                    Charsets.UTF_8
                                )
                                addFileToZip(zipOut, trashPasswordsFile, "trash/${trashPasswordsFile.name}")
                            }
                            
                            // 备份已删除的安全项目
                            if (deletedSecureItems.isNotEmpty()) {
                                val trashSecureItemBackups = deletedSecureItems.map { deletedItem ->
                                    val item = portableSecureItemForBackup(deletedItem, securityManager)
                                    val normalizedItemData = if (item.itemType == ItemType.TOTP) {
                                        normalizeRestoredTotpItemData(item.itemData, item.title)
                                    } else {
                                        item.itemData
                                    }
                                    TrashSecureItemBackupEntry(
                                        id = item.id,
                                        title = item.title,
                                        itemType = item.itemType.name,
                                        itemData = normalizedItemData,
                                        notes = item.notes,
                                        isFavorite = item.isFavorite,
                                        imagePaths = item.imagePaths,
                                        createdAt = item.createdAt.time,
                                        updatedAt = item.updatedAt.time,
                                        deletedAt = item.deletedAt?.time,
                                        categoryId = item.categoryId
                                    )
                                }
                                val trashSecureItemsFile = File(trashDir, "trash_secure_items.json")
                                trashSecureItemsFile.writeText(
                                    json.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(TrashSecureItemBackupEntry.serializer()),
                                        trashSecureItemBackups
                                    ),
                                    Charsets.UTF_8
                                )
                                addFileToZip(zipOut, trashSecureItemsFile, "trash/${trashSecureItemsFile.name}")
                            }
                            
                            trashDir.deleteRecursively()
                            val totalTrashCount = deletedPasswords.size + deletedSecureItems.size
                            android.util.Log.d("WebDavHelper", "Backup $totalTrashCount trash items (${deletedPasswords.size} passwords, ${deletedSecureItems.size} secure items)")
                        } catch (e: Exception) {
                            android.util.Log.w("WebDavHelper", "Failed to backup trash: ${e.message}")
                            warnings.add("回收站备份失败: ${e.message}")
                        }
                    }
                    
                    // 7.7 ✅ 备份常用账号信息
                    try {
                        val commonAccountPreferences = takagi.ru.monica.data.CommonAccountPreferences(context)
                        val commonInfo = commonAccountPreferences.commonAccountInfo.first()
                        val commonTemplates = commonAccountPreferences.templatesFlow.first()
                        
                        if (commonInfo.hasAnyInfo() || commonInfo.autoFillEnabled || commonTemplates.isNotEmpty()) {
                            val templateBackups = commonTemplates.map { template ->
                                CommonAccountTemplateBackupEntry(
                                    id = template.id,
                                    type = template.type,
                                    title = "",
                                    content = template.content
                                )
                            }
                            val commonAccountBackup = CommonAccountBackupEntry(
                                email = commonInfo.email,
                                phone = commonInfo.phone,
                                username = commonInfo.username,
                                autoFillEnabled = commonInfo.autoFillEnabled,
                                billingAddress = CardWalletDataCodec.encodeBillingAddress(commonInfo.billingAddress),
                                templates = templateBackups
                            )
                            val json = Json { prettyPrint = false }
                            val monicaConfigDir = File(cacheBackupDir, "monica_config").apply { mkdirs() }
                            val commonAccountFile = File(monicaConfigDir, "common_account.json")
                            commonAccountFile.writeText(
                                json.encodeToString(CommonAccountBackupEntry.serializer(), commonAccountBackup),
                                Charsets.UTF_8
                            )
                            addFileToZip(zipOut, commonAccountFile, "monica_config/${commonAccountFile.name}")
                            commonAccountFile.delete()
                            android.util.Log.d("WebDavHelper", "Backup common account info (templates=${templateBackups.size})")
                        } else {
                            // no-op
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Failed to backup common account info: ${e.message}")
                        warnings.add("常用账号信息备份失败: ${e.message}")
                    }
                    
                    // 7.8 ✅ 便携应用设置始终随正常备份保存；连接凭据仍需用户明确选择。
                    writePortableAppSettingsBackup(
                        zipOut = zipOut,
                        cacheBackupDir = cacheBackupDir,
                        backupEncryptPassword = backupEncryptPassword,
                        warnings = warnings,
                    )
                    if (preferences.includeWebDavConfig && isConfigured()) {
                        writeConnectionConfigBackup(
                            zipOut = zipOut,
                            cacheBackupDir = cacheBackupDir,
                            backupEncryptPassword = backupEncryptPassword,
                            warnings = warnings,
                        )
                    }
                    
                    // KeePass WebDAV 已下线，不再备份 keepass_webdav_config.json。
                    
                    // 7.9 ✅ 备份本地 KeePass 数据库
                    if (preferences.includeLocalKeePass) {
                        try {
                            val allKeePassDatabases = localKeePassDatabasesForBackup
                                ?.getOrThrow()
                                .orEmpty()

                            if (allKeePassDatabases.isNotEmpty()) {
                                val keepassDir = File(cacheBackupDir, "keepass")
                                if (!keepassDir.exists()) keepassDir.mkdirs()
                                
                                val json = Json { prettyPrint = false }
                                val keyFileStore = KeePassKeyFileStore(context)
                                var backupCount = 0
                                
                                allKeePassDatabases.forEach { kpDb: takagi.ru.monica.data.LocalKeePassDatabase ->
                                    try {
                                        val keyFileBytes = try {
                                            kpDb.keyFileInternalPath
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let(keyFileStore::readInternal)
                                        } catch (e: Exception) {
                                            throw KeePassKeyFileBackupUnavailableException(
                                                "KeePass数据库“${kpDb.name}”的内部密钥文件无法读取: ${e.message}",
                                                e,
                                            )
                                        }
                                        val keyFileFingerprint = keyFileBytes?.let(KeePassKeyFileStore::fingerprint)
                                        if (
                                            keyFileBytes != null &&
                                            !kpDb.keyFileFingerprint.isNullOrBlank() &&
                                            !keyFileFingerprint.equals(kpDb.keyFileFingerprint, ignoreCase = true)
                                        ) {
                                            throw KeePassKeyFileBackupUnavailableException(
                                                "KeePass数据库“${kpDb.name}”的内部密钥文件指纹校验失败"
                                            )
                                        }
                                        val keyFileEntryName = keyFileBytes?.let {
                                            "keepass/keepass_${kpDb.id}.key"
                                        }
                                        // 备份数据库元信息
                                        val metaBackup = KeePassDatabaseBackupEntry(
                                            id = kpDb.id,
                                            name = kpDb.name,
                                            description = kpDb.description ?: "",
                                            originalStorageLocation = kpDb.storageLocation.name,
                                            originalFilePath = kpDb.filePath,
                                            isDefault = kpDb.isDefault,
                                            lastSyncTime = kpDb.lastSyncedAt,
                                            createdAt = kpDb.createdAt,
                                            updatedAt = kpDb.lastAccessedAt,
                                            keyFileName = kpDb.keyFileName,
                                            keyFileFingerprint = keyFileFingerprint,
                                            keyFileEntryName = keyFileEntryName,
                                        )
                                        
                                        // 备份数据库文件内容
                                        val fileContent: ByteArray? = when (kpDb.storageLocation) {
                                            takagi.ru.monica.data.KeePassStorageLocation.INTERNAL -> {
                                                val dbFile = File(context.filesDir, kpDb.filePath)
                                                dbFile.takeIf { it.exists() }?.readBytes()
                                            }
                                            takagi.ru.monica.data.KeePassStorageLocation.EXTERNAL -> {
                                                try {
                                                    val uri = android.net.Uri.parse(kpDb.filePath)
                                                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                                } catch (e: Exception) {
                                                    android.util.Log.w("WebDavHelper", "Failed to read external KeePass file: ${e.message}")
                                                    null
                                                }
                                            }
                                        }
                                        
                                        if (fileContent != null) {
                                            // 写入元信息
                                            val metaFile = File(keepassDir, "keepass_${kpDb.id}_meta.json")
                                            metaFile.writeText(json.encodeToString(KeePassDatabaseBackupEntry.serializer(), metaBackup), Charsets.UTF_8)
                                            addFileToZip(zipOut, metaFile, "keepass/${metaFile.name}")

                                            if (keyFileBytes != null && keyFileEntryName != null) {
                                                addBytesToZip(zipOut, keyFileBytes, keyFileEntryName)
                                            }
                                            
                                            // 写入数据库文件
                                            val dataFile = File(keepassDir, "keepass_${kpDb.id}.kdbx")
                                            dataFile.writeBytes(fileContent)
                                            addFileToZip(zipOut, dataFile, "keepass/${dataFile.name}")
                                            
                                            backupCount++
                                        } else {
                                            warnings.add("KeePass数据库文件不存在: ${kpDb.name}")
                                        }
                                    } catch (e: KeePassKeyFileBackupEncryptionRequiredException) {
                                        throw e
                                    } catch (e: KeePassKeyFileBackupUnavailableException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to backup KeePass database ${kpDb.id}: ${e.message}")
                                        warnings.add("KeePass数据库备份失败: ${kpDb.name}")
                                    }
                                }
                                
                                keepassDir.deleteRecursively()
                                android.util.Log.d("WebDavHelper", "Backup $backupCount KeePass databases")
                            }
                        } catch (e: KeePassKeyFileBackupEncryptionRequiredException) {
                            throw e
                        } catch (e: KeePassKeyFileBackupUnavailableException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("WebDavHelper", "Failed to backup KeePass databases: ${e.message}")
                            warnings.add("KeePass数据库备份失败: ${e.message}")
                        }
                    }

                    // 附件备份：保留旧 `attachments/` 同机恢复格式；加密备份额外写入可跨设备恢复的 portable 格式。
                    // 对应 spec Requirement 9.5。仅备份 LOCAL 附件（Bitwarden/KeePass 附件有自己的远端/kdbx 容器）。
                    try {
                        val attachmentRepository =
                            takagi.ru.monica.attachments.AttachmentContainer.repository(context)
                        val attachmentStorage = File(context.filesDir, "secure_attachments")
                        val localAttachments = attachmentRepository.listAllActiveLocalAttachments()
                            .filter {
                                it.sourceEnum == takagi.ru.monica.attachments.model.AttachmentSource.LOCAL
                            }

                        if (localAttachments.isNotEmpty()) {
                            // 1. 写入密文 blob（按 Room 中存的 local_path 去取）
                            val writtenBlobs = mutableSetOf<String>()
                            localAttachments.forEach { att ->
                                val blobName = att.localPath ?: return@forEach
                                if (blobName in writtenBlobs) return@forEach
                                val blobFile = File(attachmentStorage, blobName)
                                if (blobFile.isFile) {
                                    addFileToZip(zipOut, blobFile, "attachments/$blobName")
                                    writtenBlobs += blobName
                                } else {
                                    warnings.add("附件密文文件缺失: $blobName")
                                }
                            }

                            // 2. 写入元数据 manifest
                            val manifestJson = takagi.ru.monica.attachments.backup
                                .AttachmentBackupCodec.encode(localAttachments)
                            val metaFile = File(cacheBackupDir, "attachments_meta.json")
                            metaFile.writeText(manifestJson, Charsets.UTF_8)
                            addFileToZip(zipOut, metaFile, "attachments/attachments_meta.json")
                            metaFile.delete()

                            android.util.Log.d(
                                "WebDavHelper",
                                "Backup attachments: ${localAttachments.size} records, ${writtenBlobs.size} blobs"
                            )

                            if (shouldEncryptBackup) {
                                val portable = takagi.ru.monica.attachments.backup.PortableAttachmentBackup
                                    .export(context, localAttachments)
                                val portableEntries = mutableListOf<takagi.ru.monica.attachments.backup.PortableAttachmentBackup.Entry>()
                                portable.payloads.forEach { payload ->
                                    val zipEntry = ZipEntry(payload.entryName)
                                    zipOut.putNextEntry(zipEntry)
                                    val written = takagi.ru.monica.attachments.backup.PortableAttachmentBackup
                                        .writePayload(context, payload, zipOut)
                                    zipOut.closeEntry()
                                    if (written) {
                                        portableEntries += payload.entry
                                    } else {
                                        warnings.add("附件可迁移备份失败: ${payload.attachment.fileName}")
                                    }
                                }
                                val portableMetaFile = File(cacheBackupDir, "attachments_portable.json")
                                portableMetaFile.writeText(
                                    takagi.ru.monica.attachments.backup.PortableAttachmentBackup
                                        .encodeManifest(portableEntries),
                                    Charsets.UTF_8
                                )
                                addFileToZip(
                                    zipOut,
                                    portableMetaFile,
                                    takagi.ru.monica.attachments.backup.PortableAttachmentBackup.MANIFEST_ENTRY
                                )
                                portableMetaFile.delete()
                                android.util.Log.d(
                                    "WebDavHelper",
                                    "Backup portable attachments: ${portableEntries.size}/${localAttachments.size}"
                                )
                                if (portableEntries.size < localAttachments.size) {
                                    warnings.add("部分附件无法解密备份: ${localAttachments.size - portableEntries.size}个")
                                }
                            } else {
                                warnings.add("本地附件跨设备恢复需要启用备份加密；当前仅保留同机兼容附件备份")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Failed to backup attachments: ${e.message}")
                        warnings.add("附件备份失败: ${e.message}")
                    }
                }

                // 8. 加密
                if (shouldEncryptBackup) {
                    val encryptResult = EncryptionHelper.encryptFile(
                        zipFile,
                        finalFile,
                        checkNotNull(backupEncryptPassword),
                    )
                    if (encryptResult.isFailure) throw encryptResult.exceptionOrNull()!!
                }

                // 生成报告
                val totalImageCount = if (preferences.includeImages) extractAllImageFileNames(filteredSecureItems).size else 0
                val totalCounts = ItemCounts(
                    passwords = backupPasswordCandidates.size,
                    notes = noteItems.size,
                    totp = totpItems.size,
                    bankCards = cardWalletItems.count { it.itemType == ItemType.BANK_CARD },
                    documents = cardWalletItems.count { it.itemType == ItemType.DOCUMENT },
                    billingAddresses = cardWalletItems.count { it.itemType == ItemType.BILLING_ADDRESS },
                    paymentAccounts = cardWalletItems.count { it.itemType == ItemType.PAYMENT_ACCOUNT },
                    passkeys = totalPasskeyCount,
                    steamMaFiles = steamMaFileBackups.size,
                    images = totalImageCount
                )
                val successCounts = ItemCounts(
                    passwords = successPasswordCount,
                    notes = successNoteCount,
                    totp = totpItems.size,
                    bankCards = cardWalletItems.count { it.itemType == ItemType.BANK_CARD },
                    documents = cardWalletItems.count { it.itemType == ItemType.DOCUMENT },
                    billingAddresses = cardWalletItems.count { it.itemType == ItemType.BILLING_ADDRESS },
                    paymentAccounts = cardWalletItems.count { it.itemType == ItemType.PAYMENT_ACCOUNT },
                    passkeys = successPasskeyCount,
                    steamMaFiles = successSteamMaFileCount,
                    images = successImageCount
                )
                val report = BackupReport(
                    success = failedItems.isEmpty(),
                    totalItems = totalCounts,
                    successItems = successCounts,
                    failedItems = failedItems,
                    warnings = warnings
                )
                android.util.Log.d(
                    "WebDavHelper",
                    "Backup zip created: scope=$contentScope, " +
                        "passwords=${successCounts.passwords}/${totalCounts.passwords}, " +
                        "totp=${successCounts.totp}/${totalCounts.totp}, " +
                        "steamMaFiles=${successCounts.steamMaFiles}/${totalCounts.steamMaFiles}, " +
                        "notes=${successCounts.notes}/${totalCounts.notes}, warnings=${warnings.size}, failures=${failedItems.size}"
                )

                Result.success(Pair(finalFile, report))
            } finally {
                // 清理临时文件 (保留 finalFile 即 ZIP 文件)
                passwordsCsvFile.delete()
                foldersRootDir.deleteRecursively()
                historyJsonFile.delete()
                if (finalFile != zipFile) zipFile.delete()
                cacheBackupDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 创建并上传备份
     * 使用锁机制防止并发备份导致的内存溢出
     */
    suspend fun createAndUploadBackup(
        passwords: List<PasswordEntry>,
        secureItems: List<SecureItem>,
        preferences: BackupPreferences = getBackupPreferences(),
        isPermanent: Boolean = false,
        isManualTrigger: Boolean = true,  // 默认为手动触发
        contentScope: BackupContentScope = BackupContentScope.MONICA_LOCAL_ONLY
    ): Result<BackupReport> = withContext(Dispatchers.IO) {
        // 检查是否已有备份正在进行
        if (!backupLock.compareAndSet(false, true)) {
            android.util.Log.w("WebDavHelper", "Backup already in progress, ignoring request")
            return@withContext Result.failure(Exception("备份正在进行中，请稍候再试"))
        }
        
        try {
            android.util.Log.d(
                "WebDavHelper",
                "Starting backup with ${passwords.size} passwords and ${secureItems.size} secure items, scope=$contentScope"
            )
            
            // 调用重构后的创建方法
            val createResult = createBackupZip(
                passwords = passwords,
                secureItems = secureItems,
                preferences = preferences,
                contentScope = contentScope
            )
            
            if (createResult.isFailure) {
                return@withContext Result.failure(createResult.exceptionOrNull() ?: Exception("创建备份失败"))
            }

            val (backupFile, report) = createResult.getOrThrow()
            
            android.util.Log.d("WebDavHelper", "Backup file created: ${backupFile.length() / 1024}KB")

            try {
                if (!report.success || report.failedItems.isNotEmpty()) {
                    android.util.Log.e(
                        "WebDavHelper",
                        "Backup upload blocked because generated backup is incomplete: " +
                            "failures=${report.failedItems.size}, " +
                            "passwords=${report.successItems.passwords}/${report.totalItems.passwords}, " +
                            "totp=${report.successItems.totp}/${report.totalItems.totp}, " +
                            "notes=${report.successItems.notes}/${report.totalItems.notes}"
                    )
                    return@withContext Result.failure(
                        Exception("备份文件不完整，已阻止上传覆盖远端备份")
                    )
                }

                // 上传
                val uploadResult = uploadBackup(backupFile, isPermanent)
                
                if (uploadResult.isSuccess) {
                    updateLastBackupTime()

                    // Trigger cleanup after successful upload
                    cleanupBackups()
                    
                    // 记录 WebDAV 上传操作到时间线
                    val uploadDetails = mutableListOf<FieldChange>()
                    val backedUpCounts = report.successItems
                    if (backedUpCounts.passwords > 0) {
                        uploadDetails.add(FieldChange("密码", "", "${backedUpCounts.passwords}项"))
                    }
                    if (backedUpCounts.totp > 0) {
                        uploadDetails.add(FieldChange("验证器", "", "${backedUpCounts.totp}项"))
                    }
                    if (backedUpCounts.bankCards > 0) {
                        uploadDetails.add(FieldChange("卡片", "", "${backedUpCounts.bankCards}项"))
                    }
                    if (backedUpCounts.notes > 0) {
                        uploadDetails.add(FieldChange("笔记", "", "${backedUpCounts.notes}项"))
                    }
                    if (backedUpCounts.documents > 0) {
                        uploadDetails.add(FieldChange("证件", "", "${backedUpCounts.documents}项"))
                    }
                    if (backedUpCounts.billingAddresses > 0) {
                        uploadDetails.add(FieldChange("账单地址", "", "${backedUpCounts.billingAddresses}项"))
                    }
                    if (backedUpCounts.paymentAccounts > 0) {
                        uploadDetails.add(FieldChange("支付方式", "", "${backedUpCounts.paymentAccounts}项"))
                    }
                    OperationLogger.logWebDavUpload(
                        isAutomatic = !isManualTrigger,
                        isPermanent = isPermanent,
                        details = uploadDetails
                    )

                    // 更新报告状态为 true (如果之前没有失败项)
                    val finalReport = report.copy(success = report.success)
                    Result.success(finalReport)
                } else {
                    Result.failure(uploadResult.exceptionOrNull() ?: Exception("上传失败"))
                }
            } finally {
                // 上传完成后删除生成的 ZIP 文件
                backupFile.delete()
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("WebDavHelper", "Out of memory during backup", e)
            System.gc()
            Result.failure(Exception("内存不足，请先压缩图片后再试"))
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Backup failed", e)
            Result.failure(Exception("备份过程失败: ${e.message}"))
        } finally {
            // 释放备份锁
            backupLock.set(false)
            android.util.Log.d("WebDavHelper", "Backup lock released")
        }
    }
    
    /**
     * 导出密码到CSV文件
     * @param passwords 密码条目列表
     * @param file 目标CSV文件
     * @param customFieldsMap 密码ID到自定义字段列表的映射（可选）
     */
    private fun exportPasswordsToCSV(
        passwords: List<PasswordEntry>, 
        file: File,
        customFieldsMap: Map<Long, List<CustomFieldBackupEntry>> = emptyMap()
    ) {
        file.outputStream().use { output ->
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                // 写入BOM标记
                writer.write("\uFEFF")
                
                // 写入列标题（包含自定义字段列）
                writer.write("name,url,username,password,note,email,phone,custom_fields")
                writer.newLine()
                
                val json = Json { prettyPrint = false }
                
                // 写入数据行
                passwords.forEach { entry ->
                    val displayName = entry.title.ifBlank { entry.website.ifBlank { entry.username } }
                    
                    // 序列化自定义字段为JSON
                    val customFieldsJson = try {
                        val fields = customFieldsMap[entry.id] ?: emptyList()
                        if (fields.isEmpty()) {
                            ""
                        } else {
                            json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(CustomFieldBackupEntry.serializer()),
                                fields
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebDavHelper", "Failed to serialize custom fields for entry ${entry.id}")
                        ""
                    }
                    
                    val row = listOf(
                        escapeCsvField(displayName),
                        escapeCsvField(entry.website),
                        escapeCsvField(entry.username),
                        escapeCsvField(entry.password),
                        escapeCsvField(buildPasswordNoteWithMetadata(entry)),
                        escapeCsvField(entry.email),
                        escapeCsvField(entry.phone),
                        escapeCsvField(customFieldsJson)
                    )
                    writer.write(row.joinToString(","))
                    writer.newLine()
                }
            }
        }
    }
    
    /**
     * 转义CSV字段
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }
    
    /**
     * 从安全项目列表中提取所有图片文件名
     */
    private fun extractAllImageFileNames(secureItems: List<SecureItem>): Set<String> {
        val imageFileNames = mutableSetOf<String>()
        val json = Json { ignoreUnknownKeys = true }
        
        secureItems.forEach { item ->
            try {
                if (!item.imagePaths.isNullOrBlank()) {
                    val imagePathsArray = json.parseToJsonElement(item.imagePaths).jsonArray
                    imagePathsArray.forEach { element ->
                        val imagePath = element.jsonPrimitive.content
                        if (imagePath.endsWith(".enc")) {
                            imageFileNames.add(imagePath)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("WebDavHelper", "Failed to parse imagePaths for item ${item.id}: ${e.message}")
            }
        }
        
        return imageFileNames
    }

    private suspend fun createSteamMaFileBackups(
        securityManager: SecurityManager
    ): List<SteamMaFileBackupEntry> {
        val repository = SteamAccountRepository(
            SteamDatabase.getDatabase(context).steamAccountDao(),
            securityManager
        )
        return repository.getAccounts()
            .filter { it.steamId.isNotBlank() && it.sharedSecret.isNotBlank() }
            .map { account ->
                SteamMaFileBackupEntry(
                    fileName = SteamMaFileBackupCodec.fileName(account),
                    content = SteamMaFileBackupCodec.encode(account)
                )
            }
    }

    private fun isSteamMaFileBackupEntry(normalizedEntryName: String): Boolean {
        val lowerName = normalizedEntryName.lowercase(Locale.ROOT)
        return lowerName.startsWith("$STEAM_MAFILE_BACKUP_DIR/") &&
            (lowerName.endsWith(".mafile") || lowerName.endsWith(".json"))
    }

    private fun restoreSteamMaFilePayload(file: File): SteamMaFilePayload? {
        return runCatching {
            SteamMaFileParser().parse(
                maFileContent = file.readText(Charsets.UTF_8),
                fileName = file.name
            )
        }.onFailure { error ->
            android.util.Log.w("WebDavHelper", "Failed to parse Steam maFile ${file.name}: ${error.message}")
        }.getOrNull()
    }
    
    /**
     * 异常：需要密码
     */
    class PasswordRequiredException : Exception("备份文件已加密，请提供解密密码")

    /**
     * 异常：备份中包含 Monica 配置，需由上层明确是否覆盖本地配置
     */
    class MonicaConfigDecisionRequiredException(
        val configEntries: List<String>
    ) : Exception("检测到 Monica 配置，请确认是否覆盖本地配置")

    private fun normalizeBackupEntryName(entryName: String): String {
        return entryName.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
    }

    private fun isMonicaConfigEntry(normalizedEntryName: String, rawEntryName: String): Boolean {
        if (normalizedEntryName.startsWith("monica_config/")) {
            return true
        }
        val rawName = rawEntryName.substringAfterLast('/').lowercase(Locale.ROOT)
        return rawName == "webdav_config.json" ||
            rawName == "bitwarden_vaults.json" ||
            rawName == "keepass_webdav_config.json"
    }

    private fun detectMonicaConfigEntries(zipFile: File): List<String> {
        val entries = linkedSetOf<String>()
        ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val normalizedEntryName = normalizeBackupEntryName(entry.name)
                if (isMonicaConfigEntry(normalizedEntryName, entry.name)) {
                    entries += normalizedEntryName
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return entries.toList()
    }

    private suspend fun clearLocalDataForOverwriteRestore(
        backupFileName: String,
        clearSteamAccounts: Boolean = false
    ): Result<Unit> {
        return try {
            android.util.Log.d(
                "WebDavHelper",
                "Overwrite restore validated, clearing Monica local data only: file=$backupFileName, " +
                    "clearSteamAccounts=$clearSteamAccounts"
            )
            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
            database.passwordEntryDao().deleteAllLocalPasswordEntries()
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.TOTP)
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.BANK_CARD)
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.DOCUMENT)
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.BILLING_ADDRESS)
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.PAYMENT_ACCOUNT)
            database.secureItemDao().deleteAllLocalItemsByType(takagi.ru.monica.data.ItemType.NOTE)
            database.passkeyDao().deleteAllLocalPasskeys()
            if (clearSteamAccounts) {
                SteamDatabase.getDatabase(context).steamAccountDao().deleteAll()
            }
            android.util.Log.d("WebDavHelper", "Monica local data cleared successfully for overwrite restore")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Failed to clear Monica local data: ${e.message}")
            Result.failure(Exception("无法清除 Monica 本地数据: ${e.message}"))
        }
    }

    /**
     * 从备份文件恢复数据 (通用方法，用于 WebDAV 下载后恢复和本地导入)
     * @param backupFile 本地备份文件（ZIP）
     * @param decryptPassword 解密密码 (如果文件已加密)
     * @return Result<RestoreResult> 包含恢复的数据和报告
     */
    suspend fun restoreFromBackupFile(
        backupFile: File,
        decryptPassword: String? = null,
        overwrite: Boolean = false,
        restoreMonicaConfig: Boolean? = true,
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        try {
            // P0修复：错误跟踪
            val failedItems = mutableListOf<FailedItem>()
            val warnings = mutableListOf<String>()
            var backupPasswordCount = 0
            var backupNoteCount = 0
            var backupTotpCount = 0 // 目前没有统计CSV中的TOTP，可以在CSV导入时统计，这里先保留变量
            var backupCardCount = 0
            var backupDocCount = 0
            var backupBillingAddressCount = 0
            var backupPaymentAccountCount = 0
            var backupImageCount = 0
            var backupPasskeyCount = 0
            var backupSteamMaFileCount = 0
            var restoredPasswordCount = 0
            var restoredNoteCount = 0
            var restoredCardCount = 0
            var restoredDocCount = 0
            var restoredBillingAddressCount = 0
            var restoredPaymentAccountCount = 0
            var restoredImageCount = 0
            var restoredPasskeyCount = 0
            var missingPasskeyPrivateKeyCount = 0
            var restoredSteamMaFileCount = 0
            var detectedMonicaConfigEntries = emptyList<String>()

            // 1. 检测是否加密
            val isEncrypted = EncryptionHelper.isEncryptedFile(backupFile)
            val resolvedDecryptPassword = if (isEncrypted) {
                (decryptPassword ?: encryptionPassword).takeIf { it.isNotEmpty() }
            } else {
                decryptPassword
            }
            
            // 2. 解密文件 (如果需要)
            val zipFile = if (isEncrypted) {
                val password = resolvedDecryptPassword
                if (password == null) {
                    return@withContext Result.failure(PasswordRequiredException())
                }
                
                val decryptedFile = File(context.cacheDir, "restore_decrypted_${System.nanoTime()}.zip")
                val decryptResult = EncryptionHelper.decryptFile(backupFile, decryptedFile, password)
                
                if (decryptResult.isFailure) {
                    return@withContext Result.failure(decryptResult.exceptionOrNull() 
                        ?: Exception("解密失败"))
                }
                
                android.util.Log.d("WebDavHelper", "Backup decrypted successfully")
                decryptedFile
            } else {
                backupFile
            }

            detectedMonicaConfigEntries = runCatching {
                detectMonicaConfigEntries(zipFile)
            }.onFailure { error ->
                android.util.Log.w("WebDavHelper", "Failed to detect Monica config entries: ${error.message}")
                warnings.add("Monica配置检测失败，按默认恢复策略继续")
            }.getOrDefault(emptyList())

            if (detectedMonicaConfigEntries.isNotEmpty() && restoreMonicaConfig == null) {
                return@withContext Result.failure(
                    MonicaConfigDecisionRequiredException(detectedMonicaConfigEntries)
                )
            }

            val shouldRestoreMonicaConfig = restoreMonicaConfig != false

            if (!shouldRestoreMonicaConfig && detectedMonicaConfigEntries.isNotEmpty()) {
                warnings.add("已跳过 Monica 配置恢复: ${detectedMonicaConfigEntries.size}项")
            }

            val keepassRestoreTempDir = File(
                context.cacheDir,
                "restore_keepass_${System.nanoTime()}",
            )

            try {
                val passwordsWithMetadata = mutableListOf<Pair<PasswordEntry, String?>>()  // ✅ 存储密码和分类名称
                val notesWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val totpWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val bankCardsWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val documentsWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val billingAddressesWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val paymentAccountsWithMetadata = mutableListOf<Pair<DataExportImportManager.ExportItem, String?>>()
                val passkeysWithMetadata = mutableListOf<Pair<PasskeyEntry, String?>>()
                val passwords = mutableListOf<PasswordEntry>()
                val secureItems = mutableListOf<DataExportImportManager.ExportItem>()
                val passkeys = mutableListOf<PasskeyEntry>()
                val steamMaFiles = mutableListOf<SteamMaFilePayload>()
                val passwordHistory = mutableListOf<PasswordHistoryBackupEntry>()
                val pendingKeePassMetadata = mutableMapOf<Long, KeePassDatabaseBackupEntry>()
                val pendingKeePassKeyFiles = mutableMapOf<Long, ByteArray>()
                val pendingKeePassDatabases = mutableMapOf<Long, File>()
                val invalidKeePassRestoreIds = mutableSetOf<Long>()
                val pendingAttachments = mutableListOf<takagi.ru.monica.attachments.backup.AttachmentBackupCodec.Entry>()
                val pendingPortableAttachmentEntries =
                    mutableListOf<takagi.ru.monica.attachments.backup.PortableAttachmentBackup.Entry>()
                val pendingPortableAttachmentPayloads = mutableMapOf<String, File>()
                
                // 临时存储CSV文件路径，延后处理
                var passwordsCsvFile: File? = null
                val secureCsvFiles = mutableListOf<File>()
                
                // 3. 解压ZIP文件并读取JSON/CSV、密码历史和图片
                ZipInputStream(FileInputStream(zipFile)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        // 防止Zip Slip漏洞
                        val entryName = entry.name.substringAfterLast('/')
                        if (entryName.contains("..")) {
                             android.util.Log.w("WebDavHelper", "Skipping unsafe zip entry: ${entry.name}")
                             entry = zipIn.nextEntry
                             continue
                        }

                        val tempFile = File(context.cacheDir, "restore_${System.nanoTime()}_${entryName}")
                        FileOutputStream(tempFile).use { fileOut ->
                            zipIn.copyTo(fileOut)
                        }
                        zipIn.closeEntry()
                        
                        try {
                            // Normalize path separators for Windows compatibility
                            val normalizedEntryName = entry.name.replace('\\', '/')
                            val keePassEntry = KeePassBackupEntryPolicy.parse(normalizedEntryName)
                            
                            when {
                                !shouldRestoreMonicaConfig && isMonicaConfigEntry(
                                    normalizeBackupEntryName(normalizedEntryName),
                                    entryName,
                                ) -> {
                                    // 用户选择不覆盖 Monica 配置，跳过该类条目。
                                }
                                // 优先收集JSON格式的密码文件
                                normalizedEntryName.contains("/passwords/") || normalizedEntryName.startsWith("passwords/") -> {
                                    backupPasswordCount++
                                    val result = restorePasswordFromJson(tempFile)
                                    if (result != null) {
                                        passwordsWithMetadata.add(result)  // ✅ 存储密码、分类名称和TOTP密钥
                                        restoredPasswordCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "密码",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                // 保存CSV文件路径，稍后处理（向后兼容）
                                entryName.equals("passwords.csv", ignoreCase = true) ||
                                    (entryName.startsWith("Monica_", ignoreCase = true) && entryName.endsWith("_password.csv", ignoreCase = true)) -> {
                                    // 只在没有JSON密码时才使用CSV
                                    if (passwordsCsvFile == null) {
                                        passwordsCsvFile = tempFile
                                        // 标记不需要删除，因为要留到后面处理
                                        // 但要注意在这个when块结束后 tempFile会被删除，所以这里需要复制一份或者不删除
                                        // 这里的逻辑稍微调整下：如果不立即处理，就不删除 tempFile
                                    } else {
                                        // 已经有一个了，这个忽略
                                        tempFile.delete()
                                    }
                                }
                                entryName.equals("secure_items.csv", ignoreCase = true) ||
                                    entryName.equals("backup.csv", ignoreCase = true) ||
                                    (entryName.startsWith("Monica_", ignoreCase = true) && entryName.endsWith("_other.csv", ignoreCase = true)) ||
                                    (entryName.startsWith("Monica_", ignoreCase = true) && entryName.endsWith("_totp.csv", ignoreCase = true)) ||
                                    (entryName.startsWith("Monica_", ignoreCase = true) && entryName.endsWith("_cards_docs.csv", ignoreCase = true)) ||
                                    (entryName.startsWith("Monica_", ignoreCase = true) && entryName.endsWith("_notes.csv", ignoreCase = true)) -> {
                                    secureCsvFiles.add(tempFile)
                                }
                                normalizedEntryName.contains("/notes/") || normalizedEntryName.startsWith("notes/") -> {
                                    backupNoteCount++
                                    val noteItem = restoreNoteFromJson(tempFile)
                                    if (noteItem != null) {
                                        notesWithMetadata.add(noteItem)
                                        restoredNoteCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "笔记",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                normalizedEntryName.contains("/bank_cards/") || normalizedEntryName.startsWith("bank_cards/") -> {
                                    backupCardCount++
                                    val bankCardItem = restoreCardWalletItemFromJson(tempFile, ItemType.BANK_CARD)
                                    if (bankCardItem != null) {
                                        bankCardsWithMetadata.add(bankCardItem)
                                        restoredCardCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "卡片",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                normalizedEntryName.contains("/documents/") || normalizedEntryName.startsWith("documents/") -> {
                                    backupDocCount++
                                    val documentItem = restoreCardWalletItemFromJson(tempFile, ItemType.DOCUMENT)
                                    if (documentItem != null) {
                                        documentsWithMetadata.add(documentItem)
                                        restoredDocCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "证件",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                normalizedEntryName.contains("/billing_addresses/") || normalizedEntryName.startsWith("billing_addresses/") -> {
                                    backupBillingAddressCount++
                                    val billingAddressItem = restoreCardWalletItemFromJson(tempFile, ItemType.BILLING_ADDRESS)
                                    if (billingAddressItem != null) {
                                        billingAddressesWithMetadata.add(billingAddressItem)
                                        restoredBillingAddressCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "账单地址",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                normalizedEntryName.contains("/payment_accounts/") || normalizedEntryName.startsWith("payment_accounts/") -> {
                                    backupPaymentAccountCount++
                                    val paymentAccountItem = restoreCardWalletItemFromJson(tempFile, ItemType.PAYMENT_ACCOUNT)
                                    if (paymentAccountItem != null) {
                                        paymentAccountsWithMetadata.add(paymentAccountItem)
                                        restoredPaymentAccountCount++
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "支付方式",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                isSteamMaFileBackupEntry(normalizedEntryName) -> {
                                    backupSteamMaFileCount++
                                    val payload = restoreSteamMaFilePayload(tempFile)
                                    if (payload != null) {
                                        steamMaFiles.add(payload)
                                        restoredSteamMaFileCount++
                                    } else {
                                        failedItems.add(
                                            FailedItem(
                                                id = 0,
                                                type = STEAM_MAFILE_BACKUP_TYPE,
                                                title = entryName,
                                                reason = "maFile解析失败"
                                            )
                                        )
                                    }
                                }
                                normalizedEntryName.contains("/authenticators/") ||
                                    normalizedEntryName.startsWith("authenticators/") ||
                                    normalizedEntryName.contains("/totp/") ||
                                    normalizedEntryName.startsWith("totp/") -> {
                                    backupTotpCount++
                                    val totpItem = restoreTotpFromJson(tempFile)
                                    if (totpItem != null) {
                                        totpWithMetadata.add(totpItem)
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "验证器",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                normalizedEntryName.contains("/passkeys/") || normalizedEntryName.startsWith("passkeys/") -> {
                                    backupPasskeyCount++
                                    val passkey = restorePasskeyFromJson(tempFile)
                                    if (passkey != null) {
                                        passkeysWithMetadata.add(passkey)
                                        if (passkey.first.syncStatus == "REFERENCE") {
                                            missingPasskeyPrivateKeyCount++
                                        } else {
                                            restoredPasskeyCount++
                                        }
                                    } else {
                                        failedItems.add(FailedItem(
                                            id = 0,
                                            type = "通行密钥",
                                            title = entryName,
                                            reason = "JSON解析失败"
                                        ))
                                    }
                                }
                                entryName.equals("password_history.json", ignoreCase = true) -> {
                                    try {
                                        val historyJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        passwordHistory += json.decodeFromString(
                                            kotlinx.serialization.builtins.ListSerializer(PasswordHistoryBackupEntry.serializer()),
                                            historyJson
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore password history: ${e.message}")
                                        warnings.add("历史密码恢复失败: ${e.message}")
                                    }
                                }
                                entryName.endsWith("_generated_history.json", ignoreCase = true) -> {
                                    // 恢复密码生成历史
                                    try {
                                        val historyJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val history = json.decodeFromString<List<takagi.ru.monica.data.PasswordGenerationHistory>>(historyJson)
                                        val historyManager = PasswordHistoryManager(context)
                                        historyManager.importHistory(history)
                                        android.util.Log.d("WebDavHelper", "Restored ${history.size} password generation history entries")
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore password generation history: ${e.message}")
                                        warnings.add("密码生成历史恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复分类数据
                                entryName.equals("categories.json", ignoreCase = true) -> {
                                    try {
                                        val categoriesJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val categoryBackups = json.decodeFromString<List<CategoryBackupEntry>>(categoriesJson)
                                        
                                        val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                                        val categoryDao = database.categoryDao()
                                        
                                        // 导入分类（保持原ID以保持与密码的关联）
                                        categoryBackups.forEach { backup ->
                                            try {
                                                // 检查分类是否已存在
                                                val existingCategories = categoryDao.getAllCategories().first()
                                                val exists = existingCategories.any { it.id == backup.id || it.name == backup.name }
                                                if (!exists) {
                                                    categoryDao.insert(takagi.ru.monica.data.Category(
                                                        id = backup.id,
                                                        name = backup.name,
                                                        sortOrder = backup.sortOrder
                                                    ))
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.w("WebDavHelper", "Failed to import category ${backup.name}: ${e.message}")
                                            }
                                        }
                                        android.util.Log.d("WebDavHelper", "Restored ${categoryBackups.size} categories")
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore categories: ${e.message}")
                                        warnings.add("分类恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复操作历史记录 (时间线)
                                entryName.equals("timeline_history.json", ignoreCase = true) -> {
                                    try {
                                        val timelineJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val logBackups = json.decodeFromString<List<OperationLogBackupEntry>>(timelineJson)
                                        
                                        if (logBackups.isNotEmpty()) {
                                            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                                            val operationLogDao = database.operationLogDao()
                                            
                                            // 获取现有日志的时间戳用于去重
                                            val existingLogs = operationLogDao.getAllLogsSync()
                                            val existingTimestamps = existingLogs.map { it.timestamp }.toSet()
                                            
                                            // 只导入不存在的日志
                                            val newLogs = logBackups.filter { backup ->
                                                backup.timestamp !in existingTimestamps
                                            }.map { backup ->
                                                takagi.ru.monica.data.OperationLog(
                                                    id = 0, // 使用新ID，避免冲突
                                                    itemType = backup.itemType,
                                                    itemId = backup.itemId,
                                                    itemTitle = backup.itemTitle,
                                                    operationType = backup.operationType,
                                                    changesJson = backup.changesJson,
                                                    deviceId = backup.deviceId,
                                                    deviceName = backup.deviceName,
                                                    timestamp = backup.timestamp,
                                                    isReverted = backup.isReverted
                                                )
                                            }
                                            
                                            if (newLogs.isNotEmpty()) {
                                                operationLogDao.insertAll(newLogs)
                                                android.util.Log.d("WebDavHelper", "Restored ${newLogs.size} new timeline entries (${logBackups.size - newLogs.size} duplicates skipped)")
                                            } else {
                                                android.util.Log.d("WebDavHelper", "All ${logBackups.size} timeline entries already exist, skipped")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore timeline: ${e.message}")
                                        warnings.add("操作历史恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复回收站数据 - 密码
                                normalizedEntryName.contains("/trash/") && entryName.equals("trash_passwords.json", ignoreCase = true) -> {
                                    try {
                                        val trashJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val trashPasswordBackups = json.decodeFromString<List<TrashPasswordBackupEntry>>(trashJson)
                                        
                                        if (trashPasswordBackups.isNotEmpty()) {
                                            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                                            val passwordEntryDao = database.passwordEntryDao()
                                            
                                            // 获取现有的已删除密码用于去重
                                            val existingDeletedPasswords = passwordEntryDao.getDeletedEntriesSync()
                                            val existingTitles = existingDeletedPasswords.map { "${it.title}_${it.createdAt.time}" }.toSet()
                                            
                                            var importedCount = 0
                                            trashPasswordBackups.forEach { backup ->
                                                val key = "${backup.title}_${backup.createdAt}"
                                                if (key !in existingTitles) {
                                                    try {
                                                        val entry = PasswordEntry(
                                                            id = 0, // 使用新ID
                                                            title = backup.title,
                                                            username = backup.username,
                                                            password = backup.password,
                                                            website = backup.website,
                                                            notes = backup.notes,
                                                            isFavorite = backup.isFavorite,
                                                            categoryId = backup.categoryId,
                                                            email = backup.email,
                                                            phone = backup.phone,
                                                            createdAt = java.util.Date(backup.createdAt),
                                                            updatedAt = java.util.Date(backup.updatedAt),
                                                            authenticatorKey = backup.authenticatorKey,
                                                            passkeyBindings = backup.passkeyBindings,
                                                            sshKeyData = backup.sshKeyData,
                                                            isDeleted = true,
                                                            deletedAt = backup.deletedAt?.let { java.util.Date(it) },
                                                            // ✅ 第三方登录(SSO)字段
                                                            loginType = backup.loginType,
                                                            ssoProvider = backup.ssoProvider,
                                                            ssoRefEntryId = backup.ssoRefEntryId,
                                                            customIconType = backup.customIconType,
                                                            customIconValue = backup.customIconValue,
                                                            customIconUpdatedAt = backup.customIconUpdatedAt
                                                        )
                                                        passwordEntryDao.insertPasswordEntry(entry)
                                                        importedCount++
                                                    } catch (e: Exception) {
                                                        android.util.Log.w("WebDavHelper", "Failed to restore trash password: ${e.message}")
                                                    }
                                                }
                                            }
                                            android.util.Log.d("WebDavHelper", "Restored $importedCount trash passwords (${trashPasswordBackups.size - importedCount} duplicates skipped)")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore trash passwords: ${e.message}")
                                        warnings.add("回收站密码恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复回收站数据 - 安全项目
                                normalizedEntryName.contains("/trash/") && entryName.equals("trash_secure_items.json", ignoreCase = true) -> {
                                    try {
                                        val trashJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val trashSecureItemBackups = json.decodeFromString<List<TrashSecureItemBackupEntry>>(trashJson)
                                        
                                        if (trashSecureItemBackups.isNotEmpty()) {
                                            val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                                            val secureItemDao = database.secureItemDao()
                                            
                                            // 获取现有的已删除安全项用于去重
                                            val existingDeletedItems = secureItemDao.getDeletedItemsSync()
                                            val existingKeys = existingDeletedItems.map { "${it.title}_${it.createdAt.time}" }.toSet()
                                            
                                            var importedCount = 0
                                            trashSecureItemBackups.forEach { backup ->
                                                val key = "${backup.title}_${backup.createdAt}"
                                                if (key !in existingKeys) {
                                                    try {
                                                        val itemType = SecureItemRestoreTypeResolver.resolve(
                                                            rawType = backup.itemType,
                                                            itemData = backup.itemData
                                                        ) ?: ItemType.NOTE
                                                        val item = SecureItem(
                                                            id = 0, // 使用新ID
                                                            title = backup.title,
                                                            itemType = itemType,
                                                            itemData = backup.itemData,
                                                            notes = backup.notes,
                                                            isFavorite = backup.isFavorite,
                                                            imagePaths = backup.imagePaths,
                                                            createdAt = java.util.Date(backup.createdAt),
                                                            updatedAt = java.util.Date(backup.updatedAt),
                                                            isDeleted = true,
                                                            deletedAt = backup.deletedAt?.let { java.util.Date(it) },
                                                            categoryId = backup.categoryId
                                                        )
                                                        secureItemDao.insertItem(item)
                                                        importedCount++
                                                    } catch (e: Exception) {
                                                        android.util.Log.w("WebDavHelper", "Failed to restore trash item: ${e.message}")
                                                    }
                                                }
                                            }
                                            android.util.Log.d("WebDavHelper", "Restored $importedCount trash secure items (${trashSecureItemBackups.size - importedCount} duplicates skipped)")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore trash secure items: ${e.message}")
                                        warnings.add("回收站项目恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName.contains("/password_icons/") || normalizedEntryName.startsWith("password_icons/") -> {
                                    try {
                                        val iconDir = File(context.filesDir, "password_icons")
                                        if (!iconDir.exists()) {
                                            iconDir.mkdirs()
                                        }
                                        val destFile = File(iconDir, entryName)
                                        tempFile.copyTo(destFile, overwrite = true)
                                    } catch (e: Exception) {
                                        warnings.add("自定义图标恢复失败: $entryName - ${e.message}")
                                    }
                                }
                                normalizedEntryName.startsWith(
                                    "${takagi.ru.monica.attachments.backup.PortableAttachmentBackup.DIR_NAME}/"
                                ) -> {
                                    try {
                                        if (normalizedEntryName == takagi.ru.monica.attachments.backup.PortableAttachmentBackup.MANIFEST_ENTRY) {
                                            val manifestText = tempFile.readText(Charsets.UTF_8)
                                            val manifest = takagi.ru.monica.attachments.backup
                                                .PortableAttachmentBackup.decodeManifest(manifestText)
                                            pendingPortableAttachmentEntries.addAll(manifest.entries.filter { it.isValid() })
                                            android.util.Log.d(
                                                "WebDavHelper",
                                                "Collected portable attachments manifest: ${manifest.entries.size} entries"
                                            )
                                        } else {
                                            pendingPortableAttachmentPayloads[normalizedEntryName] = tempFile
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "WebDavHelper",
                                            "Failed to restore portable attachment entry $entryName: ${e.message}"
                                        )
                                        warnings.add("可迁移附件恢复失败: $entryName - ${e.message}")
                                    }
                                }
                                normalizedEntryName.contains("/attachments/") || normalizedEntryName.startsWith("attachments/") -> {
                                    // 附件恢复：`attachments/<uuid>.enc` + `attachments/attachments_meta.json`
                                    // 对应 spec Requirement 9.5 / 9.6
                                    // 注意：密码的 id 在恢复时会重新分配，因此这里只落地 blob + 收集元数据，
                                    // 真正的 attachments 表 upsert 等 BackupRestoreApplier 拿到 passwordIdMap 后再做。
                                    try {
                                        val leafName = entryName.substringAfterLast('/')
                                        if (leafName.equals("attachments_meta.json", ignoreCase = true)) {
                                            val manifestText = tempFile.readText(Charsets.UTF_8)
                                            val manifest = takagi.ru.monica.attachments.backup
                                                .AttachmentBackupCodec.decode(manifestText)
                                            pendingAttachments.addAll(manifest.entries)
                                            android.util.Log.d(
                                                "WebDavHelper",
                                                "Collected attachments manifest: ${manifest.entries.size} entries"
                                            )
                                        } else if (leafName.endsWith(".enc")) {
                                            val storageDir = File(context.filesDir, "secure_attachments")
                                            if (!storageDir.exists()) storageDir.mkdirs()
                                            val destFile = File(storageDir, leafName)
                                            tempFile.copyTo(destFile, overwrite = true)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "WebDavHelper",
                                            "Failed to restore attachment entry $entryName: ${e.message}"
                                        )
                                        warnings.add("附件恢复失败: $entryName - ${e.message}")
                                    }
                                }
                                normalizedEntryName.contains("/images/") || entryName.endsWith(".enc") -> {
                                    backupImageCount++
                                    // 恢复图片文件
                                    try {
                                        val imageDir = File(context.filesDir, "secure_images")
                                        if (!imageDir.exists()) {
                                            imageDir.mkdirs()
                                        }
                                        val destFile = File(imageDir, entryName)
                                        tempFile.copyTo(destFile, overwrite = true)
                                        android.util.Log.d("WebDavHelper", "Restored image file: $entryName")
                                        restoredImageCount++  // P0修复：记录成功
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore image file $entryName: ${e.message}")
                                        // P0修复：记录失败
                                        warnings.add("图片恢复失败: $entryName - ${e.message}")
                                    }
                                }
                                // ✅ 恢复常用账号信息（兼容旧根目录与新的 monica_config 目录）
                                normalizedEntryName == "monica_config/common_account.json" ||
                                    entryName.equals("common_account.json", ignoreCase = true) -> {
                                    try {
                                        val commonAccountJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val commonAccountBackup = json.decodeFromString<CommonAccountBackupEntry>(commonAccountJson)
                                        
                                        val commonAccountPreferences = takagi.ru.monica.data.CommonAccountPreferences(context)
                                        val currentInfo = commonAccountPreferences.commonAccountInfo.first()
                                        
                                        // 只有当本地对应字段为空时才恢复，保护用户本地更新的数据
                                        if (currentInfo.email.isEmpty() && commonAccountBackup.email.isNotEmpty()) {
                                            commonAccountPreferences.setDefaultEmail(commonAccountBackup.email)
                                        }
                                        if (currentInfo.phone.isEmpty() && commonAccountBackup.phone.isNotEmpty()) {
                                            commonAccountPreferences.setDefaultPhone(commonAccountBackup.phone)
                                        }
                                        if (currentInfo.username.isEmpty() && commonAccountBackup.username.isNotEmpty()) {
                                            commonAccountPreferences.setDefaultUsername(commonAccountBackup.username)
                                        }
                                        // autoFillEnabled 只有在当前未启用且备份启用时才恢复
                                        if (!currentInfo.autoFillEnabled && commonAccountBackup.autoFillEnabled) {
                                            commonAccountPreferences.setAutoFillEnabled(true)
                                        }
                                        if (currentInfo.billingAddress.isEmpty() && commonAccountBackup.billingAddress.isNotBlank()) {
                                            val restoredBillingAddress = CardWalletDataCodec.parseBillingAddress(
                                                commonAccountBackup.billingAddress
                                            )
                                            if (!restoredBillingAddress.isEmpty()) {
                                                commonAccountPreferences.setBillingAddress(restoredBillingAddress)
                                            }
                                        }

                                        // 恢复模板：采用合并模式，不覆盖本地，仅补充缺失模板
                                        val currentTemplates = commonAccountPreferences.templatesFlow.first()
                                        val backupTemplates = commonAccountBackup.templates.mapNotNull { backupTemplate ->
                                            val content = backupTemplate.content.trim()
                                            if (content.isEmpty()) return@mapNotNull null
                                            takagi.ru.monica.data.CommonAccountTemplate(
                                                id = backupTemplate.id.trim().ifEmpty {
                                                    java.util.UUID.randomUUID().toString()
                                                },
                                                type = backupTemplate.type.trim(),
                                                title = "",
                                                content = content
                                            ).normalized()
                                        }
                                        if (backupTemplates.isNotEmpty()) {
                                            val mergedTemplates = (currentTemplates + backupTemplates)
                                                .map { it.normalized() }
                                                .filter { it.content.isNotBlank() }
                                                .distinctBy {
                                                    "${it.type.trim().lowercase(Locale.ROOT)}|" +
                                                        it.content.trim().lowercase(Locale.ROOT)
                                                }
                                            if (mergedTemplates != currentTemplates) {
                                                commonAccountPreferences.setTemplates(mergedTemplates)
                                            }
                                        }
                                        
                                        android.util.Log.d(
                                            "WebDavHelper",
                                            "Restored common account info (merge mode, templates=${commonAccountBackup.templates.size})"
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore common account info: ${e.message}")
                                        warnings.add("常用账号信息恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复 Monica 自动填充屏蔽字段配置
                                normalizedEntryName == "monica_config/autofill_blocked_fields.json" -> {
                                    try {
                                        val autofillConfigJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val autofillConfigBackup = json.decodeFromString<AutofillBlockedFieldsBackupEntry>(autofillConfigJson)
                                        val autofillBlockedFieldRecords = autofillConfigBackup.blockedFieldSignatures
                                            .mapNotNull { backup ->
                                                backup.signatureKey
                                                    .trim()
                                                    .takeIf { it.isNotBlank() }
                                                    ?.let { signatureKey ->
                                                        AutofillPreferences.BlockedFieldSignatureRecord(
                                                            signatureKey = signatureKey,
                                                            packageName = backup.packageName?.trim()?.ifBlank { null },
                                                            webDomain = backup.webDomain?.trim()?.ifBlank { null },
                                                            hints = backup.hints,
                                                            blockedAt = backup.blockedAt,
                                                        )
                                                    }
                                            }
                                        if (autofillBlockedFieldRecords.isNotEmpty()) {
                                            AutofillPreferences(context).importBlockedFieldSignatureRecords(autofillBlockedFieldRecords)
                                            warnings.add("✓ 非自动填充字段已恢复: ${autofillBlockedFieldRecords.size}项")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore blocked autofill fields: ${e.message}")
                                        warnings.add("非自动填充字段恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName == "monica_config/autofill_save_blocked_targets.json" -> {
                                    try {
                                        val saveBlockedTargetsJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val saveBlockedTargetsBackup =
                                            json.decodeFromString<AutofillSaveBlockedTargetsBackupEntry>(
                                                saveBlockedTargetsJson
                                            )
                                        if (saveBlockedTargetsBackup.blockedTargets.isNotEmpty()) {
                                            AutofillPreferences(context).importSaveBlockedTargets(
                                                saveBlockedTargetsBackup.blockedTargets,
                                            )
                                            warnings.add("✓ 不保存名单已恢复: ${saveBlockedTargetsBackup.blockedTargets.size}项")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore save-blocked targets: ${e.message}")
                                        warnings.add("不保存名单恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName == "monica_config/autofill_blacklist.json" -> {
                                    try {
                                        val autofillBlacklistJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val autofillBlacklistBackup =
                                            json.decodeFromString<AutofillBlacklistBackupEntry>(
                                                autofillBlacklistJson
                                            )
                                        val normalizedPackages = autofillBlacklistBackup.packages
                                            .mapNotNull { it.trim().takeIf { packageName -> packageName.isNotBlank() } }
                                            .toSet()
                                        AutofillPreferences(context).apply {
                                            setBlacklistEnabled(autofillBlacklistBackup.enabled)
                                            setBlacklistPackages(normalizedPackages)
                                        }
                                        warnings.add("✓ 自动填充黑名单已恢复: ${normalizedPackages.size}个应用")
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore autofill blacklist: ${e.message}")
                                        warnings.add("自动填充黑名单恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName == "monica_config/bitwarden_vaults.json" ||
                                    entryName.equals("bitwarden_vaults.json", ignoreCase = true) -> {
                                    try {
                                        val bitwardenVaultsJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val bitwardenVaultsBackup = json.decodeFromString<BitwardenVaultsBackupEntry>(bitwardenVaultsJson)
                                        val restoredCount = restoreBitwardenVaultBackups(
                                            bitwardenVaultsBackup.vaults,
                                            resolvedDecryptPassword,
                                        )
                                        if (restoredCount > 0) {
                                            warnings.add("✓ Bitwarden Vault已恢复: ${restoredCount}个（已锁定）")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore Bitwarden vaults: ${e.message}")
                                        warnings.add("Bitwarden Vault恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName == "monica_config/page_adjustment_settings.json" -> {
                                    try {
                                        val pageAdjustmentSettingsJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val pageAdjustmentBackup =
                                            json.decodeFromString<PageAdjustmentSettingsBackupEntry>(
                                                pageAdjustmentSettingsJson
                                            )
                                        SettingsManager(context).importPageAdjustmentSettings(
                                            PageAdjustmentSettingsSnapshot(
                                                passwordListQuickFiltersEnabled =
                                                    pageAdjustmentBackup.passwordListQuickFiltersEnabled,
                                                passwordListQuickFilterItems =
                                                    pageAdjustmentBackup.passwordListQuickFilterItems,
                                                passwordListCategoryQuickFiltersEnabled =
                                                    pageAdjustmentBackup.passwordListCategoryQuickFiltersEnabled,
                                                passwordListQuickFoldersEnabled =
                                                    pageAdjustmentBackup.passwordListQuickFoldersEnabled,
                                                passwordListQuickFolderStyle =
                                                    pageAdjustmentBackup.passwordListQuickFolderStyle,
                                                passwordListQuickFolderPathBannerEnabled =
                                                    pageAdjustmentBackup.passwordListQuickFolderPathBannerEnabled,
                                                passwordListSystemBackToParentFolderEnabled =
                                                    pageAdjustmentBackup.passwordListSystemBackToParentFolderEnabled,
                                                addButtonBehaviorMode =
                                                    pageAdjustmentBackup.addButtonBehaviorMode,
                                                addButtonMenuOrder =
                                                    pageAdjustmentBackup.addButtonMenuOrder,
                                                addButtonMenuEnabledActions =
                                                    pageAdjustmentBackup.addButtonMenuEnabledActions,
                                                passwordPageAggregateEnabled =
                                                    pageAdjustmentBackup.passwordPageAggregateEnabled,
                                                passwordPageVisibleContentTypes =
                                                    pageAdjustmentBackup.passwordPageVisibleContentTypes,
                                                categorySelectionUiMode =
                                                    pageAdjustmentBackup.categorySelectionUiMode,
                                                colorSettingsVersion =
                                                    pageAdjustmentBackup.colorSettingsVersion,
                                                oledPureBlackEnabled =
                                                    pageAdjustmentBackup.oledPureBlackEnabled,
                                                colorScheme =
                                                    pageAdjustmentBackup.colorScheme,
                                                customPrimaryColor =
                                                    pageAdjustmentBackup.customPrimaryColor,
                                                customSecondaryColor =
                                                    pageAdjustmentBackup.customSecondaryColor,
                                                customTertiaryColor =
                                                    pageAdjustmentBackup.customTertiaryColor,
                                                customNeutralColor =
                                                    pageAdjustmentBackup.customNeutralColor,
                                                customNeutralVariantColor =
                                                    pageAdjustmentBackup.customNeutralVariantColor,
                                                bottomNavSettingsVersion =
                                                    pageAdjustmentBackup.bottomNavSettingsVersion,
                                                bottomNavOrder =
                                                    pageAdjustmentBackup.bottomNavOrder,
                                                bottomNavVisibilityVaultV2 =
                                                    pageAdjustmentBackup.bottomNavVisibilityVaultV2,
                                                bottomNavVisibilityPasswords =
                                                    pageAdjustmentBackup.bottomNavVisibilityPasswords,
                                                bottomNavVisibilityAuthenticator =
                                                    pageAdjustmentBackup.bottomNavVisibilityAuthenticator,
                                                bottomNavVisibilityCardWallet =
                                                    pageAdjustmentBackup.bottomNavVisibilityCardWallet,
                                                bottomNavVisibilityGenerator =
                                                    pageAdjustmentBackup.bottomNavVisibilityGenerator,
                                                bottomNavVisibilityNotes =
                                                    pageAdjustmentBackup.bottomNavVisibilityNotes,
                                                bottomNavVisibilitySend =
                                                    pageAdjustmentBackup.bottomNavVisibilitySend,
                                                bottomNavVisibilityPasskey =
                                                    pageAdjustmentBackup.bottomNavVisibilityPasskey,
                                                bottomNavVisibilitySteam =
                                                    pageAdjustmentBackup.bottomNavVisibilitySteam,
                                                useDraggableBottomNav =
                                                    pageAdjustmentBackup.useDraggableBottomNav,
                                                autoHideBottomNavWhenSingleTab =
                                                    pageAdjustmentBackup.autoHideBottomNavWhenSingleTab,
                                                passwordListQuickAccessEnabled =
                                                    pageAdjustmentBackup.passwordListQuickAccessEnabled,
                                                passwordListTopModulesOrder =
                                                    pageAdjustmentBackup.passwordListTopModulesOrder,
                                                passwordCardDisplayMode =
                                                    pageAdjustmentBackup.passwordCardDisplayMode,
                                                passwordCardDisplayFields =
                                                    pageAdjustmentBackup.passwordCardDisplayFields,
                                                passwordCardShowAuthenticator =
                                                    pageAdjustmentBackup.passwordCardShowAuthenticator,
                                                passwordCardHideOtherContentWhenAuthenticator =
                                                    pageAdjustmentBackup.passwordCardHideOtherContentWhenAuthenticator,
                                                stackCardMode = pageAdjustmentBackup.stackCardMode,
                                                passwordGroupMode = pageAdjustmentBackup.passwordGroupMode,
                                                passwordWebsiteStackMatchMode =
                                                    pageAdjustmentBackup.passwordWebsiteStackMatchMode,
                                                authenticatorCardDisplayFields =
                                                    pageAdjustmentBackup.authenticatorCardDisplayFields,
                                                authenticatorCardHideCodeByDefault =
                                                    pageAdjustmentBackup.authenticatorCardHideCodeByDefault,
                                                authenticatorLayoutMode =
                                                    pageAdjustmentBackup.authenticatorLayoutMode,
                                                vaultV2LayoutMode =
                                                    pageAdjustmentBackup.vaultV2LayoutMode,
                                                validatorProgressBarStyle =
                                                    pageAdjustmentBackup.validatorProgressBarStyle,
                                                validatorUnifiedProgressBar =
                                                    pageAdjustmentBackup.validatorUnifiedProgressBar,
                                                validatorSmoothProgress =
                                                    pageAdjustmentBackup.validatorSmoothProgress,
                                                validatorVibrationEnabled =
                                                    pageAdjustmentBackup.validatorVibrationEnabled,
                                                copyNextCodeWhenExpiring =
                                                    pageAdjustmentBackup.copyNextCodeWhenExpiring,
                                                securityAnalysisAutoEnabled =
                                                    pageAdjustmentBackup.securityAnalysisAutoEnabled,
                                                passwordDetailSecurityAnalysisEnabled =
                                                    pageAdjustmentBackup.passwordDetailSecurityAnalysisEnabled,
                                                steamMiniProfileBackgroundEnabled =
                                                    pageAdjustmentBackup.steamMiniProfileBackgroundEnabled,
                                                autofillAuthRequired =
                                                    pageAdjustmentBackup.autofillAuthRequired,
                                                iconCardsEnabled = pageAdjustmentBackup.iconCardsEnabled,
                                                appLauncherIcon = pageAdjustmentBackup.appLauncherIcon,
                                                appLauncherLabel = pageAdjustmentBackup.appLauncherLabel,
                                                passwordPageIconEnabled =
                                                    pageAdjustmentBackup.passwordPageIconEnabled,
                                                authenticatorPageIconEnabled =
                                                    pageAdjustmentBackup.authenticatorPageIconEnabled,
                                                passkeyPageIconEnabled =
                                                    pageAdjustmentBackup.passkeyPageIconEnabled,
                                                unmatchedIconHandlingStrategy =
                                                    pageAdjustmentBackup.unmatchedIconHandlingStrategy,
                                                passwordFieldSettingsVersion =
                                                    pageAdjustmentBackup.passwordFieldSettingsVersion,
                                                separateUsernameAccountEnabled =
                                                    pageAdjustmentBackup.separateUsernameAccountEnabled,
                                                presetCustomFieldsJson =
                                                    pageAdjustmentBackup.presetCustomFieldsJson,
                                                passwordFieldVisibility =
                                                    PageAdjustmentPasswordFieldVisibilitySnapshot(
                                                        securityVerification =
                                                            pageAdjustmentBackup.passwordFieldVisibility.securityVerification,
                                                        categoryAndNotes =
                                                            pageAdjustmentBackup.passwordFieldVisibility.categoryAndNotes,
                                                        appBinding =
                                                            pageAdjustmentBackup.passwordFieldVisibility.appBinding,
                                                        personalInfo =
                                                            pageAdjustmentBackup.passwordFieldVisibility.personalInfo,
                                                        addressInfo =
                                                            pageAdjustmentBackup.passwordFieldVisibility.addressInfo,
                                                        paymentInfo =
                                                            pageAdjustmentBackup.passwordFieldVisibility.paymentInfo,
                                                    ),
                                            )
                                        )
                                        warnings.add("✓ 页面调整自定义已恢复")
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore page adjustment settings: ${e.message}")
                                        warnings.add("页面调整自定义恢复失败: ${e.message}")
                                    }
                                }
                                normalizedEntryName == "monica_config/security_questions.json" -> {
                                    try {
                                        val securityQuestionsBackup = Json { ignoreUnknownKeys = true }
                                            .decodeFromString<SecurityQuestionsBackupEntry>(
                                                tempFile.readText(Charsets.UTF_8)
                                            )
                                        val restored = SecurityManager(context)
                                            .restoreSecurityQuestionsFromBackup(
                                                SecurityQuestionsBackupSnapshot(
                                                    question1Id = securityQuestionsBackup.question1Id,
                                                    question1Text = securityQuestionsBackup.question1Text,
                                                    answer1Hash = securityQuestionsBackup.answer1Hash,
                                                    question2Id = securityQuestionsBackup.question2Id,
                                                    question2Text = securityQuestionsBackup.question2Text,
                                                    answer2Hash = securityQuestionsBackup.answer2Hash,
                                                )
                                            )
                                        if (restored) {
                                            warnings.add("✓ 密保问题已恢复")
                                        } else {
                                            warnings.add("密保问题备份格式无效，已跳过恢复")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "WebDavHelper",
                                            "Failed to restore security questions: ${e.message}"
                                        )
                                        warnings.add("密保问题恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复 Monica 旧版聚合配置（兼容 monica_config.json）
                                normalizedEntryName == "monica_config/monica_config.json" -> {
                                    try {
                                        val monicaConfigJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val monicaConfigBackup = json.decodeFromString<MonicaConfigBackupEntry>(monicaConfigJson)

                                        val autofillBlockedFieldRecords = monicaConfigBackup.blockedFieldSignatures
                                            .mapNotNull { backup ->
                                                backup.signatureKey
                                                    .trim()
                                                    .takeIf { it.isNotBlank() }
                                                    ?.let { signatureKey ->
                                                        AutofillPreferences.BlockedFieldSignatureRecord(
                                                            signatureKey = signatureKey,
                                                            packageName = backup.packageName?.trim()?.ifBlank { null },
                                                            webDomain = backup.webDomain?.trim()?.ifBlank { null },
                                                            hints = backup.hints,
                                                            blockedAt = backup.blockedAt,
                                                        )
                                                    }
                                            }
                                        if (autofillBlockedFieldRecords.isNotEmpty()) {
                                            AutofillPreferences(context).importBlockedFieldSignatureRecords(autofillBlockedFieldRecords)
                                            warnings.add("✓ 非自动填充字段已恢复: ${autofillBlockedFieldRecords.size}项")
                                        }

                                        if (monicaConfigBackup.saveBlockedTargets.isNotEmpty()) {
                                            AutofillPreferences(context).importSaveBlockedTargets(
                                                monicaConfigBackup.saveBlockedTargets,
                                            )
                                            warnings.add("✓ 不保存名单已恢复: ${monicaConfigBackup.saveBlockedTargets.size}项")
                                        }

                                        if (monicaConfigBackup.autofillBlacklistEnabled != null ||
                                            monicaConfigBackup.autofillBlacklistPackages != null
                                        ) {
                                            val normalizedPackages = monicaConfigBackup.autofillBlacklistPackages
                                                ?.mapNotNull { it.trim().takeIf { packageName -> packageName.isNotBlank() } }
                                                ?.toSet()
                                            AutofillPreferences(context).apply {
                                                monicaConfigBackup.autofillBlacklistEnabled?.let {
                                                    setBlacklistEnabled(it)
                                                }
                                                normalizedPackages?.let {
                                                    setBlacklistPackages(it)
                                                }
                                            }
                                            if (normalizedPackages != null) {
                                                warnings.add("✓ 自动填充黑名单已恢复: ${normalizedPackages.size}个应用")
                                            }
                                        }

                                        val restoredBitwardenVaultCount = restoreBitwardenVaultBackups(
                                            monicaConfigBackup.bitwardenVaults,
                                            resolvedDecryptPassword,
                                        )
                                        if (restoredBitwardenVaultCount > 0) {
                                            warnings.add("✓ Bitwarden Vault已恢复: ${restoredBitwardenVaultCount}个（已锁定）")
                                        }

                                        if (!isConfigured()) {
                                            if (monicaConfigBackup.encryptedPassword.isBlank()) {
                                                warnings.add("WebDAV连接密码未包含在备份中，恢复后需要重新填写")
                                            } else {
                                                try {
                                                    val decryptedWebDavPassword = decryptBackupValueWithLegacyFallback(
                                                        monicaConfigBackup.encryptedPassword,
                                                        resolvedDecryptPassword,
                                                    ).orEmpty()

                                                    val decryptedEncPassword = decryptBackupValueWithLegacyFallback(
                                                        monicaConfigBackup.encryptedEncryptionPassword,
                                                        resolvedDecryptPassword,
                                                    ).orEmpty()

                                                    if (monicaConfigBackup.serverUrl.isNotEmpty() &&
                                                        monicaConfigBackup.username.isNotEmpty() &&
                                                        decryptedWebDavPassword.isNotEmpty()
                                                    ) {
                                                        configure(monicaConfigBackup.serverUrl, monicaConfigBackup.username, decryptedWebDavPassword)
                                                        if (monicaConfigBackup.enableEncryption && decryptedEncPassword.isNotEmpty()) {
                                                            configureEncryption(true, decryptedEncPassword)
                                                        }
                                                        configureAutoBackup(monicaConfigBackup.autoBackupEnabled)
                                                        android.util.Log.d("WebDavHelper", "Restored WebDAV config from legacy Monica config")
                                                        warnings.add("✓ WebDAV配置已恢复: ${monicaConfigBackup.serverUrl}")
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.w("WebDavHelper", "Failed to decrypt legacy Monica config WebDAV credentials: ${e.message}")
                                                    warnings.add("WebDAV配置解密失败（可能密码不匹配）: ${e.message}")
                                                }
                                            }
                                        } else {
                                            android.util.Log.d("WebDavHelper", "WebDAV already configured, skipping legacy Monica connection restore")
                                            warnings.add("WebDAV已配置，已跳过连接配置恢复")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore legacy Monica config: ${e.message}")
                                        warnings.add("Monica配置恢复失败: ${e.message}")
                                    }
                                }
                                // ✅ 恢复 WebDAV 连接配置（新文件与旧版 webdav_config.json 均兼容）
                                normalizedEntryName == "monica_config/webdav_connection.json" ||
                                    entryName.equals("webdav_config.json", ignoreCase = true) -> {
                                    try {
                                        val webDavConfigJson = tempFile.readText(Charsets.UTF_8)
                                        val json = Json { ignoreUnknownKeys = true }
                                        val webDavConfigBackup = json.decodeFromString<WebDavConnectionBackupEntry>(webDavConfigJson)

                                        // 只有当本地未配置 WebDAV 时才恢复连接信息
                                        if (!isConfigured()) {
                                            if (webDavConfigBackup.encryptedPassword.isBlank()) {
                                                warnings.add("WebDAV连接密码未包含在备份中，恢复后需要重新填写")
                                            } else {
                                                try {
                                                    val decryptedWebDavPassword = decryptBackupValueWithLegacyFallback(
                                                        webDavConfigBackup.encryptedPassword,
                                                        resolvedDecryptPassword,
                                                    ).orEmpty()

                                                    val decryptedEncPassword = decryptBackupValueWithLegacyFallback(
                                                        webDavConfigBackup.encryptedEncryptionPassword,
                                                        resolvedDecryptPassword,
                                                    ).orEmpty()
                                                
                                                    if (webDavConfigBackup.serverUrl.isNotEmpty() &&
                                                        webDavConfigBackup.username.isNotEmpty() &&
                                                        decryptedWebDavPassword.isNotEmpty()) {

                                                        // 配置 WebDAV 连接
                                                        configure(webDavConfigBackup.serverUrl, webDavConfigBackup.username, decryptedWebDavPassword)

                                                        // 配置加密设置
                                                        if (webDavConfigBackup.enableEncryption && decryptedEncPassword.isNotEmpty()) {
                                                            configureEncryption(true, decryptedEncPassword)
                                                        }

                                                        // 配置自动备份
                                                        configureAutoBackup(webDavConfigBackup.autoBackupEnabled)

                                                        android.util.Log.d("WebDavHelper", "Restored WebDAV config")
                                                        warnings.add("✓ WebDAV配置已恢复: ${webDavConfigBackup.serverUrl}")
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.w("WebDavHelper", "Failed to decrypt WebDAV credentials: ${e.message}")
                                                    warnings.add("WebDAV配置解密失败（可能密码不匹配）: ${e.message}")
                                                }
                                            }
                                        } else {
                                            android.util.Log.d("WebDavHelper", "WebDAV already configured, skipping restore")
                                            warnings.add("WebDAV已配置，已跳过连接配置恢复")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebDavHelper", "Failed to restore WebDAV config: ${e.message}")
                                        warnings.add("WebDAV配置恢复失败: ${e.message}")
                                    }
                                }
                                entryName.equals("keepass_webdav_config.json", ignoreCase = true) -> {
                                    // KeePass WebDAV 已下线，不再恢复该配置文件。
                                    warnings.add("KeePass WebDAV功能已下线，已跳过配置恢复")
                                }
                                // ✅ 恢复 KeePass 数据库（恢复为内部存储）
                                keePassEntry != null -> {
                                    try {
                                        val dbId = keePassEntry.databaseId
                                        if (dbId in invalidKeePassRestoreIds) {
                                            // 同一数据库出现重复条目时拒绝整个集合，避免顺序决定最终凭据。
                                        } else {
                                            when (keePassEntry.kind) {
                                                KeePassBackupEntryPolicy.Kind.METADATA -> {
                                                    val metadata = Json { ignoreUnknownKeys = true }
                                                        .decodeFromString<KeePassDatabaseBackupEntry>(
                                                            tempFile.readText(Charsets.UTF_8),
                                                        )
                                                    if (metadata.id != 0L && metadata.id != dbId) {
                                                        throw IllegalArgumentException("KeePass元信息ID与文件名不一致")
                                                    }
                                                    if (pendingKeePassMetadata.put(dbId, metadata) != null) {
                                                        invalidKeePassRestoreIds += dbId
                                                        pendingKeePassMetadata.remove(dbId)
                                                        warnings.add("KeePass数据库 $dbId 存在重复元信息，已跳过")
                                                    }
                                                }

                                                KeePassBackupEntryPolicy.Kind.KEY_FILE -> {
                                                    if (!isEncrypted) {
                                                        throw SecurityException("未加密备份中的 KeePass 密钥文件已被拒绝")
                                                    }
                                                    if (pendingKeePassKeyFiles.put(dbId, tempFile.readBytes()) != null) {
                                                        invalidKeePassRestoreIds += dbId
                                                        pendingKeePassKeyFiles.remove(dbId)
                                                        warnings.add("KeePass数据库 $dbId 存在重复密钥文件，已跳过")
                                                    }
                                                }

                                                KeePassBackupEntryPolicy.Kind.DATABASE -> {
                                                    if (pendingKeePassDatabases.containsKey(dbId)) {
                                                        invalidKeePassRestoreIds += dbId
                                                        pendingKeePassDatabases.remove(dbId)?.delete()
                                                        warnings.add("KeePass数据库 $dbId 存在重复数据库文件，已跳过")
                                                    } else {
                                                        keepassRestoreTempDir.mkdirs()
                                                        val stagedFile = File(
                                                            keepassRestoreTempDir,
                                                            "keepass_${dbId}_${System.nanoTime()}.kdbx",
                                                        )
                                                        tempFile.copyTo(stagedFile, overwrite = false)
                                                        pendingKeePassDatabases[dbId] = stagedFile
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        invalidKeePassRestoreIds += keePassEntry.databaseId
                                        android.util.Log.w("WebDavHelper", "Failed to stage KeePass file: ${e.message}")
                                        warnings.add("KeePass文件处理失败: ${e.message}")
                                    }
                                }
                            }
                        } finally {
                            // 只有当 tempFile 不是 passwordsCsvFile 时才删除
                            if (
                                tempFile != passwordsCsvFile &&
                                tempFile !in secureCsvFiles &&
                                tempFile !in pendingPortableAttachmentPayloads.values
                            ) {
                                tempFile.delete()
                            }
                        }
                        
                        entry = zipIn.nextEntry
                    }
                }

                restoreStagedKeePassDatabases(
                    metadataById = pendingKeePassMetadata,
                    keyFilesById = pendingKeePassKeyFiles,
                    databaseFilesById = pendingKeePassDatabases,
                    invalidIds = invalidKeePassRestoreIds,
                    backupEncrypted = isEncrypted,
                    warnings = warnings,
                )
                
                // ✅ 解析分类并创建缺失的分类，同时处理跨类型文件夹归属
                if (
                    passwordsWithMetadata.isNotEmpty() ||
                    notesWithMetadata.isNotEmpty() ||
                    totpWithMetadata.isNotEmpty() ||
                    bankCardsWithMetadata.isNotEmpty() ||
                    documentsWithMetadata.isNotEmpty() ||
                    billingAddressesWithMetadata.isNotEmpty() ||
                    paymentAccountsWithMetadata.isNotEmpty() ||
                    passkeysWithMetadata.isNotEmpty()
                ) {
                    val database = takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
                    val categoryDao = database.categoryDao()
                    
                    // 获取当前所有分类
                    val existingCategories = try { categoryDao.getAllCategories().first() } catch (e: Exception) { emptyList() }
                    val categoryByName = existingCategories.associateBy { it.name }.toMutableMap()
                    
                    // 收集需要创建的分类名称
                    val categoryNamesToCreate = (passwordsWithMetadata.mapNotNull { it.second } +
                        notesWithMetadata.mapNotNull { it.second } +
                        totpWithMetadata.mapNotNull { it.second } +
                        bankCardsWithMetadata.mapNotNull { it.second } +
                        documentsWithMetadata.mapNotNull { it.second } +
                        billingAddressesWithMetadata.mapNotNull { it.second } +
                        paymentAccountsWithMetadata.mapNotNull { it.second } +
                        passkeysWithMetadata.mapNotNull { it.second })
                        .distinct()
                        .filter { it.isNotBlank() && !categoryByName.containsKey(it) }
                    
                    // 创建缺失的分类
                    categoryNamesToCreate.forEach { categoryName ->
                        try {
                            val maxSortOrder = (categoryByName.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
                            val newCategory = takagi.ru.monica.data.Category(
                                name = categoryName,
                                sortOrder = maxSortOrder
                            )
                            val newId = categoryDao.insert(newCategory)
                            val createdCategory = newCategory.copy(id = newId)
                            categoryByName[categoryName] = createdCategory
                            android.util.Log.d("WebDavHelper", "Created category: $categoryName (id=$newId)")
                        } catch (e: Exception) {
                            android.util.Log.w("WebDavHelper", "Failed to create category $categoryName: ${e.message}")
                        }
                    }
                    
                    // 将密码与分类关联
                    passwordsWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        val passwordEntry = entry.copy(categoryId = categoryId)
                        passwords.add(passwordEntry)
                    }

                    // 将笔记与分类关联
                    notesWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    // 将验证器与分类关联
                    totpWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    bankCardsWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    documentsWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    billingAddressesWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    paymentAccountsWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        secureItems.add(entry.copy(categoryId = categoryId))
                    }

                    // 将通行密钥与分类关联
                    passkeysWithMetadata.forEach { (entry, categoryName) ->
                        val categoryId = categoryName?.let { categoryByName[it]?.id }
                        passkeys.add(entry.copy(categoryId = categoryId))
                    }
                    
                    android.util.Log.d(
                        "WebDavHelper",
                        "Resolved categories for passwords=${passwords.size}, secureItems=${secureItems.size}, passkeys=${passkeys.size}"
                    )
                }

                if (notesWithMetadata.isNotEmpty() && secureItems.isEmpty()) {
                    secureItems.addAll(notesWithMetadata.map { it.first })
                }
                if (totpWithMetadata.isNotEmpty() && secureItems.none { it.itemType == ItemType.TOTP.name }) {
                    secureItems.addAll(totpWithMetadata.map { it.first })
                }
                if (bankCardsWithMetadata.isNotEmpty() && secureItems.none { it.itemType == ItemType.BANK_CARD.name }) {
                    secureItems.addAll(bankCardsWithMetadata.map { it.first })
                }
                if (documentsWithMetadata.isNotEmpty() && secureItems.none { it.itemType == ItemType.DOCUMENT.name }) {
                    secureItems.addAll(documentsWithMetadata.map { it.first })
                }
                if (billingAddressesWithMetadata.isNotEmpty() && secureItems.none { it.itemType == ItemType.BILLING_ADDRESS.name }) {
                    secureItems.addAll(billingAddressesWithMetadata.map { it.first })
                }
                if (paymentAccountsWithMetadata.isNotEmpty() && secureItems.none { it.itemType == ItemType.PAYMENT_ACCOUNT.name }) {
                    secureItems.addAll(paymentAccountsWithMetadata.map { it.first })
                }
                if (passkeysWithMetadata.isNotEmpty() && passkeys.isEmpty()) {
                    passkeys.addAll(passkeysWithMetadata.map { it.first })
                }

                // 导入旧版 CSV（向后兼容）：放到扫描结束后统一处理，避免与 JSON 顺序耦合
                if (secureCsvFiles.isNotEmpty()) {
                    secureCsvFiles.forEach { csvFile ->
                        try {
                            val role = when {
                                csvFile.name.endsWith("_notes.csv", ignoreCase = true) ->
                                    LegacyMonicaSecureCsvRole.NOTES_ONLY
                                csvFile.name.endsWith("_totp.csv", ignoreCase = true) ->
                                    LegacyMonicaSecureCsvRole.TOTP_ONLY
                                csvFile.name.endsWith("_cards_docs.csv", ignoreCase = true) ->
                                    LegacyMonicaSecureCsvRole.CARDS_DOCS_ONLY
                                else ->
                                    LegacyMonicaSecureCsvRole.GENERIC_SECURE
                            }
                            val parseResult = LegacyMonicaZipCsvRestoreParser.parseSecureItems(csvFile, role)
                            warnings.addAll(parseResult.warnings)

                            val jsonBackedTypes = buildSet {
                                if (notesWithMetadata.isNotEmpty()) add(ItemType.NOTE)
                                if (totpWithMetadata.isNotEmpty()) add(ItemType.TOTP)
                                if (bankCardsWithMetadata.isNotEmpty()) add(ItemType.BANK_CARD)
                                if (documentsWithMetadata.isNotEmpty()) add(ItemType.DOCUMENT)
                            }
                            val normalizedImported = parseResult.items
                                .map(::normalizeRestoredTotpItem)
                                .map { item ->
                                    sanitizeSecureExportItemForMonicaRestore(
                                        item = item,
                                        sourceFileName = csvFile.name
                                    )
                                }
                            secureItems.addAll(
                                normalizedImported.filterNot { importedItem ->
                                    SecureItemRestoreTypeResolver.resolve(
                                        rawType = importedItem.itemType,
                                        itemData = importedItem.itemData,
                                        sourceFileName = csvFile.name
                                    ) in jsonBackedTypes
                                }
                            )
                        } catch (e: Exception) {
                            warnings.add("导入CSV失败 ${csvFile.name}: ${e.message}")
                        } finally {
                            csvFile.delete()
                        }
                    }

                }
                
                // 5. 向后兼容：如果没有JSON密码，使用CSV（支持旧版本备份）
                passwordsCsvFile?.let { csvFile ->
                    if (passwords.isEmpty() && csvFile.exists()) {
                        android.util.Log.d("WebDavHelper", "No JSON passwords found, using CSV for backward compatibility")
                        try {
                            val csvPasswords = importPasswordsFromCSV(csvFile)
                            backupPasswordCount = csvPasswords.size
                            passwords.addAll(csvPasswords)
                            restoredPasswordCount = csvPasswords.size
                            android.util.Log.d("WebDavHelper", "Restored ${csvPasswords.size} passwords from CSV")
                        } catch (e: Exception) {
                            android.util.Log.e("WebDavHelper", "Failed to import passwords from CSV: ${e.message}")
                            warnings.add("CSV密码导入失败: ${e.message}")
                        }
                    }
                    csvFile.delete()
                }
                
                // P0修复：生成详细报告
                if (backupPasswordCount == 0) {
                    backupPasswordCount = passwords.size
                }
                val normalizedSecureItems = secureItems.map(::normalizeRestoredTotpItem)
                val totpItems = normalizedSecureItems.count { it.itemType == "TOTP" }
                val cardItems = normalizedSecureItems.count { it.itemType == "BANK_CARD" }
                val docItems = normalizedSecureItems.count { it.itemType == "DOCUMENT" }
                val billingAddressItems = normalizedSecureItems.count { it.itemType == "BILLING_ADDRESS" }
                val paymentAccountItems = normalizedSecureItems.count { it.itemType == "PAYMENT_ACCOUNT" }
                
                val backupCounts = ItemCounts(
                    passwords = backupPasswordCount,
                    notes = backupNoteCount,
                    totp = totpItems,
                    bankCards = if (backupCardCount > 0) backupCardCount else cardItems,
                    documents = if (backupDocCount > 0) backupDocCount else docItems,
                    billingAddresses = if (backupBillingAddressCount > 0) backupBillingAddressCount else billingAddressItems,
                    paymentAccounts = if (backupPaymentAccountCount > 0) backupPaymentAccountCount else paymentAccountItems,
                    passkeys = backupPasskeyCount,
                    steamMaFiles = backupSteamMaFileCount,
                    images = backupImageCount
                )
                
                val restoredCounts = ItemCounts(
                    passwords = passwords.size,
                    notes = restoredNoteCount,
                    totp = totpItems,
                    bankCards = if (restoredCardCount > 0) restoredCardCount else cardItems,
                    documents = if (restoredDocCount > 0) restoredDocCount else docItems,
                    billingAddresses = if (restoredBillingAddressCount > 0) restoredBillingAddressCount else billingAddressItems,
                    paymentAccounts = if (restoredPaymentAccountCount > 0) restoredPaymentAccountCount else paymentAccountItems,
                    passkeys = restoredPasskeyCount,
                    steamMaFiles = restoredSteamMaFileCount,
                    images = restoredImageCount
                )

                if (backupPasskeyCount > 0) {
                    warnings.add("通行密钥恢复: $restoredPasskeyCount/$backupPasskeyCount")
                }
                if (missingPasskeyPrivateKeyCount > 0) {
                    warnings.add(
                        context.getString(
                            R.string.passkey_restore_private_keys_missing,
                            missingPasskeyPrivateKeyCount,
                        )
                    )
                }

                val hasRestorableCoreData =
                    passwords.isNotEmpty() ||
                        normalizedSecureItems.isNotEmpty() ||
                        passkeys.any { it.syncStatus != "REFERENCE" } ||
                        steamMaFiles.isNotEmpty()

                if (overwrite) {
                    android.util.Log.d(
                        "WebDavHelper",
                        "Overwrite restore requested after parse: file=${backupFile.name}, " +
                            "passwords=${passwords.size}, secureItems=${normalizedSecureItems.size}, " +
                            "passkeys=${passkeys.size}, steamMaFiles=${steamMaFiles.size}, " +
                            "failedItems=${failedItems.size}, warnings=${warnings.size}"
                    )
                    if (failedItems.isNotEmpty()) {
                        return@withContext Result.failure(
                            Exception("备份解析存在失败项，已阻止替换本地数据以避免数据丢失")
                        )
                    }
                    if (!hasRestorableCoreData) {
                        return@withContext Result.failure(
                            Exception("备份中没有可恢复的数据，已阻止替换本地数据以避免数据丢失")
                        )
                    }
                    val clearResult = if (steamMaFiles.isNotEmpty()) {
                        clearLocalDataForOverwriteRestore(
                            backupFileName = backupFile.name,
                            clearSteamAccounts = true
                        )
                    } else {
                        clearLocalDataForOverwriteRestore(backupFile.name)
                    }
                    clearResult.getOrElse { error ->
                        return@withContext Result.failure(error)
                    }
                }
                
                val report = RestoreReport(
                    success = failedItems.isEmpty(),
                    backupContains = backupCounts,
                    restoredSuccessfully = restoredCounts,
                    failedItems = failedItems,
                    warnings = warnings
                )
                
                Result.success(RestoreResult(
                    content = BackupContent(
                        passwords = passwords,
                        secureItems = normalizedSecureItems,
                        passkeys = passkeys,
                        steamMaFiles = steamMaFiles,
                        customFieldsMap = pendingCustomFields.toMap(),
                        passwordHistory = passwordHistory,
                        attachments = pendingAttachments.toList(),
                        portableAttachments = takagi.ru.monica.attachments.backup.PortableAttachmentBackup.RestorePlan(
                            entries = pendingPortableAttachmentEntries.toList(),
                            payloads = pendingPortableAttachmentPayloads.toMap()
                        )
                    ),
                    report = report,
                    monicaConfigDetected = detectedMonicaConfigEntries.isNotEmpty(),
                    monicaConfigRestoreSkipped =
                        !shouldRestoreMonicaConfig && detectedMonicaConfigEntries.isNotEmpty(),
                    restoredMonicaConfigEntries =
                        if (shouldRestoreMonicaConfig) detectedMonicaConfigEntries.size else 0,
                    restartRecommended =
                        shouldRestoreMonicaConfig && detectedMonicaConfigEntries.isNotEmpty(),
                ))
            } finally {
                // 清除临时存储的自定义字段，避免内存泄漏
                pendingCustomFields.clear()
                keepassRestoreTempDir.deleteRecursively()
                if (zipFile != backupFile) {
                    zipFile.delete()
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("恢复备份失败: ${e.message}"))
        }
    }

    /**
     * 下载并恢复备份 - 返回密码、其他数据和恢复报告
     * @param backupFile 要恢复的备份文件信息
     * @param decryptPassword 解密密码 (如果文件已加密)
     */
    suspend fun downloadAndRestoreBackup(
        backupFile: BackupFile,
        decryptPassword: String? = null,
        overwrite: Boolean = false,
        restoreMonicaConfig: Boolean? = true,
    ): Result<RestoreResult> = 
        withContext(Dispatchers.IO) {
        try {
            // 1. 下载备份文件
            val downloadedFile = File(context.cacheDir, "restore_${backupFile.name}")
            val downloadResult = downloadBackup(backupFile, downloadedFile)
            
            if (downloadResult.isFailure) {
                return@withContext Result.failure(downloadResult.exceptionOrNull() 
                    ?: Exception("下载备份失败"))
            }
            
            try {
                // 2. 调用的恢复方法
                val restoreResult = restoreFromBackupFile(
                    backupFile = downloadedFile,
                    decryptPassword = decryptPassword,
                    overwrite = overwrite,
                    restoreMonicaConfig = restoreMonicaConfig,
                )
                if (restoreResult.isFailure) {
                    // 如果是密码错误，传递具体的异常
                    val ex = restoreResult.exceptionOrNull()
                    if (ex is PasswordRequiredException || ex is MonicaConfigDecisionRequiredException) {
                        return@withContext Result.failure(ex)
                    }
                    return@withContext Result.failure(ex ?: Exception("恢复失败"))
                }
                
                // 记录 WebDAV 下载/同步操作到时间线
                // 注意：此时数据还未真正写入数据库，result 包含的是备份文件中解析出的条目
                // 实际新增/修改的统计需要在合并逻辑中完成
                val result = restoreResult.getOrThrow()
                val downloadDetails = mutableListOf<FieldChange>()
                val newItemNames = mutableListOf<FieldChange>()
                
                // 密码统计
                if (result.content.passwords.isNotEmpty()) {
                    val passwordCount = result.content.passwords.size
                    downloadDetails.add(FieldChange("密码", "", "${passwordCount}项"))
                    // 收集密码名称用于git-branch风格展示（最多10个）
                    result.content.passwords.take(10).forEach { pwd ->
                        newItemNames.add(FieldChange("密码", "", pwd.title.ifBlank { pwd.username }))
                    }
                    if (passwordCount > 10) {
                        newItemNames.add(FieldChange("密码", "", "...还有${passwordCount - 10}项"))
                    }
                }
                
                // 安全项统计
                val secureItems = result.content.secureItems
                val totpItems = secureItems.filter { it.itemType == "TOTP" }
                if (totpItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("验证器", "", "${totpItems.size}项"))
                    totpItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("验证器", "", item.title))
                    }
                    if (totpItems.size > 10) {
                        newItemNames.add(FieldChange("验证器", "", "...还有${totpItems.size - 10}项"))
                    }
                }
                
                val cardItems = secureItems.filter { it.itemType == "BANK_CARD" }
                if (cardItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("卡片", "", "${cardItems.size}项"))
                    cardItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("卡片", "", item.title))
                    }
                    if (cardItems.size > 10) {
                        newItemNames.add(FieldChange("卡片", "", "...还有${cardItems.size - 10}项"))
                    }
                }
                
                val noteItems = secureItems.filter { it.itemType == "NOTE" }
                if (noteItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("笔记", "", "${noteItems.size}项"))
                    noteItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("笔记", "", item.title))
                    }
                    if (noteItems.size > 10) {
                        newItemNames.add(FieldChange("笔记", "", "...还有${noteItems.size - 10}项"))
                    }
                }
                
                val docItems = secureItems.filter { it.itemType == "DOCUMENT" }
                if (docItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("证件", "", "${docItems.size}项"))
                    docItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("证件", "", item.title))
                    }
                    if (docItems.size > 10) {
                        newItemNames.add(FieldChange("证件", "", "...还有${docItems.size - 10}项"))
                    }
                }

                val billingAddressItems = secureItems.filter { it.itemType == "BILLING_ADDRESS" }
                if (billingAddressItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("账单地址", "", "${billingAddressItems.size}项"))
                    billingAddressItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("账单地址", "", item.title))
                    }
                    if (billingAddressItems.size > 10) {
                        newItemNames.add(FieldChange("账单地址", "", "...还有${billingAddressItems.size - 10}项"))
                    }
                }

                val paymentAccountItems = secureItems.filter { it.itemType == "PAYMENT_ACCOUNT" }
                if (paymentAccountItems.isNotEmpty()) {
                    downloadDetails.add(FieldChange("支付方式", "", "${paymentAccountItems.size}项"))
                    paymentAccountItems.take(10).forEach { item ->
                        newItemNames.add(FieldChange("支付方式", "", item.title))
                    }
                    if (paymentAccountItems.size > 10) {
                        newItemNames.add(FieldChange("支付方式", "", "...还有${paymentAccountItems.size - 10}项"))
                    }
                }
                
                if (downloadDetails.isNotEmpty()) {
                    // 合并统计和详细条目列表
                    val allDetails = downloadDetails + newItemNames
                    OperationLogger.logWebDavDownload(addedItems = allDetails)
                }
                
                Result.success(result)
            } finally {
                // 清理下载的文件
                downloadedFile.delete()
            }
        } catch (e: Exception) {
            Result.failure(Exception("恢复备份失败: ${e.message}"))
        }
    }
    
    /**
     * 从CSV文件导入密码
     */
    private fun importPasswordsFromCSV(file: File): List<PasswordEntry> {
        val passwords = mutableListOf<PasswordEntry>()
        
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var firstLine = reader.readLine()
            
            // 跳过BOM标记
            if (firstLine?.startsWith("\uFEFF") == true) {
                firstLine = firstLine.substring(1)
            }
            var format = PasswordCsvFormat.UNKNOWN
            var isHeader = false
            if (firstLine != null) {
                val fields = splitCsvLine(firstLine)
                format = detectPasswordCsvFormat(fields)
                isHeader = when (format) {
                    PasswordCsvFormat.APP_EXPORT -> fields.map { it.lowercase(Locale.getDefault()) }.let {
                        it.contains("type") && it.contains("data")
                    }
                    PasswordCsvFormat.CHROME -> fields.map { it.lowercase(Locale.getDefault()) }.let {
                        it.contains("name") && it.contains("password") && it.contains("username")
                    }
                    PasswordCsvFormat.LEGACY -> fields.map { it.lowercase(Locale.getDefault()) }.let {
                        it.contains("title") && it.contains("password")
                    }
                    PasswordCsvFormat.UNKNOWN -> false
                }
                if (!isHeader && firstLine.isNotBlank()) {
                    parsePasswordEntry(firstLine, format)?.let { passwords.add(it) }
                }
            }
            reader.forEachLine { line ->
                if (line.isNotBlank()) {
                    parsePasswordEntry(line, format)?.let { passwords.add(it) }
                }
            }
        }
        
        return passwords
    }

    /**
     * 从JSON文件恢复密码
     * @return Pair of (PasswordEntry, categoryName) - categoryName用于创建/查找分类
     */
    /**
     * 从JSON文件恢复密码条目（包含自定义字段）
     * @return Pair<PasswordEntry, categoryName>
     */
    private fun restorePasswordFromJson(file: File): Pair<PasswordEntry, String?>? {
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = Json { ignoreUnknownKeys = true }
            val backup = json.decodeFromString<PasswordBackupEntry>(content)
            val entry = restorePasswordAsMonicaLocal(backup)
            // 临时存储自定义字段到全局 map（将在恢复时使用）
            if (backup.customFields.isNotEmpty()) {
                pendingCustomFields[backup.id] = backup.customFields
            }
            Pair(entry, backup.categoryName)
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to parse password JSON from ${file.name}: ${e.message}")
            null
        }
    }
    
    // 临时存储待恢复的自定义字段（原始ID -> 字段列表）
    private val pendingCustomFields = mutableMapOf<Long, List<CustomFieldBackupEntry>>()

    private fun normalizePasswordHistoryForBackup(
        entry: PasswordHistoryEntry,
        securityManager: SecurityManager
    ): PasswordHistoryBackupEntry {
        val decoded = decodePasswordHistoryForBackup(entry.password, securityManager)
            ?: throw PortableSecretExportException(
                entryTitle = "密码历史 #${entry.entryId}",
                cause = IllegalStateException("Stored password history cannot be decrypted")
            )
        return PasswordHistoryBackupEntry(
            entryId = entry.entryId,
            password = decoded,
            lastUsedAt = entry.lastUsedAt.time
        )
    }

    private fun decodePasswordHistoryForBackup(
        value: String,
        securityManager: SecurityManager
    ): String? {
        if (value.isEmpty()) return ""

        var current = value
        var changed = false
        repeat(3) {
            val decrypted = runCatching { securityManager.decryptData(current) }.getOrNull() ?: return@repeat
            if (decrypted == current) {
                return current
            }
            current = decrypted
            changed = true
        }
        if (changed) {
            return current
        }

        return if (looksLikeEncryptedHistoryPayload(value)) {
            null
        } else {
            value
        }
    }

    private fun looksLikeEncryptedHistoryPayload(value: String): Boolean {
        val trimmed = value.trim()
        if (
            trimmed.startsWith("MDK|") ||
            trimmed.startsWith("V2|") ||
            trimmed.startsWith("C2|")
        ) {
            return true
        }
        return runCatching {
            val decoded = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            decoded.size >= 28
        }.getOrDefault(false)
    }

    private fun restoreNoteFromJson(file: File): Pair<DataExportImportManager.ExportItem, String?>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val text = file.readText(Charsets.UTF_8)
            val entry = json.parseToJsonElement(text).jsonObject
            fun stringField(name: String, defaultValue: String = ""): String {
                return entry[name]?.jsonPrimitive?.contentOrNull ?: defaultValue
            }
            fun longField(name: String, defaultValue: Long = 0L): Long {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: defaultValue
            }
            fun booleanField(name: String, defaultValue: Boolean = false): Boolean {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
            }
            Pair(
                restoreSecureItemAsMonicaLocal(
                    itemType = ItemType.NOTE,
                    title = stringField("title"),
                    itemData = stringField("itemData"),
                    notes = stringField("notes"),
                    isFavorite = booleanField("isFavorite"),
                    imagePaths = stringField("imagePaths"),
                    createdAt = longField("createdAt", System.currentTimeMillis()),
                    updatedAt = longField("updatedAt", System.currentTimeMillis()),
                ),
                entry["categoryName"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to restore note from ${file.name}: ${e.message}")
            null
        }
    }

    private fun restoreCardWalletItemFromJson(
        file: File,
        fallbackType: ItemType
    ): Pair<DataExportImportManager.ExportItem, String?>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val text = file.readText(Charsets.UTF_8)
            val entry = json.parseToJsonElement(text).jsonObject
            fun stringField(name: String, defaultValue: String = ""): String {
                return entry[name]?.jsonPrimitive?.contentOrNull ?: defaultValue
            }
            fun longField(name: String, defaultValue: Long = 0L): Long {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: defaultValue
            }
            fun booleanField(name: String, defaultValue: Boolean = false): Boolean {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
            }
            val itemType = SecureItemRestoreTypeResolver.resolve(
                rawType = stringField("itemType"),
                itemData = stringField("itemData"),
                sourceFileName = file.name
            ) ?: fallbackType
            Pair(
                restoreSecureItemAsMonicaLocal(
                    itemType = itemType,
                    title = stringField("title"),
                    itemData = stringField("itemData"),
                    notes = stringField("notes"),
                    isFavorite = booleanField("isFavorite"),
                    imagePaths = stringField("imagePaths"),
                    createdAt = longField("createdAt", System.currentTimeMillis()),
                    updatedAt = longField("updatedAt", System.currentTimeMillis()),
                ),
                entry["categoryName"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to restore card wallet item from ${file.name}: ${e.message}")
            null
        }
    }

    private fun restoreTotpFromJson(file: File): Pair<DataExportImportManager.ExportItem, String?>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val text = file.readText(Charsets.UTF_8)
            val entry = json.parseToJsonElement(text).jsonObject
            fun stringField(name: String, defaultValue: String = ""): String {
                return entry[name]?.jsonPrimitive?.contentOrNull ?: defaultValue
            }
            fun longField(name: String, defaultValue: Long = 0L): Long {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: defaultValue
            }
            fun booleanField(name: String, defaultValue: Boolean = false): Boolean {
                return entry[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
            }
            Pair(
                restoreSecureItemAsMonicaLocal(
                    itemType = ItemType.TOTP,
                    title = stringField("title"),
                    itemData = normalizeRestoredTotpItemData(stringField("itemData"), stringField("title")),
                    notes = stringField("notes"),
                    isFavorite = booleanField("isFavorite"),
                    imagePaths = stringField("imagePaths"),
                    createdAt = longField("createdAt", System.currentTimeMillis()),
                    updatedAt = longField("updatedAt", System.currentTimeMillis()),
                ),
                entry["categoryName"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to restore totp from ${file.name}: ${e.message}")
            null
        }
    }

    private fun normalizeRestoredTotpItem(
        item: DataExportImportManager.ExportItem
    ): DataExportImportManager.ExportItem {
        if (!item.itemType.equals(ItemType.TOTP.name, ignoreCase = true)) {
            return item
        }
        val normalizedItemData = normalizeRestoredTotpItemData(item.itemData, item.title)
        return if (normalizedItemData == item.itemData) {
            item
        } else {
            item.copy(itemData = normalizedItemData)
        }
    }

    /**
     * 兼容老备份中的 Steam 验证器：
     * - otpType 丢失/小写时，按 Steam 特征自动修正为 STEAM
     * - 强制 Steam 使用 5 位、30 秒、SHA1
     */
    private fun normalizeRestoredTotpItemData(itemData: String, title: String): String {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(itemData).jsonObject }.getOrNull()
            ?: return itemData

        val normalizedMap = root.toMutableMap()
        var changed = false

        val rawOtpType = root["otpType"]?.jsonPrimitive?.contentOrNull
        val canonicalOtpType = when (rawOtpType?.trim()?.uppercase(Locale.ROOT)) {
            OtpType.TOTP.name -> OtpType.TOTP.name
            OtpType.HOTP.name -> OtpType.HOTP.name
            OtpType.STEAM.name -> OtpType.STEAM.name
            OtpType.YANDEX.name -> OtpType.YANDEX.name
            OtpType.MOTP.name -> OtpType.MOTP.name
            else -> null
        }
        if (canonicalOtpType != null && canonicalOtpType != rawOtpType) {
            normalizedMap["otpType"] = JsonPrimitive(canonicalOtpType)
            changed = true
        }

        val candidateJson = JsonObject(normalizedMap).toString()
        val totpData = TotpDataResolver.parseStoredItemData(
            itemData = candidateJson,
            fallbackIssuer = title
        )
            ?: return if (changed) candidateJson else itemData

        val hasSteamMetadata = listOf(
            totpData.steamFingerprint,
            totpData.steamDeviceId,
            totpData.steamSerialNumber,
            totpData.steamSharedSecretBase64,
            totpData.steamRevocationCode,
            totpData.steamIdentitySecret,
            totpData.steamTokenGid,
            totpData.steamRawJson
        ).any { it.isNotBlank() }
        val looksLikeSteam = listOf(totpData.issuer, totpData.accountName, title).any {
            it.contains("steam", ignoreCase = true)
        } || totpData.link.contains("encoder=steam", ignoreCase = true)

        val shouldForceSteam = totpData.otpType == OtpType.STEAM ||
            hasSteamMetadata ||
            (looksLikeSteam && totpData.otpType == OtpType.TOTP)

        fun updateField(key: String, value: JsonPrimitive) {
            if (normalizedMap[key] != value) {
                normalizedMap[key] = value
                changed = true
            }
        }

        if (shouldForceSteam) {
            updateField("otpType", JsonPrimitive(OtpType.STEAM.name))
            updateField("digits", JsonPrimitive(5))
            updateField("period", JsonPrimitive(30))
            updateField("algorithm", JsonPrimitive("SHA1"))
        }

        return if (changed) JsonObject(normalizedMap).toString() else itemData
    }

    private fun restorePasskeyFromJson(file: File): Pair<PasskeyEntry, String?>? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val text = file.readText(Charsets.UTF_8)
            val backup = json.parseToJsonElement(text).jsonObject
            fun stringField(name: String, defaultValue: String = ""): String {
                return backup[name]?.jsonPrimitive?.contentOrNull ?: defaultValue
            }
            fun nullableStringField(name: String): String? {
                return backup[name]?.jsonPrimitive?.contentOrNull
            }
            fun intField(name: String, defaultValue: Int = 0): Int {
                return backup[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: defaultValue
            }
            fun longField(name: String, defaultValue: Long = 0L): Long {
                return backup[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: defaultValue
            }
            fun booleanField(name: String, defaultValue: Boolean = false): Boolean {
                return backup[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
            }
            val restoredPrivateKey = PasskeyBackupPortabilityPolicy.prepareRestore(
                storedPrivateKey = stringField("privateKeyAlias"),
                resolvePrivateKey = { stored -> PasskeyPrivateKeyStore.resolve(securityManager, stored) },
                normalizePrivateKey = PasskeyPrivateKeySupport::exportPkcs8Base64,
            )
            Pair(
                PasskeyEntry(
                    id = 0,
                    credentialId = stringField("credentialId"),
                    rpId = stringField("rpId"),
                    rpName = stringField("rpName"),
                    userId = stringField("userId"),
                    userName = stringField("userName"),
                    userDisplayName = stringField("userDisplayName"),
                    publicKeyAlgorithm = intField("publicKeyAlgorithm", -7),
                    publicKey = stringField("publicKey"),
                    privateKeyAlias = restoredPrivateKey.privateKeyMaterial,
                    createdAt = longField("createdAt", System.currentTimeMillis()),
                    lastUsedAt = longField("lastUsedAt", System.currentTimeMillis()),
                    useCount = intField("useCount"),
                    iconUrl = nullableStringField("iconUrl"),
                    isDiscoverable = booleanField("isDiscoverable", true),
                    isUserVerificationRequired = booleanField("isUserVerificationRequired", true),
                    transports = stringField("transports", "internal"),
                    aaguid = stringField("aaguid"),
                    signCount = longField("signCount"),
                    isBackedUp = true,
                    notes = stringField("notes"),
                    boundPasswordId = backup["boundPasswordId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    syncStatus = restoredPrivateKey.syncStatus,
                    passkeyMode = normalizePasskeyMode(nullableStringField("passkeyMode"))
                ),
                nullableStringField("categoryName")
            )
        } catch (e: Exception) {
            android.util.Log.w("WebDavHelper", "Failed to restore passkey from ${file.name}: ${e.message}")
            null
        }
    }

    private fun normalizePasskeyMode(value: String?): String {
        return when (value) {
            PasskeyEntry.MODE_BW_COMPAT -> PasskeyEntry.MODE_BW_COMPAT
            PasskeyEntry.MODE_KEEPASS_COMPAT -> PasskeyEntry.MODE_KEEPASS_COMPAT
            else -> PasskeyEntry.MODE_LEGACY
        }
    }

    private fun detectPasswordCsvFormat(fields: List<String>): PasswordCsvFormat {
        val lowered = fields.map { it.lowercase(Locale.getDefault()) }
        return when {
            lowered.contains("type") && lowered.contains("data") && 
            lowered.contains("id") -> PasswordCsvFormat.APP_EXPORT
            lowered.contains("name") && lowered.contains("url") &&
                lowered.contains("username") && lowered.contains("password") -> PasswordCsvFormat.CHROME
            lowered.contains("title") && lowered.contains("password") -> PasswordCsvFormat.LEGACY
            else -> PasswordCsvFormat.UNKNOWN
        }
    }

    private fun parsePasswordEntry(line: String, format: PasswordCsvFormat): PasswordEntry? {
        val fields = splitCsvLine(line)
        return when (format) {
            PasswordCsvFormat.APP_EXPORT -> parseAppPasswordFields(fields)
            PasswordCsvFormat.CHROME -> parseChromePasswordFields(fields)
            PasswordCsvFormat.LEGACY -> parseLegacyPasswordFields(fields)
            PasswordCsvFormat.UNKNOWN -> parseAppPasswordFields(fields) ?: parseLegacyPasswordFields(fields) ?: parseChromePasswordFields(fields)
        }
    }

    private fun parseAppPasswordFields(fields: List<String>): PasswordEntry? {
        return try {
            // ID, Type, Title, Data, Notes, IsFavorite, ImagePaths, CreatedAt, UpdatedAt
            if (fields.size >= 9 && fields.getOrNull(1) == "PASSWORD") {
                val id = fields.getOrNull(0)?.toLongOrNull() ?: 0L
                val title = fields.getOrNull(2) ?: ""
                val dataStr = fields.getOrNull(3) ?: ""
                val notes = fields.getOrNull(4) ?: ""
                val isFavorite = fields.getOrNull(5)?.toBoolean() ?: false
                val createdAt = fields.getOrNull(7)?.toLongOrNull()?.let { Date(it) } ?: Date()
                val updatedAt = fields.getOrNull(8)?.toLongOrNull()?.let { Date(it) } ?: Date()
                val categoryId = fields.getOrNull(9)?.toLongOrNull()
                // Parse Data string (username:x;password:y;...)
                val dataMap = parsePasswordDataString(dataStr)
                
                PasswordEntry(
                    id = id, // Preserve ID!
                    title = title,
                    username = dataMap["username"] ?: "",
                    password = dataMap["password"] ?: "",
                    website = dataMap["website"] ?: "",
                    email = dataMap["email"] ?: "",
                    phone = dataMap["phone"] ?: "",
                    notes = notes,
                    isFavorite = isFavorite,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    categoryId = categoryId,
                    keepassDatabaseId = null,
                    keepassGroupPath = null,
                    keepassEntryUuid = null,
                    keepassGroupUuid = null,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    bitwardenFolderId = null,
                    bitwardenRevisionDate = null,
                    bitwardenLocalModified = false
                )
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Failed to parse APP_EXPORT password CSV line: ${e.message}")
            null
        }
    }

    private fun parsePasswordDataString(data: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        data.split(";").forEach { pair ->
            val parts = pair.split(":", limit = 2)
            if (parts.size == 2) {
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }

    private fun parseLegacyPasswordFields(fields: List<String>): PasswordEntry? {
        return try {
            if (fields.size >= 11) {
                PasswordEntry(
                    id = 0,
                    title = fields[1],
                    website = fields[2],
                    username = fields[3],
                    password = fields[4],
                    notes = fields[5],
                    isFavorite = fields[6].toBoolean(),
                    createdAt = Date(fields[7].toLong()),
                    updatedAt = Date(fields[8].toLong()),
                    sortOrder = fields.getOrNull(9)?.toIntOrNull() ?: 0,
                    isGroupCover = fields.getOrNull(10)?.toBoolean() ?: false
                )
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Failed to parse legacy password CSV line: ${e.message}")
            null
        }
    }

    private fun parseChromePasswordFields(fields: List<String>): PasswordEntry? {
        return try {
            if (fields.size >= 4) {
                val now = Date()
                val title = fields.getOrNull(0)?.trim().orEmpty()
                val website = fields.getOrNull(1)?.trim().orEmpty()
                val username = fields.getOrNull(2)?.trim().orEmpty()
                val password = fields.getOrNull(3)?.trim().orEmpty()
                val rawNote = fields.getOrNull(4) ?: ""
                val (note, metadata) = extractNoteAndMetadata(rawNote)
                val createdAt = metadata["createdAt"]?.toLongOrNull()?.let(::Date) ?: now
                val updatedAt = metadata["updatedAt"]?.toLongOrNull()?.let(::Date) ?: createdAt
                val email = fields.getOrNull(5)?.trim().orEmpty()
                val phone = fields.getOrNull(6)?.trim().orEmpty()

                PasswordEntry(
                    id = 0,
                    title = if (title.isNotBlank()) title else website.ifBlank { username },
                    website = website,
                    username = username,
                    password = password,
                    notes = note,
                    email = email,
                    phone = phone,
                    isFavorite = metadata["isFavorite"]?.toBoolean() ?: false,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    sortOrder = metadata["sortOrder"]?.toIntOrNull() ?: 0,
                    isGroupCover = metadata["isGroupCover"]?.toBoolean() ?: false
                )
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Failed to parse Chrome password CSV line: ${e.message}")
            null
        }
    }

    private fun buildPasswordNoteWithMetadata(entry: PasswordEntry): String {
        val metaParts = listOf(
            "isFavorite=${entry.isFavorite}",
            "createdAt=${entry.createdAt.time}",
            "updatedAt=${entry.updatedAt.time}",
            "sortOrder=${entry.sortOrder}",
            "isGroupCover=${entry.isGroupCover}"
        )
        return buildString {
            if (entry.notes.isNotEmpty()) {
                append(entry.notes)
                append("\n\n")
            }
            append(PASSWORD_META_MARKER)
            append(metaParts.joinToString("|"))
        }
    }

    private fun extractNoteAndMetadata(noteRaw: String): Pair<String, Map<String, String>> {
        val normalised = noteRaw.replace("\r\n", "\n")
        val markerIndex = normalised.indexOf(PASSWORD_META_MARKER)
        if (markerIndex < 0) {
            return noteRaw to emptyMap()
        }
        val baseNote = normalised.substring(0, markerIndex).trimEnd('\n', '\r')
        val metaPart = normalised.substring(markerIndex + PASSWORD_META_MARKER.length)
        val metadata = metaPart.split('|')
            .mapNotNull {
                val trimmed = it.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        return baseNote to metadata
    }

    private fun splitCsvLine(line: String): List<String> {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
            .map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }
    }

    private fun normalizeBackupIconValue(iconType: String, iconValue: String?): String? {
        if (iconValue.isNullOrBlank()) return iconValue
        return if (iconType.equals("UPLOADED", ignoreCase = true)) {
            File(iconValue).name
        } else {
            iconValue
        }
    }

    private enum class PasswordCsvFormat {
        APP_EXPORT,
        LEGACY,
        CHROME,
        UNKNOWN
    }

    private suspend fun restoreStagedKeePassDatabases(
        metadataById: Map<Long, KeePassDatabaseBackupEntry>,
        keyFilesById: Map<Long, ByteArray>,
        databaseFilesById: Map<Long, File>,
        invalidIds: Set<Long>,
        backupEncrypted: Boolean,
        warnings: MutableList<String>,
    ) {
        val ids = (metadataById.keys + keyFilesById.keys + databaseFilesById.keys).toSortedSet()
        if (ids.isEmpty()) return

        val database = try {
            takagi.ru.monica.data.PasswordDatabase.getDatabase(context)
        } catch (e: Exception) {
            warnings.add("KeePass数据库恢复失败: 无法打开本地数据库")
            return
        }
        val keepassDao = database.localKeePassDatabaseDao()
        val keyFileStore = KeePassKeyFileStore(context)

        ids.forEach { dbId ->
            val stagedDatabaseFile = databaseFilesById[dbId]
            val metadata = metadataById[dbId]
            val validation = if (dbId in invalidIds) {
                KeePassBackupEntryPolicy.RestoreSetValidation(
                    canRestore = false,
                    warning = "KeePass数据库 $dbId 存在重复或无效条目，已跳过",
                )
            } else {
                KeePassBackupEntryPolicy.validateRestoreSet(
                    databaseId = dbId,
                    hasMetadata = metadata != null,
                    hasDatabase = stagedDatabaseFile?.isFile == true,
                    declaredKeyEntryName = metadata?.keyFileEntryName,
                    hasKeyFile = keyFilesById.containsKey(dbId),
                    backupEncrypted = backupEncrypted,
                )
            }

            validation.warning?.let { warnings.add("KeePass数据库 $dbId：$it") }
            if (!validation.canRestore || metadata == null || stagedDatabaseFile == null) return@forEach

            var internalDatabaseFile: File? = null
            var storedKeyFile: KeePassKeyFileStore.StoredKeyFile? = null
            var keyFileWasReferenced = false
            try {
                if (validation.useKeyFile) {
                    val keyBytes = keyFilesById[dbId]
                        ?: error("备份中的 KeePass 密钥文件缺失")
                    val actualFingerprint = KeePassKeyFileStore.fingerprint(keyBytes)
                    if (
                        !metadata.keyFileFingerprint.isNullOrBlank() &&
                        !actualFingerprint.equals(metadata.keyFileFingerprint, ignoreCase = true)
                    ) {
                        throw SecurityException("备份中的 KeePass 密钥文件指纹不匹配")
                    }
                    val relativePath = KeePassKeyFileStore.relativePathForFingerprint(actualFingerprint)
                    keyFileWasReferenced = keepassDao.getAllDatabasesSync().any {
                        it.keyFileInternalPath == relativePath
                    }
                    storedKeyFile = keyFileStore.copyBytes(keyBytes, metadata.keyFileName)
                }

                val safeName = metadata.name
                    .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    .ifBlank { "database" }
                    .take(80)
                val internalFileName = "keepass_${System.currentTimeMillis()}_${safeName}_${dbId}.kdbx"
                val databaseFile = File(context.filesDir, "keepass/$internalFileName")
                internalDatabaseFile = databaseFile
                databaseFile.parentFile?.mkdirs()
                stagedDatabaseFile.copyTo(databaseFile, overwrite = false)

                val existingDbs = keepassDao.getAllDatabasesSync()
                val existsWithSameName = existingDbs.any { it.name == metadata.name }
                val newKeePassDb = takagi.ru.monica.data.LocalKeePassDatabase(
                    name = if (existsWithSameName) "${metadata.name} (恢复)" else metadata.name,
                    description = metadata.description,
                    filePath = "keepass/$internalFileName",
                    // 恢复后的设备路径不能沿用旧设备 URI；只绑定当前设备内部副本。
                    keyFileUri = null,
                    keyFileInternalPath = storedKeyFile?.relativePath,
                    keyFileName = storedKeyFile?.fileName,
                    keyFileFingerprint = storedKeyFile?.fingerprint,
                    storageLocation = takagi.ru.monica.data.KeePassStorageLocation.INTERNAL,
                    isDefault = false,
                    createdAt = System.currentTimeMillis(),
                    lastAccessedAt = System.currentTimeMillis(),
                )
                keepassDao.insertDatabase(newKeePassDb)
                warnings.add("✓ KeePass数据库已恢复: ${metadata.name}")
            } catch (e: Exception) {
                internalDatabaseFile?.delete()
                val failedKeyFile = storedKeyFile
                if (failedKeyFile != null && !keyFileWasReferenced) {
                    val referencedAfterFailure = runCatching {
                        keepassDao.getAllDatabasesSync().any {
                            it.keyFileInternalPath == failedKeyFile.relativePath
                        }
                    }.getOrDefault(true)
                    if (!referencedAfterFailure) {
                        runCatching { keyFileStore.deleteInternal(failedKeyFile.relativePath) }
                    }
                }
                android.util.Log.w("WebDavHelper", "Failed to restore KeePass database $dbId: ${e.message}")
                warnings.add("KeePass数据库恢复失败: ${metadata.name}")
            }
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) return
        FileInputStream(file).use { fileIn ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fileIn.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addBytesToZip(zipOut: ZipOutputStream, bytes: ByteArray, entryName: String) {
        val zipEntry = ZipEntry(entryName)
        zipOut.putNextEntry(zipEntry)
        try {
            zipOut.write(bytes)
        } finally {
            zipOut.closeEntry()
        }
    }

    private fun addDirectoryToZip(zipOut: ZipOutputStream, directory: File, basePath: String) {
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = file.relativeTo(directory).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$basePath/$relativePath")
            }
    }

    private fun toFolderKey(categoryName: String?): String {
        val normalized = categoryName?.trim().orEmpty()
        if (normalized.isEmpty()) return "_root"
        return buildString {
            normalized.forEach { ch ->
                append(
                    when {
                        ch.isLetterOrDigit() -> ch
                        ch == '-' || ch == '_' -> ch
                        ch.isWhitespace() -> '_'
                        else -> '_'
                    }
                )
            }
        }.trim('_').ifEmpty { "_root" }
    }

    private fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.contains("://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    private fun buildConnectionCandidates(rawUrl: String): List<String> {
        val normalized = normalizeServerUrl(rawUrl)
        if (normalized.isBlank()) return emptyList()
        val candidates = mutableListOf(normalized)
        val path = runCatching { Uri.parse(normalized).path }.getOrNull().orEmpty()
        if (path.isBlank() || path == "/") {
            val davUrl = joinWebDavUrl(normalized, "dav")
            if (!candidates.contains(davUrl)) {
                candidates.add(davUrl)
            }
        }
        return candidates
    }

    private fun createSardineClient(): Sardine {
        // 通过统一的 Gateway 构造，确保所有请求都经过预置式 Basic 鉴权、
        // 速率限制与 User-Agent 拦截器链（与 Kazumi webdav_client 一致），
        // 并应用用户选择的 HTTPS 证书校验档位。
        return takagi.ru.monica.webdav.WebDavGateway.buildClient(
            credentials = takagi.ru.monica.webdav.WebDavCredentials(username, password),
            tlsMode = takagi.ru.monica.webdav.WebDavTlsSettings.currentMode(context)
        )
    }

    private fun getBackupDirectoryPath(): String {
        val normalizedBase = normalizeServerUrl(serverUrl)
        return if (normalizedBase.endsWith("/$BACKUP_FOLDER_NAME", ignoreCase = true)) {
            normalizedBase
        } else {
            "$normalizedBase/$BACKUP_FOLDER_NAME"
        }
    }

    private fun getBackupFilePath(fileName: String): String {
        return "${getBackupDirectoryPath()}/$fileName"
    }

    private fun joinWebDavUrl(baseUrl: String, childPath: String): String {
        val normalizedBase = normalizeServerUrl(baseUrl)
        val normalizedChild = childPath
            .trim()
            .replace('\\', '/')
            .trim('/')
        return if (normalizedChild.isBlank()) normalizedBase else "$normalizedBase/$normalizedChild"
    }

    private fun webDavPathExists(url: String): Boolean {
        val client = sardine ?: return false
        // 单次 PROPFIND（sardine 的 exists() 内部实现）即可；失败时根据错误分类决定返回值：
        // - 404 -> 明确不存在（返回 false）
        // - RateLimited / AuthFailed -> 上抛，由上层处理而不是吞掉
        // - 其他 -> 记录后返回 false（保守处理，避免阻塞业务路径）
        try {
            return client.exists(url)
        } catch (e: Exception) {
            val classified = takagi.ru.monica.webdav.WebDavErrorClassifier.classify(e)
            when (classified.kind) {
                takagi.ru.monica.webdav.WebDavErrorKind.NotFound -> {
                    android.util.Log.d("WebDavHelper", "exists() -> not found: $url")
                    return false
                }
                takagi.ru.monica.webdav.WebDavErrorKind.RateLimited,
                takagi.ru.monica.webdav.WebDavErrorKind.AuthFailed -> {
                    android.util.Log.w(
                        "WebDavHelper",
                        "exists() failed for $url with kind=${classified.kind}"
                    )
                    throw e
                }
                else -> {
                    android.util.Log.w(
                        "WebDavHelper",
                        "exists() failed for $url, treating as not-exist: ${e.message}"
                    )
                    return false
                }
            }
        }
    }
    
    /**
     * 上传备份文件
     * 使用流式上传避免内存溢出
     */
    suspend fun uploadBackup(file: File, isPermanent: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }
            
            // 检查文件大小，如果文件过大（>50MB），给出警告日志
            val fileSizeMB = file.length() / (1024 * 1024)
            if (fileSizeMB > 50) {
                android.util.Log.w("WebDavHelper", "Large backup file detected: ${fileSizeMB}MB. Consider compressing images first.")
            }
            
            // 创建 Monica 备份目录
            val backupDir = getBackupDirectoryPath()
            if (!webDavPathExists(backupDir)) {
                sardine!!.createDirectory(backupDir)
            }
            
            // 生成带时间戳的文件名，保留加密标识
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val suffix = if (isPermanent) PERMANENT_SUFFIX else ""
            // 根据源文件名判断是否加密，保留 .enc.zip 后缀
            val isEncrypted = file.name.endsWith(".enc.zip")
            val fileName = if (isEncrypted) {
                "monica_backup_$timestamp$suffix.enc.zip"
            } else {
                "monica_backup_$timestamp$suffix.zip"
            }
            val remotePath = "$backupDir/$fileName"
            
            // 使用流式上传避免内存溢出
            // Sardine不直接支持InputStream，使用文件直接上传
            val fileSize = file.length()
            if (fileSize > 100 * 1024 * 1024) { // 大于100MB
                // 对于超大文件，分块读取
                android.util.Log.w("WebDavHelper", "Very large file (${fileSize / 1024 / 1024}MB), may take a while...")
            }
            
            // 使用readBytes但添加内存检查
            try {
                val fileBytes = file.readBytes()
                sardine!!.put(remotePath, fileBytes, "application/zip")
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("WebDavHelper", "Out of memory reading file, trying alternative method", e)
                System.gc()
                throw e
            }
            
            android.util.Log.d("WebDavHelper", "Backup uploaded successfully (${fileSizeMB}MB)")
            
            Result.success(fileName)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("WebDavHelper", "Out of memory while uploading backup", e)
            // 显式请求垃圾回收
            System.gc()
            Result.failure(Exception("备份文件过大，内存不足。请先压缩图片后再试。"))
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Failed to upload backup", e)
            // 将底层异常归一为面向用户的错误消息，同时附带规范化 URL 便于排查
            val message = buildConnectionErrorMessage(
                takagi.ru.monica.webdav.WebDavErrorClassifier.classify(e),
                takagi.ru.monica.webdav.WebDavUrlBuilder.normalizeServer(serverUrl)
            )
            Result.failure(Exception(message, e))
        }
    }
    
    /**
     * 列出所有备份文件
     */
    suspend fun listBackups(): Result<List<BackupFile>> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }
            
            val backupDir = getBackupDirectoryPath()
            
            // 检查目录是否存在
            if (!webDavPathExists(backupDir)) {
                return@withContext Result.success(emptyList())
            }
            
            // 列出目录内容
            val resources = sardine!!.list(backupDir)
            
            val backups = resources
                .filter { !it.isDirectory && it.name.endsWith(".zip") }
                .map { resource ->
                    BackupFile(
                        name = resource.name,
                        path = resource.href.toString(),
                        size = resource.contentLength ?: 0,
                        modified = resource.modified ?: Date()
                    )
                }
                .sortedByDescending { it.modified }
            
            Result.success(backups)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 下载备份文件
     */
    suspend fun downloadBackup(backupFile: BackupFile, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }
            
            val remotePath = getBackupFilePath(backupFile.name)
            
            // 下载文件
            sardine!!.get(remotePath).use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete backups older than 60 days (only temporary ones)
     */
    suspend fun cleanupBackups(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }

            val result = listBackups()
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }

            val backups = result.getOrNull() ?: emptyList()
            var deletedCount = 0

            val expiredBackups = BackupRetentionPolicy.expiredTemporaryBackupsToDelete(backups)
            android.util.Log.i(
                "WebDavHelper",
                "Cleanup scan: total=${backups.size}, " +
                    "temporary=${backups.count { !it.isPermanent }}, candidates=${expiredBackups.size}"
            )

            expiredBackups.forEach { backup ->
                android.util.Log.d("WebDavHelper", "Deleting expired backup: ${backup.name}")
                try {
                    deleteBackup(backup)
                    deletedCount++
                } catch (e: Exception) {
                    android.util.Log.w("WebDavHelper", "Failed to delete expired backup ${backup.name}: ${e.message}")
                }
            }

            Result.success(deletedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark a backup as permanent by renaming it
     */
    suspend fun markBackupAsPermanent(backup: BackupFile): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }

            if (backup.isPermanent) {
                return@withContext Result.success(true)
            }

            val oldPath = getBackupFilePath(backup.name)
            // Insert _permanent before .zip
            val newName = backup.name.replace(".zip", "${PERMANENT_SUFFIX}.zip")
            val newPath = getBackupFilePath(newName)

            sardine!!.move(oldPath, newPath)
            android.util.Log.d("WebDavHelper", "Marked backup as permanent: ${backup.name} -> $newName")
            
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unmark a permanent backup (revert to temporary)
     */
    suspend fun unmarkPermanent(backup: BackupFile): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }

            if (!backup.isPermanent) {
                return@withContext Result.success(true)
            }

            val oldPath = getBackupFilePath(backup.name)
            // Remove _permanent suffix
            val newName = backup.name.replace(PERMANENT_SUFFIX, "")
            val newPath = getBackupFilePath(newName)

            sardine!!.move(oldPath, newPath)
            android.util.Log.d("WebDavHelper", "Unmarked permanent backup: ${backup.name} -> $newName")

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    /**
     * 删除备份文件
     */
    suspend fun deleteBackup(backupFile: BackupFile): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (sardine == null) {
                android.util.Log.e("WebDavHelper", "Delete failed: WebDAV not configured")
                return@withContext Result.failure(Exception("WebDAV not configured"))
            }
            
            val remotePath = getBackupFilePath(backupFile.name)
            android.util.Log.d("WebDavHelper", "Deleting backup: $remotePath")
            
            sardine!!.delete(remotePath)
            
            android.util.Log.d("WebDavHelper", "Backup deleted successfully: ${backupFile.name}")
            Result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Delete failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
    
    /**
     * 获取加密配置
     */
    data class EncryptionConfig(
        val enabled: Boolean,
        val password: String
    )

    fun getEncryptionConfig(): EncryptionConfig {
        return EncryptionConfig(enableEncryption, encryptionPassword)
    }

    /**
     * 设置加密配置
     */
    fun setEncryptionConfig(enabled: Boolean, password: String) {
        enableEncryption = enabled
        encryptionPassword = password
        saveConfig()
    }

    /**
     * 当前的 HTTPS 证书校验档位。
     */
    fun getTlsMode(): takagi.ru.monica.webdav.WebDavTlsMode =
        takagi.ru.monica.webdav.WebDavTlsSettings.currentMode(context)

    /**
     * 切换 HTTPS 证书校验档位。
     *
     * 档位影响 OkHttp 客户端的构造，因此已建立的 sardine 实例必须重建，
     * 否则新档位只会在下次冷启动后生效。
     */
    fun setTlsMode(mode: takagi.ru.monica.webdav.WebDavTlsMode) {
        takagi.ru.monica.webdav.WebDavTlsSettings.setMode(context, mode)
        if (serverUrl.isNotEmpty()) {
            sardine = createSardineClient()
        }
        android.util.Log.d("WebDavHelper", "TLS verification mode set to $mode")
    }

    private fun migrateLegacyConfigIfNeeded(prefs: android.content.SharedPreferences) {
        val legacyServerUrl = prefs.getString(KEY_SERVER_URL, null)
        val legacyUsername = prefs.getString(KEY_USERNAME, null)
        val legacyPassword = prefs.getString(KEY_PASSWORD, null)
        val legacyEncryptionPassword = prefs.getString(KEY_ENCRYPTION_PASSWORD, null)
        val hasLegacyValues =
            !legacyServerUrl.isNullOrBlank() ||
                !legacyUsername.isNullOrBlank() ||
                !legacyPassword.isNullOrBlank() ||
                !legacyEncryptionPassword.isNullOrBlank()
        if (!hasLegacyValues) return

        if (securityManager.getProtectedString(SECURE_KEY_SERVER_URL).isNullOrBlank()) {
            securityManager.putProtectedString(SECURE_KEY_SERVER_URL, legacyServerUrl)
        }
        if (securityManager.getProtectedString(SECURE_KEY_USERNAME).isNullOrBlank()) {
            securityManager.putProtectedString(SECURE_KEY_USERNAME, legacyUsername)
        }
        if (securityManager.getProtectedString(SECURE_KEY_PASSWORD).isNullOrBlank()) {
            securityManager.putProtectedString(SECURE_KEY_PASSWORD, legacyPassword)
        }
        if (securityManager.getProtectedString(SECURE_KEY_ENCRYPTION_PASSWORD).isNullOrBlank()) {
            securityManager.putProtectedString(
                SECURE_KEY_ENCRYPTION_PASSWORD,
                legacyEncryptionPassword
            )
        }

        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_ENCRYPTION_PASSWORD)
            .apply()
    }

    private fun encryptSensitiveBackupValue(value: String?, backupEncryptPassword: String?): String? {
        val sanitizedValue = value?.takeIf { it.isNotBlank() } ?: return null
        val password = backupEncryptPassword ?: return null
        return EncryptionHelper.encryptString(sanitizedValue, password)
    }

    private fun decryptBackupValueWithLegacyFallback(value: String?, decryptPassword: String?): String? {
        val sanitizedValue = value?.takeIf { it.isNotBlank() } ?: return null
        val candidatePasswords = buildList {
            decryptPassword?.takeIf { it.isNotBlank() }?.let(::add)
            if (decryptPassword.isNullOrBlank()) {
                add(LEGACY_WEBDAV_BACKUP_FALLBACK_KEY)
            }
        }

        var lastError: Exception? = null
        for (candidate in candidatePasswords) {
            try {
                return EncryptionHelper.decryptString(sanitizedValue, candidate)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("No backup decryption password available")
    }

    private fun decryptSensitiveBackupValue(value: String?, backupEncryptPassword: String?): String? {
        return decryptBackupValueWithLegacyFallback(value, backupEncryptPassword)
    }

    private suspend fun restoreBitwardenVaultBackups(
        backups: List<BitwardenVaultBackupEntry>,
        decryptPassword: String?,
    ): Int {
        if (backups.isEmpty()) return 0

        val database = PasswordDatabase.getDatabase(context)
        val vaultDao = database.bitwardenVaultDao()
        val bitwardenRepository = takagi.ru.monica.bitwarden.repository.BitwardenRepository.getInstance(context)
        var restoredCount = 0
        var firstRestoredId: Long? = null
        var defaultVaultId: Long? = null

        backups.forEach { backup ->
            val displayEmail = backup.email.trim()
            if (displayEmail.isEmpty()) {
                return@forEach
            }

            try {
                val canonicalEmail = takagi.ru.monica.bitwarden.BitwardenVaultIdentity
                    .canonicalizeEmail(displayEmail)
                val serverUrl = backup.serverUrl.trim().ifEmpty { "https://vault.bitwarden.com" }
                val accountKey = takagi.ru.monica.bitwarden.BitwardenVaultIdentity.buildAccountKey(
                    serverUrl = serverUrl,
                    userId = backup.userId,
                    canonicalEmail = canonicalEmail
                )
                val existingVault = vaultDao.getVaultByAccountKey(accountKey)
                    ?: vaultDao.getVaultByServerAndCanonicalEmail(serverUrl, canonicalEmail)
                val backupId = backup.id.takeIf { it > 0 }
                val conflictingVault = backupId
                    ?.let { candidateId -> vaultDao.getVaultById(candidateId) }
                    ?.takeIf { candidateVault ->
                        candidateVault.id != existingVault?.id &&
                            candidateVault.accountKey != accountKey
                    }
                val targetId = when {
                    existingVault != null -> existingVault.id
                    conflictingVault == null -> backupId ?: 0L
                    else -> 0L
                }
                val defaultKdfIterations = when (backup.kdfType) {
                    takagi.ru.monica.data.bitwarden.BitwardenVault.KDF_TYPE_ARGON2ID ->
                        takagi.ru.monica.data.bitwarden.BitwardenVault.DEFAULT_ARGON2_ITERATIONS
                    else -> takagi.ru.monica.data.bitwarden.BitwardenVault.DEFAULT_PBKDF2_ITERATIONS
                }
                val now = System.currentTimeMillis()
                val restoredVault = takagi.ru.monica.data.bitwarden.BitwardenVault(
                    id = targetId,
                    email = displayEmail,
                    canonicalEmail = canonicalEmail,
                    userId = backup.userId?.trim()?.ifBlank { null },
                    accountKey = accountKey,
                    displayName = backup.displayName?.trim()?.ifBlank { null },
                    serverUrl = serverUrl,
                    identityUrl = backup.identityUrl.trim().ifEmpty { "https://identity.bitwarden.com" },
                    apiUrl = backup.apiUrl.trim().ifEmpty { "https://api.bitwarden.com" },
                    eventsUrl = backup.eventsUrl?.trim()?.ifBlank { null },
                    encryptedAccessToken = decryptSensitiveBackupValue(backup.encryptedAccessToken, decryptPassword),
                    encryptedRefreshToken = decryptSensitiveBackupValue(backup.encryptedRefreshToken, decryptPassword),
                    accessTokenExpiresAt = backup.accessTokenExpiresAt,
                    encryptedMasterKey = decryptSensitiveBackupValue(backup.encryptedMasterKey, decryptPassword),
                    encryptedEncKey = decryptSensitiveBackupValue(backup.encryptedEncKey, decryptPassword),
                    encryptedMacKey = decryptSensitiveBackupValue(backup.encryptedMacKey, decryptPassword),
                    kdfType = backup.kdfType,
                    kdfIterations = backup.kdfIterations.takeIf { it > 0 } ?: defaultKdfIterations,
                    kdfMemory = backup.kdfMemory,
                    kdfParallelism = backup.kdfParallelism,
                    lastSyncAt = backup.lastSyncAt,
                    lastFullSyncAt = backup.lastFullSyncAt,
                    revisionDate = backup.revisionDate?.trim()?.ifBlank { null },
                    isDefault = false,
                    isLocked = true,
                    isConnected = backup.isConnected,
                    syncEnabled = backup.syncEnabled,
                    createdAt = backup.createdAt.takeIf { it > 0 } ?: existingVault?.createdAt ?: now,
                    updatedAt = now,
                )

                val restoredId = if (existingVault != null) {
                    vaultDao.update(restoredVault.copy(id = existingVault.id, createdAt = existingVault.createdAt))
                    existingVault.id
                } else {
                    vaultDao.insert(
                        if (targetId > 0) {
                            restoredVault
                        } else {
                            restoredVault.copy(id = 0)
                        }
                    )
                }

                bitwardenRepository.forceLock(restoredId)
                if (firstRestoredId == null) {
                    firstRestoredId = restoredId
                }
                if (backup.isDefault) {
                    defaultVaultId = restoredId
                }
                restoredCount++
            } catch (e: Exception) {
                android.util.Log.w(
                    "WebDavHelper",
                    "Failed to restore Bitwarden vault ${displayEmail}: ${e.message}"
                )
            }
        }

        when {
            defaultVaultId != null -> vaultDao.setAsDefault(defaultVaultId!!)
            firstRestoredId != null && vaultDao.getDefaultVault() == null -> vaultDao.setAsDefault(firstRestoredId!!)
        }

        return restoredCount
    }
}

/**
 * Backup file info
 */
data class BackupFile(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Date
) {
    val isPermanent: Boolean
        get() = name.contains("_permanent")
    
    val isExpiring: Boolean
        get() {
            if (isPermanent) return false
            // Expiring if older than 50 days (10 days left until 60 days limit)
            val fiftyDaysAgo = System.currentTimeMillis() - (50L * 24 * 60 * 60 * 1000)
            return modified.time < fiftyDaysAgo
        }

    /**
     * 判断是否为加密文件
     */
    fun isEncrypted(): Boolean {
        return name.endsWith(".enc.zip")
    }
}

data class BackupContent(
    val passwords: List<PasswordEntry>,
    val secureItems: List<DataExportImportManager.ExportItem>,
    val passkeys: List<PasskeyEntry> = emptyList(),
    val steamMaFiles: List<SteamMaFilePayload> = emptyList(),
    val customFieldsMap: Map<Long, List<CustomFieldBackupEntry>> = emptyMap(),
    val passwordHistory: List<PasswordHistoryBackupEntry> = emptyList(),
    val portableAttachments: takagi.ru.monica.attachments.backup.PortableAttachmentBackup.RestorePlan =
        takagi.ru.monica.attachments.backup.PortableAttachmentBackup.RestorePlan(),
    /**
     * 从备份 zip 里解析出来的 LOCAL 附件清单。
     *
     * 仅包含元数据——对应的密文 blob 在 zip 扫描阶段已经拷到 `filesDir/secure_attachments/`。
     * 实际写入 attachments 表的时机是 [BackupRestoreApplier.applyRestoreResult]，需要等
     * 密码拿到新 id 之后按 `passwordIdMap` 重映射 `parentPasswordId`。
     */
    val attachments: List<takagi.ru.monica.attachments.backup.AttachmentBackupCodec.Entry> = emptyList()
)

/**
 * 恢复结果 - 包含恢复的内容和详细报告
 */
data class RestoreResult(
    val content: BackupContent,
    val report: RestoreReport,
    val monicaConfigDetected: Boolean = false,
    val monicaConfigRestoreSkipped: Boolean = false,
    val restoredMonicaConfigEntries: Int = 0,
    val restartRecommended: Boolean = false,
)


/**
 * 检查网络和时间同步状态
 */
private fun checkNetworkAndTimeSync(context: Context) {
    try {
        // 检查网络连接
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        
        if (activeNetworkInfo == null || !activeNetworkInfo.isConnected) {
            android.util.Log.w("WebDavHelper", "Network not available, some features may not work properly")
            // 显示网络不可用提示
            android.util.Log.w("WebDavHelper", "网络连接不可用，部分功能可能受限")
        }
        
        // 检查时间同步问题
        try {
            val currentTime = System.currentTimeMillis()
            // 检查时间是否合理 (2001年以后)
            if (currentTime < 1000000000000L) {
                android.util.Log.w("WebDavHelper", "System time appears incorrect, using default time")
                // 使用应用内的时间逻辑
            }
        } catch (e: Exception) {
            android.util.Log.e("WebDavHelper", "Error checking time", e)
        }
    } catch (e: Exception) {
        android.util.Log.e("WebDavHelper", "Error checking network and time sync", e)
    }
}

/**
 * 为用户获取系统服务
 */
private fun getSystemServiceForUser(context: Context, serviceName: String): Any? {
    try {
        // 确保在访问系统服务时提供正确的用户上下文
        return context.getSystemService(serviceName)
    } catch (e: Exception) {
        android.util.Log.e("WebDavHelper", "Error getting system service for user", e)
        // 降级到普通方式
        return context.getSystemService(serviceName)
    }
}



