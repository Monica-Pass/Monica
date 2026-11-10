package takagi.ru.monica.bitwarden.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverException
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverFailure
import takagi.ru.monica.bitwarden.service.BitwardenSendReceiverTarget
import takagi.ru.monica.bitwarden.service.receiverRequire
import takagi.ru.monica.bitwarden.service.receiverSafeUrl

class BitwardenSendReceiverCredential private constructor(internal val fields: Map<String, String>) {
    override fun toString(): String = "BitwardenSendReceiverCredential(redacted)"
    companion object {
        fun none() = BitwardenSendReceiverCredential(emptyMap())
        fun password(hash: String) = BitwardenSendReceiverCredential(mapOf("password_hash_b64" to hash))
        fun email(email: String) = BitwardenSendReceiverCredential(mapOf("email" to email))
        fun emailCode(email: String, otp: String) = BitwardenSendReceiverCredential(mapOf("email" to email, "otp" to otp))
    }
}

enum class BitwardenSendReceiverChallenge { PASSWORD, EMAIL, EMAIL_CODE, INVALID_PASSWORD, INVALID_SEND }

sealed class BitwardenSendReceiverTokenReply {
    class Granted(val token: String, val expiresInSeconds: Long) : BitwardenSendReceiverTokenReply()
    class Challenge(val challenge: BitwardenSendReceiverChallenge) : BitwardenSendReceiverTokenReply()
    class Rejected(val code: BitwardenSendReceiverFailure, val retryAfterSeconds: Long? = null) : BitwardenSendReceiverTokenReply()
    final override fun toString(): String = "BitwardenSendReceiverTokenReply(redacted)"
}

class BitwardenSendReceiverAccess(
    val id: String,
    val type: Int,
    val encryptedName: String?,
    val encryptedText: String?,
    val hidden: Boolean,
    val fileId: String?,
    val encryptedFileName: String?,
    val encryptedSize: Long?,
    val expiresAtMillis: Long?,
    val creatorIdentifier: String?,
) { override fun toString(): String = "BitwardenSendReceiverAccess(redacted)" }

class BitwardenSendReceiverDownload(val fileId: String, val url: HttpUrl) {
    override fun toString(): String = "BitwardenSendReceiverDownload(redacted)"
}

/** A per-receiver transport. Implementations must never share authenticated account clients. */
interface BitwardenSendReceiverTransport : AutoCloseable {
    suspend fun token(target: BitwardenSendReceiverTarget, accessId: String, credential: BitwardenSendReceiverCredential): BitwardenSendReceiverTokenReply
    suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess
    suspend fun authorizeFile(target: BitwardenSendReceiverTarget, token: String, fileId: String): BitwardenSendReceiverDownload
    suspend fun download(target: BitwardenSendReceiverDownload, expectedBytes: Long?, sink: OutputStream,
                         requireCurrent: () -> Unit, progress: (Long, Long?) -> Unit)
}

/** No client/config/account parameters: even storage requests use a separate anonymous pool. */
class BitwardenSendReceiverApi private constructor(trust: X509TrustManager?) : BitwardenSendReceiverTransport {
    constructor() : this(null)
    private val apiClient = anonymousClient(trust)
    private val storageClient = anonymousClient(trust)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var closed = false

