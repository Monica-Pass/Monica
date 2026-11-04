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
) {
    val readyForPassword: Boolean get() = legacy || !verifiedEmailToken.isNullOrBlank()
}

/** Registration never saves a password, token, private key or partially initialized account. */
internal class BitwardenAccountService(
    context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager(),
) {
    private val strings = AppLocaleStringResolver(context)

    suspend fun startRegistration(serverUrl: String, email: String, name: String?, tls: BitwardenTlsConfig?): Result<BitwardenRegistrationTicket> = accountOperation {
        val canonicalEmail = validatedEmail(email)
        require((name?.length ?: 0) <= 512) { strings.get(R.string.bitwarden_account_operation_failed) }
        val urls = BitwardenApiFactory.inferServerUrls(serverUrl)
        val ticket = BitwardenRegistrationTicket(urls.vault, canonicalEmail, name?.trim()?.takeIf { it.isNotEmpty() }, tls)
        val response = apiManager.getIdentityApi(urls.identity, urls.vault, tlsConfig = tls)
            .sendRegistrationVerification(BitwardenRegistrationStartRequest(canonicalEmail, ticket.name))
        if ((response.code() == 404 || response.code() == 405) && !BitwardenApiFactory.isOfficialServer(urls.vault) &&
            !BitwardenApiFactory.isOfficialEuServer(urls.vault)) {
            ticket.copy(legacy = true)
        } else {
            checkResponse(response.isSuccessful, response.code(), response.errorBody()?.string())
            ticket.copy(verifiedEmailToken = response.body()?.emailVerificationToken?.takeIf { it.isNotBlank() })
        }
    }

    suspend fun verifyRegistration(ticket: BitwardenRegistrationTicket, tokenOrLink: String): Result<BitwardenRegistrationTicket> = accountOperation {
        require(!ticket.legacy) { strings.get(R.string.bitwarden_account_verification_invalid) }
        val token = runCatching { BitwardenAccountInputPolicy.verificationToken(tokenOrLink, ticket.serverUrl, ticket.email) }
            .getOrElse { throw IllegalArgumentException(strings.get(R.string.bitwarden_account_verification_invalid)) }
        val urls = BitwardenApiFactory.inferServerUrls(ticket.serverUrl)
        val response = apiManager.getIdentityApi(urls.identity, urls.vault, tlsConfig = ticket.tlsConfig)
            .verifyRegistrationEmail(BitwardenRegistrationVerifyRequest(ticket.email, token))
        checkResponse(response.isSuccessful, response.code(), response.errorBody()?.string())
        ticket.copy(verifiedEmailToken = token)
    }

    suspend fun finishRegistration(ticket: BitwardenRegistrationTicket, password: String, confirmation: String, hint: String?): Result<Unit> = accountOperation {
        require(ticket.readyForPassword) { strings.get(R.string.bitwarden_account_verification_invalid) }
        require(password == confirmation) { strings.get(R.string.bitwarden_account_password_mismatch) }
        require(password.length >= 12 && PasswordStrengthAnalyzer.calculateStrength(password) >= 61) {
            strings.get(R.string.bitwarden_account_password_requirements)
        }
        val request = BitwardenRegistrationKeys.request(ticket, password, hint)
        currentCoroutineContext().ensureActive()
        val urls = BitwardenApiFactory.inferServerUrls(ticket.serverUrl)
        val response = if (ticket.legacy) apiManager.getVaultApi(urls.api, urls.vault, tlsConfig = ticket.tlsConfig)
            .registerLegacyAccount(request)
        else apiManager.getIdentityApi(urls.identity, urls.vault, tlsConfig = ticket.tlsConfig).finishRegistration(request)
        checkResponse(response.isSuccessful, response.code(), response.errorBody()?.string())
    }

    suspend fun requestPasswordHint(serverUrl: String, email: String, tls: BitwardenTlsConfig?): Result<Unit> = accountOperation {
        val urls = BitwardenApiFactory.inferServerUrls(serverUrl)
        val response = apiManager.getVaultApi(urls.api, urls.vault, tlsConfig = tls)
            .requestPasswordHint(BitwardenEmailRequest(validatedEmail(email)))
        checkResponse(response.isSuccessful, response.code(), response.errorBody()?.string())
    }

    private fun checkResponse(success: Boolean, code: Int, body: String?) {
        if (success) return
        val message = runCatching {
            val json = Json.parseToJsonElement(body.orEmpty()).jsonObject
            json["message"]?.jsonPrimitive?.content ?: json["Message"]?.jsonPrimitive?.content
                ?: json["errorModel"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()?.take(240)
        throw IllegalStateException(message ?: strings.get(R.string.bitwarden_account_request_failed, code))
    }

    private fun validatedEmail(value: String): String = runCatching { BitwardenAccountInputPolicy.email(value) }
        .getOrElse { throw IllegalArgumentException(strings.get(R.string.bitwarden_account_invalid_email)) }

    private suspend fun <T> accountOperation(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(action()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
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
