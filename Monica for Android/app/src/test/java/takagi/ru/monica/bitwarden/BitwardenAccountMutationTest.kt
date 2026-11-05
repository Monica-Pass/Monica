package takagi.ru.monica.bitwarden

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationBinding
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationCoordinator
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationHttpResult
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationIntentStore
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKdf
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationKind
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationOutcome
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationPhase
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationPrepared
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationProtocol
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationRecord
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationRequest
import takagi.ru.monica.bitwarden.service.BitwardenAccountMutationStored

/**
 * Pure journal/coordinator/readback tests. Requests are constructed directly to avoid KDF work;
 * the context-bound fake protection is not disk durability or authenticated-encryption evidence.
 */
class BitwardenAccountMutationTest {
    private fun binding() = BitwardenAccountMutationBinding(
        vaultId = 41, userId = "synthetic-user", canonicalEmail = "synthetic@example.invalid",
        environmentFingerprint = "synthetic-environment", transportRevision = "synthetic-revision",
        transportSecretRef = "synthetic-secret-ref", accountGeneration = 3, appGeneration = 7,
        securityStamp = "synthetic-stamp",
    )

    private fun record(
        kind: BitwardenAccountMutationKind = BitwardenAccountMutationKind.PASSWORD,
        hint: String? = null,
    ) = BitwardenAccountMutationRecord(
        operationId = "00000000-0000-0000-0000-000000000041", binding = binding(), kind = kind,
        phase = BitwardenAccountMutationPhase.PREPARED,
        oldKdf = BitwardenAccountMutationKdf(0, 600_000),
        newKdf = BitwardenAccountMutationKdf(0, 600_000),
        oldWrappedUserKey = OLD_WRAPPER,
        newWrappedUserKey = if (kind == BitwardenAccountMutationKind.PASSWORD) NEW_WRAPPER else OLD_WRAPPER,
        requestedHint = hint, originalProfileName = "Synthetic account",
    )

    private fun prepared(record: BitwardenAccountMutationRecord = record()): BitwardenAccountMutationPrepared {
        val (method, path) = when (record.kind) {
            BitwardenAccountMutationKind.DELETE -> "DELETE" to "accounts"
            BitwardenAccountMutationKind.HINT_PROFILE -> "PUT" to "accounts/profile"
            BitwardenAccountMutationKind.KDF -> "POST" to "accounts/kdf"
            else -> "POST" to "accounts/password"
        }
        val body = buildJsonObject {
            if (record.kind == BitwardenAccountMutationKind.HINT_PROFILE) {
                put("name", record.originalProfileName!!)
                put("masterPasswordHint", record.requestedHint?.let(::JsonPrimitive) ?: JsonNull)
            } else {
                put(if (record.kind == BitwardenAccountMutationKind.DELETE) "MasterPasswordHash" else "masterPasswordHash", PROOF)
            }
        }
        return BitwardenAccountMutationPrepared(record, BitwardenAccountMutationRequest(record.binding, method, path, body))
    }

    private fun profile(record: BitwardenAccountMutationRecord, hint: JsonElement? =
        record.requestedHint?.let(::JsonPrimitive) ?: JsonNull) = buildJsonObject {
        put("id", record.binding.userId)
        put("email", record.binding.canonicalEmail)
        put("name", record.originalProfileName!!)
        if (hint != null) put("masterPasswordHint", hint)
    }

    private fun stored(store: BitwardenAccountMutationIntentStore) =
        (store.restore(41) as BitwardenAccountMutationStored.Present).record

    private class MemoryJournal {
        val localData = mapOf("vault:41" to "original-encrypted-vault", "queue:41" to "original-pending-write")
        val entries = localData.toMutableMap()
        val sealedInputs = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var writes = 0
        private val envelopes = mutableMapOf<String, Pair<String, String>>()

        fun store(
            read: (String) -> String? = entries::get,
            write: (String, String) -> Boolean = { key, value -> entries[key] = value; true },
        ) = BitwardenAccountMutationIntentStore(
            read = read,
            write = { key, value -> writes++; write(key, value) },
            remove = { key -> removed += key; entries.remove(key); true },
            seal = { key, plaintext ->
                sealedInputs += plaintext
                val envelope = "fake-protected-${sealedInputs.size}"
                envelopes[envelope] = key to plaintext
                envelope
            },
            open = { key, envelope ->
                val (context, plaintext) = checkNotNull(envelopes[envelope])
                check(context == key)
                plaintext
            },
        )

        fun assertLocalDataPreserved() {
            for ((key, value) in localData) assertEquals(value, entries[key])
        }
    }

