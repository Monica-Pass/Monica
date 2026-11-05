package takagi.ru.monica.bitwarden.service

import takagi.ru.monica.R
import takagi.ru.monica.utils.AppLocaleStringResolver
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.util.Locale
import java.util.UUID

/**
 * Bitwarden 认证服务
 * 
 * 负责处理:
 * 1. 预登录 (获取 KDF 参数)
 * 2. 登录 (获取访问令牌)
 * 3. 令牌刷新
 * 4. 两步验证
 * 
 * 安全注意:
 * - 密码不会存储，只存储加密的令牌和密钥
 * - 敏感操作完成后立即清除内存中的密钥材料
 */
class BitwardenAuthService(
    private val context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    private val strings = AppLocaleStringResolver(context)

    init {
        BitwardenDiagLogger.initialize(context.applicationContext)
    }
    
    companion object {
        private const val TAG = "BitwardenAuthService"
        private const val PBKDF2_DEFAULT_ITERATIONS = 600000
        private const val ARGON2_DEFAULT_ITERATIONS = 3
        private const val ARGON2_DEFAULT_MEMORY_MB = 64
        private const val ARGON2_DEFAULT_PARALLELISM = 4
        
        // 两步验证类型
        const val TWO_FACTOR_AUTHENTICATOR = 0
        const val TWO_FACTOR_EMAIL = 1
        const val TWO_FACTOR_DUO = 2
        const val TWO_FACTOR_YUBIKEY = 3
        const val TWO_FACTOR_U2F = 4
        const val TWO_FACTOR_REMEMBER = 5
        const val TWO_FACTOR_ORGANIZATION_DUO = 6
        const val TWO_FACTOR_WEBAUTHN = 7
        const val TWO_FACTOR_EMAIL_NEW_DEVICE = -100
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    /**
     * 预登录 - 获取 KDF 参数
     */
    suspend fun preLogin(
        email: String,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        diagnosticAttemptId: String? = null,
        tlsConfig: BitwardenTlsConfig? = null,
        environment: BitwardenEnvironment? = null
    ): Result<PreLoginResult> = withContext(Dispatchers.IO) {
        try {
            val normalizedEmail = email.trim()
            val requestEnvironment = environment ?: captureEnvironment(serverUrl, tlsConfig)
            diagnosticAttemptId?.let { attemptId ->
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "prelogin_start",
                )
            }
            val identityApi = apiManager.getIdentityApi(environment = requestEnvironment)
            
            val response = identityApi.preLogin(PreLoginRequest(normalizedEmail))
            
            if (response.isSuccessful) {
                val body = response.body() ?: return@withContext Result.failure(
                    Exception("Empty response from server")
                )
                diagnosticAttemptId?.let { attemptId ->
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "prelogin_ok",
                    )
                }
                
                Result.success(
                    normalizePreLoginResult(
                        kdfType = body.kdf,
                        kdfIterations = body.kdfIterations,
                        kdfMemory = body.kdfMemory,
                        kdfParallelism = body.kdfParallelism,
                        diagnosticAttemptId = diagnosticAttemptId
                    )
                )
            } else {
                response.errorBody()?.close()
                diagnosticAttemptId?.let { attemptId ->
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "prelogin_fail",
                        httpStatus = response.code(),
                    )
                }
                Result.failure(
                    redactedBitwardenAuthResponse(response.code())
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            diagnosticAttemptId?.let { attemptId ->
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "prelogin_exception",
                    failureKind = classifyBitwardenLoginError(e),
                )
            }
            Result.failure(redactedBitwardenAuthException(e))
        }
    }
    
    /**
     * 登录 - 完整流程
     * 
     * @param email 用户邮箱
     * @param password 用户主密码
     * @param serverUrl Vault 服务 URL
     * @return 登录结果，包含令牌和加密密钥
     */
    suspend fun login(
        email: String,
        password: String,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null,
        rememberedTwoFactorToken: String? = null,
        environment: BitwardenEnvironment? = null
    ): Result<LoginResult> = withContext(Dispatchers.IO) {
        var masterKey: ByteArray? = null
        var stretchedKey: SymmetricCryptoKey? = null
        val attemptId = newAttemptId()
        
        try {
            val normalizedEmail = email.trim()
            val requestEnvironment = environment ?: captureEnvironment(serverUrl, tlsConfig)
            val urls = requestEnvironment.serverUrls()
            val normalizedCaptcha = captchaResponse?.trim()?.takeIf { it.isNotBlank() }
            val primaryHeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT

            logDiag(
                flow = "primary",
                attemptId = attemptId,
                stage = "start",
            )
            
            // 1. 预登录获取 KDF 参数
            val preLoginResult = preLogin(
                email = normalizedEmail,
                serverUrl = serverUrl,
                diagnosticAttemptId = attemptId,
                environment = requestEnvironment
            ).getOrElse {
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "stop_prelogin_error",
                    failureKind = classifyBitwardenLoginError(it),
                )
                return@withContext Result.failure(it)
            }
            
            // 邮箱必须小写化 (使用英文 locale，与 Keyguard 保持一致)
            val emailLower = normalizedEmail.lowercase(Locale.ENGLISH)
            
            // 2. 派生 Master Key
            masterKey = when (preLoginResult.kdfType) {
                BitwardenVault.KDF_TYPE_ARGON2ID -> {
                    BitwardenCrypto.deriveMasterKeyArgon2(
                        password = password,
                        salt = emailLower,  // 使用小写邮箱作为盐
                        iterations = preLoginResult.kdfIterations,
                        memory = preLoginResult.kdfMemory ?: ARGON2_DEFAULT_MEMORY_MB,
                        parallelism = preLoginResult.kdfParallelism ?: ARGON2_DEFAULT_PARALLELISM
                    )
                }

                BitwardenVault.KDF_TYPE_PBKDF2 -> {
                    BitwardenCrypto.deriveMasterKeyPbkdf2(
                        password = password,
                        salt = emailLower,  // 使用小写邮箱作为盐
                        iterations = preLoginResult.kdfIterations
                    )
                }

                else -> {
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "stop_unsupported_kdf",
                    )
                    return@withContext Result.failure(
                        IllegalArgumentException("Unsupported KDF type: ${preLoginResult.kdfType}")
                    )
                }
            }
            
            // 3. 派生 Master Password Hash (用于服务器认证)
            val passwordHash = BitwardenCrypto.deriveMasterPasswordHash(masterKey, password)
            
            // 4. 扩展 Master Key 为 Stretched Key
            stretchedKey = BitwardenCrypto.stretchMasterKey(masterKey)
            
            // 5. 准备 Auth-Email header (Base64 编码的邮箱，URL-safe)
            // 注意: Auth-Email 和 username 使用原始邮箱，不是小写！
            // 只有密钥派生使用小写邮箱作为盐
            val authEmail = toBase64UrlNoPadding(normalizedEmail)
            val deviceId = getDeviceId()
            logDiag(
                flow = "primary",
                attemptId = attemptId,
                stage = "token_request",
            )
            
            // 6. 发送登录请求 (模拟 Keyguard Linux Desktop 模式)
            val identityApi = apiManager.getIdentityApi(environment = requestEnvironment, headerProfile = primaryHeaderProfile)
            val response = if (!rememberedTwoFactorToken.isNullOrBlank()) identityApi.loginTwoFactor(
                authEmail = authEmail,
                username = normalizedEmail,
                passwordHash = passwordHash,
                captchaResponse = normalizedCaptcha,
                deviceIdentifier = deviceId,
                twoFactorToken = rememberedTwoFactorToken,
                twoFactorProvider = TWO_FACTOR_REMEMBER,
                twoFactorRemember = 0
            ) else identityApi.login(
                authEmail = authEmail,
                username = normalizedEmail,
                passwordHash = passwordHash,
                captchaResponse = normalizedCaptcha,
                deviceIdentifier = deviceId
                // deviceName 使用默认值 "linux"
            )
            
            if (response.isSuccessful) {
                val body = response.body() ?: return@withContext Result.failure(
                    Exception("Empty login response")
                )
                
                // 检查是否需要两步验证
                val challengeProviders = BitwardenTwoFactorPolicy.providers(body)
                if (challengeProviders.isNotEmpty()) {
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "result_two_factor",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.TwoFactorRequired(
                            environment = requestEnvironment,
                            providers = challengeProviders,
                            providersData = body.twoFactorProviders2,
                            // 保存中间状态用于后续两步验证
                            tempMasterKey = masterKey,
                            tempStretchedKey = stretchedKey,
                            email = normalizedEmail,
                            passwordHash = passwordHash,
                            kdfType = preLoginResult.kdfType,
                            kdfIterations = preLoginResult.kdfIterations,
                            kdfMemory = preLoginResult.kdfMemory,
                            kdfParallelism = preLoginResult.kdfParallelism,
                            authHeaderProfile = primaryHeaderProfile,
                            diagnosticAttemptId = attemptId
                        )
                    )
                }

                // 解密 Protected Symmetric Key
                val encryptedKey = body.key ?: return@withContext Result.failure(
                    Exception("No encryption key in response")
                )
                
                val symmetricKey = BitwardenCrypto.decryptSymmetricKey(encryptedKey, stretchedKey)
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "result_success",
                    httpStatus = response.code(),
                )
                
                Result.success(
                    LoginResult.Success(
                        environment = requestEnvironment,
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        expiresIn = body.expiresIn,
                        masterKey = masterKey,
                        stretchedKey = stretchedKey,
                        symmetricKey = symmetricKey,
                        kdfType = preLoginResult.kdfType,
                        kdfIterations = preLoginResult.kdfIterations,
                        kdfMemory = preLoginResult.kdfMemory,
                        kdfParallelism = preLoginResult.kdfParallelism,
                        serverUrls = urls,
                        twoFactorToken = body.twoFactorToken
                    )
                )
            } else {
                val errorBody = response.errorBody()?.string()
                val errorResponse = parseTokenError(errorBody)

                // 两步验证 (标准 2FA)
                val providers = errorResponse?.let(BitwardenTwoFactorPolicy::providers)
                if (!providers.isNullOrEmpty()) {
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "result_two_factor_from_error",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.TwoFactorRequired(
                            environment = requestEnvironment,
                            providers = providers,
                            providersData = errorResponse.twoFactorProviders2,
                            tempMasterKey = masterKey,
                            tempStretchedKey = stretchedKey,
                            email = normalizedEmail,
                            passwordHash = passwordHash,
                            kdfType = preLoginResult.kdfType,
                            kdfIterations = preLoginResult.kdfIterations,
                            kdfMemory = preLoginResult.kdfMemory,
                            kdfParallelism = preLoginResult.kdfParallelism,
                            authHeaderProfile = primaryHeaderProfile,
                            diagnosticAttemptId = attemptId
                        )
                    )
                }

                // 新设备验证 (Email New Device OTP)
                if (isNewDeviceVerificationRequired(errorResponse)) {
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "result_new_device_required",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.TwoFactorRequired(
                            environment = requestEnvironment,
                            providers = listOf(TWO_FACTOR_EMAIL_NEW_DEVICE),
                            providersData = null,
                            tempMasterKey = masterKey,
                            tempStretchedKey = stretchedKey,
                            email = normalizedEmail,
                            passwordHash = passwordHash,
                            kdfType = preLoginResult.kdfType,
                            kdfIterations = preLoginResult.kdfIterations,
                            kdfMemory = preLoginResult.kdfMemory,
                            kdfParallelism = preLoginResult.kdfParallelism,
                            authHeaderProfile = primaryHeaderProfile,
                            diagnosticAttemptId = attemptId
                        )
                    )
                }

                if (isCaptchaRequired(errorResponse, errorBody)) {
                    val message = strings.get(R.string.legacy_ui_captcha_needed)
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "result_captcha_required",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.CaptchaRequired(
                            message = message,
                            siteKey = errorResponse?.hCaptchaSiteKey
                        )
                    )
                }
                if (shouldRetryWithKeyguardFallback(
                        responseCode = response.code(),
                        errorResponse = errorResponse,
                        errorBody = errorBody,
                        captchaProvided = !normalizedCaptcha.isNullOrBlank()
                    )
                ) {
                    val retryHeaderProfile = BitwardenApiFactory.HeaderProfile.KEYGUARD_FALLBACK
                    logDiag(
                        flow = "primary",
                        attemptId = attemptId,
                        stage = "retry_keyguard_start",
                        httpStatus = response.code(),
                    )

                    try {
                        val retryIdentityApi = apiManager.getIdentityApi(environment = requestEnvironment, headerProfile = retryHeaderProfile)
                        val retryResponse = if (!rememberedTwoFactorToken.isNullOrBlank()) retryIdentityApi.loginTwoFactor(
                            authEmail = authEmail,
                            username = normalizedEmail,
                            passwordHash = passwordHash,
                            captchaResponse = normalizedCaptcha,
                            deviceIdentifier = deviceId,
                            twoFactorToken = rememberedTwoFactorToken,
                            twoFactorProvider = TWO_FACTOR_REMEMBER,
                            twoFactorRemember = 0
                        ) else retryIdentityApi.login(
                            authEmail = authEmail,
                            username = normalizedEmail,
                            passwordHash = passwordHash,
                            captchaResponse = normalizedCaptcha,
                            deviceIdentifier = deviceId
                        )
                        if (retryResponse.isSuccessful) {
                            val retryBody = retryResponse.body() ?: return@withContext Result.failure(
                                Exception("Empty login response on retry")
                            )
                            val retryChallengeProviders = BitwardenTwoFactorPolicy.providers(retryBody)
                            if (retryChallengeProviders.isNotEmpty()) {
                                logDiag(
                                    flow = "primary",
                                    attemptId = attemptId,
                                    stage = "retry_keyguard_two_factor",
                                    httpStatus = retryResponse.code(),
                                )
                                return@withContext Result.success(
                                    LoginResult.TwoFactorRequired(
                                        environment = requestEnvironment,
                                        providers = retryChallengeProviders,
                                        providersData = retryBody.twoFactorProviders2,
                                        tempMasterKey = masterKey,
                                        tempStretchedKey = stretchedKey,
                                        email = normalizedEmail,
                                        passwordHash = passwordHash,
                                        kdfType = preLoginResult.kdfType,
                                        kdfIterations = preLoginResult.kdfIterations,
                                        kdfMemory = preLoginResult.kdfMemory,
                                        kdfParallelism = preLoginResult.kdfParallelism,
                                        authHeaderProfile = retryHeaderProfile,
                                        diagnosticAttemptId = attemptId
                                    )
                                )
                            }

                            val retryEncryptedKey = retryBody.key ?: return@withContext Result.failure(
                                Exception("No encryption key in retry response")
                            )
                            val retrySymmetricKey = BitwardenCrypto.decryptSymmetricKey(
                                retryEncryptedKey,
                                stretchedKey
                            )
                            logDiag(
                                flow = "primary",
                                attemptId = attemptId,
                                stage = "retry_keyguard_success",
                                httpStatus = retryResponse.code(),
                            )
                            return@withContext Result.success(
                                LoginResult.Success(
                                    environment = requestEnvironment,
                                    accessToken = retryBody.accessToken,
                                    refreshToken = retryBody.refreshToken,
                                    expiresIn = retryBody.expiresIn,
                                    masterKey = masterKey,
                                    stretchedKey = stretchedKey,
                                    symmetricKey = retrySymmetricKey,
                                    kdfType = preLoginResult.kdfType,
                                    kdfIterations = preLoginResult.kdfIterations,
                                    kdfMemory = preLoginResult.kdfMemory,
                                    kdfParallelism = preLoginResult.kdfParallelism,
                                    serverUrls = urls,
                                    twoFactorToken = retryBody.twoFactorToken
                                )
                            )
                        } else {
                            val retryErrorBody = retryResponse.errorBody()?.string()
                            val retryErrorResponse = parseTokenError(retryErrorBody)

                            val retryProviders = retryErrorResponse?.let(BitwardenTwoFactorPolicy::providers)
                            if (!retryProviders.isNullOrEmpty()) {
                                logDiag(
                                    flow = "primary",
                                    attemptId = attemptId,
                                    stage = "retry_keyguard_two_factor_from_error",
                                    httpStatus = retryResponse.code(),
                                )
                                return@withContext Result.success(
                                    LoginResult.TwoFactorRequired(
                                        environment = requestEnvironment,
                                        providers = retryProviders,
                                        providersData = retryErrorResponse.twoFactorProviders2,
                                        tempMasterKey = masterKey,
                                        tempStretchedKey = stretchedKey,
                                        email = normalizedEmail,
                                        passwordHash = passwordHash,
                                        kdfType = preLoginResult.kdfType,
                                        kdfIterations = preLoginResult.kdfIterations,
                                        kdfMemory = preLoginResult.kdfMemory,
                                        kdfParallelism = preLoginResult.kdfParallelism,
                                        authHeaderProfile = retryHeaderProfile,
                                        diagnosticAttemptId = attemptId
                                    )
                                )
                            }

                            if (isNewDeviceVerificationRequired(retryErrorResponse)) {
                                logDiag(
                                    flow = "primary",
                                    attemptId = attemptId,
                                    stage = "retry_keyguard_new_device_required",
                                    httpStatus = retryResponse.code(),
                                )
                                return@withContext Result.success(
                                    LoginResult.TwoFactorRequired(
                                        environment = requestEnvironment,
                                        providers = listOf(TWO_FACTOR_EMAIL_NEW_DEVICE),
                                        providersData = null,
                                        tempMasterKey = masterKey,
                                        tempStretchedKey = stretchedKey,
                                        email = normalizedEmail,
                                        passwordHash = passwordHash,
                                        kdfType = preLoginResult.kdfType,
                                        kdfIterations = preLoginResult.kdfIterations,
                                        kdfMemory = preLoginResult.kdfMemory,
                                        kdfParallelism = preLoginResult.kdfParallelism,
                                        authHeaderProfile = retryHeaderProfile,
                                        diagnosticAttemptId = attemptId
                                    )
                                )
                            }

                            if (isCaptchaRequired(retryErrorResponse, retryErrorBody)) {
                                val retryMessage = strings.get(R.string.legacy_ui_captcha_needed)
                                logDiag(
                                    flow = "primary",
                                    attemptId = attemptId,
                                    stage = "retry_keyguard_captcha_required",
                                    httpStatus = retryResponse.code(),
                                )
                                return@withContext Result.success(
                                    LoginResult.CaptchaRequired(
                                        message = retryMessage,
                                        siteKey = retryErrorResponse?.hCaptchaSiteKey
                                    )
                                )
                            }

                            logDiag(
                                flow = "primary",
                                attemptId = attemptId,
                                stage = "retry_keyguard_error",
                                httpStatus = retryResponse.code(),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (retryError: Exception) {
                        logDiag(
                            flow = "primary",
                            attemptId = attemptId,
                            stage = "retry_keyguard_exception",
                            failureKind = classifyBitwardenLoginError(retryError),
                        )
                    }
                }
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "result_error",
                    httpStatus = response.code(),
                )
                
                Result.failure(
                    redactedBitwardenAuthResponse(response.code(), errorBody)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logDiag(
                flow = "primary",
                attemptId = attemptId,
                stage = "exception",
                failureKind = classifyBitwardenLoginError(e),
            )
            Result.failure(redactedBitwardenAuthException(e))
        } finally {
            // 安全清除敏感数据 (如果登录失败)
            // 成功时密钥需要传递给调用者，由调用者负责清理
        }
    }
    
    /**
     * 两步验证登录
     */
    suspend fun loginTwoFactor(
        twoFactorState: LoginResult.TwoFactorRequired,
        twoFactorCode: String,
        twoFactorProvider: Int,
        remember: Boolean = false,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null
    ): Result<LoginResult> = withContext(Dispatchers.IO) {
        val attemptId = twoFactorState.diagnosticAttemptId ?: newAttemptId()
        try {
            if (twoFactorProvider !in twoFactorState.providers || !BitwardenTwoFactorPolicy.supportsCode(twoFactorProvider)) {
                return@withContext Result.failure(IllegalArgumentException(strings.get(R.string.bitwarden_two_factor_unsupported)))
            }
            val normalizedCode = BitwardenTwoFactorPolicy.normalizeCode(twoFactorProvider, twoFactorCode)
            if (normalizedCode.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException(strings.get(R.string.legacy_ui_verification_code)))
            }
            val requestEnvironment = twoFactorState.environment ?: captureEnvironment(serverUrl, tlsConfig)
            val urls = requestEnvironment.serverUrls()
            val normalizedCaptcha = captchaResponse?.trim()?.takeIf { it.isNotBlank() }
            val headerProfile = twoFactorState.authHeaderProfile
            logDiag(
                flow = "two_factor",
                attemptId = attemptId,
                stage = "start",
            )
            val identityApi = apiManager.getIdentityApi(environment = requestEnvironment, headerProfile = headerProfile)
            
            // Auth-Email header - 使用原始邮箱，不是小写！
            val authEmail = toBase64UrlNoPadding(twoFactorState.email)
            
            val response = identityApi.loginTwoFactor(
                authEmail = authEmail,
                username = twoFactorState.email,
                passwordHash = twoFactorState.passwordHash,
                captchaResponse = normalizedCaptcha,
                deviceIdentifier = getDeviceId(),
                // deviceName 使用默认值 "linux"
                twoFactorToken = normalizedCode,
                twoFactorProvider = twoFactorProvider,
                twoFactorRemember = if (remember) 1 else 0
            )
            
            if (response.isSuccessful) {
                val body = response.body() ?: return@withContext Result.failure(
                    Exception("Empty two-factor response")
                )
                
                val encryptedKey = body.key ?: return@withContext Result.failure(
                    Exception("No encryption key in response")
                )
                
                val symmetricKey = BitwardenCrypto.decryptSymmetricKey(
                    encryptedKey, 
                    twoFactorState.tempStretchedKey
                )
                logDiag(
                    flow = "two_factor",
                    attemptId = attemptId,
                    stage = "result_success",
                    httpStatus = response.code(),
                )
                
                Result.success(
                    LoginResult.Success(
                        environment = requestEnvironment,
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        expiresIn = body.expiresIn,
                        masterKey = twoFactorState.tempMasterKey,
                        stretchedKey = twoFactorState.tempStretchedKey,
                        symmetricKey = symmetricKey,
                        kdfType = twoFactorState.kdfType,
                        kdfIterations = twoFactorState.kdfIterations,
                        kdfMemory = twoFactorState.kdfMemory,
                        kdfParallelism = twoFactorState.kdfParallelism,
                        serverUrls = urls,
                        twoFactorToken = body.twoFactorToken
                    )
                )
            } else {
                val errorBody = response.errorBody()?.string()
                val errorResponse = parseTokenError(errorBody)
                if (isCaptchaRequired(errorResponse, errorBody)) {
                    val message = strings.get(R.string.legacy_ui_captcha_needed)
                    logDiag(
                        flow = "two_factor",
                        attemptId = attemptId,
                        stage = "result_captcha_required",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.CaptchaRequired(
                            message = message,
                            siteKey = errorResponse?.hCaptchaSiteKey
                        )
                    )
                }
                logDiag(
                    flow = "two_factor",
                    attemptId = attemptId,
                    stage = "result_error",
                    httpStatus = response.code(),
                )
                Result.failure(
                    redactedBitwardenAuthResponse(response.code(), errorBody)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logDiag(
                flow = "two_factor",
                attemptId = attemptId,
                stage = "exception",
                failureKind = classifyBitwardenLoginError(e),
            )
            Result.failure(redactedBitwardenAuthException(e))
        }
    }

    /**
     * 新设备验证登录 (Email New Device OTP)
     */
    suspend fun loginNewDeviceOtp(
        twoFactorState: LoginResult.TwoFactorRequired,
        newDeviceOtp: String,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        captchaResponse: String? = null,
        tlsConfig: BitwardenTlsConfig? = null
    ): Result<LoginResult> = withContext(Dispatchers.IO) {
        val attemptId = twoFactorState.diagnosticAttemptId ?: newAttemptId()
        try {
            val requestEnvironment = twoFactorState.environment ?: captureEnvironment(serverUrl, tlsConfig)
            val urls = requestEnvironment.serverUrls()
            val normalizedCaptcha = captchaResponse?.trim()?.takeIf { it.isNotBlank() }
            val headerProfile = twoFactorState.authHeaderProfile
            logDiag(
                flow = "new_device",
                attemptId = attemptId,
                stage = "start",
            )
            val identityApi = apiManager.getIdentityApi(environment = requestEnvironment, headerProfile = headerProfile)

            val authEmail = toBase64UrlNoPadding(twoFactorState.email)

            val response = identityApi.loginNewDeviceOtp(
                authEmail = authEmail,
                username = twoFactorState.email,
                passwordHash = twoFactorState.passwordHash,
                captchaResponse = normalizedCaptcha,
                deviceIdentifier = getDeviceId(),
                newDeviceOtp = newDeviceOtp.trim()
            )

            if (response.isSuccessful) {
                val body = response.body() ?: return@withContext Result.failure(
                    Exception("Empty new device response")
                )

                val encryptedKey = body.key ?: return@withContext Result.failure(
                    Exception("No encryption key in response")
                )

                val symmetricKey = BitwardenCrypto.decryptSymmetricKey(
                    encryptedKey,
                    twoFactorState.tempStretchedKey
                )
                logDiag(
                    flow = "new_device",
                    attemptId = attemptId,
                    stage = "result_success",
                    httpStatus = response.code(),
                )

                Result.success(
                    LoginResult.Success(
                        environment = requestEnvironment,
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        expiresIn = body.expiresIn,
                        masterKey = twoFactorState.tempMasterKey,
                        stretchedKey = twoFactorState.tempStretchedKey,
                        symmetricKey = symmetricKey,
                        kdfType = twoFactorState.kdfType,
                        kdfIterations = twoFactorState.kdfIterations,
                        kdfMemory = twoFactorState.kdfMemory,
                        kdfParallelism = twoFactorState.kdfParallelism,
                        serverUrls = urls
                    )
                )
            } else {
                val errorBody = response.errorBody()?.string()
                val errorResponse = parseTokenError(errorBody)
                if (isCaptchaRequired(errorResponse, errorBody)) {
                    val message = strings.get(R.string.legacy_ui_captcha_needed)
                    logDiag(
                        flow = "new_device",
                        attemptId = attemptId,
                        stage = "result_captcha_required",
                        httpStatus = response.code(),
                    )
                    return@withContext Result.success(
                        LoginResult.CaptchaRequired(
                            message = message,
                            siteKey = errorResponse?.hCaptchaSiteKey
                        )
                    )
                }
                logDiag(
                    flow = "new_device",
                    attemptId = attemptId,
                    stage = "result_error",
                    httpStatus = response.code(),
                )
                Result.failure(
                    redactedBitwardenAuthResponse(response.code(), errorBody)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logDiag(
                flow = "new_device",
                attemptId = attemptId,
                stage = "exception",
                failureKind = classifyBitwardenLoginError(e),
            )
            Result.failure(redactedBitwardenAuthException(e))
        }
    }

    /** Uses the pending new-device proof; never falls back to ordinary email 2FA. */
    suspend fun resendNewDeviceOtp(
        twoFactorState: LoginResult.TwoFactorRequired,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        tlsConfig: BitwardenTlsConfig? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (TWO_FACTOR_EMAIL_NEW_DEVICE !in twoFactorState.providers ||
            twoFactorState.email.isBlank() || twoFactorState.passwordHash.isBlank()
        ) {
            return@withContext Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.FAILED))
        }
        try {
            val requestEnvironment = twoFactorState.environment ?: captureEnvironment(serverUrl, tlsConfig)
            val response = apiManager.getVaultApi(environment = requestEnvironment, headerProfile = twoFactorState.authHeaderProfile).resendNewDeviceOtp(
                ResendNewDeviceOtpRequest(twoFactorState.email, twoFactorState.passwordHash)
            )
            // Error bodies may echo the email or authentication proof. Do not read or log them.
            response.errorBody()?.close()
            currentCoroutineContext().ensureActive()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val failure = when (response.code()) {
                    404, 405 -> NewDeviceOtpResendFailure.UNAVAILABLE
                    429 -> NewDeviceOtpResendFailure.RATE_LIMITED
                    else -> NewDeviceOtpResendFailure.FAILED
                }
                Result.failure(NewDeviceOtpResendException(
                    failure,
                    newDeviceOtpRetryAfterSeconds(response.headers()["Retry-After"])
                ))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not retain a cause whose message may contain a URL or server response.
            Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.FAILED))
        }
    }

    suspend fun sendTwoFactorEmailLogin(
        twoFactorState: LoginResult.TwoFactorRequired,
        serverUrl: String = BitwardenApiFactory.OFFICIAL_VAULT_URL,
        tlsConfig: BitwardenTlsConfig? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val attemptId = twoFactorState.diagnosticAttemptId ?: newAttemptId()
        try {
            val requestEnvironment = twoFactorState.environment ?: captureEnvironment(serverUrl, tlsConfig)
            val headerProfile = twoFactorState.authHeaderProfile
            logDiag(
                flow = "two_factor_email",
                attemptId = attemptId,
                stage = "send_start",
            )
            val vaultApi = apiManager.getVaultApi(environment = requestEnvironment, headerProfile = headerProfile)
            val response = vaultApi.sendTwoFactorEmailLogin(
                SendEmailLoginRequest(
                    deviceIdentifier = getDeviceId(),
                    email = twoFactorState.email,
                    masterPasswordHash = twoFactorState.passwordHash
                )
            )
            if (response.isSuccessful) {
                logDiag(
                    flow = "two_factor_email",
                    attemptId = attemptId,
                    stage = "send_success",
                    httpStatus = response.code(),
                )
                Result.success(Unit)
            } else {
                response.errorBody()?.close()
                logDiag(
                    flow = "two_factor_email",
                    attemptId = attemptId,
                    stage = "send_error",
                    httpStatus = response.code(),
                )
                Result.failure(
                    redactedBitwardenAuthResponse(response.code())
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logDiag(
                flow = "two_factor_email",
                attemptId = attemptId,
                stage = "send_exception",
                failureKind = classifyBitwardenLoginError(e),
            )
            Result.failure(redactedBitwardenAuthException(e))
        }
    }
    
    /**
     * 刷新访问令牌
     */
    suspend fun refreshToken(
        refreshToken: String,
        identityUrl: String = BitwardenApiFactory.OFFICIAL_IDENTITY_URL,
        refererUrl: String? = null,
        tlsConfig: BitwardenTlsConfig? = null,
        environment: BitwardenEnvironment? = null,
        vault: BitwardenVault? = null,
    ): Result<RefreshResult> = withContext(Dispatchers.IO) {
        try {
            val identityApi = if (vault != null) apiManager.getIdentityApi(vault) else apiManager.getIdentityApi(
                environment ?: captureEnvironment(refererUrl ?: identityUrl, tlsConfig, identityUrl))
            
            val response = identityApi.refreshToken(
                refreshToken = refreshToken
            )
            
            if (response.isSuccessful) {
                val body = response.body() ?: return@withContext Result.failure(
                    Exception("Empty refresh response")
                )
                
                Result.success(
                    RefreshResult(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        expiresIn = body.expiresIn
                    )
                )
            } else {
                response.errorBody()?.close()
                Result.failure(
                    Exception("Token refresh failed: ${response.code()}")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(redactedBitwardenAuthException(e))
        }
    }
    
    /**
     * 获取设备 ID
     */
    fun retireEnvironment(environment: BitwardenEnvironment) = apiManager.retireEnvironment(environment)

    private fun captureEnvironment(serverUrl: String, tlsConfig: BitwardenTlsConfig?, identityUrl: String? = null): BitwardenEnvironment =
        BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot(
            serverUrl, identityUrl = identityUrl, tls = tlsConfig ?: BitwardenTlsConfig()), UUID.randomUUID().toString()).value
            ?: throw IllegalArgumentException("Bitwarden transport configuration rejected")

    private fun BitwardenEnvironment.serverUrls() = BitwardenApiFactory.ServerUrls(
        base.toString().removeSuffix("/"), identity.toString().removeSuffix("/"), api.toString().removeSuffix("/"),
    )

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("bitwarden_device", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", newId).apply()
        return newId
    }
    
    private fun toBase64UrlNoPadding(value: String): String {
        val base64 = Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return base64
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private fun parseTokenError(errorBody: String?): TokenResponse? {
        if (errorBody.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<TokenResponse>(errorBody) }
            .getOrNull()
    }

    private fun isNewDeviceVerificationRequired(errorResponse: TokenResponse?): Boolean {
        val modelMessage = errorResponse?.errorModel?.message.orEmpty()
        val errorDescription = errorResponse?.errorDescription.orEmpty()
        return modelMessage.contains("new device verification required", ignoreCase = true) ||
            errorDescription.contains("new device verification required", ignoreCase = true)
    }

    private fun isCaptchaRequired(errorResponse: TokenResponse?, errorBody: String?): Boolean {
        if (!errorResponse?.hCaptchaSiteKey.isNullOrBlank()) return true
        return errorBody?.contains("captcha", ignoreCase = true) == true
    }

    private fun shouldRetryWithKeyguardFallback(
        responseCode: Int,
        errorResponse: TokenResponse?,
        errorBody: String?,
        captchaProvided: Boolean
    ): Boolean {
        if (captchaProvided) return false
        if (responseCode != 400) return false

        val error = errorResponse?.error
        val description = errorResponse?.errorDescription
        val isInvalidGrant = error.equals("invalid_grant", ignoreCase = true)
        val isInvalidCredDescription = description.equals("invalid_username_or_password", ignoreCase = true)
        val isInvalidCredBody = errorBody?.contains("invalid_username_or_password", ignoreCase = true) == true

        return isInvalidGrant && (isInvalidCredDescription || isInvalidCredBody)
    }

    private fun normalizePreLoginResult(
        kdfType: Int,
        kdfIterations: Int,
        kdfMemory: Int?,
        kdfParallelism: Int?,
        diagnosticAttemptId: String?
    ): PreLoginResult {
        val normalized = when (kdfType) {
            BitwardenVault.KDF_TYPE_PBKDF2 -> PreLoginResult(
                kdfType = kdfType,
                kdfIterations = kdfIterations.takePositiveOrDefault(PBKDF2_DEFAULT_ITERATIONS),
                kdfMemory = null,
                kdfParallelism = null
            )

            BitwardenVault.KDF_TYPE_ARGON2ID -> PreLoginResult(
                kdfType = kdfType,
                kdfIterations = kdfIterations.takePositiveOrDefault(ARGON2_DEFAULT_ITERATIONS),
                kdfMemory = kdfMemory.takePositiveOrDefault(ARGON2_DEFAULT_MEMORY_MB),
                kdfParallelism = kdfParallelism.takePositiveOrDefault(ARGON2_DEFAULT_PARALLELISM)
            )

            else -> PreLoginResult(
                kdfType = kdfType,
                kdfIterations = kdfIterations,
                kdfMemory = kdfMemory,
                kdfParallelism = kdfParallelism
            )
        }

        if (
            normalized.kdfIterations != kdfIterations ||
            normalized.kdfMemory != kdfMemory ||
            normalized.kdfParallelism != kdfParallelism
        ) {
            diagnosticAttemptId?.let { attemptId ->
                logDiag(
                    flow = "primary",
                    attemptId = attemptId,
                    stage = "prelogin_normalized",
                )
            }
        }

        return normalized
    }

    private fun Int?.takePositiveOrDefault(default: Int): Int {
        return this?.takeIf { it > 0 } ?: default
    }

    private fun newAttemptId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun logDiag(
        flow: String,
        attemptId: String,
        stage: String,
        httpStatus: Int? = null,
        failureKind: BitwardenLoginError? = null,
    ) {
        val line = bitwardenAuthDiagnosticLine(flow, attemptId, stage, httpStatus, failureKind)
        Log.e(TAG, line)
        BitwardenDiagLogger.append("$TAG: $line")
    }

}

// ========== 结果数据类 ==========

data class PreLoginResult(
    val kdfType: Int,
    val kdfIterations: Int,
    val kdfMemory: Int?,
    val kdfParallelism: Int?
)

sealed class LoginResult {
    
    data class Success(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int,
        val masterKey: ByteArray,
        val stretchedKey: SymmetricCryptoKey,
        val symmetricKey: SymmetricCryptoKey,
        val kdfType: Int,
        val kdfIterations: Int,
        val kdfMemory: Int?,
        val kdfParallelism: Int?,
        val serverUrls: BitwardenApiFactory.ServerUrls,
        val twoFactorToken: String? = null,
        val environment: BitwardenEnvironment? = null
    ) : LoginResult() {
        
        /**
         * 清除敏感密钥材料
         * 调用者在保存必要数据后应调用此方法
         */
        fun clearKeys() {
            masterKey.fill(0)
            stretchedKey.clear()
            symmetricKey.clear()
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return accessToken == other.accessToken
        }
        
        override fun hashCode(): Int = accessToken.hashCode()
    }
    
    data class TwoFactorRequired(
        val providers: List<Int>,
        val providersData: Map<String, JsonElement>?,
        val tempMasterKey: ByteArray,
        val tempStretchedKey: SymmetricCryptoKey,
        val email: String,
        val passwordHash: String,
        val kdfType: Int,
        val kdfIterations: Int,
        val kdfMemory: Int?,
        val kdfParallelism: Int?,
        val authHeaderProfile: BitwardenApiFactory.HeaderProfile = BitwardenApiFactory.HeaderProfile.MONICA_DEFAULT,
        val diagnosticAttemptId: String? = null,
        val authSessionEpoch: Long? = null,
        val authAppGeneration: Long? = null,
        val environment: BitwardenEnvironment? = null
    ) : LoginResult() {
        
        /**
         * 获取支持的两步验证方式名称
         */
        fun getProviderNames(): List<String> {
            return providers.map { provider ->
                when (provider) {
                    BitwardenAuthService.TWO_FACTOR_AUTHENTICATOR -> "Authenticator App"
                    BitwardenAuthService.TWO_FACTOR_EMAIL -> "Email"
                    BitwardenAuthService.TWO_FACTOR_DUO -> "Duo"
                    BitwardenAuthService.TWO_FACTOR_YUBIKEY -> "YubiKey"
                    BitwardenAuthService.TWO_FACTOR_ORGANIZATION_DUO -> "Organization Duo"
                    BitwardenAuthService.TWO_FACTOR_WEBAUTHN -> "WebAuthn"
                    else -> "Unknown"
                }
            }
        }
        
        fun clear() {
            tempMasterKey.fill(0)
            tempStretchedKey.clear()
        }
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TwoFactorRequired) return false
            return email == other.email
        }
        
        override fun hashCode(): Int = email.hashCode()
    }

    data class CaptchaRequired(
        val message: String,
        val siteKey: String? = null
    ) : LoginResult()
}

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Int
)
