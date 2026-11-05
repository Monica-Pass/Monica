package takagi.ru.monica.bitwarden.service

import android.content.Context
import android.util.Base64
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.bitwarden.api.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.utils.PasswordStrengthAnalyzer

internal data class BitwardenRegistrationTicket(
    val serverUrl: String,
    val email: String,
    val name: String?,
    val tlsConfig: BitwardenTlsConfig?,
    val legacy: Boolean = false,
    val verifiedEmailToken: String? = null,
    val environment: BitwardenEnvironment? = null,
    val appGeneration: Long = takagi.ru.monica.security.SessionManager.lockGeneration.value,
) {
    val readyForPassword: Boolean get() = legacy || !verifiedEmailToken.isNullOrBlank()
    override fun toString() = "BitwardenRegistrationTicket(redacted)"
}

/** Registration never saves a password, token, private key or partially initialized account. */
internal class BitwardenAccountService(
    context: Context,
    apiManager: BitwardenApiManager? = null,
) {
    private val strings = AppLocaleStringResolver(context)
    private val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val apiManager = apiManager ?: BitwardenApiManager(attemptRequestIsCurrent = ::isCurrent)

    private fun isCurrent(environment: BitwardenEnvironment): Boolean =
        takagi.ru.monica.security.SessionManager.isUnlocked.value &&
            generations[environment.revision] == takagi.ru.monica.security.SessionManager.lockGeneration.value

    private fun requireCurrent(environment: BitwardenEnvironment) {
        check(isCurrent(environment) && apiManager.isEnvironmentActive(environment)) {
            strings.get(R.string.bitwarden_account_operation_failed)
        }
    }

    fun retire(environment: BitwardenEnvironment) {
        generations.remove(environment.revision)
        apiManager.retireEnvironment(environment)
    }

    fun retireAll() {
        generations.clear()
        apiManager.clearCache()
    }

    private fun legacyEnvironment(server: String, tls: BitwardenTlsConfig?): BitwardenEnvironment =
        requireNotNull(BitwardenEnvironmentResolver.resolveLegacy(BitwardenLegacyEnvironmentSnapshot(
            server, tls = tls ?: BitwardenTlsConfig()), java.util.UUID.randomUUID().toString()).value) {
            strings.get(R.string.bitwarden_account_operation_failed)
        }

    suspend fun startRegistration(serverUrl: String, email: String, name: String?, tls: BitwardenTlsConfig?): Result<BitwardenRegistrationTicket> =
        accountOperation { startRegistration(legacyEnvironment(serverUrl, tls), email, name).getOrThrow() }

    suspend fun startRegistration(environment: BitwardenEnvironment, email: String, name: String?): Result<BitwardenRegistrationTicket> = accountOperation {
        generations.putIfAbsent(environment.revision, takagi.ru.monica.security.SessionManager.lockGeneration.value)
        requireCurrent(environment)
        val canonicalEmail = validatedEmail(email)
        require((name?.length ?: 0) <= 512) { strings.get(R.string.bitwarden_account_operation_failed) }
        val ticket = BitwardenRegistrationTicket(environment.base.toString(), canonicalEmail,
            name?.trim()?.takeIf { it.isNotEmpty() }, null, environment = environment)
        val response = apiManager.getIdentityApi(environment)
            .sendRegistrationVerification(BitwardenRegistrationStartRequest(canonicalEmail, ticket.name))
        currentCoroutineContext().ensureActive()
        requireCurrent(environment)
        if ((response.code() == 404 || response.code() == 405) && environment.preset == BitwardenEnvironmentPreset.CUSTOM) {
            response.errorBody()?.close()
            ticket.copy(legacy = true)
        } else {
            response.errorBody()?.close()
            checkResponse(response.isSuccessful, response.code())
            ticket.copy(verifiedEmailToken = response.body()?.emailVerificationToken?.takeIf { it.isNotBlank() })
        }
    }

    suspend fun verifyRegistration(ticket: BitwardenRegistrationTicket, tokenOrLink: String): Result<BitwardenRegistrationTicket> = accountOperation {
        require(!ticket.legacy) { strings.get(R.string.bitwarden_account_verification_invalid) }
        val environment = requireNotNull(ticket.environment)
        requireCurrent(environment)
        check(ticket.appGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value)
        val token = runCatching { BitwardenAccountInputPolicy.verificationToken(tokenOrLink, environment.web.toString(), ticket.email) }
            .getOrElse { throw IllegalArgumentException(strings.get(R.string.bitwarden_account_verification_invalid)) }
        val response = apiManager.getIdentityApi(environment)
            .verifyRegistrationEmail(BitwardenRegistrationVerifyRequest(ticket.email, token))
        response.errorBody()?.close()
        currentCoroutineContext().ensureActive()
        requireCurrent(environment)
        checkResponse(response.isSuccessful, response.code())
        ticket.copy(verifiedEmailToken = token)
    }

    suspend fun finishRegistration(ticket: BitwardenRegistrationTicket, password: String, confirmation: String, hint: String?): Result<Unit> = accountOperation {
        require(ticket.readyForPassword) { strings.get(R.string.bitwarden_account_verification_invalid) }
        val environment = requireNotNull(ticket.environment)
        requireCurrent(environment)
        check(ticket.appGeneration == takagi.ru.monica.security.SessionManager.lockGeneration.value)
        require(password == confirmation) { strings.get(R.string.bitwarden_account_password_mismatch) }
        require(password.length >= 12 && PasswordStrengthAnalyzer.calculateStrength(password) >= 61) {
            strings.get(R.string.bitwarden_account_password_requirements)
        }
        val request = BitwardenRegistrationKeys.request(ticket, password, hint)
        currentCoroutineContext().ensureActive()
        requireCurrent(environment)
        val response = if (ticket.legacy) apiManager.getVaultApi(environment)
            .registerLegacyAccount(request)
        else apiManager.getIdentityApi(environment).finishRegistration(request)
        response.errorBody()?.close()
        currentCoroutineContext().ensureActive()
        requireCurrent(environment)
        checkResponse(response.isSuccessful, response.code())
        retire(environment)
    }

    suspend fun requestPasswordHint(serverUrl: String, email: String, tls: BitwardenTlsConfig?): Result<Unit> =
        accountOperation { requestPasswordHint(legacyEnvironment(serverUrl, tls), email).getOrThrow() }

    suspend fun requestPasswordHint(environment: BitwardenEnvironment, email: String): Result<Unit> = accountOperation {
        generations.putIfAbsent(environment.revision, takagi.ru.monica.security.SessionManager.lockGeneration.value)
        try {
        requireCurrent(environment)
        val response = apiManager.getVaultApi(environment)
            .requestPasswordHint(BitwardenEmailRequest(validatedEmail(email)))
        response.errorBody()?.close()
        currentCoroutineContext().ensureActive()
        requireCurrent(environment)
        checkResponse(response.isSuccessful, response.code())
        } finally { retire(environment) }
    }

    private fun checkResponse(success: Boolean, code: Int) {
        if (success) return
        throw AccountRequestFailure(strings.get(R.string.bitwarden_account_request_failed, code))
    }

    private class AccountRequestFailure(message: String) : IllegalStateException(message)

    private fun validatedEmail(value: String): String = runCatching { BitwardenAccountInputPolicy.email(value) }
        .getOrElse { throw IllegalArgumentException(strings.get(R.string.bitwarden_account_invalid_email)) }

    private suspend fun <T> accountOperation(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val allowed = listOf(R.string.bitwarden_account_operation_failed, R.string.bitwarden_account_invalid_email,
                R.string.bitwarden_account_password_mismatch, R.string.bitwarden_account_password_requirements,
                R.string.bitwarden_account_verification_invalid).map { strings.get(it) }
            val safe = error.message?.takeIf { error is AccountRequestFailure || it in allowed }
                ?: strings.get(R.string.bitwarden_account_operation_failed)
            Result.failure(AccountRequestFailure(safe))
        }
    }
}