    @Test fun durableReadbackPrecedesFreezeAndDispatchAndRestoresWithoutRequestProof() = runBlocking {
        val memory = MemoryJournal()
        val store = memory.store()
        val prepared = prepared()
        var frozen = false
        var sends = 0
        val coordinator = BitwardenAccountMutationCoordinator(store,
            execute = {
                assertTrue(frozen)
                assertEquals(BitwardenAccountMutationPhase.SENDING, stored(store).phase)
                sends++
                BitwardenAccountMutationHttpResult(204)
            },
            requireCurrent = { assertEquals(prepared.record.binding, it) },
            freeze = {
                assertEquals(it, stored(memory.store()))
                assertEquals(BitwardenAccountMutationPhase.SENDING, it.phase)
                frozen = true
            })

        val outcome = coordinator.send(prepared) as BitwardenAccountMutationOutcome.Confirmed
        assertEquals(outcome.record, stored(memory.store()))
        assertEquals(BitwardenAccountMutationPhase.CONFIRMED, outcome.record.phase)
        assertEquals(OLD_WRAPPER, outcome.record.oldWrappedUserKey)
        assertEquals(NEW_WRAPPER, outcome.record.newWrappedUserKey)
        for (plaintext in memory.sealedInputs) {
            assertFalse(plaintext.contains(PROOF))
            assertFalse(plaintext.contains("masterPasswordHash", ignoreCase = true))
        }
        assertTrue(runCatching { coordinator.send(prepared) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, sends)
        assertTrue(memory.removed.isEmpty())
        memory.assertLocalDataPreserved()
    }

    @Test fun failedCommitLostWriteAndUnreadableReadbackCannotDispatch() = runBlocking {
        for (failure in listOf("failed-commit", "lost-write", "corrupt-readback")) {
            val memory = MemoryJournal()
            val store = memory.store(write = { key, _ ->
                if (failure == "corrupt-readback") memory.entries[key] = "corrupt"
                failure != "failed-commit"
            })
            var sends = 0
            var freezes = 0
            val coordinator = BitwardenAccountMutationCoordinator(store,
                execute = { sends++; BitwardenAccountMutationHttpResult(204) },
                requireCurrent = {}, freeze = { freezes++ })
            assertSame(failure, BitwardenAccountMutationOutcome.NotSent, coordinator.send(prepared()))
            assertEquals(failure, 0, sends)
            assertEquals(failure, 0, freezes)
            val expected = if (failure == "corrupt-readback") BitwardenAccountMutationStored.Unreadable else BitwardenAccountMutationStored.Absent
            assertSame(expected, store.restore(41))
            assertTrue(memory.removed.isEmpty())
            memory.assertLocalDataPreserved()
        }
    }

    @Test fun unreadableIntentAndReadFailureBlockNewIntentRatherThanLookingAbsent() {
        val memory = MemoryJournal()
        memory.entries[JOURNAL_KEY] = "unrecognized-envelope"
        for (store in listOf(memory.store(), memory.store(read = { throw IOException("synthetic read failure") }))) {
            assertSame(BitwardenAccountMutationStored.Unreadable, store.restore(41))
            assertTrue(runCatching { store.stage(record()) }.isFailure)
        }
        assertEquals(0, memory.writes)
        assertTrue(memory.removed.isEmpty())
    }

    @Test fun relocatedIntentAndChangedBindingOrOperationCannotAdvanceOrRemoveOriginal() {
        val memory = MemoryJournal()
        val store = memory.store()
        val original = store.stage(record())
        memory.entries["bitwarden_account_mutation_v1_42"] = memory.entries.getValue(JOURNAL_KEY)
        assertSame(BitwardenAccountMutationStored.Unreadable, memory.store().restore(42))
        val changed = listOf(
            original.copy(binding = original.binding.copy(userId = "other-user")),
            original.copy(binding = original.binding.copy(accountGeneration = 4)),
            original.copy(binding = original.binding.copy(transportRevision = "other-revision")),
            original.copy(operationId = "00000000-0000-0000-0000-000000000042"),
        )
        for (stale in changed) {
            assertTrue(runCatching { store.transition(stale, BitwardenAccountMutationPhase.SENDING) }.isFailure)
            assertFalse(store.discardUnsentOrRejected(stale))
        }
        assertTrue(runCatching { store.stage(changed.last()) }.isFailure)
        assertEquals(original, stored(store))
        assertTrue(memory.removed.isEmpty())
    }

    @Test fun hintReadbackRequiresOriginalIdentityNameAndExplicitNullOrExactString() {
        val protocol = BitwardenAccountMutationProtocol()
        val clear = record(BitwardenAccountMutationKind.HINT_PROFILE)
        assertTrue(protocol.hintReadbackMatches(clear, profile(clear)))
        assertFalse(protocol.hintReadbackMatches(clear, profile(clear, hint = null)))
        for ((name, changed) in listOf("id" to "another-user", "email" to "another@example.invalid", "name" to "Changed name")) {
            assertFalse(protocol.hintReadbackMatches(clear, JsonObject(profile(clear) + (name to JsonPrimitive(changed)))))
        }
        val replace = record(BitwardenAccountMutationKind.HINT_PROFILE, "  exact hint  ")
        assertTrue(protocol.hintReadbackMatches(replace, profile(replace)))
        assertFalse(protocol.hintReadbackMatches(replace, profile(replace, JsonPrimitive("exact hint"))))
        assertFalse(protocol.hintReadbackMatches(replace, profile(replace, JsonObject(emptyMap()))))
    }

    @Test fun nonStringHintCannotConfirmItsMatchingTextRepresentation() {
        val protocol = BitwardenAccountMutationProtocol()
        for ((text, malformed) in listOf("true" to JsonPrimitive(true), "1" to JsonPrimitive(1))) {
            val record = record(BitwardenAccountMutationKind.HINT_PROFILE, text)
            assertFalse("A JSON boolean/number is not a password-hint string", protocol.hintReadbackMatches(record, profile(record, malformed)))
        }
    }

    @Test fun recoveryRequiresOriginalServerIdentityTransportAndBothUserKeyHalves() {
        val protocol = BitwardenAccountMutationProtocol()
        val record = record()
        val key = SymmetricCryptoKey(ByteArray(32) { 11 }, ByteArray(32) { 27 })
        val sameKey = SymmetricCryptoKey(key.encKey.copyOf(), key.macKey.copyOf())
        assertTrue(protocol.recoveryKeyMatches(record, record.binding, sameKey, key))
        val changedBindings = listOf(
            record.binding.copy(vaultId = 42), record.binding.copy(userId = "new-user-same-email"),
            record.binding.copy(canonicalEmail = "another@example.invalid"),
            record.binding.copy(environmentFingerprint = "other-environment"),
            record.binding.copy(transportRevision = "other-revision"), record.binding.copy(transportSecretRef = "other-ref"),
        )
        for (changed in changedBindings) assertFalse(protocol.recoveryKeyMatches(record, changed, sameKey, key))
        assertFalse(protocol.recoveryKeyMatches(record, record.binding, SymmetricCryptoKey(ByteArray(32) { 12 }, key.macKey.copyOf()), key))
        assertFalse(protocol.recoveryKeyMatches(record, record.binding, SymmetricCryptoKey(key.encKey.copyOf(), ByteArray(32) { 28 }), key))
    }

    @Test fun genericHttpErrorsAndLostResponsesRetainUncertainIntentWithoutRetry() = runBlocking {
        for (status in listOf(400, 401, 404, 409, 500, null)) {
            val memory = MemoryJournal()
            val store = memory.store()
            var sends = 0
            val coordinator = BitwardenAccountMutationCoordinator(store,
                execute = {
                    sends++
                    if (status == null) throw IOException("synthetic lost response")
                    BitwardenAccountMutationHttpResult(status)
                }, requireCurrent = {}, freeze = {})
            assertSame(BitwardenAccountMutationOutcome.Uncertain, coordinator.send(prepared()))
            assertEquals(BitwardenAccountMutationPhase.UNCERTAIN, stored(memory.store()).phase)
            assertSame(BitwardenAccountMutationOutcome.NotSent, coordinator.send(prepared()))
            assertEquals(1, sends)
            assertTrue(memory.removed.isEmpty())
            memory.assertLocalDataPreserved()
        }
    }

    @Test fun profileSuccessWithoutVerifiedExactReadbackRemainsUncertain() = runBlocking {
        val record = record(BitwardenAccountMutationKind.HINT_PROFILE, "desired hint")
        for (readback in listOf(null, profile(record, hint = null), profile(record, JsonPrimitive("old hint")))) {
            val store = MemoryJournal().store()
            val coordinator = BitwardenAccountMutationCoordinator(store,
                execute = { BitwardenAccountMutationHttpResult(200, verifiedProfile = readback) },
                requireCurrent = {}, freeze = {})
            assertSame(BitwardenAccountMutationOutcome.Uncertain, coordinator.send(prepared(record)))
            assertEquals(BitwardenAccountMutationPhase.UNCERTAIN, stored(store).phase)
        }
    }

    @Test fun onlyProvenPreMutationRejectionCanReleaseItsOwnJournal() = runBlocking {
        val memory = MemoryJournal()
        val store = memory.store()
        val coordinator = BitwardenAccountMutationCoordinator(store,
            execute = { BitwardenAccountMutationHttpResult(400, rejectedBeforeMutation = true) },
            requireCurrent = {}, freeze = {})
        val outcome = coordinator.send(prepared()) as BitwardenAccountMutationOutcome.Rejected
        assertEquals(BitwardenAccountMutationPhase.REJECTED, outcome.record.phase)
        assertTrue(store.discardUnsentOrRejected(outcome.record))
        assertSame(BitwardenAccountMutationStored.Absent, memory.store().restore(41))
        assertEquals(listOf(JOURNAL_KEY), memory.removed)
        memory.assertLocalDataPreserved()
    }

    @Test fun cancellationAtEitherGenerationGuardPreventsDispatchAndKeepsAnyStagedIntent() = runBlocking {
        for (cancelAt in listOf(1, 2)) {
            val memory = MemoryJournal()
            val store = memory.store()
            var checks = 0
            var sends = 0
            val coordinator = BitwardenAccountMutationCoordinator(store,
                execute = { sends++; BitwardenAccountMutationHttpResult(204) },
                requireCurrent = { if (++checks == cancelAt) throw CancellationException("synthetic generation revoked") },
                freeze = {})
            assertTrue(runCatching { coordinator.send(prepared()) }.exceptionOrNull() is CancellationException)
            assertEquals(0, sends)
            if (cancelAt == 1) assertSame(BitwardenAccountMutationStored.Absent, store.restore(41))
            else assertEquals(BitwardenAccountMutationPhase.UNCERTAIN, stored(memory.store()).phase)
            assertTrue(memory.removed.isEmpty())
        }
    }

    @Test fun cancellationAfterDispatchRecordsUncertaintyAfterCallerStops() = runBlocking {
        val memory = MemoryJournal()
        val store = memory.store()
        var sends = 0
        val coordinator = BitwardenAccountMutationCoordinator(store,
            execute = { sends++; awaitCancellation() }, requireCurrent = {}, freeze = {})
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.send(prepared()) }
        try { assertEquals(1, sends) } finally { caller.cancelAndJoin() }
        assertTrue(caller.isCancelled)
        assertEquals(BitwardenAccountMutationPhase.UNCERTAIN, stored(memory.store()).phase)
        assertSame(BitwardenAccountMutationOutcome.NotSent, coordinator.send(prepared()))
        assertEquals(1, sends)
        assertTrue(memory.removed.isEmpty())
        memory.assertLocalDataPreserved()
    }

