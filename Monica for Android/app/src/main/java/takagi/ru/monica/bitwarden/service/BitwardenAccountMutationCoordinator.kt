package takagi.ru.monica.bitwarden.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The authenticated adapter closes response bodies and never retries a mutation automatically. */
internal class BitwardenAccountMutationHttpResult(
    val status: Int,
    val verifiedProfile: JsonObject? = null,
    // Set only for a known protocol rejection that happened before mutation, never for generic 4xx/5xx.
    val rejectedBeforeMutation: Boolean = false,
) {
    override fun toString() = "BitwardenAccountMutationHttpResult(status=$status, redacted)"
}

internal sealed interface BitwardenAccountMutationOutcome {
    /** No mutation was sent; restore the journal before deciding whether the account can resume. */
    data object NotSent : BitwardenAccountMutationOutcome
    class Confirmed(val record: BitwardenAccountMutationRecord) : BitwardenAccountMutationOutcome
    class Rejected(val record: BitwardenAccountMutationRecord) : BitwardenAccountMutationOutcome
    data object Uncertain : BitwardenAccountMutationOutcome
}

/**
 * One-shot dispatch only. Root owns the account-wide mutex, durable journal gate on process startup,
 * user confirmation, UI, local archive and recovery publication. freeze must gate all normal account
 * operations without invalidating this exact one-shot operation's identity/generation capability.
 * requireCurrent checks identity, transport and original app/account generation (not the normal
 * account-operation gate now frozen by this intent). No callback may erase existing account data.
 */
internal class BitwardenAccountMutationCoordinator(
    private val store: BitwardenAccountMutationIntentStore,
    private val execute: suspend (BitwardenAccountMutationRequest) -> BitwardenAccountMutationHttpResult,
    private val requireCurrent: suspend (BitwardenAccountMutationBinding) -> Unit,
    private val freeze: suspend (BitwardenAccountMutationRecord) -> Unit,
    private val protocol: BitwardenAccountMutationProtocol = BitwardenAccountMutationProtocol(),
) {
    suspend fun send(prepared: BitwardenAccountMutationPrepared): BitwardenAccountMutationOutcome {
        val request = prepared.consume()
        var sending: BitwardenAccountMutationRecord? = null
        var dispatched = false
        try {
            currentCoroutineContext().ensureActive()
            requireCurrent(request.binding)
            val staged = store.stage(prepared.record)
            val active = store.transition(staged, BitwardenAccountMutationPhase.SENDING)
            sending = active
            freeze(active)
            currentCoroutineContext().ensureActive()
            requireCurrent(request.binding)
            dispatched = true
            val response = execute(request)
            // Recording an outcome is permitted after lock/replacement; it publishes no keys/session.
            // The store compares the complete original record and never touches a newer operation.
            return withContext(NonCancellable) {
                val applied = response.status in 200..299 &&
                    (active.kind != BitwardenAccountMutationKind.HINT_PROFILE ||
                        response.verifiedProfile?.let { protocol.hintReadbackMatches(active, it) } == true)
                when {
                    applied -> BitwardenAccountMutationOutcome.Confirmed(store.transition(active, BitwardenAccountMutationPhase.CONFIRMED))
                    response.status !in 200..299 && response.rejectedBeforeMutation ->
                        BitwardenAccountMutationOutcome.Rejected(store.transition(active, BitwardenAccountMutationPhase.REJECTED))
                    else -> {
                        markUncertain(active)
                        BitwardenAccountMutationOutcome.Uncertain
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { sending?.let(::markUncertain) }
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable) { sending?.let(::markUncertain) }
            return if (dispatched) BitwardenAccountMutationOutcome.Uncertain else BitwardenAccountMutationOutcome.NotSent
        } finally {
            prepared.discard()
        }
    }

    private fun markUncertain(expected: BitwardenAccountMutationRecord) {
        // A failed write leaves the durable SENDING marker. A confirmed/newer record is not downgraded.
        runCatching { store.transition(expected, BitwardenAccountMutationPhase.UNCERTAIN) }
    }
}
