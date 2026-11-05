package takagi.ru.monica.bitwarden.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverAccess
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverApi
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverChallenge
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverCredential
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverTokenReply
import takagi.ru.monica.bitwarden.api.BitwardenSendReceiverTransport
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** These views are ephemeral; callers must not save them in bundles, persistence or logs. */
sealed class BitwardenSendReceiverState {
    object Idle : BitwardenSendReceiverState()
    object Working : BitwardenSendReceiverState()
    class PasswordRequired(val rejected: Boolean = false) : BitwardenSendReceiverState()
    object EmailRequired : BitwardenSendReceiverState()
    class EmailCodeRequired(val resendAvailableAtMillis: Long, val rejected: Boolean = false) : BitwardenSendReceiverState()
    class TextReady(val title: String?, val text: String, val hidden: Boolean,
                    val creatorIdentifier: String?, val expiresAtMillis: Long?) : BitwardenSendReceiverState()
    class FileReady(val title: String?, val fileName: String, val encryptedSize: Long?,
                    val creatorIdentifier: String?, val expiresAtMillis: Long?) : BitwardenSendReceiverState()
    class Failure(val code: BitwardenSendReceiverFailure, val accessMayHaveBeenCounted: Boolean = false,
                  val canRetryFile: Boolean = false) : BitwardenSendReceiverState()
    object Saved : BitwardenSendReceiverState()
    object Closed : BitwardenSendReceiverState()
    final override fun toString(): String = "BitwardenSendReceiverState(redacted)"
}

/**
 * One received link, one owned anonymous transport. No account ID, client, key store or refresh path.
 * The host owns app lock/background/picker policy and must supply a live unlock-generation check.
 */
