package takagi.ru.monica.passkey

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.Category
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.PasskeyBinding
import takagi.ru.monica.data.model.PasskeyBindingCodec
import takagi.ru.monica.keepass.KeePassPasskeyCreateExecutor
import takagi.ru.monica.keepass.KeePassPasskeyDeleteExecutor
import takagi.ru.monica.repository.KeePassCompatibilityBridge
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.repository.MdbxRepository
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.MdbxStoredFolderEntry
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.MasterPasswordDialog
import takagi.ru.monica.ui.components.PasswordEntryPickerBottomSheet
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.ui.components.UnifiedMoveToCategoryBottomSheet
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.DeviceUtils
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.decodeKeePassPathForDisplay
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Passkey 创建确认 Activity
 * 
 * 当用户在网站/应用中选择使用 Monica 创建 Passkey 时显示
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateActivity : FragmentActivity() {
    
    companion object {
        private const val TAG = "PasskeyCreateActivity"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        
        // Monica authenticator AAGUID.
        // Keep this stable forever. Relying parties may display authenticator brand from AAGUID.
        // Existing passkeys registered with previous AAGUID remain valid; this only affects new registrations.
        // 6d6f6e69-6361-4d33-a001-706173736b79
        val MONICA_AAGUID = byteArrayOf(
            0x6d.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x69.toByte(),
            0x63.toByte(), 0x61.toByte(), 0x4d.toByte(), 0x33.toByte(),
            0xa0.toByte(), 0x01.toByte(), 0x70.toByte(), 0x61.toByte(),
            0x73.toByte(), 0x73.toByte(), 0x6b.toByte(), 0x79.toByte()
        )
    }
    
    private val database: PasswordDatabase by lazy {
        PasswordDatabase.getDatabase(applicationContext)
    }
    
    private val biometricHelper: BiometricAuthHelper by lazy {
        BiometricAuthHelper(this)
    }
    
    private val mdbxVaultStore: MdbxRepository by lazy {
        MdbxRepositoryFactory.create(
            context = applicationContext,
            database = database,
            securityManager = securityManager
        )
    }

    private val repository: PasskeyRepository by lazy {
        PasskeyRepository(database.passkeyDao(), mdbxVaultStore, applicationContext)
    }

    private val securityManager: SecurityManager by lazy {
        SecurityManager(applicationContext)
    }

    private val keepassBridge: KeePassCompatibilityBridge by lazy {
        KeePassCompatibilityBridge(
            KeePassWorkspaceRepository(
                context = applicationContext,
                dao = database.localKeePassDatabaseDao(),
                securityManager = securityManager
            )
        )
    }

    private val keepassPasskeyCreateExecutor by lazy {
        KeePassPasskeyCreateExecutor(keepassBridge)
    }

    private val keepassPasskeyDeleteExecutor by lazy {
        KeePassPasskeyDeleteExecutor(keepassBridge)
    }

    private val showMasterPasswordDialog = mutableStateOf(false)
    private val masterPasswordError = mutableStateOf(false)
    
    // 存储待处理的请求数据
    private var pendingRequestJson: String = ""
    private var pendingRpId: String = ""
    private var pendingUserName: String = ""
    private var pendingUserDisplayName: String = ""
    private var pendingCallingAppInfo: CallingAppInfo? = null
    private var pendingClientDataHash: ByteArray? = null
    private var pendingBoundPasswordId: Long? = null
    private var pendingCategoryId: Long? = null
    private var pendingKeepassDatabaseId: Long? = null
    private var pendingKeepassGroupPath: String? = null
    private var pendingBitwardenVaultId: Long? = null
    private var pendingBitwardenFolderId: String? = null
    private var pendingMdbxDatabaseId: Long? = null
    private var pendingMdbxFolderId: String? = null

    private data class ResolvedStorageTarget(
        val categoryId: Long?,
        val keepassDatabaseId: Long?,
        val keepassGroupPath: String?,
        val bitwardenVaultId: Long?,
        val bitwardenFolderId: String?,
        val mdbxDatabaseId: Long? = null,
        val mdbxFolderId: String? = null
    )
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "PasskeyCreateActivity onCreate")
        
        // 首先尝试从 PendingIntentHandler 获取请求（这是正确的方式）
        val providerRequest = try {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve ProviderCreateCredentialRequest", e)
            null
        }
        
        if (providerRequest == null) {
            Log.e(TAG, "providerRequest is null - this should not happen!")
        } else {
            Log.i(TAG, "providerRequest retrieved successfully")
            pendingCallingAppInfo = providerRequest.callingAppInfo
            Log.d(TAG, "CallingAppInfo: $pendingCallingAppInfo")
            Log.d(TAG, "CallingAppInfo origin: ${PasskeyBrowserOrigin.read(this, pendingCallingAppInfo)}")
            Log.d(TAG, "CallingAppInfo packageName: ${pendingCallingAppInfo?.packageName}")
            
            val callingRequest = providerRequest.callingRequest
            if (callingRequest is CreatePublicKeyCredentialRequest) {
                pendingClientDataHash = callingRequest.clientDataHash
                Log.d(TAG, "clientDataHash: ${pendingClientDataHash?.contentToString()}")
            }
        }

        val requestJson = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_REQUEST_JSON) ?: ""
        val rpId = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_RP_ID) ?: ""
        val userName = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_USER_NAME) ?: ""
        val userDisplayName = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_USER_DISPLAY_NAME) ?: userName

        recordPasskeyEvent(
            stage = "request_received",
            requestJson = requestJson,
            rpId = rpId,
        )
        
        Log.i(
            TAG,
            "request loaded: requestJsonSize=${requestJson.length}, rpIdLen=${rpId.length}, userNameLen=${userName.length}"
        )
        
        // 检查必要参数
        if (requestJson.isBlank()) {
            Log.e(TAG, "requestJson is empty!")
            recordPasskeyEvent(
                stage = "request_rejected",
                requestJson = requestJson,
                rpId = rpId,
                errorType = "CreateCredentialUnknownException",
                errorMessage = "Missing request JSON",
            )
            val resultIntent = Intent()
            PendingIntentHandler.setCreateCredentialException(
                resultIntent,
                CreateCredentialUnknownException("Missing request JSON")
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }
        
        if (rpId.isBlank()) {
            Log.e(TAG, "rpId is empty!")
            recordPasskeyEvent(
                stage = "request_rejected",
                requestJson = requestJson,
                rpId = rpId,
                errorType = "CreateCredentialUnknownException",
                errorMessage = "Missing RP ID",
            )
            val resultIntent = Intent()
            PendingIntentHandler.setCreateCredentialException(
                resultIntent,
                CreateCredentialUnknownException("Missing RP ID")
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }

        val shadowEnabled = PasskeyValidationFlags.isShadowValidationEnabled(this)
        val strictEnabled = PasskeyValidationFlags.isStrictValidationEnabled(this)
        if (shadowEnabled || strictEnabled) {
            val verdict = PasskeyRequestValidator.validate(
                context = this,
                requestJson = requestJson,
                rpId = rpId,
                callingAppInfo = pendingCallingAppInfo
            )
            recordPasskeyEvent(
                stage = "validation_done",
                requestJson = requestJson,
                rpId = rpId,
                verdict = verdict,
            )
            PasskeyRequestValidator.logShadow(
                flowTag = "CREATE",
                rpId = rpId,
                callingPackage = pendingCallingAppInfo?.packageName,
                verdict = verdict
            )
            PasskeyValidationDiagnostics.record(
                context = this,
                flowTag = "CREATE",
                rpId = rpId,
                callingPackage = pendingCallingAppInfo?.packageName,
                verdict = verdict
            )
            repository.logAudit(
                "PASSKEY_CREATE_VALIDATION",
                "rpId=$rpId|source=${verdict.resolvedSource}|reasons=${verdict.reasons.joinToString(",")}"
            )
            if (strictEnabled && verdict.strictBlock) {
                recordPasskeyEvent(
                    stage = "request_blocked",
                    requestJson = requestJson,
                    rpId = rpId,
                    verdict = verdict,
                    errorType = "CreateCredentialUnknownException",
                    errorMessage = "Passkey request validation failed",
                )
                val resultIntent = Intent()
                PendingIntentHandler.setCreateCredentialException(
                    resultIntent,
                    CreateCredentialUnknownException("Passkey request validation failed")
                )
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
                return
            }
        }
        
        Log.i(TAG, "Creating passkey for $rpId / $userName")
        
        // 保存待处理的数据
        pendingRequestJson = requestJson
        pendingRpId = rpId
        pendingUserName = userName
        pendingUserDisplayName = userDisplayName
        
        setContent {
            val settingsManager = remember { SettingsManager(this@PasskeyCreateActivity) }
            val settings by settingsManager.settingsFlow.collectAsState(
                initial = AppSettings(biometricEnabled = false)
            )
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDarkTheme
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MonicaTheme(
                darkTheme = darkTheme,
                colorScheme = settings.colorScheme,
                customPrimaryColor = settings.customPrimaryColor,
                customSecondaryColor = settings.customSecondaryColor,
                customTertiaryColor = settings.customTertiaryColor,
                customNeutralColor = settings.customNeutralColor,
                customNeutralVariantColor = settings.customNeutralVariantColor
            ) {
                var showPasswordPicker by remember { mutableStateOf(false) }
                val passwords by database.passwordEntryDao().getAllPasswordEntries()
                    .collectAsState(initial = emptyList())
                val categories by database.categoryDao().getAllCategories()
                    .collectAsState(initial = emptyList())
                val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases()
                    .collectAsState(initial = emptyList())
                val mdbxDatabases by database.localMdbxDatabaseDao().getAvailableDatabases()
                    .collectAsState(initial = emptyList())
                var selectedCategoryId by remember { mutableStateOf<Long?>(pendingCategoryId) }
                var selectedKeePassDatabaseId by remember { mutableStateOf<Long?>(pendingKeepassDatabaseId) }
                var selectedKeePassGroupPath by remember { mutableStateOf<String?>(pendingKeepassGroupPath) }
                var selectedBitwardenVaultId by remember { mutableStateOf<Long?>(pendingBitwardenVaultId) }
                var selectedBitwardenFolderId by remember { mutableStateOf<String?>(pendingBitwardenFolderId) }
                var selectedMdbxDatabaseId by remember { mutableStateOf<Long?>(pendingMdbxDatabaseId) }
                var selectedMdbxFolderId by remember { mutableStateOf<String?>(pendingMdbxFolderId) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
                val securityManagerForKeePass = remember { SecurityManager(context) }
                val keePassService = remember {
                    KeePassKdbxService(
                        context,
                        database.localKeePassDatabaseDao(),
                        securityManagerForKeePass
                    )
                }
                val keepassGroupFlows = remember {
                    mutableMapOf<Long, kotlinx.coroutines.flow.MutableStateFlow<List<takagi.ru.monica.utils.KeePassGroupInfo>>>()
                }
                val getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>> = remember {
                    { databaseId ->
                        val flow = keepassGroupFlows.getOrPut(databaseId) {
                            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                        }
                        if (flow.value.isEmpty()) {
                            scope.launch {
                                flow.value = keePassService.listGroups(databaseId).getOrDefault(emptyList())
                            }
                        }
                        flow
                    }
                }
                val mdbxFolderFlows = remember {
                    mutableMapOf<Long, kotlinx.coroutines.flow.MutableStateFlow<List<MdbxStoredFolderEntry>>>()
                }
                val getMdbxFolders: (Long) -> kotlinx.coroutines.flow.Flow<List<MdbxStoredFolderEntry>> = remember {
                    { databaseId ->
                        val flow = mdbxFolderFlows.getOrPut(databaseId) {
                            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                        }
                        if (flow.value.isEmpty()) {
                            scope.launch {
                                flow.value = runCatching {
                                    mdbxVaultStore.listFolders(databaseId)
                                }.getOrDefault(emptyList())
                            }
                        }
                        flow
                    }
                }

                PasskeyCreateScreen(
                    rpId = rpId,
                    rpName = parseRpName(requestJson, rpId),
                    userName = userName,
                    userDisplayName = userDisplayName,
                    keepassDatabases = keepassDatabases,
                    mdbxDatabases = mdbxDatabases,
                    selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                    selectedKeePassGroupPath = selectedKeePassGroupPath,
                    onKeePassDatabaseSelected = {
                        selectedKeePassDatabaseId = it
                        selectedKeePassGroupPath = null
                        if (it != null) {
                            selectedBitwardenVaultId = null
                            selectedBitwardenFolderId = null
                            selectedCategoryId = null
                            selectedMdbxDatabaseId = null
                            selectedMdbxFolderId = null
                        }
                    },
                    onKeePassGroupPathSelected = { selectedKeePassGroupPath = it },
                    bitwardenVaults = bitwardenVaults,
                    selectedBitwardenVaultId = selectedBitwardenVaultId,
                    selectedBitwardenFolderId = selectedBitwardenFolderId,
                    onBitwardenVaultSelected = {
                        selectedBitwardenVaultId = it
                        selectedBitwardenFolderId = null
                        if (it != null) {
                            selectedKeePassDatabaseId = null
                            selectedKeePassGroupPath = null
                            selectedCategoryId = null
                            selectedMdbxDatabaseId = null
                            selectedMdbxFolderId = null
                        }
                    },
                    onBitwardenFolderSelected = { selectedBitwardenFolderId = it },
                    selectedMdbxDatabaseId = selectedMdbxDatabaseId,
                    selectedMdbxFolderId = selectedMdbxFolderId,
                    onMdbxDatabaseSelected = {
                        selectedMdbxDatabaseId = it
                        if (it == null) {
                            selectedMdbxFolderId = null
                        } else {
                            selectedKeePassDatabaseId = null
                            selectedKeePassGroupPath = null
                            selectedBitwardenVaultId = null
                            selectedBitwardenFolderId = null
                            selectedCategoryId = null
                        }
                    },
                    onMdbxFolderSelected = { selectedMdbxFolderId = it },
                    categories = categories,
                    selectedCategoryId = selectedCategoryId,
                    onCategorySelected = {
                        selectedCategoryId = it
                        if (it != null) {
                            selectedKeePassDatabaseId = null
                            selectedKeePassGroupPath = null
                            selectedBitwardenVaultId = null
                            selectedBitwardenFolderId = null
                            selectedMdbxDatabaseId = null
                            selectedMdbxFolderId = null
                        }
                    },
                    getKeePassGroups = getKeePassGroups,
                    getMdbxFolders = getMdbxFolders,
                    onCreateDirect = {
                        pendingBoundPasswordId = null
                        pendingCategoryId = selectedCategoryId
                        pendingKeepassDatabaseId = selectedKeePassDatabaseId
                        pendingKeepassGroupPath = selectedKeePassGroupPath
                        pendingBitwardenVaultId = selectedBitwardenVaultId
                        pendingBitwardenFolderId = selectedBitwardenFolderId
                        pendingMdbxDatabaseId = selectedMdbxDatabaseId
                        pendingMdbxFolderId = selectedMdbxFolderId
                        requestPasskeyUserVerificationBeforeCreate()
                    },
                    onBindToPassword = {
                        showPasswordPicker = true
                    },
                    onCancel = {
                        repository.logAudit("PASSKEY_CREATE_CANCELLED", rpId)
                        // 用户取消，使用 PendingIntentHandler 设置取消异常
                        val resultIntent = Intent()
                        PendingIntentHandler.setCreateCredentialException(
                            resultIntent,
                            CreateCredentialCancellationException("User cancelled")
                        )
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    }
                )

                PasswordEntryPickerBottomSheet(
                    visible = showPasswordPicker,
                    title = stringResource(R.string.select_password_to_bind),
                    passwords = passwords.filter { !it.isDeleted && !it.isArchived },
                    selectedEntryId = pendingBoundPasswordId,
                    onDismiss = {
                        pendingBoundPasswordId = null
                        showPasswordPicker = false
                    },
                    onSelect = { password ->
                        val resolvedTarget = resolveStorageFromPassword(password)
                        pendingBoundPasswordId = password.id
                        selectedCategoryId = resolvedTarget.categoryId
                        selectedKeePassDatabaseId = resolvedTarget.keepassDatabaseId
                        selectedKeePassGroupPath = resolvedTarget.keepassGroupPath
                        selectedBitwardenVaultId = resolvedTarget.bitwardenVaultId
                        selectedBitwardenFolderId = resolvedTarget.bitwardenFolderId
                        selectedMdbxDatabaseId = resolvedTarget.mdbxDatabaseId
                        selectedMdbxFolderId = resolvedTarget.mdbxFolderId
                        pendingCategoryId = selectedCategoryId
                        pendingKeepassDatabaseId = selectedKeePassDatabaseId
                        pendingKeepassGroupPath = selectedKeePassGroupPath
                        pendingBitwardenVaultId = selectedBitwardenVaultId
                        pendingBitwardenFolderId = selectedBitwardenFolderId
                        pendingMdbxDatabaseId = selectedMdbxDatabaseId
                        pendingMdbxFolderId = selectedMdbxFolderId
                        showPasswordPicker = false
                        requestPasskeyUserVerificationBeforeCreate()
                    }
                )

                if (showMasterPasswordDialog.value) {
                    MasterPasswordDialog(
                        onDismiss = {
                            showMasterPasswordDialog.value = false
                            masterPasswordError.value = false
                        },
                        onConfirm = { password ->
                            if (securityManager.verifyMasterPassword(password)) {
                                masterPasswordError.value = false
                                showMasterPasswordDialog.value = false
                                repository.logAudit("PASSKEY_CREATE_MASTER_PASSWORD_SUCCESS", pendingRpId)
                                recordPasskeyEvent(stage = "master_password_success")
                                createPasskey(
                                    pendingRequestJson,
                                    pendingRpId,
                                    pendingUserName,
                                    pendingUserDisplayName,
                                )
                            } else {
                                masterPasswordError.value = true
                                repository.logAudit("PASSKEY_CREATE_MASTER_PASSWORD_FAILED", pendingRpId)
                                recordPasskeyEvent(
                                    stage = "master_password_failed",
                                    errorType = "MasterPasswordVerificationFailed",
                                    errorMessage = "Invalid master password",
                                )
                            }
                        },
                        isError = masterPasswordError.value,
                    )
                }
            }
        }
    }

    private fun requestPasskeyUserVerificationBeforeCreate() {
        val settings = runBlocking {
            SettingsManager(applicationContext).settingsFlow.first()
        }
        val shouldBypassBiometric = PasskeyBiometricCompatibilityPolicy.shouldBypassBiometricForPasskey(
            romType = DeviceUtils.getROMType(),
            isBypassEnabled = settings.passkeyHyperOsBiometricBypassEnabled,
            hasHyperOsSystemProperty = DeviceUtils.isHyperOsSystemPropertyPresent(),
        )

        if (!shouldBypassBiometric) {
            requestBiometricAuth()
            return
        }

        repository.logAudit("PASSKEY_CREATE_BIOMETRIC_BYPASSED_HYPER_OS", pendingRpId)
        recordPasskeyEvent(stage = "biometric_bypassed_hyperos")

        if (securityManager.isMasterPasswordSet()) {
            showMasterPasswordDialog.value = true
            return
        }

        createPasskey(
            pendingRequestJson,
            pendingRpId,
            pendingUserName,
            pendingUserDisplayName,
        )
    }
    
    /**
     * 请求生物识别验证
     * 只有通过生物识别后才能创建 Passkey
     */
    private fun requestBiometricAuth() {
        repository.logAudit("PASSKEY_CREATE_BIOMETRIC_REQUESTED", pendingRpId)
        recordPasskeyEvent(stage = "biometric_requested")
        
        biometricHelper.authenticate(
            activity = this,
            title = getString(R.string.biometric_title_passkey_create),
            subtitle = getString(R.string.biometric_subtitle_passkey_create, pendingRpId),
            negativeButtonText = getString(R.string.cancel),
            onSuccess = {
                repository.logAudit("PASSKEY_CREATE_BIOMETRIC_SUCCESS", pendingRpId)
                recordPasskeyEvent(stage = "biometric_success")
                createPasskey(pendingRequestJson, pendingRpId, pendingUserName, pendingUserDisplayName)
            },
            onError = { errorCode, errString ->
                repository.logAudit("PASSKEY_CREATE_BIOMETRIC_FAILED", 
                    "$pendingRpId|error=$errorCode|$errString")
                Log.e(TAG, "Biometric auth failed: $errorCode - $errString")
                recordPasskeyEvent(
                    stage = "biometric_failed",
                    errorType = "BiometricError:$errorCode",
                    errorMessage = errString.toString(),
                )
                // 生物识别失败，必须使用 PendingIntentHandler 设置异常响应
                val resultIntent = Intent()
                PendingIntentHandler.setCreateCredentialException(
                    resultIntent,
                    CreateCredentialUnknownException("Biometric authentication failed: $errString")
                )
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            },
            onCancel = {
                repository.logAudit("PASSKEY_CREATE_BIOMETRIC_CANCELLED", pendingRpId)
                Log.d(TAG, "Biometric auth cancelled by user")
                recordPasskeyEvent(
                    stage = "biometric_cancelled",
                    errorType = "CreateCredentialCancellationException",
                    errorMessage = "User cancelled",
                )
                // 用户取消，使用 PendingIntentHandler 设置取消异常
                val resultIntent = Intent()
                PendingIntentHandler.setCreateCredentialException(
                    resultIntent,
                    CreateCredentialCancellationException("User cancelled")
                )
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        )
    }
    
    private fun parseRpName(requestJson: String, defaultRpId: String): String {
        return try {
            val json = JSONObject(requestJson)
            val rpJson = json.optJSONObject("rp")
            rpJson?.optString("name") ?: defaultRpId
        } catch (e: Exception) {
            defaultRpId
        }
    }

    /**
     * 与主程序通行密钥绑定页保持一致：位置目标互斥（Monica/KeePass/Bitwarden 三选一）。
     */
    private fun resolveStorageFromPassword(password: PasswordEntry): ResolvedStorageTarget {
        return when {
            password.bitwardenVaultId != null -> ResolvedStorageTarget(
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                bitwardenVaultId = password.bitwardenVaultId,
                bitwardenFolderId = password.bitwardenFolderId
            )
            password.keepassDatabaseId != null -> ResolvedStorageTarget(
                categoryId = null,
                keepassDatabaseId = password.keepassDatabaseId,
                keepassGroupPath = password.keepassGroupPath,
                bitwardenVaultId = null,
                bitwardenFolderId = null
            )
            password.mdbxDatabaseId != null -> ResolvedStorageTarget(
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                bitwardenVaultId = null,
                bitwardenFolderId = null,
                mdbxDatabaseId = password.mdbxDatabaseId,
                mdbxFolderId = password.mdbxFolderId
            )
            else -> ResolvedStorageTarget(
                categoryId = password.categoryId,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                bitwardenVaultId = null,
                bitwardenFolderId = null
            )
        }
    }
    
    private fun createPasskey(
        requestJson: String,
        rpId: String,
        userName: String,
        userDisplayName: String
    ) {
        var createdCredentialIdForRollback: String? = null
        var boundPasswordIdForRollback: Long? = null
        try {
            recordPasskeyEvent(
                stage = "create_started",
                requestJson = requestJson,
                rpId = rpId,
            )
            val json = JSONObject(requestJson)
            
            // 解析用户 ID
            val userJson = json.optJSONObject("user")
            val userIdB64 = userJson?.optString("id") ?: ""
            
            // 解析 challenge
            val challengeB64 = json.optString("challenge")
            
            // 解析算法偏好
            val pubKeyCredParams = json.optJSONArray("pubKeyCredParams")
            val algorithm = getPreferredAlgorithm(pubKeyCredParams)
                ?: throw IllegalStateException(
                    "No supported algorithm in pubKeyCredParams: ${extractRequestedAlgorithms(pubKeyCredParams).joinToString(",")}"
                )
            
            // 生成凭据 ID
            val credentialId = generateCredentialId()
            val credentialIdB64 = Base64.encodeToString(
                credentialId, 
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            
            // 根据调用方请求算法生成密钥对，并构建对应 COSE 公钥
            val keyPair = generateKeyPairForAlgorithm(algorithm)
            val cosePublicKey = createCosePublicKeyFromKeyPair(
                keyPair = keyPair,
                algorithm = algorithm
            )
            
            // 创建 attestation object
            val authenticatorData = createAuthenticatorData(
                rpId = rpId,
                credentialId = credentialId,
                cosePublicKey = cosePublicKey,
                signCount = 0
            )
            
            // 创建 attestation object (使用 "none" attestation)
            val attestationObject = createAttestationObject(authenticatorData)
            
            // 无论调用方是否提供 clientDataHash，始终回传真实 clientDataJSON，
            // 避免部分调用方因 placeholder 拒绝注册响应。
            val clientDataJsonBytes = buildCreateClientDataJsonBytes(
                requestJson = requestJson,
                challengeB64 = challengeB64,
                rpId = rpId
            )
            val clientDataJsonValue = Base64.encodeToString(
                clientDataJsonBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            pendingClientDataHash?.let { providedHash ->
                val computedHash = MessageDigest.getInstance("SHA-256").digest(clientDataJsonBytes)
                if (!providedHash.contentEquals(computedHash)) {
                    Log.w(TAG, "Provided clientDataHash differs from locally built clientDataJSON hash")
                }
            }
            
            // Encode the key for compatibility, then store only a protected reference in Room.
            val privateKeyB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
            val publicKeyB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

            val discoverable = parseDiscoverable(requestJson)
            val normalizedRpId = PasskeyRpIdNormalizer.normalize(rpId) ?: rpId
            val resolvedBoundTarget = kotlinx.coroutines.runBlocking {
                pendingBoundPasswordId
                    ?.let { passwordId -> database.passwordEntryDao().getPasswordEntryById(passwordId) }
                    ?.let(::resolveStorageFromPassword)
            }
            val initialCategoryId = resolvedBoundTarget?.categoryId ?: pendingCategoryId
            val initialKeepassDatabaseId = resolvedBoundTarget?.keepassDatabaseId ?: pendingKeepassDatabaseId
            val initialKeepassGroupPath = resolvedBoundTarget?.keepassGroupPath ?: pendingKeepassGroupPath
            val initialBitwardenVaultId = resolvedBoundTarget?.bitwardenVaultId ?: pendingBitwardenVaultId
            val initialBitwardenFolderId = resolvedBoundTarget?.bitwardenFolderId ?: pendingBitwardenFolderId
            val initialMdbxDatabaseId = resolvedBoundTarget?.mdbxDatabaseId ?: pendingMdbxDatabaseId
            val initialMdbxFolderId = resolvedBoundTarget?.mdbxFolderId ?: pendingMdbxFolderId

            val passkeyMode = if (initialKeepassDatabaseId != null) {
                PasskeyEntry.MODE_KEEPASS_COMPAT
            } else {
                PasskeyEntry.MODE_BW_COMPAT
            }
            Log.i(
                TAG,
                "Passkey create target mode=$passkeyMode keepassDatabaseId=$initialKeepassDatabaseId bitwardenVaultId=$initialBitwardenVaultId algorithm=$algorithm"
            )

            // 保存到数据库
            val protectedPrivateKeyRef = PasskeyPrivateKeyStore.protectForStorage(
                context = applicationContext,
                credentialId = credentialIdB64,
                rpId = normalizedRpId,
                userId = userIdB64,
                keyMaterial = privateKeyB64
            )
            val passkeyEntry = PasskeyEntry(
                credentialId = credentialIdB64,
                rpId = normalizedRpId,
                rpName = parseRpName(requestJson, normalizedRpId),
                userId = userIdB64,
                userName = userName,
                userDisplayName = userDisplayName,
                publicKey = publicKeyB64,
                privateKeyAlias = protectedPrivateKeyRef,
                publicKeyAlgorithm = algorithm,
                createdAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
                useCount = 0,
                isDiscoverable = discoverable,
                signCount = 0L,
                boundPasswordId = pendingBoundPasswordId,
                categoryId = initialCategoryId,
                keepassDatabaseId = initialKeepassDatabaseId,
                keepassGroupPath = initialKeepassGroupPath,
                bitwardenVaultId = initialBitwardenVaultId,
                bitwardenFolderId = initialBitwardenFolderId,
                mdbxDatabaseId = initialMdbxDatabaseId,
                mdbxFolderId = if (initialMdbxDatabaseId != null) initialMdbxFolderId else null,
                syncStatus = if (initialBitwardenVaultId != null && passkeyMode == PasskeyEntry.MODE_BW_COMPAT) {
                    "PENDING"
                } else {
                    "NONE"
                },
                passkeyMode = passkeyMode
            )
            
            // 在协程中保存
            kotlinx.coroutines.runBlocking {
                val created = keepassPasskeyCreateExecutor.create(
                    passkey = passkeyEntry,
                    insertPasskey = repository::savePasskey,
                    rollbackPasskey = ::rollbackPasskeyByCredentialId
                )
                if (!created) {
                    throw IllegalStateException(getString(R.string.passkey_keepass_create_failed))
                }
                createdCredentialIdForRollback = credentialIdB64
                boundPasswordIdForRollback = pendingBoundPasswordId

                // 同步写入密码条目的通行密钥绑定（用于备份/恢复）
                val boundPasswordId = pendingBoundPasswordId
                if (boundPasswordId != null) {
                    val passwordDao = database.passwordEntryDao()
                    val passwordEntry = passwordDao.getPasswordEntryById(boundPasswordId)
                    if (passwordEntry != null) {
                        val binding = PasskeyBinding(
                            credentialId = credentialIdB64,
                            rpId = normalizedRpId,
                            rpName = parseRpName(requestJson, normalizedRpId),
                            userName = userName,
                            userDisplayName = userDisplayName
                        )
                        val updatedBindings = PasskeyBindingCodec.addBinding(passwordEntry.passkeyBindings, binding)
                        passwordDao.updatePasskeyBindings(boundPasswordId, updatedBindings)
                    }
                }
            }
            
            // 构建响应 JSON
            val responseJson = JSONObject().apply {
                put("id", credentialIdB64)
                put("rawId", credentialIdB64)
                put("type", "public-key")
                put("authenticatorAttachment", "platform")
                put("response", JSONObject().apply {
                    put("clientDataJSON", clientDataJsonValue)
                    put("attestationObject", Base64.encodeToString(
                        attestationObject, 
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ))
                    put("publicKeyAlgorithm", algorithm)
                    put("publicKey", Base64.encodeToString(
                        keyPair.public.encoded,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ))
                    put("authenticatorData", Base64.encodeToString(
                        authenticatorData,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ))
                    put("transports", JSONArray().apply {
                        put("internal")
                        put("hybrid")
                    })
                })
                put("clientExtensionResults", buildClientExtensionResults(requestJson, discoverable))
            }
            
            Log.d(TAG, "Passkey created successfully")
            repository.logAudit("PASSKEY_CREATE_SUCCESS", 
                "$credentialIdB64|rpId=$rpId|userName=$userName")
            recordPasskeyEvent(
                stage = "result_sent",
                requestJson = requestJson,
                rpId = normalizedRpId,
                credentialId = credentialIdB64,
            )
            
            // 返回结果
            val credentialResponse = CreatePublicKeyCredentialResponse(responseJson.toString())
            val resultIntent = Intent()
            PendingIntentHandler.setCreateCredentialResponse(resultIntent, credentialResponse)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create passkey", e)
            rollbackCreatedPasskeyIfNeeded(
                credentialId = createdCredentialIdForRollback,
                boundPasswordId = boundPasswordIdForRollback
            )
            repository.logAudit("PASSKEY_CREATE_ERROR", 
                "rpId=$rpId|error=${e.message}")
            recordPasskeyEvent(
                stage = "create_failed",
                requestJson = requestJson,
                rpId = rpId,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message,
            )
            val resultIntent = Intent()
            PendingIntentHandler.setCreateCredentialException(
                resultIntent,
                CreateCredentialUnknownException(e.message)
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun rollbackCreatedPasskeyIfNeeded(
        credentialId: String?,
        boundPasswordId: Long?
    ) {
        if (credentialId.isNullOrBlank()) return
        runCatching {
            kotlinx.coroutines.runBlocking {
                val createdPasskey = database.passkeyDao().getPasskeyById(credentialId)
                if (createdPasskey != null) {
                    val deleteResult = keepassPasskeyDeleteExecutor.delete(
                        passkey = createdPasskey,
                        deleteLocal = repository::deletePasskeyLocalOnly
                    )
                    deleteResult.getOrThrow()
                } else {
                    database.passkeyDao().deleteById(credentialId)
                }
                if (boundPasswordId != null) {
                    val passwordDao = database.passwordEntryDao()
                    val passwordEntry = passwordDao.getPasswordEntryById(boundPasswordId)
                    if (passwordEntry != null) {
                        val updatedBindings = PasskeyBindingCodec.removeBinding(
                            passwordEntry.passkeyBindings,
                            credentialId
                        )
                        passwordDao.updatePasskeyBindings(boundPasswordId, updatedBindings)
                    }
                }
            }
            repository.logAudit(
                "PASSKEY_CREATE_ROLLBACK",
                "$credentialId|boundPasswordId=${boundPasswordId ?: "null"}"
            )
            Log.w(TAG, "Rolled back passkey creation")
        }.onFailure { rollbackError ->
            Log.e(TAG, "Failed to rollback created passkey", rollbackError)
        }
    }

    private suspend fun rollbackPasskeyByCredentialId(credentialId: String) {
        val createdPasskey = database.passkeyDao().getPasskeyById(credentialId)
        if (createdPasskey != null) {
            repository.deletePasskeyLocalOnly(createdPasskey)
        } else {
            database.passkeyDao().deleteById(credentialId)
        }
    }

    private fun buildCreateClientDataJsonBytes(
        requestJson: String,
        challengeB64: String,
        rpId: String
    ): ByteArray {
        val origin = PasskeyOriginResolver.resolveOrigin(
            context = this,
            requestJson = requestJson,
            callingAppInfo = pendingCallingAppInfo,
            rpIdFallback = rpId
        )
        val androidPackageName = pendingCallingAppInfo?.packageName
        val clientDataJson = createClientDataJson(
            type = "webauthn.create",
            challenge = challengeB64,
            origin = origin,
            androidPackageName = androidPackageName
        )
        Log.d(TAG, "Built clientDataJSON with origin: $origin")
        return clientDataJson.toByteArray()
    }

    private fun recordPasskeyEvent(
        stage: String,
        requestJson: String = pendingRequestJson,
        rpId: String? = pendingRpId,
        credentialId: String? = null,
        verdict: PasskeyValidationVerdict? = null,
        errorType: String? = null,
        errorMessage: String? = null,
    ) {
        PasskeyValidationDiagnostics.recordEvent(
            context = this,
            flowTag = "CREATE",
            stage = stage,
            rpId = rpId,
            callingPackage = pendingCallingAppInfo?.packageName,
            requestOrigin = extractRequestOrigin(requestJson),
            callingOrigin = PasskeyBrowserOrigin.read(this, pendingCallingAppInfo),
            resolvedOrigin = verdict?.resolvedOrigin,
            resolvedSource = verdict?.resolvedSource?.name,
            reasons = verdict?.reasons ?: emptyList(),
            strictBlock = verdict?.strictBlock ?: false,
            requestJsonSize = requestJson.length,
            credentialId = credentialId,
            clientDataHashPresent = pendingClientDataHash != null,
            errorType = errorType,
            errorMessage = errorMessage,
        )
    }

    private fun extractRequestOrigin(requestJson: String): String? {
        if (requestJson.isBlank()) return null
        return runCatching {
            JSONObject(requestJson).optString("origin").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
    
    private fun getPreferredAlgorithm(pubKeyCredParams: JSONArray?): Int? {
        // 对缺失字段保持兼容，默认 ES256
        if (pubKeyCredParams == null || pubKeyCredParams.length() == 0) {
            return PasskeyEntry.ALGORITHM_ES256
        }

        val requestedAlgorithms = extractRequestedAlgorithms(pubKeyCredParams)
        val supportedAlgorithms = setOf(
            PasskeyEntry.ALGORITHM_ES256,
            PasskeyEntry.ALGORITHM_RS256
        )
        val preferred = requestedAlgorithms.firstOrNull { it in supportedAlgorithms }
        if (preferred != null) {
            return preferred
        }

        Log.w(TAG, "No supported algorithm in request: ${requestedAlgorithms.joinToString(",")}")
        return null
    }

    private fun extractRequestedAlgorithms(pubKeyCredParams: JSONArray?): List<Int> {
        if (pubKeyCredParams == null || pubKeyCredParams.length() == 0) return emptyList()
        val requested = linkedSetOf<Int>()
        for (i in 0 until pubKeyCredParams.length()) {
            val param = pubKeyCredParams.optJSONObject(i) ?: continue
            if (param.optString("type", "public-key") != "public-key") continue
            val alg = param.optInt("alg", 0)
            if (alg != 0) requested += alg
        }
        return requested.toList()
    }
    
    private fun generateCredentialId(): ByteArray {
        val uuid = UUID.randomUUID()
        val bytes = ByteArray(16)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0..7) {
            bytes[i] = ((msb shr (8 * (7 - i))) and 0xFF).toByte()
            bytes[8 + i] = ((lsb shr (8 * (7 - i))) and 0xFF).toByte()
        }
        return bytes
    }
    
    private fun generateKeyPairForAlgorithm(algorithm: Int): java.security.KeyPair {
        return when (algorithm) {
            PasskeyEntry.ALGORITHM_ES256 -> {
                val generator = KeyPairGenerator.getInstance("EC").apply {
                    val spec = ECGenParameterSpec("secp256r1")
                    initialize(spec)
                }
                generator.genKeyPair()
            }
            PasskeyEntry.ALGORITHM_RS256 -> {
                val generator = KeyPairGenerator.getInstance("RSA").apply {
                    initialize(2048)
                }
                generator.genKeyPair()
            }
            else -> throw IllegalStateException("Unsupported algorithm: $algorithm")
        }
    }

    private fun createCosePublicKeyFromKeyPair(
        keyPair: java.security.KeyPair,
        algorithm: Int
    ): ByteArray {
        return when (algorithm) {
            PasskeyEntry.ALGORITHM_ES256 -> createEs256CosePublicKey(keyPair.public as ECPublicKey)
            PasskeyEntry.ALGORITHM_RS256 -> createRs256CosePublicKey(keyPair.public as RSAPublicKey)
            else -> throw IllegalStateException("Unsupported COSE algorithm: $algorithm")
        }
    }

    /**
     * 构建 ES256 的 COSE_Key。
     * 参考 RFC 9052 / WebAuthn:
     * {1:2, 3:-7, -1:1, -2:x, -3:y}
     */
    private fun createEs256CosePublicKey(ecPubKey: ECPublicKey): ByteArray {
        val ecPoint = ecPubKey.w
        
        // 验证坐标长度
        if (ecPoint.affineX.bitLength() > 256 || ecPoint.affineY.bitLength() > 256) {
            throw IllegalStateException("EC point coordinates exceed 256 bits")
        }
        
        val byteX = bigIntToByteArray32(ecPoint.affineX)
        val byteY = bigIntToByteArray32(ecPoint.affineY)
        return encodeCborMap(
            encodeCborInt(1) to encodeCborInt(2),
            encodeCborInt(3) to encodeCborInt(PasskeyEntry.ALGORITHM_ES256),
            encodeCborInt(-1) to encodeCborInt(1),
            encodeCborInt(-2) to encodeCborByteString(byteX),
            encodeCborInt(-3) to encodeCborByteString(byteY)
        )
    }

    /**
     * 构建 RS256 的 COSE_Key。
     * 参考 RFC 9053:
     * {1:3, 3:-257, -1:n, -2:e}
     */
    private fun createRs256CosePublicKey(rsaPubKey: RSAPublicKey): ByteArray {
        val modulus = bigIntToUnsignedByteArray(rsaPubKey.modulus)
        val exponent = bigIntToUnsignedByteArray(rsaPubKey.publicExponent)
        return encodeCborMap(
            encodeCborInt(1) to encodeCborInt(3),
            encodeCborInt(3) to encodeCborInt(PasskeyEntry.ALGORITHM_RS256),
            encodeCborInt(-1) to encodeCborByteString(modulus),
            encodeCborInt(-2) to encodeCborByteString(exponent)
        )
    }

    private fun bigIntToUnsignedByteArray(bigInteger: java.math.BigInteger): ByteArray {
        val bytes = bigInteger.toByteArray()
        if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            return bytes.copyOfRange(1, bytes.size)
        }
        return bytes
    }
    
    private fun bigIntToByteArray32(bigInteger: java.math.BigInteger): ByteArray {
        var ba = bigInteger.toByteArray()
        // 如果长度不足 32，前面补零
        if (ba.size < 32) {
            val padded = ByteArray(32)
            System.arraycopy(ba, 0, padded, 32 - ba.size, ba.size)
            ba = padded
        }
        // 如果长度超过 32（由于符号位），取最后 32 字节
        return ba.copyOfRange(ba.size - 32, ba.size)
    }

    private fun encodeCborMap(vararg entries: Pair<ByteArray, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(encodeCborMajorTypeAndLength(5, entries.size.toLong()))
        entries.forEach { (key, value) ->
            output.write(key)
            output.write(value)
        }
        return output.toByteArray()
    }

    private fun encodeCborInt(value: Int): ByteArray {
        return if (value >= 0) {
            encodeCborMajorTypeAndLength(0, value.toLong())
        } else {
            val encoded = -1L - value.toLong()
            encodeCborMajorTypeAndLength(1, encoded)
        }
    }

    private fun encodeCborByteString(value: ByteArray): ByteArray {
        return encodeCborMajorTypeAndLength(2, value.size.toLong()) + value
    }

    private fun encodeCborMajorTypeAndLength(majorType: Int, length: Long): ByteArray {
        require(majorType in 0..7) { "Invalid CBOR major type: $majorType" }
        require(length >= 0) { "CBOR length must be non-negative" }

        val head = (majorType shl 5)
        return when {
            length < 24 -> byteArrayOf((head or length.toInt()).toByte())
            length <= 0xFF -> byteArrayOf((head or 24).toByte(), length.toByte())
            length <= 0xFFFF -> byteArrayOf(
                (head or 25).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
            length <= 0xFFFFFFFFL -> byteArrayOf(
                (head or 26).toByte(),
                ((length shr 24) and 0xFF).toByte(),
                ((length shr 16) and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
            else -> byteArrayOf(
                (head or 27).toByte(),
                ((length shr 56) and 0xFF).toByte(),
                ((length shr 48) and 0xFF).toByte(),
                ((length shr 40) and 0xFF).toByte(),
                ((length shr 32) and 0xFF).toByte(),
                ((length shr 24) and 0xFF).toByte(),
                ((length shr 16) and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }
    
    private fun createAuthenticatorData(
        rpId: String,
        credentialId: ByteArray,
        cosePublicKey: ByteArray,
        signCount: Int
    ): ByteArray {
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray())
        
        // 参照 KeePassDX AuthenticatorData.buildAuthenticatorData
        // Flags: UP (0x01) | UV (0x04) | BE (0x08) | BS (0x10) | AT (0x40)
        // UP = User Present
        // UV = User Verified  
        // BE = Backup Eligibility
        // BS = Backup State
        // AT = Attested Credential Data included
        var flags = 0x01 // UP
        flags = flags or 0x04 // UV
        flags = flags or 0x08 // BE - Backup Eligibility
        flags = flags or 0x10 // BS - Backup State
        flags = flags or 0x40 // AT - Attested Credential Data
        
        val signCountBytes = ByteArray(4)
        signCountBytes[0] = ((signCount shr 24) and 0xFF).toByte()
        signCountBytes[1] = ((signCount shr 16) and 0xFF).toByte()
        signCountBytes[2] = ((signCount shr 8) and 0xFF).toByte()
        signCountBytes[3] = (signCount and 0xFF).toByte()
        
        // Monica AAGUID
        val aaguid = MONICA_AAGUID
        
        // Credential ID length (2 bytes big-endian)
        val credIdLen = ByteArray(2)
        credIdLen[0] = ((credentialId.size shr 8) and 0xFF).toByte()
        credIdLen[1] = (credentialId.size and 0xFF).toByte()
        
        return rpIdHash + byteArrayOf(flags.toByte()) + signCountBytes + aaguid + credIdLen + credentialId + cosePublicKey
    }
    
    private fun createAttestationObject(authenticatorData: ByteArray): ByteArray {
        // 手动 CBOR 编码 attestation object (none attestation)
        // 结构: { "fmt": "none", "attStmt": {}, "authData": <bytes> }
        // 
        // CBOR 编码:
        // A3                           # map(3)
        //    63                        # text(3)
        //       666D74                 # "fmt"
        //    64                        # text(4)
        //       6E6F6E65               # "none"
        //    67                        # text(7)
        //       61747453746D74         # "attStmt"
        //    A0                        # map(0) - empty map
        //    68                        # text(8)
        //       6175746844617461       # "authData"
        //    58/59 XX                  # bytes(XX)
        //       <authenticatorData>
        
        val fmtKey = byteArrayOf(0x63.toByte(), 0x66, 0x6D, 0x74) // text(3) "fmt"
        val fmtValue = byteArrayOf(0x64.toByte(), 0x6E, 0x6F, 0x6E, 0x65) // text(4) "none"
        val attStmtKey = byteArrayOf(0x67.toByte(), 0x61, 0x74, 0x74, 0x53, 0x74, 0x6D, 0x74) // text(7) "attStmt"
        val attStmtValue = byteArrayOf(0xA0.toByte()) // map(0) - empty
        val authDataKey = byteArrayOf(0x68.toByte(), 0x61, 0x75, 0x74, 0x68, 0x44, 0x61, 0x74, 0x61) // text(8) "authData"
        
        // CBOR bytes header: 0x58 = 1-byte length, 0x59 = 2-byte length
        val authDataHeader = if (authenticatorData.size < 256) {
            byteArrayOf(0x58.toByte(), authenticatorData.size.toByte())
        } else {
            byteArrayOf(
                0x59.toByte(),
                ((authenticatorData.size shr 8) and 0xFF).toByte(),
                (authenticatorData.size and 0xFF).toByte()
            )
        }
        
        return byteArrayOf(0xA3.toByte()) + // map(3)
            fmtKey + fmtValue +
            attStmtKey + attStmtValue +
            authDataKey + authDataHeader + authenticatorData
    }
    
    private fun createClientDataJson(
        type: String,
        challenge: String,
        origin: String,
        androidPackageName: String? = null
    ): String {
        return JSONObject().apply {
            put("type", type)
            put("challenge", challenge)
            put("origin", origin)
            if (!androidPackageName.isNullOrBlank()) {
                put("androidPackageName", androidPackageName)
            }
            put("crossOrigin", false)
        }.toString()
    }

    private fun parseDiscoverable(requestJson: String): Boolean {
        return PasskeyDiscoverabilityResolver.isDiscoverableCreationRequest(requestJson)
    }

    private fun buildClientExtensionResults(requestJson: String, discoverable: Boolean): JSONObject {
        return JSONObject().apply {
            if (PasskeyDiscoverabilityResolver.isCredPropsRequested(requestJson)) {
                put("credProps", JSONObject().apply {
                    put("rk", discoverable)
                })
            }
        }
    }
    
}

@Composable
private fun PasskeyCreateScreen(
    rpId: String,
    rpName: String,
    userName: String,
    userDisplayName: String,
    keepassDatabases: List<LocalKeePassDatabase>,
    mdbxDatabases: List<LocalMdbxDatabase>,
    selectedKeePassDatabaseId: Long?,
    selectedKeePassGroupPath: String?,
    onKeePassDatabaseSelected: (Long?) -> Unit,
    onKeePassGroupPathSelected: (String?) -> Unit,
    bitwardenVaults: List<BitwardenVault>,
    selectedBitwardenVaultId: Long?,
    selectedBitwardenFolderId: String?,
    onBitwardenVaultSelected: (Long?) -> Unit,
    onBitwardenFolderSelected: (String?) -> Unit,
    selectedMdbxDatabaseId: Long?,
    selectedMdbxFolderId: String?,
    onMdbxDatabaseSelected: (Long?) -> Unit,
    onMdbxFolderSelected: (String?) -> Unit,
    categories: List<Category>,
    selectedCategoryId: Long?,
    onCategorySelected: (Long?) -> Unit,
    getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<takagi.ru.monica.utils.KeePassGroupInfo>>,
    getMdbxFolders: (Long) -> kotlinx.coroutines.flow.Flow<List<MdbxStoredFolderEntry>>,
    onCreateDirect: () -> Unit,
    onBindToPassword: () -> Unit,
    onCancel: () -> Unit
) {
    var isCreating by remember { mutableStateOf(false) }
    var showStoragePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    val selectedBitwardenVault = bitwardenVaults.find { it.id == selectedBitwardenVaultId }
    val selectedBitwardenFolders by (
        if (selectedBitwardenVault != null) {
            database.bitwardenFolderDao().getFoldersByVaultFlow(selectedBitwardenVault.id)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    ).collectAsState(initial = emptyList())
    val selectedCategoryName = selectedCategoryId?.let { id ->
        categories.find { it.id == id }?.name
    }
    val selectedKeePassName = selectedKeePassDatabaseId?.let { id ->
        keepassDatabases.find { it.id == id }?.name
    }
    val selectedBitwardenName = selectedBitwardenVault?.let { vault ->
        vault.displayName ?: vault.email
    }
    val selectedBitwardenFolderName = selectedBitwardenFolderId?.let { folderId ->
        selectedBitwardenFolders.find { it.bitwardenFolderId == folderId }?.name
    }
    val selectedMdbxDatabase = selectedMdbxDatabaseId?.let { id ->
        mdbxDatabases.find { it.id == id }
    }
    val selectedMdbxFolders by (
        if (selectedMdbxDatabaseId != null) {
            getMdbxFolders(selectedMdbxDatabaseId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    ).collectAsState(initial = emptyList())
    val selectedMdbxFolderName = selectedMdbxFolderId?.let { folderId ->
        selectedMdbxFolders.find { it.folderId == folderId }?.name
    }

    val storageTitle = when {
        selectedKeePassDatabaseId != null -> selectedKeePassName ?: stringResource(R.string.filter_keepass)
        selectedBitwardenVaultId != null -> selectedBitwardenName ?: stringResource(R.string.filter_bitwarden)
        selectedMdbxDatabaseId != null -> selectedMdbxDatabase?.name ?: "MDBX"
        selectedCategoryId != null -> selectedCategoryName ?: stringResource(R.string.filter_monica)
        else -> stringResource(R.string.vault_monica_only)
    }
    val storageSubtitle = when {
        selectedKeePassDatabaseId != null -> {
            selectedKeePassGroupPath
                ?.takeIf { it.isNotBlank() }
                ?.let { decodeKeePassPathForDisplay(it) }
                ?: stringResource(R.string.category_none)
        }
        selectedBitwardenVaultId != null -> selectedBitwardenFolderName ?: stringResource(R.string.category_none)
        selectedMdbxDatabaseId != null -> selectedMdbxFolderName ?: stringResource(R.string.category_none)
        selectedCategoryId != null -> selectedCategoryName ?: stringResource(R.string.category_none)
        else -> stringResource(R.string.category_none)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 10.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            isCreating = true
                            onCreateDirect()
                        },
                        enabled = !isCreating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.passkey_create_confirm))
                        }
                    }

                    FilledTonalButton(
                        onClick = onBindToPassword,
                        enabled = !isCreating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.bind_password))
                    }

                    TextButton(
                        onClick = onCancel,
                        enabled = !isCreating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Text(
                        text = stringResource(R.string.passkey_create_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(R.string.passkey_create_message, rpName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PasskeyInfoRow(
                        icon = Icons.Default.Language,
                        title = rpName,
                        subtitle = rpId
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PasskeyInfoRow(
                        icon = Icons.Default.Person,
                        title = userDisplayName,
                        subtitle = if (userName != userDisplayName) userName else null
                    )
                }
            }

            ElevatedCard(
                onClick = { showStoragePicker = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.move_to_category),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = storageTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = storageSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            UnifiedMoveToCategoryBottomSheet(
                visible = showStoragePicker,
                onDismiss = { showStoragePicker = false },
                categories = categories,
                keepassDatabases = keepassDatabases,
                mdbxDatabases = mdbxDatabases,
                bitwardenVaults = bitwardenVaults,
                getBitwardenFolders = { vaultId ->
                    database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId)
                },
                getKeePassGroups = getKeePassGroups,
                getMdbxFolders = getMdbxFolders,
                allowCopy = false,
                onTargetSelected = { target, _ ->
                    when (target) {
                        UnifiedMoveCategoryTarget.Uncategorized -> {
                            onCategorySelected(null)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.MonicaCategory -> {
                            onCategorySelected(target.categoryId)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> {
                            onCategorySelected(null)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(target.vaultId)
                            onBitwardenFolderSelected(null)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                            onCategorySelected(null)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(target.vaultId)
                            onBitwardenFolderSelected(target.folderId)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
                            onCategorySelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onKeePassDatabaseSelected(target.databaseId)
                            onKeePassGroupPathSelected(null)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                            onCategorySelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onKeePassDatabaseSelected(target.databaseId)
                            onKeePassGroupPathSelected(target.groupPath)
                            onMdbxDatabaseSelected(null)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> {
                            onCategorySelected(null)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onMdbxDatabaseSelected(target.databaseId)
                            onMdbxFolderSelected(null)
                        }
                        is UnifiedMoveCategoryTarget.MdbxFolderTarget -> {
                            onCategorySelected(null)
                            onKeePassDatabaseSelected(null)
                            onKeePassGroupPathSelected(null)
                            onBitwardenVaultSelected(null)
                            onBitwardenFolderSelected(null)
                            onMdbxDatabaseSelected(target.databaseId)
                            onMdbxFolderSelected(target.folderId)
                        }
                    }
                    showStoragePicker = false
                }
            )
        }
    }
}

@Composable
private fun PasskeyInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
