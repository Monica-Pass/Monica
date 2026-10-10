package takagi.ru.monica.passkey

import android.content.Context
import takagi.ru.monica.utils.LocaleHelper
import takagi.ru.monica.utils.StartupLanguageCache
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import takagi.ru.monica.R
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.ThemeMode
import takagi.ru.monica.repository.MdbxRepositoryFactory
import takagi.ru.monica.repository.PasskeyRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.MasterPasswordDialog
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.BiometricAuthHelper
import takagi.ru.monica.utils.SettingsManager
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Passkey 认证 Activity
 * 
 * 当用户选择使用 Monica 中的 Passkey 登录时显示
 * 强制要求生物识别验证以确保安全性
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyAuthActivity : FragmentActivity() {
    
    companion object {
        private const val TAG = "PasskeyAuthActivity"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
    
    private val database: PasswordDatabase by lazy {
        PasswordDatabase.getDatabase(applicationContext)
    }
    
    private val biometricHelper: BiometricAuthHelper by lazy {
        BiometricAuthHelper(this)
    }
    
    private val repository: PasskeyRepository by lazy {
        PasskeyRepository(
            database.passkeyDao(),
            MdbxRepositoryFactory.create(
                context = applicationContext,
                database = database,
                securityManager = securityManager
            ),
            applicationContext
        )
    }

    private val securityManager: SecurityManager by lazy {
        SecurityManager(this)
    }

    private val showMasterPasswordDialog = mutableStateOf(false)
    private val masterPasswordError = mutableStateOf(false)
    
    private var resultFinished = false
    private var signingStarted = false
    private var biometricInFlight = false
    private var verificationAttempt = 0
    private var passkey: PasskeyEntry? = null
    private var pendingRequestJson: String = ""
    private var pendingCallingAppInfo: CallingAppInfo? = null
    private var pendingClientDataHash: ByteArray? = null
    
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.setLocale(newBase, StartupLanguageCache.read(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        onBackPressedDispatcher.addCallback(this) { cancelAuthentication() }
        Log.i(TAG, "PasskeyAuthActivity onCreate")
        
        // 首先尝试从 PendingIntentHandler 获取请求（这是正确的方式）
        val providerRequest = try {
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve ProviderGetCredentialRequest", e)
            null
        }
        
        if (providerRequest == null) {
            Log.e(TAG, "providerRequest is null - this should not happen!")
            // 如果无法获取 providerRequest，尝试从 intent extras 获取（向后兼容）
        } else {
            Log.i(TAG, "providerRequest retrieved successfully")
            pendingCallingAppInfo = providerRequest.callingAppInfo
            Log.d(TAG, "CallingAppInfo: $pendingCallingAppInfo")
            Log.d(TAG, "CallingAppInfo origin: ${PasskeyBrowserOrigin.read(this, pendingCallingAppInfo)}")
            Log.d(TAG, "CallingAppInfo packageName: ${pendingCallingAppInfo?.packageName}")
            

        }
        
        var requestJson = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_REQUEST_JSON) ?: ""
        val credentialId = intent.getStringExtra(MonicaCredentialProviderService.EXTRA_CREDENTIAL_ID) ?: ""
        val recordId = intent.getLongExtra(MonicaCredentialProviderService.EXTRA_RECORD_ID, 0L)

        recordPasskeyEvent(
            stage = "request_received",
            requestJson = requestJson,
            credentialId = credentialId,
        )
        
        Log.i(
            TAG,
            "request loaded: requestJsonSize=${requestJson.length}, credentialIdSize=${credentialId.length}"
        )
        
        // 检查必要参数
        if (credentialId.isBlank()) {
            Log.e(TAG, "credentialId is empty!")
            recordPasskeyEvent(
                stage = "request_rejected",
                requestJson = requestJson,
                credentialId = credentialId,
                errorType = "GetCredentialUnknownException",
                errorMessage = "Missing credential ID",
            )
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialException(
                resultIntent,
                GetCredentialUnknownException("Missing credential ID")
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }
        
        if (requestJson.isBlank()) {
            Log.e(TAG, "requestJson is empty!")
            recordPasskeyEvent(
                stage = "request_rejected",
                requestJson = requestJson,
                credentialId = credentialId,
                errorType = "GetCredentialUnknownException",
                errorMessage = "Missing request JSON",
            )
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialException(
                resultIntent,
                GetCredentialUnknownException("Missing request JSON")
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }
        
        // 加载 Passkey 信息。Credential Provider 的候选项按 recordId 精确传递，
        // 避免同一 RP/用户名相近或同步引用数据存在时误取到另一条 credential。
        runBlocking {
            passkey = recordId
                .takeIf { it > 0L }
                ?.let { database.passkeyDao().getPasskeyByRecordId(it) }
                ?.takeIf { it.credentialId == credentialId }
            if (passkey == null && recordId <= 0L) {
                passkey = database.passkeyDao().getPasskeyById(credentialId)
            }
            if (passkey == null) {
                val normalizedId = normalizeCredentialId(credentialId)
                if (normalizedId != null) {
                    val all = database.passkeyDao().getAllPasskeysSync()
                    passkey = all.firstOrNull {
                        (recordId <= 0L || it.id == recordId) &&
                            normalizeCredentialId(it.credentialId) == normalizedId
                    }
                }
            }
        }
        
        val currentPasskey = passkey
        if (currentPasskey == null) {
            Log.e(TAG, "Passkey not found")
            recordPasskeyEvent(
                stage = "request_rejected",
                requestJson = requestJson,
                credentialId = credentialId,
                errorType = "GetCredentialUnknownException",
                errorMessage = "Passkey not found",
            )
            // 必须使用 PendingIntentHandler 设置异常，否则系统会显示 Authentication failed
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialException(
                resultIntent,
                GetCredentialUnknownException("Passkey not found")
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            return
        }

        // The selected row and the final platform option must describe the same ceremony.
        // Never sign an Intent extra alone or use an unrelated option's clientDataHash.
        try {
            val original = PasskeyGetRequestPolicy.parse(requestJson)
            require(original.allows(currentPasskey)) { "Credential not allowed for this request" }
            val actualOption = providerRequest?.credentialOptions
                ?.filterIsInstance<GetPublicKeyCredentialOption>()
                ?.firstOrNull { option ->
                    runCatching {
                        val actual = PasskeyGetRequestPolicy.parse(option.requestJson)
                        original.isSameCeremony(actual) && actual.allows(currentPasskey)
                    }.getOrDefault(false)
                } ?: throw IllegalArgumentException("Missing matching platform credential request")
            requestJson = actualOption.requestJson
            pendingClientDataHash = actualOption.clientDataHash
        } catch (error: Exception) {
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialException(resultIntent, GetCredentialUnknownException(error.message))
            setResult(Activity.RESULT_OK, resultIntent)
            resultFinished = true
            finish()
            return
        }

        val shadowEnabled = PasskeyValidationFlags.isShadowValidationEnabled(this)
        val strictEnabled = PasskeyValidationFlags.isStrictValidationEnabled(this)
        if (shadowEnabled || strictEnabled) {
            val verdict = PasskeyRequestValidator.validate(
                context = this,
                requestJson = requestJson,
                rpId = currentPasskey.rpId,
                callingAppInfo = pendingCallingAppInfo
            )
            recordPasskeyEvent(
                stage = "validation_done",
                requestJson = requestJson,
                rpId = currentPasskey.rpId,
                credentialId = currentPasskey.credentialId,
                verdict = verdict,
            )
            PasskeyRequestValidator.logShadow(
                flowTag = "AUTH",
                rpId = currentPasskey.rpId,
                callingPackage = pendingCallingAppInfo?.packageName,
                verdict = verdict
            )
            PasskeyValidationDiagnostics.record(
                context = this,
                flowTag = "AUTH",
                rpId = currentPasskey.rpId,
                callingPackage = pendingCallingAppInfo?.packageName,
                verdict = verdict
            )
            repository.logAudit(
                "PASSKEY_AUTH_VALIDATION",
                "rpId=${currentPasskey.rpId}|source=${verdict.resolvedSource}|reasons=${verdict.reasons.joinToString(",")}"
            )
            if (strictEnabled && verdict.strictBlock) {
                recordPasskeyEvent(
                    stage = "request_blocked",
                    requestJson = requestJson,
                    rpId = currentPasskey.rpId,
                    credentialId = currentPasskey.credentialId,
                    verdict = verdict,
                    errorType = "GetCredentialUnknownException",
                    errorMessage = "Passkey request validation failed",
                )
                val resultIntent = Intent()
                PendingIntentHandler.setGetCredentialException(
                    resultIntent,
                    GetCredentialUnknownException("Passkey request validation failed")
                )
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
                return
            }
        }
        
        // 保存请求数据用于生物识别验证后使用
        pendingRequestJson = requestJson
        
        setContent {
            val settingsManager = remember { SettingsManager(this@PasskeyAuthActivity) }
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
                PasskeyAuthScreen(
                    passkey = currentPasskey,
                    onConfirm = {
                        requestBiometricAuth(currentPasskey)
                    },
                    onCancel = {
                        repository.logAudit("PASSKEY_AUTH_CANCELLED", currentPasskey.credentialId)
                        cancelAuthentication()
                    },
                    onUseMasterPassword = { openMasterPassword() }
                )

                if (showMasterPasswordDialog.value) {
                    MasterPasswordDialog(
                        onDismiss = {
                            showMasterPasswordDialog.value = false
                            masterPasswordError.value = false
                        },
                        onConfirm = passwordConfirm@{ password ->
                            if (resultFinished || signingStarted || isFinishing) return@passwordConfirm
                            if (securityManager.verifyMasterPassword(password)) {
                                masterPasswordError.value = false
                                showMasterPasswordDialog.value = false
                                repository.logAudit("PASSKEY_AUTH_MASTER_PASSWORD_SUCCESS", currentPasskey.credentialId)
                                recordPasskeyEvent(
                                    stage = "master_password_success",
                                    rpId = currentPasskey.rpId,
                                    credentialId = currentPasskey.credentialId,
                                )
                                authenticateWithPasskey(pendingRequestJson, currentPasskey)
                            } else {
                                masterPasswordError.value = true
                                repository.logAudit("PASSKEY_AUTH_MASTER_PASSWORD_FAILED", currentPasskey.credentialId)
                                recordPasskeyEvent(
                                    stage = "master_password_failed",
                                    rpId = currentPasskey.rpId,
                                    credentialId = currentPasskey.credentialId,
                                    errorType = "MasterPasswordVerificationFailed",
                                    errorMessage = "Invalid master password",
                                )
                            }
                        },
                        isError = masterPasswordError.value
                    )
                }
            }
        }
    }

    
    /**
     * 请求生物识别验证
     * 只有通过生物识别后才能使用 Passkey 进行签名
     */
    private fun openMasterPassword() {
        if (resultFinished || signingStarted || isFinishing) return
        verificationAttempt++
        biometricInFlight = false
        biometricHelper.cancelAuthentication()
        showMasterPasswordDialog.value = true
    }

    private fun cancelAuthentication() {
        if (resultFinished || signingStarted) return
        resultFinished = true
        verificationAttempt++
        biometricInFlight = false
        showMasterPasswordDialog.value = false
        biometricHelper.cancelAuthentication()
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, GetCredentialCancellationException())
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        verificationAttempt++
        biometricHelper.cancelAuthentication()
        super.onDestroy()
    }

    private fun requestBiometricAuth(passkey: PasskeyEntry) {
        if (resultFinished || signingStarted || biometricInFlight || isFinishing) return
        biometricInFlight = true
        val attempt = ++verificationAttempt
        repository.logAudit("PASSKEY_AUTH_BIOMETRIC_REQUESTED", passkey.credentialId)
        recordPasskeyEvent(
            stage = "biometric_requested",
            rpId = passkey.rpId,
            credentialId = passkey.credentialId,
        )

        if (!biometricHelper.isBiometricAvailable()) {
            repository.logAudit("PASSKEY_AUTH_BIOMETRIC_UNAVAILABLE", passkey.credentialId)
            recordPasskeyEvent(
                stage = "biometric_unavailable",
                rpId = passkey.rpId,
                credentialId = passkey.credentialId,
                errorType = "BiometricUnavailable",
                errorMessage = "Biometric unavailable, fallback to master password",
            )
            biometricInFlight = false
            showMasterPasswordDialog.value = true
            return
        }
        
        biometricHelper.authenticate(
            activity = this,
            title = getString(R.string.biometric_title_passkey_auth),
            subtitle = getString(R.string.biometric_subtitle_passkey_auth, passkey.rpId),
            negativeButtonText = getString(R.string.cancel),
            onSuccess = success@{
                if (attempt != verificationAttempt || resultFinished || signingStarted || isFinishing) return@success
                biometricInFlight = false
                repository.logAudit("PASSKEY_AUTH_BIOMETRIC_SUCCESS", passkey.credentialId)
                recordPasskeyEvent(
                    stage = "biometric_success",
                    rpId = passkey.rpId,
                    credentialId = passkey.credentialId,
                )
                authenticateWithPasskey(pendingRequestJson, passkey)
            },
            onError = failure@{ errorCode, errString ->
                if (attempt != verificationAttempt || resultFinished || signingStarted || isFinishing) return@failure
                biometricInFlight = false
                repository.logAudit("PASSKEY_AUTH_BIOMETRIC_FAILED", 
                    "${passkey.credentialId}|error=$errorCode|$errString")
                Log.e(TAG, "Biometric auth failed: $errorCode - $errString")
                recordPasskeyEvent(
                    stage = "biometric_failed",
                    rpId = passkey.rpId,
                    credentialId = passkey.credentialId,
                    errorType = "BiometricError:$errorCode",
                    errorMessage = errString.toString(),
                )
                // 生物识别失败时，提供主密码验证回退
                showMasterPasswordDialog.value = true
            },
            onCancel = cancelled@{
                if (attempt != verificationAttempt || resultFinished || signingStarted || isFinishing) return@cancelled
                repository.logAudit("PASSKEY_AUTH_BIOMETRIC_CANCELLED", passkey.credentialId)
                Log.d(TAG, "Biometric auth cancelled by user")
                recordPasskeyEvent(
                    stage = "biometric_cancelled",
                    rpId = passkey.rpId,
                    credentialId = passkey.credentialId,
                    errorType = "BiometricCancelled",
                    errorMessage = "User cancelled biometric auth",
                )
                cancelAuthentication()
            }
        )
    }
    
    private fun authenticateWithPasskey(
        requestJson: String,
        passkey: PasskeyEntry
    ) {
        if (resultFinished || signingStarted || isFinishing) return
        signingStarted = true
        verificationAttempt++
        biometricHelper.cancelAuthentication()
        try {
            require(PasskeyGetRequestPolicy.parse(requestJson).allows(passkey)) { "Credential not allowed" }
            val current = runBlocking { database.passkeyDao().getPasskeyByRecordId(passkey.id) }
            require(current == passkey) { "Passkey changed during authentication; select it again" }
            recordPasskeyEvent(
                stage = "auth_started",
                requestJson = requestJson,
                rpId = passkey.rpId,
                credentialId = passkey.credentialId,
            )
            val json = JSONObject(requestJson)
            
            // 解析 challenge
            val challengeB64 = json.optString("challenge")
            val challenge = Base64.decode(challengeB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            
            val rpId = json.optString("rpId", passkey.rpId)

            // 签名计数器永远写 0：
            //
            // WebAuthn spec §6.1.1 明确允许 authenticator 始终返回 signCount = 0，
            // 表示"该 authenticator 不实现计数器"，RP 不应再做单调性校验。
            // Bitwarden / 1Password / iCloud Keychain 这类“同步型 passkey”都走这条路。
            //
            // Monica 的 passkey 是私钥可漫游 + WebDAV 全量备份，没有实时双向同步。
            // 如果还按 signCount + 1 推进，多设备 / 跨设备恢复后必然分叉：A 设备签到
            // 6，B 设备恢复后还是 5，B 再签 6，RP 端看到 6 ≤ 6 直接拒签 ——
            // 表现就是用户长期反映的"用了若干次后 passkey 突然失效"。
            //
            // 把 newSignCount 固定为 0 就彻底回避这个分叉问题。下面同时把数据库里
            // 这条记录的 sign_count 也写 0，让 useCount/lastUsedAt 这种"使用统计"
            // 字段的语义和签名计数器解耦。
            val newSignCount = 0L
            Log.i(
                TAG,
                "signCount: forcing 0 per spec §6.1.1 (was ${passkey.signCount}) " +
                    "credentialId=${passkey.credentialId}"
            )

            // 创建 authenticator data
            val authenticatorData = createAuthenticatorData(rpId, newSignCount.toInt(), passkey)
            
            val origin = PasskeyOriginResolver.resolveOrigin(
                context = this,
                requestJson = requestJson,
                callingAppInfo = pendingCallingAppInfo,
                rpIdFallback = rpId
            )
            // 区分两种场景：
            //  * 浏览器场景（系统已经算好 clientDataHash 交给我们）：此时浏览器自己的
            //    clientDataJSON 里只有 {type, challenge, origin, crossOrigin}，不含
            //    androidPackageName。如果我们额外塞 androidPackageName，RP 服务端对
            //    返回的 clientDataJSON 做 SHA-256 会得到另一个哈希，和浏览器已经签过
            //    名的那个对不上 → 站点报“密钥登录失败”。典型受害者是 Microsoft 登录。
            //  * 原生 App 场景（没有 clientDataHash）：我们需要自己构造完整的
            //    clientDataJSON 并对它签名，允许带 androidPackageName（Google GMS
            //    的既定做法）。
            val isBrowserFlow = pendingClientDataHash != null
            val androidPackageName = pendingCallingAppInfo?.packageName
            val clientDataJson = createClientDataJson(
                type = "webauthn.get",
                challenge = challengeB64,
                origin = origin,
                androidPackageName = if (isBrowserFlow) null else androidPackageName,
                includeCrossOrigin = !isBrowserFlow
            )
            val clientDataJsonBytes = clientDataJson.toByteArray()
            val clientDataHash = pendingClientDataHash
                ?: MessageDigest.getInstance("SHA-256").digest(clientDataJsonBytes)
            Log.d(
                TAG,
                "Built clientDataJSON: origin=$origin, providedClientDataHash=$isBrowserFlow, " +
                    "hasAndroidPackageName=${!isBrowserFlow && !androidPackageName.isNullOrBlank()}, " +
                    "clientDataJsonSize=${clientDataJsonBytes.size}"
            )
            
            val activePasskey = PasskeyPrivateKeyStore.protectPasskey(applicationContext, passkey)
            if (activePasskey.privateKeyAlias != passkey.privateKeyAlias) {
                runBlocking {
                    activePasskey.id.takeIf { it > 0L }?.let {
                        database.passkeyDao().update(activePasskey)
                    } ?: database.passkeyDao().update(activePasskey)
                }
                this.passkey = activePasskey
            }

            val signedData = authenticatorData + clientDataHash
            val signature = signWithPrivateKey(
                privateKeyData = activePasskey.privateKeyAlias,
                publicKeyAlgorithm = activePasskey.publicKeyAlgorithm,
                data = signedData
            )
            
            // 更新数据库
            runBlocking {
                activePasskey.id.takeIf { it > 0L }?.let { recordId ->
                    database.passkeyDao().updateUsageByRecordId(
                        recordId = recordId,
                        timestamp = System.currentTimeMillis(),
                        signCount = newSignCount
                    )
                } ?: database.passkeyDao().updateUsage(
                    credentialId = activePasskey.credentialId,
                    timestamp = System.currentTimeMillis(),
                    signCount = newSignCount
                )
            }
            
            val clientDataJsonB64 = Base64.encodeToString(
                clientDataJsonBytes,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            
            val responseJson = JSONObject().apply {
                val responseCredentialId = PasskeyCredentialIdCodec
                    .toWebAuthnId(passkey.credentialId)
                    ?: passkey.credentialId
                put("id", responseCredentialId)
                put("rawId", responseCredentialId)
                put("type", "public-key")
                put("authenticatorAttachment", "platform")
                put("response", JSONObject().apply {
                    put("clientDataJSON", clientDataJsonB64)
                    put("authenticatorData", Base64.encodeToString(
                        authenticatorData,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ))
                    put("signature", Base64.encodeToString(
                        signature,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    ))
                    put("userHandle", passkey.userId)
                })
                put("clientExtensionResults", JSONObject())
            }
            
            resultFinished = true
            Log.d(TAG, "Authentication successful")
            repository.logAudit("PASSKEY_AUTH_SUCCESS", 
                "${passkey.credentialId}|rpId=${passkey.rpId}|signCount=$newSignCount")
            recordPasskeyEvent(
                stage = "result_sent",
                requestJson = requestJson,
                rpId = passkey.rpId,
                credentialId = passkey.credentialId,
            )
            
            // 返回结果
            val credentialResponse = PublicKeyCredential(responseJson.toString())
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                resultIntent, 
                GetCredentialResponse(credentialResponse)
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
            
        } catch (e: Exception) {
            resultFinished = true
            Log.e(TAG, "Failed to authenticate with passkey", e)
            repository.logAudit("PASSKEY_AUTH_ERROR", 
                "${passkey.credentialId}|error=${e.message}")
            recordPasskeyEvent(
                stage = "auth_failed",
                requestJson = requestJson,
                rpId = passkey.rpId,
                credentialId = passkey.credentialId,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message,
            )
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialException(
                resultIntent,
                GetCredentialUnknownException(e.message)
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun recordPasskeyEvent(
        stage: String,
        requestJson: String = pendingRequestJson,
        rpId: String? = passkey?.rpId,
        credentialId: String? = passkey?.credentialId,
        verdict: PasskeyValidationVerdict? = null,
        errorType: String? = null,
        errorMessage: String? = null,
    ) {
        PasskeyValidationDiagnostics.recordEvent(
            context = this,
            flowTag = "AUTH",
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
    
    private fun createAuthenticatorData(rpId: String, signCount: Int, passkey: PasskeyEntry): ByteArray {
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray())
        
        // This helper is reached only after user presence and verification.
        val flags = PasskeyBackupFlags.authenticatorFlags(passkey.backupEligible, passkey.backupState)
        
        val signCountBytes = ByteArray(4)
        signCountBytes[0] = ((signCount shr 24) and 0xFF).toByte()
        signCountBytes[1] = ((signCount shr 16) and 0xFF).toByte()
        signCountBytes[2] = ((signCount shr 8) and 0xFF).toByte()
        signCountBytes[3] = (signCount and 0xFF).toByte()
        
        return rpIdHash + byteArrayOf(flags.toByte()) + signCountBytes
    }

    private fun normalizeCredentialId(credentialId: String): String? {
        return PasskeyCredentialIdCodec.normalize(credentialId)
    }
    
    /**
     * 使用存储的私钥签名数据
     * @param privateKeyData Base64编码的私钥，或者AndroidKeyStore别名（向后兼容）
     */
    private fun signWithPrivateKey(
        privateKeyData: String,
        publicKeyAlgorithm: Int,
        data: ByteArray
    ): ByteArray {
        val resolvedPrivateKeyData = PasskeyPrivateKeyStore.resolve(applicationContext, privateKeyData)
            ?: privateKeyData
        val privateKey: java.security.PrivateKey = try {
            PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(resolvedPrivateKeyData)?.privateKey
                ?: throw IllegalStateException("Private key data is not exportable")
        } catch (e: Exception) {
            // 回退到AndroidKeyStore（向后兼容旧数据）
            Log.d(TAG, "Falling back to AndroidKeyStore for stored key reference")
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            
            val privateKeyEntry = keyStore.getEntry(resolvedPrivateKeyData, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("Private key not found")
            privateKeyEntry.privateKey
        }

        val signature = PasskeyPrivateKeySupport.createSignature(
            privateKey = privateKey,
            publicKeyAlgorithm = publicKeyAlgorithm
        )
        signature.update(data)
        return signature.sign()
    }
    
    private fun createClientDataJson(
        type: String,
        challenge: String,
        origin: String,
        androidPackageName: String? = null,
        includeCrossOrigin: Boolean = true
    ): String {
        return JSONObject().apply {
            put("type", type)
            put("challenge", challenge)
            put("origin", origin)
            if (!androidPackageName.isNullOrBlank()) {
                put("androidPackageName", androidPackageName)
            }
            if (includeCrossOrigin) {
                put("crossOrigin", false)
            }
        }.toString()
    }

}

/**
 * 更新签名计数
 */
private suspend fun takagi.ru.monica.data.PasskeyDao.updateSignCount(credentialId: String, signCount: Int) {
    // 这需要在 DAO 中添加对应方法
    // 暂时通过更新整个条目实现
}

@Composable
private fun PasskeyAuthScreen(
    passkey: PasskeyEntry,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUseMasterPassword: () -> Unit
) {
    var isAuthenticating by remember { mutableStateOf(false) }

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
                            isAuthenticating = true
                            onConfirm()
                        },
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (isAuthenticating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.passkey_auth_confirm))
                        }
                    }

                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    TextButton(
                        onClick = onUseMasterPassword,
                        enabled = !isAuthenticating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.use_master_password))
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Text(
                        text = stringResource(R.string.passkey_auth_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(R.string.passkey_auth_message, passkey.rpName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PasskeyAuthInfoRow(
                        icon = Icons.Default.Language,
                        title = passkey.rpName,
                        subtitle = passkey.rpId
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PasskeyAuthInfoRow(
                        icon = Icons.Default.Person,
        title = passkey.authenticationTitle(),
                        subtitle = if (
                            passkey.userName != passkey.userDisplayName &&
                            passkey.userDisplayName.isNotBlank()
                        ) {
                            passkey.userName
                        } else {
                            passkey.userName
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PasskeyAuthInfoRow(
                        icon = Icons.Default.TouchApp,
                        title = stringResource(R.string.passkey_use_count),
                        subtitle = stringResource(R.string.passkey_used_count, passkey.useCount)
                    )
                }
            }
        }
    }
}

@Composable
private fun PasskeyAuthInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
