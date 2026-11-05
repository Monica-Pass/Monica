package takagi.ru.monica.bitwarden.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenAccountSessionGuardTest {
    private val entries = mutableMapOf<String, String>()
    private val store = BitwardenRememberedTwoFactorStore(
        readSealed = entries::get,
        writeSealed = { key, value -> entries[key] = value; true },
        removeSealed = { key -> entries.remove(key); true },
    )

    private fun scope(server: String = "https://vault.bitwarden.com", email: String = "a@example.test") =
        BitwardenRememberedTwoFactorStore.scope(server, email)

    @Test fun rememberedTokenIsIsolatedBetweenServersAndAccounts() {
        store.save(scope(), true, "opaque-token")
        assertEquals("opaque-token", store.read(scope()))
        assertNull(store.read(scope(server = "https://vault.bitwarden.eu")))
        assertNull(store.read(scope(email = "b@example.test")))
    }

    @Test fun scopeUsesFramingRatherThanAmbiguousSeparators() {
        assertNotEquals(scope(server = "a|b", email = "c"), scope(server = "a", email = "b|c"))
        assertEquals(64, scope().length)
        assertFalse(scope().contains("example.test"))
    }

    @Test fun choiceDefaultsToNoRememberAndRemovesPreviousToken() {
        store.save(scope(), true, "old")
        assertTrue(store.save(scope(), false, "new-server-token"))
        assertNull(store.read(scope()))
    }

    @Test fun tokenContentsAreOpaqueAndNotTrimmedOrLoggedIntoKeys() {
        val token = "opaque +/=_ token"
        assertTrue(store.save(scope(), true, token))
        assertEquals(token, store.read(scope()))
        assertFalse(entries.keys.single().contains(token))
    }

    @Test fun missingOrOversizedResponseCannotKeepAnOldRememberedToken() {
        store.save(scope(), true, "old")
        store.save(scope(), true, null)
        assertNull(store.read(scope()))
        store.save(scope(), true, "x".repeat(64 * 1024 + 1))
        assertNull(store.read(scope()))
    }

    @Test fun clearingOneAccountDoesNotClearAnotherRememberedAccount() {
        store.save(scope(), true, "first")
        store.save(scope(email = "b@example.test"), true, "second")
        store.clear(scope())
        assertNull(store.read(scope()))
        assertEquals("second", store.read(scope(email = "b@example.test")))
    }

    @Test fun unavailableSealedStoreDoesNotClaimRememberWasSaved() {
        val unavailable = BitwardenRememberedTwoFactorStore({ null }, { _, _ -> false }, { false })
        assertFalse(unavailable.save(scope(), true, "token"))
        assertNull(unavailable.read(scope()))
    }

    @Test fun firstStampObservationIsNotAChangedSession() {
        assertEquals(BitwardenStampDecision.FIRST_OBSERVATION, BitwardenSecurityStampPolicy.compare(null, "s1"))
        assertEquals(BitwardenStampDecision.FIRST_OBSERVATION, BitwardenSecurityStampPolicy.compare("", "s1"))
    }

    @Test fun changedStampIsComparedExactly() {
        assertEquals(BitwardenStampDecision.MATCH, BitwardenSecurityStampPolicy.compare("s1", "s1"))
        assertEquals(BitwardenStampDecision.CHANGED, BitwardenSecurityStampPolicy.compare("s1", "s2"))
        assertEquals(BitwardenStampDecision.CHANGED, BitwardenSecurityStampPolicy.compare("s1", "S1"))
    }

    @Test fun missingRemoteStampStopsApprovalWithoutPretendingItChanged() {
        assertEquals(BitwardenStampDecision.MISSING_REMOTE, BitwardenSecurityStampPolicy.compare("s1", null))
        assertEquals(BitwardenStampDecision.MISSING_REMOTE, BitwardenSecurityStampPolicy.compare(null, null))
    }

    @Test fun lateLoginCompletionCannotPublishAfterLogout() = runBlocking {
        val account = "late-login"
        val epoch = BitwardenAuthSessionGate.begin(account)
        val response = CompletableDeferred<Unit>()
        var published = false
        val login = launch {
            response.await()
            BitwardenAuthSessionGate.publish(account, epoch) { published = true }
        }
        BitwardenAuthSessionGate.revoke(account) { }
        response.complete(Unit)
        login.join()
        assertFalse(published)
    }

    @Test fun replacingAnAttemptPublishesOnlyTheLatestAuthentication() = runBlocking {
        val account = "replacement-login"
        val oldEpoch = BitwardenAuthSessionGate.begin(account)
        val currentEpoch = BitwardenAuthSessionGate.begin(account)
        assertNull(BitwardenAuthSessionGate.publish(account, oldEpoch) { "old" })
        assertEquals("current", BitwardenAuthSessionGate.publish(account, currentEpoch) { "current" })
    }

    @Test fun staleEditorCannotReplaceAnAlreadyRegisteredAuthentication() = runBlocking {
        val account = "stale-editor-login"
        val current = BitwardenAuthSessionGate.begin(account)
        var registered = false
        assertNull(BitwardenAuthSessionGate.beginIf(account, { false }) { registered = true })
        assertFalse(registered)
        assertEquals("current", BitwardenAuthSessionGate.publish(account, current) { "current" })
        val replacement = BitwardenAuthSessionGate.beginIf(account, { true }) { registered = true }
        assertTrue(registered)
        assertNotNull(replacement)
        assertNull(BitwardenAuthSessionGate.publish(account, current) { "obsolete" })
    }

    @Test fun revokingAnotherAccountDoesNotCancelThisLogin() = runBlocking {
        val epoch = BitwardenAuthSessionGate.begin("account-one")
        BitwardenAuthSessionGate.revoke("account-two") { }
        assertEquals("valid", BitwardenAuthSessionGate.publish("account-one", epoch) { "valid" })
    }

    @Test fun staleTokenCannotRevokeTheNewlyAuthenticatedSession() = runBlocking {
        val account = "conditional-revocation"
        val epoch = BitwardenAuthSessionGate.begin(account)
        var credentialsCleared = false
        assertFalse(BitwardenAuthSessionGate.revokeIf(account, { false }) { credentialsCleared = true })
        assertFalse(credentialsCleared)
        assertEquals("valid", BitwardenAuthSessionGate.publish(account, epoch) { "valid" })
        assertTrue(BitwardenAuthSessionGate.revokeIf(account, { true }) { credentialsCleared = true })
        assertTrue(credentialsCleared)
        assertNull(BitwardenAuthSessionGate.publish(account, epoch) { "invalid" })
    }

    @Test fun committedRevocationPersistsCredentialRemovalAfterItsCallerIsCancelled() = runBlocking {
        val account = "cancelled-local-revocation"
        val oldEpoch = BitwardenAuthSessionGate.begin(account)
        val runtimeRevoked = CompletableDeferred<Unit>()
        val continuePersistence = CompletableDeferred<Unit>()
        var storedToken: String? = "synthetic-old-token"
        val caller = launch {
            BitwardenAuthSessionGate.revokeIf(account, { true }) {
                // Production key removal publishes a generation that cancels the UI caller.
                runtimeRevoked.complete(Unit)
                continuePersistence.await()
                storedToken = null
            }
        }
        runtimeRevoked.await()
        caller.cancel()
        continuePersistence.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertNull(storedToken)
        assertNull(BitwardenAuthSessionGate.publish(account, oldEpoch) { "late old-token success" })
        val newEpoch = BitwardenAuthSessionGate.begin(account)
        assertEquals("new login", BitwardenAuthSessionGate.publish(account, newEpoch) { "new login" })
    }

}
