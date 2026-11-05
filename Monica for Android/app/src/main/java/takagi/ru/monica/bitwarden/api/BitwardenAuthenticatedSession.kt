package takagi.ru.monica.bitwarden.api

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/** A captured account session; refresh rotates credentials, never the account or its keys. */
internal class BitwardenAuthenticatedSession(
    initialToken: String,
    private val currentToken: () -> String?,
    private val requireCurrent: () -> Unit,
    private val refresh: suspend (rejectedToken: String) -> String?,
) {
    private val active = AtomicBoolean(true)
    private val refreshMutex = Mutex()
    private val tokens = linkedSetOf(initialToken)

    override fun toString() = "BitwardenAuthenticatedSession(redacted)"

    fun retire() {
        active.set(false)
        synchronized(tokens) { tokens.clear() }
    }

    fun checkCurrent() {
        check(active.get()) { "Bitwarden request session retired" }
        requireCurrent()
    }

    fun accepts(token: String): Boolean {
        checkCurrent()
        return synchronized(tokens) { token in tokens || token == currentToken() }
    }

    private fun remember(token: String): String {
        checkCurrent()
        synchronized(tokens) {
            check(active.get()) { "Bitwarden request session retired" }
            tokens.add(token)
            // Only in-process aliases for still-running operations. Old aliases may safely expire.
            while (tokens.size > 64) tokens.remove(tokens.first())
        }
        return token
    }

    private fun tokenFor(authorization: String): String {
        check(authorization.startsWith("Bearer ")) { "Bitwarden bearer credential required" }
        val supplied = authorization.removePrefix("Bearer ")
        check(accepts(supplied)) { "Bitwarden credential belongs to another session" }
        return remember(requireNotNull(currentToken()) { "Bitwarden account is locked" })
    }

    /** Other callers re-use a successful rotation instead of submitting the old refresh token. */
    suspend fun refreshRejected(rejectedToken: String): String? {
        currentCoroutineContext().ensureActive()
        check(accepts(rejectedToken)) { "Bitwarden refresh session changed" }
        return refreshMutex.withLock {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val latest = requireNotNull(currentToken())
            if (latest != rejectedToken) return@withLock remember(latest)
            val result = refresh(rejectedToken) ?: return@withLock null
            currentCoroutineContext().ensureActive()
            checkCurrent()
            check(currentToken() == result) { "Bitwarden refresh was not published" }
            remember(result)
        }
    }

    /** Retry only a concrete authenticated API rejection, never an entire operation or I/O failure. */
    suspend fun <T> execute(
        authorization: String,
        replayable: Boolean = true,
        call: suspend (authorization: String) -> Response<T>,
    ): Response<T> {
        currentCoroutineContext().ensureActive()
        val token = tokenFor(authorization)
        var response: Response<T>? = null
        try {
            response = call("Bearer $token")
            currentCoroutineContext().ensureActive()
            checkCurrent()
            if (response.code() != 401 || !replayable) return response
            val refreshed = refreshRejected(token) ?: return response
            response.errorBody()?.close()
            (response.body() as? okhttp3.ResponseBody)?.close()
            response = null
            currentCoroutineContext().ensureActive()
            checkCurrent()
            return call("Bearer $refreshed").also {
                response = it
                currentCoroutineContext().ensureActive()
                checkCurrent()
            }
        } catch (error: Throwable) {
            response?.errorBody()?.close()
            (response?.body() as? okhttp3.ResponseBody)?.close()
            throw error
        }
    }
}