class BitwardenSendReceiverSession(
    link: BitwardenSendReceiverLink,
    private val target: BitwardenSendReceiverTarget,
    private val transport: BitwardenSendReceiverTransport = BitwardenSendReceiverApi(),
    private val requireCurrent: () -> Unit,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val guard = Any()
    private val crypto: BitwardenSendReceiverCrypto
    private val accessId = link.accessId
    private val createdAt = monotonicMillis()
    private var touchedAt = createdAt
    private var closed = false
    private var busy = false
    private var activeJob: Job? = null
    private var token: String? = null
    private var tokenExpiresAt = 0L
    private var authentication: BitwardenSendReceiverChallenge? = null
    private var challengeEmail: String? = null
    private var resendAt = 0L
    private var fileAccess: BitwardenSendReceiverAccess? = null
    private var sendExpiresAt: Long? = null
    private val mutableState = MutableStateFlow<BitwardenSendReceiverState>(BitwardenSendReceiverState.Idle)
    val state: StateFlow<BitwardenSendReceiverState> = mutableState.asStateFlow()

    init {
        crypto = try {
            receiverRequire(link.webBase == target.webBase, BitwardenSendReceiverFailure.INVALID_TARGET)
            requireCurrent()
            BitwardenSendReceiverCrypto(link.consumeKey())
        } catch (error: Exception) {
            link.close(); transport.close()
            throw if (error is BitwardenSendReceiverException) error
                else BitwardenSendReceiverException(BitwardenSendReceiverFailure.CLOSED)
        }
    }

    /** An explicit new attempt. Never called by token expiry, a timer, recomposition or a retry interceptor. */
    suspend fun open(): BitwardenSendReceiverState = operation {
        token = null; challengeEmail = null; fileAccess = null; authentication = null; sendExpiresAt = null
        acceptToken(transport.token(target, accessId, BitwardenSendReceiverCredential.none()), this, initial = true)
    }

    suspend fun submitPassword(password: CharArray): BitwardenSendReceiverState {
        try {
            return operation {
                receiverRequire(authentication == BitwardenSendReceiverChallenge.PASSWORD, BitwardenSendReceiverFailure.INVALID_INPUT)
                val hash = crypto.passwordHash(password)
                current()
                acceptToken(transport.token(target, accessId, BitwardenSendReceiverCredential.password(hash)), this)
            }
        } finally { password.fill('\u0000') }
    }

    /** Explicit user action only; the expected challenge response does not confirm mail delivery. */
    suspend fun requestEmailCode(email: String): BitwardenSendReceiverState = operation {
        receiverRequire(authentication == BitwardenSendReceiverChallenge.EMAIL || authentication == BitwardenSendReceiverChallenge.EMAIL_CODE,
            BitwardenSendReceiverFailure.INVALID_INPUT)
        val normalized = email.trim()
        receiverRequire(normalized.length in 3..320 && normalized.matches(Regex("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$")),
            BitwardenSendReceiverFailure.INVALID_INPUT)
        if (monotonicMillis() < resendAt) {
            publish(BitwardenSendReceiverState.EmailCodeRequired(resendAt)); return@operation
        }
        challengeEmail = normalized
        resendAt = monotonicMillis() + 30_000
        val result = transport.token(target, accessId, BitwardenSendReceiverCredential.email(normalized))
        current()
        when (result) {
            is BitwardenSendReceiverTokenReply.Challenge -> {
                receiverRequire(result.challenge == BitwardenSendReceiverChallenge.EMAIL_CODE, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                authentication = BitwardenSendReceiverChallenge.EMAIL_CODE
                publish(BitwardenSendReceiverState.EmailCodeRequired(resendAt))
            }
            is BitwardenSendReceiverTokenReply.Rejected -> {
                if (result.code == BitwardenSendReceiverFailure.RATE_LIMITED) {
                    resendAt = maxOf(resendAt, monotonicMillis() + (result.retryAfterSeconds ?: 30).coerceIn(0, 86400) * 1000)
                }
                throw BitwardenSendReceiverException(result.code)
            }
            is BitwardenSendReceiverTokenReply.Granted -> throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
        }
    }

    suspend fun submitEmailCode(code: CharArray): BitwardenSendReceiverState {
        try {
            return operation {
                receiverRequire(authentication == BitwardenSendReceiverChallenge.EMAIL_CODE && challengeEmail != null,
                    BitwardenSendReceiverFailure.INVALID_INPUT)
                receiverRequire(code.size in 1..128 && code.none { it.code < 0x20 || it.code == 0x7f },
                    BitwardenSendReceiverFailure.INVALID_INPUT)
                acceptToken(transport.token(target, accessId,
                    BitwardenSendReceiverCredential.emailCode(challengeEmail!!, String(code))), this)
            }
        } finally { code.fill('\u0000') }
    }

    /**
     * Root supplies an app-private staging directory and the SAF output factory. The factory is not
     * invoked until complete HMAC and padding verification; it must recheck the destination lease.
     * On failed external writes the host must remove/report its exact newly created document.
     */
    suspend fun saveFile(
        stagingDirectory: File,
        openVerifiedOutput: () -> OutputStream,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ): Boolean {
        val result = operation {
            val metadata = fileAccess ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_INPUT)
            val currentToken = token
            receiverRequire(currentToken != null && monotonicMillis() + 5000 < tokenExpiresAt, BitwardenSendReceiverFailure.TOKEN_EXPIRED)
            val id = metadata.fileId ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
            BitwardenSendReceiverApi.validFileId(id)
            receiverRequire(metadata.encryptedSize == null || metadata.encryptedSize <= BitwardenSendReceiverCrypto.MAX_FILE_BYTES,
                BitwardenSendReceiverFailure.TOO_LARGE)
            receiverRequire(stagingDirectory.isDirectory || stagingDirectory.mkdirs(), BitwardenSendReceiverFailure.IO)
            val wanted = metadata.encryptedSize ?: BitwardenSendReceiverCrypto.MAX_FILE_BYTES
            receiverRequire(stagingDirectory.usableSpace >= wanted + 1024 * 1024, BitwardenSendReceiverFailure.IO)
            val staged = File(stagingDirectory, "send-receiver-${UUID.randomUUID()}.cipher")
            receiverRequire(staged.createNewFile(), BitwardenSendReceiverFailure.IO)
            try {
                RandomAccessFile(staged, "rw").use { file ->
                    current(); counted = true
                    val download = transport.authorizeFile(target, currentToken!!, id)
                    current(); receiverRequire(download.fileId == id, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    val sink = object : OutputStream() {
                        override fun write(b: Int) { current(); file.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { current(); file.write(b, off, len) }
                    }
                    transport.download(download, metadata.encryptedSize, sink, { current() }) { bytes, total ->
                        current(); progress(bytes, total)
                    }
                    current()
                    crypto.decryptFile(file, metadata.encryptedSize, {
                        current(); openVerifiedOutput()
                    }, { current() })
                    current(); publish(BitwardenSendReceiverState.Saved)
                }
            } finally { staged.delete() }
        }
        return result === BitwardenSendReceiverState.Saved
    }

    private suspend fun acceptToken(result: BitwardenSendReceiverTokenReply, operation: Operation, initial: Boolean = false) {
        operation.current()
        when (result) {
            is BitwardenSendReceiverTokenReply.Granted -> {
                receiverRequire(result.expiresInSeconds in 1..86400 && result.token.isNotBlank(), BitwardenSendReceiverFailure.INVALID_RESPONSE)
                token = result.token
                tokenExpiresAt = monotonicMillis() + result.expiresInSeconds * 1000
                challengeEmail = null
                operation.current(); operation.counted = true
                val response = transport.access(target, result.token)
                operation.current()
                receiverRequire(response.id == accessId, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                receiverRequire(response.expiresAtMillis == null || wallMillis() < response.expiresAtMillis, BitwardenSendReceiverFailure.UNAVAILABLE)
                sendExpiresAt = response.expiresAtMillis
                val title = response.encryptedName?.let { crypto.decryptText(it, 4096) }
                if (response.type == 0) {
                    val text = response.encryptedText ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    receiverRequire(response.fileId == null && response.encryptedFileName == null, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    val view = BitwardenSendReceiverState.TextReady(title, crypto.decryptText(text), response.hidden,
                        response.creatorIdentifier, response.expiresAtMillis)
                    operation.current(); operation.publish(view)
                    token = null
                } else {
                    receiverRequire(response.type == 1 && response.encryptedText == null, BitwardenSendReceiverFailure.UNSUPPORTED)
                    val fileId = response.fileId ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    BitwardenSendReceiverApi.validFileId(fileId)
                    val name = response.encryptedFileName ?: throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    val decryptedName = crypto.decryptText(name, 4096)
                    receiverRequire(decryptedName.isNotBlank(), BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    fileAccess = response
                    operation.current(); operation.publish(BitwardenSendReceiverState.FileReady(title, decryptedName,
                        response.encryptedSize, response.creatorIdentifier, response.expiresAtMillis))
                }
            }
            is BitwardenSendReceiverTokenReply.Challenge -> when (result.challenge) {
                BitwardenSendReceiverChallenge.PASSWORD, BitwardenSendReceiverChallenge.INVALID_PASSWORD -> {
                    receiverRequire(initial || authentication == BitwardenSendReceiverChallenge.PASSWORD, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    authentication = BitwardenSendReceiverChallenge.PASSWORD
                    operation.publish(BitwardenSendReceiverState.PasswordRequired(result.challenge == BitwardenSendReceiverChallenge.INVALID_PASSWORD))
                }
                BitwardenSendReceiverChallenge.EMAIL -> {
                    receiverRequire(initial, BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    authentication = BitwardenSendReceiverChallenge.EMAIL
                    operation.publish(BitwardenSendReceiverState.EmailRequired)
                }
                BitwardenSendReceiverChallenge.EMAIL_CODE -> {
                    receiverRequire(authentication == BitwardenSendReceiverChallenge.EMAIL_CODE && challengeEmail != null,
                        BitwardenSendReceiverFailure.INVALID_RESPONSE)
                    operation.publish(BitwardenSendReceiverState.EmailCodeRequired(resendAt, rejected = true))
                }
                BitwardenSendReceiverChallenge.INVALID_SEND -> throw BitwardenSendReceiverException(BitwardenSendReceiverFailure.UNAVAILABLE)
            }
            is BitwardenSendReceiverTokenReply.Rejected -> throw BitwardenSendReceiverException(result.code)
        }
    }

    private inner class Operation {
        var counted = false
        fun current() {
            requireCurrent()
            synchronized(guard) {
                val now = monotonicMillis()
                if (closed || now < createdAt || now - createdAt >= 15 * 60_000 || now - touchedAt >= 5 * 60_000 ||
                    sendExpiresAt?.let { wallMillis() >= it } == true) {
                    throw CancellationException("Send receiver expired or closed")
                }
                activeJob?.ensureActive()
                touchedAt = now
            }
        }
        fun publish(value: BitwardenSendReceiverState) {
            current()
            synchronized(guard) { if (!closed) mutableState.value = value }
        }
    }

    private suspend fun operation(block: suspend Operation.() -> Unit): BitwardenSendReceiverState = coroutineScope {
        val thisJob = currentCoroutineContext()[Job]
        val acquired = synchronized(guard) {
            if (closed || busy) false else { busy = true; activeJob = thisJob; true }
        }
        if (!acquired) return@coroutineScope mutableState.value
        val work = Operation()
        try {
            work.current(); work.publish(BitwardenSendReceiverState.Working)
            withContext(Dispatchers.IO) { work.block() }
            work.current()
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (error: Exception) {
            try {
                work.current()
                val code = (error as? BitwardenSendReceiverException)?.code ?: BitwardenSendReceiverFailure.IO
                work.publish(BitwardenSendReceiverState.Failure(code, work.counted, fileAccess != null))
            } catch (cancelled: CancellationException) { close(); throw cancelled }
            catch (_: Exception) { close() }
        } finally {
            synchronized(guard) {
                busy = false; activeJob = null
                // Also scrub fields an already-running callback could briefly assign after close.
                if (closed) {
                    token = null; tokenExpiresAt = 0; challengeEmail = null; fileAccess = null
                    authentication = null; sendExpiresAt = null
                }
            }
        }
        mutableState.value
    }

    override fun close() {
        val job = synchronized(guard) {
            if (closed) return
            closed = true; token = null; tokenExpiresAt = 0; challengeEmail = null
            fileAccess = null; authentication = null; sendExpiresAt = null
            mutableState.value = BitwardenSendReceiverState.Closed
            activeJob
        }
        job?.cancel(CancellationException("Send receiver closed"))
        crypto.close(); transport.close()
    }

    override fun toString(): String = "BitwardenSendReceiverSession(redacted)"
}