internal object BitwardenAccountInputPolicy {
    fun email(value: String): String {
        val normalized = BitwardenVaultIdentity.canonicalizeEmail(value)
        require(normalized.length in 3..320 && normalized.count { it == '@' } == 1 &&
            normalized.none { it.isWhitespace() } && normalized.substringBefore('@').isNotBlank() &&
            normalized.substringAfter('@').contains('.') && !normalized.substringAfter('@').startsWith('.') &&
            !normalized.substringAfter('@').endsWith('.'))
        return normalized
    }

    /** Only extracts a token; pasted URLs can never choose a network endpoint. */
    fun verificationToken(input: String, serverUrl: String, expectedEmail: String): String {
        val value = input.trim()
        require(value.isNotBlank() && value.length <= 64 * 1024)
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) return value
        val uri = URI(value)
        val server = URI(serverUrl)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        require(uri.scheme.equals(server.scheme, true) && uri.host.equals(server.host, true) &&
            port(uri) == port(server) && uri.userInfo == null)
        // Verify raw spelling before URI/HttpUrl normalization can hide path traversal.
        val endpoint = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString() + uri.rawPath.orEmpty()
        val candidate = BitwardenEnvironmentResolver.validateEndpoint(endpoint, BitwardenEnvironmentField.WEB).value
        val web = BitwardenEnvironmentResolver.validateEndpoint(serverUrl, BitwardenEnvironmentField.WEB).value
        require(candidate != null && web != null && candidate.encodedPath.startsWith(web.encodedPath))
        val query = uri.rawQuery ?: uri.rawFragment?.substringAfter('?', "")
        val parameters = query.orEmpty().split('&').filter { it.isNotBlank() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        fun unique(name: String): String? {
            val values = parameters.filter { it.first == name }.map { it.second }
            require(values.size <= 1)
            return values.singleOrNull()
        }
        unique("email")?.let { require(email(it) == email(expectedEmail)) }
        val verificationToken = unique("emailVerificationToken")
        val alternateToken = unique("token")
        require(verificationToken == null || alternateToken == null)
        val token = verificationToken ?: alternateToken
        require(!token.isNullOrBlank())
        return token
    }
}

