package takagi.ru.monica.bitwarden.service

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque tokens are held only by the caller's encrypted preferences, never by this policy. */
internal class BitwardenRememberedTwoFactorStore(
    private val readSealed: (String) -> String?,
    private val writeSealed: (String, String) -> Boolean,
    private val removeSealed: (String) -> Boolean,
) {
    fun read(scope: String): String? = readSealed(PREFIX + scope)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH }

    fun save(scope: String, remember: Boolean, token: String?): Boolean =
        if (remember && !token.isNullOrBlank() && token.length <= MAX_TOKEN_LENGTH) {
            writeSealed(PREFIX + scope, token)
        } else {
            clear(scope)
        }

    fun clear(scope: String): Boolean = removeSealed(PREFIX + scope)

    companion object {
        private const val PREFIX = "remembered_two_factor_v1_"
        private const val MAX_TOKEN_LENGTH = 64 * 1024

        /** Inputs must already use the account's canonical server and email, never its display name. */
        fun scope(normalizedServer: String, canonicalEmail: String): String {
            val server = normalizedServer.toByteArray(Charsets.UTF_8)
            val email = canonicalEmail.toByteArray(Charsets.UTF_8)
            val framed = ByteBuffer.allocate(8 + server.size + email.size)
                .putInt(server.size).put(server).putInt(email.size).put(email).array()
            return MessageDigest.getInstance("SHA-256").digest(framed)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

internal enum class BitwardenStampDecision { FIRST_OBSERVATION, MATCH, CHANGED, MISSING_REMOTE }

internal object BitwardenSecurityStampPolicy {
    fun compare(previous: String?, remote: String?): BitwardenStampDecision = when {
        remote.isNullOrBlank() -> BitwardenStampDecision.MISSING_REMOTE
        previous.isNullOrBlank() -> BitwardenStampDecision.FIRST_OBSERVATION
        previous == remote -> BitwardenStampDecision.MATCH
        else -> BitwardenStampDecision.CHANGED
    }
}

/** Serialize publishing with revocation so an old HTTP completion cannot reopen a logged-out account. */
internal object BitwardenAuthSessionGate {
    private class State(val mutex: Mutex = Mutex(), var epoch: Long = 0)
    private val states = ConcurrentHashMap<String, State>()

    suspend fun begin(scope: String): Long {
        val state = states.getOrPut(scope) { State() }
        return state.mutex.withLock { ++state.epoch }
    }

    suspend fun <T> publish(scope: String, expectedEpoch: Long, action: suspend () -> T): T? {
        val state = states.getOrPut(scope) { State() }
        return state.mutex.withLock {
            if (state.epoch != expectedEpoch) null else action()
        }
    }

    suspend fun revoke(scope: String, action: suspend () -> Unit) {
        val state = states.getOrPut(scope) { State() }
        state.mutex.withLock {
            ++state.epoch
            action()
        }
    }

    suspend fun <T> inspect(scope: String, action: suspend () -> T): T {
        val state = states.getOrPut(scope) { State() }
        return state.mutex.withLock { action() }
    }

    suspend fun revokeIf(scope: String, predicate: suspend () -> Boolean, action: suspend () -> Unit): Boolean {
        val state = states.getOrPut(scope) { State() }
        return state.mutex.withLock {
            if (!predicate()) return@withLock false
            currentCoroutineContext().ensureActive()
            // Removing runtime keys can itself cancel the requesting UI via its session flow.
            // Once committed, finish only this local security revocation before releasing the
            // account gate; a new login must not race the durable credential removal.
            withContext(NonCancellable) {
                ++state.epoch
                action()
                true
            }
        }
    }
}
