package takagi.ru.monica.bitwarden

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.service.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BitwardenAccountMutationCryptoTest {
    private val password = " Original password 空格 42! "
    private val next = " New password 保留 987! "
    private val email = "synthetic@example.test"
    private val userKey = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32) { 31 }, ByteArray(32) { 73 })
    private val oldKdf = BitwardenAccountMutationKdf(0, 5000)
    private val protocol = BitwardenAccountMutationProtocol(passwordAllowed = { it.length >= 12 })
    private fun snapshot(hintPresent: Boolean = true): BitwardenAccountMutationSnapshot {
        val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, oldKdf.iterations)
        val stretched = BitwardenCrypto.stretchMasterKey(master)
        return try { BitwardenAccountMutationSnapshot(
            BitwardenAccountMutationBinding(1, "user", email, "env", "revision", null, 1, 1, "stamp"),
            oldKdf, email, BitwardenCrypto.encrypt(userKey.encKey + userKey.macKey, stretched), true, false, true,
            "Synthetic", hintPresent, "Original hint", true)
        } finally { master.fill(0); stretched.clear() }
    }
    @Test fun passwordChangeRewrapsTheSameKeyAndPreservesExactPasswordBytes() {
        val prepared = protocol.prepare(snapshot(), BitwardenAccountMutationAction.Password(next, next,
            BitwardenAccountMutationHint.Preserve), password, userKey)
        val request = prepared.consume()
        assertEquals("accounts/password", request.relativePath)
        assertEquals("Original hint", request.body["masterPasswordHint"]!!.jsonPrimitive.content)
        assertFalse(request.body.toString().contains(password)); assertFalse(request.body.toString().contains(next))
        val master = BitwardenCrypto.deriveMasterKeyPbkdf2(next, email, oldKdf.iterations)
        val key = BitwardenCrypto.stretchMasterKey(master)
        try {
            assertArrayEquals(userKey.encKey + userKey.macKey, BitwardenCrypto.decrypt(prepared.record.newWrappedUserKey, key))
            assertEquals(BitwardenCrypto.deriveMasterPasswordHash(master, next), request.body["newMasterPasswordHash"]!!.jsonPrimitive.content)
            assertEquals(request.body["key"], request.body["unlockData"]!!.jsonObject["masterKeyWrappedUserKey"])
            assertNotEquals(prepared.record.oldWrappedUserKey, prepared.record.newWrappedUserKey)
        } finally { master.fill(0); key.clear() }
    }
    @Test fun wrongPasswordAndDifferentUserKeyFailBeforeRequestCreation() {
        assertTrue(runCatching { protocol.prepare(snapshot(), BitwardenAccountMutationAction.Delete, password.trim(), userKey) }.isFailure)
        val different = BitwardenCrypto.SymmetricCryptoKey(ByteArray(32), ByteArray(32))
        assertTrue(runCatching { protocol.prepare(snapshot(), BitwardenAccountMutationAction.Delete, password, different) }.isFailure)
    }
    @Test fun newKdfUsesItsOwnEndpointAndKeepsCipherKeyUnchanged() {
        val kdf = BitwardenAccountMutationKdf(0, 600000)
        val prepared = protocol.prepare(snapshot(), BitwardenAccountMutationAction.Kdf(kdf), password, userKey)
        val request = prepared.consume()
        assertEquals("accounts/kdf", request.relativePath)
        assertFalse(request.body.containsKey("masterPasswordHint"))
        assertEquals(JsonPrimitive(600000), request.body["authenticationData"]!!.jsonObject["kdf"]!!.jsonObject["iterations"])
        val master = BitwardenCrypto.deriveMasterKeyPbkdf2(password, email, kdf.iterations)
        val stretched = BitwardenCrypto.stretchMasterKey(master)
        try { assertArrayEquals(userKey.encKey + userKey.macKey, BitwardenCrypto.decrypt(prepared.record.newWrappedUserKey, stretched)) }
        finally { master.fill(0); stretched.clear() }
    }
    @Test fun missingHintRequiresExplicitReplacementAndClearingWritesNull() {
        val unknown = snapshot(false)
        assertTrue(runCatching { protocol.prepare(unknown, BitwardenAccountMutationAction.Password(next, next,
            BitwardenAccountMutationHint.Preserve), password, userKey) }.isFailure)
        val prepared = protocol.prepare(unknown, BitwardenAccountMutationAction.Password(next, next,
            BitwardenAccountMutationHint.Replace(null)), password, userKey)
        assertEquals(JsonNull, prepared.consume().body["masterPasswordHint"])
    }
    @Test fun deleteAndHintDoNotGenerateReplacementUserKeys() {
        listOf(BitwardenAccountMutationAction.Delete, BitwardenAccountMutationAction.HintWithPassword("replacement")).forEach {
            val prepared = protocol.prepare(snapshot(), it, password, userKey)
            assertEquals(prepared.record.oldWrappedUserKey, prepared.record.newWrappedUserKey)
            if (it == BitwardenAccountMutationAction.Delete) assertEquals("DELETE", prepared.consume().method)
        }
    }
    @Test fun weakNewKdfIsRejectedWithoutChangingOldLoginSupport() {
        assertTrue(runCatching { protocol.prepare(snapshot(), BitwardenAccountMutationAction.Kdf(BitwardenAccountMutationKdf(0, 5000)), password, userKey) }.isFailure)
        assertNotNull(protocol.prepare(snapshot(), BitwardenAccountMutationAction.Delete, password, userKey))
    }
}