internal object BitwardenRegistrationKeys {
    fun request(ticket: BitwardenRegistrationTicket, password: String, hint: String?): JsonObject {
        val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, ticket.email, 600_000)
        val stretched = BitwardenCrypto.stretchMasterKey(master)
        val userBytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val userKey = BitwardenCrypto.SymmetricCryptoKey(userBytes.copyOfRange(0, 32), userBytes.copyOfRange(32, 64))
        var encodedPrivate: ByteArray? = null
        return try {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            encodedPrivate = pair.private.encoded
            val publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            val privateKey = BitwardenCrypto.encrypt(requireNotNull(encodedPrivate), userKey)
            val wrapped = BitwardenCrypto.encrypt(userBytes, stretched)
            val hash = BitwardenCrypto.deriveMasterPasswordHash(master, password)
            val keys = buildJsonObject { put("publicKey", publicKey); put("encryptedPrivateKey", privateKey) }
            if (ticket.legacy) buildJsonObject {
                put("email", ticket.email); ticket.name?.let { put("name", it) }
                put("masterPasswordHash", hash); hint?.takeIf { it.isNotBlank() }?.let { put("masterPasswordHint", it) }
                put("key", wrapped); put("keys", keys); put("kdf", 0); put("kdfIterations", 600_000)
            } else buildJsonObject {
                put("email", ticket.email); put("emailVerificationToken", requireNotNull(ticket.verifiedEmailToken))
                hint?.takeIf { it.isNotBlank() }?.let { put("masterPasswordHint", it) }
                put("userAsymmetricKeys", keys)
                val kdf = buildJsonObject { put("kdfType", 0); put("iterations", 600_000) }
                put("MasterPasswordAuthentication", buildJsonObject {
                    put("Kdf", kdf); put("Salt", ticket.email); put("MasterPasswordAuthenticationHash", hash)
                })
                put("MasterPasswordUnlock", buildJsonObject {
                    put("kdf", kdf); put("salt", ticket.email); put("masterKeyWrappedUserKey", wrapped)
                })
            }
        } finally {
            master.fill(0); stretched.clear(); userBytes.fill(0); userKey.clear(); encodedPrivate?.fill(0)
        }
    }
}