    @Test fun failedReceiptCommitKeepsDurableSendingMarkerAndLocalData() = runBlocking {
        val memory = MemoryJournal()
        var writesAllowed = true
        val store = memory.store(write = { key, value ->
            if (writesAllowed) memory.entries[key] = value
            writesAllowed
        })
        val coordinator = BitwardenAccountMutationCoordinator(store,
            execute = { writesAllowed = false; BitwardenAccountMutationHttpResult(204) },
            requireCurrent = {}, freeze = {})
        assertSame(BitwardenAccountMutationOutcome.Uncertain, coordinator.send(prepared()))
        assertEquals(BitwardenAccountMutationPhase.SENDING, stored(memory.store()).phase)
        assertTrue(memory.removed.isEmpty())
        memory.assertLocalDataPreserved()
    }

    @Test fun confirmedServerDeletionRetainsArchiveAndNeverRemovesLocalVaultOrQueue() = runBlocking {
        val memory = MemoryJournal()
        val store = memory.store()
        val coordinator = BitwardenAccountMutationCoordinator(store,
            execute = {
                assertEquals("DELETE", it.method)
                assertEquals("accounts", it.relativePath)
                BitwardenAccountMutationHttpResult(204)
            }, requireCurrent = {}, freeze = {})
        val outcome = coordinator.send(prepared(record(BitwardenAccountMutationKind.DELETE))) as BitwardenAccountMutationOutcome.Confirmed
        assertFalse(store.acknowledgeReconciled(outcome.record))
        assertFalse(store.discardUnsentOrRejected(outcome.record))
        assertEquals(outcome.record, stored(memory.store()))
        assertTrue(memory.removed.isEmpty())
        memory.assertLocalDataPreserved()
    }

    private companion object {
        const val JOURNAL_KEY = "bitwarden_account_mutation_v1_41"
        const val OLD_WRAPPER = "2.synthetic-old-iv|synthetic-old-ciphertext|synthetic-old-mac"
        const val NEW_WRAPPER = "2.synthetic-new-iv|synthetic-new-ciphertext|synthetic-new-mac"
        const val PROOF = "synthetic-one-shot-authentication-proof"
    }
}
