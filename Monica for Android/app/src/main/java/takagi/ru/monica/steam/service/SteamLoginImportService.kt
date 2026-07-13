package takagi.ru.monica.steam.service

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import takagi.ru.monica.steam.core.SteamTotp
import takagi.ru.monica.steam.diagnostics.SteamDiagLogger
import takagi.ru.monica.steam.network.SteamApiClient
import takagi.ru.monica.steam.network.SteamApiException
import takagi.ru.monica.steam.network.SteamProtoReader
import takagi.ru.monica.steam.network.SteamProtoWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

/**
 * Steam 登录导入服务层（第一阶段）
 *
 * 当前目标：
 * - 打通账号密码登录流程
 * - 返回挑战状态（邮箱码 / 令牌码）
 * - 成功时返回 access_token / refresh_token（若可用）
 *
 * 后续目标：
 * - 基于 token 拉取/生成 Steam Guard 数据并落地导入
 */
class SteamLoginImportService(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        private const val TAG = "SteamLoginImport"

        private const val URL_RSA_KEY =
            "https://api.steampowered.com/IAuthenticationService/GetPasswordRSAPublicKey/v1"
        private const val URL_BEGIN_AUTH =
            "https://api.steampowered.com/IAuthenticationService/BeginAuthSessionViaCredentials/v1"
        private const val URL_UPDATE_AUTH =
            "https://api.steampowered.com/IAuthenticationService/UpdateAuthSessionWithSteamGuardCode/v1"
        private const val URL_POLL_AUTH =
            "https://api.steampowered.com/IAuthenticationService/PollAuthSessionStatus/v1"
        private const val URL_REMOVE_AUTHENTICATOR_CHALLENGE_START =
            "https://api.steampowered.com/ITwoFactorService/RemoveAuthenticatorViaChallengeStart/v1"
        private const val URL_REMOVE_AUTHENTICATOR_CHALLENGE_CONTINUE =
            "https://api.steampowered.com/ITwoFactorService/RemoveAuthenticatorViaChallengeContinue/v1"
        private const val URL_LEGACY_RSA_KEY =
            "https://steamcommunity.com/login/getrsakey/"
        private const val URL_LEGACY_DO_LOGIN =
            "https://steamcommunity.com/login/dologin/"

        private const val STEAM_WEBSITE_ID = "Mobile"
        private const val DEVICE_FRIENDLY_NAME = "Monica Android"
        private const val MAX_POLL_ATTEMPTS = 10
        private const val POLL_INTERVAL_MS = 900L
        private const val AUTH_CODE_TYPE_EMAIL = 2
        private const val AUTH_CODE_TYPE_DEVICE = 3
        private const val AUTH_CODE_TYPE_DEVICE_CONFIRMATION = 4
        private const val AUTH_CODE_TYPE_EMAIL_CONFIRMATION = 5
        private const val LEGACY_OAUTH_CLIENT_ID = "DE45CD61"
        private const val LEGACY_OAUTH_SCOPE = "read_profile write_profile read_client write_client"
        private val UNSIGNED_LONG_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val SIGNED_LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
        private val UNSIGNED_LONG_BASE = BigInteger.ONE.shiftLeft(64)

        private const val LEGACY_CODE_TYPE_TWO_FACTOR = 1001
        private const val LEGACY_CODE_TYPE_EMAIL = 1002
        private const val REPLACE_CODE_TYPE_GENERIC = 2001
        private const val ADD_AUTHENTICATOR_SMS_ACTIVATION_CODE = 3001
        private const val ADD_AUTHENTICATOR_EMAIL_ACTIVATION_CODE = 3002
        private val JWT_JSON = Json { ignoreUnknownKeys = true }

        fun isCodeChallengeType(confirmationType: Int): Boolean {
            return confirmationType == AUTH_CODE_TYPE_EMAIL ||
                confirmationType == AUTH_CODE_TYPE_DEVICE ||
                confirmationType == LEGACY_CODE_TYPE_TWO_FACTOR ||
                confirmationType == LEGACY_CODE_TYPE_EMAIL ||
                confirmationType == REPLACE_CODE_TYPE_GENERIC ||
                isAddAuthenticatorActivationType(confirmationType)
        }

        fun isSteamGuardCodeChallengeType(confirmationType: Int): Boolean {
            return confirmationType == AUTH_CODE_TYPE_DEVICE ||
                confirmationType == LEGACY_CODE_TYPE_TWO_FACTOR ||
                confirmationType == REPLACE_CODE_TYPE_GENERIC
        }

        fun isPollingChallengeType(confirmationType: Int): Boolean {
            return confirmationType == AUTH_CODE_TYPE_DEVICE_CONFIRMATION ||
                confirmationType == AUTH_CODE_TYPE_EMAIL_CONFIRMATION
        }

        fun manualCodeTypeForPollingChallenge(confirmationType: Int): Int? {
            return when (confirmationType) {
                AUTH_CODE_TYPE_DEVICE_CONFIRMATION -> AUTH_CODE_TYPE_DEVICE
                AUTH_CODE_TYPE_EMAIL_CONFIRMATION -> AUTH_CODE_TYPE_EMAIL
                else -> null
            }
        }

        fun isAddAuthenticatorActivationType(confirmationType: Int): Boolean {
            return confirmationType == ADD_AUTHENTICATOR_SMS_ACTIVATION_CODE ||
                confirmationType == ADD_AUTHENTICATOR_EMAIL_ACTIVATION_CODE
        }

        fun isAddAuthenticatorEmailActivationType(confirmationType: Int): Boolean {
            return confirmationType == ADD_AUTHENTICATOR_EMAIL_ACTIVATION_CODE
        }

        fun steamIdFromJwt(jwt: String?): String? {
            val token = jwt?.trim().orEmpty()
            if (token.isBlank()) return null
            val payloadPart = token.split('.').getOrNull(1) ?: return null
            return runCatching {
                var paddedPayload = payloadPart.replace('-', '+').replace('_', '/')
                while (paddedPayload.length % 4 != 0) {
                    paddedPayload += "="
                }
                val payloadJson = java.util.Base64.getDecoder()
                    .decode(paddedPayload)
                    .toString(Charsets.UTF_8)
                val payload = JWT_JSON
                    .parseToJsonElement(payloadJson)
                    .jsonObject
                (payload["sub"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.toLongOrNull() != null }
            }.getOrNull()
        }
    }

    private enum class AuthFlow {
        AUTH_API,
        AUTH_API_QR,
        LEGACY_WEB,
        ADD_AUTHENTICATOR_FINALIZE,
        REPLACE_EXISTING_AUTHENTICATOR
    }

    private enum class LoginPurpose {
        IMPORT_AUTHENTICATOR,
        SESSION_ONLY
    }

    private data class PendingAuthSession(
        val flow: AuthFlow,
        val purpose: LoginPurpose = LoginPurpose.IMPORT_AUTHENTICATOR,
        val userName: String,
        val clientId: String,
        val requestId: String,
        val steamId: String,
        val allowedConfirmations: List<SteamGuardChallenge>,
        val legacyEncryptedPassword: String? = null,
        val legacyRsaTimestamp: String? = null,
        val legacyEmailSteamId: String? = null,
        val legacyChallengeType: Int? = null,
        val qrChallengeUrl: String? = null,
        val addAccessToken: String? = null,
        val addImportAccessToken: String? = null,
        val addRefreshToken: String? = null,
        val addPayload: SteamGuardPayload? = null,
        val addValidateSmsCode: Boolean = true,
        val replaceAccessToken: String? = null,
        val replaceRefreshToken: String? = null
    )

    private val pendingSessions = ConcurrentHashMap<String, PendingAuthSession>()
    private val steamApi = SteamApiClient(client, json)

    private fun logDiag(line: String) {
        SteamDiagLogger.append("[SteamLoginImport] $line")
    }

    sealed class LoginResult {
        data class ChallengeRequired(
            val pendingSessionId: String,
            val steamId: String,
            val challenges: List<SteamGuardChallenge>,
            val message: String? = null
        ) : LoginResult()

        data class ReadyForImport(
            val steamId: String,
            val payload: SteamGuardPayload,
            val accessToken: String,
            val refreshToken: String?
        ) : LoginResult()

        data class Failure(
            val message: String,
            val retryable: Boolean = true
        ) : LoginResult()
    }

    sealed class AuthorizedDeviceRevokeResult {
        object Success : AuthorizedDeviceRevokeResult()
        data class Failure(val message: String) : AuthorizedDeviceRevokeResult()
    }

    sealed class QrLoginResult {
        data class ChallengeRequired(
            val pendingSessionId: String,
            val challengeUrl: String
        ) : QrLoginResult()

        data class LoginChallengeRequired(
            val challenge: LoginResult.ChallengeRequired
        ) : QrLoginResult()

        data class ReadyForImport(
            val result: LoginResult.ReadyForImport
        ) : QrLoginResult()

        data class Failure(
            val message: String,
            val retryable: Boolean = true
        ) : QrLoginResult()
    }

    data class SteamGuardChallenge(
        val confirmationType: Int,
        val associatedMessage: String = ""
    )

    data class SteamGuardPayload(
        val deviceId: String,
        val steamGuardJson: String,
        val sessionOnly: Boolean = false,
        val accountName: String? = null
    )

    suspend fun beginLogin(
        userName: String,
        password: String
    ): LoginResult = beginLoginInternal(
        userName = userName,
        password = password,
        purpose = LoginPurpose.IMPORT_AUTHENTICATOR
    )

    suspend fun beginSessionLogin(
        userName: String,
        password: String
    ): LoginResult = beginLoginInternal(
        userName = userName,
        password = password,
        purpose = LoginPurpose.SESSION_ONLY
    )

    suspend fun revokeAuthorizedDevice(
        userName: String,
        password: String,
        sharedSecret: String,
        tokenId: String
    ): AuthorizedDeviceRevokeResult = withContext(Dispatchers.IO) {
        if (userName.isBlank() || password.isBlank() || sharedSecret.isBlank()) {
            return@withContext AuthorizedDeviceRevokeResult.Failure(
                "Steam account name, password, and authenticator secret are required"
            )
        }
        val signedTokenId = parseUnsigned64AsSignedLong(tokenId)
            ?: return@withContext AuthorizedDeviceRevokeResult.Failure(
                "Steam authorized-device token is invalid"
            )

        runCatching {
            val rsaResponse = getWithQuery(
                URL_RSA_KEY,
                mapOf("account_name" to userName.trim())
            ) ?: return@runCatching AuthorizedDeviceRevokeResult.Failure(
                "Could not obtain Steam password encryption key"
            )
            val rsaPayload = rsaResponse.responseObject()
            val publicKeyMod = rsaPayload?.string("publickey_mod").orEmpty()
            val publicKeyExp = rsaPayload?.string("publickey_exp").orEmpty()
            val timeStamp = rsaPayload?.string("timestamp").orEmpty()
            if (publicKeyMod.isBlank() || publicKeyExp.isBlank() || timeStamp.isBlank()) {
                return@runCatching AuthorizedDeviceRevokeResult.Failure(
                    "Steam password encryption response is incomplete"
                )
            }
            val encryptedPassword = encryptPasswordWithRsa(password, publicKeyMod, publicKeyExp)
                ?: return@runCatching AuthorizedDeviceRevokeResult.Failure(
                    "Could not encrypt the Steam password"
                )
            val begin = beginAuthSessionViaCredentialsWithProtobuf(
                userName = userName.trim(),
                encryptedPassword = encryptedPassword,
                encryptionTimestamp = timeStamp,
                throwApiErrors = true
            ) ?: return@runCatching AuthorizedDeviceRevokeResult.Failure(
                "Steam rejected the device-removal authentication request"
            )
            val session = PendingAuthSession(
                flow = AuthFlow.AUTH_API,
                purpose = LoginPurpose.SESSION_ONLY,
                userName = userName.trim(),
                clientId = begin.clientId,
                requestId = begin.requestId,
                steamId = begin.steamId,
                allowedConfirmations = begin.challenges
            )
            val secondsRemaining = SteamTotp.secondsRemaining(System.currentTimeMillis() / 1000L)
            if (secondsRemaining <= 2) delay((secondsRemaining + 1L) * 1000L)
            val code = SteamTotp.generateAuthCode(
                sharedSecret,
                System.currentTimeMillis() / 1000L
            )
            when (
                val submitted = submitSteamGuardCodeWithProtobuf(
                    session = session,
                    code = code,
                    confirmationType = AUTH_CODE_TYPE_DEVICE
                )
            ) {
                SteamGuardSubmitResult.Accepted -> Unit
                is SteamGuardSubmitResult.Failure -> {
                    return@runCatching AuthorizedDeviceRevokeResult.Failure(submitted.message)
                }
                SteamGuardSubmitResult.UnsupportedSession -> {
                    return@runCatching AuthorizedDeviceRevokeResult.Failure(
                        "Steam device-removal authentication is unsupported"
                    )
                }
            }
            val tokens = pollForAuthorizedDeviceRevocation(session, signedTokenId)
                ?: return@runCatching AuthorizedDeviceRevokeResult.Failure(
                    "Steam device removal timed out"
                )
            val cleanupRequest = SteamProtoWriter().apply {
                writeString(1, tokens.refreshToken)
                writeVarint(2, 1L)
            }
            steamApi.callProtobuf(
                iface = "IAuthenticationService",
                method = "RevokeToken",
                request = cleanupRequest,
                accessToken = tokens.accessToken
            )
            AuthorizedDeviceRevokeResult.Success
        }.getOrElse { error ->
            android.util.Log.e(TAG, "revokeAuthorizedDevice failed: ${error.message}", error)
            AuthorizedDeviceRevokeResult.Failure(
                mapEresultToMessage((error as? SteamApiException)?.eResult)
                    ?: error.message
                    ?: "Steam authorized-device removal failed"
            )
        }
    }

    private suspend fun beginLoginInternal(
        userName: String,
        password: String,
        purpose: LoginPurpose
    ): LoginResult = withContext(Dispatchers.IO) {
        if (userName.isBlank() || password.isBlank()) {
            return@withContext LoginResult.Failure("账号或密码不能为空", retryable = false)
        }

        runCatching {
            logDiag("begin login start")
            val rsaResponse = getWithQuery(
                URL_RSA_KEY,
                mapOf(
                    "account_name" to userName.trim()
                )
            ) ?: return@runCatching LoginResult.Failure("获取 Steam RSA 密钥失败")

            val rsaPayload = rsaResponse.responseObject()
            val rsaSuccess = rsaResponse.successBoolean() ?: (rsaPayload != null)
            if (!rsaSuccess) {
                val message = rsaPayload?.messageString()
                    ?: rsaResponse.messageString()
                    ?: "Steam 登录失败（RSA）"
                return@runCatching LoginResult.Failure(message)
            }

            val publicKeyMod = rsaPayload?.string("publickey_mod").orEmpty()
            val publicKeyExp = rsaPayload?.string("publickey_exp").orEmpty()
            val timeStamp = rsaPayload?.string("timestamp").orEmpty()
            if (publicKeyMod.isBlank() || publicKeyExp.isBlank() || timeStamp.isBlank()) {
                logDiag("begin login rsa incomplete")
                return@runCatching LoginResult.Failure("Steam RSA 响应不完整")
            }
            logDiag("begin login rsa ok")

            val encryptedPassword = encryptPasswordWithRsa(password, publicKeyMod, publicKeyExp)
                ?: return@runCatching LoginResult.Failure("Steam 密码加密失败")

            val protobufBeginSession = beginAuthSessionViaCredentialsWithProtobuf(
                userName = userName.trim(),
                encryptedPassword = encryptedPassword,
                encryptionTimestamp = timeStamp
            )
            if (protobufBeginSession != null) {
                logDiag("begin auth protobuf ok challenges=${protobufBeginSession.challenges.map { it.confirmationType }.joinToString(",")}")
                if (protobufBeginSession.challenges.isNotEmpty()) {
                    val pendingSessionId = UUID.randomUUID().toString()
                    pendingSessions[pendingSessionId] = PendingAuthSession(
                        flow = AuthFlow.AUTH_API,
                        purpose = purpose,
                        userName = userName.trim(),
                        clientId = protobufBeginSession.clientId,
                        requestId = protobufBeginSession.requestId,
                        steamId = protobufBeginSession.steamId,
                        allowedConfirmations = protobufBeginSession.challenges
                    )
                    return@runCatching LoginResult.ChallengeRequired(
                        pendingSessionId = pendingSessionId,
                        steamId = protobufBeginSession.steamId,
                        challenges = protobufBeginSession.challenges,
                        message = protobufBeginSession.message
                    )
                }

                return@runCatching pollForToken(
                    protobufBeginSession.clientId,
                    protobufBeginSession.requestId,
                    protobufBeginSession.steamId,
                    purpose = purpose
                )
            }
            logDiag("begin auth protobuf unavailable; falling back form")

            val beginAuthResponse = postForm(
                URL_BEGIN_AUTH,
                mapOf(
                    "account_name" to userName.trim(),
                    "encrypted_password" to encryptedPassword,
                    "encryption_timestamp" to timeStamp,
                    "persistence" to "1",
                    "remember_login" to "true",
                    "website_id" to STEAM_WEBSITE_ID,
                    "device_friendly_name" to DEVICE_FRIENDLY_NAME,
                    "platform_type" to "3",
                    "guard_data" to "",
                    "language" to "0",
                    "qos_level" to "2"
                )
            ) ?: return@runCatching LoginResult.Failure("Steam 登录请求失败")

            val beginPayload = beginAuthResponse.responseObject()
            val beginSuccess = beginAuthResponse.successBoolean() ?: (beginPayload != null)
            if (!beginSuccess || beginPayload == null) {
                val message = beginPayload?.messageString()
                    ?: beginAuthResponse.messageString()
                    ?: "Steam 登录失败"
                return@runCatching LoginResult.Failure(message)
            }

            val clientId = beginPayload.stringAny("client_id", "clientId", "clientID")
            val requestId = beginPayload.stringAny("request_id", "requestId", "requestID")
            val steamId = beginPayload.stringAny("steamid", "steam_id", "steamId")
            if (clientId.isNullOrBlank() || requestId.isNullOrBlank() || steamId.isNullOrBlank()) {
                val eResult = beginAuthResponse.eResultInt()
                val payloadKeys = beginPayload.keys.joinToString(",")
                android.util.Log.w(
                    TAG,
                    "BeginAuth missing required fields. eResult=$eResult, payloadKeys=[$payloadKeys]"
                )
                val fallbackResult = beginLegacyLogin(userName.trim(), password, purpose)
                if (fallbackResult !is LoginResult.Failure) {
                    android.util.Log.i(TAG, "Fallback to legacy Steam login route succeeded")
                    return@runCatching fallbackResult
                }
                val intervalHint = beginPayload.intAny("interval", "poll_interval")
                val message = beginPayload.messageString()
                    ?: mapEresultToMessage(eResult)
                    ?: if (intervalHint != null) {
                        "Steam 登录被拒绝（EResult=${eResult ?: "未知"}，interval=$intervalHint）"
                    } else {
                        "Steam 登录响应不完整（可能需要额外验证或触发风控）"
                    }
                return@runCatching LoginResult.Failure(message)
            }

            val challenges = beginPayload.allowedConfirmations()
            logDiag("begin auth form ok challenges=${challenges.map { it.confirmationType }.joinToString(",")}")
            if (challenges.isNotEmpty()) {
                val pendingSessionId = UUID.randomUUID().toString()
                pendingSessions[pendingSessionId] = PendingAuthSession(
                    flow = AuthFlow.AUTH_API,
                    purpose = purpose,
                    userName = userName.trim(),
                    clientId = clientId,
                    requestId = requestId,
                    steamId = steamId,
                    allowedConfirmations = challenges
                )
                return@runCatching LoginResult.ChallengeRequired(
                    pendingSessionId = pendingSessionId,
                    steamId = steamId,
                    challenges = challenges,
                    message = beginPayload.messageString()
                )
            }

            pollForToken(clientId, requestId, steamId, purpose = purpose)
        }.getOrElse { error ->
            android.util.Log.e(TAG, "beginLogin failed: ${error.message}", error)
            LoginResult.Failure(error.message ?: "Steam 登录失败")
        }
    }

    suspend fun submitSteamGuardCode(
        pendingSessionId: String,
        code: String,
        confirmationType: Int
    ): LoginResult = withContext(Dispatchers.IO) {
        if (pendingSessionId.isBlank()) {
            return@withContext LoginResult.Failure("会话无效", retryable = false)
        }
        if (code.isBlank()) {
            return@withContext LoginResult.Failure("验证码不能为空", retryable = false)
        }

        val session = pendingSessions[pendingSessionId]
            ?: return@withContext LoginResult.Failure("登录会话已过期，请重新开始", retryable = false)

        runCatching {
            if (session.flow == AuthFlow.LEGACY_WEB) {
                logDiag("submit guard code flow=legacy")
                val legacyResult = continueLegacyLogin(session, code)
                if (legacyResult is LoginResult.ReadyForImport || legacyResult is LoginResult.Failure) {
                    pendingSessions.remove(pendingSessionId)
                }
                return@runCatching legacyResult
            }
            if (session.flow == AuthFlow.ADD_AUTHENTICATOR_FINALIZE) {
                logDiag("submit guard code flow=add_finalize")
                val finalizeResult = finalizeAddAuthenticator(session, code.trim())
                if (finalizeResult is LoginResult.ReadyForImport) {
                    pendingSessions.remove(pendingSessionId)
                }
                return@runCatching finalizeResult
            }
            if (session.flow == AuthFlow.REPLACE_EXISTING_AUTHENTICATOR) {
                logDiag("submit guard code flow=replace_existing")
                val replaceResult = continueReplaceAuthenticatorFlow(session, code.trim())
                if (replaceResult is LoginResult.ReadyForImport) {
                    pendingSessions.remove(pendingSessionId)
                }
                return@runCatching replaceResult
            }

            logDiag("submit guard code flow=auth_api type=$confirmationType")
            when (val updateResult = submitSteamGuardCodeWithProtobuf(session, code.trim(), confirmationType)) {
                SteamGuardSubmitResult.Accepted -> Unit
                is SteamGuardSubmitResult.Failure -> return@runCatching LoginResult.Failure(updateResult.message)
                SteamGuardSubmitResult.UnsupportedSession -> {
                    val fallbackResult = submitSteamGuardCodeWithForm(session, code.trim(), confirmationType)
                    if (fallbackResult != null) return@runCatching fallbackResult
                }
            }

            val pollResult = pollForToken(
                clientId = session.clientId,
                requestId = session.requestId,
                steamId = session.steamId,
                purpose = session.purpose
            )
            if (pollResult is LoginResult.ReadyForImport) {
                pendingSessions.remove(pendingSessionId)
            }
            pollResult
        }.getOrElse { error ->
            android.util.Log.e(TAG, "submitSteamGuardCode failed: ${error.message}", error)
            LoginResult.Failure(error.message ?: "Steam 验证失败")
        }
    }

    suspend fun beginQrLogin(): QrLoginResult = withContext(Dispatchers.IO) {
        runCatching {
            logDiag("begin qr login start")
            val qrSession = beginAuthSessionViaQrWithProtobuf()
                ?: return@runCatching QrLoginResult.Failure("无法创建 Steam 二维码登录会话")
            val pendingSessionId = UUID.randomUUID().toString()
            pendingSessions[pendingSessionId] = PendingAuthSession(
                flow = AuthFlow.AUTH_API_QR,
                userName = "",
                clientId = qrSession.clientId,
                requestId = qrSession.requestId,
                steamId = "",
                allowedConfirmations = qrSession.challenges,
                qrChallengeUrl = qrSession.challengeUrl
            )
            logDiag("begin qr login ok")
            QrLoginResult.ChallengeRequired(
                pendingSessionId = pendingSessionId,
                challengeUrl = qrSession.challengeUrl
            )
        }.getOrElse { error ->
            android.util.Log.e(TAG, "beginQrLogin failed: ${error.message}", error)
            QrLoginResult.Failure(error.message ?: "Steam 二维码登录失败")
        }
    }

    private sealed class SteamGuardSubmitResult {
        object Accepted : SteamGuardSubmitResult()
        object UnsupportedSession : SteamGuardSubmitResult()
        data class Failure(val message: String) : SteamGuardSubmitResult()
    }

    private data class AuthApiSessionIds(
        val clientId: Long,
        val requestId: ByteArray,
        val steamId: Long?
    )

    private data class BeginAuthSessionData(
        val clientId: String,
        val requestId: String,
        val steamId: String,
        val challenges: List<SteamGuardChallenge>,
        val message: String? = null
    )

    private data class BeginQrAuthSessionData(
        val clientId: String,
        val requestId: String,
        val challengeUrl: String,
        val challenges: List<SteamGuardChallenge>
    )

    private data class AccessTokenRefreshResult(
        val accessToken: String,
        val refreshToken: String?
    )

    private data class TemporaryAuthTokens(
        val accessToken: String,
        val refreshToken: String
    )

    private fun beginAuthSessionViaCredentialsWithProtobuf(
        userName: String,
        encryptedPassword: String,
        encryptionTimestamp: String,
        throwApiErrors: Boolean = false
    ): BeginAuthSessionData? {
        val timestamp = encryptionTimestamp.toLongOrNull() ?: return null
        val request = SteamProtoWriter().apply {
            writeString(1, DEVICE_FRIENDLY_NAME)
            writeString(2, userName)
            writeString(3, encryptedPassword)
            writeUint64(4, timestamp)
            writeBool(5, false)
            writeVarint(6, 3L)
            writeVarint(7, 1L)
            writeString(8, STEAM_WEBSITE_ID)
            writeMessage(9, buildAuthApiDeviceDetails())
            writeString(10, "")
            writeVarint(11, 0L)
            writeVarint(12, 2L)
        }

        val fields = try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "IAuthenticationService",
                    method = "BeginAuthSessionViaCredentials",
                    request = request
                )
            ).parseAll()
        } catch (error: SteamApiException) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaCredentials protobuf failed: eResult=${error.eResult}, message=${error.message}"
            )
            if (throwApiErrors) throw error
            return null
        } catch (error: Exception) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaCredentials protobuf exception: ${error.message}",
                error
            )
            return null
        }

        val clientId = fields.firstOrNull { it.number == 1 }
            ?.asLong
            ?.takeIf { it != 0L }
            ?.let(::unsignedLongToString)
        val requestId = fields.firstOrNull { it.number == 2 }
            ?.bytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val steamId = fields.firstOrNull { it.number == 5 }
            ?.asLong
            ?.takeIf { it != 0L }
            ?.let(::unsignedLongToString)
        if (clientId.isNullOrBlank() || requestId.isNullOrBlank() || steamId.isNullOrBlank()) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaCredentials protobuf missing fields: fieldNumbers=${
                    fields.joinToString(",") { it.number.toString() }
                }"
            )
            return null
        }

        return BeginAuthSessionData(
            clientId = clientId,
            requestId = requestId,
            steamId = steamId,
            challenges = fields.authApiAllowedConfirmations(),
            message = fields.firstOrNull { it.number == 8 }?.asString?.takeIf { it.isNotBlank() }
        )
    }

    private fun beginAuthSessionViaQrWithProtobuf(): BeginQrAuthSessionData? {
        val request = SteamProtoWriter().apply {
            writeString(1, DEVICE_FRIENDLY_NAME)
            writeVarint(2, 3L)
            writeMessage(3, buildAuthApiDeviceDetails())
            writeString(4, STEAM_WEBSITE_ID)
        }

        val fields = try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "IAuthenticationService",
                    method = "BeginAuthSessionViaQR",
                    request = request
                )
            ).parseAll()
        } catch (error: SteamApiException) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaQR protobuf failed: eResult=${error.eResult}, message=${error.message}"
            )
            return null
        } catch (error: Exception) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaQR protobuf exception: ${error.message}",
                error
            )
            return null
        }

        val clientId = fields.firstOrNull { it.number == 1 }
            ?.asLong
            ?.takeIf { it != 0L }
            ?.let(::unsignedLongToString)
        val challengeUrl = fields.firstOrNull { it.number == 2 }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val requestId = fields.firstOrNull { it.number == 3 }
            ?.bytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

        if (clientId.isNullOrBlank() || challengeUrl.isNullOrBlank() || requestId.isNullOrBlank()) {
            android.util.Log.w(
                TAG,
                "BeginAuthSessionViaQR protobuf missing fields: fieldNumbers=${
                    fields.joinToString(",") { it.number.toString() }
                }"
            )
            return null
        }

        return BeginQrAuthSessionData(
            clientId = clientId,
            requestId = requestId,
            challengeUrl = challengeUrl,
            challenges = fields.authApiAllowedConfirmations(confirmationField = 5)
        )
    }

    private fun buildAuthApiDeviceDetails(): SteamProtoWriter {
        return SteamProtoWriter().apply {
            writeString(1, DEVICE_FRIENDLY_NAME)
            writeVarint(2, 3L)
            writeVarint(3, -500L)
            writeVarint(4, 528L)
        }
    }

    private fun List<takagi.ru.monica.steam.network.SteamProtoField>.authApiAllowedConfirmations():
        List<SteamGuardChallenge> {
        return authApiAllowedConfirmations(confirmationField = 4)
    }

    private fun List<takagi.ru.monica.steam.network.SteamProtoField>.authApiAllowedConfirmations(
        confirmationField: Int
    ): List<SteamGuardChallenge> {
        return filter { it.number == confirmationField && it.bytes != null }.mapNotNull { field ->
            val confirmation = runCatching {
                SteamProtoReader(field.bytes ?: return@mapNotNull null).parse()
            }.getOrNull() ?: return@mapNotNull null
            val type = confirmation[1]?.asInt ?: return@mapNotNull null
            if (type == 0) return@mapNotNull null
            SteamGuardChallenge(
                confirmationType = type,
                associatedMessage = confirmation[2]?.asString.orEmpty()
            )
        }
    }

    private fun submitSteamGuardCodeWithProtobuf(
        session: PendingAuthSession,
        code: String,
        confirmationType: Int
    ): SteamGuardSubmitResult {
        val clientIdLong = parseUnsigned64AsSignedLong(session.clientId)
            ?: return SteamGuardSubmitResult.UnsupportedSession
        val steamIdLong = session.steamId.toLongOrNull()
            ?: return SteamGuardSubmitResult.UnsupportedSession
        val request = SteamProtoWriter().apply {
            writeUint64(1, clientIdLong)
            writeFixed64(2, steamIdLong)
            writeString(3, code.trim())
            writeVarint(4, confirmationType.toLong())
        }

        return try {
            steamApi.callProtobuf(
                iface = "IAuthenticationService",
                method = "UpdateAuthSessionWithSteamGuardCode",
                request = request
            )
            logDiag("submit guard protobuf accepted")
            SteamGuardSubmitResult.Accepted
        } catch (error: SteamApiException) {
            when (error.eResult) {
                29 -> {
                    logDiag("submit guard protobuf duplicate accepted")
                    SteamGuardSubmitResult.Accepted
                }
                9 -> {
                    logDiag("submit guard protobuf unsupported eResult=9; falling back form")
                    SteamGuardSubmitResult.UnsupportedSession
                }
                else -> {
                    logDiag("submit guard protobuf failed eResult=${error.eResult ?: "unknown"}")
                    SteamGuardSubmitResult.Failure(
                        mapEresultToMessage(error.eResult)
                            ?: error.message
                            ?: "Steam 验证失败"
                    )
                }
            }
        } catch (error: Exception) {
            logDiag("submit guard protobuf exception type=${error.javaClass.simpleName}")
            android.util.Log.e(TAG, "submitSteamGuardCodeWithProtobuf failed: ${error.message}", error)
            SteamGuardSubmitResult.Failure(error.message ?: "提交 Steam 验证码失败")
        }
    }

    private fun submitSteamGuardCodeWithForm(
        session: PendingAuthSession,
        code: String,
        confirmationType: Int
    ): LoginResult? {
        val updateResponse = postForm(
            URL_UPDATE_AUTH,
            mapOf(
                "client_id" to session.clientId,
                "steamid" to session.steamId,
                "code" to code.trim(),
                "code_type" to confirmationType.toString()
            )
        ) ?: return LoginResult.Failure("提交 Steam 验证码失败")

        val updatePayload = updateResponse.responseObject()
        val updateSuccess = updateResponse.successBoolean() ?: (updatePayload != null)
        val updateEResult = updateResponse.eResultInt()
        val codeAlreadyAccepted = updateEResult == 29
        if (!codeAlreadyAccepted && (!updateSuccess || (updateEResult != null && updateEResult != 1))) {
            logDiag("submit guard form failed eResult=${updateEResult ?: "unknown"}")
            val message = updatePayload?.messageString()
                ?: updateResponse.messageString()
                ?: mapEresultToMessage(updateEResult)
                ?: "Steam 验证失败"
            return LoginResult.Failure(message)
        }
        logDiag("submit guard form accepted duplicate=$codeAlreadyAccepted")
        return null
    }

    fun clearPendingSession(sessionId: String) {
        if (sessionId.isNotBlank()) {
            pendingSessions.remove(sessionId)
        }
    }

    suspend fun pollPendingSession(pendingSessionId: String): LoginResult = withContext(Dispatchers.IO) {
        val session = pendingSessions[pendingSessionId]
            ?: return@withContext LoginResult.Failure("登录会话已过期，请重新开始", retryable = false)

        if (session.flow != AuthFlow.AUTH_API) {
            return@withContext LoginResult.Failure("当前登录会话需要输入验证码", retryable = false)
        }

        val pendingResult = LoginResult.ChallengeRequired(
            pendingSessionId = pendingSessionId,
            steamId = session.steamId,
            challenges = session.allowedConfirmations,
            message = null
        )
        val result = pollForToken(
            clientId = session.clientId,
            requestId = session.requestId,
            steamId = session.steamId,
            maxAttempts = 1,
            pendingResult = pendingResult,
            purpose = session.purpose
        )
        if (result is LoginResult.ReadyForImport || result is LoginResult.Failure) {
            pendingSessions.remove(pendingSessionId)
        }
        result
    }

    suspend fun pollQrLoginSession(pendingSessionId: String): QrLoginResult = withContext(Dispatchers.IO) {
        val session = pendingSessions[pendingSessionId]
            ?: return@withContext QrLoginResult.Failure("二维码登录会话已过期，请重新开始", retryable = false)

        if (session.flow != AuthFlow.AUTH_API_QR) {
            return@withContext QrLoginResult.Failure("当前登录会话不是二维码登录", retryable = false)
        }

        pollQrForToken(pendingSessionId, session)
    }

    private suspend fun pollForToken(
        clientId: String,
        requestId: String,
        steamId: String,
        maxAttempts: Int = MAX_POLL_ATTEMPTS,
        pendingResult: LoginResult? = null,
        purpose: LoginPurpose = LoginPurpose.IMPORT_AUTHENTICATOR
    ): LoginResult {
        val authIds = buildAuthApiSessionIds(clientId, requestId, steamId)
        if (authIds != null) {
            return pollForTokenWithProtobuf(authIds, steamId, maxAttempts, pendingResult, purpose)
        }
        return pollForTokenWithForm(clientId, requestId, steamId, maxAttempts, pendingResult, purpose)
    }

    private suspend fun pollForAuthorizedDeviceRevocation(
        session: PendingAuthSession,
        tokenToRevoke: Long
    ): TemporaryAuthTokens? {
        val authIds = buildAuthApiSessionIds(
            session.clientId,
            session.requestId,
            session.steamId
        ) ?: return null
        var clientId = authIds.clientId
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            val request = SteamProtoWriter().apply {
                writeUint64(1, clientId)
                writeBytes(2, authIds.requestId)
                writeFixed64(3, tokenToRevoke)
            }
            val fields = SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "IAuthenticationService",
                    method = "PollAuthSessionStatus",
                    request = request
                )
            ).parse()
            fields[1]?.asLong?.takeIf { it != 0L }?.let { clientId = it }
            val refreshToken = fields[3]?.asString
            val accessToken = fields[4]?.asString
            if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                return TemporaryAuthTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken
                )
            }
            if (attempt < MAX_POLL_ATTEMPTS - 1) delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private suspend fun pollForTokenWithProtobuf(
        authIds: AuthApiSessionIds,
        steamId: String,
        maxAttempts: Int,
        pendingResult: LoginResult?,
        purpose: LoginPurpose
    ): LoginResult {
        var clientId = authIds.clientId
        repeat(maxAttempts) { attempt ->
            val request = SteamProtoWriter().apply {
                writeUint64(1, clientId)
                writeBytes(2, authIds.requestId)
            }
            val fields = try {
                SteamProtoReader(
                    steamApi.callProtobuf(
                        iface = "IAuthenticationService",
                        method = "PollAuthSessionStatus",
                        request = request
                    )
                ).parse()
            } catch (error: SteamApiException) {
                return LoginResult.Failure(
                    mapEresultToMessage(error.eResult)
                        ?: error.message
                        ?: "Steam 登录轮询失败"
                )
            } catch (error: Exception) {
                android.util.Log.e(TAG, "pollForTokenWithProtobuf failed: ${error.message}", error)
                return LoginResult.Failure(error.message ?: "Steam 登录轮询失败")
            }

            fields[1]?.asLong?.takeIf { it != 0L }?.let { clientId = it }
            val accessToken = fields[4]?.asString
            val refreshToken = fields[3]?.asString
            val resolvedSteamId = resolveSteamIdFromLoginTokens(steamId, accessToken, refreshToken)
            if (!accessToken.isNullOrBlank()) {
                logDiag("poll protobuf tokens access=true refresh=${!refreshToken.isNullOrBlank()}")
                if (resolvedSteamId.isNullOrBlank()) {
                    return LoginResult.Failure("Steam 登录成功但无法识别 SteamID，无法继续导入")
                }
                val accountName = fields[6]?.asString?.takeIf { it.isNotBlank() } ?: resolvedSteamId
                return resolveLoginPayloadAfterToken(
                    steamId = resolvedSteamId,
                    userName = accountName,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    purpose = purpose
                )
            }
            if (!refreshToken.isNullOrBlank()) {
                logDiag("poll protobuf tokens access=false refresh=true; refreshing access token")
                if (resolvedSteamId.isNullOrBlank()) {
                    return LoginResult.Failure("Steam 登录成功但无法识别 SteamID，无法继续导入")
                }
                val refreshedTokens = generateAccessTokenForApp(
                    steamId = resolvedSteamId,
                    refreshToken = refreshToken
                ) ?: return LoginResult.Failure("Steam 登录成功但无法换取 access token，无法继续导入")
                val accountName = fields[6]?.asString?.takeIf { it.isNotBlank() } ?: resolvedSteamId
                return resolveLoginPayloadAfterToken(
                    steamId = resolvedSteamId,
                    userName = accountName,
                    accessToken = refreshedTokens.accessToken,
                    refreshToken = refreshedTokens.refreshToken ?: refreshToken,
                    purpose = purpose
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(POLL_INTERVAL_MS)
            }
        }

        return pendingResult ?: LoginResult.Failure("Steam 登录等待超时，请稍后重试")
    }

    private fun pollQrForToken(
        pendingSessionId: String,
        session: PendingAuthSession
    ): QrLoginResult {
        val authIds = buildAuthApiSessionIds(session.clientId, session.requestId, session.steamId)
            ?: return QrLoginResult.Failure("二维码登录会话参数无效，请重新开始")
        val request = SteamProtoWriter().apply {
            writeUint64(1, authIds.clientId)
            writeBytes(2, authIds.requestId)
        }
        val fields = try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "IAuthenticationService",
                    method = "PollAuthSessionStatus",
                    request = request
                )
            ).parse()
        } catch (error: SteamApiException) {
            return QrLoginResult.Failure(
                mapEresultToMessage(error.eResult)
                    ?: error.message
                    ?: "Steam 二维码登录轮询失败"
            )
        } catch (error: Exception) {
            android.util.Log.e(TAG, "pollQrForToken failed: ${error.message}", error)
            return QrLoginResult.Failure(error.message ?: "Steam 二维码登录轮询失败")
        }

        val nextClientId = fields[1]?.asLong?.takeIf { it != 0L }
        val nextChallengeUrl = fields[2]?.asString?.takeIf { it.isNotBlank() }
        val accessToken = fields[4]?.asString
        val refreshToken = fields[3]?.asString
        val currentSession = session.copy(
            clientId = nextClientId?.let(::unsignedLongToString) ?: session.clientId,
            qrChallengeUrl = nextChallengeUrl ?: session.qrChallengeUrl
        )

        if (!accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank()) {
            val resolvedSteamId = resolveSteamIdFromLoginTokens(
                currentSession.steamId,
                accessToken,
                refreshToken
            ) ?: return QrLoginResult.Failure("Steam 登录成功但无法识别 SteamID，无法继续导入")
            val refreshedTokens = if (accessToken.isNullOrBlank()) {
                generateAccessTokenForApp(
                    steamId = resolvedSteamId,
                    refreshToken = requireNotNull(refreshToken)
                ) ?: return QrLoginResult.Failure("Steam 登录成功但无法换取 access token，无法继续导入")
            } else {
                null
            }
            val finalAccessToken = accessToken ?: requireNotNull(refreshedTokens).accessToken
            val finalRefreshToken = if (!accessToken.isNullOrBlank()) {
                refreshToken
            } else {
                requireNotNull(refreshedTokens).refreshToken ?: refreshToken
            }
            val accountName = fields[6]?.asString?.takeIf { it.isNotBlank() } ?: resolvedSteamId
            pendingSessions.remove(pendingSessionId)
            return when (
                val loginResult = resolveGuardPayloadAfterLogin(
                    steamId = resolvedSteamId,
                    userName = accountName,
                    accessToken = finalAccessToken,
                    refreshToken = finalRefreshToken
                )
            ) {
                is LoginResult.ReadyForImport -> QrLoginResult.ReadyForImport(loginResult)
                is LoginResult.ChallengeRequired -> QrLoginResult.LoginChallengeRequired(loginResult)
                is LoginResult.Failure -> QrLoginResult.Failure(loginResult.message, loginResult.retryable)
            }
        }

        pendingSessions[pendingSessionId] = currentSession
        return QrLoginResult.ChallengeRequired(
            pendingSessionId = pendingSessionId,
            challengeUrl = currentSession.qrChallengeUrl.orEmpty()
        )
    }

    private suspend fun pollForTokenWithForm(
        clientId: String,
        requestId: String,
        steamId: String,
        maxAttempts: Int,
        pendingResult: LoginResult?,
        purpose: LoginPurpose
    ): LoginResult {
        repeat(maxAttempts) { attempt ->
            val pollResponse = postForm(
                URL_POLL_AUTH,
                mapOf(
                    "client_id" to clientId,
                    "request_id" to requestId
                )
            ) ?: return LoginResult.Failure("Steam 轮询失败")

            val payload = pollResponse.responseObject()
            val success = pollResponse.successBoolean() ?: (payload != null)
            if (!success) {
                val message = payload?.messageString()
                    ?: pollResponse.messageString()
                    ?: "Steam 登录轮询失败"
                return LoginResult.Failure(message)
            }

            val accessToken = payload?.stringAny("access_token", "accessToken")
            val refreshToken = payload?.stringAny("refresh_token", "refreshToken")
            if (!accessToken.isNullOrBlank()) {
                logDiag("poll form tokens access=true refresh=${!refreshToken.isNullOrBlank()}")
                val accountName = payload.stringAny("account_name", "accountName") ?: steamId
                return resolveLoginPayloadAfterToken(
                    steamId = steamId,
                    userName = accountName,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    purpose = purpose
                )
            }
            if (!refreshToken.isNullOrBlank()) {
                logDiag("poll form tokens access=false refresh=true; refreshing access token")
                val refreshedTokens = generateAccessTokenForApp(
                    steamId = steamId,
                    refreshToken = refreshToken
                ) ?: return LoginResult.Failure("Steam 登录成功但无法换取 access token，无法继续导入")
                val accountName = payload?.stringAny("account_name", "accountName") ?: steamId
                return resolveLoginPayloadAfterToken(
                    steamId = steamId,
                    userName = accountName,
                    accessToken = refreshedTokens.accessToken,
                    refreshToken = refreshedTokens.refreshToken ?: refreshToken,
                    purpose = purpose
                )
            }

            if (attempt < maxAttempts - 1) {
                delay(POLL_INTERVAL_MS)
            }
        }

        return pendingResult ?: LoginResult.Failure("Steam 登录等待超时，请稍后重试")
    }

    private fun generateAccessTokenForApp(
        steamId: String,
        refreshToken: String
    ): AccessTokenRefreshResult? {
        val steamIdLong = steamId.toLongOrNull()
        if (steamIdLong == null) {
            logDiag("generate access token skipped steamid_invalid")
            return null
        }
        val request = SteamProtoWriter().apply {
            writeString(1, refreshToken)
            writeFixed64(2, steamIdLong)
        }

        return try {
            val fields = SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "IAuthenticationService",
                    method = "GenerateAccessTokenForApp",
                    request = request
                )
            ).parse()
            val accessToken = fields[1]?.asString
            val newRefreshToken = fields[2]?.asString
            val success = !accessToken.isNullOrBlank()
            logDiag("generate access token result success=$success refresh_rotated=${!newRefreshToken.isNullOrBlank()}")
            if (success) {
                AccessTokenRefreshResult(
                    accessToken = accessToken,
                    refreshToken = newRefreshToken
                )
            } else {
                null
            }
        } catch (error: SteamApiException) {
            logDiag("generate access token failed eResult=${error.eResult ?: "unknown"}")
            android.util.Log.w(TAG, "GenerateAccessTokenForApp failed: eResult=${error.eResult}", error)
            null
        } catch (error: Exception) {
            logDiag("generate access token exception type=${error.javaClass.simpleName}")
            android.util.Log.w(TAG, "GenerateAccessTokenForApp exception: ${error.message}", error)
            null
        }
    }

    private fun resolveSteamIdFromLoginTokens(
        steamId: String,
        accessToken: String?,
        refreshToken: String?
    ): String? {
        return steamId.takeIf { it.isNotBlank() && it.toLongOrNull() != null }
            ?: steamIdFromJwt(refreshToken)
            ?: steamIdFromJwt(accessToken)
    }

    private fun resolveLoginPayloadAfterToken(
        steamId: String,
        userName: String,
        accessToken: String,
        refreshToken: String?,
        purpose: LoginPurpose
    ): LoginResult {
        if (purpose == LoginPurpose.SESSION_ONLY) {
            val resolvedSteamId = steamId.takeIf { it.isNotBlank() && it.toLongOrNull() != null }
                ?: return LoginResult.Failure("Steam 登录成功但无法识别 SteamID，无法继续补全")
            logDiag("session only login ready")
            return buildSessionOnlyLoginResult(
                steamId = resolvedSteamId,
                userName = userName,
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        }
        return resolveGuardPayloadAfterLogin(
            steamId = steamId,
            userName = userName,
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    private fun buildSessionOnlyLoginResult(
        steamId: String,
        userName: String,
        accessToken: String,
        refreshToken: String?
    ): LoginResult.ReadyForImport {
        val accountName = userName.trim().takeIf { it.isNotBlank() } ?: steamId
        val payload = buildJsonObject {
            put("steamid", steamId)
            put("account_name", accountName)
            put("access_token", accessToken)
            refreshToken?.takeIf { it.isNotBlank() }?.let { put("refresh_token", it) }
            put("steamLoginSecure", "$steamId||$accessToken")
            put("monica_session_only_login", true)
        }
        return LoginResult.ReadyForImport(
            steamId = steamId,
            payload = SteamGuardPayload(
                deviceId = "",
                steamGuardJson = payload.toString(),
                sessionOnly = true,
                accountName = accountName
            ),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    private fun buildAuthApiSessionIds(
        clientId: String,
        requestId: String,
        steamId: String
    ): AuthApiSessionIds? {
        return AuthApiSessionIds(
            clientId = parseUnsigned64AsSignedLong(clientId) ?: return null,
            requestId = decodeAuthApiRequestIdBytes(requestId) ?: return null,
            steamId = steamId.toLongOrNull()
        )
    }

    private fun parseUnsigned64AsSignedLong(value: String): Long? {
        val big = runCatching { BigInteger(value.trim()) }.getOrNull() ?: return null
        if (big < BigInteger.ZERO || big > UNSIGNED_LONG_MAX) return null
        return if (big > SIGNED_LONG_MAX) {
            big.subtract(UNSIGNED_LONG_BASE).longValueExact()
        } else {
            big.longValueExact()
        }
    }

    private fun unsignedLongToString(value: Long): String {
        return if (value >= 0) {
            value.toString()
        } else {
            BigInteger.valueOf(value).add(UNSIGNED_LONG_BASE).toString()
        }
    }

    private fun decodeAuthApiRequestIdBytes(value: String): ByteArray? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val decoded = listOf(
            Base64.NO_WRAP,
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            Base64.URL_SAFE or Base64.DEFAULT
        ).firstNotNullOfOrNull { flags ->
            runCatching { Base64.decode(trimmed, flags) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        if (decoded != null) return decoded
        return decodeHexBytes(trimmed) ?: trimmed.toByteArray(Charsets.UTF_8)
    }

    private fun decodeHexBytes(value: String): ByteArray? {
        if (value.length % 2 != 0 || value.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        return value.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .takeIf { it.isNotEmpty() }
    }

    private sealed class AddAuthenticatorStartResult {
        data class AwaitingFinalization(
            val payload: SteamGuardPayload,
            val confirmType: Int,
            val phoneHint: String
        ) : AddAuthenticatorStartResult()

        object AuthenticatorPresent : AddAuthenticatorStartResult()

        data class Failure(
            val message: String
        ) : AddAuthenticatorStartResult()
    }

    private sealed class ReplaceAuthenticatorStartResult {
        data class Success(
            val challengeType: Int,
            val challengeHint: String,
            val message: String?
        ) : ReplaceAuthenticatorStartResult()

        data class Failure(
            val message: String
        ) : ReplaceAuthenticatorStartResult()
    }

    private fun resolveGuardPayloadAfterLogin(
        steamId: String,
        userName: String,
        accessToken: String,
        refreshToken: String?
    ): LoginResult {
        data class AddAttempt(
            val token: String,
            val result: AddAuthenticatorStartResult
        )

        val primaryAttempt = AddAttempt(
            token = accessToken,
            result = beginAddAuthenticator(
                steamId = steamId,
                accountName = userName,
                accessToken = accessToken
            )
        )
        val addAttempt = if (
            primaryAttempt.result is AddAuthenticatorStartResult.Failure &&
            !refreshToken.isNullOrBlank()
        ) {
            val refreshedAccess = generateAccessTokenForApp(
                steamId = steamId,
                refreshToken = refreshToken
            )?.accessToken
            if (!refreshedAccess.isNullOrBlank() && refreshedAccess != accessToken) {
                logDiag("add authenticator retry with refreshed access token")
                AddAttempt(
                    token = refreshedAccess,
                    result = beginAddAuthenticator(
                        steamId = steamId,
                        accountName = userName,
                        accessToken = refreshedAccess
                    )
                )
            } else {
                primaryAttempt
            }
        } else {
            primaryAttempt
        }

        return when (val addResult = addAttempt.result) {
            is AddAuthenticatorStartResult.AwaitingFinalization -> {
                logDiag("resolve guard payload route=add_finalize")
                createAddAuthenticatorFinalizeChallenge(
                    steamId = steamId,
                    userName = userName,
                    payload = addResult.payload,
                    finalizeAccessToken = addAttempt.token,
                    importAccessToken = addAttempt.token,
                    refreshToken = refreshToken,
                    confirmType = addResult.confirmType
                )
            }

            AddAuthenticatorStartResult.AuthenticatorPresent -> {
                logDiag("resolve guard payload route=replace_existing")
                when (
                    val startResult = startReplaceAuthenticatorChallenge(
                        steamId = steamId,
                        userName = userName,
                        accessToken = addAttempt.token,
                        refreshToken = refreshToken
                    )
                ) {
                    is ReplaceAuthenticatorStartResult.Success -> {
                        val pendingSessionId = UUID.randomUUID().toString()
                        pendingSessions[pendingSessionId] = PendingAuthSession(
                            flow = AuthFlow.REPLACE_EXISTING_AUTHENTICATOR,
                            userName = userName,
                            clientId = "",
                            requestId = "",
                            steamId = steamId,
                            allowedConfirmations = listOf(
                                SteamGuardChallenge(
                                    confirmationType = startResult.challengeType,
                                    associatedMessage = startResult.challengeHint
                                )
                            ),
                            replaceAccessToken = addAttempt.token,
                            replaceRefreshToken = refreshToken
                        )
                        LoginResult.ChallengeRequired(
                            pendingSessionId = pendingSessionId,
                            steamId = steamId,
                            challenges = pendingSessions[pendingSessionId]?.allowedConfirmations.orEmpty(),
                            message = startResult.message ?: "账号已绑定令牌，请输入验证码完成替换"
                        )
                    }

                    is ReplaceAuthenticatorStartResult.Failure -> {
                        LoginResult.Failure(startResult.message)
                    }
                }
            }

            is AddAuthenticatorStartResult.Failure -> {
                logDiag("resolve guard payload route=failed")
                LoginResult.Failure(addResult.message)
            }
        }
    }

    private fun createAddAuthenticatorFinalizeChallenge(
        steamId: String,
        userName: String,
        payload: SteamGuardPayload,
        finalizeAccessToken: String,
        importAccessToken: String,
        refreshToken: String?,
        confirmType: Int
    ): LoginResult.ChallengeRequired {
        val pendingSessionId = UUID.randomUUID().toString()
        val challengeType = if (confirmType == 3) {
            ADD_AUTHENTICATOR_EMAIL_ACTIVATION_CODE
        } else {
            ADD_AUTHENTICATOR_SMS_ACTIVATION_CODE
        }
        pendingSessions[pendingSessionId] = PendingAuthSession(
            flow = AuthFlow.ADD_AUTHENTICATOR_FINALIZE,
            userName = userName,
            clientId = "",
            requestId = "",
            steamId = steamId,
            allowedConfirmations = listOf(
                SteamGuardChallenge(
                    confirmationType = challengeType
                )
            ),
            addAccessToken = finalizeAccessToken,
            addImportAccessToken = importAccessToken,
            addRefreshToken = refreshToken,
            addPayload = payload,
            addValidateSmsCode = confirmType != 3
        )
        return LoginResult.ChallengeRequired(
            pendingSessionId = pendingSessionId,
            steamId = steamId,
            challenges = pendingSessions[pendingSessionId]?.allowedConfirmations.orEmpty(),
            message = null
        )
    }

    private fun beginAddAuthenticator(
        steamId: String,
        accountName: String,
        accessToken: String
    ): AddAuthenticatorStartResult {
        val steamIdLong = steamId.toLongOrNull()
            ?: return AddAuthenticatorStartResult.Failure("SteamID 无效，无法添加 Steam Guard")
        val deviceId = generateSteamDeviceId()
        val authTime = System.currentTimeMillis() / 1000L
        val request = SteamProtoWriter().apply {
            writeFixed64(1, steamIdLong)
            writeUint64(2, authTime)
            writeVarint(4, 1L)
            writeString(5, deviceId)
            writeString(6, "1")
            writeVarint(8, 2L)
        }

        val fields = try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "ITwoFactorService",
                    method = "AddAuthenticator",
                    request = request,
                    accessToken = accessToken
                )
            ).parse()
        } catch (error: SteamApiException) {
            logDiag("add authenticator failed eResult=${error.eResult ?: "unknown"}")
            return when (error.eResult) {
                29 -> AddAuthenticatorStartResult.AuthenticatorPresent
                73 -> AddAuthenticatorStartResult.Failure("Steam 账号当前受限，无法添加 Steam Guard")
                84 -> AddAuthenticatorStartResult.Failure("Steam 请求过于频繁，请稍后再试")
                else -> AddAuthenticatorStartResult.Failure(
                    error.message ?: "添加 Steam Guard 失败"
                )
            }
        } catch (error: Exception) {
            logDiag("add authenticator exception type=${error.javaClass.simpleName}")
            android.util.Log.e(TAG, "beginAddAuthenticator failed: ${error.message}", error)
            return AddAuthenticatorStartResult.Failure(error.message ?: "添加 Steam Guard 失败")
        }

        val status = fields[10]?.asInt ?: 0
        logDiag("add authenticator response status=$status fields=${fields.keys.sorted().joinToString(",")}")
        if (status == 29) {
            return AddAuthenticatorStartResult.AuthenticatorPresent
        }

        val sharedSecretBytes = fields[1]?.bytes
        if (status == 2 || sharedSecretBytes == null || sharedSecretBytes.isEmpty()) {
            val message = if (status == 2) {
                "该 Steam 账号需要先绑定手机号，才能添加 Steam Guard"
            } else {
                mapTwoFactorStatusToMessage(status)
                    ?: "Steam 未返回完整令牌数据（缺少 shared_secret）"
            }
            return AddAuthenticatorStartResult.Failure(message)
        }

        val sharedSecret = Base64.encodeToString(sharedSecretBytes, Base64.NO_WRAP)
        val serialNumber = fields[2]?.asFixed64UnsignedString?.takeIf { it != "0" }
        if (serialNumber.isNullOrBlank()) {
            android.util.Log.w(TAG, "AddAuthenticator missing serial_number, fields=${fields.keys}")
            logDiag("add authenticator missing serial_number fields=${fields.keys.sorted().joinToString(",")}")
            return AddAuthenticatorStartResult.Failure("Steam 未返回完整令牌数据（缺少 serial_number）")
        }

        val confirmType = fields[12]?.asInt ?: 0
        val phoneHint = fields[11]?.asString.orEmpty()
        val resolvedAccountName = fields[6]?.asString
            ?.takeIf { it.isNotBlank() }
            ?: accountName.takeIf { it.isNotBlank() && it != steamId }
        val canonicalPayload = buildJsonObject {
            put("steamid", JsonPrimitive(steamId))
            put("shared_secret", JsonPrimitive(sharedSecret))
            put("serial_number", JsonPrimitive(serialNumber))
            fields[3]?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("revocation_code", JsonPrimitive(it)) }
            fields[4]?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("uri", JsonPrimitive(it)) }
            put("server_time", JsonPrimitive((fields[5]?.asLong ?: authTime).toString()))
            resolvedAccountName?.let { put("account_name", JsonPrimitive(it)) }
            fields[7]?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("token_gid", JsonPrimitive(it)) }
            fields[8]?.bytes?.takeIf { it.isNotEmpty() }
                ?.let { put("identity_secret", JsonPrimitive(Base64.encodeToString(it, Base64.NO_WRAP))) }
            fields[9]?.bytes?.takeIf { it.isNotEmpty() }
                ?.let { put("secret_1", JsonPrimitive(Base64.encodeToString(it, Base64.NO_WRAP))) }
            put("status", JsonPrimitive(status.toString()))
            put("device_id", JsonPrimitive(deviceId))
            put("fully_enrolled", JsonPrimitive(false))
        }

        android.util.Log.i(
            TAG,
            "AddAuthenticator awaiting finalization: status=$status, confirmType=$confirmType"
        )
        return AddAuthenticatorStartResult.AwaitingFinalization(
            payload = SteamGuardPayload(
                deviceId = deviceId,
                steamGuardJson = canonicalPayload.toString()
            ),
            confirmType = confirmType,
            phoneHint = phoneHint
        )
    }

    private fun finalizeAddAuthenticator(
        session: PendingAuthSession,
        activationCode: String
    ): LoginResult {
        if (activationCode.isBlank()) {
            return LoginResult.Failure("验证码不能为空", retryable = false)
        }
        val accessToken = session.addAccessToken
            ?: return LoginResult.Failure("Steam Guard 激活会话已过期，请重新登录", retryable = false)
        val payload = session.addPayload
            ?: return LoginResult.Failure("Steam Guard 激活数据已过期，请重新登录", retryable = false)
        val steamIdLong = session.steamId.toLongOrNull()
            ?: return LoginResult.Failure("SteamID 无效，无法完成 Steam Guard 绑定", retryable = false)
        val sharedSecret = payload.sharedSecretOrNull()
            ?: return LoginResult.Failure("Steam Guard 数据不完整，无法生成激活验证码", retryable = false)

        logDiag("finalize add authenticator start validateSms=${session.addValidateSmsCode}")
        repeat(31) { attempt ->
            val authTime = System.currentTimeMillis() / 1000L
            val authenticatorCode = SteamTotp.generateAuthCode(sharedSecret, authTime)
            val request = SteamProtoWriter().apply {
                writeFixed64(1, steamIdLong)
                writeString(2, authenticatorCode)
                writeUint64(3, authTime)
                writeString(4, activationCode)
                writeBool(6, session.addValidateSmsCode)
            }

            val fields = try {
                SteamProtoReader(
                    steamApi.callProtobuf(
                        iface = "ITwoFactorService",
                        method = "FinalizeAddAuthenticator",
                        request = request,
                        accessToken = accessToken
                    )
                ).parse()
            } catch (error: SteamApiException) {
                logDiag("finalize add authenticator failed eResult=${error.eResult ?: "unknown"}")
                return LoginResult.Failure(
                    mapEresultToMessage(error.eResult)
                        ?: error.message
                        ?: "Steam Guard 激活失败"
                )
            } catch (error: Exception) {
                logDiag("finalize add authenticator exception type=${error.javaClass.simpleName}")
                android.util.Log.e(TAG, "finalizeAddAuthenticator failed: ${error.message}", error)
                return LoginResult.Failure(error.message ?: "Steam Guard 激活失败")
            }

            val success = fields[1]?.asBool ?: false
            val wantMore = fields[2]?.asBool ?: false
            val status = fields[4]?.asInt ?: 0
            logDiag("finalize add authenticator response success=$success wantMore=$wantMore status=$status attempt=$attempt")
            if (status == 89) {
                return LoginResult.Failure("Steam 激活码无效或已过期")
            }
            if (success) {
                return LoginResult.ReadyForImport(
                    steamId = session.steamId,
                    payload = payload.markFullyEnrolled(),
                    accessToken = session.addImportAccessToken ?: accessToken,
                    refreshToken = session.addRefreshToken
                )
            }
            if (!wantMore && status != 88) {
                return LoginResult.Failure(
                    mapTwoFactorStatusToMessage(status) ?: "Steam Guard 激活失败（status=$status）"
                )
            }
        }

        return LoginResult.Failure("Steam Guard 激活失败：无法生成 Steam 要求的连续验证码")
    }

    private fun SteamGuardPayload.sharedSecretOrNull(): String? {
        return runCatching {
            json.parseToJsonElement(steamGuardJson)
                .jsonObject
                .stringAny("shared_secret", "sharedSecret")
        }.getOrNull()
    }

    private fun SteamGuardPayload.markFullyEnrolled(): SteamGuardPayload {
        val root = runCatching {
            json.parseToJsonElement(steamGuardJson).jsonObject
        }.getOrNull() ?: return this
        val updatedPayload = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("fully_enrolled", JsonPrimitive(true))
        }
        return copy(steamGuardJson = updatedPayload.toString())
    }

    private fun generateSteamDeviceId(): String {
        return "android:${UUID.randomUUID()}"
    }

    private fun startReplaceAuthenticatorChallenge(
        steamId: String,
        userName: String,
        accessToken: String,
        refreshToken: String?
    ): ReplaceAuthenticatorStartResult {
        try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "ITwoFactorService",
                    method = "RemoveAuthenticatorViaChallengeStart",
                    request = SteamProtoWriter(),
                    accessToken = accessToken
                )
            ).parse()
        } catch (error: SteamApiException) {
            logDiag("replace start failed eResult=${error.eResult ?: "unknown"}")
            return ReplaceAuthenticatorStartResult.Failure(
                mapReplaceStartEresultToMessage(error.eResult)
                    ?: error.message
                    ?: "发起替换令牌失败"
            )
        } catch (error: Exception) {
            logDiag("replace start exception type=${error.javaClass.simpleName}")
            android.util.Log.e(TAG, "startReplaceAuthenticatorChallenge failed: ${error.message}", error)
            return ReplaceAuthenticatorStartResult.Failure(error.message ?: "发起替换令牌失败")
        }

        logDiag("replace start success transport=protobuf")
        return ReplaceAuthenticatorStartResult.Success(
            challengeType = REPLACE_CODE_TYPE_GENERIC,
            challengeHint = "请输入短信验证码以替换现有令牌",
            message = null
        )
    }

    private fun mapReplaceStartEresultToMessage(eResult: Int?): String? {
        return when (eResult) {
            1 -> null
            2 -> "Steam 登录已成功，但该账号已经绑定 Steam 验证器，Steam 拒绝转移验证器（EResult=2）。这通常表示账号没有可用手机号或当前不允许转移；请使用 maFile 导入，或在 Steam 账号绑定手机号后再试。"
            15 -> "Steam 登录已成功，但 Steam 拒绝访问验证器转移接口（EResult=15）。请使用 maFile 导入，或稍后重新登录后再试。"
            84 -> "Steam 登录已成功，但 Steam 暂时限制验证器转移（EResult=84），请稍后再试。"
            else -> eResult?.let { "Steam 登录已成功，但发起验证器转移失败（EResult=$it）" }
        }
    }

    private fun continueReplaceAuthenticatorFlow(
        session: PendingAuthSession,
        code: String
    ): LoginResult {
        val accessToken = session.replaceAccessToken
            ?: return LoginResult.Failure("替换会话无效，请重新登录导入", retryable = false)

        val primaryResult = continueReplaceAuthenticatorChallenge(
            steamId = session.steamId,
            accessToken = accessToken,
            code = code
        )
        var finalAccessToken = accessToken
        val finalResult = if (
            primaryResult.isFailure &&
            !session.replaceRefreshToken.isNullOrBlank() &&
            !isInvalidCodeError(primaryResult.exceptionOrNull()?.message.orEmpty())
        ) {
            val refreshedAccess = generateAccessTokenForApp(
                steamId = session.steamId,
                refreshToken = session.replaceRefreshToken
            )?.accessToken
            if (!refreshedAccess.isNullOrBlank() && refreshedAccess != accessToken) {
                logDiag("replace continue retry with refreshed access token")
                finalAccessToken = refreshedAccess
                continueReplaceAuthenticatorChallenge(
                    steamId = session.steamId,
                    accessToken = refreshedAccess,
                    code = code
                )
            } else {
                primaryResult
            }
        } else {
            primaryResult
        }

        val payload = finalResult.getOrElse { error ->
            return LoginResult.Failure(error.message ?: "替换令牌失败")
        }
        return LoginResult.ReadyForImport(
            steamId = session.steamId,
            payload = payload,
            accessToken = finalAccessToken,
            refreshToken = session.replaceRefreshToken
        )
    }

    private fun continueReplaceAuthenticatorChallenge(
        steamId: String,
        accessToken: String,
        code: String
    ): Result<SteamGuardPayload> {
        val request = SteamProtoWriter().apply {
            writeString(1, code.trim())
            writeBool(2, true)
            writeVarint(3, 2L)
        }
        val fields = try {
            SteamProtoReader(
                steamApi.callProtobuf(
                    iface = "ITwoFactorService",
                    method = "RemoveAuthenticatorViaChallengeContinue",
                    request = request,
                    accessToken = accessToken
                )
            ).parse()
        } catch (error: SteamApiException) {
            logDiag("replace continue failed eResult=${error.eResult ?: "unknown"}")
            return Result.failure(
                Exception(
                    mapEresultToMessage(error.eResult)
                        ?: error.message
                        ?: "替换令牌失败"
                )
            )
        } catch (error: Exception) {
            logDiag("replace continue exception type=${error.javaClass.simpleName}")
            android.util.Log.e(TAG, "continueReplaceAuthenticatorChallenge failed: ${error.message}", error)
            return Result.failure(Exception(error.message ?: "替换令牌失败"))
        }

        val replacementFields = fields[2]?.bytes?.let { bytes ->
            runCatching { SteamProtoReader(bytes).parse() }.getOrNull()
        }
        val sharedSecretBytes = replacementFields?.get(1)?.bytes
        val sharedSecret = sharedSecretBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val serialNumber = replacementFields?.get(2)?.asFixed64UnsignedString?.takeIf { it != "0" }
        if (sharedSecret.isNullOrBlank() || serialNumber.isNullOrBlank()) {
            val replacementKeys = replacementFields?.keys?.joinToString(",").orEmpty()
            android.util.Log.w(
                TAG,
                "continueReplaceAuthenticatorChallenge missing token fields, replacementKeys=[$replacementKeys]"
            )
            logDiag("replace continue missing token fields replacementKeys=[$replacementKeys]")
            return Result.failure(
                Exception("替换成功但未返回完整令牌数据（缺少 shared_secret/serial_number）")
            )
        }

        val resolvedDeviceId = generateSteamDeviceId()
        val canonicalPayload = buildJsonObject {
            put("shared_secret", JsonPrimitive(sharedSecret))
            put("serial_number", JsonPrimitive(serialNumber))
            replacementFields?.get(3)?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("revocation_code", JsonPrimitive(it)) }
            replacementFields?.get(4)?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("uri", JsonPrimitive(it)) }
            replacementFields?.get(5)?.asLong?.takeIf { it != 0L }
                ?.let { put("server_time", JsonPrimitive(it.toString())) }
            replacementFields?.get(6)?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("account_name", JsonPrimitive(it)) }
            replacementFields?.get(7)?.asString?.takeIf { it.isNotBlank() }
                ?.let { put("token_gid", JsonPrimitive(it)) }
            replacementFields?.get(8)?.bytes?.takeIf { it.isNotEmpty() }
                ?.let { put("identity_secret", JsonPrimitive(Base64.encodeToString(it, Base64.NO_WRAP))) }
            replacementFields?.get(9)?.bytes?.takeIf { it.isNotEmpty() }
                ?.let { put("secret_1", JsonPrimitive(Base64.encodeToString(it, Base64.NO_WRAP))) }
            replacementFields?.get(10)?.asInt?.takeIf { it != 0 }
                ?.let { put("status", JsonPrimitive(it.toString())) }
            replacementFields?.get(11)?.asInt?.takeIf { it != 0 }
                ?.let { put("steamguard_scheme", JsonPrimitive(it.toString())) }
            replacementFields?.get(12)?.asFixed64UnsignedString?.takeIf { it != "0" }
                ?.let { put("steamid", JsonPrimitive(it)) }
            put("device_id", JsonPrimitive(resolvedDeviceId))
            put("fully_enrolled", JsonPrimitive(true))
        }
        logDiag("replace continue success transport=protobuf fields=${replacementFields?.keys?.sorted()?.joinToString(",").orEmpty()}")
        return Result.success(
            SteamGuardPayload(
                deviceId = resolvedDeviceId,
                steamGuardJson = canonicalPayload.toString()
            )
        )
    }

    private fun buildSteamTokenParams(token: String): Map<String, String> {
        return mapOf(
            "access_token" to token,
            "oauth_token" to token,
            "key" to token
        )
    }

    private fun isInvalidCodeError(message: String): Boolean {
        return message.contains("验证码无效") ||
            message.contains("已过期") ||
            message.contains("status=89") ||
            message.contains("status=65") ||
            message.contains("eResult=65")
    }

    private data class LegacyRsaKey(
        val modulusHex: String,
        val exponentHex: String,
        val timestamp: String
    )

    private fun beginLegacyLogin(
        userName: String,
        password: String,
        purpose: LoginPurpose
    ): LoginResult {
        val rsaKey = getLegacyRsaKey(userName)
            ?: return LoginResult.Failure("Steam 登录失败：无法获取旧版 RSA 密钥")

        val encryptedPassword = encryptPasswordWithRsa(
            password = password,
            modulusHex = rsaKey.modulusHex,
            exponentHex = rsaKey.exponentHex
        ) ?: return LoginResult.Failure("Steam 登录失败：旧版密码加密失败")

        return executeLegacyLogin(
            userName = userName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaKey.timestamp,
            code = null,
            challengeType = null,
            emailSteamId = null,
            purpose = purpose
        )
    }

    private fun continueLegacyLogin(
        session: PendingAuthSession,
        code: String
    ): LoginResult {
        val encryptedPassword = session.legacyEncryptedPassword
            ?: return LoginResult.Failure("登录会话已过期，请重新开始", retryable = false)
        val rsaTimestamp = session.legacyRsaTimestamp
            ?: return LoginResult.Failure("登录会话已过期，请重新开始", retryable = false)
        val challengeType = session.legacyChallengeType
            ?: return LoginResult.Failure("登录会话状态异常，请重新开始", retryable = false)

        return executeLegacyLogin(
            userName = session.userName,
            encryptedPassword = encryptedPassword,
            rsaTimestamp = rsaTimestamp,
            code = code,
            challengeType = challengeType,
            emailSteamId = session.legacyEmailSteamId,
            purpose = session.purpose
        )
    }

    private fun executeLegacyLogin(
        userName: String,
        encryptedPassword: String,
        rsaTimestamp: String,
        code: String?,
        challengeType: Int?,
        emailSteamId: String?,
        purpose: LoginPurpose
    ): LoginResult {
        val doLoginResponse = postForm(
            URL_LEGACY_DO_LOGIN,
            mapOf(
                "username" to userName,
                "password" to encryptedPassword,
                "twofactorcode" to if (challengeType == LEGACY_CODE_TYPE_TWO_FACTOR) code.orEmpty() else "",
                "emailauth" to if (challengeType == LEGACY_CODE_TYPE_EMAIL) code.orEmpty() else "",
                "loginfriendlyname" to DEVICE_FRIENDLY_NAME,
                "captchagid" to "-1",
                "captcha_text" to "",
                "emailsteamid" to emailSteamId.orEmpty(),
                "rsatimestamp" to rsaTimestamp,
                "remember_login" to "true",
                "donotcache" to System.currentTimeMillis().toString(),
                "oauth_client_id" to LEGACY_OAUTH_CLIENT_ID,
                "oauth_scope" to LEGACY_OAUTH_SCOPE
            )
        ) ?: return LoginResult.Failure("Steam 登录失败：旧版登录请求失败")

        val success = doLoginResponse.boolAny("success") == true
        val requiresTwoFactor = doLoginResponse.boolAny("requires_twofactor") == true
        val requiresEmail = doLoginResponse.boolAny("emailauth_needed", "requires_emailauth") == true
        val requiresCaptcha = doLoginResponse.boolAny("captcha_needed") == true
        val responseMessage = doLoginResponse.messageString()

        if (success) {
            val oauth = parseLegacyOauthToken(doLoginResponse)
            val accessToken = oauth.second
                ?: return LoginResult.Failure("Steam 登录成功但未返回 OAuth token，无法继续导入")
            val steamId = oauth.first ?: userName

            return resolveLoginPayloadAfterToken(
                steamId = steamId,
                userName = userName,
                accessToken = accessToken,
                refreshToken = null,
                purpose = purpose
            )
        }

        if (requiresCaptcha) {
            return LoginResult.Failure(
                responseMessage ?: "Steam 需要图形验证码，当前版本暂不支持，请先在 Steam 客户端完成一次登录后重试",
                retryable = false
            )
        }

        if (requiresTwoFactor) {
            val pendingSessionId = UUID.randomUUID().toString()
            val steamId = doLoginResponse.stringAny("steamid", "steam_id") ?: userName
            pendingSessions[pendingSessionId] = PendingAuthSession(
                flow = AuthFlow.LEGACY_WEB,
                purpose = purpose,
                userName = userName,
                clientId = "",
                requestId = "",
                steamId = steamId,
                allowedConfirmations = listOf(
                    SteamGuardChallenge(
                        confirmationType = LEGACY_CODE_TYPE_TWO_FACTOR,
                        associatedMessage = responseMessage?.ifBlank { "请输入 Steam 令牌验证码" }
                            ?: "请输入 Steam 令牌验证码"
                    )
                ),
                legacyEncryptedPassword = encryptedPassword,
                legacyRsaTimestamp = rsaTimestamp,
                legacyEmailSteamId = null,
                legacyChallengeType = LEGACY_CODE_TYPE_TWO_FACTOR
            )
            return LoginResult.ChallengeRequired(
                pendingSessionId = pendingSessionId,
                steamId = steamId,
                challenges = pendingSessions[pendingSessionId]?.allowedConfirmations.orEmpty(),
                message = responseMessage
            )
        }

        if (requiresEmail) {
            val pendingSessionId = UUID.randomUUID().toString()
            val steamId = doLoginResponse.stringAny("steamid", "steam_id") ?: userName
            val emailId = doLoginResponse.stringAny("emailsteamid", "email_steamid")
            pendingSessions[pendingSessionId] = PendingAuthSession(
                flow = AuthFlow.LEGACY_WEB,
                purpose = purpose,
                userName = userName,
                clientId = "",
                requestId = "",
                steamId = steamId,
                allowedConfirmations = listOf(
                    SteamGuardChallenge(
                        confirmationType = LEGACY_CODE_TYPE_EMAIL,
                        associatedMessage = responseMessage?.ifBlank { "请输入邮箱验证码" }
                            ?: "请输入邮箱验证码"
                    )
                ),
                legacyEncryptedPassword = encryptedPassword,
                legacyRsaTimestamp = rsaTimestamp,
                legacyEmailSteamId = emailId,
                legacyChallengeType = LEGACY_CODE_TYPE_EMAIL
            )
            return LoginResult.ChallengeRequired(
                pendingSessionId = pendingSessionId,
                steamId = steamId,
                challenges = pendingSessions[pendingSessionId]?.allowedConfirmations.orEmpty(),
                message = responseMessage
            )
        }

        return LoginResult.Failure(
            responseMessage
                ?: doLoginResponse.stringAny("message", "extended_error_message")
                ?: "Steam 登录失败（旧版流程）"
        )
    }

    private fun parseLegacyOauthToken(payload: JsonObject): Pair<String?, String?> {
        val directToken = payload.stringAny("oauth_token", "access_token")
        val directSteamId = payload.stringAny("steamid", "steam_id")
        if (!directToken.isNullOrBlank()) {
            return directSteamId to directToken
        }

        val oauthObj = payload.oauthObject()
        val oauthToken = oauthObj?.stringAny("oauth_token", "access_token")
        val oauthSteamId = oauthObj?.stringAny("steamid", "steam_id")
        return oauthSteamId to oauthToken
    }

    private fun getLegacyRsaKey(userName: String): LegacyRsaKey? {
        val response = postForm(
            URL_LEGACY_RSA_KEY,
            mapOf(
                "username" to userName,
                "donotcache" to System.currentTimeMillis().toString()
            )
        ) ?: return null

        val modulus = response.stringAny("publickey_mod", "publickey_modulus")
        val exponent = response.stringAny("publickey_exp", "publickey_exponent")
        val timestamp = response.stringAny("timestamp", "rsatimestamp")
        if (modulus.isNullOrBlank() || exponent.isNullOrBlank() || timestamp.isNullOrBlank()) {
            android.util.Log.w(TAG, "Legacy RSA response invalid: keys=[${response.keys.joinToString(",")}]")
            return null
        }

        return LegacyRsaKey(
            modulusHex = modulus,
            exponentHex = exponent,
            timestamp = timestamp
        )
    }

    private fun encryptPasswordWithRsa(
        password: String,
        modulusHex: String,
        exponentHex: String
    ): String? {
        return runCatching {
            val modulus = BigInteger(modulusHex, 16)
            val exponent = BigInteger(exponentHex, 16)
            val spec = RSAPublicKeySpec(modulus, exponent)
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.onFailure { error ->
            android.util.Log.e(TAG, "encryptPasswordWithRsa failed: ${error.message}", error)
        }.getOrNull()
    }

    private fun postForm(
        url: String,
        params: Map<String, String>
    ): JsonObject? {
        val bodyBuilder = FormBody.Builder()
        params.forEach { (key, value) ->
            bodyBuilder.add(key, value)
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .header("User-Agent", "Mozilla/5.0 (Monica Android)")
            .header("Accept", "application/json")
            .header("Origin", "https://steamcommunity.com")
            .header("Referer", "https://steamcommunity.com/login/home/")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "postForm failed: $url, code=${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val parsed = json.parseToJsonElement(body).jsonObject
                val eResultHeader = response.header("X-eresult")
                if (eResultHeader.isNullOrBlank()) {
                    parsed
                } else {
                    buildJsonObject {
                        parsed.forEach { (k, v) -> put(k, v) }
                        put("_x_eresult", JsonPrimitive(eResultHeader))
                    }
                }
            }
        }.onFailure { error ->
            android.util.Log.e(TAG, "postForm exception: $url, error=${error.message}", error)
        }.getOrNull()
    }

    private fun getWithQuery(
        url: String,
        query: Map<String, String>
    ): JsonObject? {
        val httpUrlBuilder = url.toHttpUrlOrNull()?.newBuilder()
            ?: return null
        query.forEach { (key, value) ->
            httpUrlBuilder.addQueryParameter(key, value)
        }

        val request = Request.Builder()
            .url(httpUrlBuilder.build())
            .get()
            .header("User-Agent", "Mozilla/5.0 (Monica Android)")
            .header("Accept", "application/json")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "getWithQuery failed: $url, code=${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val parsed = json.parseToJsonElement(body).jsonObject
                val eResultHeader = response.header("X-eresult")
                if (eResultHeader.isNullOrBlank()) {
                    parsed
                } else {
                    buildJsonObject {
                        parsed.forEach { (k, v) -> put(k, v) }
                        put("_x_eresult", JsonPrimitive(eResultHeader))
                    }
                }
            }
        }.onFailure { error ->
            android.util.Log.e(TAG, "getWithQuery exception: $url, error=${error.message}", error)
        }.getOrNull()
    }

    private fun JsonObject.successBoolean(): Boolean? {
        val primitive = this["success"] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: when (primitive.contentOrNull?.trim()) {
            "1", "true", "True" -> true
            "0", "false", "False" -> false
            else -> null
        }
    }

    private fun JsonObject.responseObject(): JsonObject? = (this["response"] as? JsonObject)

    private fun JsonObject.messageString(): String? = stringAny(
        "message",
        "extended_error_message",
        "error_message",
        "detail"
    )

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.stringAny(vararg keys: String): String? {
        keys.forEach { key ->
            val value = string(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.intAny(vararg keys: String): Int? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            val intValue = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
            if (intValue != null) return intValue
        }
        return null
    }

    private fun JsonObject.boolAny(vararg keys: String): Boolean? {
        keys.forEach { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@forEach
            val boolValue = primitive.booleanOrNull
            if (boolValue != null) return boolValue
            when (primitive.contentOrNull?.trim()?.lowercase()) {
                "1", "true", "yes" -> return true
                "0", "false", "no" -> return false
            }
        }
        return null
    }

    private fun JsonObject.oauthObject(): JsonObject? {
        val oauthElement = this["oauth"] ?: return null
        return when (oauthElement) {
            is JsonObject -> oauthElement
            is JsonPrimitive -> {
                val content = oauthElement.contentOrNull ?: return null
                if (!content.trim().startsWith("{")) return null
                runCatching {
                    json.parseToJsonElement(content).jsonObject
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun JsonObject.eResultInt(): Int? = intAny("_x_eresult", "eresult", "result")

    private fun mapTwoFactorStatusToMessage(status: Int?): String? {
        return when (status) {
            null, 1 -> null
            2 -> "Steam 请求失败：参数无效"
            15 -> "Steam 请求失败：访问被拒绝"
            29 -> "该账号已绑定 Steam 令牌，需走替换流程"
            84 -> "Steam 请求失败：当前状态不允许该操作"
            88 -> "Steam 请求失败：需要额外确认"
            89 -> "Steam 请求失败：验证码无效或已过期"
            else -> "Steam 请求失败（status=$status）"
        }
    }

    private fun mapEresultToMessage(eResult: Int?): String? {
        return when (eResult) {
            1 -> null
            5 -> "Steam 登录失败：账号或密码错误"
            29 -> "Steam 返回重复请求（EResult=29），通常表示该账号已绑定令牌"
            20 -> "Steam 登录失败：会话冲突，请稍后重试"
            63 -> "Steam 登录失败：需要额外验证（EResult=63）"
            65 -> "Steam 登录失败：验证码无效或已过期"
            84 -> "Steam 登录失败：登录失败（EResult=84）"
            88 -> "Steam 登录失败：令牌验证码无效或已过期"
            89 -> "Steam 登录失败：短信或邮箱验证码无效或已过期"
            else -> eResult?.let { "Steam 登录失败（EResult=$it）" }
        }
    }

    private fun JsonObject.allowedConfirmations(): List<SteamGuardChallenge> {
        val array = (this["allowed_confirmations"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val type = (obj["confirmation_type"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val message = (obj["associated_message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SteamGuardChallenge(
                confirmationType = type,
                associatedMessage = message
            )
        }
    }
}
