package takagi.ru.monica.security

import org.junit.Assert.*
import org.junit.Test

class RecoveryAuthorizationTest {
    private var monotonic = 1_000L
    private var wall = 100_000L
    private val authority = RecoveryAuthorization({ monotonic }, { wall })
    private val binding = mapOf<String, Any?>("store" to "vault-A", "password" to "old", "answers" to "v1")
    private fun challenge() = authority.begin(binding, 1, 2, mapOf("answer" to "v2"))

    @Test fun answersAndFabricatedPermitCannotAuthorize() {
        challenge()
        assertFalse(authority.isCurrent(RecoveryPermit(), binding, 1, 2))
        assertFalse(authority.isCurrent(null, binding, 1, 2))
    }
    @Test fun correctConfirmationIsSingleUse() {
        val request = challenge()
        val proof = checkNotNull(authority.confirm(request, binding, 1, 2))
        assertNull(authority.confirm(request, binding, 1, 2))
        assertEquals(mapOf("answer" to "v2"), authority.updates(proof, binding, 1, 2))
        assertTrue(authority.consume(proof, binding, 1, 2))
        assertFalse(authority.consume(proof, binding, 1, 2))
        assertNull(authority.confirm(request, binding, 1, 2))
    }
    @Test fun wrongRequestCannotConfirmOrCancelReplacement() {
        val first = challenge()
        val second = challenge()
        assertNull(authority.confirm(first, binding, 1, 2))
        authority.cancel(first)
        assertNotNull(authority.confirm(second, binding, 1, 2))
    }
    @Test fun differentStorePasswordOrAnswerRevokes() {
        for (key in binding.keys) {
            val proof = checkNotNull(authority.confirm(challenge(), binding, 1, 2))
            assertFalse(authority.isCurrent(proof, binding + (key to "changed"), 1, 2))
            assertFalse(authority.isCurrent(proof, binding, 1, 2))
        }
    }
    @Test fun lockOrCredentialEpochRevokes() {
        val first = checkNotNull(authority.confirm(challenge(), binding, 1, 2))
        assertFalse(authority.isCurrent(first, binding, 2, 2))
        val second = checkNotNull(authority.confirm(challenge(), binding, 1, 2))
        assertFalse(authority.isCurrent(second, binding, 1, 3))
    }
    @Test fun cancelBeforeAndAfterConfirmationRevokes() {
        val request = challenge()
        authority.cancel(request)
        assertNull(authority.confirm(request, binding, 1, 2))
        val proof = checkNotNull(authority.confirm(challenge(), binding, 1, 2))
        authority.cancel(proof)
        assertFalse(authority.isCurrent(proof, binding, 1, 2))
    }
    @Test fun expirationAndEitherClockRollbackRevokes() {
        for (delta in listOf(-1L, 300_000L)) {
            val request = challenge()
            monotonic += delta
            assertNull(authority.confirm(request, binding, 1, 2))
        }
        val request = challenge()
        wall--
        assertNull(authority.confirm(request, binding, 1, 2))
    }
}
