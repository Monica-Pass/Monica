package takagi.ru.monica.security

/** Opaque identities; neither a route flag nor an existing unlocked session issues a permit. */
class RecoveryPermit internal constructor()
internal class RecoveryChallenge

/** Only the current request can advance. Cancellation and consumption remove all retained state. */
internal class RecoveryAuthorization(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
) {
    private data class Pending(
        val challenge: RecoveryChallenge,
        val binding: Map<String, Any?>,
        val startedElapsed: Long,
        val startedWall: Long,
        val generation: Long,
        val epoch: Long,
        val answerUpdates: Map<String, String>,
        var permit: RecoveryPermit? = null,
    )
    private var pending: Pending? = null
    @Synchronized fun begin(binding: Map<String, Any?>, generation: Long, epoch: Long,
                            updates: Map<String, String>): RecoveryChallenge {
        val challenge = RecoveryChallenge()
        pending = Pending(challenge, binding.toMap(), elapsed(), wall(), generation, epoch, updates.toMap())
        return challenge
    }
    private fun current(binding: Map<String, Any?>, generation: Long, epoch: Long): Pending? {
        val p = pending ?: return null
        val elapsedAge = elapsed() - p.startedElapsed
        val wallAge = wall() - p.startedWall
        if (p.binding != binding || p.generation != generation || p.epoch != epoch ||
            elapsedAge !in 0 until 5 * 60_000L || wallAge !in 0 until 5 * 60_000L) {
            pending = null
            return null
        }
        return p
    }
    @Synchronized fun confirm(challenge: RecoveryChallenge, binding: Map<String, Any?>,
                              generation: Long, epoch: Long): RecoveryPermit? {
        val p = current(binding, generation, epoch) ?: return null
        if (p.challenge !== challenge || p.permit != null) return null
        return RecoveryPermit().also { p.permit = it }
    }
    @Synchronized fun isCurrent(permit: RecoveryPermit?, binding: Map<String, Any?>,
                                generation: Long, epoch: Long): Boolean =
        permit != null && current(binding, generation, epoch)?.permit === permit
    @Synchronized fun updates(permit: RecoveryPermit, binding: Map<String, Any?>,
                              generation: Long, epoch: Long): Map<String, String>? =
        current(binding, generation, epoch)?.takeIf { it.permit === permit }?.answerUpdates
    @Synchronized fun consume(permit: RecoveryPermit, binding: Map<String, Any?>,
                              generation: Long, epoch: Long): Boolean {
        if (!isCurrent(permit, binding, generation, epoch)) return false
        pending = null
        return true
    }
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun cancel(challenge: RecoveryChallenge) {
        if (pending?.challenge === challenge) pending = null
    }
    @Synchronized fun cancel(permit: RecoveryPermit) {
        if (pending?.permit === permit) pending = null
    }
}