    override suspend fun token(target: BitwardenSendReceiverTarget, accessId: String,
                               credential: BitwardenSendReceiverCredential): BitwardenSendReceiverTokenReply {
        val body = FormBody.Builder().add("client_id", "send").add("grant_type", "send_access")
            .add("scope", "api.send.access").add("send_id", accessId).apply {
                credential.fields.forEach { (key, value) -> add(key, value) }
            }.build()
        val request = Request.Builder().url(target.identityBase.newBuilder().addPathSegments("connect/token").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store").post(body).build()
        return execute(apiClient, request, counted = false) { response, current ->
            if (response.code == 429) return@execute BitwardenSendReceiverTokenReply.Rejected(
                BitwardenSendReceiverFailure.RATE_LIMITED, response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 86400))
            if (response.code in 300..399) return@execute BitwardenSendReceiverTokenReply.Rejected(BitwardenSendReceiverFailure.INVALID_TARGET)
            val value = readJson(response, current)
            if (response.isSuccessful) {
                val token = value.string("access_token")
                val ttl = (value["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull()
                receiverRequire(token != null && token.length in 1..32768 && token.all { it.code in 0x21..0x7e } &&
                    ttl != null && ttl in 1..86400 && value.string("token_type")?.equals("Bearer", true) == true &&
                    value.string("scope")?.split(' ')?.filter(String::isNotEmpty)?.toSet() == setOf("api.send.access"),
                    BitwardenSendReceiverFailure.INVALID_RESPONSE)
                return@execute BitwardenSendReceiverTokenReply.Granted(token!!, ttl!!)
            }
            val error = value.string("error")
            val detail = value.string("send_access_error_type")
            val challenge = when {
                error == "invalid_request" && detail == "password_hash_b64_required" -> BitwardenSendReceiverChallenge.PASSWORD
                error == "invalid_request" && detail == "email_required" -> BitwardenSendReceiverChallenge.EMAIL
                error == "invalid_request" && detail == "email_and_otp_required" -> BitwardenSendReceiverChallenge.EMAIL_CODE
                error == "invalid_grant" && detail == "password_hash_b64_invalid" -> BitwardenSendReceiverChallenge.INVALID_PASSWORD
                error == "invalid_grant" && detail == "send_id_invalid" -> BitwardenSendReceiverChallenge.INVALID_SEND
                else -> null
            }
            if (challenge != null && response.code in 400..499) BitwardenSendReceiverTokenReply.Challenge(challenge)
            else BitwardenSendReceiverTokenReply.Rejected(when {
                error in setOf("unsupported_grant_type", "invalid_client", "unauthorized_client", "invalid_scope") || response.code == 405 -> BitwardenSendReceiverFailure.UNSUPPORTED
                response.code == 404 -> BitwardenSendReceiverFailure.UNAVAILABLE
                response.code >= 500 -> BitwardenSendReceiverFailure.NETWORK
                else -> BitwardenSendReceiverFailure.REJECTED
            })
        }
    }

    override suspend fun access(target: BitwardenSendReceiverTarget, token: String): BitwardenSendReceiverAccess {
        val request = authenticatedPost(target.apiBase.newBuilder().addPathSegments("sends/access").build(), token)
        return execute(apiClient, request, counted = true) { response, current ->
            requireSuccess(response)
            val value = readJson(response, current)
            val type = (value["type"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            receiverRequire(type in listOf(0, 1), BitwardenSendReceiverFailure.UNSUPPORTED)
            val text = value.optionalObject("text")
            val file = value.optionalObject("file")
            receiverRequire(value["data"] == null || value["data"] == JsonNull, BitwardenSendReceiverFailure.UNSUPPORTED)
            receiverRequire(if (type == 0) text != null && file == null else file != null && text == null,
                BitwardenSendReceiverFailure.INVALID_RESPONSE)
            val expiry = value.string("expirationDate")?.let {
                try { Instant.parse(it).toEpochMilli() } catch (_: Exception) {
                    throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
                }
            }
            val size = file?.get("size")?.takeUnless { it == JsonNull }?.let {
                val number = (it as? JsonPrimitive)?.content?.toLongOrNull()
                receiverRequire(number != null && number >= 65, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                number
            }
            val hidden = text?.get("hidden")?.let {
                val parsed = (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
                receiverRequire(parsed != null, BitwardenSendReceiverFailure.INVALID_RESPONSE); parsed
            } ?: false
            BitwardenSendReceiverAccess(
                id = value.string("id") ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE),
                type = type!!, encryptedName = value.string("name"), encryptedText = text?.string("text"), hidden = hidden,
                fileId = file?.string("id"), encryptedFileName = file?.string("fileName"), encryptedSize = size,
                expiresAtMillis = expiry, creatorIdentifier = value.string("creatorIdentifier")?.also {
                    receiverRequire(it.length <= 320 && it.none { char -> char.code < 0x20 }, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                },
            )
        }
    }

    override suspend fun authorizeFile(target: BitwardenSendReceiverTarget, token: String, fileId: String): BitwardenSendReceiverDownload {
        validFileId(fileId)
        val request = authenticatedPost(target.apiBase.newBuilder().addPathSegments("sends/access/file").addPathSegment(fileId).build(), token)
        return execute(apiClient, request, counted = true) { response, current ->
            requireSuccess(response)
            val value = readJson(response, current)
            receiverRequire(value.string("id") == fileId, BitwardenSendReceiverFailure.INVALID_RESPONSE)
            val url = value.string("url") ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
            BitwardenSendReceiverDownload(fileId, receiverSafeUrl(url, allowQuery = true))
        }
    }

    override suspend fun download(target: BitwardenSendReceiverDownload, expectedBytes: Long?, sink: OutputStream,
                                  requireCurrent: () -> Unit, progress: (Long, Long?) -> Unit) {
        receiverSafeUrl(target.url.toString(), allowQuery = true)
        val request = Request.Builder().url(target.url).header("Cache-Control", "no-store")
            .header("Accept-Encoding", "identity").get().build()
        execute(storageClient, request, counted = false) { response, current ->
            requireSuccess(response)
            receiverRequire(response.code == 200, BitwardenSendReceiverFailure.INVALID_RESPONSE)
            val body = response.body ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
            val declared = body.contentLength().takeIf { it >= 0 }
            val maximum = 512L * 1024 * 1024
            receiverRequire((declared == null || declared <= maximum) && (expectedBytes == null || expectedBytes <= maximum),
                BitwardenSendReceiverFailure.TOO_LARGE)
            receiverRequire(declared == null || expectedBytes == null || declared == expectedBytes, BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
            val buffer = ByteArray(64 * 1024)
            try {
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        current(); requireCurrent()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        receiverRequire(total <= maximum && (expectedBytes == null || total <= expectedBytes), BitwardenSendReceiverFailure.TOO_LARGE)
                        current(); requireCurrent(); sink.write(buffer, 0, count); progress(total, expectedBytes ?: declared)
                    }
                }
                receiverRequire((declared == null || total == declared) && (expectedBytes == null || total == expectedBytes),
                    BitwardenSendReceiverFailure.WRONG_KEY_OR_DAMAGED)
                current(); requireCurrent(); sink.flush()
            } finally { buffer.fill(0) }
        }
    }

    private fun authenticatedPost(url: HttpUrl, token: String): Request {
        receiverRequire(token.isNotBlank() && token.all { it.code in 0x21..0x7e }, BitwardenSendReceiverFailure.INVALID_RESPONSE)
        return Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
            .header("Cache-Control", "no-store").post(ByteArray(0).toRequestBody()).build()
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, counted: Boolean,
                                   read: (Response, () -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed) { continuation.resumeWithException(BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)); return@suspendCancellableCoroutine }
        val call = client.newCall(request.withSingleUseBody())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(BitwardenSendReceiverException(
                    if (counted) BitwardenSendReceiverFailure.OUTCOME_UNKNOWN else BitwardenSendReceiverFailure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { read(it) {
                        if (!continuation.isActive || closed) throw CancellationException("Send receiver closed")
                    } }
                    if (continuation.isActive && !closed) continuation.resume(result)
                    else continuation.cancel()
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        val safe = when (error) {
                            is CancellationException -> error
                            is BitwardenSendReceiverException -> error
                            else -> BitwardenSendReceiverException(if (counted) BitwardenSendReceiverFailure.OUTCOME_UNKNOWN else BitwardenSendReceiverFailure.INVALID_RESPONSE)
                        }
                        continuation.resumeWithException(safe)
                    }
                }
            }
        })
        if (closed) call.cancel()
    }

    /** OkHttp may replay a 503 with Retry-After: 0 even with connection retries disabled. */
    private fun Request.withSingleUseBody(): Request {
        val original = body ?: return this
        return newBuilder().method(method, object : RequestBody() {
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
            override fun isOneShot() = true
        }).build()
    }

    private fun readJson(response: Response, current: () -> Unit): JsonObject {
        val body = response.body ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
        receiverRequire(body.contentLength() <= 2 * 1024 * 1024, BitwardenSendReceiverFailure.TOO_LARGE)
        val buffer = ByteArray(8192)
        val collected = ByteArrayOutputStream()
        try {
            body.byteStream().use { input ->
                while (true) {
                    current()
                    val count = input.read(buffer)
                    if (count < 0) break
                    receiverRequire(collected.size() + count <= 2 * 1024 * 1024, BitwardenSendReceiverFailure.TOO_LARGE)
                    collected.write(buffer, 0, count)
                }
            }
            return json.parseToJsonElement(collected.toString("UTF-8")).jsonObject
        } finally { buffer.fill(0); collected.reset() }
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        throw BitwardenSendReceiverException(when (response.code) {
            in 300..399 -> BitwardenSendReceiverFailure.INVALID_TARGET
            401 -> BitwardenSendReceiverFailure.TOKEN_EXPIRED
            404 -> BitwardenSendReceiverFailure.UNAVAILABLE
            405 -> BitwardenSendReceiverFailure.UNSUPPORTED
            429 -> BitwardenSendReceiverFailure.RATE_LIMITED
            in 500..599 -> BitwardenSendReceiverFailure.OUTCOME_UNKNOWN
            else -> BitwardenSendReceiverFailure.REJECTED
        })
    }

    override fun close() {
        closed = true
        listOf(apiClient, storageClient).forEach { client ->
            client.dispatcher.cancelAll()
            val retiredPool = client.connectionPool
            retiredPoolCleanup.launch { retiredPool.evictAll() }
        }
    }
    override fun toString(): String = "BitwardenSendReceiverApi(redacted)"

    companion object {
        // Receiver closed/lease/key revocation remains synchronous; only physical TLS shutdown is deferred.
        private val retiredPoolCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Trust-only test seam; no key manager, existing client, account config or header injection. */
        internal fun withTrustForTests(trust: X509TrustManager) = BitwardenSendReceiverApi(trust)

        private fun anonymousClient(testTrust: X509TrustManager?): OkHttpClient {
            val trust = testTrust ?: TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(null as KeyStore?)
                trustManagers.filterIsInstance<X509TrustManager>().single()
            }
            // Explicitly empty key managers prevent even a process-wide default client key store
            // from supplying a certificate to an arbitrary received-link or storage origin.
            val tls = SSLContext.getInstance("TLS").apply { init(emptyArray(), arrayOf(trust), null) }
            return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .cache(null).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES).build()
        }

        internal fun validFileId(id: String) = receiverRequire(id.length in 1..128 &&
            id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' },
            BitwardenSendReceiverFailure.INVALID_RESPONSE)
    }
}

private fun JsonObject.string(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    receiverRequire(value is JsonPrimitive && value.isString, BitwardenSendReceiverFailure.INVALID_RESPONSE)
    return (value as JsonPrimitive).content
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    receiverRequire(value is JsonObject, BitwardenSendReceiverFailure.INVALID_RESPONSE)
    return value as JsonObject
}
